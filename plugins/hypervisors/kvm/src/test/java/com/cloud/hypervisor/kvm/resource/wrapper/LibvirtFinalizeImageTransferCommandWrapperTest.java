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

import org.apache.cloudstack.backup.FinalizeImageTransferCommand;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.Answer;
import com.cloud.hypervisor.kvm.resource.LibvirtComputingResource;

@RunWith(MockitoJUnitRunner.class)
public class LibvirtFinalizeImageTransferCommandWrapperTest {
    @Mock
    private LibvirtComputingResource resource;

    private RecordingFinalizeWrapper wrapper;

    @Before
    public void setUp() {
        wrapper = new RecordingFinalizeWrapper();
        when(resource.getImageServerSocketPath()).thenReturn("/run/cloudstack/image-server.sock");
    }

    @Test
    public void testExecuteDoesNotStopImageServerWhenOtherTransfersRemain() {
        wrapper.activeTransfers = 2;

        Answer answer = wrapper.execute(new FinalizeImageTransferCommand("transfer-1"), resource);

        assertTrue(answer.getResult());
        assertEquals("/run/cloudstack/image-server.sock", wrapper.socketPath);
        assertEquals("transfer-1", wrapper.transferId);
        assertFalse(wrapper.imageServerStopped);
    }

    @Test
    public void testExecuteStopsImageServerWhenNoTransfersRemain() {
        wrapper.activeTransfers = 0;

        Answer answer = wrapper.execute(new FinalizeImageTransferCommand("transfer-1"), resource);

        assertTrue(answer.getResult());
        assertTrue(wrapper.imageServerStopped);
    }

    private static class RecordingFinalizeWrapper extends LibvirtFinalizeImageTransferCommandWrapper {
        private int activeTransfers;
        private String socketPath;
        private String transferId;
        private boolean imageServerStopped;

        @Override
        protected int unregisterTransfer(String socketPath, String transferId) {
            this.socketPath = socketPath;
            this.transferId = transferId;
            return activeTransfers;
        }

        @Override
        protected boolean stopImageServer(int imageServerPort, LibvirtComputingResource resource) {
            imageServerStopped = true;
            return true;
        }
    }
}
