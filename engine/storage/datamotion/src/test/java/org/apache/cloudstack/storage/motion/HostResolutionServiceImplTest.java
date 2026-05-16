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
package org.apache.cloudstack.storage.motion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.dao.ClusterDao;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.ResourceState;
import com.cloud.storage.DataStoreRole;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Focused tests for {@link HostResolutionServiceImpl} -- the Phase 4
 * extraction of host-resolution helpers from
 * {@link StorageSystemDataMotionStrategy}.
 *
 * Behavior here is also exercised indirectly through the strategy's
 * delegating wrappers; these tests target the service directly so future
 * refactors of the strategy cannot silently drop coverage.
 */
@RunWith(MockitoJUnitRunner.class)
public class HostResolutionServiceImplTest {

    @Mock
    private ClusterDao clusterDao;

    @Mock
    private HostDao hostDao;

    @Mock
    private DataStoreManager dataStoreMgr;

    @Mock
    private ResourceManager resourceManager;

    @InjectMocks
    private HostResolutionServiceImpl service;

    private static final long ZONE_ID = 42L;

    private HostVO host(long id, long clusterId, ResourceState state) {
        HostVO h = Mockito.mock(HostVO.class);
        Mockito.lenient().when(h.getId()).thenReturn(id);
        Mockito.lenient().when(h.getClusterId()).thenReturn(clusterId);
        Mockito.lenient().when(h.getResourceState()).thenReturn(state);
        return h;
    }

