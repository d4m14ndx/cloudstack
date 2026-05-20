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
import static org.mockito.Mockito.when;

import java.util.Map;

import org.apache.cloudstack.backup.CreateImageTransferAnswer;
import org.apache.cloudstack.backup.CreateImageTransferCommand;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;

@RunWith(MockitoJUnitRunner.class)
public class LibvirtCreateImageTransferCommandWrapperTest {
    @Mock
    private LibvirtComputingResource resource;

    private RecordingCreateWrapper wrapper;

    @Before
    public void setUp() {
        wrapper = new RecordingCreateWrapper();
        when(resource.getImageServerSocketPath()).thenReturn("/run/cloudstack/image-server.sock");
        when(resource.getImageServerListenAddress()).thenReturn("10.1.1.10");
        when(resource.isImageServerTlsEnabled()).thenReturn(false);
    }

    @Test
    public void testExecuteRejectsMissingTransferToken() {
        CreateImageTransferCommand command = new CreateImageTransferCommand("transfer-1", "upload",
                "socket-1", "/var/lib/images/disk.qcow2", 60, "");

        Answer answer = wrapper.execute(command, resource);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails().contains("token"));
    }

    @Test
    public void testExecuteRegistersFileTransferWithControlSocketPathAndToken() {
        CreateImageTransferCommand command = new CreateImageTransferCommand("transfer-1", "upload",
                "socket-1", "/var/lib/images/disk.qcow2", 60, "secret-token");

        CreateImageTransferAnswer answer = (CreateImageTransferAnswer) wrapper.execute(command, resource);

        assertTrue(answer.getResult());
        assertEquals("/run/cloudstack/image-server.sock", wrapper.socketPath);
        assertEquals("transfer-1", wrapper.transferId);
        assertEquals("file", wrapper.payload.get("backend"));
        assertEquals("/var/lib/images/disk.qcow2", wrapper.payload.get("file"));
        assertEquals(60, wrapper.payload.get("idle_timeout_seconds"));
        assertEquals("secret-token", wrapper.payload.get("token"));
        assertEquals("http://10.1.1.10:54322/images/transfer-1", answer.getTransferUrl());
    }

    private static class RecordingCreateWrapper extends LibvirtCreateImageTransferCommandWrapper {
        private String socketPath;
        private String transferId;
        private Map<String, Object> payload;

        @Override
        protected boolean startImageServerIfNeeded(String socketPath, int imageServerPort, String listenAddress,
                LibvirtComputingResource resource) {
            return true;
        }

        @Override
        protected boolean registerTransfer(String socketPath, String transferId, Map<String, Object> payload) {
            this.socketPath = socketPath;
            this.transferId = transferId;
            this.payload = payload;
            return true;
        }
    }
}
