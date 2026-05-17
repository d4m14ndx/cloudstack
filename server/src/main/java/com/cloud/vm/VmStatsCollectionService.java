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

import com.cloud.uservm.UserVm;

/**
 * Pre-stop usage-stat snapshot for a user VM.
 *
 * <p>Both methods reach out to the VM's host via {@code AgentManager.easySend}
 * to fetch live counters and reconcile them against the persistent
 * {@code user_statistics} / {@code vm_disk_statistics} tables in a single
 * transaction. They are best-effort: any agent error, missing answer, or
 * unsuccessful answer logs a warning and returns without raising.
 *
 * <p>Only KVM is supported for network stats; KVM and VMware for disk
 * stats. Other hypervisors are a no-op so callers can invoke these
 * unconditionally before a stop/destroy.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as slice 19 of the Phase 4
 * Spring-component decomposition. The god class keeps one-line delegating
 * wrappers so external callers ({@code VolumeApiServiceImpl},
 * {@code VirtualMachineManagerImpl}) and the {@code UserVmService}
 * contract are unchanged.
 */
public interface VmStatsCollectionService {

    /**
     * Snapshot network byte counters for {@code userVm} from its host
     * and reconcile the {@code user_statistics} row for each
     * DirectAttached NIC. KVM-only; other hypervisors return immediately.
     */
    void collectVmNetworkStatistics(UserVm userVm);

    /**
     * Snapshot disk IO/byte counters for {@code userVm} from its host
     * and reconcile the {@code vm_disk_statistics} row for each matched
     * volume. Supported on KVM and VMware; other hypervisors return
     * immediately.
     */
    void collectVmDiskStatistics(UserVm userVm);
}
