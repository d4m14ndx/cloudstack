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

import org.apache.cloudstack.api.command.user.vpc.ListVPCOfferingsCmd;

import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.utils.Pair;

/**
 * Read-only query operations for {@link VpcOffering} entities — extracted
 * from {@link VpcManagerImpl} as part of the Phase 4 Spring-component
 * decomposition.
 *
 * <p>This component owns the look-up, search and capability-introspection
 * surface for VPC offerings: fetch by id, paginated/filtered list for the
 * {@code listVPCOfferings} API, resolution of service-provider maps,
 * domain/zone visibility, and the predicate that asks whether a particular
 * service is supported by an offering. Write operations (create / update /
 * delete / clone) remain in {@code VpcManagerImpl} for this slice — the
 * god class still calls into this component via thin delegating wrappers
 * so the {@link VpcProvisioningService} contract continues to work.
 */
public interface VpcOfferingQueryService {

    /** Look up a single VPC offering by id, returning {@code null} when not found. */
    VpcOffering getVpcOffering(long vpcOffId);

    /**
     * Build the {@code service -> providers} map for a VPC offering by
     * walking its {@link VpcOfferingServiceMapVO} rows.
     */
    Map<Service, Set<Provider>> getVpcOffSvcProvidersMap(long vpcOffId);

    /**
     * Paginated, ACL-and-domain-filtered list of VPC offerings for the
     * {@code listVPCOfferings} API. Honours name/displayText/state/zone
     * filters, removes offerings the caller cannot see, and (optionally)
     * narrows to offerings that support a given service list.
     */
    Pair<List<? extends VpcOffering>, Integer> listVpcOfferings(ListVPCOfferingsCmd cmd);

    /** True when the VPC offering supports every requested {@link Service}. */
    boolean areServicesSupportedByVpcOffering(long vpcOffId, Service... services);

    /** Domain ids the offering is restricted to (empty when offering is unrestricted). */
    List<Long> getVpcOfferingDomains(Long vpcOfferingId);

    /** Zone ids the offering is restricted to (empty when offering is unrestricted). */
    List<Long> getVpcOfferingZones(Long vpcOfferingId);
}
