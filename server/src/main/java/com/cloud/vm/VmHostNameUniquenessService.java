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

import java.util.List;
import java.util.Map;
import java.util.Set;

import com.cloud.network.dao.NetworkVO;

/**
 * Network-domain helpers that gate VM creation: enforcing distinct
 * hostnames per network-domain scope ({@code global / domain /
 * subdomain / account / network}) and verifying that any extra DHCP
 * options reference a network the VM actually has a NIC in.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 13). The god class keeps
 * one-line delegating wrappers so existing test spies and call sites
 * continue to work.
 */
public interface VmHostNameUniquenessService {

    /**
     * Reject the VM create if {@code hostName} would collide with an
     * existing VM hostname in any network the new VM joins — scoped
     * by the {@code vm.distinct.hostname.scope} global config (which
     * may broaden the search to every network sharing the same network
     * domain in the same account / domain / subdomain / globally).
     *
     * @throws com.cloud.exception.InvalidParameterValueException
     *         if a collision exists.
     */
    void checkIfHostNameUniqueInNtwkDomain(String hostName, List<NetworkVO> networkList);

    /**
     * Reject the VM create if any of the network UUIDs referenced in
     * the extra-DHCP-options map are missing from the VM's NIC list.
     *
     * @throws com.cloud.exception.InvalidParameterValueException
     *         if {@code dhcpOptionsMap} references a network the VM
     *         is not joining.
     */
    void verifyExtraDhcpOptionsNetwork(Map<String, Map<Integer, String>> dhcpOptionsMap, List<NetworkVO> networkList);

    /**
     * Expand {@code networkList} to every network that participates in
     * the hostname-uniqueness check under the current scope —
     * defaulting to "network only" (plus all VPC networks if any of
     * the input networks live in a VPC).
     */
    List<NetworkVO> getNetworksForCheckUniqueHostName(List<NetworkVO> networkList);

    /**
     * Group {@code networkList} by network-domain after expanding the
     * search via {@link #getNetworksForCheckUniqueHostName}.
     */
    Map<String, Set<Long>> getNetworkIdPerNetworkDomain(List<NetworkVO> networkList);

    /**
     * Expand {@code networkList} to every network sharing one of the
     * same network domains in the same domain (and optionally its
     * subdomains).
     */
    List<NetworkVO> getNetworksWithSameNetworkDomainInDomains(List<NetworkVO> networkList, boolean checkSubDomains);
}
