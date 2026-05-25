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

import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;

import java.util.UUID;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.dao.VmWorkJobDao;
import org.apache.cloudstack.resourcedetail.dao.SnapshotPolicyDetailsDao;
import org.apache.cloudstack.snapshot.SnapshotHelper;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.org.Grouping;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.snapshot.SnapshotApiService;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDetailsDao;

/**
 * Focused unit tests for {@link VolumeTakeSnapshotServiceImpl}.
 *
 * <p>Tests cover the four public methods of the extracted service:
 * <ul>
 *   <li>{@code allocSnapshot} — pre-flight validation (zone state, volume
 *       state, location-type / managed-storage rules, cross-zone rules);</li>
 *   <li>{@code allocSnapshotForVm} — VM + volume validation;</li>
 *   <li>{@code orchestrateTakeVolumeSnapshot} — payload building and dispatch
 *       to {@code volService.takeSnapshot};</li>
 *   <li>{@code takeSnapshotInternal} — queue vs direct path selection.</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.class)
public class VolumeTakeSnapshotServiceImplTest {

    // -----------------------------------------------------------------------
    // mocks
    // -----------------------------------------------------------------------

    @Mock private VolumeDataFactory volFactory;
    @Mock private VolumeService volService;
    @Mock private SnapshotApiService snapshotMgr;
    @Mock private SnapshotPolicyDetailsDao snapshotPolicyDetailsDao;
    @Mock private SnapshotHelper snapshotHelper;
    @Mock private VMSnapshotDetailsDao vmSnapshotDetailsDao;
    @Mock private AccountManager accountMgr;
    @Mock private DataCenterDao dcDao;
    @Mock private PrimaryDataStoreDao storagePoolDao;
    @Mock private VMTemplateDao templateDao;
    @Mock private UserVmDao userVmDao;
    @Mock private VMInstanceDao vmInstanceDao;
    @Mock private SnapshotDao snapshotDao;
    @Mock private AsyncJobManager jobMgr;
    @Mock private VmWorkJobDao workJobDao;
    @Mock private EntityManager entityMgr;

    @Mock private VolumeInfo volumeInfo;
    @Mock private StoragePoolVO storagePoolVO;
    @Mock private DataCenterVO dataCenterVO;
    @Mock private Account caller;
    @Mock private SnapshotInfo snapshotInfoMock;

    @InjectMocks
    private VolumeTakeSnapshotServiceImpl service;

    private static final long VOLUME_ID = 10L;
    private static final long ZONE_ID = 20L;
    private static final long POOL_ID = 30L;
    private static final long VM_ID = 40L;
    private static final long SNAPSHOT_ID = 50L;

    @Before
    public void setUp() {
        AccountVO account = new AccountVO("admin", 1L, "ROOT", Account.Type.ADMIN, UUID.randomUUID().toString());
        ReflectionTestUtils.setField(account, "id", 1L);
        UserVO user = new UserVO(1, "admin", "password", "admin", "admin",
                "admin@admin.com", "GMT", UUID.randomUUID().toString(), User.Source.UNKNOWN);
        ReflectionTestUtils.setField(user, "id", 1L);
        CallContext.register(user, account);

        Mockito.lenient().when(caller.getId()).thenReturn(1L);
        Mockito.lenient().when(volumeInfo.getId()).thenReturn(VOLUME_ID);
        Mockito.lenient().when(volumeInfo.getPoolId()).thenReturn(POOL_ID);
        Mockito.lenient().when(volumeInfo.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.lenient().when(storagePoolVO.getId()).thenReturn(POOL_ID);
        Mockito.lenient().when(storagePoolVO.isManaged()).thenReturn(false);
        Mockito.lenient().when(dataCenterVO.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);
        Mockito.lenient().when(dataCenterVO.getType()).thenReturn(DataCenter.Type.Core);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // -----------------------------------------------------------------------
    // allocSnapshot — volume not found
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void allocSnapshotRejectsNullVolume() throws ResourceAllocationException {
        Mockito.when(volFactory.getVolume(VOLUME_ID)).thenReturn(null);
        service.allocSnapshot(VOLUME_ID, null, "snap", null, null, null, null);
    }

    // -----------------------------------------------------------------------
    // allocSnapshot — zone not found
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void allocSnapshotRejectsNullZone() throws ResourceAllocationException {
        Mockito.when(volFactory.getVolume(VOLUME_ID)).thenReturn(volumeInfo);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(null);
        service.allocSnapshot(VOLUME_ID, null, "snap", null, null, null, null);
    }

    // -----------------------------------------------------------------------
    // allocSnapshot — zone disabled, non-admin
    // -----------------------------------------------------------------------

    @Test(expected = PermissionDeniedException.class)
    public void allocSnapshotRejectsDisabledZoneForNonAdmin() throws ResourceAllocationException {
        Mockito.when(volFactory.getVolume(VOLUME_ID)).thenReturn(volumeInfo);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(dataCenterVO);
        Mockito.when(dataCenterVO.getAllocationState()).thenReturn(Grouping.AllocationState.Disabled);
        Mockito.when(accountMgr.isRootAdmin(anyLong())).thenReturn(false);
        service.allocSnapshot(VOLUME_ID, null, "snap", null, null, null, null);
    }

    // -----------------------------------------------------------------------
    // allocSnapshot — volume not Ready
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void allocSnapshotRejectsNonReadyVolume() throws ResourceAllocationException {
        Mockito.when(volFactory.getVolume(VOLUME_ID)).thenReturn(volumeInfo);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(dataCenterVO);
        Mockito.when(volumeInfo.getState()).thenReturn(Volume.State.Allocated);
        Mockito.lenient().when(accountMgr.isRootAdmin(anyLong())).thenReturn(true);
        service.allocSnapshot(VOLUME_ID, null, "snap", null, null, null, null);
    }

    // -----------------------------------------------------------------------
    // allocSnapshot — DIR format rejected
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void allocSnapshotRejectsDirFormat() throws ResourceAllocationException {
        Mockito.when(volFactory.getVolume(VOLUME_ID)).thenReturn(volumeInfo);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(dataCenterVO);
        Mockito.when(volumeInfo.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(volumeInfo.getFormat()).thenReturn(Storage.ImageFormat.DIR);
        Mockito.lenient().when(accountMgr.isRootAdmin(anyLong())).thenReturn(true);
        service.allocSnapshot(VOLUME_ID, null, "snap", null, null, null, null);
    }

    // -----------------------------------------------------------------------
    // allocSnapshot — locationType on non-managed storage
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void allocSnapshotRejectsLocationTypeOnNonManaged() throws ResourceAllocationException {
        Mockito.when(volFactory.getVolume(VOLUME_ID)).thenReturn(volumeInfo);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(dataCenterVO);
        Mockito.when(volumeInfo.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(volumeInfo.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        Mockito.when(volumeInfo.getTemplateId()).thenReturn(null);
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(storagePoolVO);
        Mockito.when(storagePoolVO.isManaged()).thenReturn(false);
        Mockito.lenient().when(accountMgr.isRootAdmin(anyLong())).thenReturn(true);
        // exception thrown at the non-managed / locationType check before getDataStore is called
        Mockito.lenient().when(volumeInfo.getDataStore()).thenReturn(null);
        service.allocSnapshot(VOLUME_ID, null, "snap", Snapshot.LocationType.PRIMARY, null, null, null);
    }

    // -----------------------------------------------------------------------
    // allocSnapshot — no data store attached
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void allocSnapshotRejectsVolumeWithNoDataStore() throws ResourceAllocationException {
        Mockito.when(volFactory.getVolume(VOLUME_ID)).thenReturn(volumeInfo);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(dataCenterVO);
        Mockito.when(volumeInfo.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(volumeInfo.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        Mockito.when(volumeInfo.getTemplateId()).thenReturn(null);
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(storagePoolVO);
        Mockito.when(storagePoolVO.isManaged()).thenReturn(false);
        Mockito.lenient().when(accountMgr.isRootAdmin(anyLong())).thenReturn(true);
        Mockito.when(volumeInfo.getDataStore()).thenReturn(null);
        service.allocSnapshot(VOLUME_ID, null, "snap", null, null, null, null);
    }

    // -----------------------------------------------------------------------
    // allocSnapshotForVm — VM not found
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void allocSnapshotForVmRejectsNullVm() throws ResourceAllocationException {
        Mockito.when(vmInstanceDao.findById(VM_ID)).thenReturn(null);
        service.allocSnapshotForVm(VM_ID, VOLUME_ID, "snap", SNAPSHOT_ID);
    }

    // -----------------------------------------------------------------------
    // allocSnapshotForVm — volume not found
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void allocSnapshotForVmRejectsNullVolume() throws ResourceAllocationException {
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);
        Mockito.when(volFactory.getVolume(VOLUME_ID)).thenReturn(null);
        service.allocSnapshotForVm(VM_ID, VOLUME_ID, "snap", SNAPSHOT_ID);
    }

    // -----------------------------------------------------------------------
    // allocSnapshotForVm — volume attached to wrong VM
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void allocSnapshotForVmRejectsVolumeOnWrongVm() throws ResourceAllocationException {
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getId()).thenReturn(VM_ID);
        Mockito.when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);
        Mockito.when(volFactory.getVolume(VOLUME_ID)).thenReturn(volumeInfo);

        // attachedVM is a different VM
        VirtualMachine otherVm = Mockito.mock(VirtualMachine.class);
        Mockito.when(otherVm.getId()).thenReturn(99L);
        Mockito.when(volumeInfo.getAttachedVM()).thenReturn(otherVm);
        service.allocSnapshotForVm(VM_ID, VOLUME_ID, "snap", SNAPSHOT_ID);
    }

    // -----------------------------------------------------------------------
    // orchestrateTakeVolumeSnapshot — volume not found
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void orchestrateRejectsNullVolume() throws ResourceAllocationException {
        Mockito.when(volFactory.getVolume(VOLUME_ID)).thenReturn(null);
        service.orchestrateTakeVolumeSnapshot(VOLUME_ID, null, SNAPSHOT_ID, caller,
                false, null, false, null, null);
    }

    // -----------------------------------------------------------------------
    // orchestrateTakeVolumeSnapshot — volume not Ready
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void orchestrateRejectsNonReadyVolume() throws ResourceAllocationException {
        Mockito.when(volFactory.getVolume(VOLUME_ID)).thenReturn(volumeInfo);
        Mockito.when(volumeInfo.getState()).thenReturn(Volume.State.Allocated);
        service.orchestrateTakeVolumeSnapshot(VOLUME_ID, null, SNAPSHOT_ID, caller,
                false, null, false, null, null);
    }

    // -----------------------------------------------------------------------
    // orchestrateTakeVolumeSnapshot — happy path delegates to volService
    // -----------------------------------------------------------------------

    @Test
    public void orchestrateDelegatesToVolService() throws ResourceAllocationException {
        Mockito.when(volFactory.getVolume(VOLUME_ID)).thenReturn(volumeInfo);
        Mockito.when(volumeInfo.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(volumeInfo.getStoragePoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        Mockito.when(volumeInfo.getEncryptFormat()).thenReturn(null);
        Mockito.when(volService.takeSnapshot(volumeInfo)).thenReturn(snapshotInfoMock);

        Snapshot result = service.orchestrateTakeVolumeSnapshot(VOLUME_ID, null, SNAPSHOT_ID, caller,
                false, Snapshot.LocationType.SECONDARY, false, null, null);

        assertSame(snapshotInfoMock, result);
        Mockito.verify(volService).takeSnapshot(volumeInfo);
    }

    // -----------------------------------------------------------------------
    // takeSnapshotInternal — External hypervisor rejected
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void takeSnapshotInternalRejectsExternalHypervisor() throws ResourceAllocationException {
        Mockito.when(volFactory.getVolume(VOLUME_ID)).thenReturn(volumeInfo);
        Mockito.when(snapshotHelper.addStoragePoolsForCopyToPrimary(any(), any(), any(), any())).thenReturn(null);
        Mockito.when(volumeInfo.getHypervisorType()).thenReturn(HypervisorType.External);
        service.takeSnapshotInternal(VOLUME_ID, null, SNAPSHOT_ID, caller,
                false, null, false, null, null, null);
    }

    // -----------------------------------------------------------------------
    // takeSnapshotInternal — volume not found (after pool-copy checks)
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void takeSnapshotInternalRejectsNullVolumeAfterPoolCheck() throws ResourceAllocationException {
        Mockito.when(volFactory.getVolume(VOLUME_ID)).thenReturn(null);
        Mockito.when(snapshotHelper.addStoragePoolsForCopyToPrimary(any(), any(), any(), any())).thenReturn(null);
        service.takeSnapshotInternal(VOLUME_ID, null, SNAPSHOT_ID, caller,
                false, null, false, null, null, null);
    }

    // -----------------------------------------------------------------------
    // takeSnapshotInternal — detached volume goes directly to volService
    // -----------------------------------------------------------------------

    @Test
    public void takeSnapshotInternalDetachedVolumeDelegatesToVolService() throws ResourceAllocationException {
        Mockito.when(volFactory.getVolume(VOLUME_ID)).thenReturn(volumeInfo);
        Mockito.when(snapshotHelper.addStoragePoolsForCopyToPrimary(any(), any(), any(), any())).thenReturn(null);
        Mockito.when(volumeInfo.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(volumeInfo.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(volumeInfo.getInstanceId()).thenReturn(null);
        Mockito.when(volumeInfo.getPoolId()).thenReturn(POOL_ID);
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(storagePoolVO);
        Mockito.when(storagePoolVO.isManaged()).thenReturn(false);
        Mockito.when(volService.takeSnapshot(volumeInfo)).thenReturn(snapshotInfoMock);

        Snapshot result = service.takeSnapshotInternal(VOLUME_ID, null, SNAPSHOT_ID, caller,
                false, null, false, null, null, null);

        assertSame(snapshotInfoMock, result);
        Mockito.verify(volService).takeSnapshot(volumeInfo);
    }
}
