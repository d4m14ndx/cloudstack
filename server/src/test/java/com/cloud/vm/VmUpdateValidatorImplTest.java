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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.api.command.user.vm.UpdateVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class VmUpdateValidatorImplTest {

    @Mock private UserVmDao userVmDao;
    @Mock private GuestOSDao guestOSDao;
    @Mock private AccountManager accountManager;
    @Mock private ServiceOfferingDao serviceOfferingDao;

    @Mock private UpdateVMCmd cmd;
    @Mock private UserVmVO vmInstance;
    @Mock private Account callerAccount;

    @InjectMocks
    private VmUpdateValidatorImpl validator;

    private MockedStatic<CallContext> callContextMock;

    @Before
    public void setUp() {
        callContextMock = Mockito.mockStatic(CallContext.class);
        CallContext ctx = mock(CallContext.class);
        callContextMock.when(CallContext::current).thenReturn(ctx);
        lenient().when(ctx.getCallingAccount()).thenReturn(callerAccount);
    }

    @After
    public void tearDown() {
        callContextMock.close();
    }

    // ---- validateGuestOsIdForUpdateVirtualMachineCommand ----

    @Test
    public void guestOsIdNullPasses() {
        when(cmd.getOsTypeId()).thenReturn(null);
        validator.validateGuestOsIdForUpdateVirtualMachineCommand(cmd);
    }

    @Test
    public void guestOsIdFoundPasses() {
        when(cmd.getOsTypeId()).thenReturn(7L);
        when(guestOSDao.findById(7L)).thenReturn(mock(GuestOSVO.class));
        validator.validateGuestOsIdForUpdateVirtualMachineCommand(cmd);
    }

    @Test
    public void guestOsIdNotFoundRejected() {
        when(cmd.getOsTypeId()).thenReturn(7L);
        when(guestOSDao.findById(7L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> validator.validateGuestOsIdForUpdateVirtualMachineCommand(cmd));
    }

    // ---- validateInputsAndPermissionForUpdateVirtualMachineCommand ----

    @Test
    public void unknownVmRejected() {
        when(cmd.getId()).thenReturn(42L);
        when(userVmDao.findById(42L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> validator.validateInputsAndPermissionForUpdateVirtualMachineCommand(cmd));
    }

    @Test
    public void knownVmRunsAccessCheckAndGuestOsValidation() {
        when(cmd.getId()).thenReturn(42L);
        when(cmd.getOsTypeId()).thenReturn(null);
        when(userVmDao.findById(42L)).thenReturn(vmInstance);
        doNothing().when(accountManager).checkAccess(callerAccount, null, true, vmInstance);

        validator.validateInputsAndPermissionForUpdateVirtualMachineCommand(cmd);

        verify(accountManager).checkAccess(callerAccount, null, true, vmInstance);
    }

    // ---- addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified ----

    @Test
    public void newValuePresentMeansNoWrite() {
        Map<String, String> details = new HashMap<>();
        validator.addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(
                321, details, VmDetailConstants.MEMORY, 123);
        assertNull(details.get(VmDetailConstants.MEMORY));
    }

    @Test
    public void existingDetailKeyMeansNoWrite() {
        Map<String, String> details = new HashMap<>();
        details.put(VmDetailConstants.MEMORY, "999");
        validator.addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(
                null, details, VmDetailConstants.MEMORY, 123);
        // The existing entry must not be clobbered.
        assertEquals("999", details.get(VmDetailConstants.MEMORY));
    }

    @Test
    public void bothAbsentBackfillsCurrentValue() {
        Map<String, String> details = new HashMap<>();
        validator.addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(
                null, details, VmDetailConstants.MEMORY, 123);
        assertEquals("123", details.get(VmDetailConstants.MEMORY));
    }

    // ---- updateInstanceDetailsMapWithCurrentValuesForAbsentDetails ----

    @Test
    public void mergesAllThreeScalingConstantsFromCurrentOffering() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getId()).thenReturn(1L);
        when(vm.getServiceOfferingId()).thenReturn(2L);

        ServiceOfferingVO current = mock(ServiceOfferingVO.class);
        when(current.getSpeed()).thenReturn(1000);
        when(current.getRamSize()).thenReturn(2048);
        when(current.getCpu()).thenReturn(4);

        // New offering has nulls so the current values get backfilled.
        ServiceOfferingVO next = mock(ServiceOfferingVO.class);
        when(next.getSpeed()).thenReturn(null);
        when(next.getRamSize()).thenReturn(null);
        when(next.getCpu()).thenReturn(null);

        when(serviceOfferingDao.findByIdIncludingRemoved(eq(1L), eq(2L))).thenReturn(current);
        when(serviceOfferingDao.findById(anyLong())).thenReturn(next);

        Map<String, String> details = new HashMap<>();
        validator.updateInstanceDetailsMapWithCurrentValuesForAbsentDetails(details, vm, 9L);

        assertEquals("1000", details.get(VmDetailConstants.CPU_SPEED));
        assertEquals("2048", details.get(VmDetailConstants.MEMORY));
        assertEquals("4", details.get(VmDetailConstants.CPU_NUMBER));
    }

    @Test
    public void doesNotOverwriteWhenNewOfferingHasValues() {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getId()).thenReturn(1L);
        when(vm.getServiceOfferingId()).thenReturn(2L);

        ServiceOfferingVO current = mock(ServiceOfferingVO.class);
        lenient().when(current.getSpeed()).thenReturn(1000);
        lenient().when(current.getRamSize()).thenReturn(2048);
        lenient().when(current.getCpu()).thenReturn(4);

        // New offering supplies all three — backfill should not fire.
        ServiceOfferingVO next = mock(ServiceOfferingVO.class);
        when(next.getSpeed()).thenReturn(2000);
        when(next.getRamSize()).thenReturn(4096);
        when(next.getCpu()).thenReturn(8);

        when(serviceOfferingDao.findByIdIncludingRemoved(eq(1L), eq(2L))).thenReturn(current);
        when(serviceOfferingDao.findById(anyLong())).thenReturn(next);

        Map<String, String> details = new HashMap<>();
        validator.updateInstanceDetailsMapWithCurrentValuesForAbsentDetails(details, vm, 9L);

        assertNull(details.get(VmDetailConstants.CPU_SPEED));
        assertNull(details.get(VmDetailConstants.MEMORY));
        assertNull(details.get(VmDetailConstants.CPU_NUMBER));
    }

}
