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
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.framework.extensions.dao.ExtensionDetailsDao;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.PrepareExternalProvisioningAnswer;
import com.cloud.agent.api.RebootCommand;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.StopCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.dc.DataCenter;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class VmExternalProvisioningManagerImplTest {

    @Mock private AgentManager agentMgr;
    @Mock private NicDao nicsDao;
    @Mock private UserVmDao userVmDao;
    @Mock private ExtensionsManager extensionsManager;
    @Mock private ExtensionDetailsDao extensionDetailsDao;
    @Mock private NetworkService networkService;
    @Mock private HostDao hostDao;
    @Mock private NetworkModel networkModel;
    @Mock private HypervisorGuruManager hvGuruMgr;

    @InjectMocks
    private VmExternalProvisioningManagerImpl manager;

    private static final long VM_ID = 42L;
    private static final long HOST_ID = 17L;
    private static final long ZONE_ID = 7L;

    private VirtualMachineTO mockVmTO(long id) {
        VirtualMachineTO vmTO = mock(VirtualMachineTO.class);
        lenient().when(vmTO.getId()).thenReturn(id);
        return vmTO;
    }

    private Host mockHost(HypervisorType type) {
        Host host = mock(Host.class);
        lenient().when(host.getHypervisorType()).thenReturn(type);
        lenient().when(host.getId()).thenReturn(HOST_ID);
        return host;
    }

    // ---- updateVmMetadataManufacturerAndProduct ----

    @Test
    public void updateVmMetadataUsesHypervisorBasedDefaultProductWhenConfigBlank() {
        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getDataCenterId()).thenReturn(ZONE_ID);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);

        manager.updateVmMetadataManufacturerAndProduct(vmTO, vm);

        verify(vmTO).setMetadataManufacturer(VirtualMachineManager.VmMetadataManufacturer.defaultValue());
        verify(vmTO).setMetadataProductName("CloudStack KVM Hypervisor");
    }

    // ---- updateExternalVmDetailsFromPrepareAnswer ----

    @Test
    public void updateExternalVmDetailsNoOpsWhenNewDetailsNull() {
        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        UserVmVO userVm = mock(UserVmVO.class);

        manager.updateExternalVmDetailsFromPrepareAnswer(vmTO, userVm, null);

        verify(userVmDao, never()).saveDetails(any());
        verify(vmTO, never()).setDetails(anyMap());
    }

    @Test
    public void updateExternalVmDetailsNoOpsWhenNewDetailsEqualsCurrent() {
        Map<String, String> existing = new HashMap<>();
        existing.put("k", "v");
        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        when(vmTO.getDetails()).thenReturn(existing);
        UserVmVO userVm = mock(UserVmVO.class);

        manager.updateExternalVmDetailsFromPrepareAnswer(vmTO, userVm, new HashMap<>(existing));

        verify(userVmDao, never()).saveDetails(any());
    }

    @Test
    public void updateExternalVmDetailsPersistsWhenDifferent() {
        Map<String, String> existing = new HashMap<>();
        existing.put("k", "v");
        Map<String, String> updated = new HashMap<>();
        updated.put("k", "v2");
        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        when(vmTO.getDetails()).thenReturn(existing);
        UserVmVO userVm = mock(UserVmVO.class);

        manager.updateExternalVmDetailsFromPrepareAnswer(vmTO, userVm, updated);

        verify(vmTO).setDetails(updated);
        verify(userVm).setDetails(updated);
        verify(userVmDao).saveDetails(userVm);
    }

    // ---- updateExternalVmDataFromPrepareAnswer ----

    @Test
    public void updateExternalVmDataNoOpsWhenNeitherChanged() {
        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        VirtualMachineTO updatedTO = mock(VirtualMachineTO.class);
        when(updatedTO.getVncPassword()).thenReturn(null);
        when(updatedTO.getDetails()).thenReturn(null);

        manager.updateExternalVmDataFromPrepareAnswer(vmTO, updatedTO);

        verify(userVmDao, never()).findById(anyLong());
    }

    @Test
    public void updateExternalVmDataNoOpsWhenUserVmNotFound() {
        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        VirtualMachineTO updatedTO = mock(VirtualMachineTO.class);
        when(updatedTO.getVncPassword()).thenReturn("newpass");
        when(vmTO.getVncPassword()).thenReturn("oldpass");
        when(userVmDao.findById(VM_ID)).thenReturn(null);

        manager.updateExternalVmDataFromPrepareAnswer(vmTO, updatedTO);

        verify(vmTO, never()).setVncPassword(any());
    }

    @Test
    public void updateExternalVmDataAppliesNewVncPassword() {
        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        when(vmTO.getVncPassword()).thenReturn("oldpass");
        VirtualMachineTO updatedTO = mock(VirtualMachineTO.class);
        when(updatedTO.getVncPassword()).thenReturn("newpass");
        when(updatedTO.getDetails()).thenReturn(null);
        UserVmVO userVm = mock(UserVmVO.class);
        when(userVm.getPassword()).thenReturn("currentvm");
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);

        manager.updateExternalVmDataFromPrepareAnswer(vmTO, updatedTO);

        verify(userVm).setVncPassword("newpass");
        verify(vmTO).setVncPassword("newpass");
    }

    // ---- updateExternalVmNicsFromPrepareAnswer ----

    @Test
    public void updateExternalVmNicsNoOpsWhenOriginalNicsNull() {
        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        when(vmTO.getNics()).thenReturn(null);
        VirtualMachineTO updatedTO = mock(VirtualMachineTO.class);
        when(updatedTO.getNics()).thenReturn(new NicTO[]{mock(NicTO.class)});

        manager.updateExternalVmNicsFromPrepareAnswer(vmTO, updatedTO);

        verify(nicsDao, never()).update(anyLong(), any());
    }

    @Test
    public void updateExternalVmNicsUpdatesNicWhenIpAndMacDiffer() {
        NicTO original = mock(NicTO.class);
        when(original.getNicUuid()).thenReturn("uuid-1");
        when(original.getMac()).thenReturn("aa:bb:cc:dd:ee:01");
        lenient().when(original.getIp()).thenReturn("10.0.0.1");
        lenient().when(original.getIp6Address()).thenReturn(null);
        NicTO updated = mock(NicTO.class);
        when(updated.getNicUuid()).thenReturn("uuid-1");
        when(updated.getMac()).thenReturn("aa:bb:cc:dd:ee:02");
        when(updated.getIp()).thenReturn("10.0.0.2");
        when(updated.getIp6Address()).thenReturn(null);

        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        when(vmTO.getNics()).thenReturn(new NicTO[]{original});
        VirtualMachineTO updatedTO = mock(VirtualMachineTO.class);
        when(updatedTO.getNics()).thenReturn(new NicTO[]{updated});

        NicVO nicVO = mock(NicVO.class);
        when(nicVO.getId()).thenReturn(100L);
        when(nicVO.getIPv4Address()).thenReturn("10.0.0.1");
        when(nicVO.getMacAddress()).thenReturn("aa:bb:cc:dd:ee:01");
        when(nicsDao.findByUuid("uuid-1")).thenReturn(nicVO);

        manager.updateExternalVmNicsFromPrepareAnswer(vmTO, updatedTO);

        verify(nicVO).setIPv4Address("10.0.0.2");
        verify(nicVO).setMacAddress("aa:bb:cc:dd:ee:02");
        verify(nicsDao).update(eq(100L), eq(nicVO));
    }

    @Test
    public void updateExternalVmNicsSkipsWhenNicNotFound() {
        NicTO original = mock(NicTO.class);
        when(original.getNicUuid()).thenReturn("uuid-1");
        when(original.getMac()).thenReturn("aa:bb:cc:dd:ee:01");
        lenient().when(original.getIp()).thenReturn("10.0.0.1");
        lenient().when(original.getIp6Address()).thenReturn(null);
        NicTO updated = mock(NicTO.class);
        when(updated.getNicUuid()).thenReturn("uuid-1");
        when(updated.getMac()).thenReturn("aa:bb:cc:dd:ee:02");
        when(updated.getIp()).thenReturn("10.0.0.1");
        when(updated.getIp6Address()).thenReturn(null);

        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        when(vmTO.getNics()).thenReturn(new NicTO[]{original});
        VirtualMachineTO updatedTO = mock(VirtualMachineTO.class);
        when(updatedTO.getNics()).thenReturn(new NicTO[]{updated});
        when(nicsDao.findByUuid("uuid-1")).thenReturn(null);

        manager.updateExternalVmNicsFromPrepareAnswer(vmTO, updatedTO);

        verify(nicsDao, never()).update(anyLong(), any());
    }

    // ---- updateExternalVmFromPrepareAnswer ----

    @Test
    public void updateExternalVmFromPrepareAnswerNoOpsWhenUpdatedNull() {
        VirtualMachineTO vmTO = mockVmTO(VM_ID);

        manager.updateExternalVmFromPrepareAnswer(vmTO, null);

        verify(userVmDao, never()).findById(anyLong());
        verify(nicsDao, never()).update(anyLong(), any());
    }

    // ---- processPrepareExternalProvisioning ----

    @Test
    public void processPrepareExternalProvisioningThrowsOnAgentUnavailable() throws Exception {
        Host host = mockHost(HypervisorType.External);
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        DataCenter dataCenter = mock(DataCenter.class);
        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        when(vmTO.getNics()).thenReturn(new NicTO[0]);
        when(vmTO.getExternalDetails()).thenReturn(Collections.emptyMap());
        when(nicsDao.listByVmId(anyLong())).thenReturn(Collections.emptyList());
        when(extensionsManager.getExternalAccessDetails(any(Host.class), anyMap())).thenReturn(Collections.emptyMap());
        when(agentMgr.send(eq(HOST_ID), any(com.cloud.agent.api.Command.class))).thenThrow(new AgentUnavailableException("boom", HOST_ID));

        assertThrows(CloudRuntimeException.class,
                () -> manager.processPrepareExternalProvisioning(true, host, vmProfile, dataCenter, vmTO));
    }

    @Test
    public void processPrepareExternalProvisioningThrowsOnNullAnswer() throws Exception {
        Host host = mockHost(HypervisorType.External);
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        DataCenter dataCenter = mock(DataCenter.class);
        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        when(vmTO.getNics()).thenReturn(new NicTO[]{mock(NicTO.class)});
        when(vmTO.getExternalDetails()).thenReturn(Collections.emptyMap());
        when(extensionsManager.getExternalAccessDetails(any(Host.class), anyMap())).thenReturn(Collections.emptyMap());
        when(agentMgr.send(eq(HOST_ID), any(com.cloud.agent.api.Command.class))).thenReturn(null);

        assertThrows(CloudRuntimeException.class,
                () -> manager.processPrepareExternalProvisioning(true, host, vmProfile, dataCenter, vmTO));
    }

    @Test
    public void processPrepareExternalProvisioningThrowsOnNegativeResult() throws Exception {
        Host host = mockHost(HypervisorType.External);
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        DataCenter dataCenter = mock(DataCenter.class);
        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        when(vmTO.getNics()).thenReturn(new NicTO[]{mock(NicTO.class)});
        when(vmTO.getExternalDetails()).thenReturn(Collections.emptyMap());
        when(extensionsManager.getExternalAccessDetails(any(Host.class), anyMap())).thenReturn(Collections.emptyMap());
        PrepareExternalProvisioningAnswer answer = mock(PrepareExternalProvisioningAnswer.class);
        when(answer.getResult()).thenReturn(false);
        when(agentMgr.send(eq(HOST_ID), any(com.cloud.agent.api.Command.class))).thenReturn((Answer) answer);

        assertThrows(CloudRuntimeException.class,
                () -> manager.processPrepareExternalProvisioning(true, host, vmProfile, dataCenter, vmTO));
    }

    @Test
    public void processPrepareExternalProvisioningPopulatesNicsWhenEmpty() throws Exception {
        Host host = mockHost(HypervisorType.External);
        VirtualMachine vm = mock(VirtualMachine.class);
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        when(vmProfile.getId()).thenReturn(VM_ID);
        when(vmProfile.getVirtualMachine()).thenReturn(vm);
        DataCenter dataCenter = mock(DataCenter.class);
        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        when(vmTO.getNics()).thenReturn(new NicTO[0]);
        when(vmTO.getExternalDetails()).thenReturn(Collections.emptyMap());

        NicVO nicVO = mock(NicVO.class);
        when(nicsDao.listByVmId(VM_ID)).thenReturn(Collections.singletonList(nicVO));
        NicProfile nicProfile = mock(NicProfile.class);
        when(networkModel.getNicProfile(eq(vm), eq(nicVO), eq(dataCenter))).thenReturn(nicProfile);
        HypervisorGuru guru = mock(HypervisorGuru.class);
        when(hvGuruMgr.getGuru(HypervisorType.External)).thenReturn(guru);
        NicTO nicTO = mock(NicTO.class);
        when(nicTO.getDeviceId()).thenReturn(0);
        when(guru.toNicTO(nicProfile)).thenReturn(nicTO);

        when(extensionsManager.getExternalAccessDetails(any(Host.class), anyMap())).thenReturn(Collections.emptyMap());
        PrepareExternalProvisioningAnswer answer = mock(PrepareExternalProvisioningAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getVirtualMachineTO()).thenReturn(null);
        when(agentMgr.send(eq(HOST_ID), any(com.cloud.agent.api.Command.class))).thenReturn((Answer) answer);

        manager.processPrepareExternalProvisioning(true, host, vmProfile, dataCenter, vmTO);

        verify(vmTO).setNics(any(NicTO[].class));
        verify(agentMgr).send(eq(HOST_ID), any(com.cloud.agent.api.Command.class));
    }

    // ---- updateStartCommandWithExternalDetails ----

    @Test
    public void updateStartCommandNoOpsForNonExternalHypervisor() {
        Host host = mockHost(HypervisorType.KVM);
        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        StartCommand command = mock(StartCommand.class);

        manager.updateStartCommandWithExternalDetails(host, vmTO, command);

        verify(command, never()).setExternalDetails(anyMap());
    }

    @Test
    public void updateStartCommandSetsExternalDetailsForExternalHypervisor() {
        Host host = mockHost(HypervisorType.External);
        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        NicTO defaultNic = mock(NicTO.class);
        when(defaultNic.isDefaultNic()).thenReturn(true);
        when(vmTO.getNics()).thenReturn(new NicTO[]{defaultNic});
        Map<String, String> details = new HashMap<>();
        when(vmTO.getExternalDetails()).thenReturn(details);
        when(networkService.getNicVlanValueForExternalVm(defaultNic)).thenReturn("vlan-42");
        Map<String, Map<String, String>> resolved = new HashMap<>();
        when(extensionsManager.getExternalAccessDetails(eq(host), eq(details))).thenReturn(resolved);
        StartCommand command = mock(StartCommand.class);

        manager.updateStartCommandWithExternalDetails(host, vmTO, command);

        assertEquals("vlan-42", details.get(VmDetailConstants.CLOUDSTACK_VLAN));
        verify(command).setExternalDetails(resolved);
    }

    // ---- updateStopCommandForExternalHypervisorType ----

    @Test
    public void updateStopCommandNoOpsForNonExternalHypervisor() {
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        StopCommand stop = mock(StopCommand.class);
        VirtualMachineTO vmTO = mockVmTO(VM_ID);

        manager.updateStopCommandForExternalHypervisorType(HypervisorType.KVM, vmProfile, stop, vmTO);

        verify(stop, never()).setExternalDetails(anyMap());
        verify(hostDao, never()).findById(anyLong());
    }

    @Test
    public void updateStopCommandNoOpsWhenHostIdNull() {
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        when(vmProfile.getHostId()).thenReturn(null);
        StopCommand stop = mock(StopCommand.class);
        VirtualMachineTO vmTO = mockVmTO(VM_ID);

        manager.updateStopCommandForExternalHypervisorType(HypervisorType.External, vmProfile, stop, vmTO);

        verify(hostDao, never()).findById(anyLong());
    }

    @Test
    public void updateStopCommandNoOpsWhenHostNotFound() {
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        when(vmProfile.getHostId()).thenReturn(HOST_ID);
        when(hostDao.findById(HOST_ID)).thenReturn(null);
        StopCommand stop = mock(StopCommand.class);
        VirtualMachineTO vmTO = mockVmTO(VM_ID);

        manager.updateStopCommandForExternalHypervisorType(HypervisorType.External, vmProfile, stop, vmTO);

        verify(stop, never()).setExternalDetails(anyMap());
    }

    @Test
    public void updateStopCommandPopulatesDetailsAndClearsEmptyMaps() {
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        when(vmProfile.getHostId()).thenReturn(HOST_ID);
        HostVO host = mock(HostVO.class);
        when(hostDao.findById(HOST_ID)).thenReturn(host);

        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        when(vmTO.getGuestOsDetails()).thenReturn(Collections.emptyMap());
        when(vmTO.getExtraConfig()).thenReturn(Collections.emptyMap());
        when(vmTO.getNetworkIdToNetworkNameMap()).thenReturn(Collections.emptyMap());
        when(vmTO.getExternalDetails()).thenReturn(Collections.emptyMap());

        Map<String, Map<String, String>> resolved = new HashMap<>();
        when(extensionsManager.getExternalAccessDetails(eq(host), anyMap())).thenReturn(resolved);
        StopCommand stop = mock(StopCommand.class);

        manager.updateStopCommandForExternalHypervisorType(HypervisorType.External, vmProfile, stop, vmTO);

        verify(vmTO).setGuestOsDetails(null);
        verify(vmTO).setExtraConfig(null);
        verify(vmTO).setNetworkIdToNetworkNameMap(null);
        verify(stop).setVirtualMachine(vmTO);
        verify(stop).setExternalDetails(resolved);
    }

    // ---- updateRebootCommandWithExternalDetails ----

    @Test
    public void updateRebootCommandNoOpsForNonExternalHypervisor() {
        Host host = mockHost(HypervisorType.VMware);
        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        RebootCommand cmd = mock(RebootCommand.class);

        manager.updateRebootCommandWithExternalDetails(host, vmTO, cmd);

        verify(cmd, never()).setExternalDetails(anyMap());
    }

    @Test
    public void updateRebootCommandSetsExternalDetailsForExternalHypervisor() {
        Host host = mockHost(HypervisorType.External);
        VirtualMachineTO vmTO = mockVmTO(VM_ID);
        Map<String, String> details = Collections.emptyMap();
        when(vmTO.getExternalDetails()).thenReturn(details);
        Map<String, Map<String, String>> resolved = new HashMap<>();
        when(extensionsManager.getExternalAccessDetails(eq(host), eq(details))).thenReturn(resolved);
        RebootCommand cmd = mock(RebootCommand.class);

        manager.updateRebootCommandWithExternalDetails(host, vmTO, cmd);

        verify(cmd).setExternalDetails(resolved);
    }
}
