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
package com.cloud.api.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.apache.cloudstack.api.command.admin.management.ListMgmtsCmd;
import org.apache.cloudstack.api.response.ManagementServerResponse;
import org.apache.cloudstack.api.response.PeerManagementServerNodeResponse;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.dao.ManagementServerJoinDao;
import com.cloud.api.query.vo.ManagementServerJoinVO;
import org.apache.cloudstack.management.ManagementServerHost;
import com.cloud.cluster.ManagementServerHostPeerJoinVO;
import com.cloud.cluster.dao.ManagementServerHostPeerJoinDao;
import com.cloud.host.dao.HostDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class ManagementServerQueryServiceImplTest {

    @Mock private ManagementServerJoinDao managementServerJoinDao;
    @Mock private ManagementServerHostPeerJoinDao mshostPeerJoinDao;
    @Mock private HostDao hostDao;
    @Mock private AsyncJobManager jobManager;

    @Mock private SearchBuilder<ManagementServerJoinVO> searchBuilder;
    @Mock private SearchCriteria<ManagementServerJoinVO> searchCriteria;

    @InjectMocks
    private ManagementServerQueryServiceImpl service;

    @Before
    public void setUp() {
        when(managementServerJoinDao.createSearchBuilder()).thenReturn(searchBuilder);
        when(searchBuilder.create()).thenReturn(searchCriteria);
    }

    // ---- listManagementServersInternal ----

    @Test
    public void listManagementServersInternalWithNoFiltersDoesNotAddCriteria() {
        ListMgmtsCmd cmd = mock(ListMgmtsCmd.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getHostName()).thenReturn(null);
        when(cmd.getVersion()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);

        Pair<List<ManagementServerJoinVO>, Integer> expected = new Pair<>(Collections.emptyList(), 0);
        when(managementServerJoinDao.searchAndCount(eq(searchCriteria), isNull())).thenReturn(expected);

        Pair<List<ManagementServerJoinVO>, Integer> actual = service.listManagementServersInternal(cmd);

        assertEquals(expected, actual);
        verify(searchCriteria, never()).addAnd(any(String.class), any(SearchCriteria.Op.class), any());
    }

    @Test
    public void listManagementServersInternalAppliesIdFilter() {
        ListMgmtsCmd cmd = mock(ListMgmtsCmd.class);
        when(cmd.getId()).thenReturn(42L);
        when(cmd.getHostName()).thenReturn(null);
        when(cmd.getVersion()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);
        when(managementServerJoinDao.searchAndCount(eq(searchCriteria), isNull()))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.listManagementServersInternal(cmd);

        verify(searchCriteria).addAnd(eq("id"), eq(SearchCriteria.Op.EQ), eq(42L));
    }

    @Test
    public void listManagementServersInternalAppliesNameFilter() {
        ListMgmtsCmd cmd = mock(ListMgmtsCmd.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getHostName()).thenReturn("ms-1");
        when(cmd.getVersion()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);
        when(managementServerJoinDao.searchAndCount(eq(searchCriteria), isNull()))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.listManagementServersInternal(cmd);

        verify(searchCriteria).addAnd(eq("name"), eq(SearchCriteria.Op.EQ), eq("ms-1"));
    }

    @Test
    public void listManagementServersInternalAppliesVersionFilter() {
        ListMgmtsCmd cmd = mock(ListMgmtsCmd.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getHostName()).thenReturn(null);
        when(cmd.getVersion()).thenReturn("4.20.0");
        when(cmd.getKeyword()).thenReturn(null);
        when(managementServerJoinDao.searchAndCount(eq(searchCriteria), isNull()))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.listManagementServersInternal(cmd);

        verify(searchCriteria).addAnd(eq("version"), eq(SearchCriteria.Op.EQ), eq("4.20.0"));
    }

    @Test
    public void listManagementServersInternalAppliesKeywordAsLikeFilterOnVersion() {
        ListMgmtsCmd cmd = mock(ListMgmtsCmd.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getHostName()).thenReturn(null);
        when(cmd.getVersion()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn("4.20");
        when(managementServerJoinDao.searchAndCount(eq(searchCriteria), isNull()))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.listManagementServersInternal(cmd);

        verify(searchCriteria).addAnd(eq("version"), eq(SearchCriteria.Op.LIKE), eq("%4.20%"));
    }

    @Test
    public void listManagementServersInternalAppliesAllFiltersTogether() {
        ListMgmtsCmd cmd = mock(ListMgmtsCmd.class);
        when(cmd.getId()).thenReturn(7L);
        when(cmd.getHostName()).thenReturn("ms-7");
        when(cmd.getVersion()).thenReturn("4.20.0");
        when(cmd.getKeyword()).thenReturn("alpha");
        when(managementServerJoinDao.searchAndCount(eq(searchCriteria), isNull()))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.listManagementServersInternal(cmd);

        verify(searchCriteria).addAnd(eq("id"), eq(SearchCriteria.Op.EQ), eq(7L));
        verify(searchCriteria).addAnd(eq("name"), eq(SearchCriteria.Op.EQ), eq("ms-7"));
        verify(searchCriteria).addAnd(eq("version"), eq(SearchCriteria.Op.EQ), eq("4.20.0"));
        verify(searchCriteria).addAnd(eq("version"), eq(SearchCriteria.Op.LIKE), eq("%alpha%"));
    }

    @Test
    public void listManagementServersInternalReturnsDaoResult() {
        ListMgmtsCmd cmd = mock(ListMgmtsCmd.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getHostName()).thenReturn(null);
        when(cmd.getVersion()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);

        ManagementServerJoinVO row = mock(ManagementServerJoinVO.class);
        Pair<List<ManagementServerJoinVO>, Integer> expected =
                new Pair<>(Collections.singletonList(row), 1);
        when(managementServerJoinDao.searchAndCount(eq(searchCriteria), isNull())).thenReturn(expected);

        Pair<List<ManagementServerJoinVO>, Integer> actual = service.listManagementServersInternal(cmd);

        assertEquals(expected, actual);
    }

    // ---- createManagementServerResponse ----

    private ManagementServerJoinVO mockManagementServer() {
        ManagementServerJoinVO mgmt = mock(ManagementServerJoinVO.class);
        when(mgmt.getId()).thenReturn(11L);
        when(mgmt.getMsid()).thenReturn(99L);
        when(mgmt.getUuid()).thenReturn("uuid-ms-1");
        when(mgmt.getName()).thenReturn("ms-1");
        when(mgmt.getState()).thenReturn(ManagementServerHost.State.Up);
        when(mgmt.getVersion()).thenReturn("4.20.0");
        when(mgmt.getJavaVersion()).thenReturn("17.0.10");
        when(mgmt.getJavaName()).thenReturn("Eclipse Adoptium");
        when(mgmt.getOsDistribution()).thenReturn("Ubuntu 24.04");
        when(mgmt.getLastJvmStart()).thenReturn(new Date(1_000_000L));
        when(mgmt.getLastJvmStop()).thenReturn(new Date(2_000_000L));
        when(mgmt.getLastSystemBoot()).thenReturn(new Date(500_000L));
        when(mgmt.getServiceIP()).thenReturn("10.0.0.1");
        return mgmt;
    }

    @Test
    public void createManagementServerResponseCopiesScalarFields() {
        ManagementServerJoinVO mgmt = mockManagementServer();
        when(hostDao.listByLastMs(99L)).thenReturn(Collections.emptyList());
        when(hostDao.listByMs(99L)).thenReturn(Collections.emptyList());
        when(jobManager.countPendingNonPseudoJobs(99L)).thenReturn(0L);

        ManagementServerResponse response = service.createManagementServerResponse(mgmt, false);

        assertEquals("uuid-ms-1", response.getId());
        assertEquals("ms-1", response.getName());
        assertEquals(ManagementServerHost.State.Up, response.getState());
        assertEquals("4.20.0", response.getVersion());
        assertEquals("17.0.10", response.getJavaVersion());
        assertEquals("Eclipse Adoptium", response.getJavaDistribution());
        assertEquals("Ubuntu 24.04", response.getOsDistribution());
        assertEquals(new Date(1_000_000L), response.getLastServerStart());
        assertEquals(new Date(2_000_000L), response.getLastServerStop());
        assertEquals(new Date(500_000L), response.getLastBoot());
        assertEquals("10.0.0.1", response.getServiceIp());
        assertEquals("10.0.0.1", response.getIpAddress());
        assertEquals("managementserver", response.getObjectName());
    }

    @Test
    public void createManagementServerResponseFetchesAgentsAndPendingJobCount() {
        ManagementServerJoinVO mgmt = mockManagementServer();
        when(hostDao.listByLastMs(99L)).thenReturn(Arrays.asList("agent-a", "agent-b"));
        when(hostDao.listByMs(99L)).thenReturn(Arrays.asList("agent-x", "agent-y", "agent-z"));
        when(jobManager.countPendingNonPseudoJobs(99L)).thenReturn(7L);

        ManagementServerResponse response = service.createManagementServerResponse(mgmt, false);

        assertEquals(Arrays.asList("agent-a", "agent-b"), response.getLastAgents());
        assertEquals(Arrays.asList("agent-x", "agent-y", "agent-z"), response.getAgents());
        assertEquals(Long.valueOf(3L), response.getAgentsCount());
        assertEquals(Long.valueOf(7L), response.getPendingJobsCount());
    }

    @Test
    public void createManagementServerResponseWithoutPeersSkipsPeerLookup() {
        ManagementServerJoinVO mgmt = mockManagementServer();
        when(hostDao.listByLastMs(99L)).thenReturn(Collections.emptyList());
        when(hostDao.listByMs(99L)).thenReturn(Collections.emptyList());
        when(jobManager.countPendingNonPseudoJobs(99L)).thenReturn(0L);

        ManagementServerResponse response = service.createManagementServerResponse(mgmt, false);

        verify(mshostPeerJoinDao, never()).listByOwnerMshostId(anyLong());
        assertNotNull(response);
        assertTrue("peers should be empty when listPeers=false",
                response.getPeers() == null || response.getPeers().isEmpty());
    }

    @Test
    public void createManagementServerResponseWithPeersAttachesEachPeer() {
        ManagementServerJoinVO mgmt = mockManagementServer();
        when(hostDao.listByLastMs(99L)).thenReturn(Collections.emptyList());
        when(hostDao.listByMs(99L)).thenReturn(Collections.emptyList());
        when(jobManager.countPendingNonPseudoJobs(99L)).thenReturn(0L);

        ManagementServerHostPeerJoinVO peer1 = mockPeer(
                "peer-uuid-1", "peer-name-1", 101L, 7001L,
                ManagementServerHost.State.Up, "10.0.0.2", 8080);
        ManagementServerHostPeerJoinVO peer2 = mockPeer(
                "peer-uuid-2", "peer-name-2", 102L, 7002L,
                ManagementServerHost.State.Down, "10.0.0.3", 8081);
        when(mshostPeerJoinDao.listByOwnerMshostId(11L)).thenReturn(Arrays.asList(peer1, peer2));

        ManagementServerResponse response = service.createManagementServerResponse(mgmt, true);

        verify(mshostPeerJoinDao, times(1)).listByOwnerMshostId(11L);
        assertNotNull(response.getPeers());
        assertEquals(2, response.getPeers().size());
    }

    @Test
    public void createManagementServerResponseEmptyPeerListAttachesNoPeers() {
        ManagementServerJoinVO mgmt = mockManagementServer();
        when(hostDao.listByLastMs(99L)).thenReturn(Collections.emptyList());
        when(hostDao.listByMs(99L)).thenReturn(Collections.emptyList());
        when(jobManager.countPendingNonPseudoJobs(99L)).thenReturn(0L);
        when(mshostPeerJoinDao.listByOwnerMshostId(11L)).thenReturn(Collections.emptyList());

        ManagementServerResponse response = service.createManagementServerResponse(mgmt, true);

        verify(mshostPeerJoinDao, times(1)).listByOwnerMshostId(11L);
        assertTrue(response.getPeers() == null || response.getPeers().isEmpty());
    }

    @Test
    public void createManagementServerResponseBuildsPeerResponseFields() throws Exception {
        ManagementServerJoinVO mgmt = mockManagementServer();
        when(hostDao.listByLastMs(99L)).thenReturn(Collections.emptyList());
        when(hostDao.listByMs(99L)).thenReturn(Collections.emptyList());
        when(jobManager.countPendingNonPseudoJobs(99L)).thenReturn(0L);

        Date peerUpdate = new Date(3_000_000L);
        ManagementServerHostPeerJoinVO peer = mock(ManagementServerHostPeerJoinVO.class);
        when(peer.getPeerState()).thenReturn(ManagementServerHost.State.Up);
        when(peer.getLastUpdateTime()).thenReturn(peerUpdate);
        when(peer.getPeerMshostUuid()).thenReturn("peer-uuid-x");
        when(peer.getPeerMshostName()).thenReturn("peer-x");
        when(peer.getPeerMshostMsId()).thenReturn(201L);
        when(peer.getPeerMshostRunId()).thenReturn(7777L);
        when(peer.getPeerMshostState()).thenReturn("UP");
        when(peer.getPeerMshostServiceIp()).thenReturn("10.0.0.42");
        when(peer.getPeerMshostServicePort()).thenReturn(9090);
        when(mshostPeerJoinDao.listByOwnerMshostId(11L))
                .thenReturn(Collections.singletonList(peer));

        ManagementServerResponse response = service.createManagementServerResponse(mgmt, true);

        assertEquals(1, response.getPeers().size());
        PeerManagementServerNodeResponse peerResponse = response.getPeers().iterator().next();
        assertEquals(ManagementServerHost.State.Up, readField(peerResponse, "state"));
        assertEquals(peerUpdate, readField(peerResponse, "lastUpdated"));
        assertEquals("peer-uuid-x", readField(peerResponse, "peerId"));
        assertEquals("peer-x", readField(peerResponse, "peerName"));
        assertEquals("201", readField(peerResponse, "peerMsId"));
        assertEquals("7777", readField(peerResponse, "peerRunId"));
        assertEquals("UP", readField(peerResponse, "peerState"));
        assertEquals("10.0.0.42", readField(peerResponse, "peerServiceIp"));
        assertEquals("9090", readField(peerResponse, "peerServicePort"));
        assertEquals("peermanagementserver", peerResponse.getObjectName());
    }

    private static Object readField(Object target, String fieldName) throws Exception {
        java.lang.reflect.Field f = target.getClass().getDeclaredField(fieldName);
        f.setAccessible(true);
        return f.get(target);
    }

    @Test
    public void createManagementServerResponseAgentsCountReflectsListSize() {
        ManagementServerJoinVO mgmt = mockManagementServer();
        when(hostDao.listByLastMs(99L)).thenReturn(Collections.emptyList());
        when(hostDao.listByMs(99L)).thenReturn(Collections.emptyList());
        when(jobManager.countPendingNonPseudoJobs(99L)).thenReturn(0L);

        ManagementServerResponse response = service.createManagementServerResponse(mgmt, false);

        assertEquals(Long.valueOf(0L), response.getAgentsCount());
    }

    @Test
    public void createManagementServerResponseUsesMsidForHostLookups() {
        ManagementServerJoinVO mgmt = mockManagementServer();
        when(hostDao.listByLastMs(99L)).thenReturn(Collections.emptyList());
        when(hostDao.listByMs(99L)).thenReturn(Collections.emptyList());
        when(jobManager.countPendingNonPseudoJobs(99L)).thenReturn(0L);

        service.createManagementServerResponse(mgmt, false);

        ArgumentCaptor<Long> lastMsCaptor = ArgumentCaptor.forClass(Long.class);
        verify(hostDao).listByLastMs(lastMsCaptor.capture());
        assertEquals(Long.valueOf(99L), lastMsCaptor.getValue());

        ArgumentCaptor<Long> msCaptor = ArgumentCaptor.forClass(Long.class);
        verify(hostDao).listByMs(msCaptor.capture());
        assertEquals(Long.valueOf(99L), msCaptor.getValue());

        ArgumentCaptor<Long> pendingCaptor = ArgumentCaptor.forClass(Long.class);
        verify(jobManager).countPendingNonPseudoJobs(pendingCaptor.capture());
        assertEquals(Long.valueOf(99L), pendingCaptor.getValue());
    }

    @Test
    public void createManagementServerResponseDelegatesToOwnerIdWhenListingPeers() {
        ManagementServerJoinVO mgmt = mockManagementServer();
        when(hostDao.listByLastMs(99L)).thenReturn(Collections.emptyList());
        when(hostDao.listByMs(99L)).thenReturn(Collections.emptyList());
        when(jobManager.countPendingNonPseudoJobs(99L)).thenReturn(0L);
        when(mshostPeerJoinDao.listByOwnerMshostId(11L)).thenReturn(Collections.emptyList());

        service.createManagementServerResponse(mgmt, true);

        ArgumentCaptor<Long> ownerCaptor = ArgumentCaptor.forClass(Long.class);
        verify(mshostPeerJoinDao).listByOwnerMshostId(ownerCaptor.capture());
        assertEquals(Long.valueOf(11L), ownerCaptor.getValue());
    }

    @Test
    public void createManagementServerResponseNullDatesPassThrough() {
        ManagementServerJoinVO mgmt = mock(ManagementServerJoinVO.class);
        when(mgmt.getMsid()).thenReturn(99L);
        when(hostDao.listByLastMs(99L)).thenReturn(Collections.emptyList());
        when(hostDao.listByMs(99L)).thenReturn(Collections.emptyList());
        when(jobManager.countPendingNonPseudoJobs(99L)).thenReturn(0L);

        ManagementServerResponse response = service.createManagementServerResponse(mgmt, false);

        assertNull(response.getLastServerStart());
        assertNull(response.getLastServerStop());
        assertNull(response.getLastBoot());
        assertNull(response.getJavaVersion());
        assertNull(response.getJavaDistribution());
        assertNull(response.getOsDistribution());
        assertNull(response.getServiceIp());
        assertNull(response.getIpAddress());
    }

    private ManagementServerHostPeerJoinVO mockPeer(String uuid, String name, long msId, long runId,
                                                    ManagementServerHost.State state, String ip, int port) {
        ManagementServerHostPeerJoinVO peer = mock(ManagementServerHostPeerJoinVO.class);
        when(peer.getPeerState()).thenReturn(state);
        when(peer.getLastUpdateTime()).thenReturn(new Date(0L));
        when(peer.getPeerMshostUuid()).thenReturn(uuid);
        when(peer.getPeerMshostName()).thenReturn(name);
        when(peer.getPeerMshostMsId()).thenReturn(msId);
        when(peer.getPeerMshostRunId()).thenReturn(runId);
        when(peer.getPeerMshostState()).thenReturn(state.name());
        when(peer.getPeerMshostServiceIp()).thenReturn(ip);
        when(peer.getPeerMshostServicePort()).thenReturn(port);
        return peer;
    }
}
