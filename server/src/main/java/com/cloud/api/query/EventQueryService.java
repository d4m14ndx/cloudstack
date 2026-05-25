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

import org.apache.cloudstack.api.command.user.event.ListEventsCmd;

import com.cloud.api.query.vo.EventJoinVO;
import com.cloud.utils.Pair;

/**
 * Query helpers for the {@code listEvents} API — resolving the optional
 * resource UUID/type pair, layering in ACL/keyword/level/type/state filters,
 * paging the {@code event_view} join, and hydrating join rows by id.
 *
 * <p>Extracted from {@link QueryManagerImpl} as part of the Phase 4
 * Spring-component decomposition (parallel query slice, third on this file).
 * {@code QueryManagerImpl} keeps the public {@code searchForEvents}
 * orchestration entry point (the {@code QueryService} interface method) and
 * delegates the search internals to this service. The event search inherits
 * {@link ResourceIdSupport} default methods for UUID-to-id resolution so the
 * resource-id and resource-type filter validation matches the original
 * behaviour.
 */
public interface EventQueryService extends ResourceIdSupport {

    /**
     * Run the {@code listEvents} search against the {@code event_view} join.
     * Applies account-scoped ACL parameters, resource-id/resource-type
     * resolution with caller access checks, keyword/level/type/state filters,
     * date range filtering, archived-state filtering, and id/startId
     * correlation. Returns the paged join rows hydrated by id along with the
     * total count.
     */
    Pair<List<EventJoinVO>, Integer> searchForEventsInternal(ListEventsCmd cmd);
}
