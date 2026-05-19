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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.response.ProviderResponse;
import org.apache.cloudstack.api.response.ServiceResponse;
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
import com.cloud.network.VpnUser;
import com.cloud.network.vpc.StaticRoute;
import com.cloud.network.vpc.VpcGateway;
import com.cloud.network.vpc.VpcOffering;
import com.cloud.network.vpc.VpcVO;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.utils.db.EntityManager;

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
}
