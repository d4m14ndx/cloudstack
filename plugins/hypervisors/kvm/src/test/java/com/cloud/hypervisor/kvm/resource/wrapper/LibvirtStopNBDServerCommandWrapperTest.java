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

import org.apache.cloudstack.backup.StopNBDServerCommand;
import org.junit.Test;

import com.cloud.agent.api.Answer;

public class LibvirtStopNBDServerCommandWrapperTest {
    @Test
    public void testExecuteStopsSanitizedUnitName() {
        RecordingStopWrapper wrapper = new RecordingStopWrapper();

        Answer answer = wrapper.execute(new StopNBDServerCommand("transfer-1", "download"), null);

        assertTrue(answer.getResult());
        assertEquals("qemu-nbd-imagetransfer-transfer-1", wrapper.unitName);
        assertEquals("/tmp/imagetransfer/transfer-1.sock", wrapper.socketPath);
    }

    @Test
    public void testExecuteRejectsUnsafeTransferId() {
        RecordingStopWrapper wrapper = new RecordingStopWrapper();

        Answer answer = wrapper.execute(new StopNBDServerCommand("transfer/1", "download"), null);

        assertFalse(answer.getResult());
        assertTrue(answer.getDetails().contains("transferId"));
    }

    private static class RecordingStopWrapper extends LibvirtStopNBDServerCommandWrapper {
        private String unitName;
        private String socketPath;

        @Override
        protected boolean stopNbdService(String unitName) {
            this.unitName = unitName;
            return true;
        }

        @Override
        protected void deleteSocketFile(String socketPath) {
            this.socketPath = socketPath;
        }
    }
}
