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
package com.cloud.storage;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Focused tests for {@link StorageAccessGroupServiceImpl} -- the Phase 4
 * extraction of storage-access-group (SAG) resolution and uniqueness checks
 * out of {@link StorageManagerImpl}.
 *
 * The behavior here is also exercised through the manager's delegating
 * wrappers in {@link StorageManagerImplTest}; these tests target the
 * service directly so future refactors of the manager don't drop coverage.
 */
@RunWith(MockitoJUnitRunner.class)
public class StorageAccessGroupServiceImplTest {

    @Mock
    private DataCenterDao dcDao;

    @Mock
    private HostPodDao podDao;

    @Mock
    private ClusterDao clusterDao;

    @Mock
    private HostDao hostDao;

    @InjectMocks
    private StorageAccessGroupServiceImpl service;

    private DataCenterVO zone;
    private HostPodVO pod;
    private ClusterVO cluster;
    private HostVO host;

    private static final long ZONE_ID = 10L;
    private static final long POD_ID = 20L;
    private static final long CLUSTER_ID = 30L;
    private static final long HOST_ID = 40L;

    @Before
    public void setUp() {
        zone = Mockito.mock(DataCenterVO.class);
        pod = Mockito.mock(HostPodVO.class);
        cluster = Mockito.mock(ClusterVO.class);
        host = Mockito.mock(HostVO.class);
    }

    // ---------------------------------------------------------------------
    // checkIfStorageAccessGroupsExistsOnZone
    // ---------------------------------------------------------------------

    @Test
    public void checkOnZoneAllowsWhenNoOverlap() {
        Mockito.when(zone.getStorageAccessGroups()).thenReturn("sagA,sagB");
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);

