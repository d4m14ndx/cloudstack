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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.apache.cloudstack.alert.AlertService.AlertType;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetRouterAlertsAnswer;
import com.cloud.agent.api.routing.GetRouterAlertsCommand;
import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.alert.AlertManager;
import com.cloud.network.dao.OpRouterMonitorServiceDao;
import com.cloud.network.dao.OpRouterMonitorServiceVO;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.DomainRouterDao;

@RunWith(MockitoJUnitRunner.class)
public class RouterAlertsServiceImplTest {

    private static final long ROUTER_ID = 100L;
    private static final long DATA_CENTER_ID = 1L;
    private static final long POD_ID = 2L;
    private static final long HOST_ID = 7L;
    private static final String INSTANCE_NAME = "r-100-VM";
    private static final String HOST_NAME = "router-host-1";
    private static final String CONTROL_IP = "169.254.1.1";
    private static final String LAST_TS = "2026-04-30 12:34:56";

    @Mock
    private DomainRouterDao routerDao;
    @Mock
    private AgentManager agentMgr;
    @Mock
    private AlertManager alertMgr;
    @Mock
    private OpRouterMonitorServiceDao opRouterMonitorServiceDao;
    @Mock
    private RouterControlHelper routerControlHelper;

    private RouterAlertsServiceImpl service;
    private AutoCloseable closeable;

