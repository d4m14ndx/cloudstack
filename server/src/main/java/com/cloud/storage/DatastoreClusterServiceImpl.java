// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package com.cloud.storage;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.storage.SyncStoragePoolCmd;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.storage.command.SyncVolumePathAnswer;
import org.apache.cloudstack.storage.command.SyncVolumePathCommand;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.ModifyStoragePoolAnswer;
import com.cloud.agent.api.ModifyStoragePoolCommand;
import com.cloud.agent.api.StoragePoolInfo;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.dao.StoragePoolAndAccessGroupMapDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Default implementation of {@link DatastoreClusterService} — extracted
 * from {@link StorageManagerImpl}.
 *
 * @see DatastoreClusterService
 */
@Component
public class DatastoreClusterServiceImpl implements DatastoreClusterService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected PrimaryDataStoreDao storagePoolDao;
    @Inject
    protected StoragePoolTagsDao storagePoolTagsDao;
    @Inject
    protected StoragePoolHostDao storagePoolHostDao;
    @Inject
    protected StoragePoolAndAccessGroupMapDao storagePoolAccessGroupMapDao;
    @Inject
    protected VolumeDao volumeDao;
    @Inject
    protected VMInstanceDao vmInstanceDao;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected AgentManager agentMgr;
    @Inject
    protected DataStoreManager dataStoreMgr;
    @Inject
    protected VolumeDataFactory volFactory;

    @Override
    public StoragePool syncStoragePool(SyncStoragePoolCmd cmd) {
        Long poolId = cmd.getPoolId();
        StoragePoolVO pool = storagePoolDao.findById(poolId);

        if (pool == null) {
            String msg = String.format("Unable to find the storage pool with id %d record while syncing storage pool with management server", poolId);
            logger.error(msg);
            throw new InvalidParameterValueException(msg);
        }

        if (!pool.getPoolType().equals(Storage.StoragePoolType.DatastoreCluster)) {
            throw new InvalidParameterValueException("SyncStoragePool API is currently supported only for storage type of datastore cluster");
        }

        if (!pool.getStatus().equals(StoragePoolStatus.Up)) {
            throw new InvalidParameterValueException(String.format("Primary storage %s is not ready for syncing, as the status is %s", pool, pool.getStatus().toString()));
        }

        // find the host
        List<Long> poolIds = new ArrayList<>();
        poolIds.add(poolId);
        List<Long> hosts = storagePoolHostDao.findHostsConnectedToPools(poolIds);
        if (hosts.size() > 0) {
            Long hostId = hosts.get(0);
            ModifyStoragePoolCommand modifyStoragePoolCommand = new ModifyStoragePoolCommand(true, pool);
            final Answer answer = agentMgr.easySend(hostId, modifyStoragePoolCommand);

            if (answer == null) {
                throw new CloudRuntimeException(String.format("Unable to get an answer to the modify storage pool command %s", pool));
            }

            if (!answer.getResult()) {
                throw new CloudRuntimeException(String.format("Unable to process ModifyStoragePoolCommand for pool %s on the host %s due to %s", pool, hostDao.findById(hostId), answer.getDetails()));
            }

            assert (answer instanceof ModifyStoragePoolAnswer) : "Well, now why won't you actually return the ModifyStoragePoolAnswer when it's ModifyStoragePoolCommand? Pool=" +
                    pool.getId() + "Host=" + hostId;
            ModifyStoragePoolAnswer mspAnswer = (ModifyStoragePoolAnswer) answer;
            StoragePoolVO poolVO = storagePoolDao.findById(poolId);
            updateStoragePoolHostVOAndBytes(poolVO, hostId, mspAnswer);
            validateChildDatastoresToBeAddedInUpState(poolVO, mspAnswer.getDatastoreClusterChildren());
            syncDatastoreClusterStoragePool(poolId, mspAnswer.getDatastoreClusterChildren(), hostId);
            for (ModifyStoragePoolAnswer childDataStoreAnswer : mspAnswer.getDatastoreClusterChildren()) {
                StoragePoolInfo childStoragePoolInfo = childDataStoreAnswer.getPoolInfo();
                StoragePoolVO dataStoreVO = storagePoolDao.findPoolByUUID(childStoragePoolInfo.getUuid());
                for (Long host : hosts) {
                    updateStoragePoolHostVOAndBytes(dataStoreVO, host, childDataStoreAnswer);
                }
            }

        } else {
            throw new CloudRuntimeException(String.format("Unable to sync storage pool [%s] as there no connected hosts to the storage pool", pool));
        }
        return (PrimaryDataStoreInfo) dataStoreMgr.getDataStore(pool.getId(), DataStoreRole.Primary);
    }

    @Override
    public void syncDatastoreClusterStoragePool(long datastoreClusterPoolId, List<ModifyStoragePoolAnswer> childDatastoreAnswerList, long hostId) {
        StoragePoolVO datastoreClusterPool = storagePoolDao.findById(datastoreClusterPoolId);
        List<StoragePoolTagVO> storageTags = storagePoolTagsDao.findStoragePoolTags(datastoreClusterPoolId);
        List<StoragePoolVO> childDatastores = storagePoolDao.listChildStoragePoolsInDatastoreCluster(datastoreClusterPoolId);
        Set<String> childDatastoreUUIDs = new HashSet<>();
        for (StoragePoolVO childDatastore : childDatastores) {
            childDatastoreUUIDs.add(childDatastore.getUuid());
        }

        for (ModifyStoragePoolAnswer childDataStoreAnswer : childDatastoreAnswerList) {
            StoragePoolInfo childStoragePoolInfo = childDataStoreAnswer.getPoolInfo();
            StoragePoolVO dataStoreVO = getExistingPoolByUuid(childStoragePoolInfo.getUuid());
            if (dataStoreVO == null && childDataStoreAnswer.getPoolType().equalsIgnoreCase("NFS")) {
                List<StoragePoolVO> nfsStoragePools = storagePoolDao.findPoolsByStorageType(Storage.StoragePoolType.NetworkFilesystem);
                for (StoragePoolVO storagePool : nfsStoragePools) {
                    String storagePoolUUID = storagePool.getUuid();
                    if (childStoragePoolInfo.getName().equalsIgnoreCase(storagePoolUUID.replaceAll("-", ""))) {
                        dataStoreVO = storagePool;
                        break;
                    }
                }
            }
            if (dataStoreVO != null) {
                if (dataStoreVO.getParent() != datastoreClusterPoolId) {
                    logger.debug(String.format("Storage pool %s with uuid %s is found to be under datastore cluster %s at vCenter, " +
                                    "so moving the storage pool to be a child storage pool under the datastore cluster in CloudStack management server",
                            childStoragePoolInfo.getName(), childStoragePoolInfo.getUuid(), datastoreClusterPool.getName()));
                    dataStoreVO.setParent(datastoreClusterPoolId);
                    storagePoolDao.update(dataStoreVO.getId(), dataStoreVO);
                    if (CollectionUtils.isNotEmpty(storageTags)) {
                        storageTags.addAll(storagePoolTagsDao.findStoragePoolTags(dataStoreVO.getId()));
                    } else {
                        storageTags = storagePoolTagsDao.findStoragePoolTags(dataStoreVO.getId());
                    }
                    if (CollectionUtils.isNotEmpty(storageTags)) {
                        Set<StoragePoolTagVO> set = new LinkedHashSet<>(storageTags);
                        storageTags.clear();
                        storageTags.addAll(set);
                        if (logger.isDebugEnabled()) {
                            logger.debug("Updating Storage Pool Tags to :" + storageTags);
                        }
                        storagePoolTagsDao.persist(storageTags);
                    }
                } else {
                    // This is to find datastores which are removed from datastore cluster.
                    // The final set childDatastoreUUIDs contains the UUIDs of child datastores which needs to be removed from datastore cluster
                    childDatastoreUUIDs.remove(dataStoreVO.getUuid());
                }
            } else {
                dataStoreVO = createChildDatastoreVO(datastoreClusterPool, childDataStoreAnswer, storageTags);
            }
            updateStoragePoolHostVOAndBytes(dataStoreVO, hostId, childDataStoreAnswer);
        }

        handleRemoveChildStoragePoolFromDatastoreCluster(childDatastoreUUIDs);
    }

    /**
     * Look up an existing storage pool by uuid, accepting both the
     * canonical hyphenated form and the 32-char hex form that vCenter
     * returns. The hex form is converted to a UUID by splitting it into
     * two 64-bit longs.
     */
    protected StoragePoolVO getExistingPoolByUuid(String uuid) {
        if (!uuid.contains("-")) {
            UUID poolUuid = new UUID(
                    new BigInteger(uuid.substring(0, 16), 16).longValue(),
                    new BigInteger(uuid.substring(16), 16).longValue()
            );
            uuid = poolUuid.toString();
        }
        return storagePoolDao.findByUuid(uuid);
    }

    @Override
    public void validateChildDatastoresToBeAddedInUpState(StoragePoolVO datastoreClusterPool, List<ModifyStoragePoolAnswer> childDatastoreAnswerList) {
        for (ModifyStoragePoolAnswer childDataStoreAnswer : childDatastoreAnswerList) {
            StoragePoolInfo childStoragePoolInfo = childDataStoreAnswer.getPoolInfo();
            StoragePoolVO dataStoreVO = storagePoolDao.findPoolByUUID(childStoragePoolInfo.getUuid());
            if (dataStoreVO == null && childDataStoreAnswer.getPoolType().equalsIgnoreCase("NFS")) {
                List<StoragePoolVO> nfsStoragePools = storagePoolDao.findPoolsByStorageType(Storage.StoragePoolType.NetworkFilesystem);
                for (StoragePoolVO storagePool : nfsStoragePools) {
                    String storagePoolUUID = storagePool.getUuid();
                    if (childStoragePoolInfo.getName().equalsIgnoreCase(storagePoolUUID.replaceAll("-", ""))) {
                        dataStoreVO = storagePool;
                        break;
                    }
                }
            }
            if (dataStoreVO != null && !dataStoreVO.getStatus().equals(StoragePoolStatus.Up)) {
                String msg = String.format("Cannot synchronise datastore cluster %s because primary storage %s is not in Up state, " +
                        "current state is %s", datastoreClusterPool, dataStoreVO, dataStoreVO.getStatus().toString());
                throw new CloudRuntimeException(msg);
            }
        }
    }

    /**
     * Build a new child {@link StoragePoolVO} for {@code childDataStoreAnswer}
     * and persist it under {@code datastoreClusterPool}. The child
     * inherits zone/pod/cluster/scope/hypervisor/userinfo from the
     * parent, gets its name/uuid/host/path from the answer, and inherits
     * the parent's tags and storage-access groups.
     */
    protected StoragePoolVO createChildDatastoreVO(StoragePoolVO datastoreClusterPool, ModifyStoragePoolAnswer childDataStoreAnswer, List<StoragePoolTagVO> storagePoolTagVOList) {
        StoragePoolInfo childStoragePoolInfo = childDataStoreAnswer.getPoolInfo();

        StoragePoolVO dataStoreVO = new StoragePoolVO();
        dataStoreVO.setStorageProviderName(datastoreClusterPool.getStorageProviderName());
        dataStoreVO.setHostAddress(childStoragePoolInfo.getHost());
        dataStoreVO.setPoolType(Storage.StoragePoolType.PreSetup);
        dataStoreVO.setPath(childStoragePoolInfo.getHostPath());
        dataStoreVO.setPort(datastoreClusterPool.getPort());
        dataStoreVO.setName(childStoragePoolInfo.getName());
        dataStoreVO.setUuid(childStoragePoolInfo.getUuid());
        dataStoreVO.setDataCenterId(datastoreClusterPool.getDataCenterId());
        dataStoreVO.setPodId(datastoreClusterPool.getPodId());
        dataStoreVO.setClusterId(datastoreClusterPool.getClusterId());
        dataStoreVO.setStatus(StoragePoolStatus.Up);
        dataStoreVO.setUserInfo(datastoreClusterPool.getUserInfo());
        dataStoreVO.setManaged(datastoreClusterPool.isManaged());
        dataStoreVO.setCapacityIops(datastoreClusterPool.getCapacityIops());
        dataStoreVO.setCapacityBytes(childDataStoreAnswer.getPoolInfo().getCapacityBytes());
        dataStoreVO.setUsedBytes(childDataStoreAnswer.getPoolInfo().getCapacityBytes() - childDataStoreAnswer.getPoolInfo().getAvailableBytes());
        dataStoreVO.setHypervisor(datastoreClusterPool.getHypervisor());
        dataStoreVO.setScope(datastoreClusterPool.getScope());
        dataStoreVO.setParent(datastoreClusterPool.getId());

        Map<String, String> details = new HashMap<>();
        if (StringUtils.isNotEmpty(childDataStoreAnswer.getPoolType())) {
            details.put("pool_type", childDataStoreAnswer.getPoolType());
        }

        List<String> storagePoolTags = new ArrayList<>();
        boolean isTagARule = false;
        if (CollectionUtils.isNotEmpty(storagePoolTagVOList)) {
            storagePoolTags = storagePoolTagVOList.parallelStream().map(StoragePoolTagVO::getTag).collect(Collectors.toList());
            isTagARule = storagePoolTagVOList.get(0).isTagARule();
        }
        List<String> storageAccessGroups = storagePoolAccessGroupMapDao.getStorageAccessGroups(datastoreClusterPool.getId());

        storagePoolDao.persist(dataStoreVO, details, storagePoolTags, isTagARule, storageAccessGroups);
        return dataStoreVO;
    }

    /**
     * For each child uuid that vCenter no longer reports under the
     * parent, clear its {@code parent} back to 0 and run a
     * {@link SyncVolumePathCommand} for every Ready volume on it so the
     * volume's recorded datastore/path stays consistent.
     */
    protected void handleRemoveChildStoragePoolFromDatastoreCluster(Set<String> childDatastoreUUIDs) {

        for (String childDatastoreUUID : childDatastoreUUIDs) {
            StoragePoolVO dataStoreVO = storagePoolDao.findPoolByUUID(childDatastoreUUID);
            List<VolumeVO> allVolumes = volumeDao.findNonDestroyedVolumesByPoolId(dataStoreVO.getId());
            allVolumes.removeIf(volumeVO -> volumeVO.getInstanceId() == null);
            allVolumes.removeIf(volumeVO -> volumeVO.getState() != Volume.State.Ready);
            for (VolumeVO volume : allVolumes) {
                VMInstanceVO vmInstance = vmInstanceDao.findById(volume.getInstanceId());
                if (vmInstance == null) {
                    continue;
                }
                long volumeId = volume.getId();
                Long hostId = vmInstance.getHostId();
                if (hostId == null) {
                    hostId = vmInstance.getLastHostId();
                }
                HostVO hostVO = hostDao.findById(hostId);

                // Prepare for the syncvolumepath command
                DataTO volTO = volFactory.getVolume(volume.getId()).getTO();
                DiskTO disk = new DiskTO(volTO, volume.getDeviceId(), volume.getPath(), volume.getVolumeType());
                Map<String, String> details = new HashMap<>();
                details.put(DiskTO.PROTOCOL_TYPE, Storage.StoragePoolType.DatastoreCluster.toString());
                disk.setDetails(details);

                logger.debug("Attempting to process SyncVolumePathCommand for the volume {} on the host {} with state {}", volume, hostVO, hostVO.getResourceState());
                SyncVolumePathCommand cmd = new SyncVolumePathCommand(disk);
                final Answer answer = agentMgr.easySend(hostId, cmd);
                // validate answer
                if (answer == null) {
                    throw new CloudRuntimeException(String.format("Unable to get an answer to the SyncVolumePath command for volume %s", volume));
                }
                if (!answer.getResult()) {
                    throw new CloudRuntimeException(String.format("Unable to process SyncVolumePathCommand for the volume %s to the host %s due to %s", volume, hostVO, answer.getDetails()));
                }
                assert (answer instanceof SyncVolumePathAnswer) : String.format("Well, now why won't you actually return the SyncVolumePathAnswer when it's SyncVolumePathCommand? volume=%s Host=%s", volume, hostVO);

                // check for the changed details of volume and update database
                VolumeVO volumeVO = volumeDao.findById(volumeId);
                String datastoreName = answer.getContextParam("datastoreName");
                if (datastoreName != null) {
                    StoragePoolVO storagePoolVO = storagePoolDao.findByUuid(datastoreName);
                    if (storagePoolVO != null) {
                        volumeVO.setPoolId(storagePoolVO.getId());
                        volumeVO.setPoolType(storagePoolVO.getPoolType());
                    } else {
                        logger.warn("Unable to find datastore {} while updating the new datastore of the volume {}", datastoreName, volumeVO);
                    }
                }

                String volumePath = answer.getContextParam("volumePath");
                if (volumePath != null) {
                    volumeVO.setPath(volumePath);
                }

                String chainInfo = answer.getContextParam("chainInfo");
                if (chainInfo != null) {
                    volumeVO.setChainInfo(chainInfo);
                }

                volumeDao.update(volumeVO.getId(), volumeVO);
            }
            dataStoreVO.setParent(0L);
            storagePoolDao.update(dataStoreVO.getId(), dataStoreVO);
        }

    }

    @Override
    public void updateStoragePoolHostVOAndBytes(StoragePool pool, long hostId, ModifyStoragePoolAnswer mspAnswer) {
        StoragePoolHostVO poolHost = storagePoolHostDao.findByPoolHost(pool.getId(), hostId);
        if (poolHost == null) {
            poolHost = new StoragePoolHostVO(pool.getId(), hostId, mspAnswer.getPoolInfo().getLocalPath().replaceAll("//", "/"));
            storagePoolHostDao.persist(poolHost);
        } else {
            poolHost.setLocalPath(mspAnswer.getPoolInfo().getLocalPath().replaceAll("//", "/"));
        }

        StoragePoolVO poolVO = storagePoolDao.findById(pool.getId());
        if (!Storage.StoragePoolType.StorPool.equals(poolVO.getPoolType())) {
            poolVO.setUsedBytes(mspAnswer.getPoolInfo().getCapacityBytes() - mspAnswer.getPoolInfo().getAvailableBytes());
            poolVO.setCapacityBytes(mspAnswer.getPoolInfo().getCapacityBytes());
        }

        storagePoolDao.update(pool.getId(), poolVO);
    }
}
