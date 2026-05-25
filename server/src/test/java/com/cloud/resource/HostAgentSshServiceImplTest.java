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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.BDDMockito;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.UpdateHostPasswordCommand;
import com.cloud.host.DetailVO;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.utils.Ternary;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.ssh.SSHCmdHelper;
import com.cloud.utils.ssh.SshException;
import com.trilead.ssh2.Connection;

/**
 * Focused unit tests for {@link HostAgentSshServiceImpl} — the Phase 4
 * extraction of SSH-credential resolution, agent restart over SSH, and
 * agent-side host password updates out of {@link ResourceManagerImpl}.
 *
 * <p>Static {@link SSHCmdHelper} calls are intercepted with
 * {@link MockedStatic}; DAO / agent collaborators are field-injected
 * mocks. Manager-level wrappers (which still own propagation across
 * management-server peers) are exercised separately in
 * {@code ResourceManagerImplTest}.
 */
public class HostAgentSshServiceImplTest {

    private static final long HOST_ID = 42L;
    private static final int SSH_PORT = 22;
    private static final String USERNAME = "root";
    private static final String PASSWORD = "secret";
    private static final String PRIVATE_KEY = "private-key-pem";
    private static final String HOST_IP = "10.1.2.3";

    private HostDao hostDao;
    private HostDetailsDao hostDetailsDao;
    private ConfigurationDao configurationDao;
    private AgentManager agentManager;

    private HostVO host;
    private Connection sshConnection;

    private MockedStatic<SSHCmdHelper> sshHelperMocked;

    private HostAgentSshServiceImpl service;

    @Before
    public void setUp() {
        hostDao = mock(HostDao.class);
        hostDetailsDao = mock(HostDetailsDao.class);
        configurationDao = mock(ConfigurationDao.class);
        agentManager = mock(AgentManager.class);

        host = mock(HostVO.class);
        when(host.getId()).thenReturn(HOST_ID);
        when(host.getPrivateIpAddress()).thenReturn(HOST_IP);

        sshConnection = mock(Connection.class);
        sshHelperMocked = Mockito.mockStatic(SSHCmdHelper.class);

        service = new HostAgentSshServiceImpl();
        service.hostDao = hostDao;
        service.hostDetailsDao = hostDetailsDao;
        service.configurationDao = configurationDao;
        service.agentManager = agentManager;
    }

    @After
    public void tearDown() {
        sshHelperMocked.close();
    }

    // ---- getHostCredentials ----

    @Test
    public void getHostCredentials_returnsTriple_whenAllPresent() {
        when(host.getDetail("username")).thenReturn(USERNAME);
        when(host.getDetail("password")).thenReturn(PASSWORD);
        when(configurationDao.getValue("ssh.privatekey")).thenReturn(PRIVATE_KEY);

        Ternary<String, String, String> credentials = service.getHostCredentials(host);

        verify(hostDao).loadDetails(host);
        assertEquals(USERNAME, credentials.first());
        assertEquals(PASSWORD, credentials.second());
        assertEquals(PRIVATE_KEY, credentials.third());
    }

    @Test
    public void getHostCredentials_returnsTriple_whenOnlyPrivateKey() {
        // password is null but private key is present; should still resolve.
        when(host.getDetail("username")).thenReturn(USERNAME);
        when(host.getDetail("password")).thenReturn(null);
        when(configurationDao.getValue("ssh.privatekey")).thenReturn(PRIVATE_KEY);

        Ternary<String, String, String> credentials = service.getHostCredentials(host);

        assertEquals(USERNAME, credentials.first());
        assertEquals(null, credentials.second());
        assertEquals(PRIVATE_KEY, credentials.third());
    }

    @Test(expected = CloudRuntimeException.class)
    public void getHostCredentials_throws_whenUsernameMissing() {
        when(host.getDetail("username")).thenReturn(null);
        when(host.getDetail("password")).thenReturn(PASSWORD);
        when(configurationDao.getValue("ssh.privatekey")).thenReturn(PRIVATE_KEY);
        service.getHostCredentials(host);
    }

    @Test(expected = CloudRuntimeException.class)
    public void getHostCredentials_throws_whenPasswordAndPrivateKeyBothMissing() {
        when(host.getDetail("username")).thenReturn(USERNAME);
        when(host.getDetail("password")).thenReturn(null);
        when(configurationDao.getValue("ssh.privatekey")).thenReturn(null);
        service.getHostCredentials(host);
    }

    // ---- connectAndRestartAgentOnHost ----

