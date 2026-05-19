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
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
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
import com.cloud.event.ActionEventUtils;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@RunWith(MockitoJUnitRunner.class)
public class VmLeaseApplicationServiceImplTest {

    @Mock
    private VmLeaseService vmLeaseService;
    @Mock
    private VMInstanceDetailsDao vmInstanceDetailsDao;

    @InjectMocks
    private VmLeaseApplicationServiceImpl service;

    private MockedStatic<CallContext> callContextMock;

    @Before
    public void setUp() {
        callContextMock = Mockito.mockStatic(CallContext.class);
        CallContext ctx = mock(CallContext.class);
        callContextMock.when(CallContext::current).thenReturn(ctx);
        Mockito.lenient().when(ctx.getCallingUserId()).thenReturn(1L);
    }

    @After
    public void tearDown() {
        callContextMock.close();
    }

    @Test
    public void validateLeasePropertiesDelegatesToLeaseService() {
        service.validateLeaseProperties(20, VMLeaseManager.ExpiryAction.STOP);

        verify(vmLeaseService).validateLeaseProperties(20, VMLeaseManager.ExpiryAction.STOP);
    }

    @Test
    public void addLeaseDetailsDelegatesToLeaseService() {
        UserVm vm = mockVm(42L);

        service.addLeaseDetailsForInstance(vm, 10, VMLeaseManager.ExpiryAction.STOP);

        verify(vmLeaseService).addLeaseDetailsForInstance(vm, 10, VMLeaseManager.ExpiryAction.STOP);
    }

    @Test
    public void createLeaseUsesOfferingDefaultsWhenCommandValuesMissing() {
        UserVm vm = mockVm(42L);
        ServiceOfferingJoinVO offering = mock(ServiceOfferingJoinVO.class);
        when(offering.getLeaseDuration()).thenReturn(7);
        when(offering.getLeaseExpiryAction()).thenReturn(VMLeaseManager.ExpiryAction.DESTROY);

        service.applyLeaseOnCreateInstance(vm, null, null, offering);

        verify(vmLeaseService).addLeaseDetailsForInstance(vm, 7, VMLeaseManager.ExpiryAction.DESTROY);
    }

    @Test
    public void createLeaseDoesNotWriteWhenNoActionAvailable() {
        UserVm vm = mockVm(42L);
        ServiceOfferingJoinVO offering = mock(ServiceOfferingJoinVO.class);

        service.applyLeaseOnCreateInstance(vm, 10, null, offering);

        verify(vmLeaseService, never()).addLeaseDetailsForInstance(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void updateLeaseValidatesAndWritesThroughLeaseService() {
        UserVm vm = mockVm(42L);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(eq(42L), anyList()))
                .thenReturn(leaseDetails(5, VMLeaseManager.LeaseActionExecution.PENDING.name()));

        service.applyLeaseOnUpdateInstance(vm, 10, VMLeaseManager.ExpiryAction.STOP);

        verify(vmLeaseService).validateLeaseProperties(10, VMLeaseManager.ExpiryAction.STOP);
        verify(vmLeaseService).addLeaseDetailsForInstance(vm, 10, VMLeaseManager.ExpiryAction.STOP);
    }

    @Test
    public void updateLeaseDisablesPendingLeaseWithoutWritingNewLeaseDetails() {
        UserVm vm = mockVm(42L);
        when(vm.getAccountId()).thenReturn(2L);
        when(vm.getDomainId()).thenReturn(3L);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(eq(42L), anyList()))
                .thenReturn(leaseDetails(5, VMLeaseManager.LeaseActionExecution.PENDING.name()));

        try (MockedStatic<ActionEventUtils> ignored = Mockito.mockStatic(ActionEventUtils.class)) {
            service.applyLeaseOnUpdateInstance(vm, -1, VMLeaseManager.ExpiryAction.STOP);
        }

        verify(vmInstanceDetailsDao).addDetail(42L, VmDetailConstants.INSTANCE_LEASE_EXECUTION,
                VMLeaseManager.LeaseActionExecution.DISABLED.name(), false);
        verify(vmLeaseService, never()).addLeaseDetailsForInstance(Mockito.any(), Mockito.any(), Mockito.any());
    }

    @Test
    public void updateLeaseRejectsMissingDeploymentLease() {
        UserVm vm = mockVm(42L);
        when(vmInstanceDetailsDao.listDetailsKeyPairs(eq(42L), anyList())).thenReturn(new HashMap<>());

        assertThrows(CloudRuntimeException.class,
                () -> service.applyLeaseOnUpdateInstance(vm, 10, VMLeaseManager.ExpiryAction.STOP));
    }

    private UserVm mockVm(long id) {
        UserVm vm = mock(UserVm.class);
        Mockito.lenient().when(vm.getId()).thenReturn(id);
        Mockito.lenient().when(vm.getUuid()).thenReturn(UUID.randomUUID().toString());
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
