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

import jakarta.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import org.apache.cloudstack.acl.SecurityChecker.AccessType;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVMMapVO;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.vm.VirtualMachine.State;

/**
 * Pre-flight validation for VM ownership transfer — extracted from
 * {@link UserVmManagerImpl}.
 *
 * @see VmAssignmentValidator
 */
@Component
public class VmAssignmentValidatorImpl implements VmAssignmentValidator {
    private static final Logger LOG = LogManager.getLogger(VmAssignmentValidatorImpl.class);

    @Inject
    private PortForwardingRulesDao portForwardingDao;
    @Inject
    private FirewallRulesDao rulesDao;
    @Inject
    private LoadBalancerVMMapDao loadBalancerVMMapDao;
    @Inject
    private IPAddressDao ipAddressDao;
    @Inject
    private SnapshotDao snapshotDao;
    @Inject
    private AccountManager accountManager;

    @Override
    public void validateIfVmSupportsMigration(UserVmVO vm, Long vmId) {
        LOG.trace("Validating if VM [{}] exists and is not in state [{}].", vmId, State.Running);

        if (vm == null) {
            throw new InvalidParameterValueException(String.format("There is no VM by ID [%s].", vmId));
        } else if (vm.getState() == State.Running) {
            throw new InvalidParameterValueException(String.format("Unable to move VM [%s] in [%s] state.", vm, vm.getState()));
        } else if (UserVmManager.SHAREDFSVM.equals(vm.getUserVmType())) {
            throw new InvalidParameterValueException("Migration is not supported for Shared FileSystem Instances.");
        }
    }

    @Override
    public void validateIfVmHasNoRules(UserVmVO vm, Long vmId) throws InvalidParameterValueException {
        LOG.trace("Validating if VM [{}] has no Port Forwarding, Static Nat, Load Balancing or One to One Nat rules.", vm);

        List<PortForwardingRuleVO> portForwardingRules = portForwardingDao.listByVm(vmId);
        if (CollectionUtils.isNotEmpty(portForwardingRules)) {
            throw new InvalidParameterValueException(String.format("Remove any Port Forwarding rules for VM [%s] before assigning it to another user.", vm));
        }

        List<FirewallRuleVO> staticNatRules = rulesDao.listStaticNatByVmId(vmId);
        if (CollectionUtils.isNotEmpty(staticNatRules)) {
            throw new InvalidParameterValueException(String.format("Remove the StaticNat rules for VM [%s] before assigning it to another user.", vm));
        }

        List<LoadBalancerVMMapVO> loadBalancerVmMaps = loadBalancerVMMapDao.listByInstanceId(vmId);
        if (CollectionUtils.isNotEmpty(loadBalancerVmMaps)) {
            throw new InvalidParameterValueException(String.format("Remove the Load Balancing rules for VM [%s] before assigning it to another user.", vm));
        }

        List<IPAddressVO> ips = ipAddressDao.findAllByAssociatedVmId(vmId);
        for (IPAddressVO ip : ips) {
            if (ip.isOneToOneNat()) {
                throw new InvalidParameterValueException(String.format("Remove the One to One Nat rule for VM [%s] for IP [%s].", vm, ip));
            }
        }
    }

    @Override
    public void validateIfVolumesHaveNoSnapshots(List<VolumeVO> volumes) throws InvalidParameterValueException {
        LOG.trace("Verifying if there are any snapshots for any of the VM volumes.");
        for (VolumeVO volume : volumes) {
            LOG.trace("Verifying snapshots for volume [{}].", volume);
            List<SnapshotVO> snapshots = snapshotDao.listByStatusNotIn(volume.getId(), Snapshot.State.Destroyed, Snapshot.State.Error);
            if (CollectionUtils.isNotEmpty(snapshots)) {
                throw new InvalidParameterValueException(String.format("Snapshots exist for volume [%s]. Detach volume or remove snapshots for the volume before assigning VM to "
                        + "another user.", volume.getName()));
            }
        }
    }

    @Override
    public void validateIfNewOwnerHasAccessToTemplate(UserVmVO vm, Account newAccount, VirtualMachineTemplate template) {
        LOG.trace("Validating if new owner [{}] has access to the template specified for VM [{}].", newAccount, vm);

        if (template == null) {
            throw new InvalidParameterValueException(String.format("Template for VM [%s] cannot be found.", vm.getUuid()));
        }

        LOG.debug("Verifying if new owner [{}] has access to the template [{}].", newAccount, template.getUuid());
        try {
            accountManager.checkAccess(newAccount, AccessType.UseEntry, true, template);
        } catch (PermissionDeniedException e) {
            String newMsg = String.format("New owner [%s] does not have access to the template specified for VM [%s].", newAccount, vm);
            throw new PermissionDeniedException(newMsg, e);
        }
    }

    @Override
    public void validateOldAndNewAccounts(Account oldAccount,
                                          Account newAccount,
                                          Long oldAccountId,
                                          String newAccountName,
                                          Long domainId) throws InvalidParameterValueException {

        LOG.trace("Validating old [{}] and new accounts [{}].", oldAccount, newAccount);

        if (oldAccount == null) {
            throw new InvalidParameterValueException(String.format("Invalid old account [%s] for VM in domain [%s].", oldAccountId, domainId));
        }

        if (newAccount == null) {
            throw new InvalidParameterValueException(String.format("Invalid new account [%s] for VM in domain [%s].", newAccountName, domainId));
        }

        if (newAccount.getState() == Account.State.DISABLED) {
            throw new InvalidParameterValueException(String.format("The new account owner [%s] is disabled.", newAccount));
        }

        if (oldAccount.getAccountId() == newAccount.getAccountId()) {
            throw new InvalidParameterValueException(String.format("The new account [%s] is the same as the old account.", newAccount));
        }
    }

    @Override
    public void checkCallerAccessToAccounts(Account caller, Account oldAccount, Account newAccount) {
        LOG.trace("Verifying if caller [{}] has access to old account [{}].", caller, oldAccount);
        accountManager.checkAccess(caller, null, true, oldAccount);

        LOG.trace("Verifying if caller [{}] has access to new account [{}].", caller, newAccount);
        accountManager.checkAccess(caller, null, true, newAccount);
    }
}
