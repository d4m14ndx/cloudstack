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

import org.apache.cloudstack.api.command.user.affinitygroup.ListAffinityGroupsCmd;

import com.cloud.api.query.vo.AffinityGroupJoinVO;
import com.cloud.utils.Pair;

/**
 * Query helpers for the {@code listAffinityGroups} API — building the search
 * criteria against the {@code affinity_group_view} join, layering in ACL and
 * keyword/name/type filters, and folding in domain-level affinity groups
 * visible to the caller.
 *
 * <p>Extracted from {@link QueryManagerImpl} as part of the Phase 4
 * Spring-component decomposition (parallel query slice, second on this file).
 * {@code QueryManagerImpl} keeps the public {@code searchForAffinityGroups}
 * orchestration entry point (the {@code QueryService} interface method) and
 * delegates the search internals to this service.
 */
public interface AffinityGroupQueryService {

    /**
     * Run the {@code listAffinityGroups} search, applying account-scoped ACL
     * checks, keyword/name/type filters, and—when no specific VM is named—
     * merging in any domain-level affinity groups the caller can see.
     *
     * <p>If {@code virtualMachineId} is set on the command, the caller's
     * access to the VM is verified and the affinity groups currently mapped
     * to that VM are returned instead.
     */
    Pair<List<AffinityGroupJoinVO>, Integer> searchForAffinityGroupsInternal(ListAffinityGroupsCmd cmd);
}
