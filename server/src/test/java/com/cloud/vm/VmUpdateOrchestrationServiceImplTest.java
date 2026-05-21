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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.BaseCmd.HTTPMethod;
import org.apache.cloudstack.api.command.user.vm.UpdateVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.extension.ExtensionHelper;
import org.apache.cloudstack.userdata.UserDataManager;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.router.CommandSetupHelper;
import com.cloud.network.router.NetworkHelper;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.uservm.UserVm;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@RunWith(MockitoJUnitRunner.class)
public class VmUpdateOrchestrationServiceImplTest {

    private static final long VM_ID = 1L;
    private static final long ACCOUNT_ID = 2L;
    private static final long SERVICE_OFFERING_ID = 3L;
    private static final long TEMPLATE_ID = 4L;
    private static final long GUEST_OS_ID = 5L;

    @Spy
    @InjectMocks
    private VmUpdateOrchestrationServiceImpl service = new VmUpdateOrchestrationServiceImpl();

    @Mock private UserVmDao vmDao;
    @Mock private VMTemplateDao templateDao;
    @Mock private VMInstanceDao vmInstanceDao;
    @Mock private NicDao nicDao;
    @Mock private NetworkDao networkDao;
    @Mock private NetworkOrchestrationService networkMgr;
    @Mock private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Mock private AccountDao accountDao;
    @Mock private AccountManager accountManager;
    @Mock private ServiceOfferingDao serviceOfferingDao;
    @Mock private ServiceOfferingValidator serviceOfferingValidator;
    @Mock private ResourceLimitService resourceLimitService;
    @Mock private UserDataManager userDataManager;
    @Mock private VmUpdateValidator vmUpdateValidator;
    @Mock private VmDisplayFlagService vmDisplayFlagService;
    @Mock private VmExtraConfigService vmExtraConfigService;
    @Mock private VmLeaseApplicationService vmLeaseApplicationService;
    @Mock private VmSecurityGroupAssignmentService vmSecurityGroupAssignmentService;
    @Mock private VmCredentialResetService vmCredentialResetService;
    @Mock private VmHostNameUniquenessService vmHostNameUniquenessService;
    @Mock private VmGroupService vmGroupService;
    @Mock private DomainRouterDao routerDao;
    @Mock private CommandSetupHelper commandSetupHelper;
    @Mock private NetworkHelper nwHelper;
    @Mock private ExtensionHelper extensionHelper;
    @Mock private UpdateVMCmd updateVmCommand;
    @Mock private UserVmVO vm;
    @Mock private UserVmVO updatedVm;
    @Mock private VMTemplateVO template;
    @Mock private AccountVO callerAccount;
    @Mock private UserVO callerUser;

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    @Test
    public void validateInputsAndPermissionForUpdateVirtualMachineCommandChecksVmGuestOsAndAccess() {
        CallContext.register(callerUser, callerAccount);
        when(updateVmCommand.getId()).thenReturn(VM_ID);
        when(vmDao.findById(VM_ID)).thenReturn(vm);

        service.validateInputsAndPermissionForUpdateVirtualMachineCommand(updateVmCommand);

        verify(vmUpdateValidator).validateGuestOsIdForUpdateVirtualMachineCommand(updateVmCommand);
        verify(accountManager).checkAccess(callerAccount, null, true, vm);
    }

