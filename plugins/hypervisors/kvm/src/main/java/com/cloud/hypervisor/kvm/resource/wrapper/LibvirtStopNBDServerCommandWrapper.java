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
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.cloudstack.backup.StopNBDServerCommand;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.Script;

@ResourceWrapper(handles = StopNBDServerCommand.class)
public class LibvirtStopNBDServerCommandWrapper extends CommandWrapper<StopNBDServerCommand, Answer, LibvirtComputingResource> {
    protected Logger logger = LogManager.getLogger(getClass());

    @Override
    public Answer execute(StopNBDServerCommand cmd, LibvirtComputingResource resource) {
        if (cmd == null || StringUtils.isBlank(cmd.getTransferId())) {
            return new Answer(cmd, false, "transferId is empty.");
        }
        String error = LibvirtStartNBDServerCommandWrapper.unsafeNameError(cmd.getTransferId(), "transferId");
        if (error != null) {
            return new Answer(cmd, false, error);
        }

        String unitName = LibvirtStartNBDServerCommandWrapper.unitNameFor(cmd.getTransferId());
        if (!stopNbdService(unitName)) {
            return new Answer(cmd, false, "Failed to stop qemu-nbd service.");
        }
        deleteSocketFile(LibvirtStartNBDServerCommandWrapper.socketPathFor(cmd.getTransferId()));
        deleteManagedKeyFile(LibvirtStartNBDServerCommandWrapper.keyFilePathFor(cmd.getTransferId()));
        return new Answer(cmd, true, "Image transfer finalized.");
    }

    protected boolean stopNbdService(String unitName) {
        runCommand("systemctl", "stop", unitName);
        runCommand("systemctl", "reset-failed", unitName);
        return true;
    }

    protected void deleteSocketFile(String socketPath) {
        File socketFile = new File(socketPath);
        if (socketFile.exists() && !socketFile.delete()) {
            logger.warn("Failed to delete qemu-nbd socket file [{}].", socketPath);
        }
    }

    protected void deleteManagedKeyFile(Path keyFilePath) {
        try {
            Files.deleteIfExists(keyFilePath);
        } catch (IOException e) {
            logger.warn("Failed to delete qemu-nbd key file [{}].", keyFilePath, e);
        }
    }

    protected String runCommand(String... args) {
        Script script = new Script(args[0], logger);
        for (int index = 1; index < args.length; index++) {
            script.add(args[index]);
        }
        return script.execute();
    }
}
