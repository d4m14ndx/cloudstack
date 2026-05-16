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

import com.cloud.user.Account;
import com.cloud.uservm.UserVm;

/**
 * Permission checks gating the destructive end of the VM lifecycle —
 * expunging a VM, forcing a stop, and consulting plugin-side
 * gatekeepers (Kubernetes cluster manager) that may veto a destroy.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 11). {@code UserVmManagerImpl}
 * keeps delegating wrappers so the {@link UserVmManager} interface
 * contract and existing test spies continue to work.
 */
public interface VmDestroyPermissionService {

    /**
     * Reject the expunge request when the caller is not an admin and
     * the {@code allow.user.expunge.recover.vm} global is {@code false}
     * for the caller's account, or when the caller's role does not
     * grant API access to {@code expungeVirtualMachine}.
     */
    void checkExpungeVmPermission(Account callingAccount, String apiKey);

    /**
     * Encapsulates the lookup of the {@code allow.user.expunge.recover.vm}
     * config value so it can be stubbed in unit tests.
     */
    boolean isUserExpungeRecoverVmAllowed(Long accountId);

    /**
     * Consult the Kubernetes service helper, if present, to determine
     * whether the VM is a member of a Kubernetes cluster (which would
     * forbid the destroy). Tolerates the helper bean being absent.
     */
    void checkPluginsIfVmCanBeDestroyed(UserVm vm);

    /**
     * Reject the forced-stop request when the caller is not permitted
     * by the {@code allow.user.force.stop.vm} global for the caller's
     * account.
     */
    void checkForceStopVmPermission(Account callingAccount);
}
