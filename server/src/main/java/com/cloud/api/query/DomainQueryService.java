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

import org.apache.cloudstack.api.command.admin.domain.ListDomainsCmd;

import com.cloud.api.query.vo.DomainJoinVO;
import com.cloud.utils.Pair;

/**
 * Query helpers for the {@code listDomains} API — resolving the caller's
 * effective domain scope, layering in name/level/path/keyword filters and
 * resource-tag joins, paging the {@code domain} table for distinct active
 * ids, and hydrating the matching rows from the {@code domain_view} join.
 *
 * <p>Extracted from {@link QueryManagerImpl} as part of the Phase 4
 * Spring-component decomposition (parallel query slice, fifth on this
 * file). {@code QueryManagerImpl} keeps the public {@code searchForDomains}
 * orchestration entry point (the {@code QueryService} interface method,
 * which decides on the response view based on {@code ListDomainsCmdByAdmin})
 * and delegates the search internals to this service.
 */
public interface DomainQueryService {

    /**
     * Run the {@code listDomains} search against the {@code domain} table and
     * hydrate the matching rows from the {@code domain_view} join. Applies
     * the caller's domain scope, optional id/name/level/tag/keyword filters,
     * recursive sub-tree filtering via path-LIKE, and the active-state guard
     * that prevents removed domains from leaking through the API.
     *
     * @param cmd the parsed {@code listDomains} command (or the admin
     *            variant) carrying paging, filter, and tag parameters
     * @return paged list of hydrated {@link DomainJoinVO} rows and the total
     *         count of distinct domain ids matching the filter
     */
    Pair<List<DomainJoinVO>, Integer> searchForDomainsInternal(ListDomainsCmd cmd);
}
