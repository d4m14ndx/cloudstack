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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.net.InetAddress;
import java.net.URLEncoder;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.auth.UserAuthenticator;
import org.apache.cloudstack.auth.UserAuthenticator.ActionOnFailedAuthentication;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.resourcedetail.dao.UserDetailsDao;
import org.apache.cloudstack.utils.baremetal.BaremetalUtils;
import org.apache.commons.codec.binary.Base64;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.domain.DomainVO;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.exception.CloudAuthenticationException;
import com.cloud.user.dao.UserAccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;

@RunWith(MockitoJUnitRunner.class)
public class UserAuthenticationServiceImplTest {

    private static final String USERNAME = "test-user";
    private static final String PASSWORD = "secret";
    private static final long DOMAIN_ID = 7L;

    private UserAuthenticationServiceImpl service;

    @Mock
    private UserAccountDao userAccountDao;
    @Mock
    private UserDetailsDao userDetailsDao;
    @Mock
    private ConfigurationDao configDao;
    @Mock
    private DomainManager domainManager;
    @Mock
    private AccountService accountService;
    @Mock
    private UserAuthenticator userAuthenticator;
    @Mock
    private Account account;

    private MockedStatic<Transaction> transactionMocked;

    @Before
    public void setUp() {
        service = new UserAuthenticationServiceImpl();
        ReflectionTestUtils.setField(service, "userAccountDao", userAccountDao);
        ReflectionTestUtils.setField(service, "userDetailsDao", userDetailsDao);
        ReflectionTestUtils.setField(service, "configDao", configDao);
        ReflectionTestUtils.setField(service, "domainManager", domainManager);
        ReflectionTestUtils.setField(service, "accountService", accountService);
        service.setUserAuthenticators(Arrays.asList(userAuthenticator));
        service.setAllowedLoginAttempts(5);
        ReflectionTestUtils.setField(service, "validUserLastAuthTimeDurationInMs", 0L);

        transactionMocked = Mockito.mockStatic(Transaction.class);
        transactionMocked.when(() -> Transaction.execute(Mockito.any(TransactionCallbackNoReturn.class)))
                .thenAnswer(invocation -> {
                    TransactionCallbackNoReturn callback = invocation.getArgument(0);
                    callback.doInTransactionWithoutResult(null);
                    return null;
                });
    }

    @After
    public void tearDown() {
        transactionMocked.close();
    }

    @Test
    public void updateLoginAttempts_updatesAttemptsWithoutDisable() {
        UserAccountVO lockedUser = new UserAccountVO();
        lockedUser.setState(Account.State.ENABLED.toString());
        Mockito.when(userAccountDao.lockRow(42L, true)).thenReturn(lockedUser);

        service.updateLoginAttempts(42L, 3, false);

        assertEquals(3, lockedUser.getLoginAttempts());
        assertEquals(Account.State.ENABLED.toString(), lockedUser.getState());
        Mockito.verify(userAccountDao).update(42L, lockedUser);
    }

    @Test
    public void updateLoginAttempts_disablesUserWhenRequested() {
        UserAccountVO lockedUser = new UserAccountVO();
        lockedUser.setState(Account.State.ENABLED.toString());
        Mockito.when(userAccountDao.lockRow(24L, true)).thenReturn(lockedUser);

        service.updateLoginAttempts(24L, 5, true);

        assertEquals(5, lockedUser.getLoginAttempts());
        assertEquals(Account.State.DISABLED.toString(), lockedUser.getState());
        Mockito.verify(userAccountDao).update(24L, lockedUser);
    }

    @Test
    public void updateLoginAttempts_swallowsTransactionFailure() {
        transactionMocked.when(() -> Transaction.execute(Mockito.any(TransactionCallbackNoReturn.class)))
                .thenThrow(new RuntimeException("boom"));
        UserAccountVO user = new UserAccountVO();
        Mockito.when(userAccountDao.findById(9L)).thenReturn(user);

        service.updateLoginAttempts(9L, 2, false);

        Mockito.verify(userAccountDao, Mockito.never()).update(Mockito.anyLong(), Mockito.any(UserAccountVO.class));
    }

