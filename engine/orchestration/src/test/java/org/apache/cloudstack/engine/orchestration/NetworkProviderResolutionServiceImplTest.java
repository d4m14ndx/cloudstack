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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

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
 * Focused tests for {@link NetworkProviderResolutionServiceImpl} -- the
 * Phase 4 extraction of network-provider/element lookups out of
 * {@link NetworkOrchestrator}.
 *
 * Behavior here is also exercised indirectly through the orchestrator's
 * delegating wrappers and call sites such as {@code removeNic} that
 * resolve DHCP / DNS providers; these tests target the service directly
 * so future refactors of the orchestrator cannot silently drop coverage.
 */
@RunWith(MockitoJUnitRunner.class)
public class NetworkProviderResolutionServiceImplTest {

    @Mock
    private NetworkServiceMapDao networkServiceMapDao;

    @Mock
    private NetworkModel networkModel;

    @Mock
    private NetworkOfferingDetailsDao networkOfferingDetailsDao;

    @Mock
    private EntityManager entityManager;

    @InjectMocks
    private NetworkProviderResolutionServiceImpl service;

    private Network network;
    private static final long NETWORK_ID = 100L;
    private static final long OFFERING_ID = 7L;

    @Before
    public void setUp() {
        network = Mockito.mock(Network.class);
        Mockito.when(network.getId()).thenReturn(NETWORK_ID);
    }

    private NetworkServiceMapVO mapping(Service service, Provider provider) {
        NetworkServiceMapVO m = Mockito.mock(NetworkServiceMapVO.class);
        Mockito.when(m.getService()).thenReturn(service.getName());
        Mockito.when(m.getProvider()).thenReturn(provider.getName());
        return m;
    }

    // ---------------------------------------------------------------------
    // getProvidersForServiceInNetwork
    // ---------------------------------------------------------------------

    @Test
    public void getProvidersForServiceInNetworkReturnsProviderWhenConfigured() {
        List<NetworkServiceMapVO> mappings = Arrays.asList(mapping(Service.Dhcp, Provider.VirtualRouter));
        Mockito.when(networkServiceMapDao.getServicesInNetwork(NETWORK_ID)).thenReturn(mappings);

        List<Provider> providers = service.getProvidersForServiceInNetwork(network, Service.Dhcp);

        assertNotNull(providers);
        assertEquals(1, providers.size());
        assertEquals(Provider.VirtualRouter, providers.get(0));
    }

    @Test
    public void getProvidersForServiceInNetworkReturnsNullWhenServiceNotMapped() {
        List<NetworkServiceMapVO> mappings = Arrays.asList(mapping(Service.Dhcp, Provider.VirtualRouter));
        Mockito.when(networkServiceMapDao.getServicesInNetwork(NETWORK_ID)).thenReturn(mappings);

        // Asking for Lb -- not mapped -- yields null per the original contract.
        assertNull(service.getProvidersForServiceInNetwork(network, Service.Lb));
    }

    @Test
    public void getProvidersForServiceInNetworkReturnsNullWhenNoMappings() {
        Mockito.when(networkServiceMapDao.getServicesInNetwork(NETWORK_ID))
                .thenReturn(Collections.emptyList());

        assertNull(service.getProvidersForServiceInNetwork(network, Service.Dhcp));
    }

    // ---------------------------------------------------------------------
    // getElementForServiceInNetwork
    // ---------------------------------------------------------------------

    @Test
    public void getElementForServiceInNetworkReturnsNullWhenNoProvider() {
        Mockito.when(networkServiceMapDao.getServicesInNetwork(NETWORK_ID))
                .thenReturn(Collections.emptyList());

        assertNull(service.getElementForServiceInNetwork(network, Service.StaticNat));
    }

