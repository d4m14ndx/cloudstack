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

import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;

import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.vm.UserVmVO;

/**
 * Pre-flight validation helpers for the attach/detach volume flows on
 * {@link VolumeApiServiceImpl} — checks that the target VM is in a
 * compatible state (no VM snapshots, no live backups blocking the
 * operation), that the volume's storage placement is allowed in the
 * target zone, that hypervisor types match across the VM and the
 * volume, that the requested device id is sane, and that the ROOT-volume
 * detach/attach prerequisites are met.
 *
 * <p>Extracted from {@link VolumeApiServiceImpl} as part of the Phase
 * 4 Spring-component decomposition (parallel slice, 2nd).
 * {@code VolumeApiServiceImpl} keeps delegating wrappers so the
 * orchestration in {@link VolumeApiServiceImpl#attachVolumeToVM} and
 * {@code detachVolumeFromVM} can still be exercised by the existing
 * spy-verified manager-level tests, while the underlying logic is
 * now covered by focused unit tests against this component.
 */
public interface VolumeAttachValidator {

    /**
     * When the source VM's root disk and the volume to attach are tied
     * to a hypervisor type that needs to match (i.e. {@code checkNeeded}
     * is true because the volume is on a non-managed pool), reject the
     * attach if the volume's hypervisor type is set and differs from
     * the VM's.
     */
    void checkForMatchingHypervisorTypesIf(boolean checkNeeded,
                                            HypervisorType rootDiskHyperType,
                                            HypervisorType volumeToAttachHyperType);

    /**
     * Reject the attach when the target VM already has any VM
     * snapshots — attaching a new disk to a VM in that state would
     * leave the snapshot chain inconsistent.
     */
    void checkForVMSnapshots(Long vmId, UserVmVO vm);

    /**
     * Reject the attach when the volume's disk offering is configured
     * for local storage but the zone is not configured to use local
     * storage at all.
     */
    void excludeLocalStorageIfNeeded(VolumeInfo volumeToAttach);

    /**
     * Reject the attach when the requested {@code deviceId} is 0 (the
     * ROOT slot) but the VM either already has a root volume, or its
     * hypervisor/state/pool combination does not permit a ROOT
     * detach/attach.
     */
    void checkDeviceId(Long deviceId, VolumeInfo volumeToAttach, UserVmVO vm);

    /**
     * Reject a ROOT-volume detach or re-attach when the VM's
     * hypervisor doesn't support it, when the VM is in a state other
     * than {@code Stopped}, or when the volume is on a managed pool.
     */
    void validateRootVolumeDetachAttach(VolumeVO volume, UserVmVO vm);

    /**
     * Compute the amount of primary storage that must be reserved for
     * the attach. Returns zero when no resource-limit storage tags
     * apply to the volume's disk offering, or when the volume is
     * already in a state where its size is already counted against
     * the quota.
     */
    Long getRequiredPrimaryStorageSizeForVolumeAttach(List<String> resourceLimitStorageTags,
                                                      VolumeInfo volumeToAttach);

    /**
     * Reject the attach or detach when the VM is registered against a
     * backup offering, has live backup volumes recorded, and the
     * "allow attach/detach for backed-up VMs" global config is off.
     * The {@code attach} flag only affects the error message phrasing.
     */
    void checkForBackups(UserVmVO vm, boolean attach);
}
