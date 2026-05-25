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
import java.util.List;
import java.util.UUID;

import org.apache.cloudstack.api.command.user.volume.CreateVolumeCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.resourcelimit.ReservationHelper;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.UUIDManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class VolumeCreateServiceImplTest {

    @Spy
    @InjectMocks
    private VolumeCreateServiceImpl service = new VolumeCreateServiceImpl();

    @Mock private VolumeDao volsDao;
    @Mock private SnapshotDao snapshotDao;
    @Mock private DiskOfferingDao diskOfferingDao;
    @Mock private DataCenterDao dcDao;
    @Mock private UserVmDao userVmDao;
    @Mock private PrimaryDataStoreDao storagePoolDao;
    @Mock private DiskOfferingDetailsDao diskOfferingDetailsDao;
    @Mock private SnapshotDataStoreDao snapshotDataStoreDao;
    @Mock private VolumeDetailsDao volsDetailsDao;
    @Mock private AccountManager accountMgr;
    @Mock private ConfigurationManager configMgr;
    @Mock private ResourceLimitService resourceLimitMgr;
    @Mock private UUIDManager uuidMgr;
    @Mock private VolumeOrchestrationService volumeMgr;
    @Mock private DataStoreManager dataStoreMgr;
    @Mock private VolumeDataFactory volFactory;
    @Mock private VolumeService volService;
    @Mock private VolumeAttachService volumeAttachService;
    @Mock private DiskOfferingCompatibilityService diskOfferingCompatibilityService;

    private AccountVO caller;
    private AccountVO owner;

    @Before
    public void setUp() {
        UserVO user = new UserVO(1, "testuser", "password", "first", "last", "email", "tz",
                UUID.randomUUID().toString(), User.Source.UNKNOWN);
        caller = new AccountVO("admin", 1L, "domain", Account.Type.ADMIN, UUID.randomUUID().toString());
        owner = new AccountVO("owner", 1L, "domain", Account.Type.NORMAL, UUID.randomUUID().toString());
        CallContext.register(user, caller);
    }

    @After
    public void tearDown() {
        CallContext.unregisterAll();
    }

    // ---- validateVolumeSizeInBytes boundary tests ----

    @Test(expected = InvalidParameterValueException.class)
    public void validateVolumeSizeInBytesRejectsNegative() {
        service.validateVolumeSizeInBytes(-1L);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateVolumeSizeInBytesRejectsUnderOneGB() {
        service.validateVolumeSizeInBytes(1024L * 1024L * 1024L - 1);
    }

    @Test
    public void validateVolumeSizeInBytesAcceptsZero() {
        Assert.assertTrue(service.validateVolumeSizeInBytes(0L));
    }

    @Test
    public void validateVolumeSizeInBytesAcceptsExactlyOneGB() {
        Assert.assertTrue(service.validateVolumeSizeInBytes(1024L * 1024L * 1024L));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateVolumeSizeInBytesRejectsOverMax() {
        // MaxVolumeSize default = 2000 GB; use 2001 GB in bytes to exceed it
        long overMax = 2001L * 1024L * 1024L * 1024L;
        service.validateVolumeSizeInBytes(overMax);
    }

    // ---- validateCustomDiskOfferingSizeRange boundary tests ----

    @Test(expected = InvalidParameterValueException.class)
    public void validateCustomDiskOfferingSizeRangeRejectsBelowMin() {
        // CustomDiskOfferingMinSize default = 1 GB, so 0 is out of range
        service.validateCustomDiskOfferingSizeRange(0L);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateCustomDiskOfferingSizeRangeRejectsAboveMax() {
        // CustomDiskOfferingMaxSize default = 1024 GB, so 1025 is out of range
        service.validateCustomDiskOfferingSizeRange(1025L);
    }

    // ---- getVolumeNameFromCommand tests ----

    @Test
    public void getVolumeNameFromCommandReturnsUserSpecifiedName() {
        CreateVolumeCmd cmd = Mockito.mock(CreateVolumeCmd.class);
        Mockito.when(cmd.getVolumeName()).thenReturn("my-volume");
        Assert.assertEquals("my-volume", service.getVolumeNameFromCommand(cmd));
    }

    @Test
    public void getVolumeNameFromCommandGeneratesRandomWhenNull() {
        CreateVolumeCmd cmd = Mockito.mock(CreateVolumeCmd.class);
        Mockito.when(cmd.getVolumeName()).thenReturn(null);
        String result = service.getVolumeNameFromCommand(cmd);
        Assert.assertNotNull(result);
        Assert.assertTrue(result.length() > 0);
    }

    @Test
    public void getVolumeNameFromCommandGeneratesRandomWhenBlank() {
        CreateVolumeCmd cmd = Mockito.mock(CreateVolumeCmd.class);
        Mockito.when(cmd.getVolumeName()).thenReturn("   ");
        String result = service.getVolumeNameFromCommand(cmd);
        Assert.assertNotNull(result);
        Assert.assertTrue(result.length() > 0);
    }

    // ---- allocVolume error paths ----

    @Test(expected = InvalidParameterValueException.class)
    public void allocVolumeRejectsWhenSnapshotAndDiskOfferingBothNull() throws ResourceAllocationException {
        CreateVolumeCmd cmd = Mockito.mock(CreateVolumeCmd.class);
        Mockito.when(cmd.getEntityOwnerId()).thenReturn(1L);
        Mockito.when(accountMgr.getActiveAccountById(1L)).thenReturn(owner);
        Mockito.when(cmd.getDisplayVolume()).thenReturn(null);
        Mockito.when(cmd.getSnapshotId()).thenReturn(null);
        Mockito.when(cmd.getDiskOfferingId()).thenReturn(null);

        service.allocVolume(cmd);
    }

    @Test(expected = PermissionDeniedException.class)
    public void allocVolumeRejectsNonRootDisplayVolumeOverride() throws ResourceAllocationException {
        // Use a non-root caller
        AccountVO nonRootCaller = new AccountVO("user", 1L, "domain", Account.Type.NORMAL, UUID.randomUUID().toString());
        UserVO user2 = new UserVO(2, "u2", "pass", "f", "l", "e", "tz", UUID.randomUUID().toString(), User.Source.UNKNOWN);
        CallContext.unregisterAll();
        CallContext.register(user2, nonRootCaller);

        CreateVolumeCmd cmd = Mockito.mock(CreateVolumeCmd.class);
        Mockito.when(cmd.getEntityOwnerId()).thenReturn(1L);
        Mockito.when(accountMgr.getActiveAccountById(1L)).thenReturn(owner);
        Mockito.when(cmd.getDisplayVolume()).thenReturn(false);
        // isRootAdmin returns false for non-root account
        Mockito.when(accountMgr.isRootAdmin(nonRootCaller.getId())).thenReturn(false);

        service.allocVolume(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void allocVolumeRejectsCustomDiskOfferingWithoutSize() throws ResourceAllocationException {
        CreateVolumeCmd cmd = Mockito.mock(CreateVolumeCmd.class);
        Mockito.when(cmd.getEntityOwnerId()).thenReturn(1L);
        Mockito.when(accountMgr.getActiveAccountById(1L)).thenReturn(owner);
        Mockito.when(cmd.getDisplayVolume()).thenReturn(null);
        Long diskOfferingId = 100L;
        Mockito.when(cmd.getDiskOfferingId()).thenReturn(diskOfferingId);
        Mockito.when(cmd.getSnapshotId()).thenReturn(null);
        Mockito.when(cmd.getSize()).thenReturn(null);

        DiskOfferingVO diskOffering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOffering.getRemoved()).thenReturn(null);
        Mockito.when(diskOffering.isComputeOnly()).thenReturn(false);
        Mockito.when(diskOffering.isCustomized()).thenReturn(true);
        Mockito.when(diskOfferingDao.findById(diskOfferingId)).thenReturn(diskOffering);

        service.allocVolume(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void allocVolumeRejectsNonCustomOfferingWithSize() throws ResourceAllocationException {
        CreateVolumeCmd cmd = Mockito.mock(CreateVolumeCmd.class);
        Mockito.when(cmd.getEntityOwnerId()).thenReturn(1L);
        Mockito.when(accountMgr.getActiveAccountById(1L)).thenReturn(owner);
        Mockito.when(cmd.getDisplayVolume()).thenReturn(null);
        Long diskOfferingId = 100L;
        Mockito.when(cmd.getDiskOfferingId()).thenReturn(diskOfferingId);
        Mockito.when(cmd.getSnapshotId()).thenReturn(null);
        Mockito.when(cmd.getSize()).thenReturn(10L);

        DiskOfferingVO diskOffering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOffering.getRemoved()).thenReturn(null);
        Mockito.when(diskOffering.isComputeOnly()).thenReturn(false);
        Mockito.when(diskOffering.isCustomized()).thenReturn(false);
        Mockito.when(diskOfferingDao.findById(diskOfferingId)).thenReturn(diskOffering);

        service.allocVolume(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void allocVolumeRejectsSnapshotFromDifferentZoneVm() throws ResourceAllocationException {
        Long snapshotId = 42L;
        Long vmId = 99L;
        Long zoneId = 1L;
        Long vmZoneId = 2L;

        CreateVolumeCmd cmd = Mockito.mock(CreateVolumeCmd.class);
        Mockito.when(cmd.getEntityOwnerId()).thenReturn(1L);
        Mockito.when(accountMgr.getActiveAccountById(1L)).thenReturn(owner);
        Mockito.when(cmd.getDisplayVolume()).thenReturn(null);
        Mockito.when(cmd.getDiskOfferingId()).thenReturn(null);
        Mockito.when(cmd.getSnapshotId()).thenReturn(snapshotId);
        Mockito.when(cmd.getZoneId()).thenReturn(zoneId);
        Mockito.when(cmd.getVirtualMachineId()).thenReturn(vmId);

        SnapshotVO snapshot = Mockito.mock(SnapshotVO.class);
        Mockito.when(snapshot.getState()).thenReturn(Snapshot.State.BackedUp);
        Mockito.when(snapshot.getVolumeId()).thenReturn(10L);
        Mockito.when(snapshotDao.findById(snapshotId)).thenReturn(snapshot);
        Mockito.when(snapshotDataStoreDao.findOneBySnapshotAndDatastoreRole(snapshotId, DataStoreRole.Primary)).thenReturn(null);

        VolumeVO parentVolume = Mockito.mock(VolumeVO.class);
        Mockito.when(parentVolume.getPassphraseId()).thenReturn(null);
        Mockito.when(volsDao.findByIdIncludingRemoved(10L)).thenReturn(parentVolume);

        // Snapshot has disk offering
        Long diskOfferingId = 50L;
        Mockito.when(snapshot.getDiskOfferingId()).thenReturn(diskOfferingId);
        DiskOfferingVO diskOffering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOfferingDao.findById(diskOfferingId)).thenReturn(diskOffering);

        // VM is in a different zone
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        Mockito.when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        Mockito.when(vm.getDataCenterId()).thenReturn(vmZoneId); // different zone
        Mockito.when(userVmDao.findById(vmId)).thenReturn(vm);

        service.allocVolume(cmd);
    }

    // ---- allocVolume happy paths ----

    @Test
    public void allocVolumeRejectsPowerFlexSnapshot() throws ResourceAllocationException {
        Long snapshotId = 42L;
        Long storeId = 77L;

        CreateVolumeCmd cmd = Mockito.mock(CreateVolumeCmd.class);
        Mockito.when(cmd.getEntityOwnerId()).thenReturn(1L);
        Mockito.when(accountMgr.getActiveAccountById(1L)).thenReturn(owner);
        Mockito.when(cmd.getDisplayVolume()).thenReturn(null);
        Mockito.when(cmd.getDiskOfferingId()).thenReturn(null);
        Mockito.when(cmd.getSnapshotId()).thenReturn(snapshotId);

        SnapshotVO snapshot = Mockito.mock(SnapshotVO.class);
        Mockito.when(snapshot.getState()).thenReturn(Snapshot.State.BackedUp);
        Mockito.when(snapshotDao.findById(snapshotId)).thenReturn(snapshot);

        SnapshotDataStoreVO storeVO = Mockito.mock(SnapshotDataStoreVO.class);
        Mockito.when(storeVO.getDataStoreId()).thenReturn(storeId);
        Mockito.when(snapshotDataStoreDao.findOneBySnapshotAndDatastoreRole(snapshotId, DataStoreRole.Primary)).thenReturn(storeVO);

        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.PowerFlex);
        Mockito.when(storagePoolDao.findById(storeId)).thenReturn(pool);

        try {
            service.allocVolume(cmd);
            Assert.fail("Expected InvalidParameterValueException for PowerFlex snapshot");
        } catch (InvalidParameterValueException e) {
            Assert.assertTrue(e.getMessage().contains("PowerFlex"));
        }
    }

    @Test
    public void allocVolumeRejectsEncryptedVolumeSnapshot() throws ResourceAllocationException {
        Long snapshotId = 43L;
        Long volumeId = 11L;

        CreateVolumeCmd cmd = Mockito.mock(CreateVolumeCmd.class);
        Mockito.when(cmd.getEntityOwnerId()).thenReturn(1L);
        Mockito.when(accountMgr.getActiveAccountById(1L)).thenReturn(owner);
        Mockito.when(cmd.getDisplayVolume()).thenReturn(null);
        Mockito.when(cmd.getDiskOfferingId()).thenReturn(null);
        Mockito.when(cmd.getSnapshotId()).thenReturn(snapshotId);

        SnapshotVO snapshot = Mockito.mock(SnapshotVO.class);
        Mockito.when(snapshot.getState()).thenReturn(Snapshot.State.BackedUp);
        Mockito.when(snapshot.getVolumeId()).thenReturn(volumeId);
        Mockito.when(snapshotDao.findById(snapshotId)).thenReturn(snapshot);
        Mockito.when(snapshotDataStoreDao.findOneBySnapshotAndDatastoreRole(snapshotId, DataStoreRole.Primary)).thenReturn(null);

        VolumeVO parentVolume = Mockito.mock(VolumeVO.class);
        Mockito.when(parentVolume.getPassphraseId()).thenReturn(999L); // encrypted
        Mockito.when(volsDao.findByIdIncludingRemoved(volumeId)).thenReturn(parentVolume);

        try {
            service.allocVolume(cmd);
            Assert.fail("Expected UnsupportedOperationException for encrypted snapshot");
        } catch (UnsupportedOperationException e) {
            Assert.assertTrue(e.getMessage().contains("encrypted"));
        }
    }

    @Test
    public void allocVolumeFromSnapshotDefaultsZoneFromSnapshotParent() throws ResourceAllocationException {
        Long snapshotId = 44L;
        Long volumeId = 12L;
        Long diskOfferingId = 55L;
        Long expectedZoneId = 42L;

        CreateVolumeCmd cmd = Mockito.mock(CreateVolumeCmd.class);
        Mockito.when(cmd.getEntityOwnerId()).thenReturn(1L);
        Mockito.when(accountMgr.getActiveAccountById(1L)).thenReturn(owner);
        Mockito.when(cmd.getDisplayVolume()).thenReturn(null);
        Mockito.when(cmd.getDiskOfferingId()).thenReturn(null);
        Mockito.when(cmd.getSnapshotId()).thenReturn(snapshotId);
        Mockito.when(cmd.getZoneId()).thenReturn(null); // no zone supplied
        Mockito.when(cmd.getVirtualMachineId()).thenReturn(null);
        Mockito.when(cmd.getCustomId()).thenReturn(null);

        SnapshotVO snapshot = Mockito.mock(SnapshotVO.class);
        Mockito.when(snapshot.getState()).thenReturn(Snapshot.State.BackedUp);
        Mockito.when(snapshot.getVolumeId()).thenReturn(volumeId);
        Mockito.when(snapshot.getDiskOfferingId()).thenReturn(diskOfferingId);
        Mockito.when(snapshot.getMinIops()).thenReturn(0L);
        Mockito.when(snapshot.getMaxIops()).thenReturn(0L);
        Mockito.when(snapshot.getSize()).thenReturn(1024L * 1024L * 1024L);
        Mockito.when(snapshotDao.findById(snapshotId)).thenReturn(snapshot);
        Mockito.when(snapshotDataStoreDao.findOneBySnapshotAndDatastoreRole(snapshotId, DataStoreRole.Primary)).thenReturn(null);

        VolumeVO parentVolume = Mockito.mock(VolumeVO.class);
        Mockito.when(parentVolume.getPassphraseId()).thenReturn(null);
        Mockito.when(parentVolume.getDataCenterId()).thenReturn(expectedZoneId);
        Mockito.when(volsDao.findByIdIncludingRemoved(volumeId)).thenReturn(parentVolume);

        DiskOfferingVO diskOffering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOffering.getProvisioningType()).thenReturn(Storage.ProvisioningType.THIN);
        Mockito.when(diskOfferingDao.findById(diskOfferingId)).thenReturn(diskOffering);

        List<String> tags = new ArrayList<>();
        Mockito.when(resourceLimitMgr.getResourceLimitStorageTagsForResourceCountOperation(Mockito.any(), Mockito.any())).thenReturn(tags);

        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getAllocationState()).thenReturn(com.cloud.org.Grouping.AllocationState.Enabled);
        Mockito.when(zone.isLocalStorageEnabled()).thenReturn(true);
        Mockito.when(dcDao.findById(expectedZoneId)).thenReturn(zone);

        Mockito.when(uuidMgr.generateUuid(Mockito.eq(Volume.class), Mockito.any())).thenReturn(UUID.randomUUID().toString());

        VolumeVO persistedVolume = Mockito.mock(VolumeVO.class);
        Mockito.when(persistedVolume.getId()).thenReturn(100L);
        Mockito.when(persistedVolume.getUuid()).thenReturn(UUID.randomUUID().toString());
        Mockito.when(persistedVolume.getDiskOfferingId()).thenReturn(diskOfferingId);

        try (MockedStatic<Transaction> txMock = Mockito.mockStatic(Transaction.class);
             MockedStatic<UsageEventUtils> usageMock = Mockito.mockStatic(UsageEventUtils.class);
             MockedStatic<ReservationHelper> reservationMock = Mockito.mockStatic(ReservationHelper.class)) {
            txMock.when(() -> Transaction.execute(Mockito.any(TransactionCallback.class))).thenAnswer(inv -> {
                TransactionCallback<?> cb = inv.getArgument(0);
                return cb.doInTransaction(null);
            });
            Mockito.when(volsDao.persist(Mockito.any(VolumeVO.class))).thenReturn(persistedVolume);

            VolumeVO result = service.allocVolume(cmd);

            // Verify that zone was taken from parent volume (called twice: once in snapshot
            // disk-offering access check, once in zone existence check)
            Mockito.verify(dcDao, Mockito.times(2)).findById(expectedZoneId);
            Assert.assertNotNull(result);
        }
    }

    // ---- createVolumeOnStoragePool error paths ----

    @Test
    public void createVolumeOnStoragePoolRejectsMissingPool() {
        Long volumeId = 200L;
        Long storageId = 300L;

        CreateVolumeCmd cmd = Mockito.mock(CreateVolumeCmd.class);
        Mockito.when(cmd.getEntityId()).thenReturn(volumeId);
        Mockito.when(cmd.getSnapshotId()).thenReturn(null);
        Mockito.when(cmd.getStorageId()).thenReturn(storageId);
        Mockito.when(cmd.getDisplayVolume()).thenReturn(null);

        VolumeVO volume = Mockito.mock(VolumeVO.class);
        Mockito.when(volume.getAccountId()).thenReturn(1L);
        Mockito.when(volume.getDiskOfferingId()).thenReturn(55L);
        Mockito.when(volsDao.findById(volumeId)).thenReturn(volume);
        // dataStoreMgr returns null — pool not found
        Mockito.when(dataStoreMgr.getDataStore(storageId, DataStoreRole.Primary)).thenReturn(null);

        VolumeInfo volInfo = Mockito.mock(VolumeInfo.class);
        Mockito.when(volFactory.getVolume(volumeId)).thenReturn(volInfo);
        Mockito.when(diskOfferingDao.findByIdIncludingRemoved(55L)).thenReturn(Mockito.mock(DiskOfferingVO.class));

        try (MockedStatic<ReservationHelper> rMock = Mockito.mockStatic(ReservationHelper.class)) {
            service.createVolume(cmd);
            Assert.fail("Expected CloudRuntimeException for missing pool");
        } catch (com.cloud.utils.exception.CloudRuntimeException e) {
            Assert.assertTrue(e.getCause() instanceof InvalidParameterValueException);
            Assert.assertTrue(e.getCause().getMessage().contains("Failed to find the storage pool"));
        }
    }

    @Test
    public void createVolumeOnStoragePoolRejectsDownPool() {
        Long volumeId = 201L;
        Long storageId = 301L;

        CreateVolumeCmd cmd = Mockito.mock(CreateVolumeCmd.class);
        Mockito.when(cmd.getEntityId()).thenReturn(volumeId);
        Mockito.when(cmd.getSnapshotId()).thenReturn(null);
        Mockito.when(cmd.getStorageId()).thenReturn(storageId);
        Mockito.when(cmd.getDisplayVolume()).thenReturn(null);

        VolumeVO volume = Mockito.mock(VolumeVO.class);
        Mockito.when(volume.getUuid()).thenReturn("vol-uuid");
        Mockito.when(volume.getAccountId()).thenReturn(1L);
        Mockito.when(volume.getDiskOfferingId()).thenReturn(55L);
        Mockito.when(volsDao.findById(volumeId)).thenReturn(volume);

        // PrimaryDataStore implements both DataStore and StoragePool
        org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore pool =
                Mockito.mock(org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore.class);
        Mockito.when(pool.getStatus()).thenReturn(StoragePoolStatus.Maintenance);
        Mockito.when(pool.getName()).thenReturn("pool-name");
        Mockito.when(dataStoreMgr.getDataStore(storageId, DataStoreRole.Primary)).thenReturn(pool);

        VolumeInfo volInfo = Mockito.mock(VolumeInfo.class);
        Mockito.when(volFactory.getVolume(volumeId)).thenReturn(volInfo);
        Mockito.when(diskOfferingDao.findByIdIncludingRemoved(55L)).thenReturn(Mockito.mock(DiskOfferingVO.class));

        try (MockedStatic<ReservationHelper> rMock = Mockito.mockStatic(ReservationHelper.class)) {
            service.createVolume(cmd);
            Assert.fail("Expected CloudRuntimeException for down pool");
        } catch (com.cloud.utils.exception.CloudRuntimeException e) {
            Assert.assertTrue(e.getCause() instanceof InvalidParameterValueException);
            Assert.assertTrue(e.getCause().getMessage().contains("not in Up state"));
        }
    }

    @Test
    public void createVolumeFailurePathTriggersStateTransitAndDecrement() {
        Long volumeId = 202L;
        Long storageId = 302L;

        CreateVolumeCmd cmd = Mockito.mock(CreateVolumeCmd.class);
        Mockito.when(cmd.getEntityId()).thenReturn(volumeId);
        Mockito.when(cmd.getSnapshotId()).thenReturn(null);
        Mockito.when(cmd.getStorageId()).thenReturn(storageId);
        Mockito.when(cmd.getDisplayVolume()).thenReturn(true);

        VolumeVO volume = Mockito.mock(VolumeVO.class);
        Mockito.when(volume.getAccountId()).thenReturn(1L);
        Mockito.when(volume.getSize()).thenReturn(1024L * 1024L * 1024L);
        Mockito.when(volume.getDiskOfferingId()).thenReturn(55L);
        Mockito.when(volsDao.findById(volumeId)).thenReturn(volume);

        // Pool not found — causes InvalidParameterValueException which is caught
        Mockito.when(dataStoreMgr.getDataStore(storageId, DataStoreRole.Primary)).thenReturn(null);

        VolumeInfo volInfo = Mockito.mock(VolumeInfo.class);
        Mockito.when(volFactory.getVolume(volumeId)).thenReturn(volInfo);

        DiskOfferingVO diskOffering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOfferingDao.findByIdIncludingRemoved(55L)).thenReturn(diskOffering);

        try {
            service.createVolume(cmd);
            Assert.fail("Expected CloudRuntimeException");
        } catch (com.cloud.utils.exception.CloudRuntimeException e) {
            // expected — verify side effects
        }

        Mockito.verify(volInfo).stateTransit(Volume.Event.DestroyRequested);
        Mockito.verify(resourceLimitMgr).decrementVolumeResourceCount(
                Mockito.eq(1L), Mockito.eq(true),
                Mockito.anyLong(), Mockito.any(DiskOfferingVO.class));
    }
}
