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

import static org.junit.Assert.assertEquals;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import org.apache.cloudstack.backup.Backup;

import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

/**
 * Focused unit tests for {@link VolumeAttachValidatorImpl}.
 * Mirrors the slice extraction tests run against the manager-level
 * spy paths in {@link VolumeApiServiceImplTest}, but exercises the
 * extracted component directly.
 */
@RunWith(MockitoJUnitRunner.class)
public class VolumeAttachValidatorImplTest {

    @Mock
    private VMSnapshotDao vmSnapshotDao;
    @Mock
    private DataCenterDao dataCenterDao;
    @Mock
    private DiskOfferingDao diskOfferingDao;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private VolumeDao volumeDao;

    @Mock
    private VolumeInfo volumeToAttach;
    @Mock
    private UserVmVO vm;
    @Mock
    private VolumeVO volume;
    @Mock
    private DataCenterVO dataCenter;
    @Mock
    private DiskOfferingVO diskOffering;
    @Mock
    private StoragePoolVO storagePool;

    @InjectMocks
    private VolumeAttachValidatorImpl validator;

    private static final long VM_ID = 100L;
    private static final long VOLUME_ID = 200L;
    private static final long ZONE_ID = 300L;
    private static final long DISK_OFFERING_ID = 400L;
    private static final long POOL_ID = 500L;

