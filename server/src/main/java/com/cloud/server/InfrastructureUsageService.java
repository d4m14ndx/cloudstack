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
package com.cloud.server;

import java.util.List;

import org.apache.cloudstack.api.command.admin.resource.ListCapacityCmd;

import com.cloud.capacity.CapacityVO;

/**
 * Infrastructure capacity reporting — the read-only surface behind
 * {@code listCapacity} and the per-host capacity lookup used by the API
 * formatting layer. This service walks the operational capacity tables
 * ({@link com.cloud.capacity.dao.CapacityDao}) and the live storage and
 * backup stats reported by {@link com.cloud.storage.StorageManager} and
 * {@link org.apache.cloudstack.backup.BackupManager}, then groups,
 * filters, and tags the results so the admin UI can render zone, pod,
 * cluster, and storage-pool utilization.
 *
 * <p>Two listing flavors are exposed: {@link #listCapacities(ListCapacityCmd)}
 * returns the full filtered capacity set scoped to a zone, pod, cluster,
 * or globally; {@link #listTopConsumedResources(ListCapacityCmd)} returns
 * the same data ranked by percent-used and clipped to the caller's page
 * size. Both apply
 * {@link com.cloud.user.AccountManager#checkAccessAndSpecifyAuthority access checks}
 * against the calling account and optionally trigger an
 * {@link com.cloud.alert.AlertManager#recalculateCapacity() in-memory
 * recalculation} before reading the tables. Tag-aware filtering folds in
 * resource-limit host and storage tags so per-tag quotas appear as
 * separate rows.
 *
 * <p>Extracted from {@link ManagementServerImpl} as part of the Phase 4
 * Spring-component decomposition. The public
 * {@link com.cloud.server.ManagementService} entry points covered here
 * ({@code listCapacities}, {@code listTopConsumedResources}) and the
 * server-internal {@link ManagementServer#getMemoryOrCpuCapacityByHost}
 * accessor remain on the god class as one-line delegating wrappers so
 * callers binding {@code ManagementService} / {@code ManagementServer}
 * still resolve their method calls there.
 */
public interface InfrastructureUsageService {

    /**
     * Return the full set of capacity rows matching the optional zone,
     * pod, cluster, capacity-type, and tag filters carried by
     * {@link ListCapacityCmd}. Zone-wide secondary, object, and backup
     * storage capacities are appended only when the caller did not
     * narrow the scope below the zone level. When the command requests
     * the latest values an
     * {@link com.cloud.alert.AlertManager#recalculateCapacity()} pass
     * runs first so the in-memory aggregates are fresh.
     */
    List<CapacityVO> listCapacities(ListCapacityCmd cmd);

    /**
     * Return the same capacity rows as
     * {@link #listCapacities(ListCapacityCmd)} but sorted by descending
     * percent-used and clipped to {@code cmd.getPageSizeVal()}. The
     * cluster-id filter is currently rejected with
     * {@link com.cloud.exception.InvalidParameterValueException} — this
     * call always groups by zone or pod. Used by the UI dashboard to
     * highlight the most loaded resources.
     */
    List<CapacityVO> listTopConsumedResources(ListCapacityCmd cmd);

    /**
     * Look up the combined used-plus-reserved memory or CPU capacity
     * reported for a single host. Returns {@code 0} when no row is
     * present for the host/type pair. Used by
     * {@link com.cloud.api.ApiDBUtils} when formatting per-host
     * response fields.
     */
    long getMemoryOrCpuCapacityByHost(Long hostId, short capacityType);
}
