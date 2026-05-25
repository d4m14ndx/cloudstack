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

import org.apache.cloudstack.acl.Role;
import org.apache.cloudstack.api.command.admin.account.UpdateAccountCmd;
import org.apache.cloudstack.api.command.admin.user.UpdateUserCmd;

/**
 * Validation and update helpers for the {@code updateUser} / {@code updateAccount} flows
 * in {@link AccountManagerImpl} — extracted as a Phase 4 Spring-component slice.
 *
 * <p>The 10 methods that are verified on the {@link AccountManagerImpl} spy in existing
 * tests remain as one-line delegating wrappers in the god class.  Only the 4 pure leaf
 * methods that are <em>not</em> spy-verified ({@link #validateRoleChange},
 * {@link #validateAndUpdatePasswordChangeRequired},
 * {@link #validateAndUpdateAccountApiKeyAccess}, and the inline helper
 * {@link #retrieveAndValidateUser}) are called directly via this service from the
 * orchestrator.
 */
public interface UserUpdateService {

    /**
     * Validates that the caller is allowed to change {@code account}'s role to
     * {@code role}. Throws {@link com.cloud.exception.PermissionDeniedException} when the
     * change is disallowed.
     */
    void validateRoleChange(Account account, Role role, Account caller);

    /**
     * Updates the password in {@code user} if {@code newPassword} is non-null.
     * Validates source type, password policy, and (for non-admins) the current password.
     */
    void validateUserPasswordAndUpdateIfNeeded(String newPassword, UserVO user,
            String currentPassword, boolean skipCurrentPassValidation);

    /**
     * Authenticates {@code user} against all configured password encoders using
     * {@code currentPassword}.  Throws {@link com.cloud.exception.InvalidParameterValueException}
     * when no authenticator recognises the password.
     */
    void validateCurrentPassword(UserVO user, String currentPassword);

    /**
     * Validates and sets the username on {@code newUser} if the command carries a new
     * username.  Enforces uniqueness within the domain / account.
     */
    void validateAndUpdateUsernameIfNeeded(UpdateUserCmd updateUserCmd, UserVO newUser,
            Account newAccount);

    /**
     * Validates and sets the last name on {@code user} if the command carries a new
     * last name.
     */
    void validateAndUpdateLastNameIfNeeded(UpdateUserCmd updateUserCmd, UserVO user);

    /**
     * Validates and sets the first name on {@code user} if the command carries a new
     * first name.
     */
    void validateAndUpdateFirstNameIfNeeded(UpdateUserCmd updateUserCmd, UserVO user);

    /**
     * Looks up the account for {@code user}, validates it is not a project or system
     * account, and checks the caller's access.
     */
    Account retrieveAndValidateAccount(UserVO user);

    /**
     * Returns {@code CallContext.current().getCallingAccount()}.  A thin wrapper
     * that enables Mockito spy interception in tests.
     */
    Account getCurrentCallingAccount();

    /**
     * Validates and updates the API key / secret key pair on {@code user} when the
     * command carries new key values.
     */
    void validateAndUpdateApiAndSecretKeyIfNeeded(UpdateUserCmd updateUserCmd, UserVO user);

    /**
     * Validates and sets the {@code apiKeyAccess} flag on {@code user} when the
     * command carries a new value.
     */
    void validateAndUpdateUserApiKeyAccess(UpdateUserCmd updateUserCmd, UserVO user);

    /**
     * Validates and sets the {@code apiKeyAccess} flag on {@code account} when the
     * command carries a new value.
     */
    void validateAndUpdateAccountApiKeyAccess(UpdateAccountCmd updateAccountCmd, AccountVO account);

    /**
     * Looks up the user by the ID in {@code updateUserCmd}; throws
     * {@link com.cloud.exception.InvalidParameterValueException} when not found.
     */
    UserVO retrieveAndValidateUser(UpdateUserCmd updateUserCmd);

    /**
     * Enforces the {@code passwordChangeRequired} constraint when the command
     * requests it, and clears the flag when the user is updating their own password.
     */
    void validateAndUpdatePasswordChangeRequired(User caller, UpdateUserCmd updateUserCmd,
            UserVO user, Account account);
}
