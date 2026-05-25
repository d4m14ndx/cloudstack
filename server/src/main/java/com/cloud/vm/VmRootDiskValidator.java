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
package com.cloud.vm;

import java.util.Map;

import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.DiskOffering;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VMTemplateVO;

/**
 * Validation and sizing helpers for a VM's root disk — figuring out the
 * effective root disk size from a disk offering plus optional caller-supplied
 * overrides, and checking whether a resize is permitted by the hypervisor.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 4). {@code UserVmManagerImpl} keeps
 * delegating wrappers so the {@link UserVmManager} interface contract and
 * existing test spies continue to work.
 */
public interface VmRootDiskValidator {

    /**
     * Resolve the effective disk size from a {@link DiskOffering} plus an
     * optional caller-supplied size override.
     *
     * @return the size in bytes, applying validation rules on min/max
     * @throws com.cloud.exception.InvalidParameterValueException on
     *         missing/invalid sizes
     */
    long verifyAndGetDiskSize(DiskOffering diskOffering, Long diskSize);

    /**
     * Resolve the effective root disk size from optional caller custom params,
     * the template, and the root disk offering. Mutates {@code customParameters}
     * to write back the canonical {@code rootdisksize} when the service offering
     * dictates a non-zero size.
     *
     * @return the resulting size in bytes (0 for baremetal / size-less templates)
     */
    long configureCustomRootDiskSize(Map<String, String> customParameters,
                                     VMTemplateVO template,
                                     HypervisorType hypervisorType,
                                     DiskOfferingVO rootDiskOffering);

    /**
     * Only KVM, XenServer, and VMware currently support root disk size override.
     *
     * @throws com.cloud.exception.InvalidParameterValueException for other hypervisors
     */
    void verifyIfHypervisorSupportsRootdiskSizeOverride(HypervisorType hypervisorType);

    /**
     * Validate a root disk resize request against the template and hypervisor's
     * supported controller types. Mutates {@code customParameters} to clear the
     * size override when it matches the template size exactly.
     */
    void validateRootDiskResize(HypervisorType hypervisorType,
                                Long rootDiskSize,
                                VMTemplateVO templateVO,
                                UserVmVO vm,
                                Map<String, String> customParameters);
}
