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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.user.vm.DeployVnfApplianceCmd;
import org.apache.cloudstack.api.command.user.vm.SecurityGroupAction;
import org.apache.cloudstack.storage.template.VnfTemplateManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.security.SecurityGroup;
import com.cloud.network.security.SecurityGroupManager;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.vm.VirtualMachine.State;

/**
 * Security-group ID resolution + stopped-VM security-group reassignment
 * helpers — extracted from {@link UserVmManagerImpl}.
 *
 * @see VmSecurityGroupAssignmentService
 */
@Component
public class VmSecurityGroupAssignmentServiceImpl implements VmSecurityGroupAssignmentService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private SecurityGroupManager securityGroupManager;
    @Inject
    private VnfTemplateManager vnfTemplateManager;
    @Inject
    private DataCenterDao dataCenterDao;
    @Inject
    private NetworkModel networkModel;
    @Inject
    private AccountManager accountManager;

    @Override
    public List<Long> getSecurityGroupIdList(SecurityGroupAction cmd) {
        if (cmd.getSecurityGroupNameList() != null && cmd.getSecurityGroupIdList() != null) {
            throw new InvalidParameterValueException("securitygroupids parameter is mutually exclusive with securitygroupnames parameter");
        }

        //transform group names to ids here
        if (cmd.getSecurityGroupNameList() != null) {
            List<Long> securityGroupIds = new ArrayList<>();
            for (String groupName : cmd.getSecurityGroupNameList()) {
                SecurityGroup sg = securityGroupManager.getSecurityGroup(groupName, cmd.getEntityOwnerId());
                if (sg == null) {
                    throw new InvalidParameterValueException("Unable to find group by name " + groupName);
                } else {
                    securityGroupIds.add(sg.getId());
                }
            }
            return securityGroupIds;
        } else {
            return cmd.getSecurityGroupIdList();
        }
    }

    @Override
    public List<Long> getSecurityGroupIdList(SecurityGroupAction cmd, DataCenter zone, VirtualMachineTemplate template, Account owner) {
        List<Long> securityGroupIdList = getSecurityGroupIdList(cmd);
        if (cmd instanceof DeployVnfApplianceCmd) {
            SecurityGroup securityGroup = vnfTemplateManager.createSecurityGroupForVnfAppliance(zone, template, owner, (DeployVnfApplianceCmd) cmd);
            if (securityGroup != null) {
                if (securityGroupIdList == null) {
                    securityGroupIdList = new ArrayList<>();
                }
                securityGroupIdList.add(securityGroup.getId());
            }
        }
        return securityGroupIdList;
    }

    @Override
    public void checkAndUpdateSecurityGroupForVM(List<Long> securityGroupIdList, UserVmVO vm, List<NetworkVO> networks) {
        boolean isVMware = (vm.getHypervisorType() == HypervisorType.VMware);

        if (securityGroupIdList != null && isVMware) {
            throw new InvalidParameterValueException("Security group feature is not supported for VMware hypervisor");
        } else if (securityGroupIdList != null) {
            DataCenterVO zone = dataCenterDao.findById(vm.getDataCenterId());
            List<Long> networkIds = new ArrayList<>();
            try {
                if (zone.getNetworkType() == NetworkType.Basic) {
                    // Get default guest network in Basic zone
                    Network defaultNetwork = networkModel.getExclusiveGuestNetwork(zone.getId());
                    networkIds.add(defaultNetwork.getId());
                } else {
                    networkIds = networks.stream().map(Network::getId).collect(Collectors.toList());
                }
            } catch (InvalidParameterValueException e) {
                if (logger.isDebugEnabled()) {
                    logger.debug(e.getMessage(), e);
                }
            }

            if (networkModel.checkSecurityGroupSupportForNetwork(
                    accountManager.getActiveAccountById(vm.getAccountId()),
                    zone, networkIds, securityGroupIdList)
            ) {
                updateSecurityGroup(vm, securityGroupIdList);
            }
        }
    }

    @Override
    public void updateSecurityGroup(UserVmVO vm, List<Long> securityGroupIdList) {
        if (vm.getState() == State.Stopped) {
            // Remove instance from security groups
            securityGroupManager.removeInstanceFromGroups(vm);
            // Add instance in provided groups
            securityGroupManager.addInstanceToGroups(vm, securityGroupIdList);
        } else {
            throw new InvalidParameterValueException(String.format("VM %s must be stopped prior to update security groups", vm.getUuid()));
        }
    }
}
