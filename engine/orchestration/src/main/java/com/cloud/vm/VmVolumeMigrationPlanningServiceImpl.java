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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.StoragePoolAllocator;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.springframework.stereotype.Component;

import com.cloud.dc.dao.ClusterDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.host.Host;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.org.Cluster;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Default implementation of {@link VmVolumeMigrationPlanningService}.
 *
 * <p>Extracted from {@link VirtualMachineManagerImpl} as part of Phase 4 decomposition (slice 5).</p>
 */
@Component
public class VmVolumeMigrationPlanningServiceImpl implements VmVolumeMigrationPlanningService {

    @Inject
    protected VolumeDao volumeDao;
    @Inject
    protected PrimaryDataStoreDao storagePoolDao;
    @Inject
    protected StoragePoolHostDao poolHostDao;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected ClusterDao clusterDao;
    @Inject
    protected DiskOfferingDao diskOfferingDao;

    private List<StoragePoolAllocator> storagePoolAllocators;

    @Inject
    public void setStoragePoolAllocators(final List<StoragePoolAllocator> storagePoolAllocators) {
        this.storagePoolAllocators = storagePoolAllocators;
    }

    public List<StoragePoolAllocator> getStoragePoolAllocators() {
        return storagePoolAllocators;
    }

    // -------------------------------------------------------------------------
    // Public interface method
    // -------------------------------------------------------------------------

    /**
     * We create the mapping of volumes and storage pool to migrate the VMs according to the information sent by the user.
     * If the user did not enter a complete mapping, the volumes that were left behind will be auto mapped using
     * {@link #createStoragePoolMappingsForVolumes(VirtualMachineProfile, DataCenterDeployment, Map, List)}
     */
    @Override
    public Map<Volume, StoragePool> createMappingVolumeAndStoragePool(VirtualMachineProfile profile, Host targetHost,
            Map<Long, Long> userDefinedMapOfVolumesAndStoragePools) {
        return createMappingVolumeAndStoragePool(profile,
                new DataCenterDeployment(targetHost.getDataCenterId(), targetHost.getPodId(), targetHost.getClusterId(), targetHost.getId(), null, null),
                userDefinedMapOfVolumesAndStoragePools);
    }

    @Override
    public Map<Volume, StoragePool> createMappingVolumeAndStoragePool(final VirtualMachineProfile profile, final DataCenterDeployment plan,
            final Map<Long, Long> userDefinedMapOfVolumesAndStoragePools) {
        Host targetHost = null;
        if (plan.getHostId() != null) {
            targetHost = hostDao.findById(plan.getHostId());
        }
        Map<Volume, StoragePool> volumeToPoolObjectMap = buildMapUsingUserInformation(profile, targetHost, userDefinedMapOfVolumesAndStoragePools);

        List<Volume> volumesNotMapped = findVolumesThatWereNotMappedByTheUser(profile, volumeToPoolObjectMap);
        createStoragePoolMappingsForVolumes(profile, plan, volumeToPoolObjectMap, volumesNotMapped);
        return volumeToPoolObjectMap;
    }

    /**
     * Given the map of volume to target storage pool entered by the user, we check for other volumes that the VM might have and were not configured.
     * This map can be then used by CloudStack to find new target storage pools according to the target host.
     */
    protected List<Volume> findVolumesThatWereNotMappedByTheUser(VirtualMachineProfile profile, Map<Volume, StoragePool> volumeToStoragePoolObjectMap) {
        List<VolumeVO> allVolumes = volumeDao.findUsableVolumesForInstance(profile.getId());
        List<Volume> volumesNotMapped = new ArrayList<>();
        for (Volume volume : allVolumes) {
            if (!volumeToStoragePoolObjectMap.containsKey(volume)) {
                volumesNotMapped.add(volume);
            }
        }
        return volumesNotMapped;
    }

