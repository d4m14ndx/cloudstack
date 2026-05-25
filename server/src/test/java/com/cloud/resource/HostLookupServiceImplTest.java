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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetHostStatsAnswer;
import com.cloud.agent.api.GetHostStatsCommand;
import com.cloud.agent.api.UnsupportedAnswer;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.host.DetailVO;
import com.cloud.host.Host;
import com.cloud.host.HostStats;
import com.cloud.host.HostTagVO;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.db.QueryBuilder;
import com.cloud.utils.db.SearchCriteria.Op;

/**
 * Focused unit tests for {@link HostLookupServiceImpl} — the Phase 4
 * extraction of filtered host listing and per-host metadata reads out of
 * {@link ResourceManagerImpl}.
 *
 * <p>QueryBuilder-based methods are tested by mocking
 * {@link QueryBuilder#create(Class)} statically; DAO / agent-dependent methods
 * use field-injected mocks.
 */
public class HostLookupServiceImplTest {

    private static final long DC_ID = 1L;
    private static final long CLUSTER_ID = 2L;
    private static final long POD_ID = 3L;
    private static final long HOST_ID = 42L;

    // ---- collaborators ----
    private HostDao hostDao;
    private HostDetailsDao hostDetailsDao;
    private HostTagsDao hostTagsDao;
    private AgentManager agentManager;
    private HighAvailabilityManager haManager;

    private HostLookupServiceImpl service;

    // ---- QueryBuilder statics ----
    @SuppressWarnings("rawtypes")
    private QueryBuilder builder;
    @SuppressWarnings("unchecked")
    private QueryBuilder<HostVO> typedBuilder;
    private HostVO entity;
    private MockedStatic<QueryBuilder> queryBuilderMock;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Before
    public void setUp() {
        hostDao = mock(HostDao.class);
        hostDetailsDao = mock(HostDetailsDao.class);
        hostTagsDao = mock(HostTagsDao.class);
        agentManager = mock(AgentManager.class);
        haManager = mock(HighAvailabilityManager.class);

        service = new HostLookupServiceImpl();
        service.hostDao = hostDao;
        service.hostDetailsDao = hostDetailsDao;
        service.hostTagsDao = hostTagsDao;
        service.agentManager = agentManager;
        service.haManager = haManager;

        builder = mock(QueryBuilder.class);
        typedBuilder = (QueryBuilder<HostVO>) builder;
        entity = mock(HostVO.class);
        when(typedBuilder.entity()).thenReturn(entity);

        queryBuilderMock = Mockito.mockStatic(QueryBuilder.class);
        queryBuilderMock.when(() -> QueryBuilder.create(HostVO.class)).thenReturn(typedBuilder);
    }

    @After
    public void tearDown() {
        queryBuilderMock.close();
    }

    // --- listAllUpAndEnabledHosts ---

