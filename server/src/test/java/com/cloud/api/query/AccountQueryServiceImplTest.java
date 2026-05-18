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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.command.user.account.ListAccountsCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.dao.AccountJoinDao;
import com.cloud.api.query.vo.AccountJoinVO;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class AccountQueryServiceImplTest {

    private static final long CALLER_ID = 7L;
    private static final long CALLER_ACCOUNT_ID = 9L;
    private static final long CALLER_DOMAIN_ID = 11L;

    @Mock private AccountManager accountMgr;
    @Mock private DomainDao domainDao;
    @Mock private AccountDao accountDao;
    @Mock private AccountJoinDao accountJoinDao;

    @Mock private SearchBuilder<AccountVO> accountSearchBuilder;
    @Mock private SearchBuilder<DomainVO> domainSearchBuilder;
    @Mock private SearchCriteria<AccountVO> accountSearchCriteria;
    @Mock private AccountVO accountEntity;
    @Mock private DomainVO domainEntity;
    @Mock private Account caller;

    @InjectMocks
    private AccountQueryServiceImpl service;

    private MockedStatic<CallContext> callContextStatic;

    @Before
    public void setUp() {
        Mockito.lenient().when(accountDao.createSearchBuilder()).thenReturn(accountSearchBuilder);
        Mockito.lenient().when(accountSearchBuilder.entity()).thenReturn(accountEntity);
        Mockito.lenient().when(accountSearchBuilder.create()).thenReturn(accountSearchCriteria);
        Mockito.lenient().when(accountSearchBuilder.and()).thenReturn(accountSearchBuilder);
        Mockito.lenient().when(accountSearchBuilder.op(any(String.class), any(), any(SearchCriteria.Op.class))).thenReturn(accountSearchBuilder);
        Mockito.lenient().when(accountSearchBuilder.or(any(String.class), any(), any(SearchCriteria.Op.class))).thenReturn(accountSearchBuilder);
        Mockito.lenient().when(accountSearchBuilder.cp()).thenReturn(accountSearchBuilder);

        Mockito.lenient().when(domainDao.createSearchBuilder()).thenReturn(domainSearchBuilder);
        Mockito.lenient().when(domainSearchBuilder.entity()).thenReturn(domainEntity);

        Mockito.lenient().when(caller.getId()).thenReturn(CALLER_ID);
        Mockito.lenient().when(caller.getAccountId()).thenReturn(CALLER_ACCOUNT_ID);
        Mockito.lenient().when(caller.getDomainId()).thenReturn(CALLER_DOMAIN_ID);
        Mockito.lenient().when(accountMgr.isAdmin(anyLong())).thenReturn(false);

        CallContext ctx = mock(CallContext.class);
        Mockito.lenient().when(ctx.getCallingAccount()).thenReturn(caller);
        callContextStatic = Mockito.mockStatic(CallContext.class);
        callContextStatic.when(CallContext::current).thenReturn(ctx);
    }

    @After
    public void tearDown() {
        callContextStatic.close();
    }

    private ListAccountsCmd cmdWithDefaults() {
        ListAccountsCmd cmd = mock(ListAccountsCmd.class);
        Mockito.lenient().when(cmd.getDomainId()).thenReturn(null);
        Mockito.lenient().when(cmd.getId()).thenReturn(null);
        Mockito.lenient().when(cmd.getSearchName()).thenReturn(null);
        Mockito.lenient().when(cmd.isRecursive()).thenReturn(false);
        Mockito.lenient().when(cmd.listAll()).thenReturn(false);
        Mockito.lenient().when(cmd.getStartIndex()).thenReturn(0L);
        Mockito.lenient().when(cmd.getPageSizeVal()).thenReturn(50L);
        Mockito.lenient().when(cmd.getAccountType()).thenReturn(null);
        Mockito.lenient().when(cmd.getState()).thenReturn(null);
        Mockito.lenient().when(cmd.isCleanupRequired()).thenReturn(null);
        Mockito.lenient().when(cmd.getKeyword()).thenReturn(null);
        Mockito.lenient().when(cmd.getApiKeyAccess()).thenReturn(null);
        return cmd;
    }

    private void stubEmptyAccountSearch() {
        when(accountDao.searchAndCount(eq(accountSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));
    }

    @Test
    public void searchForAccountsInternalReturnsEmptyWhenCountIsZero() {
        ListAccountsCmd cmd = cmdWithDefaults();
        stubEmptyAccountSearch();

        Pair<List<AccountJoinVO>, Integer> result = service.searchForAccountsInternal(cmd);

        assertNotNull(result);
        assertEquals(Integer.valueOf(0), result.second());
        assertTrue(result.first().isEmpty());
        verify(accountJoinDao, never()).searchByIds(any(Long[].class));
    }

    @Test
    public void searchForAccountsInternalThrowsWhenExplicitDomainDoesNotExist() {
        ListAccountsCmd cmd = cmdWithDefaults();
        when(cmd.getDomainId()).thenReturn(99L);
        when(domainDao.findById(99L)).thenReturn(null);

        try {
            service.searchForAccountsInternal(cmd);
            fail("expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            assertEquals("Domain id=99 doesn't exist", expected.getMessage());
        }

        verify(accountMgr, never()).checkAccess(any(Account.class), any(Domain.class));
        verify(accountDao, never()).searchAndCount(any(SearchCriteria.class), any(Filter.class));
    }

    @Test
    public void searchForAccountsInternalEnforcesAccessForExplicitDomain() {
        ListAccountsCmd cmd = cmdWithDefaults();
        when(cmd.getDomainId()).thenReturn(42L);
        DomainVO domain = mock(DomainVO.class);
        when(domainDao.findById(42L)).thenReturn(domain);
        doThrow(new PermissionDeniedException("denied"))
                .when(accountMgr).checkAccess(eq(caller), any(Domain.class));

        try {
            service.searchForAccountsInternal(cmd);
            fail("expected PermissionDeniedException");
        } catch (PermissionDeniedException expected) {
            assertEquals("denied", expected.getMessage());
        }

        verify(accountMgr).checkAccess(eq(caller), any(Domain.class));
        verify(accountDao, never()).searchAndCount(any(SearchCriteria.class), any(Filter.class));
    }

    @Test
    public void searchForAccountsInternalDefaultsToCallerAccountForNonAdminWithoutScope() {
        ListAccountsCmd cmd = cmdWithDefaults();
        stubEmptyAccountSearch();

        service.searchForAccountsInternal(cmd);

        verify(accountSearchCriteria).setParameters(eq("id"), eq(CALLER_ACCOUNT_ID));
        verify(accountSearchCriteria, never()).setParameters(eq("domainId"), any());
    }

    @Test
    public void searchForAccountsInternalUsesCallerDomainRecursivePathForAdminListAllWithoutDomain() {
        ListAccountsCmd cmd = cmdWithDefaults();
        when(cmd.listAll()).thenReturn(true);
        when(accountMgr.isAdmin(CALLER_ID)).thenReturn(true);
        DomainVO callerDomain = mock(DomainVO.class);
        when(callerDomain.getPath()).thenReturn("/root/customer/");
        when(domainDao.findById(CALLER_DOMAIN_ID)).thenReturn(callerDomain);
        stubEmptyAccountSearch();

        service.searchForAccountsInternal(cmd);

        verify(accountSearchBuilder).join(eq("domainSearch"), eq(domainSearchBuilder), any(), any(), eq(JoinBuilder.JoinType.INNER));
        verify(accountSearchCriteria).setJoinParameters(eq("domainSearch"), eq("path"), eq("/root/customer/%"));
        verify(accountSearchCriteria, never()).setParameters(eq("domainId"), eq(CALLER_DOMAIN_ID));
    }

    @Test
    public void searchForAccountsInternalFindsAccountByNameAndDomainWhenExplicitIdAlsoProvided() {
        ListAccountsCmd cmd = cmdWithDefaults();
        when(cmd.getId()).thenReturn(123L);
        when(cmd.getDomainId()).thenReturn(42L);
        when(cmd.getSearchName()).thenReturn("Admin");
        DomainVO domain = mock(DomainVO.class);
        AccountVO account = mock(AccountVO.class);
        when(account.getId()).thenReturn(123L);
        when(domainDao.findById(42L)).thenReturn(domain);
        when(accountDao.findActiveAccount("Admin", 42L)).thenReturn(account);
        stubEmptyAccountSearch();

        service.searchForAccountsInternal(cmd);

        verify(accountDao).findActiveAccount("Admin", 42L);
        verify(accountMgr).checkAccess(caller, null, true, account);
        verify(accountSearchCriteria).setParameters(eq("accountName"), eq("Admin"));
        verify(accountSearchCriteria).setParameters(eq("id"), eq(123L));
    }

    @Test
    public void searchForAccountsInternalRejectsMissingAccountByNameAndDomain() {
        ListAccountsCmd cmd = cmdWithDefaults();
        when(cmd.getId()).thenReturn(123L);
        when(cmd.getDomainId()).thenReturn(42L);
        when(cmd.getSearchName()).thenReturn("Admin");
        DomainVO domain = mock(DomainVO.class);
        when(domainDao.findById(42L)).thenReturn(domain);
        when(accountDao.findActiveAccount("Admin", 42L)).thenReturn(null);

        try {
            service.searchForAccountsInternal(cmd);
            fail("expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            assertEquals("Unable to find account by name Admin in domain 42", expected.getMessage());
        }

        verify(accountDao, never()).searchAndCount(any(SearchCriteria.class), any(Filter.class));
    }

    @Test
    public void searchForAccountsInternalRejectsMissingAccountByIdWithoutDomain() {
        ListAccountsCmd cmd = cmdWithDefaults();
        when(cmd.getId()).thenReturn(123L);
        when(accountDao.findById(123L)).thenReturn(null);

        try {
            service.searchForAccountsInternal(cmd);
            fail("expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            assertEquals("Unable to find account by id 123", expected.getMessage());
        }

        verify(accountDao).findById(123L);
        verify(accountDao, never()).searchAndCount(any(SearchCriteria.class), any(Filter.class));
    }

    @Test
    public void searchForAccountsInternalChecksAccessForExplicitAccountIdWithoutDomain() {
        ListAccountsCmd cmd = cmdWithDefaults();
        when(cmd.getId()).thenReturn(123L);
        AccountVO account = mock(AccountVO.class);
        when(account.getId()).thenReturn(123L);
        when(accountDao.findById(123L)).thenReturn(account);
        stubEmptyAccountSearch();

        service.searchForAccountsInternal(cmd);

        verify(accountMgr).checkAccess(caller, null, true, account);
        verify(accountSearchCriteria).setParameters(eq("id"), eq(123L));
    }

    @Test
    public void searchForAccountsInternalAppliesKeywordToAccountNameAndState() {
        ListAccountsCmd cmd = cmdWithDefaults();
        when(cmd.getKeyword()).thenReturn("enabled");
        stubEmptyAccountSearch();

        service.searchForAccountsInternal(cmd);

        verify(accountSearchCriteria).setParameters(eq("keywordAccountName"), eq("%enabled%"));
        verify(accountSearchCriteria).setParameters(eq("keywordState"), eq("%enabled%"));
    }

    @Test
    public void searchForAccountsInternalHidesDomainAdminsFromNonAdminCaller() {
        ListAccountsCmd cmd = cmdWithDefaults();
        stubEmptyAccountSearch();

        service.searchForAccountsInternal(cmd);

        verify(accountSearchCriteria).setParameters(eq("type2NEQ"), eq(Account.Type.DOMAIN_ADMIN));
    }

    @Test
    public void searchForAccountsInternalDoesNotHideDomainAdminsFromAdminCaller() {
        ListAccountsCmd cmd = cmdWithDefaults();
        when(accountMgr.isAdmin(CALLER_ID)).thenReturn(true);
        stubEmptyAccountSearch();

        service.searchForAccountsInternal(cmd);

        verify(accountSearchCriteria, never()).setParameters(eq("type2NEQ"), any());
    }

    @Test
    public void searchForAccountsInternalAppliesApiKeyAccessEnabledFilter() {
        ListAccountsCmd cmd = cmdWithDefaults();
        when(cmd.getApiKeyAccess()).thenReturn("Enabled");
        stubEmptyAccountSearch();

        service.searchForAccountsInternal(cmd);

        verify(accountSearchCriteria).setParameters(eq("apiKeyAccess"), eq(true));
    }

    @Test
    public void searchForAccountsInternalThrowsForInvalidApiKeyAccess() {
        ListAccountsCmd cmd = cmdWithDefaults();
        when(cmd.getApiKeyAccess()).thenReturn("Wrong");
        stubEmptyAccountSearch();

        try {
            service.searchForAccountsInternal(cmd);
            fail("expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            assertEquals("ApiKeyAccess value can only be Enabled/Disabled/Inherit", expected.getMessage());
        }
    }

    @Test
    public void searchForAccountsInternalAppliesAccountTypeStateAndCleanupFilters() {
        ListAccountsCmd cmd = cmdWithDefaults();
        when(cmd.getAccountType()).thenReturn(Account.Type.ADMIN);
        when(cmd.getState()).thenReturn("enabled");
        when(cmd.isCleanupRequired()).thenReturn(true);
        stubEmptyAccountSearch();

        service.searchForAccountsInternal(cmd);

        verify(accountSearchCriteria).setParameters(eq("type"), eq(Account.Type.ADMIN));
        verify(accountSearchCriteria).setParameters(eq("state"), eq("enabled"));
        verify(accountSearchCriteria).setParameters(eq("needsCleanup"), eq(true));
    }

    @Test
    public void searchForAccountsInternalHydratesNonEmptyResultPageByIds() {
        ListAccountsCmd cmd = cmdWithDefaults();
        AccountVO accountOne = mock(AccountVO.class);
        when(accountOne.getId()).thenReturn(101L);
        AccountVO accountTwo = mock(AccountVO.class);
        when(accountTwo.getId()).thenReturn(102L);
        when(accountDao.searchAndCount(eq(accountSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Arrays.asList(accountOne, accountTwo), 2));
        AccountJoinVO rowOne = mock(AccountJoinVO.class);
        AccountJoinVO rowTwo = mock(AccountJoinVO.class);
        when(accountJoinDao.searchByIds(any(Long[].class))).thenReturn(Arrays.asList(rowOne, rowTwo));

        Pair<List<AccountJoinVO>, Integer> result = service.searchForAccountsInternal(cmd);

        assertEquals(Integer.valueOf(2), result.second());
        assertEquals(2, result.first().size());
        assertSame(rowOne, result.first().get(0));
        assertSame(rowTwo, result.first().get(1));
        verify(accountJoinDao).searchByIds(any(Long[].class));
    }
}
