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
package org.apache.cloudstack.engine.orchestration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.CheckNetworkAnswer;
import com.cloud.agent.api.CheckNetworkCommand;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.alert.AlertManager;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.ConnectionException;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.PhysicalNetworkSetupInfo;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeDao;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeVO;
import com.cloud.network.dao.PhysicalNetworkVO;

public class NetworkHostSetupServiceImplTest {

    private static final long HOST_ID = 101L;
    private static final long DC_ID = 11L;
    private static final long POD_ID = 22L;
    private static final long PHYSICAL_NETWORK_ID = 33L;
    private static final String PRIVATE_IP_ADDRESS = "10.1.1.5";

    private NetworkHostSetupServiceImpl service;
    private DataCenterDao dataCenterDao;
    private PhysicalNetworkDao physicalNetworkDao;
    private PhysicalNetworkTrafficTypeDao physicalNetworkTrafficTypeDao;
    private AgentManager agentManager;
    private AlertManager alertManager;
    private Host host;
    private StartupRoutingCommand startup;

    @Before
    public void setUp() {
        service = new NetworkHostSetupServiceImpl();
        dataCenterDao = mock(DataCenterDao.class);
        physicalNetworkDao = mock(PhysicalNetworkDao.class);
        physicalNetworkTrafficTypeDao = mock(PhysicalNetworkTrafficTypeDao.class);
        agentManager = mock(AgentManager.class);
        alertManager = mock(AlertManager.class);
        service.dataCenterDao = dataCenterDao;
        service.physicalNetworkDao = physicalNetworkDao;
        service.physicalNetworkTrafficTypeDao = physicalNetworkTrafficTypeDao;
        service.agentManager = agentManager;
        service.alertManager = alertManager;

        host = mock(Host.class);
        when(host.getId()).thenReturn(HOST_ID);
        when(host.getPodId()).thenReturn(POD_ID);

        startup = mock(StartupRoutingCommand.class);
        when(startup.getDataCenter()).thenReturn("zone-a");
        when(startup.getPrivateIpAddress()).thenReturn(PRIVATE_IP_ADDRESS);
        when(startup.getHypervisorType()).thenReturn(HypervisorType.KVM);
    }

    @Test
    public void testProcessConnectReturnsForNonRoutingStartupCommand() throws ConnectionException {
        service.processConnect(host, mock(StartupCommand.class), false);

        verify(dataCenterDao, never()).findByName(anyString());
        verify(physicalNetworkDao, never()).listByZone(anyLong());
        verify(agentManager, never()).easySend(anyLong(), any());
    }

    @Test
    public void testProcessConnectReturnsForTransferredConnection() throws ConnectionException {
        when(startup.isConnectionTransferred()).thenReturn(true);

        service.processConnect(host, startup, false);

        verify(dataCenterDao, never()).findByName(anyString());
        verify(physicalNetworkDao, never()).listByZone(anyLong());
        verify(agentManager, never()).easySend(anyLong(), any());
    }

    @Test
    public void testProcessConnectFindsDataCenterByName() throws ConnectionException {
        DataCenterVO dataCenter = dataCenter();
        when(dataCenterDao.findByName("zone-a")).thenReturn(dataCenter);
        stubSuccessfulEmptyNetworkCheck();

        service.processConnect(host, startup, false);

        verify(dataCenterDao).findByName("zone-a");
        verify(dataCenterDao, never()).findById(anyLong());
    }

    @Test
    public void testProcessConnectFindsDataCenterByNumericIdWhenNameMissing() throws ConnectionException {
        DataCenterVO dataCenter = dataCenter();
        when(startup.getDataCenter()).thenReturn(String.valueOf(DC_ID));
        when(dataCenterDao.findByName(String.valueOf(DC_ID))).thenReturn(null);
        when(dataCenterDao.findById(DC_ID)).thenReturn(dataCenter);
        stubSuccessfulEmptyNetworkCheck();

        service.processConnect(host, startup, false);

        verify(dataCenterDao).findByName(String.valueOf(DC_ID));
        verify(dataCenterDao).findById(DC_ID);
    }

    @Test
    public void testProcessConnectThrowsIllegalArgumentWhenDataCenterMissing() {
        when(startup.getDataCenter()).thenReturn("missing-zone");
        when(dataCenterDao.findByName("missing-zone")).thenReturn(null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () -> service.processConnect(host, startup, false));

        assertTrue(exception.getMessage().contains("Host " + PRIVATE_IP_ADDRESS + " sent incorrect data center: missing-zone"));
    }

    @Test
    public void testProcessConnectBuildsNetworkInfoAndSendsCheckCommand() throws ConnectionException {
        stubDataCenterByName();
        stubPhysicalNetworkSetup();
        ArgumentCaptor<CheckNetworkCommand> commandCaptor = ArgumentCaptor.forClass(CheckNetworkCommand.class);
        when(agentManager.easySend(eq(Long.valueOf(HOST_ID)), any(CheckNetworkCommand.class))).thenAnswer(invocation -> {
            CheckNetworkCommand command = invocation.getArgument(1);
            return new CheckNetworkAnswer(command, true, "ok");
        });

        service.processConnect(host, startup, false);

        verify(agentManager).easySend(eq(Long.valueOf(HOST_ID)), commandCaptor.capture());
        List<PhysicalNetworkSetupInfo> networkInfo = commandCaptor.getValue().getPhysicalNetworkInfoList();
        assertEquals(1, networkInfo.size());
        PhysicalNetworkSetupInfo setupInfo = networkInfo.get(0);
        assertEquals(Long.valueOf(PHYSICAL_NETWORK_ID), setupInfo.getPhysicalNetworkId());
        assertEquals("cloudbr0", setupInfo.getPublicNetworkName());
        assertEquals("cloudbr1", setupInfo.getPrivateNetworkName());
        assertEquals("cloudbr2", setupInfo.getGuestNetworkName());
        assertEquals("cloudbr3", setupInfo.getStorageNetworkName());
        assertEquals("100", setupInfo.getMgmtVlan());
    }

