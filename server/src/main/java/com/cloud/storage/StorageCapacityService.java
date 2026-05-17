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

import java.math.BigDecimal;
import java.util.List;

import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

import com.cloud.capacity.CapacityVO;
import com.cloud.utils.Pair;
import com.cloud.vm.DiskProfile;

/**
 * Storage capacity bookkeeping and pre-allocation space/IOPS checks —
 * extracted from {@link StorageManagerImpl}.
 *
 * <p>This slice owns three closely-related concerns:
 *
 * <ul>
 *   <li><b>Capacity entry persistence</b> — keep the per-pool
 *       {@code cloud.capacity} rows for
 *       {@code CAPACITY_TYPE_STORAGE} / {@code CAPACITY_TYPE_STORAGE_ALLOCATED}
 *       in sync with the underlying pool: compute the over-provisioned
 *       totals, resolve {@link com.cloud.capacity.CapacityState} from
 *       zone / cluster / host allocation state, and insert-or-update the
 *       capacity row in a single call.</li>
 *   <li><b>Used-stats aggregation</b> — produce a single
 *       {@link CapacityVO} summarising primary, secondary, or object
 *       storage used capacity across a scope (zone, pod, cluster, pool
 *       set, or individual pool).</li>
 *   <li><b>Pre-allocation checks</b> — answer "does this pool currently
 *       have enough free space (or IOPS) to accept this volume / set of
 *       volumes / resize?", honouring the
 *       {@code pool.storage.capacity.disablethreshold} and
 *       {@code pool.storage.allocated.capacity.disablethreshold} config
 *       knobs as well as the over-provisioning factor.</li>
 * </ul>
 *
 * <p>The corresponding public {@link StorageManager} entry points stay
 * on the manager and now delegate to this service so existing callers
 * and test spies keep working unchanged.
 *
 * <p>Threshold and over-provisioning configuration is read via
 * {@link com.cloud.capacity.CapacityManager} static {@link
 * org.apache.cloudstack.framework.config.ConfigKey} instances, exactly
 * as on the legacy manager.
 */
public interface StorageCapacityService {

    /**
     * Look up the storage over-provisioning factor configured for
     * {@code poolId} and return it as a {@link BigDecimal} suitable for
     * use in pool-capacity multiplication (the wrapping in a
     * {@code BigDecimal} avoids float precision loss when multiplying
     * the factor by raw capacity bytes).
     */
    BigDecimal getStorageOverProvisioningFactor(Long poolId);

    /**
     * Insert or update the {@code cloud.capacity} row for
     * {@code storagePool} and {@code capacityType}. The total is
     * computed from the pool's
     * {@link com.cloud.storage.Storage.StoragePoolType#supportsOverProvisioning()
     * over-provisioning support}: pools that support it are multiplied
     * by the configured factor (see
     * {@link #getStorageOverProvisioningFactor(Long)}); pools that do
     * not get their raw capacity bytes. The capacity-state is derived
     * from the zone for zone-wide pools, the cluster for cluster-wide
     * pools, and the single host for host-scoped (local) pools.
     */
    void createCapacityEntry(StoragePoolVO storagePool, short capacityType, long allocated);

    /**
     * Convenience wrapper: look up the pool by id and call
     * {@link #createCapacityEntry(StoragePoolVO, short, long)}
     * with {@code capacityType = CAPACITY_TYPE_STORAGE_ALLOCATED} and
     * {@code allocated = 0}.
     */
    void createCapacityEntry(long poolId);

    /**
     * Sum the {@link com.cloud.storage.StorageStats} for one or all
     * secondary stores in scope: when {@code hostId} is non-null only
     * that single store is consulted, otherwise every image store in
     * the given zone. Returns a {@link CapacityVO} of type
     * {@code CAPACITY_TYPE_SECONDARY_STORAGE} with the accumulated
     * used / total bytes.
     */
    CapacityVO getSecondaryStorageUsedStats(Long hostId, Long zoneId);

