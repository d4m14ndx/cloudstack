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
package com.cloud.hypervisor.kvm.resource.wrapper;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.backup.CreateImageTransferAnswer;
import org.apache.cloudstack.backup.CreateImageTransferCommand;
import org.apache.cloudstack.storage.resource.IpTablesHelper;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.ImageServerControlSocket;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.Script;

@ResourceWrapper(handles = CreateImageTransferCommand.class)
public class LibvirtCreateImageTransferCommandWrapper extends CommandWrapper<CreateImageTransferCommand, Answer, LibvirtComputingResource> {
    protected Logger logger = LogManager.getLogger(getClass());

    private static final String IMAGE_SERVER_TLS_CERT_FILE = "/etc/cloudstack/agent/cloud.crt";
    private static final String IMAGE_SERVER_TLS_KEY_FILE = "/etc/cloudstack/agent/cloud.key";

    @Override
    public Answer execute(CreateImageTransferCommand cmd, LibvirtComputingResource resource) {
        final String transferId = cmd.getTransferId();
        if (StringUtils.isBlank(transferId)) {
            return new CreateImageTransferAnswer(cmd, false, "transferId is empty.");
        }
        if (StringUtils.isBlank(cmd.getToken())) {
            return new CreateImageTransferAnswer(cmd, false, "transfer token is empty.");
        }

        final Map<String, Object> payload = buildPayload(cmd);
        if (payload == null) {
            return new CreateImageTransferAnswer(cmd, false, "Invalid image transfer payload.");
        }

        final int imageServerPort = LibvirtComputingResource.IMAGE_SERVER_DEFAULT_PORT;
        final String listenAddress = getListenAddress(resource);
        final String socketPath = resource.getImageServerSocketPath();
        if (StringUtils.isBlank(socketPath)) {
            return new CreateImageTransferAnswer(cmd, false, "image server control socket path is empty.");
        }
        if (!startImageServerIfNeeded(socketPath, imageServerPort, listenAddress, resource)) {
            return new CreateImageTransferAnswer(cmd, false, "Failed to start image server.");
        }
        if (!registerTransfer(socketPath, transferId, payload)) {
            return new CreateImageTransferAnswer(cmd, false, "Failed to register transfer with image server.");
        }

        final String transferScheme = resource.isImageServerTlsEnabled() ? "https" : "http";
        final String transferUrl = String.format("%s://%s:%d/images/%s", transferScheme, listenAddress, imageServerPort, transferId);
        return new CreateImageTransferAnswer(cmd, true, "Image transfer prepared on KVM host.", transferId, transferUrl);
    }

    protected Map<String, Object> buildPayload(CreateImageTransferCommand cmd) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("backend", cmd.getBackend().name());
        payload.put("idle_timeout_seconds", cmd.getIdleTimeoutSeconds());
        payload.put("token", cmd.getToken());

        if (cmd.getBackend() == CreateImageTransferCommand.Backend.file) {
            if (StringUtils.isBlank(cmd.getFile())) {
                return null;
            }
            payload.put("file", cmd.getFile());
            return payload;
        }

        if (StringUtils.isAnyBlank(cmd.getSocket(), cmd.getExportName())) {
            return null;
        }
        String safeSocket = LibvirtStartNBDServerCommandWrapper.validateSafeName(cmd.getSocket(), "socket");
        if (safeSocket == null) {
            return null;
        }
        payload.put("socket", LibvirtStartNBDServerCommandWrapper.socketPathFor(safeSocket));
        payload.put("export", cmd.getExportName());
        if (StringUtils.isNotBlank(cmd.getCheckpointId())) {
            payload.put("export_bitmap", cmd.getCheckpointId());
        }
        return payload;
    }

    protected boolean startImageServerIfNeeded(String socketPath, int imageServerPort, String listenAddress, LibvirtComputingResource resource) {
        String unitName = LibvirtComputingResource.IMAGE_SERVER_SYSTEMD_UNIT_NAME;
        if (runCommand("systemctl", "is-active", "--quiet", unitName) == null && ImageServerControlSocket.isReady(socketPath)) {
            openFirewallRule(imageServerPort);
            return true;
        }

        resetService(unitName);
        String result = runCommand(buildImageServerStartCommand(imageServerPort, listenAddress, resource));
        if (result != null) {
            logger.error("Failed to start image server: {}", result);
            return false;
        }

        int maxAttempts = 10;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            if (ImageServerControlSocket.isReady(socketPath)) {
                openFirewallRule(imageServerPort);
                return true;
            }
            sleep(1000);
        }
        return false;
    }

    protected List<String> buildImageServerStartCommand(int imageServerPort, String listenAddress, LibvirtComputingResource resource) {
        String packageDir = resource.getImageServerPath();
        String parentDir = new File(packageDir).getParent();
        String moduleName = new File(packageDir).getName();
        List<String> args = new ArrayList<>();
        args.add("systemd-run");
        args.add("--unit=" + LibvirtComputingResource.IMAGE_SERVER_SYSTEMD_UNIT_NAME);
        args.add("--property=Restart=no");
        args.add("--property=WorkingDirectory=" + parentDir);
        args.add("/usr/bin/python3");
        args.add("-m");
        args.add(moduleName);
        args.add("--listen");
        args.add(listenAddress);
        args.add("--port");
        args.add(String.valueOf(imageServerPort));
        if (resource.isImageServerTlsEnabled()) {
            args.add("--tls-enabled");
            args.add("--tls-cert-file");
            args.add(IMAGE_SERVER_TLS_CERT_FILE);
            args.add("--tls-key-file");
            args.add(IMAGE_SERVER_TLS_KEY_FILE);
        }
        return args;
    }

    protected boolean registerTransfer(String socketPath, String transferId, Map<String, Object> payload) {
        return ImageServerControlSocket.registerTransfer(socketPath, transferId, payload);
    }

    protected void openFirewallRule(int imageServerPort) {
        String rule = String.format("-p tcp -m state --state NEW -m tcp --dport %d -j ACCEPT", imageServerPort);
        IpTablesHelper.addConditionally(IpTablesHelper.INPUT_CHAIN, true, rule,
                String.format("Error in opening up image server port %d", imageServerPort));
    }

    protected String getListenAddress(LibvirtComputingResource resource) {
        String listenAddress = resource.getImageServerListenAddress();
        return StringUtils.isBlank(listenAddress) ? resource.getPrivateIp() : listenAddress;
    }

    protected void resetService(String unitName) {
        runCommand("systemctl", "reset-failed", unitName);
    }

    protected String runCommand(String... args) {
        return runCommand(List.of(args));
    }

    protected String runCommand(List<String> args) {
        Script script = new Script(args.get(0), logger);
        for (int index = 1; index < args.size(); index++) {
            script.add(args.get(index));
        }
        return script.execute();
    }

    protected void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
