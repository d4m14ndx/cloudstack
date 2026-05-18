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

import org.apache.cloudstack.api.command.admin.cluster.AddClusterCmd;
import org.apache.cloudstack.api.command.admin.cluster.DeleteClusterCmd;
import org.apache.cloudstack.api.command.admin.cluster.UpdateClusterCmd;

import com.cloud.exception.DiscoveryException;
import com.cloud.org.Cluster;

/**
 * Cluster CRUD operations extracted from {@link ResourceManagerImpl} as part
 * of Phase 4 decomposition (slice 6).
 *
 * <p>Public behaviour and API contracts on {@link ResourceManager} /
 * {@link ResourceService} remain unchanged — {@link ResourceManagerImpl}
 * retains the public overrides as thin delegates.
 */
public interface ClusterLifecycleService {

    /**
     * Discover (or create) a cluster from the parameters supplied by the
     * API command, optionally running host discovery for non-CloudManaged
     * cluster types.
     */
    List<? extends Cluster> discoverCluster(AddClusterCmd cmd)
            throws IllegalArgumentException, DiscoveryException;

    /**
     * Remove a cluster from the system. Fails if the cluster still has
     * hosts or storage pools attached.
     */
    boolean deleteCluster(DeleteClusterCmd cmd);

    /**
     * Update cluster metadata (name, hypervisor type, allocation state,
     * managed state, CPU arch, external extension details).
     */
    Cluster updateCluster(UpdateClusterCmd cmd);
}
