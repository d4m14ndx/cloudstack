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

import org.apache.cloudstack.acl.RolePermissionEntity;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.api.BaseCmd;

/**
 * Permission and superset checks for CloudStack API key pairs — the cluster of
 * helpers that {@link AccountManagerImpl} surfaces when validating whether the
 * caller's API key has the right rules to read or modify another key pair, or
 * to parse the caller's key out of an incoming command.
 *
 * <p>Pure read-side logic: extracts the calling API key from a {@link BaseCmd},
 * resolves the {@link RolePermissionEntity} list backing a key, and answers
 * "is this caller's role a superset of the target key's role?". No mutation,
 * no caller authentication, no DAO writes.
 *
 * <p>Extracted from {@link AccountManagerImpl} as part of the Phase 4
 * Spring-component decomposition. {@code AccountManagerImpl} keeps the
 * one-line delegating wrappers so the {@link AccountService} interface
 * contract continues to work unchanged.
 */
public interface ApiKeyPermissionService {

    /**
     * Extract the caller's API key from the supplied command, when the
     * request was authenticated via API-key/signature. Returns {@code null}
     * when the request came in over a session (no signature parameter on
     * the URL and no signature in the async-job payload).
     */
    String getAccessingApiKey(BaseCmd cmd);

    /**
     * Resolve the full list of {@link RolePermissionEntity} rules backing
     * the supplied API key. Throws {@link com.cloud.exception.InvalidParameterValueException}
     * when {@code apiKey} is {@code null}.
     */
    List<RolePermissionEntity> getAllKeypairPermissions(String apiKey);

    /**
     * Answer whether the caller (as extracted from {@code cmd}) holds a key
     * pair whose permissions are a superset of those on {@code accessedKeyPair}.
     * When the request was not authenticated by an API key (i.e.
     * {@link #getAccessingApiKey(BaseCmd)} returns {@code null}), the caller
     * is assumed to be session-authenticated and the answer is {@code true}.
     */
    Boolean isAccessingKeypairSuperset(ApiKeyPair accessedKeyPair, BaseCmd cmd);

    /**
     * Compare two raw permission lists — does {@code baseKeyPairPermissions}
     * cover everything in {@code comparedPermissions}? Delegates to
     * {@link org.apache.cloudstack.acl.RoleService} for the rule-matching
     * semantics.
     */
    Boolean isApiKeySupersetOfPermission(List<RolePermissionEntity> baseKeyPairPermissions,
                                         List<RolePermissionEntity> comparedPermissions);

    /**
     * Reject the request with an
     * {@link com.cloud.exception.InvalidParameterValueException} when
     * {@code keyPair} is {@code null}. Used by the list-keys flow to guard
     * against an unknown id or filter.
     */
    void validateKeyPairIsNotNull(ApiKeyPair keyPair);

    /**
     * Reject the request with a
     * {@link com.cloud.exception.PermissionDeniedException} when the caller's
     * API key has fewer permissions than the API key pair being accessed.
     */
    void validateAccessingKeyPairPermissionsIsSupersetOfAccessedKeyPair(ApiKeyPair keyPair, BaseCmd cmd);
}
