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

import com.cloud.utils.Pair;
import com.cloud.vm.VirtualMachine;

/**
 * Read-only lookups that resolve a user/system VM's console-proxy endpoint
 * and short-lived browser console authorisation token.
 *
 * <p>This slice owns the four {@code ManagementService} entry points the
 * browser console flow exercises: discovering the console proxy URL/address
 * assigned to an instance, registering a one-shot session UUID with the
 * console proxy agent so the websocket handshake will accept the user, and
 * fetching the hypervisor-side VNC port for a running instance. Every
 * operation here is a thin coordination between the
 * {@link com.cloud.consoleproxy.ConsoleProxyManager} assignment lookup, the
 * {@link com.cloud.vm.dao.VMInstanceDao} record fetch and either an
 * {@link com.cloud.agent.AgentManager} call to the console-proxy agent or to
 * the hypervisor host. The actual proxy lifecycle (start/stop/reboot/destroy
 * helpers on the god class) and the broader
 * {@link org.apache.cloudstack.consoleproxy.ConsoleAccessManager} layer that
 * builds the signed endpoint stay where they are; this slice covers only the
 * lookups that {@code ConsoleAccessManagerImpl} and {@code ConsoleProxyServlet}
 * dispatch through the management service.
 *
 * <p>Extracted from {@link ManagementServerImpl} as part of the Phase 4
 * Spring-component decomposition. The four public {@code ManagementService}
 * entry points covered here remain on the god class as one-line delegating
 * wrappers so callers still binding the {@code ManagementService} interface
 * continue to resolve their method calls there.
 */
public interface ConsoleAccessService {

    /**
     * Return the public URL root the browser console iframe should point at
     * for the user VM identified by {@code vmId}, or {@code null} if the
     * instance does not exist or no console proxy has been assigned. The
     * URL root is the {@code proxyImageUrl} reported by the
     * {@link com.cloud.consoleproxy.ConsoleProxyManager} when it picks a
     * proxy for the instance's data centre.
     */
    String getConsoleAccessUrlRoot(long vmId);

    /**
     * Authorise the supplied {@code sessionUuid} on the console proxy agent
     * that owns the VM identified by {@code vmId}. Returns a
     * {@code (success, details)} pair: success is {@code true} only when the
     * console-proxy agent acknowledged the
     * {@link com.cloud.agent.api.proxy.AllowConsoleAccessCommand}. The
     * negative cases — unknown VM, no proxy assigned, proxy agent host not
     * found, agent unreachable, agent-side failure — all return
     * {@code false} with a human-readable explanation in the second slot;
     * the method itself never throws on the failure paths so the websocket
     * handshake gets a clean error message rather than a stack trace.
     */
    Pair<Boolean, String> setConsoleAccessForVm(long vmId, String sessionUuid);

    /**
     * Return the dotted-quad / DNS address the websocket leg of the browser
     * console should connect to for the VM identified by {@code vmId}, or
     * {@code null} if the VM is unknown or no console proxy is currently
     * assigned to it. This is the {@code proxyAddress} reported by the
     * {@link com.cloud.consoleproxy.ConsoleProxyManager} for the data
     * centre the instance lives in.
     */
    String getConsoleAccessAddress(long vmId);

    /**
     * Ask the hypervisor host that currently owns {@code vm} for the
     * libvirt-style VNC endpoint, returning {@code (address, port)}. If the
     * VM is mid-migration ({@code State.Migrating}) and still has a recorded
     * last host, that last host is queried instead so the user keeps a
     * working console during migration. Returns {@code (null, -1)} when the
     * VM has no host id, when the hypervisor agent does not answer
     * positively, or when no answer comes back at all — callers treat the
     * sentinel port as "console currently unavailable" rather than as an
     * error.
     */
    Pair<String, Integer> getVncPort(VirtualMachine vm);
}
