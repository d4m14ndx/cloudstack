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
package com.cloud.vm;

import com.cloud.dc.Pod;
import com.cloud.host.HostVO;
import com.cloud.org.Cluster;

/**
 * Start/deploy destination lookup helpers extracted from
 * {@link UserVmManagerImpl}. The service keeps the existing root-admin
 * checks and DAO lookup semantics for pod, cluster, and host targets.
 */
public interface VmStartPlacementService {

    Pod getDestinationPod(Long podId, boolean isRootAdmin);

    Cluster getDestinationCluster(Long clusterId, boolean isRootAdmin);

    HostVO getDestinationHost(Long hostId, boolean isRootAdmin, boolean isExplicitHost);
}