    @Test
    public void connectAndRestartAgentOnHost_happyPath() throws SshException {
        when(agentManager.getHostSshPort(host)).thenReturn(SSH_PORT);
        BDDMockito.given(SSHCmdHelper.acquireAuthorizedConnection(eq(HOST_IP), eq(SSH_PORT),
                eq(USERNAME), eq(PASSWORD), eq(PRIVATE_KEY))).willReturn(sshConnection);
        BDDMockito.given(SSHCmdHelper.sshExecuteCmdOneShot(eq(sshConnection),
                eq("service cloudstack-agent restart")))
                .willReturn(new SSHCmdHelper.SSHCmdResult(0, "", ""));

        service.connectAndRestartAgentOnHost(host, USERNAME, PASSWORD, PRIVATE_KEY);

        sshHelperMocked.verify(() -> SSHCmdHelper.acquireAuthorizedConnection(eq(HOST_IP),
                eq(SSH_PORT), eq(USERNAME), eq(PASSWORD), eq(PRIVATE_KEY)), times(1));
        sshHelperMocked.verify(() -> SSHCmdHelper.sshExecuteCmdOneShot(eq(sshConnection),
                eq("service cloudstack-agent restart")), times(1));
    }

    @Test(expected = CloudRuntimeException.class)
    public void connectAndRestartAgentOnHost_throws_whenConnectFails() {
        when(agentManager.getHostSshPort(host)).thenReturn(SSH_PORT);
        BDDMockito.given(SSHCmdHelper.acquireAuthorizedConnection(eq(HOST_IP), eq(SSH_PORT),
                eq(USERNAME), eq(PASSWORD), eq(PRIVATE_KEY))).willReturn(null);

        service.connectAndRestartAgentOnHost(host, USERNAME, PASSWORD, PRIVATE_KEY);
    }

    @Test(expected = CloudRuntimeException.class)
    public void connectAndRestartAgentOnHost_throws_whenCommandReturnsNonZero() throws SshException {
        when(agentManager.getHostSshPort(host)).thenReturn(SSH_PORT);
        BDDMockito.given(SSHCmdHelper.acquireAuthorizedConnection(eq(HOST_IP), eq(SSH_PORT),
                eq(USERNAME), eq(PASSWORD), eq(PRIVATE_KEY))).willReturn(sshConnection);
        BDDMockito.given(SSHCmdHelper.sshExecuteCmdOneShot(eq(sshConnection),
                eq("service cloudstack-agent restart")))
                .willReturn(new SSHCmdHelper.SSHCmdResult(1, "", "failed"));

        service.connectAndRestartAgentOnHost(host, USERNAME, PASSWORD, PRIVATE_KEY);
    }

    @Test(expected = CloudRuntimeException.class)
    public void connectAndRestartAgentOnHost_throws_whenSshExecuteThrows() throws SshException {
        when(agentManager.getHostSshPort(host)).thenReturn(SSH_PORT);
        BDDMockito.given(SSHCmdHelper.acquireAuthorizedConnection(eq(HOST_IP), eq(SSH_PORT),
                eq(USERNAME), eq(PASSWORD), eq(PRIVATE_KEY))).willReturn(sshConnection);
        BDDMockito.given(SSHCmdHelper.sshExecuteCmdOneShot(eq(sshConnection),
                eq("service cloudstack-agent restart")))
                .willThrow(new SshException("boom"));

        service.connectAndRestartAgentOnHost(host, USERNAME, PASSWORD, PRIVATE_KEY);
    }

    // ---- doUpdateHostPassword ----

    @Test
    public void doUpdateHostPassword_returnsFalse_whenAgentNotAttached() {
        when(agentManager.isAgentAttached(HOST_ID)).thenReturn(false);

        assertFalse(service.doUpdateHostPassword(HOST_ID));

        verify(hostDetailsDao, never()).findDetail(eq(HOST_ID), any());
        verify(agentManager, never()).easySend(eq(HOST_ID), any(UpdateHostPasswordCommand.class));
    }

    @Test
    public void doUpdateHostPassword_sendsCommandAndReturnsAnswerResult_whenSuccess() {
        when(agentManager.isAgentAttached(HOST_ID)).thenReturn(true);
        DetailVO usernameDetail = mock(DetailVO.class);
        when(usernameDetail.getValue()).thenReturn(USERNAME);
        when(hostDetailsDao.findDetail(HOST_ID, ApiConstants.USERNAME)).thenReturn(usernameDetail);
        DetailVO passwordDetail = mock(DetailVO.class);
        when(passwordDetail.getValue()).thenReturn(PASSWORD);
        when(hostDetailsDao.findDetail(HOST_ID, ApiConstants.PASSWORD)).thenReturn(passwordDetail);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        Answer answer = mock(Answer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getDetails()).thenReturn("ok");
        when(agentManager.easySend(eq(HOST_ID), any(UpdateHostPasswordCommand.class))).thenReturn(answer);

        assertTrue(service.doUpdateHostPassword(HOST_ID));

        verify(agentManager, times(1)).easySend(eq(HOST_ID), any(UpdateHostPasswordCommand.class));
    }

