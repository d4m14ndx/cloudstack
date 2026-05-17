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
import com.cloud.host.HostStats;
import com.cloud.host.HostVO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;

/**
 * Filtered host listing and per-host metadata reads extracted from
 * {@link ResourceManagerImpl}.
 *
 * <p>This slice covers the methods that combine <em>status / resource-state
 * filtering</em> with optional scope constraints (zone, cluster, pod), plus
 * the live agent round-trip in {@link #getHostStatistics(Host)} and the
 * detail / tag reads. The purely-structural finders (by GUID, name, cluster
 * membership without any status filter) live in {@link HostQueryService}.
 *
 * <p>Operations covered:
 * <ul>
 *   <li>broad filtered listings by type, scope (cluster / pod / zone), and
 *       status — Up / Up&amp;Enabled / Up&amp;Enabled&amp;non-HA variants
 *       ({@link #listAllHosts},
 *       {@link #listAllUpHosts},
 *       {@link #listAllUpAndEnabledHosts},
 *       {@link #listAllUpAndEnabledNonHAHosts})</li>
 *   <li>zone-scoped listings by type with status filter
 *       ({@link #listAllUpAndEnabledHostsInOneZoneByType},
 *       {@link #listAllNotInMaintenanceHostsInOneZone},
 *       {@link #listAllUpAndEnabledHostsInOneZone})</li>
 *   <li>hypervisor-scoped listings with status filter
 *       ({@link #listAllUpAndEnabledHostsInOneZoneByHypervisor},
 *       {@link #listAllUpHostsInOneZoneByHypervisor},
 *       {@link #listAllHostsInOneZoneNotInClusterByHypervisor},
 *       {@link #listAllHostsInOneZoneNotInClusterByHypervisors})</li>
 *   <li>per-host metadata reads — live agent stats, guest OS category
 *       id, and host tags
 *       ({@link #getHostStatistics}, {@link #getGuestOSCategoryId},
 *       {@link #getHostTags})</li>
 * </ul>
 *
 * <p>Extracted from {@link ResourceManagerImpl} as part of the Phase 4
 * Spring-component decomposition. {@code ResourceManagerImpl} keeps
 * one-line delegating wrappers so the {@link ResourceManager}
 * interface contract (which all of these methods are part of) and
 * existing spy / mock-based tests continue to work unchanged.
 */
public interface HostLookupService {

    /**
     * Return all hosts matching the (optional) {@code type}, optional
     * {@code clusterId}, optional {@code podId}, and required
     * {@code dcId} that are {@code Status.Up} and
     * {@code ResourceState.Enabled}.
     */
    List<HostVO> listAllUpAndEnabledHosts(Host.Type type, Long clusterId, Long podId, long dcId);

    /**
     * Return all hosts matching the (optional) {@code type}, optional
     * {@code clusterId}, optional {@code podId}, and required
     * {@code dcId}, regardless of status.
     */
    List<HostVO> listAllHosts(Host.Type type, Long clusterId, Long podId, long dcId);

    /**
     * Return all hosts matching the (optional) {@code type}, optional
     * {@code clusterId}, optional {@code podId}, and required
     * {@code dcId} that are {@code Status.Up} (regardless of
     * resource state).
     */
    List<HostVO> listAllUpHosts(Host.Type type, Long clusterId, Long podId, long dcId);

    /**
     * Like {@link #listAllUpAndEnabledHosts} but filtering out hosts
     * tagged with the configured HA tag.
     */
    List<HostVO> listAllUpAndEnabledNonHAHosts(Host.Type type, Long clusterId, Long podId, long dcId);

    /**
     * Return all hosts in the zone matching the given type that are
     * {@code Status.Up} and {@code ResourceState.Enabled}.
     */
    List<HostVO> listAllUpAndEnabledHostsInOneZoneByType(Host.Type type, long dcId);

    /**
     * Return all hosts in the (optional) zone matching the given
     * type, excluding any in a maintenance-related resource state
     * ({@code Maintenance}, {@code ErrorInMaintenance},
     * {@code ErrorInPrepareForMaintenance},
     * {@code PrepareForMaintenance}, {@code Error}).
     */
    List<HostVO> listAllNotInMaintenanceHostsInOneZone(Host.Type type, Long dcId);

    /**
     * Return all hosts in the zone of the given hypervisor type that
     * are {@code Status.Up} and {@code ResourceState.Enabled}.
     */
    List<HostVO> listAllUpAndEnabledHostsInOneZoneByHypervisor(HypervisorType type, long dcId);

    /**
     * Return all hosts in the zone of the given hypervisor type that
     * are {@code Status.Up}.
     */
    List<HostVO> listAllUpHostsInOneZoneByHypervisor(HypervisorType type, long dcId);

    /**
     * Return all hosts in the zone that are {@code Status.Up} and
     * {@code ResourceState.Enabled}.
     */
    List<HostVO> listAllUpAndEnabledHostsInOneZone(long dcId);

    /**
     * Return all hosts in the zone matching the given hypervisor
     * type that are <em>not</em> in {@code clusterId} and are
     * {@code Status.Up}.
     */
    List<HostVO> listAllHostsInOneZoneNotInClusterByHypervisor(HypervisorType type, long dcId, long clusterId);

    /**
     * Same as {@link #listAllHostsInOneZoneNotInClusterByHypervisor}
     * but accepting multiple hypervisor types.
     */
    List<HostVO> listAllHostsInOneZoneNotInClusterByHypervisors(List<HypervisorType> types, long dcId, long clusterId);

    /**
     * Ask the host agent for live host statistics. Returns
     * {@code null} when the agent does not support the command, when
     * the agent is unreachable, or when the answer payload is not a
     * {@code GetHostStatsAnswer}.
     */
    HostStats getHostStatistics(Host host);

    /**
     * Return the guest OS category id stored as a host detail, or
     * {@code null} when the host or the detail does not exist.
     */
    Long getGuestOSCategoryId(long hostId);

    /**
     * Return the host's tags as a comma-separated string.
     */
    String getHostTags(long hostId);
}