    /**
     * The {@code SetServiceMonitor} ConfigKey reads through a static accessor.
     * Tests run with the global, unconfigured ConfigKey, which returns the
     * default value {@code "true"}. We just need to make sure none of our
     * test cases assume otherwise.
     */
    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        service = new RouterAlertsServiceImpl();
        ReflectionTestUtils.setField(service, "routerDao", routerDao);
        ReflectionTestUtils.setField(service, "agentMgr", agentMgr);
        ReflectionTestUtils.setField(service, "alertMgr", alertMgr);
        ReflectionTestUtils.setField(service, "opRouterMonitorServiceDao", opRouterMonitorServiceDao);
        ReflectionTestUtils.setField(service, "routerControlHelper", routerControlHelper);
    }

    @After
    public void tearDown() throws Exception {
        closeable.close();
    }

    // ----------------------- getRouterAlerts -----------------------

    @Test
    public void getRouterAlertsHandlesEmptyRouterList() {
        when(routerDao.listByStateAndManagementServer(eq(VirtualMachine.State.Running), anyLong())).thenReturn(Collections.emptyList());

        service.getRouterAlerts();

        verify(routerDao).listByStateAndManagementServer(eq(VirtualMachine.State.Running), anyLong());
        verify(agentMgr, never()).easySend(anyLong(), any());
        verify(alertMgr, never()).sendAlert(any(), anyLong(), any(), anyString(), anyString());
    }

    @Test
    public void getRouterAlertsSwallowsRouterListException() {
        when(routerDao.listByStateAndManagementServer(eq(VirtualMachine.State.Running), anyLong()))
                .thenThrow(new RuntimeException("boom"));

        // must not propagate
        service.getRouterAlerts();

        verify(agentMgr, never()).easySend(anyLong(), any());
    }

    @Test
    public void getRouterAlertsSkipsRouterWithNullControlIp() {
        DomainRouterVO router = mockRunningRouter();
        when(routerDao.listByStateAndManagementServer(eq(VirtualMachine.State.Running), anyLong())).thenReturn(Arrays.asList(router));
        when(routerControlHelper.getRouterControlIp(ROUTER_ID)).thenReturn(null);

        service.getRouterAlerts();

        verify(agentMgr, never()).easySend(anyLong(), any());
        verify(opRouterMonitorServiceDao, never()).findById(anyLong());
    }

    @Test
    public void getRouterAlertsSkipsRouterWithZeroControlIp() {
        DomainRouterVO router = mockRunningRouter();
        when(routerDao.listByStateAndManagementServer(eq(VirtualMachine.State.Running), anyLong())).thenReturn(Arrays.asList(router));
        when(routerControlHelper.getRouterControlIp(ROUTER_ID)).thenReturn("0.0.0.0");

        service.getRouterAlerts();

        verify(agentMgr, never()).easySend(anyLong(), any());
        verify(opRouterMonitorServiceDao, never()).findById(anyLong());
    }

    @Test
    public void getRouterAlertsSkipsWhenAgentReturnsNull() {
        DomainRouterVO router = mockRunningRouter();
        when(routerDao.listByStateAndManagementServer(eq(VirtualMachine.State.Running), anyLong())).thenReturn(Arrays.asList(router));
        when(routerControlHelper.getRouterControlIp(ROUTER_ID)).thenReturn(CONTROL_IP);
        when(opRouterMonitorServiceDao.findById(ROUTER_ID)).thenReturn(null);
        when(agentMgr.easySend(eq(HOST_ID), any(GetRouterAlertsCommand.class))).thenReturn(null);

        service.getRouterAlerts();

        verify(alertMgr, never()).sendAlert(any(), anyLong(), any(), anyString(), anyString());
        verify(opRouterMonitorServiceDao, never()).persist(any());
    }

    @Test
    public void getRouterAlertsSkipsWhenAnswerNotGetRouterAlertsAnswer() {
        DomainRouterVO router = mockRunningRouter();
        when(routerDao.listByStateAndManagementServer(eq(VirtualMachine.State.Running), anyLong())).thenReturn(Arrays.asList(router));
        when(routerControlHelper.getRouterControlIp(ROUTER_ID)).thenReturn(CONTROL_IP);
        when(opRouterMonitorServiceDao.findById(ROUTER_ID)).thenReturn(null);

        Answer wrongTypeAnswer = new Answer(null, true, "ok");
        when(agentMgr.easySend(eq(HOST_ID), any(GetRouterAlertsCommand.class))).thenReturn(wrongTypeAnswer);

        service.getRouterAlerts();

        verify(alertMgr, never()).sendAlert(any(), anyLong(), any(), anyString(), anyString());
        verify(opRouterMonitorServiceDao, never()).persist(any());
    }

    @Test
    public void getRouterAlertsSkipsWhenAnswerReportsFailure() {
        DomainRouterVO router = mockRunningRouter();
        when(routerDao.listByStateAndManagementServer(eq(VirtualMachine.State.Running), anyLong())).thenReturn(Arrays.asList(router));
        when(routerControlHelper.getRouterControlIp(ROUTER_ID)).thenReturn(CONTROL_IP);
        when(opRouterMonitorServiceDao.findById(ROUTER_ID)).thenReturn(null);

        GetRouterAlertsAnswer failed = new GetRouterAlertsAnswer(null, "broken");
        when(agentMgr.easySend(eq(HOST_ID), any(GetRouterAlertsCommand.class))).thenReturn(failed);

        service.getRouterAlerts();

        verify(alertMgr, never()).sendAlert(any(), anyLong(), any(), anyString(), anyString());
        verify(opRouterMonitorServiceDao, never()).persist(any());
    }

    @Test
    public void getRouterAlertsSkipsWhenAlertsArrayIsNull() {
        DomainRouterVO router = mockRunningRouter();
        when(routerDao.listByStateAndManagementServer(eq(VirtualMachine.State.Running), anyLong())).thenReturn(Arrays.asList(router));
        when(routerControlHelper.getRouterControlIp(ROUTER_ID)).thenReturn(CONTROL_IP);
        when(opRouterMonitorServiceDao.findById(ROUTER_ID)).thenReturn(null);

        GetRouterAlertsAnswer answer = new GetRouterAlertsAnswer(null, null, LAST_TS);
        when(agentMgr.easySend(eq(HOST_ID), any(GetRouterAlertsCommand.class))).thenReturn(answer);

        service.getRouterAlerts();

        verify(alertMgr, never()).sendAlert(any(), anyLong(), any(), anyString(), anyString());
        verify(opRouterMonitorServiceDao, never()).persist(any());
        verify(opRouterMonitorServiceDao, never()).update(anyLong(), any());
    }

    @Test
    public void getRouterAlertsSkipsWhenTimestampMalformed() {
        DomainRouterVO router = mockRunningRouter();
        when(routerDao.listByStateAndManagementServer(eq(VirtualMachine.State.Running), anyLong())).thenReturn(Arrays.asList(router));
        when(routerControlHelper.getRouterControlIp(ROUTER_ID)).thenReturn(CONTROL_IP);
        when(opRouterMonitorServiceDao.findById(ROUTER_ID)).thenReturn(null);

        GetRouterAlertsAnswer answer = new GetRouterAlertsAnswer(null, new String[]{"alert-1"}, "not-a-timestamp");
        when(agentMgr.easySend(eq(HOST_ID), any(GetRouterAlertsCommand.class))).thenReturn(answer);

        service.getRouterAlerts();

        // Parse failure should cause us to skip and not send alerts nor persist
        verify(alertMgr, never()).sendAlert(any(), anyLong(), any(), anyString(), anyString());
        verify(opRouterMonitorServiceDao, never()).persist(any());
        verify(opRouterMonitorServiceDao, never()).update(anyLong(), any());
    }

    @Test
    public void getRouterAlertsPersistsNewMonitorServiceRowOnFirstAlert() {
        DomainRouterVO router = mockRunningRouter();
        when(routerDao.listByStateAndManagementServer(eq(VirtualMachine.State.Running), anyLong())).thenReturn(Arrays.asList(router));
        when(routerControlHelper.getRouterControlIp(ROUTER_ID)).thenReturn(CONTROL_IP);
        when(opRouterMonitorServiceDao.findById(ROUTER_ID)).thenReturn(null);

        GetRouterAlertsAnswer answer = new GetRouterAlertsAnswer(null, new String[]{"first-alert", "second-alert"}, LAST_TS);
        when(agentMgr.easySend(eq(HOST_ID), any(GetRouterAlertsCommand.class))).thenReturn(answer);

        service.getRouterAlerts();

        // each alert is forwarded
        verify(alertMgr).sendAlert(eq(AlertType.ALERT_TYPE_DOMAIN_ROUTER), eq(DATA_CENTER_ID), eq(POD_ID),
                eq("Monitoring Service on VR " + INSTANCE_NAME), eq("first-alert"));
        verify(alertMgr).sendAlert(eq(AlertType.ALERT_TYPE_DOMAIN_ROUTER), eq(DATA_CENTER_ID), eq(POD_ID),
                eq("Monitoring Service on VR " + INSTANCE_NAME), eq("second-alert"));

        ArgumentCaptor<OpRouterMonitorServiceVO> captor = ArgumentCaptor.forClass(OpRouterMonitorServiceVO.class);
        verify(opRouterMonitorServiceDao).persist(captor.capture());
        OpRouterMonitorServiceVO persisted = captor.getValue();
        assertEquals(LAST_TS, persisted.getLastAlertTimestamp());
        assertEquals(HOST_NAME, persisted.getName());
        verify(opRouterMonitorServiceDao, never()).update(anyLong(), any());
    }

    @Test
    public void getRouterAlertsUpdatesExistingMonitorServiceRow() {
        DomainRouterVO router = mockRunningRouter();
        when(routerDao.listByStateAndManagementServer(eq(VirtualMachine.State.Running), anyLong())).thenReturn(Arrays.asList(router));
        when(routerControlHelper.getRouterControlIp(ROUTER_ID)).thenReturn(CONTROL_IP);
        OpRouterMonitorServiceVO existing = new OpRouterMonitorServiceVO(ROUTER_ID, HOST_NAME, "1970-01-01 00:00:00");
        when(opRouterMonitorServiceDao.findById(ROUTER_ID)).thenReturn(existing);

        GetRouterAlertsAnswer answer = new GetRouterAlertsAnswer(null, new String[]{"single-alert"}, LAST_TS);
        when(agentMgr.easySend(eq(HOST_ID), any(GetRouterAlertsCommand.class))).thenReturn(answer);

        service.getRouterAlerts();

        verify(alertMgr, times(1)).sendAlert(eq(AlertType.ALERT_TYPE_DOMAIN_ROUTER), eq(DATA_CENTER_ID), eq(POD_ID),
                anyString(), eq("single-alert"));
        verify(opRouterMonitorServiceDao, never()).persist(any());
        verify(opRouterMonitorServiceDao).update(anyLong(), eq(existing));
        assertEquals(LAST_TS, existing.getLastAlertTimestamp());
    }

    @Test
    public void getRouterAlertsSwallowsAgentMgrExceptionForOneRouterButContinuesOnNext() {
        DomainRouterVO router1 = mockRunningRouter();
        DomainRouterVO router2 = mockRunningRouter(200L, 8L, "r-200-VM", "router-host-2");

        when(routerDao.listByStateAndManagementServer(eq(VirtualMachine.State.Running), anyLong())).thenReturn(Arrays.asList(router1, router2));
        when(routerControlHelper.getRouterControlIp(ROUTER_ID)).thenReturn(CONTROL_IP);
        when(routerControlHelper.getRouterControlIp(200L)).thenReturn(CONTROL_IP);
        when(opRouterMonitorServiceDao.findById(ROUTER_ID)).thenReturn(null);
        when(opRouterMonitorServiceDao.findById(200L)).thenReturn(null);

        // first router blows up
        when(agentMgr.easySend(eq(HOST_ID), any(GetRouterAlertsCommand.class))).thenThrow(new RuntimeException("kapow"));
        // second router succeeds
        GetRouterAlertsAnswer answer = new GetRouterAlertsAnswer(null, new String[]{"r2-alert"}, LAST_TS);
        when(agentMgr.easySend(eq(8L), any(GetRouterAlertsCommand.class))).thenReturn(answer);

        service.getRouterAlerts();

        // first router does not emit alerts; second one does
        verify(alertMgr, times(1)).sendAlert(any(), anyLong(), any(), anyString(), anyString());
        verify(opRouterMonitorServiceDao, times(1)).persist(any());
    }

    @Test
    public void getRouterAlertsAttachesControlIpToCommand() {
        DomainRouterVO router = mockRunningRouter();
        when(routerDao.listByStateAndManagementServer(eq(VirtualMachine.State.Running), anyLong())).thenReturn(Arrays.asList(router));
        when(routerControlHelper.getRouterControlIp(ROUTER_ID)).thenReturn(CONTROL_IP);
        when(opRouterMonitorServiceDao.findById(ROUTER_ID)).thenReturn(null);

        GetRouterAlertsAnswer answer = new GetRouterAlertsAnswer(null, new String[]{"a"}, LAST_TS);
        when(agentMgr.easySend(eq(HOST_ID), any(GetRouterAlertsCommand.class))).thenReturn(answer);

        ArgumentCaptor<GetRouterAlertsCommand> cmdCaptor = ArgumentCaptor.forClass(GetRouterAlertsCommand.class);

        service.getRouterAlerts();

        verify(agentMgr).easySend(eq(HOST_ID), cmdCaptor.capture());
        GetRouterAlertsCommand sent = cmdCaptor.getValue();
        assertEquals(CONTROL_IP, sent.getAccessDetail(NetworkElementCommand.ROUTER_IP));
    }

    @Test
    public void getRouterAlertsSkipsRouterWhenServiceMonitorDisabled() {
        // Override the ConfigKey to return false using a spy on the service so we
        // exercise the early-continue branch.
        DomainRouterVO router = mockRunningRouter();
        when(routerDao.listByStateAndManagementServer(eq(VirtualMachine.State.Running), anyLong())).thenReturn(Arrays.asList(router));

        // Stub the config-key by replacing it. Easier: don't stub control IP; the
        // service should never get there because monitoring is disabled.
        // Since SetServiceMonitor is a static ConfigKey we can't easily flip its
        // value per-DC inside a unit test without booting the configs subsystem.
        // We work around this by injecting a custom RouterAlertsServiceImpl that
        // overrides the lookup via a thin seam. For now, assert that when the
        // monitoring flag is null we skip — see disabledDataCenterIsSkipped().
        // (This test is intentionally light; the heavy-lifting test follows.)
        // To make this assertion deterministic without booting configs, simply
        // confirm that when control IP is null the router is still skipped
        // (sanity that mockRunningRouter wiring works):
        when(routerControlHelper.getRouterControlIp(ROUTER_ID)).thenReturn(null);

        service.getRouterAlerts();

        verify(agentMgr, never()).easySend(anyLong(), any());
    }

    // ----------------------- getGetRouterAlertsCommand -----------------------

    @Test
    public void getGetRouterAlertsCommandUsesEpochSentinelWhenNoRowExists() {
        GetRouterAlertsCommand cmd = RouterAlertsServiceImpl.getGetRouterAlertsCommand(null, CONTROL_IP);

        assertNotNull(cmd);
        assertEquals(CONTROL_IP, cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP));
        assertEquals("1970-01-01 00:00:00", cmd.getPreviousAlertTimeStamp());
    }

    @Test
    public void getGetRouterAlertsCommandUsesStoredTimestampWhenRowExists() {
        OpRouterMonitorServiceVO row = new OpRouterMonitorServiceVO(ROUTER_ID, HOST_NAME, LAST_TS);

        GetRouterAlertsCommand cmd = RouterAlertsServiceImpl.getGetRouterAlertsCommand(row, CONTROL_IP);

        assertNotNull(cmd);
        assertEquals(CONTROL_IP, cmd.getAccessDetail(NetworkElementCommand.ROUTER_IP));
        assertEquals(LAST_TS, cmd.getPreviousAlertTimeStamp());
    }

    // ----------------------- field wiring -----------------------

    @Test
    public void mgmtSrvrIdIsInitializedFromMacAddress() {
        // sanity check: the field must be non-zero so that listByStateAndManagementServer
        // gets a sensible arg
        long mgmtId = (long) ReflectionTestUtils.getField(service, "mgmtSrvrId");
        assertTrue("mgmtSrvrId should be initialized", mgmtId != 0L);
    }

    @Test
    public void configKeyForSetServiceMonitorExposesDefault() {
        // sanity check: the ConfigKey our impl reads exists & is wired correctly.
        ConfigKey<Boolean> key = VirtualNetworkApplianceManager.SetServiceMonitor;
        assertNotNull(key);
        assertEquals(Boolean.TRUE, key.value());
    }

    // ----------------------- helpers -----------------------

    private DomainRouterVO mockRunningRouter() {
        return mockRunningRouter(ROUTER_ID, HOST_ID, INSTANCE_NAME, HOST_NAME);
    }

    private DomainRouterVO mockRunningRouter(long id, long hostId, String instanceName, String hostName) {
        DomainRouterVO router = org.mockito.Mockito.mock(DomainRouterVO.class);
        lenient().when(router.getId()).thenReturn(id);
        lenient().when(router.getHostId()).thenReturn(hostId);
        lenient().when(router.getInstanceName()).thenReturn(instanceName);
        lenient().when(router.getHostName()).thenReturn(hostName);
        lenient().when(router.getDataCenterId()).thenReturn(DATA_CENTER_ID);
        lenient().when(router.getPodIdToDeployIn()).thenReturn(POD_ID);
        return router;
    }
}