    @Test
    public void logoutUser_emitsLogoutEventWhenUserExists() {
        UserAccountVO user = createUserAccount(8L, 12L, "enabled", "enabled", User.Source.UNKNOWN);
        Mockito.when(userAccountDao.findById(8L)).thenReturn(user);

        try (MockedStatic<ActionEventUtils> utils = Mockito.mockStatic(ActionEventUtils.class)) {
            utils.when(() -> ActionEventUtils.onActionEvent(Mockito.anyLong(), Mockito.anyLong(),
                    Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(),
                    Mockito.anyString())).thenReturn(1L);

            service.logoutUser(8L);

            utils.verify(() -> ActionEventUtils.onActionEvent(8L, 12L, DOMAIN_ID, EventTypes.EVENT_USER_LOGOUT,
                    "user has logged out", 8L, ApiCommandResourceType.User.toString()));
        }
    }

    @Test
    public void logoutUser_noEventWhenUserMissing() {
        Mockito.when(userAccountDao.findById(8L)).thenReturn(null);

        try (MockedStatic<ActionEventUtils> utils = Mockito.mockStatic(ActionEventUtils.class)) {
            service.logoutUser(8L);
            utils.verifyNoInteractions();
        }
    }

    @Test
    public void authenticateUser_returnsNullForSystemUser() throws Exception {
        UserAccountVO systemUser = createUserAccount(User.UID_SYSTEM, 12L, "enabled", "enabled", User.Source.UNKNOWN);
        systemUser.setUsername(USERNAME);
        UserAuthenticationServiceImpl serviceSpy = Mockito.spy(service);
        Mockito.doReturn(systemUser).when(serviceSpy).getUserAccount(Mockito.eq(USERNAME), Mockito.eq(PASSWORD),
                Mockito.eq(DOMAIN_ID), Mockito.anyMap());

        UserAccount result = serviceSpy.authenticateUser(USERNAME, PASSWORD, DOMAIN_ID,
                InetAddress.getByName("127.0.0.1"), new HashMap<>());

        assertNull(result);
    }

    @Test
    public void authenticateUser_returnsNullForBaremetalSystemUser() throws Exception {
        UserAccountVO systemUser = createUserAccount(3L, 12L, "enabled", "enabled", User.Source.UNKNOWN);
        systemUser.setUsername(BaremetalUtils.BAREMETAL_SYSTEM_ACCOUNT_NAME);
        UserAuthenticationServiceImpl serviceSpy = Mockito.spy(service);
        Mockito.doReturn(systemUser).when(serviceSpy).getUserAccount(Mockito.eq(USERNAME), Mockito.eq(PASSWORD),
                Mockito.eq(DOMAIN_ID), Mockito.anyMap());

        UserAccount result = serviceSpy.authenticateUser(USERNAME, PASSWORD, DOMAIN_ID,
                InetAddress.getByName("127.0.0.1"), new HashMap<>());

        assertNull(result);
    }

