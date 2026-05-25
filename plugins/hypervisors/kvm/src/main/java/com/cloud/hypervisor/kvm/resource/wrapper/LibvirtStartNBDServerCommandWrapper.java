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
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.cloudstack.backup.StartNBDServerAnswer;
import org.apache.cloudstack.backup.StartNBDServerCommand;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.json.JSONArray;
import org.json.JSONObject;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;
import com.cloud.resource.CommandWrapper;
import com.cloud.resource.ResourceWrapper;
import com.cloud.utils.script.Script;

@ResourceWrapper(handles = StartNBDServerCommand.class)
public class LibvirtStartNBDServerCommandWrapper extends CommandWrapper<StartNBDServerCommand, Answer, LibvirtComputingResource> {
    protected Logger logger = LogManager.getLogger(getClass());

    private static final String SOCKET_DIR = "/tmp/imagetransfer";
    private static final Pattern SAFE_NAME = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final Set<PosixFilePermission> KEY_FILE_PERMISSIONS = PosixFilePermissions.fromString("rw-------");

    @Override
    public Answer execute(StartNBDServerCommand cmd, LibvirtComputingResource resource) {
        String validationError = validate(cmd);
        if (validationError != null) {
            return new StartNBDServerAnswer(cmd, false, validationError);
        }

        String safeTransferId = validateSafeName(cmd.getTransferId(), "transferId");
        String safeSocket = validateSafeName(cmd.getSocket(), "socket");
        String unitName = unitNameFor(safeTransferId);
        String socketPath = socketPathFor(safeSocket);
        Path keyFilePath = keyFilePathFor(safeTransferId);

        if (isNbdServiceActive(unitName)) {
            return new StartNBDServerAnswer(cmd, false, "A qemu-nbd service is already running for the transfer.");
        }
        if (!ensureSocketDirectory()) {
            return new StartNBDServerAnswer(cmd, false, "Failed to create qemu-nbd socket directory.");
        }

        List<String> command;
        try {
            command = buildQemuNbdStartCommand(cmd, unitName, socketPath, keyFilePath);
        } catch (IOException e) {
            deleteManagedKeyFile(keyFilePath);
            logger.error("Failed to prepare qemu-nbd command", e);
            return new StartNBDServerAnswer(cmd, false, "Failed to prepare qemu-nbd command: " + e.getMessage());
        }

        String result = runCommand(command);
        if (result != null) {
            deleteManagedKeyFile(keyFilePath);
            logger.error("Failed to start qemu-nbd service [{}]: {}", unitName, result);
            return new StartNBDServerAnswer(cmd, false, "Failed to start qemu-nbd service: " + result);
        }
        if (!waitForNbdService(unitName)) {
            stopAndResetNbdService(unitName);
            deleteManagedKeyFile(keyFilePath);
            return new StartNBDServerAnswer(cmd, false, "qemu-nbd service failed to start.");
        }

        return new StartNBDServerAnswer(cmd, true, "qemu-nbd service started.", cmd.getTransferId(),
                String.format("nbd+unix:///%s?socket=%s", cmd.getExportName(), socketPath));
    }

    protected String validate(StartNBDServerCommand cmd) {
        if (cmd == null) {
            return "command is required.";
        }
        if (StringUtils.isBlank(cmd.getVolumePath())) {
            return "Volume path is required for the nbd server.";
        }
        if (StringUtils.isBlank(cmd.getExportName())) {
            return "Export name is required for the nbd server.";
        }
        if (StringUtils.isBlank(cmd.getSocket())) {
            return "Socket is required for the nbd server.";
        }
        String transferError = unsafeNameError(cmd.getTransferId(), "transferId");
        if (transferError != null) {
            return transferError;
        }
        return unsafeNameError(cmd.getSocket(), "socket");
    }

    static String validateSafeName(String value, String fieldName) {
        return unsafeNameError(value, fieldName) == null ? value : null;
    }

    static String unsafeNameError(String value, String fieldName) {
        if (StringUtils.isBlank(value) || !SAFE_NAME.matcher(value).matches()) {
            return fieldName + " contains unsafe characters.";
        }
        return null;
    }

    static String unitNameFor(String safeTransferId) {
        return "qemu-nbd-imagetransfer-" + safeTransferId;
    }

    static String socketPathFor(String safeSocket) {
        return SOCKET_DIR + "/" + safeSocket + ".sock";
    }

    static Path keyFilePathFor(String safeTransferId) {
        return Path.of(SOCKET_DIR, safeTransferId + ".key");
    }

    protected List<String> buildQemuNbdStartCommand(StartNBDServerCommand cmd, String unitName, String socketPath) throws IOException {
        String safeTransferId = validateSafeName(cmd.getTransferId(), "transferId");
        return buildQemuNbdStartCommand(cmd, unitName, socketPath, safeTransferId == null ? null : keyFilePathFor(safeTransferId));
    }

