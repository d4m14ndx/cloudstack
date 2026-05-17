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

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

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

/**
 * SSH-credential resolution, agent-restart-over-SSH, and agent-side host
 * password updates extracted from {@link ResourceManagerImpl}.
 *
 * @see HostAgentSshService
 */
@Component
public class HostAgentSshServiceImpl implements HostAgentSshService {
    private static final Logger LOG = LogManager.getLogger(HostAgentSshServiceImpl.class);

    @Inject
    protected HostDao hostDao;
    @Inject
    protected HostDetailsDao hostDetailsDao;
    @Inject
    protected ConfigurationDao configurationDao;
    @Inject
    protected AgentManager agentManager;

    @Override
    public Ternary<String, String, String> getHostCredentials(HostVO host) {
        hostDao.loadDetails(host);
        final String password = host.getDetail("password");
        final String username = host.getDetail("username");
        final String privateKey = configurationDao.getValue("ssh.privatekey");
        if ((password == null && privateKey == null) || username == null) {
            throw new CloudRuntimeException("SSH to agent is enabled, but username and password or private key are not found");
        }
        return new Ternary<>(username, password, privateKey);
    }

    @Override
    public void connectAndRestartAgentOnHost(HostVO host, String username, String password, String privateKey) {
        final com.trilead.ssh2.Connection connection = SSHCmdHelper.acquireAuthorizedConnection(
                host.getPrivateIpAddress(), agentManager.getHostSshPort(host), username, password, privateKey);
        if (connection == null) {
            throw new CloudRuntimeException(String.format("SSH to agent is enabled, but failed to connect to %s via IP address [%s].", host, host.getPrivateIpAddress()));
        }
        try {
            SSHCmdHelper.SSHCmdResult result = SSHCmdHelper.sshExecuteCmdOneShot(
                    connection, "service cloudstack-agent restart");
            if (result.getReturnCode() != 0) {
                throw new CloudRuntimeException(String.format("Could not restart agent on %s due to: %s", host, result.getStdErr()));
            }
            LOG.debug("cloudstack-agent restart result: {}", result);
        } catch (final SshException e) {
            throw new CloudRuntimeException("SSH to agent is enabled, but agent restart failed", e);
        }
    }

    @Override
    public boolean doUpdateHostPassword(long hostId) {
        if (!agentManager.isAgentAttached(hostId)) {
            return false;
        }

        DetailVO nv = hostDetailsDao.findDetail(hostId, ApiConstants.USERNAME);
        final String username = nv.getValue();
        nv = hostDetailsDao.findDetail(hostId, ApiConstants.PASSWORD);
        final String password = nv.getValue();

        final HostVO host = hostDao.findById(hostId);
        final String hostIpAddress = host.getPrivateIpAddress();

        final UpdateHostPasswordCommand cmd = new UpdateHostPasswordCommand(username, password, hostIpAddress);
        final Answer answer = agentManager.easySend(hostId, cmd);

        LOG.info("Result returned from update host password ==> " + answer.getDetails());
        return answer.getResult();
    }
}