    @Test
    public void authenticateUser_addsDetailsAndReturnsUserOnSuccessfulPasswordAuth() throws Exception {
        UserAccountVO user = createUserAccount(11L, 12L, "enabled", "enabled", User.Source.UNKNOWN);
        user.setUsername(USERNAME);
        UserAuthenticationServiceImpl serviceSpy = Mockito.spy(service);
        Mockito.doReturn(user).when(serviceSpy).getUserAccount(Mockito.eq(USERNAME), Mockito.eq(PASSWORD),
                Mockito.eq(DOMAIN_ID), Mockito.anyMap());
        mockSuccessfulLoginLookups(user.getAccountId());
        Map<String, String> details = Collections.singletonMap("timezone", "UTC");
        Mockito.when(userDetailsDao.listDetailsKeyPairs(11L)).thenReturn(details);

        try (MockedStatic<ActionEventUtils> utils = Mockito.mockStatic(ActionEventUtils.class)) {
            utils.when(() -> ActionEventUtils.onActionEvent(Mockito.anyLong(), Mockito.anyLong(),
                    Mockito.anyLong(), Mockito.anyString(), Mockito.anyString(), Mockito.anyLong(),
                    Mockito.anyString())).thenReturn(1L);

            UserAccount result = serviceSpy.authenticateUser(USERNAME, PASSWORD, DOMAIN_ID,
                    InetAddress.getByName("127.0.0.1"), new HashMap<>());

            assertSame(user, result);
            assertEquals(details, result.getDetails());
            assertTrue((Long) ReflectionTestUtils.getField(serviceSpy, "validUserLastAuthTimeDurationInMs") >= 0L);
        }
    }

    @Test
    public void authenticateUser_sleepsAndReturnsNullOnFailedAuth() throws Exception {
        UserAuthenticationServiceImpl serviceSpy = Mockito.spy(service);
        Mockito.doReturn(null).when(serviceSpy).getUserAccount(Mockito.eq(USERNAME), Mockito.eq(PASSWORD),
                Mockito.eq(DOMAIN_ID), Mockito.anyMap());
        ReflectionTestUtils.setField(serviceSpy, "validUserLastAuthTimeDurationInMs", 20L);

        long start = System.currentTimeMillis();
        UserAccount result = serviceSpy.authenticateUser(USERNAME, PASSWORD, DOMAIN_ID,
                InetAddress.getByName("127.0.0.1"), new HashMap<>());
        long elapsed = System.currentTimeMillis() - start;

        assertNull(result);
        assertTrue(elapsed >= 0L);
    }

    @Test
    public void getUserAccount_resetsAttemptsForSuccessfulExternalUser() {
        UserAccountVO cachedUser = createUserAccount(31L, 12L, "enabled", "enabled", User.Source.UNKNOWN);
        cachedUser.setLoginAttempts(3);
        UserAccountVO refreshedUser = createUserAccount(31L, 12L, "enabled", "enabled", User.Source.UNKNOWN);
        UserAccountVO lockedUser = createUserAccount(31L, 12L, "enabled", "enabled", User.Source.UNKNOWN);
        mockedAccountLookupForSuccess(cachedUser, refreshedUser);
        Mockito.when(userAccountDao.lockRow(31L, true)).thenReturn(lockedUser);
        Mockito.when(userAuthenticator.getName()).thenReturn("test");
        Mockito.when(userAuthenticator.authenticate(USERNAME, PASSWORD, DOMAIN_ID, Collections.emptyMap()))
                .thenReturn(new Pair<>(true, null));
        Mockito.when(accountService.getActiveAccountById(12L)).thenReturn(account);
        Mockito.when(accountService.isRootAdmin(12L)).thenReturn(false);
        Mockito.when(account.getType()).thenReturn(Account.Type.NORMAL);

        UserAccount result = service.getUserAccount(USERNAME, PASSWORD, DOMAIN_ID, Collections.emptyMap());

        assertSame(refreshedUser, result);
        Mockito.verify(userAccountDao).update(31L, lockedUser);
        assertEquals(0, lockedUser.getLoginAttempts());
    }

