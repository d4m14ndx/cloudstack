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

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.Outcome;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.RebootAnswer;
import com.cloud.agent.api.RebootCommand;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.manager.Commands;
import com.cloud.dc.DataCenter;
import com.cloud.dc.Pod;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.Host;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.security.SecurityGroupManager;
import com.cloud.org.Cluster;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.StorageManager;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshotManager;

@Component
public class VmRebootOrchestrationServiceImpl implements VmRebootOrchestrationService {

    private static final Logger logger = LogManager.getLogger(VmRebootOrchestrationServiceImpl.class);

    @Inject
    protected AgentManager agentMgr;
    @Inject
    protected VMInstanceDao vmDao;
    @Inject
    protected VMSnapshotManager vmSnapshotMgr;
    @Inject
    protected EntityManager entityMgr;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected VmWorkJobQueueService vmWorkJobQueueService;
    @Inject
    protected VmCommandSpecPostProcessingService vmCommandSpecPostProcessingService;
    @Inject
    protected VmExternalProvisioningManager vmExternalProvisioningManager;
    @Inject
    protected SecurityGroupManager securityGroupManager;
    @Inject
    protected ResourceManager resourceMgr;
    @Inject
    protected NicDao nicsDao;
    @Inject
    protected NetworkModel networkModel;
    @Inject
    protected HypervisorGuruManager hvGuruMgr;

    @Override
    public void reboot(final String vmUuid, final Map<VirtualMachineProfile.Param, Object> params)
            throws InsufficientCapacityException, ResourceUnavailableException {
        try {
            advanceReboot(vmUuid, params);
        } catch (final ConcurrentOperationException e) {
            throw new CloudRuntimeException("Unable to reboot a VM due to concurrent operation", e);
        }
    }

