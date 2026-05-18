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
package com.cloud.configuration;

import java.util.List;

import org.apache.cloudstack.api.command.admin.vlan.CreateVlanIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.DedicatePublicIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.DeleteVlanIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.ReleasePublicIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.UpdateVlanIpRangeCmd;

import com.cloud.dc.Vlan;
import com.cloud.dc.VlanVO;
import com.cloud.domain.Domain;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.Network.Provider;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.net.NetUtils;

/**
 * VLAN and public IP-range management — create, update, delete, dedicate,
 * and release VLAN IP ranges. Extracted from {@link ConfigurationManagerImpl}
 * as slice 7 of the Phase 4 Spring-component decomposition.
 *
 * <p>{@code ConfigurationManagerImpl} retains one-line delegating wrappers
 * so both the {@code ConfigurationService} (API-facing) and
 * {@code ConfigurationManager} (engine-facing) contracts keep working
 * unchanged. In addition, {@code AccountManagerImpl} and
 * {@code DomainManagerImpl} call {@link #releaseAccountSpecificVirtualRanges}
 * / {@link #releaseDomainSpecificVirtualRanges} through {@code _configMgr},
 * which keeps routing those calls into this service via the manager's
 * delegating wrappers.
 *
 * <p>This service also owns the pod-CIDR overlap check
 * ({@link #checkPodCidrSubnets}) because it is intrinsically a public-IP /
 * VLAN concern (it compares pod subnets against guest IP networks).
 */
public interface VlanService {

    /**
     * Create a VLAN and the public IP range it contains, from a
     * {@code CreateVlanIpRangeCmd}. Validates network type / zone state,
     * resolves the owning account/project/domain, validates IPv4 and IPv6
     * ranges (including subset/superset checks against existing VLANs),
     * persists the {@code VlanVO} and {@code IPAddressVO} rows inside a
     * lock-protected transaction, and publishes a
     * {@code MESSAGE_CREATE_VLAN_IP_RANGE_EVENT}.
     */
    Vlan createVlanAndPublicIpRange(CreateVlanIpRangeCmd cmd) throws InsufficientCapacityException,
            ConcurrentOperationException, ResourceUnavailableException, ResourceAllocationException;

    /**
     * Lower-level overload of {@link #createVlanAndPublicIpRange(CreateVlanIpRangeCmd)}
     * that accepts pre-resolved IDs and parameters. Used by the network
     * orchestration path when the higher-level command form isn't available.
     */
    Vlan createVlanAndPublicIpRange(long zoneId, long networkId, long physicalNetworkId,
            boolean forVirtualNetwork, boolean forSystemVms, Long podId, String startIP, String endIP,
            String vlanGateway, String vlanNetmask, String vlanId, boolean bypassVlanOverlapCheck,
            Domain domain, Account vlanOwner, String startIPv6, String endIPv6, String vlanIp6Gateway,
            String vlanIp6Cidr, Provider provider);

    /**
     * Update an existing VLAN's IP range from an {@code UpdateVlanIpRangeCmd}.
     */
    Vlan updateVlanAndPublicIpRange(UpdateVlanIpRangeCmd cmd) throws ConcurrentOperationException,
            ResourceUnavailableException, ResourceAllocationException;

    /**
     * Delete a VLAN and its public IP range. Verifies no IPs are in use,
     * removes the {@code VlanVO}, the public-IP rows, and any pod_vlan_map
     * entry, then returns the deleted VLAN. Called from the higher-level
     * {@link #deleteVlanIpRange(DeleteVlanIpRangeCmd)}.
     */
    VlanVO deleteVlanAndPublicIpRange(long userId, long vlanDbId, Account caller);

    /**
     * Delete a VLAN IP range from a {@code DeleteVlanIpRangeCmd}.
     */
    boolean deleteVlanIpRange(DeleteVlanIpRangeCmd cmd);

    /**
     * Dedicate an existing public IP range to a specific account, project,
     * or domain. Validates resource limits and persists an
     * {@code AccountVlanMapVO} or {@code DomainVlanMapVO} accordingly.
     */
    Vlan dedicatePublicIpRange(DedicatePublicIpRangeCmd cmd) throws ResourceAllocationException;

    /**
     * Release a previously-dedicated public IP range back to the system pool.
     */
    boolean releasePublicIpRange(ReleasePublicIpRangeCmd cmd);

    /**
     * Lower-level overload of {@link #releasePublicIpRange(ReleasePublicIpRangeCmd)}
     * used by the account- and domain-cleanup paths
     * ({@link #releaseAccountSpecificVirtualRanges},
     * {@link #releaseDomainSpecificVirtualRanges}).
     */
    boolean releasePublicIpRange(long vlanDbId, User user, Account caller);

    /**
     * Release every VLAN dedicated to the given domain. Called from
     * {@code DomainManagerImpl} during domain deletion, routed through
     * {@code ConfigurationManagerImpl} as the {@code _configMgr} field.
     */
    boolean releaseDomainSpecificVirtualRanges(Domain domain);

    /**
     * Release every VLAN dedicated to the given account. Called from
     * {@code AccountManagerImpl} during account deletion, routed through
     * {@code ConfigurationManagerImpl} as the {@code _configMgr} field.
     */
    boolean releaseAccountSpecificVirtualRanges(Account account);

    /**
     * Return the account a VLAN is dedicated to, or {@code null} if it
     * isn't account-specific.
     */
    Account getVlanAccount(long vlanId);

    /**
     * Return the domain a VLAN is dedicated to, or {@code null} if it
     * isn't domain-specific.
     */
    Domain getVlanDomain(long vlanId);

    /**
     * Validate that the given pod CIDR doesn't overlap with any other pod's
     * CIDR in the same zone, nor with the zone's guest IP network.
     */
    void checkPodCidrSubnets(long zoneId, Long podIdToBeSkipped, String cidr);

    // --- Pure helpers exposed for callers (and tests) -----------------

    /** Decide whether the new range is a subset, superset, same, or unrelated. */
    NetUtils.SupersetOrSubset checkIfSubsetOrSuperset(String vlanGateway, String vlanNetmask,
            String newVlanGateway, String newVlanNetmask, String newStartIP, String newEndIP);

    /** Validate the IP range against existing VLANs; return whether it's the same subnet. */
    Pair<Boolean, Pair<String, String>> validateIpRange(String startIP, String endIP, String newVlanGateway,
            String newVlanNetmask, List<VlanVO> vlans, boolean ipv4, boolean ipv6, String ip6Gateway,
            String ip6Cidr, String startIPv6, String endIPv6, Network network);

    /** Determine whether two ranges share the same subnet (IPv4 and/or IPv6). */
    boolean hasSameSubnet(boolean ipv4, String vlanGateway, String vlanNetmask, String newVlanGateway,
            String newVlanNetmask, String newStartIp, String newEndIp, boolean ipv6, String newIp6Gateway,
            String newIp6Cidr, String newIp6StartIp, String newIp6EndIp, Network network);
}
