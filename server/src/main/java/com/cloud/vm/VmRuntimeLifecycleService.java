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
import com.cloud.agent.manager.Commands;
import com.cloud.deploy.DeployDestination;

public interface VmRuntimeLifecycleService {

    boolean finalizeVirtualMachineProfile(VirtualMachineProfile profile, DeployDestination dest, ReservationContext context);

    boolean setupVmForPvlan(boolean add, Long hostId, NicProfile nic);

    boolean finalizeDeployment(Commands cmds, VirtualMachineProfile profile, DeployDestination dest, ReservationContext context);

    boolean finalizeCommandsOnStart(Commands cmds, VirtualMachineProfile profile);

    boolean finalizeStart(VirtualMachineProfile profile, long hostId, Commands cmds, ReservationContext context,
            Map<Long, UserVmManagerImpl.VmAndCountDetails> vmIdCountMap);

    void updateVncPasswordIfItHasChanged(String originalVncPassword, String returnedVncPassword, VirtualMachineProfile profile);

    void finalizeStop(VirtualMachineProfile profile, Answer answer);
}
