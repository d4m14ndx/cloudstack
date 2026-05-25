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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.ScopeType;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.StoragePoolAndAccessGroupMapDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDao;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;

/**
 * Focused unit tests for {@link StorageAccessGroupServiceImpl} — slice 7.
 *
 * <p>All 11 injected DAOs/managers are mocked; the slice itself is wrapped in a
 * {@code @Spy} so individual leaf methods can be selectively stubbed when testing
 * orchestrator logic.
 */
@RunWith(MockitoJUnitRunner.class)
public class StorageAccessGroupServiceImplTest {

    @Mock
    protected PrimaryDataStoreDao storagePoolDao;
    @Mock
    protected StoragePoolAndAccessGroupMapDao storagePoolAccessGroupMapDao;
    @Mock
    protected StoragePoolHostDao storagePoolHostDao;
    @Mock
    protected StoragePoolTagsDao storagePoolTagsDao;
    @Mock
    protected StorageManager storageManager;
    @Mock
    protected HostDao hostDao;
    @Mock
    protected VMInstanceDao vmDao;
    @Mock
    protected VolumeDao volumeDao;
    @Mock
    protected ClusterDao clusterDao;
    @Mock
    protected HostPodDao podDao;
    @Mock
    protected DataCenterDao dataCenterDao;
    @Mock
    protected HostLookupService hostLookupService;

    @Spy
    @InjectMocks
    private StorageAccessGroupServiceImpl service;

    // ------------------------------ helpers -------------------------------

    private HostVO makeHost(long id, long dcId, long podId, long clusterId) {
        HostVO h = Mockito.mock(HostVO.class);
        Mockito.when(h.getId()).thenReturn(id);
        Mockito.when(h.getDataCenterId()).thenReturn(dcId);
        Mockito.when(h.getPodId()).thenReturn(podId);
        Mockito.when(h.getClusterId()).thenReturn(clusterId);
        return h;
    }

    private StoragePoolVO makePool(long id, ScopeType scope, long dcId, Long podId, Long clusterId) {
        StoragePoolVO p = Mockito.mock(StoragePoolVO.class);
        Mockito.when(p.getId()).thenReturn(id);
        Mockito.when(p.getScope()).thenReturn(scope);
        Mockito.when(p.getDataCenterId()).thenReturn(dcId);
        Mockito.when(p.getPodId()).thenReturn(podId);
        Mockito.when(p.getClusterId()).thenReturn(clusterId);
        return p;
    }

    @Before
    public void setup() {
        // nothing beyond @Spy @InjectMocks
    }

    // =====================================================================
    // 1. filterHostsBasedOnStorageAccessGroups
    // =====================================================================

    @Test
    public void filterHostsBasedOnStorageAccessGroups_emptyGroups_returnsAllHosts() {
        HostVO h1 = makeHost(1L, 1L, 1L, 1L);
        HostVO h2 = makeHost(2L, 1L, 1L, 1L);
        Mockito.when(storageManager.getStorageAccessGroups(null, null, null, 1L)).thenReturn(new String[]{"g1"});
        Mockito.when(storageManager.getStorageAccessGroups(null, null, null, 2L)).thenReturn(new String[]{"g2"});

        List<HostVO> result = service.filterHostsBasedOnStorageAccessGroups(Arrays.asList(h1, h2), Collections.emptyList());

        Assert.assertEquals(2, result.size());
        Assert.assertTrue(result.containsAll(Arrays.asList(h1, h2)));
    }

    @Test
    public void filterHostsBasedOnStorageAccessGroups_matchingHost_includedOnly() {
        HostVO h1 = makeHost(1L, 1L, 1L, 1L);
        HostVO h2 = makeHost(2L, 1L, 1L, 1L);
        Mockito.when(storageManager.getStorageAccessGroups(null, null, null, 1L)).thenReturn(new String[]{"g1"});
        Mockito.when(storageManager.getStorageAccessGroups(null, null, null, 2L)).thenReturn(new String[]{"g3"});

        List<HostVO> result = service.filterHostsBasedOnStorageAccessGroups(
                Arrays.asList(h1, h2), Arrays.asList("g1", "g2"));

        Assert.assertEquals(1, result.size());
        Assert.assertTrue(result.contains(h1));
        Assert.assertFalse(result.contains(h2));
    }

    @Test
    public void filterHostsBasedOnStorageAccessGroups_noIntersection_returnsEmpty() {
        HostVO h1 = makeHost(1L, 1L, 1L, 1L);
        Mockito.when(storageManager.getStorageAccessGroups(null, null, null, 1L)).thenReturn(new String[]{"g5"});

        List<HostVO> result = service.filterHostsBasedOnStorageAccessGroups(
                Collections.singletonList(h1), Arrays.asList("g1", "g2"));

        Assert.assertTrue(result.isEmpty());
    }

