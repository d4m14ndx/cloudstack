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
package com.cloud.server;

import java.util.List;

import org.apache.cloudstack.api.command.user.address.ListPublicIpAddressesCmd;

import com.cloud.dc.Vlan.VlanType;
import com.cloud.network.IpAddress;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.utils.Pair;
import com.cloud.utils.db.SearchCriteria;

/**
 * Public IP address search logic extracted from {@link ManagementServerImpl}
 * during the Phase 4 Spring-component decomposition. The god class keeps the
 * public API wrappers so callers bound to {@code ManagementService} still
 * enter through {@code ManagementServerImpl}, while this service owns the
 * actual state parsing and search-criteria construction.
 */
public interface PublicIpAddressSearchService {

    Pair<List<? extends IpAddress>, Integer> searchForIPAddresses(ListPublicIpAddressesCmd cmd);

    List<IpAddress.State> getStatesForIpAddressSearch(ListPublicIpAddressesCmd cmd);

    void setParameters(SearchCriteria<IPAddressVO> sc, ListPublicIpAddressesCmd cmd, VlanType vlanType,
            Boolean isAllocated, List<IpAddress.State> states);
}
