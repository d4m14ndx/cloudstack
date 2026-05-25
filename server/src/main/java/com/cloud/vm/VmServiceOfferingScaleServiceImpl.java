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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.user.vm.ScaleVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpgradeVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.resourcelimit.Reserver;
import org.apache.cloudstack.utils.bytescale.ByteScaleUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.ApiDBUtils;
import com.cloud.capacity.Capacity;
import com.cloud.capacity.CapacityManager;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.ServiceOffering;
import com.cloud.resourcelimit.ReservationHelper;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.dao.AccountDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VmServiceOfferingScaleServiceImpl implements VmServiceOfferingScaleService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private AccountDao accountDao;
    @Inject
    private DataCenterDao dcDao;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private UserVmDao vmDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private AccountManager accountManager;
    @Inject
    private CapacityManager capacityManager;
    @Inject
    private VirtualMachineManager itMgr;
    @Inject
    private ResourceLimitService resourceLimitService;
    @Inject
    private EntityManager entityMgr;
    @Inject
    private ServiceOfferingValidator serviceOfferingValidator;
    @Inject
    private VmUpdateValidator vmUpdateValidator;
    @Inject
    private VmRootDiskOfferingChangeService vmRootDiskOfferingChangeService;
    @Inject
    private VmMigrationValidator vmMigrationValidator;
    @Inject
    private VmUsageEventPublisher vmUsageEventPublisher;

    private int scaleRetry = 2;

    public void setScaleRetry(int scaleRetry) {
        this.scaleRetry = scaleRetry;
    }

    @Override
    public UserVm upgradeVirtualMachine(UpgradeVMCmd cmd) throws ResourceAllocationException {
        Long vmId = cmd.getId();
        Long svcOffId = cmd.getServiceOfferingId();
        Account caller = CallContext.current().getCallingAccount();

        VMInstanceVO vmInstance = vmInstanceDao.findById(vmId);
        if (vmInstance == null) {
            throw new InvalidParameterValueException("unable to find an Instance with id " + vmId);
        } else if (!(vmInstance.getState().equals(State.Stopped))) {
            throw new InvalidParameterValueException("Unable to upgrade Instance " + vmInstance.toString() + " " + " in state " + vmInstance.getState()
            + "; make sure the Instance is stopped");
        }

        accountManager.checkAccess(caller, null, true, vmInstance);

        upgradeStoppedVirtualMachine(vmId, svcOffId, cmd.getDetails());

        UserVmVO userVm = vmDao.findById(vmId);
        vmUsageEventPublisher.generateUsageEvent(userVm, userVm.isDisplayVm(), EventTypes.EVENT_VM_UPGRADE);

        return userVm;
    }

    private UserVm upgradeStoppedVirtualMachine(Long vmId, Long svcOffId, Map<String, String> customParameters) throws ResourceAllocationException {

        VMInstanceVO vmInstance = vmInstanceDao.findById(vmId);
        ServiceOfferingVO newServiceOffering = serviceOfferingDao.findById(svcOffId);
        if (newServiceOffering.getState() == ServiceOffering.State.Inactive) {
            throw new InvalidParameterValueException(String.format("Unable to upgrade Instance %s with an inactive service offering %s", vmInstance.getUuid(), newServiceOffering.getUuid()));
        }
        if (newServiceOffering.isDynamic()) {
            newServiceOffering.setDynamicFlag(true);
            serviceOfferingValidator.validateCustomParameters(newServiceOffering, customParameters);
            newServiceOffering = serviceOfferingDao.getComputeOffering(newServiceOffering, customParameters);
        } else {
            serviceOfferingValidator.validateOfferingMaxResource(newServiceOffering);
        }
        ServiceOfferingVO currentServiceOffering = serviceOfferingDao.findByIdIncludingRemoved(vmInstance.getId(), vmInstance.getServiceOfferingId());

        serviceOfferingValidator.validateDiskOfferingChecks(currentServiceOffering, newServiceOffering);

        int newCpu = newServiceOffering.getCpu();
        int newMemory = newServiceOffering.getRamSize();
        int currentCpu = currentServiceOffering.getCpu();
        int currentMemory = currentServiceOffering.getRamSize();

        Account owner = accountManager.getActiveAccountById(vmInstance.getAccountId());
        VMTemplateVO template = templateDao.findByIdIncludingRemoved(vmInstance.getTemplateId());

        List<Reserver> reservations = new ArrayList<>();
        try {
            if (!VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
                resourceLimitService.checkVmResourceLimitsForServiceOfferingChange(owner, vmInstance.isDisplay(), (long) currentCpu, (long) newCpu,
                        (long) currentMemory, (long) newMemory, currentServiceOffering, newServiceOffering, template, reservations);
            }

            itMgr.checkIfCanUpgrade(vmInstance, newServiceOffering);

            accountManager.checkAccess(owner, newServiceOffering, dcDao.findById(vmInstance.getDataCenterId()));

            DiskOfferingVO newDiskOffering = diskOfferingDao.findById(newServiceOffering.getDiskOfferingId());
            vmRootDiskOfferingChangeService.changeDiskOfferingForRootVolume(vmId, newDiskOffering, customParameters, vmInstance.getDataCenterId());

            itMgr.upgradeVmDb(vmId, newServiceOffering, currentServiceOffering);

            if (!VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
                resourceLimitService.updateVmResourceCountForServiceOfferingChange(owner.getAccountId(), vmInstance.isDisplay(), (long) currentCpu, (long) newCpu,
                        (long) currentMemory, (long) newMemory, currentServiceOffering, newServiceOffering, template);
            }

            return vmDao.findById(vmInstance.getId());

        } finally {
            ReservationHelper.closeAll(reservations);
        }
    }

    @Override
    public UserVm upgradeVirtualMachine(ScaleVMCmd cmd) throws ResourceUnavailableException, ConcurrentOperationException, ManagementServerException,
    VirtualMachineMigrationException {

        Long vmId = cmd.getId();
        Long newServiceOfferingId = cmd.getServiceOfferingId();
        VirtualMachine vm = entityMgr.findById(VirtualMachine.class, vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("Unable to find VM's UUID");
        }
        if (Hypervisor.HypervisorType.External.equals(vm.getHypervisorType())) {
            logger.error("Scale VM not supported for {} as it is {} hypervisor instance",
                    vm, Hypervisor.HypervisorType.External.name());
            throw new InvalidParameterValueException(String.format("Operation not supported for instance: %s",
                    vm.getName()));
        }
        CallContext.current().setEventDetails("Vm Id: " + vm.getUuid());

        Map<String, String> cmdDetails = cmd.getDetails();

        vmUpdateValidator.updateInstanceDetailsMapWithCurrentValuesForAbsentDetails(cmdDetails, vm, newServiceOfferingId);

        boolean result = upgradeVirtualMachine(vmId, newServiceOfferingId, cmdDetails);
        if (result) {
            UserVmVO vmInstance = vmDao.findById(vmId);
            if (vmInstance.getState().equals(State.Stopped)) {
                vmUsageEventPublisher.generateUsageEvent(vmInstance, vmInstance.isDisplayVm(), EventTypes.EVENT_VM_UPGRADE);
            }
            return vmInstance;
        } else {
            throw new CloudRuntimeException("Failed to scale the VM");
        }
    }

    @Override
    public boolean upgradeVirtualMachine(Long vmId, Long newServiceOfferingId, Map<String, String> customParameters) throws ResourceUnavailableException,
    ConcurrentOperationException, ManagementServerException, VirtualMachineMigrationException {

        VMInstanceVO vmInstance = vmInstanceDao.findById(vmId);

        Account caller = CallContext.current().getCallingAccount();
        accountManager.checkAccess(caller, null, true, vmInstance);
        if (vmInstance == null) {
            logger.error(String.format("VM instance with id [%s] is null, it is not possible to upgrade a null VM.", vmId));
            return false;
        }

        if (State.Stopped.equals(vmInstance.getState())) {
            upgradeStoppedVirtualMachine(vmId, newServiceOfferingId, customParameters);
            return true;
        }

        if (State.Running.equals(vmInstance.getState())) {
            ServiceOfferingVO newServiceOfferingVO = serviceOfferingDao.findById(newServiceOfferingId);
            VMTemplateVO template = templateDao.findByIdIncludingRemoved(vmInstance.getTemplateId());
            HostVO instanceHost = hostDao.findById(vmInstance.getHostId());
            hostDao.loadHostTags(instanceHost);

            Set<String> strictHostTags = UserVmManager.getStrictHostTags();
            if (!instanceHost.checkHostServiceOfferingAndTemplateTags(newServiceOfferingVO, template, strictHostTags)) {
                logger.error("Cannot upgrade VM {} as the new service offering {} does not have the required host tags {}.",
                        vmInstance, newServiceOfferingVO,
                        instanceHost.getHostServiceOfferingAndTemplateMissingTags(newServiceOfferingVO, template, strictHostTags));
                return false;
            }
        }
        return upgradeRunningVirtualMachine(vmId, newServiceOfferingId, customParameters);
    }

    private boolean upgradeRunningVirtualMachine(Long vmId, Long newServiceOfferingId, Map<String, String> customParameters) throws ResourceUnavailableException,
    ConcurrentOperationException, ManagementServerException, VirtualMachineMigrationException {

        Account caller = CallContext.current().getCallingAccount();
        VMInstanceVO vmInstance = vmInstanceDao.findById(vmId);
        Account owner = accountDao.findById(vmInstance.getAccountId());

        Set<HypervisorType> supportedHypervisorTypes = new HashSet<>();
        supportedHypervisorTypes.add(HypervisorType.XenServer);
        supportedHypervisorTypes.add(HypervisorType.VMware);
        supportedHypervisorTypes.add(HypervisorType.Simulator);
        supportedHypervisorTypes.add(HypervisorType.KVM);

        HypervisorType vmHypervisorType = vmInstance.getHypervisorType();

        if (!supportedHypervisorTypes.contains(vmHypervisorType)) {
            String message = String.format("Scaling the VM dynamically is not supported for VMs running on Hypervisor [%s].", vmInstance.getHypervisorType());
            logger.info(message);
            throw new InvalidParameterValueException(message);
        }

        accountManager.checkAccess(caller, null, true, vmInstance);

        ServiceOfferingVO newServiceOffering = serviceOfferingDao.findById(newServiceOfferingId);
        if (newServiceOffering.isDynamic()) {
            newServiceOffering.setDynamicFlag(true);
            serviceOfferingValidator.validateCustomParameters(newServiceOffering, customParameters);
            newServiceOffering = serviceOfferingDao.getComputeOffering(newServiceOffering, customParameters);
        }

        itMgr.checkIfCanUpgrade(vmInstance, newServiceOffering);

        ServiceOfferingVO currentServiceOffering = serviceOfferingDao.findByIdIncludingRemoved(vmInstance.getId(), vmInstance.getServiceOfferingId());
        if (newServiceOffering.isDynamicScalingEnabled() != currentServiceOffering.isDynamicScalingEnabled()) {
            throw new InvalidParameterValueException("Unable to Scale VM: since dynamic scaling enabled flag is not same for new service offering and old service offering");
        }

        serviceOfferingValidator.validateDiskOfferingChecks(currentServiceOffering, newServiceOffering);

        int newCpu = newServiceOffering.getCpu();
        int newMemory = newServiceOffering.getRamSize();
        int newSpeed = newServiceOffering.getSpeed();
        int currentCpu = currentServiceOffering.getCpu();
        int currentMemory = currentServiceOffering.getRamSize();
        int currentSpeed = currentServiceOffering.getSpeed();
        int memoryDiff = newMemory - currentMemory;
        int cpuDiff = newCpu * newSpeed - currentCpu * currentSpeed;

        if ((newSpeed < currentSpeed || newMemory < currentMemory || newCpu < currentCpu) || (newSpeed == currentSpeed && newMemory == currentMemory && newCpu == currentCpu)) {
            String message = String.format("While the VM is running, only scalling up it is supported. New service offering {\"memory\": %s, \"speed\": %s, \"cpu\": %s} should"
              + " have at least one value (ram, speed or cpu) greater than the current values {\"memory\": %s, \"speed\": %s, \"cpu\": %s}.", newMemory, newSpeed, newCpu,
              currentMemory, currentSpeed, currentCpu);

            throw new InvalidParameterValueException(message);
        }

        if (vmHypervisorType.equals(HypervisorType.KVM) && !currentServiceOffering.isDynamic()) {
            String message = String.format("Unable to live scale VM on KVM when current service offering is a \"Fixed Offering\". KVM needs the tag \"maxMemory\" to live scale and it is only configured when VM is deployed with a custom service offering and \"Dynamic Scalable\" is enabled.");
            logger.info(message);
            throw new InvalidParameterValueException(message);
        }

        serviceOfferingDao.loadDetails(currentServiceOffering);
        serviceOfferingDao.loadDetails(newServiceOffering);

        Map<String, String> currentDetails = currentServiceOffering.getDetails();
        Map<String, String> newDetails = newServiceOffering.getDetails();
        String currentVgpuType = currentDetails.get("vgpuType");
        String newVgpuType = newDetails.get("vgpuType");

        if (currentVgpuType != null && (newVgpuType == null || !newVgpuType.equalsIgnoreCase(currentVgpuType))) {
            throw new InvalidParameterValueException(String.format("Dynamic scaling of vGPU type is not supported. VM has vGPU Type: [%s].", currentVgpuType));
        }

        VMTemplateVO template = templateDao.findByIdIncludingRemoved(vmInstance.getTemplateId());

        List<Reserver> reservations = new ArrayList<>();
        try {
            resourceLimitService.checkVmResourceLimitsForServiceOfferingChange(owner, vmInstance.isDisplay(), (long) currentCpu, (long) newCpu,
                    (long) currentMemory, (long) newMemory, currentServiceOffering, newServiceOffering, template, reservations);

            boolean success = false;
            if (vmInstance.getState().equals(State.Running)) {
                int retry = scaleRetry;
                ExcludeList excludes = new ExcludeList();

                boolean enableDynamicallyScaleVm = UserVmManager.EnableDynamicallyScaleVm.valueIn(vmInstance.getDataCenterId());
                if (!enableDynamicallyScaleVm) {
                    throw new PermissionDeniedException("Dynamically scaling Instances is disabled for this zone, please contact your admin.");
                }

                if (!vmInstance.isDynamicallyScalable()) {
                    throw new CloudRuntimeException(String.format("Unable to scale %s as it does not have tools to support dynamic scaling.", vmInstance.toString()));
                }

                HostVO host = hostDao.findById(vmInstance.getHostId());
                hostDao.loadDetails(host);
                if (capacityManager.checkIfClusterCrossesThreshold(host.getClusterId(), cpuDiff, memoryDiff)) {
                    throw new CloudRuntimeException(String.format("Unable to scale %s due to insufficient resources.", vmInstance.toString()));
                }

                while (retry-- != 0) {
                    try {
                        boolean existingHostHasCapacity = false;

                        resourceLimitService.updateVmResourceCountForServiceOfferingChange(caller.getAccountId(), vmInstance.isDisplay(),
                                (long) currentCpu, (long) newCpu, (long) currentMemory, (long) newMemory,
                                currentServiceOffering, newServiceOffering, template);

                        if (!excludes.shouldAvoid(ApiDBUtils.findHostById(vmInstance.getHostId()))) {
                            existingHostHasCapacity = capacityManager.checkIfHostHasCpuCapability(host, newCpu, newSpeed)
                                    && capacityManager.checkIfHostHasCapacity(host, cpuDiff, ByteScaleUtils.mebibytesToBytes(memoryDiff), false,
                                            capacityManager.getClusterOverProvisioningFactor(host.getClusterId(), Capacity.CAPACITY_TYPE_CPU),
                                            capacityManager.getClusterOverProvisioningFactor(host.getClusterId(), Capacity.CAPACITY_TYPE_MEMORY), false)
                                    && vmMigrationValidator.checkEnforceStrictHostTagCheck(vmInstance, host);
                            excludes.addHost(vmInstance.getHostId());
                        }

                        if (!existingHostHasCapacity) {
                            itMgr.findHostAndMigrate(vmInstance.getUuid(), newServiceOfferingId, customParameters, excludes);
                        }

                        DiskOfferingVO newDiskOffering = diskOfferingDao.findById(newServiceOffering.getDiskOfferingId());
                        vmRootDiskOfferingChangeService.changeDiskOfferingForRootVolume(vmId, newDiskOffering, customParameters, vmInstance.getDataCenterId());

                        vmInstance = vmInstanceDao.findById(vmId);
                        itMgr.reConfigureVm(vmInstance.getUuid(), currentServiceOffering, newServiceOffering, customParameters, existingHostHasCapacity);
                        success = true;
                        return success;
                    } catch (InsufficientCapacityException | ResourceUnavailableException | ConcurrentOperationException e) {
                        logger.error(String.format("Unable to scale %s due to [%s].", vmInstance.toString(), e.getMessage()), e);
                    } finally {
                        if (!success) {
                            resourceLimitService.updateVmResourceCountForServiceOfferingChange(caller.getAccountId(), vmInstance.isDisplay(),
                                    (long) newCpu, (long) currentCpu, (long) newMemory, (long) currentMemory,
                                    newServiceOffering, currentServiceOffering, template);
                        }
                    }
                }
            }
            return success;

        } finally {
            ReservationHelper.closeAll(reservations);
        }
    }
}
