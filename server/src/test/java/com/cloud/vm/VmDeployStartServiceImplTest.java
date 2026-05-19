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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.vm.DeployVMCmdByAdmin;
import org.apache.cloudstack.api.command.user.vm.DeployVMCmd;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

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
import com.cloud.vm.VirtualMachine.Event;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineProfile.Param;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@RunWith(MockitoJUnitRunner.class)
public class VmDeployStartServiceImplTest {

    private static final long VM_ID = 42L;
    private static final long POD_ID = 6L;
    private static final long CLUSTER_ID = 7L;
    private static final long HOST_ID = 8L;
    private static final long TEMPLATE_ID = 9L;
    private static final long SERVICE_OFFERING_ID = 10L;
    private static final long VOLUME_ID = 11L;
    private static final long DISK_OFFERING_ID = 12L;
    private static final long ACCOUNT_ID = 13L;
    private static final long DATA_CENTER_ID = 14L;

    @Mock
    private EntityManager entityManager;
    @Mock
    private HostDao hostDao;
    @Mock
    private ServiceOfferingDao serviceOfferingDao;
    @Mock
    private VMTemplateDao templateDao;
    @Mock
    private UserVmDao vmDao;
    @Mock
    private VolumeDao volsDao;
    @Mock
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Mock
    private VirtualMachineManager virtualMachineManager;
    @Mock
    private AlertManager alertManager;
    @Mock
    private VolumeService volumeService;
    @Mock
    private VolumeOrchestrationService volumeOrchestrationService;
    @Mock
    private VmDeployStartService.ManagerOperations managerOperations;

    private VmDeployStartServiceImpl service;

