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

import org.apache.cloudstack.api.command.admin.storage.SyncStoragePoolCmd;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

import com.cloud.agent.api.ModifyStoragePoolAnswer;

/**
 * VMware datastore-cluster synchronisation — extracted from
 * {@link StorageManagerImpl} as part of the Phase 4 Spring-component
 * decomposition.
 *
 * <p>A VMware "datastore cluster" is a vCenter-managed pool of child
 * datastores. CloudStack models the cluster as a parent
 * {@link StoragePoolVO} with {@code poolType = DatastoreCluster}, and
 * each member datastore as a child {@code StoragePoolVO} whose
 * {@code parent} points back at the cluster pool. The vCenter side can
 * change behind CloudStack's back (datastores added, removed, or
 * shuffled between clusters), so we periodically — and on the explicit
 * {@code syncStoragePool} API call — ask the host to enumerate the
 * cluster's current children and reconcile our records to match.
 *
 * <p>This service owns the reconciliation pipeline:
 *
 * <ul>
 *   <li><b>Sync entry point</b> — {@link #syncStoragePool(SyncStoragePoolCmd)}
 *       picks a connected host, sends a {@code ModifyStoragePoolCommand},
 *       and feeds the returned children through the reconcile steps below.</li>
 *   <li><b>Up-state validation</b> —
 *       {@link #validateChildDatastoresToBeAddedInUpState(StoragePoolVO, List)}
 *       refuses to sync if any existing-but-not-yet-attached child is in
 *       a non-Up state, since attaching a half-broken pool will fail.</li>
 *   <li><b>Reconcile children</b> —
 *       {@link #syncDatastoreClusterStoragePool(long, List, long)} walks
 *       the vCenter-reported children. Existing pools whose parent is
 *       wrong get re-parented (and tag-union recomputed); brand-new
 *       children are created via the package-private
 *       {@code createChildDatastoreVO(...)}; children that vCenter no
 *       longer reports get their parent cleared back to 0 and any Ready
 *       volumes on them are re-paths-synced via
 *       {@code SyncVolumePathCommand}.</li>
 *   <li><b>Per-host capacity bookkeeping</b> —
 *       {@link #updateStoragePoolHostVOAndBytes(StoragePool, long, ModifyStoragePoolAnswer)}
 *       refreshes the {@code storage_pool_host_ref} row and the pool's
 *       capacity/used-bytes from the host's view. This is called for
 *       both the parent and each child during sync, and is also called
 *       independently from {@link StoragePoolAutomationImpl} on
 *       management-node-up.</li>
 * </ul>
 *
 * <p>The corresponding public {@link StorageManager} /
 * {@link com.cloud.storage.StorageService} entry points stay on the
 * manager and delegate here so existing callers
 * (e.g. {@code StoragePoolAutomationImpl}) and any future spies see
 * unchanged signatures.
 */
public interface DatastoreClusterService {

    /**
     * Implementation of the {@code syncStoragePool} API. Looks up the
     * pool, validates that it is a {@code DatastoreCluster} in
     * {@code Up} state with at least one connected host, sends a
     * {@link com.cloud.agent.api.ModifyStoragePoolCommand} to gather
     * the current child datastore list, and reconciles via
     * {@link #updateStoragePoolHostVOAndBytes},
     * {@link #validateChildDatastoresToBeAddedInUpState}, and
     * {@link #syncDatastoreClusterStoragePool}.
     *
     * @throws com.cloud.utils.exception.CloudRuntimeException when no
     *         host is connected, the host fails to answer, or any child
     *         pool is in a non-Up state.
     * @throws com.cloud.exception.InvalidParameterValueException when
     *         the pool does not exist, is not a datastore cluster, or
     *         is not Up.
     */
    StoragePool syncStoragePool(SyncStoragePoolCmd cmd);

    /**
     * Reconcile the child datastores of {@code datastoreClusterPoolId}
     * against the vCenter-reported {@code childDatastoreAnswerList}.
     * Re-parents misplaced children, creates new ones, and clears the
     * parent on children that vCenter no longer reports.
     *
     * @param datastoreClusterPoolId the parent pool id
     * @param childDatastoreAnswerList children currently reported by
     *        vCenter, via a {@code ModifyStoragePoolAnswer}
     * @param hostId the host id that produced the answer; used by
     *        {@link #updateStoragePoolHostVOAndBytes} for per-host
     *        capacity bookkeeping
     */
    void syncDatastoreClusterStoragePool(long datastoreClusterPoolId, List<ModifyStoragePoolAnswer> childDatastoreAnswerList, long hostId);

    /**
     * Throw a {@link com.cloud.utils.exception.CloudRuntimeException} if
     * any existing-but-not-yet-attached child datastore in
     * {@code childDatastoreAnswerList} is in a non-Up state. Children
     * that don't yet exist in our DB are ignored — they will be created
     * by {@link #syncDatastoreClusterStoragePool}.
     */
    void validateChildDatastoresToBeAddedInUpState(StoragePoolVO datastoreClusterPool, List<ModifyStoragePoolAnswer> childDatastoreAnswerList);

    /**
     * Refresh the {@code storage_pool_host_ref} row for
     * {@code (pool, hostId)} with the host-reported local path, and
     * update the pool's {@code capacityBytes} / {@code usedBytes} from
     * the answer's pool info. StorPool pools skip the byte refresh
     * because their capacity is reported elsewhere.
     */
    void updateStoragePoolHostVOAndBytes(StoragePool pool, long hostId, ModifyStoragePoolAnswer mspAnswer);
}
