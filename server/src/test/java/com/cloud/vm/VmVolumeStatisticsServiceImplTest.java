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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVolumeStatsAnswer;
import com.cloud.agent.api.GetVolumeStatsCommand;
import com.cloud.agent.api.VolumeStatsEntry;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StorageManager;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmVolumeStatisticsServiceImplTest {

    private static final long CLUSTER_ID = 11L;
    private static final long POOL_ID = 22L;
    private static final long DATA_CENTER_ID = 33L;
    private static final String POOL_UUID = "pool-uuid";
    private static final String PROVIDER_NAME = "provider";
    private static final StoragePoolType POOL_TYPE = StoragePoolType.NetworkFilesystem;

    @Mock private ResourceManager resourceManager;
    @Mock private PrimaryDataStoreDao storagePoolDao;
    @Mock private VMInstanceDao vmInstanceDao;
    @Mock private VolumeDao volumeDao;
    @Mock private DataStoreProviderManager dataStoreProviderManager;
    @Mock private StorageManager storageManager;
    @Mock private AgentManager agentManager;
    @Mock private DataStoreProvider dataStoreProvider;
    @Mock private DataStoreDriver dataStoreDriver;
    @Mock private PrimaryDataStoreDriver primaryDataStoreDriver;

    private VmVolumeStatisticsServiceImpl service;
    private StoragePoolVO storagePool;

    @Before
    public void setUp() {
        service = new VmVolumeStatisticsServiceImpl();
        ReflectionTestUtils.setField(service, "_resourceMgr", resourceManager);
        ReflectionTestUtils.setField(service, "_storagePoolDao", storagePoolDao);
        ReflectionTestUtils.setField(service, "_vmInstanceDao", vmInstanceDao);
        ReflectionTestUtils.setField(service, "_volsDao", volumeDao);
        ReflectionTestUtils.setField(service, "_dataStoreProviderMgr", dataStoreProviderManager);
        ReflectionTestUtils.setField(service, "storageManager", storageManager);
        ReflectionTestUtils.setField(service, "_agentMgr", agentManager);
    }

    @Test
    public void getVolumeStatisticsReturnsNullWhenClusterHasNoHosts() {
        stubPool(ScopeType.CLUSTER, HypervisorType.KVM);
        when(resourceManager.listHostsInClusterByStatus(CLUSTER_ID, Status.Up)).thenReturn(Collections.emptyList());

        assertNull(service.getVolumeStatistics(CLUSTER_ID, POOL_UUID, POOL_TYPE, 0));

        verify(dataStoreProviderManager, never()).getDataStoreProvider(anyString());
        verify(agentManager, never()).easySend(anyLong(), any());
    }

    @Test
    public void getVolumeStatisticsReturnsNullWhenHostHasNoVolumeLocators() {
        HostVO host = host(1L, HypervisorType.KVM);
        stubPool(ScopeType.CLUSTER, HypervisorType.KVM);
        when(resourceManager.listHostsInClusterByStatus(CLUSTER_ID, Status.Up)).thenReturn(Collections.singletonList(host));
        when(vmInstanceDao.listByHostId(host.getId())).thenReturn(Collections.emptyList());

        assertNull(service.getVolumeStatistics(CLUSTER_ID, POOL_UUID, POOL_TYPE, 0));

        verify(dataStoreProviderManager, never()).getDataStoreProvider(anyString());
        verify(agentManager, never()).easySend(anyLong(), any());
    }

    @Test
    public void getVolumeStatisticsSkipsZoneWidePoolWhenNeighborHypervisorDiffers() {
        HostVO host = host(1L, HypervisorType.VMware);
        stubPool(ScopeType.ZONE, HypervisorType.KVM);
        when(resourceManager.listHostsInClusterByStatus(CLUSTER_ID, Status.Up)).thenReturn(Collections.singletonList(host));

        assertNull(service.getVolumeStatistics(CLUSTER_ID, POOL_UUID, POOL_TYPE, 0));

        verify(vmInstanceDao, never()).listByHostId(anyLong());
        verify(agentManager, never()).easySend(anyLong(), any());
    }

    @Test
    public void getVolumeStatisticsUsesPoolDriverWhenPrimaryDriverProvidesStats() {
        HostVO host = host(1L, HypervisorType.KVM);
        VolumeVO volume = readyVolume(ImageFormat.QCOW2, "volume-path", null);
        GetVolumeStatsAnswer answer = statsAnswer("volume-path", 100L, 200L);
        stubPool(ScopeType.CLUSTER, HypervisorType.KVM);
        stubHostVolumes(host, 101L, Collections.singletonList(volume));
        stubProviderDriver(primaryDataStoreDriver);
        when(resourceManager.listHostsInClusterByStatus(CLUSTER_ID, Status.Up)).thenReturn(Collections.singletonList(host));
        when(primaryDataStoreDriver.canProvideVolumeStats()).thenReturn(true);
        when(storageManager.getVolumeStats(eq(storagePool), any(GetVolumeStatsCommand.class))).thenReturn(answer);

        HashMap<String, VolumeStatsEntry> result = service.getVolumeStatistics(CLUSTER_ID, POOL_UUID, POOL_TYPE, 0);

        assertEquals(answer.getVolumeStats(), result);
        verify(agentManager, never()).easySend(anyLong(), any());
    }

    @Test
    public void getVolumeStatisticsUsesAgentFallbackAndAppliesTimeoutSeconds() {
        HostVO host = host(1L, HypervisorType.KVM);
        VolumeVO volume = readyVolume(ImageFormat.QCOW2, "volume-path", null);
        stubPool(ScopeType.CLUSTER, HypervisorType.KVM);
        stubHostVolumes(host, 101L, Collections.singletonList(volume));
        stubProviderDriver(primaryDataStoreDriver);
        when(resourceManager.listHostsInClusterByStatus(CLUSTER_ID, Status.Up)).thenReturn(Collections.singletonList(host));
        when(primaryDataStoreDriver.canProvideVolumeStats()).thenReturn(false);
        when(agentManager.easySend(eq(Long.valueOf(host.getId())), any(GetVolumeStatsCommand.class)))
                .thenReturn(statsAnswer("volume-path", 100L, 200L));

        service.getVolumeStatistics(CLUSTER_ID, POOL_UUID, POOL_TYPE, 3500);

        ArgumentCaptor<GetVolumeStatsCommand> captor = ArgumentCaptor.forClass(GetVolumeStatsCommand.class);
        verify(agentManager).easySend(eq(Long.valueOf(host.getId())), captor.capture());
        assertEquals(3, captor.getValue().getWait());
    }

    @Test
    public void getVolumeStatisticsLeavesFallbackWaitUnsetWhenTimeoutIsNotPositive() {
        HostVO host = host(1L, HypervisorType.KVM);
        VolumeVO volume = readyVolume(ImageFormat.QCOW2, "volume-path", null);
        stubPool(ScopeType.CLUSTER, HypervisorType.KVM);
        stubHostVolumes(host, 101L, Collections.singletonList(volume));
        stubProviderDriver(dataStoreDriver);
        when(resourceManager.listHostsInClusterByStatus(CLUSTER_ID, Status.Up)).thenReturn(Collections.singletonList(host));
        when(agentManager.easySend(eq(Long.valueOf(host.getId())), any(GetVolumeStatsCommand.class)))
                .thenReturn(statsAnswer("volume-path", 100L, 200L));

        service.getVolumeStatistics(CLUSTER_ID, POOL_UUID, POOL_TYPE, 0);

        ArgumentCaptor<GetVolumeStatsCommand> captor = ArgumentCaptor.forClass(GetVolumeStatsCommand.class);
        verify(agentManager).easySend(eq(Long.valueOf(host.getId())), captor.capture());
        assertEquals(0, captor.getValue().getWait());
    }

    @Test
    public void getVolumeStatisticsMergesStatsFromMultipleHosts() {
        HostVO firstHost = host(1L, HypervisorType.KVM);
        HostVO secondHost = host(2L, HypervisorType.KVM);
        stubPool(ScopeType.CLUSTER, HypervisorType.KVM);
        stubHostVolumes(firstHost, 101L, Collections.singletonList(readyVolume(ImageFormat.QCOW2, "first-path", null)));
        stubHostVolumes(secondHost, 102L, Collections.singletonList(readyVolume(ImageFormat.QCOW2, "second-path", null)));
        stubProviderDriver(dataStoreDriver);
        when(resourceManager.listHostsInClusterByStatus(CLUSTER_ID, Status.Up)).thenReturn(Arrays.asList(firstHost, secondHost));
        when(agentManager.easySend(eq(Long.valueOf(firstHost.getId())), any(GetVolumeStatsCommand.class)))
                .thenReturn(statsAnswer("first-path", 100L, 200L));
        when(agentManager.easySend(eq(Long.valueOf(secondHost.getId())), any(GetVolumeStatsCommand.class)))
                .thenReturn(statsAnswer("second-path", 300L, 400L));

        HashMap<String, VolumeStatsEntry> result = service.getVolumeStatistics(CLUSTER_ID, POOL_UUID, POOL_TYPE, 0);

        assertEquals(2, result.size());
        assertTrue(result.containsKey("first-path"));
        assertTrue(result.containsKey("second-path"));
    }

    @Test
    public void getVolumeStatisticsUsesChainInfoForReadyOvaAndPathForReadyNonOva() {
        HostVO host = host(1L, HypervisorType.KVM);
        VolumeVO ovaVolume = readyVolume(ImageFormat.OVA, "ignored-path", "ova-chain-info");
        VolumeVO qcowVolume = readyVolume(ImageFormat.QCOW2, "qcow-path", "ignored-chain-info");
        stubPool(ScopeType.CLUSTER, HypervisorType.KVM);
        stubHostVolumes(host, 101L, Arrays.asList(ovaVolume, qcowVolume));
        stubProviderDriver(dataStoreDriver);
        when(resourceManager.listHostsInClusterByStatus(CLUSTER_ID, Status.Up)).thenReturn(Collections.singletonList(host));
        when(agentManager.easySend(eq(Long.valueOf(host.getId())), any(GetVolumeStatsCommand.class)))
                .thenReturn(statsAnswer("qcow-path", 100L, 200L));

        service.getVolumeStatistics(CLUSTER_ID, POOL_UUID, POOL_TYPE, 0);

        ArgumentCaptor<GetVolumeStatsCommand> captor = ArgumentCaptor.forClass(GetVolumeStatsCommand.class);
        verify(agentManager).easySend(eq(Long.valueOf(host.getId())), captor.capture());
        assertEquals(Arrays.asList("ova-chain-info", "qcow-path"), captor.getValue().getVolumeUuids());
    }

    @Test
    public void getVolumeStatisticsIgnoresNonReadyVolumesAndNullLocators() {
        HostVO host = host(1L, HypervisorType.KVM);
        VolumeVO nonReadyVolume = nonReadyVolume();
        VolumeVO ovaWithoutChainInfo = readyVolume(ImageFormat.OVA, "ignored-path", null);
        VolumeVO qcowWithoutPath = readyVolume(ImageFormat.QCOW2, null, "ignored-chain-info");
        stubPool(ScopeType.CLUSTER, HypervisorType.KVM);
        stubHostVolumes(host, 101L, Arrays.asList(nonReadyVolume, ovaWithoutChainInfo, qcowWithoutPath));
        when(resourceManager.listHostsInClusterByStatus(CLUSTER_ID, Status.Up)).thenReturn(Collections.singletonList(host));

        assertNull(service.getVolumeStatistics(CLUSTER_ID, POOL_UUID, POOL_TYPE, 0));

        verify(dataStoreProviderManager, never()).getDataStoreProvider(anyString());
        verify(agentManager, never()).easySend(anyLong(), any());
    }

    @Test
    public void getVolumeStatisticsIgnoresNullAndNonVolumeStatsAnswers() {
        HostVO firstHost = host(1L, HypervisorType.KVM);
        HostVO secondHost = host(2L, HypervisorType.KVM);
        stubPool(ScopeType.CLUSTER, HypervisorType.KVM);
        stubHostVolumes(firstHost, 101L, Collections.singletonList(readyVolume(ImageFormat.QCOW2, "first-path", null)));
        stubHostVolumes(secondHost, 102L, Collections.singletonList(readyVolume(ImageFormat.QCOW2, "second-path", null)));
        stubProviderDriver(dataStoreDriver);
        when(resourceManager.listHostsInClusterByStatus(CLUSTER_ID, Status.Up)).thenReturn(Arrays.asList(firstHost, secondHost));
        when(agentManager.easySend(eq(Long.valueOf(firstHost.getId())), any(GetVolumeStatsCommand.class))).thenReturn(null);
        when(agentManager.easySend(eq(Long.valueOf(secondHost.getId())), any(GetVolumeStatsCommand.class)))
                .thenReturn(new Answer(new GetVolumeStatsCommand(POOL_TYPE, POOL_UUID, Collections.singletonList("second-path"))));

        assertNull(service.getVolumeStatistics(CLUSTER_ID, POOL_UUID, POOL_TYPE, 0));
    }

    @Test
    public void getVolumeStatisticsIgnoresVolumeStatsAnswersWithNullStatsMap() {
        HostVO host = host(1L, HypervisorType.KVM);
        GetVolumeStatsCommand command = new GetVolumeStatsCommand(POOL_TYPE, POOL_UUID, Collections.singletonList("volume-path"));
        stubPool(ScopeType.CLUSTER, HypervisorType.KVM);
        stubHostVolumes(host, 101L, Collections.singletonList(readyVolume(ImageFormat.QCOW2, "volume-path", null)));
        stubProviderDriver(dataStoreDriver);
        when(resourceManager.listHostsInClusterByStatus(CLUSTER_ID, Status.Up)).thenReturn(Collections.singletonList(host));
        when(agentManager.easySend(eq(Long.valueOf(host.getId())), any(GetVolumeStatsCommand.class)))
                .thenReturn(new GetVolumeStatsAnswer(command, "", null));

        assertNull(service.getVolumeStatistics(CLUSTER_ID, POOL_UUID, POOL_TYPE, 0));
    }

    private void stubPool(ScopeType scope, HypervisorType hypervisorType) {
        storagePool = new StoragePoolVO();
        storagePool.setId(POOL_ID);
        storagePool.setDataCenterId(DATA_CENTER_ID);
        storagePool.setUuid(POOL_UUID);
        storagePool.setScope(scope);
        storagePool.setHypervisor(hypervisorType);
        storagePool.setStorageProviderName(PROVIDER_NAME);
        when(storagePoolDao.findPoolByUUID(POOL_UUID)).thenReturn(storagePool);
    }

    private void stubProviderDriver(DataStoreDriver driver) {
        when(dataStoreProviderManager.getDataStoreProvider(PROVIDER_NAME)).thenReturn(dataStoreProvider);
        when(dataStoreProvider.getDataStoreDriver()).thenReturn(driver);
    }

    private void stubHostVolumes(HostVO host, long vmId, List<VolumeVO> volumes) {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(vmId);
        when(vmInstanceDao.listByHostId(host.getId())).thenReturn(Collections.singletonList(vm));
        when(volumeDao.findNonDestroyedVolumesByInstanceIdAndPoolId(vmId, POOL_ID)).thenReturn(volumes);
    }

    private HostVO host(long hostId, HypervisorType hypervisorType) {
        HostVO host = new HostVO(hostId, "host-" + hostId, Host.Type.Routing, "10.0.0." + hostId,
                "255.255.255.0", "00:00:00:00:00:01", "10.0.1." + hostId, "255.255.255.0",
                "00:00:00:00:00:02", "10.0.2." + hostId, "255.255.255.0", "00:00:00:00:00:03",
                "guid-" + hostId, Status.Up, "1.0", null, null, DATA_CENTER_ID, 1L, 1L, 0L,
                null, null, null, 0L, null);
        host.setHypervisorType(hypervisorType);
        return host;
    }

    private VolumeVO readyVolume(ImageFormat format, String path, String chainInfo) {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getState()).thenReturn(Volume.State.Ready);
        when(volume.getFormat()).thenReturn(format);
        if (ImageFormat.OVA.equals(format)) {
            when(volume.getChainInfo()).thenReturn(chainInfo);
        } else {
            when(volume.getPath()).thenReturn(path);
        }
        return volume;
    }

    private VolumeVO nonReadyVolume() {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getState()).thenReturn(Volume.State.Allocated);
        return volume;
    }

    private GetVolumeStatsAnswer statsAnswer(String volumeUuid, long physicalSize, long virtualSize) {
        HashMap<String, VolumeStatsEntry> stats = new HashMap<>();
        stats.put(volumeUuid, new VolumeStatsEntry(volumeUuid, physicalSize, virtualSize));
        GetVolumeStatsCommand command = new GetVolumeStatsCommand(POOL_TYPE, POOL_UUID, Collections.singletonList(volumeUuid));
        return new GetVolumeStatsAnswer(command, "", stats);
    }
}
