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
import static org.junit.Assert.assertSame;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.EnumSet;

import org.apache.cloudstack.api.ApiConstants.DomainDetails;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.AccountResponse;
import org.apache.cloudstack.api.response.DomainResponse;
import org.apache.cloudstack.api.response.UserResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.api.query.vo.AccountJoinVO;
import com.cloud.api.query.vo.UserAccountJoinVO;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.offering.DiskOffering;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.user.UserAccount;

@RunWith(MockitoJUnitRunner.class)
public class ApiIdentityAccountResponseServiceImplTest {

    @Mock
    private ApiResponseOwnerService apiResponseOwnerService;
    @Mock
    private User user;
    @Mock
    private UserAccount userAccount;
    @Mock
    private Account account;
    @Mock
    private Domain domain;
    @Mock
    private DomainVO parentDomain;
    @Mock
    private UserAccountJoinVO userView;
    @Mock
    private AccountJoinVO accountView;
    @Mock
    private UserResponse userResponse;
    @Mock
    private AccountResponse accountResponse;
    @Mock
    private VMTemplateVO template;
    @Mock
    private DiskOfferingVO diskOffering;

    private ApiIdentityAccountResponseServiceImpl service;

    @Before
    public void setUp() {
        service = new ApiIdentityAccountResponseServiceImpl();
        ReflectionTestUtils.setField(service, "apiResponseOwnerService", apiResponseOwnerService);
    }

    @Test
    public void createUserResponseFromUserUsesUserViewResponse() {
        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.newUserView(user)).thenReturn(userView);
            apiDBUtils.when(() -> ApiDBUtils.newUserResponse(userView)).thenReturn(userResponse);

            assertSame(userResponse, service.createUserResponse(user));

            apiDBUtils.verify(() -> ApiDBUtils.newUserView(user));
            apiDBUtils.verify(() -> ApiDBUtils.newUserResponse(userView));
        }
    }

    @Test
    public void createUserAccountResponseLoadsAccountViewByUserAccountId() {
        when(userAccount.getAccountId()).thenReturn(7L);

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findAccountViewById(7L)).thenReturn(accountView);
            apiDBUtils.when(() -> ApiDBUtils.newAccountResponse(ResponseView.Full, EnumSet.of(DomainDetails.all), accountView)).thenReturn(accountResponse);

            assertSame(accountResponse, service.createUserAccountResponse(ResponseView.Full, userAccount));
        }
    }

    @Test
    public void createAccountResponseUsesAccountViewResponse() {
        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.newAccountView(account)).thenReturn(accountView);
            apiDBUtils.when(() -> ApiDBUtils.newAccountResponse(ResponseView.Restricted, EnumSet.of(DomainDetails.all), accountView)).thenReturn(accountResponse);

            assertSame(accountResponse, service.createAccountResponse(ResponseView.Restricted, account));
        }
    }

    @Test
    public void createUserResponseFromUserAccountUsesUserAccountViewResponse() {
        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.newUserView(userAccount)).thenReturn(userView);
            apiDBUtils.when(() -> ApiDBUtils.newUserResponse(userView)).thenReturn(userResponse);

            assertSame(userResponse, service.createUserResponse(userAccount));
        }
    }

    @Test
    public void createDomainResponseBuildsDomainFieldsWithOwnerServiceHelpers() {
        Date created = new Date(1234L);
        when(domain.getName()).thenReturn("domain-name");
        when(domain.getUuid()).thenReturn("domain-uuid");
        when(domain.getLevel()).thenReturn(2);
        when(domain.getCreated()).thenReturn(created);
        when(domain.getNetworkDomain()).thenReturn("network.example");
        when(domain.getParent()).thenReturn(5L);
        when(domain.getPath()).thenReturn("/customer/engineering/");
        when(domain.getChildCount()).thenReturn(1);
        when(parentDomain.getUuid()).thenReturn("parent-uuid");
        when(parentDomain.getName()).thenReturn("parent-name");
        when(apiResponseOwnerService.getPrettyDomainPath("/customer/engineering/")).thenReturn("ROOT/customer/engineering");

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findDomainById(5L)).thenReturn(parentDomain);

            DomainResponse response = service.createDomainResponse(domain);

            assertEquals("domain-name", response.getDomainName());
            assertEquals("domain-uuid", response.getId());
            assertEquals(Integer.valueOf(2), response.getLevel());
            assertEquals("parent-uuid", response.getParentDomainId());
            assertEquals("parent-name", response.getParentDomainName());
            assertEquals("ROOT/customer/engineering", response.getPath());
            assertEquals(true, response.getHasChild());
            assertEquals("domain", response.getObjectName());
            verify(apiResponseOwnerService).populateDomainTags("domain-uuid", response);
        }
    }

    @Test
    public void findersDelegateToApiDbUtils() {
        Account accountByNameDomain = Mockito.mock(Account.class);

        try (MockedStatic<ApiDBUtils> apiDBUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDBUtils.when(() -> ApiDBUtils.findUserById(1L)).thenReturn(user);
            apiDBUtils.when(() -> ApiDBUtils.findAccountByNameDomain("account", 2L)).thenReturn(accountByNameDomain);
            apiDBUtils.when(() -> ApiDBUtils.findTemplateById(3L)).thenReturn(template);
            apiDBUtils.when(() -> ApiDBUtils.findDiskOfferingById(4L)).thenReturn(diskOffering);

            assertSame(user, service.findUserById(1L));
            assertSame(accountByNameDomain, service.findAccountByNameDomain("account", 2L));
            assertSame(template, service.findTemplateById(3L));
            DiskOffering result = service.findDiskOfferingById(4L);
            assertSame(diskOffering, result);
        }
    }
}
