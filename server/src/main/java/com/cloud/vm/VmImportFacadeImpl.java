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
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.cloud.configuration.Config;
import com.cloud.dc.DataCenter;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.ServiceOffering;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.uservm.UserVm;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.db.UUIDManager;
import com.cloud.vm.dao.UserVmDao;

@Component
public class VmImportFacadeImpl implements VmImportFacade {

    @Inject
    private ConfigurationDao configDao;
    @Inject
    private UserVmDao vmDao;
    @Inject
    private UUIDManager uuidManager;

    @Override
    public UserVm importVM(final DataCenter zone, final Host host, final VirtualMachineTemplate template, final String instanceNameInternal, final String displayName,
                           final Account owner, final String userData, final Boolean isDisplayVm, final String keyboard, final long accountId, final long userId,
                           final ServiceOffering serviceOffering, final String sshPublicKeys, final Long guestOsId, final String hostName, final HypervisorType hypervisorType,
                           final Map<String, String> customParameters, final VirtualMachine.PowerState powerState,
                           final LinkedHashMap<String, List<NicProfile>> networkNicMap, final ManagerOperations operations) throws InsufficientCapacityException {
        return Transaction.execute((TransactionCallbackWithException<UserVm, InsufficientCapacityException>) status -> {
            if (zone == null) {
                throw new InvalidParameterValueException("Unable to import virtual machine with invalid zone");
            }
            if (host == null && hypervisorType == HypervisorType.VMware) {
                throw new InvalidParameterValueException("Unable to import virtual machine with invalid host");
            }

            final long id = vmDao.getNextInSequence(Long.class, "id");
            String instanceName = StringUtils.isBlank(instanceNameInternal) ?
                    getInternalName(owner.getAccountId(), id) :
                    instanceNameInternal;

            if (hostName != null) {
                operations.checkNameForRFCCompliance(hostName);
            }

            final String uuidName = uuidManager.generateUuid(UserVm.class, null);
            final Host lastHost = powerState != VirtualMachine.PowerState.PowerOn ? host : null;
            final Boolean dynamicScalingEnabled = operations.checkIfDynamicScalingCanBeEnabled(null, serviceOffering, template, zone.getId());
            Allocation allocation = new Allocation(zone, host, lastHost, template, hostName, displayName, owner, userData, isDisplayVm, keyboard,
                    accountId, userId, serviceOffering, guestOsId, sshPublicKeys, networkNicMap, id, instanceName, uuidName,
                    hypervisorType, customParameters, powerState, dynamicScalingEnabled);
            return operations.commitUserVm(allocation);
        });
    }

    String getInternalName(long accountId, long vmId) {
        String instanceSuffix = configDao.getValue(Config.InstanceName.key());
        if (instanceSuffix == null) {
            instanceSuffix = "DEFAULT";
        }
        return VirtualMachineName.getVmName(vmId, accountId, instanceSuffix);
    }

    @Override
    public void setVmRequiredFieldsForImport(boolean isImport, UserVmVO vm, DataCenter zone, HypervisorType hypervisorType,
                                             Host host, Host lastHost, VirtualMachine.PowerState powerState) {
        if (isImport) {
            vm.setDataCenterId(zone.getId());
            if (List.of(HypervisorType.VMware, HypervisorType.KVM).contains(hypervisorType) && host != null) {
                vm.setHostId(host.getId());
            }
            if (lastHost != null) {
                vm.setLastHostId(lastHost.getId());
            }
            vm.setPowerState(powerState);
            if (powerState == VirtualMachine.PowerState.PowerOn) {
                vm.setState(VirtualMachine.State.Running);
            }
        }
    }
}
