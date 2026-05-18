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
package com.cloud.resource;

import java.util.List;

import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.vm.VMInstanceVO;

/**
 * Side-effect-free helpers that support the host maintenance state machine
 * extracted from {@link ResourceManagerImpl}.
 *
 * <p>This slice intentionally excludes the spy-verified orchestration
 * methods ({@code attemptMaintain}, {@code setHostIntoMaintenance},
 * {@code setHostIntoErrorInMaintenance}, {@code setKVMVncAccess},
 * {@code resourceStateTransitTo}, {@code handleAgentIfNotConnected},
 * {@code configureVncAccessForKVMHostFailedMigrations}) — those remain on
 * {@link ResourceManagerImpl} where the existing
 * {@code verify(resourceManager)...} test assertions continue to work
 * unchanged.
 *
 * <p>Operations covered:
 * <ul>
 *   <li>{@code host.maintenance.local.storage.strategy} interpretation —
 *       {@link #isMaintenanceLocalStrategyMigrate()},
 *       {@link #isMaintenanceLocalStrategyForceStop()},
 *       {@link #isMaintenanceLocalStrategyDefault()}</li>
 *   <li>Cluster-wide migration eligibility for hosts entering
 *       maintenance — {@link #isClusterWideMigrationPossible(Host, List, List)}</li>
 *   <li>Storage-aware migration of an individual VM during maintenance —
 *       {@link #migrateAwayVmWithVolumes(HostVO, VMInstanceVO)}</li>
 *   <li>Last-resort handling for VMs that cannot be migrated
 *       (SystemVMs, vGPU VMs) — {@link #handleVmForLastHostOrWithVGpu(HostVO, VMInstanceVO)}</li>
 *   <li>Scheduling HA-managed restarts of VMs that lived on a host
 *       declared {@code Degraded} — {@link #scheduleVmsRestart(Host)}</li>
 * </ul>
 *
 * <p>Extracted from {@link ResourceManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 5).
 */
public interface HostMaintenanceService {

    /**
     * @return {@code true} when {@code host.maintenance.local.storage.strategy}
     *         is set to {@code Migration}.
     */
    boolean isMaintenanceLocalStrategyMigrate();

    /**
     * @return {@code true} when {@code host.maintenance.local.storage.strategy}
     *         is set to {@code ForceStop}.
     */
    boolean isMaintenanceLocalStrategyForceStop();

    /**
     * @return {@code true} when {@code host.maintenance.local.storage.strategy}
     *         is blank, null, or {@code Error} (the default behaviour).
     */
    boolean isMaintenanceLocalStrategyDefault();

    /**
     * Decide whether the {@code vms} running on the entering-maintenance
     * {@code host} can be migrated to hosts in other clusters in the same
     * zone. When {@link com.cloud.configuration.ConfigurationManagerImpl#MIGRATE_VM_ACROSS_CLUSTERS}
     * is enabled, the {@code hosts} collection is populated with the
     * candidate destinations (mutated in place).
     *
     * <p>SystemVMs are constrained to the same pod; cluster-wide volumes
     * disqualify non-VMware hypervisors.
     *
     * @return {@code true} when at least one valid destination host was
     *         found and no blocking constraint was hit.
     */
    boolean isClusterWideMigrationPossible(Host host, List<VMInstanceVO> vms, List<HostVO> hosts);

    /**
     * Look for a destination host able to receive {@code vm} together
     * with its local storage volume, and trigger a storage-migration via
     * {@link com.cloud.vm.VirtualMachineManager#migrateWithStorage}.
     *
     * @throws com.cloud.utils.exception.CloudRuntimeException when no
     *         destination can be found or the migration fails.
     */
    void migrateAwayVmWithVolumes(HostVO host, VMInstanceVO vm);

    /**
     * Schedule a destroy (for SystemVMs / ConsoleProxies) or a
     * {@code ForceStop} (for everything else) when no migration target
     * is available for {@code vm} while {@code host} enters maintenance.
     */
    void handleVmForLastHostOrWithVGpu(HostVO host, VMInstanceVO vm);

    /**
     * For a host that has just been transitioned to {@code Degraded},
     * ask the {@link com.cloud.ha.HighAvailabilityManager} to restart
     * any HA-eligible VMs that were running on it.
     */
    void scheduleVmsRestart(Host host);
}
