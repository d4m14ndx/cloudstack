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

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.user.volume.CreateVolumeCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import org.apache.cloudstack.resourcedetail.DiskOfferingDetailVO;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.springframework.stereotype.Component;

import org.apache.cloudstack.resourcelimit.Reserver;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.offering.DiskOffering;
import com.cloud.org.Grouping.AllocationState;
import com.cloud.resourcelimit.ReservationHelper;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.db.UUIDManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;

@Component
public class VolumeCreateServiceImpl implements VolumeCreateService {

    @Inject
    VolumeDao volsDao;
    @Inject
    SnapshotDao snapshotDao;
    @Inject
    DiskOfferingDao diskOfferingDao;
    @Inject
    DataCenterDao dcDao;
    @Inject
    UserVmDao userVmDao;
    @Inject
    PrimaryDataStoreDao storagePoolDao;
    @Inject
    DiskOfferingDetailsDao diskOfferingDetailsDao;
    @Inject
    SnapshotDataStoreDao snapshotDataStoreDao;
    @Inject
    VolumeDetailsDao volsDetailsDao;
    @Inject
    AccountManager accountMgr;
    @Inject
    ConfigurationManager configMgr;
    @Inject
    ResourceLimitService resourceLimitMgr;
    @Inject
    UUIDManager uuidMgr;
    @Inject
    VolumeOrchestrationService volumeMgr;
    @Inject
    DataStoreManager dataStoreMgr;
    @Inject
    VolumeDataFactory volFactory;
    @Inject
    VolumeService volService;
    @Inject
    VolumeAttachService volumeAttachService;
    @Inject
    DiskOfferingCompatibilityService diskOfferingCompatibilityService;

    @Override
    public String getVolumeNameFromCommand(CreateVolumeCmd cmd) {
        String userSpecifiedName = cmd.getVolumeName();

        if (org.apache.commons.lang3.StringUtils.isBlank(userSpecifiedName)) {
            userSpecifiedName = getRandomVolumeName();
        }

        return userSpecifiedName;
    }

    protected String getRandomVolumeName() {
        return UUID.randomUUID().toString();
    }

