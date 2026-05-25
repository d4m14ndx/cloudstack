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

import java.util.Map;

import jakarta.inject.Inject;

import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.MigrateCommand;
import com.cloud.agent.api.to.DpdkTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.ItWorkVO.Step;

@Component
public class VmScaleReconfigurationActionsImpl implements VmScaleReconfigurationActions {

    @Inject
    @Lazy
    protected VirtualMachineManagerImpl virtualMachineManager;

    @Override
    public VirtualMachineGuru getVmGuru(final VirtualMachine vm) {
        return virtualMachineManager.getVmGuru(vm);
    }

    @Override
    public VirtualMachineTO toVmTO(final VirtualMachineProfile profile) {
        return virtualMachineManager.toVmTO(profile);
    }

    @Override
    public boolean changeState(final VMInstanceVO vm, final VirtualMachine.Event event, final Long hostId, final ItWorkVO work, final Step step)
            throws NoTransitionException {
        return virtualMachineManager.changeState(vm, event, hostId, work, step);
    }

    @Override
    public MigrateCommand buildMigrateCommand(final VMInstanceVO vmInstance, final VirtualMachineTO virtualMachineTO, final DeployDestination destination,
            final Answer answer, final Map<String, DpdkTO> dpdkInterfaceMapping) {
        return virtualMachineManager.buildMigrateCommand(vmInstance, virtualMachineTO, destination, answer, dpdkInterfaceMapping);
    }

    @Override
    public boolean checkVmOnHost(final VirtualMachine vm, final long hostId) throws AgentUnavailableException, OperationTimedoutException {
        return virtualMachineManager.checkVmOnHost(vm, hostId);
    }

    @Override
    public Command cleanup(final String vmName) {
        return virtualMachineManager.cleanup(vmName);
    }

    @Override
    public boolean cleanup(final VirtualMachineGuru guru, final VirtualMachineProfile profile, final ItWorkVO work, final VirtualMachine.Event event,
            final boolean cleanUpEvenIfUnableToStop) {
        return virtualMachineManager.cleanup(guru, profile, work, event, cleanUpEvenIfUnableToStop);
    }

    @Override
    public boolean stateTransitTo(final VirtualMachine vm, final VirtualMachine.Event event, final Long hostId) throws NoTransitionException {
        return virtualMachineManager.stateTransitTo(vm, event, hostId);
    }

    @Override
    public void updateVmPod(final VMInstanceVO vm, final long dstHostId) {
        virtualMachineManager.updateVmPod(vm, dstHostId);
    }

    @Override
    public long getNodeId() {
        return virtualMachineManager.getNodeId();
    }
}
