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
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckOnHostAnswer;
import com.cloud.agent.api.CheckOnHostCommand;
import com.cloud.ha.Investigator;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceManager;
import com.cloud.utils.component.AdapterBase;
import com.cloud.vm.VirtualMachine;

/**
 * Investigates the state of Proxmox hosts, mirroring the KVM investigator: the host itself is
 * asked first, then the other Up hosts (nodes) of the same cluster are asked to check on it
 * with a CheckOnHostCommand. A neighbour node answers through the shared PVE cluster API, so
 * it can tell whether the node is really down or only unreachable from the management server.
 */
public class ProxmoxInvestigator extends AdapterBase implements Investigator {

    @Inject
    private AgentManager _agentMgr;
    @Inject
    private ResourceManager _resourceMgr;

    protected ProxmoxInvestigator() {
    }

    @Override
    public boolean isVmAlive(VirtualMachine vm, Host host) throws UnknownVM {
        if (vm.getHypervisorType() != HypervisorType.Proxmox) {
            throw new UnknownVM();
        }

        Status status = getHostAgentStatus(host);
        logger.debug("HA: investigated status {} for host {} while checking VM {}", status, host, vm);
        if (status == Status.Up) {
            return true;
        }
        throw new UnknownVM();
    }

    @Override
    public Status getHostAgentStatus(Host agent) {
        if (agent.getHypervisorType() != HypervisorType.Proxmox) {
            return null;
        }

        CheckOnHostCommand cmd = new CheckOnHostCommand(agent);

        Status hostStatus = null;
        try {
            Answer answer = _agentMgr.easySend(agent.getId(), cmd);
            hostStatus = interpretCheckOnHostAnswer(answer);
        } catch (Exception e) {
            logger.debug("Failed to send command to host: {}", agent, e);
        }
        if (hostStatus == null) {
            hostStatus = Status.Disconnected;
        }

        Status neighbourStatus = null;
        List<HostVO> neighbors = _resourceMgr.listHostsInClusterByStatus(agent.getClusterId(), Status.Up);
        for (HostVO neighbor : neighbors) {
            if (neighbor.getId() == agent.getId() || neighbor.getHypervisorType() != HypervisorType.Proxmox) {
                continue;
            }
            logger.debug("Investigating host: {} via neighbouring host: {}", agent, neighbor);
            try {
                Answer answer = _agentMgr.easySend(neighbor.getId(), cmd);
                Status answerStatus = interpretCheckOnHostAnswer(answer);
                if (answerStatus != null) {
                    neighbourStatus = answerStatus;
                    logger.debug("Neighbouring host: {} returned status: {} for the investigated host: {}", neighbor, neighbourStatus, agent);
                    if (neighbourStatus == Status.Up) {
                        break;
                    }
                }
            } catch (Exception e) {
                logger.debug("Failed to send command to host: {}", neighbor, e);
            }
        }

        if (neighbourStatus == Status.Up && (hostStatus == Status.Disconnected || hostStatus == Status.Down)) {
            hostStatus = Status.Disconnected;
        }
        if (neighbourStatus == Status.Down && (hostStatus == Status.Disconnected || hostStatus == Status.Down)) {
            hostStatus = Status.Down;
        }

        logger.debug("HA: investigated status {} for host {}", hostStatus, agent);
        return hostStatus;
    }

    /**
     * Maps a {@link CheckOnHostAnswer} to a host {@link Status}, mirroring the KVM idiom
     * (see KVMHostActivityChecker). Note that CheckOnHostAnswer#getResult() is ALWAYS true
     * for a determined/undetermined reply and only false on the error constructor, so it
     * cannot be used as the aliveness signal. Aliveness lives in isDetermined()/isAlive():
     * <ul>
     *   <li>null answer (no reply from the agent) -&gt; null (Disconnected, keep looking)</li>
     *   <li>error answer (getResult()==false) -&gt; Disconnected (transport/API failure)</li>
     *   <li>undetermined (not quorate / no IP / node not found) -&gt; null (keep looking)</li>
     *   <li>determined &amp; alive -&gt; Up</li>
     *   <li>determined &amp; not alive -&gt; Down</li>
     * </ul>
     * Returning null signals the caller to keep polling neighbours rather than fencing a
     * host whose state a given responder simply could not determine.
     */
    private Status interpretCheckOnHostAnswer(Answer answer) {
        if (answer == null) {
            return null;
        }
        if (!(answer instanceof CheckOnHostAnswer)) {
            // Non-CheckOnHostAnswer (e.g. an error/unsupported answer) means the responder
            // could not check; treat as disconnected rather than a definitive Down.
            return Status.Disconnected;
        }
        CheckOnHostAnswer checkAnswer = (CheckOnHostAnswer) answer;
        if (!answer.getResult()) {
            // Error constructor: the responder failed to run the check at all.
            return Status.Disconnected;
        }
        if (!checkAnswer.isDetermined()) {
            // The responder replied but could not determine aliveness; keep looking.
            return null;
        }
        return checkAnswer.isAlive() ? Status.Up : Status.Down;
    }
}
