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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.response.ApplicationLoadBalancerInstanceResponse;
import org.apache.cloudstack.api.response.ApplicationLoadBalancerResponse;
import org.apache.cloudstack.api.response.ApplicationLoadBalancerRuleResponse;
import org.apache.cloudstack.api.response.FirewallResponse;
import org.apache.cloudstack.api.response.LBHealthCheckResponse;
import org.apache.cloudstack.api.response.LBStickinessResponse;
import org.apache.cloudstack.api.response.LoadBalancerResponse;
import org.apache.cloudstack.api.response.ResourceTagResponse;
import org.apache.cloudstack.network.lb.ApplicationLoadBalancerRule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.api.query.vo.ResourceTagJoinVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.domain.DomainVO;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.HealthCheckPolicy;
import com.cloud.network.rules.LoadBalancer;
import com.cloud.network.rules.LoadBalancerContainer;
import com.cloud.network.rules.StickinessPolicy;
import com.cloud.server.ResourceTag;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.net.Ip;

@RunWith(MockitoJUnitRunner.class)
public class ApiLoadBalancerFirewallResponseServiceImplTest {

    private final ApiLoadBalancerFirewallResponseService service = new ApiLoadBalancerFirewallResponseServiceImpl();

    @Mock
    private LoadBalancer loadBalancer;
    @Mock
    private ApplicationLoadBalancerRule applicationLoadBalancerRule;
    @Mock
    private FirewallRule firewallRule;
    @Mock
    private IPAddressVO publicIp;
    @Mock
    private NetworkVO network;
    @Mock
    private DataCenterVO zone;
    @Mock
    private Account account;
    @Mock
    private DomainVO domain;
    @Mock
    private ResourceTag resourceTag;
    @Mock
    private ResourceTagJoinVO resourceTagView;
    @Mock
    private StickinessPolicy stickinessPolicy;
    @Mock
    private HealthCheckPolicy healthCheckPolicy;
    @Mock
    private UserVm userVm;

    @Test
    public void createLoadBalancerResponseBuildsOwnerTagsAndNetworkFields() {
        when(loadBalancer.getId()).thenReturn(100L);
        when(loadBalancer.getUuid()).thenReturn("lb-uuid");
        when(loadBalancer.getName()).thenReturn("web-lb");
        when(loadBalancer.getDescription()).thenReturn("web traffic");
        when(loadBalancer.getSourceIpAddressId()).thenReturn(200L);
        when(loadBalancer.getSourcePortStart()).thenReturn(80);
        when(loadBalancer.getDefaultPortStart()).thenReturn(8080);
        when(loadBalancer.getAlgorithm()).thenReturn("roundrobin");
        when(loadBalancer.getLbProtocol()).thenReturn("tcp");
        when(loadBalancer.isDisplay()).thenReturn(true);
        when(loadBalancer.getState()).thenReturn(FirewallRule.State.Revoke);
        when(loadBalancer.getNetworkId()).thenReturn(300L);
        when(loadBalancer.getCidrList()).thenReturn("198.51.100.0/24");
        when(loadBalancer.getAccountId()).thenReturn(400L);
        when(loadBalancer.getDomainId()).thenReturn(500L);
        when(publicIp.getUuid()).thenReturn("ip-uuid");
        when(publicIp.getAddress()).thenReturn(new Ip("203.0.113.10"));
        when(publicIp.getDataCenterId()).thenReturn(600L);
        when(network.getUuid()).thenReturn("network-uuid");
        when(zone.getUuid()).thenReturn("zone-uuid");
        when(zone.getName()).thenReturn("zone-name");
        stubNormalAccountAndDomain();
        ResourceTagResponse resourceTagResponse = new ResourceTagResponse();

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findFirewallSourceCidrs(100L)).thenReturn(Arrays.asList("10.0.0.0/24", "10.0.1.0/24"));
            apiDBUtils.when(() -> ApiDBUtils.findIpAddressById(200L)).thenReturn(publicIp);
            apiDBUtils.when(() -> ApiDBUtils.findZoneById(600L)).thenReturn(zone);
            apiDBUtils.when(() -> ApiDBUtils.findNetworkById(300L)).thenReturn(network);
            apiDBUtils.when(() -> ApiDBUtils.findAccountById(400L)).thenReturn(account);
            apiDBUtils.when(() -> ApiDBUtils.findDomainById(500L)).thenReturn(domain);
            apiDBUtils.when(() -> ApiDBUtils.listByResourceTypeAndId(ResourceObjectType.LoadBalancer, 100L)).thenReturn(Collections.singletonList(resourceTag));
            apiDBUtils.when(() -> ApiDBUtils.newResourceTagView(resourceTag)).thenReturn(resourceTagView);
            apiDBUtils.when(() -> ApiDBUtils.newResourceTagResponse(resourceTagView, true)).thenReturn(resourceTagResponse);

