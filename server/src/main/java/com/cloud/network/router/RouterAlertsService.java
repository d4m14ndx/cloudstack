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

/**
 * Helpers for collecting and dispatching monitoring-service alerts emitted by
 * running virtual routers.
 *
 * <p>The service polls every {@code Running} router owned by the current
 * management server, asks each one for new alerts via the
 * {@link com.cloud.agent.api.routing.GetRouterAlertsCommand}, forwards any
 * received alerts through the {@link com.cloud.alert.AlertManager}, and
 * persists the last-seen alert timestamp in the
 * {@code op_router_monitoring_service} table so the next poll only fetches
 * newer alerts.
 *
 * <p>Extracted from {@link VirtualNetworkApplianceManagerImpl} as part of the
 * Phase 4 Spring-component decomposition (parallel slice — VNApp, 2nd). The
 * god class keeps a thin delegating wrapper so that the
 * {@code CheckRouterAlertsTask} scheduler continues to invoke this code path
 * unchanged.
 */
public interface RouterAlertsService {

    /**
     * Iterate over every router in the {@code Running} state owned by the
     * current management server and pull any new monitoring-service alerts
     * from each one.
     *
     * <p>For each router:
     * <ul>
     *   <li>Skips routers whose data center does not have
     *       {@code SetServiceMonitor} enabled.</li>
     *   <li>Skips routers without a usable control IP
     *       (null or {@code 0.0.0.0}).</li>
     *   <li>Sends a {@link com.cloud.agent.api.routing.GetRouterAlertsCommand}
     *       with the last-seen timestamp (or a far-past sentinel when nothing
     *       has been seen yet).</li>
     *   <li>For every returned alert string, emits a
     *       {@code ALERT_TYPE_DOMAIN_ROUTER} alert via
     *       {@link com.cloud.alert.AlertManager}.</li>
     *   <li>Upserts the
     *       {@link com.cloud.network.dao.OpRouterMonitorServiceVO}
     *       record with the new last-alert timestamp.</li>
     * </ul>
     *
     * <p>All errors are swallowed and logged: this method is invoked from a
     * scheduled task and a single failing router must not abort the
     * remaining ones.
     */
    void getRouterAlerts();
}
