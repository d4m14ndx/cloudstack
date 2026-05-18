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

import java.util.List;

import org.apache.cloudstack.api.command.admin.usage.ListTrafficTypeImplementorsCmd;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.utils.Pair;

/**
 * Physical network lifecycle operations — extracted from {@link NetworkServiceImpl}.
 */
public interface PhysicalNetworkManagementService {

    PhysicalNetwork createPhysicalNetwork(Long zoneId, String vnetRange, String networkSpeed,
            List<String> isolationMethods, String broadcastDomainRangeStr, Long domainId,
            List<String> tags, String name);

    Pair<List<? extends PhysicalNetwork>, Integer> searchPhysicalNetworks(Long id, Long zoneId,
            String keyword, Long startIndex, Long pageSize, String name);

    PhysicalNetwork updatePhysicalNetwork(Long id, String networkSpeed, List<String> tags,
            String newVnetRange, String state);

    void addOrRemoveVnets(String[] listOfRanges, PhysicalNetworkVO network);

    boolean deletePhysicalNetwork(Long physicalNetworkId);

    List<? extends Network.Service> listNetworkServices(String providerName);

    PhysicalNetworkServiceProvider addProviderToPhysicalNetwork(Long physicalNetworkId,
            String providerName, Long destinationPhysicalNetworkId, List<String> enabledServices);

    Pair<List<? extends PhysicalNetworkServiceProvider>, Integer> listNetworkServiceProviders(
            Long physicalNetworkId, String name, String state, Long startIndex, Long pageSize);

    PhysicalNetworkServiceProvider updateNetworkServiceProvider(Long id, String stateStr,
            List<String> enabledServices) throws ResourceUnavailableException, ConcurrentOperationException;

    boolean deleteNetworkServiceProvider(Long id)
            throws ConcurrentOperationException, ResourceUnavailableException;

    PhysicalNetwork getPhysicalNetwork(Long physicalNetworkId);

    PhysicalNetwork getCreatedPhysicalNetwork(Long physicalNetworkId);

    PhysicalNetworkServiceProvider getPhysicalNetworkServiceProvider(Long providerId);

    PhysicalNetworkServiceProvider getCreatedPhysicalNetworkServiceProvider(Long providerId);

    long findPhysicalNetworkId(long zoneId, String tag, TrafficType trafficType);

    PhysicalNetworkTrafficType addTrafficTypeToPhysicalNetwork(Long physicalNetworkId,
            String trafficTypeStr, String isolationMethod, String xenLabel, String kvmLabel,
            String vmwareLabel, String simulatorLabel, String vlan, String hypervLabel);

    PhysicalNetworkTrafficType getPhysicalNetworkTrafficType(Long id);

    PhysicalNetworkTrafficType updatePhysicalNetworkTrafficType(Long id, String xenLabel,
            String kvmLabel, String vmwareLabel, String hypervLabel);

    boolean deletePhysicalNetworkTrafficType(Long id);

    Pair<List<? extends PhysicalNetworkTrafficType>, Integer> listTrafficTypes(Long physicalNetworkId);

    List<Pair<TrafficType, String>> listTrafficTypeImplementor(ListTrafficTypeImplementorsCmd cmd);

    // addDefault* helpers exposed so god-class protected wrappers can delegate
    PhysicalNetworkServiceProvider addDefaultVirtualRouterToPhysicalNetwork(long physicalNetworkId);

    PhysicalNetworkServiceProvider addDefaultVpcVirtualRouterToPhysicalNetwork(long physicalNetworkId);

    PhysicalNetworkServiceProvider addDefaultInternalLbProviderToPhysicalNetwork(long physicalNetworkId);

    PhysicalNetworkServiceProvider addDefaultSecurityGroupProviderToPhysicalNetwork(long physicalNetworkId);
}
