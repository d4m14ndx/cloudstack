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

import org.apache.cloudstack.api.command.user.vm.SecurityGroupAction;

import com.cloud.dc.DataCenter;
import com.cloud.network.dao.NetworkVO;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;

/**
 * Security-group ID resolution and stopped-VM security-group
 * reassignment — the helpers that gate which security groups a VM is
 * a member of, including the VNF-appliance default group injection.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 14). The god class keeps
 * one-line delegating wrappers so existing test spies and call sites
 * continue to work.
 */
public interface VmSecurityGroupAssignmentService {

    /**
     * Resolve a {@link SecurityGroupAction}'s {@code securitygroupnames}
     * to security-group ids, rejecting the request if both
     * {@code securitygroupids} and {@code securitygroupnames} are
     * supplied (they are mutually exclusive). Returns the
     * {@code securitygroupids} list unchanged when no names are given.
     *
     * @throws com.cloud.exception.InvalidParameterValueException
     *         if both lists are supplied, or if a referenced name does
     *         not resolve to a group in the caller's account.
     */
    List<Long> getSecurityGroupIdList(SecurityGroupAction cmd);

    /**
     * Same as {@link #getSecurityGroupIdList(SecurityGroupAction)},
     * but additionally injects the default security group created by
     * the VNF template manager when {@code cmd} is a
     * {@code DeployVnfApplianceCmd} (and the template manager actually
     * minted a group). The returned list is non-null if a VNF group is
     * created, even if the user supplied no explicit groups.
     */
    List<Long> getSecurityGroupIdList(SecurityGroupAction cmd, DataCenter zone, VirtualMachineTemplate template, Account owner);

    /**
     * Apply a new set of security groups to {@code vm} as part of an
     * update-VM call. Rejects VMware VMs (the security-group feature
     * is unsupported there); for all other hypervisors, resolves the
     * networks the VM is in (defaulting to the zone's exclusive guest
     * network in Basic zones), checks the network model permits the
     * caller's account/zone/networks/groups combination, and then
     * reassigns the VM via {@link #updateSecurityGroup} — which itself
     * requires the VM to be in the {@code Stopped} state.
     *
     * @throws com.cloud.exception.InvalidParameterValueException
     *         when the hypervisor is VMware, or when the VM is not
     *         Stopped at update time.
     */
    void checkAndUpdateSecurityGroupForVM(List<Long> securityGroupIdList, UserVmVO vm, List<NetworkVO> networks);

    /**
     * Replace {@code vm}'s security-group membership with
     * {@code securityGroupIdList}. Requires the VM to be in the
     * {@code Stopped} state — the security-group manager removes and
     * re-adds memberships, which is unsafe on a running VM.
     *
     * @throws com.cloud.exception.InvalidParameterValueException
     *         if the VM is not in the {@code Stopped} state.
     */
    void updateSecurityGroup(UserVmVO vm, List<Long> securityGroupIdList);
}
