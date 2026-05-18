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
package com.cloud.network.router;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import org.apache.cloudstack.network.RoutedIpv4Manager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.NetworkUsageAnswer;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.network.Network;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.NetworkModel;
import com.cloud.user.AccountManager;
import com.cloud.user.UserStatisticsVO;
import com.cloud.user.dao.UserStatisticsDao;
import com.cloud.user.dao.UserStatsLogDao;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;

@RunWith(MockitoJUnitRunner.class)
public class RouterNetworkStatsServiceImplTest {

    @Mock
    private DomainRouterDao routerDao;
    @Mock
    private UserStatisticsDao userStatsDao;
    @Mock
    private UserStatsLogDao userStatsLogDao;
    @Mock
    private AgentManager agentMgr;
    @Mock
    private NetworkModel networkModel;
    @Mock
    private NicDao nicDao;
    @Mock
    private AccountManager accountMgr;
    @Mock
    private DataCenterDao dcDao;
    @Mock
    private com.cloud.cluster.dao.ManagementServerHostDao msHostDao;
    @Mock
    private RoutedIpv4Manager routedIpv4Manager;

    @InjectMocks
    private RouterNetworkStatsServiceImpl service;

    private DomainRouterVO router;
    private NicVO routerNic;
    private Network network;

    @Before
    public void setUp() {
        router = Mockito.mock(DomainRouterVO.class);
        when(router.getId()).thenReturn(10L);
        when(router.getPrivateIpAddress()).thenReturn("10.0.0.1");
        when(router.getAccountId()).thenReturn(1L);
        when(router.getDataCenterId()).thenReturn(1L);
        when(router.getHostName()).thenReturn("router-1");
        when(router.getHostId()).thenReturn(100L);
        when(router.getType()).thenReturn(VirtualMachine.Type.DomainRouter);

        routerNic = Mockito.mock(NicVO.class);
        when(routerNic.getNetworkId()).thenReturn(200L);
        when(routerNic.getIPv4Address()).thenReturn("192.168.1.1");

        network = Mockito.mock(Network.class);
        when(network.getId()).thenReturn(200L);
    }

    @Test
    public void testCollectNetworkStatisticsNullRouterReturnsImmediately() {
        service.collectNetworkStatistics(null, null);
        verify(agentMgr, never()).easySend(anyLong(), any());
    }

    @Test
    public void testCollectNetworkStatisticsRouterWithNullPrivateIpSkips() {
        when(router.getPrivateIpAddress()).thenReturn(null);
        service.collectNetworkStatistics(router, null);
        verify(agentMgr, never()).easySend(anyLong(), any());
        verify(nicDao, never()).listByVmId(anyLong());
    }

    @Test
    public void testCollectNetworkStatisticsSkipsRoutedNetwork() {
        when(router.getVpcId()).thenReturn(null);
        when(networkModel.getNetwork(200L)).thenReturn(network);
        when(routedIpv4Manager.isRoutedNetwork(network)).thenReturn(true);
        when(nicDao.listByVmId(10L)).thenReturn(new ArrayList<>(Collections.singletonList(routerNic)));

        service.collectNetworkStatistics(router, null);

        verify(agentMgr, never()).easySend(anyLong(), any());
    }

    @Test
    public void testCollectNetworkStatisticsNullNetworkSkipped() {
        when(router.getVpcId()).thenReturn(null);
        when(networkModel.getNetwork(200L)).thenReturn(null);
        when(nicDao.listByVmId(10L)).thenReturn(new ArrayList<>(Collections.singletonList(routerNic)));

        service.collectNetworkStatistics(router, null);

        verify(agentMgr, never()).easySend(anyLong(), any());
    }

    @Test
    public void testCollectNetworkStatisticsNonIsolatedGuestNetworkSkippedForNonVpc() {
        when(router.getVpcId()).thenReturn(null);
        when(networkModel.getNetwork(200L)).thenReturn(network);
        when(routedIpv4Manager.isRoutedNetwork(network)).thenReturn(false);
        when(network.getTrafficType()).thenReturn(TrafficType.Public);
        when(nicDao.listByVmId(10L)).thenReturn(new ArrayList<>(Collections.singletonList(routerNic)));

        service.collectNetworkStatistics(router, null);

        verify(agentMgr, never()).easySend(anyLong(), any());
    }