    /**
     * Sum the {@link com.cloud.storage.StorageStats} for a single
     * primary storage pool, identified by {@code poolId} (the other
     * scoping args are accepted but only used as additional filters
     * on {@code zone / pod / cluster}). Returns a {@link CapacityVO}
     * of type {@code CAPACITY_TYPE_STORAGE}.
     */
    CapacityVO getStoragePoolUsedStats(Long poolId, Long clusterId, Long podId, Long zoneId);

    /**
     * Sum the {@link com.cloud.storage.StorageStats} for every primary
     * storage pool that matches the supplied scope filters. Child
     * datastores of a datastore-cluster parent are excluded
     * ({@code parent = 0}). Returns a {@link CapacityVO} of type
     * {@code CAPACITY_TYPE_STORAGE}.
     */
    CapacityVO getStoragePoolUsedStats(Long zoneId, Long podId, Long clusterId, List<Long> poolIds);

    /**
     * Sum {@code allocatedSize} and {@code totalSize} across every
     * configured object store, returning a {@link CapacityVO} of type
     * {@code CAPACITY_TYPE_OBJECT_STORAGE} for {@code zoneId}. Null
     * values on a store are skipped.
     */
    CapacityVO getObjectStorageUsedStats(Long zoneId);

    /**
     * Return {@code true} when {@code pool} can currently absorb the
     * IOPS implied by every volume in {@code requestedVolumes}. Pools
     * that do not declare an IOPS capacity (e.g. non IOPS-guaranteed
     * storage) are treated as always-sufficient. The per-volume request
     * is taken from the volume's {@code minIops} (or the disk profile's
     * when the offering changed underneath).
     */
    boolean storagePoolHasEnoughIops(List<Pair<Volume, DiskProfile>> requestedVolumes, StoragePool pool);

    /**
     * Return {@code true} when {@code pool} can currently absorb
     * {@code requestedIops} additional IOPS, on top of what
     * {@link com.cloud.capacity.CapacityManager#getUsedIops} reports.
     * Returns {@code true} immediately if the pool does not declare an
     * IOPS capacity, {@code false} if {@code pool} is null, and
     * {@code true} if {@code requestedIops} is null or zero.
     */
    boolean storagePoolHasEnoughIops(Long requestedIops, StoragePool pool);

    /**
     * Return {@code true} when {@code pool} has enough free space for
     * {@code size} additional bytes, honouring the over-provisioning
     * factor and the {@code pool.storage.allocated.capacity.disablethreshold}
     * config knob.
     */
    boolean storagePoolHasEnoughSpace(Long size, StoragePool pool);

    /**
     * Convenience wrapper that defers to
     * {@link #storagePoolHasEnoughSpace(List, StoragePool, Long)} with
     * a null {@code clusterId}.
     */
    boolean storagePoolHasEnoughSpace(List<Pair<Volume, DiskProfile>> volumeDiskProfilePairs, StoragePool pool);

    /**
     * Return {@code true} when {@code pool} has enough free space for
     * all volumes in {@code volumeDiskProfilesList}, accounting for
     * managed-storage template seeding and per-volume hypervisor
     * snapshot reserve. Also enforces the
     * {@code pool.storage.capacity.disablethreshold} and
     * {@code pool.storage.allocated.capacity.disablethreshold} config
     * knobs, and re-reads each volume from the DB to pick up any
     * updates to {@code hv_ss_reserve}.
     */
    boolean storagePoolHasEnoughSpace(List<Pair<Volume, DiskProfile>> volumeDiskProfilesList, StoragePool pool, Long clusterId);

    /**
     * Return {@code true} when growing a volume from {@code currentSize}
     * to {@code newSize} in {@code pool} would not exceed thresholds.
     * Shrinks ({@code newSize <= currentSize}) are accepted
     * unconditionally. Allows growth beyond the configured allocation
     * threshold only when
     * {@link StorageManager#AllowVolumeReSizeBeyondAllocation} is set
     * and the larger
     * {@code pool.storage.allocated.capacity.disablethreshold.volume.size}
     * threshold also accommodates the change.
     */
    boolean storagePoolHasEnoughSpaceForResize(StoragePool pool, long currentSize, long newSize);
}
