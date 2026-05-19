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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.StopCommand;
import com.cloud.agent.api.to.DpdkTO;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmStopCommandServiceImplTest {

    private static final long VM_ID = 42L;

    @Mock
    private NicDao nicsDao;
    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private VmVlanPersistenceMappingService vmVlanPersistenceMappingService;

    @InjectMocks
    private VmStopCommandServiceImpl service;

    @Test
    public void decorateStopCommandUsesControlNicForConsoleProxy() {
        VirtualMachine vm = mockVm(VirtualMachine.Type.ConsoleProxy);
        NicVO nic = new NicVO("reserver", VM_ID, 1L, VirtualMachine.Type.ConsoleProxy);
        nic.setIPv4Address("10.1.1.10");
        when(nicsDao.getControlNicForVM(VM_ID)).thenReturn(nic);

        StopCommand command = new StopCommand(vm, true, false);

        service.decorateStopCommandWithNetworkDetails(command, vm);

        assertEquals("10.1.1.10", command.getControlIp());
    }

    @Test
    public void decorateStopCommandUsesControlNicForSecondaryStorageVm() {
        VMInstanceVO vm = mockVm(VirtualMachine.Type.SecondaryStorageVm);
        NicVO nic = new NicVO("reserver", VM_ID, 1L, VirtualMachine.Type.SecondaryStorageVm);
        nic.setIPv4Address("10.1.1.20");
        when(nicsDao.getControlNicForVM(VM_ID)).thenReturn(nic);

        StopCommand command = new StopCommand(vm, true, false);

        service.decorateStopCommandWithNetworkDetails(command, vm);

        assertEquals("10.1.1.20", command.getControlIp());
    }

    @Test
    public void decorateStopCommandUsesPrivateIpForDomainRouter() {
        VirtualMachine vm = mockVm(VirtualMachine.Type.DomainRouter);
        when(vm.getPrivateIpAddress()).thenReturn("172.16.0.1");

        StopCommand command = new StopCommand(vm, true, false);

        service.decorateStopCommandWithNetworkDetails(command, vm);

        assertEquals("172.16.0.1", command.getControlIp());
        verify(nicsDao, never()).getControlNicForVM(VM_ID);
    }

    @Test
    public void decorateStopCommandLeavesControlIpNullForUserVm() {
        VirtualMachine vm = mockVm(VirtualMachine.Type.User);

        StopCommand command = new StopCommand(vm, true, false);

        service.decorateStopCommandWithNetworkDetails(command, vm);

        assertNull(command.getControlIp());
        verify(nicsDao, never()).getControlNicForVM(VM_ID);
    }

    @Test
    public void decorateStopCommandLeavesControlIpNullForNullVmType() {
        VirtualMachine vm = mockVm(null);

        StopCommand command = new StopCommand(vm, true, false);

        service.decorateStopCommandWithNetworkDetails(command, vm);

        assertNull(command.getControlIp());
        verify(nicsDao, never()).getControlNicForVM(VM_ID);
    }

    @Test
    public void decorateStopCommandAppliesNonEmptyVlanPersistenceMap() {
        VirtualMachine vm = mockVm(VirtualMachine.Type.User);
        Map<String, Boolean> vlanMap = new HashMap<>();
        vlanMap.put("vlan://100", true);
        when(vmVlanPersistenceMappingService.getVlanToPersistenceMapForVM(VM_ID)).thenReturn(vlanMap);

        StopCommand command = new StopCommand(vm, true, false);

        service.decorateStopCommandWithNetworkDetails(command, vm);

        assertSame(vlanMap, command.getVlanToPersistenceMap());
    }

    @Test
    public void decorateStopCommandSkipsEmptyVlanPersistenceMap() {
        VirtualMachine vm = mockVm(VirtualMachine.Type.User);
        when(vmVlanPersistenceMappingService.getVlanToPersistenceMapForVM(VM_ID)).thenReturn(Collections.emptyMap());

        StopCommand command = new StopCommand(vm, true, false);

        service.decorateStopCommandWithNetworkDetails(command, vm);

        assertNull(command.getVlanToPersistenceMap());
    }

    @Test
    public void buildCleanupCommandCopiesDpdkMapping() {
        VirtualMachine vm = mockVm(VirtualMachine.Type.User);
        Map<String, DpdkTO> dpdkInterfaceMapping = Map.of("eth0", new DpdkTO("/ovs", "vhost0", "client"));

        StopCommand command = service.buildCleanupCommand(vm, true, dpdkInterfaceMapping);

        assertEquals(vm.getInstanceName(), command.getVmName());
        assertTrue(command.executeInSequence());
        assertFalse(command.checkBeforeCleanup());
        assertSame(dpdkInterfaceMapping, command.getDpdkInterfaceMapping());
    }

    @Test
    public void buildCleanupCommandForStringResolvesVmAndDecoratesNetworkDetails() {
        String instanceName = "s-1-VM";
        VMInstanceVO vm = mockVm(VirtualMachine.Type.SecondaryStorageVm);
        NicVO nic = new NicVO("reserver", VM_ID, 1L, VirtualMachine.Type.SecondaryStorageVm);
        nic.setIPv4Address("10.1.1.30");
        when(vmInstanceDao.findVMByInstanceName(instanceName)).thenReturn(vm);
        when(nicsDao.getControlNicForVM(VM_ID)).thenReturn(nic);

        StopCommand command = service.buildCleanupCommand(instanceName, false);

        assertEquals(instanceName, command.getVmName());
        assertFalse(command.executeInSequence());
        assertFalse(command.checkBeforeCleanup());
        assertEquals("10.1.1.30", command.getControlIp());
    }

    private VMInstanceVO mockVm(VirtualMachine.Type type) {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        lenient().when(vm.getId()).thenReturn(VM_ID);
        lenient().when(vm.getType()).thenReturn(type);
        lenient().when(vm.getInstanceName()).thenReturn("i-2-VM");
        return vm;
    }
}
