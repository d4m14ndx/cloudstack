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
package com.cloud.api.query;

import java.util.List;

import org.apache.cloudstack.api.command.admin.management.ListMgmtsCmd;
import org.apache.cloudstack.api.response.ManagementServerResponse;

import com.cloud.api.query.vo.ManagementServerJoinVO;
import com.cloud.utils.Pair;

/**
 * Query helpers for the {@code listManagementServers} API — searching the
 * {@code mshost_view} join view, building per-server response objects, and
 * (optionally) attaching the peer-cluster nodes each management server can
 * see.
 *
 * <p>Extracted from {@link QueryManagerImpl} as part of the Phase 4
 * Spring-component decomposition (parallel query slice). {@code QueryManagerImpl}
 * keeps the public {@code listManagementServers} orchestration entry point
 * (the {@code QueryService} interface method) and delegates the search and
 * per-row response assembly to this service.
 */
public interface ManagementServerQueryService {

    /**
     * Run the {@code listManagementServers} search against the management
     * server join view, applying the optional {@code id}, {@code name},
     * {@code version}, and {@code keyword} filters from the command.
     */
    Pair<List<ManagementServerJoinVO>, Integer> listManagementServersInternal(ListMgmtsCmd cmd);

    /**
     * Build the per-row {@link ManagementServerResponse} for a management
     * server join row, including its connected agents, pending non-pseudo
     * job count, and (when {@code listPeers} is true) the peer cluster
     * nodes that this server can see.
     */
    ManagementServerResponse createManagementServerResponse(ManagementServerJoinVO mgmt, boolean listPeers);
}
