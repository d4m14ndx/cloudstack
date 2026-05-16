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

import com.cloud.offering.ServiceOffering;

/**
 * Service-offering upgrade persistence helpers — applying a new
 * {@link ServiceOffering} to an existing VM, updating the customizable
 * dynamic-offering detail map, and reporting whether the VM's ROOT
 * volume currently sits on local storage (used by upgrade-eligibility
 * checks before the new offering is committed).
 *
 * <p>Extracted from {@link VirtualMachineManagerImpl} as part of the
 * Phase 4 Spring-component decomposition.
 * {@code VirtualMachineManagerImpl} retains the
 * {@code checkIfCanUpgrade} and
 * {@code checkIfNewOfferingStorageScopeMatchesStoragePool}
 * orchestration so existing {@code @Spy}-stubbed tests on
 * {@code isRootVolumeOnLocalStorage} keep firing through the wrapper.
 */
public interface VmServiceOfferingUpgradeManager {

    /**
     * Returns {@code true} when the VM's ROOT volume is allocated on a
     * pool whose scope is {@link com.cloud.storage.ScopeType#HOST}
     * (i.e. local storage). A VM with no ROOT volume yet is treated as
     * shared (returns {@code false}).
     */
    boolean isRootVolumeOnLocalStorage(long vmId);

    /**
     * Update the VM row to reference {@code newServiceOffering},
     * mirroring its HA / cpu-limit / dynamically-scalable flags,
     * persisting the customizable detail trio
     * (cpuNumber/cpuSpeed/memory) when the new offering is dynamic,
     * and clearing those details when the previous offering was
     * dynamic but the new one is not.
     */
    boolean upgradeVmDb(long vmId, ServiceOffering newServiceOffering,
                        ServiceOffering currentServiceOffering);

    /**
     * Strip the customizable dynamic-offering detail trio
     * (cpuNumber/cpuSpeed/memory) from the VM detail map while
     * preserving every other detail row. Used when moving a VM from a
     * dynamic offering to a static one.
     */
    void removeCustomOfferingDetails(long vmId);

    /**
     * Persist the customizable dynamic-offering detail trio
     * (cpuNumber/cpuSpeed/memory) for the VM, but only for fields the
     * underlying offering itself leaves unfilled — this matches the
     * VM-snapshot restore invariant in
     * {@code UserVmManagerImpl.validateCustomParameters}, which rejects
     * persisted details for non-customizable parameters.
     */
    void saveCustomOfferingDetails(long vmId, ServiceOffering serviceOffering);
}
