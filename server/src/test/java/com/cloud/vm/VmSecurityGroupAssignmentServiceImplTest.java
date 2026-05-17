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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.command.user.vm.DeployVMCmd;
import org.apache.cloudstack.api.command.user.vm.DeployVnfApplianceCmd;
import org.apache.cloudstack.storage.template.VnfTemplateManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.security.SecurityGroup;
import com.cloud.network.security.SecurityGroupManager;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.vm.VirtualMachine.State;

/**
 * Unit tests for {@link VmSecurityGroupAssignmentServiceImpl} — the
 * security-group ID resolution / stopped-VM reassignment helpers
 * extracted from {@code UserVmManagerImpl} as slice 14 of the Phase 4
 * Spring-component decomposition.
 */
@RunWith(MockitoJUnitRunner.class)
public class VmSecurityGroupAssignmentServiceImplTest {

    @Mock private SecurityGroupManager securityGroupManager;
    @Mock private VnfTemplateManager vnfTemplateManager;
    @Mock private DataCenterDao dataCenterDao;
    @Mock private NetworkModel networkModel;
    @Mock private AccountManager accountManager;

    private VmSecurityGroupAssignmentServiceImpl service;

    @Before
    public void setUp() {
        service = new VmSecurityGroupAssignmentServiceImpl();
        ReflectionTestUtils.setField(service, "securityGroupManager", securityGroupManager);
        ReflectionTestUtils.setField(service, "vnfTemplateManager", vnfTemplateManager);
        ReflectionTestUtils.setField(service, "dataCenterDao", dataCenterDao);
        ReflectionTestUtils.setField(service, "networkModel", networkModel);
        ReflectionTestUtils.setField(service, "accountManager", accountManager);
    }

    // ---- getSecurityGroupIdList(cmd) ----

    @Test
    public void getSecurityGroupIdListRejectsBothNamesAndIds() {
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        when(cmd.getSecurityGroupNameList()).thenReturn(Arrays.asList("g1"));
        when(cmd.getSecurityGroupIdList()).thenReturn(Arrays.asList(7L));

        assertThrows(InvalidParameterValueException.class,
                () -> service.getSecurityGroupIdList(cmd));
        verify(securityGroupManager, never()).getSecurityGroup(anyString(), anyLong());
    }

    @Test
    public void getSecurityGroupIdListResolvesNamesToIds() {
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        when(cmd.getSecurityGroupNameList()).thenReturn(Arrays.asList("alpha", "beta"));
        when(cmd.getSecurityGroupIdList()).thenReturn(null);
        when(cmd.getEntityOwnerId()).thenReturn(42L);

        SecurityGroup sgA = mock(SecurityGroup.class);
        when(sgA.getId()).thenReturn(10L);
        SecurityGroup sgB = mock(SecurityGroup.class);
        when(sgB.getId()).thenReturn(11L);
        when(securityGroupManager.getSecurityGroup("alpha", 42L)).thenReturn(sgA);
        when(securityGroupManager.getSecurityGroup("beta", 42L)).thenReturn(sgB);

        List<Long> ids = service.getSecurityGroupIdList(cmd);
        assertEquals(Arrays.asList(10L, 11L), ids);
    }

    @Test
    public void getSecurityGroupIdListThrowsWhenNameMissing() {
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        when(cmd.getSecurityGroupNameList()).thenReturn(Arrays.asList("ghost"));
        when(cmd.getSecurityGroupIdList()).thenReturn(null);
        when(cmd.getEntityOwnerId()).thenReturn(42L);
        when(securityGroupManager.getSecurityGroup("ghost", 42L)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.getSecurityGroupIdList(cmd));
        assertTrue(ex.getMessage().contains("ghost"));
    }

    @Test
    public void getSecurityGroupIdListPassesThroughIdsWhenNoNames() {
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        when(cmd.getSecurityGroupNameList()).thenReturn(null);
        when(cmd.getSecurityGroupIdList()).thenReturn(Arrays.asList(5L, 6L));

        List<Long> ids = service.getSecurityGroupIdList(cmd);
        assertEquals(Arrays.asList(5L, 6L), ids);
        verify(securityGroupManager, never()).getSecurityGroup(anyString(), anyLong());
    }

