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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.DedicatedResourceVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceState;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StorageManager;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

@RunWith(MockitoJUnitRunner.class)
public class VmMigrationValidatorImplTest {

    @Mock private HostDao hostDao;
    @Mock private ClusterDao clusterDao;
    @Mock private StorageManager storageManager;
    @Mock private ServiceOfferingDao serviceOfferingDao;
    @Mock private VMTemplateDao templateDao;
    @Mock private DedicatedResourceDao dedicatedDao;
    @Mock private AccountManager accountManager;
    @Mock private VMInstanceDao vmInstanceDao;
    @Mock private VolumeDao volumeDao;
    @Mock private VMSnapshotDao vmSnapshotDao;

    @InjectMocks
    private VmMigrationValidatorImpl validator;

    @Mock private Account callerAccount;
    private MockedStatic<CallContext> callContextMock;

    @Before
    public void setUp() {
        callContextMock = Mockito.mockStatic(CallContext.class);
        CallContext ctx = mock(CallContext.class);
        callContextMock.when(CallContext::current).thenReturn(ctx);
        lenient().when(ctx.getCallingAccount()).thenReturn(callerAccount);
    }

    @After
    public void tearDown() {
        callContextMock.close();
    }

    // ---- preVmStorageMigrationCheck ----

    @Test
    public void nonAdminCallerRejected() {
        when(accountManager.isRootAdmin(anyLong())).thenReturn(false);
        assertThrows(PermissionDeniedException.class, () -> validator.preVmStorageMigrationCheck(1L));
    }

    @Test
    public void missingVmRejected() {
        when(accountManager.isRootAdmin(anyLong())).thenReturn(true);
        when(vmInstanceDao.findById(1L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class, () -> validator.preVmStorageMigrationCheck(1L));
    }

    @Test
    public void runningVmRejected() {
        when(accountManager.isRootAdmin(anyLong())).thenReturn(true);
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getState()).thenReturn(State.Running);
        when(vmInstanceDao.findById(1L)).thenReturn(vm);
        assertThrows(InvalidParameterValueException.class, () -> validator.preVmStorageMigrationCheck(1L));
    }

