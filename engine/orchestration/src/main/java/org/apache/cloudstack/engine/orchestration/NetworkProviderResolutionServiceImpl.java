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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkServiceMapVO;
import com.cloud.network.element.DhcpServiceProvider;
import com.cloud.network.element.DnsServiceProvider;
import com.cloud.network.element.LoadBalancingServiceProvider;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.element.StaticNatServiceProvider;
import com.cloud.network.element.UserDataServiceProvider;
import com.cloud.network.rules.LoadBalancerContainer.Scheme;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.dao.NetworkOfferingDetailsDao;
import com.cloud.utils.db.EntityManager;

/**
 * Lookups that resolve which network {@link Provider} (or {@link
 * NetworkElement}) implements a given {@link Service} on a particular
 * {@link Network} -- extracted from {@link NetworkOrchestrator}.
 *
 * @see NetworkProviderResolutionService
 */
@Component
public class NetworkProviderResolutionServiceImpl implements NetworkProviderResolutionService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected NetworkServiceMapDao networkServiceMapDao;

    @Inject
    protected NetworkModel networkModel;

    @Inject
    protected NetworkOfferingDetailsDao networkOfferingDetailsDao;

    @Inject
    protected EntityManager entityManager;

    @Override
    public List<Provider> getProvidersForServiceInNetwork(final Network network, final Service service) {
        final Map<Service, Set<Provider>> service2ProviderMap = getServiceProvidersMap(network.getId());
        if (service2ProviderMap.get(service) != null) {
            return new ArrayList<>(service2ProviderMap.get(service));
        }
        return null;
    }

    @Override
    public List<NetworkElement> getElementForServiceInNetwork(final Network network, final Service service) {
        final List<NetworkElement> elements = new ArrayList<>();
        final List<Provider> providers = getProvidersForServiceInNetwork(network, service);
        // Only support one provider now (except for Lb)
        if (providers == null) {
            logger.error("Cannot find {} provider for network {}", service.getName(), network);
            return null;
        }
        if (providers.size() != 1 && service != Service.Lb) {
            // support more than one LB providers only
            logger.error("Found {} {} providers for network! {}", providers.size(), service.getName(), network);
            return null;
        }

        for (final Provider provider : providers) {
            final NetworkElement element = networkModel.getElementImplementingProvider(provider.getName());
            logger.info("Let {} handle {} in network {}", element.getName(), service.getName(), network);
            elements.add(element);
        }
        return elements;
    }

    @Override
    public StaticNatServiceProvider getStaticNatProviderForNetwork(final Network network) {
        // only one provider per Static nat service is supported
        final NetworkElement element = getElementForServiceInNetwork(network, Service.StaticNat).get(0);
        assert element instanceof StaticNatServiceProvider;
        return (StaticNatServiceProvider) element;
    }

    @Override
    public LoadBalancingServiceProvider getLoadBalancingProviderForNetwork(final Network network, final Scheme lbScheme) {
        final List<NetworkElement> lbElements = getElementForServiceInNetwork(network, Service.Lb);
        NetworkElement lbElement = null;
        if (lbElements.size() > 1) {
            String providerName;
            // get network offering details
            final NetworkOffering off = entityManager.findById(NetworkOffering.class, network.getNetworkOfferingId());
            if (lbScheme == Scheme.Public) {
                providerName = networkOfferingDetailsDao.getDetail(off.getId(), NetworkOffering.Detail.PublicLbProvider);
            } else {
                providerName = networkOfferingDetailsDao.getDetail(off.getId(), NetworkOffering.Detail.InternalLbProvider);
            }
            if (providerName == null) {
                throw new InvalidParameterValueException("Can't find Lb provider supporting scheme " + lbScheme.toString() + " in network " + network);
            }
            lbElement = networkModel.getElementImplementingProvider(providerName);
        } else if (lbElements.size() == 1) {
            lbElement = lbElements.get(0);
        }

        assert lbElement != null;
        assert lbElement instanceof LoadBalancingServiceProvider;
        return (LoadBalancingServiceProvider) lbElement;
    }

    @Override
    public UserDataServiceProvider getPasswordResetProvider(final Network network) {
        final String passwordProvider = networkServiceMapDao.getProviderForServiceInNetwork(network.getId(), Service.UserData);

        if (passwordProvider == null) {
            logger.debug("Network {} doesn't support service {}", network, Service.UserData.getName());
            return null;
        }

        return (UserDataServiceProvider) networkModel.getElementImplementingProvider(passwordProvider);
    }

    @Override
    public UserDataServiceProvider getSSHKeyResetProvider(final Network network) {
        final String sshKeyProvider = networkServiceMapDao.getProviderForServiceInNetwork(network.getId(), Service.UserData);

        if (sshKeyProvider == null) {
            logger.debug("Network {} doesn't support service", network, Service.UserData.getName());
            return null;
        }

        return (UserDataServiceProvider) networkModel.getElementImplementingProvider(sshKeyProvider);
    }

    @Override
    public DhcpServiceProvider getDhcpServiceProvider(final Network network) {
        final String dhcpProvider = networkServiceMapDao.getProviderForServiceInNetwork(network.getId(), Service.Dhcp);

        if (dhcpProvider == null) {
            logger.debug("Network {} doesn't support service {}", network, Service.Dhcp.getName());
            return null;
        }

        final NetworkElement element = networkModel.getElementImplementingProvider(dhcpProvider);
        if (element instanceof DhcpServiceProvider) {
            return (DhcpServiceProvider) element;
        } else {
            return null;
        }
    }

    @Override
    public DnsServiceProvider getDnsServiceProvider(final Network network) {
        final String dnsProvider = networkServiceMapDao.getProviderForServiceInNetwork(network.getId(), Service.Dns);

        if (dnsProvider == null) {
            logger.debug("Network {} doesn't support service {}", network, Service.Dhcp.getName());
            return null;
        }

        return (DnsServiceProvider) networkModel.getElementImplementingProvider(dnsProvider);
    }

    /**
     * Build a Service -> Providers map for the given network by reading
     * {@code NetworkServiceMapDao}. Internal helper used by {@link
     * #getProvidersForServiceInNetwork(Network, Service)}.
     */
    protected Map<Service, Set<Provider>> getServiceProvidersMap(final long networkId) {
        final Map<Service, Set<Provider>> map = new HashMap<>();
        final List<NetworkServiceMapVO> nsms = networkServiceMapDao.getServicesInNetwork(networkId);
        for (final NetworkServiceMapVO nsm : nsms) {
            Set<Provider> providers = map.get(Service.getService(nsm.getService()));
            if (providers == null) {
                providers = new HashSet<>();
            }
            providers.add(Provider.getProvider(nsm.getProvider()));
            map.put(Service.getService(nsm.getService()), providers);
        }
        return map;
    }
}
