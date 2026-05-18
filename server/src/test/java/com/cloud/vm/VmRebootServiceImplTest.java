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

import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.api.command.user.vm.RebootVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.cloud.entity.api.db.dao.VMNetworkMapDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.router.VpcVirtualNetworkApplianceManager;
import com.cloud.resource.ResourceState;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.UserVO;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class VmRebootServiceImplTest {

    @Mock private UserVmDao vmDao;
    @Mock private NicDao nicDao;
    @Mock private DataCenterDao dcDao;
    @Mock private HostDao hostDao;
    @Mock private DomainRouterDao routerDao;
    @Mock private VMNetworkMapDao vmNetworkMapDao;
    @Mock private ServiceOfferingDao serviceOfferingDao;
    @Mock private AccountManager accountManager;
    @Mock private NetworkModel networkModel;
    @Mock private VirtualMachineManager itMgr;
    @Mock private VpcVirtualNetworkApplianceManager virtualNetAppliance;
    @Mock private VmMigrationValidator vmMigrationValidator;
    @Mock private VmStatsCollectionService vmStatsCollectionService;

    @Mock private VmIpFetchScheduler ipFetchScheduler;
    @Mock private UserVmManager userVmManager;
    @Mock private RebootVMCmd cmd;
    @Mock private Account callerAccount;
    @Mock private UserVmVO vm;
    @Mock private ServiceOfferingVO offering;

    private VmRebootServiceImpl service;

    private static final long VM_ID = 42L;
    private static final long DC_ID = 7L;
    private static final long HOST_ID = 11L;
    private static final long USER_ID = 5L;
    private static final long ACCOUNT_ID = 3L;

    @Before
    public void setUp() {
        service = new VmRebootServiceImpl();
        ReflectionTestUtils.setField(service, "vmDao", vmDao);
        ReflectionTestUtils.setField(service, "nicDao", nicDao);
        ReflectionTestUtils.setField(service, "dcDao", dcDao);
        ReflectionTestUtils.setField(service, "hostDao", hostDao);
        ReflectionTestUtils.setField(service, "routerDao", routerDao);
        ReflectionTestUtils.setField(service, "vmNetworkMapDao", vmNetworkMapDao);
        ReflectionTestUtils.setField(service, "serviceOfferingDao", serviceOfferingDao);
        ReflectionTestUtils.setField(service, "accountManager", accountManager);
        ReflectionTestUtils.setField(service, "networkModel", networkModel);
        ReflectionTestUtils.setField(service, "itMgr", itMgr);
        ReflectionTestUtils.setField(service, "virtualNetAppliance", virtualNetAppliance);
        ReflectionTestUtils.setField(service, "vmMigrationValidator", vmMigrationValidator);
        ReflectionTestUtils.setField(service, "vmStatsCollectionService", vmStatsCollectionService);
        service.setIpFetchScheduler(ipFetchScheduler);
        service.setUserVmManager(userVmManager);

        UserVO userVO = new UserVO(USER_ID);
        userVO.setAccountId(ACCOUNT_ID);
        CallContext.register(userVO, callerAccount);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    private void stubRunningVm() {
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getUuid()).thenReturn("vm-uuid");
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHostId()).thenReturn(HOST_ID);
        when(vm.getDataCenterId()).thenReturn(DC_ID);
        when(vm.getServiceOfferingId()).thenReturn(99L);
        when(vm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        when(cmd.getId()).thenReturn(VM_ID);
        when(cmd.isForced()).thenReturn(false);
    }

    @Test
    public void rebootVirtualMachineThrowsWhenVmNotFound() {
        when(cmd.getId()).thenReturn(VM_ID);
        when(vmDao.findById(VM_ID)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class, () -> service.rebootVirtualMachine(cmd));
    }

    @Test
    public void rebootVirtualMachineThrowsWhenVmNotRunning() {
        when(cmd.getId()).thenReturn(VM_ID);
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vm.getUuid()).thenReturn("u");
        when(vm.getDisplayNameOrHostName()).thenReturn("d");
        assertThrows(InvalidParameterValueException.class, () -> service.rebootVirtualMachine(cmd));
    }

    @Test
    public void rebootVirtualMachineThrowsWhenServiceOfferingMissing() {
        stubRunningVm();
        when(serviceOfferingDao.findById(eq(VM_ID), anyLong())).thenReturn(null);
        assertThrows(InvalidParameterValueException.class, () -> service.rebootVirtualMachine(cmd));
    }

    @Test
    public void rebootVirtualMachineDelegatesVolatileToRestore() throws Exception {
        stubRunningVm();
        when(serviceOfferingDao.findById(eq(VM_ID), anyLong())).thenReturn(offering);
        when(offering.getRemoved()).thenReturn(null);
        when(offering.isVolatileVm()).thenReturn(true);
        UserVm restored = mock(UserVm.class);
        when(userVmManager.restoreVMInternal(callerAccount, vm)).thenReturn(restored);

        UserVm result = service.rebootVirtualMachine(cmd);

        assertSame(restored, result);
        verify(userVmManager).restoreVMInternal(callerAccount, vm);
    }

    @Test
    public void rebootVirtualMachineRejectsEnterSetupOnNonVmware() {
        stubRunningVm();
        when(serviceOfferingDao.findById(eq(VM_ID), anyLong())).thenReturn(offering);
        when(offering.getRemoved()).thenReturn(null);
        when(offering.isVolatileVm()).thenReturn(false);
        when(cmd.getBootIntoSetup()).thenReturn(Boolean.TRUE);
        when(vm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        assertThrows(InvalidParameterValueException.class, () -> service.rebootVirtualMachine(cmd));
    }

    @Test
    public void rebootVirtualMachineSchedulesIpFetchForL2Nic() throws Exception {
        stubRunningVm();
        when(serviceOfferingDao.findById(eq(VM_ID), anyLong())).thenReturn(offering);
        when(offering.getRemoved()).thenReturn(null);
        when(offering.isVolatileVm()).thenReturn(false);

        // make the internal reboot return the same vm (skip routers via Basic DC)
        DataCenterVO dc = mock(DataCenterVO.class);
        when(dc.getNetworkType()).thenReturn(DataCenter.NetworkType.Basic);
        when(dcDao.findById(DC_ID)).thenReturn(dc);

        NicVO nic = mock(NicVO.class);
        when(nic.getId()).thenReturn(101L);
        when(nic.getInstanceId()).thenReturn(VM_ID);
        when(nic.getNetworkId()).thenReturn(200L);
        when(nicDao.listByVmId(VM_ID)).thenReturn(Arrays.asList(nic));
        Network net = mock(Network.class);
        when(net.getGuestType()).thenReturn(Network.GuestType.L2);
        when(networkModel.getNetwork(200L)).thenReturn(net);

        UserVm result = service.rebootVirtualMachine(cmd);
        assertSame(vm, result);
        verify(ipFetchScheduler).scheduleIpFetch(101L, VM_ID);
    }

    @Test
    public void rebootVirtualMachineInternalReturnsNullWhenVmGone() throws Exception {
        when(vmDao.findById(VM_ID)).thenReturn(null);
        assertNull(service.rebootVirtualMachineInternal(USER_ID, VM_ID, false, false));
    }

    @Test
    public void rebootVirtualMachineInternalReturnsNullWhenDestroyed() throws Exception {
        when(vm.getState()).thenReturn(VirtualMachine.State.Destroyed);
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        assertNull(service.rebootVirtualMachineInternal(USER_ID, VM_ID, false, false));
    }

    @Test
    public void rebootVirtualMachineInternalReturnsNullWhenNotRunning() throws Exception {
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        assertNull(service.rebootVirtualMachineInternal(USER_ID, VM_ID, false, false));
    }

    @Test
    public void rebootVirtualMachineInternalForcedThrowsIfHostDown() throws Exception {
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHostId()).thenReturn(HOST_ID);
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        HostVO host = mock(HostVO.class);
        when(host.getResourceState()).thenReturn(ResourceState.Enabled);
        when(host.getStatus()).thenReturn(Status.Down);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        assertThrows(CloudRuntimeException.class,
                () -> service.rebootVirtualMachineInternal(USER_ID, VM_ID, false, true));
    }

    @Test
    public void rebootVirtualMachineInternalCallsItMgrReboot() throws Exception {
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHostId()).thenReturn(HOST_ID);
        when(vm.getDataCenterId()).thenReturn(DC_ID);
        when(vm.getUuid()).thenReturn("uuid");
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        DataCenterVO dc = mock(DataCenterVO.class);
        when(dc.getNetworkType()).thenReturn(DataCenter.NetworkType.Basic);
        when(dcDao.findById(DC_ID)).thenReturn(dc);

        UserVm result = service.rebootVirtualMachineInternal(USER_ID, VM_ID, false, false);
        verify(itMgr).reboot(eq("uuid"), any());
        assertSame(vm, result);
    }

    @Test
    public void rebootVirtualMachineInternalStartsStoppedRoutersForAdvancedDc() throws Exception {
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHostId()).thenReturn(HOST_ID);
        when(vm.getDataCenterId()).thenReturn(DC_ID);
        when(vm.getUuid()).thenReturn("uuid");
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        DataCenterVO dc = mock(DataCenterVO.class);
        when(dc.getNetworkType()).thenReturn(DataCenter.NetworkType.Advanced);
        when(dcDao.findById(DC_ID)).thenReturn(dc);
        when(vmNetworkMapDao.getNetworks(VM_ID)).thenReturn(Arrays.asList(300L));
        DomainRouterVO router = mock(DomainRouterVO.class);
        when(router.getId()).thenReturn(900L);
        when(routerDao.listStopped(300L)).thenReturn(Arrays.asList(router));

        service.rebootVirtualMachineInternal(USER_ID, VM_ID, false, false);
        verify(virtualNetAppliance).startRouter(900L, true);
        verify(itMgr).reboot(eq("uuid"), any());
    }

    @Test
    public void rebootVirtualMachineInternalEnterSetupPassesBootIntoSetupParam() throws Exception {
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHostId()).thenReturn(HOST_ID);
        when(vm.getDataCenterId()).thenReturn(DC_ID);
        when(vm.getUuid()).thenReturn("uuid");
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        DataCenterVO dc = mock(DataCenterVO.class);
        when(dc.getNetworkType()).thenReturn(DataCenter.NetworkType.Basic);
        when(dcDao.findById(DC_ID)).thenReturn(dc);

        service.rebootVirtualMachineInternal(USER_ID, VM_ID, true, false);
        verify(itMgr).reboot(eq("uuid"), any(Map.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void forceRebootVirtualMachineReturnsNullWhenStopReturnsNull() throws Exception {
        when(vm.getId()).thenReturn(VM_ID);
        when(userVmManager.stopVirtualMachine(VM_ID, false)).thenReturn(null);
        assertNull(service.forceRebootVirtualMachine(vm, HOST_ID, false));
        verify(userVmManager, never()).startVirtualMachine(anyLong(), any(), any(), anyLong(), any(), any(), anyBoolean());
    }

    @Test
    public void forceRebootVirtualMachineCallsStartAfterStop() throws Exception {
        when(vm.getId()).thenReturn(VM_ID);
        UserVm stopped = mock(UserVm.class);
        when(userVmManager.stopVirtualMachine(VM_ID, false)).thenReturn(stopped);
        UserVmVO startedVo = mock(UserVmVO.class);
        Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> pair = new Pair<>(startedVo, new HashMap<>());
        when(userVmManager.startVirtualMachine(eq(VM_ID), eq(null), eq(null), eq(HOST_ID), any(), eq(null), eq(false))).thenReturn(pair);

        UserVm result = service.forceRebootVirtualMachine(vm, HOST_ID, false);
        assertSame(startedVo, result);
    }

    @Test
    public void forceRebootVirtualMachineWrapsCloudException() throws Exception {
        when(vm.getId()).thenReturn(VM_ID);
        when(userVmManager.stopVirtualMachine(VM_ID, false))
                .thenThrow(new com.cloud.exception.ConcurrentOperationException("boom"));
        assertThrows(CloudRuntimeException.class,
                () -> service.forceRebootVirtualMachine(vm, HOST_ID, false));
    }

    private static <T> T mock(Class<T> c) {
        return org.mockito.Mockito.mock(c);
    }
}
