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
package com.cloud.api.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.acl.RoleService;
import org.apache.cloudstack.acl.RoleVO;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.command.admin.user.ListUsersCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.UserResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.utils.baremetal.BaremetalUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.dao.UserAccountJoinDao;
import com.cloud.api.query.vo.UserAccountJoinVO;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import org.apache.cloudstack.acl.dao.RoleDao;

@RunWith(MockitoJUnitRunner.class)
public class UserQueryServiceImplTest {

    @Mock private AccountManager accountMgr;
    @Mock private RoleService roleService;
    @Mock private DomainDao domainDao;
    @Mock private UserAccountJoinDao userAccountJoinDao;
    @Mock private AccountDao accountDao;
    @Mock private UserDao userDao;
    @Mock private RoleDao roleDao;
    @Mock private SearchBuilder<UserAccountJoinVO> searchBuilder;
    @Mock private SearchCriteria<UserAccountJoinVO> searchCriteria;
    @Mock private UserAccountJoinVO entityProxy;
    @Mock private Account callingAccount;
    @Mock private User callingUser;
    @Mock private CallContext callContext;

    @InjectMocks
    private UserQueryServiceImpl service;

    private MockedStatic<CallContext> callContextStatic;

    @Before
    public void setUp() {
        callContextStatic = Mockito.mockStatic(CallContext.class);
        callContextStatic.when(CallContext::current).thenReturn(callContext);

        Mockito.lenient().when(callContext.getCallingAccount()).thenReturn(callingAccount);
        Mockito.lenient().when(callContext.getCallingUser()).thenReturn(callingUser);
        Mockito.lenient().when(callingAccount.getType()).thenReturn(Account.Type.NORMAL);
        Mockito.lenient().when(callingAccount.getDomainId()).thenReturn(1L);
        Mockito.lenient().when(callingAccount.getId()).thenReturn(1L);
        Mockito.lenient().when(callingUser.getId()).thenReturn(1L);
        Mockito.lenient().when(userAccountJoinDao.createSearchBuilder()).thenReturn(searchBuilder);
        Mockito.lenient().when(searchBuilder.entity()).thenReturn(entityProxy);
        Mockito.lenient().when(searchBuilder.create()).thenReturn(searchCriteria);
    }

    @After
    public void tearDown() {
        callContextStatic.close();
    }

