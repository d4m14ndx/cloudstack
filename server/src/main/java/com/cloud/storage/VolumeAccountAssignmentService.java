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
package com.cloud.storage;

import com.cloud.user.Account;

/**
 * Helpers backing {@link VolumeApiServiceImpl#assignVolumeToAccount} —
 * the API path that reassigns a volume from one account or project to
 * another. Covers the four pre-flight / commit responsibilities that
 * surround the actual {@code _accountMgr.checkAccess} and resource
 * limit check:
 * <ul>
 *   <li>resolving the destination account, including the project
 *       indirection ({@link #getAccountOrProject});</li>
 *   <li>validating the volume itself is eligible to move
 *       ({@link #validateVolume});</li>
 *   <li>validating that the source / destination account pair is
 *       legal ({@link #validateAccounts}); and</li>
 *   <li>actually rewriting the account / domain on the volume and
 *       emitting the usage events / resource-limit deltas
 *       ({@link #updateVolumeAccount}).</li>
 * </ul>
 *
 * <p>Extracted from {@link VolumeApiServiceImpl} as part of the Phase
 * 4 Spring-component decomposition (parallel slice, 4th). The host
 * class keeps thin delegating wrappers so the orchestration in
 * {@link VolumeApiServiceImpl#assignVolumeToAccount} can still be
 * exercised by the existing spy-verified manager-level tests, while
 * the underlying logic is now covered by focused unit tests against
 * this component.</p>
 */
public interface VolumeAccountAssignmentService {

    /**
     * Validate that the volume can be reassigned to another account.
     *
     * <p>Throws {@link com.cloud.exception.InvalidParameterValueException}
     * if {@code volume} is {@code null}. Throws
     * {@link com.cloud.exception.PermissionDeniedException} if the
     * volume is currently attached to a VM, or if it still has
     * non-destroyed / non-error snapshots — both leave dangling
     * references that can't be safely moved with the volume.</p>
     */
    void validateVolume(String volumeUuid, VolumeVO volume);

    /**
     * Validate the source and destination accounts for an
     * {@code assignVolumeToAccount} call. Throws
     * {@link com.cloud.exception.InvalidParameterValueException} when
     * either account is {@code null}, when the destination account is
     * in {@code DISABLED} or {@code LOCKED} state, or when the source
     * and destination accounts are the same (no-op move).
     */
    void validateAccounts(String newAccountUuid, VolumeVO volume,
                          Account oldAccount, Account newAccount);

    /**
     * Resolve the destination account for the assignment. Either
     * {@code accountId} OR {@code projectId} must be supplied (not
     * both). When a project is given, the caller must have access to
     * the project, and the project's owning account is returned;
     * otherwise the account is resolved directly via
     * {@code AccountService#getActiveAccountById}.
     */
    Account getAccountOrProject(String projectUuid, Long accountId,
                                Long projectId, Account caller);

    /**
     * Commit the account change on the volume: publish the
     * {@code VOLUME_DELETE} usage event for the old owner, decrement
     * the old account's resource counters, switch the volume's
     * {@code accountId} / {@code domainId}, persist, increment the
     * new account's counters, publish the {@code VOLUME_CREATE}
     * usage event for the new owner, and ask
     * {@code VolumeService#moveVolumeOnSecondaryStorageToAnotherAccount}
     * to migrate any secondary-storage state.
     *
     * <p>Must be called from inside a {@code TransactionCallback}.</p>
     */
    void updateVolumeAccount(Account oldAccount, VolumeVO volume, Account newAccount);
}
