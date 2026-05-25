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
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.uservm.UserVm;

import org.apache.cloudstack.api.command.user.vm.RebootVMCmd;

/**
 * Handles the VM reboot lifecycle — normal and forced reboot, including
 * router pre-start for advanced networks and volatile-VM restore.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 20). {@code UserVmManagerImpl}
 * keeps a one-line delegating wrapper so the {@link UserVmManager}
 * interface contract and existing test spies continue to work.
 */
public interface VmRebootService {

    /**
     * Entry-point for the {@code rebootVirtualMachine} API command.
     * Validates the caller's access, checks host-maintenance state,
     * handles volatile-VM restore, and delegates to the internal
     * reboot helpers.
     */
    UserVm rebootVirtualMachine(RebootVMCmd cmd)
            throws InsufficientCapacityException, ResourceUnavailableException, ResourceAllocationException;

    /**
     * Internal reboot of a running VM identified by {@code vmId}.
     * Starts any stopped routers for advanced networks before issuing the
     * hypervisor reboot. Returns {@code null} when the VM is not in
     * {@link VirtualMachine.State#Running} state.
     */
    UserVm rebootVirtualMachineInternal(long userId, long vmId, boolean enterSetup, boolean forced)
            throws InsufficientCapacityException, ResourceUnavailableException;

    /**
     * Force-reboot by issuing a hard stop followed by an immediate start
     * on the same host. Used when the target host is healthy but the guest
     * OS is unresponsive to a soft reboot.
     */
    UserVm forceRebootVirtualMachine(UserVmVO vm, long hostId, boolean enterSetup);
}
