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
package com.cloud.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.response.ProviderResponse;
import org.apache.cloudstack.api.response.RemoteAccessVpnResponse;
import org.apache.cloudstack.api.response.ServiceResponse;
import org.apache.cloudstack.api.response.Site2SiteVpnConnectionResponse;
import org.apache.cloudstack.api.response.Site2SiteVpnGatewayResponse;
import org.apache.cloudstack.api.response.StaticRouteResponse;
import org.apache.cloudstack.api.response.VpcOfferingResponse;
import org.apache.cloudstack.api.response.VpnUsersResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.api.query.vo.VpcOfferingJoinVO;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.RemoteAccessVpn;
import com.cloud.network.Site2SiteVpnConnection;
import com.cloud.network.Site2SiteVpnGateway;
import com.cloud.network.VpnUser;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.vpc.StaticRoute;
import com.cloud.network.vpc.VpcGateway;
import com.cloud.network.vpc.VpcOffering;
import com.cloud.network.vpc.VpcVO;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.net.Ip;

@RunWith(MockitoJUnitRunner.class)
public class ApiVpcVpnResponseServiceImplTest {

    private final ApiVpcVpnResponseServiceImpl service = new ApiVpcVpnResponseServiceImpl();

    @Mock
    private ApiResponseOwnerService apiResponseOwnerService;
    @Mock
    private EntityManager entityManager;
    @Mock
    private VpnUser vpnUser;
    @Mock
    private StaticRoute staticRoute;
    @Mock
    private VpcVO vpc;
    @Mock
    private VpcGateway vpcGateway;

    @Before
    public void setup() {
        ReflectionTestUtils.setField(service, "apiResponseOwnerService", apiResponseOwnerService);
        ReflectionTestUtils.setField(service, "_entityMgr", entityManager);
    }

    @Test
    public void createVpnUserResponsePopulatesFieldsAndDelegatesOwnerPopulation() {
        when(vpnUser.getUuid()).thenReturn("vpn-user-uuid");
        when(vpnUser.getUsername()).thenReturn("alice");
        when(vpnUser.getState()).thenReturn(VpnUser.State.Active);

        VpnUsersResponse response = service.createVpnUserResponse(vpnUser);

        assertEquals("vpn-user-uuid", ReflectionTestUtils.getField(response, "id"));
        assertEquals("alice", ReflectionTestUtils.getField(response, "userName"));
        assertEquals("Active", response.getState());
        assertEquals("vpnuser", response.getObjectName());
        verify(apiResponseOwnerService).populateOwner(response, vpnUser);
    }

    @Test
    public void createStaticRouteResponseMapsRevokeToDeletingAndDelegatesOwnerPopulation() {
        when(staticRoute.getUuid()).thenReturn("route-uuid");
        when(staticRoute.getId()).thenReturn(10L);
        when(staticRoute.getVpcId()).thenReturn(20L);
        when(staticRoute.getVpcGatewayId()).thenReturn(30L);
        when(staticRoute.getNextHop()).thenReturn("192.0.2.1");
        when(staticRoute.getCidr()).thenReturn("198.51.100.0/24");
        when(staticRoute.getState()).thenReturn(StaticRoute.State.Revoke);
        when(staticRoute.getAccountId()).thenReturn(40L);
        when(staticRoute.getDomainId()).thenReturn(50L);
        when(vpc.getUuid()).thenReturn("vpc-uuid");
        when(vpcGateway.getUuid()).thenReturn("gateway-uuid");
        when(vpcGateway.getIp4Address()).thenReturn("10.0.0.1");
        when(entityManager.findById(VpcGateway.class, 30L)).thenReturn(vpcGateway);

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findVpcById(20L)).thenReturn(vpc);
            apiDBUtils.when(() -> ApiDBUtils.listByResourceTypeAndId(ResourceObjectType.StaticRoute, 10L)).thenReturn(Collections.emptyList());

            StaticRouteResponse response = service.createStaticRouteResponse(staticRoute);

