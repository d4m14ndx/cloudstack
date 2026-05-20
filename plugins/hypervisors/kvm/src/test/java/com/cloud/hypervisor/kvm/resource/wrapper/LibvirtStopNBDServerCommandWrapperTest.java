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

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.cloudstack.backup.StopNBDServerCommand;
import org.junit.Test;

import com.cloud.agent.api.Answer;

public class LibvirtStopNBDServerCommandWrapperTest {
    private static final Path NBD_DIR = Path.of("/tmp/imagetransfer");

    @Test
    public void testExecuteStopsSanitizedUnitName() {
        RecordingStopWrapper wrapper = new RecordingStopWrapper();

        Answer answer = wrapper.execute(new StopNBDServerCommand("transfer-1", "download"), null);

        assertTrue(answer.getResult());
        assertEquals("qemu-nbd-imagetransfer-transfer-1", wrapper.unitName);
        assertEquals("/tmp/imagetransfer/transfer-1.sock", wrapper.socketPath);
    }

    @Test
    public void testExecuteRejectsUnsafeTransferId() {
        RecordingStopWrapper wrapper = new RecordingStopWrapper();

        Answer answer = wrapper.execute(new StopNBDServerCommand("transfer/1", "download"), null);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails().contains("transferId"));
    }

    @Test
    public void testExecuteRemovesSocketAndManagedKeyFile() throws Exception {
        String transferId = "stop-" + System.nanoTime();
        Files.createDirectories(NBD_DIR);
        Path socket = NBD_DIR.resolve(transferId + ".sock");
        Path keyFile = NBD_DIR.resolve(transferId + ".key");
        Files.writeString(socket, "socket");
        Files.write(keyFile, "plain-secret".getBytes(StandardCharsets.UTF_8));
        RecordingStopWrapper wrapper = new RecordingStopWrapper(false);

        try {
            Answer answer = wrapper.execute(new StopNBDServerCommand(transferId, "download"), null);

            assertTrue(answer.getResult());
            assertFalse(Files.exists(socket));
            assertFalse(Files.exists(keyFile));
        } finally {
            Files.deleteIfExists(socket);
            Files.deleteIfExists(keyFile);
        }
    }

    @Test
    public void testUnsafeTransferIdCannotEscapeCleanupDirectory() throws Exception {
        Path outsideKeyFile = NBD_DIR.resolve("..").resolve("outside-nbd-key-" + System.nanoTime() + ".key").normalize();
        Files.writeString(outsideKeyFile, "outside");
        RecordingStopWrapper wrapper = new RecordingStopWrapper(false);

        try {
            Answer answer = wrapper.execute(new StopNBDServerCommand("../" + outsideKeyFile.getFileName(), "download"), null);

            assertFalse(answer.getResult());
            assertTrue(Files.exists(outsideKeyFile));
        } finally {
            Files.deleteIfExists(outsideKeyFile);
        }
    }

    private static class RecordingStopWrapper extends LibvirtStopNBDServerCommandWrapper {
        private String unitName;
        private String socketPath;
        private final boolean recordOnly;

        RecordingStopWrapper() {
            this(true);
        }

        RecordingStopWrapper(boolean recordOnly) {
            this.recordOnly = recordOnly;
        }

        @Override
        protected boolean stopNbdService(String unitName) {
            this.unitName = unitName;
            return true;
        }

        @Override
        protected void deleteSocketFile(String socketPath) {
            this.socketPath = socketPath;
            if (!recordOnly) {
                super.deleteSocketFile(socketPath);
            }
        }
    }
}
