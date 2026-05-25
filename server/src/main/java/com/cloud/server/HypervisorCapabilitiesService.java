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

import org.apache.cloudstack.api.command.admin.config.UpdateHypervisorCapabilitiesCmd;

import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorCapabilities;
import com.cloud.hypervisor.HypervisorCapabilitiesVO;
import com.cloud.utils.Pair;

/**
 * Hypervisor capability metadata lookups and per-version updates.
 *
 * <p>The hypervisor capabilities catalogue stores the
 * {@link com.cloud.hypervisor.HypervisorCapabilities} record for each
 * hypervisor type + version pair: limits on max guests per host, max data
 * volumes, max hosts per cluster, plus feature flags such as
 * {@code securityGroupEnabled}, {@code storageMotionSupported}, and
 * {@code vmSnapshotEnabled}. This slice owns the pure read/write surface
 * for that catalogue.
 *
 * <p>Extracted from {@link ManagementServerImpl} as part of the Phase 4
 * Spring-component decomposition. The two public {@code ManagementService}
 * entry points covered here ({@code listHypervisorCapabilities} and
 * {@code updateHypervisorCapabilities}) remain on the god class as one-line
 * delegating wrappers so callers still binding the {@code ManagementService}
 * interface continue to resolve their method calls there. The internal
 * resolver {@code getHypervisorCapabilitiesForUpdate} (id-or-version lookup)
 * also moves here in full.
 */
public interface HypervisorCapabilitiesService {

    /**
     * Page-aware search of the hypervisor capabilities catalogue, narrowing
     * by id, hypervisor type, and/or keyword (matched as a {@code LIKE} on
     * {@code hypervisorType}).
     */
    Pair<List<? extends HypervisorCapabilities>, Integer> listHypervisorCapabilities(
            Long id, HypervisorType hypervisorType, String keyword,
            Long startIndex, Long pageSizeVal);

    /**
     * Apply per-version updates to the hypervisor capability record selected
     * either by id or by {@code (hypervisor, hypervisorVersion)}. When the
     * supplied version does not yet exist, a copy of the resolved parent row
     * is persisted before the update is applied so that the parent record is
     * not mutated in place.
     *
     * <p>Returns the freshly updated row, the resolved row unchanged when no
     * field-level updates were supplied, or {@code null} when the
     * underlying DAO update fails.
     */
    HypervisorCapabilities updateHypervisorCapabilities(UpdateHypervisorCapabilitiesCmd cmd);

    /**
     * Resolve a {@link HypervisorCapabilitiesVO} for update purposes from
     * either an explicit id or a {@code (hypervisor, hypervisorVersion)}
     * pair. Throws {@link com.cloud.exception.InvalidParameterValueException}
     * for invalid combinations (neither supplied, both supplied, unknown
     * hypervisor type, missing version, no matching row).
     */
    HypervisorCapabilitiesVO getHypervisorCapabilitiesForUpdate(
            Long id, String hypervisorStr, String hypervisorVersion);
}
