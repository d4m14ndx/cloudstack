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
package com.cloud.api;

import org.apache.cloudstack.api.response.NicResponse;
import org.apache.cloudstack.api.response.UnmanagedInstanceDiskResponse;
import org.apache.cloudstack.api.response.UnmanagedInstanceResponse;
import org.apache.cloudstack.vm.UnmanagedInstanceTO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.cloud.host.Host;
import com.cloud.org.Cluster;

@Component
public class ApiUnmanagedInstanceResponseServiceImpl implements ApiUnmanagedInstanceResponseService {

    @Override
    public UnmanagedInstanceResponse createUnmanagedInstanceResponse(UnmanagedInstanceTO instance, Cluster cluster, Host host) {
        UnmanagedInstanceResponse response = new UnmanagedInstanceResponse();
        response.setName(instance.getName());
        if (cluster != null) {
            response.setClusterId(cluster.getUuid());
            response.setClusterName(cluster.getName());
        } else if (instance.getClusterName() != null) {
            response.setClusterName(instance.getClusterName());
        }
        if (host != null) {
            response.setHostId(host.getUuid());
            response.setHostName(host.getName());
            if (host.getHypervisorType() != null) {
                response.setHypervisor(host.getHypervisorType().name());
            }
            response.setHypervisorVersion(host.getHypervisorVersion());
        } else {
            if (instance.getHostName() != null) {
                response.setHostName(instance.getHostName());
            }
            if (instance.getHypervisorType() != null) {
                response.setHypervisor(instance.getHypervisorType());
            }
            if (instance.getHostHypervisorVersion() != null) {
                response.setHypervisorVersion(instance.getHostHypervisorVersion());
            }
        }
        response.setPowerState((instance.getPowerState() != null) ? instance.getPowerState().toString() : UnmanagedInstanceTO.PowerState.PowerUnknown.toString());
        response.setCpuCores(instance.getCpuCores());
        response.setCpuSpeed(instance.getCpuSpeed());
        response.setCpuCoresPerSocket(instance.getCpuCoresPerSocket());
        response.setMemory(instance.getMemory());
        response.setOperatingSystemId(instance.getOperatingSystemId());
        response.setOperatingSystem(instance.getOperatingSystem());
        response.setBootMode(instance.getBootMode());
        response.setBootType(instance.getBootType());
        response.setObjectName("unmanagedinstance");

        addDiskResponses(instance, response);
        addNicResponses(instance, response);
        return response;
    }

    protected void addDiskResponses(UnmanagedInstanceTO instance, UnmanagedInstanceResponse response) {
        if (instance.getDisks() != null) {
            for (UnmanagedInstanceTO.Disk disk : instance.getDisks()) {
                UnmanagedInstanceDiskResponse diskResponse = new UnmanagedInstanceDiskResponse();
                diskResponse.setDiskId(disk.getDiskId());
                if (StringUtils.isNotEmpty(disk.getLabel())) {
                    diskResponse.setLabel(disk.getLabel());
                }
                diskResponse.setCapacity(disk.getCapacity());
                diskResponse.setController(disk.getController());
                diskResponse.setControllerUnit(disk.getControllerUnit());
                diskResponse.setPosition(disk.getPosition());
                diskResponse.setImagePath(disk.getImagePath());
                diskResponse.setDatastoreName(disk.getDatastoreName());
                diskResponse.setDatastoreHost(disk.getDatastoreHost());
                diskResponse.setDatastorePath(disk.getDatastorePath());
                diskResponse.setDatastoreType(disk.getDatastoreType());
                response.addDisk(diskResponse);
            }
        }
    }

    protected void addNicResponses(UnmanagedInstanceTO instance, UnmanagedInstanceResponse response) {
        if (instance.getNics() != null) {
            for (UnmanagedInstanceTO.Nic nic : instance.getNics()) {
                NicResponse nicResponse = new NicResponse();
                nicResponse.setId(nic.getNicId());
                nicResponse.setNetworkName(nic.getNetwork());
                nicResponse.setMacAddress(nic.getMacAddress());
                if (StringUtils.isNotEmpty(nic.getAdapterType())) {
                    nicResponse.setAdapterType(nic.getAdapterType());
                }
                if (!CollectionUtils.isEmpty(nic.getIpAddress())) {
                    nicResponse.setIpAddresses(nic.getIpAddress());
                }
                nicResponse.setVlanId(nic.getVlan());
                nicResponse.setIsolatedPvlanId(nic.getPvlan());
                nicResponse.setIsolatedPvlanType(nic.getPvlanType());
                response.addNic(nicResponse);
            }
        }
    }
}