    /**
     * Builds the map of storage pools and volumes with the information entered by the user. Before creating the an entry we validate if the migration is feasible checking if the migration is allowed and if the target host can access the defined target storage pool.
     */
    protected Map<Volume, StoragePool> buildMapUsingUserInformation(VirtualMachineProfile profile, Host targetHost, Map<Long, Long> userDefinedVolumeToStoragePoolMap) {
        Map<Volume, StoragePool> volumeToPoolObjectMap = new HashMap<>();
        if (MapUtils.isEmpty(userDefinedVolumeToStoragePoolMap)) {
            return volumeToPoolObjectMap;
        }
        for (Long volumeId : userDefinedVolumeToStoragePoolMap.keySet()) {
            VolumeVO volume = volumeDao.findById(volumeId);

            Long poolId = userDefinedVolumeToStoragePoolMap.get(volumeId);
            StoragePoolVO targetPool = storagePoolDao.findById(poolId);
            StoragePoolVO currentPool = storagePoolDao.findById(volume.getPoolId());

            executeManagedStorageChecksWhenTargetStoragePoolProvided(currentPool, volume, targetPool);
            if (targetHost != null && poolHostDao.findByPoolHost(targetPool.getId(), targetHost.getId()) == null) {
                throw new CloudRuntimeException(
                        String.format("Cannot migrate the volume [%s] to the storage pool [%s] while migrating VM [%s] to target host [%s]. The host does not have access to the storage pool entered.",
                                volume.getUuid(), targetPool.getUuid(), profile.getUuid(), targetHost.getUuid()));
            }
            if (currentPool.getId() == targetPool.getId()) {
                logger.info("The volume [{}] is already allocated in storage pool [{}].", volume.getUuid(), targetPool.getUuid());
            }
            volumeToPoolObjectMap.put(volume, targetPool);
        }
        return volumeToPoolObjectMap;
    }

    /**
     * Executes the managed storage checks for the mapping<volume, storage pool> entered by the user.
     */
    protected void executeManagedStorageChecksWhenTargetStoragePoolProvided(StoragePoolVO currentPool, VolumeVO volume, StoragePoolVO targetPool) {
        if (!currentPool.isManaged() || currentPool.getPoolType().equals(Storage.StoragePoolType.PowerFlex)) {
            return;
        }
        if (currentPool.getId() == targetPool.getId()) {
            return;
        }

        Map<String, String> details = storagePoolDao.getDetails(currentPool.getId());
        if (details != null && Boolean.parseBoolean(details.get(Storage.Capability.ALLOW_MIGRATE_OTHER_POOLS.toString()))) {
            return;
        }
        throw new CloudRuntimeException(String.format("Currently, a volume on managed storage can only be 'migrated' to itself " + "[volumeId=%s, currentStoragePoolId=%s, targetStoragePoolId=%s].",
                volume.getUuid(), currentPool.getUuid(), targetPool.getUuid()));
    }

    /**
     * For each one of the volumes we will map it to a storage pool that is available via the target host.
     * An exception is thrown if we cannot find a storage pool that is accessible in the target host to migrate the volume to.
     */
    protected void createStoragePoolMappingsForVolumes(VirtualMachineProfile profile, DataCenterDeployment plan, Map<Volume, StoragePool> volumeToPoolObjectMap, List<Volume> volumesNotMapped) {
        for (Volume volume : volumesNotMapped) {
            StoragePoolVO currentPool = storagePoolDao.findById(volume.getPoolId());

            Host targetHost = null;
            if (plan.getHostId() != null) {
                targetHost = hostDao.findById(plan.getHostId());
            }
            executeManagedStorageChecksWhenTargetStoragePoolNotProvided(targetHost, currentPool, volume);
            if (ScopeType.HOST.equals(currentPool.getScope()) || isStorageCrossClusterMigration(plan.getClusterId(), currentPool)) {
                createVolumeToStoragePoolMappingIfPossible(profile, plan, volumeToPoolObjectMap, volume, currentPool);
            } else if (shouldMapVolume(profile, currentPool)) {
                volumeToPoolObjectMap.put(volume, currentPool);
            }
        }
    }

    /**
     * Returns true if it should map the volume for a storage pool to migrate.
     */
    protected boolean shouldMapVolume(VirtualMachineProfile profile, StoragePoolVO currentPool) {
        boolean isManaged = currentPool.isManaged();
        boolean isNotKvm = HypervisorType.KVM != profile.getHypervisorType();
        return isNotKvm || isManaged;
    }