    // =====================================================================
    // 2. getStoragePoolsByAccessGroups
    // =====================================================================

    @Test
    public void getStoragePoolsByAccessGroups_includeEmpty_returnsBothTaggedAndEmpty() {
        StoragePoolVO taggedCluster = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO taggedZone = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO emptyCluster = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO emptyZone = Mockito.mock(StoragePoolVO.class);

        Mockito.when(storagePoolDao.findPoolsByAccessGroupsForHostConnection(
                anyLong(), anyLong(), anyLong(), eq(ScopeType.CLUSTER), any(String[].class)))
                .thenReturn(Collections.singletonList(taggedCluster));
        Mockito.when(storagePoolDao.findZoneWideStoragePoolsByAccessGroupsForHostConnection(
                anyLong(), any(String[].class)))
                .thenReturn(Collections.singletonList(taggedZone));
        Mockito.when(storagePoolDao.findStoragePoolsByEmptyStorageAccessGroups(
                anyLong(), anyLong(), anyLong(), eq(ScopeType.CLUSTER), isNull()))
                .thenReturn(Collections.singletonList(emptyCluster));
        Mockito.when(storagePoolDao.findStoragePoolsByEmptyStorageAccessGroups(
                anyLong(), isNull(), isNull(), eq(ScopeType.ZONE), isNull()))
                .thenReturn(Collections.singletonList(emptyZone));

        List<StoragePoolVO> result = service.getStoragePoolsByAccessGroups(1L, 1L, 1L, new String[]{"g1"}, true);

        Assert.assertTrue(result.contains(taggedCluster));
        Assert.assertTrue(result.contains(taggedZone));
        Assert.assertTrue(result.contains(emptyCluster));
        Assert.assertTrue(result.contains(emptyZone));
        Assert.assertEquals(4, result.size());
    }

    @Test
    public void getStoragePoolsByAccessGroups_excludeEmpty_returnsOnlyTaggedPools() {
        StoragePoolVO tagged = Mockito.mock(StoragePoolVO.class);
        Mockito.when(storagePoolDao.findPoolsByAccessGroupsForHostConnection(
                anyLong(), anyLong(), anyLong(), eq(ScopeType.CLUSTER), any(String[].class)))
                .thenReturn(Collections.singletonList(tagged));
        Mockito.when(storagePoolDao.findZoneWideStoragePoolsByAccessGroupsForHostConnection(
                anyLong(), any(String[].class)))
                .thenReturn(Collections.emptyList());

        List<StoragePoolVO> result = service.getStoragePoolsByAccessGroups(1L, 1L, 1L, new String[]{"g1"}, false);

        Assert.assertEquals(1, result.size());
        Assert.assertTrue(result.contains(tagged));
        Mockito.verify(storagePoolDao, Mockito.never()).findStoragePoolsByEmptyStorageAccessGroups(
                anyLong(), any(), any(), any(), any());
    }

    // =====================================================================
    // 3. connectHostToStoragePool / disconnectHostFromStoragePool
    // =====================================================================

    @Test
    public void connectHostToStoragePool_callsStorageManager() throws Exception {
        HostVO host = Mockito.mock(HostVO.class);
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(pool.getId()).thenReturn(10L);

        service.connectHostToStoragePool(host, pool);

        Mockito.verify(storageManager).connectHostToSharedPool(host, 10L);
    }

    @Test
    public void disconnectHostFromStoragePool_callsStorageManagerAndDeletesRecord() throws Exception {
        HostVO host = Mockito.mock(HostVO.class);
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(host.getId()).thenReturn(5L);
        Mockito.when(pool.getId()).thenReturn(10L);

        service.disconnectHostFromStoragePool(host, pool);

        Mockito.verify(storageManager).disconnectHostFromSharedPool(host, pool);
        Mockito.verify(storagePoolHostDao).deleteStoragePoolHostDetails(5L, 10L);
    }

    // =====================================================================
    // 4. checkIfAnyVolumesInUse
    // =====================================================================

    @Test
    public void checkIfAnyVolumesInUse_succeeds_whenSagsToDeleteEmpty() {
        HostVO host = Mockito.mock(HostVO.class);
        // should not throw
        service.checkIfAnyVolumesInUse(Collections.emptyList(), Collections.emptyList(), host);
    }

