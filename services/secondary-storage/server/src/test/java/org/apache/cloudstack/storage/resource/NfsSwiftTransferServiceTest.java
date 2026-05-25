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
package org.apache.cloudstack.storage.resource;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.SwiftTO;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.StorageLayer;
import com.cloud.utils.SwiftUtil;

@RunWith(MockitoJUnitRunner.class)
public class NfsSwiftTransferServiceTest {

    private static final SwiftTO SWIFT = new SwiftTO(1L, "http://swift.example", "account", "user", "key", null);

    private final NfsSecondaryStorageResource resource = Mockito.spy(new NfsSecondaryStorageResource());
    private final NfsSwiftTransferService service = new NfsSwiftTransferService(resource);

    @Test
    public void swiftWriteMetadataFilePreservesTemplatePropertiesContent() throws Exception {
        Path tempFile = Files.createTempFile("swift-template", ".properties");
        try {
            File metaFile = service.swiftWriteMetadataFile(tempFile.toString(), "unique", "disk.qcow2", 1024L, 2048L);

            Assert.assertEquals("uniquename=unique\nfilename=disk.qcow2\nsize=1024\nvirtualsize=2048",
                    Files.readString(metaFile.toPath()));
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    @Test
    public void swiftCommandBuildersPreserveExistingShellStrings() {
        Assert.assertEquals("/usr/bin/python /usr/local/cloud/systemvm/scripts/storage/secondary/swift -A http://swift.example -U account:user -K key download T-1 disk.qcow2 -o /tmp/disk.qcow2",
                service.buildSwiftDownloadCommand(SWIFT, "T-1", "disk.qcow2", "/tmp/disk.qcow2"));
        Assert.assertEquals("cd /tmp/swift;/usr/bin/python /usr/local/cloud/systemvm/scripts/storage/secondary/swift -A http://swift.example -U account:user -K key download T-1",
                service.buildSwiftDownloadContainerCommand(SWIFT, "T-1", "/tmp/swift"));
        Assert.assertEquals("cd /tmp/swift;/usr/bin/python /usr/local/cloud/systemvm/scripts/storage/secondary/swift -A http://swift.example -U account:user -K key upload T-1 disk.qcow2",
                service.buildSwiftUploadCommand(SWIFT, "T-1", "/tmp/swift", "disk.qcow2", 1024L));
        Assert.assertEquals("cd /tmp/swift;/usr/bin/python /usr/local/cloud/systemvm/scripts/storage/secondary/swift -A http://swift.example -U account:user -K key upload -S 5368709120 T-1 disk.qcow2",
                service.buildSwiftUploadCommand(SWIFT, "T-1", "/tmp/swift", "disk.qcow2", 5368709121L));
        Assert.assertEquals("/usr/bin/python /usr/local/cloud/systemvm/scripts/storage/secondary/swift -A http://swift.example -U account:user -K key list T-1 template.properties",
                service.buildSwiftListCommand(SWIFT, "T-1", "template.properties"));
        Assert.assertEquals("/usr/bin/python /usr/local/cloud/systemvm/scripts/storage/secondary/swift -A http://swift.example -U account:user -K key delete T-1 disk.qcow2",
                service.buildSwiftDeleteCommand(SWIFT, "T-1", "disk.qcow2"));
    }

    @Test
    public void listUploadFilesExpandsWildcardAndSkipsHiddenFiles() throws Exception {
        Path tempDir = Files.createTempDirectory("swift-upload");
        try {
            Files.createFile(tempDir.resolve("visible.qcow2"));
            Files.createFile(tempDir.resolve(".hidden"));

            List<String> files = service.listUploadFiles(tempDir.toString(), "*");

            Assert.assertEquals(1, files.size());
            Assert.assertEquals("visible.qcow2", files.get(0));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    @Test
    public void copyFromNfsToSwiftReturnsTemplatePathAndWritesMetadata() throws Exception {
        Path tempDir = Files.createTempDirectory("swift-copy");
        Path sourcePath = tempDir.resolve("disk.raw");
        Files.writeString(sourcePath, "raw-data");
        Files.createDirectories(tempDir.resolve("meta"));
        StorageLayer storage = Mockito.mock(StorageLayer.class);
        resource._storage = storage;
        Mockito.doReturn("NFSv3").when(resource).getNfsVersion();
        Mockito.doReturn(sourcePath.toFile()).when(resource).getFile("template/source/disk.raw", "nfs://host/export", "NFSv3");
        Mockito.doReturn(ImageFormat.RAW).when(resource).getTemplateFormat("disk.raw");
        Mockito.doReturn(4096L).when(resource).getVirtualSize(sourcePath.toFile(), ImageFormat.RAW);
        Mockito.when(storage.createUniqDir()).thenReturn(tempDir.resolve("meta").toFile());
        Mockito.when(storage.create(tempDir.resolve("meta").toString(), "template.properties")).thenReturn(true);

        NfsTO nfs = new NfsTO("nfs://host/export", null);
        TemplateObjectTO source = new TemplateObjectTO();
        source.setDataStore(nfs);
        source.setPath("template/source/disk.raw");
        TemplateObjectTO dest = new TemplateObjectTO();
        dest.setDataStore(SWIFT);
        dest.setId(17L);
        dest.setName("unique-template");
        CopyCommand command = new CopyCommand(source, dest, 1000, true);

        try (MockedStatic<SwiftUtil> swift = Mockito.mockStatic(SwiftUtil.class)) {
            swift.when(() -> SwiftUtil.getContainerName("TEMPLATE", 17L)).thenReturn("T-17");
            swift.when(() -> SwiftUtil.putObject(SWIFT, sourcePath.toFile(), "T-17", "disk.raw")).thenReturn("T-17/disk.raw");
            swift.when(() -> SwiftUtil.putObject(Mockito.eq(SWIFT), Mockito.any(File.class), Mockito.eq("T-17"), Mockito.eq("template.properties"))).thenReturn("T-17/template.properties");

            Answer answer = service.copyFromNfsToSwift(command);

            Assert.assertTrue(answer.getResult());
            TemplateObjectTO result = (TemplateObjectTO)((CopyCmdAnswer)answer).getNewData();
            Assert.assertEquals("T-17/disk.raw", result.getPath());
            Assert.assertEquals(Long.valueOf(4096L), result.getSize());
            Assert.assertEquals(Long.valueOf(sourcePath.toFile().length()), result.getPhysicalSize());
            Assert.assertEquals(ImageFormat.RAW, result.getFormat());
            swift.verify(() -> SwiftUtil.putObject(SWIFT, sourcePath.toFile(), "T-17", "disk.raw"));
            swift.verify(() -> SwiftUtil.putObject(Mockito.eq(SWIFT), Mockito.any(File.class), Mockito.eq("T-17"), Mockito.eq("template.properties")));
        } finally {
            deleteRecursively(tempDir);
        }
    }

    private static void deleteRecursively(Path path) throws Exception {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try (var paths = Files.walk(path)) {
            paths.sorted((left, right) -> right.compareTo(left))
                    .forEach(file -> file.toFile().delete());
        }
    }
}
