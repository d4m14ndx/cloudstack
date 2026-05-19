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

import java.net.URI;

import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;

public interface VmNetworkAttachmentOrchestrationService {

    NicProfile addVmToNetwork(VirtualMachine vm, Network network, NicProfile requested, BackendNicOperations backendNicOperations)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException;

    void checkIfNetworkExistsForUserVM(VirtualMachine virtualMachine, Network network);

    NicTO toNicTO(NicProfile nic, HypervisorType hypervisorType);

    boolean removeNicFromVm(VirtualMachine vm, Nic nic, BackendNicOperations backendNicOperations)
            throws ConcurrentOperationException, ResourceUnavailableException;

    boolean removeVmFromNetwork(VirtualMachine vm, Network network, URI broadcastUri, BackendNicOperations backendNicOperations)
            throws ConcurrentOperationException, ResourceUnavailableException;

    interface BackendNicOperations {
        boolean plugNic(Network network, NicTO nic, VirtualMachineTO vm, ReservationContext context, DeployDestination dest)
                throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException;

        boolean unplugNic(Network network, NicTO nic, VirtualMachineTO vm, ReservationContext context, DeployDestination dest)
                throws ConcurrentOperationException, ResourceUnavailableException;
    }
}