    @Test
    public void doUpdateHostPassword_returnsAnswerResult_whenAgentReportsFailure() {
        when(agentManager.isAgentAttached(HOST_ID)).thenReturn(true);
        DetailVO usernameDetail = mock(DetailVO.class);
        when(usernameDetail.getValue()).thenReturn(USERNAME);
        when(hostDetailsDao.findDetail(HOST_ID, ApiConstants.USERNAME)).thenReturn(usernameDetail);
        DetailVO passwordDetail = mock(DetailVO.class);
        when(passwordDetail.getValue()).thenReturn(PASSWORD);
        when(hostDetailsDao.findDetail(HOST_ID, ApiConstants.PASSWORD)).thenReturn(passwordDetail);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        Answer answer = mock(Answer.class);
        when(answer.getResult()).thenReturn(false);
        when(answer.getDetails()).thenReturn("denied");
        when(agentManager.easySend(eq(HOST_ID), any(UpdateHostPasswordCommand.class))).thenReturn(answer);

        assertFalse(service.doUpdateHostPassword(HOST_ID));

        verify(agentManager, times(1)).easySend(eq(HOST_ID), any(UpdateHostPasswordCommand.class));
    }

    @Test
    public void doUpdateHostPassword_loadsCredentialsFromHostDetailsDao() {
        // Verify the credentials are read from HostDetailsDao using the
        // ApiConstants.USERNAME / ApiConstants.PASSWORD detail keys (and
        // not from HostVO.getDetail, which is what getHostCredentials uses).
        when(agentManager.isAgentAttached(HOST_ID)).thenReturn(true);
        DetailVO usernameDetail = mock(DetailVO.class);
        when(usernameDetail.getValue()).thenReturn(USERNAME);
        when(hostDetailsDao.findDetail(HOST_ID, ApiConstants.USERNAME)).thenReturn(usernameDetail);
        DetailVO passwordDetail = mock(DetailVO.class);
        when(passwordDetail.getValue()).thenReturn(PASSWORD);
        when(hostDetailsDao.findDetail(HOST_ID, ApiConstants.PASSWORD)).thenReturn(passwordDetail);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        Answer answer = mock(Answer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getDetails()).thenReturn("ok");
        when(agentManager.easySend(eq(HOST_ID), any(UpdateHostPasswordCommand.class))).thenReturn(answer);

        service.doUpdateHostPassword(HOST_ID);

        verify(hostDetailsDao, times(1)).findDetail(HOST_ID, ApiConstants.USERNAME);
        verify(hostDetailsDao, times(1)).findDetail(HOST_ID, ApiConstants.PASSWORD);
        verify(hostDao, times(1)).findById(HOST_ID);
    }

    @Test
    public void doUpdateHostPassword_usesHostPrivateIpInCommand() {
        when(agentManager.isAgentAttached(HOST_ID)).thenReturn(true);
        DetailVO usernameDetail = mock(DetailVO.class);
        when(usernameDetail.getValue()).thenReturn(USERNAME);
        when(hostDetailsDao.findDetail(HOST_ID, ApiConstants.USERNAME)).thenReturn(usernameDetail);
        DetailVO passwordDetail = mock(DetailVO.class);
        when(passwordDetail.getValue()).thenReturn(PASSWORD);
        when(hostDetailsDao.findDetail(HOST_ID, ApiConstants.PASSWORD)).thenReturn(passwordDetail);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        Answer answer = mock(Answer.class);
        when(answer.getResult()).thenReturn(true);
        when(answer.getDetails()).thenReturn("ok");

        // Capture the UpdateHostPasswordCommand passed to easySend and
        // verify the host private IP made it onto the command.
        org.mockito.ArgumentCaptor<UpdateHostPasswordCommand> captor =
                org.mockito.ArgumentCaptor.forClass(UpdateHostPasswordCommand.class);
        when(agentManager.easySend(eq(HOST_ID), captor.capture())).thenReturn(answer);

        service.doUpdateHostPassword(HOST_ID);

        UpdateHostPasswordCommand sent = captor.getValue();
        assertEquals(USERNAME, sent.getUsername());
        assertEquals(PASSWORD, sent.getNewPassword());
        assertEquals(HOST_IP, sent.getHostIp());
    }
}
