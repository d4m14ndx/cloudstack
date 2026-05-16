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

import java.util.List;

import com.cloud.network.Network;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.element.DhcpServiceProvider;
import com.cloud.network.element.DnsServiceProvider;
import com.cloud.network.element.LoadBalancingServiceProvider;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.element.StaticNatServiceProvider;
import com.cloud.network.element.UserDataServiceProvider;
import com.cloud.network.rules.LoadBalancerContainer.Scheme;

/**
 * Pure lookup helpers that map a {@link Network} (plus a
 * {@link Service}) onto the {@link Provider}(s) or {@link NetworkElement}(s)
 * that actually implement the service for that network.
 *
 * <p>Extracted from {@link NetworkOrchestrator} as part of the Phase 4
 * Spring-component decomposition. The orchestrator continues to expose
 * the corresponding methods on {@link
 * org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService},
 * each as a one-line wrapper that delegates here, so existing call
 * sites and test spies keep working unchanged.
 *
 * <p>Nothing in here mutates state -- every method is a read-only lookup
 * over {@code NetworkServiceMapDao}, {@code NetworkModel},
 * {@code NetworkOfferingDetailsDao} and the {@code EntityManager}.
 */
public interface NetworkProviderResolutionService {

    /**
     * Return the list of {@link Provider}s configured for {@code service}
     * on the given {@code network}, or {@code null} if the network does
     * not have any provider for that service.
     */
    List<Provider> getProvidersForServiceInNetwork(Network network, Service service);

    /**
     * Return the {@link NetworkElement}s implementing {@code service} on
     * the given {@code network}.
     *
     * <p>Returns {@code null} when no provider is configured for the
     * service. For every service except {@link Service#Lb} the result
     * contains a single element; an error is logged and {@code null}
     * returned if more than one provider is found for a non-LB service.
     */
    List<NetworkElement> getElementForServiceInNetwork(Network network, Service service);

    /**
     * Return the {@link StaticNatServiceProvider} for {@code network}.
     * Only one provider per static-NAT service is supported.
     */
    StaticNatServiceProvider getStaticNatProviderForNetwork(Network network);

    /**
     * Return the {@link LoadBalancingServiceProvider} for {@code network}
     * matching the requested {@link Scheme}. When the network offering
     * configures separate public and internal LB providers, the matching
     * one for {@code lbScheme} is resolved via
     * {@code NetworkOfferingDetailsDao}; otherwise the single configured
     * LB element is returned.
     *
     * @throws com.cloud.exception.InvalidParameterValueException
     *         when multiple LB providers are configured but no provider
     *         is recorded in the offering details for the requested scheme.
     */
    LoadBalancingServiceProvider getLoadBalancingProviderForNetwork(Network network, Scheme lbScheme);

    /**
     * Return the {@link UserDataServiceProvider} responsible for
     * password reset on the network (delegates to the {@link
     * Service#UserData} provider). Returns {@code null} if no provider
     * is configured.
     */
    UserDataServiceProvider getPasswordResetProvider(Network network);

    /**
     * Return the {@link UserDataServiceProvider} responsible for
     * SSH-key reset on the network (delegates to the {@link
     * Service#UserData} provider). Returns {@code null} if no provider
     * is configured.
     */
    UserDataServiceProvider getSSHKeyResetProvider(Network network);

    /**
     * Return the {@link DhcpServiceProvider} for {@code network}, or
     * {@code null} when no DHCP provider is configured or the
     * configured provider's element is not a {@link DhcpServiceProvider}.
     */
    DhcpServiceProvider getDhcpServiceProvider(Network network);

    /**
     * Return the {@link DnsServiceProvider} for {@code network}, or
     * {@code null} when no DNS provider is configured.
     */
    DnsServiceProvider getDnsServiceProvider(Network network);
}
