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
import java.util.Objects;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd.HTTPMethod;
import org.apache.cloudstack.api.command.user.vm.UpdateVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.apache.cloudstack.query.QueryService;
import org.apache.cloudstack.resourcelimit.Reserver;
import org.apache.cloudstack.userdata.UserDataManager;
import org.apache.cloudstack.vm.lease.VMLeaseManager;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.math.NumberUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.manager.Commands;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.router.CommandSetupHelper;
import com.cloud.network.router.NetworkHelper;
import com.cloud.offering.ServiceOffering;
import com.cloud.resourcelimit.ReservationHelper;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.dao.AccountDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@Component
public class VmUpdateOrchestrationServiceImpl implements VmUpdateOrchestrationService {
    private static final Logger LOG = LogManager.getLogger(VmUpdateOrchestrationServiceImpl.class);

    @Inject
    private UserVmDao vmDao;
    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private NicDao nicDao;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private NetworkOrchestrationService networkMgr;
    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Inject
    private AccountDao accountDao;
    @Inject
    private AccountManager accountManager;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private ServiceOfferingValidator serviceOfferingValidator;
    @Inject
    private ResourceLimitService resourceLimitService;
    @Inject
    private UserDataManager userDataManager;
    @Inject
    private VmUpdateValidator vmUpdateValidator;
    @Inject
    private VmDisplayFlagService vmDisplayFlagService;
    @Inject
    private VmExtraConfigService vmExtraConfigService;
    @Inject
    private VmLeaseApplicationService vmLeaseApplicationService;
    @Inject
    private VmSecurityGroupAssignmentService vmSecurityGroupAssignmentService;
    @Inject
    private VmCredentialResetService vmCredentialResetService;
    @Inject
    private VmHostNameUniquenessService vmHostNameUniquenessService;
    @Inject
    private VmGroupService vmGroupService;
    @Inject
    private DomainRouterDao routerDao;
    @Inject
    private CommandSetupHelper commandSetupHelper;
    @Autowired
    @Qualifier("networkHelper")
    private NetworkHelper nwHelper;
    @Inject
    private ExtensionHelper extensionHelper;

    @Override
    public void verifyVmLimits(UserVmVO vmInstance, Map<String, String> details) {
        Account owner = accountDao.findById(vmInstance.getAccountId());
        if (owner == null) {
            throw new InvalidParameterValueException("The owner of " + vmInstance + " does not exist: " + vmInstance.getAccountId());
        }

        long newCpu = NumberUtils.toLong(details.get(VmDetailConstants.CPU_NUMBER));
        long newMemory = NumberUtils.toLong(details.get(VmDetailConstants.MEMORY));
        ServiceOfferingVO currentServiceOffering = serviceOfferingDao.findByIdIncludingRemoved(vmInstance.getId(), vmInstance.getServiceOfferingId());
        ServiceOfferingVO svcOffering = serviceOfferingDao.findById(vmInstance.getServiceOfferingId());
        boolean isDynamic = currentServiceOffering.isDynamic();
        if (isDynamic) {
            Map<String, String> customParameters = new HashMap<>();
            customParameters.put(VmDetailConstants.CPU_NUMBER, String.valueOf(newCpu));
            customParameters.put(VmDetailConstants.MEMORY, String.valueOf(newMemory));
            if (details.containsKey(VmDetailConstants.CPU_SPEED)) {
                customParameters.put(VmDetailConstants.CPU_SPEED, details.get(VmDetailConstants.CPU_SPEED));
            }
            serviceOfferingValidator.validateCustomParameters(svcOffering, customParameters);
        } else if (details.containsKey(VmDetailConstants.CPU_NUMBER) || details.containsKey(VmDetailConstants.MEMORY)
                || details.containsKey(VmDetailConstants.CPU_SPEED)) {
            throw new InvalidParameterValueException("CPU number, Memory and CPU speed cannot be updated for a non-dynamic offering");
        }
        if (VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
            return;
        }
        long currentCpu = currentServiceOffering.getCpu();
        long currentMemory = currentServiceOffering.getRamSize();
        VMTemplateVO template = templateDao.findByIdIncludingRemoved(vmInstance.getTemplateId());
        List<Reserver> reservations = new ArrayList<>();
        try {
            resourceLimitService.checkVmResourceLimitsForServiceOfferingChange(owner, vmInstance.isDisplay(), currentCpu, newCpu,
                    currentMemory, newMemory, currentServiceOffering, svcOffering, template, reservations);
            if (newCpu > currentCpu) {
                resourceLimitService.incrementVmCpuResourceCount(owner.getAccountId(), vmInstance.isDisplay(), svcOffering, template, newCpu - currentCpu);
            } else if (newCpu > 0 && currentCpu > newCpu) {
                resourceLimitService.decrementVmCpuResourceCount(owner.getAccountId(), vmInstance.isDisplay(), svcOffering, template, currentCpu - newCpu);
            }
            if (newMemory > currentMemory) {
                resourceLimitService.incrementVmMemoryResourceCount(owner.getAccountId(), vmInstance.isDisplay(), svcOffering, template, newMemory - currentMemory);
            } else if (newMemory > 0 && currentMemory > newMemory) {
                resourceLimitService.decrementVmMemoryResourceCount(owner.getAccountId(), vmInstance.isDisplay(), svcOffering, template, currentMemory - newMemory);
            }
        } catch (ResourceAllocationException e) {
            LOG.error(String.format("Failed to updated VM due to: %s", e.getLocalizedMessage()));
            throw new InvalidParameterValueException(e.getLocalizedMessage());
        } finally {
            ReservationHelper.closeAll(reservations);
        }
    }

