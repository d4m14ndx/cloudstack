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

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.Outcome;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.RebootAnswer;
import com.cloud.agent.api.RebootCommand;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.manager.Commands;
import com.cloud.dc.DataCenter;
import com.cloud.dc.Pod;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.security.SecurityGroupManager;
import com.cloud.org.Cluster;
import com.cloud.resource.ResourceManager;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshotManager;

@RunWith(MockitoJUnitRunner.class)
public class VmRebootOrchestrationServiceImplTest {

    private static final String VM_UUID = "vm-uuid";
    private static final long VM_ID = 42L;
    private static final long HOST_ID = 7L;
    private static final long DATA_CENTER_ID = 8L;
    private static final long CLUSTER_ID = 9L;
    private static final long POD_ID = 10L;

    @Spy
    @InjectMocks
    private VmRebootOrchestrationServiceImpl service = new VmRebootOrchestrationServiceImpl();

    @Mock
    private AgentManager agentMgr;
    @Mock
    private VMInstanceDao vmDao;
    @Mock
    private VMSnapshotManager vmSnapshotMgr;
    @Mock
    private EntityManager entityMgr;
    @Mock
    private HostDao hostDao;
    @Mock
    private VmWorkJobQueueService vmWorkJobQueueService;
    @Mock
    private VmCommandSpecPostProcessingService vmCommandSpecPostProcessingService;
    @Mock
    private VmExternalProvisioningManager vmExternalProvisioningManager;
    @Mock
    private SecurityGroupManager securityGroupManager;
    @Mock
    private ResourceManager resourceMgr;
    @Mock
    private VMInstanceVO vm;
    @Mock
    private HostVO host;
    @Mock
    private VirtualMachineTO vmTo;

    @Test
    public void advanceRebootDispatchesThroughJobQueueWhenNotAlreadyInWorkJob()
            throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {
        AsyncJobExecutionContext jobContext = mock(AsyncJobExecutionContext.class);
        Outcome<VirtualMachine> outcome = mock(Outcome.class);
        Map<VirtualMachineProfile.Param, Object> params = Map.of(VirtualMachineProfile.Param.BootIntoSetup, Boolean.TRUE);
        when(jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)).thenReturn(false);
        when(vmWorkJobQueueService.rebootVmThroughJobQueue(VM_UUID, params)).thenReturn(outcome);

        try (MockedStatic<AsyncJobExecutionContext> context = mockStatic(AsyncJobExecutionContext.class)) {
            context.when(AsyncJobExecutionContext::getCurrentExecutionContext).thenReturn(jobContext);

            service.advanceReboot(VM_UUID, params);
        }

