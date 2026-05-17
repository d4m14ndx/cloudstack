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

import java.util.List;

import com.cloud.host.Host;

/**
 * Host-to-managed-storage-pool access checks — for managed primary
 * storage these dispatch to the pool's
 * {@link org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver}
 * to ask whether a given host can access, prepare-access-to, or
 * disconnect-from the pool. Non-managed pools are always considered
 * accessible (and always considered disconnectable).
 *
 * <p>Extracted from {@link StorageManagerImpl} as part of the Phase 4
 * Spring-component decomposition. The public
 * {@link com.cloud.storage.StorageManager} interface entry points stay
 * on the manager and now delegate here so existing test spies (e.g.
 * {@code Mockito.doReturn(...).when(storageManagerImpl)
 * .findUpAndEnabledHostWithAccessToStoragePools(...)}) keep working.
 */
public interface HostStorageAccessService {

    /**
     * Pick an Up + Enabled host from the storage_pool_host join that is
     * able to access every supplied pool. Hosts are sampled in
     * randomised order so callers don't always retry the same host.
     *
     * @param poolIds storage-pool ids the host must be able to access
     * @return a {@link Host} reachable for every pool in {@code poolIds},
     *         or {@code null} when no such host exists.
     */
    Host findUpAndEnabledHostWithAccessToStoragePools(List<Long> poolIds);

    /**
     * Does the host have access to the given storage pool? Non-managed
     * pools always return {@code true}; managed pools dispatch to the
     * pool's {@code PrimaryDataStoreDriver#canHostAccessStoragePool}.
     *
     * @return {@code false} when either argument is {@code null}, or
     *         when the managed pool's driver refuses the host.
     */
    boolean canHostAccessStoragePool(Host host, StoragePool pool);

    /**
     * Can the host prepare access to the (managed) pool? Returns
     * {@code false} for non-managed pools and for {@code null}
     * arguments; managed pools dispatch to
     * {@code PrimaryDataStoreDriver#canHostPrepareStoragePoolAccess}.
     */
    boolean canHostPrepareStoragePoolAccess(Host host, StoragePool pool);

    /**
     * Can the host be disconnected from the (managed) pool? Returns
     * {@code true} for non-managed pools and for a {@code null} pool;
     * managed pools dispatch to
     * {@code PrimaryDataStoreDriver#canDisconnectHostFromStoragePool}.
     */
    boolean canDisconnectHostFromStoragePool(Host host, StoragePool pool);
}
