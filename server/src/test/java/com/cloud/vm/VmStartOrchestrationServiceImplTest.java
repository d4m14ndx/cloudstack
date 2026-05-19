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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.cloud.entity.api.VirtualMachineEntity;
import org.apache.cloudstack.engine.service.api.OrchestrationService;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.capacity.CapacityManager;
import com.cloud.dc.Pod;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.deploy.DeploymentPlanningManager;
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
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.Pair;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineProfile.Param;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@RunWith(MockitoJUnitRunner.class)
public class VmStartOrchestrationServiceImplTest {

    private static final long VM_ID = 42L;
    private static final long TEMPLATE_ID = 99L;
    private static final long SERVICE_OFFERING_ID = 77L;
    private static final long OWNER_ACCOUNT_ID = 33L;
    private static final long CALLER_ACCOUNT_ID = 22L;
    private static final long USER_ID = 11L;
    private static final long DATA_CENTER_ID = 5L;
    private static final long POD_ID = 6L;
    private static final long CLUSTER_ID = 7L;
    private static final long HOST_ID = 8L;
    private static final String RESERVATION_ID = "reservation-1";

    @Mock
    private AccountDao accountDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private ServiceOfferingDao serviceOfferingDao;
    @Mock
    private UserDao userDao;
    @Mock
    private UserVmDao vmDao;
    @Mock
    private VMTemplateDao templateDao;
    @Mock
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Mock
    private ReservationDao reservationDao;
    @Mock
    private AccountManager accountManager;
    @Mock
    private AccountService accountService;
    @Mock
    private CapacityManager capacityManager;
    @Mock
    private DeploymentPlanningManager planningManager;
    @Mock
    private NetworkModel networkModel;
    @Mock
    private OrchestrationService orchestrationService;
    @Mock
    private ResourceLimitService resourceLimitService;
    @Mock
    private SecurityGroupManager securityGroupManager;
    @Mock
    private VmCredentialResetService vmCredentialResetService;
    @Mock
    private VmMigrationValidator vmMigrationValidator;
    @Mock
    private VmPasswordSSHKeyResetService vmPasswordSSHKeyResetService;
    @Mock
    private VmStartPlacementService vmStartPlacementService;
    @Mock
    private VirtualMachineEntity vmEntity;
    @Mock
    private Account callerAccount;
    @Mock
    private AccountVO owner;
    @Mock
    private UserVmVO vm;
    @Mock
    private VMTemplateVO template;
    @Mock
    private ServiceOfferingVO offering;

    private final Map<ConfigKey, Object> originalConfigValues = new HashMap<>();
    private VmStartOrchestrationServiceImpl service;
    private UserVO callerUser;

    @Before
    public void setUp() {
        service = new VmStartOrchestrationServiceImpl();
        ReflectionTestUtils.setField(service, "accountDao", accountDao);
        ReflectionTestUtils.setField(service, "hostDao", hostDao);
        ReflectionTestUtils.setField(service, "serviceOfferingDao", serviceOfferingDao);
        ReflectionTestUtils.setField(service, "userDao", userDao);
        ReflectionTestUtils.setField(service, "vmDao", vmDao);
        ReflectionTestUtils.setField(service, "templateDao", templateDao);
        ReflectionTestUtils.setField(service, "vmInstanceDetailsDao", vmInstanceDetailsDao);
        ReflectionTestUtils.setField(service, "reservationDao", reservationDao);
        ReflectionTestUtils.setField(service, "accountManager", accountManager);
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "capacityManager", capacityManager);
        ReflectionTestUtils.setField(service, "planningManager", planningManager);
        ReflectionTestUtils.setField(service, "networkModel", networkModel);
        ReflectionTestUtils.setField(service, "orchestrationService", orchestrationService);
        ReflectionTestUtils.setField(service, "resourceLimitService", resourceLimitService);
        ReflectionTestUtils.setField(service, "securityGroupManager", securityGroupManager);
        ReflectionTestUtils.setField(service, "vmCredentialResetService", vmCredentialResetService);
        ReflectionTestUtils.setField(service, "vmMigrationValidator", vmMigrationValidator);
        ReflectionTestUtils.setField(service, "vmPasswordSSHKeyResetService", vmPasswordSSHKeyResetService);
        ReflectionTestUtils.setField(service, "vmStartPlacementService", vmStartPlacementService);