    @Test
    public void getSecurityGroupIdListReturnsNullWhenNothingProvided() {
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        when(cmd.getSecurityGroupNameList()).thenReturn(null);
        when(cmd.getSecurityGroupIdList()).thenReturn(null);

        assertNull(service.getSecurityGroupIdList(cmd));
    }

    // ---- getSecurityGroupIdList(cmd, zone, template, owner) ----

    @Test
    public void getSecurityGroupIdListWithZoneSkipsVnfInjectionForNonVnfCmd() {
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        when(cmd.getSecurityGroupNameList()).thenReturn(null);
        when(cmd.getSecurityGroupIdList()).thenReturn(Arrays.asList(1L));

        DataCenter zone = mock(DataCenter.class);
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        Account owner = mock(Account.class);

        List<Long> ids = service.getSecurityGroupIdList(cmd, zone, template, owner);
        assertEquals(Arrays.asList(1L), ids);
        verify(vnfTemplateManager, never()).createSecurityGroupForVnfAppliance(any(), any(), any(), any());
    }

    @Test
    public void getSecurityGroupIdListWithZoneInjectsVnfGroup() {
        DeployVnfApplianceCmd cmd = mock(DeployVnfApplianceCmd.class);
        when(cmd.getSecurityGroupNameList()).thenReturn(null);
        when(cmd.getSecurityGroupIdList()).thenReturn(new java.util.ArrayList<>(Arrays.asList(1L)));

        DataCenter zone = mock(DataCenter.class);
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        Account owner = mock(Account.class);

        SecurityGroup vnfSg = mock(SecurityGroup.class);
        when(vnfSg.getId()).thenReturn(99L);
        when(vnfTemplateManager.createSecurityGroupForVnfAppliance(zone, template, owner, cmd)).thenReturn(vnfSg);

        List<Long> ids = service.getSecurityGroupIdList(cmd, zone, template, owner);
        assertEquals(Arrays.asList(1L, 99L), ids);
    }

    @Test
    public void getSecurityGroupIdListWithZoneCreatesListWhenVnfGroupNeeded() {
        DeployVnfApplianceCmd cmd = mock(DeployVnfApplianceCmd.class);
        when(cmd.getSecurityGroupNameList()).thenReturn(null);
        when(cmd.getSecurityGroupIdList()).thenReturn(null);

        DataCenter zone = mock(DataCenter.class);
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        Account owner = mock(Account.class);

        SecurityGroup vnfSg = mock(SecurityGroup.class);
        when(vnfSg.getId()).thenReturn(77L);
        when(vnfTemplateManager.createSecurityGroupForVnfAppliance(zone, template, owner, cmd)).thenReturn(vnfSg);

        List<Long> ids = service.getSecurityGroupIdList(cmd, zone, template, owner);
        assertNotNull(ids);
        assertEquals(Arrays.asList(77L), ids);
    }

    @Test
    public void getSecurityGroupIdListWithZoneLeavesListUnchangedWhenVnfReturnsNull() {
        DeployVnfApplianceCmd cmd = mock(DeployVnfApplianceCmd.class);
        when(cmd.getSecurityGroupNameList()).thenReturn(null);
        when(cmd.getSecurityGroupIdList()).thenReturn(Arrays.asList(3L));

        DataCenter zone = mock(DataCenter.class);
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        Account owner = mock(Account.class);

        when(vnfTemplateManager.createSecurityGroupForVnfAppliance(zone, template, owner, cmd)).thenReturn(null);

        List<Long> ids = service.getSecurityGroupIdList(cmd, zone, template, owner);
        assertEquals(Arrays.asList(3L), ids);
    }

    // ---- checkAndUpdateSecurityGroupForVM ----

    @Test
    public void checkAndUpdateSecurityGroupForVMNoOpWhenIdListNull() {
        UserVmVO vm = mock(UserVmVO.class);
        service.checkAndUpdateSecurityGroupForVM(null, vm, Collections.emptyList());
        verify(dataCenterDao, never()).findById(anyLong());
        verify(networkModel, never()).checkSecurityGroupSupportForNetwork(any(), any(), any(), any());
    }