            assertEquals("route-uuid", ReflectionTestUtils.getField(response, "id"));
            assertEquals("vpc-uuid", ReflectionTestUtils.getField(response, "vpcId"));
            assertEquals("gateway-uuid", ReflectionTestUtils.getField(response, "vpcGatewayId"));
            assertEquals("10.0.0.1", ReflectionTestUtils.getField(response, "vpcGatewayIp"));
            assertEquals("192.0.2.1", ReflectionTestUtils.getField(response, "nextHop"));
            assertEquals("198.51.100.0/24", ReflectionTestUtils.getField(response, "cidr"));
            assertEquals("Deleting", ReflectionTestUtils.getField(response, "state"));
            assertEquals(Collections.emptyList(), ReflectionTestUtils.getField(response, "tags"));
            assertEquals("staticroute", response.getObjectName());
            verify(apiResponseOwnerService).populateAccount(response, 40L);
            verify(apiResponseOwnerService).populateDomain(response, 50L);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    public void createVpcOfferingResponseSkipsGatewayServiceAndCopiesProviders() {
        VpcOffering offering = Mockito.mock(VpcOffering.class);
        VpcOfferingJoinVO offeringView = Mockito.mock(VpcOfferingJoinVO.class);
        VpcOfferingResponse expectedResponse = new VpcOfferingResponse();
        Map<Service, java.util.Set<Provider>> services = new LinkedHashMap<>();
        services.put(Service.Gateway, new LinkedHashSet<>(List.of(Provider.VPCVirtualRouter)));
        services.put(Service.SourceNat, new LinkedHashSet<>(List.of(Provider.VPCVirtualRouter)));

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.newVpcOfferingView(offering)).thenReturn(offeringView);
            apiDBUtils.when(() -> ApiDBUtils.newVpcOfferingResponse(offeringView)).thenReturn(expectedResponse);
            apiDBUtils.when(() -> ApiDBUtils.listVpcOffServices(offeringView.getId())).thenReturn(services);

            VpcOfferingResponse response = service.createVpcOfferingResponse(offering);

