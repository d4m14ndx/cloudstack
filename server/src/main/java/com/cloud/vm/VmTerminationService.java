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

import org.apache.cloudstack.api.command.user.vm.DestroyVMCmd;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.offering.ServiceOffering;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.uservm.UserVm;

public interface VmTerminationService {

    boolean stopVirtualMachine(long userId, long vmId);

    UserVm destroyVm(DestroyVMCmd cmd, ManagerOperations managerOperations)
            throws ResourceUnavailableException, ConcurrentOperationException;

    UserVm stopVirtualMachine(long vmId, boolean forced) throws ConcurrentOperationException;

    UserVm destroyVm(long vmId, boolean expunge, ManagerOperations managerOperations)
            throws ResourceUnavailableException, ConcurrentOperationException;

    interface ManagerOperations {
        UserVm stopVirtualMachine(long vmId, boolean forced) throws ConcurrentOperationException;

        UserVm destroyVm(long vmId, boolean expunge) throws ResourceUnavailableException, ConcurrentOperationException;

        boolean expunge(UserVmVO vm);

        void transitionExpungingToError(long vmId);

        void resourceCountDecrement(long accountId, Boolean displayVm,
                ServiceOffering serviceOffering, VirtualMachineTemplate template);

        Boolean getDestroyRootVolumeOnVmDestruction(Long domainId);
    }
}
