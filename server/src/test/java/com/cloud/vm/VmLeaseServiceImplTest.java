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

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.text.SimpleDateFormat;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.TimeZone;
import java.util.UUID;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.vm.lease.VMLeaseManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.vo.ServiceOfferingJoinVO;
import com.cloud.uservm.UserVm;
import com.cloud.event.ActionEventUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@RunWith(MockitoJUnitRunner.class)
public class VmLeaseServiceImplTest {

    @Mock private VMInstanceDetailsDao vmInstanceDetailsDao;

    @InjectMocks
    private VmLeaseServiceImpl service;

    private MockedStatic<CallContext> callContextMock;

    @Before
    public void setUp() {
        callContextMock = Mockito.mockStatic(CallContext.class);
        CallContext ctx = mock(CallContext.class);
        callContextMock.when(CallContext::current).thenReturn(ctx);
        lenient().when(ctx.getCallingUserId()).thenReturn(1L);
    }

    @After
    public void tearDown() {
        callContextMock.close();
    }

    // ---- validateLeaseProperties ----

    @Test
    public void bothNullAccepted() {
        service.validateLeaseProperties(null, null);
    }

    @Test
    public void minusOneDurationAccepted() {
        service.validateLeaseProperties(-1, null);
    }

    @Test
    public void valid_duration_and_action_accepted() {
        service.validateLeaseProperties(20, VMLeaseManager.ExpiryAction.STOP);
    }

