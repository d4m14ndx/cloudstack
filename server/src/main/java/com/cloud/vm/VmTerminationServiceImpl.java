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
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.user.vm.DestroyVMCmd;
import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.cloud.entity.api.VirtualMachineEntity;
import org.apache.cloudstack.engine.service.api.OrchestrationService;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.google.gson.reflect.TypeToken;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.CloudException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.as.AutoScaleManager;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.serializer.GsonHelper;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.dao.UserDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VmStatsDao;

@Component
public class VmTerminationServiceImpl implements VmTerminationService {

    protected Logger logger = LogManager.getLogger(getClass());
    private static final java.lang.reflect.Type JOB_PARAMS_TYPE = new TypeToken<HashMap<String, String>>() {}.getType();

    @Inject
    private UserDao userDao;
    @Inject
    private UserVmDao vmDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private AccountManager accountManager;
    @Inject
    private ResourceLimitService resourceLimitService;
    @Inject
    private ReservationDao reservationDao;
    @Inject
    private VmStatsDao vmStatsDao;
    @Inject
    private OrchestrationService orchestrationService;
    @Inject
    private AutoScaleManager autoScaleManager;
    @Inject
    private BackupManager backupManager;
    @Inject
    private org.apache.cloudstack.engine.subsystem.api.storage.VolumeService volumeService;
    @Inject
    private VmDestroyPermissionService vmDestroyPermissionService;
    @Inject
    private VmVolumeLifecycleValidationService vmVolumeLifecycleValidationService;
    @Inject
    private VmVolumeDestroyCleanupService vmVolumeDestroyCleanupService;

    @Override
    public boolean stopVirtualMachine(long userId, long vmId) {
        boolean status = false;
        UserVmVO vm = vmDao.findById(vmId);
        if (logger.isDebugEnabled()) {
            logger.debug("Stopping vm {} with id {}", vm, vmId);
        }
        if (vm == null || vm.getRemoved() != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("VM is either removed or deleted.");
            }
            return true;
        }

