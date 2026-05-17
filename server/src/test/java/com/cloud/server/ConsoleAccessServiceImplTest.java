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
package com.cloud.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVncPortAnswer;
import com.cloud.agent.api.GetVncPortCommand;
import com.cloud.agent.api.proxy.AllowConsoleAccessCommand;
import com.cloud.consoleproxy.ConsoleProxyManager;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host.Type;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.info.ConsoleProxyInfo;
import com.cloud.utils.Pair;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class ConsoleAccessServiceImplTest {

    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private ConsoleProxyManager consoleProxyManager;
    @Mock
    private HostDao hostDao;
    @Mock
    private AgentManager agentManager;

    @InjectMocks
    private ConsoleAccessServiceImpl service = new ConsoleAccessServiceImpl();

    private AutoCloseable closeable;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
    }

    @After
    public void tearDown() throws Exception {
        closeable.close();
    }

    // --- getConsoleAccessUrlRoot ------------------------------------------

    @Test
    public void getConsoleAccessUrlRootReturnsNullWhenVmMissing() {
        when(vmInstanceDao.findById(5L)).thenReturn(null);

        assertNull(service.getConsoleAccessUrlRoot(5L));
    }

    @Test
    public void getConsoleAccessUrlRootReturnsNullWhenProxyNotAssigned() {
        VMInstanceVO vm = mockVm(5L, 11L);
        when(consoleProxyManager.assignProxy(11L, vm)).thenReturn(null);

        assertNull(service.getConsoleAccessUrlRoot(5L));
    }

    @Test
    public void getConsoleAccessUrlRootReturnsProxyImageUrl() {
        VMInstanceVO vm = mockVm(5L, 11L);
        ConsoleProxyInfo proxy = mock(ConsoleProxyInfo.class, "proxy-image");
        when(proxy.getProxyImageUrl()).thenReturn("https://cpvm-1.example/console");
        when(consoleProxyManager.assignProxy(11L, vm)).thenReturn(proxy);

        assertEquals("https://cpvm-1.example/console", service.getConsoleAccessUrlRoot(5L));
    }

    // --- getConsoleAccessAddress -----------------------------------------

    @Test
    public void getConsoleAccessAddressReturnsNullWhenVmMissing() {
        when(vmInstanceDao.findById(6L)).thenReturn(null);

        assertNull(service.getConsoleAccessAddress(6L));
    }

    @Test
    public void getConsoleAccessAddressReturnsNullWhenProxyNotAssigned() {
        VMInstanceVO vm = mockVm(6L, 22L);
        when(consoleProxyManager.assignProxy(22L, vm)).thenReturn(null);

        assertNull(service.getConsoleAccessAddress(6L));
    }

    @Test
    public void getConsoleAccessAddressReturnsProxyAddress() {
        VMInstanceVO vm = mockVm(6L, 22L);
        ConsoleProxyInfo proxy = mock(ConsoleProxyInfo.class, "proxy-addr");
        when(proxy.getProxyAddress()).thenReturn("10.0.0.42");
        when(consoleProxyManager.assignProxy(22L, vm)).thenReturn(proxy);

        assertEquals("10.0.0.42", service.getConsoleAccessAddress(6L));
    }

    // --- setConsoleAccessForVm -------------------------------------------

    @Test
    public void setConsoleAccessRejectsMissingVm() {
        when(vmInstanceDao.findById(7L)).thenReturn(null);

        Pair<Boolean, String> result = service.setConsoleAccessForVm(7L, "uuid");

        assertFalse(result.first());
        assertTrue(result.second().contains("Cannot find an instance"));
    }

    @Test
    public void setConsoleAccessRejectsMissingProxy() {
        VMInstanceVO vm = mockVm(8L, 33L);
        when(consoleProxyManager.assignProxy(33L, vm)).thenReturn(null);

        Pair<Boolean, String> result = service.setConsoleAccessForVm(8L, "uuid");

        assertFalse(result.first());
        assertTrue(result.second().contains("Cannot find a console proxy for"));
    }

    @Test
    public void setConsoleAccessRejectsMissingProxyAgent() {
        VMInstanceVO vm = mockVm(9L, 44L);
        ConsoleProxyInfo proxy = mock(ConsoleProxyInfo.class, "proxy-noagent");
        when(proxy.getProxyName()).thenReturn("cpvm-5");
        when(consoleProxyManager.assignProxy(44L, vm)).thenReturn(proxy);
        when(hostDao.findByTypeNameAndZoneId(44L, "cpvm-5", Type.ConsoleProxy)).thenReturn(null);

        Pair<Boolean, String> result = service.setConsoleAccessForVm(9L, "uuid");

        assertFalse(result.first());
        assertTrue(result.second().contains("Cannot find a console proxy agent"));
    }

    @Test
    public void setConsoleAccessReturnsFailureWhenAgentUnavailable() throws Exception {
        VMInstanceVO vm = mockVm(10L, 55L);
        ConsoleProxyInfo proxy = mock(ConsoleProxyInfo.class, "proxy-unreachable");
        when(proxy.getProxyName()).thenReturn("cpvm-99");
        when(consoleProxyManager.assignProxy(55L, vm)).thenReturn(proxy);
        HostVO proxyHost = mock(HostVO.class);
        when(proxyHost.getId()).thenReturn(900L);
        when(hostDao.findByTypeNameAndZoneId(55L, "cpvm-99", Type.ConsoleProxy)).thenReturn(proxyHost);
        when(agentManager.send(eq(900L), any(AllowConsoleAccessCommand.class)))
                .thenThrow(new AgentUnavailableException("boom", 900L));

        Pair<Boolean, String> result = service.setConsoleAccessForVm(10L, "uuid");

        assertFalse(result.first());
        assertTrue(result.second().startsWith("Could not send allow session"));
    }

    @Test
    public void setConsoleAccessReturnsFailureWhenAgentTimesOut() throws Exception {
        VMInstanceVO vm = mockVm(11L, 56L);
        ConsoleProxyInfo proxy = mock(ConsoleProxyInfo.class, "proxy-timeout");
        when(proxy.getProxyName()).thenReturn("cpvm-100");
        when(consoleProxyManager.assignProxy(56L, vm)).thenReturn(proxy);
        HostVO proxyHost = mock(HostVO.class);
        when(proxyHost.getId()).thenReturn(901L);
        when(hostDao.findByTypeNameAndZoneId(56L, "cpvm-100", Type.ConsoleProxy)).thenReturn(proxyHost);
        when(agentManager.send(eq(901L), any(AllowConsoleAccessCommand.class)))
                .thenThrow(new OperationTimedoutException(new com.cloud.agent.api.Command[0], 901L, 0L, 0, false));

        Pair<Boolean, String> result = service.setConsoleAccessForVm(11L, "uuid");

        assertFalse(result.first());
        assertTrue(result.second().startsWith("Could not send allow session"));
    }

    @Test
    public void setConsoleAccessReturnsNullAnswerSentinelWhenAgentReturnsNull() throws Exception {
        VMInstanceVO vm = mockVm(12L, 57L);
        ConsoleProxyInfo proxy = mock(ConsoleProxyInfo.class, "proxy-null");
        when(proxy.getProxyName()).thenReturn("cpvm-101");
        when(consoleProxyManager.assignProxy(57L, vm)).thenReturn(proxy);
        HostVO proxyHost = mock(HostVO.class);
        when(proxyHost.getId()).thenReturn(902L);
        when(hostDao.findByTypeNameAndZoneId(57L, "cpvm-101", Type.ConsoleProxy)).thenReturn(proxyHost);
        when(agentManager.send(eq(902L), any(AllowConsoleAccessCommand.class))).thenReturn(null);

        Pair<Boolean, String> result = service.setConsoleAccessForVm(12L, "uuid");

        assertFalse(result.first());
        assertEquals("null answer", result.second());
    }

    @Test
    public void setConsoleAccessReturnsSuccessWhenAgentAcknowledges() throws Exception {
        VMInstanceVO vm = mockVm(13L, 58L);
        ConsoleProxyInfo proxy = mock(ConsoleProxyInfo.class, "proxy-ok");
        when(proxy.getProxyName()).thenReturn("cpvm-102");
        when(consoleProxyManager.assignProxy(58L, vm)).thenReturn(proxy);
        HostVO proxyHost = mock(HostVO.class);
        when(proxyHost.getId()).thenReturn(903L);
        when(hostDao.findByTypeNameAndZoneId(58L, "cpvm-102", Type.ConsoleProxy)).thenReturn(proxyHost);
        Answer answer = mock(Answer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getDetails()).thenReturn("session-stored");
        when(agentManager.send(eq(903L), any(AllowConsoleAccessCommand.class))).thenReturn(answer);

        Pair<Boolean, String> result = service.setConsoleAccessForVm(13L, "session-uuid");

        assertTrue(result.first());
        assertEquals("session-stored", result.second());
        // Verify the command we send carries the supplied UUID.
        ArgumentCaptor<AllowConsoleAccessCommand> captor =
                ArgumentCaptor.forClass(AllowConsoleAccessCommand.class);
        verify(agentManager).send(eq(903L), captor.capture());
        assertEquals("session-uuid", captor.getValue().getSessionUuid());
    }

    @Test
    public void setConsoleAccessReturnsAgentNegativeAnswerVerbatim() throws Exception {
        VMInstanceVO vm = mockVm(14L, 59L);
        ConsoleProxyInfo proxy = mock(ConsoleProxyInfo.class, "proxy-neg");
        when(proxy.getProxyName()).thenReturn("cpvm-103");
        when(consoleProxyManager.assignProxy(59L, vm)).thenReturn(proxy);
        HostVO proxyHost = mock(HostVO.class);
        when(proxyHost.getId()).thenReturn(904L);
        when(hostDao.findByTypeNameAndZoneId(59L, "cpvm-103", Type.ConsoleProxy)).thenReturn(proxyHost);
        Answer answer = mock(Answer.class);
        when(answer.getResult()).thenReturn(false);
        when(answer.getDetails()).thenReturn("session table full");
        when(agentManager.send(eq(904L), any(AllowConsoleAccessCommand.class))).thenReturn(answer);

        Pair<Boolean, String> result = service.setConsoleAccessForVm(14L, "uuid");

        assertFalse(result.first());
        assertEquals("session table full", result.second());
    }

    // --- getVncPort ------------------------------------------------------

    @Test
    public void getVncPortReturnsSentinelWhenVmHasNoHost() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getHostId()).thenReturn(null);

        Pair<String, Integer> result = service.getVncPort(vm);

        assertNull(result.first());
        assertEquals(Integer.valueOf(-1), result.second());
        verify(agentManager, never()).easySend(anyLong(), any());
    }

    @Test
    public void getVncPortQueriesCurrentHostForRunningVm() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getHostId()).thenReturn(200L);
        when(vm.getState()).thenReturn(State.Running);
        when(vm.getId()).thenReturn(20L);
        when(vm.getInstanceName()).thenReturn("i-1-20-VM");

        GetVncPortAnswer answer = mock(GetVncPortAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getAddress()).thenReturn("10.1.1.1");
        when(answer.getPort()).thenReturn(5901);
        when(agentManager.easySend(eq(200L), any(GetVncPortCommand.class))).thenReturn(answer);

        Pair<String, Integer> result = service.getVncPort(vm);

        assertEquals("10.1.1.1", result.first());
        assertEquals(Integer.valueOf(5901), result.second());
        verify(agentManager).easySend(eq(200L), any(GetVncPortCommand.class));
    }

    @Test
    public void getVncPortQueriesLastHostWhenMigrating() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getHostId()).thenReturn(201L);
        when(vm.getLastHostId()).thenReturn(300L);
        when(vm.getState()).thenReturn(State.Migrating);
        when(vm.getId()).thenReturn(21L);
        when(vm.getInstanceName()).thenReturn("i-1-21-VM");

        GetVncPortAnswer answer = mock(GetVncPortAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getAddress()).thenReturn("10.1.1.2");
        when(answer.getPort()).thenReturn(5902);
        when(agentManager.easySend(eq(300L), any(GetVncPortCommand.class))).thenReturn(answer);

        Pair<String, Integer> result = service.getVncPort(vm);

        assertEquals("10.1.1.2", result.first());
        assertEquals(Integer.valueOf(5902), result.second());
        verify(agentManager).easySend(eq(300L), any(GetVncPortCommand.class));
        verify(agentManager, never()).easySend(eq(201L), any(GetVncPortCommand.class));
    }

    @Test
    public void getVncPortFallsBackToCurrentHostWhenMigratingButLastHostNull() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getHostId()).thenReturn(202L);
        when(vm.getLastHostId()).thenReturn(null);
        when(vm.getState()).thenReturn(State.Migrating);
        when(vm.getId()).thenReturn(22L);
        when(vm.getInstanceName()).thenReturn("i-1-22-VM");

        GetVncPortAnswer answer = mock(GetVncPortAnswer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getAddress()).thenReturn("10.1.1.3");
        when(answer.getPort()).thenReturn(5903);
        when(agentManager.easySend(eq(202L), any(GetVncPortCommand.class))).thenReturn(answer);

        Pair<String, Integer> result = service.getVncPort(vm);

        assertEquals("10.1.1.3", result.first());
        assertEquals(Integer.valueOf(5903), result.second());
    }

    @Test
    public void getVncPortReturnsSentinelWhenAgentAnswerIsNull() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getHostId()).thenReturn(203L);
        when(vm.getState()).thenReturn(State.Running);
        when(vm.getId()).thenReturn(23L);
        when(vm.getInstanceName()).thenReturn("i-1-23-VM");
        when(agentManager.easySend(eq(203L), any(GetVncPortCommand.class))).thenReturn(null);

        Pair<String, Integer> result = service.getVncPort(vm);

        assertNull(result.first());
        assertEquals(Integer.valueOf(-1), result.second());
    }

    @Test
    public void getVncPortReturnsSentinelWhenAgentAnswerNegative() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getHostId()).thenReturn(204L);
        when(vm.getState()).thenReturn(State.Running);
        when(vm.getId()).thenReturn(24L);
        when(vm.getInstanceName()).thenReturn("i-1-24-VM");

        GetVncPortAnswer answer = mock(GetVncPortAnswer.class);
        when(answer.getResult()).thenReturn(false);
        when(agentManager.easySend(eq(204L), any(GetVncPortCommand.class))).thenReturn(answer);

        Pair<String, Integer> result = service.getVncPort(vm);

        assertNull(result.first());
        assertEquals(Integer.valueOf(-1), result.second());
    }

    // --- helpers ---------------------------------------------------------

    /**
     * Wire a {@link VMInstanceVO} for the supplied id/zone pair into the dao.
     * Centralised so each call site keeps to one or two lines of setup.
     */
    private VMInstanceVO mockVm(final long vmId, final long zoneId) {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getDataCenterId()).thenReturn(zoneId);
        when(vmInstanceDao.findById(vmId)).thenReturn(vm);
        return vm;
    }

    @SuppressWarnings("unchecked")
    private static <T> T mock(Class<T> cls, String name) {
        return org.mockito.Mockito.mock(cls, name);
    }

    @SuppressWarnings("unchecked")
    private static <T> T mock(Class<T> cls) {
        return org.mockito.Mockito.mock(cls);
    }
}