    @Override
    public UserVm updateVirtualMachine(UpdateVMCmd cmd) throws ResourceUnavailableException, InsufficientCapacityException {
        validateInputsAndPermissionForUpdateVirtualMachineCommand(cmd);

        String displayName = cmd.getDisplayName();
        String group = cmd.getGroup();
        Boolean ha = cmd.getHaEnable();
        Boolean isDisplayVm = cmd.getDisplayVm();
        Long id = cmd.getId();
        Long osTypeId = cmd.getOsTypeId();
        Boolean isDynamicallyScalable = cmd.isDynamicallyScalable();
        String hostName = cmd.getHostName();
        Map<String, String> details = cmd.getDetails();
        List<Long> securityGroupIdList = vmSecurityGroupAssignmentService.getSecurityGroupIdList(cmd);
        boolean cleanupDetails = cmd.isCleanupDetails();
        String extraConfig = cmd.getExtraConfig();
        boolean cleanupExtraConfig = cmd.isCleanupExtraConfig();

        UserVmVO vmInstance = vmDao.findById(cmd.getId());
        VMTemplateVO template = templateDao.findById(vmInstance.getTemplateId());

        UserVmVO userVm = vmDao.findById(cmd.getId());
        if (userVm != null && UserVmManager.SHAREDFSVM.equals(userVm.getUserVmType())) {
            throw new InvalidParameterValueException("Operation not supported on Shared FileSystem Instance");
        }

        String userData = cmd.getUserData();
        Long userDataId = cmd.getUserdataId();
        String userDataDetails = null;
        if (MapUtils.isNotEmpty(cmd.getUserdataDetails())) {
            userDataDetails = cmd.getUserdataDetails().toString();
        }
        userData = vmCredentialResetService.finalizeUserData(userData, userDataId, template);
        userData = userDataManager.validateUserData(userData, cmd.getHttpMethod());

        if (isDisplayVm != null && isDisplayVm != vmInstance.isDisplay()) {
            updateDisplayVmFlag(isDisplayVm, id, vmInstance);
        }
        final Account caller = CallContext.current().getCallingAccount();
        final List<String> userDenyListedSettings = Stream.of(QueryService.UserVMDeniedDetails.value().split(","))
                .map(String::trim)
                .collect(Collectors.toList());
        userDenyListedSettings.addAll(QueryService.RootAdminOnlyVmSettings);
        if (template != null && template.getExtensionId() != null) {
            userDenyListedSettings.addAll(extensionHelper.getExtensionReservedResourceDetails(template.getExtensionId()));
        }

        final List<String> userReadOnlySettings = Stream.of(QueryService.UserVMReadOnlyDetails.value().split(","))
                .map(String::trim)
                .collect(Collectors.toList());
        List<VMInstanceDetailVO> existingDetails = vmInstanceDetailsDao.listDetails(id);
        if (cleanupDetails) {
            cleanupDetails(id, template, caller, userDenyListedSettings, userReadOnlySettings, existingDetails);
        } else if (MapUtils.isNotEmpty(details)) {
            validateAndSaveDetails(id, details, vmInstance, template, caller, userDenyListedSettings, userReadOnlySettings, existingDetails);
        }
        updateVmExtraConfig(userVm, extraConfig, cleanupExtraConfig);

        if (VMLeaseManager.InstanceLeaseEnabled.value() && cmd.getLeaseDuration() != null) {
            vmLeaseApplicationService.applyLeaseOnUpdateInstance(vmInstance, cmd.getLeaseDuration(), cmd.getLeaseExpiryAction());
        }

        return updateVirtualMachine(id, displayName, group, ha, isDisplayVm,
                cmd.getDeleteProtection(), osTypeId, userData,
                userDataId, userDataDetails, isDynamicallyScalable, cmd.getHttpMethod(),
                cmd.getCustomId(), hostName, cmd.getInstanceName(), securityGroupIdList,
                cmd.getDhcpOptionsMap());
    }

