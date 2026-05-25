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
import org.apache.cloudstack.backup.StopBackupAnswer;
import org.apache.cloudstack.backup.StopBackupCommand;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@RunWith(MockitoJUnitRunner.class)
public class LibvirtStopBackupCommandWrapperTest {

    private LibvirtStopBackupCommandWrapper wrapper;
    private LibvirtComputingResource resource;

    @Before
    public void setUp() {
        wrapper = new LibvirtStopBackupCommandWrapper();
        resource = Mockito.mock(LibvirtComputingResource.class);
        when(resource.getCmdsTimeout()).thenReturn(30);
    }

    @Test
    public void executeEndsRunningVmBackupJob() {
        StopBackupCommand command = new StopBackupCommand("i-2-VM", 2L, 3L);

        try (MockedConstruction<Script> scripts = Mockito.mockConstruction(Script.class, (mock, context) -> when(mock.execute()).thenReturn(null))) {
            Answer answer = wrapper.execute(command, resource);

            Assert.assertTrue(answer instanceof StopBackupAnswer);
            Assert.assertTrue(answer.getResult());
            Script virsh = scripts.constructed().get(0);
            verify(virsh).add("backup-end");
            verify(virsh).add("--domain");
            verify(virsh).add("i-2-VM");
        }
    }

    @Test
    public void executeReturnsFailureWhenVirshReportsError() {
        StopBackupCommand command = new StopBackupCommand("i-2-VM", 2L, 3L);

        try (MockedConstruction<Script> ignored = Mockito.mockConstruction(Script.class, (mock, context) -> when(mock.execute()).thenReturn("no backup job"))) {
            Answer answer = wrapper.execute(command, resource);

            Assert.assertFalse(answer.getResult());
            Assert.assertTrue(answer.getDetails().contains("no backup job"));
        }
    }
}
