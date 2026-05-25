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

import java.util.List;

import org.apache.cloudstack.acl.RoleType;

/**
 * Read-only account and user lookups against the persistence layer — the
 * cluster of {@code findByX}-style accessors that {@link AccountManagerImpl}
 * surfaces through the {@link AccountService} contract. No mutation, no
 * permission checks, no business logic beyond a thin {@code null}-guard or
 * detail-fold here and there.
 *
 * <p>Extracted from {@link AccountManagerImpl} as part of the Phase 4
 * Spring-component decomposition. {@code AccountManagerImpl} keeps the
 * one-line delegating wrappers so the {@link AccountService} /
 * {@link AccountManager} interface contracts (and the {@code @Spy}
 * stubs in {@code AccountManagerImplTest} that intercept
 * {@link #getRoleType(Account)} and {@link #getActiveUser(long)})
 * continue to work unchanged.
 */
public interface AccountLookupService {

    /**
     * Find an active (non-removed) account by {@code accountName} within
     * {@code domainId}. Both arguments are required — passing {@code null}
     * for either is rejected with an
     * {@link com.cloud.exception.InvalidParameterValueException}.
     */
    Account getActiveAccountByName(String accountName, Long domainId);

    /**
     * Find a {@link UserAccount} by {@code username} within {@code domainId}.
     * The {@code username} must be unique inside the domain.
     */
    UserAccount getActiveUserAccount(String username, Long domainId);

    /**
     * List all {@link UserAccount}s whose primary email matches
     * {@code email} within {@code domainId} — typically zero or one row,
     * but the schema allows multiples so the result is a list.
     */
    List<UserAccount> getActiveUserAccountByEmail(String email, Long domainId);

    /**
     * Find an active (non-removed) account by id, or {@code null} if no
     * such row exists.
     */
    Account getActiveAccountById(long accountId);

    /**
     * Find an account by id, INCLUDING soft-removed rows. Used by audit
     * and cleanup paths that need to resolve historical owners.
     */
    Account getAccount(long accountId);

    /**
     * Resolve the {@link RoleType} of the supplied account from its
     * {@link Account.Type}. Returns {@link RoleType#Unknown} for a
     * {@code null} account rather than throwing — callers downstream
     * surface a friendlier permission-denied message.
     */
    RoleType getRoleType(Account account);

    /**
     * Find an active (non-removed) user by id.
     */
    User getActiveUser(long userId);

    /**
     * Find a user by id, INCLUDING soft-removed rows.
     */
    User getUserIncludingRemoved(long userId);

    /**
     * Find a user by the one-shot registration token that was issued
     * when their account was provisioned. The token is invalidated as
     * soon as it is consumed.
     */
    User getActiveUserByRegistrationToken(String registrationToken);

    /**
     * Mark the supplied user as having completed registration —
     * essentially flipping the {@code registered} flag on the
     * {@link UserVO} row.
     */
    void markUserRegistered(long userId);

    /**
     * Load a {@link UserAccount} by id and fold its detail key/value
     * pairs onto the returned VO. Returns {@code null} when no such
     * user account exists.
     */
    UserAccount getUserAccountById(Long userId);
}
