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
package org.apache.cloudstack.network.routeros.element;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.junit.Before;
import org.junit.Test;

import com.cloud.exception.UnsupportedServiceException;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;

/**
 * Contract tests for the RouterOS network elements: provider identity,
 * capability maps and honest failures for unsupported services.
 */
public class RouterOSElementTest {

    private RouterOSElement element;
    private RouterOSVpcElement vpcElement;

    @Before
    public void setUp() {
        element = new RouterOSElement();
        element.setName("RouterOS");
        vpcElement = new RouterOSVpcElement();
        vpcElement.setName("VpcRouterOS");
    }

    @Test
    public void testProviderIdentities() {
        assertEquals(Provider.RouterOS, element.getProvider());
        assertEquals("RouterOS", element.getProvider().getName());
        assertEquals(Provider.VpcRouterOS, vpcElement.getProvider());
        assertEquals("VpcRouterOS", vpcElement.getProvider().getName());
        assertFalse(element.getProvider().isExternal());
    }

    @Test
    public void testIsolatedCapabilitiesCoverCoreRouterServices() {
        final Map<Service, Map<Capability, String>> capabilities = element.getCapabilities();
        assertTrue(capabilities.containsKey(Service.Gateway));
        assertTrue(capabilities.containsKey(Service.Dhcp));
        assertTrue(capabilities.containsKey(Service.SourceNat));
        assertTrue(capabilities.containsKey(Service.StaticNat));
        assertTrue(capabilities.containsKey(Service.PortForwarding));
        assertTrue(capabilities.containsKey(Service.Firewall));
        assertEquals("peraccount", capabilities.get(Service.SourceNat).get(Capability.SupportedSourceNatTypes));
        assertEquals("tcp,udp,icmp", capabilities.get(Service.Firewall).get(Capability.SupportedProtocols));
    }

    @Test
    public void testUnsupportedServicesAreNotAdvertised() {
        final Map<Service, Map<Capability, String>> capabilities = element.getCapabilities();
        assertFalse("LB must not be advertised", capabilities.containsKey(Service.Lb));
        assertFalse("VPN must not be advertised", capabilities.containsKey(Service.Vpn));
        assertFalse("UserData must not be advertised", capabilities.containsKey(Service.UserData));
        assertNull(element.updateHealthChecks(null, null));
        assertFalse(element.handlesOnlyRulesInTransitionState());
    }

    @Test
    public void testVpcCapabilitiesAddNetworkAclAndDropFirewall() {
        final Map<Service, Map<Capability, String>> capabilities = vpcElement.getCapabilities();
        assertTrue(capabilities.containsKey(Service.NetworkACL));
        assertTrue(capabilities.containsKey(Service.SourceNat));
        assertFalse("Firewall service is replaced by ACLs inside VPCs", capabilities.containsKey(Service.Firewall));
    }

    // Load balancing / remote-access VPN / UserData are not advertised in the
    // capability map, so RouterOS is never selected for them. But core managers
    // call these SPI methods on EVERY registered provider for a network, so they
    // must be safe "not handled" no-ops (mirroring VirtualRouterElement) rather
    // than throwing, or they would break LB/VPN cloud-wide.

    @Test
    public void testLoadBalancingIsNonThrowingNoOp() throws Exception {
        assertTrue("applyLBRules must be a no-op returning true for foreign networks", element.applyLBRules(null, null));
    }

    @Test
    public void testLbValidationReturnsTrueWhenNotHandled() {
        // LoadBalancingRulesManagerImpl calls validateLBRule on all providers unguarded.
        assertTrue("validateLBRule must return true (no objection) when not handled", element.validateLBRule(null, null));
    }

    @Test
    public void testRemoteAccessVpnStartIsNonThrowingNoOp() throws Exception {
        assertFalse("startVpn must return false when not handled", element.startVpn(null));
    }

    @Test
    public void testRemoteAccessVpnStopIsNonThrowingNoOp() throws Exception {
        assertFalse("stopVpn must return false when not handled", element.stopVpn(null));
    }

    @Test
    public void testVpnUsersReturnNullWhenNotHandled() throws Exception {
        assertNull("applyVpnUsers must return the null-array no-op sentinel", element.applyVpnUsers(null, null));
    }

    @Test
    public void testUserDataIsNonThrowingNoOp() throws Exception {
        assertTrue("savePassword must be a safe no-op", element.savePassword(null, null, null));
        assertTrue("saveUserData must be a safe no-op", element.saveUserData(null, null, null));
        assertTrue("saveSSHKey must be a safe no-op", element.saveSSHKey(null, null, null, null));
    }

    @Test
    public void testSite2SiteVpnIsNonThrowingNoOp() throws Exception {
        assertFalse("startSite2SiteVpn must return false when not handled", vpcElement.startSite2SiteVpn(null));
        assertFalse("stopSite2SiteVpn must return false when not handled", vpcElement.stopSite2SiteVpn(null));
    }

    @Test(expected = UnsupportedServiceException.class)
    public void testPrivateGatewayFailsLoudly() throws Exception {
        // Private gateways are not a capability-gated service, so an explicit
        // create on a RouterOS VPC genuinely fails (only this operation, not cloud-wide).
        vpcElement.createPrivateGateway(null);
    }

    @Test
    public void testVpcFirewallServiceIsNonThrowingNoOp() throws Exception {
        // VPCs use network ACLs; VpcRouterOS never advertises Firewall, and
        // FirewallManagerImpl guards the call, so this returns the not-handled no-op.
        assertFalse(vpcElement.applyFWRules(null, null));
    }

    @Test
    public void testVpcElementDoesNotDeployPerNicAppliance() throws Exception {
        // prepare() must be a no-op for VPC tiers: the appliance is deployed by
        // implementVpc, not per user-VM NIC. A no-op returns true without touching
        // the (null) manager, so no NPE and no duplicate deploy.
        assertTrue(vpcElement.prepare(null, null, null, null, null));
    }

    @Test
    public void testVerifyServicesCombinationRequiresSourceNatForNatServices() {
        final Set<Service> withSourceNat = new HashSet<>(Arrays.asList(Service.SourceNat, Service.PortForwarding, Service.Firewall));
        assertTrue(element.verifyServicesCombination(withSourceNat));

        final Set<Service> natWithoutSourceNat = new HashSet<>(Arrays.asList(Service.PortForwarding));
        assertFalse(element.verifyServicesCombination(natWithoutSourceNat));

        final Set<Service> dhcpOnly = new HashSet<>(Arrays.asList(Service.Dhcp));
        assertTrue(element.verifyServicesCombination(dhcpOnly));
    }

    @Test
    public void testCanEnableIndividualServices() {
        assertTrue(element.canEnableIndividualServices());
    }

    @Test
    public void testIpDeployerIsSelf() {
        assertEquals(element, element.getIpDeployer(null));
        assertEquals(vpcElement, vpcElement.getIpDeployer(null));
    }

    @Test
    public void testExtraDhcpOptionsHonestlyRefused() {
        assertTrue(element.setExtraDhcpOptions(null, 1L, null));
        final Map<Integer, String> options = Map.of(66, "10.0.0.1");
        assertFalse(element.setExtraDhcpOptions(null, 1L, options));
    }
}
