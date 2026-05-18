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

import org.apache.cloudstack.api.command.admin.cluster.ListClustersCmd;
import org.apache.cloudstack.api.command.admin.host.ListHostsCmd;

import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.org.Cluster;
import com.cloud.utils.Pair;

/**
 * Read-only cluster and host search operations extracted from
 * {@link ManagementServerImpl}.
 */
public interface ClusterHostQueryService {

    List<? extends Cluster> searchForClusters(long zoneId, Long startIndex, Long pageSizeVal, String hypervisorType);

    Pair<List<? extends Cluster>, Integer> searchForClusters(ListClustersCmd cmd);

    Pair<List<? extends Host>, Integer> searchForServers(ListHostsCmd cmd);

    Pair<List<HostVO>, Integer> searchForServers(Long startIndex, Long pageSize, Object name, Object type, Object state, Object zone,
            Object pod, Object cluster, Object id, Object keyword, Object resourceState, Object haHosts, Object hypervisorType,
            Object hypervisorVersion, Object... excludes);
}
