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

import org.apache.cloudstack.auth.UserTwoFactorAuthenticator;

/**
 * Provider-registry lookups and login-time setup-state cleanup for user
 * two-factor authentication — the small cluster of helpers that
 * {@link AccountManagerImpl} surfaces when callers ask "what 2FA providers
 * are installed", "which provider should this user use" and "this user's
 * 2FA is still mid-setup at login, sweep it".
 *
 * <p>Pure read-only registry plumbing: holds the list of {@link
 * UserTwoFactorAuthenticator} beans contributed by plugins, lazily builds a
 * name-to-provider map, and resolves providers either by domain
 * (via the {@code user.2fa.default.provider} {@code ConfigKey}) or by a
 * specific user's persisted {@code user2faProvider}. Plus the
 * {@link #clearUserTwoFactorAuthenticationInSetupStateOnLogin(UserAccount)}
 * sweep that the login path runs to drop 2FA state when a user logs in
 * before completing setup. The orchestration surface
 * ({@link AccountManagerImpl#setupUserTwoFactorAuthentication},
 * {@link AccountManagerImpl#verifyUsingTwoFactorAuthenticationCode},
 * {@link AccountManagerImpl#enableTwoFactorAuthentication},
 * {@link AccountManagerImpl#disableTwoFactorAuthentication}) stays in the
 * god class so its {@code checkAccess} hooks, the static-field-driven
 * {@code enableUserTwoFactorAuthentication} / {@code userTwoFactorAuthenticationProvidersMap}
 * mocks in {@code AccountManagerImplTest} and the spy-verified inner calls
 * keep working.
 *
 * <p>Extracted from {@link AccountManagerImpl} as part of the Phase 4
 * Spring-component decomposition — the 4th parallel slice on this file.
 * {@code AccountManagerImpl} keeps the one-line delegating wrappers so the
 * {@link AccountService} / {@link AccountManager} interface contracts
 * continue to work unchanged.
 */
public interface TwoFactorAuthenticationService {

    /**
     * Snapshot of every {@link UserTwoFactorAuthenticator} plugin bean
     * registered with the platform (the list Spring wires in, unfiltered).
     * Order matches the bean-discovery order; callers typically iterate to
     * present a UI of installable providers.
     */
    List<UserTwoFactorAuthenticator> listUserTwoFactorAuthenticationProviders();

    /**
     * Provider plugin bound to the domain-scoped
     * {@code user.2fa.default.provider} config — i.e. the plugin a user in
     * {@code domainId} will get when they enable 2FA without naming one.
     * Throws {@link com.cloud.utils.exception.CloudRuntimeException} when
     * the configured name is empty or no provider with that name is
     * registered.
     */
    UserTwoFactorAuthenticator getUserTwoFactorAuthenticationProvider(Long domainId);

    /**
     * Provider plugin registered under {@code name}, case-insensitive.
     * Throws {@link com.cloud.utils.exception.CloudRuntimeException} when
     * the name is empty or no provider with that name is registered.
     */
    UserTwoFactorAuthenticator getUserTwoFactorAuthenticationProvider(String name);

    /**
     * Provider plugin appropriate for verifying a specific user's 2FA code.
     * Prefers the {@code user2faProvider} that the user enrolled with;
     * falls back to the domain-scoped default. Same throwing semantics as
     * {@link #getUserTwoFactorAuthenticationProvider(String)}.
     */
    UserTwoFactorAuthenticator getUserTwoFactorAuthenticator(Long domainId, Long userAccountId);

    /**
     * Provider plugin registered under {@code name}, case-insensitive — the
     * same resolution as
     * {@link #getUserTwoFactorAuthenticationProvider(String)} but with the
     * legacy "UserTwoFactorAuthenticator" error wording that callers in the
     * verify-code path expect.
     */
    UserTwoFactorAuthenticator getUserTwoFactorAuthenticator(String name);

    /**
     * Sweep partially-configured 2FA state for {@code user} when they log in
     * before completing setup. If 2FA is flagged as enabled but the
     * {@code Setup2FADetail} user-detail is still {@code ENABLED} (i.e. not
     * {@code VERIFIED}), this clears {@code user2faProvider},
     * {@code keyFor2fa} and the {@code Setup2FADetail} row, all within a
     * single transaction. Returns the (possibly mutated) {@link UserAccount}.
     * No-op for users with no 2FA configured.
     */
    UserAccount clearUserTwoFactorAuthenticationInSetupStateOnLogin(UserAccount user);

    /**
     * Re-snapshot the provider-name to provider-bean map from the current
     * {@link #setUserTwoFactorAuthenticationProviders(List)} contents.
     * Called once at {@code start()} time; idempotent on repeat calls
     * (subsequent invocations overwrite earlier entries by name).
     */
    void initializeUserTwoFactorAuthenticationProvidersMap();

    /**
     * Inject the list of plugin-contributed {@link UserTwoFactorAuthenticator}
     * beans. Called by the Spring container; legacy tests also call this to
     * stage a fixed list of provider mocks. Does not eagerly rebuild the
     * map — callers must follow with
     * {@link #initializeUserTwoFactorAuthenticationProvidersMap()} when they
     * want the new list reflected in lookups.
     */
    void setUserTwoFactorAuthenticationProviders(List<UserTwoFactorAuthenticator> userTwoFactorAuthenticationProviders);

    /**
     * The plugin list currently wired into this service, as set by
     * {@link #setUserTwoFactorAuthenticationProviders(List)}. May be
     * {@code null} when nothing has been wired yet.
     */
    List<UserTwoFactorAuthenticator> getUserTwoFactorAuthenticationProviders();
}
