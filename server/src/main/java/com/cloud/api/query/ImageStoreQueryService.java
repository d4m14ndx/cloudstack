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

import org.apache.cloudstack.api.command.admin.storage.ListImageStoresCmd;
import org.apache.cloudstack.api.command.admin.storage.ListSecondaryStagingStoresCmd;

import com.cloud.api.query.vo.ImageStoreJoinVO;
import com.cloud.utils.Pair;

/**
 * Query helpers for the {@code listImageStores} and
 * {@code listSecondaryStagingStores} APIs — both walk the
 * {@code image_store_view} join view using the same scoping/filtering
 * shape but pin the {@code role} parameter to either {@code Image} or
 * {@code ImageCache}.
 *
 * <p>Extracted from {@link QueryManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 6). {@code QueryManagerImpl}
 * keeps the public {@code searchForImageStores} and
 * {@code searchForSecondaryStagingStores} entry points (defined on
 * {@link org.apache.cloudstack.query.QueryService}) and delegates the
 * underlying search to this service.
 */
public interface ImageStoreQueryService {

    /**
     * Run the {@code listImageStores} search against the
     * {@code image_store_view} join view, applying the optional
     * {@code id}, {@code name}, {@code zoneId}, {@code provider},
     * {@code protocol}, {@code readonly}, and {@code keyword} filters
     * from the command. The {@code role} parameter is always pinned to
     * {@link com.cloud.storage.DataStoreRole#Image}.
     */
    Pair<List<ImageStoreJoinVO>, Integer> searchForImageStoresInternal(ListImageStoresCmd cmd);

    /**
     * Run the {@code listSecondaryStagingStores} search against the
     * {@code image_store_view} join view, applying the optional
     * {@code id}, {@code name}, {@code zoneId}, {@code provider},
     * {@code protocol}, and {@code keyword} filters from the command.
     * The {@code role} parameter is always pinned to
     * {@link com.cloud.storage.DataStoreRole#ImageCache}.
     */
    Pair<List<ImageStoreJoinVO>, Integer> searchForCacheStoresInternal(ListSecondaryStagingStoresCmd cmd);
}
