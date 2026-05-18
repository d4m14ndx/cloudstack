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
package com.cloud.resource;

import java.util.List;
import java.util.Map;

import com.cloud.host.Host;
import com.cloud.org.Cluster;

/**
 * Narrow callback surface for collaborators that {@link ClusterLifecycleService}
 * needs but cannot own. Implemented by {@link ResourceManagerImpl}.
 *
 * <p>Introduced as part of Phase 4 decomposition (slice 6) to avoid a
 * circular Spring dependency between {@code ClusterLifecycleServiceImpl} and
 * the full {@link ResourceManager} interface.
 */
public interface ClusterLifecycleCallbacks {

    /**
     * Thin wrapper around the private {@code createHostAndAgent} helper on
     * {@link ResourceManagerImpl}, invoked during cluster discovery with
     * {@code old=true}, {@code forRebalance=false}.
     */
    Host createHostAndAgentForDiscovery(ServerResource resource,
                                        Map<String, String> details,
                                        List<String> hostTags,
                                        List<String> storageAccessGroups);

    /**
     * Delegates to the public {@code getCluster(Long)} method on
     * {@link ResourceManagerImpl}.
     */
    Cluster getCluster(Long clusterId);

    /**
     * Delegates to the public {@code umanageHost(long)} method on
     * {@link ResourceManagerImpl}.
     */
    boolean umanageHost(long hostId);
}
