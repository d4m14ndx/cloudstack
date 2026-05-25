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

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.manager.Commands;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class VmExpungeCommandServiceImpl implements VmExpungeCommandService {

    private static final Logger logger = LogManager.getLogger(VmExpungeCommandServiceImpl.class);

    @Inject
    protected AgentManager agentMgr;

    @Override
    public void sendVolumeExpungeCommands(List<Command> volumeExpungeCommands, Long hostId, VMInstanceVO vm)
            throws OperationTimedoutException, AgentUnavailableException {
        if (CollectionUtils.isEmpty(volumeExpungeCommands) || hostId == null) {
            return;
        }

        final Commands cmds = new Commands(Command.OnError.Stop);
        for (final Command volumeExpungeCommand : volumeExpungeCommands) {
            volumeExpungeCommand.setBypassHostMaintenance(isValidSystemVMType(vm));
            cmds.addCommand(volumeExpungeCommand);
        }

        agentMgr.send(hostId, cmds);
        handleUnsuccessfulCommands(cmds, vm);
    }

    @Override
    public void sendFinalizeExpungeCommands(List<Command> finalizeExpungeCommands, List<Command> nicExpungeCommands,
            VMInstanceVO vm, Long hostId) throws OperationTimedoutException, AgentUnavailableException {
        if ((CollectionUtils.isEmpty(finalizeExpungeCommands) && CollectionUtils.isEmpty(nicExpungeCommands)) || hostId == null) {
            return;
        }

        final Commands cmds = new Commands(Command.OnError.Stop);
        addAllExpungeCommandsFromList(finalizeExpungeCommands, cmds, vm);
        addAllExpungeCommandsFromList(nicExpungeCommands, cmds, vm);
        agentMgr.send(hostId, cmds);
        if (!cmds.isSuccessful()) {
            for (final Answer answer : cmds.getAnswers()) {
                if (!answer.getResult()) {
                    logger.warn("Failed to expunge vm due to: {}", answer.getDetails());
                    throw new CloudRuntimeException(String.format("Unable to expunge %s due to %s", vm, answer.getDetails()));
                }
            }
        }
    }

    protected void handleUnsuccessfulCommands(Commands cmds, VMInstanceVO vm) throws CloudRuntimeException {
        String cmdsStr = cmds.toString();
        String vmToString = vm.toString();

        if (cmds.isSuccessful()) {
            logger.debug("The commands [{}] to {} were successful.", cmdsStr, vmToString);
            return;
        }

        logger.info("The commands [{}] to {} were unsuccessful. Handling answers.", cmdsStr, vmToString);

        Answer[] answers = cmds.getAnswers();
        if (answers == null) {
            logger.debug("There are no answers to commands [{}] to {}.", cmdsStr, vmToString);
            return;
        }

        for (Answer answer : answers) {
            String details = answer.getDetails();
            if (!answer.getResult()) {
                String message = String.format("Unable to expunge %s due to [%s].", vmToString, details);
                logger.error(message);
                throw new CloudRuntimeException(message);
            }

            logger.debug("Commands [{}] to {} got answer [{}].", cmdsStr, vmToString, details);
        }
    }

    private void addAllExpungeCommandsFromList(List<Command> cmdList, Commands cmds, VMInstanceVO vm) {
        if (CollectionUtils.isEmpty(cmdList)) {
            return;
        }
        for (final Command command : cmdList) {
            command.setBypassHostMaintenance(isValidSystemVMType(vm));
            logger.trace("Adding expunge command [{}] for VM [{}]", command.toString(), vm.toString());
            cmds.addCommand(command);
        }
    }

    private boolean isValidSystemVMType(VirtualMachine vm) {
        return VirtualMachine.Type.SecondaryStorageVm.equals(vm.getType()) ||
                VirtualMachine.Type.ConsoleProxy.equals(vm.getType());
    }
}
