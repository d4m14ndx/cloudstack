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

import org.apache.cloudstack.api.command.user.vm.BaseDeployVMCmd;
import org.apache.cloudstack.api.command.user.vm.CreateVMFromBackupCmd;

import com.cloud.dc.DataCenter;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.Network.IpAddresses;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.Snapshot;
import com.cloud.storage.Volume;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;

public interface VmBackupInstanceLifecycleService {

    UserVm allocateVMFromBackup(CreateVMFromBackupCmd cmd, ManagerOperations managerOperations)
            throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException;

    UserVm restoreVMFromBackup(CreateVMFromBackupCmd cmd, ManagerOperations managerOperations)
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException;

    interface ManagerOperations {
        void verifyDetails(Map<String, String> details);

        void verifyServiceOffering(BaseDeployVMCmd cmd, ServiceOffering serviceOffering);

        void verifyTemplate(BaseDeployVMCmd cmd, VirtualMachineTemplate template, Long serviceOfferingId);

        UserVm createVirtualMachine(BaseDeployVMCmd cmd, DataCenter zone, Account owner,
                ServiceOffering serviceOffering, VirtualMachineTemplate template, HypervisorType hypervisor,
                Long diskOfferingId, Long size, Long overrideDiskOfferingId, List<VmDiskInfo> dataDiskInfoList,
                List<Long> networkIds, Map<Long, IpAddresses> ipToNetworkMap, Volume volume, Snapshot snapshot)
                throws InsufficientCapacityException, ResourceUnavailableException, ResourceAllocationException;

        Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> startVirtualMachine(long vmId, Long podId,
                Long clusterId, Long hostId, Map<VirtualMachineProfile.Param, Object> additionalParams,
                String deploymentPlannerToUse)
                throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException;

        UserVm startVirtualMachine(long vmId, Long podId, Long clusterId, Long hostId,
                Map<Long, DiskOffering> diskOfferingMap, Map<VirtualMachineProfile.Param, Object> additionalParams,
                String deploymentPlannerToUse)
                throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException;

        boolean expunge(UserVmVO vm);

        UserVmVO resetVMSSHKeyInternal(UserVmVO userVm, Account owner, List<String> names)
                throws ResourceUnavailableException, InsufficientCapacityException;

        Network getDefaultNetwork(DataCenter zone, Account owner, boolean selectAny)
                throws InsufficientCapacityException, ResourceAllocationException;
    }
}
