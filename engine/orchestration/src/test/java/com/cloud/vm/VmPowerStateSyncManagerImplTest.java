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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.framework.jobs.dao.VmWorkJobDao;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.utils.cache.SingleCache;

import com.cloud.alert.AlertManager;
import com.cloud.event.ActionEventUtils;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.vm.VirtualMachine.PowerState;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmPowerStateSyncManagerImplTest {

    @InjectMocks
    private VmPowerStateSyncManagerImpl manager;

    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private VmWorkJobDao workJobDao;
    @Mock
    private HighAvailabilityManager haMgr;
    @Mock
    private HostDao hostDao;
    @Mock
    private AlertManager alertMgr;
    @Mock
    private VmStateMachineActions vmStateMachineActions;

    private static final long VM_ID = 42L;
    private static final long HOST_ID = 100L;

    @Before
    public void setUp() {
        // Supply an empty-cache by default; override per-test as needed
        SingleCache<List<Long>> emptyCache = new SingleCache<>(10, Collections::emptyList);
        ReflectionTestUtils.setField(manager, "vmIdsInProgressCache", emptyCache);
        ReflectionTestUtils.setField(manager, "syncTransitioningVmPowerState", true);
    }

    // ──────────────────────────────────────────────────────────────────
    // handlePowerStateReport tests
    // ──────────────────────────────────────────────────────────────────

    @Test
    public void handlePowerStateReport_nullVm_logsAndReturns() throws Exception {
        when(workJobDao.listPendingWorkJobs(VirtualMachine.Type.Instance, VM_ID))
                .thenReturn(Collections.emptyList());
        when(haMgr.hasPendingHaWork(VM_ID)).thenReturn(false);
        when(vmInstanceDao.findById(VM_ID)).thenReturn(null);

        manager.handlePowerStateReport(VM_ID);

        verify(vmStateMachineActions, never()).stateTransitTo(any(), any(), any());
        verify(alertMgr, never()).sendAlert(any(), anyLong(), any(), anyString(), anyString());
    }

    @Test
    public void handlePowerStateReport_pendingWorkJob_resetsCounters() throws Exception {
        VmWorkJobVO pendingJob = mock(VmWorkJobVO.class);
        when(workJobDao.listPendingWorkJobs(VirtualMachine.Type.Instance, VM_ID))
                .thenReturn(List.of(pendingJob));

        manager.handlePowerStateReport(VM_ID);

        verify(vmInstanceDao).resetVmPowerStateTracking(VM_ID);
        verify(vmStateMachineActions, never()).stateTransitTo(any(), any(), any());
    }

    @Test
    public void handlePowerStateReport_pendingHaWork_resetsCounters() {
        when(workJobDao.listPendingWorkJobs(VirtualMachine.Type.Instance, VM_ID))
                .thenReturn(Collections.emptyList());
        when(haMgr.hasPendingHaWork(VM_ID)).thenReturn(true);

        manager.handlePowerStateReport(VM_ID);

        verify(vmInstanceDao).resetVmPowerStateTracking(VM_ID);
    }

    @Test
    public void handlePowerStateReport_routesPowerOnToOnHandler() throws Exception {
        VMInstanceVO vm = mockVm(State.Running, PowerState.PowerOn);
        setupCleanJobsAndHa();
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);

        manager.handlePowerStateReport(VM_ID);

        verify(vmStateMachineActions).stateTransitTo(eq(vm), eq(VirtualMachine.Event.FollowAgentPowerOnReport), any());
    }

    @Test
    public void handlePowerStateReport_routesPowerOffToOffHandler() throws Exception {
        VMInstanceVO vm = mockVm(State.Running, PowerState.PowerOff);
        when(vm.isHaEnabled()).thenReturn(false);
        org.mockito.Mockito.lenient().when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        setupCleanJobsAndHa();
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);
        when(vmStateMachineActions.sendStop(any(), any(), anyBoolean(), anyBoolean())).thenReturn(true);

        try (MockedStatic<ActionEventUtils> mocked = mockStatic(ActionEventUtils.class)) {
            manager.handlePowerStateReport(VM_ID);
        }

        verify(vmStateMachineActions).stateTransitTo(eq(vm), eq(VirtualMachine.Event.FollowAgentPowerOffReport), eq(null));
    }

    @Test
    public void handlePowerStateReport_routesPowerReportMissingToOffHandler() throws Exception {
        VMInstanceVO vm = mockVm(State.Running, PowerState.PowerReportMissing);
        when(vm.isHaEnabled()).thenReturn(false);
        org.mockito.Mockito.lenient().when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        setupCleanJobsAndHa();
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);

        try (MockedStatic<ActionEventUtils> mocked = mockStatic(ActionEventUtils.class)) {
            manager.handlePowerStateReport(VM_ID);
        }

        // PowerReportMissing path: releaseVmResources called, sendStop NOT called
        verify(vmStateMachineActions).releaseVmResources(any(), eq(true));
        verify(vmStateMachineActions, never()).sendStop(any(), any(), anyBoolean(), anyBoolean());
    }

    // ──────────────────────────────────────────────────────────────────
    // handlePowerOnReportWithNoPendingJobsOnVM tests
    // ──────────────────────────────────────────────────────────────────

    @Test
    public void handlePowerOnReport_startingState_transitionsAndAlerts() throws Exception {
        VMInstanceVO vm = mockVm(State.Starting, PowerState.PowerOn);

        manager.handlePowerOnReportWithNoPendingJobsOnVM(vm);

        verify(vmStateMachineActions).stateTransitTo(eq(vm), eq(VirtualMachine.Event.FollowAgentPowerOnReport), any());
        verify(alertMgr).sendAlert(eq(AlertManager.AlertType.ALERT_TYPE_SYNC), anyLong(), any(), anyString(), anyString());
    }

    @Test
    public void handlePowerOnReport_runningSameHost_singleTransitionNoAlert() throws Exception {
        VMInstanceVO vm = mockVm(State.Running, PowerState.PowerOn);
        when(vm.getHostId()).thenReturn(HOST_ID);
        when(vm.getPowerHostId()).thenReturn(HOST_ID);

        manager.handlePowerOnReportWithNoPendingJobsOnVM(vm);

        verify(vmStateMachineActions).stateTransitTo(eq(vm), eq(VirtualMachine.Event.FollowAgentPowerOnReport), eq(HOST_ID));
        verify(alertMgr, never()).sendAlert(any(), anyLong(), any(), anyString(), anyString());
    }

    @Test
    public void handlePowerOnReport_stoppedState_transitionsAlertsAndEmitsActionEvent() throws Exception {
        VMInstanceVO vm = mockVm(State.Stopped, PowerState.PowerOn);

        try (MockedStatic<ActionEventUtils> mocked = mockStatic(ActionEventUtils.class)) {
            manager.handlePowerOnReportWithNoPendingJobsOnVM(vm);

            verify(vmStateMachineActions).stateTransitTo(eq(vm), eq(VirtualMachine.Event.FollowAgentPowerOnReport), any());
            verify(alertMgr).sendAlert(eq(AlertManager.AlertType.ALERT_TYPE_SYNC), anyLong(), any(), anyString(), anyString());
            mocked.verify(() -> ActionEventUtils.onActionEvent(anyLong(), anyLong(), anyLong(), anyString(), anyString(), anyLong(), anyString()));
        }
    }

    @Test
    public void handlePowerOnReport_destroyedState_noop() throws Exception {
        VMInstanceVO vm = mockVm(State.Destroyed, PowerState.PowerOn);

        manager.handlePowerOnReportWithNoPendingJobsOnVM(vm);

        verify(vmStateMachineActions, never()).stateTransitTo(any(), any(), any());
        verify(alertMgr, never()).sendAlert(any(), anyLong(), any(), anyString(), anyString());
    }

    // ──────────────────────────────────────────────────────────────────
    // handlePowerOffReportWithNoPendingJobsOnVM tests
    // ──────────────────────────────────────────────────────────────────

    @Test
    public void handlePowerOffReport_haEnabledRunningKvm_schedulesRestart() throws Exception {
        // ForceHA ConfigKey defaults to false in tests; vm.isHaEnabled()=true satisfies the HA condition
        VMInstanceVO vm = mockVm(State.Running, PowerState.PowerOff);
        when(vm.isHaEnabled()).thenReturn(true);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(haMgr.hasPendingHaWork(VM_ID)).thenReturn(false);

        try (MockedStatic<ActionEventUtils> mocked = mockStatic(ActionEventUtils.class)) {
            manager.handlePowerOffReportWithNoPendingJobsOnVM(vm);
        }

        verify(haMgr).scheduleRestart(vm, true);
        verify(vmStateMachineActions, never()).stateTransitTo(any(), any(), any()); //NOSONAR
    }

    @Test
    public void handlePowerOffReport_powerOff_sendsStopThenReleasesAndTransitions() throws Exception {
        VMInstanceVO vm = mockVm(State.Running, PowerState.PowerOff);
        when(vm.isHaEnabled()).thenReturn(false);
        org.mockito.Mockito.lenient().when(vm.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        when(vmStateMachineActions.getVmGuru(vm)).thenReturn(mock(VirtualMachineGuru.class));
        when(vmStateMachineActions.sendStop(any(), any(), eq(true), eq(true))).thenReturn(true);

        try (MockedStatic<ActionEventUtils> mocked = mockStatic(ActionEventUtils.class)) {
            manager.handlePowerOffReportWithNoPendingJobsOnVM(vm);

            verify(vmStateMachineActions).sendStop(any(), any(), eq(true), eq(true));
            verify(vmStateMachineActions).releaseVmResources(any(), eq(true));
            verify(vmStateMachineActions).stateTransitTo(eq(vm), eq(VirtualMachine.Event.FollowAgentPowerOffReport), eq(null));
            verify(alertMgr).sendAlert(eq(AlertManager.AlertType.ALERT_TYPE_SYNC), anyLong(), any(), anyString(), anyString());
        }
    }

    @Test
    public void handlePowerOffReport_powerReportMissing_releasesOnly() throws Exception {
        VMInstanceVO vm = mockVm(State.Running, PowerState.PowerReportMissing);
        when(vm.isHaEnabled()).thenReturn(false);
        org.mockito.Mockito.lenient().when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);

        try (MockedStatic<ActionEventUtils> mocked = mockStatic(ActionEventUtils.class)) {
            manager.handlePowerOffReportWithNoPendingJobsOnVM(vm);

            verify(vmStateMachineActions, never()).sendStop(any(), any(), anyBoolean(), anyBoolean());
            verify(vmStateMachineActions).releaseVmResources(any(), eq(true));
            verify(vmStateMachineActions).stateTransitTo(eq(vm), eq(VirtualMachine.Event.FollowAgentPowerOffReport), eq(null));
        }
    }

    @Test
    public void handlePowerOffReport_destroyedState_noop() throws Exception {
        VMInstanceVO vm = mockVm(State.Destroyed, PowerState.PowerOff);

        manager.handlePowerOffReportWithNoPendingJobsOnVM(vm);

        verify(vmStateMachineActions, never()).stateTransitTo(any(), any(), any());
        verify(haMgr, never()).scheduleRestart(any(), anyBoolean());
        verify(alertMgr, never()).sendAlert(any(), anyLong(), any(), anyString(), anyString());
    }

    // ──────────────────────────────────────────────────────────────────
    // scanStalledVMInTransitionStateOnUpHost tests
    // ──────────────────────────────────────────────────────────────────

    @Test
    public void scanStalledVMInTransitionStateOnUpHost_syncDisabled_noops() {
        ReflectionTestUtils.setField(manager, "syncTransitioningVmPowerState", false);

        manager.scanStalledVMInTransitionStateOnUpHost(HOST_ID);

        verify(hostDao, never()).isHostUp(anyLong());
    }

    @Test
    public void scanStalledVMInTransitionStateOnUpHost_hostDown_noops() {
        when(hostDao.isHostUp(HOST_ID)).thenReturn(false);

        manager.scanStalledVMInTransitionStateOnUpHost(HOST_ID);

        verify(vmInstanceDao, never()).listByHostAndState(anyLong(), any());
    }

    @Test
    public void scanStalledVMInTransitionStateOnUpHost_stalledVms_treatedAsPoweredOff() throws Exception {
        when(hostDao.isHostUp(HOST_ID)).thenReturn(true);

        // Build a stale VM: powerStateUpdateTime well before the cutTime threshold
        VMInstanceVO staleVm = mockVm(State.Starting, PowerState.PowerOff);
        long oldTime = System.currentTimeMillis() - 200_000L; // older than 2 * 60s default threshold
        when(staleVm.getPowerStateUpdateTime()).thenReturn(new Date(oldTime));

        when(vmInstanceDao.listByHostAndState(eq(HOST_ID), any(), any(), any()))
                .thenReturn(List.of(staleVm));

        // Use spy and stub the inner handler to avoid ActionEventUtils NPE
        VmPowerStateSyncManagerImpl spy = spy(manager);
        org.mockito.Mockito.doNothing().when(spy).handlePowerOffReportWithNoPendingJobsOnVM(staleVm);

        spy.scanStalledVMInTransitionStateOnUpHost(HOST_ID);

        verify(spy).handlePowerOffReportWithNoPendingJobsOnVM(staleVm);
    }

    @Test
    public void scanStalledVMInTransitionStateOnUpHost_recentReport_routesByPowerState() throws Exception {
        when(hostDao.isHostUp(HOST_ID)).thenReturn(true);

        long recentTime = System.currentTimeMillis() + 200_000L; // well into the future = recent

        VMInstanceVO powerOnVm = mockVm(State.Starting, PowerState.PowerOn);
        when(powerOnVm.getPowerStateUpdateTime()).thenReturn(new Date(recentTime));

        VMInstanceVO powerOffVm = mockVm(State.Stopping, PowerState.PowerOff);
        when(powerOffVm.getPowerStateUpdateTime()).thenReturn(new Date(recentTime));

        when(vmInstanceDao.listByHostAndState(eq(HOST_ID), any(), any(), any()))
                .thenReturn(List.of(powerOnVm, powerOffVm));

        VmPowerStateSyncManagerImpl spy = spy(manager);
        org.mockito.Mockito.doNothing().when(spy).handlePowerOnReportWithNoPendingJobsOnVM(powerOnVm);
        org.mockito.Mockito.doNothing().when(spy).handlePowerOffReportWithNoPendingJobsOnVM(powerOffVm);

        spy.scanStalledVMInTransitionStateOnUpHost(HOST_ID);

        verify(spy).handlePowerOnReportWithNoPendingJobsOnVM(powerOnVm);
        verify(spy).handlePowerOffReportWithNoPendingJobsOnVM(powerOffVm);
    }

    // ──────────────────────────────────────────────────────────────────
    // scanStalledVMInTransitionStateOnDisconnectedHosts tests
    // ──────────────────────────────────────────────────────────────────

    @Test
    public void scanStalledVMInTransitionStateOnDisconnectedHosts_emitsAlerts() {
        VmPowerStateSyncManagerImpl spy = spy(manager);

        VMInstanceVO vm1 = mockVm(State.Starting, PowerState.PowerUnknown);
        VMInstanceVO vm2 = mockVm(State.Stopping, PowerState.PowerUnknown);

        // Stub the raw-JDBC helper to avoid DB access
        org.mockito.Mockito.doReturn(List.of(VM_ID, VM_ID + 1))
                .when(spy).listStalledVMInTransitionStateOnDisconnectedHosts(any(Date.class));
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm1);
        when(vmInstanceDao.findById(VM_ID + 1)).thenReturn(vm2);

        spy.scanStalledVMInTransitionStateOnDisconnectedHosts();

        verify(alertMgr, times(2)).sendAlert(
                eq(AlertManager.AlertType.ALERT_TYPE_SYNC),
                anyLong(), any(), anyString(), anyString());
    }

    // ──────────────────────────────────────────────────────────────────
    // getApiCommandResourceTypeForVm branches
    // ──────────────────────────────────────────────────────────────────

    @Test
    public void getApiCommandResourceTypeForVm_branches() {
        assertEquals(ApiCommandResourceType.DomainRouter,
                manager.getApiCommandResourceTypeForVm(mockVmOfType(VirtualMachine.Type.DomainRouter)));
        assertEquals(ApiCommandResourceType.ConsoleProxy,
                manager.getApiCommandResourceTypeForVm(mockVmOfType(VirtualMachine.Type.ConsoleProxy)));
        assertEquals(ApiCommandResourceType.SystemVm,
                manager.getApiCommandResourceTypeForVm(mockVmOfType(VirtualMachine.Type.SecondaryStorageVm)));
        assertEquals(ApiCommandResourceType.VirtualMachine,
                manager.getApiCommandResourceTypeForVm(mockVmOfType(VirtualMachine.Type.User)));
    }

    // ──────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────

    private VMInstanceVO mockVm(State state, PowerState powerState) {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getState()).thenReturn(state);
        when(vm.getPowerState()).thenReturn(powerState);
        when(vm.getDataCenterId()).thenReturn(1L);
        when(vm.getPodIdToDeployIn()).thenReturn(1L);
        when(vm.getHostName()).thenReturn("test-host");
        when(vm.getInstanceName()).thenReturn("i-1-VM");
        when(vm.getDomainId()).thenReturn(1L);
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(vm.getPowerHostId()).thenReturn(HOST_ID);
        when(vm.getPowerStateUpdateTime()).thenReturn(new Date());
        return vm;
    }

    private VirtualMachine mockVmOfType(VirtualMachine.Type type) {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getType()).thenReturn(type);
        return vm;
    }

    private void setupCleanJobsAndHa() {
        when(workJobDao.listPendingWorkJobs(VirtualMachine.Type.Instance, VM_ID))
                .thenReturn(Collections.emptyList());
        when(haMgr.hasPendingHaWork(VM_ID)).thenReturn(false);
    }
}
