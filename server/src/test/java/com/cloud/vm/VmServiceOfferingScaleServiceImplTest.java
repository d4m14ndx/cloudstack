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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyFloat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyShort;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.command.user.vm.ScaleVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpgradeVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.resourcelimit.Reserver;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.ApiDBUtils;
import com.cloud.capacity.CapacityManager;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.ServiceOffering;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmServiceOfferingScaleServiceImplTest {

    private static final long VM_ID = 11L;
    private static final long NEW_OFFERING_ID = 22L;
    private static final long CURRENT_OFFERING_ID = 21L;
    private static final long ACCOUNT_ID = 33L;
    private static final long ZONE_ID = 44L;
    private static final long TEMPLATE_ID = 55L;
    private static final long HOST_ID = 66L;
    private static final long CLUSTER_ID = 77L;
    private static final long DISK_OFFERING_ID = 88L;
    private static final String VM_UUID = "vm-uuid";

    @InjectMocks
    private VmServiceOfferingScaleServiceImpl service;

    @Mock
    private AccountDao accountDao;
    @Mock
    private DataCenterDao dcDao;
    @Mock
    private DiskOfferingDao diskOfferingDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private VMTemplateDao templateDao;
    @Mock
    private UserVmDao vmDao;
    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private ServiceOfferingDao serviceOfferingDao;
    @Mock
    private AccountManager accountManager;
    @Mock
    private CapacityManager capacityManager;
    @Mock
    private VirtualMachineManager itMgr;
    @Mock
    private ResourceLimitService resourceLimitService;
    @Mock
    private EntityManager entityMgr;
    @Mock
    private ServiceOfferingValidator serviceOfferingValidator;
    @Mock
    private VmUpdateValidator vmUpdateValidator;
    @Mock
    private VmRootDiskOfferingChangeService vmRootDiskOfferingChangeService;
    @Mock
    private VmMigrationValidator vmMigrationValidator;
    @Mock
    private VmUsageEventPublisher vmUsageEventPublisher;

    private final Map<ConfigKey, Object> originalConfigValues = new HashMap<>();
    private MockedStatic<ApiDBUtils> apiDbUtilsMock;
    private ServiceOfferingVO currentOffering;
    private ServiceOfferingVO newOffering;
    private DiskOfferingVO diskOffering;

    @After
    public void afterTest() {
        if (apiDbUtilsMock != null) {
            apiDbUtilsMock.close();
            apiDbUtilsMock = null;
        }
        try {
            CallContext.unregister();
        } catch (RuntimeException ignored) {
        }
        for (Map.Entry<ConfigKey, Object> entry : originalConfigValues.entrySet()) {
            updateDefaultConfigValue(entry.getKey(), entry.getValue(), true);
        }
    }

    @Test
    public void upgradeStoppedVirtualMachineRejectsInactiveOffering() {
        registerCallContext(mock(Account.class));
        VMInstanceVO vm = stubVm(VM_ID, State.Stopped, HypervisorType.KVM);
        ServiceOfferingVO inactiveOffering = stubOffering(NEW_OFFERING_ID, 2, 1000, 2048, false, false);
        when(inactiveOffering.getState()).thenReturn(ServiceOffering.State.Inactive);
        when(serviceOfferingDao.findById(NEW_OFFERING_ID)).thenReturn(inactiveOffering);

        assertThrows(InvalidParameterValueException.class,
                () -> service.upgradeVirtualMachine(mockUpgradeCommand(VM_ID, NEW_OFFERING_ID, new HashMap<>())));

        verify(accountManager).checkAccess(any(Account.class), eq(null), eq(true), eq(vm));
        verifyNoInteractions(serviceOfferingValidator, vmRootDiskOfferingChangeService, vmUsageEventPublisher);
    }

    @Test
    public void upgradeStoppedVirtualMachineValidatesDynamicOfferingAndUsesComputedOffering() throws Exception {
        registerCallContext(mock(Account.class));
        Map<String, String> details = new HashMap<>();
        details.put(VmDetailConstants.CPU_NUMBER, "4");
        ServiceOfferingVO dynamicOffering = stubOffering(NEW_OFFERING_ID, 2, 1000, 2048, true, false);
        ServiceOfferingVO computedOffering = prepareStoppedUpgradeSuccess(dynamicOffering);

        boolean result = service.upgradeVirtualMachine(VM_ID, NEW_OFFERING_ID, details);

        assertTrue(result);
        verify(serviceOfferingValidator).validateCustomParameters(dynamicOffering, details);
        verify(serviceOfferingDao).getComputeOffering(dynamicOffering, details);
        verify(itMgr).upgradeVmDb(VM_ID, computedOffering, currentOffering());
    }

    @Test
    public void upgradeStoppedVirtualMachineChecksAndUpdatesResourceLimitsWhenAllVmsAreCounted() throws Exception {
        registerCallContext(mock(Account.class));
        updateDefaultConfigValue(VirtualMachineManager.ResourceCountRunningVMsonly, false, false);
        prepareStoppedUpgradeSuccess(stubOffering(NEW_OFFERING_ID, 4, 1000, 4096, false, false));

        boolean result = service.upgradeVirtualMachine(VM_ID, NEW_OFFERING_ID, new HashMap<>());

        assertTrue(result);
        verify(resourceLimitService).checkVmResourceLimitsForServiceOfferingChange(any(Account.class), eq(true),
                eq(1L), eq(4L), eq(1024L), eq(4096L), eq(currentOffering()), any(ServiceOfferingVO.class),
                any(VMTemplateVO.class), any(List.class));
        verify(resourceLimitService).updateVmResourceCountForServiceOfferingChange(eq(ACCOUNT_ID), eq(true),
                eq(1L), eq(4L), eq(1024L), eq(4096L), eq(currentOffering()), any(ServiceOfferingVO.class),
                any(VMTemplateVO.class));
    }

    @Test
    public void upgradeStoppedVirtualMachineDelegatesRootDiskOfferingChange() throws Exception {
        registerCallContext(mock(Account.class));
        Map<String, String> details = new HashMap<>();
        ServiceOfferingVO newOffering = prepareStoppedUpgradeSuccess(stubOffering(NEW_OFFERING_ID, 3, 1000, 3072, false, false));
        DiskOfferingVO newDiskOffering = diskOffering();

        service.upgradeVirtualMachine(VM_ID, NEW_OFFERING_ID, details);

        verify(diskOfferingDao).findById(newOffering.getDiskOfferingId());
        verify(vmRootDiskOfferingChangeService).changeDiskOfferingForRootVolume(VM_ID, newDiskOffering, details, ZONE_ID);
    }

    @Test
    public void upgradeStoppedVirtualMachineClosesReservationsWhenUpgradeFails() throws Exception {
        registerCallContext(mock(Account.class));
        updateDefaultConfigValue(VirtualMachineManager.ResourceCountRunningVMsonly, false, false);
        Reserver reservation = mock(Reserver.class);
        prepareStoppedUpgradeSuccess(stubOffering(NEW_OFFERING_ID, 4, 1000, 4096, false, false));
        doAnswer(invocation -> {
            List<Reserver> reservations = invocation.getArgument(9);
            reservations.add(reservation);
            return null;
        }).when(resourceLimitService).checkVmResourceLimitsForServiceOfferingChange(any(Account.class), anyBoolean(),
                anyLong(), anyLong(), anyLong(), anyLong(), any(ServiceOfferingVO.class), any(ServiceOfferingVO.class),
                any(VMTemplateVO.class), any(List.class));
        doThrow(new InvalidParameterValueException("cannot upgrade")).when(itMgr).checkIfCanUpgrade(any(VMInstanceVO.class), any(ServiceOfferingVO.class));

        assertThrows(InvalidParameterValueException.class,
                () -> service.upgradeVirtualMachine(VM_ID, NEW_OFFERING_ID, new HashMap<>()));

        verify(reservation).close();
    }

    @Test
    public void upgradeCommandPublishesUsageEventAfterSuccessfulStoppedUpgrade() throws Exception {
        registerCallContext(mock(Account.class));
        UserVmVO upgradedVm = userVm();
        prepareStoppedUpgradeSuccess(stubOffering(NEW_OFFERING_ID, 4, 1000, 4096, false, false), upgradedVm);

        UserVm result = service.upgradeVirtualMachine(mockUpgradeCommand(VM_ID, NEW_OFFERING_ID, new HashMap<>()));

        assertSame(upgradedVm, result);
        verify(vmUsageEventPublisher).generateUsageEvent(upgradedVm, true, EventTypes.EVENT_VM_UPGRADE);
    }

    @Test
    public void scaleCommandRejectsMissingVm() {
        registerCallContext(mock(Account.class));
        when(entityMgr.findById(VirtualMachine.class, VM_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.upgradeVirtualMachine(mockScaleCommand(VM_ID, NEW_OFFERING_ID, new HashMap<>())));
    }

    @Test
    public void scaleCommandRejectsExternalHypervisor() {
        registerCallContext(mock(Account.class));
        VirtualMachine vm = stubEntityVm(HypervisorType.External);
        when(entityMgr.findById(VirtualMachine.class, VM_ID)).thenReturn(vm);

        assertThrows(InvalidParameterValueException.class,
                () -> service.upgradeVirtualMachine(mockScaleCommand(VM_ID, NEW_OFFERING_ID, new HashMap<>())));
    }

    @Test
    public void scaleCommandBackfillsDetailsBeforeUpgrade() throws Exception {
        registerCallContext(mock(Account.class));
        Map<String, String> details = new HashMap<>();
        VirtualMachine entityVm = stubEntityVm(HypervisorType.KVM);
        when(entityMgr.findById(VirtualMachine.class, VM_ID)).thenReturn(entityVm);
        prepareStoppedUpgradeSuccess(stubOffering(NEW_OFFERING_ID, 4, 1000, 4096, false, false));

        service.upgradeVirtualMachine(mockScaleCommand(VM_ID, NEW_OFFERING_ID, details));

        verify(vmUpdateValidator).updateInstanceDetailsMapWithCurrentValuesForAbsentDetails(details, entityVm, NEW_OFFERING_ID);
    }

    @Test
    public void scaleCommandPublishesUsageEventForStoppedVmOnly() throws Exception {
        registerCallContext(mock(Account.class));
        VirtualMachine entityVm = stubEntityVm(HypervisorType.KVM);
        UserVmVO stoppedVm = userVm();
        when(entityMgr.findById(VirtualMachine.class, VM_ID)).thenReturn(entityVm);
        prepareStoppedUpgradeSuccess(stubOffering(NEW_OFFERING_ID, 4, 1000, 4096, false, false), stoppedVm);

        service.upgradeVirtualMachine(mockScaleCommand(VM_ID, NEW_OFFERING_ID, new HashMap<>()));

        verify(vmUsageEventPublisher).generateUsageEvent(stoppedVm, true, EventTypes.EVENT_VM_UPGRADE);
    }

    @Test
    public void upgradeVirtualMachineReturnsFalseWhenRunningVmHostTagsDoNotMatch() throws Exception {
        registerCallContext(mock(Account.class));
        stubRunningVmWithHostTags(false, stubOffering(NEW_OFFERING_ID, 4, 1000, 4096, false, true),
                stubOffering(CURRENT_OFFERING_ID, 2, 1000, 2048, true, true));

        boolean result = service.upgradeVirtualMachine(VM_ID, NEW_OFFERING_ID, new HashMap<>());

        assertFalse(result);
        verify(itMgr, never()).checkIfCanUpgrade(any(VMInstanceVO.class), any(ServiceOfferingVO.class));
    }

    @Test
    public void runningScaleRejectsUnsupportedHypervisor() {
        registerCallContext(mock(Account.class));
        stubRunningVmWithHostTags(true, stubOffering(NEW_OFFERING_ID, 4, 1000, 4096, false, true),
                stubOffering(CURRENT_OFFERING_ID, 2, 1000, 2048, true, true), HypervisorType.LXC);

        assertThrows(InvalidParameterValueException.class,
                () -> service.upgradeVirtualMachine(VM_ID, NEW_OFFERING_ID, new HashMap<>()));
    }

    @Test
    public void runningScaleRejectsDynamicScalingFlagMismatch() {
        registerCallContext(mock(Account.class));
        stubRunningVmWithHostTags(true, stubOffering(NEW_OFFERING_ID, 4, 1000, 4096, false, true),
                stubOffering(CURRENT_OFFERING_ID, 2, 1000, 2048, true, false));

        assertThrows(InvalidParameterValueException.class,
                () -> service.upgradeVirtualMachine(VM_ID, NEW_OFFERING_ID, new HashMap<>()));
    }

    @Test
    public void runningScaleRejectsScaleDownOrEqualOffering() {
        registerCallContext(mock(Account.class));
        stubRunningVmWithHostTags(true, stubOffering(NEW_OFFERING_ID, 2, 1000, 2048, false, true),
                stubOffering(CURRENT_OFFERING_ID, 2, 1000, 2048, true, true));

        assertThrows(InvalidParameterValueException.class,
                () -> service.upgradeVirtualMachine(VM_ID, NEW_OFFERING_ID, new HashMap<>()));
    }

    @Test
    public void runningScaleRejectsFixedKvmOffering() {
        registerCallContext(mock(Account.class));
        stubRunningVmWithHostTags(true, stubOffering(NEW_OFFERING_ID, 4, 1000, 4096, false, true),
                stubOffering(CURRENT_OFFERING_ID, 2, 1000, 2048, false, true), HypervisorType.KVM);

        assertThrows(InvalidParameterValueException.class,
                () -> service.upgradeVirtualMachine(VM_ID, NEW_OFFERING_ID, new HashMap<>()));
    }

    @Test
    public void runningScaleRejectsVgpuTypeChange() {
        registerCallContext(mock(Account.class));
        ServiceOfferingVO newOffering = stubOffering(NEW_OFFERING_ID, 4, 1000, 4096, false, true, Collections.singletonMap("vgpuType", "GRID-A"));
        ServiceOfferingVO currentOffering = stubOffering(CURRENT_OFFERING_ID, 2, 1000, 2048, true, true, Collections.singletonMap("vgpuType", "GRID-B"));
        stubRunningVmWithHostTags(true, newOffering, currentOffering);

        assertThrows(InvalidParameterValueException.class,
                () -> service.upgradeVirtualMachine(VM_ID, NEW_OFFERING_ID, new HashMap<>()));
    }

    @Test
    public void runningScaleRejectsZoneDisabled() {
        registerCallContext(mock(Account.class));
        updateDefaultConfigValue(UserVmManager.EnableDynamicallyScaleVm, false, false);
        stubRunningScaleReady(stubOffering(NEW_OFFERING_ID, 4, 1000, 4096, false, true),
                stubOffering(CURRENT_OFFERING_ID, 2, 1000, 2048, true, true));

        assertThrows(PermissionDeniedException.class,
                () -> service.upgradeVirtualMachine(VM_ID, NEW_OFFERING_ID, new HashMap<>()));
    }

    @Test
    public void runningScaleRejectsVmWithoutDynamicScalingTools() {
        registerCallContext(mock(Account.class));
        updateDefaultConfigValue(UserVmManager.EnableDynamicallyScaleVm, true, false);
        VMInstanceVO vm = stubRunningScaleReady(stubOffering(NEW_OFFERING_ID, 4, 1000, 4096, false, true),
                stubOffering(CURRENT_OFFERING_ID, 2, 1000, 2048, true, true));
        when(vm.isDynamicallyScalable()).thenReturn(false);

        assertThrows(CloudRuntimeException.class,
                () -> service.upgradeVirtualMachine(VM_ID, NEW_OFFERING_ID, new HashMap<>()));
    }

    @Test
    public void runningScaleRejectsClusterThreshold() {
        registerCallContext(mock(Account.class));
        updateDefaultConfigValue(UserVmManager.EnableDynamicallyScaleVm, true, false);
        stubRunningScaleReady(stubOffering(NEW_OFFERING_ID, 4, 1000, 4096, false, true),
                stubOffering(CURRENT_OFFERING_ID, 2, 1000, 2048, true, true));
        when(capacityManager.checkIfClusterCrossesThreshold(eq(CLUSTER_ID), anyInt(), anyLong())).thenReturn(true);

        assertThrows(CloudRuntimeException.class,
                () -> service.upgradeVirtualMachine(VM_ID, NEW_OFFERING_ID, new HashMap<>()));
    }

    @Test
    public void runningScaleReconfiguresOnExistingHostWhenCapacityAndTagsPass() throws Exception {
        registerCallContext(mock(Account.class));
        updateDefaultConfigValue(UserVmManager.EnableDynamicallyScaleVm, true, false);
        VMInstanceVO vm = stubRunningScaleReady(stubOffering(NEW_OFFERING_ID, 4, 1000, 4096, false, true),
                stubOffering(CURRENT_OFFERING_ID, 2, 1000, 2048, true, true));
        stubExistingHostCapacity(true);
        when(vmMigrationValidator.checkEnforceStrictHostTagCheck(eq(vm), any(HostVO.class))).thenReturn(true);

        boolean result = service.upgradeVirtualMachine(VM_ID, NEW_OFFERING_ID, new HashMap<>());

        assertTrue(result);
        verify(itMgr, never()).findHostAndMigrate(any(), anyLong(), anyMap(), any());
        verify(itMgr).reConfigureVm(eq(VM_UUID), eq(currentOffering()), eq(newOffering()), anyMap(), eq(true));
    }

    @Test
    public void runningScaleMigratesWhenExistingHostCannotFit() throws Exception {
        registerCallContext(mock(Account.class));
        updateDefaultConfigValue(UserVmManager.EnableDynamicallyScaleVm, true, false);
        stubRunningScaleReady(stubOffering(NEW_OFFERING_ID, 4, 1000, 4096, false, true),
                stubOffering(CURRENT_OFFERING_ID, 2, 1000, 2048, true, true));
        stubExistingHostCapacity(false);

        boolean result = service.upgradeVirtualMachine(VM_ID, NEW_OFFERING_ID, new HashMap<>());

        assertTrue(result);
        verify(itMgr).findHostAndMigrate(eq(VM_UUID), eq(NEW_OFFERING_ID), anyMap(), any());
        verify(itMgr).reConfigureVm(eq(VM_UUID), eq(currentOffering()), eq(newOffering()), anyMap(), eq(false));
    }

    @Test
    public void runningScaleRollsBackResourceCountsWhenRetryFails() throws Exception {
        registerCallContext(mock(Account.class));
        updateDefaultConfigValue(UserVmManager.EnableDynamicallyScaleVm, true, false);
        service.setScaleRetry(1);
        stubRunningScaleReady(stubOffering(NEW_OFFERING_ID, 4, 1000, 4096, false, true),
                stubOffering(CURRENT_OFFERING_ID, 2, 1000, 2048, true, true));
        stubExistingHostCapacity(true);
        when(vmMigrationValidator.checkEnforceStrictHostTagCheck(any(VMInstanceVO.class), any(HostVO.class))).thenReturn(true);
        when(itMgr.reConfigureVm(any(), any(ServiceOfferingVO.class), any(ServiceOfferingVO.class), anyMap(), anyBoolean()))
                .thenThrow(new ResourceUnavailableException("nope", VirtualMachine.class, VM_ID));

        boolean result = service.upgradeVirtualMachine(VM_ID, NEW_OFFERING_ID, new HashMap<>());

        assertFalse(result);
        verify(resourceLimitService).updateVmResourceCountForServiceOfferingChange(anyLong(), eq(true),
                eq(2L), eq(4L), eq(2048L), eq(4096L), eq(currentOffering()), eq(newOffering()), any(VMTemplateVO.class));
        verify(resourceLimitService).updateVmResourceCountForServiceOfferingChange(anyLong(), eq(true),
                eq(4L), eq(2L), eq(4096L), eq(2048L), eq(newOffering()), eq(currentOffering()), any(VMTemplateVO.class));
    }

    @Test
    public void runningScaleClosesReservationsWhenAnyFailureEscapes() throws Exception {
        registerCallContext(mock(Account.class));
        updateDefaultConfigValue(UserVmManager.EnableDynamicallyScaleVm, false, false);
        Reserver reservation = mock(Reserver.class);
        stubRunningScaleReady(stubOffering(NEW_OFFERING_ID, 4, 1000, 4096, false, true),
                stubOffering(CURRENT_OFFERING_ID, 2, 1000, 2048, true, true));
        doAnswer(invocation -> {
            List<Reserver> reservations = invocation.getArgument(9);
            reservations.add(reservation);
            return null;
        }).when(resourceLimitService).checkVmResourceLimitsForServiceOfferingChange(any(Account.class), anyBoolean(),
                anyLong(), anyLong(), anyLong(), anyLong(), any(ServiceOfferingVO.class), any(ServiceOfferingVO.class),
                any(VMTemplateVO.class), any(List.class));

        assertThrows(PermissionDeniedException.class,
                () -> service.upgradeVirtualMachine(VM_ID, NEW_OFFERING_ID, new HashMap<>()));

        verify(reservation).close();
    }

    private ScaleVMCmd mockScaleCommand(long vmId, long serviceOfferingId, Map<String, String> details) {
        ScaleVMCmd cmd = mock(ScaleVMCmd.class);
        when(cmd.getId()).thenReturn(vmId);
        when(cmd.getServiceOfferingId()).thenReturn(serviceOfferingId);
        when(cmd.getDetails()).thenReturn(details);
        return cmd;
    }

    private UpgradeVMCmd mockUpgradeCommand(long vmId, long serviceOfferingId, Map<String, String> details) {
        UpgradeVMCmd cmd = mock(UpgradeVMCmd.class);
        when(cmd.getId()).thenReturn(vmId);
        when(cmd.getServiceOfferingId()).thenReturn(serviceOfferingId);
        when(cmd.getDetails()).thenReturn(details);
        return cmd;
    }

    private VMInstanceVO stubVm(long id, State state, HypervisorType hypervisorType) {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        lenient().when(vm.getId()).thenReturn(id);
        lenient().when(vm.getUuid()).thenReturn(VM_UUID);
        lenient().when(vm.getName()).thenReturn("vm-" + id);
        lenient().when(vm.getState()).thenReturn(state);
        lenient().when(vm.getHypervisorType()).thenReturn(hypervisorType);
        lenient().when(vm.getAccountId()).thenReturn(ACCOUNT_ID);
        lenient().when(vm.getServiceOfferingId()).thenReturn(CURRENT_OFFERING_ID);
        lenient().when(vm.getTemplateId()).thenReturn(TEMPLATE_ID);
        lenient().when(vm.getDataCenterId()).thenReturn(ZONE_ID);
        lenient().when(vm.getHostId()).thenReturn(HOST_ID);
        lenient().when(vm.isDisplay()).thenReturn(true);
        lenient().when(vm.isDynamicallyScalable()).thenReturn(true);
        lenient().when(vm.toString()).thenReturn("vm-" + id);
        lenient().when(vmInstanceDao.findById(id)).thenReturn(vm);
        return vm;
    }

    private ServiceOfferingVO stubOffering(long id, int cpu, int speed, int memory, boolean dynamic, boolean dynamicScaling) {
        return stubOffering(id, cpu, speed, memory, dynamic, dynamicScaling, new HashMap<>());
    }

    private ServiceOfferingVO stubOffering(long id, int cpu, int speed, int memory, boolean dynamic,
            boolean dynamicScaling, Map<String, String> details) {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        lenient().when(offering.getId()).thenReturn(id);
        lenient().when(offering.getUuid()).thenReturn("offering-" + id);
        lenient().when(offering.getState()).thenReturn(ServiceOffering.State.Active);
        lenient().when(offering.getCpu()).thenReturn(cpu);
        lenient().when(offering.getSpeed()).thenReturn(speed);
        lenient().when(offering.getRamSize()).thenReturn(memory);
        lenient().when(offering.isDynamic()).thenReturn(dynamic);
        lenient().when(offering.isDynamicScalingEnabled()).thenReturn(dynamicScaling);
        lenient().when(offering.getDiskOfferingId()).thenReturn(DISK_OFFERING_ID);
        lenient().when(offering.getDetails()).thenReturn(details);
        return offering;
    }

    private void registerCallContext(Account caller) {
        try {
            CallContext.unregister();
        } catch (RuntimeException ignored) {
        }
        UserVO user = mock(UserVO.class);
        lenient().when(user.getId()).thenReturn(1L);
        CallContext.register(user, caller);
    }

    private ServiceOfferingVO prepareStoppedUpgradeSuccess(ServiceOfferingVO newOffering) throws Exception {
        return prepareStoppedUpgradeSuccess(newOffering, userVm());
    }

    private ServiceOfferingVO prepareStoppedUpgradeSuccess(ServiceOfferingVO newOffering, UserVmVO upgradedVm) throws Exception {
        stubVm(VM_ID, State.Stopped, HypervisorType.KVM);
        when(serviceOfferingDao.findById(NEW_OFFERING_ID)).thenReturn(newOffering);
        if (newOffering.isDynamic()) {
            ServiceOfferingVO computedOffering = stubOffering(NEW_OFFERING_ID + 100, newOffering.getCpu(),
                    newOffering.getSpeed(), newOffering.getRamSize(), false, newOffering.isDynamicScalingEnabled());
            when(serviceOfferingDao.getComputeOffering(eq(newOffering), anyMap())).thenReturn(computedOffering);
            newOffering = computedOffering;
        }
        this.newOffering = newOffering;
        ServiceOfferingVO current = currentOffering();
        AccountVO owner = ownerAccount();
        VMTemplateVO template = template();
        DataCenterVO zone = mock(DataCenterVO.class);
        Long diskOfferingId = newOffering.getDiskOfferingId();
        DiskOfferingVO rootDiskOffering = diskOffering();
        when(serviceOfferingDao.findByIdIncludingRemoved(VM_ID, CURRENT_OFFERING_ID)).thenReturn(current);
        when(accountManager.getActiveAccountById(ACCOUNT_ID)).thenReturn(owner);
        when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(template);
        when(dcDao.findById(ZONE_ID)).thenReturn(zone);
        when(diskOfferingDao.findById(diskOfferingId)).thenReturn(rootDiskOffering);
        when(vmDao.findById(VM_ID)).thenReturn(upgradedVm);
        return newOffering;
    }

    private VMInstanceVO stubRunningScaleReady(ServiceOfferingVO newOffering, ServiceOfferingVO currentOffering) {
        return stubRunningVmWithHostTags(true, newOffering, currentOffering);
    }

    private VMInstanceVO stubRunningVmWithHostTags(boolean tagsPass, ServiceOfferingVO newOffering,
            ServiceOfferingVO currentOffering) {
        return stubRunningVmWithHostTags(tagsPass, newOffering, currentOffering, HypervisorType.XenServer);
    }

    private VMInstanceVO stubRunningVmWithHostTags(boolean tagsPass, ServiceOfferingVO newOffering,
            ServiceOfferingVO currentOffering, HypervisorType hypervisorType) {
        VMInstanceVO vm = stubVm(VM_ID, State.Running, hypervisorType);
        when(serviceOfferingDao.findById(NEW_OFFERING_ID)).thenReturn(newOffering);
        AccountVO owner = ownerAccount();
        VMTemplateVO template = template();
        Long diskOfferingId = newOffering.getDiskOfferingId();
        DiskOfferingVO rootDiskOffering = diskOffering();
        when(serviceOfferingDao.findByIdIncludingRemoved(VM_ID, CURRENT_OFFERING_ID)).thenReturn(currentOffering);
        when(accountDao.findById(ACCOUNT_ID)).thenReturn(owner);
        when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(template);
        HostVO host = host(tagsPass);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        when(diskOfferingDao.findById(diskOfferingId)).thenReturn(rootDiskOffering);
        this.newOffering = newOffering;
        this.currentOffering = currentOffering;
        return vm;
    }

    private HostVO host(boolean tagsPass) {
        HostVO host = mock(HostVO.class);
        lenient().when(host.getId()).thenReturn(HOST_ID);
        lenient().when(host.getClusterId()).thenReturn(CLUSTER_ID);
        lenient().when(host.checkHostServiceOfferingAndTemplateTags(any(ServiceOfferingVO.class), any(VMTemplateVO.class), any()))
                .thenReturn(tagsPass);
        lenient().when(host.getHostServiceOfferingAndTemplateMissingTags(any(ServiceOfferingVO.class), any(VMTemplateVO.class), any()))
                .thenReturn(Collections.emptySet());
        return host;
    }

    private void stubExistingHostCapacity(boolean hasCapacity) {
        HostVO host = host(true);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        apiDbUtilsMock = mockStatic(ApiDBUtils.class);
        apiDbUtilsMock.when(() -> ApiDBUtils.findHostById(HOST_ID)).thenReturn(host);
        lenient().when(capacityManager.checkIfClusterCrossesThreshold(eq(CLUSTER_ID), anyInt(), anyLong())).thenReturn(false);
        lenient().when(capacityManager.checkIfHostHasCpuCapability(eq(host), anyInt(), anyInt())).thenReturn(hasCapacity);
        lenient().when(capacityManager.checkIfHostHasCapacity(eq(host), anyInt(), anyLong(), eq(false), anyFloat(), anyFloat(), eq(false)))
                .thenReturn(hasCapacity);
        lenient().when(capacityManager.getClusterOverProvisioningFactor(eq(CLUSTER_ID), anyShort())).thenReturn(1.0f);
    }

    private VirtualMachine stubEntityVm(HypervisorType hypervisorType) {
        VirtualMachine vm = mock(VirtualMachine.class);
        lenient().when(vm.getUuid()).thenReturn(VM_UUID);
        lenient().when(vm.getName()).thenReturn("vm-name");
        lenient().when(vm.getHypervisorType()).thenReturn(hypervisorType);
        return vm;
    }

    private UserVmVO userVm() {
        UserVmVO userVm = mock(UserVmVO.class);
        lenient().when(userVm.getId()).thenReturn(VM_ID);
        lenient().when(userVm.getState()).thenReturn(State.Stopped);
        lenient().when(userVm.isDisplayVm()).thenReturn(true);
        return userVm;
    }

    private AccountVO ownerAccount() {
        AccountVO owner = mock(AccountVO.class);
        lenient().when(owner.getAccountId()).thenReturn(ACCOUNT_ID);
        return owner;
    }

    private VMTemplateVO template() {
        return mock(VMTemplateVO.class);
    }

    private DiskOfferingVO diskOffering() {
        if (diskOffering != null) {
            return diskOffering;
        }
        diskOffering = mock(DiskOfferingVO.class);
        lenient().when(diskOffering.getId()).thenReturn(DISK_OFFERING_ID);
        return diskOffering;
    }

    private ServiceOfferingVO currentOffering() {
        if (currentOffering != null) {
            return currentOffering;
        }
        ServiceOfferingVO current = stubOffering(CURRENT_OFFERING_ID, 1, 1000, 1024, true, true);
        reset(current);
        lenient().when(current.getId()).thenReturn(CURRENT_OFFERING_ID);
        lenient().when(current.getUuid()).thenReturn("offering-" + CURRENT_OFFERING_ID);
        lenient().when(current.getState()).thenReturn(ServiceOffering.State.Active);
        lenient().when(current.getCpu()).thenReturn(1);
        lenient().when(current.getSpeed()).thenReturn(1000);
        lenient().when(current.getRamSize()).thenReturn(1024);
        lenient().when(current.isDynamic()).thenReturn(true);
        lenient().when(current.isDynamicScalingEnabled()).thenReturn(true);
        lenient().when(current.getDiskOfferingId()).thenReturn(DISK_OFFERING_ID);
        lenient().when(current.getDetails()).thenReturn(new HashMap<>());
        currentOffering = current;
        return currentOffering;
    }

    private ServiceOfferingVO newOffering() {
        return newOffering;
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
