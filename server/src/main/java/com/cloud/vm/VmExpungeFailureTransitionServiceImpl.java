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

import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;

@Component
public class VmExpungeFailureTransitionServiceImpl implements VmExpungeFailureTransitionService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private UserVmDao userVmDao;
    @Inject
    private VirtualMachineManager virtualMachineManager;

    @Override
    public void transitionExpungingToError(long vmId) {
        UserVmVO vm = userVmDao.findById(vmId);
        if (vm != null && vm.getState() == State.Expunging) {
            try {
                boolean transitioned = virtualMachineManager.stateTransitTo(vm, VirtualMachine.Event.OperationFailedToError, null);
                if (transitioned) {
                    logger.info("Transitioned VM [{}] from Expunging to Error after failed expunge", vm.getUuid());
                } else {
                    logger.warn("Failed to persist transition of VM [{}] from Expunging to Error after failed expunge, possibly due to concurrent update", vm.getUuid());
                }
            } catch (NoTransitionException e) {
                logger.warn("Failed to transition VM {} to Error state: {}", vm, e.getMessage());
            }
        }
    }
}
