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
import java.util.Map;

import org.apache.cloudstack.storage.template.DownloadManager;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.amazonaws.services.s3.model.S3ObjectSummary;
import com.cloud.agent.api.storage.ListTemplateAnswer;
import com.cloud.agent.api.storage.ListTemplateCommand;
import com.cloud.agent.api.storage.ListVolumeAnswer;
import com.cloud.agent.api.storage.ListVolumeCommand;
import com.cloud.agent.api.to.NfsTO;
import com.cloud.agent.api.to.S3TO;
import com.cloud.agent.api.to.SwiftTO;
import com.cloud.storage.template.TemplateProp;
import com.cloud.utils.SwiftUtil;
import com.cloud.utils.storage.S3.S3Utils;

@RunWith(MockitoJUnitRunner.class)
public class NfsDataStoreListingServiceTest {

    private static final SwiftTO SWIFT = new SwiftTO(1L, "http://swift.example", "account", "user", "key", null);
    private static final S3TO S3 = new S3TO(1L, "uuid", "access", "secret", "endpoint", "bucket", null, false,
            null, null, null, null, false, 0L, null, null);

    private final NfsSecondaryStorageResource resource = Mockito.spy(new NfsSecondaryStorageResource());
    private final NfsDataStoreListingService service = new NfsDataStoreListingService(resource);

    @Test
    public void swiftListTemplateParsesMetadataIntoTemplateProperties() throws Exception {
        Path metadata = Files.createTempFile("template", ".properties");
        Files.writeString(metadata, "uniquename=ubuntu-22\nfilename=disk.qcow2\nsize=1024\nvirtualsize=2048");

        try (MockedStatic<SwiftUtil> swift = Mockito.mockStatic(SwiftUtil.class)) {
            swift.when(() -> SwiftUtil.list(SWIFT, "", null)).thenReturn(new String[] {"T-7", "V-7"});
            swift.when(() -> SwiftUtil.list(SWIFT, "T-7", "template.properties")).thenReturn(new String[] {"template.properties"});
            swift.when(() -> SwiftUtil.getObject(Mockito.eq(SWIFT), Mockito.any(File.class), Mockito.eq("T-7/template.properties")))
                    .thenReturn(metadata.toFile());

            Map<String, TemplateProp> templates = service.swiftListTemplate(SWIFT);

            Assert.assertEquals(1, templates.size());
            TemplateProp template = templates.get("ubuntu-22");
            Assert.assertEquals("ubuntu-22", template.getTemplateName());
            Assert.assertEquals("T-7/disk.qcow2", template.getInstallPath());
            Assert.assertEquals(2048L, template.getSize());
            Assert.assertEquals(1024L, template.getPhysicalSize());
        } finally {
            Files.deleteIfExists(metadata);
        }
    }

    @Test
    public void s3ListTemplateMapsTemplateNameFromObjectKeyAndBuildsListAnswer() {
        resource._inSystemVM = true;
        S3ObjectSummary summary = objectSummary("template/tmpl/2/17/ubuntu-22/disk.qcow2", 4096L);

        try (MockedStatic<S3Utils> s3 = Mockito.mockStatic(S3Utils.class)) {
            s3.when(() -> S3Utils.listDirectory(S3, "bucket", "template/tmpl")).thenReturn(List.of(summary));

            ListTemplateAnswer answer = (ListTemplateAnswer)service.execute(new ListTemplateCommand(S3));

            Assert.assertEquals("bucket", answer.getSecUrl());
            TemplateProp template = answer.getTemplateInfo().get("ubuntu-22");
            Assert.assertEquals("ubuntu-22", template.getTemplateName());
            Assert.assertEquals("template/tmpl/2/17/ubuntu-22/disk.qcow2", template.getInstallPath());
            Assert.assertEquals(4096L, template.getSize());
            Assert.assertEquals(4096L, template.getPhysicalSize());
        }
    }

    @Test
    public void s3ListVolumeMapsVolumeIdFromObjectKeyAndBuildsListAnswer() {
        resource._inSystemVM = true;
        S3ObjectSummary summary = objectSummary("volumes/2/19/volume.qcow2", 8192L);

        try (MockedStatic<S3Utils> s3 = Mockito.mockStatic(S3Utils.class)) {
            s3.when(() -> S3Utils.listDirectory(S3, "bucket", "volumes")).thenReturn(List.of(summary));

            ListVolumeAnswer answer = (ListVolumeAnswer)service.execute(new ListVolumeCommand(S3, "ignored"));

            Assert.assertEquals("bucket", answer.getSecUrl());
            TemplateProp volume = answer.getTemplateInfo().get(19L);
            Assert.assertEquals("19", volume.getTemplateName());
            Assert.assertEquals("volumes/2/19/volume.qcow2", volume.getInstallPath());
            Assert.assertEquals(8192L, volume.getSize());
            Assert.assertEquals(8192L, volume.getPhysicalSize());
        }
    }

    @Test
    public void nfsListCommandsUseDownloadManagerAndRootDirectory() {
        resource._inSystemVM = true;
        DownloadManager downloadManager = Mockito.mock(DownloadManager.class);
        resource._dlMgr = downloadManager;
        NfsTO nfs = new NfsTO("nfs://host/export", null);
        Map<String, TemplateProp> templates = Map.of("template", new TemplateProp("template", "path", 1L, 1L, true, false));
        Map<Long, TemplateProp> volumes = Map.of(11L, new TemplateProp("11", "path", 2L, 2L, true, false));
        Mockito.doReturn("NFSv3").when(resource).getNfsVersion();
        Mockito.doReturn("/mnt/root").when(resource).getRootDir("nfs://host/export", "NFSv3");
        Mockito.when(downloadManager.gatherTemplateInfo("/mnt/root")).thenReturn(templates);
        Mockito.when(downloadManager.gatherVolumeInfo("/mnt/root")).thenReturn(volumes);

        ListTemplateAnswer templateAnswer = (ListTemplateAnswer)service.execute(new ListTemplateCommand(nfs, "NFSv3"));
        ListVolumeAnswer volumeAnswer = (ListVolumeAnswer)service.execute(new ListVolumeCommand(nfs, "nfs://host/export"));

        Assert.assertSame(templates, templateAnswer.getTemplateInfo());
        Assert.assertEquals("nfs://host/export", templateAnswer.getSecUrl());
        Assert.assertSame(volumes, volumeAnswer.getTemplateInfo());
        Assert.assertEquals("nfs://host/export", volumeAnswer.getSecUrl());
    }

    private static S3ObjectSummary objectSummary(String key, long size) {
        S3ObjectSummary summary = new S3ObjectSummary();
        summary.setKey(key);
        summary.setSize(size);
        return summary;
    }
}
