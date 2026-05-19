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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.UnsupportedServiceException;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingServiceMapVO;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;

@RunWith(MockitoJUnitRunner.class)
public class NetworkProviderMappingServiceImplTest {

    private static final long OFFERING_ID = 101L;
    private static final long NETWORK_ID = 202L;
    private static final long PHYSICAL_NETWORK_ID = 303L;

    @Mock
    private NetworkOfferingServiceMapDao networkOfferingServiceMapDao;

    @Mock
    private NetworkModel networkModel;

    @Mock
    private PhysicalNetworkServiceProviderDao physicalNetworkServiceProviderDao;

    @Mock
    private NetworkServiceMapDao networkServiceMapDao;

    @Mock
    private NetworkOffering offering;

    @InjectMocks
    private NetworkProviderMappingServiceImpl service;

    @Test
    public void finalizeServicesAndProvidersForNetworkReturnsExplicitProviderMap() {
        when(offering.getId()).thenReturn(OFFERING_ID);
        when(networkOfferingServiceMapDao.listByNetworkOfferingId(OFFERING_ID)).thenReturn(Collections.singletonList(mapping(Service.Dhcp, Provider.VirtualRouter)));

        Map<String, String> result = service.finalizeServicesAndProvidersForNetwork(offering, null);

        assertEquals(1, result.size());
        assertEquals(Provider.VirtualRouter.getName(), result.get(Service.Dhcp.getName()));
    }

    @Test
    public void finalizeServicesAndProvidersForNetworkUsesDefaultUniqueProviderWhenProviderIsNull() {
        when(offering.getId()).thenReturn(OFFERING_ID);
        when(networkOfferingServiceMapDao.listByNetworkOfferingId(OFFERING_ID)).thenReturn(Collections.singletonList(mapping(Service.Dns, null)));
        when(networkModel.getDefaultUniqueProviderForService(Service.Dns.getName())).thenReturn(Provider.VirtualRouter);

        Map<String, String> result = service.finalizeServicesAndProvidersForNetwork(offering, null);

        assertEquals(Provider.VirtualRouter.getName(), result.get(Service.Dns.getName()));
        verify(networkModel).getDefaultUniqueProviderForService(Service.Dns.getName());
    }

    @Test
    public void finalizeServicesAndProvidersForNetworkKeepsFirstProviderForDuplicateService() {
        when(offering.getId()).thenReturn(OFFERING_ID);
        when(networkOfferingServiceMapDao.listByNetworkOfferingId(OFFERING_ID)).thenReturn(Arrays.asList(
                mapping(Service.Lb, Provider.VirtualRouter),
                mapping(Service.Lb, Provider.Netscaler)));

        Map<String, String> result = service.finalizeServicesAndProvidersForNetwork(offering, null);

        assertEquals(1, result.size());
        assertEquals(Provider.VirtualRouter.getName(), result.get(Service.Lb.getName()));
    }

    @Test
    public void finalizeServicesAndProvidersForNetworkSkipsDefaultLookupForDuplicateServiceAfterFirstProvider() {
        when(offering.getId()).thenReturn(OFFERING_ID);
        when(networkOfferingServiceMapDao.listByNetworkOfferingId(OFFERING_ID)).thenReturn(Arrays.asList(
                mapping(Service.UserData, Provider.VirtualRouter),
                mapping(Service.UserData, null)));

        service.finalizeServicesAndProvidersForNetwork(offering, null);

        verify(networkModel, never()).getDefaultUniqueProviderForService(anyString());
    }

    @Test
    public void finalizeServicesAndProvidersForNetworkChecksPhysicalNetworkProviderSupportWhenPhysicalNetworkIsPresent() {
        when(offering.getId()).thenReturn(OFFERING_ID);
        when(networkOfferingServiceMapDao.listByNetworkOfferingId(OFFERING_ID)).thenReturn(Collections.singletonList(mapping(Service.StaticNat, Provider.JuniperSRX)));
        when(physicalNetworkServiceProviderDao.isServiceProviderEnabled(PHYSICAL_NETWORK_ID, Provider.JuniperSRX.getName(), Service.StaticNat.getName())).thenReturn(true);

        Map<String, String> result = service.finalizeServicesAndProvidersForNetwork(offering, PHYSICAL_NETWORK_ID);

        assertEquals(Provider.JuniperSRX.getName(), result.get(Service.StaticNat.getName()));
        verify(physicalNetworkServiceProviderDao).isServiceProviderEnabled(PHYSICAL_NETWORK_ID, Provider.JuniperSRX.getName(), Service.StaticNat.getName());
    }

