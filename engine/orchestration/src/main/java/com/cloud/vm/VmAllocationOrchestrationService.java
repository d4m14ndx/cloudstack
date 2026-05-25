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

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.cloud.deploy.DeploymentPlan;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.DiskOfferingInfo;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.Snapshot;
import com.cloud.storage.Volume;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;

public interface VmAllocationOrchestrationService {

    void allocate(String vmInstanceName, VirtualMachineTemplate template, ServiceOffering serviceOffering,
            DiskOfferingInfo rootDiskOfferingInfo, List<DiskOfferingInfo> dataDiskOfferings, List<Long> dataDiskDeviceIds,
            LinkedHashMap<? extends Network, List<? extends NicProfile>> auxiliaryNetworks, DeploymentPlan plan,
            HypervisorType hyperType, Map<String, Map<Integer, String>> extraDhcpOptions,
            Map<Long, DiskOffering> datadiskTemplateToDiskOfferingMap, Volume volume, Snapshot snapshot)
            throws InsufficientCapacityException;

    void allocate(String vmInstanceName, VirtualMachineTemplate template, ServiceOffering serviceOffering,
            LinkedHashMap<? extends Network, List<? extends NicProfile>> networks, DeploymentPlan plan,
            HypervisorType hyperType, Volume volume, Snapshot snapshot) throws InsufficientCapacityException;

    void allocateRootVolume(VMInstanceVO vm, VirtualMachineTemplate template, DiskOfferingInfo rootDiskOfferingInfo,
            Account owner, Long rootDiskSizeFinal, Volume volume, Snapshot snapshot);

    void checkIfTemplateNeededForCreatingVmVolumes(VMInstanceVO vm);
}
