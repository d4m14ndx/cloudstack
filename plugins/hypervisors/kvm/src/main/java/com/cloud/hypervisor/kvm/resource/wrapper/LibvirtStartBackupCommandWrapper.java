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
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.script.Script;
import org.apache.cloudstack.backup.StartBackupAnswer;
import org.apache.cloudstack.backup.StartBackupCommand;
import org.apache.cloudstack.utils.cryptsetup.KeyFile;
import org.apache.cloudstack.utils.qemu.QemuImageOptions;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgException;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.apache.cloudstack.utils.qemu.QemuObject;
import org.apache.cloudstack.utils.security.ParserUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang3.StringUtils;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.libvirt.LibvirtException;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringWriter;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

@ResourceWrapper(handles = StartBackupCommand.class)
public class LibvirtStartBackupCommandWrapper extends CommandWrapper<StartBackupCommand, Answer, LibvirtComputingResource> {

    private static final String VIRSH_COMMAND = "virsh";
    private static final String QEMU_IMG_SECRET_NAME = "sec0";

    @Override
    public Answer execute(StartBackupCommand command, LibvirtComputingResource resource) {
        try {
            validate(command.getToCheckpointId(), command.getDiskPathUuidMap());
            if (command.isStoppedVM()) {
                addBitmapsForStoppedVm(command, resource);
            } else {
                createCheckpointForRunningVm(command, resource);
            }
            return new StartBackupAnswer(command, true, null, Instant.now().getEpochSecond());
        } catch (Exception e) {
            logger.error("Failed to start backup checkpoint [{}] on VM [{}].", command.getToCheckpointId(), command.getVmName(), e);
            return new StartBackupAnswer(command, false, e.getMessage());
        } finally {
            clearPassphrases(command.getDiskPathPassphraseMap());
        }
    }

    protected void createCheckpointForRunningVm(StartBackupCommand command, LibvirtComputingResource resource) throws Exception {
        Map<String, String> diskPathLabelMap = resource.getDiskPathLabelMap(command.getVmName());
        String checkpointXml = buildCheckpointXml(command, diskPathLabelMap);
        Path checkpointXmlPath = Files.createTempFile("cloudstack-checkpoint-", ".xml");
        try {
            Files.write(checkpointXmlPath, checkpointXml.getBytes(StandardCharsets.UTF_8));
            Script script = new Script(VIRSH_COMMAND, resource.getCmdsTimeout(), logger);
            script.add("checkpoint-create");
            script.add("--domain");
            script.add(command.getVmName());
            script.add("--xmlfile");
            script.add(checkpointXmlPath.toString());
            String result = script.execute();
            if (result != null) {
                throw new CloudRuntimeException(result);
            }
        } finally {
            Files.deleteIfExists(checkpointXmlPath);
        }
    }

    protected String buildCheckpointXml(StartBackupCommand command, Map<String, String> diskPathLabelMap) throws Exception {
        DocumentBuilder docBuilder = ParserUtils.getSaferDocumentBuilderFactory().newDocumentBuilder();
        Document document = docBuilder.newDocument();
        Element root = document.createElement("domaincheckpoint");
        document.appendChild(root);

        appendTextElement(document, root, "name", command.getToCheckpointId());
        if (StringUtils.isNotBlank(command.getFromCheckpointId())) {
            Element parent = document.createElement("parent");
            appendTextElement(document, parent, "name", command.getFromCheckpointId());
            root.appendChild(parent);
        }

        Element disks = document.createElement("disks");
        root.appendChild(disks);
        for (String diskPath : command.getDiskPathUuidMap().keySet()) {
            String diskLabel = diskPathLabelMap.get(diskPath);
            if (StringUtils.isBlank(diskLabel)) {
                throw new CloudRuntimeException(String.format("Unable to map disk path [%s] to a VM disk label.", diskPath));
            }
            Element disk = document.createElement("disk");
            disk.setAttribute("name", diskLabel);
            disk.setAttribute("checkpoint", "bitmap");
            disks.appendChild(disk);
        }

        Transformer transformer = TransformerFactory.newInstance().newTransformer();
        transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
        transformer.setOutputProperty(OutputKeys.INDENT, "yes");
        StringWriter writer = new StringWriter();
        transformer.transform(new DOMSource(document), new StreamResult(writer));
        return writer.toString();
    }

    protected void addBitmapsForStoppedVm(StartBackupCommand command, LibvirtComputingResource resource) throws IOException, QemuImgException, LibvirtException {
        QemuImg qemuImg = new QemuImg(resource.getCmdsTimeout());
        for (String diskPath : command.getDiskPathUuidMap().keySet()) {
            runBitmapOperation(qemuImg, QemuImg.BitmapOperation.Add, diskPath, command.getToCheckpointId(), getPassphrase(command.getDiskPathPassphraseMap(), diskPath));
        }
    }

    protected void runBitmapOperation(QemuImg qemuImg, QemuImg.BitmapOperation operation, String diskPath, String bitmapName, byte[] passphrase)
            throws IOException, QemuImgException {
        if (ArrayUtils.isEmpty(passphrase)) {
            QemuImgFile volume = new QemuImgFile(diskPath, QemuImg.PhysicalDiskFormat.QCOW2);
            qemuImg.bitmap(operation, volume, bitmapName);
            return;
        }

        try (KeyFile keyFile = new KeyFile(passphrase)) {
            QemuImageOptions imageOptions = new QemuImageOptions(QemuImg.PhysicalDiskFormat.QCOW2, diskPath, QEMU_IMG_SECRET_NAME);
            QemuObject secret = QemuObject.prepareSecretForQemuImg(QemuImg.PhysicalDiskFormat.QCOW2, QemuObject.EncryptFormat.LUKS,
                    keyFile.toString(), QEMU_IMG_SECRET_NAME, null);
            qemuImg.bitmap(operation, imageOptions, Collections.singletonList(secret), bitmapName);
        }
    }

    private void appendTextElement(Document document, Element parent, String name, String value) {
        Element element = document.createElement(name);
        element.setTextContent(value);
        parent.appendChild(element);
    }

    private void validate(String checkpointId, Map<String, String> diskPathUuidMap) {
        if (StringUtils.isBlank(checkpointId)) {
            throw new CloudRuntimeException("Checkpoint ID is required.");
        }
        if (MapUtils.isEmpty(diskPathUuidMap)) {
            throw new CloudRuntimeException("At least one disk path is required.");
        }
    }

    private byte[] getPassphrase(Map<String, byte[]> diskPathPassphraseMap, String diskPath) {
        if (MapUtils.isEmpty(diskPathPassphraseMap)) {
            return null;
        }
        return diskPathPassphraseMap.get(diskPath);
    }

    private void clearPassphrases(Map<String, byte[]> diskPathPassphraseMap) {
        if (MapUtils.isEmpty(diskPathPassphraseMap)) {
            return;
        }
        for (byte[] passphrase : diskPathPassphraseMap.values()) {
            if (passphrase != null) {
                Arrays.fill(passphrase, (byte) 0);
            }
        }
    }
}
