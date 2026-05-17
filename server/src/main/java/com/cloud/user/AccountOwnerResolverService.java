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

/**
 * Owner-and-account-id resolution for resource-creation and resource-listing
 * API handlers — the cluster of helpers every {@code createXxxCmd} and
 * {@code listXxxCmd} handler calls to translate the caller-supplied
 * {@code (accountName, domainId, projectId, accountId)} parameter quartet into
 * the concrete {@link Account} (or account-id) that owns / scopes the
 * resource.
 *
 * <p>Three concerns live here:
 * <ol>
 *   <li><b>{@link #finalizeOwner}</b> — pick the {@link Account} that should
 *       own a new resource. Rejects the system account as a default owner,
 *       rejects ambiguous {@code (account+domain, project)} combinations,
 *       resolves {@code projectId} to the project's owner-account (gated by
 *       {@code projectManager.canAccessProjectAccount}), resolves
 *       {@code (accountName, domainId)} differently for admin and non-admin
 *       callers, and falls back to the caller itself when no explicit owner
 *       was specified. Runs the security-checker chain on the resolved
 *       domain for admin callers.</li>
 *   <li><b>{@link #finalizeAccountId(String, Long, Long, boolean)}</b> — the
 *       legacy four-arg overload used by resource-creation handlers (volume,
 *       network, snapshot, …) that need a numeric account-id rather than an
 *       {@link Account}. Rejects project-type accounts, optionally enforces
 *       that the account / project is in the {@code ENABLED} / {@code Active}
 *       state, and throws
 *       {@link com.cloud.exception.PermissionDeniedException} for resources
 *       targeted at a disabled owner.</li>
 *   <li><b>{@link #finalizeAccountId(Long, String, Long, Long)}</b> — the
 *       newer four-arg overload used by listing handlers that may receive
 *       any of {@code accountId}, {@code (accountName, domainId)} or
 *       {@code projectId} as the scope key. Enforces mutual-exclusion
 *       between project- and account-keyed lookups, surfaces the
 *       project-must-be-Active rule via
 *       {@link #getActiveProjectAccountByProjectId}, and translates the
 *       legacy {@code InvalidParameterValueException} from
 *       {@code (accountName, domainId)} resolution into a
 *       {@code ServerApiException} so the API layer renders the correct
 *       parameter-error response.</li>
 * </ol>
 *
 * <p>Extracted from {@link AccountManagerImpl} as part of the Phase 4
 * Spring-component decomposition — the 6th parallel slice on this file.
 * {@link AccountManagerImpl} keeps one-line delegating wrappers so the
 * {@link AccountService} contract continues to work unchanged for every
 * resource-creation and resource-listing handler across the codebase.
 */
public interface AccountOwnerResolverService {

    /**
     * Resolve the {@link Account} that should own a newly-created resource.
     *
     * <p>Behaviour:
     * <ul>
     *   <li>System caller without an explicit owner — throws
     *       {@link com.cloud.exception.InvalidParameterValueException}; the
     *       system account is never a default owner.</li>
     *   <li>Both {@code (accountName + domainId)} and {@code projectId}
     *       supplied — throws
     *       {@link com.cloud.exception.InvalidParameterValueException}.</li>
     *   <li>{@code projectId} supplied — looks up the project, runs
     *       {@code projectManager.canAccessProjectAccount(caller, …)} and
     *       returns the project's owner-account. Throws
     *       {@link com.cloud.exception.PermissionDeniedException} when the
     *       caller cannot access the project.</li>
     *   <li>{@code (accountName, domainId)} supplied by an admin caller —
     *       looks up the domain, finds the active account, runs the
     *       security-checker chain on the domain, returns the resolved
     *       account.</li>
     *   <li>{@code (accountName, domainId)} supplied by a non-admin caller —
     *       must match the caller's own account; otherwise throws
     *       {@link com.cloud.exception.PermissionDeniedException}.</li>
     *   <li>{@code accountName} supplied without {@code domainId} — throws
     *       {@link com.cloud.exception.InvalidParameterValueException}.</li>
     *   <li>No explicit owner supplied — returns {@code caller}.</li>
     * </ul>
     */
    Account finalizeOwner(Account caller, String accountName, Long domainId, Long projectId);

    /**
     * Resolve {@code (accountName, domainId, projectId)} into an
     * account-id for resource-creation handlers that store the owner as a
     * numeric id rather than an {@link Account}. Rejects project-type
     * accounts (the project's owner-account is returned instead when
     * {@code projectId} is supplied), and — when {@code enabledOnly} is
     * {@code true} — requires the resolved account / project to be in the
     * {@code ENABLED} / {@code Active} state, throwing
     * {@link com.cloud.exception.PermissionDeniedException} otherwise.
     * Returns {@code null} when no owner could be resolved from the inputs
     * (caller-keyed resources are expected to default elsewhere).
     */
    Long finalizeAccountId(String accountName, Long domainId, Long projectId, boolean enabledOnly);

    /**
     * Resolve any of {@code accountId}, {@code (accountName, domainId)} or
     * {@code projectId} into an account-id, enforcing mutual exclusion
     * between project- and account-keyed lookups. Surfaces the
     * project-must-be-Active rule via
     * {@link #getActiveProjectAccountByProjectId}; translates the legacy
     * {@link com.cloud.exception.InvalidParameterValueException} from
     * {@code (accountName, domainId)} resolution into a
     * {@link org.apache.cloudstack.api.ServerApiException} so the API layer
     * renders the correct parameter-error response.
     */
    Long finalizeAccountId(Long accountId, String accountName, Long domainId, Long projectId);

    /**
     * Look up the project's owner-account-id, asserting that the project
     * exists and is in the {@code Active} state. Used by the
     * project-scoped branch of
     * {@link #finalizeAccountId(Long, String, Long, Long)}; throws
     * {@link org.apache.cloudstack.api.ServerApiException} on a missing or
     * non-active project.
     */
    long getActiveProjectAccountByProjectId(long projectId);
}