    @Test
    public void getUserAccount_skipsResetForInternalUser() {
        UserAccountVO cachedUser = createUserAccount(32L, 12L, "enabled", "enabled", User.Source.UNKNOWN);
        UserAccountVO refreshedUser = createUserAccount(32L, 12L, "enabled", "enabled", User.Source.UNKNOWN);
        mockedAccountLookupForSuccess(cachedUser, refreshedUser);
        Mockito.when(userAuthenticator.getName()).thenReturn("test");
        Mockito.when(userAuthenticator.authenticate(USERNAME, PASSWORD, DOMAIN_ID, Collections.emptyMap()))
                .thenReturn(new Pair<>(true, null));
        Mockito.when(accountService.getActiveAccountById(12L)).thenReturn(account);
        Mockito.when(accountService.isRootAdmin(12L)).thenReturn(true);

        UserAccount result = service.getUserAccount(USERNAME, PASSWORD, DOMAIN_ID, Collections.emptyMap());

        assertSame(refreshedUser, result);
        Mockito.verify(userAccountDao, Mockito.never()).update(Mockito.anyLong(), Mockito.any(UserAccountVO.class));
    }

    @Test
    public void getUserAccount_throwsWhenAuthenticatedUserOrAccountDisabled() {
        UserAccountVO cachedUser = createUserAccount(33L, 12L, "enabled", "enabled", User.Source.UNKNOWN);
        UserAccountVO refreshedUser = createUserAccount(33L, 12L, "disabled", "enabled", User.Source.UNKNOWN);
        mockedAccountLookupForSuccess(cachedUser, refreshedUser);
        Mockito.when(userAuthenticator.getName()).thenReturn("test");
        Mockito.when(userAuthenticator.authenticate(USERNAME, PASSWORD, DOMAIN_ID, Collections.emptyMap()))
                .thenReturn(new Pair<>(true, null));
        Mockito.when(domainManager.getDomain(DOMAIN_ID)).thenReturn(new DomainVO());

        assertThrows(CloudAuthenticationException.class,
                () -> service.getUserAccount(USERNAME, PASSWORD, DOMAIN_ID, Collections.emptyMap()));
    }

    @Test
    public void getUserAccount_returnsNullAndIncrementsAttemptsOnFailedAuth() {
        UserAccountVO user = createUserAccount(34L, 12L, "enabled", "enabled", User.Source.UNKNOWN);
        user.setLoginAttempts(1);
        UserAccountVO lockedUser = createUserAccount(34L, 12L, "enabled", "enabled", User.Source.UNKNOWN);
        lockedUser.setLoginAttempts(1);
        Mockito.when(userAccountDao.getUserAccount(USERNAME, DOMAIN_ID)).thenReturn(user);
        Mockito.when(userAccountDao.lockRow(34L, true)).thenReturn(lockedUser);
        Mockito.when(userAuthenticator.getName()).thenReturn("test");
        Mockito.when(userAuthenticator.authenticate(USERNAME, PASSWORD, DOMAIN_ID, Collections.emptyMap()))
                .thenReturn(new Pair<>(false, ActionOnFailedAuthentication.INCREMENT_INCORRECT_LOGIN_ATTEMPT_COUNT));
        Mockito.when(accountService.getActiveAccountById(12L)).thenReturn(account);
        Mockito.when(accountService.isRootAdmin(12L)).thenReturn(false);
        Mockito.when(account.getType()).thenReturn(Account.Type.NORMAL);

        UserAccount result = service.getUserAccount(USERNAME, PASSWORD, DOMAIN_ID, Collections.emptyMap());

        assertNull(result);
        Mockito.verify(userAccountDao).update(34L, lockedUser);
        assertEquals(2, lockedUser.getLoginAttempts());
    }

    @Test
    public void getUserAccountForSSO_returnsNullWhenKeyMissing() {
        Mockito.when(configDao.getValue("security.singlesignon.key")).thenReturn(null);

        assertNull(service.getUserAccountForSSO(USERNAME, DOMAIN_ID, signedRequest("ignored", null)));
    }

    @Test
    public void getUserAccountForSSO_returnsNullWhenTimestampOutsideTolerance() {
        Mockito.when(configDao.getValue("security.singlesignon.key")).thenReturn("key");
        Mockito.when(configDao.getValue("security.singlesignon.tolerance.millis")).thenReturn("1");
        Map<String, Object[]> requestParameters = signedRequest("key",
                String.valueOf(System.currentTimeMillis() - 5000L));

        assertNull(service.getUserAccountForSSO(USERNAME, DOMAIN_ID, requestParameters));
    }

