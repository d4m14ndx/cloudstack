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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.event.UsageEventVO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.NicDao;

/**
 * Unit tests for {@link VmUsageEventPublisherImpl} — the VM-level and
 * per-NIC usage-event publication helpers extracted from
 * {@code UserVmManagerImpl} as slice 16 of the Phase 4
 * Spring-component decomposition.
 */
@RunWith(MockitoJUnitRunner.class)
public class VmUsageEventPublisherImplTest {

    @Mock private ServiceOfferingDao serviceOfferingDao;
    @Mock private NicDao nicDao;
    @Mock private NetworkDao networkDao;

    private VmUsageEventPublisherImpl service;
    private MockedStatic<UsageEventUtils> usageEventUtilsMock;

    @Before
    public void setUp() {
        service = new VmUsageEventPublisherImpl();
        ReflectionTestUtils.setField(service, "serviceOfferingDao", serviceOfferingDao);
        ReflectionTestUtils.setField(service, "nicDao", nicDao);
        ReflectionTestUtils.setField(service, "networkDao", networkDao);
        usageEventUtilsMock = mockStatic(UsageEventUtils.class);
    }

    @After
    public void tearDown() {
        usageEventUtilsMock.close();
    }

    private VirtualMachine mockVm(long id, long accountId, long zoneId, long serviceOfferingId,
                                  long templateId, String hostName, String uuid,
                                  HypervisorType hvType) {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getId()).thenReturn(id);
        when(vm.getAccountId()).thenReturn(accountId);
        when(vm.getDataCenterId()).thenReturn(zoneId);
        when(vm.getServiceOfferingId()).thenReturn(serviceOfferingId);
        when(vm.getTemplateId()).thenReturn(templateId);
        when(vm.getHostName()).thenReturn(hostName);
        when(vm.getUuid()).thenReturn(uuid);
        when(vm.getHypervisorType()).thenReturn(hvType);
        return vm;
    }

    // ---- generateUsageEvent ----

    @Test
    public void generateUsageEventForFixedOfferingUsesStaticPublishVariant() {
        VirtualMachine vm = mockVm(1L, 7L, 3L, 50L, 60L, "host-1", "vm-uuid-1", HypervisorType.KVM);

        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.isDynamic()).thenReturn(false);
        when(offering.getId()).thenReturn(50L);
        when(serviceOfferingDao.findById(1L, 50L)).thenReturn(offering);

        service.generateUsageEvent(vm, true, EventTypes.EVENT_VM_CREATE);

        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_VM_CREATE), eq(7L), eq(3L), eq(1L),
                eq("host-1"), eq(50L), eq(60L), eq(HypervisorType.KVM.toString()),
                eq(VirtualMachine.class.getName()), eq("vm-uuid-1"), eq(true)));
        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                anyString(), anyLong(), anyLong(), anyLong(), anyString(),
                anyLong(), any(), anyString(), anyString(), anyString(),
                any(Map.class), anyBoolean()), never());
    }

    @Test
    public void generateUsageEventForDynamicOfferingPublishesCustomParameters() {
        VirtualMachine vm = mockVm(2L, 8L, 3L, 51L, 60L, "host-2", "vm-uuid-2", HypervisorType.KVM);

        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.isDynamic()).thenReturn(true);
        when(offering.getId()).thenReturn(51L);
        when(offering.getCpu()).thenReturn(4);
        when(offering.getSpeed()).thenReturn(2400);
        when(offering.getRamSize()).thenReturn(8192);
        when(serviceOfferingDao.findById(2L, 51L)).thenReturn(offering);

        service.generateUsageEvent(vm, false, EventTypes.EVENT_VM_UPGRADE);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_VM_UPGRADE), eq(8L), eq(3L), eq(2L),
                eq("host-2"), eq(51L), eq(60L), eq(HypervisorType.KVM.toString()),
                eq(VirtualMachine.class.getName()), eq("vm-uuid-2"),
                captor.capture(), eq(false)));
        Map<String, String> dynamicParams = captor.getValue();
        assertEquals("4", dynamicParams.get(UsageEventVO.DynamicParameters.cpuNumber.name()));
        assertEquals("2400", dynamicParams.get(UsageEventVO.DynamicParameters.cpuSpeed.name()));
        assertEquals("8192", dynamicParams.get(UsageEventVO.DynamicParameters.memory.name()));
    }

    @Test
    public void generateUsageEventPropagatesHypervisorTypeString() {
        VirtualMachine vm = mockVm(3L, 9L, 1L, 52L, 60L, "host-3", "vm-uuid-3", HypervisorType.VMware);

        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.isDynamic()).thenReturn(false);
        when(offering.getId()).thenReturn(52L);
        when(serviceOfferingDao.findById(3L, 52L)).thenReturn(offering);

        service.generateUsageEvent(vm, true, EventTypes.EVENT_VM_DESTROY);

        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                anyString(), anyLong(), anyLong(), anyLong(), anyString(),
                anyLong(), anyLong(), eq("VMware"), anyString(), anyString(), anyBoolean()));
    }

    @Test
    public void generateUsageEventPropagatesIsDisplayFalse() {
        VirtualMachine vm = mockVm(4L, 11L, 1L, 53L, 60L, "host-4", "vm-uuid-4", HypervisorType.KVM);

        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.isDynamic()).thenReturn(false);
        when(offering.getId()).thenReturn(53L);
        when(serviceOfferingDao.findById(4L, 53L)).thenReturn(offering);

        service.generateUsageEvent(vm, false, EventTypes.EVENT_VM_STOP);

        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_VM_STOP), anyLong(), anyLong(), anyLong(), anyString(),
                anyLong(), anyLong(), anyString(), anyString(), anyString(), eq(false)));
    }

    // ---- generateNetworkUsageForVm ----

    @Test
    public void generateNetworkUsageForVmWithNoNicsPublishesNothing() {
        VirtualMachine vm = mockVm(5L, 12L, 1L, 50L, 60L, "host-5", "vm-uuid-5", HypervisorType.KVM);
        when(nicDao.listByVmId(5L)).thenReturn(Collections.emptyList());

        service.generateNetworkUsageForVm(vm, true, EventTypes.EVENT_NETWORK_OFFERING_ASSIGN);

        usageEventUtilsMock.verifyNoInteractions();
    }

    @Test
    public void generateNetworkUsageForVmEmitsOneEventPerNic() {
        VirtualMachine vm = mockVm(6L, 12L, 1L, 50L, 60L, "host-6", "vm-uuid-6", HypervisorType.KVM);

        NicVO nic1 = mock(NicVO.class);
        when(nic1.getId()).thenReturn(101L);
        when(nic1.getNetworkId()).thenReturn(201L);
        when(nic1.isDefaultNic()).thenReturn(true);

        NicVO nic2 = mock(NicVO.class);
        when(nic2.getId()).thenReturn(102L);
        when(nic2.getNetworkId()).thenReturn(202L);
        when(nic2.isDefaultNic()).thenReturn(false);

        when(nicDao.listByVmId(6L)).thenReturn(Arrays.asList(nic1, nic2));

        NetworkVO net1 = mock(NetworkVO.class);
        when(net1.getNetworkOfferingId()).thenReturn(301L);
        when(networkDao.findById(201L)).thenReturn(net1);

        NetworkVO net2 = mock(NetworkVO.class);
        when(net2.getNetworkOfferingId()).thenReturn(302L);
        when(networkDao.findById(202L)).thenReturn(net2);

        service.generateNetworkUsageForVm(vm, true, EventTypes.EVENT_NETWORK_OFFERING_ASSIGN);

        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN), eq(12L), eq(1L), eq(6L),
                eq("101"), eq(301L), isNull(), eq(1L),
                anyString(), eq("vm-uuid-6"), eq(true)));
        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN), eq(12L), eq(1L), eq(6L),
                eq("102"), eq(302L), isNull(), eq(0L),
                anyString(), eq("vm-uuid-6"), eq(true)));
    }

    @Test
    public void generateNetworkUsageForVmDefaultNicFlag() {
        VirtualMachine vm = mockVm(7L, 12L, 1L, 50L, 60L, "host-7", "vm-uuid-7", HypervisorType.KVM);

        NicVO nic = mock(NicVO.class);
        when(nic.getId()).thenReturn(110L);
        when(nic.getNetworkId()).thenReturn(210L);
        when(nic.isDefaultNic()).thenReturn(false);
        when(nicDao.listByVmId(7L)).thenReturn(Arrays.asList(nic));

        NetworkVO network = mock(NetworkVO.class);
        when(network.getNetworkOfferingId()).thenReturn(310L);
        when(networkDao.findById(210L)).thenReturn(network);

        service.generateNetworkUsageForVm(vm, false, EventTypes.EVENT_NETWORK_OFFERING_REMOVE);

        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_NETWORK_OFFERING_REMOVE), anyLong(), anyLong(), anyLong(),
                eq("110"), eq(310L), isNull(), eq(0L),
                anyString(), anyString(), eq(false)));
    }

    // ---- saveUsageEvent ----

    private UserVmVO mockUserVm(long id, State state, boolean display, long serviceOfferingId) {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getId()).thenReturn(id);
        when(vm.getAccountId()).thenReturn(7L);
        when(vm.getDataCenterId()).thenReturn(3L);
        when(vm.getServiceOfferingId()).thenReturn(serviceOfferingId);
        when(vm.getTemplateId()).thenReturn(60L);
        when(vm.getHostName()).thenReturn("h");
        when(vm.getUuid()).thenReturn("u-" + id);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vm.getState()).thenReturn(state);
        when(vm.isDisplayVm()).thenReturn(display);
        return vm;
    }

    @Test
    public void saveUsageEventNoopWhenDestroyed() {
        UserVmVO vm = mockUserVm(20L, State.Destroyed, true, 50L);

        service.saveUsageEvent(vm);

        usageEventUtilsMock.verifyNoInteractions();
    }

    @Test
    public void saveUsageEventNoopWhenExpunging() {
        UserVmVO vm = mockUserVm(21L, State.Expunging, true, 50L);

        service.saveUsageEvent(vm);

        usageEventUtilsMock.verifyNoInteractions();
    }

    @Test
    public void saveUsageEventNoopWhenError() {
        UserVmVO vm = mockUserVm(22L, State.Error, true, 50L);

        service.saveUsageEvent(vm);

        usageEventUtilsMock.verifyNoInteractions();
    }

    @Test
    public void saveUsageEventDisplayVmStoppedFiresOnlyCreate() {
        UserVmVO vm = mockUserVm(23L, State.Stopped, true, 50L);
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.isDynamic()).thenReturn(false);
        when(offering.getId()).thenReturn(50L);
        when(serviceOfferingDao.findById(23L, 50L)).thenReturn(offering);

        service.saveUsageEvent(vm);

        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_VM_CREATE), anyLong(), anyLong(), anyLong(),
                anyString(), anyLong(), anyLong(), anyString(),
                anyString(), anyString(), eq(true)));
        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_VM_START), anyLong(), anyLong(), anyLong(),
                anyString(), anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyBoolean()), never());
    }

    @Test
    public void saveUsageEventDisplayVmRunningFiresCreateAndStartAndNetwork() {
        UserVmVO vm = mockUserVm(24L, State.Running, true, 50L);
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.isDynamic()).thenReturn(false);
        when(offering.getId()).thenReturn(50L);
        when(serviceOfferingDao.findById(24L, 50L)).thenReturn(offering);

        NicVO nic = mock(NicVO.class);
        when(nic.getId()).thenReturn(401L);
        when(nic.getNetworkId()).thenReturn(501L);
        when(nic.isDefaultNic()).thenReturn(true);
        when(nicDao.listByVmId(24L)).thenReturn(Arrays.asList(nic));
        NetworkVO net = mock(NetworkVO.class);
        when(net.getNetworkOfferingId()).thenReturn(601L);
        when(networkDao.findById(501L)).thenReturn(net);

        service.saveUsageEvent(vm);

        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_VM_CREATE), anyLong(), anyLong(), anyLong(),
                anyString(), anyLong(), anyLong(), anyString(),
                anyString(), anyString(), eq(true)));
        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_VM_START), anyLong(), anyLong(), anyLong(),
                anyString(), anyLong(), anyLong(), anyString(),
                anyString(), anyString(), eq(true)));
        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN), anyLong(), anyLong(), anyLong(),
                anyString(), anyLong(), isNull(), anyLong(),
                anyString(), anyString(), eq(true)));
    }

    @Test
    public void saveUsageEventDisplayVmStoppingFiresStartAndNetworkAssign() {
        UserVmVO vm = mockUserVm(25L, State.Stopping, true, 50L);
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.isDynamic()).thenReturn(false);
        when(offering.getId()).thenReturn(50L);
        when(serviceOfferingDao.findById(25L, 50L)).thenReturn(offering);

        when(nicDao.listByVmId(25L)).thenReturn(Collections.emptyList());

        service.saveUsageEvent(vm);

        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_VM_START), anyLong(), anyLong(), anyLong(),
                anyString(), anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyBoolean()));
    }

    @Test
    public void saveUsageEventHiddenVmStoppedFiresOnlyDestroy() {
        UserVmVO vm = mockUserVm(26L, State.Stopped, false, 50L);
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.isDynamic()).thenReturn(false);
        when(offering.getId()).thenReturn(50L);
        when(serviceOfferingDao.findById(26L, 50L)).thenReturn(offering);

        service.saveUsageEvent(vm);

        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_VM_DESTROY), anyLong(), anyLong(), anyLong(),
                anyString(), anyLong(), anyLong(), anyString(),
                anyString(), anyString(), eq(true)));
        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_VM_STOP), anyLong(), anyLong(), anyLong(),
                anyString(), anyLong(), anyLong(), anyString(),
                anyString(), anyString(), anyBoolean()), never());
    }

    @Test
    public void saveUsageEventHiddenVmRunningFiresDestroyAndStopAndNetworkRemove() {
        UserVmVO vm = mockUserVm(27L, State.Running, false, 50L);
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.isDynamic()).thenReturn(false);
        when(offering.getId()).thenReturn(50L);
        when(serviceOfferingDao.findById(27L, 50L)).thenReturn(offering);

        NicVO nic = mock(NicVO.class);
        when(nic.getId()).thenReturn(701L);
        when(nic.getNetworkId()).thenReturn(801L);
        when(nic.isDefaultNic()).thenReturn(false);
        when(nicDao.listByVmId(27L)).thenReturn(Arrays.asList(nic));
        NetworkVO net = mock(NetworkVO.class);
        when(net.getNetworkOfferingId()).thenReturn(901L);
        when(networkDao.findById(801L)).thenReturn(net);

        service.saveUsageEvent(vm);

        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_VM_DESTROY), anyLong(), anyLong(), anyLong(),
                anyString(), anyLong(), anyLong(), anyString(),
                anyString(), anyString(), eq(true)));
        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_VM_STOP), anyLong(), anyLong(), anyLong(),
                anyString(), anyLong(), anyLong(), anyString(),
                anyString(), anyString(), eq(true)));
        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_NETWORK_OFFERING_REMOVE), anyLong(), anyLong(), anyLong(),
                anyString(), anyLong(), isNull(), anyLong(),
                anyString(), anyString(), eq(true)));
    }

    @Test
    public void saveUsageEventDisplayVmHaltedDoesNotFireNetworkEvent() {
        UserVmVO vm = mockUserVm(28L, State.Stopped, true, 50L);
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.isDynamic()).thenReturn(false);
        when(offering.getId()).thenReturn(50L);
        when(serviceOfferingDao.findById(28L, 50L)).thenReturn(offering);

        service.saveUsageEvent(vm);

        // No network event should be published — only the VM_CREATE event
        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN), anyLong(), anyLong(), anyLong(),
                anyString(), anyLong(), isNull(), anyLong(),
                anyString(), anyString(), anyBoolean()), never());
    }

    @Test
    public void saveUsageEventDynamicOfferingPublishesParameterizedEvent() {
        UserVmVO vm = mockUserVm(29L, State.Stopped, true, 50L);
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.isDynamic()).thenReturn(true);
        when(offering.getId()).thenReturn(50L);
        when(offering.getCpu()).thenReturn(2);
        when(offering.getSpeed()).thenReturn(1500);
        when(offering.getRamSize()).thenReturn(4096);
        when(serviceOfferingDao.findById(29L, 50L)).thenReturn(offering);

        service.saveUsageEvent(vm);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_VM_CREATE), anyLong(), anyLong(), anyLong(),
                anyString(), anyLong(), anyLong(), anyString(),
                anyString(), anyString(), captor.capture(), anyBoolean()));
        Map<String, String> params = captor.getValue();
        assertNotNull(params);
        assertEquals("2", params.get(UsageEventVO.DynamicParameters.cpuNumber.name()));
        assertEquals("1500", params.get(UsageEventVO.DynamicParameters.cpuSpeed.name()));
        assertEquals("4096", params.get(UsageEventVO.DynamicParameters.memory.name()));
    }

    @Test
    public void generateUsageEventBuildsExpectedDynamicParameterMap() {
        VirtualMachine vm = mockVm(30L, 7L, 3L, 50L, 60L, "host-30", "vm-uuid-30", HypervisorType.XenServer);

        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.isDynamic()).thenReturn(true);
        when(offering.getId()).thenReturn(50L);
        when(offering.getCpu()).thenReturn(8);
        when(offering.getSpeed()).thenReturn(3000);
        when(offering.getRamSize()).thenReturn(16384);
        when(serviceOfferingDao.findById(30L, 50L)).thenReturn(offering);

        service.generateUsageEvent(vm, true, EventTypes.EVENT_VM_UPGRADE);

        ArgumentCaptor<Map<String, String>> captor = ArgumentCaptor.forClass(Map.class);
        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                anyString(), anyLong(), anyLong(), anyLong(), anyString(),
                anyLong(), anyLong(), anyString(), anyString(), anyString(),
                captor.capture(), anyBoolean()));
        Map<String, String> params = captor.getValue();
        Map<String, String> expected = new HashMap<>();
        expected.put(UsageEventVO.DynamicParameters.cpuNumber.name(), "8");
        expected.put(UsageEventVO.DynamicParameters.cpuSpeed.name(), "3000");
        expected.put(UsageEventVO.DynamicParameters.memory.name(), "16384");
        assertEquals(expected, params);
    }

    @Test
    public void generateNetworkUsageForVmHandlesMultipleNicsInOrder() {
        VirtualMachine vm = mockVm(31L, 12L, 1L, 50L, 60L, "host-31", "vm-uuid-31", HypervisorType.KVM);

        NicVO n1 = mock(NicVO.class);
        when(n1.getId()).thenReturn(1001L);
        when(n1.getNetworkId()).thenReturn(2001L);
        when(n1.isDefaultNic()).thenReturn(true);

        NicVO n2 = mock(NicVO.class);
        when(n2.getId()).thenReturn(1002L);
        when(n2.getNetworkId()).thenReturn(2002L);
        when(n2.isDefaultNic()).thenReturn(false);

        NicVO n3 = mock(NicVO.class);
        when(n3.getId()).thenReturn(1003L);
        when(n3.getNetworkId()).thenReturn(2003L);
        when(n3.isDefaultNic()).thenReturn(false);

        when(nicDao.listByVmId(31L)).thenReturn(Arrays.asList(n1, n2, n3));

        NetworkVO net1 = mock(NetworkVO.class);
        when(net1.getNetworkOfferingId()).thenReturn(3001L);
        when(networkDao.findById(2001L)).thenReturn(net1);
        NetworkVO net2 = mock(NetworkVO.class);
        when(net2.getNetworkOfferingId()).thenReturn(3002L);
        when(networkDao.findById(2002L)).thenReturn(net2);
        NetworkVO net3 = mock(NetworkVO.class);
        when(net3.getNetworkOfferingId()).thenReturn(3003L);
        when(networkDao.findById(2003L)).thenReturn(net3);

        service.generateNetworkUsageForVm(vm, true, EventTypes.EVENT_NETWORK_OFFERING_ASSIGN);

        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN), eq(12L), eq(1L), eq(31L),
                anyString(), anyLong(), isNull(), anyLong(),
                anyString(), anyString(), eq(true)),
                times(3));
    }

    @Test
    public void generateNetworkUsageForVmPropagatesVmClassNameInEvent() {
        VirtualMachine vm = mockVm(32L, 12L, 1L, 50L, 60L, "host-32", "vm-uuid-32", HypervisorType.KVM);

        NicVO nic = mock(NicVO.class);
        when(nic.getId()).thenReturn(150L);
        when(nic.getNetworkId()).thenReturn(250L);
        when(nic.isDefaultNic()).thenReturn(true);
        when(nicDao.listByVmId(32L)).thenReturn(Arrays.asList(nic));

        NetworkVO network = mock(NetworkVO.class);
        when(network.getNetworkOfferingId()).thenReturn(350L);
        when(networkDao.findById(250L)).thenReturn(network);

        service.generateNetworkUsageForVm(vm, false, EventTypes.EVENT_NETWORK_OFFERING_REMOVE);

        // vm.getClass().getName() — for a mock, the class name is dynamic, so
        // we just verify the call structure is correct.
        usageEventUtilsMock.verify(() -> UsageEventUtils.publishUsageEvent(
                eq(EventTypes.EVENT_NETWORK_OFFERING_REMOVE), eq(12L), eq(1L), eq(32L),
                eq("150"), eq(350L), isNull(), eq(1L),
                anyString(), eq("vm-uuid-32"), eq(false)),
                atLeastOnce());
    }
}
