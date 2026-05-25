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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.cloud.host.Host;

/**
 * Per-host VM statistics collector — issues the agent
 * {@code GetVm*StatsCommand} family against a target host, parses the
 * answer's name-keyed map, and re-keys the result by VM id so callers
 * (the central {@code StatsCollector} background loop, the
 * autoscale loop, etc.) see a uniform {@code Map<Long, ?>} shape.
 *
 * <p>Extracted from {@link VirtualMachineManagerImpl} as part of the
 * Phase 4 Spring-component decomposition. The public
 * {@link VirtualMachineManager} interface retains the three
 * {@code getVirtualMachineStatistics} / {@code getVmDiskStatistics} /
 * {@code getVmNetworkStatistics} overloads as thin wrappers that
 * delegate to this collector, so callers that already inject
 * {@code VirtualMachineManager} keep compiling unchanged.
 *
 * <p>Every method swallows agent unavailability and answer-failure
 * by returning an empty map — matching the legacy in-place behavior
 * relied on by the StatsCollector's per-host loop.
 */
public interface VmStatsCollector {

    /**
     * Convenience overload: resolve VM ids to instance names via
     * {@link com.cloud.vm.dao.VMInstanceDao#getNameIdMapForVmIds},
     * then forward to {@link #getVirtualMachineStatistics(Host, Map)}.
     * Returns an empty map when {@code vmIds} is empty.
     */
    HashMap<Long, ? extends VmStats> getVirtualMachineStatistics(Host host, List<Long> vmIds);

    /**
     * Issue a {@code GetVmStatsCommand} for the supplied
     * {@code instanceName -> vmId} map, then re-key the answer by id.
     * Returns an empty map when {@code vmInstanceNameIdMap} is empty,
     * the agent does not answer, the answer reports failure, or the
     * answer's stats map is null.
     */
    HashMap<Long, ? extends VmStats> getVirtualMachineStatistics(Host host, Map<String, Long> vmInstanceNameIdMap);

    /**
     * Same shape as {@link #getVirtualMachineStatistics(Host, Map)} but
     * sends a {@code GetVmDiskStatsCommand}; each map entry is a list
     * of per-disk statistics for that VM.
     */
    HashMap<Long, List<? extends VmDiskStats>> getVmDiskStatistics(Host host, Map<String, Long> vmInstanceNameIdMap);

    /**
     * Same shape as {@link #getVirtualMachineStatistics(Host, Map)} but
     * sends a {@code GetVmNetworkStatsCommand}; each map entry is a
     * list of per-nic statistics for that VM.
     */
    HashMap<Long, List<? extends VmNetworkStats>> getVmNetworkStatistics(Host host, Map<String, Long> vmInstanceNameIdMap);
}
