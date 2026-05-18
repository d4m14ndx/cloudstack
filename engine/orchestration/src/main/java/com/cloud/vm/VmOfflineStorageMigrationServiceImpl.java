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

package com.cloud.vm;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.MigrateVmToPoolAnswer;
import com.cloud.agent.api.UnregisterVMCommand;
import com.cloud.agent.manager.Commands;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.org.Cluster;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.Volume.Type;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.StringUtils;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.VirtualMachine.Event;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VmOfflineStorageMigrationServiceImpl implements VmOfflineStorageMigrationService {

    private static final Logger logger = LogManager.getLogger(VmOfflineStorageMigrationServiceImpl.class);

    @Inject
    protected VMInstanceDao vmInstanceDao;
    @Inject
    protected VolumeDao volumeDao;
    @Inject
    protected PrimaryDataStoreDao storagePoolDao;
    @Inject
    protected ClusterDao clusterDao;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected DiskOfferingDao diskOfferingDao;
    @Inject
    protected ClusterDetailsDao clusterDetailsDao;
    @Inject
    protected AgentManager agentMgr;
    @Inject
    protected HypervisorGuruManager hvGuruMgr;
    @Inject
    protected NetworkOrchestrationService networkMgr;
    @Inject
    protected VolumeOrchestrationService volumeMgr;
    @Inject
    protected StorageManager storageMgr;
    @Inject
    protected VmVolumeMigrationPlanningService vmVolumeMigrationPlanningService;
    @Inject
    @Lazy
    protected VirtualMachineManager virtualMachineManager;

    @Override
    public void orchestrateStorageMigration(final String vmUuid, final Map<Long, Long> volumeToPool) {
        final VMInstanceVO vm = vmInstanceDao.findByUuid(vmUuid);

        try {
            Map<Volume, StoragePool> volumeToPoolMap = prepareVmStorageMigration(vm, volumeToPool);

            logger.debug("Offline migration of {} vm {} with volumes",
                            vm.getHypervisorType().toString(),
                            vm.getInstanceName());

            migrateThroughHypervisorOrStorage(vm, volumeToPoolMap);

        } catch (ConcurrentOperationException
                | InsufficientCapacityException
                | StorageUnavailableException e) {
            String msg = String.format("Failed to migrate VM: %s", vmUuid);
            logger.warn(msg, e);
            throw new CloudRuntimeException(msg, e);
        } finally {
            try {
                virtualMachineManager.stateTransitTo(vm, Event.AgentReportStopped, null);
            } catch (final NoTransitionException e) {
                String anotherMEssage = String.format("failed to change vm state of VM: %s", vmUuid);
                logger.warn(anotherMEssage, e);
                throw new CloudRuntimeException(anotherMEssage, e);
            }
        }
    }

    protected Answer[] attemptHypervisorMigration(VMInstanceVO vm, Map<Volume, StoragePool> volumeToPool, Long hostId) {
        if (hostId == null) {
            return null;
        }
        final HypervisorGuru hvGuru = hvGuruMgr.getGuru(vm.getHypervisorType());

        List<Command> commandsToSend = hvGuru.finalizeMigrate(vm, volumeToPool);

        if (CollectionUtils.isNotEmpty(commandsToSend)) {
            Commands commandsContainer = new Commands(Command.OnError.Stop);
            commandsContainer.addCommands(commandsToSend);

            try {
                return agentMgr.send(hostId, commandsContainer);
            } catch (AgentUnavailableException | OperationTimedoutException e) {
                logger.warn("Hypervisor migration failed for the VM: {}", vm, e);
            }
        }
        return null;
    }

    protected void afterHypervisorMigrationCleanup(VMInstanceVO vm, Map<Volume, StoragePool> volumeToPool, Long sourceClusterId, Answer[] hypervisorMigrationResults) throws InsufficientCapacityException {
        logger.debug("Cleaning up after hypervisor pool migration volumes for VM {}({})", vm.getInstanceName(), vm.getUuid());

        StoragePool rootVolumePool = null;
        if (MapUtils.isNotEmpty(volumeToPool)) {
            for (Map.Entry<Volume, StoragePool> entry : volumeToPool.entrySet()) {
                if (Type.ROOT.equals(entry.getKey().getVolumeType())) {
                    rootVolumePool = entry.getValue();
                    break;
                }
            }
        }
        setDestinationPoolAndReallocateNetwork(rootVolumePool, vm);
        Long destClusterId = rootVolumePool != null ? rootVolumePool.getClusterId() : null;
        if (destClusterId != null && !destClusterId.equals(sourceClusterId)) {
            logger.debug("Resetting lastHost for VM {}({})", vm.getInstanceName(), vm.getUuid());
            vm.setLastHostId(null);
            vm.setPodIdToDeployIn(rootVolumePool.getPodId());
        }

        markVolumesInPool(vm, hypervisorMigrationResults);
    }

    protected void markVolumesInPool(VMInstanceVO vm, Answer[] hypervisorMigrationResults) {
        MigrateVmToPoolAnswer relevantAnswer = null;
        if (hypervisorMigrationResults.length == 1 && !hypervisorMigrationResults[0].getResult()) {
            throw new CloudRuntimeException(String.format("VM ID: %s migration failed. %s", vm.getUuid(), hypervisorMigrationResults[0].getDetails()));
        }
        for (Answer answer : hypervisorMigrationResults) {
            logger.debug("Received an {}: {}", answer.getClass().getSimpleName(), answer);
            if (answer instanceof MigrateVmToPoolAnswer) {
                relevantAnswer = (MigrateVmToPoolAnswer) answer;
            }
        }
        if (relevantAnswer == null) {
            throw new CloudRuntimeException("No relevant migration results found");
        }
        List<VolumeObjectTO> results = relevantAnswer.getVolumeTos();
        if (results == null) {
            results = new ArrayList<>();
        }
        List<VolumeVO> volumes = volumeDao.findUsableVolumesForInstance(vm.getId());
        logger.debug("Found {} volumes for VM {}(uuid:{}, id:{})", results.size(), vm.getInstanceName(), vm.getUuid(), vm.getId());
        for (VolumeObjectTO result : results) {
            logger.debug("Updating volume ({}) with path '{}' on pool '{}'", result.getUuid(), result.getPath(), result.getDataStoreUuid());
            VolumeVO volume = volumeDao.findById(result.getId());
            StoragePool pool = storagePoolDao.findPoolByUUID(result.getDataStoreUuid());
            if (volume == null || pool == null) {
                continue;
            }
            volume.setPath(result.getPath());
            volume.setPoolId(pool.getId());
            volume.setPoolType(pool.getPoolType());
            if (result.getChainInfo() != null) {
                volume.setChainInfo(result.getChainInfo());
            }
            volumeDao.update(volume.getId(), volume);
        }
    }

    protected void migrateThroughHypervisorOrStorage(VMInstanceVO vm, Map<Volume, StoragePool> volumeToPool) throws StorageUnavailableException, InsufficientCapacityException {
        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        Pair<Long, Long> vmClusterAndHost = virtualMachineManager.findClusterAndHostIdForVm(vm, false);
        final Long sourceClusterId = vmClusterAndHost.first();
        final Long sourceHostId = vmClusterAndHost.second();
        Answer[] hypervisorMigrationResults = attemptHypervisorMigration(vm, volumeToPool, sourceHostId);
        boolean migrationResult = false;
        if (hypervisorMigrationResults == null) {
            migrationResult = volumeMgr.storageMigration(profile, volumeToPool);
            if (migrationResult) {
                postStorageMigrationCleanup(vm, volumeToPool, hostDao.findById(sourceHostId), sourceClusterId);
            } else {
                logger.debug("Storage migration failed");
            }
        } else {
            afterHypervisorMigrationCleanup(vm, volumeToPool, sourceClusterId, hypervisorMigrationResults);
        }
    }

    protected Map<Volume, StoragePool> prepareVmStorageMigration(VMInstanceVO vm, Map<Long, Long> volumeToPool) {
        Map<Volume, StoragePool> volumeToPoolMap = new HashMap<>();
        if (MapUtils.isEmpty(volumeToPool)) {
            throw new CloudRuntimeException(String.format("Unable to migrate %s: missing volume to pool mapping.", vm.toString()));
        }
        Cluster cluster = null;
        Long dataCenterId = null;
        for (Map.Entry<Long, Long> entry: volumeToPool.entrySet()) {
            StoragePool pool = storagePoolDao.findById(entry.getValue());
            if (pool.getClusterId() != null) {
                cluster = clusterDao.findById(pool.getClusterId());
                break;
            }
            dataCenterId = pool.getDataCenterId();
        }
        Long podId = null;
        Long clusterId = null;
        if (cluster != null) {
            dataCenterId = cluster.getDataCenterId();
            podId = cluster.getPodId();
            clusterId = cluster.getId();
        }
        if (dataCenterId == null) {
            String msg = "Unable to migrate Instance: failed to create deployment destination with given volume to pool map";
            logger.debug(msg);
            throw new CloudRuntimeException(msg);
        }
        final DataCenterDeployment destination = new DataCenterDeployment(dataCenterId, podId, clusterId, null, null, null);
        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        volumeToPoolMap = vmVolumeMigrationPlanningService.createMappingVolumeAndStoragePool(profile, destination, volumeToPool);
        try {
            virtualMachineManager.stateTransitTo(vm, Event.StorageMigrationRequested, null);
        } catch (final NoTransitionException e) {
            String msg = String.format("Unable to migrate Instance: %s", vm.getUuid());
            logger.warn(msg, e);
            throw new CloudRuntimeException(msg, e);
        }
        return volumeToPoolMap;
    }

    protected void checkDestinationForTags(StoragePool destPool, VMInstanceVO vm) {
        List<VolumeVO> vols = volumeDao.findUsableVolumesForInstance(vm.getId());

        List<String> storageTags = storageMgr.getStoragePoolTagList(destPool.getId());
        for (Volume vol : vols) {
            DiskOfferingVO diskOffering = diskOfferingDao.findById(vol.getDiskOfferingId());
            List<String> volumeTags = StringUtils.csvTagsToList(diskOffering.getTags());
            if (!matches(volumeTags, storageTags)) {
                String msg = String.format("destination pool '%s' with tags '%s', does not support the volume diskoffering for volume '%s' (tags: '%s') ",
                        destPool.getName(),
                        StringUtils.listToCsvTags(storageTags),
                        vol.getName(),
                        StringUtils.listToCsvTags(volumeTags)
                );
                throw new CloudRuntimeException(msg);
            }
        }
    }

    static boolean matches(List<String> volumeTags, List<String> storagePoolTags) {
        boolean result = true;
        if (volumeTags != null) {
            for (String tag : volumeTags) {
                if (storagePoolTags == null || !storagePoolTags.contains(tag)) {
                    result = false;
                    break;
                }
            }
        }
        return result;
    }

    protected void postStorageMigrationCleanup(VMInstanceVO vm, Map<Volume, StoragePool> volumeToPool, HostVO srcHost, Long srcClusterId) throws InsufficientCapacityException {
        StoragePool rootVolumePool = null;
        if (MapUtils.isNotEmpty(volumeToPool)) {
            for (Map.Entry<Volume, StoragePool> entry : volumeToPool.entrySet()) {
                if (Type.ROOT.equals(entry.getKey().getVolumeType())) {
                    rootVolumePool = entry.getValue();
                    break;
                }
            }
        }
        setDestinationPoolAndReallocateNetwork(rootVolumePool, vm);

        vm.setLastHostId(null);
        if (rootVolumePool != null) {
            vm.setPodIdToDeployIn(rootVolumePool.getPodId());
        }

        if (vm.getHypervisorType().equals(HypervisorType.VMware)) {
            afterStorageMigrationVmwareVMCleanup(rootVolumePool, vm, srcHost, srcClusterId);
        }
    }

    protected void setDestinationPoolAndReallocateNetwork(StoragePool destPool, VMInstanceVO vm) throws InsufficientCapacityException {
        if (destPool != null && destPool.getPodId() != null && !destPool.getPodId().equals(vm.getPodIdToDeployIn())) {
            logger.debug("as the pod for vm {} has changed we are reallocating its network", vm.getInstanceName());
            final DataCenterDeployment plan = new DataCenterDeployment(vm.getDataCenterId(), destPool.getPodId(), null, null, null, null);
            final VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(vm, null, null, null, null);
            networkMgr.reallocate(vmProfile, plan);
        }
    }

    protected void afterStorageMigrationVmwareVMCleanup(StoragePool destPool, VMInstanceVO vm, HostVO srcHost, Long srcClusterId) {
        final Long destClusterId = destPool.getClusterId();
        if (srcClusterId != null && destClusterId != null && !srcClusterId.equals(destClusterId) && srcHost != null) {
            final String srcDcName = clusterDetailsDao.getVmwareDcName(srcClusterId);
            final String destDcName = clusterDetailsDao.getVmwareDcName(destClusterId);
            if (srcDcName != null && destDcName != null && !srcDcName.equals(destDcName)) {
                removeStaleVmFromSource(vm, srcHost);
            }
        }
    }

    protected void removeStaleVmFromSource(VMInstanceVO vm, HostVO srcHost) {
        logger.debug("Since VM's storage was successfully migrated across VMware Datacenters, unregistering VM: {} from source host: {}",
                vm, srcHost);
        final UnregisterVMCommand uvc = new UnregisterVMCommand(vm.getInstanceName());
        uvc.setCleanupVmFiles(true);
        try {
            agentMgr.send(srcHost.getId(), uvc);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            throw new CloudRuntimeException(String.format(
                    "Failed to unregister VM: %s from source host: %s after successfully migrating VM's storage across VMware Datacenters",
                    vm, srcHost), e);
        }
    }
}
