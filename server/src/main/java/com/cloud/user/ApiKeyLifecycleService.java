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
import java.util.Map;

import org.apache.cloudstack.acl.ApiKeyPairVO;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.api.BaseCmd;

import com.cloud.utils.Ternary;

/**
 * Generation, persistence and removal of CloudStack API key pairs — the cluster
 * of helpers that {@link AccountManagerImpl} surfaces when it issues new
 * api/secret key tuples, persists key-scoped permission rules, looks up key
 * pairs from the DAO layer and tears expired keys down.
 *
 * <p>Pure key-lifecycle logic: random-key minting (with collision retry against
 * the persistence layer), persistence of {@link ApiKeyPairVO} with associated
 * permission rules, cascade-delete of permission rows, and a small handful of
 * {@code findByX}-style accessors. The orchestration surface
 * ({@link AccountManagerImpl#createApiKeyAndSecretKey(org.apache.cloudstack.api.command.admin.user.RegisterUserKeysCmd)}
 * and friends) stays in the god class so its caller-privilege checks,
 * {@code @ActionEvent} hooks and spy-verified inner calls keep working.
 *
 * <p>Extracted from {@link AccountManagerImpl} as part of the Phase 4
 * Spring-component decomposition — the 3rd parallel slice on this file.
 * {@code AccountManagerImpl} keeps the one-line delegating wrappers so the
 * {@link AccountService} / {@link AccountManager} interface contracts (and
 * the {@code @Spy} stub in {@code AccountManagerImplTest} that intercepts
 * {@link #findUserByApiKey(String)}) continue to work unchanged.
 */
public interface ApiKeyLifecycleService {

    /**
     * Generate a fresh, collision-checked API key for {@code userId} and bind
     * it onto {@code newApiKeyPair} via {@link ApiKeyPairVO#setApiKey(String)}.
     * Returns the generated key, or {@code null} when ten consecutive retries
     * all collided with an existing row (which in practice never happens — the
     * keyspace is 160 bits) or the {@code HmacSHA1} algorithm is missing from
     * the JVM.
     */
    String createUserApiKey(long userId, ApiKeyPairVO newApiKeyPair);

    /**
     * Generate a fresh, collision-checked secret key for {@code userId} and bind
     * it onto {@code newApiKeyPair} via {@link ApiKeyPairVO#setSecretKey(String)}.
     * Same {@code null}-return semantics as {@link #createUserApiKey(long, ApiKeyPairVO)}.
     */
    String createUserSecretKey(long userId, ApiKeyPairVO newApiKeyPair);

    /**
     * Persist {@code newApiKeyPair} along with the supplied permission rules,
     * after confirming the caller's effective rule set is a superset of the
     * permissions being granted. Throws
     * {@link com.cloud.exception.InvalidParameterValueException} when the
     * caller would be elevating the key above its own permission set.
     *
     * @param account         the {@link Account} that will own the new key pair
     * @param newApiKeyPair   the {@link ApiKeyPairVO} to persist (api/secret
     *                        already populated)
     * @param rules           the raw rule maps from the inbound request — each
     *                        entry is keyed by {@code rule}, {@code permission},
     *                        {@code description}
     * @param cmd             the inbound {@link BaseCmd}, used to resolve the
     *                        caller's API key for the superset comparison
     */
    ApiKeyPairVO validateAndPersistKeyPairAndPermissions(Account account, ApiKeyPairVO newApiKeyPair,
                                                        List<Map<String, Object>> rules, BaseCmd cmd);

    /**
     * Cascade-delete the supplied key pair: remove every
     * {@code ApiKeyPairPermissionVO} that references it, then remove the
     * key-pair row itself. No caller-privilege checks — those are the
     * responsibility of the orchestrating method on {@link AccountManagerImpl}.
     */
    void internalDeleteApiKey(ApiKeyPair keyPair);

    /**
     * Tear down the supplied key pair when its {@code endDate} has passed,
     * via {@link #internalDeleteApiKey(ApiKeyPair)}. No-op for unexpired keys.
     */
    void removeApiKeyPairIfExpired(ApiKeyPair apiKeyPair);

    /**
     * Find an {@link ApiKeyPair} by its primary key, or {@code null} when
     * the row does not exist.
     */
    ApiKeyPair getKeyPairById(Long id);

    /**
     * Find an {@link ApiKeyPair} by its public api-key value, or {@code null}
     * when no such row exists. The api-key column carries a unique index.
     */
    ApiKeyPair getKeyPairByApiKey(String apiKey);

    /**
     * Resolve the most-recently-issued {@link ApiKeyPair} for {@code userId},
     * folded over {@link com.cloud.api.ApiDBUtils#searchForLatestUserKeyPair(Long)}.
     */
    ApiKeyPair getLatestUserKeyPair(Long userId);

    /**
     * Resolve the {@link User}, owning {@link Account} and {@link ApiKeyPair}
     * for the supplied api-key string, or {@code null} when no row matches.
     * Used by the api-key signature-verification path.
     */
    Ternary<User, Account, ApiKeyPair> findUserByApiKey(String apiKey);
}
