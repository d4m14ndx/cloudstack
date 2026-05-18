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

import static org.apache.cloudstack.resourcedetail.UserDetailVO.PasswordChangeRequired;

import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.Role;
import org.apache.cloudstack.acl.RoleService;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.acl.ApiKeyPairVO;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.acl.dao.ApiKeyPairDao;
import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.account.UpdateAccountCmd;
import org.apache.cloudstack.api.command.admin.user.UpdateUserCmd;
import org.apache.cloudstack.auth.UserAuthenticator;
import org.apache.cloudstack.auth.UserAuthenticator.ActionOnFailedAuthentication;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.resourcedetail.dao.UserDetailsDao;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Validation and update helpers for the updateUser / updateAccount flows —
 * extracted from {@link AccountManagerImpl} as Phase 4 slice 7.
 *
 * @see UserUpdateService
 */
@Component
public class UserUpdateServiceImpl implements UserUpdateService {

    private static final Logger logger = LogManager.getLogger(UserUpdateServiceImpl.class);

    @Inject
    private UserDao userDao;

    @Inject
    private AccountDao accountDao;

    @Inject
    private ApiKeyPairDao apiKeyPairDao;

    @Inject
    private UserDetailsDao userDetailsDao;

    @Inject
    private RoleService roleService;

    @Inject
    private PasswordPolicy passwordPolicy;

    @Inject
    private DomainDao domainDao;

    /** Back-reference — Spring resolves the proxy at application start; no circular-init issue. */
    @Inject
    @Lazy
    private AccountService accountService;

    /** Back-reference for findUserByApiKey — resolved lazily. */
    @Inject
    @Lazy
    private AccountManager accountManager;

    protected List<UserAuthenticator> userPasswordEncoders;

    static ConfigKey<Boolean> userAllowMultipleAccounts = AccountManagerImpl.userAllowMultipleAccounts;

    // -------------------------------------------------------------------------
    // UserUpdateService implementation
    // -------------------------------------------------------------------------

    @Override
    public void validateRoleChange(Account account, Role role, Account caller) {
        if (account.getRoleId() != null && account.getRoleId().equals(role.getId())) {
            return;
        }
        Role currentRole = roleService.findRole(account.getRoleId());
        Role callerRole = roleService.findRole(caller.getRoleId());
        String errorMsg = String.format("Unable to update account role to %s, ", role.getName());
        if (RoleType.Unknown.equals(callerRole.getRoleType())) {
            throw new PermissionDeniedException(String.format("%s as the caller privileges are unknown", errorMsg));
        }
        if (RoleType.Unknown.equals(role.getRoleType())) {
            throw new PermissionDeniedException(String.format("%s as the new role privileges are unknown", errorMsg));
        }
        if (!callerRole.getRoleType().equals(RoleType.Admin) &&
                (role.getRoleType().ordinal() < callerRole.getRoleType().ordinal() ||
                        currentRole.getRoleType().ordinal() < callerRole.getRoleType().ordinal())) {
            throw new PermissionDeniedException(String.format("%s as either current or new role has higher " +
                    "privileges than the caller", errorMsg));
        }
        if (account.isDefault()) {
            throw new PermissionDeniedException(String.format("%s as the account is a default account", errorMsg));
        }
        if (role.getRoleType().equals(RoleType.Admin) && account.getDomainId() != com.cloud.domain.Domain.ROOT_DOMAIN) {
            throw new PermissionDeniedException(String.format("%s as the user does not belong to the ROOT domain",
                    errorMsg));
        }
    }