    @Test
    public void getElementForServiceInNetworkReturnsNullForNonLbWithMultipleProviders() {
        // Two providers configured for StaticNat -- not allowed; expect null.
        List<NetworkServiceMapVO> mappings = Arrays.asList(
                mapping(Service.StaticNat, Provider.VirtualRouter),
                mapping(Service.StaticNat, Provider.JuniperSRX));
        Mockito.when(networkServiceMapDao.getServicesInNetwork(NETWORK_ID)).thenReturn(mappings);

        assertNull(service.getElementForServiceInNetwork(network, Service.StaticNat));
    }

    @Test
    public void getElementForServiceInNetworkReturnsSingleElement() {
        List<NetworkServiceMapVO> mappings = Arrays.asList(mapping(Service.StaticNat, Provider.VirtualRouter));
        Mockito.when(networkServiceMapDao.getServicesInNetwork(NETWORK_ID)).thenReturn(mappings);
        NetworkElement element = Mockito.mock(NetworkElement.class);
        Mockito.when(element.getName()).thenReturn("VirtualRouter");
        Mockito.when(networkModel.getElementImplementingProvider(Provider.VirtualRouter.getName()))
                .thenReturn(element);

        List<NetworkElement> result = service.getElementForServiceInNetwork(network, Service.StaticNat);

        assertNotNull(result);
        assertEquals(1, result.size());
        assertSame(element, result.get(0));
    }

    @Test
    public void getElementForServiceInNetworkAllowsMultipleLbProviders() {
        List<NetworkServiceMapVO> mappings = Arrays.asList(
                mapping(Service.Lb, Provider.VirtualRouter),
                mapping(Service.Lb, Provider.InternalLbVm));
        Mockito.when(networkServiceMapDao.getServicesInNetwork(NETWORK_ID)).thenReturn(mappings);
        NetworkElement vr = Mockito.mock(NetworkElement.class);
        Mockito.when(vr.getName()).thenReturn("VirtualRouter");
        NetworkElement ilb = Mockito.mock(NetworkElement.class);
        Mockito.when(ilb.getName()).thenReturn("InternalLbVm");
        Mockito.when(networkModel.getElementImplementingProvider(Provider.VirtualRouter.getName())).thenReturn(vr);
        Mockito.when(networkModel.getElementImplementingProvider(Provider.InternalLbVm.getName())).thenReturn(ilb);

        List<NetworkElement> result = service.getElementForServiceInNetwork(network, Service.Lb);

        assertNotNull(result);
        assertEquals(2, result.size());
        assertTrue(result.contains(vr));
        assertTrue(result.contains(ilb));
    }

    // ---------------------------------------------------------------------
    // getStaticNatProviderForNetwork
    // ---------------------------------------------------------------------

    @Test
    public void getStaticNatProviderForNetworkReturnsCastElement() {
        List<NetworkServiceMapVO> mappings = Arrays.asList(mapping(Service.StaticNat, Provider.VirtualRouter));
        Mockito.when(networkServiceMapDao.getServicesInNetwork(NETWORK_ID)).thenReturn(mappings);
        StaticNatServiceProvider snp = Mockito.mock(StaticNatServiceProvider.class,
                Mockito.withSettings().extraInterfaces(NetworkElement.class));
        Mockito.when(((NetworkElement) snp).getName()).thenReturn("VirtualRouter");
        Mockito.when(networkModel.getElementImplementingProvider(Provider.VirtualRouter.getName()))
                .thenReturn((NetworkElement) snp);

        StaticNatServiceProvider result = service.getStaticNatProviderForNetwork(network);

        assertSame(snp, result);
    }

    // ---------------------------------------------------------------------
    // getLoadBalancingProviderForNetwork
    // ---------------------------------------------------------------------

