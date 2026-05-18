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

import java.util.Arrays;
import java.util.Collections;

import org.apache.cloudstack.acl.Role;
import org.apache.cloudstack.acl.RoleService;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.acl.ApiKeyPairVO;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.acl.dao.ApiKeyPairDao;
import org.apache.cloudstack.api.command.admin.account.UpdateAccountCmd;
import org.apache.cloudstack.api.command.admin.user.UpdateUserCmd;
import org.apache.cloudstack.auth.UserAuthenticator;
import org.apache.cloudstack.auth.UserAuthenticator.ActionOnFailedAuthentication;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.resourcedetail.dao.UserDetailsDao;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class UserUpdateServiceImplTest {

    private UserUpdateServiceImpl service;

    @Mock
    private UserDao userDao;
    @Mock
    private AccountDao accountDao;
    @Mock
    private ApiKeyPairDao apiKeyPairDao;
    @Mock
    private UserDetailsDao userDetailsDao;
    @Mock
    private RoleService roleService;
    @Mock
    private PasswordPolicy passwordPolicy;
    @Mock
    private DomainDao domainDao;
    @Mock
    private AccountService accountService;
    @Mock
    private AccountManager accountManager;
    @Mock
    private UserAuthenticator userAuthenticator;
    @Mock
    private User callingUser;
    @Mock
    private Account callingAccount;
    @Mock
    private UserVO userVoMock;
    @Mock
    private AccountVO accountVoMock;
    @Mock
    private UpdateUserCmd updateUserCmdMock;
    @Mock
    private UpdateAccountCmd updateAccountCmdMock;

    @Before
    public void setUp() {
        service = new UserUpdateServiceImpl();
        ReflectionTestUtils.setField(service, "userDao", userDao);
        ReflectionTestUtils.setField(service, "accountDao", accountDao);
        ReflectionTestUtils.setField(service, "apiKeyPairDao", apiKeyPairDao);
        ReflectionTestUtils.setField(service, "userDetailsDao", userDetailsDao);
        ReflectionTestUtils.setField(service, "roleService", roleService);
        ReflectionTestUtils.setField(service, "passwordPolicy", passwordPolicy);
        ReflectionTestUtils.setField(service, "domainDao", domainDao);
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "accountManager", accountManager);
        service.setUserPasswordEncoders(Arrays.asList(userAuthenticator));
        CallContext.register(callingUser, callingAccount);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // -------------------------------------------------------------------------
    // retrieveAndValidateUser
    // -------------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void retrieveAndValidateUser_throwsWhenUserMissing() {
        Mockito.when(updateUserCmdMock.getId()).thenReturn(42L);
        Mockito.when(userDao.getUser(42L)).thenReturn(null);
        service.retrieveAndValidateUser(updateUserCmdMock);
    }

    @Test
    public void retrieveAndValidateUser_returnsUserWhenFound() {
        Mockito.when(updateUserCmdMock.getId()).thenReturn(42L);
        Mockito.when(userDao.getUser(42L)).thenReturn(userVoMock);
        UserVO result = service.retrieveAndValidateUser(updateUserCmdMock);
        Assert.assertEquals(userVoMock, result);
    }

    // -------------------------------------------------------------------------
    // retrieveAndValidateAccount
    // -------------------------------------------------------------------------

    @Test(expected = CloudRuntimeException.class)
    public void retrieveAndValidateAccount_throwsWhenAccountNull() {
        Mockito.when(userVoMock.getAccountId()).thenReturn(1L);
        Mockito.when(accountDao.findById(1L)).thenReturn(null);
        service.retrieveAndValidateAccount(userVoMock);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void retrieveAndValidateAccount_throwsWhenProjectType() {
        Mockito.when(userVoMock.getAccountId()).thenReturn(1L);
        AccountVO projAccount = Mockito.mock(AccountVO.class);
        Mockito.when(projAccount.getType()).thenReturn(Account.Type.PROJECT);
        Mockito.when(accountDao.findById(1L)).thenReturn(projAccount);
        service.retrieveAndValidateAccount(userVoMock);
    }

    @Test(expected = PermissionDeniedException.class)
    public void retrieveAndValidateAccount_throwsWhenSystemAccount() {
        Mockito.when(userVoMock.getAccountId()).thenReturn(Account.ACCOUNT_ID_SYSTEM);
        AccountVO sysAccount = Mockito.mock(AccountVO.class);
        Mockito.when(sysAccount.getType()).thenReturn(Account.Type.ADMIN);
        Mockito.when(sysAccount.getId()).thenReturn(Account.ACCOUNT_ID_SYSTEM);
        Mockito.when(accountDao.findById(Account.ACCOUNT_ID_SYSTEM)).thenReturn(sysAccount);
        service.retrieveAndValidateAccount(userVoMock);
    }

    @Test
    public void retrieveAndValidateAccount_callsCheckAccessOnSuccess() {
        long accountId = 10L;
        Mockito.when(userVoMock.getAccountId()).thenReturn(accountId);
        Mockito.when(accountVoMock.getType()).thenReturn(Account.Type.NORMAL);
        Mockito.when(accountVoMock.getId()).thenReturn(accountId);
        Mockito.when(accountDao.findById(accountId)).thenReturn(accountVoMock);
        Mockito.doNothing().when(accountService).checkAccess(Mockito.any(Account.class),
                Mockito.eq(AccessType.OperateEntry), Mockito.anyBoolean(), Mockito.any(Account.class));
        Account result = service.retrieveAndValidateAccount(userVoMock);
        Assert.assertEquals(accountVoMock, result);
        Mockito.verify(accountService).checkAccess(Mockito.any(Account.class),
                Mockito.eq(AccessType.OperateEntry), Mockito.anyBoolean(), Mockito.eq(accountVoMock));
    }

    // -------------------------------------------------------------------------
    // validateAndUpdateFirstNameIfNeeded
    // -------------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void validateAndUpdateFirstNameIfNeeded_blank_throws() {
        Mockito.when(updateUserCmdMock.getFirstname()).thenReturn("  ");
        service.validateAndUpdateFirstNameIfNeeded(updateUserCmdMock, userVoMock);
    }

    @Test
    public void validateAndUpdateFirstNameIfNeeded_setsValueWhenValid() {
        Mockito.when(updateUserCmdMock.getFirstname()).thenReturn("Alice");
        service.validateAndUpdateFirstNameIfNeeded(updateUserCmdMock, userVoMock);
        Mockito.verify(userVoMock).setFirstname("Alice");
    }

    // -------------------------------------------------------------------------
    // validateAndUpdateLastNameIfNeeded
    // -------------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void validateAndUpdateLastNameIfNeeded_blank_throws() {
        Mockito.when(updateUserCmdMock.getLastname()).thenReturn("  ");
        service.validateAndUpdateLastNameIfNeeded(updateUserCmdMock, userVoMock);
    }

    @Test
    public void validateAndUpdateLastNameIfNeeded_setsValueWhenValid() {
        Mockito.when(updateUserCmdMock.getLastname()).thenReturn("Smith");
        service.validateAndUpdateLastNameIfNeeded(updateUserCmdMock, userVoMock);
        Mockito.verify(userVoMock).setLastname("Smith");
    }

    // -------------------------------------------------------------------------
    // validateAndUpdateUsernameIfNeeded
    // -------------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void validateAndUpdateUsernameIfNeeded_blank_throws() {
        Mockito.when(updateUserCmdMock.getUsername()).thenReturn("  ");
        service.validateAndUpdateUsernameIfNeeded(updateUserCmdMock, userVoMock, accountVoMock);
    }

    @Test
    public void validateAndUpdateUsernameIfNeeded_setsUsernameOnSuccess() {
        Mockito.when(updateUserCmdMock.getUsername()).thenReturn("alice");
        Mockito.lenient().when(userVoMock.getId()).thenReturn(99L);
        Mockito.when(userDao.findUsersByName("alice")).thenReturn(Collections.emptyList());
        service.validateAndUpdateUsernameIfNeeded(updateUserCmdMock, userVoMock, accountVoMock);
        Mockito.verify(userVoMock).setUsername("alice");
    }

    @Test
    public void validateAndUpdateUsernameIfNeeded_nullUsername_noop() {
        // null username → early return, userDao.findUsersByName never called
        Mockito.when(updateUserCmdMock.getUsername()).thenReturn(null);
        service.validateAndUpdateUsernameIfNeeded(updateUserCmdMock, userVoMock, accountVoMock);
        Mockito.verify(userDao, Mockito.never()).findUsersByName(Mockito.anyString());
        Mockito.verify(userVoMock, Mockito.never()).setUsername(Mockito.anyString());
    }

    // -------------------------------------------------------------------------
    // validateAndUpdateApiAndSecretKeyIfNeeded
    // -------------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void validateAndUpdateApiAndSecretKeyIfNeeded_apiKeyOnly_throws() {
        Mockito.when(updateUserCmdMock.getApiKey()).thenReturn("someKey");
        // secretKey is null → XOR branch
        service.validateAndUpdateApiAndSecretKeyIfNeeded(updateUserCmdMock, userVoMock);
    }

    @Test
    public void validateAndUpdateApiAndSecretKeyIfNeeded_bothBlank_returnsNoop() {
        // Both null → no-op
        service.validateAndUpdateApiAndSecretKeyIfNeeded(updateUserCmdMock, userVoMock);
        Mockito.verify(apiKeyPairDao, Mockito.never()).getLastApiKeyCreatedByUser(Mockito.anyLong());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateAndUpdateApiAndSecretKeyIfNeeded_duplicateApiKey_throws() {
        Mockito.when(updateUserCmdMock.getApiKey()).thenReturn("dupKey");
        Mockito.when(updateUserCmdMock.getSecretKey()).thenReturn("secret");
        Mockito.when(userVoMock.getId()).thenReturn(1L);
        ApiKeyPairVO existingPair = Mockito.mock(ApiKeyPairVO.class);
        Mockito.when(apiKeyPairDao.getLastApiKeyCreatedByUser(1L)).thenReturn(existingPair);
        Ternary<User, Account, ApiKeyPair> found = new Ternary<>(Mockito.mock(User.class),
                Mockito.mock(Account.class), Mockito.mock(ApiKeyPair.class));
        Mockito.when(accountManager.findUserByApiKey("dupKey")).thenReturn(found);
        service.validateAndUpdateApiAndSecretKeyIfNeeded(updateUserCmdMock, userVoMock);
    }

    @Test
    public void validateAndUpdateApiAndSecretKeyIfNeeded_updatesKeyPair_onSuccess() {
        String apiKey = "newKey";
        String secretKey = "newSecret";
        Mockito.when(updateUserCmdMock.getApiKey()).thenReturn(apiKey);
        Mockito.when(updateUserCmdMock.getSecretKey()).thenReturn(secretKey);
        Mockito.when(userVoMock.getId()).thenReturn(1L);
        ApiKeyPairVO keyPair = Mockito.mock(ApiKeyPairVO.class);
        Mockito.when(keyPair.getId()).thenReturn(10L);
        Mockito.when(apiKeyPairDao.getLastApiKeyCreatedByUser(1L)).thenReturn(keyPair);
        Mockito.when(accountManager.findUserByApiKey(apiKey)).thenReturn(null);
        service.validateAndUpdateApiAndSecretKeyIfNeeded(updateUserCmdMock, userVoMock);
        Mockito.verify(keyPair).setApiKey(apiKey);
        Mockito.verify(keyPair).setSecretKey(secretKey);
        Mockito.verify(apiKeyPairDao).update(10L, keyPair);
    }

    // -------------------------------------------------------------------------
    // validateAndUpdateUserApiKeyAccess
    // -------------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void validateAndUpdateUserApiKeyAccess_invalidValue_throws() {
        Mockito.when(updateUserCmdMock.getApiKeyAccess()).thenReturn("SomethingInvalid");
        service.validateAndUpdateUserApiKeyAccess(updateUserCmdMock, userVoMock);
    }

    @Test
    public void validateAndUpdateUserApiKeyAccess_enabled_setsTrue() {
        Mockito.when(updateUserCmdMock.getApiKeyAccess()).thenReturn("Enabled");
        Mockito.lenient().when(userVoMock.getId()).thenReturn(7L);
        Mockito.lenient().when(callingUser.getId()).thenReturn(3L);
        Mockito.lenient().when(callingAccount.getAccountId()).thenReturn(2L);
        Mockito.lenient().when(callingAccount.getDomainId()).thenReturn(1L);
        try (MockedStatic<com.cloud.event.ActionEventUtils> utils =
                Mockito.mockStatic(com.cloud.event.ActionEventUtils.class)) {
            utils.when(() -> com.cloud.event.ActionEventUtils.onActionEvent(
                    Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(),
                    Mockito.anyString(), Mockito.anyString(),
                    Mockito.anyLong(), Mockito.anyString())).thenReturn(1L);
            service.validateAndUpdateUserApiKeyAccess(updateUserCmdMock, userVoMock);
        }
        Mockito.verify(userVoMock).setApiKeyAccess(true);
    }

    // -------------------------------------------------------------------------
    // validateAndUpdateAccountApiKeyAccess
    // -------------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void validateAndUpdateAccountApiKeyAccess_invalidValue_throws() {
        Mockito.when(updateAccountCmdMock.getApiKeyAccess()).thenReturn("SomethingBad");
        service.validateAndUpdateAccountApiKeyAccess(updateAccountCmdMock, accountVoMock);
    }

    @Test
    public void validateAndUpdateAccountApiKeyAccess_inherit_setsNull() {
        Mockito.when(updateAccountCmdMock.getApiKeyAccess()).thenReturn("Inherit");
        Mockito.lenient().when(accountVoMock.getId()).thenReturn(8L);
        Mockito.lenient().when(callingUser.getId()).thenReturn(3L);
        Mockito.lenient().when(callingAccount.getAccountId()).thenReturn(2L);
        Mockito.lenient().when(callingAccount.getDomainId()).thenReturn(1L);
        try (MockedStatic<com.cloud.event.ActionEventUtils> utils =
                Mockito.mockStatic(com.cloud.event.ActionEventUtils.class)) {
            utils.when(() -> com.cloud.event.ActionEventUtils.onActionEvent(
                    Mockito.anyLong(), Mockito.anyLong(), Mockito.anyLong(),
                    Mockito.anyString(), Mockito.anyString(),
                    Mockito.anyLong(), Mockito.anyString())).thenReturn(1L);
            service.validateAndUpdateAccountApiKeyAccess(updateAccountCmdMock, accountVoMock);
        }
        Mockito.verify(accountVoMock).setApiKeyAccess(null);
    }

    // -------------------------------------------------------------------------
    // validateCurrentPassword
    // -------------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void validateCurrentPassword_wrongPassword_throws() {
        long accountId = 5L;
        long domainId = 2L;
        Mockito.when(userVoMock.getAccountId()).thenReturn(accountId);
        Mockito.when(userVoMock.getUsername()).thenReturn("user");
        AccountVO acct = Mockito.mock(AccountVO.class);
        Mockito.when(acct.getDomainId()).thenReturn(domainId);
        Mockito.when(accountDao.findById(accountId)).thenReturn(acct);
        Pair<Boolean, ActionOnFailedAuthentication> failResult = new Pair<>(false,
                ActionOnFailedAuthentication.INCREMENT_INCORRECT_LOGIN_ATTEMPT_COUNT);
        Mockito.when(userAuthenticator.authenticate("user", "wrongpwd", domainId, null)).thenReturn(failResult);
        service.validateCurrentPassword(userVoMock, "wrongpwd");
    }

    @Test
    public void validateCurrentPassword_correctPassword_doesNotThrow() {
        long accountId = 5L;
        long domainId = 2L;
        Mockito.when(userVoMock.getAccountId()).thenReturn(accountId);
        Mockito.when(userVoMock.getUsername()).thenReturn("user");
        AccountVO acct = Mockito.mock(AccountVO.class);
        Mockito.when(acct.getDomainId()).thenReturn(domainId);
        Mockito.when(accountDao.findById(accountId)).thenReturn(acct);
        Pair<Boolean, ActionOnFailedAuthentication> okResult = new Pair<>(true,
                ActionOnFailedAuthentication.INCREMENT_INCORRECT_LOGIN_ATTEMPT_COUNT);
        Mockito.when(userAuthenticator.authenticate("user", "correctpwd", domainId, null)).thenReturn(okResult);
        service.validateCurrentPassword(userVoMock, "correctpwd"); // should not throw
    }

    // -------------------------------------------------------------------------
    // validateAndUpdatePasswordChangeRequired
    // -------------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void validateAndUpdatePasswordChangeRequired_SAMLUser_throws() {
        Mockito.when(updateUserCmdMock.isPasswordChangeRequired()).thenReturn(true);
        Mockito.when(userVoMock.getState()).thenReturn(Account.State.ENABLED);
        Mockito.when(accountVoMock.getState()).thenReturn(Account.State.ENABLED);
        Mockito.when(userVoMock.getSource()).thenReturn(User.Source.SAML2);
        service.validateAndUpdatePasswordChangeRequired(callingUser, updateUserCmdMock, userVoMock, accountVoMock);
    }

    @Test(expected = CloudRuntimeException.class)
    public void validateAndUpdatePasswordChangeRequired_lockedUser_throws() {
        Mockito.when(updateUserCmdMock.isPasswordChangeRequired()).thenReturn(true);
        Mockito.when(userVoMock.getState()).thenReturn(Account.State.DISABLED);
        service.validateAndUpdatePasswordChangeRequired(callingUser, updateUserCmdMock, userVoMock, accountVoMock);
    }

    @Test
    public void validateAndUpdatePasswordChangeRequired_adminEnforces_addDetail() {
        long callerId = 1L;
        long userId = 2L;
        Mockito.when(callingUser.getId()).thenReturn(callerId);
        Mockito.when(callingUser.getAccountId()).thenReturn(10L);
        Mockito.when(userVoMock.getId()).thenReturn(userId);
        Mockito.when(userVoMock.getState()).thenReturn(Account.State.ENABLED);
        Mockito.when(accountVoMock.getState()).thenReturn(Account.State.ENABLED);
        Mockito.when(userVoMock.getSource()).thenReturn(User.Source.UNKNOWN);
        Mockito.when(updateUserCmdMock.isPasswordChangeRequired()).thenReturn(true);
        Mockito.when(accountService.isRootAdmin(10L)).thenReturn(true);
        service.validateAndUpdatePasswordChangeRequired(callingUser, updateUserCmdMock, userVoMock, accountVoMock);
        Mockito.verify(userDetailsDao).addDetail(Mockito.eq(userId), Mockito.anyString(),
                Mockito.anyString(), Mockito.anyBoolean());
    }

    // -------------------------------------------------------------------------
    // validateRoleChange
    // -------------------------------------------------------------------------

    @Test
    public void validateRoleChange_sameRole_noop() {
        long roleId = 5L;
        Account account = Mockito.mock(Account.class);
        Mockito.when(account.getRoleId()).thenReturn(roleId);
        Role role = Mockito.mock(Role.class);
        Mockito.when(role.getId()).thenReturn(roleId);
        Account caller = Mockito.mock(Account.class);
        service.validateRoleChange(account, role, caller); // no exception
        Mockito.verify(roleService, Mockito.never()).findRole(Mockito.anyLong());
    }

    @Test(expected = PermissionDeniedException.class)
    public void validateRoleChange_callerUnknownRole_throws() {
        Account account = Mockito.mock(Account.class);
        Mockito.when(account.getRoleId()).thenReturn(1L);
        Role newRole = Mockito.mock(Role.class);
        Mockito.when(newRole.getId()).thenReturn(99L);
        Account caller = Mockito.mock(Account.class);
        Mockito.when(caller.getRoleId()).thenReturn(2L);
        Role callerRole = Mockito.mock(Role.class);
        Mockito.when(callerRole.getRoleType()).thenReturn(RoleType.Unknown);
        Mockito.when(roleService.findRole(2L)).thenReturn(callerRole);
        service.validateRoleChange(account, newRole, caller);
    }

    @Test(expected = PermissionDeniedException.class)
    public void validateRoleChange_newRoleUnknown_throws() {
        Account account = Mockito.mock(Account.class);
        Mockito.when(account.getRoleId()).thenReturn(1L);
        Role newRole = Mockito.mock(Role.class);
        Mockito.when(newRole.getId()).thenReturn(99L);
        Mockito.when(newRole.getRoleType()).thenReturn(RoleType.Unknown);
        Account caller = Mockito.mock(Account.class);
        Mockito.when(caller.getRoleId()).thenReturn(2L);
        Role callerRole = Mockito.mock(Role.class);
        Mockito.when(callerRole.getRoleType()).thenReturn(RoleType.DomainAdmin);
        Mockito.when(roleService.findRole(2L)).thenReturn(callerRole);
        service.validateRoleChange(account, newRole, caller);
    }

    @Test(expected = PermissionDeniedException.class)
    public void validateRoleChange_adminRoleNotInRootDomain_throws() {
        Account account = Mockito.mock(Account.class);
        Mockito.when(account.getRoleId()).thenReturn(1L);
        Mockito.when(account.getDomainId()).thenReturn(2L); // non-root domain
        Role currentRole = Mockito.mock(Role.class);
        Mockito.lenient().when(currentRole.getRoleType()).thenReturn(RoleType.User);
        Role newRole = Mockito.mock(Role.class);
        Mockito.when(newRole.getId()).thenReturn(99L);
        Mockito.when(newRole.getRoleType()).thenReturn(RoleType.Admin);
        Mockito.when(newRole.getName()).thenReturn("Admin");
        Account caller = Mockito.mock(Account.class);
        Mockito.when(caller.getRoleId()).thenReturn(2L);
        Role callerRole = Mockito.mock(Role.class);
        Mockito.when(callerRole.getRoleType()).thenReturn(RoleType.Admin);
        Mockito.lenient().when(roleService.findRole(1L)).thenReturn(currentRole);
        Mockito.when(roleService.findRole(2L)).thenReturn(callerRole);
        service.validateRoleChange(account, newRole, caller);
    }

    @Test(expected = PermissionDeniedException.class)
    public void validateRoleChange_lowerPrivilege_throws() {
        Account account = Mockito.mock(Account.class);
        Mockito.when(account.getRoleId()).thenReturn(1L);
        Role newRole = Mockito.mock(Role.class);
        Mockito.when(newRole.getId()).thenReturn(99L);
        Mockito.when(newRole.getRoleType()).thenReturn(RoleType.Admin); // caller can't grant Admin
        Mockito.lenient().when(newRole.getName()).thenReturn("Admin");
        Account caller = Mockito.mock(Account.class);
        Mockito.when(caller.getRoleId()).thenReturn(2L);
        Role callerRole = Mockito.mock(Role.class);
        Mockito.when(callerRole.getRoleType()).thenReturn(RoleType.DomainAdmin); // lower than Admin
        Mockito.lenient().when(roleService.findRole(1L)).thenReturn(Mockito.mock(Role.class));
        Mockito.when(roleService.findRole(2L)).thenReturn(callerRole);
        service.validateRoleChange(account, newRole, caller);
    }

    // -------------------------------------------------------------------------
    // getCurrentCallingAccount
    // -------------------------------------------------------------------------

    @Test
    public void getCurrentCallingAccount_returnsCallContextAccount() {
        Account result = service.getCurrentCallingAccount();
        Assert.assertEquals(callingAccount, result);
    }
}