    @Test
    public void listAllUpAndEnabledHosts_withTypeClusterPod_appliesAllFilters() {
        HostVO h = mock(HostVO.class);
        when(typedBuilder.list()).thenReturn(List.of(h));

        List<HostVO> result = service.listAllUpAndEnabledHosts(Host.Type.Routing, CLUSTER_ID, POD_ID, DC_ID);

        assertEquals(1, result.size());
        assertSame(h, result.get(0));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(Host.Type.Routing));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(CLUSTER_ID));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(POD_ID));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(DC_ID));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(Status.Up));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(ResourceState.Enabled));
    }

    @Test
    public void listAllUpAndEnabledHosts_nullTypeAndCluster_skipsThoseFilters() {
        when(typedBuilder.list()).thenReturn(Collections.emptyList());

        List<HostVO> result = service.listAllUpAndEnabledHosts(null, null, null, DC_ID);

        assertTrue(result.isEmpty());
        // type and cluster should NOT be filtered
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(DC_ID));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(Status.Up));
    }

    // --- listAllHosts ---

    @Test
    public void listAllHosts_returnsAllRegardlessOfStatus() {
        HostVO h1 = mock(HostVO.class);
        HostVO h2 = mock(HostVO.class);
        when(typedBuilder.list()).thenReturn(List.of(h1, h2));

        List<HostVO> result = service.listAllHosts(Host.Type.Storage, null, null, DC_ID);

        assertEquals(2, result.size());
        // Status.Up and ResourceState.Enabled must NOT be applied
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(Host.Type.Storage));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(DC_ID));
    }

    // --- listAllUpHosts ---

    @Test
    public void listAllUpHosts_appliesOnlyUpStatusNotResourceState() {
        HostVO h = mock(HostVO.class);
        when(typedBuilder.list()).thenReturn(List.of(h));

        service.listAllUpHosts(Host.Type.Routing, CLUSTER_ID, null, DC_ID);

        verify(typedBuilder).and(any(), eq(Op.EQ), eq(Status.Up));
        // ResourceState.Enabled must NOT be applied
        Mockito.verify(typedBuilder, Mockito.never()).and(any(), eq(Op.EQ), eq(ResourceState.Enabled));
    }

    // --- listAllUpAndEnabledNonHAHosts ---

    @Test
    public void listAllUpAndEnabledNonHAHosts_delegatesToDaoWithHaTag() {
        String haTag = "ha-dedicated";
        HostVO h = mock(HostVO.class);
        when(haManager.getHaTag()).thenReturn(haTag);
        when(hostDao.listAllUpAndEnabledNonHAHosts(Host.Type.Routing, CLUSTER_ID, POD_ID, DC_ID, haTag))
                .thenReturn(List.of(h));

        List<HostVO> result = service.listAllUpAndEnabledNonHAHosts(Host.Type.Routing, CLUSTER_ID, POD_ID, DC_ID);

        assertEquals(1, result.size());
        assertSame(h, result.get(0));
        verify(hostDao).listAllUpAndEnabledNonHAHosts(Host.Type.Routing, CLUSTER_ID, POD_ID, DC_ID, haTag);
    }

    // --- listAllUpAndEnabledHostsInOneZoneByType ---

    @Test
    public void listAllUpAndEnabledHostsInOneZoneByType_appliesTypeZoneUpEnabledFilters() {
        HostVO h = mock(HostVO.class);
        when(typedBuilder.list()).thenReturn(List.of(h));

        List<HostVO> result = service.listAllUpAndEnabledHostsInOneZoneByType(Host.Type.Routing, DC_ID);

        assertEquals(1, result.size());
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(Host.Type.Routing));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(DC_ID));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(Status.Up));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(ResourceState.Enabled));
    }

    // --- listAllNotInMaintenanceHostsInOneZone ---

    @Test
    public void listAllNotInMaintenanceHostsInOneZone_withZone_appliesZoneAndMaintenanceExclusion() {
        HostVO h = mock(HostVO.class);
        when(typedBuilder.list()).thenReturn(List.of(h));

        List<HostVO> result = service.listAllNotInMaintenanceHostsInOneZone(Host.Type.Routing, DC_ID);

        assertEquals(1, result.size());
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(DC_ID));
        verify(typedBuilder).and(any(), eq(Op.NIN),
                eq(ResourceState.Maintenance),
                eq(ResourceState.ErrorInMaintenance),
                eq(ResourceState.ErrorInPrepareForMaintenance),
                eq(ResourceState.PrepareForMaintenance),
                eq(ResourceState.Error));
    }

    @Test
    public void listAllNotInMaintenanceHostsInOneZone_nullZone_skipsZoneFilter() {
        when(typedBuilder.list()).thenReturn(Collections.emptyList());

        service.listAllNotInMaintenanceHostsInOneZone(Host.Type.Routing, null);

        // dc filter must NOT be applied
        Mockito.verify(typedBuilder, Mockito.never()).and(any(), eq(Op.EQ), eq((Object) null));
    }

    // --- listAllUpAndEnabledHostsInOneZoneByHypervisor ---

    @Test
    public void listAllUpAndEnabledHostsInOneZoneByHypervisor_appliesHvZoneUpEnabled() {
        HostVO h = mock(HostVO.class);
        when(typedBuilder.list()).thenReturn(List.of(h));

        List<HostVO> result = service.listAllUpAndEnabledHostsInOneZoneByHypervisor(HypervisorType.KVM, DC_ID);

        assertEquals(1, result.size());
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(HypervisorType.KVM));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(DC_ID));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(Status.Up));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(ResourceState.Enabled));
    }

    // --- listAllUpHostsInOneZoneByHypervisor ---

    @Test
    public void listAllUpHostsInOneZoneByHypervisor_appliesHvZoneUpNoResourceStateFilter() {
        when(typedBuilder.list()).thenReturn(Collections.emptyList());

        service.listAllUpHostsInOneZoneByHypervisor(HypervisorType.VMware, DC_ID);

        verify(typedBuilder).and(any(), eq(Op.EQ), eq(HypervisorType.VMware));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(Status.Up));
        Mockito.verify(typedBuilder, Mockito.never()).and(any(), eq(Op.EQ), eq(ResourceState.Enabled));
    }

    // --- listAllUpAndEnabledHostsInOneZone ---

    @Test
    public void listAllUpAndEnabledHostsInOneZone_appliesZoneUpEnabled() {
        HostVO h = mock(HostVO.class);
        when(typedBuilder.list()).thenReturn(List.of(h));

        List<HostVO> result = service.listAllUpAndEnabledHostsInOneZone(DC_ID);

        assertEquals(1, result.size());
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(DC_ID));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(Status.Up));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(ResourceState.Enabled));
    }

    // --- listAllHostsInOneZoneNotInClusterByHypervisor ---

    @Test
    public void listAllHostsInOneZoneNotInClusterByHypervisor_appliesNeqCluster() {
        HostVO h = mock(HostVO.class);
        when(typedBuilder.list()).thenReturn(List.of(h));

        service.listAllHostsInOneZoneNotInClusterByHypervisor(HypervisorType.KVM, DC_ID, CLUSTER_ID);

        verify(typedBuilder).and(any(), eq(Op.EQ), eq(HypervisorType.KVM));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(DC_ID));
        verify(typedBuilder).and(any(), eq(Op.NEQ), eq(CLUSTER_ID));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(Status.Up));
    }

    // --- listAllHostsInOneZoneNotInClusterByHypervisors ---

    @Test
    public void listAllHostsInOneZoneNotInClusterByHypervisors_appliesInClause() {
        HostVO h = mock(HostVO.class);
        when(typedBuilder.list()).thenReturn(List.of(h));
        List<HypervisorType> types = List.of(HypervisorType.KVM, HypervisorType.VMware);

        service.listAllHostsInOneZoneNotInClusterByHypervisors(types, DC_ID, CLUSTER_ID);

        verify(typedBuilder).and(any(), eq(Op.IN), eq(types));
        verify(typedBuilder).and(any(), eq(Op.NEQ), eq(CLUSTER_ID));
    }

    // --- getHostStatistics ---

    @Test
    public void getHostStatistics_unsupportedAnswer_returnsNull() {
        Host host = mock(Host.class);
        when(host.getId()).thenReturn(HOST_ID);
        when(host.getGuid()).thenReturn("g-1");
        when(host.getName()).thenReturn("h1");
        when(agentManager.easySend(eq(HOST_ID), any(GetHostStatsCommand.class)))
                .thenReturn(mock(UnsupportedAnswer.class));

        assertNull(service.getHostStatistics(host));
    }

    @Test
    public void getHostStatistics_nullAnswer_returnsNull() {
        Host host = mock(Host.class);
        when(host.getId()).thenReturn(HOST_ID);
        when(host.getGuid()).thenReturn("g-2");
        when(host.getName()).thenReturn("h2");
        when(agentManager.easySend(eq(HOST_ID), any(GetHostStatsCommand.class))).thenReturn(null);

        assertNull(service.getHostStatistics(host));
    }

    @Test
    public void getHostStatistics_failedAnswer_returnsNull() {
        Host host = mock(Host.class);
        when(host.getId()).thenReturn(HOST_ID);
        when(host.getGuid()).thenReturn("g-3");
        when(host.getName()).thenReturn("h3");
        Answer failed = mock(Answer.class);
        when(failed.getResult()).thenReturn(false);
        when(agentManager.easySend(eq(HOST_ID), any(GetHostStatsCommand.class))).thenReturn(failed);

        assertNull(service.getHostStatistics(host));
    }

    @Test
    public void getHostStatistics_successAnswer_returnsHostStats() {
        Host host = mock(Host.class);
        when(host.getId()).thenReturn(HOST_ID);
        when(host.getGuid()).thenReturn("g-4");
        when(host.getName()).thenReturn("h4");
        HostStats expectedStats = mock(HostStats.class);
        GetHostStatsAnswer answer = mock(GetHostStatsAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getHostStats()).thenReturn(expectedStats);
        when(agentManager.easySend(eq(HOST_ID), any(GetHostStatsCommand.class))).thenReturn(answer);

        HostStats result = service.getHostStatistics(host);

        assertSame(expectedStats, result);
    }

    // --- getGuestOSCategoryId ---

    @Test
    public void getGuestOSCategoryId_hostNotFound_returnsNull() {
        when(hostDao.findById(HOST_ID)).thenReturn(null);

        assertNull(service.getGuestOSCategoryId(HOST_ID));
    }

    @Test
    public void getGuestOSCategoryId_detailNotFound_returnsNull() {
        HostVO host = mock(HostVO.class);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        when(hostDetailsDao.findDetail(HOST_ID, "guest.os.category.id")).thenReturn(null);

        assertNull(service.getGuestOSCategoryId(HOST_ID));
    }

    @Test
    public void getGuestOSCategoryId_detailFound_returnsParsedLong() {
        HostVO host = mock(HostVO.class);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        DetailVO detail = mock(DetailVO.class);
        when(detail.getValue()).thenReturn("7");
        when(hostDetailsDao.findDetail(HOST_ID, "guest.os.category.id")).thenReturn(detail);

        Long result = service.getGuestOSCategoryId(HOST_ID);

        assertEquals(Long.valueOf(7L), result);
    }

    // --- getHostTags ---

    @Test
    public void getHostTags_noTags_returnsEmptyString() {
        when(hostTagsDao.getHostTags(HOST_ID)).thenReturn(Collections.emptyList());

        String result = service.getHostTags(HOST_ID);

        // StringUtils.listToCsvTags of empty list returns empty string
        assertTrue(result == null || result.isEmpty());
    }

    @Test
    public void getHostTags_multipleTags_returnsCsvString() {
        HostTagVO tag1 = mock(HostTagVO.class);
        HostTagVO tag2 = mock(HostTagVO.class);
        when(tag1.getTag()).thenReturn("gpu");
        when(tag2.getTag()).thenReturn("fast");
        when(hostTagsDao.getHostTags(HOST_ID)).thenReturn(List.of(tag1, tag2));

        String result = service.getHostTags(HOST_ID);

        assertTrue(result.contains("gpu"));
        assertTrue(result.contains("fast"));
    }
}