    @Test
    public void finalizeServicesAndProvidersForNetworkChecksPhysicalNetworkProviderSupportAfterDefaultProviderResolution() {
        when(offering.getId()).thenReturn(OFFERING_ID);
        when(networkOfferingServiceMapDao.listByNetworkOfferingId(OFFERING_ID)).thenReturn(Collections.singletonList(mapping(Service.Dhcp, null)));
        when(networkModel.getDefaultUniqueProviderForService(Service.Dhcp.getName())).thenReturn(Provider.VirtualRouter);
        when(physicalNetworkServiceProviderDao.isServiceProviderEnabled(PHYSICAL_NETWORK_ID, Provider.VirtualRouter.getName(), Service.Dhcp.getName())).thenReturn(true);

        Map<String, String> result = service.finalizeServicesAndProvidersForNetwork(offering, PHYSICAL_NETWORK_ID);

        assertEquals(Provider.VirtualRouter.getName(), result.get(Service.Dhcp.getName()));
        verify(physicalNetworkServiceProviderDao).isServiceProviderEnabled(PHYSICAL_NETWORK_ID, Provider.VirtualRouter.getName(), Service.Dhcp.getName());
    }

    @Test
    public void finalizeServicesAndProvidersForNetworkThrowsWhenProviderUnsupportedOnPhysicalNetwork() {
        when(offering.getId()).thenReturn(OFFERING_ID);
        when(networkOfferingServiceMapDao.listByNetworkOfferingId(OFFERING_ID)).thenReturn(Collections.singletonList(mapping(Service.Firewall, Provider.PaloAlto)));
        when(physicalNetworkServiceProviderDao.isServiceProviderEnabled(PHYSICAL_NETWORK_ID, Provider.PaloAlto.getName(), Service.Firewall.getName())).thenReturn(false);

        UnsupportedServiceException exception = assertThrows(UnsupportedServiceException.class,
                () -> service.finalizeServicesAndProvidersForNetwork(offering, PHYSICAL_NETWORK_ID));

        assertTrue(exception.getMessage().contains(Provider.PaloAlto.getName()));
        assertTrue(exception.getMessage().contains(Service.Firewall.getName()));
    }

    @Test
    public void finalizeServicesAndProvidersForNetworkSkipsPhysicalNetworkCheckWhenPhysicalNetworkIsNull() {
        when(offering.getId()).thenReturn(OFFERING_ID);
        when(networkOfferingServiceMapDao.listByNetworkOfferingId(OFFERING_ID)).thenReturn(Collections.singletonList(mapping(Service.Dhcp, Provider.VirtualRouter)));

        service.finalizeServicesAndProvidersForNetwork(offering, null);

        verify(physicalNetworkServiceProviderDao, never()).isServiceProviderEnabled(anyLong(), anyString(), anyString());
    }

    @Test
    public void finalizeServicesAndProvidersForNetworkReturnsEmptyMapWhenOfferingHasNoServices() {
        when(offering.getId()).thenReturn(OFFERING_ID);
        when(networkOfferingServiceMapDao.listByNetworkOfferingId(OFFERING_ID)).thenReturn(Collections.emptyList());

        Map<String, String> result = service.finalizeServicesAndProvidersForNetwork(offering, null);

        assertTrue(result.isEmpty());
    }

    @Test
    public void getNetworkProvidersConvertsDistinctProviderNamesAndPreservesOrder() {
        when(networkServiceMapDao.getDistinctProviders(NETWORK_ID)).thenReturn(Arrays.asList(Provider.VirtualRouter.getName(), Provider.Netscaler.getName()));

        List<Provider> result = service.getNetworkProviders(NETWORK_ID);

        assertEquals(2, result.size());
        assertSame(Provider.VirtualRouter, result.get(0));
        assertSame(Provider.Netscaler, result.get(1));
    }

    @Test
    public void getNetworkProvidersReturnsEmptyListWhenDaoReturnsNoProviders() {
        when(networkServiceMapDao.getDistinctProviders(NETWORK_ID)).thenReturn(Collections.emptyList());

        List<Provider> result = service.getNetworkProviders(NETWORK_ID);

        assertTrue(result.isEmpty());
    }

    private NetworkOfferingServiceMapVO mapping(final Service service, final Provider provider) {
        return new NetworkOfferingServiceMapVO(OFFERING_ID, service, provider);
    }
}