        verify(vmWorkJobQueueService).retrieveVmFromJobOutcome(outcome, VM_UUID, "rebootVm");
        verify(vmWorkJobQueueService).retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
    }

    @Test
    public void advanceRebootCreatesAndExpungesPlaceholderWhenAlreadyInWorkJob()
            throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {
        AsyncJobExecutionContext jobContext = mock(AsyncJobExecutionContext.class);
        VmWorkJobVO placeholder = new VmWorkJobVO("");
        when(jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)).thenReturn(true);
        when(vmDao.findByUuid(VM_UUID)).thenReturn(vm);
        when(vm.getId()).thenReturn(VM_ID);
        when(vmWorkJobQueueService.createPlaceHolderWork(VM_ID)).thenReturn(placeholder);
        doNothing().when(service).orchestrateReboot(VM_UUID, null);

        try (MockedStatic<AsyncJobExecutionContext> context = mockStatic(AsyncJobExecutionContext.class)) {
            context.when(AsyncJobExecutionContext::getCurrentExecutionContext).thenReturn(jobContext);

            service.advanceReboot(VM_UUID, null);
        }

        verify(service).orchestrateReboot(VM_UUID, null);
        verify(vmWorkJobQueueService).expungePlaceHolderWork(placeholder);
    }

    @Test
    public void rebootWrapsConcurrentOperationException() throws Exception {
        doAnswer(invocation -> {
            throw new ConcurrentOperationException("busy");
        }).when(service).advanceReboot(VM_UUID, null);

        assertThrows(CloudRuntimeException.class, () -> service.reboot(VM_UUID, null));
    }

    @Test
    public void orchestrateRebootSendsCommandAndSchedulesSecurityGroupRefresh() throws Exception {
        Map<VirtualMachineProfile.Param, Object> params = Map.of(VirtualMachineProfile.Param.BootIntoSetup, Boolean.TRUE);
        prepareVmAndHost();
        when(securityGroupManager.isVmSecurityGroupEnabled(VM_ID)).thenReturn(true);
        doReturn(vmTo).when(service).getVmTO(VM_ID);
        doReturn(false).when(service).getExecuteInSequence(HypervisorType.KVM);
        doAnswer(invocation -> {
            Commands cmds = invocation.getArgument(1);
            RebootCommand command = cmds.getCommand(RebootCommand.class);
            cmds.setAnswers(new Answer[] {new RebootAnswer(command, "ok", true)});
            return null;
        }).when(agentMgr).send(eq(HOST_ID), any(Commands.class));

        service.orchestrateReboot(VM_UUID, params);

        ArgumentCaptor<RebootCommand> rebootCommandCaptor = ArgumentCaptor.forClass(RebootCommand.class);
        verify(vmCommandSpecPostProcessingService).setEnterSetupMode(vmTo, params);
        verify(vmExternalProvisioningManager).updateRebootCommandWithExternalDetails(eq(host), eq(vmTo), rebootCommandCaptor.capture());
        assertSame(vmTo, rebootCommandCaptor.getValue().getVirtualMachine());
        verify(securityGroupManager).scheduleRulesetUpdateToHosts(eq(List.of(VM_ID)), eq(true), eq(null));
        verify(resourceMgr, never()).updateGPUDetailsForVmStart(anyLong(), anyLong(), any());
    }

    @Test
    public void orchestrateRebootRejectsActiveSnapshotTasks() throws Exception {
        when(vmDao.findByUuid(VM_UUID)).thenReturn(vm);
        when(vm.getId()).thenReturn(VM_ID);
        when(vmSnapshotMgr.hasActiveVMSnapshotTasks(VM_ID)).thenReturn(true);

        assertThrows(CloudRuntimeException.class, () -> service.orchestrateReboot(VM_UUID, null));

        verify(agentMgr, never()).send(any(), any(Commands.class));
    }

    @Test
    public void orchestrateRebootFailsWhenHostCannotBeResolved() throws Exception {
        when(vmDao.findByUuid(VM_UUID)).thenReturn(vm);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getHostId()).thenReturn(HOST_ID);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class, () -> service.orchestrateReboot(VM_UUID, null));

        org.junit.Assert.assertEquals("Unable to retrieve host with id " + HOST_ID, exception.getMessage());
        verify(agentMgr, never()).send(any(), any(Commands.class));
    }

    private void prepareVmAndHost() {
        DataCenter dataCenter = mock(DataCenter.class);
        Cluster cluster = mock(Cluster.class);
        Pod pod = mock(Pod.class);
        when(vmDao.findByUuid(VM_UUID)).thenReturn(vm);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getHostId()).thenReturn(HOST_ID);
        when(vm.getDataCenterId()).thenReturn(DATA_CENTER_ID);
        when(vm.getInstanceName()).thenReturn("i-42-VM");
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        when(host.getId()).thenReturn(HOST_ID);
        when(host.getClusterId()).thenReturn(CLUSTER_ID);
        when(host.getPodId()).thenReturn(POD_ID);
        when(entityMgr.findById(DataCenter.class, DATA_CENTER_ID)).thenReturn(dataCenter);
        when(entityMgr.findById(Cluster.class, CLUSTER_ID)).thenReturn(cluster);
        when(entityMgr.findById(Pod.class, POD_ID)).thenReturn(pod);
    }
}
