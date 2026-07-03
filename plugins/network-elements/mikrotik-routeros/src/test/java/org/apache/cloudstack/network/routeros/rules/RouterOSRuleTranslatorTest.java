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
package org.apache.cloudstack.network.routeros.rules;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

import org.junit.Test;

import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.StaticNat;
import com.cloud.network.vpc.NetworkACLItem;
import com.cloud.network.vpc.StaticRouteProfile;
import com.cloud.utils.net.Ip;

/**
 * Tests for the pure CloudStack-to-RouterOS rule translation logic.
 */
public class RouterOSRuleTranslatorTest {

    private final RouterOSRuleTranslator translator = new RouterOSRuleTranslator();

    private FirewallRule firewallRule(final String uuid, final FirewallRule.TrafficType trafficType, final String protocol, final Integer portStart,
            final Integer portEnd, final List<String> sourceCidrs) {
        final FirewallRule rule = mock(FirewallRule.class);
        when(rule.getUuid()).thenReturn(uuid);
        when(rule.getTrafficType()).thenReturn(trafficType);
        when(rule.getProtocol()).thenReturn(protocol);
        when(rule.getSourcePortStart()).thenReturn(portStart);
        when(rule.getSourcePortEnd()).thenReturn(portEnd);
        when(rule.getSourceCidrList()).thenReturn(sourceCidrs);
        return rule;
    }

    // ------------------------------------------------------------------
    // Firewall ingress
    // ------------------------------------------------------------------

    @Test
    public void testIngressTcpRuleBecomesPreroutingConnectionMark() {
        final FirewallRule rule = firewallRule("f1", FirewallRule.TrafficType.Ingress, "tcp", 80, 90, Arrays.asList("192.168.10.0/24"));
        final List<RouterOSRule> rules = translator.translateFirewallRule(rule, "203.0.113.10", "ether1", false);

        assertEquals(1, rules.size());
        final RouterOSRule mark = rules.get(0);
        assertEquals(RouterOSRuleTranslator.RouterOSApiPaths.FIREWALL_MANGLE, mark.getPath());
        final Map<String, String> params = mark.getParams();
        assertEquals("prerouting", params.get("chain"));
        assertEquals("mark-connection", params.get("action"));
        assertEquals(RouterOSRuleTranslator.FIREWALL_ALLOW_MARK, params.get("new-connection-mark"));
        assertEquals("203.0.113.10", params.get("dst-address"));
        assertEquals("192.168.10.0/24", params.get("src-address"));
        assertEquals("tcp", params.get("protocol"));
        assertEquals("80-90", params.get("dst-port"));
        assertEquals("cs-fw-f1", params.get("comment"));
    }

    @Test
    public void testIngressRuleWithMultipleCidrsEmitsOneMarkPerCidr() {
        final FirewallRule rule = firewallRule("f2", FirewallRule.TrafficType.Ingress, "udp", 53, 53, Arrays.asList("10.0.0.0/8", "172.16.0.0/12"));
        final List<RouterOSRule> rules = translator.translateFirewallRule(rule, "203.0.113.10", "ether1", false);
        assertEquals(2, rules.size());
        assertEquals("10.0.0.0/8", rules.get(0).getParam("src-address"));
        assertEquals("172.16.0.0/12", rules.get(1).getParam("src-address"));
        assertEquals("53", rules.get(0).getParam("dst-port"));
        assertEquals("cs-fw-f2", rules.get(0).getComment());
        assertEquals("cs-fw-f2", rules.get(1).getComment());
    }

    @Test
    public void testIngressRuleWithoutCidrMatchesAnySource() {
        final FirewallRule rule = firewallRule("f3", FirewallRule.TrafficType.Ingress, "tcp", 22, null, null);
        final List<RouterOSRule> rules = translator.translateFirewallRule(rule, "203.0.113.10", "ether1", false);
        assertEquals(1, rules.size());
        assertNull(rules.get(0).getParam("src-address"));
        assertEquals("22", rules.get(0).getParam("dst-port"));
    }

    @Test
    public void testIngressIcmpRuleCarriesIcmpOptions() {
        final FirewallRule rule = firewallRule("f4", FirewallRule.TrafficType.Ingress, "icmp", null, null, null);
        when(rule.getIcmpType()).thenReturn(8);
        when(rule.getIcmpCode()).thenReturn(0);
        final List<RouterOSRule> rules = translator.translateFirewallRule(rule, "203.0.113.10", "ether1", false);
        assertEquals("icmp", rules.get(0).getParam("protocol"));
        assertEquals("8:0", rules.get(0).getParam("icmp-options"));
        assertNull(rules.get(0).getParam("dst-port"));
    }

