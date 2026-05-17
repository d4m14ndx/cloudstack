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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.GetVmDiskStatsAnswer;
import com.cloud.agent.api.GetVmDiskStatsCommand;
import com.cloud.agent.api.GetVmNetworkStatsAnswer;
import com.cloud.agent.api.GetVmNetworkStatsCommand;
import com.cloud.agent.api.VmDiskStatsEntry;
import com.cloud.agent.api.VmNetworkStatsEntry;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.VlanDao;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.UserStatisticsVO;
import com.cloud.user.VmDiskStatisticsVO;
import com.cloud.user.dao.UserStatisticsDao;
import com.cloud.user.dao.VmDiskStatisticsDao;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.uservm.UserVm;
import com.cloud.vm.dao.NicDao;

/**
 * Unit tests for {@link VmStatsCollectionServiceImpl} — the pre-stop
 * stats-snapshot helper extracted from {@code UserVmManagerImpl} as
 * slice 19 of the Phase 4 Spring-component decomposition.
 *
 * <p>Tests are deliberately split between the public entry points (which
 * exercise the hypervisor gating + agent round-trip + answer-result
 * handling) and the protected {@code updateXxxStatsInTransaction}
 * helpers (which exercise the reconciliation logic without needing a
 * working transaction or agent manager).
 */
@RunWith(MockitoJUnitRunner.class)
public class VmStatsCollectionServiceImplTest {

    @Mock private HostDao hostDao;
    @Mock private VolumeDao volsDao;
    @Mock private AgentManager agentMgr;
    @Mock private AccountManager accountMgr;
    @Mock private NicDao nicDao;
    @Mock private VmDiskStatisticsDao vmDiskStatsDao;
    @Mock private UserStatisticsDao userStatsDao;
    @Mock private VlanDao vlanDao;
    @Mock private ConfigurationDao configDao;

    @Mock private UserVm userVm;
    @Mock private HostVO host;
    @Mock private Account account;

    private VmStatsCollectionServiceImpl service;

    @Before
    public void setUp() {
        service = new VmStatsCollectionServiceImpl();
        ReflectionTestUtils.setField(service, "hostDao", hostDao);
        ReflectionTestUtils.setField(service, "volsDao", volsDao);
        ReflectionTestUtils.setField(service, "agentMgr", agentMgr);
        ReflectionTestUtils.setField(service, "accountMgr", accountMgr);
        ReflectionTestUtils.setField(service, "nicDao", nicDao);
        ReflectionTestUtils.setField(service, "vmDiskStatsDao", vmDiskStatsDao);
        ReflectionTestUtils.setField(service, "userStatsDao", userStatsDao);
        ReflectionTestUtils.setField(service, "vlanDao", vlanDao);
        ReflectionTestUtils.setField(service, "configDao", configDao);
    }

    // ---------- init / config wiring ----------

    @Test
    public void initDefaultsToDailyWhenConfigMissing() {
        // Missing config falls back to 1440 (daily) per NumbersUtil.parseInt default
        when(configDao.getValue("usage.stats.job.aggregation.range")).thenReturn(null);
        service.init();
        assertTrue((boolean) ReflectionTestUtils.getField(service, "dailyOrHourly"));
    }

    @Test
    public void initSetsDailyOrHourlyFalseForOtherRange() {
        when(configDao.getValue("usage.stats.job.aggregation.range")).thenReturn("30");
        service.init();
        assertFalse((boolean) ReflectionTestUtils.getField(service, "dailyOrHourly"));
    }

    @Test
    public void initSetsDailyOrHourlyTrueForDaily() {
        when(configDao.getValue("usage.stats.job.aggregation.range")).thenReturn("1440");
        service.init();
        assertTrue((boolean) ReflectionTestUtils.getField(service, "dailyOrHourly"));
    }

    @Test
    public void initSetsDailyOrHourlyTrueForHourly() {
        when(configDao.getValue("usage.stats.job.aggregation.range")).thenReturn("60");
        service.init();
        assertTrue((boolean) ReflectionTestUtils.getField(service, "dailyOrHourly"));
    }

    @Test
    public void initWithNullConfigDaoIsNoOp() {
        ReflectionTestUtils.setField(service, "configDao", null);
        service.init();
        assertFalse((boolean) ReflectionTestUtils.getField(service, "dailyOrHourly"));
    }

    // ---------- hypervisor gating ----------

    @Test
    public void collectVmNetworkStatisticsSkipsNonKvm() {
        when(userVm.getHypervisorType()).thenReturn(HypervisorType.VMware);
        service.collectVmNetworkStatistics(userVm);
        verify(agentMgr, never()).easySend(anyLong(), any());
    }