    @Test
    public void checkAndUpdateSecurityGroupForVMRejectsVMware() {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.VMware);

        assertThrows(InvalidParameterValueException.class,
                () -> service.checkAndUpdateSecurityGroupForVM(Arrays.asList(1L), vm, Collections.emptyList()));
        verify(dataCenterDao, never()).findById(anyLong());
    }

    @Test
    public void checkAndUpdateSecurityGroupForVMResolvesBasicZoneToExclusiveGuestNetwork() {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vm.getDataCenterId()).thenReturn(1L);
        when(vm.getAccountId()).thenReturn(42L);
        when(vm.getState()).thenReturn(State.Stopped);

        DataCenterVO zone = mock(DataCenterVO.class);
        when(zone.getId()).thenReturn(1L);
        when(zone.getNetworkType()).thenReturn(NetworkType.Basic);
        when(dataCenterDao.findById(1L)).thenReturn(zone);

        Network defaultNetwork = mock(Network.class);
        when(defaultNetwork.getId()).thenReturn(101L);
        when(networkModel.getExclusiveGuestNetwork(1L)).thenReturn(defaultNetwork);

        AccountVO account = mock(AccountVO.class);
        when(accountManager.getActiveAccountById(42L)).thenReturn(account);

        when(networkModel.checkSecurityGroupSupportForNetwork(eq(account), eq(zone), anyList(), eq(Arrays.asList(8L))))
                .thenReturn(true);

        service.checkAndUpdateSecurityGroupForVM(Arrays.asList(8L), vm, Collections.emptyList());

        verify(networkModel).getExclusiveGuestNetwork(1L);
        verify(securityGroupManager).removeInstanceFromGroups(vm);
        verify(securityGroupManager).addInstanceToGroups(vm, Arrays.asList(8L));
    }

    @Test
    public void checkAndUpdateSecurityGroupForVMUsesNetworksDirectlyInAdvancedZone() {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vm.getDataCenterId()).thenReturn(1L);
        when(vm.getAccountId()).thenReturn(42L);
        when(vm.getState()).thenReturn(State.Stopped);

        DataCenterVO zone = mock(DataCenterVO.class);
        when(zone.getNetworkType()).thenReturn(NetworkType.Advanced);
        when(dataCenterDao.findById(1L)).thenReturn(zone);

        NetworkVO net = mock(NetworkVO.class);
        when(net.getId()).thenReturn(200L);

        AccountVO account = mock(AccountVO.class);
        when(accountManager.getActiveAccountById(42L)).thenReturn(account);

        when(networkModel.checkSecurityGroupSupportForNetwork(eq(account), eq(zone), eq(Arrays.asList(200L)), eq(Arrays.asList(8L))))
                .thenReturn(true);

        service.checkAndUpdateSecurityGroupForVM(Arrays.asList(8L), vm, Arrays.asList(net));

        verify(networkModel, never()).getExclusiveGuestNetwork(anyLong());
        verify(securityGroupManager).removeInstanceFromGroups(vm);
        verify(securityGroupManager).addInstanceToGroups(vm, Arrays.asList(8L));
    }

    @Test
    public void checkAndUpdateSecurityGroupForVMSwallowsInvalidParamExceptionWhenLookingUpDefaultNetwork() {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vm.getDataCenterId()).thenReturn(1L);
        when(vm.getAccountId()).thenReturn(42L);

        DataCenterVO zone = mock(DataCenterVO.class);
        when(zone.getId()).thenReturn(1L);
        when(zone.getNetworkType()).thenReturn(NetworkType.Basic);
        when(dataCenterDao.findById(1L)).thenReturn(zone);

        // The Basic-zone exclusive-guest-network lookup may fail — the
        // caller swallows it, falling through with an empty network list.
        when(networkModel.getExclusiveGuestNetwork(1L))
                .thenThrow(new InvalidParameterValueException("no exclusive guest network"));

        AccountVO account = mock(AccountVO.class);
        when(accountManager.getActiveAccountById(42L)).thenReturn(account);

        // Network model rejects the empty list -> updateSecurityGroup not invoked.
        when(networkModel.checkSecurityGroupSupportForNetwork(eq(account), eq(zone), eq(Collections.emptyList()), eq(Arrays.asList(9L))))
                .thenReturn(false);

        service.checkAndUpdateSecurityGroupForVM(Arrays.asList(9L), vm, Collections.emptyList());
        verify(securityGroupManager, never()).removeInstanceFromGroups(any());
        verify(securityGroupManager, never()).addInstanceToGroups(any(), anyList());
    }

    @Test
    public void checkAndUpdateSecurityGroupForVMSkipsUpdateWhenNetworkModelRejects() {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vm.getDataCenterId()).thenReturn(1L);
        when(vm.getAccountId()).thenReturn(42L);

        DataCenterVO zone = mock(DataCenterVO.class);
        when(zone.getNetworkType()).thenReturn(NetworkType.Advanced);
        when(dataCenterDao.findById(1L)).thenReturn(zone);

        AccountVO account = mock(AccountVO.class);
        when(accountManager.getActiveAccountById(42L)).thenReturn(account);

        when(networkModel.checkSecurityGroupSupportForNetwork(any(), any(), anyList(), anyList()))
                .thenReturn(false);

        service.checkAndUpdateSecurityGroupForVM(Arrays.asList(8L), vm, Collections.emptyList());
        verify(securityGroupManager, never()).removeInstanceFromGroups(any());
        verify(securityGroupManager, never()).addInstanceToGroups(any(), anyList());
    }

    // ---- updateSecurityGroup ----

    @Test
    public void updateSecurityGroupReplacesGroupsOnStoppedVm() {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getState()).thenReturn(State.Stopped);

        service.updateSecurityGroup(vm, Arrays.asList(11L, 12L));

        verify(securityGroupManager, times(1)).removeInstanceFromGroups(vm);
        verify(securityGroupManager, times(1)).addInstanceToGroups(vm, Arrays.asList(11L, 12L));
    }

    @Test
    public void updateSecurityGroupRejectsRunningVm() {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getUuid()).thenReturn("vm-uuid");
        when(vm.getState()).thenReturn(State.Running);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.updateSecurityGroup(vm, Arrays.asList(11L)));
        assertTrue(ex.getMessage().contains("vm-uuid"));
        assertTrue(ex.getMessage().contains("stopped"));
        verify(securityGroupManager, never()).removeInstanceFromGroups(any());
        verify(securityGroupManager, never()).addInstanceToGroups(any(), anyList());
    }

    @Test
    public void updateSecurityGroupAcceptsEmptyList() {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getState()).thenReturn(State.Stopped);

        service.updateSecurityGroup(vm, Collections.emptyList());
        verify(securityGroupManager).removeInstanceFromGroups(vm);
        verify(securityGroupManager).addInstanceToGroups(vm, Collections.emptyList());
    }

    // ---- Mixed scenarios ----

    @Test
    public void getSecurityGroupIdListResolvesSingleName() {
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        when(cmd.getSecurityGroupNameList()).thenReturn(Arrays.asList("solo"));
        when(cmd.getSecurityGroupIdList()).thenReturn(null);
        when(cmd.getEntityOwnerId()).thenReturn(7L);

        SecurityGroup sg = mock(SecurityGroup.class);
        when(sg.getId()).thenReturn(1234L);
        when(securityGroupManager.getSecurityGroup("solo", 7L)).thenReturn(sg);

        List<Long> ids = service.getSecurityGroupIdList(cmd);
        assertEquals(Arrays.asList(1234L), ids);
    }

    @Test
    public void getSecurityGroupIdListEmptyIdsListPassesThrough() {
        // An empty list is semantically different from null: the caller
        // wants the VM in zero security groups. The helper must hand
        // back the very same list reference unchanged.
        List<Long> empty = Collections.emptyList();
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        when(cmd.getSecurityGroupNameList()).thenReturn(null);
        when(cmd.getSecurityGroupIdList()).thenReturn(empty);

        assertSame(empty, service.getSecurityGroupIdList(cmd));
    }
}
