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

import org.apache.cloudstack.storage.command.UploadStatusAnswer;
import org.apache.cloudstack.storage.command.UploadStatusCommand;
import org.apache.cloudstack.storage.command.UploadStatusCommand.EntityType;
import org.apache.cloudstack.storage.template.UploadEntity;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.mockito.Mockito;

import io.netty.channel.Channel;

public class NfsPostUploadServiceTest {

    private static final String ENTITY_UUID = "entity-uuid";

    @Rule
    public TemporaryFolder temporaryFolder = new TemporaryFolder();

    private final NfsPostUploadService service = new NfsPostUploadService();

    @Test
    public void executeCompletedUploadStatusReturnsFinalSizesAndRemovesEntity() {
        UploadEntity uploadEntity = uploadEntity(UploadEntity.Status.COMPLETED);
        uploadEntity.setVirtualSize(1234L);
        uploadEntity.setPhysicalSize(567L);
        uploadEntity.setTemplatePath("/templates/final/template.qcow2");
        service.putUploadEntity(ENTITY_UUID, uploadEntity);

        UploadStatusAnswer answer = service.execute(uploadStatusCommand(false));

        Assert.assertEquals(UploadStatusAnswer.UploadStatus.COMPLETED, answer.getStatus());
        Assert.assertEquals(1234L, answer.getVirtualSize());
        Assert.assertEquals(567L, answer.getPhysicalSize());
        Assert.assertEquals("/templates/final/template.qcow2", answer.getInstallPath());
        Assert.assertEquals(100, answer.getDownloadPercent());
        Assert.assertFalse(service.hasUploadEntity(ENTITY_UUID));
    }

    @Test
    public void executeInProgressUploadStatusReportsPhysicalSizeAndCapsPercent() throws Exception {
        Path installPath = temporaryFolder.newFolder("install").toPath();
        Files.write(installPath.resolve("part-one"), new byte[40]);
        Files.createDirectory(installPath.resolve("nested"));
        Files.write(installPath.resolve("nested").resolve("part-two"), new byte[70]);
        UploadEntity uploadEntity = uploadEntity(UploadEntity.Status.IN_PROGRESS);
        uploadEntity.setInstallPathPrefix(installPath.toString());
        uploadEntity.setContentLength(100L);
        service.putUploadEntity(ENTITY_UUID, uploadEntity);

        UploadStatusAnswer answer = service.execute(uploadStatusCommand(false));

        Assert.assertEquals(UploadStatusAnswer.UploadStatus.IN_PROGRESS, answer.getStatus());
        Assert.assertEquals(110L, answer.getPhysicalSize());
        Assert.assertEquals(100, answer.getDownloadPercent());
        Assert.assertTrue(service.hasUploadEntity(ENTITY_UUID));
    }

    @Test
    public void executeAbortedUploadStatusClosesRegisteredChannelAndRemovesEntity() {
        UploadEntity uploadEntity = uploadEntity(UploadEntity.Status.IN_PROGRESS);
        Channel channel = Mockito.mock(Channel.class);
        Mockito.when(channel.isActive()).thenReturn(true);
        service.putUploadEntity(ENTITY_UUID, uploadEntity);
        service.registerUploadChannel(ENTITY_UUID, channel);

        UploadStatusAnswer answer = service.execute(uploadStatusCommand(true));

        Assert.assertEquals(UploadStatusAnswer.UploadStatus.ERROR, answer.getStatus());
        Assert.assertEquals("Upload Entity aborted", answer.getDetails());
        Mockito.verify(channel).close();
        Assert.assertFalse(service.hasUploadEntity(ENTITY_UUID));
    }

    @Test
    public void updateStateMapWithErrorCreatesErrorResponseForNextStatusCommand() {
        service.updateStateMapWithError(ENTITY_UUID, "upload failed");

        UploadStatusAnswer answer = service.execute(uploadStatusCommand(false));

        Assert.assertEquals(UploadStatusAnswer.UploadStatus.ERROR, answer.getStatus());
        Assert.assertEquals("upload failed", answer.getDetails());
        Assert.assertFalse(service.hasUploadEntity(ENTITY_UUID));
    }

    private static UploadEntity uploadEntity(UploadEntity.Status status) {
        UploadEntity uploadEntity = new UploadEntity(ENTITY_UUID, 1L, status, "template.qcow2", File.separator + "install");
        uploadEntity.setContentLength(100L);
        return uploadEntity;
    }

    private static UploadStatusCommand uploadStatusCommand(boolean abort) {
        return new UploadStatusCommand(ENTITY_UUID, EntityType.Template, abort);
    }
}
