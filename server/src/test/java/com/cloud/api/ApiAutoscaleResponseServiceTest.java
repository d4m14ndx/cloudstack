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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.response.AutoScalePolicyResponse;
import org.apache.cloudstack.api.response.AutoScaleVmGroupResponse;
import org.apache.cloudstack.api.response.AutoScaleVmProfileResponse;
import org.apache.cloudstack.api.response.ConditionResponse;
import org.apache.cloudstack.api.response.CounterResponse;
import org.apache.cloudstack.context.CallContext;

import com.cloud.domain.DomainVO;
import com.cloud.network.Network;
import com.cloud.network.as.AutoScalePolicy;
import com.cloud.network.as.AutoScalePolicyVO;
import com.cloud.network.as.AutoScaleVmGroup;
import com.cloud.network.as.AutoScaleVmGroupVO;
import com.cloud.network.as.AutoScaleVmProfileVO;
import com.cloud.network.as.Condition;
import com.cloud.network.as.ConditionVO;
import com.cloud.network.as.Counter;
import com.cloud.network.as.CounterVO;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserData;
import com.cloud.user.UserDataVO;
import com.cloud.user.UserVO;
import com.cloud.user.dao.UserDataDao;
import com.cloud.utils.net.Ip;
import com.cloud.storage.VMTemplateVO;

@RunWith(MockitoJUnitRunner.class)
public class ApiAutoscaleResponseServiceTest {

    @Mock
    private AccountManager accountManager;
    @Mock
    private AnnotationDao annotationDao;
    @Mock
    private NetworkServiceMapDao networkServiceMapDao;
    @Mock
    private UserDataDao userDataDao;

    private ApiAutoscaleResponseService service;

