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

import org.apache.cloudstack.api.command.admin.internallb.ListInternalLBVMsCmd;
import org.apache.cloudstack.api.command.admin.router.GetRouterHealthCheckResultsCmd;
import org.apache.cloudstack.api.command.admin.router.ListRoutersCmd;
import org.apache.cloudstack.api.response.DomainRouterResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.RouterHealthCheckResultResponse;

/**
 * Query helpers for the {@code listRouters}, {@code listInternalLBVMs}, and
 * {@code getRouterHealthCheckResults} APIs extracted from
 * {@link QueryManagerImpl} as part of the Phase 4 Spring-component
 * decomposition (slice 7).
 *
 * <p>{@code QueryManagerImpl} keeps the public interface entry points and
 * delegates the implementation to this service.
 */
public interface RouterQueryService {

    /**
     * Search for domain routers applying all filters from the command and
     * optionally enriching responses with health-check data.
     *
     * @param cmd the parsed {@code listRouters} command
     * @return paged list of router responses
     */
    ListResponse<DomainRouterResponse> searchForRouters(ListRoutersCmd cmd);

    /**
     * Search for internal load-balancer VMs.
     *
     * @param cmd the parsed {@code listInternalLBVMs} command
     * @return paged list of router responses
     */
    ListResponse<DomainRouterResponse> searchForInternalLbVms(ListInternalLBVMsCmd cmd);

    /**
     * Return the health-check results for a specific router, optionally
     * triggering a fresh check run first.
     *
     * @param cmd the parsed {@code getRouterHealthCheckResults} command
     * @return list of health-check result responses
     */
    List<RouterHealthCheckResultResponse> listRouterHealthChecks(GetRouterHealthCheckResultsCmd cmd);
}