    @Test
    public void updateVirtualMachineCommandPreparesInputsAndDelegatesToIdUpdate() throws Exception {
        CallContext.register(callerUser, callerAccount);
        List<Long> securityGroupIds = List.of(11L, 12L);
        UserVm expected = mock(UserVm.class);
        configureCommandUpdateDefaults(securityGroupIds);
        when(updateVmCommand.getDisplayName()).thenReturn("display");
        when(updateVmCommand.getGroup()).thenReturn("group");
        when(updateVmCommand.getHaEnable()).thenReturn(true);
        when(updateVmCommand.getDisplayVm()).thenReturn(false);
        when(updateVmCommand.getOsTypeId()).thenReturn(GUEST_OS_ID);
        when(updateVmCommand.isDynamicallyScalable()).thenReturn(true);
        when(updateVmCommand.getHostName()).thenReturn("host");
        when(updateVmCommand.getCustomId()).thenReturn("custom");
        when(updateVmCommand.getInstanceName()).thenReturn("instance");
        when(vm.isDisplay()).thenReturn(true);
        doReturn(expected).when(service).updateVirtualMachine(eq(VM_ID), eq("display"), eq("group"), eq(true),
                eq(false), nullable(Boolean.class), eq(GUEST_OS_ID), nullable(String.class), nullable(Long.class),
                nullable(String.class), eq(true), nullable(HTTPMethod.class), eq("custom"), eq("host"),
                eq("instance"), eq(securityGroupIds), nullable(Map.class));

        UserVm result = service.updateVirtualMachine(updateVmCommand);

        assertSame(expected, result);
        verify(vmUpdateValidator).validateGuestOsIdForUpdateVirtualMachineCommand(updateVmCommand);
        verify(accountManager).checkAccess(callerAccount, null, true, vm);
        verify(vmSecurityGroupAssignmentService).getSecurityGroupIdList(updateVmCommand);
        verify(vmDisplayFlagService).applyDisplayFlag(false, VM_ID, vm);
        verify(service).updateVirtualMachine(eq(VM_ID), eq("display"), eq("group"), eq(true),
                eq(false), nullable(Boolean.class), eq(GUEST_OS_ID), nullable(String.class), nullable(Long.class),
                nullable(String.class), eq(true), nullable(HTTPMethod.class), eq("custom"), eq("host"),
                eq("instance"), eq(securityGroupIds), nullable(Map.class));
    }

    @Test
    public void updateVirtualMachineCommandCleansDisplayDetails() throws Exception {
        CallContext.register(callerUser, callerAccount);
        configureCommandUpdateDefaults(Collections.emptyList());
        when(updateVmCommand.isCleanupDetails()).thenReturn(true);
        when(callerAccount.getType()).thenReturn(Account.Type.ADMIN);
        VMInstanceDetailVO userDetail = new VMInstanceDetailVO(VM_ID, "userdetail", "foo", true);
        VMInstanceDetailVO systemDetail = new VMInstanceDetailVO(VM_ID, "systemdetail", "bar", false);
        when(vmInstanceDetailsDao.listDetails(VM_ID)).thenReturn(List.of(userDetail, systemDetail));
        doReturn(updatedVm).when(service).updateVirtualMachine(anyLong(), nullable(String.class), nullable(String.class),
                nullable(Boolean.class), nullable(Boolean.class), nullable(Boolean.class), nullable(Long.class),
                nullable(String.class), nullable(Long.class), nullable(String.class), nullable(Boolean.class),
                nullable(HTTPMethod.class), nullable(String.class), nullable(String.class), nullable(String.class),
                any(), nullable(Map.class));

        service.updateVirtualMachine(updateVmCommand);

        verify(vmInstanceDetailsDao).removeDetail(VM_ID, "userdetail");
        verify(vmInstanceDetailsDao, never()).removeDetail(VM_ID, "systemdetail");
    }

    @Test
    public void updateVirtualMachineByIdPersistsRequestedFieldsAndAppliesSecurityGroups() throws Exception {
        List<Long> securityGroupIds = List.of(8L);
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(vmDao.findById(VM_ID)).thenReturn(vm, updatedVm);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getState()).thenReturn(State.Stopped);
        when(vm.getServiceOfferingId()).thenReturn(SERVICE_OFFERING_ID);
        when(vm.getUserData()).thenReturn("old-user-data");
        when(serviceOfferingDao.findById(VM_ID, SERVICE_OFFERING_ID)).thenReturn(offering);
        when(offering.isOfferHA()).thenReturn(true);
        when(nicDao.listByVmId(VM_ID)).thenReturn(Collections.emptyList());

