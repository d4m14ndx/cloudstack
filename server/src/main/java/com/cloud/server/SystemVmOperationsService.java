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
package com.cloud.server;

import java.util.List;

import org.apache.cloudstack.api.command.admin.systemvm.PatchSystemVMCmd;
import org.apache.cloudstack.api.command.admin.systemvm.ScaleSystemVMCmd;
import org.apache.cloudstack.api.command.admin.systemvm.UpgradeSystemVMCmd;
import org.apache.cloudstack.api.command.admin.systemvm.ListSystemVMsCmd;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.utils.Pair;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;

/**
 * Handles system VM patch, upgrade and search operations extracted from
 * {@link ManagementServerImpl} as part of the Phase 4 Spring-component
 * decomposition. The five public entry points remain on the god class as
 * one-line delegating wrappers so that callers bound to the
 * {@link ManagementService} interface continue to resolve their calls there.
 */
public interface SystemVmOperationsService {

    VirtualMachine upgradeSystemVM(ScaleSystemVMCmd cmd)
        throws ResourceUnavailableException, ManagementServerException,
               VirtualMachineMigrationException, ConcurrentOperationException;

    VirtualMachine upgradeSystemVM(UpgradeSystemVMCmd cmd);

    Pair<Boolean, String> patchSystemVM(PatchSystemVMCmd cmd);

    /** Used by the patch flow when the systemVM is already resolved. */
    Pair<Boolean, String> updateSystemVM(VMInstanceVO systemVM, boolean forced);

    Pair<List<? extends VirtualMachine>, Integer> searchForSystemVm(ListSystemVMsCmd cmd);
}
