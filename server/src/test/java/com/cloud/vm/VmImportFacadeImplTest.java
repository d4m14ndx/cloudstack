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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.configuration.Config;
import com.cloud.dc.DataCenter;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.utils.db.UUIDManager;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class VmImportFacadeImplTest {

    @Mock
    private ConfigurationDao configDao;
    @Mock
    private UserVmDao vmDao;
    @Mock
    private UUIDManager uuidManager;
    @Mock
    private VmImportFacade.ManagerOperations operations;

    @InjectMocks
    private VmImportFacadeImpl service;

    @Test
    public void getInternalNameUsesConfiguredInstanceSuffix() {
        when(configDao.getValue(Config.InstanceName.key())).thenReturn("CS");

        String internalName = service.getInternalName(8L, 101L);

        assertEquals(VirtualMachineName.getVmName(101L, 8L, "CS"), internalName);
    }

    @Test
    public void getInternalNameUsesDefaultSuffixWhenConfigIsMissing() {
        when(configDao.getValue(Config.InstanceName.key())).thenReturn(null);

        String internalName = service.getInternalName(8L, 101L);

        assertEquals(VirtualMachineName.getVmName(101L, 8L, "DEFAULT"), internalName);
    }

    @Test
    public void setVmRequiredFieldsForImportSkipsNonImportVm() {
        UserVmVO vm = org.mockito.Mockito.mock(UserVmVO.class);

        service.setVmRequiredFieldsForImport(false, vm, org.mockito.Mockito.mock(DataCenter.class),
                HypervisorType.VMware, org.mockito.Mockito.mock(Host.class), org.mockito.Mockito.mock(Host.class),
                VirtualMachine.PowerState.PowerOn);

        verify(vm, never()).setDataCenterId(anyLong());
    }

    @Test
    public void setVmRequiredFieldsForImportFinalizesRunningVmOnImportHost() {
        DataCenter zone = org.mockito.Mockito.mock(DataCenter.class);
        Host host = org.mockito.Mockito.mock(Host.class);
        Host lastHost = org.mockito.Mockito.mock(Host.class);
        UserVmVO vm = org.mockito.Mockito.mock(UserVmVO.class);
        when(zone.getId()).thenReturn(1L);
        when(host.getId()).thenReturn(2L);
        when(lastHost.getId()).thenReturn(3L);

        service.setVmRequiredFieldsForImport(true, vm, zone, HypervisorType.KVM, host, lastHost,
                VirtualMachine.PowerState.PowerOn);

        verify(vm).setDataCenterId(1L);
        verify(vm).setHostId(2L);
        verify(vm).setLastHostId(3L);
        verify(vm).setPowerState(VirtualMachine.PowerState.PowerOn);
        verify(vm).setState(VirtualMachine.State.Running);
    }

    @Test
    public void setVmRequiredFieldsForImportDoesNotSetHostForUnsupportedHypervisor() {
        DataCenter zone = org.mockito.Mockito.mock(DataCenter.class);
        Host host = org.mockito.Mockito.mock(Host.class);
        UserVmVO vm = org.mockito.Mockito.mock(UserVmVO.class);
        when(zone.getId()).thenReturn(1L);

        service.setVmRequiredFieldsForImport(true, vm, zone, HypervisorType.Any, host, null,
                VirtualMachine.PowerState.PowerOff);

        verify(vm).setDataCenterId(1L);
        verify(vm, never()).setHostId(anyLong());
        verify(vm, never()).setLastHostId(anyLong());
        verify(vm).setPowerState(VirtualMachine.PowerState.PowerOff);
        verify(vm, never()).setState(VirtualMachine.State.Running);
    }

    @Test
    public void importVMBuildsAllocationWithGeneratedInternalName() throws InsufficientCapacityException {
        DataCenter zone = org.mockito.Mockito.mock(DataCenter.class);
        Host host = org.mockito.Mockito.mock(Host.class);
        VirtualMachineTemplate template = org.mockito.Mockito.mock(VirtualMachineTemplate.class);
        Account owner = org.mockito.Mockito.mock(Account.class);
        ServiceOffering offering = org.mockito.Mockito.mock(ServiceOffering.class);
        UserVmVO expectedVm = org.mockito.Mockito.mock(UserVmVO.class);
        LinkedHashMap<String, List<NicProfile>> networkNicMap = new LinkedHashMap<>();
        Map<String, String> customParameters = Map.of("key", "value");
        when(zone.getId()).thenReturn(5L);
        when(owner.getAccountId()).thenReturn(8L);
        when(configDao.getValue(Config.InstanceName.key())).thenReturn("CS");
        when(vmDao.getNextInSequence(eq(Long.class), eq("id"))).thenReturn(101L);
        when(uuidManager.generateUuid(UserVm.class, null)).thenReturn("uuid-101");
        when(template.getFormat()).thenReturn(ImageFormat.ISO);
        when(operations.checkIfDynamicScalingCanBeEnabled(null, offering, template, 5L)).thenReturn(true);
        when(operations.commitUserVm(any(VmImportFacade.Allocation.class))).thenReturn(expectedVm);

        UserVm result = service.importVM(zone, host, template, null, "display", owner, "user-data", true, "us",
                8L, 9L, offering, "ssh-rsa key", 10L, "host-name", HypervisorType.VMware, customParameters,
                VirtualMachine.PowerState.PowerOff, networkNicMap, operations);

        assertSame(expectedVm, result);
        verify(operations).checkNameForRFCCompliance("host-name");
        ArgumentCaptor<VmImportFacade.Allocation> captor = ArgumentCaptor.forClass(VmImportFacade.Allocation.class);
        verify(operations).commitUserVm(captor.capture());
        VmImportFacade.Allocation allocation = captor.getValue();
        assertSame(zone, allocation.getZone());
        assertSame(host, allocation.getHost());
        assertSame(host, allocation.getLastHost());
        assertSame(template, allocation.getTemplate());
        assertEquals("host-name", allocation.getHostName());
        assertEquals("display", allocation.getDisplayName());
        assertSame(owner, allocation.getOwner());
        assertEquals("user-data", allocation.getUserData());
        assertEquals(true, allocation.getDisplayVm());
        assertEquals("us", allocation.getKeyboard());
        assertEquals(8L, allocation.getAccountId());
        assertEquals(9L, allocation.getUserId());
        assertSame(offering, allocation.getServiceOffering());
        assertEquals(true, allocation.isIso());
        assertEquals(Long.valueOf(10L), allocation.getGuestOsId());
        assertEquals("ssh-rsa key", allocation.getSshPublicKeys());
        assertSame(networkNicMap, allocation.getNetworkNicMap());
        assertEquals(101L, allocation.getId());
        assertEquals(VirtualMachineName.getVmName(101L, 8L, "CS"), allocation.getInstanceName());
        assertEquals("uuid-101", allocation.getUuidName());
        assertEquals(HypervisorType.VMware, allocation.getHypervisorType());
        assertSame(customParameters, allocation.getCustomParameters());
        assertEquals(VirtualMachine.PowerState.PowerOff, allocation.getPowerState());
        assertEquals(true, allocation.getDynamicScalingEnabled());
    }

    @Test
    public void importVMUsesProvidedInstanceNameAndKeepsLastHostNullForRunningVm() throws InsufficientCapacityException {
        DataCenter zone = org.mockito.Mockito.mock(DataCenter.class);
        Host host = org.mockito.Mockito.mock(Host.class);
        VirtualMachineTemplate template = org.mockito.Mockito.mock(VirtualMachineTemplate.class);
        Account owner = org.mockito.Mockito.mock(Account.class);
        ServiceOffering offering = org.mockito.Mockito.mock(ServiceOffering.class);
        UserVmVO expectedVm = org.mockito.Mockito.mock(UserVmVO.class);
        when(zone.getId()).thenReturn(5L);
        when(vmDao.getNextInSequence(eq(Long.class), eq("id"))).thenReturn(101L);
        when(uuidManager.generateUuid(UserVm.class, null)).thenReturn("uuid-101");
        when(template.getFormat()).thenReturn(ImageFormat.QCOW2);
        when(operations.checkIfDynamicScalingCanBeEnabled(null, offering, template, 5L)).thenReturn(false);
        when(operations.commitUserVm(any(VmImportFacade.Allocation.class))).thenReturn(expectedVm);

        service.importVM(zone, host, template, "provided-instance", "display", owner, null, null, null,
                8L, 9L, offering, null, null, null, HypervisorType.KVM, Map.of(),
                VirtualMachine.PowerState.PowerOn, new LinkedHashMap<>(), operations);

        ArgumentCaptor<VmImportFacade.Allocation> captor = ArgumentCaptor.forClass(VmImportFacade.Allocation.class);
        verify(operations).commitUserVm(captor.capture());
        assertEquals("provided-instance", captor.getValue().getInstanceName());
        assertNull(captor.getValue().getLastHost());
        assertEquals(false, captor.getValue().getDynamicScalingEnabled());
        verify(operations, never()).checkNameForRFCCompliance(any());
        verify(configDao, never()).getValue(Config.InstanceName.key());
    }

    @Test
    public void importVMRejectsMissingZone() {
        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class, () ->
                service.importVM(null, null, org.mockito.Mockito.mock(VirtualMachineTemplate.class), null, "display",
                        org.mockito.Mockito.mock(Account.class), null, null, null, 1L, 2L,
                        org.mockito.Mockito.mock(ServiceOffering.class), null, null, null, HypervisorType.KVM, Map.of(),
                        VirtualMachine.PowerState.PowerOff, new LinkedHashMap<>(), operations));

        assertEquals("Unable to import virtual machine with invalid zone", exception.getMessage());
    }

    @Test
    public void importVMRejectsMissingVmwareHost() {
        DataCenter zone = org.mockito.Mockito.mock(DataCenter.class);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class, () ->
                service.importVM(zone, null, org.mockito.Mockito.mock(VirtualMachineTemplate.class), null, "display",
                        org.mockito.Mockito.mock(Account.class), null, null, null, 1L, 2L,
                        org.mockito.Mockito.mock(ServiceOffering.class), null, null, null, HypervisorType.VMware, Map.of(),
                        VirtualMachine.PowerState.PowerOff, new LinkedHashMap<>(), operations));

        assertEquals("Unable to import virtual machine with invalid host", exception.getMessage());
    }
}
