//
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
//
package com.cloud.hypervisor.kvm.resource.wrapper;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.apache.cloudstack.backup.StartNBDServerCommand;
import org.junit.Test;

import com.cloud.agent.api.Answer;

public class LibvirtStartNBDServerCommandWrapperTest {
    @Test
    public void testExecuteStartsPerTransferService() {
        RecordingStartWrapper wrapper = new RecordingStartWrapper();
        StartNBDServerCommand command = new StartNBDServerCommand("transfer-1", "export1",
                "/var/lib/images/disk.qcow2", "socket-1", "download", null, null);

        Answer answer = wrapper.execute(command, null);

        assertTrue(answer.getResult());
        assertEquals("qemu-nbd-imagetransfer-transfer-1", wrapper.unitName);
        assertTrue(wrapper.startCommand.contains("--unit=qemu-nbd-imagetransfer-transfer-1"));
        assertTrue(wrapper.startCommand.contains("/tmp/imagetransfer/socket-1.sock"));
    }

    @Test
    public void testBuildQemuNbdCommandUsesSanitizedUnitAndSocketPath() throws Exception {
        LibvirtStartNBDServerCommandWrapper wrapper = new LibvirtStartNBDServerCommandWrapper();
        StartNBDServerCommand command = new StartNBDServerCommand("transfer-1", "export1",
                "/var/lib/images/disk.qcow2", "socket-1", "download", null, null);

        List<String> args = wrapper.buildQemuNbdStartCommand(command, "qemu-nbd-imagetransfer-transfer-1",
                "/tmp/imagetransfer/socket-1.sock");

        assertEquals("systemd-run", args.get(0));
        assertTrue(args.contains("--unit=qemu-nbd-imagetransfer-transfer-1"));
        assertTrue(args.contains("--export-name"));
        assertTrue(args.contains("export1"));
        assertTrue(args.contains("--socket"));
        assertTrue(args.contains("/tmp/imagetransfer/socket-1.sock"));
        assertTrue(args.contains("--read-only"));
        assertEquals("/var/lib/images/disk.qcow2", args.get(args.size() - 1));
    }

    @Test
    public void testExecuteRejectsUnsafeTransferId() {
        LibvirtStartNBDServerCommandWrapper wrapper = new LibvirtStartNBDServerCommandWrapper();
        StartNBDServerCommand command = new StartNBDServerCommand("transfer;1", "export1",
                "/var/lib/images/disk.qcow2", "socket-1", "download", null, null);

        Answer answer = wrapper.execute(command, null);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails().contains("transferId"));
    }

    private static class RecordingStartWrapper extends LibvirtStartNBDServerCommandWrapper {
        private String unitName;
        private List<String> startCommand = new ArrayList<>();

        @Override
        protected boolean isNbdServiceActive(String unitName) {
            this.unitName = unitName;
            return false;
        }

        @Override
        protected boolean ensureSocketDirectory() {
            return true;
        }

        @Override
        protected String runCommand(List<String> args) {
            startCommand = args;
            return null;
        }

        @Override
        protected boolean waitForNbdService(String unitName) {
            return true;
        }
    }
}