    @Override
    public void validateUserPasswordAndUpdateIfNeeded(String newPassword, UserVO user,
            String currentPassword, boolean skipCurrentPassValidation) {
        if (newPassword == null) {
            logger.trace("No new password to update for user: {}", user);
            return;
        }
        if (StringUtils.isBlank(newPassword)) {
            throw new InvalidParameterValueException("Password cannot be empty or blank.");
        }

        User.Source userSource = user.getSource();
        if (userSource == User.Source.SAML2 || userSource == User.Source.SAML2DISABLED || userSource == User.Source.LDAP) {
            logger.warn("Unable to update the password for user [{}], as its source is [{}].", user, user.getSource().toString());
            throw new InvalidParameterValueException("CloudStack does not support updating passwords for SAML or LDAP users. Please contact your cloud administrator for assistance.");
        }

        passwordPolicy.verifyIfPasswordCompliesWithPasswordPolicies(newPassword, user.getUsername(), accountService.getAccount(user.getAccountId()).getDomainId());

        Account callingAccount = getCurrentCallingAccount();
        boolean isRootAdminExecutingPasswordUpdate = callingAccount.getId() == Account.ACCOUNT_ID_SYSTEM || accountService.isRootAdmin(callingAccount.getId());
        boolean isDomainAdmin = accountService.isDomainAdmin(callingAccount.getId());
        boolean isAdmin = isDomainAdmin || isRootAdminExecutingPasswordUpdate;
        boolean skipValidation = isAdmin || skipCurrentPassValidation;
        if (isAdmin) {
            logger.trace("Admin account [{}] executing password update for user [{}] ", callingAccount, user);
        }
        if (!skipValidation && StringUtils.isBlank(currentPassword)) {
            throw new InvalidParameterValueException("To set a new password the current password must be provided.");
        }
        if (CollectionUtils.isEmpty(userPasswordEncoders)) {
            throw new CloudRuntimeException("No user authenticators configured!");
        }
        if (!skipValidation) {
            validateCurrentPassword(user, currentPassword);
        }
        UserAuthenticator userAuthenticator = userPasswordEncoders.get(0);
        String newPasswordEncoded = userAuthenticator.encode(newPassword);
        user.setPassword(newPasswordEncoded);
    }

    @Override
    public void validateCurrentPassword(UserVO user, String currentPassword) {
        AccountVO userAccount = accountDao.findById(user.getAccountId());
        boolean currentPasswordMatchesDataBasePassword = false;
        for (UserAuthenticator userAuthenticator : userPasswordEncoders) {
            Pair<Boolean, ActionOnFailedAuthentication> authenticationResult = userAuthenticator.authenticate(user.getUsername(), currentPassword, userAccount.getDomainId(), null);
            if (authenticationResult == null) {
                logger.trace(String.format("Authenticator [%s] is returning null for the authenticate method.", userAuthenticator.getClass()));
                continue;
            }
            if (BooleanUtils.toBoolean(authenticationResult.first())) {
                logger.debug("User [{}] re-authenticated [authenticator={}] during password update.", user, userAuthenticator.getName());
                currentPasswordMatchesDataBasePassword = true;
                break;
            }
        }
        if (!currentPasswordMatchesDataBasePassword) {
            throw new InvalidParameterValueException("Current password is incorrect.");
        }
    }

    @Override
    public void validateAndUpdateUsernameIfNeeded(UpdateUserCmd updateUserCmd, UserVO newUser,
            Account newAccount) {
        String userName = updateUserCmd.getUsername();
        if (userName == null) {
            return;
        }
        if (StringUtils.isBlank(userName)) {
            throw new InvalidParameterValueException("Username cannot be empty.");
        }
        List<UserVO> existingUsers = userDao.findUsersByName(userName);
        for (UserVO existingUser : existingUsers) {
            if (existingUser.getId() == newUser.getId()) {
                continue;
            }

            // duplicate usernames cannot exist in same domain unless explicitly configured
            if (!userAllowMultipleAccounts.valueInScope(ConfigKey.Scope.Domain, newAccount.getDomainId())) {
                assertUserNotAlreadyInDomain(existingUser, newAccount);
            }

            // can't rename a username to an existing one in the same account
            assertUserNotAlreadyInAccount(existingUser, newAccount);
        }
        newUser.setUsername(userName);
    }

    @Override
    public void validateAndUpdateLastNameIfNeeded(UpdateUserCmd updateUserCmd, UserVO user) {
        String lastName = updateUserCmd.getLastname();
        if (lastName != null) {
            if (StringUtils.isBlank(lastName)) {
                throw new InvalidParameterValueException("Lastname cannot be empty.");
            }
            user.setLastname(lastName);
        }
    }

