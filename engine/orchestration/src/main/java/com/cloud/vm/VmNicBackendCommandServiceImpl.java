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

import java.util.HashMap;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.commons.collections.MapUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.PlugNicAnswer;
import com.cloud.agent.api.PlugNicCommand;
import com.cloud.agent.api.ReplugNicAnswer;
import com.cloud.agent.api.ReplugNicCommand;
import com.cloud.agent.api.UnPlugNicAnswer;
import com.cloud.agent.api.UnPlugNicCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.manager.Commands;
import com.cloud.dc.DataCenter;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.Host;
import com.cloud.network.Network;
import com.cloud.network.dao.NetworkDetailVO;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VmNicBackendCommandServiceImpl implements VmNicBackendCommandService {

    private static final Logger logger = LogManager.getLogger(VmNicBackendCommandServiceImpl.class);

    @Inject
    protected AgentManager agentMgr;
    @Inject
    protected VMInstanceDao vmDao;
    @Inject
    protected UserVmDao userVmDao;
    @Inject
    protected UserVmService userVmService;
    @Inject
    protected NetworkDetailsDao networkDetailsDao;
    @Inject
    protected VmVlanPersistenceMappingService vmVlanPersistenceMappingService;

    @Override
    public boolean replugNic(final Network network, final NicTO nic, final VirtualMachineTO vm, final Host host) throws ConcurrentOperationException,
            ResourceUnavailableException, InsufficientCapacityException {
        boolean result = true;

        final VMInstanceVO router = vmDao.findById(vm.getId());
        if (router.getState() == State.Running) {
            try {
                final ReplugNicCommand replugNicCmd = new ReplugNicCommand(nic, vm.getName(), vm.getType(), vm.getDetails());
                final Commands cmds = new Commands(Command.OnError.Stop);
                cmds.addCommand("replugnic", replugNicCmd);
                agentMgr.send(host.getId(), cmds);
                final ReplugNicAnswer replugNicAnswer = cmds.getAnswer(ReplugNicAnswer.class);
                if (replugNicAnswer == null || !replugNicAnswer.getResult()) {
                    logger.warn("Unable to replug nic for vm {}", vm.getName());
                    result = false;
                }
            } catch (final OperationTimedoutException e) {
                throw new AgentUnavailableException("Unable to plug nic for router " + vm.getName() + " in network " + network, host.getId(), e);
            }
        } else {
            String message = String.format("Unable to apply ReplugNic, VM [%s] is not in the right state (\"Running\"). VM state [%s].", router.toString(), router.getState());
            logger.warn(message);

            throw new ResourceUnavailableException(message, DataCenter.class, router.getDataCenterId());
        }

        return result;
    }

    @Override
    public boolean plugNic(final Network network, final NicTO nic, final VirtualMachineTO vm, final ReservationContext context, final DeployDestination dest)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        boolean result = true;

        final VMInstanceVO router = vmDao.findById(vm.getId());
        if (router.getState() == State.Running) {
            try {
                NetworkDetailVO pvlanTypeDetail = networkDetailsDao.findDetail(network.getId(), ApiConstants.ISOLATED_PVLAN_TYPE);
                if (pvlanTypeDetail != null) {
                    Map<NetworkOffering.Detail, String> nicDetails = nic.getDetails() == null ? new HashMap<>() : nic.getDetails();
                    logger.debug("Found PVLAN type: {} on network details, adding it as part of the PlugNicCommand", pvlanTypeDetail.getValue());
                    nicDetails.putIfAbsent(NetworkOffering.Detail.pvlanType, pvlanTypeDetail.getValue());
                    nic.setDetails(nicDetails);
                }
                final PlugNicCommand plugNicCmd = new PlugNicCommand(nic, vm.getName(), vm.getType(), vm.getDetails());
                final Commands cmds = new Commands(Command.OnError.Stop);
                cmds.addCommand("plugnic", plugNicCmd);
                agentMgr.send(dest.getHost().getId(), cmds);
                final PlugNicAnswer plugNicAnswer = cmds.getAnswer(PlugNicAnswer.class);
                if (plugNicAnswer == null || !plugNicAnswer.getResult()) {
                    logger.warn("Unable to plug nic for vm {}", vm.getName());
                    result = false;
                }
            } catch (final OperationTimedoutException e) {
                throw new AgentUnavailableException("Unable to plug nic for router " + vm.getName() + " in network " + network, dest.getHost().getId(), e);
            }
        } else {
            String message = String.format("Unable to apply PlugNic, VM [%s] is not in the right state (\"Running\"). VM state [%s].", router.toString(), router.getState());
            logger.warn(message);

            throw new ResourceUnavailableException(message, DataCenter.class,
                    router.getDataCenterId());
        }

        return result;
    }

    @Override
    public boolean unplugNic(final Network network, final NicTO nic, final VirtualMachineTO vm, final ReservationContext context, final DeployDestination dest)
            throws ConcurrentOperationException, ResourceUnavailableException {

        boolean result = true;
        final VMInstanceVO router = vmDao.findById(vm.getId());

        if (router.getState() == State.Running) {
            UserVmVO userVm = userVmDao.findById(vm.getId());
            if (userVm != null && userVm.getType() == VirtualMachine.Type.User) {
                userVmService.collectVmNetworkStatistics(userVm);
            }
            try {
                final Commands cmds = new Commands(Command.OnError.Stop);
                final UnPlugNicCommand unplugNicCmd = new UnPlugNicCommand(nic, vm.getName());
                Map<String, Boolean> vlanToPersistenceMap = vmVlanPersistenceMappingService.getVlanToPersistenceMapForVM(vm.getId());
                if (MapUtils.isNotEmpty(vlanToPersistenceMap)) {
                    unplugNicCmd.setVlanToPersistenceMap(vlanToPersistenceMap);
                }
                cmds.addCommand("unplugnic", unplugNicCmd);
                agentMgr.send(dest.getHost().getId(), cmds);

                final UnPlugNicAnswer unplugNicAnswer = cmds.getAnswer(UnPlugNicAnswer.class);
                if (unplugNicAnswer == null || !unplugNicAnswer.getResult()) {
                    logger.warn("Unable to unplug nic from router {}", router);
                    result = false;
                }
            } catch (final OperationTimedoutException e) {
                throw new AgentUnavailableException("Unable to unplug nic from rotuer " + router + " from network " + network, dest.getHost().getId(), e);
            }
        } else if (router.getState() == State.Stopped || router.getState() == State.Stopping) {
            logger.debug("Vm {} is in {}, so not sending unplug nic command to the backend", router.getInstanceName(), router.getState());
        } else {
            String message = String.format("Unable to apply unplug nic, VM [%s] is not in the right state (\"Running\"). VM state [%s].", router.toString(), router.getState());
            logger.warn(message);

            throw new ResourceUnavailableException(message, DataCenter.class, router.getDataCenterId());
        }

        return result;
    }
}
