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
package com.cloud.network.vpc;

import java.util.List;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.user.Account;

/**
 * VPC public-IP allocation and release — extracted from
 * {@link VpcManagerImpl} as part of the Phase 4 Spring-component
 * decomposition.
 *
 * <p>This component owns the lifecycle of public IP addresses on a
 * {@link Vpc}: associating a free IP from the account to the VPC
 * (electing source NAT when none is configured), releasing a VPC IP
 * back to the VPC pool, deciding whether an IP is still allocated to
 * a VPC (one-to-one NAT or firewall rules), looking up the existing
 * source-NAT IP for an owner, and assigning a fresh source-NAT IP
 * for a VPC virtual router (with NSX / Netris awareness).
 *
 * <p>{@code VpcManagerImpl} keeps its public-API methods as thin
 * delegating wrappers so the {@link VpcService} and
 * {@link com.cloud.network.vpc.VpcManager} contracts — including
 * callers in {@code RulesManagerImpl}, {@code FirewallManagerImpl},
 * {@code LoadBalancingRulesManagerImpl}, {@code IpAddressManagerImpl},
 * {@code NicPlugInOutRules}, {@code VpcNetworkHelperImpl},
 * {@code VpcVirtualNetworkApplianceManagerImpl} and
 * {@code VpcRouterDeploymentDefinition} — continue to work unchanged.
 *
 * <p>Methods that depend on VR / static-NAT orchestration (e.g.
 * {@code configStaticNatForVpcVr}, {@code reconfigStaticNatForVpcVr},
 * {@code getIpAddressForVpcVr}) remain in {@code VpcManagerImpl} because
 * they reach into router lookup and {@link com.cloud.network.rules.RulesManager}
 * apply paths.
 */
public interface VpcIpAllocationService {

    /**
     * Associate the given (already allocated) public IP to the VPC.
     * Validates account access (caller can manage the IP and the VPC
     * owner), flips the IP to source-NAT iff the VPC offering requires
     * source-NAT and the VPC does not yet have a source-NAT IP, persists
     * the {@code vpcId} on the IP row, and marks the public IP as
     * allocated. Returns {@code null} if the IP cannot be located,
     * otherwise the freshly-loaded {@link IPAddressVO}.
     */
    IpAddress associateIPToVpc(long ipId, long vpcId) throws ResourceAllocationException, ResourceUnavailableException,
            InsufficientAddressCapacityException, ConcurrentOperationException;

    /**
     * Release a VPC public IP from a specific tier network — used when
     * a firewall, load-balancer or NAT rule that pinned the IP to that
     * network is removed. Skips the release entirely when the IP is
     * still allocated to the VPC (one-to-one NAT or remaining firewall
     * rules), applies the IP association removal on the network's
     * elements, then clears the {@code associatedWithNetworkId} on the
     * IP row.
     */
    void unassignIPFromVpcNetwork(long ipId, long networkId);

    /** {@link #unassignIPFromVpcNetwork(long, long)} variant for callers that already hold the IP and Network. */
    void unassignIPFromVpcNetwork(IPAddressVO ip, Network network);

    /**
     * Returns {@code true} when the IP is still tied to a VPC — either
     * via one-to-one NAT or because firewall rules still reference it.
     * A {@code false} result means it can be released from the tier.
     */
    boolean isIpAllocatedToVpc(IpAddress ip);

    /**
     * Find or allocate the source-NAT IP for the VPC's virtual router.
     * Reuses the existing source-NAT IP for the owner/VPC when present
     * (NSX/Netris paths skip non-system-VM IPs); otherwise allocates a
     * fresh IP from the public range (NSX uses system-VM IPs, Netris
     * uses non-system-VM IPs, regular VPCs use the dedicated VPC range).
     */
    PublicIp assignSourceNatIpAddressToVpc(Account owner, Vpc vpc, Long podId) throws InsufficientAddressCapacityException, ConcurrentOperationException;

    /**
     * Look up the existing source-NAT public IP for the given account
     * and VPC, returning {@code null} when none is allocated. The
     * {@code forNsx} / {@code forNetris} flags filter to system-VM IPs
     * when the VPC is backed by an NSX / Netris provider.
     */
    IPAddressVO getExistingSourceNatInVpc(long ownerId, long vpcId, boolean forNsx, boolean forNetris);

    /**
     * List the public IPs assigned to the given VPC (and optionally
     * filtered by source-NAT flag) on the {@link com.cloud.dc.Vlan.VlanType#VirtualNetwork}
     * VLAN type. Used by source-NAT IP lookup and VPC restart paths.
     */
    List<IPAddressVO> listPublicIpsAssignedToVpc(long accountId, Boolean sourceNat, long vpcId);
}
