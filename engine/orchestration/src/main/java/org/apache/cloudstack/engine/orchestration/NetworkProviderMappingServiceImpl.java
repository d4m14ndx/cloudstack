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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.springframework.stereotype.Component;

import com.cloud.exception.UnsupportedServiceException;
import com.cloud.network.Network.Provider;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingServiceMapVO;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;

@Component
public class NetworkProviderMappingServiceImpl implements NetworkProviderMappingService {

    @Inject
    protected NetworkOfferingServiceMapDao networkOfferingServiceMapDao;

    @Inject
    protected NetworkModel networkModel;

    @Inject
    protected PhysicalNetworkServiceProviderDao physicalNetworkServiceProviderDao;

    @Inject
    protected NetworkServiceMapDao networkServiceMapDao;

    @Override
    public Map<String, String> finalizeServicesAndProvidersForNetwork(final NetworkOffering offering, final Long physicalNetworkId) {
        final Map<String, String> svcProviders = new HashMap<>();
        final Map<String, List<String>> providerSvcs = new HashMap<>();
        final List<NetworkOfferingServiceMapVO> servicesMap = networkOfferingServiceMapDao.listByNetworkOfferingId(offering.getId());

        final boolean checkPhysicalNetwork = physicalNetworkId != null;

        for (final NetworkOfferingServiceMapVO serviceMap : servicesMap) {
            if (svcProviders.containsKey(serviceMap.getService())) {
                // FIXME - right now we pick up the first provider from the list, need to add more logic based on
                // provider load, etc
                continue;
            }

            final String service = serviceMap.getService();
            String provider = serviceMap.getProvider();

            if (provider == null) {
                provider = networkModel.getDefaultUniqueProviderForService(service).getName();
            }

            if (checkPhysicalNetwork && !physicalNetworkServiceProviderDao.isServiceProviderEnabled(physicalNetworkId, provider, service)) {
                throw new UnsupportedServiceException("Provider " + provider + " is either not enabled or doesn't " + "support service " + service + " in physical network id="
                        + physicalNetworkId);
            }

            svcProviders.put(service, provider);
            List<String> l = providerSvcs.get(provider);
            if (l == null) {
                providerSvcs.put(provider, l = new ArrayList<>());
            }
            l.add(service);
        }

        return svcProviders;
    }

    @Override
    public List<Provider> getNetworkProviders(final long networkId) {
        final List<String> providerNames = networkServiceMapDao.getDistinctProviders(networkId);
        final List<Provider> providers = new ArrayList<>();
        for (final String providerName : providerNames) {
            providers.add(Provider.getProvider(providerName));
        }

        return providers;
    }
}