    @Test
    public void searchForUsersAppliesCommandFiltersToSearchCriteria() {
        when(callingAccount.getType()).thenReturn(Account.Type.ADMIN);
        ListUsersCmd cmd = cmdWithDefaults();
        String username = "Admin";
        String accountName = "Admin";
        Account.Type accountType = Account.Type.ADMIN;
        Long domainId = 1L;
        String apiKeyAccess = "Disabled";
        User.Source userSource = User.Source.NATIVE;
        when(cmd.getUsername()).thenReturn(username);
        when(cmd.getAccountName()).thenReturn(accountName);
        when(cmd.getAccountType()).thenReturn(accountType);
        when(cmd.getDomainId()).thenReturn(domainId);
        when(cmd.getApiKeyAccess()).thenReturn(apiKeyAccess);
        when(cmd.getUserSource()).thenReturn(userSource);
        when(userAccountJoinDao.searchAndCount(any(SearchCriteria.class), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        try (MockedStatic<ViewResponseHelper> viewResponseHelper = Mockito.mockStatic(ViewResponseHelper.class)) {
            viewResponseHelper.when(() -> ViewResponseHelper.createUserResponse(any(), any(), any(UserAccountJoinVO[].class)))
                    .thenReturn(Collections.emptyList());

            service.searchForUsers(ResponseView.Restricted, cmd);
        }

        verify(searchCriteria).setParameters("username", username);
        verify(searchCriteria).setParameters("accountName", accountName);
        verify(searchCriteria).setParameters("type", accountType);
        verify(searchCriteria).setParameters("domainId", domainId);
        verify(searchCriteria).setParameters("apiKeyAccess", false);
        verify(searchCriteria).setParameters("userSource", userSource.toString());
        verify(userAccountJoinDao).searchAndCount(any(SearchCriteria.class), any(Filter.class));
    }

    @Test
    public void searchForUsersNormalCallerCannotRequestAnotherUserId() {
        ListUsersCmd cmd = cmdWithDefaults();
        when(callingUser.getId()).thenReturn(10L);
        when(cmd.getId()).thenReturn(11L);

        try {
            service.searchForUsers(ResponseView.Restricted, cmd);
            fail("expected PermissionDeniedException");
        } catch (PermissionDeniedException expected) {
            assertEquals("Calling user is not authorized to see the user requested by id", expected.getMessage());
        }

        verify(userAccountJoinDao, never()).searchAndCount(any(SearchCriteria.class), any(Filter.class));
    }

    @Test
    public void searchForUsersRejectsInvalidApiKeyAccess() {
        when(callingAccount.getType()).thenReturn(Account.Type.ADMIN);
        ListUsersCmd cmd = cmdWithDefaults();
        when(cmd.getApiKeyAccess()).thenReturn("Maybe");

        try {
            service.searchForUsers(ResponseView.Restricted, cmd);
            fail("expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            assertEquals("ApiKeyAccess value can only be Enabled/Disabled/Inherit", expected.getMessage());
        }
    }

    @Test
    public void searchForUsersSystemUserIdReturnsEmptyResponse() {
        ListUsersCmd cmd = cmdWithDefaults();
        when(cmd.getId()).thenReturn(1L);

        try (MockedStatic<ViewResponseHelper> viewResponseHelper = Mockito.mockStatic(ViewResponseHelper.class)) {
            viewResponseHelper.when(() -> ViewResponseHelper.createUserResponse(any(), any(), any(UserAccountJoinVO[].class)))
                    .thenReturn(Collections.emptyList());

            ListResponse<UserResponse> response = service.searchForUsers(ResponseView.Restricted, cmd);

            assertEquals(Integer.valueOf(0), response.getCount());
            assertTrue(response.getResponses().isEmpty());
        }

        verify(userAccountJoinDao, never()).searchAndCount(any(SearchCriteria.class), any(Filter.class));
    }

    @Test
    public void searchForUsersDomainWithoutAccountUsesDomainPath() {
        when(callingAccount.getType()).thenReturn(Account.Type.ADMIN);
        ListUsersCmd cmd = cmdWithDefaults();
        when(cmd.getDomainId()).thenReturn(7L);
        DomainVO domain = new DomainVO();
        domain.setPath("/ROOT/child/");
        when(domainDao.findById(7L)).thenReturn(domain);
        when(userAccountJoinDao.searchAndCount(any(SearchCriteria.class), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        try (MockedStatic<ViewResponseHelper> viewResponseHelper = Mockito.mockStatic(ViewResponseHelper.class)) {
            viewResponseHelper.when(() -> ViewResponseHelper.createUserResponse(any(), any(), any(UserAccountJoinVO[].class)))
                    .thenReturn(Collections.emptyList());

            service.searchForUsers(ResponseView.Restricted, cmd);
        }

        verify(searchCriteria).setParameters("domainPath", "/ROOT/child/%");
    }

    @Test
    public void searchForAccessibleUsersFiltersBaremetalNullsDeniedAndDisallowedRoles() {
        DomainVO callingDomain = new DomainVO();
        callingDomain.setPath("/ROOT/");
        when(domainDao.findById(1L)).thenReturn(callingDomain);
        RoleVO allowed = new RoleVO(100L, "allowed", null, "allowed");
        RoleVO removed = new RoleVO(200L, "removed", null, "removed");
        List<RoleVO> roles = new ArrayList<>(Arrays.asList(allowed, removed));
        when(roleDao.listAll()).thenReturn(roles);
        doAnswer(invocation -> {
            List<RoleVO> mutableRoles = invocation.getArgument(0);
            mutableRoles.removeIf(role -> role.getId() == 200L);
            return null;
        }).when(roleService).removeRolesIfNeeded(any());
        when(accountMgr.isRootAdmin(1L)).thenReturn(false);

        UserAccountJoinVO baremetal = userRow(10L, 20L, BaremetalUtils.BAREMETAL_SYSTEM_ACCOUNT_NAME, 100L);
        UserAccountJoinVO missingAccount = userRow(11L, 21L, "missing-account", 100L);
        UserAccountJoinVO missingUser = userRow(12L, 22L, "missing-user", 100L);
        UserAccountJoinVO denied = userRow(13L, 23L, "denied", 100L);
        UserAccountJoinVO disallowedRole = userRow(14L, 24L, "disallowed-role", 200L);
        UserAccountJoinVO included = userRow(15L, 25L, "included", 100L);
        when(userAccountJoinDao.searchAndCount(any(SearchCriteria.class), any(Filter.class)))
                .thenReturn(new Pair<>(Arrays.asList(baremetal, missingAccount, missingUser, denied, disallowedRole, included), 6));

        AccountVO deniedAccount = mock(AccountVO.class);
        UserVO deniedUser = mock(UserVO.class);
        AccountVO includedAccount = mock(AccountVO.class);
        UserVO includedUser = mock(UserVO.class);
        AccountVO disallowedAccount = mock(AccountVO.class);
        UserVO disallowedUser = mock(UserVO.class);
        when(accountDao.findByIdIncludingRemoved(21L)).thenReturn(null);
        when(accountDao.findByIdIncludingRemoved(22L)).thenReturn(mock(AccountVO.class));
        when(userDao.findByIdIncludingRemoved(12L)).thenReturn(null);
        when(accountDao.findByIdIncludingRemoved(23L)).thenReturn(deniedAccount);
        when(userDao.findByIdIncludingRemoved(13L)).thenReturn(deniedUser);
        when(accountDao.findByIdIncludingRemoved(24L)).thenReturn(disallowedAccount);
        when(userDao.findByIdIncludingRemoved(14L)).thenReturn(disallowedUser);
        when(accountDao.findByIdIncludingRemoved(25L)).thenReturn(includedAccount);
        when(userDao.findByIdIncludingRemoved(15L)).thenReturn(includedUser);
        doThrow(new PermissionDeniedException("denied")).when(accountMgr)
                .checkCallerRoleTypeAllowedForUserOrAccountOperations(eq(deniedAccount), eq(deniedUser));

        List<Long> result = service.searchForAccessibleUsers();

        assertEquals(Collections.singletonList(15L), result);
        verify(accountMgr).isRootAdmin(callingAccount.getId());
        verify(roleService).removeRolesIfNeeded(roles);
    }

    private UserAccountJoinVO userRow(long id, long accountId, String username, Long roleId) {
        UserAccountJoinVO row = mock(UserAccountJoinVO.class);
        when(row.getId()).thenReturn(id);
        when(row.getAccountId()).thenReturn(accountId);
        when(row.getUsername()).thenReturn(username);
        when(row.getAccountRoleId()).thenReturn(roleId);
        return row;
    }

    private ListUsersCmd cmdWithDefaults() {
        ListUsersCmd cmd = mock(ListUsersCmd.class);
        Mockito.lenient().when(cmd.listAll()).thenReturn(false);
        Mockito.lenient().when(cmd.getId()).thenReturn(null);
        Mockito.lenient().when(cmd.getUsername()).thenReturn(null);
        Mockito.lenient().when(cmd.getAccountType()).thenReturn(null);
        Mockito.lenient().when(cmd.getAccountName()).thenReturn(null);
        Mockito.lenient().when(cmd.getState()).thenReturn(null);
        Mockito.lenient().when(cmd.getKeyword()).thenReturn(null);
        Mockito.lenient().when(cmd.getApiKeyAccess()).thenReturn(null);
        Mockito.lenient().when(cmd.getUserSource()).thenReturn(null);
        Mockito.lenient().when(cmd.getDomainId()).thenReturn(null);
        Mockito.lenient().when(cmd.isRecursive()).thenReturn(false);
        Mockito.lenient().when(cmd.getPageSizeVal()).thenReturn(null);
        Mockito.lenient().when(cmd.getStartIndex()).thenReturn(null);
        return cmd;
    }
}