    /**
     * Executes the managed storage checks for the volumes that the user has not entered a mapping of <volume, storage pool>.
     */
    protected void executeManagedStorageChecksWhenTargetStoragePoolNotProvided(Host targetHost, StoragePoolVO currentPool, Volume volume) {
        if (!currentPool.isManaged()) {
            return;
        }
        if (targetHost != null && poolHostDao.findByPoolHost(currentPool.getId(), targetHost.getId()) == null) {
            throw new CloudRuntimeException(String.format("The target host does not have access to the volume's managed storage pool. [volumeId=%s, storageId=%s, targetHostId=%s].", volume.getUuid(),
                    currentPool.getUuid(), targetHost.getUuid()));
        }
    }

    /**
     * Return true if the VM migration is a cross cluster migration.
     */
    protected boolean isStorageCrossClusterMigration(Long clusterId, StoragePoolVO currentPool) {
        return clusterId != null && ScopeType.CLUSTER.equals(currentPool.getScope()) && !currentPool.getClusterId().equals(clusterId);
    }

    /**
     * We will add a mapping of volume to storage pool if needed.
     */
    protected void createVolumeToStoragePoolMappingIfPossible(VirtualMachineProfile profile, DataCenterDeployment plan, Map<Volume, StoragePool> volumeToPoolObjectMap, Volume volume,
            StoragePoolVO currentPool) {
        List<StoragePool> storagePoolList = getCandidateStoragePoolsToMigrateLocalVolume(profile, plan, volume);

        if (CollectionUtils.isEmpty(storagePoolList)) {
            String msg;
            if (plan.getHostId() != null) {
                Host targetHost = hostDao.findById(plan.getHostId());
                msg = String.format("There are no storage pools available at the target host [%s] to migrate volume [%s]", targetHost.getUuid(), volume.getUuid());
            } else {
                Cluster targetCluster = clusterDao.findById(plan.getClusterId());
                msg = String.format("There are no storage pools available in the target cluster [%s] to migrate volume [%s]", targetCluster.getUuid(), volume.getUuid());
            }
            throw new CloudRuntimeException(msg);
        }

        Collections.shuffle(storagePoolList);
        boolean candidatePoolsListContainsVolumeCurrentStoragePool = false;
        for (StoragePool storagePool : storagePoolList) {
            if (storagePool.getId() == currentPool.getId()) {
                candidatePoolsListContainsVolumeCurrentStoragePool = true;
                break;
            }
        }
        if (!candidatePoolsListContainsVolumeCurrentStoragePool) {
            volumeToPoolObjectMap.put(volume, storagePoolDao.findByUuid(storagePoolList.get(0).getUuid()));
        }
    }

    /**
     * We use {@link StoragePoolAllocator} objects to find storage pools for given DataCenterDeployment where we would be able to allocate the given volume.
     */
    protected List<StoragePool> getCandidateStoragePoolsToMigrateLocalVolume(VirtualMachineProfile profile, DataCenterDeployment plan, Volume volume) {
        List<StoragePool> poolList = new ArrayList<>();

        DiskOfferingVO diskOffering = diskOfferingDao.findById(volume.getDiskOfferingId());
        DiskProfile diskProfile = new DiskProfile(volume, diskOffering, profile.getHypervisorType());
        ExcludeList avoid = new ExcludeList();

        StoragePoolVO volumeStoragePool = storagePoolDao.findById(volume.getPoolId());
        if (volumeStoragePool.isLocal()) {
            diskProfile.setUseLocalStorage(true);
        }
        for (StoragePoolAllocator allocator : storagePoolAllocators) {
            List<StoragePool> poolListFromAllocator = allocator.allocateToPool(diskProfile, profile, plan, avoid, StoragePoolAllocator.RETURN_UPTO_ALL);
            if (CollectionUtils.isEmpty(poolListFromAllocator)) {
                continue;
            }
            for (StoragePool pool : poolListFromAllocator) {
                if (pool.isLocal() || isStorageCrossClusterMigration(plan.getClusterId(), volumeStoragePool)) {
                    poolList.add(pool);
                }
            }
        }
        return poolList;
    }

    private static final org.apache.logging.log4j.Logger logger = org.apache.logging.log4j.LogManager.getLogger(VmVolumeMigrationPlanningServiceImpl.class);
}