    @Test
    public void stoppedUserVmWithVmSnapshotsRejected() {
        when(accountManager.isRootAdmin(anyLong())).thenReturn(true);
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getState()).thenReturn(State.Stopped);
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vmInstanceDao.findById(1L)).thenReturn(vm);
        when(vm.getId()).thenReturn(1L);
        when(volumeDao.findByInstance(1L)).thenReturn(Collections.singletonList(mock(VolumeVO.class)));
        when(vmSnapshotDao.findByVm(1L)).thenReturn(Collections.singletonList(mock(com.cloud.vm.snapshot.VMSnapshotVO.class)));
        assertThrows(InvalidParameterValueException.class, () -> validator.preVmStorageMigrationCheck(1L));
    }

    @Test
    public void stoppedUserVmKvmPasses() {
        when(accountManager.isRootAdmin(anyLong())).thenReturn(true);
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getState()).thenReturn(State.Stopped);
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vm.getId()).thenReturn(1L);
        when(vmInstanceDao.findById(1L)).thenReturn(vm);
        when(volumeDao.findByInstance(1L)).thenReturn(Collections.singletonList(mock(VolumeVO.class)));
        when(vmSnapshotDao.findByVm(1L)).thenReturn(Collections.emptyList());
        assertTrue(validator.preVmStorageMigrationCheck(1L) == vm);
    }

    // ---- checkIfDestinationPoolHasSameStorageAccessGroups ----

    @Test
    public void destPoolWithoutCommonStorageAccessRejected() {
        StoragePool destPool = mock(StoragePool.class);
        when(destPool.getName()).thenReturn("destPool");
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getHostId()).thenReturn(5L);
        HostVO host = mock(HostVO.class);
        when(host.getName()).thenReturn("srcHost");
        when(hostDao.findById(5L)).thenReturn(host);
        when(storageManager.checkIfHostAndStoragePoolHasCommonStorageAccessGroups(host, destPool)).thenReturn(false);
        assertThrows(InvalidParameterValueException.class,
                () -> validator.checkIfDestinationPoolHasSameStorageAccessGroups(destPool, vm));
    }

    @Test
    public void vmWithoutHostSkipsCheck() {
        StoragePool destPool = mock(StoragePool.class);
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getHostId()).thenReturn(null);
        validator.checkIfDestinationPoolHasSameStorageAccessGroups(destPool, vm);
    }

    // ---- checkDestinationHypervisorType ----

    @Test
    public void incompatibleHypervisorRejected() {
        StoragePool destPool = mock(StoragePool.class);
        when(destPool.getHypervisor()).thenReturn(HypervisorType.VMware);
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        assertThrows(InvalidParameterValueException.class,
                () -> validator.checkDestinationHypervisorType(destPool, vm));
    }

    @Test
    public void anyHypervisorAccepted() {
        StoragePool destPool = mock(StoragePool.class);
        when(destPool.getHypervisor()).thenReturn(HypervisorType.Any);
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        validator.checkDestinationHypervisorType(destPool, vm);
    }

    // ---- validateStorageAccessGroupsOnHosts ----

    @Test
    public void srcWithoutSagsSkipped() {
        Host src = mock(Host.class);
        Host dst = mock(Host.class);
        // No SAGs on either host — the early-return on empty src list should fire.
        validator.validateStorageAccessGroupsOnHosts(src, dst);
    }

    @Test
    public void srcWithSagsButDestWithoutRejected() {
        Host src = mock(Host.class);
        Host dst = mock(Host.class);
        when(src.getId()).thenReturn(1L);
        when(dst.getId()).thenReturn(2L);
        when(storageManager.getStorageAccessGroups(null, null, null, 1L)).thenReturn(new String[]{"sag-a"});
        when(storageManager.getStorageAccessGroups(null, null, null, 2L)).thenReturn(null);
        assertThrows(CloudRuntimeException.class, () -> validator.validateStorageAccessGroupsOnHosts(src, dst));
    }

    @Test
    public void srcSagsNotSubsetOfDestRejected() {
        Host src = mock(Host.class);
        Host dst = mock(Host.class);
        when(src.getId()).thenReturn(1L);
        when(dst.getId()).thenReturn(2L);
        when(storageManager.getStorageAccessGroups(null, null, null, 1L)).thenReturn(new String[]{"sag-a", "sag-b"});
        when(storageManager.getStorageAccessGroups(null, null, null, 2L)).thenReturn(new String[]{"sag-a"});
        assertThrows(CloudRuntimeException.class, () -> validator.validateStorageAccessGroupsOnHosts(src, dst));
    }

    @Test
    public void destSagsSupersetOfSrcAccepted() {
        Host src = mock(Host.class);
        Host dst = mock(Host.class);
        when(src.getId()).thenReturn(1L);
        when(dst.getId()).thenReturn(2L);
        when(storageManager.getStorageAccessGroups(null, null, null, 1L)).thenReturn(new String[]{"sag-a"});
        when(storageManager.getStorageAccessGroups(null, null, null, 2L)).thenReturn(new String[]{"sag-a", "sag-b"});
        validator.validateStorageAccessGroupsOnHosts(src, dst);
    }

    // ---- checkIfHostIsDedicated ----

    @Test
    public void hostWithDedicatedRecordReturnsTrue() {
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(1L);
        when(dedicatedDao.findByHostId(1L)).thenReturn(mock(DedicatedResourceVO.class));
        assertTrue(validator.checkIfHostIsDedicated(host));
    }

    @Test
    public void hostWithoutAnyDedicatedRecordReturnsFalse() {
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(1L);
        when(dedicatedDao.findByHostId(1L)).thenReturn(null);
        when(dedicatedDao.findByClusterId(any())).thenReturn(null);
        when(dedicatedDao.findByPodId(any())).thenReturn(null);
        assertFalse(validator.checkIfHostIsDedicated(host));
    }

    // ---- checkIfHostOfVMIsInPrepareForMaintenanceState ----

    @Test
    public void prepareForMaintenanceHostRejected() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getHostId()).thenReturn(5L);
        HostVO host = mock(HostVO.class);
        when(host.getResourceState()).thenReturn(ResourceState.PrepareForMaintenance);
        when(hostDao.findById(5L)).thenReturn(host);
        assertThrows(InvalidParameterValueException.class,
                () -> validator.checkIfHostOfVMIsInPrepareForMaintenanceState(vm, "Migrate"));
    }

    @Test
    public void enabledHostAccepted() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getHostId()).thenReturn(5L);
        HostVO host = mock(HostVO.class);
        when(host.getResourceState()).thenReturn(ResourceState.Enabled);
        when(hostDao.findById(5L)).thenReturn(host);
        validator.checkIfHostOfVMIsInPrepareForMaintenanceState(vm, "Migrate");
    }

    // ---- isOnSupportedHypevisorForMigration ----

    @Test
    public void supportedHypervisorsReturnTrue() {
        for (HypervisorType type : List.of(HypervisorType.XenServer, HypervisorType.VMware, HypervisorType.KVM,
                HypervisorType.Hyperv, HypervisorType.LXC, HypervisorType.Simulator)) {
            VMInstanceVO vm = mock(VMInstanceVO.class);
            when(vm.getHypervisorType()).thenReturn(type);
            assertTrue(type + " should be supported", validator.isOnSupportedHypevisorForMigration(vm));
        }
    }

    @Test
    public void unsupportedHypervisorReturnsFalse() {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.None);
        assertFalse(validator.isOnSupportedHypevisorForMigration(vm));
    }
}
