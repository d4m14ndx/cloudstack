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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.MigrateVmToPoolAnswer;
import com.cloud.agent.api.MigrateVmToPoolCommand;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Storage;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.VirtualMachine.Event;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmOfflineStorageMigrationServiceImplTest {

    @Spy
    @InjectMocks
    private VmOfflineStorageMigrationServiceImpl service;

    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private ClusterDao clusterDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private DiskOfferingDao diskOfferingDao;
    @Mock
    private ClusterDetailsDao clusterDetailsDao;
    @Mock
    private AgentManager agentMgr;
    @Mock
    private HypervisorGuruManager hvGuruMgr;
    @Mock
    private NetworkOrchestrationService networkMgr;
    @Mock
    private VolumeOrchestrationService volumeMgr;
    @Mock
    private StorageManager storageMgr;
    @Mock
    private VmVolumeMigrationPlanningService vmVolumeMigrationPlanningService;
    @Mock
    private VirtualMachineManager virtualMachineManager;

    @Test
    public void orchestrateStorageMigrationEmptyMappingThrowsAndAttemptsStoppedTransition() throws Exception {
        VMInstanceVO vm = mockVm("vm-1");
        when(vmInstanceDao.findByUuid("vm-1")).thenReturn(vm);

        assertThrows(CloudRuntimeException.class, () -> service.orchestrateStorageMigration("vm-1", new HashMap<>()));

        verify(virtualMachineManager).stateTransitTo(vm, Event.AgentReportStopped, null);
    }

    @Test
    public void orchestrateStorageMigrationWrapsStorageMigrationFailureAndAttemptsStoppedTransition() throws Exception {
        VMInstanceVO vm = mockVm("vm-2");
        Map<Volume, StoragePool> volumeToPool = new HashMap<>();
        when(vmInstanceDao.findByUuid("vm-2")).thenReturn(vm);
        doReturn(volumeToPool).when(service).prepareVmStorageMigration(eq(vm), any());
        doThrow(new StorageUnavailableException("offline failed", 1L)).when(service).migrateThroughHypervisorOrStorage(vm, volumeToPool);

        assertThrows(CloudRuntimeException.class, () -> service.orchestrateStorageMigration("vm-2", Map.of(1L, 2L)));

        verify(virtualMachineManager).stateTransitTo(vm, Event.AgentReportStopped, null);
    }

    @Test
    public void orchestrateStorageMigrationWrapsFailedStoppedTransition() throws Exception {
        VMInstanceVO vm = mockVm("vm-3");
        Map<Volume, StoragePool> volumeToPool = new HashMap<>();
        when(vmInstanceDao.findByUuid("vm-3")).thenReturn(vm);
        doReturn(volumeToPool).when(service).prepareVmStorageMigration(eq(vm), any());
        doNothing().when(service).migrateThroughHypervisorOrStorage(vm, volumeToPool);
        doThrow(new NoTransitionException("failed")).when(virtualMachineManager).stateTransitTo(vm, Event.AgentReportStopped, null);

        assertThrows(CloudRuntimeException.class, () -> service.orchestrateStorageMigration("vm-3", Map.of(1L, 2L)));
    }

    @Test
    public void prepareVmStorageMigrationRejectsEmptyVolumePoolMap() {
        VMInstanceVO vm = mockVm("vm-4");

        assertThrows(CloudRuntimeException.class, () -> service.prepareVmStorageMigration(vm, new HashMap<>()));
    }

    @Test
    public void prepareVmStorageMigrationBuildsClusterDeploymentAndRequestsStorageMigrationState() throws Exception {
        VMInstanceVO vm = mockVm("vm-5");
        StoragePoolVO pool = mock(StoragePoolVO.class);
        ClusterVO cluster = mock(ClusterVO.class);
        Map<Long, Long> requestedMap = Map.of(11L, 22L);
        Map<Volume, StoragePool> plannedMap = new HashMap<>();
        ArgumentCaptor<DataCenterDeployment> planCaptor = ArgumentCaptor.forClass(DataCenterDeployment.class);
        when(pool.getClusterId()).thenReturn(33L);
        when(storagePoolDao.findById(22L)).thenReturn(pool);
        when(clusterDao.findById(33L)).thenReturn(cluster);
        when(cluster.getDataCenterId()).thenReturn(44L);
        when(cluster.getPodId()).thenReturn(55L);
        when(cluster.getId()).thenReturn(33L);
        when(vmVolumeMigrationPlanningService.createMappingVolumeAndStoragePool(any(), planCaptor.capture(), eq(requestedMap)))
                .thenReturn(plannedMap);

        Map<Volume, StoragePool> result = service.prepareVmStorageMigration(vm, requestedMap);

        assertEquals(plannedMap, result);
        assertEquals(44L, planCaptor.getValue().getDataCenterId());
        assertEquals(Long.valueOf(55L), planCaptor.getValue().getPodId());
        assertEquals(Long.valueOf(33L), planCaptor.getValue().getClusterId());
        assertNull(planCaptor.getValue().getHostId());
        verify(virtualMachineManager).stateTransitTo(vm, Event.StorageMigrationRequested, null);
    }

    @Test
    public void prepareVmStorageMigrationBuildsZoneDeploymentWhenPoolsHaveNoCluster() throws Exception {
        VMInstanceVO vm = mockVm("vm-6");
        StoragePoolVO pool = mock(StoragePoolVO.class);
        Map<Long, Long> requestedMap = Map.of(11L, 22L);
        Map<Volume, StoragePool> plannedMap = new HashMap<>();
        ArgumentCaptor<DataCenterDeployment> planCaptor = ArgumentCaptor.forClass(DataCenterDeployment.class);
        when(pool.getClusterId()).thenReturn(null);
        when(pool.getDataCenterId()).thenReturn(44L);
        when(storagePoolDao.findById(22L)).thenReturn(pool);
        when(vmVolumeMigrationPlanningService.createMappingVolumeAndStoragePool(any(), planCaptor.capture(), eq(requestedMap)))
                .thenReturn(plannedMap);

        Map<Volume, StoragePool> result = service.prepareVmStorageMigration(vm, requestedMap);

        assertEquals(plannedMap, result);
        assertEquals(44L, planCaptor.getValue().getDataCenterId());
        assertNull(planCaptor.getValue().getPodId());
        assertNull(planCaptor.getValue().getClusterId());
        verify(clusterDao, never()).findById(anyLong());
        verify(virtualMachineManager).stateTransitTo(vm, Event.StorageMigrationRequested, null);
    }

    @Test
    public void prepareVmStorageMigrationRejectsMapThatCannotResolveDataCenter() throws Exception {
        VMInstanceVO vm = mockVm("vm-7");
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getClusterId()).thenReturn(33L);
        when(storagePoolDao.findById(22L)).thenReturn(pool);
        when(clusterDao.findById(33L)).thenReturn(null);

        assertThrows(CloudRuntimeException.class, () -> service.prepareVmStorageMigration(vm, Map.of(11L, 22L)));

        verify(virtualMachineManager, never()).stateTransitTo(eq(vm), eq(Event.StorageMigrationRequested), any());
    }

    @Test
    public void migrateThroughHypervisorOrStorageFallsBackToStorageMigrationAndRunsPostCleanup() throws Exception {
        VMInstanceVO vm = mockVm("vm-8");
        HostVO sourceHost = mock(HostVO.class);
        Map<Volume, StoragePool> volumeToPool = new HashMap<>();
        when(virtualMachineManager.findClusterAndHostIdForVm(vm, false)).thenReturn(new Pair<>(33L, 44L));
        doReturn(null).when(service).attemptHypervisorMigration(vm, volumeToPool, 44L);
        when(volumeMgr.storageMigration(any(), eq(volumeToPool))).thenReturn(true);
        when(hostDao.findById(44L)).thenReturn(sourceHost);
        doNothing().when(service).postStorageMigrationCleanup(vm, volumeToPool, sourceHost, 33L);

        service.migrateThroughHypervisorOrStorage(vm, volumeToPool);

        verify(volumeMgr).storageMigration(any(), eq(volumeToPool));
        verify(service).postStorageMigrationCleanup(vm, volumeToPool, sourceHost, 33L);
    }

    @Test
    public void migrateThroughHypervisorOrStorageUsesHypervisorResultsWhenFinalizeCommandsRun() throws Exception {
        VMInstanceVO vm = mockVm("vm-9");
        Map<Volume, StoragePool> volumeToPool = new HashMap<>();
        Answer[] answers = new Answer[] {new Answer(mock(Command.class))};
        when(virtualMachineManager.findClusterAndHostIdForVm(vm, false)).thenReturn(new Pair<>(33L, 44L));
        doReturn(answers).when(service).attemptHypervisorMigration(vm, volumeToPool, 44L);
        doNothing().when(service).afterHypervisorMigrationCleanup(vm, volumeToPool, 33L, answers);

        service.migrateThroughHypervisorOrStorage(vm, volumeToPool);

        verify(volumeMgr, never()).storageMigration(any(), any());
        verify(service).afterHypervisorMigrationCleanup(vm, volumeToPool, 33L, answers);
    }

    @Test
    public void attemptHypervisorMigrationReturnsNullWhenSourceHostMissing() {
        VMInstanceVO vm = mockVm("vm-10");

        assertNull(service.attemptHypervisorMigration(vm, new HashMap<>(), null));

        verify(hvGuruMgr, never()).getGuru(any());
    }

    @Test
    public void markVolumesInPoolThrowsForSingleFailedAnswer() {
        VMInstanceVO vm = mockVm("vm-11");
        Answer failedAnswer = new Answer(mock(Command.class), false, "nope");

        assertThrows(CloudRuntimeException.class, () -> service.markVolumesInPool(vm, new Answer[] {failedAnswer}));
    }

    @Test
    public void markVolumesInPoolUpdatesPathPoolTypeAndChainInfoFromMigrateAnswer() {
        VMInstanceVO vm = mockVm("vm-12");
        VolumeVO volume = mock(VolumeVO.class);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        VolumeObjectTO result = new VolumeObjectTO();
        result.setId(77L);
        result.setUuid("vol-77");
        result.setPath("new/path");
        result.setDataStoreUuid("pool-uuid");
        result.setChainInfo("chain-info");
        MigrateVmToPoolAnswer answer = new MigrateVmToPoolAnswer(new MigrateVmToPoolCommand("vm", List.of(), null, false), List.of(result));
        when(vm.getId()).thenReturn(12L);
        when(volumeDao.findUsableVolumesForInstance(12L)).thenReturn(List.of(volume));
        when(volumeDao.findById(77L)).thenReturn(volume);
        when(storagePoolDao.findPoolByUUID("pool-uuid")).thenReturn(pool);
        when(pool.getId()).thenReturn(88L);
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(volume.getId()).thenReturn(77L);

        service.markVolumesInPool(vm, new Answer[] {answer});

        verify(volume).setPath("new/path");
        verify(volume).setPoolId(88L);
        verify(volume).setPoolType(Storage.StoragePoolType.NetworkFilesystem);
        verify(volume).setChainInfo("chain-info");
        verify(volumeDao).update(77L, volume);
    }

    @Test
    public void markVolumesInPoolThrowsWhenNoMigrateVmToPoolAnswerExists() {
        VMInstanceVO vm = mockVm("vm-13");
        Answer answer = new Answer(mock(Command.class));

        assertThrows(CloudRuntimeException.class, () -> service.markVolumesInPool(vm, new Answer[] {answer}));
    }

    @Test
    public void postStorageMigrationCleanupReallocatesNetworkWhenRootPoolPodChanges() throws Exception {
        VMInstanceVO vm = mockVm("vm-14");
        Volume rootVolume = mock(Volume.class);
        StoragePool rootPool = mock(StoragePool.class);
        Map<Volume, StoragePool> volumeToPool = new HashMap<>();
        volumeToPool.put(rootVolume, rootPool);
        when(rootVolume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        when(rootPool.getPodId()).thenReturn(22L);
        when(vm.getPodIdToDeployIn()).thenReturn(11L);
        when(vm.getDataCenterId()).thenReturn(33L);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        ArgumentCaptor<DataCenterDeployment> planCaptor = ArgumentCaptor.forClass(DataCenterDeployment.class);

        service.postStorageMigrationCleanup(vm, volumeToPool, mock(HostVO.class), 44L);

        verify(networkMgr).reallocate(any(), planCaptor.capture());
        assertEquals(33L, planCaptor.getValue().getDataCenterId());
        assertEquals(Long.valueOf(22L), planCaptor.getValue().getPodId());
        verify(vm).setLastHostId(null);
        verify(vm).setPodIdToDeployIn(22L);
    }

    @Test
    public void afterStorageMigrationVmwareVMCleanupUnregistersVmWhenVmwareDatacenterChanges() {
        VMInstanceVO vm = mockVm("vm-15");
        StoragePool destPool = mock(StoragePool.class);
        HostVO sourceHost = mock(HostVO.class);
        when(destPool.getClusterId()).thenReturn(22L);
        when(clusterDetailsDao.getVmwareDcName(11L)).thenReturn("src-dc");
        when(clusterDetailsDao.getVmwareDcName(22L)).thenReturn("dest-dc");
        doNothing().when(service).removeStaleVmFromSource(vm, sourceHost);

        service.afterStorageMigrationVmwareVMCleanup(destPool, vm, sourceHost, 11L);

        verify(service).removeStaleVmFromSource(vm, sourceHost);
    }

    @Test
    public void removeStaleVmFromSourceWrapsAgentSendFailure() throws Exception {
        VMInstanceVO vm = mockVm("vm-16");
        HostVO sourceHost = mock(HostVO.class);
        when(sourceHost.getId()).thenReturn(44L);
        doThrow(new AgentUnavailableException(44L)).when(agentMgr).send(eq(44L), any(Command.class));

        assertThrows(CloudRuntimeException.class, () -> service.removeStaleVmFromSource(vm, sourceHost));
    }

    @Test
    public void checkDestinationForTagsThrowsWhenDiskOfferingTagsDoNotMatchPoolTags() {
        VMInstanceVO vm = mockVm("vm-17");
        StoragePool destPool = mock(StoragePool.class);
        VolumeVO volume = mock(VolumeVO.class);
        DiskOfferingVO diskOffering = mock(DiskOfferingVO.class);
        when(vm.getId()).thenReturn(17L);
        when(destPool.getId()).thenReturn(88L);
        when(destPool.getName()).thenReturn("pool-88");
        when(volumeDao.findUsableVolumesForInstance(17L)).thenReturn(List.of(volume));
        when(storageMgr.getStoragePoolTagList(88L)).thenReturn(List.of("silver"));
        when(volume.getDiskOfferingId()).thenReturn(99L);
        when(volume.getName()).thenReturn("volume-99");
        when(diskOfferingDao.findById(99L)).thenReturn(diskOffering);
        when(diskOffering.getTags()).thenReturn("gold");

        assertThrows(CloudRuntimeException.class, () -> service.checkDestinationForTags(destPool, vm));
    }

    @Test
    public void matchesOfSortsPreservesExistingTruthTable() {
        List<String> nothing = null;
        List<String> empty = new ArrayList<>();
        List<String> tag = Arrays.asList("bla");
        List<String> tags = Arrays.asList("bla", "blob");
        List<String> others = Arrays.asList("bla", "blieb");
        List<String> three = Arrays.asList("bla", "blob", "blieb");

        assertTrue(VmOfflineStorageMigrationServiceImpl.matches(tag, tags));
        assertTrue(VmOfflineStorageMigrationServiceImpl.matches(tag, others));
        assertTrue(VmOfflineStorageMigrationServiceImpl.matches(nothing, tags));
        assertTrue(VmOfflineStorageMigrationServiceImpl.matches(empty, tag));
        assertFalse(VmOfflineStorageMigrationServiceImpl.matches(tags, tag));
        assertFalse(VmOfflineStorageMigrationServiceImpl.matches(tag, nothing));
        assertFalse(VmOfflineStorageMigrationServiceImpl.matches(tag, empty));
        assertFalse(VmOfflineStorageMigrationServiceImpl.matches(tags, others));
        assertFalse(VmOfflineStorageMigrationServiceImpl.matches(others, tags));
        assertTrue(VmOfflineStorageMigrationServiceImpl.matches(nothing, three));
        assertTrue(VmOfflineStorageMigrationServiceImpl.matches(empty, three));
        assertTrue(VmOfflineStorageMigrationServiceImpl.matches(tag, three));
        assertTrue(VmOfflineStorageMigrationServiceImpl.matches(tags, three));
        assertTrue(VmOfflineStorageMigrationServiceImpl.matches(others, three));
    }

    private VMInstanceVO mockVm(String uuid) {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getUuid()).thenReturn(uuid);
        when(vm.getInstanceName()).thenReturn(uuid + "-name");
        when(vm.getHypervisorType()).thenReturn(HypervisorType.VMware);
        return vm;
    }
}