    private void cleanupDetails(Long id,
                                VMTemplateVO template,
                                Account caller,
                                List<String> userDenyListedSettings,
                                List<String> userReadOnlySettings,
                                List<VMInstanceDetailVO> existingDetails) {
        if (template != null && template.isDeployAsIs()) {
            throw new InvalidParameterValueException("Detail settings are read from OVA, it cannot be cleaned up by API call.");
        }
        if (caller != null && caller.getType() == Account.Type.ADMIN) {
            for (final VMInstanceDetailVO detail : existingDetails) {
                if (detail != null && detail.isDisplay() && !isExtraConfig(detail.getName())) {
                    vmInstanceDetailsDao.removeDetail(id, detail.getName());
                }
            }
            return;
        }
        for (final VMInstanceDetailVO detail : existingDetails) {
            if (detail != null && !userDenyListedSettings.contains(detail.getName())
                    && !userReadOnlySettings.contains(detail.getName()) && detail.isDisplay()
                    && !isExtraConfig(detail.getName())) {
                vmInstanceDetailsDao.removeDetail(id, detail.getName());
            }
        }
    }

    private void validateAndSaveDetails(Long id,
                                        Map<String, String> details,
                                        UserVmVO vmInstance,
                                        VMTemplateVO template,
                                        Account caller,
                                        List<String> userDenyListedSettings,
                                        List<String> userReadOnlySettings,
                                        List<VMInstanceDetailVO> existingDetails) {
        if (details.containsKey(VmDetailConstants.INSTANCE_LEASE_EXECUTION)
                || details.containsKey(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE)
                || details.containsKey(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION)) {
            throw new InvalidParameterValueException("lease parameters should not be included in details as key");
        }

        if (details.containsKey("extraconfig")) {
            throw new InvalidParameterValueException("'extraconfig' should not be included in details as key");
        }

        validateDeployAsIsDetails(details, vmInstance, template, existingDetails);
        details.entrySet().removeIf(detail -> isExtraConfig(detail.getKey()));
        validateCallerCanUpdateDetails(details, caller, userDenyListedSettings, userReadOnlySettings, existingDetails);

        for (final VMInstanceDetailVO existingDetail : existingDetails) {
            if (!existingDetail.isDisplay() || isExtraConfig(existingDetail.getName())) {
                details.put(existingDetail.getName(), existingDetail.getValue());
            }
        }

        verifyVmLimits(vmInstance, details);
        vmInstance.setDetails(details);
        vmDao.saveDetails(vmInstance);
    }