            assertEquals(expectedResponse, response);
            List<ServiceResponse> serviceResponses = (List<ServiceResponse>) ReflectionTestUtils.getField(response, "services");
            assertEquals(1, serviceResponses.size());
            assertEquals(Service.SourceNat.getName(), ReflectionTestUtils.getField(serviceResponses.get(0), "name"));
            List<ProviderResponse> providers = (List<ProviderResponse>) ReflectionTestUtils.getField(serviceResponses.get(0), "providers");
            assertEquals(1, providers.size());
            assertEquals(Provider.VPCVirtualRouter.getName(), ReflectionTestUtils.getField(providers.get(0), "name"));
        }
    }

    @Test
    public void createVpnUserResponseSetsStateToAddedWhenActive() {
        when(vpnUser.getUuid()).thenReturn("user-uuid-2");
        when(vpnUser.getUsername()).thenReturn("bob");
        when(vpnUser.getState()).thenReturn(VpnUser.State.Add);

        VpnUsersResponse response = service.createVpnUserResponse(vpnUser);

        assertEquals("Add", response.getState());
        assertEquals("bob", ReflectionTestUtils.getField(response, "userName"));
    }

    @Test
    public void createStaticRouteResponsePreservesActiveState() {
        when(staticRoute.getUuid()).thenReturn("route-uuid-2");
        when(staticRoute.getId()).thenReturn(11L);
        when(staticRoute.getVpcId()).thenReturn(null);
        when(staticRoute.getVpcGatewayId()).thenReturn(null);
        when(staticRoute.getNextHop()).thenReturn(null);
        when(staticRoute.getCidr()).thenReturn("198.51.100.0/24");
        when(staticRoute.getState()).thenReturn(StaticRoute.State.Active);
        when(staticRoute.getAccountId()).thenReturn(40L);
        when(staticRoute.getDomainId()).thenReturn(50L);

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.listByResourceTypeAndId(ResourceObjectType.StaticRoute, 11L)).thenReturn(Collections.emptyList());

            StaticRouteResponse response = service.createStaticRouteResponse(staticRoute);

            assertEquals("Active", ReflectionTestUtils.getField(response, "state"));
            assertNull(ReflectionTestUtils.getField(response, "vpcId"));
        }
    }

    @Test
    public void createSite2SiteVpnGatewayResponsePopulatesBasicFields() {
        Site2SiteVpnGateway vpnGateway = Mockito.mock(Site2SiteVpnGateway.class);
        IPAddressVO ipAddress = Mockito.mock(IPAddressVO.class);
        when(vpnGateway.getUuid()).thenReturn("vpn-gw-uuid");
        when(vpnGateway.getAddrId()).thenReturn(100L);
        when(vpnGateway.getVpcId()).thenReturn(200L);
        when(vpnGateway.getAccountId()).thenReturn(300L);
        when(vpnGateway.getDomainId()).thenReturn(400L);
        when(ipAddress.getAddress()).thenReturn(new Ip("203.0.113.1"));
        when(vpc.getUuid()).thenReturn("vpc-uuid");
        when(vpc.getName()).thenReturn("my-vpc");

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findIpAddressById(100L)).thenReturn(ipAddress);
            apiDBUtils.when(() -> ApiDBUtils.findVpcById(200L)).thenReturn(vpc);

            Site2SiteVpnGatewayResponse response = service.createSite2SiteVpnGatewayResponse(vpnGateway);

            assertEquals("vpn-gw-uuid", ReflectionTestUtils.getField(response, "id"));
            assertEquals("203.0.113.1", ReflectionTestUtils.getField(response, "ip"));
            assertEquals("vpc-uuid", ReflectionTestUtils.getField(response, "vpcId"));
            assertEquals("my-vpc", ReflectionTestUtils.getField(response, "vpcName"));
            assertEquals("vpngateway", response.getObjectName());
        }
    }

    @Test
    public void createStaticRouteResponseWithNextHopAndGatewayNullHandledGracefully() {
        when(staticRoute.getUuid()).thenReturn("route-uuid-3");
        when(staticRoute.getId()).thenReturn(12L);
        when(staticRoute.getVpcId()).thenReturn(20L);
        when(staticRoute.getVpcGatewayId()).thenReturn(null);
        when(staticRoute.getNextHop()).thenReturn("192.0.2.254");
        when(staticRoute.getCidr()).thenReturn("10.0.0.0/8");
        when(staticRoute.getState()).thenReturn(StaticRoute.State.Add);
        when(staticRoute.getAccountId()).thenReturn(1L);
        when(staticRoute.getDomainId()).thenReturn(1L);
        when(vpc.getUuid()).thenReturn("vpc-uuid-b");

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findVpcById(20L)).thenReturn(vpc);
            apiDBUtils.when(() -> ApiDBUtils.listByResourceTypeAndId(ResourceObjectType.StaticRoute, 12L)).thenReturn(Collections.emptyList());

            StaticRouteResponse response = service.createStaticRouteResponse(staticRoute);

            assertEquals("route-uuid-3", ReflectionTestUtils.getField(response, "id"));
            assertEquals("vpc-uuid-b", ReflectionTestUtils.getField(response, "vpcId"));
            assertEquals("192.0.2.254", ReflectionTestUtils.getField(response, "nextHop"));
            assertNull(ReflectionTestUtils.getField(response, "vpcGatewayId"));
            assertEquals("Add", ReflectionTestUtils.getField(response, "state"));
        }
    }

    @Test
    public void createSite2SiteVpnConnectionResponsePopulatesStateAndTimestamps() {
        Site2SiteVpnConnection connection = Mockito.mock(Site2SiteVpnConnection.class);
        when(connection.getUuid()).thenReturn("conn-uuid");
        when(connection.isPassive()).thenReturn(false);
        // getVpnGatewayId() and getCustomerGatewayId() return primitive long — let mock return 0L (default)
        when(connection.getAccountId()).thenReturn(1L);
        when(connection.getDomainId()).thenReturn(1L);
        when(connection.getState()).thenReturn(Site2SiteVpnConnection.State.Disconnected);
        when(connection.isDisplay()).thenReturn(true);

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            // vpnGatewayId=0 and customerGatewayId=0 — both lookups return null so no nested field population
            apiDBUtils.when(() -> ApiDBUtils.findVpnGatewayById(0L)).thenReturn(null);
            apiDBUtils.when(() -> ApiDBUtils.findCustomerGatewayById(0L)).thenReturn(null);

            Site2SiteVpnConnectionResponse response = service.createSite2SiteVpnConnectionResponse(connection);

            assertEquals("conn-uuid", ReflectionTestUtils.getField(response, "id"));
            assertEquals("Disconnected", ReflectionTestUtils.getField(response, "state"));
            assertEquals("vpnconnection", response.getObjectName());
        }
    }

    @Test
    public void createRemoteAccessVpnResponseSetsIpRangeAndPresharedKey() {
        RemoteAccessVpn vpn = Mockito.mock(RemoteAccessVpn.class);
        IPAddressVO ipAddress = Mockito.mock(IPAddressVO.class);
        when(vpn.getUuid()).thenReturn("ravpn-uuid");
        when(vpn.getServerAddressId()).thenReturn(500L);
        when(vpn.getIpRange()).thenReturn("10.0.0.1-10.0.0.10");
        when(vpn.getIpsecPresharedKey()).thenReturn("secret");
        when(vpn.getState()).thenReturn(RemoteAccessVpn.State.Running);
        when(vpn.isDisplay()).thenReturn(true);
        when(ipAddress.getUuid()).thenReturn("ip-uuid");
        when(ipAddress.getAddress()).thenReturn(new Ip("203.0.113.20"));

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findIpAddressById(500L)).thenReturn(ipAddress);

            RemoteAccessVpnResponse response = service.createRemoteAccessVpnResponse(vpn);

            assertEquals("ravpn-uuid", ReflectionTestUtils.getField(response, "id"));
            assertEquals("ip-uuid", ReflectionTestUtils.getField(response, "publicIpId"));
            assertEquals("203.0.113.20", ReflectionTestUtils.getField(response, "publicIp"));
            assertEquals("10.0.0.1-10.0.0.10", ReflectionTestUtils.getField(response, "ipRange"));
            assertEquals("secret", ReflectionTestUtils.getField(response, "presharedKey"));
            assertEquals("Running", ReflectionTestUtils.getField(response, "state"));
            assertEquals("remoteaccessvpn", response.getObjectName());
        }
    }

    @Test
    public void createVpcOfferingResponseHandlesNullProviderInServiceMap() {
        VpcOffering offering = Mockito.mock(VpcOffering.class);
        VpcOfferingJoinVO offeringView = Mockito.mock(VpcOfferingJoinVO.class);
        VpcOfferingResponse expectedResponse = new VpcOfferingResponse();
        Map<Service, java.util.Set<Provider>> services = new LinkedHashMap<>();
        java.util.Set<Provider> providersWithNull = new LinkedHashSet<>();
        providersWithNull.add(null);
        providersWithNull.add(Provider.VPCVirtualRouter);
        services.put(Service.Firewall, providersWithNull);

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.newVpcOfferingView(offering)).thenReturn(offeringView);
            apiDBUtils.when(() -> ApiDBUtils.newVpcOfferingResponse(offeringView)).thenReturn(expectedResponse);
            apiDBUtils.when(() -> ApiDBUtils.listVpcOffServices(offeringView.getId())).thenReturn(services);

            VpcOfferingResponse response = service.createVpcOfferingResponse(offering);

            assertNotNull(response);
            @SuppressWarnings("unchecked")
            List<ServiceResponse> serviceResponses = (List<ServiceResponse>) ReflectionTestUtils.getField(response, "services");
            assertEquals(1, serviceResponses.size());
            // Only VPCVirtualRouter provider appears, null is skipped
            @SuppressWarnings("unchecked")
            List<ProviderResponse> providerResponses = (List<ProviderResponse>) ReflectionTestUtils.getField(serviceResponses.get(0), "providers");
            assertEquals(1, providerResponses.size());
        }
    }
}
