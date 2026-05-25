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
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.cloudstack.api.response.ConfigurationGroupResponse;
import org.apache.cloudstack.api.response.ConfigurationResponse;
import org.apache.cloudstack.api.response.ConfigurationSubGroupResponse;
import org.apache.cloudstack.api.response.ControlledEntityResponse;
import org.apache.cloudstack.api.response.ResourceCountResponse;
import org.apache.cloudstack.api.response.ResourceLimitResponse;
import org.apache.cloudstack.config.Configuration;
import org.apache.cloudstack.config.ConfigurationGroup;
import org.apache.cloudstack.framework.config.impl.ConfigurationSubGroupVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.Resource.ResourceOwnerType;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.configuration.ResourceCount;
import com.cloud.configuration.ResourceLimit;
import com.cloud.utils.Pair;

@RunWith(MockitoJUnitRunner.class)
public class ApiOfferingConfigurationResponseServiceTest {

    @Mock
    private ConfigurationManager configManager;
    @Mock
    private Configuration configuration;
    @Mock
    private ConfigurationGroup configurationGroup;
    @Mock
    private ResourceLimit resourceLimit;
    @Mock
    private ResourceCount resourceCount;

    private ApiOfferingConfigurationResponseService service;

    @Before
    public void setup() {
        service = new ApiOfferingConfigurationResponseService();
        ReflectionTestUtils.setField(service, "_configMgr", configManager);
    }

    @Test
    public void createResourceLimitResponseConvertsStorageBytesToGiBAndUsesProvidedOwnerPopulators() {
        when(resourceLimit.getResourceOwnerType()).thenReturn(ResourceOwnerType.Account);
        when(resourceLimit.getOwnerId()).thenReturn(7L);
        when(resourceLimit.getType()).thenReturn(ResourceType.primary_storage);
        when(resourceLimit.getMax()).thenReturn(ResourceType.bytesToGiB + 1L);
        when(resourceLimit.getTag()).thenReturn("fast-storage");

        ResourceLimitResponse response = service.createResourceLimitResponse(resourceLimit,
                (target, accountId) -> target.setAccountName("account-" + accountId),
                (target, domainId) -> target.setDomainName("domain-" + domainId),
                ownerId -> 11L);

        assertEquals("account-7", ReflectionTestUtils.getField(response, "accountName"));
        assertEquals("domain-11", ReflectionTestUtils.getField(response, "domainName"));
        assertEquals("10", ReflectionTestUtils.getField(response, "resourceType"));
        assertEquals("primary_storage", ReflectionTestUtils.getField(response, "resourceTypeName"));
        assertEquals(2L, ReflectionTestUtils.getField(response, "max"));
        assertEquals("fast-storage", ReflectionTestUtils.getField(response, "tag"));
        assertEquals("resourcelimit", response.getObjectName());
    }

    @Test
    public void createResourceCountResponseSetsTagOnlyWhenPresent() {
        when(resourceCount.getResourceOwnerType()).thenReturn(ResourceOwnerType.Domain);
        when(resourceCount.getOwnerId()).thenReturn(13L);
        when(resourceCount.getType()).thenReturn(ResourceType.user_vm);
        when(resourceCount.getCount()).thenReturn(42L);
        when(resourceCount.getTag()).thenReturn("");

        ResourceCountResponse response = service.createResourceCountResponse(resourceCount,
                this::populateAccountName,
                (target, domainId) -> target.setDomainName("domain-" + domainId),
                ownerId -> null);

        assertEquals("domain-13", ReflectionTestUtils.getField(response, "domainName"));
        assertEquals("0", ReflectionTestUtils.getField(response, "resourceType"));
        assertEquals("user_vm", ReflectionTestUtils.getField(response, "resourceTypeName"));
        assertEquals(42L, ReflectionTestUtils.getField(response, "resourceCount"));
        assertNull(ReflectionTestUtils.getField(response, "tag"));
        assertEquals("resourcecount", response.getObjectName());
    }

    @Test
    public void createConfigurationResponseMapsGroupsAndEncryptsConfiguredValues() {
        when(configuration.getCategory()).thenReturn("Advanced");
        when(configuration.getName()).thenReturn("secret.setting");
        when(configuration.getDescription()).thenReturn("description");
        when(configuration.getValue()).thenReturn("plain-value");
        when(configuration.getDefaultValue()).thenReturn("default-value");
        when(configuration.isDynamic()).thenReturn(true);
        when(configuration.getComponent()).thenReturn("management-server");
        when(configuration.getParent()).thenReturn("parent.setting");
        when(configuration.getDisplayText()).thenReturn("Secret setting");
        when(configuration.getOptions()).thenReturn("one,two");
        when(configuration.isEncrypted()).thenReturn(true);
        when(configManager.getConfigurationGroupAndSubGroup("secret.setting")).thenReturn(new Pair<>("group", "subgroup"));
        when(configManager.getConfigurationType("secret.setting")).thenReturn("String");

        ConfigurationResponse response = service.createConfigurationResponse(configuration);

        assertEquals("Advanced", response.getCategory());
        assertEquals("group", response.getGroup());
        assertEquals("subgroup", response.getSubGroup());
        assertEquals("description", response.getDescription());
        assertEquals("secret.setting", response.getName());
        assertEquals("plain-value", response.getValue());
        assertEquals("default-value", response.getDefaultValue());
        assertEquals(true, response.isDynamic());
        assertEquals("management-server", response.getComponent());
        assertEquals("parent.setting", response.getParent());
        assertEquals("Secret setting", response.getDisplayText());
        assertEquals("String", response.getType());
        assertEquals("one,two", response.getOptions());
        assertEquals("configuration", response.getObjectName());
    }

    @Test
    public void createConfigurationGroupResponseIncludesSubGroups() {
        when(configurationGroup.getId()).thenReturn(19L);
        when(configurationGroup.getName()).thenReturn("compute");
        when(configurationGroup.getDescription()).thenReturn("compute settings");
        when(configurationGroup.getPrecedence()).thenReturn(3L);
        ConfigurationSubGroupVO configurationSubGroup = new ConfigurationSubGroupVO("placement", "placement", 5L);
        when(configManager.getConfigurationSubGroups(19L)).thenReturn(List.of(configurationSubGroup));

        ConfigurationGroupResponse response = service.createConfigurationGroupResponse(configurationGroup);

        assertEquals("compute", response.getGroupName());
        assertEquals("compute settings", response.getDescription());
        assertEquals(3L, response.getPrecedence().longValue());
        assertEquals("configurationgroup", response.getObjectName());
        ConfigurationSubGroupResponse subGroupResponse = response.getSubGroups().get(0);
        assertEquals("placement", subGroupResponse.getSubGroupName());
        assertEquals(5L, subGroupResponse.getPrecedence().longValue());
        assertEquals("subgroup", subGroupResponse.getObjectName());
    }

    private void populateAccountName(ControlledEntityResponse response, Long accountId) {
        response.setAccountName("account-" + accountId);
    }
}
