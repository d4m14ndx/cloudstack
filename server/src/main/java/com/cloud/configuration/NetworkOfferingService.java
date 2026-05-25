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
import java.util.Map;
import java.util.Set;

import org.apache.cloudstack.api.command.admin.network.CloneNetworkOfferingCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteNetworkOfferingCmd;
import org.apache.cloudstack.api.command.admin.network.NetworkOfferingBaseCmd;
import org.apache.cloudstack.api.command.admin.network.UpdateNetworkOfferingCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkOfferingsCmd;

import com.cloud.network.Network;
import com.cloud.network.Networks.TrafficType;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.utils.Pair;
import com.cloud.utils.net.NetUtils;

public interface NetworkOfferingService {

    NetworkOffering createNetworkOffering(NetworkOfferingBaseCmd cmd);

    NetworkOfferingVO createNetworkOffering(String name, String displayText, TrafficType trafficType,
            String tags, boolean specifyVlan, NetworkOffering.Availability availability, Integer networkRate,
            Map<Network.Service, Set<Network.Provider>> serviceProviderMap, boolean isDefault,
            Network.GuestType type, boolean systemOnly, Long serviceOfferingId, boolean conserveMode,
            Map<Network.Service, Map<Network.Capability, String>> serviceCapabilityMap, boolean specifyIpRanges,
            boolean isPersistent, Map<NetworkOffering.Detail, String> details, boolean egressDefaultPolicy,
            Integer maxconn, boolean enableKeepAlive, Boolean forVpc, Boolean forTungsten, boolean forNsx,
            boolean forNetris, NetworkOffering.NetworkMode networkMode, List<Long> domainIds, List<Long> zoneIds,
            boolean enableOffering, NetUtils.InternetProtocol internetProtocol,
            NetworkOffering.RoutingMode routingMode, boolean specifyAsNumber);

    NetworkOffering updateNetworkOffering(UpdateNetworkOfferingCmd cmd);

    Pair<List<? extends NetworkOffering>, Integer> searchForNetworkOfferings(ListNetworkOfferingsCmd cmd);

    boolean deleteNetworkOffering(DeleteNetworkOfferingCmd cmd);

    NetworkOffering cloneNetworkOffering(CloneNetworkOfferingCmd cmd);

    boolean isOfferingForVpc(NetworkOffering offering);

    Integer getNetworkOfferingNetworkRate(long networkOfferingId, Long dataCenterId);

    List<Long> getNetworkOfferingDomains(Long networkOfferingId);

    List<Long> getNetworkOfferingZones(Long networkOfferingId);

    List<? extends NetworkOffering> listNetworkOfferings(TrafficType trafficType, boolean systemOnly);
}