    @Test
    public void testIngressIcmpAnyTypeOmitsIcmpOptions() {
        final FirewallRule rule = firewallRule("f5", FirewallRule.TrafficType.Ingress, "icmp", null, null, null);
        when(rule.getIcmpType()).thenReturn(-1);
        when(rule.getIcmpCode()).thenReturn(-1);
        final List<RouterOSRule> rules = translator.translateFirewallRule(rule, "203.0.113.10", "ether1", false);
        assertEquals("icmp", rules.get(0).getParam("protocol"));
        assertNull(rules.get(0).getParam("icmp-options"));
    }

    @Test
    public void testIngressIcmpTypeWithAnyCodeMatchesFullCodeRange() {
        // type>=0 but code=-1 (any code) must match the whole code range, not pin code 0
        final FirewallRule rule = firewallRule("f5b", FirewallRule.TrafficType.Ingress, "icmp", null, null, null);
        when(rule.getIcmpType()).thenReturn(8);
        when(rule.getIcmpCode()).thenReturn(-1);
        final List<RouterOSRule> rules = translator.translateFirewallRule(rule, "203.0.113.10", "ether1", false);
        assertEquals("icmp", rules.get(0).getParam("protocol"));
        assertEquals("8:0-255", rules.get(0).getParam("icmp-options"));
    }

    @Test
    public void testAllProtocolOmitsProtocolMatcher() {
        final FirewallRule rule = firewallRule("f6", FirewallRule.TrafficType.Ingress, "all", null, null, null);
        final List<RouterOSRule> rules = translator.translateFirewallRule(rule, "203.0.113.10", "ether1", false);
        assertNull(rules.get(0).getParam("protocol"));
        assertNull(rules.get(0).getParam("dst-port"));
    }

    // ------------------------------------------------------------------
    // Firewall egress
    // ------------------------------------------------------------------

    @Test
    public void testEgressRuleWithDefaultDenyPolicyBecomesForwardAccept() {
        // default egress policy Deny => user egress rules permit (accept)
        final FirewallRule rule = firewallRule("e1", FirewallRule.TrafficType.Egress, "tcp", 443, 443, Arrays.asList("10.1.1.0/24"));
        final List<RouterOSRule> rules = translator.translateFirewallRule(rule, null, "ether1", false);
        assertEquals(1, rules.size());
        final RouterOSRule filter = rules.get(0);
        assertEquals(RouterOSRuleTranslator.RouterOSApiPaths.FIREWALL_FILTER, filter.getPath());
        assertEquals("forward", filter.getParam("chain"));
        assertEquals("accept", filter.getParam("action"));
        assertEquals("ether1", filter.getParam("out-interface"));
        assertEquals("10.1.1.0/24", filter.getParam("src-address"));
        assertEquals("443", filter.getParam("dst-port"));
        assertEquals("cs-fw-e1", filter.getComment());
    }

    @Test
    public void testEgressRuleWithDefaultAllowPolicyBecomesForwardDrop() {
        // default egress policy Allow => user egress rules block (drop)
        final FirewallRule rule = firewallRule("e1d", FirewallRule.TrafficType.Egress, "tcp", 443, 443, Arrays.asList("10.1.1.0/24"));
        final List<RouterOSRule> rules = translator.translateFirewallRule(rule, null, "ether1", true);
        assertEquals(1, rules.size());
        assertEquals("drop", rules.get(0).getParam("action"));
        assertEquals("forward", rules.get(0).getParam("chain"));
        assertEquals("ether1", rules.get(0).getParam("out-interface"));
    }

    @Test
    public void testEgressRuleWithDestinationCidrs() {
        final FirewallRule rule = firewallRule("e2", FirewallRule.TrafficType.Egress, "tcp", 25, 25, Arrays.asList("10.1.1.0/24"));
        when(rule.getDestinationCidrList()).thenReturn(Arrays.asList("198.51.100.0/24", "192.0.2.0/24"));
        final List<RouterOSRule> rules = translator.translateFirewallRule(rule, null, "ether1", false);
        assertEquals(2, rules.size());
        assertEquals("198.51.100.0/24", rules.get(0).getParam("dst-address"));
        assertEquals("192.0.2.0/24", rules.get(1).getParam("dst-address"));
    }

