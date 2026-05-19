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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.resource.ResourceCleanupService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.Command;
import com.cloud.deployasis.dao.UserVmDeployAsIsDetailsDao;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmExpungeOrchestrationServiceImplTest {

    private static final String VM_UUID = "vm-uuid";
    private static final long VM_ID = 7L;
    private static final long HOST_ID = 42L;

    @InjectMocks
    private VmExpungeOrchestrationServiceImpl service;

    @Mock
    private VMInstanceDao vmDao;
    @Mock
    private UserVmDao userVmDao;
    @Mock
    private HypervisorGuruManager hvGuruMgr;
    @Mock
    private NetworkOrchestrationService networkMgr;
    @Mock
    private VolumeOrchestrationService volumeMgr;
    @Mock
    private VmExpungeCommandService vmExpungeCommandService;
    @Mock
    private UserVmDeployAsIsDetailsDao userVmDeployAsIsDetailsDao;
    @Mock
    private AnnotationDao annotationDao;
    @Mock
    private ResourceCleanupService resourceCleanupService;
    @Mock
    private VmIscsiTargetManager vmIscsiTargetManager;
    @Mock
    private VirtualMachineManager virtualMachineManager;
    @Mock
    private VmStateMachineActions vmStateMachineActions;
    @Mock
    private VMInstanceVO vm;
    @Mock
    private UserVmVO userVm;
    @Mock
    private HypervisorGuru hvGuru;
    @Mock
    private VirtualMachineGuru vmGuru;

    @Test
    public void advanceExpunge_missingVmDoesNothing() throws Exception {
        service.advanceExpunge((VMInstanceVO)null);

        verifyNoInteractions(virtualMachineManager, hvGuruMgr, networkMgr, volumeMgr, vmExpungeCommandService);
    }

    @Test
    public void isVmDestroyed_returnsTrueForRemovedVm() {
        when(vm.getRemoved()).thenReturn(new java.util.Date());

        assertTrue(service.isVmDestroyed(vm));
    }

    @Test
    public void isVmDestroyed_returnsFalseForActiveVm() {
        assertFalse(service.isVmDestroyed(vm));
    }

    @Test
    public void advanceExpunge_externalVmMarksDetailBeforeStop() throws Exception {
        prepareVm(HypervisorType.External, HOST_ID);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);
        prepareSuccessfulExpunge(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        service.advanceExpunge(vm);

        InOrder order = inOrder(userVmDao, userVm, virtualMachineManager);
        order.verify(userVmDao).loadDetails(userVm);
        order.verify(userVm).setDetail(VmDetailConstants.EXPUNGE_EXTERNAL_VM, Boolean.TRUE.toString());
        order.verify(userVmDao).saveDetails(userVm);
        order.verify(virtualMachineManager).advanceStop(VM_UUID, VirtualMachineManagerImpl.VmDestroyForcestop.value());
    }

    @Test
    public void advanceExpunge_cleansResourcesAndSendsExpungeCommands() throws Exception {
        prepareVm(HypervisorType.KVM, HOST_ID);
        Command nicCommand = Mockito.mock(Command.class);
        Command volumeCommand = Mockito.mock(Command.class);
        Command finalizeCommand = Mockito.mock(Command.class);
        List<Map<String, String>> targets = List.of(Map.of("iqn", "iqn.2026-05.test"));
        prepareSuccessfulExpunge(List.of(nicCommand), List.of(volumeCommand), List.of(finalizeCommand), targets);

        service.advanceExpunge(vm);

        verify(virtualMachineManager).advanceStop(VM_UUID, VirtualMachineManagerImpl.VmDestroyForcestop.value());
        verify(vmStateMachineActions).stateTransitTo(vm, VirtualMachine.Event.ExpungeOperation, HOST_ID);
        verify(networkMgr).cleanupNics(any(VirtualMachineProfile.class));
        verify(vmExpungeCommandService).sendVolumeExpungeCommands(List.of(volumeCommand), HOST_ID, vm);
        verify(volumeMgr).revokeAccess(VM_ID, HOST_ID);
        verify(volumeMgr).cleanupVolumes(VM_ID);
        verify(vmIscsiTargetManager).removeDynamicTargets(HOST_ID, targets);
        verify(vmGuru).finalizeExpunge(vm);
        verify(userVmDeployAsIsDetailsDao).removeDetails(VM_ID);
        verify(annotationDao).removeByEntityType(AnnotationService.EntityType.VM.name(), VM_UUID);
        verify(vmExpungeCommandService).sendFinalizeExpungeCommands(List.of(finalizeCommand), List.of(nicCommand), vm, HOST_ID);
        verify(resourceCleanupService).purgeExpungedVmResourcesLaterIfNeeded(vm);
    }

    @Test
    public void advanceExpunge_noHostCleansVolumesWithoutRevokingAccessOrRemovingTargets() throws Exception {
        prepareVm(HypervisorType.KVM, null);
        prepareSuccessfulExpunge(Collections.emptyList(), Collections.emptyList(), Collections.emptyList(), Collections.emptyList());

        service.advanceExpunge(vm);

        verify(volumeMgr, never()).revokeAccess(eq(VM_ID), anyLong());
        verify(volumeMgr).cleanupVolumes(VM_ID);
        verify(vmIscsiTargetManager, never()).removeDynamicTargets(anyLong(), any());
    }

    @Test
    public void expunge_concurrentOperation_wrapsExistingMessage() throws Exception {
        prepareVm(HypervisorType.KVM, HOST_ID);
        when(vmDao.findByUuid(VM_UUID)).thenReturn(vm);
        doThrow(new ConcurrentOperationException("busy"))
                .when(virtualMachineManager).advanceStop(VM_UUID, VirtualMachineManagerImpl.VmDestroyForcestop.value());

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class, () -> service.expunge(VM_UUID));

        assertEquals("Concurrent operation ", exception.getMessage());
    }

    @Test
    public void expunge_operationTimedOut_wrapsExistingMessage() throws Exception {
        prepareVm(HypervisorType.KVM, HOST_ID);
        when(vmDao.findByUuid(VM_UUID)).thenReturn(vm);
        doThrow(new OperationTimedoutException(null, HOST_ID, 1L, 1, false))
                .when(virtualMachineManager).advanceStop(VM_UUID, VirtualMachineManagerImpl.VmDestroyForcestop.value());

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class, () -> service.expunge(VM_UUID));

        assertEquals("Operation timed out", exception.getMessage());
    }

    private void prepareVm(HypervisorType hypervisorType, Long hostId) {
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getUuid()).thenReturn(VM_UUID);
        when(vm.getHypervisorType()).thenReturn(hypervisorType);
        when(vm.getHostId()).thenReturn(hostId);
        if (hostId == null) {
            when(vm.getLastHostId()).thenReturn(null);
        }
    }

    private void prepareSuccessfulExpunge(List<Command> nicCommands, List<Command> volumeCommands,
            List<Command> finalizeCommands, List<Map<String, String>> targets) throws Exception {
        when(vmDao.findByUuid(VM_UUID)).thenReturn(vm);
        when(vmStateMachineActions.stateTransitTo(vm, VirtualMachine.Event.ExpungeOperation, vm.getHostId())).thenReturn(true);
        when(hvGuruMgr.getGuru(vm.getHypervisorType())).thenReturn(hvGuru);
        when(hvGuru.finalizeExpungeNics(eq(vm), any())).thenReturn(nicCommands);
        when(hvGuru.finalizeExpungeVolumes(vm)).thenReturn(volumeCommands);
        when(vmIscsiTargetManager.getTargets(vm.getHostId(), VM_ID)).thenReturn(targets);
        when(vmStateMachineActions.getVmGuru(vm)).thenReturn(vmGuru);
        when(hvGuru.finalizeExpunge(vm)).thenReturn(finalizeCommands);
    }
}
