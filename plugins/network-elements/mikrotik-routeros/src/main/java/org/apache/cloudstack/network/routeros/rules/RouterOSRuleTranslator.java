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

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.StaticNat;
import com.cloud.network.vpc.NetworkACLItem;
import com.cloud.network.vpc.StaticRouteProfile;
import com.cloud.utils.net.NetUtils;

/**
 * Pure translation layer from CloudStack rule models to RouterOS REST
 * parameter maps. No I/O, no Spring: everything here is unit testable.
 *
 * Comment-tagging convention (idempotency keys):
 * <ul>
 *   <li>{@code cs-fw-<rule-uuid>}         firewall rule (mangle mark + filter objects)</li>
 *   <li>{@code cs-pf-<rule-uuid>}         port forwarding dst-nat rule</li>
 *   <li>{@code cs-staticnat-<ip-uuid>}    1:1 NAT rule pair for a static NAT IP</li>
 *   <li>{@code cs-acl-<network-uuid>-item-<item-uuid>} network ACL item</li>
 *   <li>{@code cs-acl-<network-uuid>-default-<dir>}    ACL default drop rules</li>
 *   <li>{@code cs-route-<route-uuid>}     VPC static route</li>
 *   <li>{@code cs-ip-<ip-uuid>}           public IP address on the public interface</li>
 *   <li>{@code cs-net-<network-uuid>-*}   per-network base objects (gateway address,
 *       source NAT, DHCP, base firewall rules)</li>
 * </ul>
 */
public class RouterOSRuleTranslator {

    /** Connection mark set by ingress firewall rules and honored by the base accept rule. */
    public static final String FIREWALL_ALLOW_MARK = "cs-fw-allow";

    public static final String PROTO_TCP = "tcp";
    public static final String PROTO_UDP = "udp";
    public static final String PROTO_ICMP = "icmp";
    public static final String PROTO_ALL = "all";

    // ------------------------------------------------------------------
    // Comment builders
    // ------------------------------------------------------------------

    public static String firewallRuleComment(final FirewallRule rule) {
        return "cs-fw-" + rule.getUuid();
    }

    public static String portForwardingComment(final PortForwardingRule rule) {
        return "cs-pf-" + rule.getUuid();
    }

    public static String staticNatComment(final StaticNat rule) {
        return "cs-staticnat-" + rule.getSourceIpAddressId();
    }

    public static String aclItemComment(final String networkUuid, final NetworkACLItem item) {
        return "cs-acl-" + networkUuid + "-item-" + item.getUuid();
    }

    public static String aclItemCommentPrefix(final String networkUuid) {
        return "cs-acl-" + networkUuid + "-item-";
    }

    public static String aclDefaultComment(final String networkUuid, final NetworkACLItem.TrafficType trafficType) {
        return "cs-acl-" + networkUuid + "-default-" + (trafficType == NetworkACLItem.TrafficType.Ingress ? "in" : "out");
    }

    public static String staticRouteComment(final StaticRouteProfile route) {
        return "cs-route-" + route.getUuid();
    }

    public static String publicIpComment(final String ipUuid) {
        return "cs-ip-" + ipUuid;
    }

    public static String networkComment(final String networkUuid, final String suffix) {
        return "cs-net-" + networkUuid + "-" + suffix;
    }

    public static String nicComment(final String nicUuid) {
        return "cs-nic-" + nicUuid;
    }

    // ------------------------------------------------------------------
    // Firewall
    // ------------------------------------------------------------------

