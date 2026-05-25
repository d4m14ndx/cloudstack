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

import org.apache.cloudstack.api.command.user.vm.ResetVMPasswordCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMSSHKeyCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMUserDataCmd;

import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.storage.VMTemplateVO;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;

/**
 * Handles password, SSH-key, and user-data reset operations for virtual machines.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 22). {@code UserVmManagerImpl}
 * keeps one-line delegating wrappers so the {@link UserVmManager}
 * interface contract and existing test spies continue to work.
 */
public interface VmPasswordSSHKeyResetService {

    /**
     * Entry-point for the {@code resetVMPassword} API command.
     * Validates state, resets the password via the network element, and returns
     * the updated VM.
     */
    UserVm resetVMPassword(ResetVMPasswordCmd cmd, String password)
            throws ResourceUnavailableException, InsufficientCapacityException;

    /**
     * Internal implementation of password reset via the network element.
     * Encrypts and stores the new password; reboots the VM if it is running.
     */
    boolean resetVMPasswordInternal(Long vmId, String password)
            throws ResourceUnavailableException, InsufficientCapacityException;

    /**
     * Entry-point for the {@code resetVMUserData} API command.
     * Validates state and updates the user-data fields on the VM record.
     */
    UserVm resetVMUserData(ResetVMUserDataCmd cmd)
            throws ResourceUnavailableException, InsufficientCapacityException;

    /**
     * Entry-point for the {@code resetVMSSHKey} API command.
     * Validates state, resolves key pairs, and delegates to the internal overload.
     */
    UserVm resetVMSSHKey(ResetVMSSHKeyCmd cmd)
            throws ResourceUnavailableException, InsufficientCapacityException;

    /**
     * Internal implementation of SSH-key reset for a given VM, owner, and key-pair names.
     * Saves the new key via the network element; reboots the VM if it is running.
     */
    UserVmVO resetVMSSHKeyInternal(UserVmVO userVm, Account owner, List<String> names)
            throws ResourceUnavailableException, InsufficientCapacityException;

    /**
     * Determines the effective VM password to use during start/deploy.
     * Returns the existing encrypted password, the caller-supplied password, or a
     * freshly generated random password — depending on template and VM state.
     */
    String getCurrentVmPasswordOrDefineNewPassword(String newPassword, UserVmVO vm, VMTemplateVO template);
}