    @Test
    public void testProcessConnectSuccessWithoutReconnectDoesNotAlert() throws ConnectionException {
        stubDataCenterByName();
        stubPhysicalNetworkSetup();
        stubSuccessfulAgentAnswer(false);

        service.processConnect(host, startup, false);

        verify(alertManager, never()).sendAlert(any(), anyLong(), anyLong(), anyString(), anyString());
    }

    @Test
    public void testProcessConnectNullAnswerThrowsSetupErrorConnectionException() {
        stubDataCenterByName();
        stubPhysicalNetworkSetup();
        when(agentManager.easySend(eq(Long.valueOf(HOST_ID)), any(CheckNetworkCommand.class))).thenReturn(null);

        ConnectionException exception = assertThrows(ConnectionException.class, () -> service.processConnect(host, startup, false));

        assertTrue(exception.isSetupError());
        assertTrue(exception.getMessage().contains("Unable to get an answer to the CheckNetworkCommand from agent"));
    }

    @Test
    public void testProcessConnectFailedAnswerAlertsAndThrowsSetupError() {
        stubDataCenterByName();
        stubPhysicalNetworkSetup();
        String msg = "Incorrect Network setup on agent, Reinitialize agent after network names are setup, details : bad bridge";
        when(agentManager.easySend(eq(Long.valueOf(HOST_ID)), any(CheckNetworkCommand.class))).thenAnswer(invocation -> {
            CheckNetworkCommand command = invocation.getArgument(1);
            return new CheckNetworkAnswer(command, false, "bad bridge");
        });

        ConnectionException exception = assertThrows(ConnectionException.class, () -> service.processConnect(host, startup, false));

        verify(alertManager).sendAlert(AlertManager.AlertType.ALERT_TYPE_HOST, DC_ID, POD_ID, msg, msg);
        assertTrue(exception.isSetupError());
        assertEquals(msg, exception.getMessage());
    }

    @Test
    public void testProcessConnectReconnectAnswerThrowsNonSetupError() {
        stubDataCenterByName();
        stubPhysicalNetworkSetup();
        stubSuccessfulAgentAnswer(true);

        ConnectionException exception = assertThrows(ConnectionException.class, () -> service.processConnect(host, startup, false));

        assertFalse(exception.isSetupError());
        assertEquals("Reinitialize agent after network setup.", exception.getMessage());
    }

    private void stubDataCenterByName() {
        when(dataCenterDao.findByName("zone-a")).thenReturn(dataCenter());
    }

    private void stubSuccessfulEmptyNetworkCheck() {
        when(physicalNetworkDao.listByZone(DC_ID)).thenReturn(Collections.emptyList());
        stubSuccessfulAgentAnswer(false);
    }

    private void stubSuccessfulAgentAnswer(boolean reconnect) {
        when(agentManager.easySend(eq(Long.valueOf(HOST_ID)), any(CheckNetworkCommand.class))).thenAnswer(invocation -> {
            CheckNetworkCommand command = invocation.getArgument(1);
            return new CheckNetworkAnswer(command, true, "ok", reconnect);
        });
    }

    private void stubPhysicalNetworkSetup() {
        when(physicalNetworkDao.listByZone(DC_ID)).thenReturn(Collections.singletonList(physicalNetwork()));
        when(physicalNetworkTrafficTypeDao.getNetworkTag(PHYSICAL_NETWORK_ID, TrafficType.Public, HypervisorType.KVM)).thenReturn("cloudbr0");
        when(physicalNetworkTrafficTypeDao.getNetworkTag(PHYSICAL_NETWORK_ID, TrafficType.Management, HypervisorType.KVM)).thenReturn("cloudbr1");
        when(physicalNetworkTrafficTypeDao.getNetworkTag(PHYSICAL_NETWORK_ID, TrafficType.Guest, HypervisorType.KVM)).thenReturn("cloudbr2");
        when(physicalNetworkTrafficTypeDao.getNetworkTag(PHYSICAL_NETWORK_ID, TrafficType.Storage, HypervisorType.KVM)).thenReturn("cloudbr3");
        when(physicalNetworkTrafficTypeDao.findBy(PHYSICAL_NETWORK_ID, TrafficType.Management)).thenReturn(managementTraffic());
    }

    private DataCenterVO dataCenter() {
        return new DataCenterVO(DC_ID, "zone-a", "zone-a", "8.8.8.8", "8.8.4.4", "10.0.0.1", "10.0.0.2", "10.1.0.0/16", "domain",
                1L, DataCenter.NetworkType.Advanced, "token", "example.com");
    }

    private PhysicalNetworkVO physicalNetwork() {
        return new PhysicalNetworkVO(PHYSICAL_NETWORK_ID, DC_ID, null, null, null, PhysicalNetwork.BroadcastDomainRange.ZONE, "physical-network");
    }

    private PhysicalNetworkTrafficTypeVO managementTraffic() {
        return new PhysicalNetworkTrafficTypeVO(PHYSICAL_NETWORK_ID, TrafficType.Management, null, "cloudbr1", null, null, "100", null, null);
    }
}
