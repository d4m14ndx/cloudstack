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

import static com.cloud.configuration.ConfigurationManagerImpl.MIGRATE_VM_ACROSS_CLUSTERS;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.Outcome;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.ScopeType;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmMigrateAwayPlanningServiceImplTest {

    private static final String VM_UUID = "vm-uuid";
    private static final long VM_ID = 42L;
    private static final long SRC_HOST_ID = 7L;
    private static final long DATA_CENTER_ID = 8L;
    private static final long POD_ID = 9L;
    private static final long CLUSTER_ID = 10L;
    private static final long POOL_ID = 11L;

    @Spy
    @InjectMocks
    private VmMigrateAwayPlanningServiceImpl service = new VmMigrateAwayPlanningServiceImpl();

    @Mock
    private VMInstanceDao vmDao;
    @Mock
    private ServiceOfferingDao offeringDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private VolumeDao volsDao;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private ClusterDao clusterDao;
    @Mock
    private DeploymentPlanningManager dpMgr;
    @Mock
    private HighAvailabilityManager haMgr;
    @Mock
    private VmWorkJobQueueService vmWorkJobQueueService;
    @Mock
    private VirtualMachineManagerImpl virtualMachineManager;
    @Mock
    private VMInstanceVO vm;
    @Mock
    private HostVO host;

    private ConfigDepotImpl originalConfigDepot;
    private boolean configDepotOverridden;

    @After
    public void cleanup() {
        if (configDepotOverridden) {
            ReflectionTestUtils.setField(MIGRATE_VM_ACROSS_CLUSTERS, "s_depot", originalConfigDepot);
        }
    }

    @Test
    public void migrateAwayDispatchesThroughJobQueueWhenNotAlreadyInWorkJob() throws Exception {
        AsyncJobExecutionContext jobContext = mock(AsyncJobExecutionContext.class);
        Outcome<VirtualMachine> outcome = mock(Outcome.class);
        when(jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)).thenReturn(false);
        when(vmWorkJobQueueService.migrateVmAwayThroughJobQueue(VM_UUID, SRC_HOST_ID)).thenReturn(outcome);

        try (MockedStatic<AsyncJobExecutionContext> context = mockStatic(AsyncJobExecutionContext.class)) {
            context.when(AsyncJobExecutionContext::getCurrentExecutionContext).thenReturn(jobContext);

            service.migrateAway(VM_UUID, SRC_HOST_ID);
        }

        verify(vmWorkJobQueueService).retrieveVmFromJobOutcome(outcome, VM_UUID, "migrateVmAway");
        verify(vmWorkJobQueueService).retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome);
    }

    @Test
    public void migrateAwayCreatesPlaceholderAndRetriesWithHaPlannerWhenAlreadyInWorkJob() throws Exception {
        AsyncJobExecutionContext jobContext = mock(AsyncJobExecutionContext.class);
        VmWorkJobVO placeholder = new VmWorkJobVO("");
        DeploymentPlanner haPlanner = mock(DeploymentPlanner.class);
        when(jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)).thenReturn(true);
        when(vmDao.findByUuid(VM_UUID)).thenReturn(vm);
        when(vm.getId()).thenReturn(VM_ID);
        when(vmWorkJobQueueService.createPlaceHolderWork(VM_ID)).thenReturn(placeholder);
        when(haMgr.getHAPlanner()).thenReturn(haPlanner);
        doThrow(new InsufficientServerCapacityException("capacity", DataCenter.class, DATA_CENTER_ID))
                .doNothing()
                .when(service).orchestrateMigrateAway(VM_UUID, SRC_HOST_ID, null);
        doNothing().when(service).orchestrateMigrateAway(VM_UUID, SRC_HOST_ID, haPlanner);

        try (MockedStatic<AsyncJobExecutionContext> context = mockStatic(AsyncJobExecutionContext.class)) {
            context.when(AsyncJobExecutionContext::getCurrentExecutionContext).thenReturn(jobContext);

            service.migrateAway(VM_UUID, SRC_HOST_ID);
        }

        verify(service).orchestrateMigrateAway(VM_UUID, SRC_HOST_ID, null);
        verify(service).orchestrateMigrateAway(VM_UUID, SRC_HOST_ID, haPlanner);
        verify(vmWorkJobQueueService).expungePlaceHolderWork(placeholder);
    }

    @Test
    public void orchestrateMigrateAwayPlansDestinationAndDelegatesLiveMigrationToManager() throws Exception {
        DeploymentPlanner planner = mock(DeploymentPlanner.class);
        DeployDestination destination = mock(DeployDestination.class);
        Host destinationHost = mock(Host.class);
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        VolumeVO rootVolume = mock(VolumeVO.class);
        StoragePoolVO rootDiskPool = mock(StoragePoolVO.class);
        prepareVmAndHost();
        when(offeringDao.findById(VM_ID, 12L)).thenReturn(offering);
        when(volsDao.findReadyRootVolumesByInstance(VM_ID)).thenReturn(List.of(rootVolume));
        when(rootVolume.getPoolId()).thenReturn(POOL_ID);
        when(storagePoolDao.findById(POOL_ID)).thenReturn(rootDiskPool);
        when(rootDiskPool.getId()).thenReturn(POOL_ID);
        when(destination.getHost()).thenReturn(destinationHost);
        when(destinationHost.getId()).thenReturn(13L);
        when(dpMgr.planDeployment(any(VirtualMachineProfile.class), any(DataCenterDeployment.class), any(ExcludeList.class), eq(planner))).thenReturn(destination);

        service.orchestrateMigrateAway(VM_UUID, SRC_HOST_ID, planner);

        ArgumentCaptor<DataCenterDeployment> planCaptor = ArgumentCaptor.forClass(DataCenterDeployment.class);
        verify(dpMgr).planDeployment(any(VirtualMachineProfile.class), planCaptor.capture(), any(ExcludeList.class), eq(planner));
        assertTrue(planCaptor.getValue().isMigrationPlan());
        verify(virtualMachineManager).migrate(vm, SRC_HOST_ID, destination);
    }

    @Test
    public void orchestrateMigrateAwayMissingVmThrowsOriginalMessage() throws Exception {
        CloudRuntimeException exception = org.junit.Assert.assertThrows(CloudRuntimeException.class,
                () -> service.orchestrateMigrateAway(VM_UUID, SRC_HOST_ID, null));

        assertEquals("Unable to find VM with uuid [vm-uuid].", exception.getMessage());
    }

    @Test
    public void checkIfVmHasClusterWideVolumesReturnsTrueWhenAnyVolumePoolIsClusterScoped() {
        VolumeVO zoneVolume = mock(VolumeVO.class);
        VolumeVO clusterVolume = mock(VolumeVO.class);
        StoragePoolVO zonePool = mock(StoragePoolVO.class);
        StoragePoolVO clusterPool = mock(StoragePoolVO.class);
        when(volsDao.findCreatedByInstance(VM_ID)).thenReturn(List.of(zoneVolume, clusterVolume));
        when(zoneVolume.getPoolId()).thenReturn(21L);
        when(clusterVolume.getPoolId()).thenReturn(22L);
        when(storagePoolDao.findById(21L)).thenReturn(zonePool);
        when(storagePoolDao.findById(22L)).thenReturn(clusterPool);
        when(zonePool.getScope()).thenReturn(ScopeType.ZONE);
        when(clusterPool.getScope()).thenReturn(ScopeType.CLUSTER);

        assertTrue(service.checkIfVmHasClusterWideVolumes(VM_ID));
    }

    @Test
    public void getMigrationDeploymentKeepsCurrentPodAndClusterWhenCrossClusterMigrationIsDisabled() {
        when(host.getDataCenterId()).thenReturn(DATA_CENTER_ID);
        when(host.getPodId()).thenReturn(POD_ID);
        when(host.getClusterId()).thenReturn(CLUSTER_ID);

        DataCenterDeployment plan = service.getMigrationDeployment(vm, host, POOL_ID, new ExcludeList());

        assertEquals(DATA_CENTER_ID, plan.getDataCenterId());
        assertEquals(Long.valueOf(POD_ID), plan.getPodId());
        assertEquals(Long.valueOf(CLUSTER_ID), plan.getClusterId());
        assertEquals(Long.valueOf(POOL_ID), plan.getPoolId());
    }

    @Test
    public void getMigrationDeploymentForVmwareCrossClusterMigrationExcludesDifferentHypervisorClustersAndDropsUserVmPodAndCluster() {
        ExcludeList excludes = new ExcludeList();
        ClusterVO vmwareCluster = mock(ClusterVO.class);
        overrideMigrateAcrossClustersConfig(true);
        when(host.getDataCenterId()).thenReturn(DATA_CENTER_ID);
        when(host.getHypervisorType()).thenReturn(HypervisorType.VMware);
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(clusterDao.listAllClusterIds(DATA_CENTER_ID)).thenReturn(new ArrayList<>(List.of(31L, 32L)));
        when(clusterDao.listByDcHyType(DATA_CENTER_ID, HypervisorType.VMware.toString())).thenReturn(List.of(vmwareCluster));
        when(vmwareCluster.getId()).thenReturn(31L);

        DataCenterDeployment plan = service.getMigrationDeployment(vm, host, POOL_ID, excludes);

        assertEquals(DATA_CENTER_ID, plan.getDataCenterId());
        assertNull(plan.getPodId());
        assertNull(plan.getClusterId());
        assertEquals(Long.valueOf(POOL_ID), plan.getPoolId());
        assertTrue(excludes.getClustersToAvoid().contains(32L));
    }

    @Test
    public void getMigrationDeploymentForSystemVmCrossClusterMigrationKeepsCurrentPod() {
        ExcludeList excludes = new ExcludeList();
        overrideMigrateAcrossClustersConfig(true);
        when(host.getDataCenterId()).thenReturn(DATA_CENTER_ID);
        when(host.getPodId()).thenReturn(POD_ID);
        when(host.getHypervisorType()).thenReturn(HypervisorType.VMware);
        when(vm.getType()).thenReturn(VirtualMachine.Type.ConsoleProxy);
        when(clusterDao.listAllClusterIds(DATA_CENTER_ID)).thenReturn(List.of());
        when(clusterDao.listByDcHyType(DATA_CENTER_ID, HypervisorType.VMware.toString())).thenReturn(List.of());

        DataCenterDeployment plan = service.getMigrationDeployment(vm, host, POOL_ID, excludes);

        assertEquals(Long.valueOf(POD_ID), plan.getPodId());
        assertNull(plan.getClusterId());
    }

    private void prepareVmAndHost() {
        when(vmDao.findByUuid(VM_UUID)).thenReturn(vm);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getServiceOfferingId()).thenReturn(12L);
        when(vm.getHostId()).thenReturn(SRC_HOST_ID);
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(hostDao.findById(SRC_HOST_ID)).thenReturn(host);
        when(host.getDataCenterId()).thenReturn(DATA_CENTER_ID);
        when(host.getPodId()).thenReturn(POD_ID);
        when(host.getClusterId()).thenReturn(CLUSTER_ID);
    }

    private void overrideMigrateAcrossClustersConfig(final boolean enabled) {
        originalConfigDepot = (ConfigDepotImpl)ReflectionTestUtils.getField(MIGRATE_VM_ACROSS_CLUSTERS, "s_depot");
        ConfigDepotImpl configDepot = Mockito.mock(ConfigDepotImpl.class);
        Mockito.when(configDepot.getConfigStringValue(Mockito.eq(MIGRATE_VM_ACROSS_CLUSTERS.key()),
                Mockito.eq(ConfigKey.Scope.Zone), Mockito.eq(DATA_CENTER_ID))).thenReturn(Boolean.toString(enabled));
        ReflectionTestUtils.setField(MIGRATE_VM_ACROSS_CLUSTERS, "s_depot", configDepot);
        configDepotOverridden = true;
    }
}
