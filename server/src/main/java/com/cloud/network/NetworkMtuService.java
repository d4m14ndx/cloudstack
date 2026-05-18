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
package com.cloud.network;

import java.util.Map;
import java.util.Set;

import com.cloud.agent.api.to.IpAddressTO;
import com.cloud.network.dao.NetworkVO;
import com.cloud.utils.Pair;

/**
 * MTU validation and router update component extracted from {@link NetworkServiceImpl}.
 */
public interface NetworkMtuService {

    void mtuCheckForVpcNetwork(Long vpcId, Pair<Integer, Integer> interfaceMTUs, Integer publicMtu);

    Pair<Integer, Integer> validateMtuConfig(Integer publicMtu, Integer privateMtu, Long zoneId);

    Pair<Integer, Integer> validateMtuOnUpdate(NetworkVO network, Long zoneId, Integer publicMtu, Integer privateMtu);

    void updateNetworkMtu(NetworkVO network, long networkId, Long zoneId, Integer publicMtu, Integer privateMtu, boolean restartNetwork);

    boolean updateMtuOnVr(Map<Long, Set<IpAddressTO>> routersToIpList);
}
