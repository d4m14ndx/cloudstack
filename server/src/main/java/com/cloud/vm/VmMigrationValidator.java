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

import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.StoragePool;
import com.cloud.template.VirtualMachineTemplate;

/**
 * Pre-flight validation helpers for VM migration and storage migration —
 * checking that the migration target host or storage pool is compatible
 * with the VM (hypervisor type, host tags, storage access groups,
 * dedication, maintenance state) and that the VM itself is in a
 * migration-eligible state.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 9). {@code UserVmManagerImpl}
 * keeps delegating wrappers so the {@link UserVmManager} interface
 * contract and existing test spies continue to work.
 */
public interface VmMigrationValidator {

    /**
     * Verify that the caller is a root admin, the VM exists in a
     * migration-eligible state (Stopped, supported hypervisor, no
     * snapshots, no extra data disks on unsupported hypervisors), and
     * return the resolved VMInstanceVO.
     */
    VMInstanceVO preVmStorageMigrationCheck(Long vmId);

    /**
     * Reject when the destination pool does not share at least one
     * storage access group with the VM's current host.
     */
    void checkIfDestinationPoolHasSameStorageAccessGroups(StoragePool destPool, VMInstanceVO vm);

    /**
     * Reject when the destination pool's hypervisor type doesn't match
     * the VM's (with {@code Any} as a wildcard).
     */
    void checkDestinationHypervisorType(StoragePool destPool, VMInstanceVO vm);

    /**
     * Reject when the source host has storage access groups but the
     * destination host has none, or has a non-superset of them.
     */
    void validateStorageAccessGroupsOnHosts(Host srcHost, Host destinationHost);

    /**
     * Returns {@code true} when the destination host carries the strict
     * tags required by the VM's service offering and template.
     */
    boolean checkEnforceStrictHostTagCheck(VMInstanceVO vm, HostVO host);

    /**
     * Variant that accepts the offering/template directly so callers
     * that already have them resolved don't reload.
     */
    boolean checkEnforceStrictHostTagCheck(HostVO host, ServiceOffering serviceOffering, VirtualMachineTemplate template);

    /**
     * Reject when the destination host does not carry the strict tags
     * required by the VM's service offering and template.
     */
    void validateStrictHostTagCheck(VMInstanceVO vm, HostVO host);

    /**
     * Returns {@code true} when the host (or its cluster or pod) has a
     * dedicated-resource record.
     */
    boolean checkIfHostIsDedicated(HostVO host);

    /**
     * Reject when the VM's current host is in
     * {@code PrepareForMaintenance} state.
     */
    void checkIfHostOfVMIsInPrepareForMaintenanceState(VirtualMachine vm, String operation);

    /**
     * Returns {@code true} when the VM's hypervisor supports being
     * migrated (KVM/VMware/XenServer/Hyperv/LXC/Simulator).
     */
    boolean isOnSupportedHypevisorForMigration(VMInstanceVO vm);
}