    private void validateDeployAsIsDetails(Map<String, String> details,
                                           UserVmVO vmInstance,
                                           VMTemplateVO template,
                                           List<VMInstanceDetailVO> existingDetails) {
        if (template == null || !template.isDeployAsIs()) {
            return;
        }
        final List<String> vmwareAllowedDetailsFromOva = UserVmManager.VmwareAdditionalDetailsFromOvaEnabled.valueIn(vmInstance.getDataCenterId())
                ? Stream.of(UserVmManager.VmwareAllowedAdditionalDetailsFromOva.valueIn(vmInstance.getDataCenterId()).split(","))
                .map(String::trim)
                .collect(Collectors.toList()) : List.of();
        for (String detailKey : details.keySet()) {
            if (vmwareAllowedDetailsFromOva.contains(detailKey)) {
                continue;
            }
            VMInstanceDetailVO detailVO = existingDetails.stream()
                    .filter(detail -> Objects.equals(detail.getName(), detailKey))
                    .findFirst()
                    .orElse(null);
            if (detailVO != null && ObjectUtils.allNotNull(detailVO.getValue(), details.get(detailKey))
                    && detailVO.getValue().equals(details.get(detailKey))) {
                continue;
            }
            throw new InvalidParameterValueException("Detail settings are read from OVA, it cannot be changed by API call.");
        }
    }

    private void validateCallerCanUpdateDetails(Map<String, String> details,
                                                Account caller,
                                                List<String> userDenyListedSettings,
                                                List<String> userReadOnlySettings,
                                                List<VMInstanceDetailVO> existingDetails) {
        if (caller != null && caller.getType() == Account.Type.ADMIN) {
            return;
        }
        for (final String detailName : details.keySet()) {
            if (userDenyListedSettings.contains(detailName)) {
                throw new InvalidParameterValueException("You're not allowed to add or edit the restricted setting: " + detailName);
            }
            if (userReadOnlySettings.contains(detailName)) {
                throw new InvalidParameterValueException("You're not allowed to add or edit the read-only setting: " + detailName);
            }
            if (existingDetails.stream().anyMatch(detail -> Objects.equals(detail.getName(), detailName) && !detail.isDisplay())) {
                throw new InvalidParameterValueException("You're not allowed to add or edit the non-displayable setting: " + detailName);
            }
        }
        for (final VMInstanceDetailVO detail : existingDetails) {
            if (userDenyListedSettings.contains(detail.getName()) || userReadOnlySettings.contains(detail.getName())) {
                details.put(detail.getName(), detail.getValue());
            }
        }
    }

    private boolean isExtraConfig(String detailName) {
        return detailName != null && detailName.startsWith(ApiConstants.EXTRA_CONFIG);
    }

    private void updateVmExtraConfig(UserVmVO userVm, String extraConfig, boolean cleanupExtraConfig) {
        if (cleanupExtraConfig) {
            LOG.info("Cleaning up extraconfig from user vm: {}", userVm.getUuid());
            vmInstanceDetailsDao.removeDetailsWithPrefix(userVm.getId(), ApiConstants.EXTRA_CONFIG);
            return;
        }
        if (StringUtils.isNotBlank(extraConfig)) {
            if (UserVmManager.EnableAdditionalVmConfig.valueIn(userVm.getAccountId())) {
                LOG.info("Adding extra configuration to user vm: {}", userVm.getUuid());
                vmExtraConfigService.addExtraConfig(userVm, extraConfig);
            } else {
                throw new InvalidParameterValueException("attempted setting extraconfig but enable.additional.vm.configuration is disabled");
            }
        }
    }

    @Override
    public void updateDisplayVmFlag(Boolean isDisplayVm, Long id, UserVmVO vmInstance) {
        vmDisplayFlagService.applyDisplayFlag(isDisplayVm, id, vmInstance);
    }

    @Override
    public void validateInputsAndPermissionForUpdateVirtualMachineCommand(UpdateVMCmd cmd) {
        UserVmVO vmInstance = vmDao.findById(cmd.getId());
        if (vmInstance == null) {
            throw new InvalidParameterValueException("unable to find virtual machine with id: " + cmd.getId());
        }
        vmUpdateValidator.validateGuestOsIdForUpdateVirtualMachineCommand(cmd);
        Account caller = CallContext.current().getCallingAccount();
        accountManager.checkAccess(caller, null, true, vmInstance);
    }