    @Test
    public void getLoadBalancingProviderForNetworkReturnsSingleElement() {
        List<NetworkServiceMapVO> mappings = Arrays.asList(mapping(Service.Lb, Provider.VirtualRouter));
        Mockito.when(networkServiceMapDao.getServicesInNetwork(NETWORK_ID)).thenReturn(mappings);
        LoadBalancingServiceProvider lbp = Mockito.mock(LoadBalancingServiceProvider.class,
                Mockito.withSettings().extraInterfaces(NetworkElement.class));
        Mockito.when(((NetworkElement) lbp).getName()).thenReturn("VirtualRouter");
        Mockito.when(networkModel.getElementImplementingProvider(Provider.VirtualRouter.getName()))
                .thenReturn((NetworkElement) lbp);

        LoadBalancingServiceProvider result = service.getLoadBalancingProviderForNetwork(network, Scheme.Public);

        assertSame(lbp, result);
    }

    @Test
    public void getLoadBalancingProviderForNetworkResolvesPublicLbProviderFromOfferingDetails() {
        Mockito.when(network.getNetworkOfferingId()).thenReturn(OFFERING_ID);
        List<NetworkServiceMapVO> mappings = Arrays.asList(
                mapping(Service.Lb, Provider.VirtualRouter),
                mapping(Service.Lb, Provider.InternalLbVm));
        Mockito.when(networkServiceMapDao.getServicesInNetwork(NETWORK_ID)).thenReturn(mappings);
        NetworkElement vrElement = Mockito.mock(NetworkElement.class);
        NetworkElement ilbElement = Mockito.mock(NetworkElement.class);
        Mockito.when(networkModel.getElementImplementingProvider(Provider.VirtualRouter.getName()))
                .thenReturn(vrElement);
        Mockito.when(networkModel.getElementImplementingProvider(Provider.InternalLbVm.getName()))
                .thenReturn(ilbElement);

        NetworkOffering offering = Mockito.mock(NetworkOffering.class);
        Mockito.when(offering.getId()).thenReturn(OFFERING_ID);
        Mockito.when(entityManager.findById(NetworkOffering.class, OFFERING_ID)).thenReturn(offering);
        Mockito.when(networkOfferingDetailsDao.getDetail(OFFERING_ID, NetworkOffering.Detail.PublicLbProvider))
                .thenReturn("VirtualRouter");

        LoadBalancingServiceProvider selected = Mockito.mock(LoadBalancingServiceProvider.class,
                Mockito.withSettings().extraInterfaces(NetworkElement.class));
        Mockito.when(networkModel.getElementImplementingProvider("VirtualRouter"))
                .thenReturn((NetworkElement) selected);

        LoadBalancingServiceProvider result = service.getLoadBalancingProviderForNetwork(network, Scheme.Public);

        assertSame(selected, result);
        Mockito.verify(networkOfferingDetailsDao).getDetail(OFFERING_ID, NetworkOffering.Detail.PublicLbProvider);
        Mockito.verify(networkOfferingDetailsDao, Mockito.never())
                .getDetail(ArgumentMatchers.anyLong(), ArgumentMatchers.eq(NetworkOffering.Detail.InternalLbProvider));
    }

    @Test
    public void getLoadBalancingProviderForNetworkResolvesInternalLbProviderFromOfferingDetails() {
        Mockito.when(network.getNetworkOfferingId()).thenReturn(OFFERING_ID);
        List<NetworkServiceMapVO> mappings = Arrays.asList(
                mapping(Service.Lb, Provider.VirtualRouter),
                mapping(Service.Lb, Provider.InternalLbVm));
        Mockito.when(networkServiceMapDao.getServicesInNetwork(NETWORK_ID)).thenReturn(mappings);
        NetworkElement vrElement = Mockito.mock(NetworkElement.class);
        NetworkElement ilbElement = Mockito.mock(NetworkElement.class);
        Mockito.when(networkModel.getElementImplementingProvider(Provider.VirtualRouter.getName()))
                .thenReturn(vrElement);
        Mockito.when(networkModel.getElementImplementingProvider(Provider.InternalLbVm.getName()))
                .thenReturn(ilbElement);

        NetworkOffering offering = Mockito.mock(NetworkOffering.class);
        Mockito.when(offering.getId()).thenReturn(OFFERING_ID);
        Mockito.when(entityManager.findById(NetworkOffering.class, OFFERING_ID)).thenReturn(offering);
        Mockito.when(networkOfferingDetailsDao.getDetail(OFFERING_ID, NetworkOffering.Detail.InternalLbProvider))
                .thenReturn("InternalLbVm");

        LoadBalancingServiceProvider selected = Mockito.mock(LoadBalancingServiceProvider.class,
                Mockito.withSettings().extraInterfaces(NetworkElement.class));
        Mockito.when(networkModel.getElementImplementingProvider("InternalLbVm"))
                .thenReturn((NetworkElement) selected);

        LoadBalancingServiceProvider result = service.getLoadBalancingProviderForNetwork(network, Scheme.Internal);

        assertSame(selected, result);
        Mockito.verify(networkOfferingDetailsDao).getDetail(OFFERING_ID, NetworkOffering.Detail.InternalLbProvider);
    }

