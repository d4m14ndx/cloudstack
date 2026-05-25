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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.UpdateVmNicAnswer;
import com.cloud.agent.api.UpdateVmNicCommand;
import com.cloud.agent.manager.Commands;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.vm.dao.NicDao;

@RunWith(MockitoJUnitRunner.class)
public class VmNicUpdateServiceImplTest {

    private static final long HOST_ID = 42L;
    private static final long NIC_ID = 101L;
    private static final long DEFAULT_NIC_ID = 102L;
    private static final String VM_NAME = "i-2-VM";
    private static final String VM_UUID = "vm-uuid";
    private static final String NIC_UUID = "nic-uuid";
    private static final String MAC_ADDRESS = "02:00:00:00:00:01";

    @InjectMocks
    private VmNicUpdateServiceImpl service;

    @Mock
    private AgentManager agentMgr;
    @Mock
    private NicDao nicsDao;
    @Mock
    private VirtualMachine vm;
    @Mock
    private Nic nic;
    @Mock
    private Nic defaultNic;

    @Test
    public void updateDefaultNicForVMSwapsDefaultFlagAndDeviceIds() {
        NicVO nicVO = new NicVO("reserver", 1L, 1L, VirtualMachine.Type.User);
        nicVO.setDefaultNic(false);
        nicVO.setDeviceId(4);
        NicVO defaultNicVO = new NicVO("reserver", 1L, 1L, VirtualMachine.Type.User);
        defaultNicVO.setDefaultNic(true);
        defaultNicVO.setDeviceId(0);
        when(nic.getId()).thenReturn(NIC_ID);
        when(nic.getDeviceId()).thenReturn(4);
        when(nic.getUuid()).thenReturn(NIC_UUID);
        when(defaultNic.getId()).thenReturn(DEFAULT_NIC_ID);
        when(defaultNic.getDeviceId()).thenReturn(0);
        when(defaultNic.getUuid()).thenReturn("default-nic-uuid");
        when(nicsDao.findById(NIC_ID)).thenReturn(nicVO);
        when(nicsDao.findById(DEFAULT_NIC_ID)).thenReturn(defaultNicVO);

        Boolean result = service.updateDefaultNicForVM(vm, nic, defaultNic);

        assertTrue(result);
        assertTrue(nicVO.isDefaultNic());
        assertEquals(0, nicVO.getDeviceId());
        assertFalse(defaultNicVO.isDefaultNic());
        assertEquals(4, defaultNicVO.getDeviceId());
        verify(nicsDao).persist(nicVO);
        verify(nicsDao).persist(defaultNicVO);
    }

    @Test
    public void updateVmNicPersistsEnabledForStoppedVmWithoutAgentCommand() throws Exception {
        NicVO nicVO = new NicVO("reserver", 1L, 1L, VirtualMachine.Type.User);
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(nic.getId()).thenReturn(NIC_ID);
        when(nicsDao.findById(NIC_ID)).thenReturn(nicVO);

        boolean result = service.updateVmNic(vm, nic, false);

        assertTrue(result);
        assertFalse(nicVO.isEnabled());
        verify(agentMgr, never()).send(eq(HOST_ID), any(Commands.class));
        verify(nicsDao).persist(nicVO);
    }

    @Test
    public void updateVmNicSendsUpdateCommandAndPersistsForRunningVm() throws Exception {
        NicVO nicVO = new NicVO("reserver", 1L, 1L, VirtualMachine.Type.User);
        mockRunningVmAndNic();
        when(nicsDao.findById(NIC_ID)).thenReturn(nicVO);
        answerWithUpdateVmNicAnswer(true);

        boolean result = service.updateVmNic(vm, nic, false);

        assertTrue(result);
        Commands sentCommands = captureSentCommands();
        assertEquals(1, sentCommands.size());
        UpdateVmNicCommand command = (UpdateVmNicCommand)sentCommands.toCommands()[0];
        assertEquals(MAC_ADDRESS, command.getNicMacAddress());
        assertEquals(VM_NAME, command.getVmName());
        assertFalse(command.isEnabled());
        assertFalse(nicVO.isEnabled());
        verify(nicsDao).persist(nicVO);
    }

    @Test
    public void updateVmNicReturnsFalseWhenAgentAnswerFails() throws Exception {
        mockRunningVmAndNic();
        answerWithUpdateVmNicAnswer(false);

        boolean result = service.updateVmNic(vm, nic, true);

        assertFalse(result);
        verify(nicsDao, never()).persist(any(NicVO.class));
    }

    @Test
    public void updateVmNicWrapsTimeoutAsAgentUnavailableException() throws Exception {
        mockRunningVmAndNic();
        when(agentMgr.send(eq(HOST_ID), any(Commands.class))).thenThrow(new OperationTimedoutException(null, HOST_ID, 0L, 0, false));

        AgentUnavailableException exception = assertThrows(AgentUnavailableException.class,
                () -> service.updateVmNic(vm, nic, true));

        assertTrue(exception.getMessage().contains("Unable to update NIC " + NIC_UUID + " for VM " + VM_UUID + "."));
        verify(nicsDao, never()).persist(any(NicVO.class));
    }

    private void mockRunningVmAndNic() {
        when(vm.getState()).thenReturn(VirtualMachine.State.Running);
        when(vm.getHostId()).thenReturn(HOST_ID);
        when(vm.getName()).thenReturn(VM_NAME);
        when(vm.getUuid()).thenReturn(VM_UUID);
        when(nic.getId()).thenReturn(NIC_ID);
        when(nic.getUuid()).thenReturn(NIC_UUID);
        when(nic.getMacAddress()).thenReturn(MAC_ADDRESS);
    }

    private void answerWithUpdateVmNicAnswer(boolean result) throws Exception {
        when(agentMgr.send(eq(HOST_ID), any(Commands.class))).thenAnswer(invocation -> {
            Commands commands = invocation.getArgument(1);
            Command command = commands.toCommands()[0];
            Answer[] answers = new Answer[] {new UpdateVmNicAnswer((UpdateVmNicCommand)command, result, "result")};
            commands.setAnswers(answers);
            return answers;
        });
    }

    private Commands captureSentCommands() throws Exception {
        ArgumentCaptor<Commands> captor = ArgumentCaptor.forClass(Commands.class);
        verify(agentMgr).send(eq(HOST_ID), captor.capture());
        return captor.getValue();
    }
}