        UserVm result = service.updateVirtualMachine(VM_ID, "display", "group", true, false, true,
                GUEST_OS_ID, null, 9L, "userdata-details", false, HTTPMethod.POST, "custom",
                null, "instance", securityGroupIds, null);

        assertSame(updatedVm, result);
        verify(vmGroupService).addInstanceToGroup(VM_ID, "group");
        verify(vmSecurityGroupAssignmentService).checkAndUpdateSecurityGroupForVM(securityGroupIds, vm, Collections.emptyList());
        verify(vmDao).updateVM(VM_ID, "display", true, GUEST_OS_ID, "old-user-data", 9L,
                "userdata-details", false, false, true, "custom", null, "instance");
    }

    @Test
    public void verifyVmLimitsRejectsFixedOfferingCpuMemoryUpdates() {
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getAccountId()).thenReturn(ACCOUNT_ID);
        when(vm.getServiceOfferingId()).thenReturn(SERVICE_OFFERING_ID);
        when(accountDao.findById(ACCOUNT_ID)).thenReturn(callerAccount);
        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);
        when(serviceOffering.isDynamic()).thenReturn(false);
        when(serviceOfferingDao.findByIdIncludingRemoved(VM_ID, SERVICE_OFFERING_ID)).thenReturn(serviceOffering);
        when(serviceOfferingDao.findById(SERVICE_OFFERING_ID)).thenReturn(serviceOffering);
        Map<String, String> details = new HashMap<>();
        details.put(VmDetailConstants.CPU_SPEED, "2500");

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.verifyVmLimits(vm, details));

        assertEquals("CPU number, Memory and CPU speed cannot be updated for a non-dynamic offering", exception.getMessage());
    }

    @Test
    public void updateDnsNoopsWhenHostNameIsBlank() throws ResourceUnavailableException, InsufficientCapacityException {
        service.updateDns(vm, "");

        verifyNoInteractions(routerDao, commandSetupHelper, nwHelper);
    }

    @Test
    public void verifyVmLimitsThrowsWhenOwnerIsNull() {
        when(vm.getAccountId()).thenReturn(ACCOUNT_ID);
        when(accountDao.findById(ACCOUNT_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class, () -> service.verifyVmLimits(vm, new HashMap<>()));
    }

    @Test
    public void validateInputsAndPermissionThrowsWhenVmNotFound() {
        CallContext.register(callerUser, callerAccount);
        when(updateVmCommand.getId()).thenReturn(VM_ID);
        when(vmDao.findById(VM_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.validateInputsAndPermissionForUpdateVirtualMachineCommand(updateVmCommand));
    }

    @Test
    public void updateUserDataDelegatesToCredentialResetService() throws ResourceUnavailableException, InsufficientCapacityException {
        UserVm userVm = mock(UserVm.class);

        service.updateUserData(userVm);

        verify(vmCredentialResetService).updateUserData(userVm);
    }

    @Test
    public void updateDnsNoopsWhenHostNameIsNull() throws ResourceUnavailableException, InsufficientCapacityException {
        service.updateDns(vm, null);

        verifyNoInteractions(nicDao, routerDao, commandSetupHelper, nwHelper);
    }

    private void configureCommandUpdateDefaults(List<Long> securityGroupIds) {
        when(updateVmCommand.getId()).thenReturn(VM_ID);
        when(updateVmCommand.getDetails()).thenReturn(Collections.emptyMap());
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(templateDao.findById(TEMPLATE_ID)).thenReturn(template);
        when(vmSecurityGroupAssignmentService.getSecurityGroupIdList(updateVmCommand)).thenReturn(securityGroupIds);
        when(vmCredentialResetService.finalizeUserData(nullable(String.class), nullable(Long.class), eq(template))).thenReturn(null);
        when(userDataManager.validateUserData(nullable(String.class), nullable(HTTPMethod.class))).thenReturn(null);
        doNothing().when(vmUpdateValidator).validateGuestOsIdForUpdateVirtualMachineCommand(updateVmCommand);
    }
}
