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

/**
 * Narrow callback interface allowing the scale/reconfiguration service to reuse
 * migration and state-machine helpers that still live on
 * {@link VirtualMachineManagerImpl}.
 */
interface VmScaleReconfigurationActions {

    VirtualMachineGuru getVmGuru(VirtualMachine vm);

    VirtualMachineTO toVmTO(VirtualMachineProfile profile);

    boolean changeState(VMInstanceVO vm, VirtualMachine.Event event, Long hostId, ItWorkVO work, Step step) throws NoTransitionException;

    MigrateCommand buildMigrateCommand(VMInstanceVO vmInstance, VirtualMachineTO virtualMachineTO, DeployDestination destination, Answer answer,
            Map<String, DpdkTO> dpdkInterfaceMapping);

    boolean checkVmOnHost(VirtualMachine vm, long hostId) throws AgentUnavailableException, OperationTimedoutException;

    Command cleanup(String vmName);

    boolean cleanup(VirtualMachineGuru guru, VirtualMachineProfile profile, ItWorkVO work, VirtualMachine.Event event, boolean cleanUpEvenIfUnableToStop);

    boolean stateTransitTo(VirtualMachine vm, VirtualMachine.Event event, Long hostId) throws NoTransitionException;

    void updateVmPod(VMInstanceVO vm, long dstHostId);

    long getNodeId();
}
