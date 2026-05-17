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
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor.HypervisorType;

/**
 * Read-only host lookup and listing — the simple "find me hosts that match
 * these criteria" slice of {@link ResourceManagerImpl}. Every method here is
 * a stateless {@link com.cloud.utils.db.QueryBuilder} query against
 * {@link HostVO}; none of them mutate state or call agents.
 *
 * <p>Operations covered:
 * <ul>
 *   <li>directly-connected host enumeration ({@link #findDirectlyConnectedHosts})</li>
 *   <li>GUID / name based finders ({@link #findHostByGuid(String)},
 *       {@link #findHostByGuid(long, String)}, {@link #findHostByGuidPrefix},
 *       {@link #findHostByName})</li>
 *   <li>cluster-scoped listings ({@link #listAllHostsInCluster},
 *       {@link #listHostsInClusterByStatus})</li>
 *   <li>zone-scoped listings by host {@link Host.Type}
 *       ({@link #listAllHostsInOneZoneByType},
 *       {@link #listAllHostsInAllZonesByType})</li>
 *   <li>random running-host pick by hypervisor type
 *       ({@link #findOneRandomRunningHostByHypervisor})</li>
 * </ul>
 *
 * <p>Extracted from {@link ResourceManagerImpl} as part of the Phase 4
 * Spring-component decomposition. {@code ResourceManagerImpl} keeps
 * one-line delegating wrappers so the {@link ResourceManager} interface
 * contract and existing spy / mock-based tests continue to work unchanged.
 */
public interface HostQueryService {

    /**
     * Hosts whose {@code resource} column is non-null (i.e. directly
     * connected agents) and whose {@link com.cloud.resource.ResourceState}
     * is not {@code Disabled}.
     */
    List<HostVO> findDirectlyConnectedHosts();

    /**
     * Hosts matching the supplied GUID within a specific data center.
     * Returns an empty list when no match.
     */
    List<HostVO> findHostByGuid(long dcId, String guid);

    /**
     * Host with the exact GUID (and not soft-removed), or {@code null}.
     */
    HostVO findHostByGuid(String guid);

    /**
     * Host whose GUID starts with the supplied prefix (and not soft-removed),
     * or {@code null}.
     */
    HostVO findHostByGuidPrefix(String guid);

    /**
     * Host with the exact name (and not soft-removed), or {@code null}.
     */
    HostVO findHostByName(String name);

    /**
     * All hosts in the given cluster regardless of status / resource state.
     */
    List<HostVO> listAllHostsInCluster(long clusterId);

    /**
     * Hosts in the given cluster matching the supplied {@link Status}.
     */
    List<HostVO> listHostsInClusterByStatus(long clusterId, Status status);

    /**
     * All hosts of the given {@link Host.Type} in the given zone.
     */
    List<HostVO> listAllHostsInOneZoneByType(Host.Type type, long dcId);

    /**
     * All hosts of the given {@link Host.Type} across every zone.
     */
    List<HostVO> listAllHostsInAllZonesByType(Host.Type type);

    /**
     * One random running, enabled, non-removed host whose hypervisor type
     * matches the supplied value (and optionally the supplied zone).
     * Returns {@code null} when no such host exists.
     */
    HostVO findOneRandomRunningHostByHypervisor(HypervisorType type, Long dcId);
}
