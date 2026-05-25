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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.dc.DataCenter;
import com.cloud.deploy.DeployDestination;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmNetworkAttachmentOrchestrationServiceImplTest {

    private static final long VM_ID = 11L;
    private static final long NIC_ID = 22L;
    private static final long NETWORK_ID = 33L;
    private static final long ZONE_ID = 44L;
    private static final long HOST_ID = 55L;

    @InjectMocks
    private VmNetworkAttachmentOrchestrationServiceImpl service;

    @Mock
    private NetworkOrchestrationService networkMgr;
    @Mock
    private NetworkModel networkModel;
    @Mock
    private VMInstanceDao vmDao;
    @Mock
    private NicDao nicsDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private NetworkDao networkDao;
    @Mock
    private HypervisorGuruManager hvGuruMgr;
    @Mock
    private EntityManager entityMgr;
    @Mock
    private UserVmManager userVmMgr;
    @Mock
    private VmNetworkAttachmentOrchestrationService.BackendNicOperations backendNicOperations;

    @Mock
    private VirtualMachine vm;
    @Mock
    private VMInstanceVO vmVO;
    @Mock
    private Network network;
    @Mock
    private NetworkVO networkVO;
    @Mock
    private NicProfile requested;
    @Mock
    private NicProfile createdNic;
    @Mock
    private Nic nic;
    @Mock
    private NicVO nicVO;
    @Mock
    private NicVO lock;
    @Mock
    private DataCenter dataCenter;
    @Mock
    private HostVO host;
    @Mock
    private HypervisorGuru hypervisorGuru;
    @Mock
    private VirtualMachineTO vmTO;
    @Mock
    private NicTO nicTO;

    @Test
    public void checkIfNetworkExistsForUserVMThrowsWhenUserVmAlreadyHasNicInNetwork() {
        NicVO existingNic = mock(NicVO.class);
        when(vm.getType()).thenReturn(Type.User);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getInstanceName()).thenReturn("i-11-VM");
        when(network.getId()).thenReturn(NETWORK_ID);
        when(network.getUuid()).thenReturn("network-uuid");
        when(existingNic.getNetworkId()).thenReturn(NETWORK_ID);
        when(nicsDao.listByVmId(VM_ID)).thenReturn(java.util.List.of(existingNic));

        assertThrows(CloudRuntimeException.class, () -> service.checkIfNetworkExistsForUserVM(vm, network));
    }

    @Test
    public void addVmToNetworkCreatesStoppedNicWithoutBackendPlug() throws Exception {
        mockBasicVmAndNetwork(State.Stopped);
        when(networkMgr.createNicForVm(eq(network), eq(requested), any(ReservationContext.class), any(VirtualMachineProfile.class), eq(false))).thenReturn(createdNic);

        try (MockedStatic<CallContext> ignored = mockCallContext()) {
            NicProfile result = service.addVmToNetwork(vm, network, requested, backendNicOperations);

            assertSame(createdNic, result);
            verify(networkMgr).createNicForVm(eq(network), eq(requested), any(ReservationContext.class), any(VirtualMachineProfile.class), eq(false));
            verify(backendNicOperations, never()).plugNic(any(), any(), any(), any(), any());
        }
    }

    @Test
    public void addVmToNetworkRemovesCreatedNicWhenBackendPlugFails() throws Exception {
        mockBasicVmAndNetwork(State.Running);
        when(vmVO.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(createdNic.getId()).thenReturn(NIC_ID);
        when(networkMgr.createNicForVm(eq(network), eq(requested), any(ReservationContext.class), any(VirtualMachineProfile.class), eq(true))).thenReturn(createdNic);
        when(hvGuruMgr.getGuru(HypervisorType.KVM)).thenReturn(hypervisorGuru);
        when(hypervisorGuru.implement(any(VirtualMachineProfile.class))).thenReturn(vmTO);
        when(hypervisorGuru.toNicTO(createdNic)).thenReturn(nicTO);
        when(backendNicOperations.plugNic(eq(network), eq(nicTO), eq(vmTO), any(ReservationContext.class), any(DeployDestination.class))).thenReturn(false);
        when(nicsDao.findById(NIC_ID)).thenReturn(nicVO);

        try (MockedStatic<CallContext> ignored = mockCallContext()) {
            NicProfile result = service.addVmToNetwork(vm, network, requested, backendNicOperations);

            assertNull(result);
            verify(networkMgr).removeNic(any(VirtualMachineProfile.class), eq(nicVO));
        }
    }

    @Test
    public void toNicTODelegatesToHypervisorGuru() {
        when(hvGuruMgr.getGuru(HypervisorType.KVM)).thenReturn(hypervisorGuru);
        when(hypervisorGuru.toNicTO(createdNic)).thenReturn(nicTO);

        NicTO result = service.toNicTO(createdNic, HypervisorType.KVM);

        assertSame(nicTO, result);
    }

    @Test
    public void removeNicFromVmReleasesAndRemovesStoppedNicWithoutBackendUnplug() throws Exception {
        mockBasicVmAndNetwork(State.Stopped);
        mockRemoveNicNetwork();
        when(nic.getId()).thenReturn(NIC_ID);

        try (MockedStatic<CallContext> ignored = mockCallContext()) {
            assertTrue(service.removeNicFromVm(vm, nic, backendNicOperations));

            verify(backendNicOperations, never()).unplugNic(any(), any(), any(), any(), any());
            verify(networkMgr).releaseNic(any(VirtualMachineProfile.class), eq(nic));
            verify(networkMgr).removeNic(any(VirtualMachineProfile.class), eq(nic));
            verify(nicsDao).remove(NIC_ID);
        }
    }

    @Test
    public void removeVmFromNetworkRejectsDefaultUserNic() throws Exception {
        mockBasicVmAndNetwork(State.Stopped);
        mockRemoveVmFromNetworkVmTo();
        when(network.getId()).thenReturn(NETWORK_ID);
        when(networkModel.getNicInNetwork(VM_ID, NETWORK_ID)).thenReturn(nic);
        when(nic.isDefaultNic()).thenReturn(true);
        when(vm.getType()).thenReturn(Type.User);

        try (MockedStatic<CallContext> ignored = mockCallContext()) {
            assertThrows(CloudRuntimeException.class, () -> service.removeVmFromNetwork(vm, network, null, backendNicOperations));

            verify(nicsDao, never()).acquireInLockTable(any());
        }
    }

    @Test
    public void removeVmFromNetworkReturnsTrueWhenNicDisappearsBeforeLock() throws Exception {
        mockBasicVmAndNetwork(State.Stopped);
        mockRemoveVmFromNetworkVmTo();
        when(network.getId()).thenReturn(NETWORK_ID);
        when(networkModel.getNicInNetwork(VM_ID, NETWORK_ID)).thenReturn(nic);
        when(nic.getId()).thenReturn(NIC_ID);
        when(nicsDao.acquireInLockTable(NIC_ID)).thenReturn(null);
        when(nicsDao.findById(NIC_ID)).thenReturn(null);

        try (MockedStatic<CallContext> ignored = mockCallContext()) {
            assertTrue(service.removeVmFromNetwork(vm, network, null, backendNicOperations));
        }
    }

    private void mockBasicVmAndNetwork(State state) {
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getHostId()).thenReturn(HOST_ID);
        when(vm.getState()).thenReturn(state);
        when(vm.getType()).thenReturn(Type.User);
        when(vmDao.findById(VM_ID)).thenReturn(vmVO);
        when(nicsDao.listByVmId(VM_ID)).thenReturn(java.util.Collections.emptyList());
        when(network.getDataCenterId()).thenReturn(ZONE_ID);
        when(entityMgr.findById(DataCenter.class, ZONE_ID)).thenReturn(dataCenter);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
    }

    private void mockRemoveNicNetwork() {
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(networkDao.findById(NETWORK_ID)).thenReturn(networkVO);
        when(networkVO.getId()).thenReturn(NETWORK_ID);
        when(networkVO.getDataCenterId()).thenReturn(ZONE_ID);
        when(vmVO.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(hvGuruMgr.getGuru(HypervisorType.KVM)).thenReturn(hypervisorGuru);
        when(hypervisorGuru.implement(any(VirtualMachineProfile.class))).thenReturn(vmTO);
    }

    private void mockRemoveVmFromNetworkVmTo() {
        when(vmVO.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(hvGuruMgr.getGuru(HypervisorType.KVM)).thenReturn(hypervisorGuru);
        when(hypervisorGuru.implement(any(VirtualMachineProfile.class))).thenReturn(vmTO);
    }

    private MockedStatic<CallContext> mockCallContext() {
        MockedStatic<CallContext> callContext = Mockito.mockStatic(CallContext.class);
        CallContext currentContext = mock(CallContext.class);
        when(currentContext.getCallingUser()).thenReturn(mock(User.class));
        when(currentContext.getCallingAccount()).thenReturn(mock(Account.class));
        callContext.when(CallContext::current).thenReturn(currentContext);
        return callContext;
    }
}
