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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.engine.subsystem.api.storage.StoragePoolAllocator;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@RunWith(MockitoJUnitRunner.class)
public class VmDiskOfferingSuitabilityServiceImplTest {

    @InjectMocks
    private VmDiskOfferingSuitabilityServiceImpl service;

    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private ClusterDao clusterDao;
    @Mock
    private DiskOfferingDao diskOfferingDao;
    @Mock
    private StoragePoolAllocator storagePoolAllocator;

    @Mock
    private VMInstanceVO vm;
    @Mock
    private VirtualMachineProfile profile;
    @Mock
    private DiskOfferingVO diskOffering;
    @Mock
    private StoragePool storagePool;
    @Mock
    private HostVO host;
    @Mock
    private HostVO lastHost;
    @Mock
    private VolumeVO volume;
    @Mock
    private StoragePoolVO storagePoolVo;

    private final long vmId = 1L;
    private final long zoneId = 2L;
    private final long podId = 3L;
    private final long accountId = 4L;
    private final long domainId = 5L;
    private final long currentHostId = 6L;
    private final long lastHostId = 7L;
    private final long clusterId = 8L;
    private final long lastHostClusterId = 9L;
    private final long poolId = 10L;
    private final long diskOfferingId = 11L;

    @Before
    public void setUp() {
        service.setStoragePoolAllocators(List.of(storagePoolAllocator));
        when(vm.getId()).thenReturn(vmId);
    }

    @Test
    public void isDiskOfferingSuitableForVmReturnsTrueWhenAllocatorFindsPool() {
        configureVmForDiskSuitability();
        when(diskOfferingDao.findById(diskOfferingId)).thenReturn(diskOffering);
        when(storagePool.getName()).thenReturn("pool");
        when(storagePoolAllocator.allocateToPool(any(DiskProfile.class), eq(profile), any(DeploymentPlan.class), any(ExcludeList.class), eq(1)))
                .thenReturn(List.of(storagePool));

        boolean result = service.isDiskOfferingSuitableForVm(vm, profile, podId, clusterId, currentHostId, diskOfferingId);

        assertTrue(result);
        ArgumentCaptor<DeploymentPlan> planCaptor = ArgumentCaptor.forClass(DeploymentPlan.class);
        verify(storagePoolAllocator).allocateToPool(any(DiskProfile.class), eq(profile), planCaptor.capture(), any(ExcludeList.class), eq(1));
        DeploymentPlan plan = planCaptor.getValue();
        assertEquals(zoneId, plan.getDataCenterId());
        assertEquals(podId, plan.getPodId().longValue());
        assertEquals(clusterId, plan.getClusterId().longValue());
        assertEquals(currentHostId, plan.getHostId().longValue());
    }

    @Test
    public void isDiskOfferingSuitableForVmReturnsFalseWhenAllocatorReturnsEmpty() {
        configureVmForDiskSuitability();
        when(diskOfferingDao.findById(diskOfferingId)).thenReturn(diskOffering);
        when(storagePoolAllocator.allocateToPool(any(DiskProfile.class), eq(profile), any(DeploymentPlan.class), any(ExcludeList.class), eq(1)))
                .thenReturn(new ArrayList<>());

        assertFalse(service.isDiskOfferingSuitableForVm(vm, profile, podId, clusterId, currentHostId, diskOfferingId));
    }

    @Test
    public void getDiskOfferingSuitabilityForVmReturnsEmptyWhenDeployVmDetailExists() {
        when(vmInstanceDao.findById(vmId)).thenReturn(vm);
        when(vmInstanceDetailsDao.findDetail(vmId, VmDetailConstants.DEPLOY_VM)).thenReturn(new VMInstanceDetailVO());

        Map<Long, Boolean> result = service.getDiskOfferingSuitabilityForVm(vmId, List.of(1L, 2L));

        assertNotNull(result);
        assertTrue(result.isEmpty());
        verify(hostDao, never()).findById(anyLong());
        verify(storagePoolAllocator, never()).allocateToPool(any(), any(), any(), any(), anyInt());
    }

    @Test
    public void getDiskOfferingSuitabilityForVmChecksEachOfferingWithResolvedClusterAndHost() {
        configureVmForDiskSuitability();
        when(vmInstanceDao.findById(vmId)).thenReturn(vm);
        when(vm.getHostId()).thenReturn(currentHostId);
        when(hostDao.findById(currentHostId)).thenReturn(host);
        when(host.getClusterId()).thenReturn(clusterId);
        ClusterVO cluster = Mockito.mock(ClusterVO.class);
        when(cluster.getPodId()).thenReturn(podId);
        when(clusterDao.findById(clusterId)).thenReturn(cluster);
        DiskOfferingVO firstOffering = Mockito.mock(DiskOfferingVO.class);
        DiskOfferingVO secondOffering = Mockito.mock(DiskOfferingVO.class);
        when(diskOfferingDao.findById(1L)).thenReturn(firstOffering);
        when(diskOfferingDao.findById(2L)).thenReturn(secondOffering);
        when(storagePool.getName()).thenReturn("pool");
        when(storagePoolAllocator.allocateToPool(any(DiskProfile.class), any(VirtualMachineProfile.class), any(DeploymentPlan.class), any(ExcludeList.class), eq(1)))
                .thenReturn(new ArrayList<>())
                .thenReturn(List.of(storagePool));

        Map<Long, Boolean> result = service.getDiskOfferingSuitabilityForVm(vmId, List.of(1L, 2L));

        assertEquals(2, result.size());
        assertFalse(result.get(1L));
        assertTrue(result.get(2L));
        verify(diskOfferingDao).findById(1L);
        verify(diskOfferingDao).findById(2L);
    }