    @Test
    public void checkIfAnyVolumesInUse_succeeds_whenNoVolumesUseDeletedSags() {
        HostVO host = Mockito.mock(HostVO.class);
        Mockito.when(host.getId()).thenReturn(1L);
        Mockito.doReturn(Collections.emptyList())
                .when(service).listOfVolumesUsingTheStorageAccessGroups(any(), anyLong(), isNull(), isNull(), isNull());

        service.checkIfAnyVolumesInUse(Arrays.asList("sagA"), Arrays.asList("sagB"), host);
        // no exception
    }

    @Test(expected = CloudRuntimeException.class)
    public void checkIfAnyVolumesInUse_throws_whenVolumeHasNoPoolCoverage() {
        HostVO host = Mockito.mock(HostVO.class);
        Mockito.when(host.getId()).thenReturn(1L);
        Mockito.when(host.getDataCenterId()).thenReturn(2L);
        Mockito.when(host.getPodId()).thenReturn(3L);
        Mockito.when(host.getClusterId()).thenReturn(4L);

        VolumeVO vol = Mockito.mock(VolumeVO.class);
        Mockito.doReturn(new ArrayList<>(Collections.singletonList(vol)))
                .when(service).listOfVolumesUsingTheStorageAccessGroups(any(), anyLong(), isNull(), isNull(), isNull());
        Mockito.doReturn(Collections.emptyList())
                .when(service).getStoragePoolsByAccessGroups(anyLong(), anyLong(), anyLong(), any(String[].class), anyBoolean());

        service.checkIfAnyVolumesInUse(Arrays.asList("sagA"), Arrays.asList("sagB"), host);
    }

    // =====================================================================
    // 5. updateConnectionsBetweenHostsAndStoragePools
    // =====================================================================

    @Test
    public void updateConnectionsBetweenHostsAndStoragePools_connectsNewPool() throws Exception {
        HostVO host = makeHost(1L, 1L, 1L, 1L);
        StoragePoolVO newPool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(newPool.getId()).thenReturn(30L);

        // Before: no pools connected
        Mockito.when(storageManager.findStoragePoolsConnectedToHost(1L))
                .thenReturn(Collections.emptyList());

        // After: new pool should connect
        Mockito.doReturn(Collections.singletonList(newPool))
                .when(service).getStoragePoolsByAccessGroups(anyLong(), anyLong(), anyLong(), any(String[].class), anyBoolean());

        service.updateConnectionsBetweenHostsAndStoragePools(
                Collections.singletonMap(host, Arrays.asList("sagA")));

        Mockito.verify(storageManager).connectHostToSharedPool(eq(host), eq(30L));
    }

    // =====================================================================
    // 6. getEligibleUpHostsInClusterForStorageConnection
    // =====================================================================

    @Test
    public void getEligibleUpHostsInClusterForStorageConnection_filtersByPoolSags() {
        org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo store =
                Mockito.mock(org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo.class);
        Mockito.when(store.getId()).thenReturn(1L);
        Mockito.when(store.getClusterId()).thenReturn(1L);
        Mockito.when(store.getPodId()).thenReturn(1L);
        Mockito.when(store.getDataCenterId()).thenReturn(1L);

        HostVO h1 = makeHost(1L, 1L, 1L, 1L);
        HostVO h2 = makeHost(2L, 1L, 1L, 1L);
        Mockito.when(hostLookupService.listAllUpHosts(eq(Host.Type.Routing), anyLong(), anyLong(), anyLong()))
                .thenReturn(Arrays.asList(h1, h2));
        Mockito.when(storagePoolAccessGroupMapDao.getStorageAccessGroups(1L)).thenReturn(Arrays.asList("g1"));
        Mockito.when(storageManager.getStorageAccessGroups(null, null, null, 1L)).thenReturn(new String[]{"g1"});
        Mockito.when(storageManager.getStorageAccessGroups(null, null, null, 2L)).thenReturn(new String[]{"g2"});

        List<HostVO> result = service.getEligibleUpHostsInClusterForStorageConnection(store);

        Assert.assertEquals(1, result.size());
        Assert.assertTrue(result.contains(h1));
    }

    @Test(expected = CloudRuntimeException.class)
    public void getEligibleUpHostsInClusterForStorageConnection_throwsWhenNoHosts() {
        org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo store =
                Mockito.mock(org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo.class);
        Mockito.when(store.getId()).thenReturn(1L);
        Mockito.when(store.getClusterId()).thenReturn(1L);
        Mockito.when(store.getPodId()).thenReturn(1L);
        Mockito.when(store.getDataCenterId()).thenReturn(1L);

        Mockito.when(hostLookupService.listAllUpHosts(any(), anyLong(), anyLong(), anyLong()))
                .thenReturn(Collections.emptyList());

        service.getEligibleUpHostsInClusterForStorageConnection(store);
    }