    @Override
    public UserVm updateVirtualMachine(long id,
                                       String displayName,
                                       String group,
                                       Boolean ha,
                                       Boolean isDisplayVmEnabled,
                                       Boolean deleteProtection,
                                       Long osTypeId,
                                       String userData,
                                       Long userDataId,
                                       String userDataDetails,
                                       Boolean isDynamicallyScalable,
                                       HTTPMethod httpMethod,
                                       String customId,
                                       String hostName,
                                       String instanceName,
                                       List<Long> securityGroupIdList,
                                       Map<String, Map<Integer, String>> extraDhcpOptionsMap)
            throws ResourceUnavailableException, InsufficientCapacityException {
        UserVmVO vm = vmDao.findById(id);
        if (vm == null) {
            throw new CloudRuntimeException("Unable to find virtual machine with id " + id);
        }

        if (instanceName != null) {
            VMInstanceVO vmInstance = vmInstanceDao.findVMByInstanceName(instanceName);
            if (vmInstance != null && vmInstance.getId() != id) {
                throw new CloudRuntimeException("Instance name : " + instanceName + " is not unique");
            }
        }

        if (vm.getState() == State.Error || vm.getState() == State.Expunging) {
            LOG.error("vm {} is not in the correct state. current state: {}", vm, vm.getState());
            throw new InvalidParameterValueException(String.format("Vm %s is not in the right state", vm));
        }

        if (displayName == null) {
            displayName = vm.getDisplayName();
        }
        if (ha == null) {
            ha = vm.isHaEnabled();
        }

        ServiceOffering offering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
        if (!offering.isOfferHA() && ha) {
            throw new InvalidParameterValueException("Can't enable ha for the vm as it's created from the Service offering having HA disabled");
        }

        if (isDisplayVmEnabled == null) {
            isDisplayVmEnabled = vm.isDisplayVm();
        }
        if (deleteProtection == null) {
            deleteProtection = vm.isDeleteProtection();
        }

        boolean updateUserdata = false;
        if (userData != null) {
            userData = userData.replace("\\n", "");
            userData = userDataManager.validateUserData(userData, httpMethod);
            updateUserdata = true;
        } else {
            userData = vm.getUserData();
        }

        if (userDataId == null) {
            userDataId = vm.getUserDataId();
        }
        if (userDataDetails == null) {
            userDataDetails = vm.getUserDataDetails();
        }
        if (osTypeId == null) {
            osTypeId = vm.getGuestOSId();
        }
        if (group != null) {
            vmGroupService.addInstanceToGroup(id, group);
        }

        if (isDynamicallyScalable == null) {
            isDynamicallyScalable = vm.isDynamicallyScalable();
        } else if (isDynamicallyScalable) {
            validateDynamicScaling(vm, offering);
        }

        List<? extends Nic> nics = nicDao.listByVmId(vm.getId());
        if (hostName != null) {
            checkNameForRFCCompliance(hostName);
            if (vm.getHostName().equals(hostName)) {
                LOG.debug("Vm " + vm + " is already set with the hostName specified: " + hostName);
                hostName = null;
            }

            List<NetworkVO> vmNetworks = new ArrayList<>(nics.size());
            for (Nic nic : nics) {
                vmNetworks.add(networkDao.findById(nic.getNetworkId()));
            }
            vmHostNameUniquenessService.checkIfHostNameUniqueInNtwkDomain(hostName, vmNetworks);
        }

        List<NetworkVO> networks = nics.stream()
                .map(nic -> networkDao.findById(nic.getNetworkId()))
                .collect(Collectors.toList());

        vmHostNameUniquenessService.verifyExtraDhcpOptionsNetwork(extraDhcpOptionsMap, networks);
        for (Nic nic : nics) {
            networkMgr.saveExtraDhcpOptions(networks.stream()
                    .filter(network -> network.getId() == nic.getNetworkId())
                    .findFirst()
                    .get()
                    .getUuid(), nic.getId(), extraDhcpOptionsMap);
        }

        checkAndUpdateSecurityGroupForVM(securityGroupIdList, vm, networks);

        vmDao.updateVM(id, displayName, ha, osTypeId, userData, userDataId,
                userDataDetails, isDisplayVmEnabled, isDynamicallyScalable,
                deleteProtection, customId, hostName, instanceName);

        if (updateUserdata) {
            updateUserData(vm);
        }

        if (State.Running == vm.getState()) {
            updateDns(vm, hostName);
        }

        return vmDao.findById(id);
    }

