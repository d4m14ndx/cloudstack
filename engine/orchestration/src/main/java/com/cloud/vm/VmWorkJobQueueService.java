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
import java.util.Map;

import org.apache.cloudstack.framework.jobs.Outcome;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;

import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.offering.ServiceOffering;

public interface VmWorkJobQueueService {
    VmWorkJobVO createPlaceHolderWork(long instanceId);

    VmWorkJobVO createPlaceHolderWork(long instanceId, String secondaryObjectIdentifier);

    void expungePlaceHolderWork(VmWorkJobVO placeHolder);

    Outcome<VirtualMachine> startVmThroughJobQueue(String vmUuid, Map<VirtualMachineProfile.Param, Object> params, DeploymentPlan planToDeploy, DeploymentPlanner planner);

    Outcome<VirtualMachine> stopVmThroughJobQueue(String vmUuid, boolean cleanup);

    Outcome<VirtualMachine> rebootVmThroughJobQueue(String vmUuid, Map<VirtualMachineProfile.Param, Object> params);

    Outcome<VirtualMachine> migrateVmThroughJobQueue(String vmUuid, long srcHostId, DeployDestination dest);

    Outcome<VirtualMachine> migrateVmAwayThroughJobQueue(String vmUuid, long srcHostId);

    Outcome<VirtualMachine> migrateVmWithStorageThroughJobQueue(String vmUuid, long srcHostId, long destHostId, Map<Long, Long> volumeToPool);

    Outcome<VirtualMachine> migrateVmForScaleThroughJobQueue(String vmUuid, long srcHostId, DeployDestination dest, Long newSvcOfferingId);

    Outcome<VirtualMachine> migrateVmStorageThroughJobQueue(String vmUuid, Map<Long, Long> volumeToPool);

    Outcome<VirtualMachine> addVmToNetworkThroughJobQueue(VirtualMachine vm, Network network, NicProfile requested);

    Outcome<VirtualMachine> removeNicFromVmThroughJobQueue(VirtualMachine vm, Nic nic);

    Outcome<VirtualMachine> removeVmFromNetworkThroughJobQueue(VirtualMachine vm, Network network, URI broadcastUri);

    Outcome<VirtualMachine> reconfigureVmThroughJobQueue(String vmUuid, ServiceOffering oldServiceOffering, ServiceOffering newServiceOffering,
            Map<String, String> customParameters, boolean reconfiguringOnExistingHost);

    Outcome<VirtualMachine> restoreVirtualMachineThroughJobQueue(long vmId, Long newTemplateId, Long rootDiskOfferingId, boolean expunge, Map<String, String> details);

    Outcome<VirtualMachine> updateDefaultNicForVMThroughJobQueue(VirtualMachine vm, Nic nic, Nic defaultNic);

    Outcome<VirtualMachine> updateVmNicThroughJobQueue(VirtualMachine vm, Nic nic, Boolean isNicEnabled);

    VirtualMachine retrieveVmFromJobOutcome(Outcome<VirtualMachine> jobOutcome, String vmUuid, String jobName);

    Object retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(Outcome<VirtualMachine> outcome) throws ResourceUnavailableException, InsufficientCapacityException;
}
