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

import org.apache.cloudstack.api.command.admin.vm.RecoverVMCmd;

import com.cloud.exception.ResourceAllocationException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.storage.VolumeVO;
import com.cloud.uservm.UserVm;

/**
 * Handles the VM recovery lifecycle — restoring a Destroyed VM and its root
 * volume back to a running-eligible state.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 21). {@code UserVmManagerImpl}
 * keeps one-line delegating wrappers so the {@link UserVmManager}
 * interface contract and existing test spies continue to work.
 */
public interface VmRecoveryService {

    /**
     * Entry-point for the {@code recoverVirtualMachine} API command.
     * Validates caller permissions and VM state, transitions the VM out of
     * Destroyed state, recovers its root volume, and increments resource counts.
     */
    UserVm recoverVirtualMachine(RecoverVMCmd cmd) throws ResourceAllocationException, CloudRuntimeException;

    /**
     * Recovers the root volume for the given VM. If the volume is in Destroy
     * state it is recovered and re-attached; otherwise a creation usage event
     * is published.
     */
    void recoverRootVolume(VolumeVO volume, Long vmId);
}