        // No exception expected.
        service.checkIfStorageAccessGroupsExistsOnZone(ZONE_ID, Arrays.asList("sagX", "sagY"));
    }

    @Test
    public void checkOnZoneAllowsWhenZoneHasNoSags() {
        Mockito.when(zone.getStorageAccessGroups()).thenReturn(null);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);

        service.checkIfStorageAccessGroupsExistsOnZone(ZONE_ID, Arrays.asList("sagA"));
    }

    @Test
    public void checkOnZoneThrowsWhenOverlap() {
        Mockito.when(zone.getStorageAccessGroups()).thenReturn("sag1,sag2");
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.checkIfStorageAccessGroupsExistsOnZone(ZONE_ID, Arrays.asList("sag1", "sag9")));
        assertTrue("message should mention the zone overlap",
                ex.getMessage().contains("sag1") && ex.getMessage().contains("zone"));
    }

    // ---------------------------------------------------------------------
    // checkIfStorageAccessGroupsExistsOnPod
    // ---------------------------------------------------------------------

    @Test
    public void checkOnPodAllowsWhenNoOverlapAtPodOrZone() {
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(pod.getStorageAccessGroups()).thenReturn("p1,p2");
        Mockito.when(zone.getStorageAccessGroups()).thenReturn("z1,z2");
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);

        service.checkIfStorageAccessGroupsExistsOnPod(POD_ID, Arrays.asList("newA", "newB"));
    }

    @Test
    public void checkOnPodThrowsWhenPodOverlap() {
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(pod.getStorageAccessGroups()).thenReturn("p1,p2");
        Mockito.when(zone.getStorageAccessGroups()).thenReturn("");
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.checkIfStorageAccessGroupsExistsOnPod(POD_ID, Arrays.asList("p1", "new")));
        assertTrue(ex.getMessage().contains("pod"));
    }

    @Test
    public void checkOnPodThrowsWhenZoneOverlap() {
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(pod.getStorageAccessGroups()).thenReturn(null);
        Mockito.when(zone.getStorageAccessGroups()).thenReturn("z1,z2");
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.checkIfStorageAccessGroupsExistsOnPod(POD_ID, Arrays.asList("z2", "new")));
        assertTrue(ex.getMessage().contains("zone"));
    }

    @Test
    public void checkOnPodMessageMentionsBothLevelsOnDoubleOverlap() {
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(pod.getStorageAccessGroups()).thenReturn("p1");
        Mockito.when(zone.getStorageAccessGroups()).thenReturn("z1");
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.checkIfStorageAccessGroupsExistsOnPod(POD_ID, Arrays.asList("p1", "z1")));
        assertTrue(ex.getMessage().contains("pod") && ex.getMessage().contains("zone"));
    }

    // ---------------------------------------------------------------------
    // checkIfStorageAccessGroupsExistsOnCluster
    // ---------------------------------------------------------------------

    @Test
    public void checkOnClusterAllowsWhenNoOverlapAnywhere() {
        Mockito.when(cluster.getPodId()).thenReturn(POD_ID);
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(cluster.getStorageAccessGroups()).thenReturn("c1");
        Mockito.when(pod.getStorageAccessGroups()).thenReturn("p1");
        Mockito.when(zone.getStorageAccessGroups()).thenReturn("z1");
        Mockito.when(clusterDao.findById(CLUSTER_ID)).thenReturn(cluster);
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);

        service.checkIfStorageAccessGroupsExistsOnCluster(CLUSTER_ID, Arrays.asList("brand", "new"));
    }

    @Test
    public void checkOnClusterThrowsWhenClusterOverlap() {
        Mockito.when(cluster.getPodId()).thenReturn(POD_ID);
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(cluster.getStorageAccessGroups()).thenReturn("c1,c2");
        Mockito.when(pod.getStorageAccessGroups()).thenReturn(null);
        Mockito.when(zone.getStorageAccessGroups()).thenReturn(null);
        Mockito.when(clusterDao.findById(CLUSTER_ID)).thenReturn(cluster);
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.checkIfStorageAccessGroupsExistsOnCluster(CLUSTER_ID, Arrays.asList("c1", "new")));
        assertTrue(ex.getMessage().contains("cluster"));
    }

    @Test
    public void checkOnClusterMessageMentionsAllLevelsOnTripleOverlap() {
        Mockito.when(cluster.getPodId()).thenReturn(POD_ID);
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(cluster.getStorageAccessGroups()).thenReturn("c1");
        Mockito.when(pod.getStorageAccessGroups()).thenReturn("p1");
        Mockito.when(zone.getStorageAccessGroups()).thenReturn("z1");
        Mockito.when(clusterDao.findById(CLUSTER_ID)).thenReturn(cluster);
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.checkIfStorageAccessGroupsExistsOnCluster(CLUSTER_ID, Arrays.asList("c1", "p1", "z1")));
        assertTrue(ex.getMessage().contains("cluster"));
        assertTrue(ex.getMessage().contains("pod"));
        assertTrue(ex.getMessage().contains("zone"));
    }

    // ---------------------------------------------------------------------
    // getStorageAccessGroups (resolution by most-specific level)
    // ---------------------------------------------------------------------

    @Test
    public void getSagsByHostMergesAllFourLevels() {
        Mockito.when(hostDao.findById(HOST_ID)).thenReturn(host);
        Mockito.when(host.getClusterId()).thenReturn(CLUSTER_ID);
        Mockito.when(clusterDao.findById(CLUSTER_ID)).thenReturn(cluster);
        Mockito.when(cluster.getPodId()).thenReturn(POD_ID);
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);

        Mockito.when(host.getStorageAccessGroups()).thenReturn("sagH");
        Mockito.when(cluster.getStorageAccessGroups()).thenReturn("sagC");
        Mockito.when(pod.getStorageAccessGroups()).thenReturn("sagP");
        Mockito.when(zone.getStorageAccessGroups()).thenReturn("sagZ");

        String[] result = service.getStorageAccessGroups(null, null, null, HOST_ID);

        assertNotNull(result);
        assertEquals(4, result.length);
        assertEquals("sagH", result[0]);
        assertEquals("sagC", result[1]);
        assertEquals("sagP", result[2]);
        assertEquals("sagZ", result[3]);
    }

    @Test
    public void getSagsByHostDropsBlankAndNullEntries() {
        Mockito.when(hostDao.findById(HOST_ID)).thenReturn(host);
        Mockito.when(host.getClusterId()).thenReturn(CLUSTER_ID);
        Mockito.when(clusterDao.findById(CLUSTER_ID)).thenReturn(cluster);
        Mockito.when(cluster.getPodId()).thenReturn(POD_ID);
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);

        Mockito.when(host.getStorageAccessGroups()).thenReturn("");
        Mockito.when(cluster.getStorageAccessGroups()).thenReturn("sagC");
        Mockito.when(pod.getStorageAccessGroups()).thenReturn(null);
        Mockito.when(zone.getStorageAccessGroups()).thenReturn(" ");

        String[] result = service.getStorageAccessGroups(null, null, null, HOST_ID);

        assertNotNull(result);
        assertEquals(1, result.length);
        assertEquals("sagC", result[0]);
    }

    @Test
    public void getSagsByClusterIgnoresHostLevel() {
        Mockito.when(clusterDao.findById(CLUSTER_ID)).thenReturn(cluster);
        Mockito.when(cluster.getPodId()).thenReturn(POD_ID);
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);

        Mockito.when(cluster.getStorageAccessGroups()).thenReturn("sagC");
        Mockito.when(pod.getStorageAccessGroups()).thenReturn("sagP");
        Mockito.when(zone.getStorageAccessGroups()).thenReturn("sagZ");

        String[] result = service.getStorageAccessGroups(null, null, CLUSTER_ID, null);

        assertNotNull(result);
        assertEquals(3, result.length);
        // host-level dao must not be touched
        Mockito.verifyNoInteractions(hostDao);
    }

    @Test
    public void getSagsByPodReturnsPodAndZoneOnly() {
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);

        Mockito.when(pod.getStorageAccessGroups()).thenReturn("sagP1,sagP2");
        Mockito.when(zone.getStorageAccessGroups()).thenReturn("sagZ");

        String[] result = service.getStorageAccessGroups(null, POD_ID, null, null);

        assertNotNull(result);
        assertEquals(3, result.length);
        Mockito.verifyNoInteractions(clusterDao);
        Mockito.verifyNoInteractions(hostDao);
    }

    @Test
    public void getSagsByZoneOnlyTouchesZoneDao() {
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);
        Mockito.when(zone.getStorageAccessGroups()).thenReturn("sagZ1,sagZ2");

        String[] result = service.getStorageAccessGroups(ZONE_ID, null, null, null);

        assertNotNull(result);
        assertEquals(2, result.length);
        assertEquals("sagZ1", result[0]);
        assertEquals("sagZ2", result[1]);
        Mockito.verifyNoInteractions(podDao);
        Mockito.verifyNoInteractions(clusterDao);
        Mockito.verifyNoInteractions(hostDao);
    }

    @Test
    public void getSagsReturnsEmptyArrayWhenAllIdsNull() {
        String[] result = service.getStorageAccessGroups(null, null, null, null);

        assertNotNull(result);
        assertEquals(0, result.length);
        Mockito.verifyNoInteractions(dcDao);
        Mockito.verifyNoInteractions(podDao);
        Mockito.verifyNoInteractions(clusterDao);
        Mockito.verifyNoInteractions(hostDao);
    }

    @Test
    public void getSagsReturnsEmptyArrayWhenAllLevelsBlank() {
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);
        Mockito.when(zone.getStorageAccessGroups()).thenReturn("");

        String[] result = service.getStorageAccessGroups(ZONE_ID, null, null, null);

        assertNotNull(result);
        assertEquals(0, result.length);
    }

    @Test
    public void getSagsByHostHandlesCommaSeparatedValues() {
        Mockito.when(hostDao.findById(HOST_ID)).thenReturn(host);
        Mockito.when(host.getClusterId()).thenReturn(CLUSTER_ID);
        Mockito.when(clusterDao.findById(CLUSTER_ID)).thenReturn(cluster);
        Mockito.when(cluster.getPodId()).thenReturn(POD_ID);
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);

        Mockito.when(host.getStorageAccessGroups()).thenReturn("h1,h2");
        Mockito.when(cluster.getStorageAccessGroups()).thenReturn("c1");
        Mockito.when(pod.getStorageAccessGroups()).thenReturn(null);
        Mockito.when(zone.getStorageAccessGroups()).thenReturn("z1,z2");

        String[] result = service.getStorageAccessGroups(null, null, null, HOST_ID);

        // Order is host, cluster, pod, zone (pod skipped because null) — values comma-split.
        List<String> resultList = Arrays.asList(result);
        assertTrue(resultList.contains("h1"));
        assertTrue(resultList.contains("h2"));
        assertTrue(resultList.contains("c1"));
        assertTrue(resultList.contains("z1"));
        assertTrue(resultList.contains("z2"));
        assertEquals(5, result.length);
    }

    @Test
    public void checkOnZoneAllowsEmptyInput() {
        Mockito.when(zone.getStorageAccessGroups()).thenReturn("sagA,sagB");
        Mockito.when(dcDao.findById(ZONE_ID)).thenReturn(zone);

        // Empty list of new tags can never overlap.
        service.checkIfStorageAccessGroupsExistsOnZone(ZONE_ID, Collections.emptyList());
    }
}
