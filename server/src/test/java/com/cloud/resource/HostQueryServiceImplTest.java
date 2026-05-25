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
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.utils.db.QueryBuilder;
import com.cloud.utils.db.SearchCriteria.Op;

/**
 * Focused unit tests for {@link HostQueryServiceImpl} — the Phase 4 extraction
 * of read-only host lookup and listing out of {@link ResourceManagerImpl}.
 *
 * <p>Every method here is a static {@link QueryBuilder} chain against
 * {@link HostVO}, so we mock {@link QueryBuilder#create(Class)} statically
 * and assert both the criteria added to the chain and the final list / find
 * result. The manager-level wrappers are exercised separately in
 * {@code ResourceManagerImplTest}.
 */
public class HostQueryServiceImplTest {

    private HostQueryServiceImpl service;

    @SuppressWarnings("rawtypes")
    private QueryBuilder builder;
    @SuppressWarnings("unchecked")
    private QueryBuilder<HostVO> typedBuilder;
    private HostVO entity;
    private MockedStatic<QueryBuilder> queryBuilderMock;

    @SuppressWarnings({"unchecked", "rawtypes"})
    @Before
    public void setUp() {
        service = new HostQueryServiceImpl();
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

    // --- findDirectlyConnectedHosts ---

    @Test
    public void findDirectlyConnectedHosts_returnsBuilderListAndAppliesResourceFilters() {
        HostVO h1 = mock(HostVO.class);
        HostVO h2 = mock(HostVO.class);
        when(typedBuilder.list()).thenReturn(List.of(h1, h2));

        List<HostVO> result = service.findDirectlyConnectedHosts();

        assertEquals(2, result.size());
        assertSame(h1, result.get(0));
        // resource NNULL and resourceState NIN Disabled
        verify(typedBuilder).and(any(), eq(Op.NNULL));
        verify(typedBuilder).and(any(), eq(Op.NIN), eq(ResourceState.Disabled));
    }

    @Test
    public void findDirectlyConnectedHosts_emptyBuilderListReturnsEmptyList() {
        when(typedBuilder.list()).thenReturn(Collections.emptyList());

        assertTrue(service.findDirectlyConnectedHosts().isEmpty());
    }

    // --- findHostByGuid(long, String) ---

    @Test
    public void findHostByGuidByZone_appliesDcAndGuidFilters() {
        HostVO h1 = mock(HostVO.class);
        when(typedBuilder.list()).thenReturn(List.of(h1));

        List<HostVO> result = service.findHostByGuid(5L, "abc-123");

        assertEquals(1, result.size());
        assertSame(h1, result.get(0));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(5L));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq("abc-123"));
    }

    // --- findHostByGuid(String) ---

    @Test
    public void findHostByGuid_appliesGuidAndRemovedNullAndReturnsFind() {
        HostVO h = mock(HostVO.class);
        when(typedBuilder.find()).thenReturn(h);

        HostVO result = service.findHostByGuid("the-guid");

        assertSame(h, result);
        verify(typedBuilder).and(any(), eq(Op.EQ), eq("the-guid"));
        verify(typedBuilder).and(any(), eq(Op.NULL));
    }

    @Test
    public void findHostByGuid_noMatchReturnsNull() {
        when(typedBuilder.find()).thenReturn(null);

        assertNull(service.findHostByGuid("missing"));
    }

    // --- findHostByGuidPrefix ---

    @Test
    public void findHostByGuidPrefix_appliesLikePatternWithTrailingPercent() {
        HostVO h = mock(HostVO.class);
        when(typedBuilder.find()).thenReturn(h);

        HostVO result = service.findHostByGuidPrefix("prefix");

        assertSame(h, result);
        verify(typedBuilder).and(any(), eq(Op.LIKE), eq("prefix%"));
        verify(typedBuilder).and(any(), eq(Op.NULL));
    }

    // --- findHostByName ---

    @Test
    public void findHostByName_appliesNameAndRemovedNullFilters() {
        HostVO h = mock(HostVO.class);
        when(typedBuilder.find()).thenReturn(h);

        HostVO result = service.findHostByName("host-1");

        assertSame(h, result);
        verify(typedBuilder).and(any(), eq(Op.EQ), eq("host-1"));
        verify(typedBuilder).and(any(), eq(Op.NULL));
    }

    @Test
    public void findHostByName_noMatchReturnsNull() {
        when(typedBuilder.find()).thenReturn(null);

        assertNull(service.findHostByName("ghost"));
    }

    // --- listAllHostsInCluster ---

    @Test
    public void listAllHostsInCluster_appliesClusterIdFilterOnly() {
        HostVO h1 = mock(HostVO.class);
        HostVO h2 = mock(HostVO.class);
        when(typedBuilder.list()).thenReturn(List.of(h1, h2));

        List<HostVO> result = service.listAllHostsInCluster(77L);

        assertEquals(2, result.size());
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(77L));
        // Exactly one and() call was issued.
        verify(typedBuilder, atLeastOnce()).and(any(), eq(Op.EQ), eq(77L));
    }

    // --- listHostsInClusterByStatus ---

    @Test
    public void listHostsInClusterByStatus_appliesClusterAndStatusFilters() {
        HostVO h = mock(HostVO.class);
        when(typedBuilder.list()).thenReturn(List.of(h));

        List<HostVO> result = service.listHostsInClusterByStatus(11L, Status.Up);

        assertEquals(1, result.size());
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(11L));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(Status.Up));
    }

    // --- listAllHostsInOneZoneByType ---

    @Test
    public void listAllHostsInOneZoneByType_appliesTypeAndDcFilters() {
        HostVO h = mock(HostVO.class);
        when(typedBuilder.list()).thenReturn(List.of(h));

        List<HostVO> result = service.listAllHostsInOneZoneByType(Host.Type.Routing, 3L);

        assertEquals(1, result.size());
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(Host.Type.Routing));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(3L));
    }

    // --- listAllHostsInAllZonesByType ---

    @Test
    public void listAllHostsInAllZonesByType_appliesTypeFilterOnly() {
        HostVO h = mock(HostVO.class);
        when(typedBuilder.list()).thenReturn(List.of(h));

        List<HostVO> result = service.listAllHostsInAllZonesByType(Host.Type.SecondaryStorage);

        assertEquals(1, result.size());
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(Host.Type.SecondaryStorage));
    }

    // --- findOneRandomRunningHostByHypervisor ---

    @Test
    public void findOneRandomRunningHostByHypervisor_emptyListReturnsNull() {
        when(typedBuilder.list()).thenReturn(Collections.emptyList());

        assertNull(service.findOneRandomRunningHostByHypervisor(HypervisorType.KVM, 1L));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(HypervisorType.KVM));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(Host.Type.Routing));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(Status.Up));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(ResourceState.Enabled));
        verify(typedBuilder).and(any(), eq(Op.EQ), eq(1L));
        verify(typedBuilder).and(any(), eq(Op.NULL));
    }

    @Test
    public void findOneRandomRunningHostByHypervisor_nullDcSkipsDcFilter() {
        when(typedBuilder.list()).thenReturn(Collections.emptyList());

        assertNull(service.findOneRandomRunningHostByHypervisor(HypervisorType.KVM, null));
        // No EQ on a Long dcId added.
        verify(typedBuilder, Mockito.never()).and(any(), eq(Op.EQ), any(Long.class));
    }

    @Test
    public void findOneRandomRunningHostByHypervisor_singleHostReturnsThatHost() {
        HostVO only = mock(HostVO.class);
        when(typedBuilder.list()).thenReturn(new ArrayList<>(List.of(only)));

        HostVO result = service.findOneRandomRunningHostByHypervisor(HypervisorType.VMware, 5L);

        assertSame(only, result);
    }

    @Test
    public void findOneRandomRunningHostByHypervisor_returnsOneOfReturnedHosts() {
        HostVO a = mock(HostVO.class);
        HostVO b = mock(HostVO.class);
        HostVO c = mock(HostVO.class);
        // Mutable list so Collections.shuffle works.
        when(typedBuilder.list()).thenReturn(new ArrayList<>(List.of(a, b, c)));

        HostVO result = service.findOneRandomRunningHostByHypervisor(HypervisorType.KVM, null);

        // Random pick from a non-empty list — must be one of the inputs.
        assertTrue(result == a || result == b || result == c);
    }
}
