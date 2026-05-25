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

import com.cloud.vm.VMInstanceVO;

/**
 * Pre-flight validation helpers for the resize volume flow on
 * {@link VolumeApiServiceImpl} — checks that the requested resize is
 * feasible: the volume is in a hypervisor and state that supports
 * resize, no concurrent snapshot is in flight, the ROOT-volume/VM
 * power-state combination is allowed, the IOPS limits are coherent
 * with the storage pool, and (when a new disk offering is supplied)
 * the new offering's size/IOPS/tags are compatible with the existing
 * volume and offering.
 *
 * <p>Extracted from {@link VolumeApiServiceImpl} as part of the Phase
 * 4 Spring-component decomposition (parallel slice, 3rd).
 * {@code VolumeApiServiceImpl} keeps thin delegating wrappers for the
 * entry-point helpers, so the orchestration in
 * {@link VolumeApiServiceImpl#resizeVolume} and
 * {@link VolumeApiServiceImpl#changeDiskOfferingForVolumeInternal}
 * can still be exercised by the existing spy-verified manager-level
 * tests, while the underlying logic is now covered by focused unit
 * tests against this component.
 */
public interface VolumeResizeValidator {

    /**
     * A volume should not be resized if it covers ALL the following
     * scenarios:
     * <ol>
     *   <li>It is a ROOT volume.</li>
     *   <li>Its current disk offering enforces a fixed root disk size
     *       (i.e. compute-only with a non-zero disk size). In that
     *       case, root-disk size can only change by switching to a
     *       new service offering.</li>
     *   <li>The backing template is not an ISO (ISO-backed roots get
     *       a free-form data disk and can still be resized).</li>
     * </ol>
     */
    boolean isNotPossibleToResize(VolumeVO volume, DiskOfferingVO diskOffering);

    /**
     * Reject the resize when the volume is a ROOT disk, its size is
     * actually changing, and the owning VM is not in the
     * {@code Stopped} state.
     */
    void checkIfVolumeIsRootAndVmIsRunning(Long newSize, VolumeVO volume, VMInstanceVO vmInstanceVO);

    /**
     * Validate the requested {@code minIops}/{@code maxIops} pair.
     * Both must be supplied together (or both omitted), and
     * {@code minIops} cannot exceed {@code maxIops}. PowerFlex pools
     * are special-cased: they only accept {@code iopsLimit}, so
     * {@code minIops} is normalised to {@code 0} when {@code maxIops}
     * is set.
     */
    void validateIops(Long minIops, Long maxIops, Storage.StoragePoolType poolType);

    /**
     * Reject the resize when there is an in-flight snapshot on the
     * volume, when the volume's hypervisor type is not in the
     * resize-supported list, when the volume is not in a
     * Ready/Allocated state, when the operation would shrink a
     * VMware volume, or when a ROOT volume on VMware is not powered
     * off.
     */
    void validateVolumeReadyStateAndHypervisorChecks(VolumeVO volume, long currentSize, Long newSize);

    /**
     * Compute the {@code minIops}/{@code maxIops} that should be
     * applied after the resize. When the new offering is
     * customised-IOPS the existing volume's limits are kept (and
     * validated), otherwise the offering's own limits are used. The
     * resolved values are written back into the supplied
     * single-element arrays.
     */
    void setNewIopsLimits(VolumeVO volume, DiskOfferingVO newDiskOffering, Long[] newMinIops, Long[] newMaxIops);

    /**
     * Reject the change of disk offering when the new offering is
     * functionally identical to the existing one, when the
     * disk-size-strictness flags disagree, when the storage-pool tags
     * required by the offerings don't match, when the volume is a
     * ROOT disk tied to a service offering with strict disk offering
     * binding, or when the existing offering enforces a fixed size
     * that would be violated.
     */
    void checkIfVolumeCanResizeWithNewDiskOffering(VolumeVO volume,
                                                   DiskOfferingVO existingDiskOffering,
                                                   DiskOfferingVO newDiskOffering,
                                                   Long newSize,
                                                   VMInstanceVO vmInstanceVO);

    /**
     * Validate the requested resize against the supplied new disk
     * offering, resolve the effective new size / IOPS /
     * hypervisor-snapshot-reserve into the caller-provided
     * single-element arrays, and run the compatibility checks against
     * the existing offering and the owning VM. Throws an
     * {@link com.cloud.exception.InvalidParameterValueException} when
     * any of those checks fail.
     */
    void validateVolumeResizeWithNewDiskOfferingAndLoad(VolumeVO volume,
                                                        DiskOfferingVO existingDiskOffering,
                                                        DiskOfferingVO newDiskOffering,
                                                        Long[] newSize,
                                                        Long[] newMinIops,
                                                        Long[] newMaxIops,
                                                        Integer[] newHypervisorSnapshotReserve);
}
