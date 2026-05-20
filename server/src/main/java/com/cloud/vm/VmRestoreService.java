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

import java.util.Map;

import org.apache.cloudstack.api.command.user.vm.RestoreVMCmd;

import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.offering.DiskOffering;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;

public interface VmRestoreService {

    UserVm restoreVM(RestoreVMCmd cmd)
            throws InsufficientCapacityException, ResourceUnavailableException, ResourceAllocationException;

    UserVm restoreVirtualMachine(Account caller, long vmId, Long newTemplateId,
            Long rootDiskOfferingId, boolean expunge, Map<String, String> details)
            throws InsufficientCapacityException, ResourceUnavailableException;

    UserVm restoreVMInternal(Account caller, UserVmVO vm, Long newTemplateId,
            Long rootDiskOfferingId, boolean expunge, Map<String, String> details)
            throws InsufficientCapacityException, ResourceUnavailableException, ResourceAllocationException;

    UserVm restoreVMInternal(Account caller, UserVmVO vm)
            throws InsufficientCapacityException, ResourceUnavailableException, ResourceAllocationException;

    Long getRootVolumeSizeForVmRestore(Volume vol, VMTemplateVO template, UserVmVO userVm,
            DiskOffering diskOffering, Map<String, String> details, boolean update);
}
