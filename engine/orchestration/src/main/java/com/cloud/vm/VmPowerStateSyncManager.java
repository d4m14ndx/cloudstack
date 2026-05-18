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
 * Handles out-of-band VM power-state reports and scanning for stalled
 * VMs in transitional states.
 *
 * Extracted from {@link VirtualMachineManagerImpl} (Phase 4, slice 6).
 */
public interface VmPowerStateSyncManager {

    /**
     * Processes a VM power-state report (routed from the message-bus handler on the god class).
     *
     * @param vmId the VM whose power state changed
     */
    void handlePowerStateReport(Long vmId);

    /**
     * Scans VMs in transition states on an UP host and resolves them.
     *
     * @param hostId the agent/host ID
     */
    void scanStalledVMInTransitionStateOnUpHost(long hostId);

    /**
     * Scans VMs in transition states on disconnected hosts and sends alerts.
     */
    void scanStalledVMInTransitionStateOnDisconnectedHosts();
}
