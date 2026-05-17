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

import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.uservm.UserVm;

/**
 * Helpers for VM credential-reset operations: userdata propagation,
 * userdata finalization, password encryption, and SSH-key detail
 * management.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 15). The god class keeps
 * one-line delegating wrappers so existing test spies and call sites
 * continue to work.
 */
public interface VmCredentialResetService {

    /**
     * Push updated userdata to all networks attached to {@code vm},
     * throwing {@link com.cloud.utils.exception.CloudRuntimeException}
     * if no network accepts the update.
     */
    void updateUserData(UserVm vm) throws ResourceUnavailableException, InsufficientCapacityException;

    /**
     * Attempt to push userdata for {@code vm} to every attached NIC;
     * returns {@code true} if at least one NIC accepted the update.
     */
    boolean applyUserData(HypervisorType hyperVisorType, UserVm vm, Nic nic)
            throws ResourceUnavailableException, InsufficientCapacityException;

    /**
     * Resolve the effective userdata string for a VM, applying the
     * template's {@code UserDataOverridePolicy} when the template itself
     * carries a userdata payload.
     *
     * <p>Returns {@code null} when no userdata is present anywhere.
     *
     * @throws com.cloud.exception.InvalidParameterValueException
     *         if both {@code userData} and {@code userDataId} are supplied,
     *         or if the template's policy is {@code DENYOVERRIDE} and the
     *         caller tried to provide userdata.
     */
    String finalizeUserData(String userData, Long userDataId, VirtualMachineTemplate template);

    /**
     * Encrypt {@code password} with the VM's SSH public key (if one is
     * stored in the VM's details) and persist the encrypted value in
     * {@code vm_details} under
     * {@link com.cloud.vm.VmDetailConstants#ENCRYPTED_PASSWORD}.  A
     * no-op when no RSA public key is present.
     */
    void encryptAndStorePassword(UserVmVO vm, String password);

    /**
     * Remove the encrypted-password entry from the VM's detail map.
     * Called after an SSH key pair reset to invalidate the old
     * key-encrypted password.
     */
    void removeEncryptedPasswordFromUserVmVoDetails(long vmId);
}
