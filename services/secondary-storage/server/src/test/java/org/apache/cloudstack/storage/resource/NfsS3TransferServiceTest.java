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

import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.command.CopyCommand;
import org.apache.cloudstack.storage.to.TemplateObjectTO;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.amazonaws.services.s3.transfer.Download;
import com.amazonaws.services.s3.transfer.Upload;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.S3TO;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.StorageLayer;
import com.cloud.utils.storage.S3.S3Utils;

@RunWith(MockitoJUnitRunner.class)
public class NfsS3TransferServiceTest {

    private static final S3TO S3 = new S3TO(1L, "uuid", "access", "secret", "endpoint", "bucket", null, false,
            null, null, null, null, false, 0L, null, null);

    private final NfsSecondaryStorageResource resource = Mockito.spy(new NfsSecondaryStorageResource());
    private final NfsS3TransferService service = new NfsS3TransferService(resource);

    @Test
    public void copyFromNfsToS3PreservesTemplateKeyAndAnswerFields() throws Exception {
        Path sourceDir = Files.createTempDirectory("s3-upload");
        Path sourcePath = sourceDir.resolve("disk.qcow2");
        Files.writeString(sourcePath, "qcow2-data");
        Mockito.doReturn("NFSv3").when(resource).getNfsVersion();
        Mockito.doReturn("/mnt/export").when(resource).getRootDir("nfs://host/export", "NFSv3");
        Mockito.doReturn(sourcePath.toFile()).when(resource).findFile("/mnt/export/template/source");
        Mockito.doReturn(ImageFormat.QCOW2).when(resource).getTemplateFormat("disk.qcow2");
        Mockito.doReturn(8192L).when(resource).getVirtualSize(sourcePath.toFile(), ImageFormat.QCOW2);

        NfsTO nfs = new NfsTO("nfs://host/export", null);
        TemplateObjectTO source = new TemplateObjectTO();
        source.setDataStore(nfs);
        source.setPath("template/source");
        TemplateObjectTO dest = new TemplateObjectTO();
        dest.setDataStore(S3);
        dest.setPath("template/tmpl/2/17/ubuntu-22");
        CopyCommand command = new CopyCommand(source, dest, 1000, true);

        Upload upload = Mockito.mock(Upload.class);
        try (MockedStatic<S3Utils> s3 = Mockito.mockStatic(S3Utils.class)) {
            s3.when(() -> S3Utils.putFile(S3, sourcePath.toFile(), "bucket", "template/tmpl/2/17/ubuntu-22/disk.qcow2")).thenReturn(upload);

            Answer answer = service.copyFromNfsToS3(command);

            Assert.assertTrue(answer.getResult());
            TemplateObjectTO result = (TemplateObjectTO)((CopyCmdAnswer)answer).getNewData();
            Assert.assertEquals("template/tmpl/2/17/ubuntu-22/disk.qcow2", result.getPath());
            Assert.assertEquals(Long.valueOf(8192L), result.getSize());
            Assert.assertEquals(Long.valueOf(sourcePath.toFile().length()), result.getPhysicalSize());
            Assert.assertEquals(ImageFormat.QCOW2, result.getFormat());
            Mockito.verify(upload).waitForCompletion();
        } finally {
            deleteRecursively(sourceDir);
        }
    }

    @Test
    public void copyFromS3ToNfsDownloadsObjectBasenameThenPostProcesses() throws Exception {
        Path downloadDir = Files.createTempDirectory("s3-download");
        StorageLayer storage = Mockito.mock(StorageLayer.class);
        resource._storage = storage;
        Mockito.doReturn("NFSv3").when(resource).getNfsVersion();
        Mockito.doReturn("/mnt/export").when(resource).getRootDir("nfs://host/export", "NFSv3");
        Mockito.when(storage.getFile("/mnt/export/template/cache")).thenReturn(downloadDir.toFile());

        TemplateObjectTO source = new TemplateObjectTO();
        source.setDataStore(S3);
        source.setPath("template/tmpl/2/17/ubuntu-22/disk.qcow2");
        TemplateObjectTO dest = new TemplateObjectTO();
        NfsTO nfs = new NfsTO("nfs://host/export", null);
        dest.setDataStore(nfs);
        dest.setPath("template/cache");
        TemplateObjectTO processed = new TemplateObjectTO();
        processed.setPath("template/cache/disk.qcow2");
        CopyCmdAnswer processedAnswer = new CopyCmdAnswer(processed);
        Mockito.doReturn(processedAnswer).when(resource).postProcessing(new File(downloadDir.toFile(), "disk.qcow2"),
                "/mnt/export/template/cache", "template/cache", source, dest);

        Download download = Mockito.mock(Download.class);
        try (MockedStatic<S3Utils> s3 = Mockito.mockStatic(S3Utils.class)) {
            s3.when(() -> S3Utils.getFile(S3, "bucket", "template/tmpl/2/17/ubuntu-22/disk.qcow2",
                    new File(downloadDir.toFile(), "disk.qcow2"))).thenReturn(download);

            Answer answer = service.copyFromS3ToNfs(new CopyCommand(source, dest, 1000, true), source, S3, dest, nfs);

            Assert.assertSame(processedAnswer, answer);
            Mockito.verify(download).waitForCompletion();
        } finally {
            deleteRecursively(downloadDir);
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