    @Test
    public void findClusterAndHostIdForVmUsesCurrentHostWhenAllowed() {
        when(vm.getHostId()).thenReturn(currentHostId);
        when(hostDao.findById(currentHostId)).thenReturn(host);
        when(host.getClusterId()).thenReturn(clusterId);

        Pair<Long, Long> result = service.findClusterAndHostIdForVm(vm, false);

        assertEquals(clusterId, result.first().longValue());
        assertEquals(currentHostId, result.second().longValue());
    }

    @Test
    public void findClusterAndHostIdForVmSkipsCurrentHostForStartingVmWhenRequested() {
        when(vm.getState()).thenReturn(State.Starting);
        when(vm.getLastHostId()).thenReturn(lastHostId);
        when(hostDao.findById(lastHostId)).thenReturn(lastHost);
        when(lastHost.getClusterId()).thenReturn(lastHostClusterId);

        Pair<Long, Long> result = service.findClusterAndHostIdForVm(vm, true);

        assertEquals(lastHostClusterId, result.first().longValue());
        assertEquals(lastHostId, result.second().longValue());
        verify(hostDao, never()).findById(currentHostId);
    }

    @Test
    public void findClusterAndHostIdForVmUsesLastHostWhenCurrentHostMissing() {
        when(vm.getHostId()).thenReturn(null);
        when(vm.getLastHostId()).thenReturn(lastHostId);
        when(hostDao.findById(lastHostId)).thenReturn(lastHost);
        when(lastHost.getClusterId()).thenReturn(lastHostClusterId);

        Pair<Long, Long> result = service.findClusterAndHostIdForVm(vm, false);

        assertEquals(lastHostClusterId, result.first().longValue());
        assertEquals(lastHostId, result.second().longValue());
    }

    @Test
    public void findClusterAndHostIdForVmFallsBackToReadyVolumePoolClusterAndHost() {
        configureReadyVolumeWithCluster();
        when(hostDao.findHypervisorHostInCluster(clusterId)).thenReturn(List.of(host));
        when(host.getId()).thenReturn(currentHostId);

        Pair<Long, Long> result = service.findClusterAndHostIdForVm(vm, false);

        assertEquals(clusterId, result.first().longValue());
        assertEquals(currentHostId, result.second().longValue());
    }

    @Test
    public void findClusterAndHostIdForVmFromIdReturnsNullPairWhenVmMissing() {
        when(vmInstanceDao.findById(vmId)).thenReturn(null);

        Pair<Long, Long> result = service.findClusterAndHostIdForVm(vmId);

        assertNull(result.first());
        assertNull(result.second());
    }

    @Test
    public void findClusterAndHostIdForVmFromVolumesIgnoresNonReadyVolumes() {
        when(volumeDao.findByInstance(vmId)).thenReturn(List.of(volume));
        when(volume.getState()).thenReturn(Volume.State.Allocated);

        Pair<Long, Long> result = service.findClusterAndHostIdForVmFromVolumes(vmId);

        assertNull(result.first());
        assertNull(result.second());
        verify(storagePoolDao, never()).findById(anyLong());
    }

    @Test
    public void findClusterAndHostIdForVmFromVolumesIgnoresPoolsWithoutCluster() {
        when(volumeDao.findByInstance(vmId)).thenReturn(List.of(volume));
        when(volume.getState()).thenReturn(Volume.State.Ready);
        when(volume.getPoolId()).thenReturn(poolId);
        when(storagePoolDao.findById(poolId)).thenReturn(storagePoolVo);
        when(storagePoolVo.getClusterId()).thenReturn(null);

        Pair<Long, Long> result = service.findClusterAndHostIdForVmFromVolumes(vmId);

        assertNull(result.first());
        assertNull(result.second());
        verify(hostDao, never()).findHypervisorHostInCluster(anyLong());
    }

    @Test
    public void findClusterAndHostIdForVmFromVolumesKeepsClusterWhenNoHostFound() {
        configureReadyVolumeWithCluster();
        when(hostDao.findHypervisorHostInCluster(clusterId)).thenReturn(new ArrayList<>());

        Pair<Long, Long> result = service.findClusterAndHostIdForVmFromVolumes(vmId);

        assertEquals(clusterId, result.first().longValue());
        assertNull(result.second());
    }

    private void configureVmForDiskSuitability() {
        when(vm.getDataCenterId()).thenReturn(zoneId);
        when(vm.getAccountId()).thenReturn(accountId);
        when(vm.getDomainId()).thenReturn(domainId);
        when(profile.getHypervisorType()).thenReturn(HypervisorType.KVM);
    }

    private void configureReadyVolumeWithCluster() {
        when(volumeDao.findByInstance(vmId)).thenReturn(List.of(volume));
        when(volume.getState()).thenReturn(Volume.State.Ready);
        when(volume.getPoolId()).thenReturn(poolId);
        when(storagePoolDao.findById(poolId)).thenReturn(storagePoolVo);
        when(storagePoolVo.getClusterId()).thenReturn(clusterId);
    }
}
