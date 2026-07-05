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
package org.apache.cloudstack.network.routeros;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.network.routeros.api.RouterOSApiClient;
import org.apache.cloudstack.network.routeros.rules.RouterOSRule;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.LinkedHashMap;

import com.cloud.deploy.DeploymentPlan;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.RouterOSDeviceDao;
import com.cloud.network.element.RouterOSDeviceVO;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.StaticNat;
import com.cloud.network.router.VirtualRouter;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.net.Ip;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.NicProfile;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.DomainRouterDao;

/**
 * Unit tests for the ordering / lifecycle behaviour of {@link RouterOSVmManagerImpl}
 * that cannot be expressed in the pure translator: place-before anchoring of
 * static-NAT and egress rules, and skipping of the system default-egress rule.
 */
public class RouterOSVmManagerImplTest {

    private RouterOSVmManagerImpl manager;
    private RouterOSApiClient client;
    private RouterOSDeviceVO device;
    private Network network;

    @Before
    public void setUp() throws Exception {
        manager = org.mockito.Mockito.spy(new RouterOSVmManagerImpl());
        manager._ipAddressDao = mock(IPAddressDao.class);
        manager._networkOfferingDao = mock(NetworkOfferingDao.class);
        manager._routerOSDeviceDao = mock(RouterOSDeviceDao.class);

        client = mock(RouterOSApiClient.class);
        device = mock(RouterOSDeviceVO.class);
        network = mock(Network.class);
        when(network.getId()).thenReturn(100L);
        when(network.getUuid()).thenReturn("net-uuid");
        when(network.getNetworkOfferingId()).thenReturn(7L);

        doReturn(device).when(manager).getDeviceForNetwork(network);
        doReturn(client).when(manager).getActiveClient(device, network);
        doReturn("ether1").when(manager).publicInterfaceName(client, device);
    }

    private static Map<String, String> idMap(final String id) {
        return Collections.singletonMap(RouterOSApiClient.ID_FIELD, id);
    }

    @Test
    public void testStaticNatSrcNatLegIsPlacedBeforeNetworkSrcNat() throws Exception {
        // network source-NAT anchor present
        when(client.listByComment(RouterOSApiClient.PATH_FIREWALL_NAT, "cs-net-net-uuid-srcnat"))
                .thenReturn(new ArrayList<>(Arrays.asList(idMap("*NET"))));

        final StaticNat rule = mock(StaticNat.class);
        when(rule.isForRevoke()).thenReturn(false);
        when(rule.getSourceIpAddressId()).thenReturn(42L);
        when(rule.getDestIpAddress()).thenReturn("10.1.1.60");
        final IPAddressVO ip = mock(IPAddressVO.class);
        when(ip.getAddress()).thenReturn(new Ip("203.0.113.20"));
        when(manager._ipAddressDao.findById(42L)).thenReturn(ip);

        manager.applyStaticNats(network, Arrays.asList(rule));

        final ArgumentCaptor<List<RouterOSRule>> captor = ArgumentCaptor.forClass(List.class);
        verify(client).ensureRules(eq("cs-staticnat-42"), captor.capture());
        final List<RouterOSRule> pair = captor.getValue();
        assertEquals(2, pair.size());
        // the dst-nat leg is not anchored, the src-nat leg is placed before the network srcnat rule
        RouterOSRule srcNat = null;
        RouterOSRule dstNat = null;
        for (final RouterOSRule r : pair) {
            if ("srcnat".equals(r.getParam("chain"))) {
                srcNat = r;
            } else {
                dstNat = r;
            }
        }
        assertEquals("*NET", srcNat.getParam(RouterOSRule.PLACE_BEFORE));
        assertTrue("dst-nat leg must not carry place-before", dstNat.getParam(RouterOSRule.PLACE_BEFORE) == null);
    }