    protected List<String> buildQemuNbdStartCommand(StartNBDServerCommand cmd, String unitName, String socketPath, Path keyFilePath) throws IOException {
        List<String> args = new ArrayList<>();
        args.add("systemd-run");
        args.add("--unit=" + unitName);
        args.add("--property=Restart=no");
        args.add("qemu-nbd");

        byte[] passphrase = cmd.getPassphrase();
        String imageArg = cmd.getVolumePath();
        try {
            if (passphrase != null && passphrase.length > 0) {
                if (keyFilePath == null) {
                    throw new IOException("Safe transfer id is required for encrypted qemu-nbd exports.");
                }
                createManagedKeyFile(passphrase, keyFilePath);
                args.add("--object");
                args.add(String.format("secret,id=sec0,file=%s", keyFilePath));
                args.add("--image-opts");
                imageArg = String.format("driver=qcow2,file.driver=file,file.filename=%s,encrypt.key-secret=sec0", cmd.getVolumePath());
            }

            args.add("--export-name");
            args.add(cmd.getExportName());
            args.add("--socket");
            args.add(socketPath);
            args.add("--persistent");
            args.add("--shared=0");
            if (StringUtils.isNotBlank(cmd.getFromCheckpointId()) && isBitmapPresentOnDisk(cmd.getVolumePath(), cmd.getFromCheckpointId())) {
                args.add("-B");
                args.add(cmd.getFromCheckpointId());
            }
            if ("download".equalsIgnoreCase(cmd.getDirection())) {
                args.add("--read-only");
            }
            args.add(imageArg);
            return args;
        } catch (IOException | RuntimeException e) {
            if (passphrase != null && passphrase.length > 0) {
                deleteManagedKeyFile(keyFilePath);
            }
            throw e;
        } finally {
            cmd.clearPassphrase();
        }
    }

    protected boolean isNbdServiceActive(String unitName) {
        return runCommand("systemctl", "is-active", "--quiet", unitName) == null;
    }

    protected boolean waitForNbdService(String unitName) {
        int maxAttempts = 4;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            sleep(5000);
            if (isNbdServiceActive(unitName)) {
                return true;
            }
        }
        return false;
    }

    protected boolean ensureSocketDirectory() {
        File dir = new File(SOCKET_DIR);
        return dir.exists() || dir.mkdirs();
    }

    protected Path createManagedKeyFile(byte[] passphrase, Path keyFilePath) throws IOException {
        deleteManagedKeyFile(keyFilePath);
        try {
            Files.createFile(keyFilePath, PosixFilePermissions.asFileAttribute(KEY_FILE_PERMISSIONS));
        } catch (UnsupportedOperationException e) {
            Files.createFile(keyFilePath);
            setOwnerOnlyPermissions(keyFilePath);
        }
        Files.write(keyFilePath, passphrase, StandardOpenOption.WRITE);
        setOwnerOnlyPermissions(keyFilePath);
        return keyFilePath;
    }

    protected void setOwnerOnlyPermissions(Path keyFilePath) throws IOException {
        try {
            Files.setPosixFilePermissions(keyFilePath, KEY_FILE_PERMISSIONS);
        } catch (UnsupportedOperationException e) {
            File keyFile = keyFilePath.toFile();
            boolean permissionsUpdated = keyFile.setReadable(false, false)
                    && keyFile.setReadable(true, true)
                    && keyFile.setWritable(false, false)
                    && keyFile.setWritable(true, true)
                    && keyFile.setExecutable(false, false);
            if (!permissionsUpdated) {
                throw new IOException("Failed to set owner-only permissions on qemu-nbd key file.");
            }
        }
    }

    protected void deleteManagedKeyFile(Path keyFilePath) {
        if (keyFilePath == null) {
            return;
        }
        try {
            Files.deleteIfExists(keyFilePath);
        } catch (IOException e) {
            logger.warn("Failed to delete qemu-nbd key file [{}].", keyFilePath, e);
        }
    }

    protected void stopAndResetNbdService(String unitName) {
        runCommand("systemctl", "stop", unitName);
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

    protected boolean isBitmapPresentOnDisk(String volumePath, String fromCheckpointId) {
        String qemuImgInfo = Script.runBashScriptIgnoreExitValue(String.format("qemu-img info --output=json %s", volumePath), 0);
        if (StringUtils.isBlank(qemuImgInfo)) {
            logger.warn("Unable to read qemu-img info output for disk path [{}].", volumePath);
            return false;
        }
        try {
            JSONObject info = new JSONObject(qemuImgInfo);
            JSONObject formatSpecific = info.optJSONObject("format-specific");
            if (formatSpecific == null) {
                return false;
            }
            JSONObject formatData = formatSpecific.optJSONObject("data");
            if (formatData == null) {
                return false;
            }
            JSONArray bitmaps = formatData.optJSONArray("bitmaps");
            if (bitmaps == null) {
                return false;
            }
            for (int index = 0; index < bitmaps.length(); index++) {
                JSONObject bitmap = bitmaps.optJSONObject(index);
                if (bitmap != null && fromCheckpointId.equals(bitmap.optString("name"))) {
                    return true;
                }
            }
        } catch (Exception e) {
            logger.warn("Failed to parse qemu-img info output for disk path [{}].", volumePath, e);
        }
        return false;
    }
}