    @Override
    public void validateAndUpdateFirstNameIfNeeded(UpdateUserCmd updateUserCmd, UserVO user) {
        String firstName = updateUserCmd.getFirstname();
        if (firstName != null) {
            if (StringUtils.isBlank(firstName)) {
                throw new InvalidParameterValueException("Firstname cannot be empty.");
            }
            user.setFirstname(firstName);
        }
    }

    @Override
    public Account retrieveAndValidateAccount(UserVO user) {
        Account account = accountDao.findById(user.getAccountId());
        if (account == null) {
            throw new CloudRuntimeException("Unable to find user account with ID: " + user.getAccountId());
        }
        if (account.getType() == Account.Type.PROJECT) {
            throw new InvalidParameterValueException(String.format("Unable to find user with: %s", user));
        }
        if (account.getId() == Account.ACCOUNT_ID_SYSTEM) {
            throw new PermissionDeniedException(String.format("user: %s is a system account; update is not allowed.", user));
        }
        accountService.checkAccess(getCurrentCallingAccount(), AccessType.OperateEntry, true, account);
        return account;
    }

    @Override
    public Account getCurrentCallingAccount() {
        return CallContext.current().getCallingAccount();
    }

    @Override
    public void validateAndUpdateApiAndSecretKeyIfNeeded(UpdateUserCmd updateUserCmd, UserVO user) {
        String apiKey = updateUserCmd.getApiKey();
        String secretKey = updateUserCmd.getSecretKey();

        boolean isApiKeyBlank = StringUtils.isBlank(apiKey);
        boolean isSecretKeyBlank = StringUtils.isBlank(secretKey);
        if (isApiKeyBlank ^ isSecretKeyBlank) {
            throw new InvalidParameterValueException("Please provide a valid API and secret key pair.");
        }
        if (isApiKeyBlank && isSecretKeyBlank) {
            return;
        }

        ApiKeyPairVO lastUserKeyPair = apiKeyPairDao.getLastApiKeyCreatedByUser(user.getId());
        if (lastUserKeyPair == null) {
            throw new InvalidParameterValueException(String.format("User [%s] has no active API key pairs to be updated.", user.getUsername()));
        }

        Ternary<User, Account, ApiKeyPair> keyPairTernary = accountManager.findUserByApiKey(apiKey);
        if (keyPairTernary != null) {
            throw new InvalidParameterValueException(String.format("The API key [%s] already exists in the system. Please provide a unique key.", apiKey));
        }

        lastUserKeyPair.setApiKey(apiKey);
        lastUserKeyPair.setSecretKey(secretKey);
        apiKeyPairDao.update(lastUserKeyPair.getId(), lastUserKeyPair);
    }

    @Override
    public void validateAndUpdateUserApiKeyAccess(UpdateUserCmd updateUserCmd, UserVO user) {
        if (updateUserCmd.getApiKeyAccess() != null) {
            try {
                ApiConstants.ApiKeyAccess access = ApiConstants.ApiKeyAccess.valueOf(updateUserCmd.getApiKeyAccess().toUpperCase());
                user.setApiKeyAccess(access.toBoolean());
                Long callingUserId = CallContext.current().getCallingUserId();
                Account callingAccount = CallContext.current().getCallingAccount();
                ActionEventUtils.onActionEvent(callingUserId, callingAccount.getAccountId(), callingAccount.getDomainId(),
                        EventTypes.API_KEY_ACCESS_UPDATE, "Api key access was changed for the User to " + access,
                        user.getId(), ApiCommandResourceType.User.toString());
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("ApiKeyAccess value can only be Enabled/Disabled/Inherit");
            }
        }
    }

