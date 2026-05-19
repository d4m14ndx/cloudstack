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
package com.cloud.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.ConsoleSessionResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.domain.Domain;
import com.cloud.host.Host;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.vm.ConsoleSessionVO;
import com.cloud.vm.VMInstanceVO;

@RunWith(MockitoJUnitRunner.class)
public class ApiConsoleSessionResponseServiceTest {

    private static final long DOMAIN_ID = 11L;
    private static final long USER_ID = 12L;
    private static final long ACCOUNT_ID = 13L;
    private static final long HOST_ID = 14L;
    private static final long INSTANCE_ID = 15L;

    @Mock
    private ConsoleSessionVO consoleSession;
    @Mock
    private Domain domain;
    @Mock
    private User user;
    @Mock
    private Account account;
    @Mock
    private Host host;
    @Mock
    private VMInstanceVO instance;

    private ApiConsoleSessionResponseService service;

    @Before
    public void setup() {
        service = Mockito.spy(new ApiConsoleSessionResponseService());
    }

    @Test
    public void createConsoleSessionResponseReturnsEmptyResponseForNullConsoleSession() {
        ConsoleSessionResponse response = service.createConsoleSessionResponse(null, ResponseView.Full);

        assertNull(response.getId());
        assertNull(response.getCreated());
        assertNull(response.getObjectName());
    }

    @Test
    public void createConsoleSessionResponseForRestrictedViewCopiesBaseFieldsAndLookupFields() {
        Date created = new Date(1000L);
        Date acquired = new Date(2000L);
        Date removed = new Date(3000L);
        stubConsoleSessionBaseFields(created, acquired, removed);
        stubLookupObjects(false);

        ConsoleSessionResponse response = service.createConsoleSessionResponse(consoleSession, ResponseView.Restricted);

        assertBaseFields(response, created, acquired, removed);
        assertDomainUserAccountAndVmFields(response);
        assertNull(response.getHostId());
        assertNull(response.getHostName());
        assertEquals("consolesession", response.getObjectName());
        verify(service, never()).findHostById(HOST_ID);
    }

    @Test
    public void createConsoleSessionResponseForFullViewPopulatesHostFields() {
        Date created = new Date(1000L);
        Date acquired = new Date(2000L);
        Date removed = new Date(3000L);
        stubConsoleSessionBaseFields(created, acquired, removed);
        stubLookupObjects(true);

        ConsoleSessionResponse response = service.createConsoleSessionResponse(consoleSession, ResponseView.Full);

        assertBaseFields(response, created, acquired, removed);
        assertDomainUserAccountAndVmFields(response);
        assertEquals("host-uuid", response.getHostId());
        assertEquals("host-name", response.getHostName());
        assertEquals("consolesession", response.getObjectName());
    }

    @Test
    public void createConsoleSessionResponseLeavesLookupFieldsUnsetWhenObjectsAreMissing() {
        Date created = new Date(1000L);
        Date acquired = new Date(2000L);
        Date removed = new Date(3000L);
        stubConsoleSessionBaseFields(created, acquired, removed);
        stubMissingLookupObjects();

        ConsoleSessionResponse response = service.createConsoleSessionResponse(consoleSession, ResponseView.Full);

        assertBaseFields(response, created, acquired, removed);
        assertNull(response.getDomain());
        assertNull(response.getDomainPath());
        assertNull(response.getDomainId());
        assertNull(response.getUser());
        assertNull(response.getUserId());
        assertNull(response.getAccount());
        assertNull(response.getAccountId());
        assertNull(response.getVmId());
        assertNull(response.getVmName());
        assertNull(response.getHostId());
        assertNull(response.getHostName());
        assertEquals("consolesession", response.getObjectName());
    }

    private void stubConsoleSessionBaseFields(Date created, Date acquired, Date removed) {
        when(consoleSession.getUuid()).thenReturn("session-uuid");
        when(consoleSession.getCreated()).thenReturn(created);
        when(consoleSession.getAcquired()).thenReturn(acquired);
        when(consoleSession.getRemoved()).thenReturn(removed);
        when(consoleSession.getConsoleEndpointCreatorAddress()).thenReturn("127.0.0.1");
        when(consoleSession.getClientAddress()).thenReturn("127.0.0.2");
        when(consoleSession.getDomainId()).thenReturn(DOMAIN_ID);
        when(consoleSession.getUserId()).thenReturn(USER_ID);
        when(consoleSession.getAccountId()).thenReturn(ACCOUNT_ID);
        when(consoleSession.getHostId()).thenReturn(HOST_ID);
        when(consoleSession.getInstanceId()).thenReturn(INSTANCE_ID);
    }

    private void stubLookupObjects(boolean includeHost) {
        doReturn(domain).when(service).findDomainById(DOMAIN_ID);
        when(domain.getName()).thenReturn("domain-name");
        when(domain.getPath()).thenReturn("/ROOT/domain-name");
        when(domain.getUuid()).thenReturn("domain-uuid");

        doReturn(user).when(service).findUserById(USER_ID);
        when(user.getUsername()).thenReturn("user-name");
        when(user.getUuid()).thenReturn("user-uuid");

        doReturn(account).when(service).findAccountById(ACCOUNT_ID);
        when(account.getAccountName()).thenReturn("account-name");
        when(account.getUuid()).thenReturn("account-uuid");

        doReturn(instance).when(service).findVMInstanceById(INSTANCE_ID);
        when(instance.getUuid()).thenReturn("vm-uuid");
        when(instance.getInstanceName()).thenReturn("vm-name");

        if (includeHost) {
            doReturn(host).when(service).findHostById(HOST_ID);
            when(host.getUuid()).thenReturn("host-uuid");
            when(host.getName()).thenReturn("host-name");
        }
    }

    private void stubMissingLookupObjects() {
        doReturn(null).when(service).findDomainById(DOMAIN_ID);
        doReturn(null).when(service).findUserById(USER_ID);
        doReturn(null).when(service).findAccountById(ACCOUNT_ID);
        doReturn(null).when(service).findVMInstanceById(INSTANCE_ID);
        doReturn(null).when(service).findHostById(HOST_ID);
    }

    private void assertBaseFields(ConsoleSessionResponse response, Date created, Date acquired, Date removed) {
        assertEquals("session-uuid", response.getId());
        assertEquals(created, response.getCreated());
        assertEquals(acquired, response.getAcquired());
        assertEquals(removed, response.getRemoved());
        assertEquals("127.0.0.1", response.getConsoleEndpointCreatorAddress());
        assertEquals("127.0.0.2", response.getClientAddress());
    }

    private void assertDomainUserAccountAndVmFields(ConsoleSessionResponse response) {
        assertEquals("domain-name", response.getDomain());
        assertEquals("/ROOT/domain-name", response.getDomainPath());
        assertEquals("domain-uuid", response.getDomainId());
        assertEquals("user-name", response.getUser());
        assertEquals("user-uuid", response.getUserId());
        assertEquals("account-name", response.getAccount());
        assertEquals("account-uuid", response.getAccountId());
        assertEquals("vm-uuid", response.getVmId());
        assertEquals("vm-name", response.getVmName());
    }
}
