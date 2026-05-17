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
package org.apache.cloudstack.vm;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.resourcelimit.Reserver;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.configuration.Resource;
import com.cloud.dc.DataCenter;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.org.Cluster;
import com.cloud.storage.StoragePool;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.Pair;
import com.cloud.utils.UuidUtils;

/**
 * Pre-flight disk / storage-pool validation for unmanaged-VM import —
 * extracted from {@link UnmanagedVMsManagerImpl}.
 *
 * @see UnmanagedInstanceDiskValidator
 */
@Component
public class UnmanagedInstanceDiskValidatorImpl implements UnmanagedInstanceDiskValidator {
    private static final Logger LOG = LogManager.getLogger(UnmanagedInstanceDiskValidatorImpl.class);

    @Inject
    private PrimaryDataStoreDao primaryDataStoreDao;
    @Inject
    private VolumeApiService volumeApiService;
    @Inject
    private AccountService accountService;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private ResourceLimitService resourceLimitService;

    @Override
    public StoragePool getStoragePool(final UnmanagedInstanceTO.Disk disk, final DataCenter zone, final Cluster cluster, DiskOffering diskOffering) {
        StoragePool storagePool = null;
        final String dsHost = disk.getDatastoreHost();
        final String dsPath = disk.getDatastorePath();
        final String dsType = disk.getDatastoreType();
        final String dsName = disk.getDatastoreName();
        if (dsType != null) {
            List<StoragePoolVO> pools = primaryDataStoreDao.listPoolByHostPath(dsHost, dsPath);
            for (StoragePool pool : pools) {
                if (pool.getDataCenterId() == zone.getId() &&
                        (pool.getClusterId() == null || pool.getClusterId().equals(cluster.getId())) &&
                        volumeApiService.doesStoragePoolSupportDiskOffering(pool, diskOffering)) {
                    storagePool = pool;
                    break;
                }
            }
        }

        if (storagePool == null) {
            Set<StoragePoolVO> pools = new HashSet<>(primaryDataStoreDao.listPoolsByCluster(cluster.getId()));
            pools.addAll(primaryDataStoreDao.listByDataCenterId(zone.getId()));
            boolean isNameUuid = StringUtils.isNotBlank(dsName) && UuidUtils.isUuid(dsName);
            for (StoragePool pool : pools) {
                String searchPoolParam = StringUtils.isNotBlank(dsPath) ? dsPath : dsName;
                if ((StringUtils.contains(pool.getPath(), searchPoolParam) || isNameUuid && pool.getUuid().equals(dsName)) &&
                        volumeApiService.doesStoragePoolSupportDiskOffering(pool, diskOffering)) {
                    storagePool = pool;
                    break;
                }
            }
        }
        if (storagePool == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Storage pool for disk %s(%s) with datastore: %s not found in zone ID: %s", disk.getLabel(), disk.getDiskId(), disk.getDatastoreName(), zone.getUuid()));
        }
        return storagePool;
    }

    @Override
    public boolean storagePoolSupportsDiskOffering(StoragePool pool, DiskOffering diskOffering) {
        if (pool == null) {
            return false;
        }
        if (diskOffering == null) {
            return false;
        }
        return volumeApiService.doesStoragePoolSupportDiskOffering(pool, diskOffering);
    }