    @Before
    public void setUp() {
        service = new ApiAutoscaleResponseService();
        ReflectionTestUtils.setField(service, "_accountMgr", accountManager);
        ReflectionTestUtils.setField(service, "annotationDao", annotationDao);
        ReflectionTestUtils.setField(service, "ntwkSrvcDao", networkServiceMapDao);
        ReflectionTestUtils.setField(service, "userDataDao", userDataDao);

        AccountVO account = new AccountVO("testaccount", 1L, "networkdomain", Account.Type.NORMAL, "uuid");
        account.setId(1);
        UserVO user = new UserVO(1, "testuser", "password", "firstname", "lastName", "email", "timezone",
                UUID.randomUUID().toString(), User.Source.UNKNOWN);
        CallContext.register(user, account);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    @Test
    public void createCounterResponseCopiesCounterFields() {
        CounterVO counter = new CounterVO(Counter.Source.CPU, "cpu", "average", Network.Provider.VirtualRouter);

        CounterResponse response = service.createCounterResponse(counter);

        assertEquals(counter.getUuid(), field(response, "id"));
        assertEquals(Counter.Source.CPU.toString(), field(response, "source"));
        assertEquals("cpu", field(response, "name"));
        assertEquals("average", field(response, "value"));
        assertEquals(Network.Provider.VirtualRouter.getName(), field(response, "provider"));
    }

    @Test
    public void createConditionResponseIncludesCounterAndOwner() {
        CounterVO counter = new CounterVO(Counter.Source.CPU, "cpu", "average", Network.Provider.VirtualRouter);
        ConditionVO condition = new ConditionVO(1L, 75L, 3L, 2L, Condition.Operator.GT);

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.getCounter(1L)).thenReturn(counter);
            when(ApiDBUtils.findAccountById(anyLong())).thenReturn(new AccountVO("account", 2L, "networkdomain", Account.Type.NORMAL, "uuid"));
            when(ApiDBUtils.findDomainById(anyLong())).thenReturn(new DomainVO("domain", 1L, 1L, "networkdomain"));

            ConditionResponse response = service.createConditionResponse(condition);

            assertEquals(condition.getUuid(), field(response, "id"));
            assertEquals(counter.getUuid(), field(response, "counterId"));
            assertEquals(counter.getName(), field(response, "counterName"));
            assertEquals(Condition.Operator.GT.toString(), field(response, "relationalOperator"));
            assertEquals(Long.valueOf(75L), field(response, "threshold"));
            assertEquals("account", field(response, "accountName"));
            assertEquals("domain", field(response, "domain"));
            assertEquals(counter.getUuid(), field(field(response, "counterResponse"), "id"));
        }
    }

    @Test
    public void createAutoScaleVmProfileResponseIncludesUserDataPolicyAndDetails() {
        long zoneId = 1L;
        long domainId = 2L;
        long accountId = 3L;
        long serviceOfferingId = 4L;
        long templateId = 5L;
        long userDataId = 6L;
        long autoScaleUserId = 7L;
        AutoScaleVmProfileVO vmProfile = new AutoScaleVmProfileVO(zoneId, domainId, accountId, serviceOfferingId,
                templateId, null, null, "userdata", null, autoScaleUserId);
        vmProfile.setUserDataId(userDataId);
        vmProfile.setUserDataDetails("userdataDetails");

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findAccountById(accountId)).thenReturn(new AccountVO("account", domainId, "networkdomain", Account.Type.NORMAL, "uuid"));
            when(ApiDBUtils.findDomainById(domainId)).thenReturn(new DomainVO("domain", 1L, 1L, "networkdomain"));

            VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
            when(ApiDBUtils.findTemplateById(templateId)).thenReturn(template);
            when(template.getUserDataOverridePolicy()).thenReturn(UserData.UserDataOverridePolicy.APPEND);

            UserDataVO userData = Mockito.mock(UserDataVO.class);
            when(userDataDao.findById(userDataId)).thenReturn(userData);
            when(userData.getUuid()).thenReturn("userdata-uuid");
            when(userData.getName()).thenReturn("userdata-name");

            AutoScaleVmProfileResponse response = service.createAutoScaleVmProfileResponse(vmProfile);

            assertEquals(UserData.UserDataOverridePolicy.APPEND.toString(), response.getUserDataPolicy());
            assertEquals("userdata", response.getUserData());
            assertEquals("userdata-uuid", response.getUserDataId());
            assertEquals("userdata-name", response.getUserDataName());
            assertEquals("userdataDetails", response.getUserDataDetails());
            assertEquals("account", field(response, "accountName"));
        }
    }

    @Test
    public void createAutoScalePolicyResponseIncludesConditions() {
        AutoScalePolicyVO policy = new AutoScalePolicyVO("scaleup", 2L, 3L, 60, 120, null, AutoScalePolicy.Action.SCALEUP);
        CounterVO counter = new CounterVO(Counter.Source.CPU, "cpu", "average", Network.Provider.VirtualRouter);
        ConditionVO condition = new ConditionVO(1L, 80L, 3L, 2L, Condition.Operator.GT);

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.getAutoScalePolicyConditions(policy.getId())).thenReturn(List.of(condition));
            when(ApiDBUtils.getCounter(1L)).thenReturn(counter);
            when(ApiDBUtils.findAccountById(anyLong())).thenReturn(new AccountVO("account", 2L, "networkdomain", Account.Type.NORMAL, "uuid"));
            when(ApiDBUtils.findDomainById(anyLong())).thenReturn(new DomainVO("domain", 1L, 1L, "networkdomain"));

            AutoScalePolicyResponse response = service.createAutoScalePolicyResponse(policy);

            List<ConditionResponse> conditions = field(response, "conditions");
            assertEquals(policy.getUuid(), field(response, "id"));
            assertEquals("scaleup", field(response, "name"));
            assertEquals(Integer.valueOf(60), field(response, "duration"));
            assertEquals(Integer.valueOf(120), field(response, "quietTime"));
            assertEquals(AutoScalePolicy.Action.SCALEUP.toString(), field(response, "action"));
            assertEquals(1, conditions.size());
            assertEquals(condition.getUuid(), field(conditions.get(0), "id"));
            assertEquals("account", field(response, "accountName"));
        }
    }

    @Test
    public void createAutoScaleVmGroupResponseIncludesNetworkAndPolicyDetails() {
        AutoScaleVmGroupVO vmGroup = new AutoScaleVmGroupVO(1L, 2L, 3L, 4L, "test", 5, 6, 7, 8,
                new Date(), 9L, AutoScaleVmGroup.State.ENABLED);
        AutoScalePolicyVO scaleUpPolicy = new AutoScalePolicyVO("scaleup", 2L, 3L, 60, 120, null, AutoScalePolicy.Action.SCALEUP);
        AutoScalePolicyVO scaleDownPolicy = new AutoScalePolicyVO("scaledown", 2L, 3L, 60, 120, null, AutoScalePolicy.Action.SCALEDOWN);
        LoadBalancerVO lb = new LoadBalancerVO(null, null, null, 0L, 8080, 8081, null, 0L, 0L, 1L, null, null);
        NetworkVO network = new NetworkVO(1L, null, null, null, 2L, 1L, 2L, 3L,
                "testnetwork", "displaytext", "networkdomain", null, 1L, null, null, false, null, false);
        IPAddressVO ipAddress = new IPAddressVO(new Ip("10.10.10.10"), 1L, 1L, 1L, false);

        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findAutoScaleVmProfileById(anyLong())).thenReturn(null);
            when(ApiDBUtils.countAvailableVmsByGroupId(anyLong())).thenReturn(9);
            when(ApiDBUtils.findLoadBalancerById(anyLong())).thenReturn(lb);
            when(ApiDBUtils.findNetworkById(anyLong())).thenReturn(network);
            when(ApiDBUtils.findIpAddressById(anyLong())).thenReturn(ipAddress);
            when(ApiDBUtils.findAccountById(anyLong())).thenReturn(new AccountVO("account", 2L, "networkdomain", Account.Type.NORMAL, "uuid"));
            when(ApiDBUtils.findDomainById(anyLong())).thenReturn(new DomainVO("domain", 1L, 1L, "networkdomain"));
            apiDbUtils.when(() -> ApiDBUtils.getAutoScaleVmGroupPolicies(Mockito.eq(vmGroup.getId()), Mockito.anyList(), Mockito.anyList())).thenAnswer(invocation -> {
                invocation.<List<AutoScalePolicy>>getArgument(1).add(scaleUpPolicy);
                invocation.<List<AutoScalePolicy>>getArgument(2).add(scaleDownPolicy);
                return null;
            });
            when(ApiDBUtils.getAutoScalePolicyConditions(scaleUpPolicy.getId())).thenReturn(List.of());
            when(ApiDBUtils.getAutoScalePolicyConditions(scaleDownPolicy.getId())).thenReturn(List.of());
            when(networkServiceMapDao.getProviderForServiceInNetwork(network.getId(), Network.Service.Lb)).thenReturn("VirtualRouter");

            AutoScaleVmGroupResponse response = service.createAutoScaleVmGroupResponse(vmGroup);

            assertEquals("test", response.getName());
            assertEquals(5, response.getMinMembers());
            assertEquals(6, response.getMaxMembers());
            assertEquals(8, response.getInterval());
            assertEquals(9, response.getAvailableVirtualMachineCount());
            assertEquals(AutoScaleVmGroup.State.ENABLED.toString(), response.getState());
            assertEquals("testnetwork", response.getNetworkName());
            assertEquals("VirtualRouter", response.getLbProvider());
            assertEquals("10.10.10.10", response.getPublicIp());
            assertEquals("8080", response.getPublicPort());
            assertEquals("8081", response.getPrivatePort());
            List<AutoScalePolicyResponse> scaleUpPolicies = field(response, "scaleUpPolicies");
            List<AutoScalePolicyResponse> scaleDownPolicies = field(response, "scaleDownPolicies");
            assertEquals(1, scaleUpPolicies.size());
            assertEquals(1, scaleDownPolicies.size());
            assertEquals("scaleup", field(scaleUpPolicies.get(0), "name"));
            assertEquals("scaledown", field(scaleDownPolicies.get(0), "name"));
            assertEquals("account", field(response, "accountName"));
        }
    }

    @Test
    public void createAutoScaleVmGroupResponseUsesNoneProviderWhenNetworkHasNoProvider() {
        AutoScaleVmGroupVO vmGroup = new AutoScaleVmGroupVO(1L, 2L, 3L, 4L, "test", 5, 6, 7, 8,
                new Date(), 9L, AutoScaleVmGroup.State.ENABLED);
        LoadBalancerVO lb = new LoadBalancerVO(null, null, null, 0L, 8080, 8081, null, 0L, 0L, 1L, null, null);
        NetworkVO network = new NetworkVO(1L, null, null, null, 2L, 1L, 2L, 3L,
                "testnetwork", "displaytext", "networkdomain", null, 1L, null, null, false, null, false);

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.findAutoScaleVmProfileById(anyLong())).thenReturn(null);
            when(ApiDBUtils.findLoadBalancerById(anyLong())).thenReturn(lb);
            when(ApiDBUtils.findNetworkById(anyLong())).thenReturn(network);
            when(ApiDBUtils.findIpAddressById(anyLong())).thenReturn(null);
            when(ApiDBUtils.findAccountById(anyLong())).thenReturn(new AccountVO("account", 2L, "networkdomain", Account.Type.NORMAL, "uuid"));
            when(ApiDBUtils.findDomainById(anyLong())).thenReturn(new DomainVO("domain", 1L, 1L, "networkdomain"));
            when(networkServiceMapDao.getProviderForServiceInNetwork(network.getId(), Network.Service.Lb)).thenReturn(null);

            AutoScaleVmGroupResponse response = service.createAutoScaleVmGroupResponse(vmGroup);

            assertEquals(Network.Provider.None.toString(), response.getLbProvider());
            assertNull(response.getPublicIp());
        }
    }

    @SuppressWarnings("unchecked")
    private static <T> T field(Object target, String name) {
        return (T)ReflectionTestUtils.getField(target, name);
    }
}
