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
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.RecreateCheckpointsCommand;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.snapshot.SnapshotManager;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class VmMigrationCheckpointServiceImplTest {

    @Spy
    @InjectMocks
    private VmMigrationCheckpointServiceImpl service;

    @Mock
    private AgentManager agentManager;
    @Mock
    private VolumeOrchestrationService volumeOrchestrationService;
    @Mock
    private SnapshotManager snapshotManager;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private VMInstanceVO vm;

    @Test
    public void recreateCheckpointsKvmOnVmAfterMigrationNonKvmReturnsBeforeVolumeLookup() throws Exception {
        when(vm.getHypervisorType()).thenReturn(HypervisorType.VMware);

        service.recreateCheckpointsKvmOnVmAfterMigration(vm, 7L);

        verify(volumeDao, never()).findByInstance(anyLong());
        verify(agentManager, never()).send(eq(7L), any(RecreateCheckpointsCommand.class));
    }

    @Test
    public void recreateCheckpointsKvmOnVmAfterMigrationKvmWithoutCheckpointVolumesReturnsBeforeAgentCall() throws Exception {
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        doReturn(List.of()).when(service).getVmVolumesWithCheckpointsToRecreate(vm);

        service.recreateCheckpointsKvmOnVmAfterMigration(vm, 7L);

        verify(agentManager, never()).send(eq(7L), any(RecreateCheckpointsCommand.class));
    }

    @Test
    public void recreateCheckpointsKvmOnVmAfterMigrationAgentUnavailableEndsSnapshotChainAndThrows() throws Exception {
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        doReturn(List.of(checkpointVolume(11L))).when(service).getVmVolumesWithCheckpointsToRecreate(vm);
        doThrow(new AgentUnavailableException(7L)).when(agentManager).send(eq(7L), any(RecreateCheckpointsCommand.class));

        assertThrows(CloudRuntimeException.class, () -> service.recreateCheckpointsKvmOnVmAfterMigration(vm, 7L));

        verify(snapshotManager).endSnapshotChainForVolume(11L, HypervisorType.KVM);
    }

    @Test
    public void recreateCheckpointsKvmOnVmAfterMigrationOperationTimeoutEndsSnapshotChainAndThrows() throws Exception {
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        doReturn(List.of(checkpointVolume(11L))).when(service).getVmVolumesWithCheckpointsToRecreate(vm);
        doThrow(new OperationTimedoutException(null, 7L, 0L, 0, false)).when(agentManager).send(eq(7L), any(RecreateCheckpointsCommand.class));

        assertThrows(CloudRuntimeException.class, () -> service.recreateCheckpointsKvmOnVmAfterMigration(vm, 7L));

        verify(snapshotManager).endSnapshotChainForVolume(11L, HypervisorType.KVM);
    }

    @Test
    public void recreateCheckpointsKvmOnVmAfterMigrationFailedAnswerEndsSnapshotChainWithoutThrowing() throws Exception {
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        doReturn(List.of(checkpointVolume(11L))).when(service).getVmVolumesWithCheckpointsToRecreate(vm);
        when(agentManager.send(eq(7L), any(RecreateCheckpointsCommand.class))).thenReturn(new Answer(null, false, "failed"));

        service.recreateCheckpointsKvmOnVmAfterMigration(vm, 7L);

        verify(snapshotManager).endSnapshotChainForVolume(11L, HypervisorType.KVM);
    }

    @Test
    public void recreateCheckpointsKvmOnVmAfterMigrationSuccessfulAnswerDoesNotEndSnapshotChain() throws Exception {
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        doReturn(List.of(checkpointVolume(11L))).when(service).getVmVolumesWithCheckpointsToRecreate(vm);
        when(agentManager.send(eq(7L), any(RecreateCheckpointsCommand.class))).thenReturn(new Answer(null, true, null));

        service.recreateCheckpointsKvmOnVmAfterMigration(vm, 7L);

        verify(snapshotManager, never()).endSnapshotChainForVolume(anyLong(), eq(HypervisorType.KVM));
    }

    @Test
    public void getVmVolumesWithCheckpointsToRecreateFiltersVolumesWithoutCheckpointPaths() {
        VolumeVO volumeWithoutCheckpoints = mock(VolumeVO.class);
        VolumeVO volumeWithCheckpoints = mock(VolumeVO.class);
        when(vm.getId()).thenReturn(42L);
        when(volumeWithoutCheckpoints.getId()).thenReturn(1L);
        when(volumeWithCheckpoints.getId()).thenReturn(2L);
        when(volumeWithCheckpoints.getPath()).thenReturn("volume-path");
        when(volumeDao.findByInstance(42L)).thenReturn(List.of(volumeWithoutCheckpoints, volumeWithCheckpoints));
        when(volumeOrchestrationService.getVolumeCheckpointPathsAndImageStoreUrls(1L, HypervisorType.KVM))
                .thenReturn(new Pair<>(List.of(), Set.of()));
        when(volumeOrchestrationService.getVolumeCheckpointPathsAndImageStoreUrls(2L, HypervisorType.KVM))
                .thenReturn(new Pair<>(List.of("checkpoint-path"), Set.of("store-url")));

        List<VolumeObjectTO> result = service.getVmVolumesWithCheckpointsToRecreate(vm);

        assertEquals(1, result.size());
        assertEquals(List.of("checkpoint-path"), result.get(0).getCheckpointPaths());
        assertEquals(Set.of("store-url"), result.get(0).getCheckpointImageStoreUrls());
        assertEquals("volume-path", result.get(0).getPath());
    }

    @Test
    public void endSnapshotChainForVolumesUsesDestinationVolumeInTargetPool() {
        Volume sourceVolume = mock(Volume.class);
        StoragePool targetPool = mock(StoragePool.class);
        VolumeVO destinationVolume = mock(VolumeVO.class);
        Map<Volume, StoragePool> volumeToPoolMap = new HashMap<>();
        volumeToPoolMap.put(sourceVolume, targetPool);
        when(sourceVolume.getName()).thenReturn("data");
        when(targetPool.getId()).thenReturn(20L);
        when(volumeDao.findByPoolIdName(20L, "data")).thenReturn(destinationVolume);
        when(destinationVolume.getId()).thenReturn(33L);

        service.endSnapshotChainForVolumes(volumeToPoolMap, HypervisorType.KVM);

        verify(snapshotManager).endSnapshotChainForVolume(33L, HypervisorType.KVM);
    }

    private VolumeObjectTO checkpointVolume(long id) {
        VolumeObjectTO volume = new VolumeObjectTO();
        volume.setId(id);
        volume.setCheckpointPaths(List.of("checkpoint-path"));
        volume.setCheckpointImageStoreUrls(Set.of("store-url"));
        volume.setPath("volume-path");
        return volume;
    }
}