    private void validateDynamicScaling(UserVmVO vm, ServiceOffering offering) {
        VMTemplateVO template = templateDao.findByIdIncludingRemoved(vm.getTemplateId());
        if (!template.isDynamicallyScalable()) {
            throw new InvalidParameterValueException("Dynamic Scaling cannot be enabled for the Instance since its Template does not have dynamic scaling enabled");
        }
        if (!offering.isDynamicScalingEnabled()) {
            throw new InvalidParameterValueException("Dynamic Scaling cannot be enabled for the Instance since its service offering does not have dynamic scaling enabled");
        }
        if (!UserVmManager.EnableDynamicallyScaleVm.valueIn(vm.getDataCenterId())) {
            LOG.debug("Dynamic Scaling cannot be enabled for the VM {} since the global setting enable.dynamic.scale.vm is set to false", vm);
            throw new InvalidParameterValueException("Dynamic Scaling cannot be enabled for the VM since corresponding global setting is set to false");
        }
    }

    private void checkNameForRFCCompliance(String name) {
        if (!NetUtils.verifyDomainNameLabel(name, true)) {
            throw new InvalidParameterValueException("Invalid name. Vm name can contain ASCII letters 'a' through 'z', the digits '0' through '9', "
                    + "and the hyphen ('-'), must be between 1 and 63 characters long, and can't start or end with \"-\" and can't start with digit");
        }
    }

    @Override
    public void checkAndUpdateSecurityGroupForVM(List<Long> securityGroupIdList, UserVmVO vm, List<NetworkVO> networks) {
        vmSecurityGroupAssignmentService.checkAndUpdateSecurityGroupForVM(securityGroupIdList, vm, networks);
    }

    @Override
    public void updateUserData(UserVm vm) throws ResourceUnavailableException, InsufficientCapacityException {
        vmCredentialResetService.updateUserData(vm);
    }

    @Override
    public void updateDns(UserVmVO vm, String hostName) throws ResourceUnavailableException, InsufficientCapacityException {
        if (StringUtils.isEmpty(hostName)) {
            return;
        }
        vm.setHostName(hostName);
        try {
            List<NicVO> nicVOs = nicDao.listByVmId(vm.getId());
            for (NicVO nic : nicVOs) {
                List<DomainRouterVO> routers = routerDao.findByNetwork(nic.getNetworkId());
                for (DomainRouterVO router : routers) {
                    if (router.getState() != State.Running) {
                        LOG.warn("Unable to update DNS for VM {}, as virtual router: {} is not in the right state: {} ", vm, router, router.getState());
                        continue;
                    }
                    Commands commands = new Commands(Command.OnError.Stop);
                    commandSetupHelper.createDhcpEntryCommand(router, vm, nic, false, commands);
                    if (!nwHelper.sendCommandsToRouter(router, commands)) {
                        throw new CloudRuntimeException(String.format("Unable to send commands to virtual router: %s", router.getHostId()));
                    }
                    Answer answer = commands.getAnswer("dhcp");
                    if (answer == null || !answer.getResult()) {
                        throw new CloudRuntimeException("Failed to update hostname");
                    }
                    updateUserData(vm);
                }
            }
        } catch (CloudRuntimeException e) {
            throw new CloudRuntimeException(String.format("Failed to update hostname of VM %s to %s", vm.getInstanceName(), vm.getHostName()));
        }
    }
}
