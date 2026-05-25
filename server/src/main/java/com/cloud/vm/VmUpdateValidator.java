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

import org.apache.cloudstack.api.command.user.vm.UpdateVMCmd;

/**
 * Input validation and detail-merging helpers for the
 * {@code updateVirtualMachine} flow — checking that the VM and (optional)
 * guest OS exist, that the caller has access, and merging per-VM detail
 * overrides with the values dictated by a (possibly new) service offering.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 5). {@code UserVmManagerImpl} keeps
 * delegating wrappers so the {@link UserVmManager} interface contract and
 * existing test spies continue to work.
 */
public interface VmUpdateValidator {

    /**
     * Look up the VM referenced by the command, validate its guest-OS id
     * (if supplied), and check that the calling account has access to it.
     *
     * @throws com.cloud.exception.InvalidParameterValueException if the VM
     *         or guest OS cannot be found
     */
    void validateInputsAndPermissionForUpdateVirtualMachineCommand(UpdateVMCmd cmd);

    /**
     * Validate that the guest OS id on the command (when present) resolves
     * to an existing record.
     */
    void validateGuestOsIdForUpdateVirtualMachineCommand(UpdateVMCmd cmd);

    /**
     * For the standard scaling triplet ({@value VmDetailConstants#CPU_SPEED},
     * {@value VmDetailConstants#MEMORY}, {@value VmDetailConstants#CPU_NUMBER}),
     * fill in values from the current service offering whenever the new
     * service offering doesn't dictate them and the caller didn't pass an
     * override. Mutates {@code details} in place; does not touch the database.
     */
    void updateInstanceDetailsMapWithCurrentValuesForAbsentDetails(Map<String, String> details,
                                                                   VirtualMachine vmInstance,
                                                                   Long newServiceOfferingId);

    /**
     * Helper used by {@link #updateInstanceDetailsMapWithCurrentValuesForAbsentDetails}.
     * If neither the new value nor an existing entry under {@code detailKey}
     * is present, write the current value into {@code details}.
     */
    void addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(Integer newValue,
                                                                           Map<String, String> details,
                                                                           String detailKey,
                                                                           Integer currentValue);
}
