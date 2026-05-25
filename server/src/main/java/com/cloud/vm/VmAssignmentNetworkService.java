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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.cloudstack.api.command.admin.vm.AssignVMCmd;

import com.cloud.dc.DataCenterVO;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.network.Network;
import com.cloud.network.dao.NetworkVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;

public interface VmAssignmentNetworkService {

    Network ensureDestinationNetwork(AssignVMCmd cmd, UserVmVO vm, Account newAccount) throws InsufficientCapacityException, ResourceAllocationException;

    NetworkOfferingVO getOfferingWithRequiredAvailabilityForNetworkCreation();

    void updateVmNetwork(AssignVMCmd cmd, Account caller, UserVmVO vm, Account newAccount, VirtualMachineTemplate template)
            throws InsufficientCapacityException, ResourceAllocationException;

    void updateBasicTypeNetworkForVm(UserVmVO vm, Account newAccount, VirtualMachineTemplate template, VirtualMachineProfileImpl vmOldProfile,
            DataCenterVO zone, List<Long> networkIdList, List<Long> securityGroupIdList) throws InsufficientCapacityException;

    void updateAdvancedTypeNetworkForVm(Account caller, UserVmVO vm, Account newAccount, VirtualMachineTemplate template,
            VirtualMachineProfileImpl vmOldProfile, DataCenterVO zone, List<Long> networkIdList, List<Long> securityGroupIdList)
            throws InsufficientCapacityException, ResourceAllocationException, InvalidParameterValueException;

    void cleanupOfOldOwnerNicsForNetwork(VirtualMachineProfileImpl vmOldProfile);

    void addDefaultNetworkToNetworkList(List<NetworkVO> networkList, Network defaultNetwork);

    void allocateNetworksForVm(UserVmVO vm, LinkedHashMap<Network, List<? extends NicProfile>> networks) throws InsufficientCapacityException;

    void addSecurityGroupsToVm(Account newAccount, UserVmVO vm, VirtualMachineTemplate template, List<Long> securityGroupIdList, Network defaultNetwork);

    void addNetworksToNetworkIdList(UserVmVO vm, Account newAccount, List<Long> networkIdList, Set<NetworkVO> applicableNetworks,
            Map<Long, String> requestedIPv4ForNics, Map<Long, String> requestedIPv6ForNics);

    NetworkVO addNicsToApplicableNetworksAndReturnDefaultNetwork(LinkedHashSet<NetworkVO> applicableNetworks, Map<Long, String> requestedIPv4ForNics,
            Map<Long, String> requestedIPv6ForNics, LinkedHashMap<Network, List<? extends NicProfile>> networks);

    void selectApplicableNetworkToCreateVm(Account newAccount, DataCenterVO zone, Set<NetworkVO> applicableNetworks)
            throws InsufficientCapacityException, ResourceAllocationException;

    void addDefaultSecurityGroupToSecurityGroupIdList(Account newAccount, List<Long> securityGroupIdList);

    void keepOldSharedNetworkForVm(UserVmVO vm, Account newAccount, List<Long> networkIdList, Set<NetworkVO> applicableNetworks,
            Map<Long, String> requestedIPv4ForNics, Map<Long, String> requestedIPv6ForNics);

    void addAdditionalNetworksToVm(UserVmVO vm, Account newAccount, List<Long> networkIdList, Set<NetworkVO> applicableNetworks,
            Map<Long, String> requestedIPv4ForNics, Map<Long, String> requestedIPv6ForNics);

    NetworkVO createApplicableNetworkToCreateVm(Account newAccount, DataCenterVO zone) throws InsufficientCapacityException, ResourceAllocationException;

    Network implementNetwork(Account caller, DataCenterVO zone, Network newNetwork);

    boolean canAccountUseNetwork(Account newAccount, Network network);
}
