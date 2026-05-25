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

import org.apache.cloudstack.api.command.user.vm.AddNicToVMCmd;
import org.apache.cloudstack.api.command.user.vm.RemoveNicFromVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateDefaultNicForVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateVmNicIpCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateVmNicCmd;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.uservm.UserVm;

/**
 * VM NIC lifecycle — adding, removing, and updating NICs on a running or
 * stopped instance.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4 god-class
 * decomposition (Spring-component slice 3). The {@link UserVmManager} /
 * {@link com.cloud.vm.UserVmService} interface methods continue to exist on
 * {@code UserVmManagerImpl} as delegating wrappers so all interface contracts
 * and existing test spies keep working.
 */
public interface VmNicService {

    UserVm addNicToVirtualMachine(AddNicToVMCmd cmd)
            throws InvalidParameterValueException, PermissionDeniedException, CloudRuntimeException;

    UserVm removeNicFromVirtualMachine(RemoveNicFromVMCmd cmd)
            throws InvalidParameterValueException, PermissionDeniedException, CloudRuntimeException;

    UserVm updateDefaultNicForVirtualMachine(UpdateDefaultNicForVMCmd cmd)
            throws InvalidParameterValueException, CloudRuntimeException;

    UserVm updateNicIpForVirtualMachine(UpdateVmNicIpCmd cmd);

    UserVm updateVirtualMachineNic(UpdateVmNicCmd cmd);

    /**
     * If the given MAC address is not a valid MAC, returns the next available
     * MAC for the given network. Exposed for legacy callers and tests that
     * exercised it on UserVmManagerImpl.
     */
    String validateOrReplaceMacAddress(String macAddress, com.cloud.network.dao.NetworkVO network);
}
