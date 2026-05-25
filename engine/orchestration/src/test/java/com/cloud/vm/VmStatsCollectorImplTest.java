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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVmDiskStatsAnswer;
import com.cloud.agent.api.GetVmDiskStatsCommand;
import com.cloud.agent.api.GetVmNetworkStatsAnswer;
import com.cloud.agent.api.GetVmNetworkStatsCommand;
import com.cloud.agent.api.GetVmStatsAnswer;
import com.cloud.agent.api.GetVmStatsCommand;
import com.cloud.agent.api.VmDiskStatsEntry;
import com.cloud.agent.api.VmNetworkStatsEntry;
import com.cloud.agent.api.VmStatsEntry;
import com.cloud.host.Host;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmStatsCollectorImplTest {

    @Mock private AgentManager agentMgr;
    @Mock private VMInstanceDao vmInstanceDao;

    @InjectMocks
    private VmStatsCollectorImpl collector;

    private static final long HOST_ID = 7L;
    private static final long VM_ID_A = 11L;
    private static final long VM_ID_B = 22L;

    private Host host;

    @Before
    public void setup() {
        host = mock(Host.class);
        when(host.getId()).thenReturn(HOST_ID);
        when(host.getGuid()).thenReturn("guid-host-7");
        when(host.getName()).thenReturn("host-7");
    }

    private Map<String, Long> nameIdMap() {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put("i-a", VM_ID_A);
        map.put("i-b", VM_ID_B);
        return map;
    }

    // ---- getVirtualMachineStatistics(Host, List<Long>) ----

    @Test
    public void vmStatsByIdsReturnsEmptyForNullList() {
        HashMap<Long, ? extends VmStats> result = collector.getVirtualMachineStatistics(host, (List<Long>) null);

        assertTrue(result.isEmpty());
        verify(vmInstanceDao, never()).getNameIdMapForVmIds(anyList());
        verify(agentMgr, never()).easySend(eq(HOST_ID), any());
    }

    @Test
    public void vmStatsByIdsReturnsEmptyForEmptyList() {
        HashMap<Long, ? extends VmStats> result = collector.getVirtualMachineStatistics(host, Collections.emptyList());

        assertTrue(result.isEmpty());
        verify(vmInstanceDao, never()).getNameIdMapForVmIds(anyList());
    }

    @Test
    public void vmStatsByIdsResolvesNamesAndDelegatesToMapOverload() {
        List<Long> ids = Arrays.asList(VM_ID_A, VM_ID_B);
        Map<String, Long> resolved = nameIdMap();
        when(vmInstanceDao.getNameIdMapForVmIds(ids)).thenReturn(resolved);
        VmStatsEntry entryA = new VmStatsEntry();
        HashMap<String, VmStatsEntry> answerMap = new HashMap<>();
        answerMap.put("i-a", entryA);
        GetVmStatsAnswer answer = mock(GetVmStatsAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getVmStatsMap()).thenReturn(answerMap);
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmStatsCommand.class))).thenReturn(answer);

        HashMap<Long, ? extends VmStats> result = collector.getVirtualMachineStatistics(host, ids);

        assertEquals(1, result.size());
        assertSame(entryA, result.get(VM_ID_A));
        verify(vmInstanceDao).getNameIdMapForVmIds(ids);
    }

    // ---- getVirtualMachineStatistics(Host, Map) ----

    @Test
    public void vmStatsByMapReturnsEmptyForNullMap() {
        HashMap<Long, ? extends VmStats> result = collector.getVirtualMachineStatistics(host, (Map<String, Long>) null);

        assertTrue(result.isEmpty());
        verify(agentMgr, never()).easySend(eq(HOST_ID), any());
    }

    @Test
    public void vmStatsByMapReturnsEmptyForEmptyMap() {
        HashMap<Long, ? extends VmStats> result = collector.getVirtualMachineStatistics(host, Collections.emptyMap());

        assertTrue(result.isEmpty());
        verify(agentMgr, never()).easySend(eq(HOST_ID), any());
    }

    @Test
    public void vmStatsByMapReturnsEmptyWhenAgentReturnsNullAnswer() {
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmStatsCommand.class))).thenReturn(null);

        HashMap<Long, ? extends VmStats> result = collector.getVirtualMachineStatistics(host, nameIdMap());

        assertTrue(result.isEmpty());
    }

    @Test
    public void vmStatsByMapReturnsEmptyWhenAnswerReportsFailure() {
        GetVmStatsAnswer answer = mock(GetVmStatsAnswer.class);
        when(answer.getResult()).thenReturn(false);
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmStatsCommand.class))).thenReturn(answer);

        HashMap<Long, ? extends VmStats> result = collector.getVirtualMachineStatistics(host, nameIdMap());

        assertTrue(result.isEmpty());
    }

    @Test
    public void vmStatsByMapReturnsEmptyWhenAnswerHasNullStatsMap() {
        GetVmStatsAnswer answer = mock(GetVmStatsAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getVmStatsMap()).thenReturn(null);
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmStatsCommand.class))).thenReturn(answer);

        HashMap<Long, ? extends VmStats> result = collector.getVirtualMachineStatistics(host, nameIdMap());

        assertTrue(result.isEmpty());
    }

    @Test
    public void vmStatsByMapRekeysEachEntryByVmId() {
        VmStatsEntry entryA = new VmStatsEntry();
        VmStatsEntry entryB = new VmStatsEntry();
        HashMap<String, VmStatsEntry> answerMap = new HashMap<>();
        answerMap.put("i-a", entryA);
        answerMap.put("i-b", entryB);
        GetVmStatsAnswer answer = mock(GetVmStatsAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getVmStatsMap()).thenReturn(answerMap);
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmStatsCommand.class))).thenReturn(answer);

        HashMap<Long, ? extends VmStats> result = collector.getVirtualMachineStatistics(host, nameIdMap());

        assertEquals(2, result.size());
        assertSame(entryA, result.get(VM_ID_A));
        assertSame(entryB, result.get(VM_ID_B));
    }

    @Test
    public void vmStatsByMapSkipsEntriesNotInInputMap() {
        VmStatsEntry entry = new VmStatsEntry();
        HashMap<String, VmStatsEntry> answerMap = new HashMap<>();
        answerMap.put("i-ghost", entry); // not in name->id map
        GetVmStatsAnswer answer = mock(GetVmStatsAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getVmStatsMap()).thenReturn(answerMap);
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmStatsCommand.class))).thenReturn(answer);

        HashMap<Long, ? extends VmStats> result = collector.getVirtualMachineStatistics(host, nameIdMap());

        // ghost rekeys to a null vmId, but the entry itself is preserved
        assertEquals(1, result.size());
        assertTrue(result.containsKey(null));
    }

    @Test
    public void vmStatsByMapSendsCommandWithInstanceNamesGuidAndHostName() {
        GetVmStatsAnswer answer = mock(GetVmStatsAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getVmStatsMap()).thenReturn(new HashMap<>());
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmStatsCommand.class))).thenReturn(answer);

        collector.getVirtualMachineStatistics(host, nameIdMap());

        ArgumentCaptor<GetVmStatsCommand> captor = ArgumentCaptor.forClass(GetVmStatsCommand.class);
        verify(agentMgr).easySend(eq(HOST_ID), captor.capture());
        GetVmStatsCommand sent = captor.getValue();
        assertTrue(sent.getVmNames().containsAll(Arrays.asList("i-a", "i-b")));
        assertEquals("guid-host-7", sent.getHostGuid());
        assertEquals("host-7", sent.getHostName());
    }

    // ---- getVmDiskStatistics ----

    @Test
    public void diskStatsReturnsEmptyForNullMap() {
        HashMap<Long, List<? extends VmDiskStats>> result = collector.getVmDiskStatistics(host, null);

        assertTrue(result.isEmpty());
        verify(agentMgr, never()).easySend(eq(HOST_ID), any());
    }

    @Test
    public void diskStatsReturnsEmptyForEmptyMap() {
        HashMap<Long, List<? extends VmDiskStats>> result = collector.getVmDiskStatistics(host, Collections.emptyMap());

        assertTrue(result.isEmpty());
    }

    @Test
    public void diskStatsReturnsEmptyOnNullAnswer() {
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmDiskStatsCommand.class))).thenReturn(null);

        HashMap<Long, List<? extends VmDiskStats>> result = collector.getVmDiskStatistics(host, nameIdMap());

        assertTrue(result.isEmpty());
    }

    @Test
    public void diskStatsReturnsEmptyOnFailingAnswer() {
        Answer answer = mock(GetVmDiskStatsAnswer.class);
        when(answer.getResult()).thenReturn(false);
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmDiskStatsCommand.class))).thenReturn(answer);

        HashMap<Long, List<? extends VmDiskStats>> result = collector.getVmDiskStatistics(host, nameIdMap());

        assertTrue(result.isEmpty());
    }

    @Test
    public void diskStatsReturnsEmptyWhenAnswerHasNullMap() {
        GetVmDiskStatsAnswer answer = mock(GetVmDiskStatsAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getVmDiskStatsMap()).thenReturn(null);
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmDiskStatsCommand.class))).thenReturn(answer);

        HashMap<Long, List<? extends VmDiskStats>> result = collector.getVmDiskStatistics(host, nameIdMap());

        assertTrue(result.isEmpty());
    }

    @Test
    public void diskStatsRekeysEachListByVmId() {
        List<VmDiskStatsEntry> listA = Arrays.asList(mock(VmDiskStatsEntry.class), mock(VmDiskStatsEntry.class));
        List<VmDiskStatsEntry> listB = Collections.singletonList(mock(VmDiskStatsEntry.class));
        HashMap<String, List<VmDiskStatsEntry>> answerMap = new HashMap<>();
        answerMap.put("i-a", listA);
        answerMap.put("i-b", listB);
        GetVmDiskStatsAnswer answer = mock(GetVmDiskStatsAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getVmDiskStatsMap()).thenReturn(answerMap);
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmDiskStatsCommand.class))).thenReturn(answer);

        HashMap<Long, List<? extends VmDiskStats>> result = collector.getVmDiskStatistics(host, nameIdMap());

        assertEquals(2, result.size());
        assertSame(listA, result.get(VM_ID_A));
        assertSame(listB, result.get(VM_ID_B));
    }

    // ---- getVmNetworkStatistics ----

    @Test
    public void networkStatsReturnsEmptyForNullMap() {
        HashMap<Long, List<? extends VmNetworkStats>> result = collector.getVmNetworkStatistics(host, null);

        assertTrue(result.isEmpty());
        verify(agentMgr, never()).easySend(eq(HOST_ID), any());
    }

    @Test
    public void networkStatsReturnsEmptyForEmptyMap() {
        HashMap<Long, List<? extends VmNetworkStats>> result = collector.getVmNetworkStatistics(host, Collections.emptyMap());

        assertTrue(result.isEmpty());
    }

    @Test
    public void networkStatsReturnsEmptyOnNullAnswer() {
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmNetworkStatsCommand.class))).thenReturn(null);

        HashMap<Long, List<? extends VmNetworkStats>> result = collector.getVmNetworkStatistics(host, nameIdMap());

        assertTrue(result.isEmpty());
    }

    @Test
    public void networkStatsReturnsEmptyOnFailingAnswer() {
        Answer answer = mock(GetVmNetworkStatsAnswer.class);
        when(answer.getResult()).thenReturn(false);
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmNetworkStatsCommand.class))).thenReturn(answer);

        HashMap<Long, List<? extends VmNetworkStats>> result = collector.getVmNetworkStatistics(host, nameIdMap());

        assertTrue(result.isEmpty());
    }

    @Test
    public void networkStatsReturnsEmptyWhenAnswerHasNullMap() {
        GetVmNetworkStatsAnswer answer = mock(GetVmNetworkStatsAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getVmNetworkStatsMap()).thenReturn(null);
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmNetworkStatsCommand.class))).thenReturn(answer);

        HashMap<Long, List<? extends VmNetworkStats>> result = collector.getVmNetworkStatistics(host, nameIdMap());

        assertTrue(result.isEmpty());
    }

    @Test
    public void networkStatsRekeysEachListByVmId() {
        List<VmNetworkStatsEntry> listA = Arrays.asList(mock(VmNetworkStatsEntry.class));
        List<VmNetworkStatsEntry> listB = Arrays.asList(mock(VmNetworkStatsEntry.class), mock(VmNetworkStatsEntry.class));
        HashMap<String, List<VmNetworkStatsEntry>> answerMap = new HashMap<>();
        answerMap.put("i-a", listA);
        answerMap.put("i-b", listB);
        GetVmNetworkStatsAnswer answer = mock(GetVmNetworkStatsAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getVmNetworkStatsMap()).thenReturn(answerMap);
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmNetworkStatsCommand.class))).thenReturn(answer);

        HashMap<Long, List<? extends VmNetworkStats>> result = collector.getVmNetworkStatistics(host, nameIdMap());

        assertEquals(2, result.size());
        assertSame(listA, result.get(VM_ID_A));
        assertSame(listB, result.get(VM_ID_B));
    }

    @Test
    public void networkStatsSendsCommandWithInstanceNamesGuidAndHostName() {
        GetVmNetworkStatsAnswer answer = mock(GetVmNetworkStatsAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getVmNetworkStatsMap()).thenReturn(new HashMap<>());
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmNetworkStatsCommand.class))).thenReturn(answer);

        collector.getVmNetworkStatistics(host, nameIdMap());

        ArgumentCaptor<GetVmNetworkStatsCommand> captor = ArgumentCaptor.forClass(GetVmNetworkStatsCommand.class);
        verify(agentMgr).easySend(eq(HOST_ID), captor.capture());
        GetVmNetworkStatsCommand sent = captor.getValue();
        assertTrue(sent.getVmNames().containsAll(Arrays.asList("i-a", "i-b")));
        assertEquals("guid-host-7", sent.getHostGuid());
        assertEquals("host-7", sent.getHostName());
    }

    @Test
    public void unknownVmNameInDiskAnswerMapsToNullKey() {
        List<VmDiskStatsEntry> orphan = Collections.singletonList(mock(VmDiskStatsEntry.class));
        HashMap<String, List<VmDiskStatsEntry>> answerMap = new HashMap<>();
        answerMap.put("i-ghost", orphan);
        GetVmDiskStatsAnswer answer = mock(GetVmDiskStatsAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getVmDiskStatsMap()).thenReturn(answerMap);
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmDiskStatsCommand.class))).thenReturn(answer);

        HashMap<Long, List<? extends VmDiskStats>> result = collector.getVmDiskStatistics(host, nameIdMap());

        assertEquals(1, result.size());
        assertSame(orphan, result.get(null));
    }

    @Test
    public void diskStatsCommandIncludesGuidAndHostName() {
        GetVmDiskStatsAnswer answer = mock(GetVmDiskStatsAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getVmDiskStatsMap()).thenReturn(new HashMap<>());
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmDiskStatsCommand.class))).thenReturn(answer);

        collector.getVmDiskStatistics(host, nameIdMap());

        ArgumentCaptor<GetVmDiskStatsCommand> captor = ArgumentCaptor.forClass(GetVmDiskStatsCommand.class);
        verify(agentMgr).easySend(eq(HOST_ID), captor.capture());
        GetVmDiskStatsCommand sent = captor.getValue();
        assertEquals("guid-host-7", sent.getHostGuid());
        assertEquals("host-7", sent.getHostName());
        assertTrue(sent.getVmNames().containsAll(Arrays.asList("i-a", "i-b")));
    }

    @Test
    public void vmStatsByIdsHandlesNullDaoResultGracefully() {
        List<Long> ids = Arrays.asList(VM_ID_A);
        when(vmInstanceDao.getNameIdMapForVmIds(ids)).thenReturn(null);

        HashMap<Long, ? extends VmStats> result = collector.getVirtualMachineStatistics(host, ids);

        assertTrue(result.isEmpty());
        verify(agentMgr, never()).easySend(eq(HOST_ID), any());
    }

    @Test
    public void vmStatsByIdsHandlesEmptyDaoResultGracefully() {
        List<Long> ids = Arrays.asList(VM_ID_A);
        when(vmInstanceDao.getNameIdMapForVmIds(ids)).thenReturn(Collections.emptyMap());

        HashMap<Long, ? extends VmStats> result = collector.getVirtualMachineStatistics(host, ids);

        assertTrue(result.isEmpty());
        verify(agentMgr, never()).easySend(eq(HOST_ID), any());
    }

    @Test
    public void diskStatsLeavesNullKeyResultsWhenAnswerEntriesAreNullValued() {
        HashMap<String, List<VmDiskStatsEntry>> answerMap = new HashMap<>();
        answerMap.put("i-a", null);
        GetVmDiskStatsAnswer answer = mock(GetVmDiskStatsAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getVmDiskStatsMap()).thenReturn(answerMap);
        when(agentMgr.easySend(eq(HOST_ID), any(GetVmDiskStatsCommand.class))).thenReturn(answer);

        HashMap<Long, List<? extends VmDiskStats>> result = collector.getVmDiskStatistics(host, nameIdMap());

        assertEquals(1, result.size());
        assertNull(result.get(VM_ID_A));
    }
}