    @Override
    @DB
    public VolumeVO allocVolume(CreateVolumeCmd cmd) throws ResourceAllocationException {
        Account caller = CallContext.current().getCallingAccount();

        long ownerId = cmd.getEntityOwnerId();
        Account owner = accountMgr.getActiveAccountById(ownerId);
        Boolean displayVolume = cmd.getDisplayVolume();

        // permission check
        accountMgr.checkAccess(caller, null, true, accountMgr.getActiveAccountById(ownerId));

        if (displayVolume == null) {
            displayVolume = true;
        } else {
            if (!accountMgr.isRootAdmin(caller.getId())) {
                throw new PermissionDeniedException("Cannot update parameter displayvolume, only admin permitted ");
            }
        }

        Long zoneId = cmd.getZoneId();
        Long diskOfferingId = null;
        DiskOfferingVO diskOffering = null;
        Long size = null;
        Long minIops = null;
        Long maxIops = null;
        // Volume VO used for extracting the source template id
        VolumeVO parentVolume = null;

        // validate input parameters before creating the volume
        if (cmd.getSnapshotId() == null && cmd.getDiskOfferingId() == null) {
            throw new InvalidParameterValueException("At least one of disk Offering ID or snapshot ID must be passed whilst creating volume");
        }

        // disallow passing disk offering ID with DATA disk volume snapshots
        if (cmd.getSnapshotId() != null && cmd.getDiskOfferingId() != null) {
            SnapshotVO snapshot = snapshotDao.findById(cmd.getSnapshotId());
            if (snapshot != null) {
                parentVolume = volsDao.findByIdIncludingRemoved(snapshot.getVolumeId());
                if (parentVolume != null && parentVolume.getVolumeType() != Volume.Type.ROOT)
                    throw new InvalidParameterValueException("Disk Offering ID cannot be passed whilst creating volume from snapshot other than ROOT disk snapshots");
            }
            parentVolume = null;
        }

        Map<String, String> details = new HashMap<>();
        if (cmd.getDiskOfferingId() != null) { // create a new volume

            diskOfferingId = cmd.getDiskOfferingId();
            size = cmd.getSize();
            Long sizeInGB = size;
            if (size != null) {
                if (size > 0) {
                    size = size * 1024 * 1024 * 1024; // user specify size in GB
                } else {
                    throw new InvalidParameterValueException("Disk size must be larger than 0");
                }
            }

            // Check that the disk offering is specified
            diskOffering = diskOfferingDao.findById(diskOfferingId);
            if ((diskOffering == null) || diskOffering.getRemoved() != null || diskOffering.isComputeOnly()) {
                throw new InvalidParameterValueException("Please specify a valid disk offering.");
            }

            if (diskOffering.isCustomized()) {
                if (size == null) {
                    throw new InvalidParameterValueException("This disk offering requires a custom size specified");
                }
                validateCustomDiskOfferingSizeRange(sizeInGB);
            }

            if (!diskOffering.isCustomized() && size != null) {
                throw new InvalidParameterValueException("This disk offering does not allow custom size");
            }

            configMgr.checkDiskOfferingAccess(owner, diskOffering, dcDao.findById(zoneId));

            if (diskOffering.getDiskSize() > 0) {
                size = diskOffering.getDiskSize();
            }

            DiskOfferingDetailVO bandwidthLimitDetail = diskOfferingDetailsDao.findDetail(diskOfferingId, Volume.BANDWIDTH_LIMIT_IN_MBPS);
            if (bandwidthLimitDetail != null) {
                details.put(Volume.BANDWIDTH_LIMIT_IN_MBPS, bandwidthLimitDetail.getValue());
            }
            DiskOfferingDetailVO iopsLimitDetail = diskOfferingDetailsDao.findDetail(diskOfferingId, Volume.IOPS_LIMIT);
            if (iopsLimitDetail != null) {
                details.put(Volume.IOPS_LIMIT, iopsLimitDetail.getValue());
            }

            Boolean isCustomizedIops = diskOffering.isCustomizedIops();

            if (isCustomizedIops != null) {
                if (isCustomizedIops) {
                    minIops = cmd.getMinIops();
                    maxIops = cmd.getMaxIops();

                    if (minIops == null && maxIops == null) {
                        minIops = 0L;
                        maxIops = 0L;
                    } else {
                        if (minIops == null || minIops <= 0) {
                            throw new InvalidParameterValueException("The min IOPS must be greater than 0.");
                        }

                        if (maxIops == null) {
                            maxIops = 0L;
                        }

                        if (minIops > maxIops) {
                            throw new InvalidParameterValueException("The min IOPS must be less than or equal to the max IOPS.");
                        }
                    }
                } else {
                    minIops = diskOffering.getMinIops();
                    maxIops = diskOffering.getMaxIops();
                }
            } else {
                minIops = diskOffering.getMinIops();
                maxIops = diskOffering.getMaxIops();
            }

            if (!validateVolumeSizeInBytes(size == null ? 0 : size)) {
                throw new InvalidParameterValueException(String.format("Invalid size for custom volume creation: %s, max volume size is: %s GB", NumbersUtil.toReadableSize(size), VolumeOrchestrationService.MaxVolumeSize.value()));
            }
        }

        if (cmd.getSnapshotId() != null) { // create volume from snapshot
            Long snapshotId = cmd.getSnapshotId();
            SnapshotVO snapshotCheck = snapshotDao.findById(snapshotId);
            if (snapshotCheck == null) {
                throw new InvalidParameterValueException("unable to find a snapshot with id " + snapshotId);
            }

            if (snapshotCheck.getState() != Snapshot.State.BackedUp) {
                throw new InvalidParameterValueException(String.format("Snapshot %s is not in %s state yet and can't be used for volume creation", snapshotCheck, Snapshot.State.BackedUp));
            }

            SnapshotDataStoreVO snapshotStore = snapshotDataStoreDao.findOneBySnapshotAndDatastoreRole(snapshotId, DataStoreRole.Primary);
            if (snapshotStore != null) {
                StoragePoolVO storagePoolVO = storagePoolDao.findById(snapshotStore.getDataStoreId());
                if (storagePoolVO.getPoolType() == Storage.StoragePoolType.PowerFlex) {
                    throw new InvalidParameterValueException("Create volume from snapshot is not supported for PowerFlex volume snapshots");
                }
            }

            parentVolume = volsDao.findByIdIncludingRemoved(snapshotCheck.getVolumeId());

            // Don't support creating templates from encrypted volumes (yet)
            if (parentVolume.getPassphraseId() != null) {
                throw new UnsupportedOperationException("Cannot create new volumes from encrypted volume snapshots");
            }

            if (zoneId == null) {
                // if zoneId is not provided, we default to create volume in the same zone as the snapshot zone.
                zoneId = parentVolume.getDataCenterId();
            }

            if (diskOffering == null) { // Pure snapshot is being used to create volume.
                diskOfferingId = snapshotCheck.getDiskOfferingId();
                diskOffering = diskOfferingDao.findById(diskOfferingId);

                minIops = snapshotCheck.getMinIops();
                maxIops = snapshotCheck.getMaxIops();
                size = snapshotCheck.getSize(); // ; disk offering is used for tags purposes
            } else {
                if (size < snapshotCheck.getSize()) {
                    throw new InvalidParameterValueException(String.format("Invalid size for volume creation: %dGB, snapshot size is: %dGB",
                            size / (1024 * 1024 * 1024), snapshotCheck.getSize() / (1024 * 1024 * 1024)));
                }
            }

            configMgr.checkDiskOfferingAccess(null, diskOffering, dcDao.findById(zoneId));

            // check snapshot permissions
            accountMgr.checkAccess(caller, null, true, snapshotCheck);

            // one step operation - create volume in VM's cluster and attach it
            // to the VM
            Long vmId = cmd.getVirtualMachineId();
            if (vmId != null) {
                // Check that the virtual machine ID is valid and it's a user vm
                UserVmVO vm = userVmDao.findById(vmId);
                if (vm == null || vm.getType() != VirtualMachine.Type.User) {
                    throw new InvalidParameterValueException("Please specify a valid User VM.");
                }
                if (vm.getDataCenterId() != zoneId) {
                    throw new InvalidParameterValueException("The specified zone is different than zone of the VM");
                }
                // Check that the VM is in the correct state
                if (vm.getState() != State.Running && vm.getState() != State.Stopped) {
                    throw new InvalidParameterValueException("Please specify a VM that is either running or stopped.");
                }

                // permission check
                accountMgr.checkAccess(caller, null, false, vm);
            }
        }

        Storage.ProvisioningType provisioningType = diskOffering.getProvisioningType();

        List<String> tags = resourceLimitMgr.getResourceLimitStorageTagsForResourceCountOperation(displayVolume, diskOffering);
        if (tags.size() == 1 && tags.get(0) == null) {
            tags = new ArrayList<>();
        }

        List<Reserver> reservations = new ArrayList<>();
        try {
            resourceLimitMgr.checkVolumeResourceLimit(owner, displayVolume, size, diskOffering, reservations);

            // Verify that zone exists
            DataCenterVO zone = dcDao.findById(zoneId);
            if (zone == null) {
                throw new InvalidParameterValueException("Unable to find zone by id " + zoneId);
            }

            // Check if zone is disabled
            if (AllocationState.Disabled == zone.getAllocationState() && !accountMgr.isRootAdmin(caller.getId())) {
                throw new PermissionDeniedException(String.format("Cannot perform this operation, Zone: %s is currently disabled", zone));
            }

            // If local storage is disabled then creation of volume with local disk
            // offering not allowed
            if (!zone.isLocalStorageEnabled() && diskOffering.isUseLocalStorage()) {
                throw new InvalidParameterValueException("Zone is not configured to use local storage but volume's disk offering " + diskOffering.getName() + " uses it");
            }

            String userSpecifiedName = getVolumeNameFromCommand(cmd);

            return commitVolume(cmd.getSnapshotId(), caller, owner, displayVolume, zoneId, diskOfferingId, provisioningType, size, minIops, maxIops, parentVolume, userSpecifiedName,
                    uuidMgr.generateUuid(Volume.class, cmd.getCustomId()), details);
        } finally {
            ReservationHelper.closeAll(reservations);
        }
    }

