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

import java.util.Date;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.affinity.AffinityGroupVMMapVO;
import org.apache.cloudstack.affinity.dao.AffinityGroupVMMapDao;
import org.apache.cloudstack.vm.UnmanagedVMsManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.UnsupportedServiceException;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@Component
public class VmUnmanageServiceImpl implements VmUnmanageService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected UserVmDao _vmDao;
    @Inject
    protected VolumeDao _volsDao;
    @Inject
    protected VMInstanceDao _vmInstanceDao;
    @Inject
    protected VMInstanceDetailsDao vmInstanceDetailsDao;
    @Inject
    protected AffinityGroupVMMapDao _affinityGroupVMMapDao;
    @Inject
    protected ServiceOfferingDao serviceOfferingDao;
    @Inject
    protected VMTemplateDao _templateDao;
    @Inject
    protected DiskOfferingDao _diskOfferingDao;
    @Inject
    protected ResourceLimitService _resourceLimitMgr;
    @Inject
    protected VirtualMachineManager _itMgr;
    @Inject
    protected VmVolumeLifecycleValidationService vmVolumeLifecycleValidationService;

    @Override
    public Pair<Boolean, String> unmanageUserVM(Long vmId, Long paramHostId, ManagerOperations managerOperations) {
        UserVmVO vm = _vmDao.findById(vmId);
        if (vm == null || vm.getRemoved() != null) {
            throw new InvalidParameterValueException("Unable to find a VM with ID = " + vmId);
        }

        vm = _vmDao.acquireInLockTable(vm.getId());

        try {
            if (vm.getState() != State.Running && vm.getState() != State.Stopped) {
                String errorMsg = "Instance: " + vm.getName() + " is not running or stopped, cannot be unmanaged";
                logger.debug(errorMsg);
                throw new CloudRuntimeException(errorMsg);
            }

            if (!UnmanagedVMsManager.isSupported(vm.getHypervisorType())) {
                throw new UnsupportedServiceException("Unmanaging a VM is currently not supported on hypervisor " +
                        vm.getHypervisorType().toString());
            }

            List<VolumeVO> volumes = _volsDao.findByInstance(vm.getId());
            vmVolumeLifecycleValidationService.checkUnmanagingVMOngoingVolumeSnapshots(vm);
            vmVolumeLifecycleValidationService.checkUnmanagingVMVolumes(vm, volumes);

            Pair<Boolean, String> result = _itMgr.unmanage(vm.getUuid(), paramHostId);
            if (result.first()) {
                cleanupUnmanageVMResources(vm, managerOperations);
                unmanageVMFromDB(vm.getId());
                publishUnmanageVMUsageEvents(vm, volumes, managerOperations);
            } else {
                throw new CloudRuntimeException("Error while unmanaging VM: " + vm.getUuid());
            }
            return result;
        } catch (Exception e) {
            logger.error("Could not unmanage VM {}", vm, e);
            throw new CloudRuntimeException(e);
        } finally {
            _vmDao.releaseFromLockTable(vm.getId());
        }
    }

    void publishUnmanageVMUsageEvents(UserVmVO vm, List<VolumeVO> volumes, ManagerOperations managerOperations) {
        postProcessingUnmanageVMVolumes(volumes, vm);
        postProcessingUnmanageVM(vm, managerOperations);
    }

    void cleanupUnmanageVMResources(UserVmVO vm, ManagerOperations managerOperations) {
        managerOperations.cleanupVmResources(vm);
        removeVMFromAffinityGroups(vm.getId());
    }

    void unmanageVMFromDB(long vmId) {
        VMInstanceVO vm = _vmInstanceDao.findById(vmId);
        vmInstanceDetailsDao.removeDetails(vmId);
        vm.setState(State.Expunging);
        vm.setRemoved(new Date());
        _vmInstanceDao.update(vm.getId(), vm);
    }

    private void removeVMFromAffinityGroups(long vmId) {
        List<AffinityGroupVMMapVO> affinityGroups = _affinityGroupVMMapDao.listByInstanceId(vmId);
        if (affinityGroups.size() > 0) {
            logger.debug("Cleaning up VM from affinity groups after unmanaging");
            for (AffinityGroupVMMapVO map : affinityGroups) {
                _affinityGroupVMMapDao.expunge(map.getId());
            }
        }
    }

    void postProcessingUnmanageVM(UserVmVO vm, ManagerOperations managerOperations) {
        ServiceOfferingVO offering = serviceOfferingDao.findById(vm.getServiceOfferingId());
        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vm.getTemplateId());
        // First generate a VM stop event if the VM was not stopped already
        boolean resourceNotDecremented = true;
        if (vm.getState() != State.Stopped) {
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VM_STOP, vm.getAccountId(), vm.getDataCenterId(),
                    vm.getId(), vm.getHostName(), vm.getServiceOfferingId(), vm.getTemplateId(),
                    vm.getHypervisorType().toString(), VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplayVm());

            managerOperations.resourceCountDecrement(vm.getAccountId(), vm.isDisplayVm(), offering, template);
            resourceNotDecremented = false;
        }
        // VM destroy usage event
        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VM_DESTROY, vm.getAccountId(), vm.getDataCenterId(),
                vm.getId(), vm.getHostName(), vm.getServiceOfferingId(), vm.getTemplateId(),
                vm.getHypervisorType().toString(), VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplayVm());
        if (resourceNotDecremented) {
            managerOperations.resourceCountDecrement(vm.getAccountId(), vm.isDisplayVm(), offering, template);
        }
    }

    void postProcessingUnmanageVMVolumes(List<VolumeVO> volumes, UserVmVO vm) {
        for (VolumeVO volume : volumes) {
            if (volume.getVolumeType() == Volume.Type.ROOT) {
                UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_DELETE, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(),
                        Volume.class.getName(), volume.getUuid(), volume.isDisplayVolume());
            }
            _resourceLimitMgr.decrementVolumeResourceCount(vm.getAccountId(), volume.isDisplayVolume(),
                    volume.getSize(), _diskOfferingDao.findByIdIncludingRemoved(volume.getDiskOfferingId()));
        }
    }
}
