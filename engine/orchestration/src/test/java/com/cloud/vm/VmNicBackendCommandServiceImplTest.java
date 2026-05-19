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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.api.ApiConstants;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.PlugNicAnswer;
import com.cloud.agent.api.PlugNicCommand;
import com.cloud.agent.api.ReplugNicAnswer;
import com.cloud.agent.api.ReplugNicCommand;
import com.cloud.agent.api.UnPlugNicAnswer;
import com.cloud.agent.api.UnPlugNicCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.agent.manager.Commands;
import com.cloud.dc.DataCenter;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.Host;
import com.cloud.network.Network;
import com.cloud.network.dao.NetworkDetailVO;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmNicBackendCommandServiceImplTest {

    private static final long VM_ID = 42L;
    private static final long NETWORK_ID = 101L;
    private static final long HOST_ID = 202L;
    private static final long DATA_CENTER_ID = 303L;
    private static final String VM_NAME = "i-2-VM";

    @InjectMocks
    private VmNicBackendCommandServiceImpl service;

    @Mock
    private AgentManager agentMgr;
    @Mock
    private VMInstanceDao vmDao;
    @Mock
    private UserVmDao userVmDao;
    @Mock
    private UserVmService userVmService;
    @Mock
    private NetworkDetailsDao networkDetailsDao;
    @Mock
    private VmVlanPersistenceMappingService vmVlanPersistenceMappingService;
    @Mock
    private Network network;
    @Mock
    private NicTO nic;
    @Mock
    private VirtualMachineTO vmTO;
    @Mock
    private Host host;
    @Mock
    private DeployDestination dest;

    @Test
    public void plugNicAddsPvlanTypeToNicDetailsAndSendsCommand() throws Exception {
        VMInstanceVO vm = runningVm();
        when(network.getId()).thenReturn(NETWORK_ID);
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        when(dest.getHost()).thenReturn(host);
        when(host.getId()).thenReturn(HOST_ID);
        mockVmTo();
        NetworkDetailVO pvlanType = new NetworkDetailVO(NETWORK_ID, ApiConstants.ISOLATED_PVLAN_TYPE, "promiscuous", false);
        when(networkDetailsDao.findDetail(NETWORK_ID, ApiConstants.ISOLATED_PVLAN_TYPE)).thenReturn(pvlanType);
        Map<NetworkOffering.Detail, String> nicDetails = new HashMap<>();
        when(nic.getDetails()).thenReturn(nicDetails);
        answerWithPlugNicAnswer(true);

        boolean result = service.plugNic(network, nic, vmTO, null, dest);

        assertTrue(result);
        assertEquals("promiscuous", nicDetails.get(NetworkOffering.Detail.pvlanType));
        PlugNicCommand command = captureSentCommands().getCommand(PlugNicCommand.class);
        assertSame(nic, command.getNic());
        assertEquals(VM_NAME, command.getVmName());
        assertEquals(VirtualMachine.Type.User, command.getVMType());
    }

    @Test
    public void unplugNicCollectsUserVmStatisticsAndPassesVlanPersistenceMap() throws Exception {
        VMInstanceVO vm = runningVm();
        UserVmVO userVm = mock(UserVmVO.class);
        Map<String, Boolean> vlanToPersistenceMap = Map.of("vlan://100", true);
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);
        when(userVm.getType()).thenReturn(VirtualMachine.Type.User);
        when(dest.getHost()).thenReturn(host);
        when(host.getId()).thenReturn(HOST_ID);
        mockVmTo();
        when(vmVlanPersistenceMappingService.getVlanToPersistenceMapForVM(VM_ID)).thenReturn(vlanToPersistenceMap);
        answerWithUnplugNicAnswer(true);

        boolean result = service.unplugNic(network, nic, vmTO, null, dest);

        assertTrue(result);
        verify(userVmService).collectVmNetworkStatistics(userVm);
        UnPlugNicCommand command = captureSentCommands().getCommand(UnPlugNicCommand.class);
        assertSame(vlanToPersistenceMap, command.getVlanToPersistenceMap());
    }

    @Test
    public void unplugNicSkipsBackendCommandForStoppedVm() throws Exception {
        VMInstanceVO vm = vmWithState(State.Stopped);
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        mockVmTo();

        boolean result = service.unplugNic(network, nic, vmTO, null, dest);

        assertTrue(result);
        verify(agentMgr, never()).send(eq(HOST_ID), any(Commands.class));
        verify(userVmService, never()).collectVmNetworkStatistics(any());
    }

    @Test
    public void replugNicReturnsFalseWhenAgentAnswerFails() throws Exception {
        VMInstanceVO vm = runningVm();
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        when(host.getId()).thenReturn(HOST_ID);
        mockVmTo();
        answerWithReplugNicAnswer(false);

        boolean result = service.replugNic(network, nic, vmTO, host);

        assertFalse(result);
    }

    @Test
    public void plugNicWrapsTimeoutAsAgentUnavailableException() throws Exception {
        VMInstanceVO vm = runningVm();
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        when(dest.getHost()).thenReturn(host);
        when(host.getId()).thenReturn(HOST_ID);
        mockVmTo();
        when(agentMgr.send(eq(HOST_ID), any(Commands.class))).thenThrow(new OperationTimedoutException(null, HOST_ID, 0L, 0, false));

        AgentUnavailableException exception = assertThrows(AgentUnavailableException.class,
                () -> service.plugNic(network, nic, vmTO, null, dest));

        assertTrue(exception.getMessage().contains("Unable to plug nic for router " + VM_NAME + " in network " + network));
    }

    @Test
    public void plugNicThrowsResourceUnavailableWhenVmIsNotRunning() {
        VMInstanceVO vm = vmWithState(State.Stopped);
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        mockVmTo();

        ResourceUnavailableException exception = assertThrows(ResourceUnavailableException.class,
                () -> service.plugNic(network, nic, vmTO, null, dest));

        assertTrue(exception.getMessage().contains("Unable to apply PlugNic"));
        assertEquals(DataCenter.class, exception.getScope());
    }

    private VMInstanceVO runningVm() {
        return vmWithState(State.Running);
    }

    private VMInstanceVO vmWithState(State state) {
        VMInstanceVO vm = new VMInstanceVO();
        vm.setState(state);
        vm.setDataCenterId(DATA_CENTER_ID);
        return vm;
    }

    private void mockVmTo() {
        when(vmTO.getId()).thenReturn(VM_ID);
        when(vmTO.getName()).thenReturn(VM_NAME);
        when(vmTO.getType()).thenReturn(VirtualMachine.Type.User);
        when(vmTO.getDetails()).thenReturn(Map.of("platform", "test"));
    }

    private void answerWithPlugNicAnswer(boolean result) throws Exception {
        when(agentMgr.send(eq(HOST_ID), any(Commands.class))).thenAnswer(invocation -> {
            Commands commands = invocation.getArgument(1);
            PlugNicCommand command = commands.getCommand(PlugNicCommand.class);
            Answer[] answers = new Answer[] {new PlugNicAnswer(command, result, "result")};
            commands.setAnswers(answers);
            return answers;
        });
    }

    private void answerWithUnplugNicAnswer(boolean result) throws Exception {
        when(agentMgr.send(eq(HOST_ID), any(Commands.class))).thenAnswer(invocation -> {
            Commands commands = invocation.getArgument(1);
            UnPlugNicCommand command = commands.getCommand(UnPlugNicCommand.class);
            Answer[] answers = new Answer[] {new UnPlugNicAnswer(command, result, "result")};
            commands.setAnswers(answers);
            return answers;
        });
    }

    private void answerWithReplugNicAnswer(boolean result) throws Exception {
        when(agentMgr.send(eq(HOST_ID), any(Commands.class))).thenAnswer(invocation -> {
            Commands commands = invocation.getArgument(1);
            ReplugNicCommand command = commands.getCommand(ReplugNicCommand.class);
            Answer[] answers = new Answer[] {new ReplugNicAnswer(command, result, "result")};
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