    @Override
    public void validateAndUpdateAccountApiKeyAccess(UpdateAccountCmd updateAccountCmd, AccountVO account) {
        if (updateAccountCmd.getApiKeyAccess() != null) {
            try {
                ApiConstants.ApiKeyAccess access = ApiConstants.ApiKeyAccess.valueOf(updateAccountCmd.getApiKeyAccess().toUpperCase());
                account.setApiKeyAccess(access.toBoolean());
                Long callingUserId = CallContext.current().getCallingUserId();
                Account callingAccount = CallContext.current().getCallingAccount();
                ActionEventUtils.onActionEvent(callingUserId, callingAccount.getAccountId(), callingAccount.getDomainId(),
                        EventTypes.API_KEY_ACCESS_UPDATE, "Api key access was changed for the Account to " + access,
                        account.getId(), ApiCommandResourceType.Account.toString());
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("ApiKeyAccess value can only be Enabled/Disabled/Inherit");
            }
        }
    }

    @Override
    public UserVO retrieveAndValidateUser(UpdateUserCmd updateUserCmd) {
        Long userId = updateUserCmd.getId();
        UserVO user = userDao.getUser(userId);
        if (user == null) {
            throw new InvalidParameterValueException("Unable to find user with id: " + userId);
        }
        return user;
    }

    @Override
    public void validateAndUpdatePasswordChangeRequired(User caller, UpdateUserCmd updateUserCmd,
            UserVO user, Account account) {
        if (updateUserCmd.isPasswordChangeRequired()) {
            if (user.getState() != Account.State.ENABLED || account.getState() != Account.State.ENABLED) {
                throw new CloudRuntimeException("CloudStack does not support enforcing password change for locked/disabled User or Account.");
            }

            User.Source userSource = user.getSource();
            if (userSource == User.Source.SAML2 || userSource == User.Source.SAML2DISABLED || userSource == User.Source.LDAP) {
                logger.warn("Enforcing password change is not permitted for source [{}].", user.getSource());
                throw new InvalidParameterValueException("CloudStack does not support enforcing password change for SAML or LDAP users.");
            }
        }

        boolean isCallerSameAsUser = user.getId() == caller.getId();
        boolean isPasswordResetRequired = updateUserCmd.isPasswordChangeRequired() && !isCallerSameAsUser;
        // Admins only can enforce passwordChangeRequired for user
        if (accountService.isRootAdmin(caller.getAccountId()) || accountService.isDomainAdmin(caller.getAccountId())) {
            if (isPasswordResetRequired) {
                userDetailsDao.addDetail(user.getId(), PasswordChangeRequired, "true", false);
            }
        }

        if (StringUtils.isNotBlank(updateUserCmd.getPassword())) {
            // Remove passwordChangeRequired if user updating own pwd or admin has not enforced it
            if (isCallerSameAsUser || !isPasswordResetRequired) {
                userDetailsDao.removeDetail(user.getId(), PasswordChangeRequired);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Private helpers (duplicated from AccountManagerImpl per playbook §gotchas)
    // -------------------------------------------------------------------------

    private void assertUserNotAlreadyInAccount(User existingUser, Account newAccount) {
        if (existingUser.getAccountId() == newAccount.getId()) {
            AccountVO existingAccount = accountDao.findById(newAccount.getId());
            throw new InvalidParameterValueException(String.format("Username [%s] already exists in account [id=%s,name=%s]", existingUser.getUsername(), existingAccount.getUuid(), existingAccount.getAccountName()));
        }
    }

    private void assertUserNotAlreadyInDomain(User existingUser, Account originalAccount) {
        Account existingAccount = accountDao.findById(existingUser.getAccountId());
        if (existingAccount.getDomainId() == originalAccount.getDomainId()) {
            DomainVO existingDomain = domainDao.findById(existingAccount.getDomainId());
            throw new InvalidParameterValueException(String.format("Username [%s] already exists in domain [id=%s,name=%s] user account [id=%s,name=%s]", existingUser.getUsername(), existingDomain.getUuid(), existingDomain.getName(), existingAccount.getUuid(), existingAccount.getAccountName()));
        }
    }

    // -------------------------------------------------------------------------
    // Setter injection for list-type collaborators (Spring XML wiring)
    // -------------------------------------------------------------------------

    public List<UserAuthenticator> getUserPasswordEncoders() {
        return userPasswordEncoders;
    }

    public void setUserPasswordEncoders(List<UserAuthenticator> encoders) {
        this.userPasswordEncoders = encoders;
    }
}
