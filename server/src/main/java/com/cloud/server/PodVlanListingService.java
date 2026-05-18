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

import org.apache.cloudstack.api.command.admin.pod.ListPodsByCmd;
import org.apache.cloudstack.api.command.admin.vlan.ListVlanIpRangesCmd;

import com.cloud.dc.Pod;
import com.cloud.dc.Vlan;
import com.cloud.utils.Pair;

/**
 * Pod + VLAN IP-range listing logic extracted from
 * {@link ManagementServerImpl}.
 */
public interface PodVlanListingService {

    Pair<List<? extends Pod>, Integer> searchForPods(ListPodsByCmd cmd);

    Pair<List<? extends Vlan>, Integer> searchForVlans(ListVlanIpRangesCmd cmd);
}
