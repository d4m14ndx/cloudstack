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

import com.cloud.dc.DataCenter;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.dao.NetworkVO;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;

public interface VmCreationNetworkSelectionService {

    VmCreationSecurityGroupNetworkSelection selectBasicSecurityGroupNetworks(DataCenter zone, VirtualMachineTemplate template,
            List<Long> securityGroupIdList, Account owner, HypervisorType hypervisor);

    VmCreationSecurityGroupNetworkSelection selectAdvancedSecurityGroupNetworks(DataCenter zone, VirtualMachineTemplate template,
            List<Long> networkIdList, List<Long> securityGroupIdList, Account owner, HypervisorType hypervisor, String eventSource);

    List<NetworkVO> selectAdvancedNetworks(DataCenter zone, VirtualMachineTemplate template, List<Long> networkIdList, Account owner,
            HypervisorType hypervisor, Map<String, Map<Integer, String>> dhcpOptionsMap)
            throws InsufficientCapacityException, ResourceAllocationException;

    NetworkVO validateVpcNetworkAndReturnIt(VirtualMachineTemplate template, Account owner, HypervisorType hypervisor,
            List<HypervisorType> vpcSupportedHTypes, Long networkId);

    NetworkVO getDefaultNetwork(DataCenter zone, Account owner, boolean selectAny)
            throws InsufficientCapacityException, ResourceAllocationException;

    void verifyExtraDhcpOptionsNetwork(Map<String, Map<Integer, String>> dhcpOptionsMap, List<NetworkVO> networkList)
            throws InvalidParameterValueException;

    class VmCreationSecurityGroupNetworkSelection {
        private final List<NetworkVO> networkList;
        private final List<Long> securityGroupIdList;

        public VmCreationSecurityGroupNetworkSelection(List<NetworkVO> networkList, List<Long> securityGroupIdList) {
            this.networkList = networkList;
            this.securityGroupIdList = securityGroupIdList;
        }

        public List<NetworkVO> getNetworkList() {
            return networkList;
        }

        public List<Long> getSecurityGroupIdList() {
            return securityGroupIdList;
        }
    }
}