    @Override
    public Pair<UnmanagedInstanceTO.Disk, List<UnmanagedInstanceTO.Disk>> getRootAndDataDisks(
            List<UnmanagedInstanceTO.Disk> disks,
            final Map<String, Long> dataDiskOfferingMap) {
        UnmanagedInstanceTO.Disk rootDisk = null;
        List<UnmanagedInstanceTO.Disk> dataDisks = new ArrayList<>();

        Set<String> callerDiskIds = dataDiskOfferingMap.keySet();
        if (callerDiskIds.size() != disks.size() - 1) {
            String msg = String.format("VM has total %d disks for which %d disk offering mappings provided. %d disks need a disk offering for import", disks.size(), callerDiskIds.size(), disks.size() - 1);
            LOG.error(String.format("%s. %s parameter can be used to provide disk offerings for the disks", msg, ApiConstants.DATADISK_OFFERING_LIST));
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, msg);
        }
        List<String> diskIdsWithoutOffering = new ArrayList<>();
        for (UnmanagedInstanceTO.Disk disk : disks) {
            String diskId = disk.getDiskId();
            if (!callerDiskIds.contains(diskId)) {
                diskIdsWithoutOffering.add(diskId);
                rootDisk = disk;
            } else {
                dataDisks.add(disk);
                DiskOffering diskOffering = diskOfferingDao.findById(dataDiskOfferingMap.getOrDefault(disk.getDiskId(), null));
                if ((disk.getCapacity() == null || disk.getCapacity() <= 0) && diskOffering != null) {
                    disk.setCapacity(diskOffering.getDiskSize());
                }
            }
        }
        if (diskIdsWithoutOffering.size() > 1 || rootDisk == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("VM has total %d disks, disk offering mapping not provided for %d disks. Disk IDs that may need a disk offering - %s", disks.size(), diskIdsWithoutOffering.size() - 1, String.join(", ", diskIdsWithoutOffering)));
        }

        return new Pair<>(rootDisk, dataDisks);
    }

    @Override
    public void checkUnmanagedDiskAndOfferingForImport(String instanceName, UnmanagedInstanceTO.Disk disk, DiskOffering diskOffering, ServiceOffering serviceOffering, final Account owner, final DataCenter zone, final Cluster cluster, final boolean migrateAllowed, List<Reserver> reservations)
            throws ServerApiException, PermissionDeniedException, ResourceAllocationException {
        if (serviceOffering == null && diskOffering == null) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Disk offering for disk ID [%s] not found during VM [%s] import.", disk.getDiskId(), instanceName));
        }
        if (diskOffering != null) {
            accountService.checkAccess(owner, diskOffering, zone);
        }
        if (disk.getCapacity() == null || disk.getCapacity() == 0) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Size of disk(ID: %s) is found invalid during VM import", disk.getDiskId()));
        }
        if (diskOffering != null && !diskOffering.isCustomized() && diskOffering.getDiskSize() == 0) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Size of fixed disk offering(ID: %s) is found invalid during VM import", diskOffering.getUuid()));
        }
        if (diskOffering != null && !diskOffering.isCustomized() && diskOffering.getDiskSize() < disk.getCapacity()) {
            throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Size of disk offering(ID: %s) %dGB is found less than the size of disk(ID: %s) %dGB during VM import", diskOffering.getUuid(), (diskOffering.getDiskSize() / Resource.ResourceType.bytesToGiB), disk.getDiskId(), (disk.getCapacity() / (Resource.ResourceType.bytesToGiB))));
        }
        diskOffering = diskOffering != null ? diskOffering : diskOfferingDao.findById(serviceOffering.getDiskOfferingId());
        StoragePool storagePool = getStoragePool(disk, zone, cluster, diskOffering);
        if (diskOffering != null && !migrateAllowed && !storagePoolSupportsDiskOffering(storagePool, diskOffering)) {
            throw new InvalidParameterValueException(String.format("Disk offering: %s is not compatible with storage pool: %s of unmanaged disk: %s", diskOffering.getUuid(), storagePool.getUuid(), disk.getDiskId()));
        }
        resourceLimitService.checkVolumeResourceLimit(owner, true, disk.getCapacity(), diskOffering, reservations);
    }

    @Override
    public void checkUnmanagedDiskAndOfferingForImport(String instanceName, List<UnmanagedInstanceTO.Disk> disks, final Map<String, Long> diskOfferingMap, final Account owner, final DataCenter zone, final Cluster cluster, final boolean migrateAllowed, List<Reserver> reservations)
            throws ServerApiException, PermissionDeniedException, ResourceAllocationException {
        String diskController = null;
        for (UnmanagedInstanceTO.Disk disk : disks) {
            if (disk == null) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Unable to retrieve disk details for VM [%s].", instanceName));
            }
            if (!diskOfferingMap.containsKey(disk.getDiskId())) {
                throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Disk offering for disk ID [%s] not found during VM import.", disk.getDiskId()));
            }
            if (StringUtils.isEmpty(diskController)) {
                diskController = disk.getController();
            } else {
                if (!diskController.equals(disk.getController())) {
                    throw new ServerApiException(ApiErrorCode.INTERNAL_ERROR, String.format("Multiple data disk controllers of different type (%s, %s) are not supported for import. Please make sure that all data disk controllers are of the same type", diskController, disk.getController()));
                }
            }
            checkUnmanagedDiskAndOfferingForImport(instanceName, disk, diskOfferingDao.findById(diskOfferingMap.get(disk.getDiskId())), null, owner, zone, cluster, migrateAllowed, reservations);
        }
    }
}
