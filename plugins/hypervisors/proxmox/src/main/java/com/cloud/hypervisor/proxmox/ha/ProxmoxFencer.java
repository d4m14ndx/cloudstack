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
package com.cloud.hypervisor.proxmox.ha;

import java.util.List;

import javax.inject.Inject;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.FenceAnswer;
import com.cloud.agent.api.FenceCommand;
import com.cloud.alert.AlertManager;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.ha.FenceBuilder;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceManager;
import com.cloud.utils.component.AdapterBase;
import com.cloud.vm.VirtualMachine;

/**
 * Fences a VM off a failed Proxmox host by asking the other Up hosts (nodes) in the same
 * cluster to execute a FenceCommand, mirroring the KVM fencer. The ProxmoxResource on a
 * healthy node verifies through the shared PVE API that the VM is no longer running on the
 * failed node before HA restarts it elsewhere.
 */
public class ProxmoxFencer extends AdapterBase implements FenceBuilder {

    @Inject
    private AgentManager _agentMgr;
    @Inject
    private AlertManager _alertMgr;
    @Inject
    private ResourceManager _resourceMgr;

    public ProxmoxFencer() {
        super();
    }

    @Override
    public Boolean fenceOff(VirtualMachine vm, Host host) {
        if (host.getHypervisorType() != HypervisorType.Proxmox) {
            logger.warn("Don't know how to fence non-Proxmox hosts {}", host.getHypervisorType());
            return null;
        }

        List<HostVO> hosts = _resourceMgr.listAllHostsInCluster(host.getClusterId());
        FenceCommand fence = new FenceCommand(vm, host);

        int i = 0;
        for (HostVO h : hosts) {
            if (h.getHypervisorType() != HypervisorType.Proxmox) {
                continue;
            }
            if (h.getStatus() != Status.Up) {
                continue;
            }

            i++;

            if (h.getId() == host.getId()) {
                continue;
            }

            FenceAnswer answer;
            try {
                answer = (FenceAnswer)_agentMgr.send(h.getId(), fence);
            } catch (AgentUnavailableException | OperationTimedoutException e) {
                logger.info("Moving on to the next host because {} is unavailable", h, e);
                continue;
            }
            if (answer != null && answer.getResult()) {
                return true;
            }
        }

        _alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_HOST, host.getDataCenterId(), host.getPodId(),
                "Unable to fence off host: " + host.getId(),
                "Fencing off host " + host.getId() + " did not succeed after asking " + i + " hosts. Check Agent logs for more information.");

        logger.error("Unable to fence off {} on {}", vm, host);

        return false;
    }
}