    @Test
    public void collectVmDiskStatisticsSkipsXenServer() {
        when(userVm.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        service.collectVmDiskStatistics(userVm);
        verify(agentMgr, never()).easySend(anyLong(), any());
    }

    @Test
    public void collectVmDiskStatisticsSkipsWhenHostIdNull() {
        when(userVm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(userVm.getHostId()).thenReturn(null);
        service.collectVmDiskStatistics(userVm);
        verify(agentMgr, never()).easySend(anyLong(), any());
    }

    // ---------- agent round-trip ----------

    @Test
    public void collectVmNetworkStatisticsReturnsWhenAgentAnswerNull() {
        when(userVm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(userVm.getHostId()).thenReturn(7L);
        when(userVm.getInstanceName()).thenReturn("i-1");
        when(userVm.getAccountId()).thenReturn(2L);
        when(hostDao.findById(7L)).thenReturn(host);
        when(host.getGuid()).thenReturn("g");
        when(host.getName()).thenReturn("h");
        when(accountMgr.getAccount(2L)).thenReturn(account);
        when(agentMgr.easySend(eq(7L), any(GetVmNetworkStatsCommand.class))).thenReturn(null);

        service.collectVmNetworkStatistics(userVm);

        verify(userStatsDao, never()).update(anyLong(), any());
    }

    @Test
    public void collectVmNetworkStatisticsReturnsWhenAnswerUnsuccessful() {
        when(userVm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(userVm.getHostId()).thenReturn(7L);
        when(userVm.getInstanceName()).thenReturn("i-1");
        when(userVm.getAccountId()).thenReturn(2L);
        when(hostDao.findById(7L)).thenReturn(host);
        when(accountMgr.getAccount(2L)).thenReturn(account);
        GetVmNetworkStatsAnswer ans = org.mockito.Mockito.mock(GetVmNetworkStatsAnswer.class);
        when(ans.getResult()).thenReturn(false);
        when(agentMgr.easySend(eq(7L), any(GetVmNetworkStatsCommand.class))).thenReturn(ans);

        service.collectVmNetworkStatistics(userVm);

        verify(userStatsDao, never()).update(anyLong(), any());
    }

    @Test
    public void collectVmDiskStatisticsReturnsWhenAnswerUnsuccessful() {
        when(userVm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(userVm.getHostId()).thenReturn(7L);
        when(userVm.getInstanceName()).thenReturn("i-1");
        when(userVm.getAccountId()).thenReturn(2L);
        when(hostDao.findById(7L)).thenReturn(host);
        when(accountMgr.getAccount(2L)).thenReturn(account);
        GetVmDiskStatsAnswer ans = org.mockito.Mockito.mock(GetVmDiskStatsAnswer.class);
        when(ans.getResult()).thenReturn(false);
        when(agentMgr.easySend(eq(7L), any(GetVmDiskStatsCommand.class))).thenReturn(ans);

        service.collectVmDiskStatistics(userVm);

        verify(vmDiskStatsDao, never()).update(anyLong(), any());
    }

    @Test
    public void collectVmDiskStatisticsReturnsWhenAgentThrows() {
        when(userVm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(userVm.getHostId()).thenReturn(7L);
        when(userVm.getInstanceName()).thenReturn("i-1");
        when(userVm.getAccountId()).thenReturn(2L);
        when(hostDao.findById(7L)).thenReturn(host);
        when(accountMgr.getAccount(2L)).thenReturn(account);
        when(agentMgr.easySend(eq(7L), any(GetVmDiskStatsCommand.class))).thenThrow(new RuntimeException("net"));

        service.collectVmDiskStatistics(userVm);

        verify(vmDiskStatsDao, never()).update(anyLong(), any());
    }

    // ---------- network-stats reconciliation ----------

    @Test
    public void updateNetworkStatsHandlesNullMap() {
        GetVmNetworkStatsAnswer ans = new GetVmNetworkStatsAnswer(new GetVmNetworkStatsCommand(new ArrayList<>(), "g", "h"), "ok", "h", null);
        service.updateNetworkStatsInTransaction(userVm, host, account, ans);
        verify(userStatsDao, never()).update(anyLong(), any());
    }

    @Test
    public void updateNetworkStatsBreaksWhenVlanNotDirectAttached() {
        when(userVm.getInstanceName()).thenReturn("i-1");
        HashMap<String, List<VmNetworkStatsEntry>> map = new HashMap<>();
        map.put("i-1", Arrays.asList(new VmNetworkStatsEntry("i-1", "aa:bb", 10L, 20L)));
        GetVmNetworkStatsAnswer ans = new GetVmNetworkStatsAnswer(
                new GetVmNetworkStatsCommand(new ArrayList<>(), "g", "h"), "ok", "h", map);

        NicVO nic = new NicVO("foo", 1L, 11L, VirtualMachine.Type.User);
        nic.setNetworkId(99L);
        @SuppressWarnings("unchecked")
        SearchCriteria<NicVO> sc = (SearchCriteria<NicVO>) org.mockito.Mockito.mock(SearchCriteria.class);
        when(nicDao.createSearchCriteria()).thenReturn(sc);
        when(nicDao.search(eq(sc), any())).thenReturn(Arrays.asList(nic));
        VlanVO vlan = new VlanVO(VlanType.VirtualNetwork, "200", "10.0.0.1", "10.0.0.254",
                1L, "cidr", 99L, 0L, "n0", null, null);
        when(vlanDao.listVlansByNetworkId(99L)).thenReturn(Arrays.asList(vlan));

        service.updateNetworkStatsInTransaction(userVm, host, account, ans);

        verify(userStatsDao, never()).update(anyLong(), any());
    }

    @Test
    public void updateNetworkStatsSkipsWhenAllBytesZero() {
        when(userVm.getInstanceName()).thenReturn("i-1");
        when(userVm.getAccountId()).thenReturn(2L);
        when(userVm.getDataCenterId()).thenReturn(3L);
        when(userVm.getId()).thenReturn(5L);
        HashMap<String, List<VmNetworkStatsEntry>> map = new HashMap<>();
        map.put("i-1", Arrays.asList(new VmNetworkStatsEntry("i-1", "aa:bb", 0L, 0L)));
        GetVmNetworkStatsAnswer ans = new GetVmNetworkStatsAnswer(
                new GetVmNetworkStatsCommand(new ArrayList<>(), "g", "h"), "ok", "h", map);

        NicVO nic = new NicVO("foo", 1L, 11L, VirtualMachine.Type.User);
        nic.setNetworkId(99L);
        nic.setIPv4Address("1.2.3.4");
        @SuppressWarnings("unchecked")
        SearchCriteria<NicVO> sc = (SearchCriteria<NicVO>) org.mockito.Mockito.mock(SearchCriteria.class);
        when(nicDao.createSearchCriteria()).thenReturn(sc);
        when(nicDao.search(eq(sc), any())).thenReturn(Arrays.asList(nic));
        VlanVO vlan = new VlanVO(VlanType.DirectAttached, "200", "10.0.0.1", "10.0.0.254",
                1L, "cidr", 99L, 0L, "n0", null, null);
        when(vlanDao.listVlansByNetworkId(99L)).thenReturn(Arrays.asList(vlan));
        UserStatisticsVO previous = new UserStatisticsVO(2L, 3L, "1.2.3.4", 5L, "UserVm", 99L);
        when(userStatsDao.findBy(anyLong(), anyLong(), anyLong(), anyString(), anyLong(), anyString())).thenReturn(previous);
        when(userStatsDao.lock(anyLong(), anyLong(), anyLong(), anyString(), anyLong(), anyString())).thenReturn(previous);

        service.updateNetworkStatsInTransaction(userVm, host, account, ans);

        verify(userStatsDao, never()).update(anyLong(), any());
    }

    @Test
    public void updateNetworkStatsUpdatesWhenBytesPresent() {
        when(userVm.getInstanceName()).thenReturn("i-1");
        when(userVm.getAccountId()).thenReturn(2L);
        when(userVm.getDataCenterId()).thenReturn(3L);
        when(userVm.getId()).thenReturn(5L);

        ReflectionTestUtils.setField(service, "dailyOrHourly", false);

        HashMap<String, List<VmNetworkStatsEntry>> map = new HashMap<>();
        map.put("i-1", Arrays.asList(new VmNetworkStatsEntry("i-1", "aa:bb", 1000L, 2000L)));
        GetVmNetworkStatsAnswer ans = new GetVmNetworkStatsAnswer(
                new GetVmNetworkStatsCommand(new ArrayList<>(), "g", "h"), "ok", "h", map);

        NicVO nic = new NicVO("foo", 1L, 11L, VirtualMachine.Type.User);
        nic.setNetworkId(99L);
        nic.setIPv4Address("1.2.3.4");
        @SuppressWarnings("unchecked")
        SearchCriteria<NicVO> sc = (SearchCriteria<NicVO>) org.mockito.Mockito.mock(SearchCriteria.class);
        when(nicDao.createSearchCriteria()).thenReturn(sc);
        when(nicDao.search(eq(sc), any())).thenReturn(Arrays.asList(nic));
        VlanVO vlan = new VlanVO(VlanType.DirectAttached, "200", "10.0.0.1", "10.0.0.254",
                1L, "cidr", 99L, 0L, "n0", null, null);
        when(vlanDao.listVlansByNetworkId(99L)).thenReturn(Arrays.asList(vlan));
        UserStatisticsVO previous = new UserStatisticsVO(2L, 3L, "1.2.3.4", 5L, "UserVm", 99L);
        UserStatisticsVO lock = new UserStatisticsVO(2L, 3L, "1.2.3.4", 5L, "UserVm", 99L);
        ReflectionTestUtils.setField(lock, "id", 42L);
        when(userStatsDao.findBy(anyLong(), anyLong(), anyLong(), anyString(), anyLong(), anyString())).thenReturn(previous);
        when(userStatsDao.lock(anyLong(), anyLong(), anyLong(), anyString(), anyLong(), anyString())).thenReturn(lock);

        service.updateNetworkStatsInTransaction(userVm, host, account, ans);

        assertEquals(1000L, lock.getCurrentBytesSent());
        assertEquals(2000L, lock.getCurrentBytesReceived());
        verify(userStatsDao, times(1)).update(eq(42L), eq(lock));
    }

    // ---------- disk-stats reconciliation ----------

    @Test
    public void updateDiskStatsHandlesNullMap() {
        GetVmDiskStatsAnswer ans = new GetVmDiskStatsAnswer(new GetVmDiskStatsCommand(new ArrayList<>(), "g", "h"), "ok", "h", null);
        service.updateDiskStatsInTransaction(userVm, host, account, ans);
        verify(vmDiskStatsDao, never()).update(anyLong(), any());
    }

    @Test
    public void updateDiskStatsBreaksWhenNoVolumeMatch() {
        when(userVm.getInstanceName()).thenReturn("i-1");
        HashMap<String, List<VmDiskStatsEntry>> map = new HashMap<>();
        map.put("i-1", Arrays.asList(new VmDiskStatsEntry("i-1", "/foo", 1L, 1L, 1L, 1L)));
        GetVmDiskStatsAnswer ans = new GetVmDiskStatsAnswer(
                new GetVmDiskStatsCommand(new ArrayList<>(), "g", "h"), "ok", "h", map);
        @SuppressWarnings("unchecked")
        SearchCriteria<VolumeVO> sc = (SearchCriteria<VolumeVO>) org.mockito.Mockito.mock(SearchCriteria.class);
        when(volsDao.createSearchCriteria()).thenReturn(sc);
        when(volsDao.search(eq(sc), any())).thenReturn(new ArrayList<>());

        service.updateDiskStatsInTransaction(userVm, host, account, ans);

        verify(vmDiskStatsDao, never()).update(anyLong(), any());
    }

    @Test
    public void updateDiskStatsSkipsWhenAllCountersZero() {
        when(userVm.getInstanceName()).thenReturn("i-1");
        when(userVm.getAccountId()).thenReturn(2L);
        when(userVm.getDataCenterId()).thenReturn(3L);
        when(userVm.getId()).thenReturn(5L);
        HashMap<String, List<VmDiskStatsEntry>> map = new HashMap<>();
        map.put("i-1", Arrays.asList(new VmDiskStatsEntry("i-1", "/foo", 0L, 0L, 0L, 0L)));
        GetVmDiskStatsAnswer ans = new GetVmDiskStatsAnswer(
                new GetVmDiskStatsCommand(new ArrayList<>(), "g", "h"), "ok", "h", map);
        VolumeVO volume = org.mockito.Mockito.mock(VolumeVO.class);
        when(volume.getId()).thenReturn(77L);
        @SuppressWarnings("unchecked")
        SearchCriteria<VolumeVO> sc = (SearchCriteria<VolumeVO>) org.mockito.Mockito.mock(SearchCriteria.class);
        when(volsDao.createSearchCriteria()).thenReturn(sc);
        when(volsDao.search(eq(sc), any())).thenReturn(Arrays.asList(volume));
        VmDiskStatisticsVO stat = new VmDiskStatisticsVO(2L, 3L, 5L, 77L);
        when(vmDiskStatsDao.findBy(2L, 3L, 5L, 77L)).thenReturn(stat);
        when(vmDiskStatsDao.lock(2L, 3L, 5L, 77L)).thenReturn(stat);

        service.updateDiskStatsInTransaction(userVm, host, account, ans);

        verify(vmDiskStatsDao, never()).update(anyLong(), any());
    }

    @Test
    public void updateDiskStatsUpdatesCountersAndAggregates() {
        when(userVm.getInstanceName()).thenReturn("i-1");
        when(userVm.getAccountId()).thenReturn(2L);
        when(userVm.getDataCenterId()).thenReturn(3L);
        when(userVm.getId()).thenReturn(5L);

        ReflectionTestUtils.setField(service, "dailyOrHourly", false);

        HashMap<String, List<VmDiskStatsEntry>> map = new HashMap<>();
        map.put("i-1", Arrays.asList(new VmDiskStatsEntry("i-1", "/foo", 10L, 20L, 1000L, 2000L)));
        GetVmDiskStatsAnswer ans = new GetVmDiskStatsAnswer(
                new GetVmDiskStatsCommand(new ArrayList<>(), "g", "h"), "ok", "h", map);
        VolumeVO volume = org.mockito.Mockito.mock(VolumeVO.class);
        when(volume.getId()).thenReturn(77L);
        @SuppressWarnings("unchecked")
        SearchCriteria<VolumeVO> sc = (SearchCriteria<VolumeVO>) org.mockito.Mockito.mock(SearchCriteria.class);
        when(volsDao.createSearchCriteria()).thenReturn(sc);
        when(volsDao.search(eq(sc), any())).thenReturn(Arrays.asList(volume));
        VmDiskStatisticsVO stat = new VmDiskStatisticsVO(2L, 3L, 5L, 77L);
        ReflectionTestUtils.setField(stat, "id", 123L);
        when(vmDiskStatsDao.findBy(2L, 3L, 5L, 77L)).thenReturn(stat);
        when(vmDiskStatsDao.lock(2L, 3L, 5L, 77L)).thenReturn(stat);

        service.updateDiskStatsInTransaction(userVm, host, account, ans);

        assertEquals(20L, stat.getCurrentIORead());
        assertEquals(10L, stat.getCurrentIOWrite());
        assertEquals(2000L, stat.getCurrentBytesRead());
        assertEquals(1000L, stat.getCurrentBytesWrite());
        // agg bytes updated because dailyOrHourly is false
        assertEquals(2000L, stat.getAggBytesRead());
        assertEquals(1000L, stat.getAggBytesWrite());
        verify(vmDiskStatsDao, times(1)).update(eq(123L), eq(stat));
    }

    @Test
    public void updateDiskStatsSkipsAggWhenDailyOrHourly() {
        when(userVm.getInstanceName()).thenReturn("i-1");
        when(userVm.getAccountId()).thenReturn(2L);
        when(userVm.getDataCenterId()).thenReturn(3L);
        when(userVm.getId()).thenReturn(5L);

        ReflectionTestUtils.setField(service, "dailyOrHourly", true);

        HashMap<String, List<VmDiskStatsEntry>> map = new HashMap<>();
        map.put("i-1", Arrays.asList(new VmDiskStatsEntry("i-1", "/foo", 10L, 20L, 1000L, 2000L)));
        GetVmDiskStatsAnswer ans = new GetVmDiskStatsAnswer(
                new GetVmDiskStatsCommand(new ArrayList<>(), "g", "h"), "ok", "h", map);
        VolumeVO volume = org.mockito.Mockito.mock(VolumeVO.class);
        when(volume.getId()).thenReturn(77L);
        @SuppressWarnings("unchecked")
        SearchCriteria<VolumeVO> sc = (SearchCriteria<VolumeVO>) org.mockito.Mockito.mock(SearchCriteria.class);
        when(volsDao.createSearchCriteria()).thenReturn(sc);
        when(volsDao.search(eq(sc), any())).thenReturn(Arrays.asList(volume));
        VmDiskStatisticsVO stat = new VmDiskStatisticsVO(2L, 3L, 5L, 77L);
        ReflectionTestUtils.setField(stat, "id", 123L);
        when(vmDiskStatsDao.findBy(2L, 3L, 5L, 77L)).thenReturn(stat);
        when(vmDiskStatsDao.lock(2L, 3L, 5L, 77L)).thenReturn(stat);

        service.updateDiskStatsInTransaction(userVm, host, account, ans);

        assertEquals(0L, stat.getAggBytesRead());
        assertEquals(0L, stat.getAggBytesWrite());
        verify(vmDiskStatsDao, times(1)).update(eq(123L), eq(stat));
    }
}