    @Test
    public void testEgressAnyCidrOmitsAddressMatchers() {
        final FirewallRule rule = firewallRule("e3", FirewallRule.TrafficType.Egress, "all", null, null, Arrays.asList("0.0.0.0/0"));
        final List<RouterOSRule> rules = translator.translateFirewallRule(rule, null, "ether1", false);
        assertEquals(1, rules.size());
        assertNull(rules.get(0).getParam("src-address"));
        assertNull(rules.get(0).getParam("dst-address"));
    }

    // ------------------------------------------------------------------
    // Port forwarding
    // ------------------------------------------------------------------

    @Test
    public void testPortForwardingRangeTranslation() {
        final PortForwardingRule rule = mock(PortForwardingRule.class);
        when(rule.getUuid()).thenReturn("p1");
        when(rule.getProtocol()).thenReturn("tcp");
        when(rule.getSourcePortStart()).thenReturn(8080);
        when(rule.getSourcePortEnd()).thenReturn(8081);
        when(rule.getDestinationPortStart()).thenReturn(80);
        when(rule.getDestinationPortEnd()).thenReturn(81);
        when(rule.getDestinationIpAddress()).thenReturn(new Ip("10.1.1.50"));

        final List<RouterOSRule> rules = translator.translatePortForwardingRule(rule, "203.0.113.10");
        assertEquals(1, rules.size());
        final Map<String, String> params = rules.get(0).getParams();
        assertEquals(RouterOSRuleTranslator.RouterOSApiPaths.FIREWALL_NAT, rules.get(0).getPath());
        assertEquals("dstnat", params.get("chain"));
        assertEquals("dst-nat", params.get("action"));
        assertEquals("203.0.113.10", params.get("dst-address"));
        assertEquals("tcp", params.get("protocol"));
        assertEquals("8080-8081", params.get("dst-port"));
        assertEquals("10.1.1.50", params.get("to-addresses"));
        assertEquals("80-81", params.get("to-ports"));
        assertEquals("cs-pf-p1", params.get("comment"));
    }

    @Test
    public void testPortForwardingSinglePort() {
        final PortForwardingRule rule = mock(PortForwardingRule.class);
        when(rule.getUuid()).thenReturn("p2");
        when(rule.getProtocol()).thenReturn("udp");
        when(rule.getSourcePortStart()).thenReturn(514);
        when(rule.getSourcePortEnd()).thenReturn(514);
        when(rule.getDestinationPortStart()).thenReturn(1514);
        when(rule.getDestinationPortEnd()).thenReturn(1514);
        when(rule.getDestinationIpAddress()).thenReturn(new Ip("10.1.1.51"));

        final Map<String, String> params = translator.translatePortForwardingRule(rule, "203.0.113.10").get(0).getParams();
        assertEquals("514", params.get("dst-port"));
        assertEquals("1514", params.get("to-ports"));
        assertEquals("udp", params.get("protocol"));
    }

    // ------------------------------------------------------------------
    // Static NAT
    // ------------------------------------------------------------------

    @Test
    public void testStaticNatEmitsSymmetricNatPair() {
        final StaticNat rule = mock(StaticNat.class);
        when(rule.getSourceIpAddressId()).thenReturn(42L);
        when(rule.getDestIpAddress()).thenReturn("10.1.1.60");

        final List<RouterOSRule> rules = translator.translateStaticNat(rule, "203.0.113.20");
        assertEquals(2, rules.size());

        final Map<String, String> dstNat = rules.get(0).getParams();
        assertEquals("dstnat", dstNat.get("chain"));
        assertEquals("203.0.113.20", dstNat.get("dst-address"));
        assertEquals("10.1.1.60", dstNat.get("to-addresses"));

        final Map<String, String> srcNat = rules.get(1).getParams();
        assertEquals("srcnat", srcNat.get("chain"));
        assertEquals("10.1.1.60", srcNat.get("src-address"));
        assertEquals("203.0.113.20", srcNat.get("to-addresses"));

        assertEquals("cs-staticnat-42", dstNat.get("comment"));
        assertEquals("cs-staticnat-42", srcNat.get("comment"));
    }

    // ------------------------------------------------------------------
    // Network ACLs
    // ------------------------------------------------------------------

