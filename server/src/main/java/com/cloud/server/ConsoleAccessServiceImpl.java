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

import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVncPortAnswer;
import com.cloud.agent.api.GetVncPortCommand;
import com.cloud.agent.api.proxy.AllowConsoleAccessCommand;
import com.cloud.consoleproxy.ConsoleProxyManager;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host.Type;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.info.ConsoleProxyInfo;
import com.cloud.utils.Pair;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * @see ConsoleAccessService
 */
@Component
public class ConsoleAccessServiceImpl implements ConsoleAccessService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private ConsoleProxyManager consoleProxyManager;
    @Inject
    private HostDao hostDao;
    @Inject
    private AgentManager agentManager;

    @Override
    public String getConsoleAccessUrlRoot(final long vmId) {
        final VMInstanceVO vm = vmInstanceDao.findById(vmId);
        if (vm != null) {
            final ConsoleProxyInfo proxy = getConsoleProxyForVm(vm.getDataCenterId(), vm);
            if (proxy != null) {
                return proxy.getProxyImageUrl();
            }
        }
        return null;
    }

    @Override
    public Pair<Boolean, String> setConsoleAccessForVm(final long vmId, final String sessionUuid) {
        final VMInstanceVO vm = vmInstanceDao.findById(vmId);
        if (vm == null) {
            return new Pair<>(false, "Cannot find an instance with id = " + vmId);
        }
        final ConsoleProxyInfo proxy = getConsoleProxyForVm(vm.getDataCenterId(), vm);
        if (proxy == null) {
            return new Pair<>(false, "Cannot find a console proxy for the instance " + vmId);
        }
        final AllowConsoleAccessCommand cmd = new AllowConsoleAccessCommand(sessionUuid);
        final HostVO hostVO = hostDao.findByTypeNameAndZoneId(vm.getDataCenterId(), proxy.getProxyName(), Type.ConsoleProxy);
        if (hostVO == null) {
            return new Pair<>(false, "Cannot find a console proxy agent for CPVM with name " + proxy.getProxyName());
        }
        Answer answer;
        try {
            answer = agentManager.send(hostVO.getId(), cmd);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            final String errorMsg = "Could not send allow session command to CPVM: " + e.getMessage();
            logger.error(errorMsg, e);
            return new Pair<>(false, errorMsg);
        }
        boolean result = false;
        String details = "null answer";

        if (answer != null) {
            result = answer.getResult();
            details = answer.getDetails();
        }
        return new Pair<>(result, details);
    }

    @Override
    public String getConsoleAccessAddress(final long vmId) {
        final VMInstanceVO vm = vmInstanceDao.findById(vmId);
        if (vm != null) {
            final ConsoleProxyInfo proxy = getConsoleProxyForVm(vm.getDataCenterId(), vm);
            return proxy != null ? proxy.getProxyAddress() : null;
        }
        return null;
    }

    @Override
    public Pair<String, Integer> getVncPort(final VirtualMachine vm) {
        if (vm.getHostId() == null) {
            logger.warn("Instance " + vm.getHostName() + " does not have host, return -1 for its VNC port");
            return new Pair<>(null, -1);
        }

        if (logger.isTraceEnabled()) {
            logger.trace("Trying to retrieve VNC port from agent about Instance " + vm.getHostName());
        }

        final GetVncPortAnswer answer;
        if (vm.getState() == State.Migrating && vm.getLastHostId() != null) {
            answer = (GetVncPortAnswer) agentManager.easySend(vm.getLastHostId(), new GetVncPortCommand(vm.getId(), vm.getInstanceName()));
        } else {
            answer = (GetVncPortAnswer) agentManager.easySend(vm.getHostId(), new GetVncPortCommand(vm.getId(), vm.getInstanceName()));
        }
        if (answer != null && answer.getResult()) {
            return new Pair<>(answer.getAddress(), answer.getPort());
        }

        return new Pair<>(null, -1);
    }

    /**
     * Ask the {@link ConsoleProxyManager} which console proxy currently owns
     * (or should now own) the supplied instance in the given data centre.
     * Kept package-private so the unit tests can stub the result of the
     * assignment lookup, and so the three public lookups above can share a
     * single helper rather than re-deriving the proxy each call site.
     */
    ConsoleProxyInfo getConsoleProxyForVm(final long dataCenterId, final VMInstanceVO userVm) {
        return consoleProxyManager.assignProxy(dataCenterId, userVm);
    }
}
