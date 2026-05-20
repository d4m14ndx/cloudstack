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

import static com.cloud.vm.VirtualMachineManager.ResourceCountRunningVMsonly;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.Outcome;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.StopAnswer;
import com.cloud.agent.api.StopCommand;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.GPUDeviceTO;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.api.ApiDBUtils;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.ResourceState;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.ItWorkVO.Step;
import com.cloud.vm.VirtualMachine.Event;
import com.cloud.vm.VirtualMachine.PowerState;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VmStopOrchestrationServiceImpl implements VmStopOrchestrationService {

    private static final Logger logger = LogManager.getLogger(VmStopOrchestrationServiceImpl.class);

    @Inject
    protected AgentManager agentMgr;
    @Inject
    protected VMInstanceDao vmDao;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected ItWorkDao workDao;
    @Inject
    protected UserVmDao userVmDao;
    @Inject
    protected VolumeDao volsDao;
    @Inject
    protected PrimaryDataStoreDao storagePoolDao;
    @Inject
    protected ServiceOfferingDao offeringDao;
    @Inject
    protected VMTemplateDao templateDao;
    @Inject
    protected ResourceManager resourceMgr;
    @Inject
    protected ResourceLimitService resourceLimitMgr;
    @Inject
    protected VolumeOrchestrationService volumeMgr;
    @Inject
    protected NetworkOrchestrationService networkMgr;
    @Inject
    protected VmWorkJobQueueService vmWorkJobQueueService;
    @Inject
    protected VmStopCommandService vmStopCommandService;
    @Inject
    @Lazy
    protected VirtualMachineManagerImpl virtualMachineManager;

    private List<Map<String, String>> getVolumesToDisconnect(VirtualMachine vm) {
        List<Map<String, String>> volumesToDisconnect = new ArrayList<>();

        List<VolumeVO> volumes = volsDao.findByInstance(vm.getId());

        if (CollectionUtils.isEmpty(volumes)) {
            return volumesToDisconnect;
        }

        for (VolumeVO volume : volumes) {
            StoragePoolVO storagePool = storagePoolDao.findById(volume.getPoolId());

            if (storagePool != null && storagePool.isManaged()) {
                Map<String, String> info = new HashMap<>();

                info.put(DiskTO.STORAGE_HOST, storagePool.getHostAddress());
                info.put(DiskTO.STORAGE_PORT, String.valueOf(storagePool.getPort()));
                info.put(DiskTO.IQN, volume.get_iScsiName());
                info.put(DiskTO.PROTOCOL_TYPE, (volume.getPoolType() != null) ? volume.getPoolType().toString() : null);

                volumesToDisconnect.add(info);
            }
        }

        return volumesToDisconnect;
    }

    @Override
    public boolean sendStop(final VirtualMachineGuru guru, final VirtualMachineProfile profile, final boolean force, final boolean checkBeforeCleanup) {
        final VirtualMachine vm = profile.getVirtualMachine();
        StopCommand stpCmd = new StopCommand(vm, virtualMachineManager.getExecuteInSequence(vm.getHypervisorType()), checkBeforeCleanup);
        virtualMachineManager.updateStopCommandForExternalHypervisorType(vm.getHypervisorType(), profile, stpCmd);
        vmStopCommandService.decorateStopCommandWithNetworkDetails(stpCmd, vm);
        stpCmd.setVolumesToDisconnect(getVolumesToDisconnect(vm));
        final StopCommand stop = stpCmd;
        try {
            Answer answer = null;
            if(vm.getHostId() != null) {
                answer = agentMgr.send(vm.getHostId(), stop);
            }
            if (answer != null && answer instanceof StopAnswer) {
                final StopAnswer stopAns = (StopAnswer)answer;
                if (vm.getType() == VirtualMachine.Type.User) {
                    final String platform = stopAns.getPlatform();
                    if (platform != null) {
                        final UserVmVO userVm = userVmDao.findById(vm.getId());
                        userVmDao.loadDetails(userVm);
                        userVm.setDetail(VmDetailConstants.PLATFORM, platform);
                        userVmDao.saveDetails(userVm);
                    }
                }

                final GPUDeviceTO gpuDevice = stop.getGpuDevice();
                resourceMgr.updateGPUDetailsForVmStop(vm, gpuDevice);
                if (!answer.getResult()) {
                    final String details = answer.getDetails();
                    logger.debug("Unable to stop VM due to {}", details);
                    return false;
                }

                guru.finalizeStop(profile, answer);

                final UserVmVO userVm = userVmDao.findById(vm.getId());
                if (vm.getType() == VirtualMachine.Type.User) {
                    if (userVm != null) {
                        userVm.setPowerState(PowerState.PowerOff);
                        userVmDao.update(userVm.getId(), userVm);
                    }
                }
            } else {
                logger.error("Invalid answer received in response to a StopCommand for {}", vm.getInstanceName());
                return false;
            }

        } catch (final AgentUnavailableException | OperationTimedoutException e) {
            logger.warn("Unable to stop {} due to [{}].", vm.toString(), e.getMessage(), e);
            if (!force) {
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean cleanup(final VirtualMachineGuru guru, final VirtualMachineProfile profile, final ItWorkVO work, final Event event, final boolean cleanUpEvenIfUnableToStop) {
        final VirtualMachine vm = profile.getVirtualMachine();
        final State state = vm.getState();
        logger.debug("Cleaning up resources for the vm {} in {} state", vm, state);
        try {
            if (state == State.Starting) {
                if (work != null) {
                    final Step step = work.getStep();
                    if (step == Step.Starting && !cleanUpEvenIfUnableToStop) {
                        logger.warn("Unable to cleanup vm {}; work state is incorrect: {}", vm, step);
                        return false;
                    }

                    if (step == Step.Started || step == Step.Starting || step == Step.Release) {
                        if (vm.getHostId() != null) {
                            if (!virtualMachineManager.sendStop(guru, profile, cleanUpEvenIfUnableToStop, false)) {
                                logger.warn("Failed to stop vm {} in {} state as a part of cleanup process", vm, State.Starting);
                                return false;
                            }
                        }
                    }

                    if (step != Step.Release && step != Step.Prepare && step != Step.Started && step != Step.Starting) {
                        logger.debug("Cleanup is not needed for vm {}; work state is incorrect: {}", vm, step);
                        return true;
                    }
                } else {
                    if (vm.getHostId() != null) {
                        if (!virtualMachineManager.sendStop(guru, profile, cleanUpEvenIfUnableToStop, false)) {
                            logger.warn("Failed to stop vm {} in {} state as a part of cleanup process", vm, State.Starting);
                            return false;
                        }
                    }
                }

            } else if (state == State.Stopping) {
                if (vm.getHostId() != null) {
                    if (!virtualMachineManager.sendStop(guru, profile, cleanUpEvenIfUnableToStop, false)) {
                        logger.warn("Failed to stop vm {} in {} state as a part of cleanup process", vm, State.Stopping);
                        return false;
                    }
                }
            } else if (state == State.Migrating) {
                if (vm.getHostId() != null || vm.getLastHostId() != null) {
                    if (!virtualMachineManager.sendStop(guru, profile, cleanUpEvenIfUnableToStop, false)) {
                        logger.warn("Failed to stop vm {} in {} state as a part of cleanup process", vm, State.Migrating);
                        return false;
                    }
                }
            } else if (state == State.Running) {
                if (!virtualMachineManager.sendStop(guru, profile, cleanUpEvenIfUnableToStop, false)) {
                    logger.warn("Failed to stop vm {} in {} state as a part of cleanup process", vm, State.Running);
                    return false;
                }
            }
        } finally {
            releaseVmResources(profile, cleanUpEvenIfUnableToStop);
        }

        return true;
    }

    @Override
    public void releaseVmResources(final VirtualMachineProfile profile, final boolean forced) {
        final VirtualMachine vm = profile.getVirtualMachine();
        final State state = vm.getState();
        try {
            networkMgr.release(profile, forced);
            logger.debug("Successfully released network resources for the VM {} in {} state", vm, state);
        } catch (final Exception e) {
            logger.warn("Unable to release some network resources for the VM {} in {} state", vm, state, e);
        }

        try {
            if (vm.getHypervisorType() != HypervisorType.BareMetal && vm.getHypervisorType() != HypervisorType.External) {
                volumeMgr.release(profile);
                logger.debug("Successfully released storage resources for the VM {} in {} state", vm, state);
            }
        } catch (final Exception e) {
            logger.warn("Unable to release storage resources for the VM {} in {} state", vm, state, e);
        }

        logger.debug("Successfully cleaned up resources for the VM {} in {} state", vm, state);
    }

    @Override
    public void advanceStop(final String vmUuid, final boolean cleanUpEvenIfUnableToStop)
            throws AgentUnavailableException, OperationTimedoutException, ConcurrentOperationException {

        final AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {

            VmWorkJobVO placeHolder = null;
            final VirtualMachine vm = vmDao.findByUuid(vmUuid);
            placeHolder = vmWorkJobQueueService.createPlaceHolderWork(vm.getId());
            try {
                orchestrateStop(vmUuid, cleanUpEvenIfUnableToStop);
            } finally {
                vmWorkJobQueueService.expungePlaceHolderWork(placeHolder);
            }

        } else {
            final Outcome<VirtualMachine> outcome = vmWorkJobQueueService.stopVmThroughJobQueue(vmUuid, cleanUpEvenIfUnableToStop);

            vmWorkJobQueueService.retrieveVmFromJobOutcome(outcome, vmUuid, "stopVm");

            try {
                vmWorkJobQueueService.retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
            } catch (ResourceUnavailableException | InsufficientCapacityException ex) {
                throw new RuntimeException("Unexpected exception", ex);
            }
        }
    }

    @Override
    public void orchestrateStop(final String vmUuid, final boolean cleanUpEvenIfUnableToStop) throws AgentUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        final VMInstanceVO vm = vmDao.findByUuid(vmUuid);

        advanceStop(vm, cleanUpEvenIfUnableToStop);
    }

    private void advanceStop(final VMInstanceVO vm, final boolean cleanUpEvenIfUnableToStop) throws AgentUnavailableException, OperationTimedoutException,
    ConcurrentOperationException {
        final State state = vm.getState();
        if (state == State.Stopped) {
            logger.debug("VM is already stopped: {}", vm);
            return;
        }

        if (state == State.Destroyed || state == State.Expunging || state == State.Error) {
            logger.debug("Stopped called on {} but the state is {}", vm, state);
            return;
        }

        final ItWorkVO work = workDao.findByOutstandingWork(vm.getId(), vm.getState());
        if (work != null) {
            logger.debug("Found an outstanding work item for this vm {} with state: {}, work id: {}", vm, vm.getState(), work.getId());
        }
        final Long hostId = vm.getHostId();
        if (hostId == null) {
            if (!cleanUpEvenIfUnableToStop) {
                logger.debug("HostId is null but this is not a forced stop, cannot stop vm {} with state: {}", vm, vm.getState());
                throw new CloudRuntimeException("Unable to stop " + vm);
            }
            try {
                virtualMachineManager.stateTransitTo(vm, Event.AgentReportStopped, null, null);
            } catch (final NoTransitionException e) {
                logger.warn(e.getMessage());
            }

            if (work != null) {
                logger.debug("Updating work item to Done, id: {}", work.getId());
                work.setStep(Step.Done);
                workDao.update(work.getId(), work);
            }
            return;
        } else {
            HostVO host = hostDao.findById(hostId);
            if (!cleanUpEvenIfUnableToStop && vm.getState() == State.Running && host.getResourceState() == ResourceState.PrepareForMaintenance) {
                logger.debug("Host is in PrepareForMaintenance state - Stop VM operation on the VM: {} is not allowed", vm);
                throw new CloudRuntimeException(String.format("Stop VM operation on the VM %s is not allowed as host is preparing for maintenance mode", vm));
            }
        }

        final VirtualMachineGuru vmGuru = virtualMachineManager.getVmGuru(vm);
        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);

        try {
            if (!virtualMachineManager.stateTransitTo(vm, Event.StopRequested, vm.getHostId())) {
                throw new ConcurrentOperationException(String.format("%s is being operated on.", vm.toString()));
            }
        } catch (final NoTransitionException e1) {
            if (!cleanUpEvenIfUnableToStop) {
                throw new CloudRuntimeException("We cannot stop " + vm + " when it is in state " + vm.getState());
            }
            final boolean doCleanup = true;
            logger.warn("Unable to transition the state but we're moving on because it's forced stop", e1);

            if (doCleanup) {
                if (virtualMachineManager.cleanup(vmGuru, new VirtualMachineProfileImpl(vm), work, Event.StopRequested, cleanUpEvenIfUnableToStop)) {
                    try {
                        if (work != null) {
                            logger.debug("Updating work item to Done, id: {}", work.getId());
                        }
                        if (!virtualMachineManager.changeState(vm, Event.AgentReportStopped, null, work, Step.Done)) {
                            throw new CloudRuntimeException("Unable to stop " + vm);
                        }

                    } catch (final NoTransitionException e) {
                        logger.warn("Unable to cleanup {}", vm);
                        throw new CloudRuntimeException("Unable to stop " + vm, e);
                    }
                } else {
                    logger.debug("Failed to cleanup VM: {}", vm);
                    throw new CloudRuntimeException("Failed to cleanup " + vm + " , current state " + vm.getState());
                }
            }
        }

        if (vm.getState() != State.Stopping) {
            throw new CloudRuntimeException("We cannot proceed with stop VM " + vm + " since it is not in 'Stopping' state, current state: " + vm.getState());
        }

        vmGuru.prepareStop(profile);

        final StopCommand stop = new StopCommand(vm, virtualMachineManager.getExecuteInSequence(vm.getHypervisorType()), false, cleanUpEvenIfUnableToStop);
        virtualMachineManager.updateStopCommandForExternalHypervisorType(vm.getHypervisorType(), profile, stop);
        vmStopCommandService.decorateStopCommandWithNetworkDetails(stop, vm);

        boolean stopped = false;
        Answer answer = null;
        try {
            answer = agentMgr.send(vm.getHostId(), stop);
            if (answer != null) {
                if (answer instanceof StopAnswer) {
                    final StopAnswer stopAns = (StopAnswer)answer;
                    if (vm.getType() == VirtualMachine.Type.User) {
                        final String platform = stopAns.getPlatform();
                        if (platform != null) {
                            final UserVmVO userVm = userVmDao.findById(vm.getId());
                            userVmDao.loadDetails(userVm);
                            userVm.setDetail(VmDetailConstants.PLATFORM, platform);
                            userVmDao.saveDetails(userVm);
                        }
                    }
                }
                stopped = answer.getResult();
                if (!stopped) {
                    throw new CloudRuntimeException("Unable to stop the Instance due to " + answer.getDetails());
                }
                vmGuru.finalizeStop(profile, answer);
                final GPUDeviceTO gpuDevice = stop.getGpuDevice();
                resourceMgr.updateGPUDetailsForVmStop(vm, gpuDevice);
            } else {
                throw new CloudRuntimeException("Invalid answer received in response to a StopCommand on " + vm.instanceName);
            }

        } catch (AgentUnavailableException | OperationTimedoutException e) {
            logger.warn("Unable to stop {} due to [{}].", profile.toString(), e.toString(), e);
        } finally {
            if (!stopped) {
                if (!cleanUpEvenIfUnableToStop) {
                    logger.warn("Unable to stop vm {}", vm);
                    try {
                        virtualMachineManager.stateTransitTo(vm, Event.OperationFailed, vm.getHostId());
                    } catch (final NoTransitionException e) {
                        logger.warn("Unable to transition the state " + vm, e);
                    }
                    throw new CloudRuntimeException("Unable to stop " + vm);
                } else {
                    logger.warn("Unable to actually stop {} but continue with release because it's a force stop", vm);
                    vmGuru.finalizeStop(profile, answer);
                    if (HypervisorType.External.equals(profile.getHypervisorType())) {
                        try {
                            virtualMachineManager.stateTransitTo(vm, VirtualMachine.Event.OperationSucceeded, null);
                        } catch (final NoTransitionException e) {
                            logger.warn("Unable to transition the state " + vm, e);
                        }
                    }

                }
            } else {
                if (VirtualMachine.systemVMs.contains(vm.getType())) {
                    HostVO systemVmHost = ApiDBUtils.findHostByTypeNameAndZoneId(vm.getDataCenterId(), vm.getHostName(),
                            VirtualMachine.Type.SecondaryStorageVm.equals(vm.getType()) ? Host.Type.SecondaryStorageVM : Host.Type.ConsoleProxy);
                    if (systemVmHost != null) {
                        agentMgr.agentStatusTransitTo(systemVmHost, Status.Event.ShutdownRequested, virtualMachineManager.getNodeId());
                    }
                }
            }
        }

        logger.debug("{} is stopped on the host.  Proceeding to release resource held.", vm);

        releaseVmResources(profile, cleanUpEvenIfUnableToStop);

        try {
            if (work != null) {
                logger.debug("Updating the outstanding work item to Done, id: {}", work.getId());
                work.setStep(Step.Done);
                workDao.update(work.getId(), work);
            }

            boolean result = Transaction.execute(new TransactionCallbackWithException<Boolean, NoTransitionException>() {
                @Override
                public Boolean doInTransaction(TransactionStatus status) throws NoTransitionException {
                    boolean result = virtualMachineManager.stateTransitTo(vm, Event.OperationSucceeded, null);

                    if (result && VirtualMachine.Type.User.equals(vm.type) && ResourceCountRunningVMsonly.value()) {
                        ServiceOfferingVO offering = offeringDao.findById(vm.getId(), vm.getServiceOfferingId());
                        VMTemplateVO template = templateDao.findByIdIncludingRemoved(vm.getTemplateId());
                        resourceLimitMgr.decrementVmResourceCount(vm.getAccountId(), vm.isDisplay(), offering, template);
                    }
                    return result;
                }
            });

            if (!result) {
                throw new CloudRuntimeException("unable to stop " + vm);
            }
        } catch (final NoTransitionException e) {
            String message = String.format("Unable to stop %s due to [%s].", vm.toString(), e.getMessage());
            logger.warn(message, e);
            throw new CloudRuntimeException(message, e);
        }
    }

}
