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
import java.util.Map;
import java.util.Set;

import org.apache.cloudstack.api.command.admin.vpc.CloneVPCOfferingCmd;
import org.apache.cloudstack.api.command.admin.vpc.CreateVPCOfferingCmd;
import org.apache.cloudstack.api.command.admin.vpc.UpdateVPCOfferingCmd;

import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.vpc.VpcOffering.State;
import com.cloud.offering.NetworkOffering;
import com.cloud.utils.net.NetUtils;

/**
 * CRUD operations for {@link VpcOffering} entities — extracted from
 * {@link VpcManagerImpl} as part of the Phase 4 Spring-component decomposition.
 *
 * <p>This component owns create, clone, delete and update of VPC offerings.
 * Read/query operations remain in {@link VpcOfferingQueryService}.
 */
public interface VpcOfferingCrudService {

    /** Create a VPC offering from an API command. */
    VpcOffering createVpcOffering(CreateVPCOfferingCmd cmd);

    /**
     * Create a VPC offering from raw parameters (used during initial
     * configuration and by API command processing).
     */
    VpcOffering createVpcOffering(String name, String displayText,
            List<String> supportedServices,
            Map<String, List<String>> serviceProviders,
            Map serviceCapabilityList,
            NetUtils.InternetProtocol internetProtocol,
            Long serviceOfferingId,
            String externalProvider,
            NetworkOffering.NetworkMode networkMode,
            List<Long> domainIds, List<Long> zoneIds,
            State state,
            NetworkOffering.RoutingMode routingMode,
            boolean specifyAsNumber,
            boolean conserveMode);

    /** Clone an existing VPC offering into a new one. */
    VpcOffering cloneVPCOffering(CloneVPCOfferingCmd cmd);

    /** Delete a VPC offering by id. Returns {@code true} on success. */
    boolean deleteVpcOffering(long offId);

    /** Update a VPC offering (legacy three-parameter overload). */
    VpcOffering updateVpcOffering(long vpcOffId, String vpcOfferingName, String displayText, String state);

    /** Update a VPC offering from an API command. */
    VpcOffering updateVpcOffering(UpdateVPCOfferingCmd cmd);

    /**
     * Low-level transactional create used during initial configuration (when only a
     * {@code Map<Service, Set<Provider>>} is available, not a full command object).
     */
    VpcOfferingVO createVpcOfferingInternal(String name, String displayText,
            Map<Service, Set<Provider>> svcProviderMap,
            boolean isDefault, State state, Long serviceOfferingId,
            boolean supportsDistributedRouter, boolean offersRegionLevelVPC,
            boolean redundantRouter, NetworkOffering.NetworkMode networkMode,
            NetworkOffering.RoutingMode routingMode, boolean specifyAsNumber,
            boolean conserveMode);
}
