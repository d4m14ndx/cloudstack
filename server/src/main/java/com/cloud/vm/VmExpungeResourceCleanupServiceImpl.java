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
import java.util.Objects;

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.kubernetes.cluster.KubernetesServiceHelper;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.element.NsxProviderVO;
import com.cloud.network.lb.LoadBalancingRulesManager;
import com.cloud.network.rules.FirewallManager;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.security.SecurityGroupManager;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VmExpungeResourceCleanupServiceImpl implements VmExpungeResourceCleanupService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private UserVmDao userVmDao;
    @Inject
    private NetworkOrchestrationService networkMgr;
    @Inject
    private SecurityGroupManager securityGroupMgr;
    @Inject
    private VmGroupService vmGroupService;
    @Inject
    private FirewallManager firewallMgr;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private NsxProviderDao nsxProviderDao;
    @Inject
    private List<KubernetesServiceHelper> kubernetesServiceHelpers;
    @Inject
    private RulesManager rulesMgr;
    @Inject
    private LoadBalancingRulesManager lbMgr;
    @Inject
    private IPAddressDao ipAddressDao;
    @Inject
    private AccountManager accountMgr;

    @Override
    public void releaseNetworkResourcesOnExpunge(long vmId) throws ConcurrentOperationException, ResourceUnavailableException {
        final VMInstanceVO vmInstance = userVmDao.findById(vmId);
        if (vmInstance != null) {
            final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vmInstance);
            networkMgr.release(profile, false);
        } else {
            logger.error("Couldn't find vm with id = " + vmId + ", unable to release network resources");
        }
    }

    @Override
    public boolean cleanupVmResources(UserVmVO vm) {
        long vmId = vm.getId();
        boolean success = true;
        // Remove vm from security groups
        securityGroupMgr.removeInstanceFromGroups(vm);

        // Remove vm from instance group
        vmGroupService.removeInstanceFromInstanceGroup(vmId);

        // cleanup firewall rules
        if (firewallMgr.revokeFirewallRulesForVm(vmId)) {
            logger.debug("Firewall rules are removed successfully as a part of vm {} expunge", vm);
        } else {
            success = false;
            logger.warn("Fail to remove firewall rules as a part of vm {} expunge", vm);
        }

        // cleanup port forwarding rules
        VMInstanceVO vmInstanceVO = vmInstanceDao.findById(vmId);
        NsxProviderVO nsx = nsxProviderDao.findByZoneId(vmInstanceVO.getDataCenterId());
        if (Objects.isNull(nsx) || Objects.isNull(kubernetesServiceHelpers.get(0).findByVmId(vmId))) {
            if (rulesMgr.revokePortForwardingRulesForVm(vmId)) {
                logger.debug("Port forwarding rules are removed successfully as a part of vm {} expunge", vm);
            } else {
                success = false;
                logger.warn("Fail to remove port forwarding rules as a part of vm {} expunge", vm);
            }
        }

        // cleanup load balancer rules
        if (lbMgr.removeVmFromLoadBalancers(vmId)) {
            logger.debug("Removed vm {} from all load balancers as a part of expunge process", vm);
        } else {
            success = false;
            logger.warn("Fail to remove vm {} from load balancers as a part of expunge process", vm);
        }

        // If vm is assigned to static nat, disable static nat for the ip
        // address and disassociate ip if elasticIP is enabled
        List<IPAddressVO> ips = ipAddressDao.findAllByAssociatedVmId(vmId);

        for (IPAddressVO ip : ips) {
            try {
                if (rulesMgr.disableStaticNat(ip.getId(), accountMgr.getAccount(Account.ACCOUNT_ID_SYSTEM), User.UID_SYSTEM, true)) {
                    logger.debug("Disabled 1-1 NAT for IP address {} as a part of Instance {} expunge", ip, vm);
                } else {
                    logger.warn("Failed to disable static NAT for IP address {} as a part of Instance {} expunge", ip, vm);
                    success = false;
                }
            } catch (ResourceUnavailableException e) {
                success = false;
                logger.warn("Failed to disable static NAT for IP address {} as a part of Instance {} expunge because resource is unavailable", ip, vm, e);
            }
        }

        return success;
    }
}