        userDao.findById(userId);
        try {
            VirtualMachineEntity vmEntity = orchestrationService.getVirtualMachine(vm.getUuid());
            status = vmEntity.stop(Long.toString(userId));
        } catch (ResourceUnavailableException e) {
            logger.debug("Unable to stop due to ", e);
            status = false;
        } catch (CloudException e) {
            throw new CloudRuntimeException("Unable to contact the agent to stop the Instance " + vm, e);
        }
        return status;
    }

    @Override
    public UserVm destroyVm(DestroyVMCmd cmd, ManagerOperations managerOperations)
            throws ResourceUnavailableException, ConcurrentOperationException {
        CallContext ctx = CallContext.current();
        long vmId = cmd.getId();
        boolean expunge = cmd.getExpunge();

        if (expunge) {
            String jobParamsString = ((AsyncJobVO) cmd.getJob()).getCmdInfo();
            HashMap<String, String> jobParams = GsonHelper.getGson().fromJson(jobParamsString, JOB_PARAMS_TYPE);
            String apiKey = jobParams.get("apiKey");
            vmDestroyPermissionService.checkExpungeVmPermission(ctx.getCallingAccount(), apiKey);
        }

        UserVmVO vm = vmDao.findById(vmId);

        if (vm == null || vm.getRemoved() != null) {
            throw new InvalidParameterValueException("unable to find a virtual machine with id " + vmId);
        }
        if (UserVmManager.SHAREDFSVM.equals(vm.getUserVmType())) {
            throw new InvalidParameterValueException("Operation not supported on Shared FileSystem Instance");
        }

        if (Arrays.asList(State.Destroyed, State.Expunging).contains(vm.getState()) && !expunge) {
            logger.debug("Vm {} is already destroyed", vm);
            return vm;
        }

        if (vm.isDeleteProtection()) {
            throw new InvalidParameterValueException(String.format(
                    "Instance [id = %s, name = %s] has delete protection enabled and cannot be deleted.",
                    vm.getUuid(), vm.getName()));
        }

        autoScaleManager.checkIfVmActionAllowed(vmId);
        vmDestroyPermissionService.checkPluginsIfVmCanBeDestroyed(vm);

        logger.debug("Checking if there are any ongoing Snapshots on the ROOT volumes associated with Instance {}", vm);
        if (vmVolumeLifecycleValidationService.checkStatusOfVolumeSnapshots(vm, Volume.Type.ROOT)) {
            throw new CloudRuntimeException("There is/are unbacked up Snapshot(s) on ROOT volume, Instance destroy is not permitted, please try again later.");
        }
        logger.debug("Found no ongoing Snapshots on volume of type ROOT, for the Instance {}", vm);

        List<VolumeVO> volumesToBeDeleted = getVolumesFromIds(cmd);

        vmVolumeLifecycleValidationService.checkForUnattachedVolumes(vmId, volumesToBeDeleted);
        vmVolumeLifecycleValidationService.validateVolumes(volumesToBeDeleted);

        final org.apache.cloudstack.acl.ControlledEntity[] volumesToDelete = volumesToBeDeleted.toArray(new org.apache.cloudstack.acl.ControlledEntity[0]);
        accountManager.checkAccess(ctx.getCallingAccount(), null, true, volumesToDelete);

        if (expunge) {
            backupManager.checkAndRemoveBackupOfferingBeforeExpunge(vm);
        }

        managerOperations.stopVirtualMachine(vmId, UserVmManagerImpl.VmDestroyForcestop.value());

        List<VolumeVO> dataVols = volumeDao.findByInstanceAndType(vmId, Volume.Type.DATADISK);
        vmVolumeDestroyCleanupService.detachVolumesFromVm(vm, dataVols);

        UserVm destroyedVm = managerOperations.destroyVm(vmId, expunge);
        if (expunge) {
            boolean expunged = false;
            String errorMsg = "";
            try {
                expunged = managerOperations.expunge(vm);
            } catch (RuntimeException e) {
                logger.error("Failed to expunge VM [{}] due to: {}", vm, e.getMessage(), e);
                errorMsg = e.getMessage();
            }
            if (!expunged) {
                managerOperations.transitionExpungingToError(vm.getId());
                throw new CloudRuntimeException("Failed to expunge VM " + vm.getUuid() + (StringUtils.isNotBlank(errorMsg) ? " due to: " + errorMsg : ""));
            }
        }

        autoScaleManager.removeVmFromVmGroup(vmId);

        vmVolumeDestroyCleanupService.deleteVolumesFromVm(vm, volumesToBeDeleted, expunge);

        if (managerOperations.getDestroyRootVolumeOnVmDestruction(vm.getDomainId())) {
            VolumeVO rootVolume = volumeDao.getInstanceRootVolume(vm.getId());
            if (rootVolume != null) {
                volumeService.destroyVolume(rootVolume.getId());
            } else {
                logger.warn("Tried to destroy ROOT volume for VM [{}], but couldn't retrieve it.", vm);
            }
        }

        return destroyedVm;
    }

    private List<VolumeVO> getVolumesFromIds(DestroyVMCmd cmd) {
        List<VolumeVO> volumes = new ArrayList<>();
        if (cmd.getVolumeIds() != null) {
            for (Long volId : cmd.getVolumeIds()) {
                VolumeVO vol = volumeDao.findById(volId);

                if (vol == null) {
                    throw new InvalidParameterValueException("Unable to find volume with ID: " + volId);
                }
                volumes.add(vol);
            }
        }
        return volumes;
    }

    @Override
    public UserVm stopVirtualMachine(long vmId, boolean forced) throws ConcurrentOperationException {
        Account caller = CallContext.current().getCallingAccount();
        Long userId = CallContext.current().getCallingUserId();

        if (caller != null && caller.getRemoved() != null) {
            throw new PermissionDeniedException("The account " + caller.getUuid() + " is removed");
        }

        UserVmVO vm = vmDao.findById(vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("unable to find a virtual machine with id " + vmId);
        }

        if (forced) {
            vmDestroyPermissionService.checkForceStopVmPermission(caller);
        }

        autoScaleManager.checkIfVmActionAllowed(vmId);

        boolean status;
        try {
            VirtualMachineEntity vmEntity = orchestrationService.getVirtualMachine(vm.getUuid());

            if (forced) {
                status = vmEntity.stopForced(Long.toString(userId));
            } else {
                status = vmEntity.stop(Long.toString(userId));
            }
            if (status) {
                return vmDao.findById(vmId);
            } else {
                return null;
            }
        } catch (ResourceUnavailableException e) {
            throw new CloudRuntimeException("Unable to contact the agent to stop the virtual machine " + vm, e);
        } catch (CloudException e) {
            throw new CloudRuntimeException("Unable to contact the agent to stop the virtual machine " + vm, e);
        }
    }

    @Override
    public UserVm destroyVm(long vmId, boolean expunge, ManagerOperations managerOperations)
            throws ResourceUnavailableException, ConcurrentOperationException {
        UserVmVO vm = vmDao.findById(vmId);
        if (vm == null || vm.getRemoved() != null) {
            throw new InvalidParameterValueException("Unable to find a virtual machine with specified vmId");
        }

        if (vm.getState() == State.Destroyed || vm.getState() == State.Expunging) {
            logger.trace("Vm {} is already destroyed", vm);
            return vm;
        }

        vmStatsDao.removeAllByVmId(vmId);

        boolean status;
        State vmState = vm.getState();

        Account owner = accountManager.getAccount(vm.getAccountId());

        ServiceOfferingVO offering = serviceOfferingDao.findByIdIncludingRemoved(vm.getId(), vm.getServiceOfferingId());

        try (CheckedReservation vmReservation = new CheckedReservation(owner, ResourceType.user_vm, vmId, null, -1L, reservationDao, resourceLimitService);
             CheckedReservation cpuReservation = new CheckedReservation(owner, ResourceType.cpu, vmId, null, -1 * Long.valueOf(offering.getCpu()), reservationDao, resourceLimitService);
             CheckedReservation memReservation = new CheckedReservation(owner, ResourceType.memory, vmId, null, -1 * Long.valueOf(offering.getRamSize()), reservationDao, resourceLimitService);
             CheckedReservation gpuReservation = offering.getGpuCount() != null && offering.getGpuCount() > 0 ?
                     new CheckedReservation(owner, ResourceType.gpu, vmId, null, -1 * Long.valueOf(offering.getGpuCount()), reservationDao, resourceLimitService) : null;
        ) {
            try {
                VirtualMachineEntity vmEntity = orchestrationService.getVirtualMachine(vm.getUuid());
                status = vmEntity.destroy(expunge);
            } catch (CloudException e) {
                CloudRuntimeException ex = new CloudRuntimeException("Unable to destroy with specified vmId", e);
                ex.addProxyObject(vm.getUuid(), "vmId");
                throw ex;
            }

            if (status) {
                List<VolumeVO> volumes = volumeDao.findByInstance(vmId);
                for (VolumeVO volume : volumes) {
                    if (volume.getVolumeType().equals(Volume.Type.ROOT)) {
                        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_DELETE, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(),
                                Volume.class.getName(), volume.getUuid(), volume.isDisplayVolume());
                    }
                }

                if (vmState != State.Error) {
                    VMTemplateVO template = templateDao.findByIdIncludingRemoved(vm.getTemplateId());
                    managerOperations.resourceCountDecrement(vm.getAccountId(), vm.isDisplayVm(), offering, template);
                }
                return vmDao.findById(vmId);
            } else {
                CloudRuntimeException ex = new CloudRuntimeException("Failed to destroy vm with specified vmId");
                ex.addProxyObject(vm.getUuid(), "vmId");
                throw ex;
            }
        } catch (Exception e) {
            throw new CloudRuntimeException("Failed to destroy vm with specified vmId", e);
        }
    }
}
