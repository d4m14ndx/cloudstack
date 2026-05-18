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
package com.cloud.resource;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.ha.HighAvailabilityManager.WorkType;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Focused unit tests for {@link HostMaintenanceServiceImpl} — the Phase 4
 * extraction of side-effect-free helpers supporting the host maintenance
 * state machine.
 *
 * <p>Spy-verified orchestration ({@code attemptMaintain},
 * {@code setHostIntoMaintenance}, {@code setKVMVncAccess},
 * {@code resourceStateTransitTo}, {@code handleAgentIfNotConnected},
 * {@code configureVncAccessForKVMHostFailedMigrations}) is intentionally
 * left on {@link ResourceManagerImpl} so existing
 * {@code verify(resourceManager).foo(...)} tests continue to work. Those
 * call paths remain covered by {@code ResourceManagerImplTest}.
 */
@RunWith(MockitoJUnitRunner.class)
public class HostMaintenanceServiceImplTest {

    private static final long HOST_ID = 42L;
    private static final long DC_ID = 7L;
    private static final long POD_ID = 3L;
    private static final long CLUSTER_ID = 5L;
    private static final long VM_ID = 100L;
    private static final long SERVICE_OFFERING_ID = 11L;

    private DataCenterDao dataCenterDao;
    private ServiceOfferingDao serviceOfferingDao;
    private DeploymentPlanningManager deploymentManager;
    private VirtualMachineManager vmManager;
    private HighAvailabilityManager haManager;
    private HostLookupService hostLookupService;
    private VMInstanceDao vmInstanceDao;

    private HostVO host;
    private VMInstanceVO vm;

    private HostMaintenanceServiceImpl service;

    private String originalStrategyValue;

