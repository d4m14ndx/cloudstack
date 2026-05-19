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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
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
import com.cloud.agent.api.ScaleVmCommand;
import com.cloud.alert.AlertManager;
import com.cloud.capacity.CapacityManager;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.event.EventTypes;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.offering.ServiceOffering;
import com.cloud.org.Cluster;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmScaleReconfigurationServiceImplTest {

    private static final String VM_UUID = "vm-uuid";
    private static final long VM_ID = 42L;
    private static final long SRC_HOST_ID = 7L;
    private static final long DST_HOST_ID = 8L;
    private static final long OLD_OFFERING_ID = 11L;
    private static final long NEW_OFFERING_ID = 12L;
    private static final long DATA_CENTER_ID = 13L;
    private static final long POD_ID = 14L;
    private static final long SRC_CLUSTER_ID = 15L;
    private static final long DST_CLUSTER_ID = 16L;

    @Spy
    @InjectMocks
    private VmScaleReconfigurationServiceImpl service = new VmScaleReconfigurationServiceImpl();

    @Mock
    private VMInstanceDao vmDao;
    @Mock
    private ServiceOfferingDao offeringDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private DeploymentPlanningManager dpMgr;
    @Mock
    private AgentManager agentMgr;
    @Mock
    private NetworkOrchestrationService networkMgr;
    @Mock
    private VolumeOrchestrationService volumeMgr;
    @Mock
    private ItWorkDao workDao;
    @Mock
    private AlertManager alertMgr;
    @Mock
    private HypervisorGuruManager hvGuruMgr;
    @Mock
    private UserVmManager userVmMgr;
    @Mock
    private CapacityManager capacityMgr;
    @Mock
    private VmWorkJobQueueService vmWorkJobQueueService;
    @Mock
    private VmServiceOfferingUpgradeManager vmServiceOfferingUpgradeManager;
    @Mock
    private VmScaleReconfigurationActions vmScaleReconfigurationActions;
    @Mock
    private VMInstanceVO vm;
    @Mock
    private HostVO sourceHost;
    @Mock
    private HostVO destHost;
    @Mock
    private DeployDestination dest;
    @Mock
    private ServiceOfferingVO baseOffering;
    @Mock
    private ServiceOfferingVO computedOffering;

    @Test
    public void findHostAndMigrateBuildsDynamicOfferingPlanAndDelegatesToScaleMigration()
            throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException {
        Map<String, String> customParameters = Map.of("cpuNumber", "4");
        DeploymentPlanner.ExcludeList excludes = new DeploymentPlanner.ExcludeList();
        when(vmDao.findByUuid(VM_UUID)).thenReturn(vm);
        when(vm.getHostId()).thenReturn(SRC_HOST_ID);
        when(vm.getServiceOfferingId()).thenReturn(OLD_OFFERING_ID);
        when(vm.getUuid()).thenReturn(VM_UUID);
        when(offeringDao.findById(NEW_OFFERING_ID)).thenReturn(baseOffering);
        when(baseOffering.isDynamic()).thenReturn(true);
        when(offeringDao.getComputeOffering(baseOffering, customParameters)).thenReturn(computedOffering);
        when(hostDao.findById(SRC_HOST_ID)).thenReturn(sourceHost);
        when(sourceHost.getDataCenterId()).thenReturn(DATA_CENTER_ID);
        when(sourceHost.getPodId()).thenReturn(POD_ID);
        when(sourceHost.getClusterId()).thenReturn(SRC_CLUSTER_ID);
        when(dpMgr.planDeployment(any(VirtualMachineProfile.class), any(DataCenterDeployment.class), eq(excludes), isNull())).thenReturn(dest);
        when(dest.getHost()).thenReturn(destHost);
        when(destHost.getId()).thenReturn(DST_HOST_ID);
        doNothing().when(service).migrateForScale(VM_UUID, SRC_HOST_ID, dest, OLD_OFFERING_ID);

        service.findHostAndMigrate(VM_UUID, NEW_OFFERING_ID, customParameters, excludes);

        verify(baseOffering).setDynamicFlag(true);
        verify(offeringDao).getComputeOffering(baseOffering, customParameters);
        verify(vm).setServiceOfferingId(NEW_OFFERING_ID);
        verify(service).migrateForScale(VM_UUID, SRC_HOST_ID, dest, OLD_OFFERING_ID);
    }

    @Test
    public void migrateForScaleDispatchesThroughJobQueueWhenNotAlreadyInWorkJob()
            throws Exception {
        AsyncJobExecutionContext jobContext = mock(AsyncJobExecutionContext.class);
        Outcome<VirtualMachine> outcome = mock(Outcome.class);
        when(jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)).thenReturn(false);
        when(vmWorkJobQueueService.migrateVmForScaleThroughJobQueue(VM_UUID, SRC_HOST_ID, dest, OLD_OFFERING_ID)).thenReturn(outcome);

        try (MockedStatic<AsyncJobExecutionContext> context = mockStatic(AsyncJobExecutionContext.class)) {
            context.when(AsyncJobExecutionContext::getCurrentExecutionContext).thenReturn(jobContext);

            service.migrateForScale(VM_UUID, SRC_HOST_ID, dest, OLD_OFFERING_ID);
        }

        verify(vmWorkJobQueueService).retrieveVmFromJobOutcome(outcome, VM_UUID, "migrateVmForScale");
        verify(vmWorkJobQueueService).retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
    }

    @Test
    public void migrateForScaleCreatesAndExpungesPlaceholderWhenAlreadyInWorkJob()
            throws ResourceUnavailableException, ConcurrentOperationException {
        AsyncJobExecutionContext jobContext = mock(AsyncJobExecutionContext.class);
        VmWorkJobVO placeholder = new VmWorkJobVO("");
        when(jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)).thenReturn(true);
        when(vmDao.findByUuid(VM_UUID)).thenReturn(vm);
        when(vm.getId()).thenReturn(VM_ID);
        when(vmWorkJobQueueService.createPlaceHolderWork(VM_ID)).thenReturn(placeholder);
        doNothing().when(service).orchestrateMigrateForScale(VM_UUID, SRC_HOST_ID, dest, OLD_OFFERING_ID);

        try (MockedStatic<AsyncJobExecutionContext> context = mockStatic(AsyncJobExecutionContext.class)) {
            context.when(AsyncJobExecutionContext::getCurrentExecutionContext).thenReturn(jobContext);

            service.migrateForScale(VM_UUID, SRC_HOST_ID, dest, OLD_OFFERING_ID);
        }

        verify(service).orchestrateMigrateForScale(VM_UUID, SRC_HOST_ID, dest, OLD_OFFERING_ID);
        verify(vmWorkJobQueueService).expungePlaceHolderWork(placeholder);
    }

    @Test
    public void orchestrateMigrateForScaleRejectsDestinationOnDifferentCluster()
            throws ResourceUnavailableException, ConcurrentOperationException {
        Cluster destCluster = mock(Cluster.class);
        when(vmDao.findByUuid(VM_UUID)).thenReturn(vm);
        when(dest.getHost()).thenReturn(destHost);
        when(destHost.getId()).thenReturn(DST_HOST_ID);
        when(hostDao.findById(SRC_HOST_ID)).thenReturn(sourceHost);
        when(hostDao.findById(DST_HOST_ID)).thenReturn(destHost);
        when(sourceHost.getClusterId()).thenReturn(SRC_CLUSTER_ID);
        when(dest.getCluster()).thenReturn(destCluster);
        when(destCluster.getId()).thenReturn(DST_CLUSTER_ID);

        assertThrows(CloudRuntimeException.class,
                () -> service.orchestrateMigrateForScale(VM_UUID, SRC_HOST_ID, dest, OLD_OFFERING_ID));

        verifyNoInteractions(networkMgr, volumeMgr, vmScaleReconfigurationActions);
    }

    @Test
    public void reConfigureVmDispatchesThroughJobQueueWhenNotAlreadyInWorkJob()
            throws Exception {
        AsyncJobExecutionContext jobContext = mock(AsyncJobExecutionContext.class);
        Outcome<VirtualMachine> outcome = mock(Outcome.class);
        ServiceOffering oldOffering = mock(ServiceOffering.class);
        ServiceOffering newOffering = mock(ServiceOffering.class);
        Map<String, String> customParameters = Map.of("memory", "4096");
        when(jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)).thenReturn(false);
        when(vmWorkJobQueueService.reconfigureVmThroughJobQueue(VM_UUID, oldOffering, newOffering, customParameters, true)).thenReturn(outcome);
        when(vmWorkJobQueueService.retrieveVmFromJobOutcome(outcome, VM_UUID, "reconfigureVm")).thenReturn(vm);

        VMInstanceVO result;
        try (MockedStatic<AsyncJobExecutionContext> context = mockStatic(AsyncJobExecutionContext.class)) {
            context.when(AsyncJobExecutionContext::getCurrentExecutionContext).thenReturn(jobContext);

            result = service.reConfigureVm(VM_UUID, oldOffering, newOffering, customParameters, true);
        }

        assertSame(vm, result);
        verify(vmWorkJobQueueService).retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
    }

    @Test
    public void orchestrateReConfigureVmSendsScaleCommandAndUpdatesCapacityOnSameHost() throws Exception {
        ServiceOffering oldOffering = mock(ServiceOffering.class);
        ServiceOffering newOffering = mock(ServiceOffering.class);
        when(vmDao.findByUuid(VM_UUID)).thenReturn(vm);
        when(vm.getHostId()).thenReturn(SRC_HOST_ID);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getUuid()).thenReturn(VM_UUID);
        when(vm.getInstanceName()).thenReturn("i-2-42-VM");
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vm.isDisplayVm()).thenReturn(true);
        when(hostDao.findById(SRC_HOST_ID)).thenReturn(sourceHost);
        when(sourceHost.getClusterId()).thenReturn(SRC_CLUSTER_ID);
        when(oldOffering.getId()).thenReturn(OLD_OFFERING_ID);
        when(newOffering.getId()).thenReturn(NEW_OFFERING_ID);
        when(newOffering.getCpu()).thenReturn(4);
        when(newOffering.getSpeed()).thenReturn(1000);
        when(newOffering.getRamSize()).thenReturn(2048);
        when(newOffering.getLimitCpuUse()).thenReturn(false);
        when(vmScaleReconfigurationActions.getNodeId()).thenReturn(99L);
        when(agentMgr.send(eq(SRC_HOST_ID), any(ScaleVmCommand.class))).thenReturn(new Answer(null, true, null));

        VMInstanceVO result = service.orchestrateReConfigureVm(VM_UUID, oldOffering, newOffering, true);

        ArgumentCaptor<ScaleVmCommand> commandCaptor = ArgumentCaptor.forClass(ScaleVmCommand.class);
        assertSame(vm, result);
        verify(agentMgr).send(eq(SRC_HOST_ID), commandCaptor.capture());
        assertEquals(VM_ID, commandCaptor.getValue().getVirtualMachine().getId());
        assertEquals(VM_UUID, commandCaptor.getValue().getVirtualMachine().getUuid());
        assertEquals(VirtualMachine.Type.User, commandCaptor.getValue().getVirtualMachine().getType());
        verify(vmServiceOfferingUpgradeManager).upgradeVmDb(VM_ID, newOffering, oldOffering);
        verify(userVmMgr).generateUsageEvent(vm, true, EventTypes.EVENT_VM_DYNAMIC_SCALE);
        verify(capacityMgr).releaseVmCapacity(vm, false, false, SRC_HOST_ID);
        verify(capacityMgr).allocateVmCapacity(vm, false);
    }

    @Test
    public void customOfferingDetailWrappersDelegateToUpgradeManager() {
        ServiceOffering offering = mock(ServiceOffering.class);

        service.removeCustomOfferingDetails(VM_ID);
        service.saveCustomOfferingDetails(VM_ID, offering);

        verify(vmServiceOfferingUpgradeManager).removeCustomOfferingDetails(VM_ID);
        verify(vmServiceOfferingUpgradeManager).saveCustomOfferingDetails(VM_ID, offering);
    }
}
