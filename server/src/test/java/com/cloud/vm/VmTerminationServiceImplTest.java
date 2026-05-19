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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.api.command.user.vm.DestroyVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.cloud.entity.api.VirtualMachineEntity;
import org.apache.cloudstack.engine.service.api.OrchestrationService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.network.as.AutoScaleManager;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.dao.UserDao;
import com.cloud.uservm.UserVm;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class VmTerminationServiceImplTest {

    private static final long USER_ID = 10L;
    private static final long VM_ID = 20L;
    private static final long VOLUME_ID = 30L;
    private static final long DOMAIN_ID = 40L;

    @InjectMocks
    private VmTerminationServiceImpl service;

    @Mock
    private UserVmDao vmDao;
    @Mock
    private UserDao userDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private AccountManager accountManager;
    @Mock
    private OrchestrationService orchestrationService;
    @Mock
    private AutoScaleManager autoScaleManager;
    @Mock
    private VmDestroyPermissionService vmDestroyPermissionService;
    @Mock
    private VmVolumeLifecycleValidationService vmVolumeLifecycleValidationService;
    @Mock
    private VmVolumeDestroyCleanupService vmVolumeDestroyCleanupService;
    @Mock
    private VmTerminationService.ManagerOperations managerOperations;
    @Mock
    private DestroyVMCmd destroyVmCmd;
    @Mock
    private UserVmVO vm;
    @Mock
    private UserVm destroyedVm;
    @Mock
    private VolumeVO volume;
    @Mock
    private VolumeVO dataVolume;
    @Mock
    private Account callingAccount;
    @Mock
    private CallContext callContext;
    @Mock
    private VirtualMachineEntity virtualMachineEntity;

    @Test
    public void destroyVmCommandOrchestratesStopDestroyDetachAndVolumeCleanup() throws Exception {
        when(destroyVmCmd.getId()).thenReturn(VM_ID);
        when(destroyVmCmd.getExpunge()).thenReturn(false);
        when(destroyVmCmd.getVolumeIds()).thenReturn(List.of(VOLUME_ID));
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getState()).thenReturn(State.Running);
        when(vm.getDomainId()).thenReturn(DOMAIN_ID);
        when(vm.getUserVmType()).thenReturn("User");
        when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
        when(volumeDao.findByInstanceAndType(VM_ID, Volume.Type.DATADISK)).thenReturn(List.of(dataVolume));
        when(managerOperations.stopVirtualMachine(eq(VM_ID), anyBoolean())).thenReturn(vm);
        when(managerOperations.destroyVm(VM_ID, false)).thenReturn(destroyedVm);
        when(managerOperations.getDestroyRootVolumeOnVmDestruction(DOMAIN_ID)).thenReturn(false);
        when(callContext.getCallingAccount()).thenReturn(callingAccount);

        try (MockedStatic<CallContext> mockedCallContext = mockStatic(CallContext.class)) {
            mockedCallContext.when(CallContext::current).thenReturn(callContext);

            UserVm result = service.destroyVm(destroyVmCmd, managerOperations);

            assertSame(destroyedVm, result);
            verify(autoScaleManager).checkIfVmActionAllowed(VM_ID);
            verify(vmDestroyPermissionService).checkPluginsIfVmCanBeDestroyed(vm);
            verify(vmVolumeLifecycleValidationService).checkStatusOfVolumeSnapshots(vm, Volume.Type.ROOT);
            verify(vmVolumeLifecycleValidationService).checkForUnattachedVolumes(VM_ID, List.of(volume));
            verify(vmVolumeLifecycleValidationService).validateVolumes(List.of(volume));
            verify(accountManager).checkAccess(eq(callingAccount), eq(null), eq(true), any(ControlledEntity[].class));
            verify(managerOperations).stopVirtualMachine(VM_ID, false);
            verify(vmVolumeDestroyCleanupService).detachVolumesFromVm(vm, List.of(dataVolume));
            verify(managerOperations).destroyVm(VM_ID, false);
            verify(autoScaleManager).removeVmFromVmGroup(VM_ID);
            verify(vmVolumeDestroyCleanupService).deleteVolumesFromVm(vm, List.of(volume), false);
            verifyNoInteractions(virtualMachineEntity);
        }
    }

    @Test
    public void stopVirtualMachineUsesForcedStopWhenRequested() throws Exception {
        ReflectionTestUtils.setField(service, "orchestrationService", orchestrationService);
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getUuid()).thenReturn("vm-uuid");
        when(callContext.getCallingAccount()).thenReturn(callingAccount);
        when(callContext.getCallingUserId()).thenReturn(USER_ID);
        when(orchestrationService.getVirtualMachine("vm-uuid")).thenReturn(virtualMachineEntity);
        when(virtualMachineEntity.stopForced(Long.toString(USER_ID))).thenReturn(true);

        try (MockedStatic<CallContext> mockedCallContext = mockStatic(CallContext.class)) {
            mockedCallContext.when(CallContext::current).thenReturn(callContext);

            UserVm result = service.stopVirtualMachine(VM_ID, true);

            assertSame(vm, result);
            verify(vmDestroyPermissionService).checkForceStopVmPermission(callingAccount);
            verify(autoScaleManager).checkIfVmActionAllowed(VM_ID);
            verify(virtualMachineEntity).stopForced(Long.toString(USER_ID));
        }
    }
}
