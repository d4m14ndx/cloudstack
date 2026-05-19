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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.List;

import org.apache.cloudstack.acl.ApiKeyPairPermissionVO;
import org.apache.cloudstack.acl.ApiKeyPairVO;
import org.apache.cloudstack.acl.RolePermissionEntity.Permission;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.acl.RoleVO;
import org.apache.cloudstack.acl.dao.RoleDao;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPairPermission;
import org.apache.cloudstack.api.response.ApiKeyPairResponse;
import org.apache.cloudstack.api.response.BaseRolePermissionResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.ApiKeyPairState;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;

@RunWith(MockitoJUnitRunner.class)
public class ApiKeyPairResponseServiceTest {

    private static final long USER_ID = 11L;
    private static final long ACCOUNT_ID = 12L;
    private static final long DOMAIN_ID = 13L;
    private static final long ROLE_ID = 14L;

    @Mock
    private RoleDao roleDao;
    @Mock
    private AccountDao accountDao;
    @Mock
    private DomainDao domainDao;
    @Mock
    private User user;

    private ApiKeyPairResponseService service;
    private Date created;
    private Date startDate;
    private Date endDate;

    @Before
    public void setup() {
        service = Mockito.spy(new ApiKeyPairResponseService());
        ReflectionTestUtils.setField(service, "roleDao", roleDao);
        ReflectionTestUtils.setField(service, "accountDao", accountDao);
        ReflectionTestUtils.setField(service, "domainDao", domainDao);
        created = new Date(1000L);
        startDate = new Date(2000L);
        endDate = new Date(System.currentTimeMillis() + 3600000L);
    }

    @Test
    public void createKeyPairResponsePopulatesFullResponseWithEnabledState() {
        ApiKeyPairVO keyPair = createKeyPair(null, endDate);
        stubLookupObjects();

        ApiKeyPairResponse response = service.createKeyPairResponse(keyPair);

        assertEquals("keypair-name", response.getName());
        assertEquals("api-key", response.getApiKey());
        assertEquals("secret-key", response.getSecretKey());
        assertEquals("keypair-description", response.getDescription());
        assertEquals("keypair-uuid", response.getId());
        assertEquals(created, response.getCreated());
        assertEquals(startDate, response.getStartDate());
        assertEquals(endDate, response.getEndDate());
        assertEquals("user-uuid", response.getUserId());
        assertEquals("user-name", response.getUsername());
        assertEquals("account-uuid", response.getAccountId());
        assertEquals("account-name", response.getAccountName());
        assertEquals(Account.Type.NORMAL.toString(), response.getAccountType());
        assertEquals("domain-uuid", response.getDomainId());
        assertEquals("domain-name", response.getDomainName());
        assertEquals("ROOT/account/domain", response.getDomainPath());
        assertEquals("role-uuid", response.getRoleId());
        assertEquals("role-name", response.getRoleName());
        assertEquals(RoleType.User.name(), response.getRoleType());
        assertEquals(ApiKeyPairState.ENABLED, response.getState());
    }

    @Test
    public void createKeyPairResponseSetsRemovedState() {
        ApiKeyPairVO keyPair = createKeyPair(new Date(3000L), endDate);
        stubLookupObjects();

        ApiKeyPairResponse response = service.createKeyPairResponse(keyPair);

        assertEquals(ApiKeyPairState.REMOVED, response.getState());
    }

    @Test
    public void createKeyPairResponseSetsExpiredState() {
        ApiKeyPairVO keyPair = createKeyPair(null, new Date(1L));
        stubLookupObjects();

        ApiKeyPairResponse response = service.createKeyPairResponse(keyPair);

        assertEquals(ApiKeyPairState.EXPIRED, response.getState());
    }

    @Test
    public void createKeypairPermissionsResponseMapsPermissionFields() {
        ApiKeyPairPermission permission = new ApiKeyPairPermissionVO(1L, "listUsers", Permission.ALLOW, "can list users");

        ListResponse<BaseRolePermissionResponse> response = service.createKeypairPermissionsResponse(List.of(permission));

        BaseRolePermissionResponse permissionResponse = response.getResponses().get(0);
        assertEquals("listUsers", permissionResponse.getRule());
        assertEquals("allow", permissionResponse.getRulePermission());
        assertEquals("can list users", ReflectionTestUtils.getField(permissionResponse, "ruleDescription"));
        assertEquals("keypermission", permissionResponse.getObjectName());
    }

    @Test
    public void populateDomainInApiKeyPairResponseFormatsRootPrefixedPathAndRemovesTrailingSlash() {
        DomainVO domain = new DomainVO();
        domain.setUuid("domain-uuid");
        domain.setName("domain-name");
        domain.setPath("/account/domain/");
        when(domainDao.findById(DOMAIN_ID)).thenReturn(domain);
        ApiKeyPairResponse response = new ApiKeyPairResponse();

        service.populateDomainInApiKeyPairResponse(DOMAIN_ID, response);

        assertEquals("ROOT/account/domain", response.getDomainPath());
    }

    private ApiKeyPairVO createKeyPair(Date removed, Date keyPairEndDate) {
        ApiKeyPairVO keyPair = new ApiKeyPairVO();
        keyPair.setUuid("keypair-uuid");
        keyPair.setName("keypair-name");
        keyPair.setApiKey("api-key");
        keyPair.setSecretKey("secret-key");
        keyPair.setDescription("keypair-description");
        keyPair.setCreated(created);
        keyPair.setStartDate(startDate);
        keyPair.setEndDate(keyPairEndDate);
        keyPair.setRemoved(removed);
        keyPair.setUserId(USER_ID);
        keyPair.setAccountId(ACCOUNT_ID);
        return keyPair;
    }

    private void stubLookupObjects() {
        doReturn(user).when(service).findUserById(USER_ID);
        when(user.getUuid()).thenReturn("user-uuid");
        when(user.getUsername()).thenReturn("user-name");

        AccountVO account = new AccountVO("account-name", DOMAIN_ID, "network-domain", Account.Type.NORMAL, ROLE_ID, "account-uuid");
        when(accountDao.findByIdIncludingRemoved(ACCOUNT_ID)).thenReturn(account);

        DomainVO domain = new DomainVO();
        domain.setUuid("domain-uuid");
        domain.setName("domain-name");
        domain.setPath("/account/domain/");
        when(domainDao.findById(DOMAIN_ID)).thenReturn(domain);

        RoleVO role = new RoleVO(ROLE_ID, "role-name", RoleType.User, "role-description");
        role.setUuid("role-uuid");
        when(roleDao.findById(ROLE_ID)).thenReturn(role);
    }
}
