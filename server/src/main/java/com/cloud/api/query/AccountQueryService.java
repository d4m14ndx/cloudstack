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

import org.apache.cloudstack.api.command.user.account.ListAccountsCmd;

import com.cloud.api.query.vo.AccountJoinVO;
import com.cloud.utils.Pair;

/**
 * Query helpers for the {@code listAccounts} API — resolving the caller's
 * effective account/domain scope, layering in id/name/type/state/keyword/API
 * key filters, paging the {@code account} table for distinct ids, and
 * hydrating the matching rows from the {@code account_view} join.
 *
 * <p>Extracted from {@link QueryManagerImpl} as part of the Phase 4
 * Spring-component decomposition (parallel query slice, eleventh on this
 * file). {@code QueryManagerImpl} keeps the public {@code searchForAccounts}
 * orchestration entry point (the {@code QueryService} interface method,
 * which decides on the response view based on {@code ListAccountsCmdByAdmin})
 * and delegates the search internals to this service.
 */
public interface AccountQueryService {

    /**
     * Run the {@code listAccounts} search against the {@code account} table and
     * hydrate the matching rows from the {@code account_view} join. Applies the
     * caller's scope, optional id/name/domain/type/state/API key filters,
     * recursive sub-tree filtering via domain path, and guards that prevent
     * project, system, and non-admin-visible domain-admin accounts from leaking
     * through the API.
     *
     * @param cmd the parsed {@code listAccounts} command (or the admin variant)
     *            carrying paging, filter, and details parameters
     * @return paged list of hydrated {@link AccountJoinVO} rows and the total
     *         count of distinct account ids matching the filter
     */
    Pair<List<AccountJoinVO>, Integer> searchForAccountsInternal(ListAccountsCmd cmd);
}