    /**
     * Translate a CloudStack firewall rule.
     *
     * Ingress rules (to a public IP) are expressed as connection marks in the
     * mangle prerouting chain: mangle runs before dst-nat, so the original
     * public destination address and port are still visible there. Matching
     * connections receive the {@link #FIREWALL_ALLOW_MARK}; the per-network
     * base rule set accepts marked dst-nat connections in the forward chain
     * and drops unmarked ones.
     *
     * Egress rules become plain accept rules in the forward chain towards the
     * public interface.
     *
     * @param publicIp the public IP the rule is bound to (ingress only, may be null for egress)
     * @param publicInterface RouterOS interface name of the public uplink
     */
    public List<RouterOSRule> translateFirewallRule(final FirewallRule rule, final String publicIp, final String publicInterface) {
        final String comment = firewallRuleComment(rule);
        final List<RouterOSRule> result = new ArrayList<>();
        if (rule.getTrafficType() == FirewallRule.TrafficType.Egress) {
            final List<String> sourceCidrs = cidrsOrAny(rule.getSourceCidrList());
            final List<String> destCidrs = rule.getDestinationCidrList();
            for (final String sourceCidr : sourceCidrs) {
                if (destCidrs == null || destCidrs.isEmpty()) {
                    result.add(egressFilterRule(rule, sourceCidr, null, publicInterface, comment));
                } else {
                    for (final String destCidr : destCidrs) {
                        result.add(egressFilterRule(rule, sourceCidr, destCidr, publicInterface, comment));
                    }
                }
            }
        } else {
            for (final String sourceCidr : cidrsOrAny(rule.getSourceCidrList())) {
                final Map<String, String> params = new LinkedHashMap<>();
                params.put("chain", "prerouting");
                params.put("action", "mark-connection");
                params.put("new-connection-mark", FIREWALL_ALLOW_MARK);
                params.put("passthrough", "yes");
                params.put("connection-state", "new");
                params.put("dst-address", publicIp);
                if (!isAnyCidr(sourceCidr)) {
                    params.put("src-address", sourceCidr);
                }
                applyProtocolAndPorts(params, rule.getProtocol(), rule.getSourcePortStart(), rule.getSourcePortEnd(), rule.getIcmpType(), rule.getIcmpCode());
                params.put("comment", comment);
                result.add(new RouterOSRule(RouterOSApiPaths.FIREWALL_MANGLE, params));
            }
        }
        return result;
    }

    private RouterOSRule egressFilterRule(final FirewallRule rule, final String sourceCidr, final String destCidr, final String publicInterface, final String comment) {
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("chain", "forward");
        params.put("action", "accept");
        params.put("out-interface", publicInterface);
        if (!isAnyCidr(sourceCidr)) {
            params.put("src-address", sourceCidr);
        }
        if (destCidr != null && !isAnyCidr(destCidr)) {
            params.put("dst-address", destCidr);
        }
        applyProtocolAndPorts(params, rule.getProtocol(), rule.getSourcePortStart(), rule.getSourcePortEnd(), rule.getIcmpType(), rule.getIcmpCode());
        params.put("comment", comment);
        return new RouterOSRule(RouterOSApiPaths.FIREWALL_FILTER, params);
    }

    // ------------------------------------------------------------------
    // Port forwarding
    // ------------------------------------------------------------------

    public List<RouterOSRule> translatePortForwardingRule(final PortForwardingRule rule, final String publicIp) {
        final String comment = portForwardingComment(rule);
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("chain", "dstnat");
        params.put("action", "dst-nat");
        params.put("dst-address", publicIp);
        params.put("protocol", toRouterOSProtocol(rule.getProtocol()));
        params.put("dst-port", portRange(rule.getSourcePortStart(), rule.getSourcePortEnd()));
        params.put("to-addresses", rule.getDestinationIpAddress().addr());
        params.put("to-ports", portRange(rule.getDestinationPortStart(), rule.getDestinationPortEnd()));
        params.put("comment", comment);
        final List<RouterOSRule> result = new ArrayList<>();
        result.add(new RouterOSRule(RouterOSApiPaths.FIREWALL_NAT, params));
        return result;
    }

    // ------------------------------------------------------------------
    // Static NAT
    // ------------------------------------------------------------------

