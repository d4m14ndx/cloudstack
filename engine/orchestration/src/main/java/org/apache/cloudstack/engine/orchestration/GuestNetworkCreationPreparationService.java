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
package org.apache.cloudstack.engine.orchestration;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;

import com.cloud.dc.DataCenterVO;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.network.Network;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.dao.NetworkVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.user.Account;
import com.cloud.utils.Pair;

public interface GuestNetworkCreationPreparationService {

    GuestNetworkCreationPreparation prepareGuestNetworkCreation(long networkOfferingId, String gateway, String cidr, String vlanId,
            boolean bypassVlanOverlapCheck, String networkDomain, Account owner, Long domainId, PhysicalNetwork physicalNetwork, long zoneId, ACLType aclType,
            Boolean subdomainAccess, String ip6Gateway, String ip6Cidr, String isolatedPvlan, Network.PVlanType isolatedPvlanType, String externalId,
            Boolean isPrivateNetwork, String routerIp, String routerIpv6, String ip4Dns1, String ip4Dns2, String ip6Dns1, String ip6Dns2,
            Pair<Integer, Integer> vrIfaceMTUs, Integer networkCidrSize, boolean keepMacAddressOnPublicNic);
}

class GuestNetworkCreationPreparation {
    private final NetworkOfferingVO networkOffering;
    private final DataCenterVO zone;
    private final String networkDomain;
    private final Boolean subdomainAccess;
    private final DataCenterDeployment plan;
    private final NetworkVO predefinedNetwork;

    GuestNetworkCreationPreparation(NetworkOfferingVO networkOffering, DataCenterVO zone, String networkDomain, Boolean subdomainAccess,
            DataCenterDeployment plan, NetworkVO predefinedNetwork) {
        this.networkOffering = networkOffering;
        this.zone = zone;
        this.networkDomain = networkDomain;
        this.subdomainAccess = subdomainAccess;
        this.plan = plan;
        this.predefinedNetwork = predefinedNetwork;
    }

    public NetworkOfferingVO getNetworkOffering() {
        return networkOffering;
    }

    public DataCenterVO getZone() {
        return zone;
    }

    public String getNetworkDomain() {
        return networkDomain;
    }

    public Boolean getSubdomainAccess() {
        return subdomainAccess;
    }

    public DataCenterDeployment getPlan() {
        return plan;
    }

    public NetworkVO getPredefinedNetwork() {
        return predefinedNetwork;
    }
}