    private NetworkACLItem aclItem(final String uuid, final int number, final NetworkACLItem.TrafficType trafficType, final NetworkACLItem.Action action,
            final String protocol, final Integer portStart, final Integer portEnd, final List<String> cidrs, final NetworkACLItem.State state) {
        final NetworkACLItem item = mock(NetworkACLItem.class);
        when(item.getUuid()).thenReturn(uuid);
        when(item.getNumber()).thenReturn(number);
        when(item.getTrafficType()).thenReturn(trafficType);
        when(item.getAction()).thenReturn(action);
        when(item.getProtocol()).thenReturn(protocol);
        when(item.getSourcePortStart()).thenReturn(portStart);
        when(item.getSourcePortEnd()).thenReturn(portEnd);
        when(item.getSourceCidrList()).thenReturn(cidrs);
        when(item.getState()).thenReturn(state);
        return item;
    }

    @Test
    public void testAclIngressItemMatchesTrafficIntoTheTier() {
        final NetworkACLItem item = aclItem("a1", 10, NetworkACLItem.TrafficType.Ingress, NetworkACLItem.Action.Allow, "tcp", 80, 80,
                Arrays.asList("0.0.0.0/0"), NetworkACLItem.State.Add);
        final List<RouterOSRule> rules = translator.translateAclItem(item, "net1", "ether2", "10.1.1.0/24");
        assertEquals(1, rules.size());
        final Map<String, String> params = rules.get(0).getParams();
        assertEquals("forward", params.get("chain"));
        assertEquals("accept", params.get("action"));
        assertEquals("ether2", params.get("out-interface"));
        assertNull(params.get("in-interface"));
        assertNull(params.get("src-address"));
        assertEquals("80", params.get("dst-port"));
        assertEquals("cs-acl-net1-item-a1", params.get("comment"));
    }

    @Test
    public void testAclEgressDenyItemMatchesTrafficLeavingTheTier() {
        final NetworkACLItem item = aclItem("a2", 20, NetworkACLItem.TrafficType.Egress, NetworkACLItem.Action.Deny, "udp", 123, 123,
                Arrays.asList("198.51.100.0/24"), NetworkACLItem.State.Add);
        final List<RouterOSRule> rules = translator.translateAclItem(item, "net1", "ether2", "10.1.1.0/24");
        final Map<String, String> params = rules.get(0).getParams();
        assertEquals("drop", params.get("action"));
        assertEquals("ether2", params.get("in-interface"));
        assertEquals("198.51.100.0/24", params.get("dst-address"));
        assertEquals("123", params.get("dst-port"));
    }

    @Test
    public void testAclItemsAreOrderedByNumberAndRevokedItemsSkipped() {
        final NetworkACLItem third = aclItem("c", 30, NetworkACLItem.TrafficType.Ingress, NetworkACLItem.Action.Deny, "all", null, null, null,
                NetworkACLItem.State.Add);
        final NetworkACLItem first = aclItem("a", 1, NetworkACLItem.TrafficType.Ingress, NetworkACLItem.Action.Allow, "tcp", 22, 22, null,
                NetworkACLItem.State.Add);
        final NetworkACLItem revoked = aclItem("b", 2, NetworkACLItem.TrafficType.Ingress, NetworkACLItem.Action.Allow, "tcp", 80, 80, null,
                NetworkACLItem.State.Revoke);

        final List<RouterOSRule> rules = translator.translateAclItems(Arrays.asList(third, revoked, first), "net1", "ether2", "10.1.1.0/24");
        assertEquals(2, rules.size());
        assertEquals("cs-acl-net1-item-a", rules.get(0).getComment());
        assertEquals("cs-acl-net1-item-c", rules.get(1).getComment());
    }

    @Test
    public void testAclDefaultDropAnchors() {
        final RouterOSRule in = translator.aclDefaultDropRule("net1", "ether2", NetworkACLItem.TrafficType.Ingress);
        assertEquals("drop", in.getParam("action"));
        assertEquals("ether2", in.getParam("out-interface"));
        assertEquals("cs-acl-net1-default-in", in.getComment());

        final RouterOSRule out = translator.aclDefaultDropRule("net1", "ether2", NetworkACLItem.TrafficType.Egress);
        assertEquals("ether2", out.getParam("in-interface"));
        assertEquals("cs-acl-net1-default-out", out.getComment());
    }

    @Test
    public void testPlaceBeforeCopyKeepsParamsAndAddsHint() {
        final NetworkACLItem item = aclItem("a3", 5, NetworkACLItem.TrafficType.Ingress, NetworkACLItem.Action.Allow, "tcp", 443, 443, null,
                NetworkACLItem.State.Add);
        final RouterOSRule rule = translator.translateAclItem(item, "net1", "ether2", "10.1.1.0/24").get(0).withPlaceBefore("*A");
        assertEquals("*A", rule.getParam(RouterOSRule.PLACE_BEFORE));
        assertEquals("443", rule.getParam("dst-port"));
    }