            LoadBalancerResponse response = service.createLoadBalancerResponse(loadBalancer);

            assertEquals("lb-uuid", ReflectionTestUtils.getField(response, "id"));
            assertEquals("web-lb", ReflectionTestUtils.getField(response, "name"));
            assertEquals("ip-uuid", ReflectionTestUtils.getField(response, "publicIpId"));
            assertEquals("203.0.113.10", ReflectionTestUtils.getField(response, "publicIp"));
            assertEquals("80", ReflectionTestUtils.getField(response, "publicPort"));
            assertEquals("8080", ReflectionTestUtils.getField(response, "privatePort"));
            assertEquals("roundrobin", ReflectionTestUtils.getField(response, "algorithm"));
            assertEquals("tcp", ReflectionTestUtils.getField(response, "lbProtocol"));
            assertEquals("Deleting", ReflectionTestUtils.getField(response, "state"));
            assertEquals("account-name", ReflectionTestUtils.getField(response, "accountName"));
            assertEquals("domain-uuid", ReflectionTestUtils.getField(response, "domainId"));
            assertEquals("domain-name", ReflectionTestUtils.getField(response, "domainName"));
            assertEquals("ROOT/customer", ReflectionTestUtils.getField(response, "domainPath"));
            assertEquals("zone-uuid", ReflectionTestUtils.getField(response, "zoneId"));
            assertEquals("zone-name", ReflectionTestUtils.getField(response, "zoneName"));
            assertEquals("network-uuid", ReflectionTestUtils.getField(response, "networkId"));
            assertEquals("198.51.100.0/24", ReflectionTestUtils.getField(response, "cidrList"));
            assertSame(resourceTagResponse, getFirstResourceTag(response));
            assertEquals("loadbalancer", response.getObjectName());
        }
    }

    @Test
    public void createFirewallResponseBuildsIngressRuleWithDestCidrsAndTags() {
        when(firewallRule.getId()).thenReturn(101L);
        when(firewallRule.getUuid()).thenReturn("fw-uuid");
        when(firewallRule.getProtocol()).thenReturn("tcp");
        when(firewallRule.getSourcePortStart()).thenReturn(443);
        when(firewallRule.getSourcePortEnd()).thenReturn(444);
        when(firewallRule.getTrafficType()).thenReturn(FirewallRule.TrafficType.Ingress);
        when(firewallRule.getSourceIpAddressId()).thenReturn(201L);
        when(firewallRule.getNetworkId()).thenReturn(301L);
        when(firewallRule.getState()).thenReturn(FirewallRule.State.Revoke);
        when(firewallRule.getIcmpCode()).thenReturn(1);
        when(firewallRule.getIcmpType()).thenReturn(2);
        when(firewallRule.isDisplay()).thenReturn(false);
        when(publicIp.getUuid()).thenReturn("public-ip-uuid");
        when(publicIp.getAddress()).thenReturn(new Ip("203.0.113.11"));
        when(network.getUuid()).thenReturn("network-uuid");
        ResourceTagResponse resourceTagResponse = new ResourceTagResponse();

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findFirewallSourceCidrs(101L)).thenReturn(Collections.singletonList("0.0.0.0/0"));
            apiDBUtils.when(() -> ApiDBUtils.findFirewallDestCidrs(101L)).thenReturn(Collections.singletonList("10.0.0.0/24"));
            apiDBUtils.when(() -> ApiDBUtils.findIpAddressById(201L)).thenReturn(publicIp);
            apiDBUtils.when(() -> ApiDBUtils.findNetworkById(301L)).thenReturn(network);
            apiDBUtils.when(() -> ApiDBUtils.listByResourceTypeAndId(ResourceObjectType.FirewallRule, 101L)).thenReturn(Collections.singletonList(resourceTag));
            apiDBUtils.when(() -> ApiDBUtils.newResourceTagView(resourceTag)).thenReturn(resourceTagView);
            apiDBUtils.when(() -> ApiDBUtils.newResourceTagResponse(resourceTagView, true)).thenReturn(resourceTagResponse);

            FirewallResponse response = service.createFirewallResponse(firewallRule);

            assertEquals("fw-uuid", ReflectionTestUtils.getField(response, "id"));
            assertEquals("tcp", ReflectionTestUtils.getField(response, "protocol"));
            assertEquals(443, ReflectionTestUtils.getField(response, "startPort"));
            assertEquals(444, ReflectionTestUtils.getField(response, "endPort"));
            assertEquals("public-ip-uuid", ReflectionTestUtils.getField(response, "publicIpAddressId"));
            assertEquals("203.0.113.11", ReflectionTestUtils.getField(response, "publicIpAddress"));
            assertEquals("network-uuid", ReflectionTestUtils.getField(response, "networkId"));
            assertEquals("0.0.0.0/0", ReflectionTestUtils.getField(response, "cidrList"));
            assertEquals("10.0.0.0/24", ReflectionTestUtils.getField(response, "destCidr"));
            assertEquals("Deleting", ReflectionTestUtils.getField(response, "state"));
            assertEquals("Ingress", ReflectionTestUtils.getField(response, "trafficType"));
            assertSame(resourceTagResponse, getFirstResourceTag(response));
            assertEquals("firewallrule", response.getObjectName());
        }
    }

    @Test
    public void createLoadBalancerPolicyResponsesPopulateAccountDomainAndRules() {
        when(loadBalancer.getUuid()).thenReturn("lb-uuid");
        when(loadBalancer.getAccountId()).thenReturn(400L);
        stubNormalAccountAndDomain();
        when(stickinessPolicy.getUuid()).thenReturn("stickiness-uuid");
        when(stickinessPolicy.getName()).thenReturn("cookie");
        when(stickinessPolicy.getMethodName()).thenReturn("LbCookie");
        when(stickinessPolicy.getDescription()).thenReturn("stick on cookie");
        when(stickinessPolicy.getParams()).thenReturn(Collections.singletonList(new Pair<>("name", "route")));
        when(stickinessPolicy.isDisplay()).thenReturn(true);
        when(healthCheckPolicy.getUuid()).thenReturn("health-uuid");
        when(healthCheckPolicy.getpingpath()).thenReturn("/health");
        when(healthCheckPolicy.getDescription()).thenReturn("health check");
        when(healthCheckPolicy.getHealthcheckInterval()).thenReturn(5);
        when(healthCheckPolicy.getResponseTime()).thenReturn(2);
        when(healthCheckPolicy.getHealthcheckThresshold()).thenReturn(3);
        when(healthCheckPolicy.getUnhealthThresshold()).thenReturn(4);
        when(healthCheckPolicy.isDisplay()).thenReturn(true);

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findAccountById(400L)).thenReturn(account);
            apiDBUtils.when(() -> ApiDBUtils.findDomainById(500L)).thenReturn(domain);

            LBStickinessResponse stickinessResponse = service.createLBStickinessPolicyResponse(Collections.singletonList(stickinessPolicy), loadBalancer);
            LBHealthCheckResponse healthCheckResponse = service.createLBHealthCheckPolicyResponse(Collections.singletonList(healthCheckPolicy), loadBalancer);
            LBStickinessResponse emptyStickinessResponse = service.createLBStickinessPolicyResponse(Collections.singletonList(stickinessPolicy), null);

            assertEquals("lb-uuid", ReflectionTestUtils.getField(stickinessResponse, "lbRuleId"));
            assertEquals("account-name", stickinessResponse.getAccountName());
            assertEquals("domain-name", stickinessResponse.getDomainName());
            assertEquals(1, stickinessResponse.getStickinessPolicies().size());
            assertEquals("cookie", stickinessResponse.getStickinessPolicies().get(0).getName());
            assertEquals("stickinesspolicies", stickinessResponse.getObjectName());
            assertEquals("lb-uuid", ReflectionTestUtils.getField(healthCheckResponse, "lbRuleId"));
            assertEquals("account-name", healthCheckResponse.getAccountName());
            assertEquals("domain-name", healthCheckResponse.getDomainName());
            assertEquals(1, healthCheckResponse.getHealthCheckPolicies().size());
            assertEquals("/health", healthCheckResponse.getHealthCheckPolicies().get(0).getpingpath());
            assertEquals("healthcheckpolicies", healthCheckResponse.getObjectName());
            assertNull(ReflectionTestUtils.getField(emptyStickinessResponse, "lbRuleId"));
        }
    }

    @Test
    public void createLoadBalancerContainerReponseBuildsInternalRuleInstancesAndTags() {
        when(applicationLoadBalancerRule.getId()).thenReturn(102L);
        when(applicationLoadBalancerRule.getUuid()).thenReturn("app-lb-uuid");
        when(applicationLoadBalancerRule.getName()).thenReturn("internal-lb");
        when(applicationLoadBalancerRule.getDescription()).thenReturn("internal traffic");
        when(applicationLoadBalancerRule.getAlgorithm()).thenReturn("leastconn");
        when(applicationLoadBalancerRule.isDisplay()).thenReturn(true);
        when(applicationLoadBalancerRule.getNetworkId()).thenReturn(302L);
        when(applicationLoadBalancerRule.getAccountId()).thenReturn(400L);
        when(applicationLoadBalancerRule.getDomainId()).thenReturn(500L);
        when(applicationLoadBalancerRule.getScheme()).thenReturn(LoadBalancerContainer.Scheme.Internal);
        when(applicationLoadBalancerRule.getSourceIp()).thenReturn(new Ip("10.1.1.10"));
        when(applicationLoadBalancerRule.getDefaultPortStart()).thenReturn(8080);
        when(applicationLoadBalancerRule.getSourcePortStart()).thenReturn(80);
        when(applicationLoadBalancerRule.getState()).thenReturn(FirewallRule.State.Revoke);
        when(network.getUuid()).thenReturn("network-uuid");
        when(userVm.getUuid()).thenReturn("vm-uuid");
        when(userVm.getInstanceName()).thenReturn("i-2-3-VM");
        stubNormalAccountAndDomain();
        ResourceTagResponse resourceTagResponse = new ResourceTagResponse();
        Map<Ip, UserVm> instances = Collections.singletonMap(new Ip("10.1.1.20"), userVm);

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findNetworkById(302L)).thenReturn(network);
            apiDBUtils.when(() -> ApiDBUtils.findAccountById(400L)).thenReturn(account);
            apiDBUtils.when(() -> ApiDBUtils.findDomainById(500L)).thenReturn(domain);
            apiDBUtils.when(() -> ApiDBUtils.listByResourceTypeAndId(ResourceObjectType.LoadBalancer, 102L)).thenReturn(Collections.singletonList(resourceTag));
            apiDBUtils.when(() -> ApiDBUtils.newResourceTagView(resourceTag)).thenReturn(resourceTagView);
            apiDBUtils.when(() -> ApiDBUtils.newResourceTagResponse(resourceTagView, true)).thenReturn(resourceTagResponse);

            ApplicationLoadBalancerResponse response = service.createLoadBalancerContainerReponse(applicationLoadBalancerRule, instances);

            assertEquals("app-lb-uuid", ReflectionTestUtils.getField(response, "id"));
            assertEquals("internal-lb", ReflectionTestUtils.getField(response, "name"));
            assertEquals("leastconn", ReflectionTestUtils.getField(response, "algorithm"));
            assertEquals("network-uuid", ReflectionTestUtils.getField(response, "networkId"));
            assertEquals("10.1.1.10", ReflectionTestUtils.getField(response, "sourceIp"));
            assertEquals("network-uuid", ReflectionTestUtils.getField(response, "sourceIpNetworkId"));
            assertEquals("account-name", ReflectionTestUtils.getField(response, "accountName"));
            assertEquals("domain-uuid", ReflectionTestUtils.getField(response, "domainId"));
            assertEquals("loadbalancer", response.getObjectName());
            assertSame(resourceTagResponse, getFirstResourceTag(response));

            List<ApplicationLoadBalancerRuleResponse> ruleResponses =
                    (List<ApplicationLoadBalancerRuleResponse>) ReflectionTestUtils.getField(response, "lbRules");
            assertEquals(1, ruleResponses.size());
            assertEquals(8080, ReflectionTestUtils.getField(ruleResponses.get(0), "instancePort"));
            assertEquals(80, ReflectionTestUtils.getField(ruleResponses.get(0), "sourcePort"));
            assertEquals("Deleting", ReflectionTestUtils.getField(ruleResponses.get(0), "state"));

            List<ApplicationLoadBalancerInstanceResponse> instanceResponses =
                    (List<ApplicationLoadBalancerInstanceResponse>) ReflectionTestUtils.getField(response, "lbInstances");
            assertEquals(1, instanceResponses.size());
            assertEquals("10.1.1.20", ReflectionTestUtils.getField(instanceResponses.get(0), "ipAddress"));
            assertEquals("vm-uuid", ReflectionTestUtils.getField(instanceResponses.get(0), "id"));
            assertEquals("i-2-3-VM", ReflectionTestUtils.getField(instanceResponses.get(0), "name"));
        }
    }

    private void stubNormalAccountAndDomain() {
        when(account.getType()).thenReturn(Account.Type.NORMAL);
        when(account.getAccountName()).thenReturn("account-name");
        when(account.getDomainId()).thenReturn(500L);
        when(domain.getUuid()).thenReturn("domain-uuid");
        when(domain.getName()).thenReturn("domain-name");
        when(domain.getPath()).thenReturn("/customer/");
    }

    private ResourceTagResponse getFirstResourceTag(Object response) {
        List<ResourceTagResponse> tags = (List<ResourceTagResponse>) ReflectionTestUtils.getField(response, "tags");
        assertEquals(1, tags.size());
        return tags.get(0);
    }
}