    @Override
    public void advanceReboot(final String vmUuid, final Map<VirtualMachineProfile.Param, Object> params)
            throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {

        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            final VirtualMachine vm = vmDao.findByUuid(vmUuid);
            VmWorkJobVO placeHolder = vmWorkJobQueueService.createPlaceHolderWork(vm.getId());
            try {
                logger.debug("reboot parameter value of {} == {} at orchestration", VirtualMachineProfile.Param.BootIntoSetup.getName(),
                        (params == null ? "<very null>" : params.get(VirtualMachineProfile.Param.BootIntoSetup)));
                orchestrateReboot(vmUuid, params);
            } finally {
                vmWorkJobQueueService.expungePlaceHolderWork(placeHolder);
            }
        } else {
            logger.debug("reboot parameter value of {} == {} through job-queue", VirtualMachineProfile.Param.BootIntoSetup.getName(),
                    (params == null ? "<very null>" : params.get(VirtualMachineProfile.Param.BootIntoSetup)));
            final Outcome<VirtualMachine> outcome = vmWorkJobQueueService.rebootVmThroughJobQueue(vmUuid, params);

            vmWorkJobQueueService.retrieveVmFromJobOutcome(outcome, vmUuid, "rebootVm");

            vmWorkJobQueueService.retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
        }
    }

    @Override
    public void orchestrateReboot(final String vmUuid, final Map<VirtualMachineProfile.Param, Object> params) throws InsufficientCapacityException,
            ConcurrentOperationException, ResourceUnavailableException {
        final VMInstanceVO vm = vmDao.findByUuid(vmUuid);
        if (vmSnapshotMgr.hasActiveVMSnapshotTasks(vm.getId())) {
            logger.error("Unable to reboot Instance: {} due to: {} has active Instance Snapshot tasks", vm, vm.getInstanceName());
            throw new CloudRuntimeException("Unable to reboot Instance: " + vm + " due to: " + vm.getInstanceName() + " has active Instance Snapshots tasks");
        }
        final DataCenter dc = entityMgr.findById(DataCenter.class, vm.getDataCenterId());
        final Host host = hostDao.findById(vm.getHostId());
        if (host == null) {
            throw new CloudRuntimeException("Unable to retrieve host with id " + vm.getHostId());
        }
        final Cluster cluster = entityMgr.findById(Cluster.class, host.getClusterId());
        final Pod pod = entityMgr.findById(Pod.class, host.getPodId());
        final DeployDestination dest = new DeployDestination(dc, pod, cluster, host);

        try {
            final Commands cmds = new Commands(Command.OnError.Stop);
            RebootCommand rebootCmd = new RebootCommand(vm.getInstanceName(), getExecuteInSequence(vm.getHypervisorType()));
            VirtualMachineTO vmTo = getVmTO(vm.getId());
            vmCommandSpecPostProcessingService.setEnterSetupMode(vmTo, params);
            rebootCmd.setVirtualMachine(vmTo);
            vmExternalProvisioningManager.updateRebootCommandWithExternalDetails(host, vmTo, rebootCmd);
            cmds.addCommand(rebootCmd);
            agentMgr.send(host.getId(), cmds);

            final Answer rebootAnswer = cmds.getAnswer(RebootAnswer.class);
            if (rebootAnswer != null && rebootAnswer.getResult()) {
                boolean isVmSecurityGroupEnabled = securityGroupManager.isVmSecurityGroupEnabled(vm.getId());
                if (isVmSecurityGroupEnabled && vm.getType() == VirtualMachine.Type.User) {
                    List<Long> affectedVms = new ArrayList<>();
                    affectedVms.add(vm.getId());
                    securityGroupManager.scheduleRulesetUpdateToHosts(affectedVms, true, null);
                }
                if (vmTo.getGpuDevice() != null) {
                    resourceMgr.updateGPUDetailsForVmStart(host.getId(), vm.getId(), vmTo.getGpuDevice());
                }
                return;
            }

            String errorMsg = "Unable to reboot VM " + vm + " on " + dest.getHost() + " due to " + (rebootAnswer == null ? "no reboot response" : rebootAnswer.getDetails());
            logger.info(errorMsg);
            throw new CloudRuntimeException(errorMsg);
        } catch (final OperationTimedoutException e) {
            logger.warn("Unable to send the reboot command to host {} for the vm {} due to operation timeout.", dest.getHost(), vm, e);
            throw new CloudRuntimeException("Failed to reboot the vm on host " + dest.getHost(), e);
        }
    }

    protected VirtualMachineTO getVmTO(Long vmId) {
        final VMInstanceVO vm = vmDao.findById(vmId);
        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        final List<NicVO> nics = nicsDao.listByVmId(profile.getId());
        Collections.sort(nics, new Comparator<NicVO>() {
            @Override
            public int compare(NicVO nic1, NicVO nic2) {
                Long nicId1 = Long.valueOf(nic1.getDeviceId());
                Long nicId2 = Long.valueOf(nic2.getDeviceId());
                return nicId1.compareTo(nicId2);
            }
        });

        for (final NicVO nic : nics) {
            final Network network = networkModel.getNetwork(nic.getNetworkId());
            final NicProfile nicProfile =
                    new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(), null, networkModel.isSecurityGroupSupportedInNetwork(network),
                            networkModel.getNetworkTag(profile.getHypervisorType(), network));
            profile.addNic(nicProfile);
        }
        final HypervisorGuru hvGuru = hvGuruMgr.getGuru(profile.getVirtualMachine().getHypervisorType());
        return hvGuru.implement(profile);
    }

    protected boolean getExecuteInSequence(final HypervisorType hypervisorType) {
        if (null == hypervisorType) {
            return VirtualMachineManager.ExecuteInSequence.value();
        }

        if (Set.of(HypervisorType.KVM, HypervisorType.XenServer, HypervisorType.Hyperv, HypervisorType.LXC).contains(hypervisorType)) {
            return false;
        } else if (hypervisorType.equals(HypervisorType.VMware)) {
            return StorageManager.shouldExecuteInSequenceOnVmware();
        }
        return VirtualMachineManager.ExecuteInSequence.value();
    }
}
