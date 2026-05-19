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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.ResourceUnavailableException;
import com.cloud.kubernetes.cluster.KubernetesServiceHelper;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.element.NsxProviderVO;
import com.cloud.network.lb.LoadBalancingRulesManager;
import com.cloud.network.rules.FirewallManager;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.security.SecurityGroupManager;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmExpungeResourceCleanupServiceImplTest {

    private static final long VM_ID = 42L;
    private static final long ZONE_ID = 99L;
    private static final long FIRST_IP_ID = 101L;
    private static final long SECOND_IP_ID = 102L;

    @Mock
    private UserVmDao userVmDao;
    @Mock
    private NetworkOrchestrationService networkMgr;
    @Mock
    private SecurityGroupManager securityGroupMgr;
    @Mock
    private VmGroupService vmGroupService;
    @Mock
    private FirewallManager firewallMgr;
    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private NsxProviderDao nsxProviderDao;
    @Mock
    private KubernetesServiceHelper kubernetesServiceHelper;
    @Mock
    private RulesManager rulesMgr;
    @Mock
    private LoadBalancingRulesManager lbMgr;
    @Mock
    private IPAddressDao ipAddressDao;
    @Mock
    private AccountManager accountMgr;
    @Mock
    private Account systemAccount;

    private VmExpungeResourceCleanupServiceImpl service;

    @Before
    public void setUp() {
        service = new VmExpungeResourceCleanupServiceImpl();
        ReflectionTestUtils.setField(service, "userVmDao", userVmDao);
        ReflectionTestUtils.setField(service, "networkMgr", networkMgr);
        ReflectionTestUtils.setField(service, "securityGroupMgr", securityGroupMgr);
        ReflectionTestUtils.setField(service, "vmGroupService", vmGroupService);
        ReflectionTestUtils.setField(service, "firewallMgr", firewallMgr);
        ReflectionTestUtils.setField(service, "vmInstanceDao", vmInstanceDao);
        ReflectionTestUtils.setField(service, "nsxProviderDao", nsxProviderDao);
        ReflectionTestUtils.setField(service, "kubernetesServiceHelpers", List.of(kubernetesServiceHelper));
        ReflectionTestUtils.setField(service, "rulesMgr", rulesMgr);
        ReflectionTestUtils.setField(service, "lbMgr", lbMgr);
        ReflectionTestUtils.setField(service, "ipAddressDao", ipAddressDao);
        ReflectionTestUtils.setField(service, "accountMgr", accountMgr);
    }

    @Test
    public void releaseNetworkResourcesOnExpungeReleasesWhenVmExists() throws Exception {
        UserVmVO vm = mock(UserVmVO.class);
        when(userVmDao.findById(VM_ID)).thenReturn(vm);

        service.releaseNetworkResourcesOnExpunge(VM_ID);

        verify(networkMgr).release(any(VirtualMachineProfile.class), eq(false));
    }

    @Test
    public void releaseNetworkResourcesOnExpungeDoesNotReleaseWhenVmIsMissing() throws Exception {
        when(userVmDao.findById(VM_ID)).thenReturn(null);

        service.releaseNetworkResourcesOnExpunge(VM_ID);

        verify(networkMgr, never()).release(any(VirtualMachineProfile.class), eq(false));
    }

    @Test
    public void cleanupVmResourcesReturnsTrueWhenAllCleanupSucceeds() {
        UserVmVO vm = vm();
        VMInstanceVO vmInstance = vmInstance();
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vmInstance);
        when(nsxProviderDao.findByZoneId(ZONE_ID)).thenReturn(null);
        when(firewallMgr.revokeFirewallRulesForVm(VM_ID)).thenReturn(true);
        when(rulesMgr.revokePortForwardingRulesForVm(VM_ID)).thenReturn(true);
        when(lbMgr.removeVmFromLoadBalancers(VM_ID)).thenReturn(true);
        when(ipAddressDao.findAllByAssociatedVmId(VM_ID)).thenReturn(List.of());

        assertTrue(service.cleanupVmResources(vm));

        verify(securityGroupMgr).removeInstanceFromGroups(vm);
        verify(vmGroupService).removeInstanceFromInstanceGroup(VM_ID);
        verify(firewallMgr).revokeFirewallRulesForVm(VM_ID);
        verify(rulesMgr).revokePortForwardingRulesForVm(VM_ID);
        verify(lbMgr).removeVmFromLoadBalancers(VM_ID);
    }

    @Test
    public void cleanupVmResourcesAccumulatesFalseAndContinuesWhenFirewallPortForwardingAndLoadBalancerFail() {
        UserVmVO vm = vm();
        VMInstanceVO vmInstance = vmInstance();
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vmInstance);
        when(nsxProviderDao.findByZoneId(ZONE_ID)).thenReturn(null);
        when(firewallMgr.revokeFirewallRulesForVm(VM_ID)).thenReturn(false);
        when(rulesMgr.revokePortForwardingRulesForVm(VM_ID)).thenReturn(false);
        when(lbMgr.removeVmFromLoadBalancers(VM_ID)).thenReturn(false);
        when(ipAddressDao.findAllByAssociatedVmId(VM_ID)).thenReturn(List.of());

        assertFalse(service.cleanupVmResources(vm));

        verify(firewallMgr).revokeFirewallRulesForVm(VM_ID);
        verify(rulesMgr).revokePortForwardingRulesForVm(VM_ID);
        verify(lbMgr).removeVmFromLoadBalancers(VM_ID);
    }

    @Test
    public void cleanupVmResourcesSkipsPortForwardingWhenNsxExistsAndKubernetesHelperFindsVm() {
        UserVmVO vm = vm();
        VMInstanceVO vmInstance = vmInstance();
        NsxProviderVO nsxProvider = mock(NsxProviderVO.class);
        ControlledEntity controlledEntity = mock(ControlledEntity.class);
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vmInstance);
        when(nsxProviderDao.findByZoneId(ZONE_ID)).thenReturn(nsxProvider);
        when(kubernetesServiceHelper.findByVmId(VM_ID)).thenReturn(controlledEntity);
        when(firewallMgr.revokeFirewallRulesForVm(VM_ID)).thenReturn(true);
        when(lbMgr.removeVmFromLoadBalancers(VM_ID)).thenReturn(true);
        when(ipAddressDao.findAllByAssociatedVmId(VM_ID)).thenReturn(List.of());

        assertTrue(service.cleanupVmResources(vm));

        verify(rulesMgr, never()).revokePortForwardingRulesForVm(VM_ID);
    }

    @Test
    public void cleanupVmResourcesDisablesStaticNatForEveryAssociatedIp() throws Exception {
        UserVmVO vm = vm();
        IPAddressVO firstIp = ip(FIRST_IP_ID);
        IPAddressVO secondIp = ip(SECOND_IP_ID);
        VMInstanceVO vmInstance = vmInstance();
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vmInstance);
        when(nsxProviderDao.findByZoneId(ZONE_ID)).thenReturn(null);
        when(firewallMgr.revokeFirewallRulesForVm(VM_ID)).thenReturn(true);
        when(rulesMgr.revokePortForwardingRulesForVm(VM_ID)).thenReturn(true);
        when(lbMgr.removeVmFromLoadBalancers(VM_ID)).thenReturn(true);
        when(ipAddressDao.findAllByAssociatedVmId(VM_ID)).thenReturn(List.of(firstIp, secondIp));
        when(accountMgr.getAccount(Account.ACCOUNT_ID_SYSTEM)).thenReturn(systemAccount);
        when(rulesMgr.disableStaticNat(FIRST_IP_ID, systemAccount, User.UID_SYSTEM, true)).thenReturn(true);
        when(rulesMgr.disableStaticNat(SECOND_IP_ID, systemAccount, User.UID_SYSTEM, true)).thenReturn(true);

        assertTrue(service.cleanupVmResources(vm));

        verify(rulesMgr).disableStaticNat(FIRST_IP_ID, systemAccount, User.UID_SYSTEM, true);
        verify(rulesMgr).disableStaticNat(SECOND_IP_ID, systemAccount, User.UID_SYSTEM, true);
    }

    @Test
    public void cleanupVmResourcesReturnsFalseWhenDisableStaticNatFailsOrThrows() throws Exception {
        UserVmVO vm = vm();
        IPAddressVO firstIp = ip(FIRST_IP_ID);
        IPAddressVO secondIp = ip(SECOND_IP_ID);
        VMInstanceVO vmInstance = vmInstance();
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vmInstance);
        when(nsxProviderDao.findByZoneId(ZONE_ID)).thenReturn(null);
        when(firewallMgr.revokeFirewallRulesForVm(VM_ID)).thenReturn(true);
        when(rulesMgr.revokePortForwardingRulesForVm(VM_ID)).thenReturn(true);
        when(lbMgr.removeVmFromLoadBalancers(VM_ID)).thenReturn(true);
        when(ipAddressDao.findAllByAssociatedVmId(VM_ID)).thenReturn(List.of(firstIp, secondIp));
        when(accountMgr.getAccount(Account.ACCOUNT_ID_SYSTEM)).thenReturn(systemAccount);
        when(rulesMgr.disableStaticNat(FIRST_IP_ID, systemAccount, User.UID_SYSTEM, true)).thenReturn(false);
        when(rulesMgr.disableStaticNat(SECOND_IP_ID, systemAccount, User.UID_SYSTEM, true)).thenThrow(new ResourceUnavailableException("unavailable", IPAddressVO.class, SECOND_IP_ID));

        assertFalse(service.cleanupVmResources(vm));

        verify(rulesMgr).disableStaticNat(FIRST_IP_ID, systemAccount, User.UID_SYSTEM, true);
        verify(rulesMgr).disableStaticNat(SECOND_IP_ID, systemAccount, User.UID_SYSTEM, true);
    }

    private UserVmVO vm() {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getId()).thenReturn(VM_ID);
        return vm;
    }

    private VMInstanceVO vmInstance() {
        VMInstanceVO vmInstance = mock(VMInstanceVO.class);
        when(vmInstance.getDataCenterId()).thenReturn(ZONE_ID);
        return vmInstance;
    }

    private IPAddressVO ip(long id) {
        IPAddressVO ip = mock(IPAddressVO.class);
        when(ip.getId()).thenReturn(id);
        return ip;
    }
}