    private SnapshotInfo snapshot(DataStoreRole storeRole, HypervisorType hyper) {
        SnapshotInfo s = Mockito.mock(SnapshotInfo.class);
        DataStore ds = Mockito.mock(DataStore.class);
        Mockito.when(ds.getRole()).thenReturn(storeRole);
        Mockito.when(s.getDataStore()).thenReturn(ds);
        Mockito.when(s.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(s.getHypervisorType()).thenReturn(hyper);
        return s;
    }

    private VolumeInfo volume(DataStoreRole storeRole) {
        VolumeInfo v = Mockito.mock(VolumeInfo.class);
        DataStore ds = Mockito.mock(DataStore.class);
        Mockito.when(ds.getRole()).thenReturn(storeRole);
        Mockito.when(v.getDataStore()).thenReturn(ds);
        Mockito.when(v.getDataCenterId()).thenReturn(ZONE_ID);
        return v;
    }

    // ---------------------------------------------------------------------
    // getHost(List<HostVO>, boolean) -- the core filter / shuffle helper
    // ---------------------------------------------------------------------

    @Test
    public void getHostFromListReturnsNullWhenInputIsNull() {
        assertNull(service.getHost((List<HostVO>) null, false));
    }

    @Test
    public void getHostFromListReturnsNullWhenAllHostsDisabled() {
        List<HostVO> hosts = new ArrayList<>(Arrays.asList(
                host(1L, 1L, ResourceState.Disabled),
                host(2L, 1L, ResourceState.Maintenance)));

        assertNull(service.getHost(hosts, false));
    }

    @Test
    public void getHostFromListReturnsSingleEnabledHostWithoutResignCheck() {
        HostVO enabled = host(7L, 1L, ResourceState.Enabled);
        List<HostVO> hosts = new ArrayList<>(Arrays.asList(enabled));

        HostVO result = service.getHost(hosts, false);

        assertSame(enabled, result);
    }

    @Test
    public void getHostFromListSkipsDisabledHostsWithoutResignCheck() {
        HostVO disabled = host(1L, 1L, ResourceState.Disabled);
        HostVO enabled = host(2L, 1L, ResourceState.Enabled);
        List<HostVO> hosts = new ArrayList<>(Arrays.asList(disabled, enabled));

        HostVO result = service.getHost(hosts, false);

        assertSame(enabled, result);
    }

    @Test
    public void getHostFromListReturnsHostWhenClusterSupportsResign() {
        HostVO enabled = host(5L, 99L, ResourceState.Enabled);
        Mockito.when(clusterDao.getSupportsResigning(99L)).thenReturn(true);

        HostVO result = service.getHost(new ArrayList<>(Arrays.asList(enabled)), true);

        assertSame(enabled, result);
    }

    @Test
    public void getHostFromListReturnsNullWhenNoClusterSupportsResign() {
        HostVO enabled = host(5L, 99L, ResourceState.Enabled);
        Mockito.when(clusterDao.getSupportsResigning(99L)).thenReturn(false);

        assertNull(service.getHost(new ArrayList<>(Arrays.asList(enabled)), true));
    }

    @Test
    public void getHostFromListChecksClusterOnlyOncePerCluster() {
        // Two enabled hosts in the same non-resigning cluster -- cluster
        // should only be queried once.
        HostVO h1 = host(5L, 99L, ResourceState.Enabled);
        HostVO h2 = host(6L, 99L, ResourceState.Enabled);
        // No host in another cluster -- expected to return null after one cluster lookup.
        Mockito.when(clusterDao.getSupportsResigning(99L)).thenReturn(false);

        assertNull(service.getHost(new ArrayList<>(Arrays.asList(h1, h2)), true));

        Mockito.verify(clusterDao, Mockito.times(1)).getSupportsResigning(99L);
    }

    @Test
    public void getHostFromListPrefersResigningClusterWhenRequested() {
        HostVO badCluster = host(1L, 10L, ResourceState.Enabled);
        HostVO goodCluster = host(2L, 20L, ResourceState.Enabled);
        Mockito.when(clusterDao.getSupportsResigning(10L)).thenReturn(false);
        Mockito.when(clusterDao.getSupportsResigning(20L)).thenReturn(true);

        // Pin order by passing a fixed list -- shuffle is in-place and may
        // visit either first, but the result must always be the resigning
        // cluster's host.
        for (int i = 0; i < 25; i++) {
            HostVO result = service.getHost(
                    new ArrayList<>(Arrays.asList(badCluster, goodCluster)), true);
            assertSame(goodCluster, result);
        }
    }

    // ---------------------------------------------------------------------
    // getHost(SnapshotInfo, HypervisorType, boolean)
    // ---------------------------------------------------------------------

    @Test
    public void getHostForSnapshotUsesResourceManagerWhenStoreIsPrimary() {
        SnapshotInfo snap = snapshot(DataStoreRole.Primary, HypervisorType.KVM);
        HostVO h = host(1L, 1L, ResourceState.Enabled);
        Mockito.when(resourceManager.getEligibleUpAndEnabledHostsInZoneForStorageConnection(
                snap.getDataStore(), ZONE_ID, HypervisorType.KVM))
                .thenReturn(new ArrayList<>(Arrays.asList(h)));

        assertSame(h, service.getHost(snap, HypervisorType.KVM, false));
        Mockito.verify(hostDao, Mockito.never())
                .listByDataCenterIdAndHypervisorType(ArgumentMatchers.anyLong(), ArgumentMatchers.any());
    }

    @Test
    public void getHostForSnapshotUsesHostDaoWhenStoreIsImage() {
        SnapshotInfo snap = snapshot(DataStoreRole.Image, HypervisorType.VMware);
        HostVO h = host(1L, 1L, ResourceState.Enabled);
        Mockito.when(hostDao.listByDataCenterIdAndHypervisorType(ZONE_ID, HypervisorType.VMware))
                .thenReturn(new ArrayList<>(Arrays.asList(h)));

        assertSame(h, service.getHost(snap, HypervisorType.VMware, false));
        Mockito.verify(resourceManager, Mockito.never())
                .getEligibleUpAndEnabledHostsInZoneForStorageConnection(
                        ArgumentMatchers.any(), ArgumentMatchers.anyLong(), ArgumentMatchers.any());
    }

    @Test
    public void getHostForSnapshotThrowsWhenZoneIdNull() {
        SnapshotInfo snap = Mockito.mock(SnapshotInfo.class);
        Mockito.when(snap.getDataCenterId()).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.getHost(snap, HypervisorType.KVM, false));
    }

    @Test
    public void getHostForSnapshotThrowsWhenHypervisorTypeNull() {
        SnapshotInfo snap = snapshot(DataStoreRole.Primary, HypervisorType.KVM);

        assertThrows(IllegalArgumentException.class,
                () -> service.getHost(snap, null, false));
    }

