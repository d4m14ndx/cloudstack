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

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.springframework.stereotype.Component;

import com.cloud.dc.Pod;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.org.Cluster;
import com.cloud.resource.ResourceState;

@Component
public class VmStartPlacementServiceImpl implements VmStartPlacementService {

    @Inject
    private HostPodDao hostPodDao;
    @Inject
    private ClusterDao clusterDao;
    @Inject
    private HostDao hostDao;

    @Override
    public Pod getDestinationPod(Long podId, boolean isRootAdmin) {
        Pod destinationPod = null;
        if (podId != null) {
            if (!isRootAdmin) {
                throw new PermissionDeniedException(
                        "Parameter " + ApiConstants.POD_ID + " can only be specified by a Root Admin, permission denied");
            }
            destinationPod = hostPodDao.findById(podId);
            if (destinationPod == null) {
                throw new InvalidParameterValueException("Unable to find the pod to deploy the VM, pod id=" + podId);
            }
        }
        return destinationPod;
    }

    @Override
    public Cluster getDestinationCluster(Long clusterId, boolean isRootAdmin) {
        Cluster destinationCluster = null;
        if (clusterId != null) {
            if (!isRootAdmin) {
                throw new PermissionDeniedException(
                        "Parameter " + ApiConstants.CLUSTER_ID + " can only be specified by a Root Admin, permission denied");
            }
            destinationCluster = clusterDao.findById(clusterId);
            if (destinationCluster == null) {
                throw new InvalidParameterValueException("Unable to find the cluster to deploy the VM, cluster id=" + clusterId);
            }
        }
        return destinationCluster;
    }

    @Override
    public HostVO getDestinationHost(Long hostId, boolean isRootAdmin, boolean isExplicitHost) {
        HostVO destinationHost = null;
        if (hostId != null) {
            if (isExplicitHost && !isRootAdmin) {
                throw new PermissionDeniedException(
                        "Parameter " + ApiConstants.HOST_ID + " can only be specified by a Root Admin, permission denied");
            }
            destinationHost = hostDao.findById(hostId);
            if (destinationHost == null) {
                throw new InvalidParameterValueException("Unable to find the host to deploy the VM, host id=" + hostId);
            } else if (destinationHost.getResourceState() != ResourceState.Enabled || destinationHost.getStatus() != Status.Up ) {
                throw new InvalidParameterValueException("Unable to deploy the VM as the host: " + destinationHost.getName() + " is not in the right state");
            }
        }
        return destinationHost;
    }
}
