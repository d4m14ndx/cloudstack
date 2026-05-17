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
package com.cloud.network.router;

import com.cloud.network.VirtualNetworkApplianceService.RouterHealthStatus;
import com.cloud.vm.DomainRouterVO;

/**
 * Helpers for persisting router health-check results: parsing the JSON
 * monitoring payload returned by a virtual router and upserting individual
 * check rows in the {@code router_health_check} table.
 *
 * <p>This slice owns the result-side of the health-check pipeline: <em>fetch
 * → parse → persist</em>. The orchestration that decides <em>when</em> to
 * fetch results (basic tests, config push, scheduled task) stays in
 * {@link VirtualNetworkApplianceManagerImpl}, as does the failure-handling
 * decision tree (alerting, recreating the router) — those concerns belong to
 * different slices.
 *
 * <p>Extracted from {@link VirtualNetworkApplianceManagerImpl} as part of the
 * Phase 4 Spring-component decomposition (parallel slice — VNApp, 3rd). The
 * god class keeps thin delegating wrappers so existing call sites continue
 * to work unchanged.
 */
public interface RouterHealthCheckResultsService {

    /**
     * Reset the two synthetic "basic" health-check rows
     * ({@code connectivity.test} and {@code filesystem.writable.test}) for the
     * given router, after expunging any prior per-check rows.
     *
     * <p>Called when communication with the router fails or when results
     * cannot be fetched, so that subsequent UI/alerting consumers see the
     * router in a clean failed state rather than a stale partial one.
     *
     * @param routerId  router whose rows are reset
     * @param connected status to record for the connectivity check
     * @param writable  status to record for the filesystem-writable check
     * @param message   short detail string used when the corresponding status
     *                  is not {@code SUCCESS}
     */
    void resetRouterHealthChecksAndConnectivity(long routerId, RouterHealthStatus connected, RouterHealthStatus writable, String message);

    /**
     * Parse the monitoring JSON returned by a router and upsert every
     * individual health-check row in the database. Silently ignores blank
     * input and logs (but does not propagate) JSON syntax errors.
     *
     * <p>The expected JSON shape is:
     * <pre>{@code
     * {
     *   checkType1: {
     *     checkName1: { success, lastUpdate, lastRunDuration, message },
     *     checkName2: { ... }
     *   },
     *   checkType2: { ... }
     * }
     * }</pre>
     *
     * @param router            owning router (used for tracing + scoping)
     * @param monitoringResult  raw JSON payload from the router
     */
    void updateDbHealthChecksFromRouterResponse(DomainRouterVO router, String monitoringResult);
}