    @Test
    public void testEgressUserRuleIsPlacedBeforeEgressDefaultWithDefaultDenyPolicy() throws Exception {
        final NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        when(offering.isEgressDefaultPolicy()).thenReturn(false); // default Deny => accept rules
        when(manager._networkOfferingDao.findById(7L)).thenReturn(offering);
        when(client.listByComment(RouterOSApiClient.PATH_FIREWALL_FILTER, "cs-net-net-uuid-egress-default"))
                .thenReturn(new ArrayList<>(Arrays.asList(idMap("*EG"))));

        final FirewallRule rule = mock(FirewallRule.class);
        when(rule.getUuid()).thenReturn("e1");
        when(rule.getType()).thenReturn(FirewallRule.FirewallRuleType.User);
        when(rule.getState()).thenReturn(FirewallRule.State.Add);
        when(rule.getTrafficType()).thenReturn(FirewallRule.TrafficType.Egress);
        when(rule.getProtocol()).thenReturn("tcp");
        when(rule.getSourcePortStart()).thenReturn(443);
        when(rule.getSourcePortEnd()).thenReturn(443);
        when(rule.getSourceCidrList()).thenReturn(Arrays.asList("10.1.1.0/24"));

        manager.applyFirewallRules(network, Arrays.asList(rule));

        final ArgumentCaptor<List<RouterOSRule>> captor = ArgumentCaptor.forClass(List.class);
        verify(client).ensureRules(eq("cs-fw-e1"), captor.capture());
        final List<RouterOSRule> rules = captor.getValue();
        assertEquals(1, rules.size());
        assertEquals("accept", rules.get(0).getParam("action"));
        assertEquals("*EG", rules.get(0).getParam(RouterOSRule.PLACE_BEFORE));
    }

    @Test
    public void testSystemEgressRuleIsSkipped() throws Exception {
        final NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        when(offering.isEgressDefaultPolicy()).thenReturn(true);
        when(manager._networkOfferingDao.findById(7L)).thenReturn(offering);
        when(client.listByComment(RouterOSApiClient.PATH_FIREWALL_FILTER, "cs-net-net-uuid-egress-default"))
                .thenReturn(new ArrayList<>(Arrays.asList(idMap("*EG"))));

        final FirewallRule systemRule = mock(FirewallRule.class);
        when(systemRule.getType()).thenReturn(FirewallRule.FirewallRuleType.System);

        manager.applyFirewallRules(network, Arrays.asList(systemRule));

        // the System default-egress rule must be ignored (programGuestNetwork owns it)
        verify(client, never()).ensureRules(anyString(), any());
        verify(client, never()).removeByComment(anyString(), any(String[].class));
    }

    @Test
    public void testExpungedApplianceRowIsRemovedForVpc() throws Exception {
        // finding 8: an expunged backing VM must not brick the network forever
        when(device.getVmInstanceId()).thenReturn(null);
        assertTrue("device with no backing VM must be reported as gone", !manager.applianceVmExists(device));
    }

    @Test
    public void testAllocateApplianceCreatesSystemTypedRouter() throws Exception {
        manager._routerDao = mock(DomainRouterDao.class);
        manager._templateDao = mock(VMTemplateDao.class);
        manager._serviceOfferingDao = mock(ServiceOfferingDao.class);
        manager._accountMgr = mock(AccountManager.class);
        manager._itMgr = mock(VirtualMachineManager.class);

        final VMTemplateVO template = mock(VMTemplateVO.class);
        when(template.getId()).thenReturn(11L);
        when(template.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(template.getGuestOSId()).thenReturn(1L);
        when(manager._templateDao.findValidByTemplateName(anyString())).thenReturn(template);

        final ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(offering.getId()).thenReturn(5L);
        doReturn(offering).when(manager).findServiceOffering(1L);
        doReturn(77L).when(manager).findVirtualRouterProviderId(100L, null, 1L);

        final Account systemAccount = mock(Account.class);
        when(systemAccount.getDomainId()).thenReturn(1L);
        when(systemAccount.getId()).thenReturn(1L);
        when(manager._accountMgr.getSystemAccount()).thenReturn(systemAccount);

        when(manager._routerDao.getNextInSequence(Long.class, "id")).thenReturn(123L);
        final DomainRouterVO[] persisted = new DomainRouterVO[1];
        when(manager._routerDao.persist(any(DomainRouterVO.class))).thenAnswer(inv -> {
            persisted[0] = inv.getArgument(0);
            return persisted[0];
        });
        when(manager._routerDao.findById(123L)).thenAnswer(inv -> persisted[0]);
        when(manager._routerOSDeviceDao.persist(any())).thenAnswer(inv -> inv.getArgument(0));

        final DeploymentPlan plan = mock(DeploymentPlan.class);
        when(plan.getDataCenterId()).thenReturn(1L);

        manager.allocateAppliance(100L, null, new LinkedHashMap<Network, List<? extends NicProfile>>(), plan, "203.0.113.20");

        assertEquals(VirtualMachine.Type.RouterOSVm, persisted[0].getType());
        assertEquals(VirtualRouter.Role.ROUTEROS_VM, persisted[0].getRole());
        assertEquals(77L, persisted[0].getElementId());
        assertEquals(1L, persisted[0].getAccountId());
    }
}