    @Before
    public void setUp() {
        Mockito.lenient().when(vm.getId()).thenReturn(VM_ID);
        Mockito.lenient().when(vm.getName()).thenReturn("vm-name");
        Mockito.lenient().when(vm.getUuid()).thenReturn("vm-uuid");
        Mockito.lenient().when(volumeToAttach.getId()).thenReturn(VOLUME_ID);
        Mockito.lenient().when(volumeToAttach.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.lenient().when(volumeToAttach.getDiskOfferingId()).thenReturn(DISK_OFFERING_ID);
    }

    // ------------------------------------------------------------------
    // checkForMatchingHypervisorTypesIf
    // ------------------------------------------------------------------

    @Test
    public void checkForMatchingHypervisorTypesIfNoopWhenCheckNotNeeded() {
        // No exception expected.
        validator.checkForMatchingHypervisorTypesIf(false, HypervisorType.KVM, HypervisorType.XenServer);
    }

    @Test
    public void checkForMatchingHypervisorTypesIfNoopWhenVolumeTypeIsNone() {
        validator.checkForMatchingHypervisorTypesIf(true, HypervisorType.KVM, HypervisorType.None);
    }

    @Test
    public void checkForMatchingHypervisorTypesIfNoopWhenTypesMatch() {
        validator.checkForMatchingHypervisorTypesIf(true, HypervisorType.KVM, HypervisorType.KVM);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void checkForMatchingHypervisorTypesIfRejectsMismatch() {
        validator.checkForMatchingHypervisorTypesIf(true, HypervisorType.KVM, HypervisorType.XenServer);
    }

    // ------------------------------------------------------------------
    // checkForVMSnapshots
    // ------------------------------------------------------------------

    @Test
    public void checkForVMSnapshotsAllowsAttachWhenNoSnapshots() {
        Mockito.when(vmSnapshotDao.findByVm(VM_ID)).thenReturn(Collections.emptyList());
        validator.checkForVMSnapshots(VM_ID, vm);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void checkForVMSnapshotsRejectsAttachWhenSnapshotsPresent() {
        List<VMSnapshotVO> snaps = new ArrayList<>();
        snaps.add(Mockito.mock(VMSnapshotVO.class));
        Mockito.when(vmSnapshotDao.findByVm(VM_ID)).thenReturn(snaps);
        validator.checkForVMSnapshots(VM_ID, vm);
    }

    // ------------------------------------------------------------------
    // excludeLocalStorageIfNeeded
    // ------------------------------------------------------------------

    @Test
    public void excludeLocalStorageIfNeededAllowsWhenZoneAllowsLocalStorage() {
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(dataCenter);
        Mockito.when(dataCenter.isLocalStorageEnabled()).thenReturn(true);
        validator.excludeLocalStorageIfNeeded(volumeToAttach);
        // Disk offering should never be queried because the zone allows local storage.
        Mockito.verify(diskOfferingDao, Mockito.never()).findById(Mockito.anyLong());
    }

    @Test
    public void excludeLocalStorageIfNeededAllowsWhenOfferingNotLocal() {
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(dataCenter);
        Mockito.when(dataCenter.isLocalStorageEnabled()).thenReturn(false);
        Mockito.when(diskOfferingDao.findById(DISK_OFFERING_ID)).thenReturn(diskOffering);
        Mockito.when(diskOffering.isUseLocalStorage()).thenReturn(false);
        validator.excludeLocalStorageIfNeeded(volumeToAttach);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void excludeLocalStorageIfNeededRejectsLocalOfferingInNonLocalZone() {
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(dataCenter);
        Mockito.when(dataCenter.isLocalStorageEnabled()).thenReturn(false);
        Mockito.when(diskOfferingDao.findById(DISK_OFFERING_ID)).thenReturn(diskOffering);
        Mockito.when(diskOffering.isUseLocalStorage()).thenReturn(true);
        Mockito.when(diskOffering.getName()).thenReturn("local-only-offering");
        validator.excludeLocalStorageIfNeeded(volumeToAttach);
    }

    // ------------------------------------------------------------------
    // validateRootVolumeDetachAttach
    // ------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void validateRootVolumeDetachAttachRejectsUnsupportedHypervisor() {
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.Hyperv);
        validator.validateRootVolumeDetachAttach(volume, vm);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateRootVolumeDetachAttachRejectsRunningVm() {
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(vm.getState()).thenReturn(State.Running);
        validator.validateRootVolumeDetachAttach(volume, vm);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateRootVolumeDetachAttachRejectsManagedPool() {
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(vm.getState()).thenReturn(State.Stopped);
        Mockito.when(volume.getPoolId()).thenReturn(POOL_ID);
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(storagePool);
        Mockito.when(storagePool.isManaged()).thenReturn(true);
        validator.validateRootVolumeDetachAttach(volume, vm);
    }

    @Test
    public void validateRootVolumeDetachAttachAllowsStoppedVmWithoutPool() {
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(vm.getState()).thenReturn(State.Stopped);
        Mockito.when(volume.getPoolId()).thenReturn(null);
        validator.validateRootVolumeDetachAttach(volume, vm);
    }

    @Test
    public void validateRootVolumeDetachAttachAllowsStoppedVmOnUnmanagedPool() {
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(vm.getState()).thenReturn(State.Stopped);
        Mockito.when(volume.getPoolId()).thenReturn(POOL_ID);
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(storagePool);
        Mockito.when(storagePool.isManaged()).thenReturn(false);
        validator.validateRootVolumeDetachAttach(volume, vm);
    }

    // ------------------------------------------------------------------
    // checkDeviceId
    // ------------------------------------------------------------------

    @Test
    public void checkDeviceIdNoopWhenDeviceIdIsNull() {
        validator.checkDeviceId(null, volumeToAttach, vm);
        // No DAO interactions should happen.
        Mockito.verify(volumeDao, Mockito.never()).findById(Mockito.anyLong());
        Mockito.verify(volumeDao, Mockito.never()).findByInstanceAndDeviceId(Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    public void checkDeviceIdNoopWhenDeviceIdIsNotZero() {
        validator.checkDeviceId(1L, volumeToAttach, vm);
        Mockito.verify(volumeDao, Mockito.never()).findById(Mockito.anyLong());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void checkDeviceIdRejectsWhenVmAlreadyHasRootVolume() {
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(vm.getState()).thenReturn(State.Stopped);
        Mockito.when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(volume.getPoolId()).thenReturn(null);
        List<VolumeVO> existing = new ArrayList<>();
        existing.add(Mockito.mock(VolumeVO.class));
        Mockito.when(volumeDao.findByInstanceAndDeviceId(VM_ID, 0)).thenReturn(existing);
        validator.checkDeviceId(0L, volumeToAttach, vm);
    }

    @Test
    public void checkDeviceIdAllowsWhenNoRootVolumeAndRootValidationPasses() {
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(vm.getState()).thenReturn(State.Stopped);
        Mockito.when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(volume.getPoolId()).thenReturn(null);
        Mockito.when(volumeDao.findByInstanceAndDeviceId(VM_ID, 0)).thenReturn(Collections.emptyList());
        validator.checkDeviceId(0L, volumeToAttach, vm);
    }

    // ------------------------------------------------------------------
    // getRequiredPrimaryStorageSizeForVolumeAttach
    // ------------------------------------------------------------------

    @Test
    public void getRequiredPrimaryStorageSizeReturnsZeroWhenTagsEmpty() {
        Long result = validator.getRequiredPrimaryStorageSizeForVolumeAttach(Collections.emptyList(), volumeToAttach);
        assertEquals(0L, (long) result);
    }

    @Test
    public void getRequiredPrimaryStorageSizeReturnsZeroWhenVolumeAllocated() {
        Mockito.when(volumeToAttach.getState()).thenReturn(Volume.State.Allocated);
        Long result = validator.getRequiredPrimaryStorageSizeForVolumeAttach(List.of("tag1"), volumeToAttach);
        assertEquals(0L, (long) result);
    }

    @Test
    public void getRequiredPrimaryStorageSizeReturnsZeroWhenVolumeReady() {
        Mockito.when(volumeToAttach.getState()).thenReturn(Volume.State.Ready);
        Long result = validator.getRequiredPrimaryStorageSizeForVolumeAttach(List.of("tag1"), volumeToAttach);
        assertEquals(0L, (long) result);
    }

    @Test
    public void getRequiredPrimaryStorageSizeReturnsVolumeSizeWhenUploaded() {
        Mockito.when(volumeToAttach.getState()).thenReturn(Volume.State.Uploaded);
        Mockito.when(volumeToAttach.getSize()).thenReturn(4096L);
        Long result = validator.getRequiredPrimaryStorageSizeForVolumeAttach(List.of("tag1"), volumeToAttach);
        assertEquals(4096L, (long) result);
    }

    // ------------------------------------------------------------------
    // checkForBackups
    // ------------------------------------------------------------------

    @Test
    public void checkForBackupsNoopWhenVmHasNoBackupOffering() {
        Mockito.when(vm.getBackupOfferingId()).thenReturn(null);
        validator.checkForBackups(vm, true);
    }

    @Test
    public void checkForBackupsNoopWhenVmHasNoBackupVolumes() {
        Mockito.when(vm.getBackupOfferingId()).thenReturn(1L);
        Mockito.when(vm.getBackupVolumeList()).thenReturn(Collections.emptyList());
        validator.checkForBackups(vm, true);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void checkForBackupsRejectsAttachWhenBackupsPresent() {
        Mockito.when(vm.getBackupOfferingId()).thenReturn(1L);
        // Non-empty backup volume list defeats the empty-check short circuit; with the
        // (default-off) global config in place, the throw branch fires.
        Mockito.when(vm.getBackupVolumeList()).thenReturn(List.of(Mockito.mock(Backup.VolumeInfo.class)));
        validator.checkForBackups(vm, true);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void checkForBackupsRejectsDetachWhenBackupsPresent() {
        Mockito.when(vm.getBackupOfferingId()).thenReturn(1L);
        Mockito.when(vm.getBackupVolumeList()).thenReturn(List.of(Mockito.mock(Backup.VolumeInfo.class)));
        validator.checkForBackups(vm, false);
    }
}
