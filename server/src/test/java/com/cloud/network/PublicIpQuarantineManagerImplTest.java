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
package com.cloud.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Calendar;
import java.util.Date;

import org.apache.cloudstack.api.command.user.address.RemoveQuarantinedIpCmd;
import org.apache.cloudstack.api.command.user.address.UpdateQuarantinedIpCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.PublicIpQuarantineDao;
import com.cloud.network.vo.PublicIpQuarantineVO;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;

@RunWith(MockitoJUnitRunner.class)
public class PublicIpQuarantineManagerImplTest {

    @Mock private PublicIpQuarantineDao publicIpQuarantineDao;
    @Mock private IPAddressDao ipAddressDao;
    @Mock private AccountDao accountDao;
    @Mock private DomainDao domainDao;
    @Mock private AccountManager accountManager;
    @Mock private IpAddressManager ipAddrMgr;

    @Mock private UpdateQuarantinedIpCmd updateCmd;
    @Mock private RemoveQuarantinedIpCmd removeCmd;
    @Mock private PublicIpQuarantineVO publicIpQuarantineVO;
    @Mock private IPAddressVO ipAddressVO;
    @Mock private AccountVO accountVO;
    @Mock private DomainVO domainVO;
    @Mock private Ip ipMock;

    @InjectMocks
    private PublicIpQuarantineManagerImpl manager;

    private MockedStatic<CallContext> callContextMocked;

    private static final Long PUBLIC_IP_ID = 1L;
    private static final String DUMMY_IP_ADDRESS = "192.168.0.1";

    private static Date beforeDate;
    private static Date afterDate;

    @BeforeClass
    public static void setUpBeforeClass() {
        Date now = new Date();
        Calendar calendar = Calendar.getInstance();

        calendar.setTime(now);
        calendar.add(Calendar.DATE, -1);
        beforeDate = calendar.getTime();

        calendar.setTime(now);
        calendar.add(Calendar.DATE, 1);
        afterDate = calendar.getTime();
    }

    @Before
    public void setUp() {
        callContextMocked = Mockito.mockStatic(CallContext.class);
        CallContext ctx = mock(CallContext.class);
        callContextMocked.when(CallContext::current).thenReturn(ctx);
        Account caller = mock(Account.class);
        when(ctx.getCallingAccount()).thenReturn(caller);
    }

    @After
    public void tearDown() {
        callContextMocked.close();
    }

    // ---- retrievePublicIpQuarantine ----

    @Test(expected = CloudRuntimeException.class)
    public void retrieveWithBothArgsNullThrows() {
        manager.retrievePublicIpQuarantine(null, null);
    }

    @Test
    public void retrieveByIdHitsByIdOnly() {
        when(publicIpQuarantineDao.findById(anyLong())).thenReturn(publicIpQuarantineVO);

        PublicIpQuarantine result = manager.retrievePublicIpQuarantine(PUBLIC_IP_ID, null);

        assertSame(publicIpQuarantineVO, result);
        verify(publicIpQuarantineDao, times(1)).findById(anyLong());
        verify(publicIpQuarantineDao, never()).findByIpAddress(anyString());
    }

    @Test(expected = CloudRuntimeException.class)
    public void retrieveByMissingIdThrows() {
        when(publicIpQuarantineDao.findById(anyLong())).thenReturn(null);

        manager.retrievePublicIpQuarantine(PUBLIC_IP_ID, null);
    }

    @Test
    public void retrieveByAddressHitsByAddressOnly() {
        when(publicIpQuarantineDao.findByIpAddress(anyString())).thenReturn(publicIpQuarantineVO);

        PublicIpQuarantine result = manager.retrievePublicIpQuarantine(null, DUMMY_IP_ADDRESS);

        assertSame(publicIpQuarantineVO, result);
        verify(publicIpQuarantineDao, never()).findById(anyLong());
        verify(publicIpQuarantineDao, times(1)).findByIpAddress(anyString());
    }

    @Test(expected = CloudRuntimeException.class)
    public void retrieveByMissingAddressThrows() {
        when(publicIpQuarantineDao.findByIpAddress(anyString())).thenReturn(null);

        manager.retrievePublicIpQuarantine(null, DUMMY_IP_ADDRESS);
    }

    @Test
    public void retrievePrefersIdOverAddressWhenBothGiven() {
        when(publicIpQuarantineDao.findById(anyLong())).thenReturn(publicIpQuarantineVO);

        manager.retrievePublicIpQuarantine(PUBLIC_IP_ID, DUMMY_IP_ADDRESS);

        verify(publicIpQuarantineDao, times(1)).findById(anyLong());
        verify(publicIpQuarantineDao, never()).findByIpAddress(anyString());
    }

    // ---- updatePublicIpAddressInQuarantine ----

    private void stubActiveQuarantineLookupById() {
        when(updateCmd.getId()).thenReturn(PUBLIC_IP_ID);
        when(publicIpQuarantineDao.findById(anyLong())).thenReturn(publicIpQuarantineVO);
        when(accountDao.findById(anyLong())).thenReturn(accountVO);
        when(domainDao.findById(anyLong())).thenReturn(domainVO);
        doNothing().when(accountManager).checkAccess(any(Account.class), any(Domain.class));
        when(ipAddressDao.findById(anyLong())).thenReturn(ipAddressVO);
        when(ipAddressVO.getAddress()).thenReturn(ipMock);
        when(ipMock.toString()).thenReturn(DUMMY_IP_ADDRESS);
    }

