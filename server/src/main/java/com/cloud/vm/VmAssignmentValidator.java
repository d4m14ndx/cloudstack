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

import java.util.List;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.VolumeVO;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;

/**
 * Pre-flight validation helpers for the {@code assignVMToAccount}
 * ("move VM to another user") flow — checking that the VM is in a
 * movable state, has no networking rules that would dangle, owns no
 * volumes with active snapshots, and that the new owner is real,
 * accessible, and has rights to the VM's template.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 7). {@code UserVmManagerImpl}
 * keeps delegating wrappers so the {@link UserVmManager} interface
 * contract and existing test spies continue to work.
 */
public interface VmAssignmentValidator {

    /**
     * Reject the assign request when the VM does not exist, is currently
     * {@link com.cloud.vm.VirtualMachine.State#Running}, or is a shared
     * file system instance (which cannot be moved).
     */
    void validateIfVmSupportsMigration(UserVmVO vm, Long vmId);

    /**
     * Reject the assign request when the VM has any port-forwarding,
     * static-NAT, load-balancing, or 1:1 NAT rules attached.
     */
    void validateIfVmHasNoRules(UserVmVO vm, Long vmId) throws InvalidParameterValueException;

    /**
     * Reject the assign request when any of the VM's volumes has live
     * (non-{@code Destroyed} / non-{@code Error}) snapshots.
     */
    void validateIfVolumesHaveNoSnapshots(List<VolumeVO> volumes) throws InvalidParameterValueException;

    /**
     * Reject the assign request when the new owner cannot {@code UseEntry}
     * the VM's template, or the template is missing.
     */
    void validateIfNewOwnerHasAccessToTemplate(UserVmVO vm, Account newAccount, VirtualMachineTemplate template);

    /**
     * Reject when either account is missing, when the new account is
     * disabled, or when the assign would be a no-op (same account).
     */
    void validateOldAndNewAccounts(Account oldAccount,
                                   Account newAccount,
                                   Long oldAccountId,
                                   String newAccountName,
                                   Long domainId) throws InvalidParameterValueException;

    /**
     * Run an access check for both the old and the new account against
     * the caller, throwing if either is denied.
     */
    void checkCallerAccessToAccounts(Account caller, Account oldAccount, Account newAccount);
}
