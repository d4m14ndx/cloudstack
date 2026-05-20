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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.cloudstack.backup.BackupManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.as.AutoScaleManager;
import com.cloud.storage.Volume;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.uservm.UserVm;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class VmExpungeOrchestrationServiceImplTest {

    private static final long VM_ID = 42L;
    private static final String VM_UUID = "vm-uuid";

    @Mock
    private UserVmDao vmDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private VirtualMachineManager virtualMachineManager;
    @Mock
    private BackupManager backupManager;
    @Mock
    private AutoScaleManager autoScaleManager;
    @Mock
    private VmExpungeResourceCleanupService vmExpungeResourceCleanupService;
    @Mock
    private VmExpungeFailureTransitionService vmExpungeFailureTransitionService;

    private VmExpungeOrchestrationServiceImpl service;

    @Before
    public void setUp() {
        service = new VmExpungeOrchestrationServiceImpl();
        ReflectionTestUtils.setField(service, "vmDao", vmDao);
        ReflectionTestUtils.setField(service, "volumeDao", volumeDao);
        ReflectionTestUtils.setField(service, "virtualMachineManager", virtualMachineManager);
        ReflectionTestUtils.setField(service, "backupManager", backupManager);
        ReflectionTestUtils.setField(service, "autoScaleManager", autoScaleManager);
        ReflectionTestUtils.setField(service, "vmExpungeResourceCleanupService", vmExpungeResourceCleanupService);
        ReflectionTestUtils.setField(service, "vmExpungeFailureTransitionService", vmExpungeFailureTransitionService);
    }

    @Test
    public void expungeReturnsFalseWhenLockCannotBeAcquired() {
        UserVmVO vm = vm(VM_ID);
        when(vmDao.acquireInLockTable(VM_ID)).thenReturn(null);

        assertFalse(service.expunge(vm));

        verify(vmDao, never()).releaseFromLockTable(anyLong());
    }

    @Test
    public void expungeCleansResourcesClearsUserDataAndRemovesVm() throws Exception {
        UserVmVO vm = vm(VM_ID);
        when(vm.getUuid()).thenReturn(VM_UUID);
        when(vm.getRemoved()).thenReturn(null);
        when(vm.getUserDataId()).thenReturn(99L);
        when(vmDao.acquireInLockTable(VM_ID)).thenReturn(vm);
        when(vmExpungeResourceCleanupService.cleanupVmResources(vm)).thenReturn(true);

        assertTrue(service.expunge(vm));

        verify(backupManager).checkAndRemoveBackupOfferingBeforeExpunge(vm);
        verify(autoScaleManager).removeVmFromVmGroup(VM_ID);
        verify(vmExpungeResourceCleanupService).releaseNetworkResourcesOnExpunge(VM_ID);
        verify(volumeDao).findByInstanceAndType(VM_ID, Volume.Type.ROOT);
        verify(virtualMachineManager).advanceExpunge(VM_UUID);
        verify(vm).setUserDataId(null);
        verify(vmDao).update(VM_ID, vm);
        verify(vmDao).remove(VM_ID);
        verify(vmDao).releaseFromLockTable(VM_ID);
    }

    @Test
    public void expungeReturnsFalseWhenCleanupFailsAndStillReleasesLock() throws Exception {
        UserVmVO vm = vm(VM_ID);
        when(vm.getUuid()).thenReturn(VM_UUID);
        when(vm.getRemoved()).thenReturn(null);
        when(vmDao.acquireInLockTable(VM_ID)).thenReturn(vm);
        when(vmExpungeResourceCleanupService.cleanupVmResources(vm)).thenReturn(false);

        assertFalse(service.expunge(vm));

        verify(vmDao, never()).remove(VM_ID);
        verify(vmDao).releaseFromLockTable(VM_ID);
    }

    @Test
    public void expungeReturnsFalseWhenNetworkReleaseFailsAndStillReleasesLock() throws Exception {
        UserVmVO vm = vm(VM_ID);
        when(vmDao.acquireInLockTable(VM_ID)).thenReturn(vm);
        doThrow(new ResourceUnavailableException("network unavailable", UserVm.class, VM_ID))
                .when(vmExpungeResourceCleanupService).releaseNetworkResourcesOnExpunge(VM_ID);

        assertFalse(service.expunge(vm));

        verify(vmDao).releaseFromLockTable(VM_ID);
        verify(virtualMachineManager, never()).advanceExpunge(any());
    }

    @Test
    public void scheduleExpungeTaskUsesConfiguredInterval() {
        ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
        VmExpungeOrchestrationService.ManagerOperations managerOperations = mock(VmExpungeOrchestrationService.ManagerOperations.class);
        service.configure(Map.of("expunge.interval", "15", "expunge.delay", "7"));

        service.scheduleExpungeTask(executor, managerOperations);

        verify(executor).scheduleWithFixedDelay(any(Runnable.class), eq(15L), eq(15L), eq(TimeUnit.SECONDS));
    }

    @Test
    public void runScheduledExpungeContinuesAfterSingleVmFailure() throws Exception {
        UserVmVO firstVm = vm(1L);
        UserVmVO secondVm = vm(2L);
        VmExpungeOrchestrationService.ManagerOperations managerOperations = mock(VmExpungeOrchestrationService.ManagerOperations.class);
        when(vmDao.findDestroyedVms(any())).thenReturn(List.of(firstVm, secondVm));
        doThrow(new ResourceUnavailableException("expunge failed", UserVm.class, 1L)).when(managerOperations).expungeVm(1L);

        service.runScheduledExpunge(managerOperations);

        verify(managerOperations).expungeVm(1L);
        verify(managerOperations).expungeVm(2L);
    }

    @Test
    public void transitionExpungingToErrorDelegatesToFailureTransitionService() {
        service.transitionExpungingToError(VM_ID);

        verify(vmExpungeFailureTransitionService).transitionExpungingToError(VM_ID);
    }

    private UserVmVO vm(long id) {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getId()).thenReturn(id);
        return vm;
    }
}