    @Before
    public void setUp() {
        service = new VmDeployStartServiceImpl();
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "hostDao", hostDao);
        ReflectionTestUtils.setField(service, "serviceOfferingDao", serviceOfferingDao);
        ReflectionTestUtils.setField(service, "templateDao", templateDao);
        ReflectionTestUtils.setField(service, "vmDao", vmDao);
        ReflectionTestUtils.setField(service, "volsDao", volsDao);
        ReflectionTestUtils.setField(service, "vmInstanceDetailsDao", vmInstanceDetailsDao);
        ReflectionTestUtils.setField(service, "virtualMachineManager", virtualMachineManager);
        ReflectionTestUtils.setField(service, "alertManager", alertManager);
        ReflectionTestUtils.setField(service, "volumeService", volumeService);
        ReflectionTestUtils.setField(service, "volumeOrchestrationService", volumeOrchestrationService);
    }

    @Test
    public void addVmUefiBootOptionsToParamsSetsUefiBootParams() {
        Map<Param, Object> params = new HashMap<>();

        service.addVmUefiBootOptionsToParams(params, ApiConstants.BootType.UEFI.toString(), ApiConstants.BootMode.SECURE.toString());

        assertSame("Yes", params.get(Param.UefiFlag));
        assertSame(ApiConstants.BootType.UEFI.toString(), params.get(Param.BootType));
        assertSame(ApiConstants.BootMode.SECURE.toString(), params.get(Param.BootMode));
    }

    @Test
    public void deployCommandWithStartVmFalseReturnsExistingVm() throws Exception {
        DeployVMCmd cmd = mock(DeployVMCmd.class);
        UserVm existingVm = mock(UserVm.class);
        when(cmd.getEntityId()).thenReturn(VM_ID);
        when(cmd.getStartVm()).thenReturn(false);
        when(managerOperations.getUserVm(VM_ID)).thenReturn(existingVm);

        UserVm result = service.startVirtualMachine(cmd, managerOperations);

        assertSame(existingVm, result);
        verify(managerOperations).getUserVm(VM_ID);
        verifyNoInteractions(vmInstanceDetailsDao);
    }

    @Test
    public void deployCommandBuildsAdminPlacementAndStartParams()
            throws ResourceUnavailableException, InsufficientCapacityException, ConcurrentOperationException, ResourceAllocationException {
        DeployVMCmdByAdmin cmd = mock(DeployVMCmdByAdmin.class);
        Map<Long, DiskOffering> diskOfferingMap = new HashMap<>();
        UserVmVO vm = runningVm();
        VMTemplateVO template = passwordEnabledTemplate();
        Map<Param, Object> returnedParams = new HashMap<>();
        returnedParams.put(Param.VmPassword, "generated-password");

        when(cmd.getEntityId()).thenReturn(VM_ID);
        when(cmd.getStartVm()).thenReturn(true);
        when(cmd.getHostId()).thenReturn(HOST_ID);
        when(cmd.getPodId()).thenReturn(POD_ID);
        when(cmd.getClusterId()).thenReturn(CLUSTER_ID);
        when(cmd.getDataDiskTemplateToDiskOfferingMap()).thenReturn(diskOfferingMap);
        when(cmd.getBootIntoSetup()).thenReturn(Boolean.TRUE);
        when(cmd.getPassword()).thenReturn("requested-password");
        when(cmd.getDeploymentPlanner()).thenReturn("planner");
        when(vmInstanceDetailsDao.findDetail(VM_ID, ApiConstants.BootType.UEFI.toString()))
                .thenReturn(new VMInstanceDetailVO(VM_ID, ApiConstants.BootType.UEFI.toString(), ApiConstants.BootMode.SECURE.toString(), true));
        when(managerOperations.startVirtualMachine(eq(VM_ID), eq(POD_ID), eq(CLUSTER_ID), eq(HOST_ID), anyMap(), eq("planner")))
                .thenReturn(new Pair<>(vm, returnedParams));
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(template);

        UserVm result = service.startVirtualMachine(cmd, managerOperations);

        assertSame(vm, result);
        ArgumentCaptor<Map<Param, Object>> paramsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(managerOperations).startVirtualMachine(eq(VM_ID), eq(POD_ID), eq(CLUSTER_ID), eq(HOST_ID), paramsCaptor.capture(), eq("planner"));
        Map<Param, Object> params = paramsCaptor.getValue();
        assertSame(Boolean.TRUE, params.get(Param.BootIntoSetup));
        assertSame("requested-password", params.get(Param.VmPassword));
        assertSame("Yes", params.get(Param.UefiFlag));
        assertSame(ApiConstants.BootType.UEFI.toString(), params.get(Param.BootType));
        assertSame(ApiConstants.BootMode.SECURE.toString(), params.get(Param.BootMode));
        verify(vm).setPassword("generated-password");
    }

    @Test
    public void deployStartResizesDataDisksAfterVmReachesRunning()
            throws ResourceUnavailableException, InsufficientCapacityException, ConcurrentOperationException, ResourceAllocationException {
        Map<Long, DiskOffering> diskOfferingMap = new HashMap<>();
        diskOfferingMap.put(99L, mock(DiskOffering.class));
        Map<Param, Object> additionalParams = new HashMap<>();
        additionalParams.put(Param.VmPassword, "api-password");
        UserVmVO vm = runningVm();
        VMTemplateVO template = passwordEnabledTemplate();
        VolumeVO dataVolume = mock(VolumeVO.class);
        DiskOffering diskOffering = mock(DiskOffering.class);

        when(managerOperations.startVirtualMachine(eq(VM_ID), eq(POD_ID), eq(CLUSTER_ID), eq(HOST_ID), eq(additionalParams), eq("planner")))
                .thenReturn(new Pair<>(vm, additionalParams));
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        when(volsDao.findByInstance(VM_ID)).thenReturn(List.of(dataVolume));
        when(dataVolume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        when(dataVolume.getDiskOfferingId()).thenReturn(DISK_OFFERING_ID);
        when(dataVolume.getId()).thenReturn(VOLUME_ID);
        when(entityManager.findById(DiskOffering.class, DISK_OFFERING_ID)).thenReturn(diskOffering);
        when(diskOffering.getDiskSize()).thenReturn(512L);
        when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(template);

        UserVm result = service.startVirtualMachine(VM_ID, POD_ID, CLUSTER_ID, HOST_ID, diskOfferingMap, additionalParams, "planner", managerOperations);

        assertSame(vm, result);
        verify(volumeService).resizeVolumeOnHypervisor(VOLUME_ID, 512L, HOST_ID, "i-42");
        verify(vm).setPassword("api-password");
    }

    @Test
    public void updateVmStateForFailedCreationDestroysUsableVolumesAndDecrementsResources()
            throws Exception {
        UserVmVO vm = vmWithState(State.Stopped);
        HostVO host = mock(HostVO.class);
        VolumeVO usableVolume = mock(VolumeVO.class);
        VolumeVO destroyedVolume = mock(VolumeVO.class);
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        VMTemplateVO template = mock(VMTemplateVO.class);

        when(vmDao.findById(VM_ID)).thenReturn(vm);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        when(volsDao.findUsableVolumesForInstance(VM_ID)).thenReturn(List.of(usableVolume, destroyedVolume));
        when(usableVolume.getState()).thenReturn(Volume.State.Ready);
        when(destroyedVolume.getState()).thenReturn(Volume.State.Destroy);
        when(serviceOfferingDao.findById(VM_ID, SERVICE_OFFERING_ID)).thenReturn(offering);
        when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(template);

        service.updateVmStateForFailedVmCreation(VM_ID, HOST_ID, managerOperations);

        verify(virtualMachineManager).stateTransitTo(vm, Event.OperationFailedToError, null);
        verify(volumeOrchestrationService).destroyVolume(usableVolume);
        verify(volumeOrchestrationService, never()).destroyVolume(destroyedVolume);
        verify(alertManager).sendAlert(eq(AlertManager.AlertType.ALERT_TYPE_USERVM), eq(DATA_CENTER_ID), eq(POD_ID), anyString(), anyString());
        verify(managerOperations).resourceCountDecrement(ACCOUNT_ID, true, offering, template);
    }

    private UserVmVO runningVm() {
        return vmWithState(State.Running);
    }

    private UserVmVO vmWithState(State state) {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(vm.getServiceOfferingId()).thenReturn(SERVICE_OFFERING_ID);
        when(vm.getState()).thenReturn(state);
        when(vm.getHostId()).thenReturn(HOST_ID);
        when(vm.getInstanceName()).thenReturn("i-42");
        when(vm.getAccountId()).thenReturn(ACCOUNT_ID);
        when(vm.isDisplayVm()).thenReturn(true);
        when(vm.getDataCenterId()).thenReturn(DATA_CENTER_ID);
        when(vm.getPodIdToDeployIn()).thenReturn(POD_ID);
        return vm;
    }

    private VMTemplateVO passwordEnabledTemplate() {
        VMTemplateVO template = mock(VMTemplateVO.class);
        when(template.isEnablePassword()).thenReturn(true);
        return template;
    }
}
