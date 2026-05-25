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

import org.apache.cloudstack.api.command.user.vmgroup.CreateVMGroupCmd;
import org.apache.cloudstack.api.command.user.vmgroup.DeleteVMGroupCmd;

/**
 * VM Group management — creating named groups of VMs and assigning instances
 * to them.
 *
 * <p>Extracted from {@link UserVmManagerImpl} (Phase 4 god-class decomposition).
 * The public methods on {@code UserVmManagerImpl} that match these signatures
 * are kept as delegating wrappers so the {@code UserVmManager} /
 * {@code UserVmService} interface contracts (and any tests that mock them)
 * continue to work unchanged.
 */
public interface VmGroupService {

    /**
     * Create a new VM group owned by the account identified in the command's
     * caller / project / account fields. Fails if a group with the same name
     * already exists for that account.
     */
    InstanceGroupVO createVmGroup(CreateVMGroupCmd cmd);

    /**
     * Lower-level helper: create (or return existing) group for an account.
     * Acquires the account row lock so duplicate group creation is impossible.
     */
    InstanceGroupVO createVmGroup(String groupName, long accountId);

    /**
     * Delete a VM group identified by the command's group id. Checks calling
     * account access first.
     */
    boolean deleteVmGroup(DeleteVMGroupCmd cmd);

    /**
     * Delete a VM group by id, without an access check. Removes all
     * {@code group_vm_map} entries first.
     */
    boolean deleteVmGroup(long groupId);

    /**
     * Add a user VM to a named group (creating the group if needed). Currently
     * a VM can only belong to a single group, so any existing membership is
     * replaced.
     */
    boolean addInstanceToGroup(long userVmId, String groupName);

    /**
     * Returns the (single) group a VM belongs to, or null if unassigned.
     */
    InstanceGroupVO getGroupForVm(long vmId);

    /**
     * Remove a VM from all groups it belongs to.
     */
    void removeInstanceFromInstanceGroup(long vmId);
}
