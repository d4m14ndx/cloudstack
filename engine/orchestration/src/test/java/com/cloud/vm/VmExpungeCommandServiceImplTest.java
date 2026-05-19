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

package com.cloud.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.manager.Commands;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class VmExpungeCommandServiceImplTest {

    private static final long HOST_ID = 42L;
    private static final String VM_STRING = "vm-to-expunge";

    @InjectMocks
    private VmExpungeCommandServiceImpl service;

    @Mock
    private AgentManager agentMgr;

    @Mock
    private VMInstanceVO vm;

    @Test
    public void sendVolumeExpungeCommands_emptyCommands_doesNotSend() throws Exception {
        service.sendVolumeExpungeCommands(Collections.emptyList(), HOST_ID, vm);

        verify(agentMgr, never()).send(anyLong(), any(Commands.class));
    }

    @Test
    public void sendVolumeExpungeCommands_nullHost_doesNotSend() throws Exception {
        service.sendVolumeExpungeCommands(List.of(new TestCommand()), null, vm);

        verify(agentMgr, never()).send(anyLong(), any(Commands.class));
    }

    @Test
    public void sendVolumeExpungeCommands_consoleProxy_setsBypassAndSends() throws Exception {
        TestCommand command = new TestCommand();
        when(vm.getType()).thenReturn(VirtualMachine.Type.ConsoleProxy);
        answerSuccessfully();

        service.sendVolumeExpungeCommands(List.of(command), HOST_ID, vm);

        Commands sentCommands = captureSentCommands();
        assertEquals(1, sentCommands.size());
        assertSame(command, sentCommands.toCommands()[0]);
        assertTrue(command.isBypassHostMaintenance());
    }

    @Test
    public void sendVolumeExpungeCommands_failedAnswer_throwsExistingBracketedMessage() throws Exception {
        TestCommand command = new TestCommand();
        when(vm.toString()).thenReturn(VM_STRING);
        answerWithFailure("storage cleanup failed");

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.sendVolumeExpungeCommands(List.of(command), HOST_ID, vm));

        assertEquals("Unable to expunge " + VM_STRING + " due to [storage cleanup failed].", exception.getMessage());
    }

    @Test
    public void sendFinalizeExpungeCommands_combinesFinalizeThenNicCommandsAndSetsBypass() throws Exception {
        TestCommand finalizeCommand = new TestCommand();
        TestCommand nicCommand = new TestCommand();
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        answerSuccessfully();

        service.sendFinalizeExpungeCommands(List.of(finalizeCommand), List.of(nicCommand), vm, HOST_ID);

        Commands sentCommands = captureSentCommands();
        assertEquals(2, sentCommands.size());
        assertSame(finalizeCommand, sentCommands.toCommands()[0]);
        assertSame(nicCommand, sentCommands.toCommands()[1]);
        assertFalse(finalizeCommand.isBypassHostMaintenance());
        assertFalse(nicCommand.isBypassHostMaintenance());
    }

    @Test
    public void sendFinalizeExpungeCommands_failedAnswer_throwsExistingUnbracketedMessage() throws Exception {
        TestCommand command = new TestCommand();
        when(vm.toString()).thenReturn(VM_STRING);
        answerWithFailure("finalize failed");

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.sendFinalizeExpungeCommands(List.of(command), Collections.emptyList(), vm, HOST_ID));

        assertEquals("Unable to expunge " + VM_STRING + " due to finalize failed", exception.getMessage());
    }

    private void answerSuccessfully() throws Exception {
        when(agentMgr.send(eq(HOST_ID), any(Commands.class))).thenAnswer(invocation -> {
            Commands commands = invocation.getArgument(1);
            Command[] sentCommands = commands.toCommands();
            Answer[] answers = new Answer[sentCommands.length];
            for (int i = 0; i < sentCommands.length; i++) {
                answers[i] = new Answer(sentCommands[i]);
            }
            commands.setAnswers(answers);
            return answers;
        });
    }

    private void answerWithFailure(String details) throws Exception {
        when(agentMgr.send(eq(HOST_ID), any(Commands.class))).thenAnswer(invocation -> {
            Commands commands = invocation.getArgument(1);
            Command command = commands.toCommands()[0];
            Answer[] answers = new Answer[] {new Answer(command, false, details)};
            commands.setAnswers(answers);
            return answers;
        });
    }

    private Commands captureSentCommands() throws Exception {
        ArgumentCaptor<Commands> captor = ArgumentCaptor.forClass(Commands.class);
        verify(agentMgr).send(eq(HOST_ID), captor.capture());
        return captor.getValue();
    }

    private static class TestCommand extends Command {
        @Override
        public boolean executeInSequence() {
            return false;
        }
    }
}
