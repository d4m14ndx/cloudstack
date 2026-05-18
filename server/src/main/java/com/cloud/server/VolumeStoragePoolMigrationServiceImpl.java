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
package com.cloud.server;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.StoragePoolAllocator;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.StoragePoolJoinDao;
import com.cloud.api.query.vo.StoragePoolJoinVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorCapabilitiesVO;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.org.Cluster;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfileImpl;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * @see VolumeStoragePoolMigrationService
 */
@Component
public class VolumeStoragePoolMigrationServiceImpl implements VolumeStoragePoolMigrationService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected AccountManager _accountMgr;
    @Inject
    protected VolumeDao _volumeDao;
    @Inject
    protected VMInstanceDao _vmInstanceDao;
    @Inject
    protected HostDao _hostDao;
    @Inject
    protected HypervisorCapabilitiesDao _hypervisorCapabilitiesDao;
    @Inject
    protected PrimaryDataStoreDao _poolDao;
    @Inject
    protected ClusterDao _clusterDao;
    @Inject
    protected DiskOfferingDao _diskOfferingDao;
    @Inject
    protected StoragePoolJoinDao _poolJoinDao;
    @Inject
    protected List<StoragePoolAllocator> _storagePoolAllocators;

    @Override
    @SuppressWarnings("unchecked")
    public Pair<List<? extends StoragePool>, List<? extends StoragePool>> listStoragePoolsForMigrationOfVolume(final Long volumeId, String keyword) {
        Pair<List<? extends StoragePool>, List<? extends StoragePool>> allPoolsAndSuitablePoolsPair =
                listStoragePoolsForMigrationOfVolumeInternal(volumeId, null, null, null, null, false, true, false, keyword);
        List<? extends StoragePool> allPools = allPoolsAndSuitablePoolsPair.first();
        List<? extends StoragePool> suitablePools = allPoolsAndSuitablePoolsPair.second();
        List<StoragePool> avoidPools = new ArrayList<>();

        final VolumeVO volume = _volumeDao.findById(volumeId);
        StoragePool srcVolumePool = _poolDao.findById(volume.getPoolId());
        if (srcVolumePool.getParent() != 0L) {
            StoragePool datastoreCluster = _poolDao.findById(srcVolumePool.getParent());
            avoidPools.add(datastoreCluster);
        }
        abstractDataStoreClustersList((List<StoragePool>) allPools, new ArrayList<>());
        abstractDataStoreClustersList((List<StoragePool>) suitablePools, avoidPools);
        return new Pair<>(allPools, suitablePools);
    }

    @Override
    public Pair<List<? extends StoragePool>, List<? extends StoragePool>> listStoragePoolsForSystemMigrationOfVolume(final Long volumeId,
            Long newDiskOfferingId, Long newSize, Long newMinIops, Long newMaxIops, boolean keepSourceStoragePool,
            boolean bypassStorageTypeCheck) {
        return listStoragePoolsForMigrationOfVolumeInternal(volumeId, newDiskOfferingId, newSize, newMinIops, newMaxIops,
                keepSourceStoragePool, bypassStorageTypeCheck, true, null);
    }

    @Override
    @SuppressWarnings("unchecked")
    public Pair<List<? extends StoragePool>, List<? extends StoragePool>> listStoragePoolsForMigrationOfVolumeInternal(final Long volumeId,
            Long newDiskOfferingId, Long newSize, Long newMinIops, Long newMaxIops, boolean keepSourceStoragePool,
            boolean bypassStorageTypeCheck, boolean bypassAccountCheck, String keyword) {
        if (!bypassAccountCheck) {
            final Account caller = CallContext.current().getCallingAccount();
            if (!_accountMgr.isRootAdmin(caller.getId())) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Caller is not a root admin, permission denied to migrate the volume");
                }
                throw new PermissionDeniedException("No permission to migrate volume, only root admin can migrate a volume");
            }
        }

        final VolumeVO volume = _volumeDao.findById(volumeId);
        if (volume == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find volume with" + " specified id.");
            ex.addProxyObject(volumeId.toString(), "volumeId");
            throw ex;
        }

        Long diskOfferingId = volume.getDiskOfferingId();
        if (newDiskOfferingId != null) {
            diskOfferingId = newDiskOfferingId;
        }

        // Volume must be attached to an instance for live migration.
        List<? extends StoragePool> allPools = new ArrayList<>();
        List<StoragePool> suitablePools = new ArrayList<>();

        // Volume must be in Ready state to be migrated.
        if (!Volume.State.Ready.equals(volume.getState())) {
            logger.info("Volume " + volume + " must be in ready state for migration.");
            return new Pair<>(allPools, suitablePools);
        }

        final Long instanceId = volume.getInstanceId();
        VMInstanceVO vm = null;
        if (instanceId != null) {
            vm = _vmInstanceDao.findById(instanceId);
        }

        if (vm == null) {
            logger.info("Volume " + volume + " isn't attached to any Instance. Looking for storage pools in the " + "zone to which this volumes can be migrated.");
        } else if (vm.getState() != State.Running) {
            logger.info("Volume " + volume + " isn't attached to any running Instance. Looking for storage pools in the " + "cluster to which this volumes can be migrated.");
        } else {
            logger.info("Volume " + volume + " is attached to any running Instance. Looking for storage pools in the " + "cluster to which this volumes can be migrated.");
            boolean storageMotionSupported = false;
            // Check if the underlying hypervisor supports storage motion.
            final Long hostId = vm.getHostId();
            if (hostId != null) {
                final HostVO host = _hostDao.findById(hostId);
                HypervisorCapabilitiesVO capabilities = null;
                if (host != null) {
                    capabilities = _hypervisorCapabilitiesDao.findByHypervisorTypeAndVersion(host.getHypervisorType(), host.getHypervisorVersion());
                } else {
                    logger.error("Details of the host on which the Instance " + vm + ", to which volume " + volume + " is " + "attached, couldn't be retrieved.");
                }

                if (capabilities != null) {
                    storageMotionSupported = capabilities.isStorageMotionSupported();
                } else {
                    logger.error("Capabilities for host " + host + " couldn't be retrieved.");
                }
            }

            if (!storageMotionSupported) {
                logger.info("Volume " + volume + " is attached to a running Instance and the hypervisor doesn't support" + " storage motion.");
                return new Pair<>(allPools, suitablePools);
            }
        }

        StoragePool srcVolumePool = _poolDao.findById(volume.getPoolId());
        HypervisorType hypervisorType = getHypervisorType(vm, srcVolumePool);
        Pair<Host, List<Cluster>> hostClusterPair = getVolumeVmHostClusters(srcVolumePool, vm, hypervisorType);
        Host vmHost = hostClusterPair.first();
        List<Cluster> clusters = hostClusterPair.second();
        allPools = getAllStoragePoolCompatibleWithVolumeSourceStoragePool(srcVolumePool, hypervisorType, clusters, keyword);
        ExcludeList avoid = new ExcludeList();
        if (!keepSourceStoragePool) {
            allPools.remove(srcVolumePool);
            avoid.addPool(srcVolumePool.getId());
        }
        if (vm != null) {
            suitablePools = findAllSuitableStoragePoolsForVm(volume, diskOfferingId, newSize, newMinIops, newMaxIops, vm, vmHost, avoid,
                    CollectionUtils.isNotEmpty(clusters) ? clusters.get(0) : null, hypervisorType, bypassStorageTypeCheck, keyword);
        } else {
            suitablePools = findAllSuitableStoragePoolsForDetachedVolume(volume, diskOfferingId, allPools);
        }
        removeDataStoreClusterParents((List<StoragePool>) allPools);
        removeDataStoreClusterParents(suitablePools);
        return new Pair<>(allPools, suitablePools);
    }

    protected HypervisorType getHypervisorType(VMInstanceVO vm, StoragePool srcVolumePool) {
        HypervisorType type = null;
        if (vm == null) {
            StoragePoolVO poolVo = _poolDao.findById(srcVolumePool.getId());
            if (ScopeType.CLUSTER.equals(poolVo.getScope())) {
                Long clusterId = poolVo.getClusterId();
                if (clusterId != null) {
                    ClusterVO cluster = _clusterDao.findById(clusterId);
                    type = cluster.getHypervisorType();
                }
            }

            if (null == type) {
                type = srcVolumePool.getHypervisor();
            }
        } else {
            type = vm.getHypervisorType();
        }
        return type;
    }

    protected void removeDataStoreClusterParents(List<StoragePool> storagePools) {
        Predicate<StoragePool> childDatastorePredicate = pool -> (pool.getParent() != 0);
        List<StoragePool> childDatastores = storagePools.stream().filter(childDatastorePredicate).collect(Collectors.toList());
        if (!childDatastores.isEmpty()) {
            Set<Long> parentStoragePoolIds = childDatastores.stream().map(mo -> mo.getParent()).collect(Collectors.toSet());
            for (Long parentStoragePoolId : parentStoragePoolIds) {
                StoragePool parentPool = _poolDao.findById(parentStoragePoolId);
                storagePools.remove(parentPool);
            }
        }
    }

    protected void abstractDataStoreClustersList(List<StoragePool> storagePools, List<StoragePool> avoidPools) {
        Predicate<StoragePool> childDatastorePredicate = pool -> (pool.getParent() != 0);
        List<StoragePool> childDatastores = storagePools.stream().filter(childDatastorePredicate).collect(Collectors.toList());
        storagePools.removeAll(avoidPools);
        if (!childDatastores.isEmpty()) {
            storagePools.removeAll(childDatastores);
            Set<Long> parentStoragePoolIds = childDatastores.stream().map(mo -> mo.getParent()).collect(Collectors.toSet());
            for (Long parentStoragePoolId : parentStoragePoolIds) {
                StoragePool parentPool = _poolDao.findById(parentStoragePoolId);
                if (!storagePools.contains(parentPool) && !avoidPools.contains(parentPool))
                    storagePools.add(parentPool);
            }
        }
    }

    protected Pair<Host, List<Cluster>> getVolumeVmHostClusters(StoragePool srcVolumePool, VirtualMachine vm, HypervisorType hypervisorType) {
        Host host = null;
        List<Cluster> clusters = new ArrayList<>();
        Long clusterId = srcVolumePool.getClusterId();
        if (vm != null) {
            Long hostId = vm.getHostId();
            if (hostId == null) {
                hostId = vm.getLastHostId();
            }
            if (hostId != null) {
                host = _hostDao.findById(hostId);
            }
        }
        if (clusterId == null && host != null) {
            clusterId = host.getClusterId();
        }
        if (clusterId != null && vm != null) {
            clusters.add(_clusterDao.findById(clusterId));
        } else {
            clusters.addAll(_clusterDao.listByDcHyType(srcVolumePool.getDataCenterId(), hypervisorType.toString()));
        }
        return new Pair<>(host, clusters);
    }

    /**
     * This method looks for all storage pools that are compatible with the given volume.
     * <ul>
     *  <li>We will look for storage systems that are zone wide.</li>
     *  <li>We also all storage available filtering by data center, pod and cluster as the current storage pool used by the given volume.</li>
     * </ul>
     */
    protected List<? extends StoragePool> getAllStoragePoolCompatibleWithVolumeSourceStoragePool(StoragePool srcVolumePool,
            HypervisorType hypervisorType, List<Cluster> clusters, String keyword) {
        List<StoragePoolVO> storagePools = new ArrayList<>();
        List<StoragePoolVO> zoneWideStoragePools = _poolDao.findZoneWideStoragePoolsByHypervisor(srcVolumePool.getDataCenterId(), hypervisorType, keyword);
        if (CollectionUtils.isNotEmpty(zoneWideStoragePools)) {
            storagePools.addAll(zoneWideStoragePools);
        }
        if (CollectionUtils.isNotEmpty(clusters)) {
            List<Long> clusterIds = clusters.stream().map(Cluster::getId).collect(Collectors.toList());
            List<StoragePoolVO> clusterAndLocalStoragePools = _poolDao.findPoolsInClusters(clusterIds, keyword);
            if (CollectionUtils.isNotEmpty(clusterAndLocalStoragePools)) {
                storagePools.addAll(clusterAndLocalStoragePools);
            }
        }

        return storagePools;
    }

    /**
     *  Looks for all suitable storage pools to allocate the given volume.
     *  We take into account the service offering of the VM and volume to find suitable storage pools. It is also excluded from the search the current storage pool used by the volume.
     *  We use {@link StoragePoolAllocator} to look for possible storage pools to allocate the given volume. We will look for possible local storage poosl even if the volume is using a shared storage disk offering.
     * <p>
     *  Side note: the idea behind this method is to provide power for administrators of manually overriding deployments defined by CloudStack.
     */
    protected List<StoragePool> findAllSuitableStoragePoolsForVm(final VolumeVO volume, Long diskOfferingId, Long newSize,
            Long newMinIops, Long newMaxIops, VMInstanceVO vm, Host vmHost, ExcludeList avoid, Cluster srcCluster,
            HypervisorType hypervisorType, boolean bypassStorageTypeCheck, String keyword) {
        List<StoragePool> suitablePools = new ArrayList<>();
        Long clusterId = null;
        Long podId = null;
        if (srcCluster != null) {
            clusterId = srcCluster.getId();
            podId = srcCluster.getPodId();
        }
        DataCenterDeployment plan = new DataCenterDeployment(volume.getDataCenterId(), podId, clusterId,
                null, null, null, null);
        VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        // OfflineVmwareMigration: vm might be null here; deal!

        DiskOfferingVO diskOffering = _diskOfferingDao.findById(diskOfferingId);
        DiskProfile diskProfile = new DiskProfile(volume, diskOffering, hypervisorType);
        if (!Objects.equals(volume.getDiskOfferingId(), diskOfferingId)) {
            diskProfile.setSize(newSize);
            diskProfile.setMinIops(newMinIops);
            diskProfile.setMaxIops(newMaxIops);
        }

        for (StoragePoolAllocator allocator : _storagePoolAllocators) {
            List<StoragePool> pools = allocator.allocateToPool(diskProfile, profile, plan, avoid, StoragePoolAllocator.RETURN_UPTO_ALL, bypassStorageTypeCheck, keyword);
            if (CollectionUtils.isEmpty(pools)) {
                continue;
            }
            for (StoragePool pool : pools) {
                boolean isLocalPoolSameHostAsVmHost = pool.isLocal() &&
                        (vmHost == null || StringUtils.equals(vmHost.getPrivateIpAddress(), pool.getHostAddress()));
                if (isLocalPoolSameHostAsVmHost || pool.isShared()) {
                    suitablePools.add(pool);
                }
            }
        }
        return suitablePools;
    }

    protected List<StoragePool> findAllSuitableStoragePoolsForDetachedVolume(Volume volume, Long diskOfferingId, List<? extends StoragePool> allPools) {
        List<StoragePool> suitablePools = new ArrayList<>();
        if (CollectionUtils.isEmpty(allPools)) {
            return  suitablePools;
        }
        DiskOfferingVO diskOffering = _diskOfferingDao.findById(diskOfferingId);
        List<String> tags = new ArrayList<>();
        String[] tagsArray = diskOffering.getTagsArray();
        if (tagsArray != null && tagsArray.length > 0) {
            tags = Arrays.asList(tagsArray);
        }
        Long[] poolIds = allPools.stream().map(StoragePool::getId).toArray(Long[]::new);
        List<StoragePoolJoinVO> pools = _poolJoinDao.searchByIds(poolIds);
        for (StoragePoolJoinVO storagePool : pools) {
            if (StoragePoolStatus.Up.equals(storagePool.getStatus()) &&
                    (CollectionUtils.isEmpty(tags) || tags.contains(storagePool.getTag()))) {
                Optional<? extends StoragePool> match = allPools.stream().filter(x -> x.getId() == storagePool.getId()).findFirst();
                match.ifPresent(suitablePools::add);
            }
        }
        return suitablePools;
    }
}