    // ---------------------------------------------------------------------
    // getHost(VolumeInfo, HypervisorType, boolean)
    // ---------------------------------------------------------------------

    @Test
    public void getHostForVolumeUsesResourceManagerWhenStoreIsPrimary() {
        VolumeInfo vol = volume(DataStoreRole.Primary);
        HostVO h = host(3L, 7L, ResourceState.Enabled);
        Mockito.when(resourceManager.getEligibleUpAndEnabledHostsInZoneForStorageConnection(
                vol.getDataStore(), ZONE_ID, HypervisorType.XenServer))
                .thenReturn(new ArrayList<>(Arrays.asList(h)));

        assertSame(h, service.getHost(vol, HypervisorType.XenServer, false));
    }

    @Test
    public void getHostForVolumeUsesHostDaoWhenStoreIsImageCache() {
        VolumeInfo vol = volume(DataStoreRole.ImageCache);
        HostVO h = host(4L, 9L, ResourceState.Enabled);
        Mockito.when(hostDao.listByDataCenterIdAndHypervisorType(ZONE_ID, HypervisorType.KVM))
                .thenReturn(new ArrayList<>(Arrays.asList(h)));

        assertSame(h, service.getHost(vol, HypervisorType.KVM, false));
    }

    // ---------------------------------------------------------------------
    // getHost(SnapshotInfo)
    // ---------------------------------------------------------------------

    @Test
    public void getHostForSnapshotXenServerPrefersResigningCluster() {
        SnapshotInfo snap = snapshot(DataStoreRole.Primary, HypervisorType.XenServer);
        HostVO resigning = host(1L, 100L, ResourceState.Enabled);
        Mockito.when(resourceManager.getEligibleUpAndEnabledHostsInZoneForStorageConnection(
                snap.getDataStore(), ZONE_ID, HypervisorType.XenServer))
                .thenReturn(new ArrayList<>(Arrays.asList(resigning)));
        Mockito.when(clusterDao.getSupportsResigning(100L)).thenReturn(true);

        assertSame(resigning, service.getHost(snap));
    }

    @Test
    public void getHostForSnapshotXenServerFallsBackWhenNoResigningCluster() {
        SnapshotInfo snap = snapshot(DataStoreRole.Primary, HypervisorType.XenServer);
        HostVO nonResigning = host(2L, 200L, ResourceState.Enabled);

        // First call (with resign=true) yields a host whose cluster does not
        // support resigning -> getHost(...) returns null. Fallback (resign=false)
        // succeeds.
        Mockito.when(resourceManager.getEligibleUpAndEnabledHostsInZoneForStorageConnection(
                ArgumentMatchers.eq(snap.getDataStore()),
                ArgumentMatchers.eq(ZONE_ID),
                ArgumentMatchers.eq(HypervisorType.XenServer)))
                .thenReturn(new ArrayList<>(Arrays.asList(nonResigning)),
                            new ArrayList<>(Arrays.asList(nonResigning)));
        Mockito.when(clusterDao.getSupportsResigning(200L)).thenReturn(false);

        assertSame(nonResigning, service.getHost(snap));
    }