    @Test
    public void negativeDurationRejected() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.validateLeaseProperties(-2, VMLeaseManager.ExpiryAction.STOP));
    }

    @Test
    public void zeroDurationRejected() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.validateLeaseProperties(0, VMLeaseManager.ExpiryAction.STOP));
    }

    @Test
    public void durationAboveMaxRejected() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.validateLeaseProperties(VMLeaseManager.MAX_LEASE_DURATION_DAYS + 1,
                        VMLeaseManager.ExpiryAction.STOP));
    }

    @Test
    public void nullDurationWithActionRejected() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.validateLeaseProperties(null, VMLeaseManager.ExpiryAction.STOP));
    }

    @Test
    public void durationWithoutActionRejected() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.validateLeaseProperties(20, null));
    }

    // ---- addLeaseDetailsForInstance ----

    @Test
    public void addLeaseDetailsWritesAllThreeDetailRows() {
        UserVm vm = mock(UserVm.class);
        when(vm.getId()).thenReturn(42L);
        when(vm.getUuid()).thenReturn(UUID.randomUUID().toString());

        service.addLeaseDetailsForInstance(vm, 10, VMLeaseManager.ExpiryAction.STOP);

        verify(vmInstanceDetailsDao).addDetail(eq(42L),
                eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION),
                eq(VMLeaseManager.ExpiryAction.STOP.name()), anyBoolean());
        verify(vmInstanceDetailsDao).addDetail(eq(42L),
                eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE),
                eq(formatLeaseExpiry(10)), anyBoolean());
        verify(vmInstanceDetailsDao).addDetail(eq(42L),
                eq(VmDetailConstants.INSTANCE_LEASE_EXECUTION),
                eq("PENDING"), anyBoolean());
    }

    @Test
    public void addLeaseWithNullDurationIsNoOp() {
        UserVm vm = mock(UserVm.class);
        service.addLeaseDetailsForInstance(vm, null, VMLeaseManager.ExpiryAction.STOP);
        verify(vmInstanceDetailsDao, never()).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    public void addLeaseWithZeroDurationIsNoOp() {
        UserVm vm = mock(UserVm.class);
        service.addLeaseDetailsForInstance(vm, 0, VMLeaseManager.ExpiryAction.STOP);
        verify(vmInstanceDetailsDao, never()).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }

    // ---- applyLeaseOnCreateInstance ----

    @Test
    public void applyLeaseOnCreateUsesCmdDurationWhenSupplied() {
        UserVm vm = mockVm(42L);
        ServiceOfferingJoinVO offering = mock(ServiceOfferingJoinVO.class);
        service.applyLeaseOnCreateInstance(vm, 10, VMLeaseManager.ExpiryAction.DESTROY, offering);
        verify(vmInstanceDetailsDao).addDetail(eq(42L),
                eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION),
                eq(VMLeaseManager.ExpiryAction.DESTROY.name()), anyBoolean());
    }

    @Test
    public void applyLeaseOnCreateFallsBackToOffering() {
        UserVm vm = mockVm(42L);
        ServiceOfferingJoinVO offering = mock(ServiceOfferingJoinVO.class);
        when(offering.getLeaseDuration()).thenReturn(7);
        when(offering.getLeaseExpiryAction()).thenReturn(VMLeaseManager.ExpiryAction.STOP);

        service.applyLeaseOnCreateInstance(vm, null, null, offering);

        verify(vmInstanceDetailsDao).addDetail(eq(42L),
                eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_ACTION),
                eq(VMLeaseManager.ExpiryAction.STOP.name()), anyBoolean());
    }

    @Test
    public void applyLeaseOnCreateBailsWhenOfferingHasNoDuration() {
        UserVm vm = mock(UserVm.class);
        ServiceOfferingJoinVO offering = mock(ServiceOfferingJoinVO.class);
        when(offering.getLeaseDuration()).thenReturn(null);

        service.applyLeaseOnCreateInstance(vm, null, VMLeaseManager.ExpiryAction.STOP, offering);

        verify(vmInstanceDetailsDao, never()).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    public void applyLeaseOnCreateBailsWhenNoActionAvailable() {
        UserVm vm = mock(UserVm.class);
        ServiceOfferingJoinVO offering = mock(ServiceOfferingJoinVO.class);
        when(offering.getLeaseExpiryAction()).thenReturn(null);

        service.applyLeaseOnCreateInstance(vm, 10, null, offering);

        verify(vmInstanceDetailsDao, never()).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }

    // ---- applyLeaseOnUpdateInstance ----

    @Test
    public void updateLeaseRejectedWhenInstanceHadNoLeaseAtDeploy() {
        UserVm vm = mockVm(42L);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), Mockito.anyList())).thenReturn(new HashMap<>());

        assertThrows(CloudRuntimeException.class,
                () -> service.applyLeaseOnUpdateInstance(vm, 10, VMLeaseManager.ExpiryAction.STOP));
    }

    @Test
    public void updateLeaseRejectedWhenExecutionNotPending() {
        UserVm vm = mockVm(42L);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), Mockito.anyList()))
                .thenReturn(leaseDetails(5, VMLeaseManager.LeaseActionExecution.DISABLED.name()));

        assertThrows(CloudRuntimeException.class,
                () -> service.applyLeaseOnUpdateInstance(vm, 10, VMLeaseManager.ExpiryAction.STOP));
    }

    @Test
    public void updateLeaseRejectedWhenLeaseExpired() {
        UserVm vm = mockVm(42L);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), Mockito.anyList()))
                .thenReturn(leaseDetails(-2, VMLeaseManager.LeaseActionExecution.PENDING.name()));

        assertThrows(CloudRuntimeException.class,
                () -> service.applyLeaseOnUpdateInstance(vm, 10, VMLeaseManager.ExpiryAction.STOP));
    }

    @Test
    public void updateLeaseExtendsPendingLease() {
        UserVm vm = mockVm(42L);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), Mockito.anyList()))
                .thenReturn(leaseDetails(5, VMLeaseManager.LeaseActionExecution.PENDING.name()));

        service.applyLeaseOnUpdateInstance(vm, 10, VMLeaseManager.ExpiryAction.STOP);

        verify(vmInstanceDetailsDao).addDetail(eq(42L),
                eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE),
                eq(formatLeaseExpiry(10)), anyBoolean());
    }

    @Test
    public void updateLeaseDisablesWhenDurationIsMinusOne() {
        UserVm vm = mockVm(42L);
        when(vm.getAccountId()).thenReturn(2L);
        when(vm.getDomainId()).thenReturn(3L);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(anyLong(), Mockito.anyList()))
                .thenReturn(leaseDetails(5, VMLeaseManager.LeaseActionExecution.PENDING.name()));

        try (MockedStatic<ActionEventUtils> ignored = Mockito.mockStatic(ActionEventUtils.class)) {
            service.applyLeaseOnUpdateInstance(vm, -1, VMLeaseManager.ExpiryAction.STOP);
        }

        verify(vmInstanceDetailsDao).addDetail(eq(42L),
                eq(VmDetailConstants.INSTANCE_LEASE_EXECUTION),
                eq(VMLeaseManager.LeaseActionExecution.DISABLED.name()), anyBoolean());
        // No new expiry rows when disabling.
        verify(vmInstanceDetailsDao, never()).addDetail(eq(42L),
                eq(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE), anyString(), anyBoolean());
    }

    // ---- Helpers ----

    private UserVm mockVm(long id) {
        UserVm vm = mock(UserVm.class);
        lenient().when(vm.getId()).thenReturn(id);
        lenient().when(vm.getUuid()).thenReturn(UUID.randomUUID().toString());
        return vm;
    }

    private Map<String, String> leaseDetails(int daysFromNow, String execution) {
        Map<String, String> details = new HashMap<>();
        details.put(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE, formatLeaseExpiry(daysFromNow));
        details.put(VmDetailConstants.INSTANCE_LEASE_EXECUTION, execution);
        return details;
    }

    private String formatLeaseExpiry(int daysFromNow) {
        LocalDateTime expiry = LocalDateTime.now(ZoneOffset.UTC).plusDays(daysFromNow);
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
        return sdf.format(Date.from(expiry.atZone(ZoneOffset.UTC).toInstant()));
    }

}
