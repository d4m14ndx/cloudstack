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

import org.apache.cloudstack.api.command.user.volume.MigrateVolumeCmd;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

import com.cloud.vm.VMInstanceVO;

/**
 * Pre-flight validation helpers for the volume migration flow on
 * {@link VolumeApiServiceImpl} — verifies migration is allowed by the
 * source-and-destination storage pool combination, the owning VM
 * state, and the optional caller-supplied new {@link DiskOfferingVO}.
 *
 * <p>Extracted from {@link VolumeApiServiceImpl} as part of the Phase
 * 4 Spring-component decomposition (parallel slice, 7th).
 * {@code VolumeApiServiceImpl} keeps thin delegating wrappers for the
 * entry-point helpers, so the orchestration in
 * {@link VolumeApiServiceImpl#migrateVolume} can still be exercised by
 * the existing spy-verified manager-level tests, while the underlying
 * logic is now covered by focused unit tests against this component.
 */
public interface VolumeMigrationValidator {

    /**
     * Reject the migration when the owning VM is in a state that is
     * not supported for storage migration. Only {@code Stopped},
     * {@code Running}, and {@code Shutdown} states are allowed.
     */
    void checkVmStateForMigration(VMInstanceVO vm, VolumeVO vol);

    /**
     * Returns {@code true} when either the source or the destination
     * storage pool is NOT a StorPool pool. Used to short-circuit
     * KVM-on-StorPool live migration restrictions: only
     * StorPool-to-StorPool moves are supported by the StorPool live
     * migration strategy on KVM.
     */
    boolean isSourceOrDestNotOnStorPool(StoragePoolVO storagePoolVO, StoragePoolVO destinationStoragePoolVo);

    /**
     * Returns {@code true} when BOTH the source and the destination
     * storage pools are StorPool pools. Used to determine if encrypted
     * volume migration can proceed (StorPool-to-StorPool is the only
     * supported case besides PowerFlex).
     */
    boolean isSourceAndDestOnStorPool(StoragePoolVO storagePoolVO, StoragePoolVO destinationStoragePoolVo);

    /**
     * Retrieves the new disk offering supplied on the
     * {@link MigrateVolumeCmd} (if any) and validates it. Returns
     * {@code null} when no new offering is provided. Otherwise the
     * offering must exist, not be removed, and the calling account
     * must have access to it in the volume's zone.
     */
    DiskOfferingVO retrieveAndValidateNewDiskOffering(MigrateVolumeCmd cmd);
}
