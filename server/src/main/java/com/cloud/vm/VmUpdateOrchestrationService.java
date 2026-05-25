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

import org.apache.cloudstack.api.BaseCmd.HTTPMethod;
import org.apache.cloudstack.api.command.user.vm.UpdateVMCmd;

import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.dao.NetworkVO;
import com.cloud.uservm.UserVm;

public interface VmUpdateOrchestrationService {

    void verifyVmLimits(UserVmVO vmInstance, Map<String, String> details);

    UserVm updateVirtualMachine(UpdateVMCmd cmd) throws ResourceUnavailableException, InsufficientCapacityException;

    void updateDisplayVmFlag(Boolean isDisplayVm, Long id, UserVmVO vmInstance);

    void validateInputsAndPermissionForUpdateVirtualMachineCommand(UpdateVMCmd cmd);

    UserVm updateVirtualMachine(long id,
                                String displayName,
                                String group,
                                Boolean ha,
                                Boolean isDisplayVmEnabled,
                                Boolean deleteProtection,
                                Long osTypeId,
                                String userData,
                                Long userDataId,
                                String userDataDetails,
                                Boolean isDynamicallyScalable,
                                HTTPMethod httpMethod,
                                String customId,
                                String hostName,
                                String instanceName,
                                List<Long> securityGroupIdList,
                                Map<String, Map<Integer, String>> extraDhcpOptionsMap)
            throws ResourceUnavailableException, InsufficientCapacityException;

    void checkAndUpdateSecurityGroupForVM(List<Long> securityGroupIdList, UserVmVO vm, List<NetworkVO> networks);

    void updateUserData(UserVm vm) throws ResourceUnavailableException, InsufficientCapacityException;

    void updateDns(UserVmVO vm, String hostName) throws ResourceUnavailableException, InsufficientCapacityException;
}
