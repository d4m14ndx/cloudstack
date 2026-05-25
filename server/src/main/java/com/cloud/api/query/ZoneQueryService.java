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

import org.apache.cloudstack.api.command.user.zone.ListZonesCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ZoneResponse;

/**
 * Query helpers for the {@code listZones} API extracted from
 * {@link QueryManagerImpl} as part of the Phase 4 Spring-component
 * decomposition (slice 9).
 *
 * <p>{@code QueryManagerImpl} keeps the public interface entry points and
 * delegates the implementation to this service.
 */
public interface ZoneQueryService {

    /**
     * List data centers applying all filters from the command, returning full
     * or restricted view depending on caller type.
     *
     * @param cmd the parsed {@code listZones} command
     * @return paged list of zone responses
     */
    ListResponse<ZoneResponse> listDataCenters(ListZonesCmd cmd);

    /**
     * List data centers with a minimal response payload (used internally by
     * storage-access-group queries).
     *
     * @param cmd the parsed {@code listZones} command
     * @return paged list of minimal zone responses
     */
    ListResponse<ZoneResponse> listDataCentersWithMinimalResponse(ListZonesCmd cmd);
}