    public List<RouterOSRule> translateStaticNat(final StaticNat rule, final String publicIp) {
        final String comment = staticNatComment(rule);
        final List<RouterOSRule> result = new ArrayList<>();

        final Map<String, String> dstNat = new LinkedHashMap<>();
        dstNat.put("chain", "dstnat");
        dstNat.put("action", "dst-nat");
        dstNat.put("dst-address", publicIp);
        dstNat.put("to-addresses", rule.getDestIpAddress());
        dstNat.put("comment", comment);
        result.add(new RouterOSRule(RouterOSApiPaths.FIREWALL_NAT, dstNat));

        final Map<String, String> srcNat = new LinkedHashMap<>();
        srcNat.put("chain", "srcnat");
        srcNat.put("action", "src-nat");
        srcNat.put("src-address", rule.getDestIpAddress());
        srcNat.put("to-addresses", publicIp);
        srcNat.put("comment", comment);
        result.add(new RouterOSRule(RouterOSApiPaths.FIREWALL_NAT, srcNat));

        return result;
    }

    // ------------------------------------------------------------------
    // Network ACLs (VPC tiers)
    // ------------------------------------------------------------------

    /**
     * Translate the full ACL item list of a VPC tier. Items are ordered by
     * their ACL number (ascending = highest priority first), matching the
     * order in which they must be written to the device; the manager appends
     * them ahead of the tier's default-drop rules using {@code place-before}.
     * Items in Revoke state are skipped.
     */
    public List<RouterOSRule> translateAclItems(final List<? extends NetworkACLItem> items, final String networkUuid, final String tierInterface, final String tierCidr) {
        final List<NetworkACLItem> sorted = new ArrayList<>(items);
        sorted.sort(Comparator.comparingInt(NetworkACLItem::getNumber));
        final List<RouterOSRule> result = new ArrayList<>();
        for (final NetworkACLItem item : sorted) {
            if (item.getState() == NetworkACLItem.State.Revoke) {
                continue;
            }
            result.addAll(translateAclItem(item, networkUuid, tierInterface, tierCidr));
        }
        return result;
    }

    public List<RouterOSRule> translateAclItem(final NetworkACLItem item, final String networkUuid, final String tierInterface, final String tierCidr) {
        final String comment = aclItemComment(networkUuid, item);
        final List<RouterOSRule> result = new ArrayList<>();
        final boolean ingress = item.getTrafficType() == NetworkACLItem.TrafficType.Ingress;
        for (final String cidr : cidrsOrAny(item.getSourceCidrList())) {
            final Map<String, String> params = new LinkedHashMap<>();
            params.put("chain", "forward");
            params.put("action", item.getAction() == NetworkACLItem.Action.Allow ? "accept" : "drop");
            if (ingress) {
                // traffic entering the tier
                params.put("out-interface", tierInterface);
                if (!isAnyCidr(cidr)) {
                    params.put("src-address", cidr);
                }
            } else {
                // traffic leaving the tier
                params.put("in-interface", tierInterface);
                if (!isAnyCidr(cidr)) {
                    params.put("dst-address", cidr);
                }
            }
            applyProtocolAndPorts(params, item.getProtocol(), item.getSourcePortStart(), item.getSourcePortEnd(), item.getIcmpType(), item.getIcmpCode());
            params.put("comment", comment);
            result.add(new RouterOSRule(RouterOSApiPaths.FIREWALL_FILTER, params));
        }
        return result;
    }

    public RouterOSRule aclDefaultDropRule(final String networkUuid, final String tierInterface, final NetworkACLItem.TrafficType trafficType) {
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("chain", "forward");
        params.put("action", "drop");
        if (trafficType == NetworkACLItem.TrafficType.Ingress) {
            params.put("out-interface", tierInterface);
        } else {
            params.put("in-interface", tierInterface);
        }
        params.put("comment", aclDefaultComment(networkUuid, trafficType));
        return new RouterOSRule(RouterOSApiPaths.FIREWALL_FILTER, params);
    }

    // ------------------------------------------------------------------
    // Routing / addressing / NAT base
    // ------------------------------------------------------------------

    public RouterOSRule translateStaticRoute(final StaticRouteProfile route) {
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("dst-address", route.getCidr());
        params.put("gateway", route.getGateway());
        params.put("comment", staticRouteComment(route));
        return new RouterOSRule(RouterOSApiPaths.ROUTE, params);
    }

