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

import com.cloud.exception.ResourceAllocationException;
import com.cloud.user.Account;

/**
 * Snapshot-creation logic extracted from {@link VolumeApiServiceImpl}.
 *
 * <p>Covers the snapshot-allocation paths (policy-driven, manual, VM-snapshot
 * context) and the orchestration of actually taking a volume snapshot —
 * the cluster of methods that share {@code snapshotMgr}, {@code snapshotHelper}
 * and {@code snapshotPolicyDetailsDao}, which the rest of
 * {@link VolumeApiServiceImpl} rarely touches.</p>
 *
 * <p><strong>VmWork shim note:</strong> the async job dispatcher discovers
 * {@code orchestrate*(VmWork…)} methods on {@link VolumeApiServiceImpl} via
 * reflection. {@link VolumeApiServiceImpl} therefore keeps a 2-line
 * {@code @ReflectionUse} shim that delegates the
 * {@code VmWorkTakeVolumeSnapshot} work to
 * {@link #orchestrateTakeVolumeSnapshot(Long, Long, Long, Account, boolean,
 * Snapshot.LocationType, boolean, List, List)}.</p>
 *
 * <p>Extracted from {@link VolumeApiServiceImpl} as part of the Phase 4
 * Spring-component decomposition (parallel slice 8).</p>
 */
public interface VolumeTakeSnapshotService {

    /**
     * Core snapshot-allocation and take logic called from the public
     * {@link VolumeApiServiceImpl#takeSnapshot} entry point.
     *
     * <p>Validates volume state, resolves policy zone/pool IDs, serialises
     * through the VM work queue when the volume is attached to a running VM,
     * and falls back to a direct {@code volService.takeSnapshot} call for
     * detached volumes.</p>
     */
    Snapshot takeSnapshotInternal(Long volumeId, Long policyId, Long snapshotId, Account account,
            boolean quiescevm, Snapshot.LocationType locationType, boolean asyncBackup,
            List<Long> zoneIds, List<Long> poolIds, Boolean useStorageReplication)
            throws ResourceAllocationException;

    /**
     * Orchestration step invoked either directly (re-entrant job) or via the
     * VmWork async-job queue.  Builds the {@link CreateSnapshotPayload} and
     * delegates to {@code volService.takeSnapshot}.
     */
    Snapshot orchestrateTakeVolumeSnapshot(Long volumeId, Long policyId, Long snapshotId,
            Account account, boolean quiescevm, Snapshot.LocationType locationType,
            boolean asyncBackup, List<Long> zoneIds, List<Long> poolIds)
            throws ResourceAllocationException;

    /**
     * Allocates a snapshot record in CREATE_IN_PROGRESS state, performing all
     * pre-flight checks (zone state, volume state, template system-type check,
     * managed-storage location rules, cross-zone rules).
     */
    Snapshot allocSnapshot(Long volumeId, Long policyId, String snapshotName,
            Snapshot.LocationType locationType, List<Long> zoneIds, List<Long> poolIds,
            Boolean useStorageReplication) throws ResourceAllocationException;

    /**
     * Allocates a snapshot in the context of a VM-level snapshot operation.
     * Validates both the VM and the volume, checks PowerFlex and
     * file-based-storage-snapshot restrictions.
     */
    Snapshot allocSnapshotForVm(Long vmId, Long volumeId, String snapshotName, Long vmSnapshotId)
            throws ResourceAllocationException;

    /**
     * Submits a {@code VmWorkTakeVolumeSnapshot} job to the VM work queue so
     * the snapshot is serialised against other operations on the same VM.
     * Returns an {@link org.apache.cloudstack.framework.jobs.Outcome} that the
     * caller can wait on.
     */
    org.apache.cloudstack.framework.jobs.Outcome<Snapshot> takeVolumeSnapshotThroughJobQueue(
            Long vmId, Long volumeId, Long policyId, Long snapshotId, Long accountId,
            boolean quiesceVm, Snapshot.LocationType locationType, boolean asyncBackup,
            List<Long> zoneIds, List<Long> poolIds);
}
