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

import com.cloud.host.HostVO;
import com.cloud.utils.Ternary;

/**
 * SSH-credential resolution, agent-restart-over-SSH, and agent-side host
 * password updates — the per-host slice of credential / password handling
 * that lives next to {@link ResourceManagerImpl}.
 *
 * <p>Operations covered:
 * <ul>
 *   <li>resolving SSH credentials (username, password, private key) for a
 *       host from its persisted details plus the global {@code ssh.privatekey}
 *       configuration ({@link #getHostCredentials(HostVO)})</li>
 *   <li>SSH-ing into a host and restarting the {@code cloudstack-agent}
 *       service — used by maintenance recovery when the agent is not
 *       connected and {@code kvm.ssh.to.agent} is enabled
 *       ({@link #connectAndRestartAgentOnHost})</li>
 *   <li>pushing an {@code UpdateHostPasswordCommand} to the host's agent
 *       so the host-side password matches the values persisted in host
 *       details ({@link #doUpdateHostPassword(long)})</li>
 * </ul>
 *
 * <p>Extracted from {@link ResourceManagerImpl} as part of the Phase 4
 * Spring-component decomposition. {@code ResourceManagerImpl} keeps
 * delegating wrappers so the existing spy / mock-based tests (which call
 * {@code resourceManager.getHostCredentials(...)} and
 * {@code resourceManager.connectAndRestartAgentOnHost(...)}) continue to
 * work unchanged, and so the public
 * {@link com.cloud.resource.ResourceService#updateHostPassword} /
 * {@link com.cloud.resource.ResourceService#updateClusterPassword}
 * contracts remain on {@code ResourceManagerImpl} where they can
 * propagate the resource event across management-server peers.
 */
public interface HostAgentSshService {

    /**
     * Resolve SSH credentials for {@code host} from its persisted details
     * (username, password) plus the global {@code ssh.privatekey}
     * configuration. The returned triple is {@code (username, password,
     * privateKey)}.
     *
     * @throws com.cloud.utils.exception.CloudRuntimeException when the
     *         username is missing or when both password and private key
     *         are missing.
     */
    Ternary<String, String, String> getHostCredentials(HostVO host);

    /**
     * SSH into {@code host}'s private IP (using the host agent's SSH
     * port) and run {@code service cloudstack-agent restart}. Assumes
     * the caller has already verified that {@code kvm.ssh.to.agent} is
     * enabled and that the agent is not currently connected.
     *
     * @throws com.cloud.utils.exception.CloudRuntimeException when the
     *         SSH connection cannot be established or the restart
     *         command fails.
     */
    void connectAndRestartAgentOnHost(HostVO host, String username, String password, String privateKey);

    /**
     * Push an {@code UpdateHostPasswordCommand} to the host's agent so
     * the host-side credentials match the persisted host-detail values.
     * Returns {@code false} when no agent is attached for the host, and
     * otherwise returns the agent answer's result.
     */
    boolean doUpdateHostPassword(long hostId);
}
