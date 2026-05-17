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
import java.util.Map;

import com.cloud.api.query.vo.SnapshotJoinVO;
import com.cloud.user.Account;
import com.cloud.utils.Pair;

/**
 * Query helpers for the {@code listSnapshots} and {@code listSnapshot} APIs —
 * resolving ACL search parameters, applying all snapshot filters (type,
 * interval, location, storage-pool, image-store, tags, keyword, zone), paging
 * the {@code snapshot_view} join, and hydrating the distinct id set.
 *
 * <p>Extracted from {@link QueryManagerImpl} as part of the Phase 4
 * Spring-component decomposition (parallel query slice, fourth on this file).
 * {@code QueryManagerImpl} keeps the public {@code listSnapshots} and
 * {@code listSnapshot} orchestration entry points (the {@code QueryService}
 * interface methods) and delegates the search internals to this service.
 */
public interface SnapshotQueryService {

    /**
     * Run the low-level snapshot search.  Applies ACL, all column filters,
     * tag joins, storage-pool and image-store joins, and paging.  Returns the
     * paged, hydrated snapshot join rows along with the total count.
     *
     * @param id            optional single snapshot id filter
     * @param ids           optional list of snapshot ids filter (mutually exclusive with {@code id})
     * @param volumeId      optional parent-volume filter
     * @param name          optional exact name filter
     * @param keyword       optional LIKE keyword filter on name
     * @param tags          optional tag key/value map filter
     * @param snapshotTypeStr  optional snapshot type string (e.g. "MANUAL", "RECURRING")
     * @param intervalTypeStr  optional interval type string (used together with {@code volumeId})
     * @param zoneId        optional zone filter
     * @param locationTypeStr  optional location type string ("PRIMARY" or "SECONDARY")
     * @param isShowUnique  when {@code true} collapse per-snapshot-id instead of per store pair
     * @param accountName   ACL account-name filter
     * @param domainId      ACL domain-id filter
     * @param projectId     ACL project-id filter
     * @param storagePoolId optional primary storage-pool filter
     * @param imageStoreId  optional secondary image-store filter
     * @param startIndex    pagination start index
     * @param pageSize      pagination page size
     * @param listAll       ACL list-all flag
     * @param isRecursive   ACL recursive-domain flag
     * @param caller        calling account (used for ACL resolution)
     * @return paged list of hydrated {@link SnapshotJoinVO} rows and total count
     */
    Pair<List<SnapshotJoinVO>, Integer> searchForSnapshotsWithParams(
            Long id, List<Long> ids,
            Long volumeId, String name, String keyword, Map<String, String> tags,
            String snapshotTypeStr, String intervalTypeStr,
            Long zoneId, String locationTypeStr,
            boolean isShowUnique,
            String accountName, Long domainId, Long projectId,
            Long storagePoolId, Long imageStoreId,
            Long startIndex, Long pageSize,
            boolean listAll, boolean isRecursive,
            Account caller);
}
