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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class VmExpungeFailureTransitionServiceImplTest {

    private static final long VM_ID = 42L;

    @Mock
    private UserVmDao userVmDao;
    @Mock
    private VirtualMachineManager virtualMachineManager;

    private VmExpungeFailureTransitionServiceImpl service;

    @Before
    public void setUp() {
        service = new VmExpungeFailureTransitionServiceImpl();
        ReflectionTestUtils.setField(service, "userVmDao", userVmDao);
        ReflectionTestUtils.setField(service, "virtualMachineManager", virtualMachineManager);
    }

    @Test
    public void transitionExpungingToErrorVmInExpungingState() throws Exception {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getState()).thenReturn(VirtualMachine.State.Expunging);
        when(vm.getUuid()).thenReturn("test-uuid");
        when(userVmDao.findById(VM_ID)).thenReturn(vm);
        when(virtualMachineManager.stateTransitTo(eq(vm), eq(VirtualMachine.Event.OperationFailedToError), eq(null))).thenReturn(true);

        service.transitionExpungingToError(VM_ID);

        verify(virtualMachineManager).stateTransitTo(vm, VirtualMachine.Event.OperationFailedToError, null);
    }

    @Test
    public void transitionExpungingToErrorWarnsWhenTransitionReturnsFalse() throws Exception {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getState()).thenReturn(VirtualMachine.State.Expunging);
        when(vm.getUuid()).thenReturn("test-uuid");
        when(userVmDao.findById(VM_ID)).thenReturn(vm);
        when(virtualMachineManager.stateTransitTo(eq(vm), eq(VirtualMachine.Event.OperationFailedToError), eq(null))).thenReturn(false);

        service.transitionExpungingToError(VM_ID);

        verify(virtualMachineManager).stateTransitTo(vm, VirtualMachine.Event.OperationFailedToError, null);
    }

    @Test
    public void transitionExpungingToErrorVmNotInExpungingState() throws Exception {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(userVmDao.findById(VM_ID)).thenReturn(vm);

        service.transitionExpungingToError(VM_ID);

        verify(virtualMachineManager, never()).stateTransitTo(any(VirtualMachine.class), any(VirtualMachine.Event.class), any());
    }

    @Test
    public void transitionExpungingToErrorVmNotFound() throws Exception {
        when(userVmDao.findById(VM_ID)).thenReturn(null);

        service.transitionExpungingToError(VM_ID);

        verify(virtualMachineManager, never()).stateTransitTo(any(VirtualMachine.class), any(VirtualMachine.Event.class), any());
    }

    @Test
    public void transitionExpungingToErrorHandlesNoTransitionException() throws Exception {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getState()).thenReturn(VirtualMachine.State.Expunging);
        when(userVmDao.findById(VM_ID)).thenReturn(vm);
        when(virtualMachineManager.stateTransitTo(eq(vm), eq(VirtualMachine.Event.OperationFailedToError), eq(null)))
                .thenThrow(new NoTransitionException("no transition"));

        service.transitionExpungingToError(VM_ID);
    }
}