    @Test
    public void testCollectNetworkStatisticsZeroBytesSkipsPersistence() {
        when(router.getVpcId()).thenReturn(null);
        when(networkModel.getNetwork(200L)).thenReturn(network);
        when(routedIpv4Manager.isRoutedNetwork(network)).thenReturn(false);
        when(network.getTrafficType()).thenReturn(TrafficType.Guest);
        when(network.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(nicDao.listByVmId(10L)).thenReturn(new ArrayList<>(Collections.singletonList(routerNic)));

        NetworkUsageAnswer answer = Mockito.mock(NetworkUsageAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getBytesReceived()).thenReturn(0L);
        when(answer.getBytesSent()).thenReturn(0L);
        when(agentMgr.easySend(eq(100L), any())).thenReturn(answer);

        service.collectNetworkStatistics(router, null);

        verify(userStatsDao, never()).update(anyLong(), any());
    }

    @Test
    public void testCollectNetworkStatisticsFailedAnswerSkipsPersistence() {
        when(router.getVpcId()).thenReturn(null);
        when(networkModel.getNetwork(200L)).thenReturn(network);
        when(routedIpv4Manager.isRoutedNetwork(network)).thenReturn(false);
        when(network.getTrafficType()).thenReturn(TrafficType.Guest);
        when(network.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(nicDao.listByVmId(10L)).thenReturn(new ArrayList<>(Collections.singletonList(routerNic)));

        NetworkUsageAnswer answer = Mockito.mock(NetworkUsageAnswer.class);
        when(answer.getResult()).thenReturn(false);
        when(answer.getDetails()).thenReturn("boom");
        when(agentMgr.easySend(eq(100L), any())).thenReturn(answer);

        service.collectNetworkStatistics(router, null);

        verify(userStatsDao, never()).update(anyLong(), any());
    }

    @Test
    public void testCollectNetworkStatisticsWithSpecificNicSkipsListByVmId() {
        when(router.getVpcId()).thenReturn(null);
        when(networkModel.getNetwork(200L)).thenReturn(network);
        when(routedIpv4Manager.isRoutedNetwork(network)).thenReturn(true);

        service.collectNetworkStatistics(router, routerNic);

        verify(nicDao, never()).listByVmId(anyLong());
    }

    @Test
    public void testProcessStopOrRebootAnswerFlushesCounters() {
        when(routerDao.getRouterNetworks(10L)).thenReturn(Collections.singletonList(200L));
        UserStatisticsVO stats = Mockito.mock(UserStatisticsVO.class);
        when(stats.getCurrentBytesReceived()).thenReturn(500L);
        when(stats.getNetBytesReceived()).thenReturn(1000L);
        when(stats.getCurrentBytesSent()).thenReturn(300L);
        when(stats.getNetBytesSent()).thenReturn(700L);
        when(stats.getId()).thenReturn(42L);
        when(userStatsDao.lock(eq(1L), eq(1L), eq(200L), isNull(), eq(10L), anyString())).thenReturn(stats);

        service.processStopOrRebootAnswer(router, Mockito.mock(Answer.class));

        verify(stats).setCurrentBytesReceived(0);
        verify(stats).setNetBytesReceived(1500L);
        verify(stats).setCurrentBytesSent(0);
        verify(stats).setNetBytesSent(1000L);
        verify(userStatsDao).update(eq(42L), eq(stats));
    }

    @Test
    public void testProcessStopOrRebootAnswerNullUserStatsLogsWarning() {
        when(routerDao.getRouterNetworks(10L)).thenReturn(Collections.singletonList(200L));
        when(userStatsDao.lock(anyLong(), anyLong(), anyLong(), isNull(), anyLong(), anyString())).thenReturn(null);
        when(dcDao.findById(anyLong())).thenReturn(Mockito.mock(DataCenterVO.class));

        // should not throw
        service.processStopOrRebootAnswer(router, Mockito.mock(Answer.class));

        verify(userStatsDao, never()).update(anyLong(), any());
    }

    @Test
    public void testRunNetworkUsageCollectionInvokesCollectPerRouter() {
        DomainRouterVO r1 = Mockito.mock(DomainRouterVO.class);
        DomainRouterVO r2 = Mockito.mock(DomainRouterVO.class);
        when(r1.getPrivateIpAddress()).thenReturn(null); // makes collect return early
        when(r2.getPrivateIpAddress()).thenReturn(null);
        when(routerDao.listByStateAndNetworkType(eq(VirtualMachine.State.Running),
                eq(Network.GuestType.Isolated), anyLong())).thenReturn(Arrays.asList(r1, r2));

        service.runNetworkUsageCollection();

        // Both should be evaluated (private IP null short-circuits)
        verify(r1, times(1)).getPrivateIpAddress();
        verify(r2, times(1)).getPrivateIpAddress();
    }

    @Test
    public void testRunNetworkUsageCollectionSwallowsExceptions() {
        when(routerDao.listByStateAndNetworkType(any(), any(), anyLong()))
                .thenThrow(new RuntimeException("db down"));

        // must not propagate
        service.runNetworkUsageCollection();
    }

    @Test
    public void testRunNetworkStatsUpdateNoOpWhenLockNotOwned() {
        // The GlobalLock cannot be acquired without a DB. The method must
        // swallow that and return cleanly without touching the stats DAOs.
        service.runNetworkStatsUpdate();
        verify(userStatsDao, never()).listUpdatedStats();
    }

    @Test
    public void testSetDailyOrHourly() {
        service.setDailyOrHourly(true);
        // The flag affects collectNetworkStatistics. Without invoking the full path
        // we just confirm it is settable without exception.
        service.setDailyOrHourly(false);
    }

    @Test
    public void testCollectNetworkStatisticsForVpcRouterTargetsPublicNic() {
        when(router.getVpcId()).thenReturn(99L);
        when(networkModel.getNetwork(200L)).thenReturn(network);
        when(routedIpv4Manager.isRoutedNetwork(network)).thenReturn(false);
        when(network.getTrafficType()).thenReturn(TrafficType.Guest); // not Public — should be skipped for VPC
        when(nicDao.listByVmId(10L)).thenReturn(new ArrayList<>(Collections.singletonList(routerNic)));

        service.collectNetworkStatistics(router, null);

        verify(agentMgr, never()).easySend(anyLong(), any());
    }

    @Test
    public void testProcessStopOrRebootAnswerNoNetworks() {
        when(routerDao.getRouterNetworks(10L)).thenReturn(Collections.<Long>emptyList());

        service.processStopOrRebootAnswer(router, Mockito.mock(Answer.class));

        verify(userStatsDao, never()).lock(anyLong(), anyLong(), anyLong(), any(), anyLong(), anyString());
        verify(userStatsDao, never()).update(anyLong(), any());
    }

    @Test
    public void testCollectNetworkStatisticsCapturesUsageCommand() {
        when(router.getVpcId()).thenReturn(null);
        when(networkModel.getNetwork(200L)).thenReturn(network);
        when(routedIpv4Manager.isRoutedNetwork(network)).thenReturn(false);
        when(network.getTrafficType()).thenReturn(TrafficType.Guest);
        when(network.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(nicDao.listByVmId(10L)).thenReturn(new ArrayList<>(Collections.singletonList(routerNic)));
        when(agentMgr.easySend(anyLong(), any())).thenReturn(null);

        service.collectNetworkStatistics(router, null);

        ArgumentCaptor<com.cloud.agent.api.NetworkUsageCommand> cmd =
                ArgumentCaptor.forClass(com.cloud.agent.api.NetworkUsageCommand.class);
        verify(agentMgr).easySend(eq(100L), cmd.capture());
        assertEquals("router-1", cmd.getValue().getDomRName());
    }

    @Test
    public void testCollectNetworkStatisticsExceptionFromAgentManagerSwallowed() {
        when(router.getVpcId()).thenReturn(null);
        when(networkModel.getNetwork(200L)).thenReturn(network);
        when(routedIpv4Manager.isRoutedNetwork(network)).thenReturn(false);
        when(network.getTrafficType()).thenReturn(TrafficType.Guest);
        when(network.getGuestType()).thenReturn(Network.GuestType.Isolated);
        when(nicDao.listByVmId(10L)).thenReturn(new ArrayList<>(Collections.singletonList(routerNic)));
        when(agentMgr.easySend(anyLong(), any())).thenThrow(new RuntimeException("agent down"));

        // must not propagate
        service.collectNetworkStatistics(router, null);

        verify(userStatsDao, never()).update(anyLong(), any());
    }

    @Test
    public void testProcessStopOrRebootAnswerMultipleNetworks() {
        when(routerDao.getRouterNetworks(10L)).thenReturn(Arrays.asList(200L, 300L));
        UserStatisticsVO stats1 = Mockito.mock(UserStatisticsVO.class);
        UserStatisticsVO stats2 = Mockito.mock(UserStatisticsVO.class);
        when(stats1.getCurrentBytesReceived()).thenReturn(100L);
        when(stats1.getCurrentBytesSent()).thenReturn(50L);
        when(stats1.getNetBytesReceived()).thenReturn(0L);
        when(stats1.getNetBytesSent()).thenReturn(0L);
        when(stats1.getId()).thenReturn(1L);
        when(stats2.getCurrentBytesReceived()).thenReturn(200L);
        when(stats2.getCurrentBytesSent()).thenReturn(150L);
        when(stats2.getNetBytesReceived()).thenReturn(0L);
        when(stats2.getNetBytesSent()).thenReturn(0L);
        when(stats2.getId()).thenReturn(2L);
        when(userStatsDao.lock(eq(1L), eq(1L), eq(200L), isNull(), eq(10L), anyString())).thenReturn(stats1);
        when(userStatsDao.lock(eq(1L), eq(1L), eq(300L), isNull(), eq(10L), anyString())).thenReturn(stats2);

        service.processStopOrRebootAnswer(router, Mockito.mock(Answer.class));

        verify(userStatsDao).update(eq(1L), eq(stats1));
        verify(userStatsDao).update(eq(2L), eq(stats2));
    }

    @Test
    public void testListByVmIdResults() {
        // Ensure when nic is null, the impl looks up nics via nicDao
        when(router.getVpcId()).thenReturn(null);
        when(nicDao.listByVmId(10L)).thenReturn(Collections.<NicVO>emptyList());

        service.collectNetworkStatistics(router, null);

        verify(nicDao).listByVmId(10L);
        verify(agentMgr, never()).easySend(anyLong(), any());
    }
}
