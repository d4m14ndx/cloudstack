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
import com.cloud.utils.script.Script;
import org.apache.cloudstack.backup.StartBackupAnswer;
import org.apache.cloudstack.backup.StartBackupCommand;
import org.apache.cloudstack.utils.qemu.QemuImageOptions;
import org.apache.cloudstack.utils.qemu.QemuImg;
import org.apache.cloudstack.utils.qemu.QemuImgFile;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class LibvirtStartBackupCommandWrapperTest {

    private LibvirtStartBackupCommandWrapper wrapper;
    private LibvirtComputingResource resource;

    @Before
    public void setUp() {
        wrapper = new LibvirtStartBackupCommandWrapper();
        resource = Mockito.mock(LibvirtComputingResource.class);
        when(resource.getCmdsTimeout()).thenReturn(30);
    }

    @Test
    public void executeCreatesRunningVmCheckpointWithDiskLabels() {
        Map<String, String> diskPathUuidMap = new HashMap<>();
        diskPathUuidMap.put("/var/lib/libvirt/images/root.qcow2", "root-volume");
        StartBackupCommand command = new StartBackupCommand("i-2-VM", "checkpoint-2", "checkpoint-1", 100L,
                "/run/cloudstack/backup.sock", diskPathUuidMap, null, false);
        when(resource.getDiskPathLabelMap("i-2-VM")).thenReturn(Collections.singletonMap("/var/lib/libvirt/images/root.qcow2", "vda"));

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> when(mock.execute()).thenReturn(null))) {
            long startEpochSeconds = System.currentTimeMillis() / 1000L;
            Answer answer = wrapper.execute(command, resource);

            Assert.assertTrue(answer instanceof StartBackupAnswer);
            Assert.assertTrue(answer.getResult());
            Assert.assertNotNull(((StartBackupAnswer) answer).getCheckpointCreateTime());
            Assert.assertTrue(((StartBackupAnswer) answer).getCheckpointCreateTime() >= startEpochSeconds);
            Assert.assertTrue(((StartBackupAnswer) answer).getCheckpointCreateTime() <= (System.currentTimeMillis() / 1000L) + 1L);
            Script virsh = scripts.constructed().get(0);
            verify(virsh).add("checkpoint-create");
            verify(virsh).add("--domain");
            verify(virsh).add("i-2-VM");
            verify(virsh).add("--xmlfile");
            verify(virsh, times(5)).add(any(String.class));
        }
    }

    @Test
    public void executeAddsStoppedVmBitmapToEachDisk() throws Exception {
        Map<String, String> diskPathUuidMap = new HashMap<>();
        diskPathUuidMap.put("/var/lib/libvirt/images/root.qcow2", "root-volume");
        StartBackupCommand command = new StartBackupCommand("i-2-VM", "checkpoint-2", null, null,
                null, diskPathUuidMap, null, true);

        try (MockedConstruction<QemuImg> qemuImgs = Mockito.mockConstruction(QemuImg.class)) {
            Answer answer = wrapper.execute(command, resource);

            Assert.assertTrue(answer.getResult());
            verify(qemuImgs.constructed().get(0)).bitmap(eq(QemuImg.BitmapOperation.Add), any(QemuImgFile.class), eq("checkpoint-2"));
        }
    }

    @Test
    public void executeAddsEncryptedStoppedVmBitmapUsingImageOptionsAndSecret() throws Exception {
        Map<String, String> diskPathUuidMap = new HashMap<>();
        diskPathUuidMap.put("/var/lib/libvirt/images/root.qcow2", "root-volume");
        Map<String, byte[]> passphrases = new HashMap<>();
        passphrases.put("/var/lib/libvirt/images/root.qcow2", "secret".getBytes());
        StartBackupCommand command = new StartBackupCommand("i-2-VM", "checkpoint-2", null, null,
                null, diskPathUuidMap, passphrases, true);

        try (MockedConstruction<QemuImg> qemuImgs = Mockito.mockConstruction(QemuImg.class)) {
            Answer answer = wrapper.execute(command, resource);

            Assert.assertTrue(answer.getResult());
            verify(qemuImgs.constructed().get(0)).bitmap(eq(QemuImg.BitmapOperation.Add), any(QemuImageOptions.class), any(), eq("checkpoint-2"));
        }
    }

    @Test
    public void executeFailsWhenRunningVmDiskPathCannotBeMappedToDiskLabel() {
        Map<String, String> diskPathUuidMap = new HashMap<>();
        diskPathUuidMap.put("/var/lib/libvirt/images/root.qcow2", "root-volume");
        StartBackupCommand command = new StartBackupCommand("i-2-VM", "checkpoint-2", null, null,
                null, diskPathUuidMap, null, false);
        when(resource.getDiskPathLabelMap("i-2-VM")).thenReturn(Collections.emptyMap());

        Answer answer = wrapper.execute(command, resource);

        Assert.assertFalse(answer.getResult());
        Assert.assertTrue(answer.getDetails().contains("Unable to map disk path"));
    }
}
