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

import java.util.List;
import java.util.Map;

import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;

import com.cloud.dc.DataCenter;
import com.cloud.network.Network.IpAddresses;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.Snapshot;
import com.cloud.storage.Volume;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;

public class VmDeployRequestResolution {

    private final DataCenter zone;
    private final Account owner;
    private final ServiceOffering serviceOffering;
    private final VirtualMachineTemplate template;
    private final Long diskOfferingId;
    private final Long size;
    private final Long overrideDiskOfferingId;
    private final List<VmDiskInfo> dataDiskInfoList;
    private final List<Long> networkIds;
    private final Map<Long, IpAddresses> ipToNetworkMap;
    private final VolumeInfo volume;
    private final Snapshot snapshot;

    public VmDeployRequestResolution(DataCenter zone, Account owner, ServiceOffering serviceOffering,
            VirtualMachineTemplate template, Long diskOfferingId, Long size, Long overrideDiskOfferingId,
            List<VmDiskInfo> dataDiskInfoList, List<Long> networkIds, Map<Long, IpAddresses> ipToNetworkMap,
            VolumeInfo volume, Snapshot snapshot) {
        this.zone = zone;
        this.owner = owner;
        this.serviceOffering = serviceOffering;
        this.template = template;
        this.diskOfferingId = diskOfferingId;
        this.size = size;
        this.overrideDiskOfferingId = overrideDiskOfferingId;
        this.dataDiskInfoList = dataDiskInfoList;
        this.networkIds = networkIds;
        this.ipToNetworkMap = ipToNetworkMap;
        this.volume = volume;
        this.snapshot = snapshot;
    }

    public DataCenter getZone() {
        return zone;
    }

    public Account getOwner() {
        return owner;
    }

    public ServiceOffering getServiceOffering() {
        return serviceOffering;
    }

    public VirtualMachineTemplate getTemplate() {
        return template;
    }

    public Long getDiskOfferingId() {
        return diskOfferingId;
    }

    public Long getSize() {
        return size;
    }

    public Long getOverrideDiskOfferingId() {
        return overrideDiskOfferingId;
    }

    public List<VmDiskInfo> getDataDiskInfoList() {
        return dataDiskInfoList;
    }

    public List<Long> getNetworkIds() {
        return networkIds;
    }

    public Map<Long, IpAddresses> getIpToNetworkMap() {
        return ipToNetworkMap;
    }

    public Volume getVolume() {
        return volume;
    }

    public Snapshot getSnapshot() {
        return snapshot;
    }
}