    @Test
    public void getHostForSnapshotXenServerThrowsWhenNoHostAvailable() {
        SnapshotInfo snap = snapshot(DataStoreRole.Primary, HypervisorType.XenServer);
        Mockito.when(resourceManager.getEligibleUpAndEnabledHostsInZoneForStorageConnection(
                ArgumentMatchers.any(), ArgumentMatchers.anyLong(), ArgumentMatchers.eq(HypervisorType.XenServer)))
                .thenReturn(Collections.emptyList());

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.getHost(snap));
        assertTrue(ex.getMessage().contains("Unable to locate an applicable host"));
    }

    @Test
    public void getHostForSnapshotKvmGoesDirectlyWithoutResignCheck() {
        SnapshotInfo snap = snapshot(DataStoreRole.Primary, HypervisorType.KVM);
        HostVO h = host(8L, 3L, ResourceState.Enabled);
        Mockito.when(resourceManager.getEligibleUpAndEnabledHostsInZoneForStorageConnection(
                snap.getDataStore(), ZONE_ID, HypervisorType.KVM))
                .thenReturn(new ArrayList<>(Arrays.asList(h)));

        assertSame(h, service.getHost(snap));
        // No cluster lookup for KVM path
        Mockito.verifyNoInteractions(clusterDao);
    }

    @Test
    public void getHostForSnapshotVMwareGoesDirectlyWithoutResignCheck() {
        SnapshotInfo snap = snapshot(DataStoreRole.Primary, HypervisorType.VMware);
        HostVO h = host(9L, 4L, ResourceState.Enabled);
        Mockito.when(resourceManager.getEligibleUpAndEnabledHostsInZoneForStorageConnection(
                snap.getDataStore(), ZONE_ID, HypervisorType.VMware))
                .thenReturn(new ArrayList<>(Arrays.asList(h)));

        assertSame(h, service.getHost(snap));
        Mockito.verifyNoInteractions(clusterDao);
    }

    @Test
    public void getHostForSnapshotUnsupportedHypervisorThrows() {
        SnapshotInfo snap = snapshot(DataStoreRole.Primary, HypervisorType.Hyperv);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.getHost(snap));
        assertEquals("Unsupported hypervisor type", ex.getMessage());
    }

    // ---------------------------------------------------------------------
    // getHostInCluster(StoragePoolVO)
    // ---------------------------------------------------------------------

    @Test
    public void getHostInClusterReturnsFirstEnabledHost() {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(pool.getId()).thenReturn(11L);

        PrimaryDataStore store = Mockito.mock(PrimaryDataStore.class);
        Mockito.when(dataStoreMgr.getDataStore(11L, DataStoreRole.Primary)).thenReturn(store);

        HostVO disabled = host(1L, 1L, ResourceState.Disabled);
        HostVO enabled = host(2L, 1L, ResourceState.Enabled);
        Mockito.when(resourceManager.getEligibleUpAndEnabledHostsInClusterForStorageConnection(store))
                .thenReturn(new ArrayList<>(Arrays.asList(disabled, enabled)));

        assertSame(enabled, service.getHostInCluster(pool));
    }

    @Test
    public void getHostInClusterThrowsWhenNoHostsReturned() {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(pool.getId()).thenReturn(11L);
        PrimaryDataStore store = Mockito.mock(PrimaryDataStore.class);
        Mockito.when(dataStoreMgr.getDataStore(11L, DataStoreRole.Primary)).thenReturn(store);
        Mockito.when(resourceManager.getEligibleUpAndEnabledHostsInClusterForStorageConnection(store))
                .thenReturn(Collections.emptyList());

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.getHostInCluster(pool));
        assertEquals("Unable to locate a host", ex.getMessage());
    }

    @Test
    public void getHostInClusterThrowsWhenAllHostsDisabled() {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(pool.getId()).thenReturn(11L);
        PrimaryDataStore store = Mockito.mock(PrimaryDataStore.class);
        Mockito.when(dataStoreMgr.getDataStore(11L, DataStoreRole.Primary)).thenReturn(store);
        HostVO disabled = host(1L, 1L, ResourceState.Disabled);
        Mockito.when(resourceManager.getEligibleUpAndEnabledHostsInClusterForStorageConnection(store))
                .thenReturn(new ArrayList<>(Arrays.asList(disabled)));

        assertThrows(CloudRuntimeException.class, () -> service.getHostInCluster(pool));
    }

    @Test
    public void getHostInClusterThrowsWhenResourceManagerReturnsNull() {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(pool.getId()).thenReturn(11L);
        PrimaryDataStore store = Mockito.mock(PrimaryDataStore.class);
        Mockito.when(dataStoreMgr.getDataStore(11L, DataStoreRole.Primary)).thenReturn(store);
        Mockito.when(resourceManager.getEligibleUpAndEnabledHostsInClusterForStorageConnection(store))
                .thenReturn(null);

        assertThrows(CloudRuntimeException.class, () -> service.getHostInCluster(pool));
    }
}
