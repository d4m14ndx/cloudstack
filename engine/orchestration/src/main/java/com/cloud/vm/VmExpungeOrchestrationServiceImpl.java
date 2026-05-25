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
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.resource.ResourceCleanupService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.Command;
import com.cloud.deployasis.dao.UserVmDeployAsIsDetailsDao;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VmExpungeOrchestrationServiceImpl implements VmExpungeOrchestrationService {

    private static final Logger logger = LogManager.getLogger(VmExpungeOrchestrationServiceImpl.class);

    @Inject
    protected VMInstanceDao vmDao;
    @Inject
    protected UserVmDao userVmDao;
    @Inject
    protected HypervisorGuruManager hvGuruMgr;
    @Inject
    protected NetworkOrchestrationService networkMgr;
    @Inject
    protected VolumeOrchestrationService volumeMgr;
    @Inject
    protected VmExpungeCommandService vmExpungeCommandService;
    @Inject
    protected UserVmDeployAsIsDetailsDao userVmDeployAsIsDetailsDao;
    @Inject
    protected AnnotationDao annotationDao;
    @Inject
    protected ResourceCleanupService resourceCleanupService;
    @Inject
    protected VmIscsiTargetManager vmIscsiTargetManager;
    @Inject
    @Lazy
    protected VirtualMachineManager virtualMachineManager;
    @Inject
    @Lazy
    protected VmStateMachineActions vmStateMachineActions;

    @Override
    public void expunge(final String vmUuid) throws ResourceUnavailableException {
        try {
            advanceExpunge(vmUuid);
        } catch (final OperationTimedoutException e) {
            throw new CloudRuntimeException("Operation timed out", e);
        } catch (final ConcurrentOperationException e) {
            throw new CloudRuntimeException("Concurrent operation ", e);
        }
    }

    @Override
    public void advanceExpunge(final String vmUuid) throws ResourceUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        final VMInstanceVO vm = vmDao.findByUuid(vmUuid);
        advanceExpunge(vm);
    }

    @Override
    public boolean isVmDestroyed(VMInstanceVO vm) {
        if (vm == null || vm.getRemoved() != null) {
            logger.debug("Unable to find vm or vm is expunged: {}", vm);
            return true;
        }
        return false;
    }

    @Override
    public void advanceExpunge(VMInstanceVO vm) throws ResourceUnavailableException, OperationTimedoutException, ConcurrentOperationException {
        if (isVmDestroyed(vm)) {
            return;
        }

        if (HypervisorType.External.equals(vm.getHypervisorType())) {
            UserVmVO userVM = userVmDao.findById(vm.getId());
            userVmDao.loadDetails(userVM);
            userVM.setDetail(VmDetailConstants.EXPUNGE_EXTERNAL_VM, Boolean.TRUE.toString());
            userVmDao.saveDetails(userVM);
        }

        virtualMachineManager.advanceStop(vm.getUuid(), VirtualMachineManagerImpl.VmDestroyForcestop.value());
        vm = vmDao.findByUuid(vm.getUuid());

        try {
            if (!vmStateMachineActions.stateTransitTo(vm, VirtualMachine.Event.ExpungeOperation, vm.getHostId())) {
                logger.debug("Unable to expunge the vm because it is not in the correct state: {}", vm);
                throw new CloudRuntimeException("Unable to expunge " + vm);
            }
        } catch (final NoTransitionException e) {
            logger.debug("Unable to expunge the vm because it is not in the correct state: {}", vm);
            throw new CloudRuntimeException("Unable to expunge " + vm, e);
        }

        logger.debug("Expunging vm {}", vm);

        final VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        final HypervisorGuru hvGuru = hvGuruMgr.getGuru(vm.getHypervisorType());

        List<NicProfile> vmNics = profile.getNics();
        logger.debug("Cleaning up NICS [{}] of {}.", vmNics.stream().map(nic -> nic.toString()).collect(Collectors.joining(", ")), vm.toString());
        final List<Command> nicExpungeCommands = hvGuru.finalizeExpungeNics(vm, profile.getNics());
        networkMgr.cleanupNics(profile);

        logger.debug("Cleaning up hypervisor data structures (ex. SRs in XenServer) for managed storage. Data from {}.", vm.toString());

        final List<Command> volumeExpungeCommands = hvGuru.finalizeExpungeVolumes(vm);
        final Long hostId = vm.getHostId() != null ? vm.getHostId() : vm.getLastHostId();
        List<Map<String, String>> targets = getTargets(hostId, vm.getId());

        vmExpungeCommandService.sendVolumeExpungeCommands(volumeExpungeCommands, hostId, vm);

        if (hostId != null) {
            volumeMgr.revokeAccess(vm.getId(), hostId);
        }

        volumeMgr.cleanupVolumes(vm.getId());

        if (hostId != null && CollectionUtils.isNotEmpty(targets)) {
            removeDynamicTargets(hostId, targets);
        }

        final VirtualMachineGuru guru = vmStateMachineActions.getVmGuru(vm);
        guru.finalizeExpunge(vm);

        userVmDeployAsIsDetailsDao.removeDetails(vm.getId());
        annotationDao.removeByEntityType(AnnotationService.EntityType.VM.name(), vm.getUuid());

        final List<Command> finalizeExpungeCommands = hvGuru.finalizeExpunge(vm);
        vmExpungeCommandService.sendFinalizeExpungeCommands(finalizeExpungeCommands, nicExpungeCommands, vm, hostId);

        logger.debug("Expunged {}", vm);
        resourceCleanupService.purgeExpungedVmResourcesLaterIfNeeded(vm);
    }

    private List<Map<String, String>> getTargets(Long hostId, long vmId) {
        return vmIscsiTargetManager.getTargets(hostId, vmId);
    }

    private void removeDynamicTargets(long hostId, List<Map<String, String>> targets) {
        vmIscsiTargetManager.removeDynamicTargets(hostId, targets);
    }
}