    @Test
    public void getLoadBalancingProviderForNetworkThrowsWhenProviderNotConfiguredInOfferingDetails() {
        Mockito.when(network.getNetworkOfferingId()).thenReturn(OFFERING_ID);
        List<NetworkServiceMapVO> mappings = Arrays.asList(
                mapping(Service.Lb, Provider.VirtualRouter),
                mapping(Service.Lb, Provider.InternalLbVm));
        Mockito.when(networkServiceMapDao.getServicesInNetwork(NETWORK_ID)).thenReturn(mappings);
        NetworkElement vrElement = Mockito.mock(NetworkElement.class);
        NetworkElement ilbElement = Mockito.mock(NetworkElement.class);
        Mockito.when(networkModel.getElementImplementingProvider(Provider.VirtualRouter.getName()))
                .thenReturn(vrElement);
        Mockito.when(networkModel.getElementImplementingProvider(Provider.InternalLbVm.getName()))
                .thenReturn(ilbElement);

        NetworkOffering offering = Mockito.mock(NetworkOffering.class);
        Mockito.when(offering.getId()).thenReturn(OFFERING_ID);
        Mockito.when(entityManager.findById(NetworkOffering.class, OFFERING_ID)).thenReturn(offering);
        Mockito.when(networkOfferingDetailsDao.getDetail(OFFERING_ID, NetworkOffering.Detail.PublicLbProvider))
                .thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.getLoadBalancingProviderForNetwork(network, Scheme.Public));
    }

    // ---------------------------------------------------------------------
    // getPasswordResetProvider / getSSHKeyResetProvider
    // ---------------------------------------------------------------------

    @Test
    public void getPasswordResetProviderReturnsCastUserDataProvider() {
        Mockito.when(networkServiceMapDao.getProviderForServiceInNetwork(NETWORK_ID, Service.UserData))
                .thenReturn("VirtualRouter");
        UserDataServiceProvider udp = Mockito.mock(UserDataServiceProvider.class,
                Mockito.withSettings().extraInterfaces(NetworkElement.class));
        Mockito.when(networkModel.getElementImplementingProvider("VirtualRouter"))
                .thenReturn((NetworkElement) udp);

        UserDataServiceProvider result = service.getPasswordResetProvider(network);

        assertSame(udp, result);
    }

    @Test
    public void getPasswordResetProviderReturnsNullWhenNoProvider() {
        Mockito.when(networkServiceMapDao.getProviderForServiceInNetwork(NETWORK_ID, Service.UserData))
                .thenReturn(null);

        assertNull(service.getPasswordResetProvider(network));
        Mockito.verify(networkModel, Mockito.never())
                .getElementImplementingProvider(ArgumentMatchers.anyString());
    }

    @Test
    public void getSSHKeyResetProviderReturnsCastUserDataProvider() {
        Mockito.when(networkServiceMapDao.getProviderForServiceInNetwork(NETWORK_ID, Service.UserData))
                .thenReturn("VirtualRouter");
        UserDataServiceProvider udp = Mockito.mock(UserDataServiceProvider.class,
                Mockito.withSettings().extraInterfaces(NetworkElement.class));
        Mockito.when(networkModel.getElementImplementingProvider("VirtualRouter"))
                .thenReturn((NetworkElement) udp);

        UserDataServiceProvider result = service.getSSHKeyResetProvider(network);

        assertSame(udp, result);
    }

    @Test
    public void getSSHKeyResetProviderReturnsNullWhenNoProvider() {
        Mockito.when(networkServiceMapDao.getProviderForServiceInNetwork(NETWORK_ID, Service.UserData))
                .thenReturn(null);

        assertNull(service.getSSHKeyResetProvider(network));
        Mockito.verify(networkModel, Mockito.never())
                .getElementImplementingProvider(ArgumentMatchers.anyString());
    }

    // ---------------------------------------------------------------------
    // getDhcpServiceProvider
    // ---------------------------------------------------------------------

    @Test
    public void getDhcpServiceProviderReturnsCastDhcpProvider() {
        Mockito.when(networkServiceMapDao.getProviderForServiceInNetwork(NETWORK_ID, Service.Dhcp))
                .thenReturn("VirtualRouter");
        DhcpServiceProvider dhcp = Mockito.mock(DhcpServiceProvider.class,
                Mockito.withSettings().extraInterfaces(NetworkElement.class));
        Mockito.when(networkModel.getElementImplementingProvider("VirtualRouter"))
                .thenReturn((NetworkElement) dhcp);

        DhcpServiceProvider result = service.getDhcpServiceProvider(network);

        assertSame(dhcp, result);
    }

    @Test
    public void getDhcpServiceProviderReturnsNullWhenNoProvider() {
        Mockito.when(networkServiceMapDao.getProviderForServiceInNetwork(NETWORK_ID, Service.Dhcp))
                .thenReturn(null);

        assertNull(service.getDhcpServiceProvider(network));
    }

    @Test
    public void getDhcpServiceProviderReturnsNullWhenElementIsNotDhcpProvider() {
        // Configured provider exists but its element is not a DhcpServiceProvider --
        // the contract is to return null rather than ClassCastException.
        Mockito.when(networkServiceMapDao.getProviderForServiceInNetwork(NETWORK_ID, Service.Dhcp))
                .thenReturn("VirtualRouter");
        NetworkElement nonDhcp = Mockito.mock(NetworkElement.class);
        Mockito.when(networkModel.getElementImplementingProvider("VirtualRouter")).thenReturn(nonDhcp);

        assertNull(service.getDhcpServiceProvider(network));
    }

    // ---------------------------------------------------------------------
    // getDnsServiceProvider
    // ---------------------------------------------------------------------

    @Test
    public void getDnsServiceProviderReturnsCastDnsProvider() {
        Mockito.when(networkServiceMapDao.getProviderForServiceInNetwork(NETWORK_ID, Service.Dns))
                .thenReturn("VirtualRouter");
        DnsServiceProvider dns = Mockito.mock(DnsServiceProvider.class,
                Mockito.withSettings().extraInterfaces(NetworkElement.class));
        Mockito.when(networkModel.getElementImplementingProvider("VirtualRouter"))
                .thenReturn((NetworkElement) dns);

        DnsServiceProvider result = service.getDnsServiceProvider(network);

        assertSame(dns, result);
    }

    @Test
    public void getDnsServiceProviderReturnsNullWhenNoProvider() {
        Mockito.when(networkServiceMapDao.getProviderForServiceInNetwork(NETWORK_ID, Service.Dns))
                .thenReturn(null);

        assertNull(service.getDnsServiceProvider(network));
        Mockito.verify(networkModel, Mockito.never())
                .getElementImplementingProvider(ArgumentMatchers.anyString());
    }
}
