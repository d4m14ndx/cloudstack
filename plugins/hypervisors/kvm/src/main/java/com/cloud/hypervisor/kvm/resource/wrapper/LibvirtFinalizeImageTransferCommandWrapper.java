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

import org.apache.cloudstack.backup.FinalizeImageTransferCommand;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.ImageServerControlSocket;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.Script;

@ResourceWrapper(handles = FinalizeImageTransferCommand.class)
public class LibvirtFinalizeImageTransferCommandWrapper extends CommandWrapper<FinalizeImageTransferCommand, Answer, LibvirtComputingResource> {
    protected Logger logger = LogManager.getLogger(getClass());

    @Override
    public Answer execute(FinalizeImageTransferCommand cmd, LibvirtComputingResource resource) {
        final String transferId = cmd.getTransferId();
        if (StringUtils.isBlank(transferId)) {
            return new Answer(cmd, false, "transferId is empty.");
        }
        final String socketPath = resource.getImageServerSocketPath();
        if (StringUtils.isBlank(socketPath)) {
            return new Answer(cmd, false, "image server control socket path is empty.");
        }

        int activeTransfers = unregisterTransfer(socketPath, transferId);
        if (activeTransfers < 0) {
            stopImageServer(LibvirtComputingResource.IMAGE_SERVER_DEFAULT_PORT, resource);
            return new Answer(cmd, true, "Image transfer finalized (server unreachable, forced stop).");
        }
        if (activeTransfers == 0) {
            stopImageServer(LibvirtComputingResource.IMAGE_SERVER_DEFAULT_PORT, resource);
        }
        return new Answer(cmd, true, "Image transfer finalized.");
    }

    protected int unregisterTransfer(String socketPath, String transferId) {
        return ImageServerControlSocket.unregisterTransfer(socketPath, transferId);
    }

    protected boolean stopImageServer(int imageServerPort, LibvirtComputingResource resource) {
        String unitName = LibvirtComputingResource.IMAGE_SERVER_SYSTEMD_UNIT_NAME;
        runCommand("systemctl", "stop", unitName);
        runCommand("systemctl", "reset-failed", unitName);
        removeFirewallRule(imageServerPort);
        return true;
    }

    protected void removeFirewallRule(int port) {
        runCommand("iptables", "-D", "INPUT", "-p", "tcp", "-m", "state", "--state", "NEW", "-m", "tcp",
                "--dport", String.valueOf(port), "-j", "ACCEPT");
    }

    protected String runCommand(String... args) {
        Script script = new Script(args[0], logger);
        for (int index = 1; index < args.length; index++) {
            script.add(args[index]);
        }
        return script.execute();
    }
}