    // ------------------------------------------------------------------
    // Static routes / source NAT / public IPs
    // ------------------------------------------------------------------

    @Test
    public void testStaticRouteTranslation() {
        final StaticRouteProfile route = mock(StaticRouteProfile.class);
        when(route.getUuid()).thenReturn("r1");
        when(route.getCidr()).thenReturn("192.168.100.0/24");
        when(route.getGateway()).thenReturn("10.10.10.5");

        final RouterOSRule rule = translator.translateStaticRoute(route);
        assertEquals(RouterOSRuleTranslator.RouterOSApiPaths.ROUTE, rule.getPath());
        assertEquals("192.168.100.0/24", rule.getParam("dst-address"));
        assertEquals("10.10.10.5", rule.getParam("gateway"));
        assertEquals("cs-route-r1", rule.getComment());
    }

    @Test
    public void testSourceNatRule() {
        final RouterOSRule rule = translator.sourceNatRule("10.1.1.0/24", "203.0.113.10", "ether1", "net1");
        assertEquals("srcnat", rule.getParam("chain"));
        assertEquals("src-nat", rule.getParam("action"));
        assertEquals("10.1.1.0/24", rule.getParam("src-address"));
        assertEquals("ether1", rule.getParam("out-interface"));
        assertEquals("203.0.113.10", rule.getParam("to-addresses"));
        assertEquals("cs-net-net1-srcnat", rule.getComment());
    }

    @Test
    public void testPublicIpAddressRuleConvertsNetmaskToPrefixLength() {
        final RouterOSRule rule = translator.publicIpAddressRule("203.0.113.10", "255.255.255.0", "ether1", "ipuuid");
        assertEquals("203.0.113.10/24", rule.getParam("address"));
        assertEquals("ether1", rule.getParam("interface"));
        assertEquals("cs-ip-ipuuid", rule.getComment());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    @Test
    public void testPortRangeFormatting() {
        assertEquals("80", RouterOSRuleTranslator.portRange(80, null));
        assertEquals("80", RouterOSRuleTranslator.portRange(80, 80));
        assertEquals("80-90", RouterOSRuleTranslator.portRange(80, 90));
        assertNull(RouterOSRuleTranslator.portRange(null, null));
    }

    @Test
    public void testProtocolMapping() {
        assertEquals("tcp", RouterOSRuleTranslator.toRouterOSProtocol("TCP"));
        assertEquals("udp", RouterOSRuleTranslator.toRouterOSProtocol("udp"));
        assertEquals("icmp", RouterOSRuleTranslator.toRouterOSProtocol("icmp"));
        assertNull(RouterOSRuleTranslator.toRouterOSProtocol("all"));
        assertNull(RouterOSRuleTranslator.toRouterOSProtocol("any"));
        assertNull(RouterOSRuleTranslator.toRouterOSProtocol(null));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUnknownProtocolIsRejected() {
        RouterOSRuleTranslator.toRouterOSProtocol("gre");
    }

    @Test
    public void testNumericProtocolIsPassedThrough() {
        // CloudStack network ACLs allow numeric IP protocols (e.g. 47 = GRE); RouterOS accepts them.
        assertEquals("47", RouterOSRuleTranslator.toRouterOSProtocol("47"));
        assertEquals("50", RouterOSRuleTranslator.toRouterOSProtocol("50"));
    }

    @Test
    public void testNumericProtocolAclItemTranslatesInsteadOfThrowing() {
        final NetworkACLItem item = aclItem("gre1", 15, NetworkACLItem.TrafficType.Ingress, NetworkACLItem.Action.Allow, "47", null, null,
                Arrays.asList("0.0.0.0/0"), NetworkACLItem.State.Add);
        final List<RouterOSRule> rules = translator.translateAclItem(item, "net1", "ether2", "10.1.1.0/24");
        assertEquals(1, rules.size());
        assertEquals("47", rules.get(0).getParam("protocol"));
        assertNull(rules.get(0).getParam("dst-port"));
    }

    @Test
    public void testRuleParamsAreImmutable() {
        final RouterOSRule rule = translator.sourceNatRule("10.1.1.0/24", "203.0.113.10", "ether1", "net1");
        try {
            rule.getParams().put("chain", "hacked");
            assertFalse("params must be immutable", true);
        } catch (final UnsupportedOperationException expected) {
            assertTrue(true);
        }
    }
}
