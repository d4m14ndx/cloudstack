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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import org.apache.cloudstack.backup.StartNBDServerCommand;
import org.junit.Test;

import com.cloud.agent.api.Answer;

public class LibvirtStartNBDServerCommandWrapperTest {
    private static final Path NBD_DIR = Path.of("/tmp/imagetransfer");
    private static final Set<PosixFilePermission> OWNER_READ_WRITE = PosixFilePermissions.fromString("rw-------");

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

    @Test
    public void testEncryptedCommandUsesManagedKeyFileAndClearsPassphrase() throws Exception {
        String transferId = uniqueName("transfer");
        String socket = uniqueName("socket");
        Path expectedKeyFile = NBD_DIR.resolve(transferId + ".key");
        byte[] passphrase = "plain-secret".getBytes(StandardCharsets.UTF_8);
        RecordingStartWrapper wrapper = new RecordingStartWrapper();
        StartNBDServerCommand command = new StartNBDServerCommand(transferId, "export1",
                "/var/lib/images/disk.qcow2", socket, "download", null, passphrase);
        Path actualKeyFile = null;

        try {
            Answer answer = wrapper.execute(command, null);

            assertTrue(answer.getResult());
            actualKeyFile = keyFilePathFrom(wrapper.startCommand);
            assertEquals(expectedKeyFile, actualKeyFile);
            assertTrue(wrapper.startCommand.contains("--object"));
            assertTrue(wrapper.startCommand.contains("--image-opts"));
            assertTrue(wrapper.startCommand.get(wrapper.startCommand.size() - 1).contains("encrypt.key-secret=sec0"));
            assertFalse(String.join(" ", wrapper.startCommand).contains("plain-secret"));
            assertTrue(Files.exists(actualKeyFile));
            assertPosixOwnerReadWriteIfSupported(actualKeyFile);
            assertTrue("passphrase bytes were not zeroed", allZeroes(passphrase));
            assertNull(command.getPassphrase());
        } finally {
            deleteIfExists(actualKeyFile);
            deleteIfExists(expectedKeyFile);
        }
    }

    @Test
    public void testEncryptedKeyFileRemovedOnLaunchFailure() throws Exception {
        String transferId = uniqueName("launch-fail");
        String socket = uniqueName("socket");
        RecordingStartWrapper wrapper = new RecordingStartWrapper();
        wrapper.startCommandResult = "launch failed";
        StartNBDServerCommand command = new StartNBDServerCommand(transferId, "export1",
                "/var/lib/images/disk.qcow2", socket, "download", null,
                "plain-secret".getBytes(StandardCharsets.UTF_8));
        Path keyFile = null;

        try {
            Answer answer = wrapper.execute(command, null);

            assertFalse(answer.getResult());
            keyFile = keyFilePathFrom(wrapper.startCommand);
            assertFalse(Files.exists(keyFile));
        } finally {
            deleteIfExists(keyFile);
            deleteIfExists(NBD_DIR.resolve(transferId + ".key"));
        }
    }

    @Test
    public void testEncryptedKeyFileRemovedOnServiceWaitFailure() throws Exception {
        String transferId = uniqueName("wait-fail");
        String socket = uniqueName("socket");
        RecordingStartWrapper wrapper = new RecordingStartWrapper();
        wrapper.waitResult = false;
        StartNBDServerCommand command = new StartNBDServerCommand(transferId, "export1",
                "/var/lib/images/disk.qcow2", socket, "download", null,
                "plain-secret".getBytes(StandardCharsets.UTF_8));
        Path keyFile = null;

        try {
            Answer answer = wrapper.execute(command, null);

            assertFalse(answer.getResult());
            keyFile = keyFilePathFrom(wrapper.startCommand);
            assertFalse(Files.exists(keyFile));
        } finally {
            deleteIfExists(keyFile);
            deleteIfExists(NBD_DIR.resolve(transferId + ".key"));
        }
    }

    private static String uniqueName(String prefix) {
        return prefix + "-" + System.nanoTime();
    }

    private static Path keyFilePathFrom(List<String> args) {
        assertNotNull(args);
        String prefix = "secret,id=sec0,file=";
        for (String arg : args) {
            if (arg.startsWith(prefix)) {
                return Path.of(arg.substring(prefix.length()));
            }
        }
        throw new AssertionError("No qemu secret key file argument found: " + args);
    }

    private static void assertPosixOwnerReadWriteIfSupported(Path path) throws IOException {
        try {
            assertEquals(OWNER_READ_WRITE, Files.getPosixFilePermissions(path));
        } catch (UnsupportedOperationException e) {
            // POSIX permissions are not available on every filesystem used by developers.
        }
    }

    private static boolean allZeroes(byte[] bytes) {
        byte[] zeroes = new byte[bytes.length];
        return Arrays.equals(zeroes, bytes);
    }

    private static void deleteIfExists(Path path) throws IOException {
        if (path != null) {
            Files.deleteIfExists(path);
        }
    }

    private static class RecordingStartWrapper extends LibvirtStartNBDServerCommandWrapper {
        private String unitName;
        private List<String> startCommand = new ArrayList<>();
        private String startCommandResult;
        private boolean waitResult = true;

        @Override
        protected boolean isNbdServiceActive(String unitName) {
            this.unitName = unitName;
            return false;
        }

        @Override
        protected boolean ensureSocketDirectory() {
            try {
                Files.createDirectories(NBD_DIR);
                return true;
            } catch (IOException e) {
                return false;
            }
        }

        @Override
        protected String runCommand(List<String> args) {
            startCommand = args;
            return startCommandResult;
        }

        @Override
        protected boolean waitForNbdService(String unitName) {
            return waitResult;
        }

        @Override
        protected void stopAndResetNbdService(String unitName) {
            // Keep the captured start command available for key file assertions.
        }
    }
}
