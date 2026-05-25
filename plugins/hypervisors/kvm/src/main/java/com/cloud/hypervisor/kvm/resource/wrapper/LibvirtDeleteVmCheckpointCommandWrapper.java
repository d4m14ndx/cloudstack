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

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.Script;
import org.apache.cloudstack.backup.DeleteVmCheckpointCommand;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgException;

import java.util.Arrays;
import java.util.Map;

@ResourceWrapper(handles = DeleteVmCheckpointCommand.class)
public class LibvirtDeleteVmCheckpointCommandWrapper extends CommandWrapper<DeleteVmCheckpointCommand, Answer, LibvirtComputingResource> {

    @Override
    public Answer execute(DeleteVmCheckpointCommand command, LibvirtComputingResource resource) {
        try {
            if (command.isStoppedVM()) {
                removeBitmapsForStoppedVm(command, resource);
            } else {
                deleteRunningVmCheckpointMetadata(command, resource);
            }
            return new Answer(command, true, null);
        } catch (Exception e) {
            logger.error("Failed to delete checkpoint [{}] on VM [{}].", command.getCheckpointId(), command.getVmName(), e);
            return new Answer(command, false, e.getMessage());
        } finally {
            clearPassphrases(command.getDiskPathPassphraseMap());
        }
    }

    protected void deleteRunningVmCheckpointMetadata(DeleteVmCheckpointCommand command, LibvirtComputingResource resource) {
        Script script = new Script("virsh", resource.getCmdsTimeout(), logger);
        script.add("checkpoint-delete");
        script.add("--domain");
        script.add(command.getVmName());
        script.add("--checkpointname");
        script.add(command.getCheckpointId());
        script.add("--metadata");
        String result = script.execute();
        if (result != null) {
            throw new RuntimeException(result);
        }
    }

    protected void removeBitmapsForStoppedVm(DeleteVmCheckpointCommand command, LibvirtComputingResource resource) throws Exception {
        LibvirtStartBackupCommandWrapper bitmapWrapper = new LibvirtStartBackupCommandWrapper();
        QemuImg qemuImg = new QemuImg(resource.getCmdsTimeout());
        for (String diskPath : command.getDiskPathUuidMap().keySet()) {
            try {
                bitmapWrapper.runBitmapOperation(qemuImg, QemuImg.BitmapOperation.Remove, diskPath, command.getCheckpointId(),
                        getPassphrase(command.getDiskPathPassphraseMap(), diskPath));
            } catch (QemuImgException e) {
                if (!isMissingBitmap(e)) {
                    throw e;
                }
                logger.warn("Could not delete dirty bitmap [{}] from disk [{}] because it was not found.", command.getCheckpointId(), diskPath);
            }
        }
    }

    private boolean isMissingBitmap(QemuImgException e) {
        return e.getMessage() != null && (e.getMessage().contains("Dirty bitmap") || e.getMessage().contains("not found"));
    }

    private byte[] getPassphrase(Map<String, byte[]> diskPathPassphraseMap, String diskPath) {
        if (diskPathPassphraseMap == null) {
            return null;
        }
        return diskPathPassphraseMap.get(diskPath);
    }

    private void clearPassphrases(Map<String, byte[]> diskPathPassphraseMap) {
        if (diskPathPassphraseMap == null) {
            return;
        }
        for (byte[] passphrase : diskPathPassphraseMap.values()) {
            if (passphrase != null) {
                Arrays.fill(passphrase, (byte) 0);
            }
        }
    }
}
