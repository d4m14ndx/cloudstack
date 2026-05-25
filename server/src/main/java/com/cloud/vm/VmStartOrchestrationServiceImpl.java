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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.cloud.entity.api.VirtualMachineEntity;
import org.apache.cloudstack.engine.service.api.OrchestrationService;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import com.cloud.capacity.CapacityManager;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.dc.Pod;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.NetworkModel;
import com.cloud.network.security.SecurityGroup;
import com.cloud.network.security.SecurityGroupManager;
import com.cloud.org.Cluster;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.Pair;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineProfile.Param;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@Component
public class VmStartOrchestrationServiceImpl implements VmStartOrchestrationService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private AccountDao accountDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private UserDao userDao;
    @Inject
    private UserVmDao vmDao;
    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Inject
    private ReservationDao reservationDao;
    @Inject
    private AccountManager accountManager;
    @Inject
    private AccountService accountService;
    @Inject
    private CapacityManager capacityManager;
    @Inject
    private DeploymentPlanningManager planningManager;
    @Inject
    private NetworkModel networkModel;
    @Inject
    private OrchestrationService orchestrationService;
    @Inject
    private ResourceLimitService resourceLimitService;
    @Inject
    private SecurityGroupManager securityGroupManager;
    @Inject
    private VmCredentialResetService vmCredentialResetService;
    @Inject
    private VmMigrationValidator vmMigrationValidator;
    @Inject
    private VmPasswordSSHKeyResetService vmPasswordSSHKeyResetService;
    @Inject
    private VmStartPlacementService vmStartPlacementService;

    @Override
    public Pair<UserVmVO, Map<Param, Object>> startVirtualMachine(long vmId, Long hostId, @NotNull Map<Param, Object> additionalParams,
            String deploymentPlannerToUse) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        return startVirtualMachine(vmId, null, null, hostId, additionalParams, deploymentPlannerToUse);
    }

    @Override
    public Pair<UserVmVO, Map<Param, Object>> startVirtualMachine(long vmId, Long podId, Long clusterId, Long hostId,
            @NotNull Map<Param, Object> additionalParams, String deploymentPlannerToUse)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        return startVirtualMachine(vmId, podId, clusterId, hostId, additionalParams, deploymentPlannerToUse, true);
    }

    @Override
    public Pair<UserVmVO, Map<Param, Object>> startVirtualMachine(long vmId, Long podId, Long clusterId, Long hostId,
            @NotNull Map<Param, Object> additionalParams, String deploymentPlannerToUse, boolean isExplicitHost)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        final Account callerAccount = CallContext.current().getCallingAccount();

        if (callerAccount == null || callerAccount.getRemoved() != null) {
            throw new InvalidParameterValueException(String.format("The account %s is removed", callerAccount));
        }

        UserVmVO vm = vmDao.findById(vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("unable to find a virtual machine with id " + vmId);
        }

        if (vm.getState() == State.Running) {
            throw new InvalidParameterValueException(String.format("The virtual machine %s (%s) is already running",
                    vm.getUuid(), vm.getDisplayNameOrHostName()));
        }

        accountManager.checkAccess(callerAccount, null, true, vm);

        Account owner = accountDao.findById(vm.getAccountId());

        if (owner == null) {
            throw new InvalidParameterValueException("The owner of " + vm + " does not exist: " + vm.getAccountId());
        }

        if (owner.getState() == Account.State.DISABLED) {
            throw new PermissionDeniedException(String.format("The owner of %s is disabled: %s", vm, owner));
        }
        boolean isRootAdmin = accountService.isRootAdmin(callerAccount.getId());

        VMTemplateVO template = templateDao.findByIdIncludingRemoved(vm.getTemplateId());
        if (VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
            ServiceOfferingVO offering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
            List<String> resourceLimitHostTags = resourceLimitService.getResourceLimitHostTags(offering, template);
            try (CheckedReservation vmReservation = new CheckedReservation(owner, ResourceType.user_vm, resourceLimitHostTags, 1L, reservationDao, resourceLimitService);
                 CheckedReservation cpuReservation = new CheckedReservation(owner, ResourceType.cpu, resourceLimitHostTags, Long.valueOf(offering.getCpu()), reservationDao, resourceLimitService);
                 CheckedReservation memReservation = new CheckedReservation(owner, ResourceType.memory, resourceLimitHostTags, Long.valueOf(offering.getRamSize()), reservationDao, resourceLimitService);
            ) {
                return startVirtualMachineUnchecked(vm, template, podId, clusterId, hostId, additionalParams, deploymentPlannerToUse, isExplicitHost, isRootAdmin);
            }
        } else {
            return startVirtualMachineUnchecked(vm, template, podId, clusterId, hostId, additionalParams, deploymentPlannerToUse, isExplicitHost, isRootAdmin);
        }
    }

    private Pair<UserVmVO, Map<Param, Object>> startVirtualMachineUnchecked(UserVmVO vm, VMTemplateVO template, Long podId,
            Long clusterId, Long hostId, @NotNull Map<Param, Object> additionalParams, String deploymentPlannerToUse,
            boolean isExplicitHost, boolean isRootAdmin) throws ResourceUnavailableException, InsufficientCapacityException {

        if (securityGroupManager.isVmSecurityGroupEnabled(vm.getId()) && securityGroupManager.getSecurityGroupsForVm(vm.getId()).isEmpty()
                && !securityGroupManager.isVmMappedToDefaultSecurityGroup(vm.getId()) && networkModel.canAddDefaultSecurityGroup()) {
            if (logger.isDebugEnabled()) {
                logger.debug("Vm " + vm + " is security group enabled, but not mapped to default security group; creating the mapping automatically");
            }

            SecurityGroup defaultSecurityGroup = securityGroupManager.getDefaultSecurityGroup(vm.getAccountId());
            if (defaultSecurityGroup != null) {
                List<Long> groupList = new ArrayList<>();
                groupList.add(defaultSecurityGroup.getId());
                securityGroupManager.addInstanceToGroups(vm, groupList);
            }
        }

        Pod destinationPod = vmStartPlacementService.getDestinationPod(podId, isRootAdmin);
        Cluster destinationCluster = vmStartPlacementService.getDestinationCluster(clusterId, isRootAdmin);
        HostVO destinationHost = vmStartPlacementService.getDestinationHost(hostId, isRootAdmin, isExplicitHost);
        DataCenterDeployment plan = null;
        boolean deployOnGivenHost = false;
        if (destinationHost != null) {
            logger.debug("Destination Host to deploy the VM is specified, specifying a deployment plan to deploy the VM");
            hostDao.loadHostTags(destinationHost);
            vmMigrationValidator.validateStrictHostTagCheck(vm, destinationHost);

            final ServiceOfferingVO offering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
            Pair<Boolean, Boolean> cpuCapabilityAndCapacity = capacityManager.checkIfHostHasCpuCapabilityAndCapacity(destinationHost, offering, false);
            if (!cpuCapabilityAndCapacity.first() || !cpuCapabilityAndCapacity.second()) {
                String errorMsg;
                if (!cpuCapabilityAndCapacity.first()) {
                    errorMsg = String.format("Cannot deploy the VM to specified host %s, requested CPU and speed is more than the host capability", destinationHost);
                } else {
                    errorMsg = String.format("Cannot deploy the VM to specified host %s, host does not have enough free CPU or RAM, please check the logs", destinationHost);
                }
                logger.info(errorMsg);
                if (!UserVmManagerImpl.AllowDeployVmIfGivenHostFails.value()) {
                    throw new InvalidParameterValueException(errorMsg);
                }
            } else {
                plan = new DataCenterDeployment(vm.getDataCenterId(), destinationHost.getPodId(), destinationHost.getClusterId(), destinationHost.getId(), null, null);
                if (!UserVmManagerImpl.AllowDeployVmIfGivenHostFails.value()) {
                    deployOnGivenHost = true;
                }
            }
        } else if (destinationCluster != null) {
            logger.debug("Destination Cluster to deploy the VM is specified, specifying a deployment plan to deploy the VM");
            plan = new DataCenterDeployment(vm.getDataCenterId(), destinationCluster.getPodId(), destinationCluster.getId(), null, null, null);
            if (!UserVmManagerImpl.AllowDeployVmIfGivenHostFails.value()) {
                deployOnGivenHost = true;
            }
        } else if (destinationPod != null) {
            logger.debug("Destination Pod to deploy the VM is specified, specifying a deployment plan to deploy the VM");
            plan = new DataCenterDeployment(vm.getDataCenterId(), destinationPod.getId(), null, null, null, null);
            if (!UserVmManagerImpl.AllowDeployVmIfGivenHostFails.value()) {
                deployOnGivenHost = true;
            }
        }

        Map<Param, Object> params = null;
        if (vm.isUpdateParameters()) {
            vmDao.loadDetails(vm);
            String password = vmPasswordSSHKeyResetService.getCurrentVmPasswordOrDefineNewPassword(
                    String.valueOf(additionalParams.getOrDefault(Param.VmPassword, "")), vm, template);
            if (!validPassword(password)) {
                throw new InvalidParameterValueException("A valid password for this virtual machine was not provided.");
            }
            vmCredentialResetService.encryptAndStorePassword(vm, password);
            params = createParameterInParameterMap(params, additionalParams, Param.VmPassword, password);
        }

        if (additionalParams.containsKey(Param.BootIntoSetup)) {
            if (!HypervisorType.VMware.equals(vm.getHypervisorType())) {
                throw new InvalidParameterValueException(ApiConstants.BOOT_INTO_SETUP + " makes no sense for " + vm.getHypervisorType());
            }
            Object paramValue = additionalParams.get(Param.BootIntoSetup);
            if (logger.isTraceEnabled()) {
                logger.trace("It was specified whether to enter setup mode: " + paramValue.toString());
            }
            params = createParameterInParameterMap(params, additionalParams, Param.BootIntoSetup, paramValue);
        }

        VirtualMachineEntity vmEntity = orchestrationService.getVirtualMachine(vm.getUuid());

        DeploymentPlanner planner = null;
        if (deploymentPlannerToUse != null) {
            planner = planningManager.getDeploymentPlannerByName(deploymentPlannerToUse);
            if (planner == null) {
                throw new InvalidParameterValueException("Can't find a planner by name " + deploymentPlannerToUse);
            }
        }
        vmEntity.setParamsToEntity(additionalParams);

        UserVO callerUser = userDao.findById(CallContext.current().getCallingUserId());
        String reservationId = vmEntity.reserve(planner, plan, new ExcludeList(), Long.toString(callerUser.getId()));
        vmEntity.deploy(reservationId, Long.toString(callerUser.getId()), params, deployOnGivenHost);

        Pair<UserVmVO, Map<Param, Object>> vmParamPair = new Pair<>(vm, params);
        if (vm.isUpdateParameters()) {
            if (template.isEnablePassword()) {
                if (vm.getDetail(VmDetailConstants.PASSWORD) != null) {
                    vmInstanceDetailsDao.removeDetail(vm.getId(), VmDetailConstants.PASSWORD);
                }
                vm.setUpdateParameters(false);
                vmDao.update(vm.getId(), vm);
            }
        }
        return vmParamPair;
    }

    private Map<Param, Object> createParameterInParameterMap(Map<Param, Object> params, Map<Param, Object> parameterMap, Param parameter,
            Object parameterValue) {
        if (logger.isTraceEnabled()) {
            logger.trace(String.format("createParameterInParameterMap(%s, %s)", parameter, parameterValue));
        }
        if (params == null) {
            if (logger.isTraceEnabled()) {
                logger.trace("creating new Parameter map");
            }
            params = new HashMap<>();
            if (parameterMap != null) {
                params.putAll(parameterMap);
            }
        }
        params.put(parameter, parameterValue);
        return params;
    }

    private boolean validPassword(String password) {
        if (password == null || password.length() == 0) {
            return false;
        }
        for (int i = 0; i < password.length(); i++) {
            if (password.charAt(i) == ' ') {
                return false;
            }
        }
        return true;
    }
}
