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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.vm.DeployVMCmdByAdmin;
import org.apache.cloudstack.api.command.user.vm.DeployVMCmd;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.alert.AlertManager;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.offering.DiskOffering;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.VirtualMachine.Event;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineProfile.Param;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@Component
public class VmDeployStartServiceImpl implements VmDeployStartService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private EntityManager entityManager;
    @Inject
    private HostDao hostDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private UserVmDao vmDao;
    @Inject
    private VolumeDao volsDao;
    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Inject
    private VirtualMachineManager virtualMachineManager;
    @Inject
    private AlertManager alertManager;
    @Inject
    private VolumeService volumeService;
    @Inject
    private VolumeOrchestrationService volumeOrchestrationService;

    @Override
    public void addVmUefiBootOptionsToParams(Map<Param, Object> params, String bootType, String bootMode) {
        if (logger.isTraceEnabled()) {
            logger.trace(String.format("Adding boot options (%s, %s, %s) into the param map for Instance start as UEFI detail(%s=%s) found for the Instance",
                    Param.UefiFlag.getName(),
                    Param.BootType.getName(),
                    Param.BootMode.getName(),
                    bootType,
                    bootMode));
        }
        params.put(Param.UefiFlag, "Yes");
        params.put(Param.BootType, bootType);
        params.put(Param.BootMode, bootMode);
    }

    @Override
    public UserVm startVirtualMachine(DeployVMCmd cmd, ManagerOperations managerOperations)
            throws ResourceUnavailableException, InsufficientCapacityException,
            ConcurrentOperationException, ResourceAllocationException {
        long vmId = cmd.getEntityId();
        if (!cmd.getStartVm()) {
            return managerOperations.getUserVm(vmId);
        }
        Long podId = null;
        Long clusterId = null;
        Long hostId = cmd.getHostId();
        Map<Param, Object> additionalParams = new HashMap<>();
        Map<Long, DiskOffering> diskOfferingMap = cmd.getDataDiskTemplateToDiskOfferingMap();
        if (cmd instanceof DeployVMCmdByAdmin) {
            DeployVMCmdByAdmin adminCmd = (DeployVMCmdByAdmin)cmd;
            podId = adminCmd.getPodId();
            clusterId = adminCmd.getClusterId();
        }
        VMInstanceDetailVO uefiDetail = vmInstanceDetailsDao.findDetail(cmd.getEntityId(), ApiConstants.BootType.UEFI.toString());
        if (uefiDetail != null) {
            addVmUefiBootOptionsToParams(additionalParams, uefiDetail.getName(), uefiDetail.getValue());
        }
        if (cmd.getBootIntoSetup() != null) {
            additionalParams.put(Param.BootIntoSetup, cmd.getBootIntoSetup());
        }

        if (StringUtils.isNotBlank(cmd.getPassword())) {
            additionalParams.put(Param.VmPassword, cmd.getPassword());
        }

        return startVirtualMachine(vmId, podId, clusterId, hostId, diskOfferingMap, additionalParams,
                cmd.getDeploymentPlanner(), managerOperations);
    }

    @Override
    public UserVm startVirtualMachine(long vmId, Long podId, Long clusterId, Long hostId,
            Map<Long, DiskOffering> diskOfferingMap, Map<Param, Object> additionalParams,
            String deploymentPlannerToUse, ManagerOperations managerOperations)
            throws ResourceUnavailableException, InsufficientCapacityException,
            ConcurrentOperationException, ResourceAllocationException {
        UserVmVO vm = vmDao.findById(vmId);
        Pair<UserVmVO, Map<Param, Object>> vmParamPair = null;

        try {
            vmParamPair = managerOperations.startVirtualMachine(vmId, podId, clusterId, hostId, additionalParams, deploymentPlannerToUse);
            vm = vmParamPair.first();

            UserVmVO tmpVm = vmDao.findById(vm.getId());
            if (!tmpVm.getState().equals(State.Running)) {
                logger.error("VM " + tmpVm + " unexpectedly went to " + tmpVm.getState() + " state");
                throw new ConcurrentOperationException("Failed to deploy VM " + vm);
            }

            resizeDataDisksIfNeeded(diskOfferingMap, tmpVm, vm);
        } finally {
            updateVmStateForFailedVmCreation(vm.getId(), hostId, managerOperations);
        }

        VMTemplateVO template = templateDao.findByIdIncludingRemoved(vm.getTemplateId());
        if (template.isEnablePassword()) {
            vm.setPassword((String)vmParamPair.second().get(Param.VmPassword));
        }

        return vm;
    }

    private void resizeDataDisksIfNeeded(Map<Long, DiskOffering> diskOfferingMap, UserVmVO tmpVm, UserVmVO vm) {
        try {
            if (!diskOfferingMap.isEmpty()) {
                List<VolumeVO> vols = volsDao.findByInstance(tmpVm.getId());
                for (VolumeVO vol : vols) {
                    if (vol.getVolumeType() == Volume.Type.DATADISK) {
                        DiskOffering doff = entityManager.findById(DiskOffering.class, vol.getDiskOfferingId());
                        volumeService.resizeVolumeOnHypervisor(vol.getId(), doff.getDiskSize(), tmpVm.getHostId(), vm.getInstanceName());
                    }
                }
            }
        } catch (Exception e) {
            logger.fatal("Unable to resize the data disk for vm {} due to {}", vm, e.getMessage(), e);
        }
    }

    @Override
    public void updateVmStateForFailedVmCreation(Long vmId, Long hostId, ManagerOperations managerOperations) {
        UserVmVO vm = vmDao.findById(vmId);

        if (vm != null && vm.getState().equals(State.Stopped)) {
            HostVO host = hostDao.findById(hostId);
            logger.debug("Destroying vm {} as it failed to create on Host: {} with id {}", vm, host, hostId);
            try {
                virtualMachineManager.stateTransitTo(vm, Event.OperationFailedToError, null);
            } catch (NoTransitionException e1) {
                logger.warn(e1.getMessage());
            }

            List<VolumeVO> volumesForThisVm = volsDao.findUsableVolumesForInstance(vm.getId());
            for (VolumeVO volume : volumesForThisVm) {
                if (volume.getState() != Volume.State.Destroy) {
                    volumeOrchestrationService.destroyVolume(volume);
                }
            }
            String msg = String.format("Failed to deploy Vm %s, on Host %s with Id: %d", vm, host, hostId);
            alertManager.sendAlert(AlertManager.AlertType.ALERT_TYPE_USERVM, vm.getDataCenterId(), vm.getPodIdToDeployIn(), msg, msg);

            ServiceOfferingVO offering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
            VMTemplateVO template = templateDao.findByIdIncludingRemoved(vm.getTemplateId());
            managerOperations.resourceCountDecrement(vm.getAccountId(), vm.isDisplayVm(), offering, template);
        }
    }
}
