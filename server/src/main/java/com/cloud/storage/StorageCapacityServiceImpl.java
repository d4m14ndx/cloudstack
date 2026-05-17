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

import static com.cloud.utils.NumbersUtil.toHumanReadableSize;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.ApiDBUtils;
import com.cloud.capacity.Capacity;
import com.cloud.capacity.CapacityManager;
import com.cloud.capacity.CapacityState;
import com.cloud.capacity.CapacityVO;
import com.cloud.capacity.dao.CapacityDao;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.org.Grouping.AllocationState;
import com.cloud.resource.ResourceState;
import com.cloud.server.StatsCollector;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Storage capacity bookkeeping, used-stats aggregation, and pre-allocation
 * space / IOPS checks — extracted from {@link StorageManagerImpl}.
 *
 * @see StorageCapacityService
 */
@Component
public class StorageCapacityServiceImpl implements StorageCapacityService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected CapacityDao capacityDao;
    @Inject
    protected CapacityManager capacityMgr;
    @Inject
    protected PrimaryDataStoreDao storagePoolDao;
    @Inject
    protected StoragePoolHostDao storagePoolHostDao;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected DataCenterDao dcDao;
    @Inject
    protected ClusterDao clusterDao;
    @Inject
    protected ConfigurationManager configMgr;
    @Inject
    protected DataStoreManager dataStoreMgr;
    @Inject
    protected DataStoreProviderManager dataStoreProviderMgr;
    @Inject
    protected ObjectStoreDao objectStoreDao;
    @Inject
    protected VolumeDao volumeDao;
    @Inject
    protected VMTemplateDao templateDao;
    @Inject
    protected DiskOfferingDao diskOfferingDao;
    @Inject
    protected VMInstanceDao vmInstanceDao;
    @Inject
    protected VolumeService volService;
    @Inject
    protected VolumeDataFactory volFactory;
    @Inject
    protected TemplateDataFactory tmplFactory;

    // ---------------------------------------------------------------------
    // Capacity bookkeeping
    // ---------------------------------------------------------------------

    @Override
    public BigDecimal getStorageOverProvisioningFactor(Long poolId) {
        return new BigDecimal(CapacityManager.StorageOverprovisioningFactor.valueIn(poolId));
    }

    @Override
    public void createCapacityEntry(StoragePoolVO storagePool, short capacityType, long allocated) {
        SearchCriteria<CapacityVO> capacitySC = capacityDao.createSearchCriteria();
        capacitySC.addAnd("hostOrPoolId", SearchCriteria.Op.EQ, storagePool.getId());
        capacitySC.addAnd("dataCenterId", SearchCriteria.Op.EQ, storagePool.getDataCenterId());
        capacitySC.addAnd("capacityType", SearchCriteria.Op.EQ, capacityType);

        List<CapacityVO> capacities = capacityDao.search(capacitySC, null);

        long totalOverProvCapacity;
        if (storagePool.getPoolType().supportsOverProvisioning()) {
            // All this is for the inaccuracy of floats for big number multiplication.
            BigDecimal overProvFactor = getStorageOverProvisioningFactor(storagePool.getId());
            totalOverProvCapacity = overProvFactor.multiply(new BigDecimal(storagePool.getCapacityBytes())).longValue();
            logger.debug("Found storage pool {} of type {} with overprovisioning factor {}", storagePool, storagePool.getPoolType(), overProvFactor);
            logger.debug("Total over provisioned capacity calculated is {} * {}", overProvFactor, toHumanReadableSize(storagePool.getCapacityBytes()));
        } else {
            logger.debug("Found storage pool {} of type {}", storagePool, storagePool.getPoolType());
            totalOverProvCapacity = storagePool.getCapacityBytes();
        }

        logger.debug("Total over provisioned capacity of the pool {} is {}", storagePool, toHumanReadableSize(totalOverProvCapacity));
        CapacityState capacityState = CapacityState.Enabled;
        if (storagePool.getScope() == ScopeType.ZONE) {
            DataCenterVO dc = dcDao.findById(storagePool.getDataCenterId());
            AllocationState allocationState = dc.getAllocationState();
            capacityState = (allocationState == AllocationState.Disabled) ? CapacityState.Disabled : CapacityState.Enabled;
        } else {
            if (storagePool.getClusterId() != null) {
                ClusterVO cluster = ApiDBUtils.findClusterById(storagePool.getClusterId());
                if (cluster != null) {
                    AllocationState allocationState = configMgr.findClusterAllocationState(cluster);
                    capacityState = (allocationState == AllocationState.Disabled) ? CapacityState.Disabled : CapacityState.Enabled;
                }
            }
        }

        if (storagePool.getScope() == ScopeType.HOST) {
            List<StoragePoolHostVO> stoargePoolHostVO = storagePoolHostDao.listByPoolId(storagePool.getId());

            if (stoargePoolHostVO != null && !stoargePoolHostVO.isEmpty()) {
                HostVO host = hostDao.findById(stoargePoolHostVO.get(0).getHostId());

                if (host != null) {
                    capacityState = (host.getResourceState() == ResourceState.Disabled) ? CapacityState.Disabled : CapacityState.Enabled;
                }
            }
        }

        if (capacities.size() == 0) {
            CapacityVO capacity = new CapacityVO(storagePool.getId(), storagePool.getDataCenterId(), storagePool.getPodId(), storagePool.getClusterId(), allocated, totalOverProvCapacity,
                    capacityType);
            capacity.setCapacityState(capacityState);
            capacityDao.persist(capacity);
        } else {
            CapacityVO capacity = capacities.get(0);
            if (capacity.getTotalCapacity() != totalOverProvCapacity || allocated != capacity.getUsedCapacity() || capacity.getCapacityState() != capacityState) {
                capacity.setTotalCapacity(totalOverProvCapacity);
                capacity.setUsedCapacity(allocated);
                capacity.setCapacityState(capacityState);
                capacityDao.update(capacity.getId(), capacity);
            }
        }
        logger.debug("Successfully set Capacity - {} for capacity type - {} , DataCenterId - {}, Pool - {}, PodId {}",
                toHumanReadableSize(totalOverProvCapacity), capacityType, storagePool.getDataCenterId(), storagePool, storagePool.getPodId());
    }

    @Override
    public void createCapacityEntry(long poolId) {
        StoragePoolVO storage = storagePoolDao.findById(poolId);
        createCapacityEntry(storage, Capacity.CAPACITY_TYPE_STORAGE_ALLOCATED, 0);
    }

    // ---------------------------------------------------------------------
    // Used-stats aggregation
    // ---------------------------------------------------------------------

    @Override
    public CapacityVO getSecondaryStorageUsedStats(Long hostId, Long zoneId) {
        List<Long> hosts = new ArrayList<>();
        if (hostId != null) {
            hosts.add(hostId);
        } else {
            List<DataStore> stores = dataStoreMgr.getImageStoresByScope(new ZoneScope(zoneId));
            if (stores != null) {
                for (DataStore store : stores) {
                    hosts.add(store.getId());
                }
            }
        }

        CapacityVO capacity = new CapacityVO(hostId, zoneId, null, null, 0, 0, Capacity.CAPACITY_TYPE_SECONDARY_STORAGE);
        for (Long id : hosts) {
            StorageStats stats = ApiDBUtils.getSecondaryStorageStatistics(id);
            if (stats == null) {
                continue;
            }
            capacity.setUsedCapacity(stats.getByteUsed() + capacity.getUsedCapacity());
            capacity.setTotalCapacity(stats.getCapacityBytes() + capacity.getTotalCapacity());
        }

        return capacity;
    }

    @Override
    public CapacityVO getStoragePoolUsedStats(Long poolId, Long clusterId, Long podId, Long zoneId) {
        return getStoragePoolUsedStatsInternal(zoneId, podId, clusterId, null, poolId);
    }

    @Override
    public CapacityVO getStoragePoolUsedStats(Long zoneId, Long podId, Long clusterId, List<Long> poolIds) {
        return getStoragePoolUsedStatsInternal(zoneId, podId, clusterId, poolIds, null);
    }

    protected CapacityVO getStoragePoolUsedStatsInternal(Long zoneId, Long podId, Long clusterId, List<Long> poolIds, Long poolId) {
        SearchCriteria<StoragePoolVO> sc = storagePoolDao.createSearchCriteria();
        List<StoragePoolVO> pools = new ArrayList<>();

        if (zoneId != null) {
            sc.addAnd("dataCenterId", SearchCriteria.Op.EQ, zoneId);
        }
        if (podId != null) {
            sc.addAnd("podId", SearchCriteria.Op.EQ, podId);
        }
        if (clusterId != null) {
            sc.addAnd("clusterId", SearchCriteria.Op.EQ, clusterId);
        }
        if (CollectionUtils.isNotEmpty(poolIds)) {
            sc.addAnd("id", SearchCriteria.Op.IN, poolIds.toArray());
        }
        if (poolId != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, poolId);
        }
        sc.addAnd("parent", SearchCriteria.Op.EQ, 0L);
        if (poolId != null) {
            pools.add(storagePoolDao.findById(poolId));
        } else {
            pools = storagePoolDao.search(sc, null);
        }

        CapacityVO capacity = new CapacityVO(poolId, zoneId, podId, clusterId, 0, 0, Capacity.CAPACITY_TYPE_STORAGE);
        for (StoragePoolVO primaryDataStoreVO : pools) {
            StorageStats stats = ApiDBUtils.getStoragePoolStatistics(primaryDataStoreVO.getId());
            if (stats == null) {
                continue;
            }
            capacity.setUsedCapacity(stats.getByteUsed() + capacity.getUsedCapacity());
            capacity.setTotalCapacity(stats.getCapacityBytes() + capacity.getTotalCapacity());
        }
        return capacity;
    }

    @Override
    public CapacityVO getObjectStorageUsedStats(Long zoneId) {
        List<ObjectStoreVO> objectStores = objectStoreDao.listObjectStores();
        Long allocated = 0L;
        Long total = 0L;
        for (ObjectStoreVO objectStore : objectStores) {
            if (objectStore.getAllocatedSize() != null) {
                allocated += objectStore.getAllocatedSize();
            }
            if (objectStore.getTotalSize() != null) {
                total += objectStore.getTotalSize();
            }
        }
        return new CapacityVO(null, zoneId, null, null, allocated, total, Capacity.CAPACITY_TYPE_OBJECT_STORAGE);
    }

    // ---------------------------------------------------------------------
    // Pre-allocation IOPS / space checks
    // ---------------------------------------------------------------------

    @Override
    public boolean storagePoolHasEnoughIops(List<Pair<Volume, DiskProfile>> requestedVolumes, StoragePool pool) {
        if (requestedVolumes == null || requestedVolumes.isEmpty() || pool == null) {
            logger.debug(String.format("Cannot check if storage [%s] has enough IOPS to allocate volumes [%s].", pool, requestedVolumes));
            return false;
        }
        if (checkIfPoolIopsCapacityNull(pool)) {
            return true;
        }
        long requestedIops = 0;
        for (Pair<Volume, DiskProfile> volumeDiskProfilePair : requestedVolumes) {
            Volume requestedVolume = volumeDiskProfilePair.first();
            DiskProfile diskProfile = volumeDiskProfilePair.second();
            Long minIops = requestedVolume.getMinIops();
            if (requestedVolume.getDiskOfferingId() != diskProfile.getDiskOfferingId()) {
                minIops = diskProfile.getMinIops();
            }

            if (minIops != null && minIops > 0) {
                requestedIops += minIops;
            }
        }
        return storagePoolHasEnoughIopsInternal(requestedIops, requestedVolumes, pool, true);
    }

    @Override
    public boolean storagePoolHasEnoughIops(Long requestedIops, StoragePool pool) {
        if (pool == null) {
            return false;
        }
        if (requestedIops == null || requestedIops == 0) {
            return true;
        }
        return storagePoolHasEnoughIopsInternal(requestedIops, new ArrayList<>(), pool, false);
    }

    protected boolean storagePoolHasEnoughIopsInternal(long requestedIops, List<Pair<Volume, DiskProfile>> requestedVolumes, StoragePool pool, boolean skipPoolNullIopsCheck) {
        if (!skipPoolNullIopsCheck && checkIfPoolIopsCapacityNull(pool)) {
            return true;
        }
        StoragePoolVO storagePoolVo = storagePoolDao.findById(pool.getId());
        long currentIops = capacityMgr.getUsedIops(storagePoolVo);
        long futureIops = currentIops + requestedIops;
        boolean hasEnoughIops = futureIops <= pool.getCapacityIops();
        String hasCapacity = hasEnoughIops ? "has" : "does not have";
        logger.debug(String.format("Pool [%s] %s enough IOPS to allocate volumes [%s].", pool, hasCapacity, requestedVolumes));
        return hasEnoughIops;
    }

    protected boolean checkIfPoolIopsCapacityNull(StoragePool pool) {
        // Only IOPS-guaranteed primary storage like SolidFire is using/setting IOPS.
        // This check returns true for storage that does not specify IOPS.
        if (pool.getCapacityIops() == null) {
            logger.info("Storage pool {} does not supply IOPS capacity, assuming enough capacity", pool);

            return true;
        }
        return false;
    }

    @Override
    public boolean storagePoolHasEnoughSpace(Long size, StoragePool pool) {
        if (size == null || size == 0) {
            return true;
        }
        final StoragePoolVO poolVO = storagePoolDao.findById(pool.getId());
        long allocatedSizeWithTemplate = capacityMgr.getAllocatedPoolCapacity(poolVO, null);
        return checkPoolforSpace(pool, allocatedSizeWithTemplate, size);
    }

    @Override
    public boolean storagePoolHasEnoughSpace(List<Pair<Volume, DiskProfile>> volumeDiskProfilePairs, StoragePool pool) {
        return storagePoolHasEnoughSpace(volumeDiskProfilePairs, pool, null);
    }

    @Override
    public boolean storagePoolHasEnoughSpace(List<Pair<Volume, DiskProfile>> volumeDiskProfilesList, StoragePool pool, Long clusterId) {
        if (CollectionUtils.isEmpty(volumeDiskProfilesList)) {
            logger.debug(String.format("Cannot check if pool [%s] has enough space to allocate volumes because the volumes list is empty.", pool));
            return false;
        }

        if (!checkUsagedSpace(pool)) {
            logger.debug(String.format("Cannot allocate pool [%s] because there is not enough space in this pool.", pool));
            return false;
        }

        // allocated space includes templates
        if (logger.isDebugEnabled()) {
            logger.debug("Destination pool: {}", pool);
        }
        // allocated space includes templates
        final StoragePoolVO poolVO = storagePoolDao.findById(pool.getId());
        long allocatedSizeWithTemplate = capacityMgr.getAllocatedPoolCapacity(poolVO, null);
        long totalAskingSize = 0;

        for (Pair<Volume, DiskProfile> volumeDiskProfilePair : volumeDiskProfilesList) {
            // refreshing the volume from the DB to get latest hv_ss_reserve (hypervisor snapshot reserve) field
            // I could have just assigned this to "volume", but decided to make a new variable for it so that it
            // might be clearer that this "volume" in "volumeDiskProfilesList" still might have an old value for hv_ss_reverse.
            Volume volume = volumeDiskProfilePair.first();
            DiskProfile diskProfile = volumeDiskProfilePair.second();
            VolumeVO volumeVO = volumeDao.findById(volume.getId());

            if (volumeVO.getHypervisorSnapshotReserve() == null) {
                // update the volume's hv_ss_reserve (hypervisor snapshot reserve) from a disk offering (used for managed storage)
                volService.updateHypervisorSnapshotReserveForVolume(getDiskOfferingVO(volumeVO), volumeVO.getId(), getHypervisorType(volumeVO));

                // hv_ss_reserve field might have been updated; refresh from DB to make use of it in getDataObjectSizeIncludingHypervisorSnapshotReserve
                volumeVO = volumeDao.findById(volume.getId());
            }

            // this if statement should resolve to true at most once per execution of the for loop its contained within (for a root disk that is
            // to leverage a template)
            if (volume.getTemplateId() != null) {
                VMTemplateVO tmpl = templateDao.findByIdIncludingRemoved(volume.getTemplateId());

                if (tmpl != null && !ImageFormat.ISO.equals(tmpl.getFormat())) {
                    allocatedSizeWithTemplate = capacityMgr.getAllocatedPoolCapacity(poolVO, tmpl);
                }
            }

            if (logger.isDebugEnabled()) {
                logger.debug("Pool ID for the volume {} is {}", volumeVO, volumeVO.getPoolId());
            }

            // A ready-state volume is already allocated in a pool, so the asking size is zero for it.
            // In case the volume is moving across pools or is not ready yet, the asking size has to be computed.
            if ((volumeVO.getState() != Volume.State.Ready) || (volumeVO.getPoolId() != pool.getId())) {
                totalAskingSize += getDataObjectSizeIncludingHypervisorSnapshotReserve(volumeVO, diskProfile, poolVO);

                totalAskingSize += getAskingSizeForTemplateBasedOnClusterAndStoragePool(volumeVO.getTemplateId(), clusterId, poolVO);
            }
        }

        return checkPoolforSpace(pool, allocatedSizeWithTemplate, totalAskingSize);
    }

    @Override
    public boolean storagePoolHasEnoughSpaceForResize(StoragePool pool, long currentSize, long newSize) {
        if (!checkUsagedSpace(pool)) {
            return false;
        }
        if (logger.isDebugEnabled()) {
            logger.debug("Destination pool: {}", pool);
        }
        long totalAskingSize = newSize - currentSize;

        if (totalAskingSize <= 0) {
            return true;
        } else {
            final StoragePoolVO poolVO = storagePoolDao.findById(pool.getId());
            final long allocatedSizeWithTemplate = capacityMgr.getAllocatedPoolCapacity(poolVO, null);
            return checkPoolforSpace(pool, allocatedSizeWithTemplate, totalAskingSize, true);
        }
    }

    // ---------------------------------------------------------------------
    // Internal helpers (unchanged behaviour from StorageManagerImpl)
    // ---------------------------------------------------------------------

    protected boolean checkUsagedSpace(StoragePool pool) {
        // Managed storage does not currently deal with accounting for physically used space (only provisioned space). Just return true if "pool" is managed.
        if (pool.isManaged() && !canPoolProvideStorageStats(pool)) {
            return true;
        }

        long totalSize = pool.getCapacityBytes();
        long usedSize = getUsedSize(pool);
        double usedPercentage = ((double) usedSize / (double) totalSize);
        double storageUsedThreshold = CapacityManager.StorageCapacityDisableThreshold.valueIn(pool.getId());
        if (logger.isDebugEnabled()) {
            logger.debug("Checking pool {} for storage, totalSize: {}, usedBytes: {}, usedPct: {}, disable threshold: {}", pool, pool.getCapacityBytes(), pool.getUsedBytes(), usedPercentage, storageUsedThreshold);
        }
        if (usedPercentage >= storageUsedThreshold) {
            if (logger.isDebugEnabled()) {
                logger.debug("Insufficient space on pool: {} since its usage percentage: {} has crossed the pool.storage.capacity.disablethreshold: {}", pool, usedPercentage, storageUsedThreshold);
            }
            return false;
        }
        return true;
    }

    protected long getUsedSize(StoragePool pool) {
        if (pool.getStorageProviderName().equalsIgnoreCase(DataStoreProvider.DEFAULT_PRIMARY) || canPoolProvideStorageStats(pool)) {
            return (pool.getUsedBytes());
        }

        StatsCollector sc = StatsCollector.getInstance();
        if (sc != null) {
            StorageStats stats = sc.getStoragePoolStats(pool.getId());
            if (stats == null) {
                stats = sc.getStorageStats(pool.getId());
            }
            if (stats != null) {
                return (stats.getByteUsed());
            }
        }

        return 0;
    }

    protected boolean canPoolProvideStorageStats(StoragePool pool) {
        DataStoreProvider storeProvider = dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();
        return storeDriver instanceof PrimaryDataStoreDriver && ((PrimaryDataStoreDriver) storeDriver).canProvideStorageStats();
    }

    protected boolean checkPoolforSpace(StoragePool pool, long allocatedSizeWithTemplate, long totalAskingSize) {
        return checkPoolforSpace(pool, allocatedSizeWithTemplate, totalAskingSize, false);
    }

    protected boolean checkPoolforSpace(StoragePool pool, long allocatedSizeWithTemplate, long totalAskingSize, boolean forVolumeResize) {
        // allocated space includes templates
        StoragePoolVO poolVO = storagePoolDao.findById(pool.getId());

        long totalOverProvCapacity;

        if (pool.getPoolType().supportsOverProvisioning()) {
            BigDecimal overProvFactor = getStorageOverProvisioningFactor(pool.getId());

            totalOverProvCapacity = overProvFactor.multiply(new BigDecimal(pool.getCapacityBytes())).longValue();

            logger.debug("Found storage pool {} of type {} with overprovisioning factor {}", pool, pool.getPoolType(), overProvFactor);
            logger.debug("Total over provisioned capacity calculated is {} * {}", overProvFactor, toHumanReadableSize(pool.getCapacityBytes()));
        } else {
            totalOverProvCapacity = pool.getCapacityBytes();

            logger.debug("Found storage pool {} of type {}", poolVO, pool.getPoolType());
        }

        logger.debug("Total capacity of the pool {} is {}", poolVO, toHumanReadableSize(totalOverProvCapacity));

        double storageAllocatedThreshold = CapacityManager.StorageAllocatedCapacityDisableThreshold.valueIn(pool.getId());

        if (logger.isDebugEnabled()) {
            logger.debug("Checking pool: {} for storage allocation , maxSize : {}, " +
                            "totalAllocatedSize : {}, askingSize : {}, allocated disable threshold: {}",
                    pool, toHumanReadableSize(totalOverProvCapacity), toHumanReadableSize(allocatedSizeWithTemplate), toHumanReadableSize(totalAskingSize), storageAllocatedThreshold);
        }

        double usedPercentage = (allocatedSizeWithTemplate + totalAskingSize) / (double) (totalOverProvCapacity);

        if (usedPercentage > storageAllocatedThreshold) {
            if (logger.isDebugEnabled()) {
                logger.debug("Insufficient un-allocated capacity on: {} for storage " +
                                "allocation since its allocated percentage: {} has crossed the allocated" +
                                " pool.storage.allocated.capacity.disablethreshold: {}",
                        pool, usedPercentage, storageAllocatedThreshold);
            }
            if (!forVolumeResize) {
                return false;
            }
            if (!StorageManager.AllowVolumeReSizeBeyondAllocation.valueIn(pool.getId())) {
                logger.debug(String.format("Skipping the pool %s as %s is false", pool, StorageManager.AllowVolumeReSizeBeyondAllocation.key()));
                return false;
            }

            double storageAllocatedThresholdForResize = CapacityManager.StorageAllocatedCapacityDisableThresholdForVolumeSize.valueIn(pool.getId());
            if (usedPercentage > storageAllocatedThresholdForResize) {
                logger.debug(String.format("Skipping the pool %s since its allocated percentage: %s has crossed the allocated %s: %s",
                        pool, usedPercentage, CapacityManager.StorageAllocatedCapacityDisableThresholdForVolumeSize.key(), storageAllocatedThresholdForResize));
                return false;
            }
        }

        if (totalOverProvCapacity < (allocatedSizeWithTemplate + totalAskingSize)) {
            if (logger.isDebugEnabled()) {
                logger.debug("Insufficient un-allocated capacity on: {} for storage " +
                                "allocation, not enough storage, maxSize : {}, totalAllocatedSize : {}, " +
                                "askingSize : {}", pool, toHumanReadableSize(totalOverProvCapacity),
                        toHumanReadableSize(allocatedSizeWithTemplate), toHumanReadableSize(totalAskingSize));
            }

            return false;
        }

        return true;
    }

    /**
     * Storage plug-ins for managed storage can be designed in such a way as to store a template on the primary storage once and
     * make use of it via storage-side cloning.
     *
     * This method determines how many more bytes it will need for the template (if the template is already stored on the primary storage,
     * then the answer is 0).
     */
    protected long getAskingSizeForTemplateBasedOnClusterAndStoragePool(Long templateId, Long clusterId, StoragePoolVO storagePoolVO) {
        if (templateId == null || clusterId == null || storagePoolVO == null || !storagePoolVO.isManaged()) {
            return 0;
        }

        VMTemplateVO tmpl = templateDao.findByIdIncludingRemoved(templateId);

        if (tmpl == null || ImageFormat.ISO.equals(tmpl.getFormat())) {
            return 0;
        }

        HypervisorType hypervisorType = tmpl.getHypervisorType();

        // The getSupportsResigning method is applicable for XenServer as a UUID-resigning patch may or may not be installed on those hypervisor hosts.
        if (clusterDao.getSupportsResigning(clusterId) || HypervisorType.VMware.equals(hypervisorType) || HypervisorType.KVM.equals(hypervisorType)) {
            return getBytesRequiredForTemplate(tmpl, storagePoolVO);
        }

        return 0;
    }

    protected long getDataObjectSizeIncludingHypervisorSnapshotReserve(Volume volume, DiskProfile diskProfile, StoragePool pool) {
        DataStoreProvider storeProvider = dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();

        if (storeDriver instanceof PrimaryDataStoreDriver) {
            PrimaryDataStoreDriver primaryStoreDriver = (PrimaryDataStoreDriver) storeDriver;

            VolumeInfo volumeInfo = volFactory.getVolume(volume.getId());
            if (volume.getDiskOfferingId() != diskProfile.getDiskOfferingId()) {
                return diskProfile.getSize();
            }
            return primaryStoreDriver.getDataObjectSizeIncludingHypervisorSnapshotReserve(volumeInfo, pool);
        }

        return volume.getSize();
    }

    protected DiskOfferingVO getDiskOfferingVO(Volume volume) {
        Long diskOfferingId = volume.getDiskOfferingId();

        return diskOfferingDao.findById(diskOfferingId);
    }

    protected HypervisorType getHypervisorType(Volume volume) {
        Long instanceId = volume.getInstanceId();

        VMInstanceVO vmInstance = vmInstanceDao.findById(instanceId);

        if (vmInstance != null) {
            return vmInstance.getHypervisorType();
        }

        return null;
    }

    protected long getBytesRequiredForTemplate(VMTemplateVO tmpl, StoragePool pool) {
        if (tmplFactory.isTemplateMarkedForDirectDownload(tmpl.getId())) {
            return tmpl.getSize();
        }

        DataStoreProvider storeProvider = dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();

        if (storeDriver instanceof PrimaryDataStoreDriver) {
            PrimaryDataStoreDriver primaryStoreDriver = (PrimaryDataStoreDriver) storeDriver;

            TemplateInfo templateInfo = tmplFactory.getReadyTemplateOnImageStore(tmpl.getId(), pool.getDataCenterId());

            return primaryStoreDriver.getBytesRequiredForTemplate(templateInfo, pool);
        }

        return tmpl.getSize();
    }
}