    @Before
    public void setUp() throws Exception {
        dataCenterDao = mock(DataCenterDao.class);
        serviceOfferingDao = mock(ServiceOfferingDao.class);
        deploymentManager = mock(DeploymentPlanningManager.class);
        vmManager = mock(VirtualMachineManager.class);
        haManager = mock(HighAvailabilityManager.class);
        hostLookupService = mock(HostLookupService.class);
        vmInstanceDao = mock(VMInstanceDao.class);

        host = mock(HostVO.class);
        when(host.getId()).thenReturn(HOST_ID);
        when(host.getDataCenterId()).thenReturn(DC_ID);
        when(host.getPodId()).thenReturn(POD_ID);
        when(host.getClusterId()).thenReturn(CLUSTER_ID);

        vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getServiceOfferingId()).thenReturn(SERVICE_OFFERING_ID);
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);

        service = new HostMaintenanceServiceImpl();
        service.dataCenterDao = dataCenterDao;
        service.serviceOfferingDao = serviceOfferingDao;
        service.deploymentManager = deploymentManager;
        service.vmManager = vmManager;
        service.haManager = haManager;
        service.hostLookupService = hostLookupService;
        service.vmInstanceDao = vmInstanceDao;

        originalStrategyValue = ResourceManager.HOST_MAINTENANCE_LOCAL_STRATEGY.value();
    }

    @After
    public void tearDown() throws Exception {
        setStrategyValue(originalStrategyValue);
    }

    private void setStrategyValue(String value) throws Exception {
        Field f = ConfigKey.class.getDeclaredField("_defaultValue");
        f.setAccessible(true);
        f.set(ResourceManager.HOST_MAINTENANCE_LOCAL_STRATEGY, value);
        Field v = ConfigKey.class.getDeclaredField("_value");
        v.setAccessible(true);
        v.set(ResourceManager.HOST_MAINTENANCE_LOCAL_STRATEGY, null);
    }

    // ---- isMaintenanceLocalStrategy* ----

    @Test
    public void isMaintenanceLocalStrategyMigrate_trueWhenMigration() throws Exception {
        setStrategyValue("Migration");
        assertTrue(service.isMaintenanceLocalStrategyMigrate());
        assertFalse(service.isMaintenanceLocalStrategyForceStop());
        assertFalse(service.isMaintenanceLocalStrategyDefault());
    }

    @Test
    public void isMaintenanceLocalStrategyForceStop_trueWhenForceStop() throws Exception {
        setStrategyValue("ForceStop");
        assertTrue(service.isMaintenanceLocalStrategyForceStop());
        assertFalse(service.isMaintenanceLocalStrategyMigrate());
        assertFalse(service.isMaintenanceLocalStrategyDefault());
    }

    @Test
    public void isMaintenanceLocalStrategyDefault_trueWhenBlank() throws Exception {
        setStrategyValue("");
        assertTrue(service.isMaintenanceLocalStrategyDefault());
        assertFalse(service.isMaintenanceLocalStrategyMigrate());
        assertFalse(service.isMaintenanceLocalStrategyForceStop());
    }

    @Test
    public void isMaintenanceLocalStrategyDefault_trueWhenError() throws Exception {
        setStrategyValue("Error");
        assertTrue(service.isMaintenanceLocalStrategyDefault());
    }

    @Test
    public void isMaintenanceLocalStrategy_caseInsensitive() throws Exception {
        setStrategyValue("miGRation");
        assertTrue(service.isMaintenanceLocalStrategyMigrate());
        setStrategyValue("forcestop");
        assertTrue(service.isMaintenanceLocalStrategyForceStop());
    }

    // ---- isClusterWideMigrationPossible ----

    @Test
    public void isClusterWideMigrationPossible_falseWhenZoneFlagOff() {
        // MIGRATE_VM_ACROSS_CLUSTERS defaults to false at zone level so this exercises
        // the early-exit branch without flipping config.
        List<HostVO> hosts = new ArrayList<>();
        boolean result = service.isClusterWideMigrationPossible(host, Collections.singletonList(vm), hosts);
        assertFalse(result);
    }

    // ---- migrateAwayVmWithVolumes ----

    @Test
    public void migrateAwayVmWithVolumes_invokesMigrateWithStorage() throws Exception {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(serviceOfferingDao.findById(SERVICE_OFFERING_ID)).thenReturn(offering);
        when(vm.getUuid()).thenReturn("vm-uuid");

        DeployDestination dest = mock(DeployDestination.class);
        Host destHost = mock(Host.class);
        when(destHost.getId()).thenReturn(999L);
        when(dest.getHost()).thenReturn(destHost);
        when(deploymentManager.planDeployment(any(), any(DataCenterDeployment.class), any(DeploymentPlanner.ExcludeList.class), isNull()))
                .thenReturn(dest);

        service.migrateAwayVmWithVolumes(host, vm);

        verify(vmManager).migrateWithStorage(eq("vm-uuid"), eq(HOST_ID), eq(999L), isNull());
    }

    @Test(expected = CloudRuntimeException.class)
    public void migrateAwayVmWithVolumes_throwsWhenNoDestination() throws Exception {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(serviceOfferingDao.findById(SERVICE_OFFERING_ID)).thenReturn(offering);
        when(deploymentManager.planDeployment(any(), any(DataCenterDeployment.class), any(DeploymentPlanner.ExcludeList.class), isNull()))
                .thenReturn(null);

        service.migrateAwayVmWithVolumes(host, vm);
    }

    @Test(expected = CloudRuntimeException.class)
    public void migrateAwayVmWithVolumes_throwsOnInsufficientCapacity() throws Exception {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(serviceOfferingDao.findById(SERVICE_OFFERING_ID)).thenReturn(offering);
        when(deploymentManager.planDeployment(any(), any(DataCenterDeployment.class), any(DeploymentPlanner.ExcludeList.class), isNull()))
                .thenThrow(new InsufficientServerCapacityException("none", DataCenterVO.class, DC_ID));

        service.migrateAwayVmWithVolumes(host, vm);
    }

    @Test(expected = CloudRuntimeException.class)
    public void migrateAwayVmWithVolumes_throwsOnMigrateFailure() throws Exception {
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        when(serviceOfferingDao.findById(SERVICE_OFFERING_ID)).thenReturn(offering);
        when(vm.getUuid()).thenReturn("vm-uuid");
        DeployDestination dest = mock(DeployDestination.class);
        Host destHost = mock(Host.class);
        when(destHost.getId()).thenReturn(999L);
        when(dest.getHost()).thenReturn(destHost);
        when(deploymentManager.planDeployment(any(), any(DataCenterDeployment.class), any(DeploymentPlanner.ExcludeList.class), isNull()))
                .thenReturn(dest);
        org.mockito.Mockito.doThrow(new ResourceUnavailableException("nope", DataCenterVO.class, DC_ID))
                .when(vmManager).migrateWithStorage(any(), anyLong(), anyLong(), any());

        service.migrateAwayVmWithVolumes(host, vm);
    }

    // ---- handleVmForLastHostOrWithVGpu ----

    @Test
    public void handleVmForLastHostOrWithVGpu_destroysSystemVm() {
        when(vm.getType()).thenReturn(VirtualMachine.Type.SecondaryStorageVm);
        service.handleVmForLastHostOrWithVGpu(host, vm);
        verify(haManager).scheduleDestroy(eq(vm), eq(HOST_ID), eq(HighAvailabilityManager.ReasonType.HostMaintenance));
        verify(haManager, never()).scheduleStop(any(), anyLong(), any(WorkType.class));
    }

    @Test
    public void handleVmForLastHostOrWithVGpu_destroysConsoleProxy() {
        when(vm.getType()).thenReturn(VirtualMachine.Type.ConsoleProxy);
        service.handleVmForLastHostOrWithVGpu(host, vm);
        verify(haManager).scheduleDestroy(eq(vm), eq(HOST_ID), eq(HighAvailabilityManager.ReasonType.HostMaintenance));
    }

    @Test
    public void handleVmForLastHostOrWithVGpu_forceStopsUserVm() {
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        service.handleVmForLastHostOrWithVGpu(host, vm);
        verify(haManager).scheduleStop(eq(vm), eq(HOST_ID), eq(WorkType.ForceStop));
        verify(haManager, never()).scheduleDestroy(any(), anyLong(), any());
    }

    // ---- scheduleVmsRestart ----

    @Test
    public void scheduleVmsRestart_schedulesForRunningStartingStopping() {
        VMInstanceVO running = mock(VMInstanceVO.class);
        when(running.getState()).thenReturn(State.Running);
        VMInstanceVO starting = mock(VMInstanceVO.class);
        when(starting.getState()).thenReturn(State.Starting);
        VMInstanceVO stopping = mock(VMInstanceVO.class);
        when(stopping.getState()).thenReturn(State.Stopping);
        VMInstanceVO stopped = mock(VMInstanceVO.class);
        when(stopped.getState()).thenReturn(State.Stopped);

        when(vmInstanceDao.listByHostId(HOST_ID)).thenReturn(Arrays.asList(running, starting, stopping, stopped));

        service.scheduleVmsRestart(host);

        verify(haManager).scheduleRestart(eq(running), eq(false), eq(HighAvailabilityManager.ReasonType.HostDegraded));
        verify(haManager).scheduleRestart(eq(starting), eq(false), eq(HighAvailabilityManager.ReasonType.HostDegraded));
        verify(haManager).scheduleRestart(eq(stopping), eq(false), eq(HighAvailabilityManager.ReasonType.HostDegraded));
        verify(haManager, never()).scheduleRestart(eq(stopped), any(Boolean.class), any());
    }

    @Test
    public void scheduleVmsRestart_noopWhenNoVms() {
        when(vmInstanceDao.listByHostId(HOST_ID)).thenReturn(Collections.emptyList());

        service.scheduleVmsRestart(host);

        verify(haManager, never()).scheduleRestart(any(), any(Boolean.class), any());
    }

    @Test
    public void scheduleVmsRestart_skipsAllStoppedVms() {
        VMInstanceVO stopped1 = mock(VMInstanceVO.class);
        when(stopped1.getState()).thenReturn(State.Stopped);
        VMInstanceVO destroyed = mock(VMInstanceVO.class);
        when(destroyed.getState()).thenReturn(State.Destroyed);
        when(vmInstanceDao.listByHostId(HOST_ID)).thenReturn(Arrays.asList(stopped1, destroyed));

        service.scheduleVmsRestart(host);

        verify(haManager, never()).scheduleRestart(any(), any(Boolean.class), any());
    }
}
