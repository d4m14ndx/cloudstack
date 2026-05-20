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

import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.Outcome;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmStopOrchestrationServiceImplTest {

    private static final String VM_UUID = "vm-uuid";
    private static final long VM_ID = 42L;

    @InjectMocks
    private VmStopOrchestrationServiceImpl service;

    @Mock
    private VMInstanceDao vmDao;
    @Mock
    private VmWorkJobQueueService vmWorkJobQueueService;
    @Mock
    private NetworkOrchestrationService networkMgr;
    @Mock
    private VolumeOrchestrationService volumeMgr;
    @Mock
    private VMInstanceVO vm;
    @Mock
    private VirtualMachineManagerImpl virtualMachineManager;

    @Test
    public void advanceStopDispatchesThroughJobQueueWhenNotAlreadyInWorkJob() throws Exception {
        AsyncJobExecutionContext jobContext = mock(AsyncJobExecutionContext.class);
        Outcome<VirtualMachine> outcome = mock(Outcome.class);
        when(jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)).thenReturn(false);
        when(vmWorkJobQueueService.stopVmThroughJobQueue(VM_UUID, true)).thenReturn(outcome);

        try (MockedStatic<AsyncJobExecutionContext> context = mockStatic(AsyncJobExecutionContext.class)) {
            context.when(AsyncJobExecutionContext::getCurrentExecutionContext).thenReturn(jobContext);

            service.advanceStop(VM_UUID, true);
        }

        verify(vmWorkJobQueueService).retrieveVmFromJobOutcome(outcome, VM_UUID, "stopVm");
        verify(vmWorkJobQueueService).retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
    }

    @Test
    public void releaseVmResourcesSkipsStorageReleaseForBareMetal() throws Exception {
        VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(vm.getState()).thenReturn(State.Stopped);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.BareMetal);

        service.releaseVmResources(profile, true);

        verify(networkMgr).release(profile, true);
        verifyNoInteractions(volumeMgr);
    }

    @Test
    public void cleanupUsesManagerSendStopHookForRunningVm() throws Exception {
        VirtualMachineGuru guru = mock(VirtualMachineGuru.class);
        VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        when(profile.getVirtualMachine()).thenReturn(vm);
        when(vm.getState()).thenReturn(State.Running);
        when(virtualMachineManager.sendStop(guru, profile, true, false)).thenReturn(true);

        boolean result = service.cleanup(guru, profile, null, null, true);

        assertTrue(result);
        verify(virtualMachineManager).sendStop(guru, profile, true, false);
        verify(networkMgr).release(profile, true);
    }
}
