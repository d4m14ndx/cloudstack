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

import com.cloud.offering.DiskOffering;
import com.cloud.utils.Pair;

/**
 * Compatibility checks between volumes, storage pools, and disk
 * offerings — the tag-matching logic that decides whether a disk
 * offering can be assigned to a pool, whether a pool can host an
 * offering, and the pre-flight validation run before changing a
 * volume's disk offering during migration.
 *
 * <p>Extracted from {@link VolumeApiServiceImpl} as part of the
 * Phase 4 Spring-component decomposition (parallel slice).
 * {@code VolumeApiServiceImpl} keeps delegating wrappers so the
 * {@link VolumeApiService} interface contract and existing test
 * spies for {@code getStoragePoolTags} continue to work.
 */
public interface DiskOfferingCompatibilityService {

    /**
     * Return the storage pool's tag set, paired with a flag indicating
     * whether the tags are to be interpreted as a tag-rule expression
     * (e.g. {@code tags[0] == 'A'}) or a plain list of tags.
     *
     * <p>If the storage pool has no tags configured at all, a {@code null}
     * pair is returned so callers can distinguish "no tags" from
     * "empty tag list".
     */
    Pair<List<String>, Boolean> resolveStoragePoolTags(StoragePool destPool);

    /**
     * Apply the tag-matching rules between a previously-resolved pair
     * of storage pool tags and a disk offering's comma-separated tag
     * string. Pure logic — does not touch any DAO. The destination pool
     * is provided only for log lines.
     */
    boolean storagePoolTagsMatchOfferingTags(StoragePool destPool,
                                             Pair<List<String>, Boolean> storagePoolTags,
                                             String diskOfferingTags);

    /**
     * Reject the disk-offering replacement when local/shared semantics
     * disagree between the destination pool and the new disk offering.
     */
    void validateBasicMigrationCompatibility(Volume volume, DiskOffering newDiskOffering, StoragePool destPool);

    /**
     * Reject the disk-offering replacement for ROOT volumes whose VM is
     * pinned to its service offering via {@code diskOfferingStrictness}.
     */
    void validateRootVolumeServiceOfferingStrictness(Volume volume);

    /**
     * Log a warning when the new disk offering's size differs from the
     * volume's current size — the migration is allowed to proceed but
     * the discrepancy is recorded for the operator.
     */
    void logSizeMismatchOnMigration(Volume volume, DiskOffering newDiskOffering);
}
