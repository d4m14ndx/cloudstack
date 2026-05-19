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

import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.UpdateVmNicAnswer;
import com.cloud.agent.api.UpdateVmNicCommand;
import com.cloud.agent.manager.Commands;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.NicDao;

@Component
public class VmNicUpdateServiceImpl implements VmNicUpdateService {

    private static final Logger logger = LogManager.getLogger(VmNicUpdateServiceImpl.class);

    @Inject
    protected AgentManager agentMgr;
    @Inject
    protected NicDao nicsDao;

    @Override
    public Boolean updateDefaultNicForVM(final VirtualMachine vm, final Nic nic, final Nic defaultNic) {
        logger.debug("Updating default nic of vm {} from nic {} to nic {}", vm, defaultNic.getUuid(), nic.getUuid());
        Integer chosenID = nic.getDeviceId();
        Integer existingID = defaultNic.getDeviceId();
        NicVO nicVO = nicsDao.findById(nic.getId());
        NicVO defaultNicVO = nicsDao.findById(defaultNic.getId());

        nicVO.setDefaultNic(true);
        nicVO.setDeviceId(existingID);
        defaultNicVO.setDefaultNic(false);
        defaultNicVO.setDeviceId(chosenID);

        nicsDao.persist(nicVO);
        nicsDao.persist(defaultNicVO);
        return true;
    }

    @Override
    public boolean updateVmNic(final VirtualMachine vm, final Nic nic, final Boolean enabled) throws ResourceUnavailableException {
        if (vm.getState() == State.Running) {
            try {
                UpdateVmNicCommand updateVmNicCmd = new UpdateVmNicCommand(nic.getMacAddress(), vm.getName(), enabled);
                Commands cmds = new Commands(Command.OnError.Stop);
                cmds.addCommand("updatevmnic", updateVmNicCmd);

                agentMgr.send(vm.getHostId(), cmds);

                UpdateVmNicAnswer updateVmNicAnswer = cmds.getAnswer(UpdateVmNicAnswer.class);
                if (updateVmNicAnswer == null || !updateVmNicAnswer.getResult()) {
                    logger.warn("Unable to update VM {} NIC [{}].", vm.getName(), nic.getUuid());
                    return false;
                }
            } catch (final OperationTimedoutException e) {
                throw new AgentUnavailableException(String.format("Unable to update NIC %s for VM %s.", nic.getUuid(), vm.getUuid()), vm.getHostId(), e);
            }
        }

        NicVO nicVo = nicsDao.findById(nic.getId());
        nicVo.setEnabled(enabled);
        nicsDao.persist(nicVo);

        return true;
    }
}
