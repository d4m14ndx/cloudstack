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

/**
 * Helpers for applying a change to a VM's {@code displayVm} flag —
 * persists the flag on the {@link UserVmVO}, adjusts the owner's VM
 * resource count when the {@code resource.count.running.vms.only}
 * setting is off, fires the state-aware bulk usage events for the
 * flip, and cascades the new display value to the VM's ROOT and
 * DATADISK volumes.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 17). The god class keeps a
 * one-line {@code updateDisplayVmFlag} wrapper so existing test
 * spies that stub the protected method continue to work.
 */
public interface VmDisplayFlagService {

    /**
     * Apply a {@code displayVm} flip to {@code vmInstance}:
     * <ol>
     *   <li>set the flag on the VO,</li>
     *   <li>increment or decrement the owner's VM resource count
     *       (suppressed entirely when
     *       {@link VirtualMachineManager#ResourceCountRunningVMsonly}
     *       is enabled),</li>
     *   <li>fire the appropriate VM/network usage events for the new
     *       display state and the VM's current run state, and</li>
     *   <li>cascade the new display value to the VM's ROOT volume
     *       and every DATADISK volume.</li>
     * </ol>
     *
     * @param isDisplayVm the new display flag value
     * @param vmId        the VM id (used for the volume look-ups; the
     *                    caller already has the {@link UserVmVO} so
     *                    accepting the id keeps the call sites
     *                    unchanged)
     * @param vmInstance  the VO whose flag should be flipped
     */
    void applyDisplayFlag(Boolean isDisplayVm, Long vmId, UserVmVO vmInstance);
}
