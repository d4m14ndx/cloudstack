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

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.StorageLayer;

public class NfsImageMetadataServiceTest {

    private final NfsImageMetadataService service = new NfsImageMetadataService();

    @Test
    public void getTemplateFormatPreservesExtensionMappingAndPathSeparatorHandling() {
        Assert.assertEquals(ImageFormat.VHD, service.getTemplateFormat("/template/disk.vhd"));
        Assert.assertEquals(ImageFormat.VHDX, service.getTemplateFormat("C:\\template\\disk.VHDX"));
        Assert.assertEquals(ImageFormat.QCOW2, service.getTemplateFormat("disk.qcow2"));
        Assert.assertEquals(ImageFormat.OVA, service.getTemplateFormat("disk.ova"));
        Assert.assertEquals(ImageFormat.TAR, service.getTemplateFormat("disk.tar"));
        Assert.assertEquals(ImageFormat.RAW, service.getTemplateFormat("disk.img"));
        Assert.assertEquals(ImageFormat.RAW, service.getTemplateFormat("disk.raw"));
        Assert.assertEquals(ImageFormat.VMDK, service.getTemplateFormat("disk.vmdk"));
        Assert.assertEquals(ImageFormat.VDI, service.getTemplateFormat("disk.vdi"));
        Assert.assertNull(service.getTemplateFormat("/template.vhd/disk"));
        Assert.assertNull(service.getTemplateFormat("disk.unknown"));
    }

    @Test
    public void findFilePreservesFallbackOrderAndReturnsFirstExistingCandidate() {
        StorageLayer storage = Mockito.mock(StorageLayer.class);
        File base = missing("/templates/disk");
        File qcow2 = missing("/templates/disk.qcow2");
        File vhd = existing("/templates/disk.vhd");
        Mockito.when(storage.getFile("/templates/disk")).thenReturn(base);
        Mockito.when(storage.getFile("/templates/disk.qcow2")).thenReturn(qcow2);
        Mockito.when(storage.getFile("/templates/disk.vhd")).thenReturn(vhd);

        File file = service.findFile(storage, "/templates/disk");

        Assert.assertEquals("/templates/disk.vhd", file.getPath());
        Mockito.verify(storage).getFile("/templates/disk");
        Mockito.verify(storage).getFile("/templates/disk.qcow2");
        Mockito.verify(storage).getFile("/templates/disk.vhd");
        Mockito.verify(storage, Mockito.never()).getFile("/templates/disk.ova");
        Mockito.verify(storage, Mockito.never()).getFile("/templates/disk.vmdk");
    }

    @Test
    public void findFileReturnsNullWhenNoCandidateExists() {
        StorageLayer storage = Mockito.mock(StorageLayer.class);
        Mockito.when(storage.getFile(Mockito.anyString())).thenAnswer(invocation -> missing(invocation.getArgument(0)));

        Assert.assertNull(service.findFile(storage, "/templates/disk"));
    }

    @Test
    public void getVirtualSizeReturnsFileLengthWhenFormatHasNoProcessor() {
        File file = Mockito.mock(File.class);
        Mockito.when(file.length()).thenReturn(1234L);

        Assert.assertEquals(1234L, service.getVirtualSize(file, ImageFormat.VHDX, Mockito.mock(StorageLayer.class)));
    }

    private static File existing(String path) {
        File file = Mockito.mock(File.class);
        Mockito.when(file.exists()).thenReturn(true);
        Mockito.when(file.getPath()).thenReturn(path);
        return file;
    }

    private static File missing(String path) {
        File file = Mockito.mock(File.class);
        Mockito.when(file.exists()).thenReturn(false);
        Mockito.when(file.getPath()).thenReturn(path);
        return file;
    }
}