    // =====================================================================
    // 7. getEligibleUpAndEnabledHostsInZoneForStorageConnection
    // =====================================================================

    @Test
    public void getEligibleUpAndEnabledHostsInZoneForStorageConnection_filtersByHypervisorAndSags() {
        org.apache.cloudstack.engine.subsystem.api.storage.DataStore ds =
                Mockito.mock(org.apache.cloudstack.engine.subsystem.api.storage.DataStore.class);
        Mockito.when(ds.getId()).thenReturn(2L);

        HostVO h1 = makeHost(1L, 5L, 1L, 1L);
        Mockito.when(hostLookupService.listAllUpAndEnabledHostsInOneZoneByHypervisor(
                eq(HypervisorType.KVM), eq(5L))).thenReturn(Collections.singletonList(h1));
        Mockito.when(storagePoolAccessGroupMapDao.getStorageAccessGroups(2L)).thenReturn(Collections.emptyList());
        Mockito.when(storageManager.getStorageAccessGroups(null, null, null, 1L)).thenReturn(new String[]{});

        List<HostVO> result = service.getEligibleUpAndEnabledHostsInZoneForStorageConnection(ds, 5L, HypervisorType.KVM);

        Assert.assertEquals(1, result.size());
        Assert.assertTrue(result.contains(h1));
    }

    // =====================================================================
    // 8. updateHostStorageAccessGroups
    // =====================================================================

    @Test
    public void updateHostStorageAccessGroups_persistsNewSagsAndUpdatesConnections() {
        HostVO host = makeHost(1L, 1L, 1L, 1L);
        Mockito.when(hostDao.findById(1L)).thenReturn(host);
        Mockito.when(host.getStorageAccessGroups()).thenReturn(null);
        Mockito.when(storageManager.getStorageAccessGroups(null, null, 1L, null)).thenReturn(new String[]{});

        Mockito.doNothing().when(service).checkIfAnyVolumesInUse(any(), any(), any());
        Mockito.doNothing().when(service).updateConnectionsBetweenHostsAndStoragePools(any());

        service.updateHostStorageAccessGroups(1L, Arrays.asList("sagNew"));

        Mockito.verify(host).setStorageAccessGroups("sagNew");
        Mockito.verify(hostDao).update(eq(1L), eq(host));
    }

    // =====================================================================
    // 9. updateZoneStorageAccessGroups
    // =====================================================================

    @Test
    public void updateZoneStorageAccessGroups_propagatesToPodsAndUpdatesConnections() {
        long zoneId = 10L;
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getId()).thenReturn(zoneId);
        Mockito.when(zone.getStorageAccessGroups()).thenReturn(null);
        Mockito.when(dataCenterDao.findById(zoneId)).thenReturn(zone);

        HostPodVO pod = Mockito.mock(HostPodVO.class);
        Mockito.when(pod.getId()).thenReturn(20L);
        Mockito.when(pod.getStorageAccessGroups()).thenReturn(null);
        Mockito.when(podDao.listByDataCenterId(zoneId)).thenReturn(Collections.singletonList(pod));
        Mockito.when(podDao.findById(20L)).thenReturn(pod);
        Mockito.when(clusterDao.listByPodId(20L)).thenReturn(Collections.emptyList());

        HostVO host = makeHost(1L, zoneId, 20L, 30L);
        Mockito.when(hostDao.findHypervisorHostInPod(20L)).thenReturn(Collections.singletonList(host));
        Mockito.when(storageManager.getStorageAccessGroups(null, null, null, 1L)).thenReturn(new String[]{});

        Mockito.doNothing().when(service).updateConnectionsBetweenHostsAndStoragePools(any());

        service.updateZoneStorageAccessGroups(zoneId, Arrays.asList("sagNew"));