    @Override
    public void validateCustomDiskOfferingSizeRange(Long sizeInGB) {
        Long customDiskOfferingMaxSize = VolumeOrchestrationService.CustomDiskOfferingMaxSize.value();
        Long customDiskOfferingMinSize = VolumeOrchestrationService.CustomDiskOfferingMinSize.value();

        if ((sizeInGB < customDiskOfferingMinSize) || (sizeInGB > customDiskOfferingMaxSize)) {
            throw new InvalidParameterValueException(String.format("Volume size: %s GB is out of allowed range. Min: %s. Max: %s", sizeInGB, customDiskOfferingMinSize, customDiskOfferingMaxSize));
        }
    }

    private VolumeVO commitVolume(final Long snapshotId, final Account caller, final Account owner, final Boolean displayVolume, final Long zoneId, final Long diskOfferingId,
                                  final Storage.ProvisioningType provisioningType, final Long size, final Long minIops, final Long maxIops, final VolumeVO parentVolume, final String userSpecifiedName, final String uuid, final Map<String, String> details) {
        return Transaction.execute(new TransactionCallback<VolumeVO>() {
            @Override
            public VolumeVO doInTransaction(TransactionStatus status) {
                VolumeVO volume = new VolumeVO(userSpecifiedName, -1, -1, -1, -1, -1L, null, null, provisioningType, 0, Volume.Type.DATADISK);
                volume.setPoolId(null);
                volume.setUuid(uuid);
                volume.setDataCenterId(zoneId);
                volume.setPodId(null);
                volume.setAccountId(owner.getId());
                volume.setDomainId(owner.getDomainId());
                volume.setDiskOfferingId(diskOfferingId);
                volume.setSize(size);
                volume.setMinIops(minIops);
                volume.setMaxIops(maxIops);
                volume.setInstanceId(null);
                volume.setUpdated(new Date());
                volume.setDisplayVolume(displayVolume);
                if (parentVolume != null) {
                    volume.setTemplateId(parentVolume.getTemplateId());
                    volume.setFormat(parentVolume.getFormat());
                } else {
                    volume.setTemplateId(null);
                }

                volume = volsDao.persist(volume);

                if (snapshotId == null && displayVolume) {
                    // for volume created from snapshot, create usage event after volume creation
                    UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_CREATE, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(), diskOfferingId, null, size,
                            Volume.class.getName(), volume.getUuid(), volume.getInstanceId(), displayVolume);
                }

                if (volume != null && details != null) {
                    List<VolumeDetailVO> volumeDetailsVO = new ArrayList<VolumeDetailVO>();
                    if (details.containsKey(Volume.BANDWIDTH_LIMIT_IN_MBPS)) {
                        volumeDetailsVO.add(new VolumeDetailVO(volume.getId(), Volume.BANDWIDTH_LIMIT_IN_MBPS, details.get(Volume.BANDWIDTH_LIMIT_IN_MBPS), false));
                    }
                    if (details.containsKey(Volume.IOPS_LIMIT)) {
                        volumeDetailsVO.add(new VolumeDetailVO(volume.getId(), Volume.IOPS_LIMIT, details.get(Volume.IOPS_LIMIT), false));
                    }
                    if (!volumeDetailsVO.isEmpty()) {
                        volsDetailsDao.saveDetails(volumeDetailsVO);
                    }
                }

                CallContext.current().setEventDetails("Volume ID: " + volume.getUuid());
                CallContext.current().putContextParameter(Volume.class, volume.getId());
                // Increment resource count during allocation; if actual creation fails,
                // decrement it
                resourceLimitMgr.incrementVolumeResourceCount(volume.getAccountId(), displayVolume, volume.getSize(),
                        diskOfferingDao.findById(volume.getDiskOfferingId()));
                return volume;
            }
        });
    }

    @Override
    public boolean validateVolumeSizeInBytes(long size) {
        long maxVolumeSize = VolumeOrchestrationService.MaxVolumeSize.value();
        if (size < 0 || (size > 0 && size < (1024 * 1024 * 1024))) {
            throw new InvalidParameterValueException("Please specify a size of at least 1 GB.");
        } else if (size > (maxVolumeSize * 1024 * 1024 * 1024)) {
            throw new InvalidParameterValueException(String.format("Requested volume size is %s, but the maximum size allowed is %d GB.", NumbersUtil.toReadableSize(size), maxVolumeSize));
        }

        return true;
    }

    private VolumeVO createVolumeOnStoragePool(Long volumeId, Long storageId) throws ExecutionException, InterruptedException {
        VolumeVO volume = volsDao.findById(volumeId);
        StoragePool storagePool = (StoragePool) dataStoreMgr.getDataStore(storageId, DataStoreRole.Primary);
        if (storagePool == null) {
            throw new InvalidParameterValueException("Failed to find the storage pool: " + storageId);
        } else if (!storagePool.getStatus().equals(StoragePoolStatus.Up)) {
            throw new InvalidParameterValueException(String.format("Cannot create volume %s on storage pool %s as the storage pool is not in Up state.",
                    volume.getUuid(), storagePool.getName()));
        }

        if (storagePool.getDataCenterId() != volume.getDataCenterId()) {
            throw new InvalidParameterValueException(String.format("Cannot create volume %s in zone %s on storage pool %s in zone %s.",
                    volume.getUuid(), volume.getDataCenterId(), storagePool.getUuid(), storagePool.getDataCenterId()));
        }

        DiskOfferingVO diskOffering = diskOfferingDao.findById(volume.getDiskOfferingId());
        if (!doesStoragePoolSupportDiskOffering(storagePool, diskOffering)) {
            throw new InvalidParameterValueException(String.format("Disk offering: %s is not compatible with the storage pool", diskOffering.getUuid()));
        }

        DataStore dataStore = dataStoreMgr.getDataStore(storageId, DataStoreRole.Primary);
        VolumeInfo volumeInfo = volFactory.getVolume(volumeId, dataStore);
        AsyncCallFuture<VolumeApiResult> createVolumeFuture = volService.createVolumeAsync(volumeInfo, dataStore);
        VolumeApiResult createVolumeResult = createVolumeFuture.get();
        if (createVolumeResult.isFailed()) {
            throw new CloudRuntimeException("Volume creation on storage failed: " + createVolumeResult.getResult());
        }
        return volsDao.findById(volumeInfo.getId());
    }

    @Override
    @DB
    public VolumeVO createVolume(CreateVolumeCmd cmd) {
        VolumeVO volume = volsDao.findById(cmd.getEntityId());
        boolean created = true;

        try {
            if (cmd.getSnapshotId() != null) {
                volume = createVolumeFromSnapshot(volume, cmd.getSnapshotId(), cmd.getVirtualMachineId());
                if (volume.getState() != Volume.State.Ready) {
                    created = false;
                }

                // if VM Id is provided, attach the volume to the VM
                if (cmd.getVirtualMachineId() != null) {
                    try {
                        volumeAttachService.attachVolumeToVM(cmd.getVirtualMachineId(), volume.getId(), volume.getDeviceId(), false);
                    } catch (Exception ex) {
                        StringBuilder message = new StringBuilder("Volume: ");
                        message.append(volume.getUuid());
                        message.append(" created successfully, but failed to attach the newly created volume to VM: ");
                        message.append(cmd.getVirtualMachineId());
                        message.append(" due to error: ");
                        message.append(ex.getMessage());
                        throw new CloudRuntimeException(message.toString());
                    }
                }
            } else if (cmd.getStorageId() != null) {
                volume = createVolumeOnStoragePool(cmd.getEntityId(), cmd.getStorageId());
            }
            return volume;
        } catch (Exception e) {
            created = false;
            VolumeInfo vol = volFactory.getVolume(cmd.getEntityId());
            vol.stateTransit(Volume.Event.DestroyRequested);
            throw new CloudRuntimeException(String.format("Failed to create volume: %s", volume), e);
        } finally {
            if (!created) {
                VolumeVO finalVolume = volume;
                resourceLimitMgr.decrementVolumeResourceCount(volume.getAccountId(), cmd.getDisplayVolume(),
                        volume.getSize(), diskOfferingDao.findByIdIncludingRemoved(volume.getDiskOfferingId()));
            }
        }
    }

    protected VolumeVO createVolumeFromSnapshot(VolumeVO volume, long snapshotId, Long vmId) throws StorageUnavailableException {
        VolumeInfo createdVolume = null;
        SnapshotVO snapshot = snapshotDao.findById(snapshotId);
        snapshot.getVolumeId();

        UserVmVO vm = null;
        if (vmId != null) {
            vm = userVmDao.findById(vmId);
        }

        // sync old snapshots to region store if necessary

        createdVolume = volumeMgr.createVolumeFromSnapshot(volume, snapshot, vm);
        VolumeVO volumeVo = volsDao.findById(createdVolume.getId());
        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_CREATE, createdVolume.getAccountId(), createdVolume.getDataCenterId(), createdVolume.getId(), createdVolume.getName(),
                createdVolume.getDiskOfferingId(), null, createdVolume.getSize(), Volume.class.getName(), createdVolume.getUuid(), volume.getInstanceId(), volumeVo.isDisplayVolume());

        return volumeVo;
    }

    private boolean doesStoragePoolSupportDiskOffering(StoragePool destPool, DiskOffering diskOffering) {
        String offeringTags = diskOffering != null ? diskOffering.getTags() : null;
        Pair<List<String>, Boolean> storagePoolTags = getStoragePoolTags(destPool);
        return diskOfferingCompatibilityService.storagePoolTagsMatchOfferingTags(destPool, storagePoolTags, offeringTags);
    }

    protected Pair<List<String>, Boolean> getStoragePoolTags(StoragePool destPool) {
        return diskOfferingCompatibilityService.resolveStoragePoolTags(destPool);
    }
}