    @Test
    public void getUserAccountForSSO_returnsUserWhenSignatureMatches() {
        String ssoKey = "shared-secret";
        String timestamp = String.valueOf(System.currentTimeMillis());
        Map<String, Object[]> requestParameters = signedRequest(ssoKey, timestamp);
        UserAccountVO user = createUserAccount(35L, 12L, "enabled", "enabled", User.Source.UNKNOWN);
        Mockito.when(configDao.getValue("security.singlesignon.key")).thenReturn(ssoKey);
        Mockito.when(configDao.getValue("security.singlesignon.tolerance.millis")).thenReturn("10000");
        Mockito.when(userAccountDao.getUserAccount(USERNAME, DOMAIN_ID)).thenReturn(user);

        UserAccount result = service.getUserAccountForSSO(USERNAME, DOMAIN_ID, requestParameters);

        assertSame(user, result);
    }

    private void mockedAccountLookupForSuccess(UserAccountVO cachedUser, UserAccountVO refreshedUser) {
        Mockito.when(userAccountDao.getUserAccount(USERNAME, DOMAIN_ID)).thenReturn(cachedUser, refreshedUser);
    }

    private void mockSuccessfulLoginLookups(long accountId) {
        Mockito.when(accountService.getAccount(accountId)).thenReturn(account);
        Mockito.when(account.getDomainId()).thenReturn(DOMAIN_ID);
        DomainVO domain = new DomainVO();
        domain.setPath("ROOT/test");
        Mockito.when(domainManager.getDomain(DOMAIN_ID)).thenReturn(domain);
        Mockito.when(account.toString()).thenReturn("account");
    }

    private UserAccountVO createUserAccount(long userId, long accountId, String state, String accountState,
            User.Source source) {
        UserAccountVO user = new UserAccountVO();
        user.setId(userId);
        user.setAccountId(accountId);
        user.setDomainId(DOMAIN_ID);
        user.setState(state);
        user.setAccountState(accountState);
        user.setSource(source);
        user.setUsername(USERNAME);
        return user;
    }

    private Map<String, Object[]> signedRequest(String key, String timestamp) {
        Map<String, Object[]> request = new LinkedHashMap<>();
        request.put("username", new String[] {USERNAME});
        request.put("domainid", new String[] {String.valueOf(DOMAIN_ID)});
        if (timestamp != null) {
            request.put("timestamp", new String[] {timestamp});
        }
        String actualTimestamp = timestamp;
        if (actualTimestamp == null) {
            actualTimestamp = String.valueOf(System.currentTimeMillis());
            request.put("timestamp", new String[] {actualTimestamp});
        }
        if (key != null) {
            request.put("signature", new String[] {signRequest(key, request)});
        }
        return request;
    }

    private String signRequest(String key, Map<String, Object[]> requestParameters) {
        try {
            StringBuilder unsigned = new StringBuilder();
            for (String paramName : requestParameters.keySet().stream().sorted().toList()) {
                if ("signature".equalsIgnoreCase(paramName)) {
                    continue;
                }
                if (unsigned.length() > 0) {
                    unsigned.append("&");
                }
                unsigned.append(paramName)
                        .append("=")
                        .append(URLEncoder.encode(((String[]) requestParameters.get(paramName))[0],
                                com.cloud.utils.StringUtils.getPreferredCharset()));
            }
            Mac mac = Mac.getInstance("HmacSHA1");
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "HmacSHA1");
            mac.init(keySpec);
            mac.update(unsigned.toString().toLowerCase().replaceAll("\\+", "%20").getBytes());
            return new String(Base64.encodeBase64(mac.doFinal()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