    @Test
    public void updateRejectsExpiredQuarantine() {
        when(updateCmd.getEndDate()).thenReturn(afterDate);
        stubActiveQuarantineLookupById();
        when(publicIpQuarantineVO.getEndDate()).thenReturn(beforeDate);

        String expected = String.format("The quarantine for the public IP address [%s] is no longer active; thus, it cannot be updated.", DUMMY_IP_ADDRESS);
        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> manager.updatePublicIpAddressInQuarantine(updateCmd));
        assertEquals(expected, ex.getMessage());
    }

    @Test
    public void updateRejectsEndDateInThePast() {
        when(updateCmd.getEndDate()).thenReturn(beforeDate);

        String expected = String.format("The given end date [%s] is invalid as it is before the current date.", beforeDate);
        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> manager.updatePublicIpAddressInQuarantine(updateCmd));
        assertEquals(expected, ex.getMessage());
    }

    @Test
    public void updateExtendsActiveQuarantine() {
        Calendar cal = Calendar.getInstance();
        cal.setTime(afterDate);
        cal.add(Calendar.DATE, 5);
        Date newEndDate = cal.getTime();

        when(updateCmd.getEndDate()).thenReturn(newEndDate);
        stubActiveQuarantineLookupById();
        when(publicIpQuarantineVO.getEndDate()).thenReturn(afterDate);
        when(ipAddrMgr.updatePublicIpAddressInQuarantine(anyLong(), any(Date.class))).thenReturn(publicIpQuarantineVO);

        PublicIpQuarantine result = manager.updatePublicIpAddressInQuarantine(updateCmd);

        assertSame(publicIpQuarantineVO, result);
        verify(ipAddrMgr).updatePublicIpAddressInQuarantine(eq(publicIpQuarantineVO.getId()), eq(newEndDate));
    }

    // ---- removePublicIpAddressFromQuarantine ----

    @Test
    public void removeRejectsBlankRemovalReason() {
        when(removeCmd.getId()).thenReturn(PUBLIC_IP_ID);
        when(removeCmd.getRemovalReason()).thenReturn("   ");
        when(publicIpQuarantineDao.findById(anyLong())).thenReturn(publicIpQuarantineVO);
        when(ipAddressDao.findById(anyLong())).thenReturn(ipAddressVO);
        when(ipAddressVO.getAddress()).thenReturn(ipMock);
        when(ipMock.toString()).thenReturn(DUMMY_IP_ADDRESS);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> manager.removePublicIpAddressFromQuarantine(removeCmd));
        assertEquals(String.format("The given reason for removing the public IP address [%s] from quarantine is blank.", DUMMY_IP_ADDRESS),
                ex.getMessage());
        verify(ipAddrMgr, never()).removePublicIpAddressFromQuarantine(anyLong(), anyString());
    }

    @Test
    public void removeRejectsNullRemovalReason() {
        when(removeCmd.getId()).thenReturn(PUBLIC_IP_ID);
        when(removeCmd.getRemovalReason()).thenReturn(null);
        when(publicIpQuarantineDao.findById(anyLong())).thenReturn(publicIpQuarantineVO);
        when(ipAddressDao.findById(anyLong())).thenReturn(ipAddressVO);
        when(ipAddressVO.getAddress()).thenReturn(ipMock);
        when(ipMock.toString()).thenReturn(DUMMY_IP_ADDRESS);

        assertThrows(CloudRuntimeException.class,
                () -> manager.removePublicIpAddressFromQuarantine(removeCmd));
        verify(ipAddrMgr, never()).removePublicIpAddressFromQuarantine(anyLong(), anyString());
    }

    @Test
    public void removeDelegatesToManagerWhenValid() {
        String reason = "Test removal reason";
        when(removeCmd.getId()).thenReturn(PUBLIC_IP_ID);
        when(removeCmd.getRemovalReason()).thenReturn(reason);
        when(publicIpQuarantineDao.findById(anyLong())).thenReturn(publicIpQuarantineVO);
        when(accountDao.findById(anyLong())).thenReturn(accountVO);
        when(domainDao.findById(anyLong())).thenReturn(domainVO);
        doNothing().when(accountManager).checkAccess(any(Account.class), any(Domain.class));

        manager.removePublicIpAddressFromQuarantine(removeCmd);

        verify(ipAddrMgr, times(1)).removePublicIpAddressFromQuarantine(eq(publicIpQuarantineVO.getId()), eq(reason));
    }

    @Test(expected = CloudRuntimeException.class)
    public void removeThrowsWhenNoActiveQuarantineFound() {
        when(removeCmd.getId()).thenReturn(PUBLIC_IP_ID);
        when(publicIpQuarantineDao.findById(anyLong())).thenReturn(null);

        manager.removePublicIpAddressFromQuarantine(removeCmd);
    }
}
