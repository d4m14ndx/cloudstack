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

import org.apache.cloudstack.api.command.user.securitygroup.ListSecurityGroupsCmd;

import com.cloud.api.query.vo.SecurityGroupJoinVO;
import com.cloud.utils.Pair;

/**
 * Query helpers for the {@code listSecurityGroups} API extracted from
 * {@link QueryManagerImpl}. {@code QueryManagerImpl} keeps the public
 * {@code searchForSecurityGroups} orchestration entry point and delegates the
 * search internals to this service.
 */
public interface SecurityGroupQueryService {

    /**
     * Run the security-group search, applying VM access checks for
     * VM-specific listing or ACL/search filters for general listing.
     */
    Pair<List<SecurityGroupJoinVO>, Integer> searchForSecurityGroupsInternal(ListSecurityGroupsCmd cmd);
}