        callerUser = new UserVO(USER_ID);
        callerUser.setAccountId(CALLER_ACCOUNT_ID);
        when(callerAccount.getId()).thenReturn(CALLER_ACCOUNT_ID);
        when(callerAccount.getRemoved()).thenReturn(null);
        CallContext.register(callerUser, callerAccount);
    }

    @After
    public void tearDown() {
        CallContext.unregisterAll();
        for (Map.Entry<ConfigKey, Object> entry : originalConfigValues.entrySet()) {
            updateDefaultConfigValue(entry.getKey(), entry.getValue(), true);
        }
    }

    @Test
    public void startVirtualMachineThrowsWhenCallingAccountIsRemovedOrNull() {
        when(callerAccount.getRemoved()).thenReturn(new Date());

        assertThrows(InvalidParameterValueException.class,
                () -> service.startVirtualMachine(VM_ID, null, null, null, new HashMap<>(), null, true));

        verifyNoInteractions(vmDao);
    }

    @Test
    public void startVirtualMachineThrowsWhenVmIsMissing() {
        when(vmDao.findById(VM_ID)).thenReturn(null);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.startVirtualMachine(VM_ID, null, null, null, new HashMap<>(), null, true));

        assertTrue(exception.getMessage().contains("unable to find a virtual machine with id"));
    }

    @Test
    public void startVirtualMachineThrowsWhenVmAlreadyRunning() {
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getState()).thenReturn(State.Running);
        when(vm.getUuid()).thenReturn("vm-uuid");
        when(vm.getDisplayNameOrHostName()).thenReturn("vm-name");

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.startVirtualMachine(VM_ID, null, null, null, new HashMap<>(), null, true));

        assertTrue(exception.getMessage().contains("already running"));
    }

    @Test
    public void startVirtualMachineRejectsMissingOwner() {
        stubStoppedVm();
        when(accountDao.findById(OWNER_ACCOUNT_ID)).thenReturn(null);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.startVirtualMachine(VM_ID, null, null, null, new HashMap<>(), null, true));

        assertTrue(exception.getMessage().contains("does not exist"));
    }

    @Test
    public void startVirtualMachineRejectsDisabledOwner() {
        stubStoppedVm();
        when(accountDao.findById(OWNER_ACCOUNT_ID)).thenReturn(owner);
        when(owner.getState()).thenReturn(Account.State.DISABLED);

        assertThrows(PermissionDeniedException.class,
                () -> service.startVirtualMachine(VM_ID, null, null, null, new HashMap<>(), null, true));
    }

    @Test
    public void startVirtualMachineUsesPlacementServiceForRootAdminDestinations()
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        stubSuccessfulStart();
        HostVO destinationHost = stubDestinationHostWithCapacity();
        Pod destinationPod = mock(Pod.class);
        Cluster destinationCluster = mock(Cluster.class);
        when(vmStartPlacementService.getDestinationPod(POD_ID, true)).thenReturn(destinationPod);
        when(vmStartPlacementService.getDestinationCluster(CLUSTER_ID, true)).thenReturn(destinationCluster);
        when(vmStartPlacementService.getDestinationHost(HOST_ID, true, false)).thenReturn(destinationHost);

        service.startVirtualMachine(VM_ID, POD_ID, CLUSTER_ID, HOST_ID, new HashMap<>(), null, false);

        verify(vmStartPlacementService).getDestinationPod(POD_ID, true);
        verify(vmStartPlacementService).getDestinationCluster(CLUSTER_ID, true);
        verify(vmStartPlacementService).getDestinationHost(HOST_ID, true, false);
    }

    @Test
    public void startVirtualMachineRejectsExplicitHostWithoutCapacityWhenFallbackDisabled() {
        updateDefaultConfigValue(UserVmManagerImpl.AllowDeployVmIfGivenHostFails, false, false);
        stubSuccessfulStart();
        HostVO destinationHost = stubDestinationHost();
        when(vmStartPlacementService.getDestinationHost(HOST_ID, true, true)).thenReturn(destinationHost);
        when(serviceOfferingDao.findById(VM_ID, SERVICE_OFFERING_ID)).thenReturn(offering);
        when(capacityManager.checkIfHostHasCpuCapabilityAndCapacity(destinationHost, offering, false)).thenReturn(new Pair<>(false, true));

        assertThrows(InvalidParameterValueException.class,
                () -> service.startVirtualMachine(VM_ID, null, null, HOST_ID, new HashMap<>(), null, true));
    }

    @Test
    public void startVirtualMachineDeploysOnGivenHostWhenCapacityPassesAndFallbackDisabled()
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        updateDefaultConfigValue(UserVmManagerImpl.AllowDeployVmIfGivenHostFails, false, false);
        stubSuccessfulStart();
        HostVO destinationHost = stubDestinationHostWithCapacity();

        service.startVirtualMachine(VM_ID, null, null, HOST_ID, new HashMap<>(), null, true);

        verify(hostDao).loadHostTags(destinationHost);
        verify(vmMigrationValidator).validateStrictHostTagCheck(vm, destinationHost);
        ArgumentCaptor<DeploymentPlan> planCaptor = ArgumentCaptor.forClass(DeploymentPlan.class);
        verify(vmEntity).reserve(nullable(DeploymentPlanner.class), planCaptor.capture(), any(ExcludeList.class), eq(Long.toString(USER_ID)));
        DataCenterDeployment plan = (DataCenterDeployment)planCaptor.getValue();
        assertSame(HOST_ID, plan.getHostId());
        assertSame(CLUSTER_ID, plan.getClusterId());
        assertSame(POD_ID, plan.getPodId());
        verify(vmEntity).deploy(RESERVATION_ID, Long.toString(USER_ID), null, true);
    }

    @Test
    public void startVirtualMachineAddsDefaultSecurityGroupWhenNeeded()
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        stubSuccessfulStart();
        SecurityGroup defaultSecurityGroup = mock(SecurityGroup.class);
        when(defaultSecurityGroup.getId()).thenReturn(123L);
        when(securityGroupManager.isVmSecurityGroupEnabled(VM_ID)).thenReturn(true);
        when(securityGroupManager.getSecurityGroupsForVm(VM_ID)).thenReturn(Collections.emptyList());
        when(securityGroupManager.isVmMappedToDefaultSecurityGroup(VM_ID)).thenReturn(false);
        when(networkModel.canAddDefaultSecurityGroup()).thenReturn(true);
        when(securityGroupManager.getDefaultSecurityGroup(OWNER_ACCOUNT_ID)).thenReturn(defaultSecurityGroup);

        service.startVirtualMachine(VM_ID, null, null, null, new HashMap<>(), null, true);

        verify(securityGroupManager).addInstanceToGroups(vm, Collections.singletonList(123L));
    }

    @Test
    public void startVirtualMachineDoesNotAddDefaultSecurityGroupWhenDefaultGroupMissing()
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        stubSuccessfulStart();
        when(securityGroupManager.isVmSecurityGroupEnabled(VM_ID)).thenReturn(true);
        when(securityGroupManager.getSecurityGroupsForVm(VM_ID)).thenReturn(Collections.emptyList());
        when(securityGroupManager.isVmMappedToDefaultSecurityGroup(VM_ID)).thenReturn(false);
        when(networkModel.canAddDefaultSecurityGroup()).thenReturn(true);
        when(securityGroupManager.getDefaultSecurityGroup(OWNER_ACCOUNT_ID)).thenReturn(null);

        service.startVirtualMachine(VM_ID, null, null, null, new HashMap<>(), null, true);

        verify(securityGroupManager, never()).addInstanceToGroups(any(UserVmVO.class), anyList());
    }

    @Test
    public void startVirtualMachineCreatesReservationsWhenRunningVmResourceCountsOnly()
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        updateDefaultConfigValue(VirtualMachineManager.ResourceCountRunningVMsonly, true, false);
        stubSuccessfulStart();
        when(serviceOfferingDao.findById(VM_ID, SERVICE_OFFERING_ID)).thenReturn(offering);
        when(offering.getCpu()).thenReturn(2);
        when(offering.getRamSize()).thenReturn(1024);
        when(resourceLimitService.getResourceLimitHostTags(offering, template)).thenReturn(Collections.singletonList("tag-a"));

        try (MockedConstruction<CheckedReservation> ignored = mockConstruction(CheckedReservation.class)) {
            service.startVirtualMachine(VM_ID, null, null, null, new HashMap<>(), null, true);
        }

        verify(resourceLimitService).getResourceLimitHostTags(offering, template);
        verify(vmEntity).deploy(RESERVATION_ID, Long.toString(USER_ID), null, false);
    }

    @Test
    public void startVirtualMachineRejectsUnknownDeploymentPlanner() {
        stubSuccessfulStart();
        when(planningManager.getDeploymentPlannerByName("missing-planner")).thenReturn(null);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.startVirtualMachine(VM_ID, null, null, null, new HashMap<>(), "missing-planner", true));

        assertTrue(exception.getMessage().contains("Can't find a planner by name"));
    }

    @Test
    public void startVirtualMachinePersistsValidPasswordParameter()
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        stubSuccessfulStart();
        when(vm.isUpdateParameters()).thenReturn(true);
        when(vmPasswordSSHKeyResetService.getCurrentVmPasswordOrDefineNewPassword("", vm, template)).thenReturn("validPassword");

        service.startVirtualMachine(VM_ID, null, null, null, new HashMap<>(), null, true);

        verify(vmCredentialResetService).encryptAndStorePassword(vm, "validPassword");
        ArgumentCaptor<Map<Param, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vmEntity).deploy(eq(RESERVATION_ID), eq(Long.toString(USER_ID)), paramsCaptor.capture(), eq(false));
        assertSame("validPassword", paramsCaptor.getValue().get(Param.VmPassword));
    }

    @Test
    public void startVirtualMachineRejectsInvalidPassword() {
        stubSuccessfulStart();
        when(vm.isUpdateParameters()).thenReturn(true);
        when(vmPasswordSSHKeyResetService.getCurrentVmPasswordOrDefineNewPassword("", vm, template)).thenReturn("bad password");

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.startVirtualMachine(VM_ID, null, null, null, new HashMap<>(), null, true));

        assertTrue(exception.getMessage().contains("A valid password"));
        verify(vmCredentialResetService, never()).encryptAndStorePassword(any(UserVmVO.class), any());
    }

    @Test
    public void startVirtualMachineAcceptsBootIntoSetupForVmware()
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        stubSuccessfulStart();
        when(vm.getHypervisorType()).thenReturn(HypervisorType.VMware);
        Map<Param, Object> additionalParams = new HashMap<>();
        additionalParams.put(Param.BootIntoSetup, Boolean.TRUE);

        service.startVirtualMachine(VM_ID, null, null, null, additionalParams, null, true);

        ArgumentCaptor<Map<Param, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(vmEntity).deploy(eq(RESERVATION_ID), eq(Long.toString(USER_ID)), paramsCaptor.capture(), eq(false));
        assertSame(Boolean.TRUE, paramsCaptor.getValue().get(Param.BootIntoSetup));
    }

    @Test
    public void startVirtualMachineRejectsBootIntoSetupForNonVmware() {
        stubSuccessfulStart();
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Map<Param, Object> additionalParams = new HashMap<>();
        additionalParams.put(Param.BootIntoSetup, Boolean.TRUE);

        assertThrows(InvalidParameterValueException.class,
                () -> service.startVirtualMachine(VM_ID, null, null, null, additionalParams, null, true));
    }

    @Test
    public void startVirtualMachineClearsPasswordDetailAfterTemplatePasswordDeploy()
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        stubSuccessfulStart();
        when(vm.isUpdateParameters()).thenReturn(true);
        when(template.isEnablePassword()).thenReturn(true);
        when(vm.getDetail(VmDetailConstants.PASSWORD)).thenReturn("old-password");
        when(vmPasswordSSHKeyResetService.getCurrentVmPasswordOrDefineNewPassword("", vm, template)).thenReturn("validPassword");

        service.startVirtualMachine(VM_ID, null, null, null, new HashMap<>(), null, true);

        verify(vmInstanceDetailsDao).removeDetail(VM_ID, VmDetailConstants.PASSWORD);
        verify(vm).setUpdateParameters(false);
        verify(vmDao).update(VM_ID, vm);
    }

    private void stubSuccessfulStart() {
        stubStoppedVm();
        when(accountDao.findById(OWNER_ACCOUNT_ID)).thenReturn(owner);
        when(owner.getState()).thenReturn(Account.State.ENABLED);
        when(accountService.isRootAdmin(CALLER_ACCOUNT_ID)).thenReturn(true);
        when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(template);
        when(userDao.findById(USER_ID)).thenReturn(callerUser);
        when(orchestrationService.getVirtualMachine("vm-uuid")).thenReturn(vmEntity);
        try {
            when(vmEntity.reserve(nullable(DeploymentPlanner.class), nullable(DeploymentPlan.class), any(ExcludeList.class), eq(Long.toString(USER_ID)))).thenReturn(RESERVATION_ID);
        } catch (InsufficientCapacityException | ResourceUnavailableException e) {
            throw new AssertionError(e);
        }
    }

    private void stubStoppedVm() {
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getUuid()).thenReturn("vm-uuid");
        when(vm.getState()).thenReturn(State.Stopped);
        when(vm.getAccountId()).thenReturn(OWNER_ACCOUNT_ID);
        when(vm.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(vm.getServiceOfferingId()).thenReturn(SERVICE_OFFERING_ID);
        when(vm.getDataCenterId()).thenReturn(DATA_CENTER_ID);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vm.isUpdateParameters()).thenReturn(false);
    }

    private HostVO stubDestinationHostWithCapacity() {
        HostVO destinationHost = stubDestinationHost();
        when(serviceOfferingDao.findById(VM_ID, SERVICE_OFFERING_ID)).thenReturn(offering);
        when(capacityManager.checkIfHostHasCpuCapabilityAndCapacity(destinationHost, offering, false)).thenReturn(new Pair<>(true, true));
        return destinationHost;
    }

    private HostVO stubDestinationHost() {
        HostVO destinationHost = mock(HostVO.class);
        when(destinationHost.getId()).thenReturn(HOST_ID);
        when(destinationHost.getPodId()).thenReturn(POD_ID);
        when(destinationHost.getClusterId()).thenReturn(CLUSTER_ID);
        when(vmStartPlacementService.getDestinationHost(HOST_ID, true, true)).thenReturn(destinationHost);
        return destinationHost;
    }

    private void updateDefaultConfigValue(final ConfigKey configKey, final Object value, boolean revert) {
        try {
            Field field = ConfigKey.class.getDeclaredField("_defaultValue");
            field.setAccessible(true);
            if (!revert) {
                originalConfigValues.put(configKey, field.get(configKey));
            }
            field.set(configKey, String.valueOf(value));
        } catch (IllegalAccessException | NoSuchFieldException e) {
            throw new RuntimeException(e);
        }
    }
}