    public RouterOSRule sourceNatRule(final String guestCidr, final String publicIp, final String publicInterface, final String networkUuid) {
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("chain", "srcnat");
        params.put("action", "src-nat");
        params.put("src-address", guestCidr);
        params.put("out-interface", publicInterface);
        params.put("to-addresses", publicIp);
        params.put("comment", networkComment(networkUuid, "srcnat"));
        return new RouterOSRule(RouterOSApiPaths.FIREWALL_NAT, params);
    }

    public RouterOSRule publicIpAddressRule(final String ip, final String netmask, final String publicInterface, final String ipUuid) {
        final Map<String, String> params = new LinkedHashMap<>();
        params.put("address", ip + "/" + NetUtils.getCidrSize(netmask));
        params.put("interface", publicInterface);
        params.put("comment", publicIpComment(ipUuid));
        return new RouterOSRule(RouterOSApiPaths.IP_ADDRESS, params);
    }

    // ------------------------------------------------------------------
    // Shared helpers
    // ------------------------------------------------------------------

    /**
     * Map a CloudStack protocol string onto RouterOS. Returns null for
     * {@code all}, which on RouterOS is expressed by omitting the protocol
     * matcher entirely.
     */
    public static String toRouterOSProtocol(final String protocol) {
        if (protocol == null) {
            return null;
        }
        switch (protocol.toLowerCase()) {
            case PROTO_TCP:
                return PROTO_TCP;
            case PROTO_UDP:
                return PROTO_UDP;
            case PROTO_ICMP:
                return PROTO_ICMP;
            case PROTO_ALL:
            case "any":
                return null;
            default:
                throw new IllegalArgumentException("Protocol '" + protocol + "' is not supported by the RouterOS provider");
        }
    }

    /**
     * @return RouterOS port range notation: "80" for a single port,
     * "80-90" for a range. Null when no ports apply.
     */
    public static String portRange(final Integer start, final Integer end) {
        if (start == null) {
            return null;
        }
        if (end == null || end.equals(start)) {
            return String.valueOf(start);
        }
        return start + "-" + end;
    }

    protected static void applyProtocolAndPorts(final Map<String, String> params, final String protocol, final Integer portStart, final Integer portEnd,
            final Integer icmpType, final Integer icmpCode) {
        final String routerOsProtocol = toRouterOSProtocol(protocol);
        if (routerOsProtocol == null) {
            return;
        }
        params.put("protocol", routerOsProtocol);
        if (PROTO_ICMP.equals(routerOsProtocol)) {
            if (icmpType != null && icmpType >= 0) {
                final int code = icmpCode != null && icmpCode >= 0 ? icmpCode : 0;
                params.put("icmp-options", icmpType + ":" + code);
            }
            return;
        }
        final String range = portRange(portStart, portEnd);
        if (range != null) {
            params.put("dst-port", range);
        }
    }

    protected static List<String> cidrsOrAny(final List<String> cidrs) {
        if (cidrs == null || cidrs.isEmpty()) {
            final List<String> any = new ArrayList<>();
            any.add(NetUtils.ALL_IP4_CIDRS);
            return any;
        }
        return cidrs;
    }

    protected static boolean isAnyCidr(final String cidr) {
        return cidr == null || NetUtils.ALL_IP4_CIDRS.equals(cidr.trim());
    }

    /**
     * REST collection paths used by translated rules. Kept separate from the
     * API client so this package stays free of I/O dependencies.
     */
    public static final class RouterOSApiPaths {
        public static final String IP_ADDRESS = "ip/address";
        public static final String FIREWALL_FILTER = "ip/firewall/filter";
        public static final String FIREWALL_NAT = "ip/firewall/nat";
        public static final String FIREWALL_MANGLE = "ip/firewall/mangle";
        public static final String ROUTE = "ip/route";
        public static final String DHCP_SERVER = "ip/dhcp-server";
        public static final String DHCP_NETWORK = "ip/dhcp-server/network";
        public static final String DHCP_LEASE = "ip/dhcp-server/lease";

        private RouterOSApiPaths() {
        }
    }
}