        Mockito.verify(service).updateConnectionsBetweenHostsAndStoragePools(any());
    }

    // =====================================================================
    // 10. updateClusterStorageAccessGroups
    // =====================================================================

    @Test
    public void updateClusterStorageAccessGroups_propagatesToHosts() {
        long clusterId = 3L;
        ClusterVO cluster = Mockito.mock(ClusterVO.class);
        Mockito.when(cluster.getId()).thenReturn(clusterId);
        Mockito.when(cluster.getStorageAccessGroups()).thenReturn(null);
        Mockito.when(clusterDao.findById(clusterId)).thenReturn(cluster);

        HostVO host = makeHost(1L, 1L, 1L, clusterId);
        Mockito.when(hostDao.findHypervisorHostInCluster(clusterId)).thenReturn(Collections.singletonList(host));
        Mockito.when(storageManager.getStorageAccessGroups(null, null, null, 1L)).thenReturn(new String[]{});

        Mockito.doNothing().when(service).updateConnectionsBetweenHostsAndStoragePools(any());

        service.updateClusterStorageAccessGroups(clusterId, Arrays.asList("sagNew"));

        Mockito.verify(service).updateConnectionsBetweenHostsAndStoragePools(any());
    }

    // =====================================================================
    // 11. updateStoragePoolConnectionsOnHosts (cluster scope)
    // =====================================================================

    @Test
    public void updateStoragePoolConnectionsOnHosts_clusterScope_connectsMatchingHosts() throws Exception {
        StoragePoolVO pool = makePool(1L, ScopeType.CLUSTER, 1L, 1L, 1L);
        Mockito.when(storagePoolDao.findById(1L)).thenReturn(pool);

        HostVO host = makeHost(2L, 1L, 1L, 1L);
        Mockito.when(hostLookupService.listAllUpHosts(eq(Host.Type.Routing), anyLong(), anyLong(), anyLong()))
                .thenReturn(Collections.singletonList(host));
        Mockito.when(storageManager.getStorageAccessGroups(null, null, null, 2L)).thenReturn(new String[]{"sagA"});
        Mockito.when(storagePoolHostDao.findByPoolHost(1L, 2L)).thenReturn(null);

        Mockito.doNothing().when(service).connectHostToStoragePool(any(), any());

        service.updateStoragePoolConnectionsOnHosts(1L, Arrays.asList("sagA"));

        Mockito.verify(service).connectHostToStoragePool(eq(host), eq(pool));
    }

    @Test
    public void updateStoragePoolConnectionsOnHosts_zoneScope_connectsAllWhenNoSags() throws Exception {
        StoragePoolVO pool = makePool(1L, ScopeType.ZONE, 1L, null, null);
        Mockito.when(storagePoolDao.findById(1L)).thenReturn(pool);

        HostVO host = makeHost(2L, 1L, 1L, 1L);
        Mockito.when(hostLookupService.listAllUpHosts(eq(Host.Type.Routing), isNull(), isNull(), anyLong()))
                .thenReturn(Collections.singletonList(host));
        Mockito.when(storageManager.getStorageAccessGroups(null, null, null, 2L)).thenReturn(new String[]{});
        Mockito.when(storagePoolHostDao.findByPoolHost(1L, 2L)).thenReturn(null);

        Mockito.doNothing().when(service).connectHostToStoragePool(any(), any());

        service.updateStoragePoolConnectionsOnHosts(1L, Collections.emptyList());

        Mockito.verify(service).connectHostToStoragePool(eq(host), eq(pool));
    }

    @Test(expected = CloudRuntimeException.class)
    public void updateStoragePoolConnectionsOnHosts_throws_whenConflictingHostHasRunningVolume() {
        StoragePoolVO pool = makePool(1L, ScopeType.ZONE, 1L, null, null);
        Mockito.when(storagePoolDao.findById(1L)).thenReturn(pool);

        HostVO host = makeHost(2L, 1L, 1L, 1L);
        Mockito.when(hostLookupService.listAllUpHosts(any(), isNull(), isNull(), anyLong()))
                .thenReturn(Collections.singletonList(host));
        // host has "sagB" but pool has "sagA" — host should be disconnected
        Mockito.when(storageManager.getStorageAccessGroups(null, null, null, 2L)).thenReturn(new String[]{"sagB"});
        Mockito.when(storagePoolHostDao.findByPoolHost(1L, 2L)).thenReturn(Mockito.mock(StoragePoolHostVO.class));

        // host is using the pool
        Mockito.doReturn(Collections.singletonList(2L)).when(service).listOfHostIdsUsingTheStoragePool(1L);

        VolumeVO vol = Mockito.mock(VolumeVO.class);
        Mockito.when(vol.getInstanceId()).thenReturn(10L);
        Mockito.when(volumeDao.findNonDestroyedVolumesByPoolId(1L)).thenReturn(Collections.singletonList(vol));

        com.cloud.vm.VMInstanceVO vm = Mockito.mock(com.cloud.vm.VMInstanceVO.class);
        Mockito.when(vm.getHostId()).thenReturn(2L);
        Mockito.when(vmDao.findById(10L)).thenReturn(vm);
        Mockito.when(hostDao.findById(2L)).thenReturn(host);

        service.updateStoragePoolConnectionsOnHosts(1L, Collections.singletonList("sagA"));
    }
}
