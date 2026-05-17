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

import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.network.Network.IpAddresses;
import com.cloud.vm.NicSecondaryIp;

/**
 * Encapsulates allocation, configuration and release of secondary IP addresses
 * on a VM NIC. Extracted from {@link NetworkServiceImpl} as part of the
 * Phase 4 god-class decomposition.
 */
public interface NicSecondaryIpService {

    /**
     * Configures security-group rules for the supplied secondary IP when the
     * containing zone has security groups enabled.
     */
    boolean configureNicSecondaryIp(NicSecondaryIp secIp, boolean isZoneSgEnabled);

    /**
     * Allocates a secondary IP (v4 and/or v6) on the given NIC.
     */
    NicSecondaryIp allocateSecondaryGuestIP(long nicId, IpAddresses requestedIpPair) throws InsufficientAddressCapacityException;

    /**
     * Releases a previously allocated secondary IP from the NIC it was assigned to.
     */
    boolean releaseSecondaryIpFromNic(long ipAddressId);
}
