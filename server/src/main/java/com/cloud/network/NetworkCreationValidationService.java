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

import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.PVlanType;
import com.cloud.network.vpc.Vpc;
import com.cloud.offering.NetworkOffering;
import com.cloud.user.Account;
import com.cloud.utils.Pair;

public interface NetworkCreationValidationService {

    void checkSharedNetworkCidrOverlap(Long zoneId, long physicalNetworkId, String cidr);

    void validateNetworkCidrSize(Account caller, Integer cidrSize, String cidr, NetworkOffering networkOffering, long accountId, long zoneId);

    void validateSharedNetworkRouterIPs(String gateway, String startIP, String endIP, String netmask,
            String routerIPv4, String routerIPv6, String startIPv6, String endIPv6, String ip6Cidr,
            NetworkOffering ntwkOff);

    String getVpcPrependedNetworkName(String networkName, Vpc vpc);

    boolean isNonVpcNetworkSupportingDynamicRouting(NetworkOffering networkOffering);

    void validateNetworkCreationSupported(long zoneId, String zoneName, GuestType guestType);

    boolean getAndValidateSupportForKeepMacAddressOnPublicNicParameter(Boolean keepMacAddressOnPublicNic,
            NetworkOffering networkOffering);

    void validateNetworkOfferingForNonRootAdminUser(NetworkOffering ntwkOff);

    Pair<String, PVlanType> getPrivateVlanPair(String pvlanId, String pvlanTypeStr, String vlanId);

    void performBasicPrivateVlanChecks(String vlanId, String secondaryVlanId, PVlanType privateVlanType);
}
