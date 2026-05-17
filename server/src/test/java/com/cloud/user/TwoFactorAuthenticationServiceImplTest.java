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
package com.cloud.user;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.auth.UserTwoFactorAuthenticator;
import org.apache.cloudstack.resourcedetail.UserDetailVO;
import org.apache.cloudstack.resourcedetail.dao.UserDetailsDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.user.dao.UserAccountDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class TwoFactorAuthenticationServiceImplTest {

    @Mock private UserAccountDao userAccountDao;
    @Mock private UserDetailsDao userDetailsDao;
    @Mock private AccountService accountService;

    @InjectMocks
    private TwoFactorAuthenticationServiceImpl service;

    private Map<String, UserTwoFactorAuthenticator> savedProvidersMap;
    private MockedStatic<Transaction> transactionMocked;

    @Before
    public void setUp() {
        // Snapshot the static provider map so each test runs in isolation.
        savedProvidersMap = AccountManagerImpl.userTwoFactorAuthenticationProvidersMap;
        AccountManagerImpl.userTwoFactorAuthenticationProvidersMap = new HashMap<>();

        // Run the transaction body inline so tests exercise the real callback logic.
        transactionMocked = Mockito.mockStatic(Transaction.class);
        transactionMocked.when(() -> Transaction.execute(Mockito.any(TransactionCallback.class)))
                .thenAnswer(inv -> {
                    TransactionCallback<?> cb = inv.getArgument(0);
                    return cb.doInTransaction(null);
                });
    }

    @After
    public void tearDown() {
        AccountManagerImpl.userTwoFactorAuthenticationProvidersMap = savedProvidersMap;
        transactionMocked.close();
    }

    // ---- listUserTwoFactorAuthenticationProviders ----

    @Test
    public void listUserTwoFactorAuthenticationProvidersReturnsConfiguredList() {
        UserTwoFactorAuthenticator totp = mock(UserTwoFactorAuthenticator.class);
        UserTwoFactorAuthenticator pin = mock(UserTwoFactorAuthenticator.class);
        List<UserTwoFactorAuthenticator> providers = Arrays.asList(totp, pin);
        service.setUserTwoFactorAuthenticationProviders(providers);

        List<UserTwoFactorAuthenticator> result = service.listUserTwoFactorAuthenticationProviders();

        assertNotNull(result);
        assertEquals(2, result.size());
        assertSame(totp, result.get(0));
        assertSame(pin, result.get(1));
    }

    @Test
    public void listUserTwoFactorAuthenticationProvidersReturnsNullWhenUnset() {
        assertNull(service.listUserTwoFactorAuthenticationProviders());
    }

    // ---- initializeUserTwoFactorAuthenticationProvidersMap ----

    @Test
    public void initializeUserTwoFactorAuthenticationProvidersMapPopulatesStaticMap() {
        UserTwoFactorAuthenticator totp = mock(UserTwoFactorAuthenticator.class);
        when(totp.getName()).thenReturn("TOTP");
        UserTwoFactorAuthenticator pin = mock(UserTwoFactorAuthenticator.class);
        when(pin.getName()).thenReturn("StaticPin");
        service.setUserTwoFactorAuthenticationProviders(Arrays.asList(totp, pin));

        service.initializeUserTwoFactorAuthenticationProvidersMap();

        // Lowercased keys stored on the shared static map.
        assertSame(totp, AccountManagerImpl.userTwoFactorAuthenticationProvidersMap.get("totp"));
        assertSame(pin, AccountManagerImpl.userTwoFactorAuthenticationProvidersMap.get("staticpin"));
    }

    @Test
    public void initializeUserTwoFactorAuthenticationProvidersMapHandlesNullList() {
        // Should not throw when no providers have been wired yet.
        service.initializeUserTwoFactorAuthenticationProvidersMap();
        assertTrue(AccountManagerImpl.userTwoFactorAuthenticationProvidersMap.isEmpty());
    }

    // ---- getUserTwoFactorAuthenticationProvider(name) ----

    @Test
    public void getUserTwoFactorAuthenticationProviderByNameReturnsRegisteredProvider() {
        UserTwoFactorAuthenticator totp = mock(UserTwoFactorAuthenticator.class);
        AccountManagerImpl.userTwoFactorAuthenticationProvidersMap.put("totp", totp);

        // Lookup is case-insensitive.
        assertSame(totp, service.getUserTwoFactorAuthenticationProvider("TOTP"));
        assertSame(totp, service.getUserTwoFactorAuthenticationProvider("totp"));
    }

    @Test
    public void getUserTwoFactorAuthenticationProviderByNameThrowsOnEmpty() {
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.getUserTwoFactorAuthenticationProvider(""));
        assertTrue(ex.getMessage().contains("empty"));
    }

    @Test
    public void getUserTwoFactorAuthenticationProviderByNameThrowsOnUnknown() {
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.getUserTwoFactorAuthenticationProvider("notreal"));
        assertTrue(ex.getMessage().contains("Failed to find"));
        assertTrue(ex.getMessage().contains("notreal"));
    }

    // ---- getUserTwoFactorAuthenticator(name) ----

    @Test
    public void getUserTwoFactorAuthenticatorByNameThrowsOnEmpty() {
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.getUserTwoFactorAuthenticator(""));
        // Uses the legacy "UserTwoFactorAuthenticator" wording (vs the
        // "two factor authentication provider" wording on the other lookup).
        assertTrue(ex.getMessage().contains("UserTwoFactorAuthenticator"));
        assertTrue(ex.getMessage().contains("empty"));
    }

    @Test
    public void getUserTwoFactorAuthenticatorByNameThrowsOnUnknown() {
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.getUserTwoFactorAuthenticator("notreal"));
        assertTrue(ex.getMessage().contains("UserTwoFactorAuthenticator"));
        assertTrue(ex.getMessage().contains("notreal"));
    }

    @Test
    public void getUserTwoFactorAuthenticatorByNameReturnsRegisteredProvider() {
        UserTwoFactorAuthenticator pin = mock(UserTwoFactorAuthenticator.class);
        AccountManagerImpl.userTwoFactorAuthenticationProvidersMap.put("staticpin", pin);

        assertSame(pin, service.getUserTwoFactorAuthenticator("StaticPin"));
    }

    // ---- getUserTwoFactorAuthenticator(domainId, userAccountId) ----

    @Test
    public void getUserTwoFactorAuthenticatorPrefersUserEnrolledProvider() {
        UserTwoFactorAuthenticator pin = mock(UserTwoFactorAuthenticator.class);
        AccountManagerImpl.userTwoFactorAuthenticationProvidersMap.put("staticpin", pin);
        UserAccount user = mock(UserAccount.class);
        when(user.getUser2faProvider()).thenReturn("staticpin");
        when(accountService.getUserAccountById(42L)).thenReturn(user);

        UserTwoFactorAuthenticator result = service.getUserTwoFactorAuthenticator(1L, 42L);

        assertSame(pin, result);
        verify(accountService, times(1)).getUserAccountById(42L);
    }

    @Test
    public void getUserTwoFactorAuthenticatorFallsBackToDomainDefaultWhenUserProviderIsNull() {
        // user.getUser2faProvider() == null → drop into the
        // domain-default branch (which resolves "totp" by config default).
        UserTwoFactorAuthenticator totp = mock(UserTwoFactorAuthenticator.class);
        AccountManagerImpl.userTwoFactorAuthenticationProvidersMap.put("totp", totp);
        UserAccount user = mock(UserAccount.class);
        when(user.getUser2faProvider()).thenReturn(null);
        when(accountService.getUserAccountById(42L)).thenReturn(user);

        UserTwoFactorAuthenticator result = service.getUserTwoFactorAuthenticator(1L, 42L);

        assertSame(totp, result);
    }

    @Test
    public void getUserTwoFactorAuthenticatorUsesDomainDefaultWhenUserAccountIdIsNull() {
        UserTwoFactorAuthenticator totp = mock(UserTwoFactorAuthenticator.class);
        AccountManagerImpl.userTwoFactorAuthenticationProvidersMap.put("totp", totp);

        UserTwoFactorAuthenticator result = service.getUserTwoFactorAuthenticator(1L, null);

        assertSame(totp, result);
        verify(accountService, never()).getUserAccountById(Mockito.anyLong());
    }

    // ---- clearUserTwoFactorAuthenticationInSetupStateOnLogin ----

    @Test
    public void clearUserTwoFactorAuthenticationInSetupStateOnLoginNoOpsForNonEnabledUser() {
        UserAccount user = mock(UserAccount.class);
        when(user.isUser2faEnabled()).thenReturn(false);
        when(user.getUser2faProvider()).thenReturn(null);

        UserAccount result = service.clearUserTwoFactorAuthenticationInSetupStateOnLogin(user);

        assertSame(user, result);
        verify(userDetailsDao, never()).findDetail(Mockito.anyLong(), Mockito.anyString());
        verify(userAccountDao, never()).update(Mockito.anyLong(), Mockito.any(UserAccountVO.class));
    }

    @Test
    public void clearUserTwoFactorAuthenticationInSetupStateOnLoginKeepsVerifiedSetup() {
        // user has 2FA enabled and the setup is VERIFIED → leave alone.
        UserAccount user = mock(UserAccount.class);
        when(user.isUser2faEnabled()).thenReturn(true);
        lenient().when(user.getUser2faProvider()).thenReturn("totp");
        when(user.getId()).thenReturn(7L);

        UserDetailVO detail = mock(UserDetailVO.class);
        when(detail.getValue()).thenReturn(UserAccountVO.Setup2FAstatus.VERIFIED.name());
        when(userDetailsDao.findDetail(7L, UserDetailVO.Setup2FADetail)).thenReturn(detail);

        UserAccount result = service.clearUserTwoFactorAuthenticationInSetupStateOnLogin(user);

        assertSame(user, result);
        verify(userDetailsDao, never()).remove(Mockito.anyLong());
        verify(userAccountDao, never()).update(Mockito.anyLong(), Mockito.any(UserAccountVO.class));
    }

    @Test
    public void clearUserTwoFactorAuthenticationInSetupStateOnLoginClearsEnabledButNotVerifiedSetup() {
        UserAccount user = mock(UserAccount.class);
        when(user.isUser2faEnabled()).thenReturn(true);
        lenient().when(user.getUser2faProvider()).thenReturn("totp");
        when(user.getId()).thenReturn(7L);

        UserDetailVO detail = mock(UserDetailVO.class);
        when(detail.getId()).thenReturn(99L);
        when(detail.getValue()).thenReturn(UserAccountVO.Setup2FAstatus.ENABLED.name());
        when(userDetailsDao.findDetail(7L, UserDetailVO.Setup2FADetail)).thenReturn(detail);

        UserAccountVO userAccountVO = new UserAccountVO();
        userAccountVO.setUser2faEnabled(true);
        userAccountVO.setUser2faProvider("totp");
        userAccountVO.setKeyFor2fa("secret");
        when(userAccountDao.findById(7L)).thenReturn(userAccountVO);

        UserAccount result = service.clearUserTwoFactorAuthenticationInSetupStateOnLogin(user);

        // Detail row removed, 2FA fields wiped, mutated VO returned.
        verify(userDetailsDao, times(1)).remove(99L);
        verify(userAccountDao, times(1)).update(7L, userAccountVO);
        assertSame(userAccountVO, result);
        assertEquals(false, userAccountVO.isUser2faEnabled());
        assertNull(userAccountVO.getUser2faProvider());
        assertNull(userAccountVO.getKeyFor2fa());
    }

    @Test
    public void clearUserTwoFactorAuthenticationInSetupStateOnLoginHandlesMissingDetail() {
        // 2FA-marker fields are set but no Setup2FADetail row exists
        // → still clear the user account (legacy behavior).
        UserAccount user = mock(UserAccount.class);
        when(user.isUser2faEnabled()).thenReturn(true);
        lenient().when(user.getUser2faProvider()).thenReturn("totp");
        when(user.getId()).thenReturn(7L);
        when(userDetailsDao.findDetail(7L, UserDetailVO.Setup2FADetail)).thenReturn(null);

        UserAccountVO userAccountVO = new UserAccountVO();
        userAccountVO.setUser2faEnabled(true);
        userAccountVO.setUser2faProvider("totp");
        userAccountVO.setKeyFor2fa("secret");
        when(userAccountDao.findById(7L)).thenReturn(userAccountVO);

        UserAccount result = service.clearUserTwoFactorAuthenticationInSetupStateOnLogin(user);

        verify(userDetailsDao, never()).remove(Mockito.anyLong());
        verify(userAccountDao, times(1)).update(7L, userAccountVO);
        assertSame(userAccountVO, result);
        assertEquals(false, userAccountVO.isUser2faEnabled());
        assertNull(userAccountVO.getUser2faProvider());
        assertNull(userAccountVO.getKeyFor2fa());
    }

    // ---- getUserTwoFactorAuthenticationProviders accessor ----

    @Test
    public void getUserTwoFactorAuthenticationProvidersMirrorsSetterContents() {
        assertNull(service.getUserTwoFactorAuthenticationProviders());
        UserTwoFactorAuthenticator totp = mock(UserTwoFactorAuthenticator.class);
        List<UserTwoFactorAuthenticator> providers = Arrays.asList(totp);

        service.setUserTwoFactorAuthenticationProviders(providers);

        assertSame(providers, service.getUserTwoFactorAuthenticationProviders());
    }
}
