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
package com.cloud.network;

import com.cloud.dc.DataCenter;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.Pair;

/**
 * Network DNS lookup and validation helpers — resolves the effective
 * IPv4/IPv6 DNS pair for a network using the standard network ->
 * VPC -> zone fallback chain, and validates user-supplied DNS pair
 * inputs against IPv4/IPv6 formats and the "DNS2 requires DNS1" rule.
 *
 * <p>Extracted from {@link NetworkModelImpl} as part of the Phase 4
 * Spring-component decomposition (parallel slice on this file).
 * {@code NetworkModelImpl} keeps delegating wrappers so the
 * {@link NetworkModel} interface contract is preserved.
 */
public interface NetworkDnsResolver {

    /**
     * Resolve the effective IPv4 DNS pair for a network. Falls back
     * from network-level DNS, to the network's VPC (if any), to the
     * zone-level DNS. The pair is returned as-is from the first level
     * that has a non-blank {@code dns1}; {@code dns2} may be null.
     */
    Pair<String, String> getNetworkIp4Dns(Network network, DataCenter zone);

    /**
     * Resolve the effective IPv6 DNS pair for a network. Falls back
     * from network-level IPv6 DNS, to the network's VPC (if any), to
     * the zone-level IPv6 DNS. The pair is returned as-is from the
     * first level that has a non-blank {@code ip6Dns1}; {@code ip6Dns2}
     * may be null.
     */
    Pair<String, String> getNetworkIp6Dns(Network network, DataCenter zone);

    /**
     * Validate a user-supplied IPv4 DNS pair. Throws
     * {@link InvalidParameterValueException} when DNS2 is set without
     * DNS1, or when either value is non-blank but not a valid IPv4.
     */
    void verifyIp4DnsPair(String ip4Dns1, String ip4Dns2);

    /**
     * Validate a user-supplied IPv6 DNS pair. Throws
     * {@link InvalidParameterValueException} when DNS2 is set without
     * DNS1, or when either value is non-blank but not a valid IPv6.
     */
    void verifyIp6DnsPair(String ip6Dns1, String ip6Dns2);
}
