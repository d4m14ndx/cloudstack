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
 * VM usage-event publication helpers — a single VM-level usage event
 * (with dynamic-offering parameters when applicable), per-NIC network
 * offering events, and the state-aware bulk publish that fires when a
 * VM's {@code displayVm} flag flips.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 16). The god class keeps
 * one-line delegating wrappers so existing test spies and the
 * {@link UserVmManager#generateUsageEvent} interface contract continue
 * to work.
 */
public interface VmUsageEventPublisher {

    /**
     * Publish a single usage event for {@code vm}, attaching the VM's
     * static service-offering ID for fixed offerings, or the per-VM
     * cpu/speed/memory values for dynamic offerings.
     */
    void generateUsageEvent(VirtualMachine vm, boolean isDisplay, String eventType);

    /**
     * Publish a per-NIC network-offering usage event for every NIC on
     * {@code vm}. Used when a VM's display flag flips so that billing
     * picks up the network-offering portion of the change.
     */
    void generateNetworkUsageForVm(VirtualMachine vm, boolean isDisplay, String eventType);

    /**
     * After a {@code displayVm} flag change, fire the right
     * combination of VM-create/start/destroy/stop usage events plus the
     * network-offering events — the exact set depends on the new
     * display state and the VM's current run state. No-op when the VM
     * is in {@code Destroyed}, {@code Expunging}, or {@code Error}.
     */
    void saveUsageEvent(UserVmVO vm);
}
