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
package com.cloud.server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.apache.cloudstack.api.command.admin.resource.ArchiveAlertsCmd;
import org.apache.cloudstack.api.command.admin.resource.DeleteAlertsCmd;
import org.apache.cloudstack.api.command.admin.resource.ListAlertsCmd;
import org.apache.cloudstack.api.command.user.event.ArchiveEventsCmd;
import org.apache.cloudstack.api.command.user.event.DeleteEventsCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.alert.AlertVO;
import com.cloud.alert.dao.AlertDao;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.EventVO;
import com.cloud.event.dao.EventDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class AuditTrailServiceImplTest {

    @Mock
    private EventDao eventDao;
    @Mock
    private AlertDao alertDao;
    @Mock
    private DomainDao domainDao;
    @Mock
    private AccountDao accountDao;
    @Mock
    private AccountManager accountManager;
    @Mock
    private AccountService accountService;

    @InjectMocks
    private AuditTrailServiceImpl service = new AuditTrailServiceImpl();

    @Mock
    private AccountVO callerAccount;
    @Mock
    private User callerUser;

    private AutoCloseable closeable;

    private static final long CALLER_ID = 42L;
    private static final long CALLER_DOMAIN_ID = 7L;

    @Before
    public void setup() {
        closeable = MockitoAnnotations.openMocks(this);
        // The reflective ListAlertsCmd / ArchiveAlertsCmd path uses
        // CallContext.current().getCallingAccount(), so register a fresh
        // context per test.
        when(callerAccount.getId()).thenReturn(CALLER_ID);
        when(callerAccount.getDomainId()).thenReturn(CALLER_DOMAIN_ID);
        CallContext.register(callerUser, callerAccount);
    }

    @After
    public void tearDown() throws Exception {
        CallContext.unregister();
        closeable.close();
    }

    // --- archiveEvents -----------------------------------------------------

    @Test
    public void archiveEventsNormalUserScopesToOwnAccount() {
        ArchiveEventsCmd cmd = Mockito.mock(ArchiveEventsCmd.class);
        when(cmd.getIds()).thenReturn(null);
        when(accountService.isNormalUser(CALLER_ID)).thenReturn(true);
        List<EventVO> events = new ArrayList<>();
        when(eventDao.listToArchiveOrDeleteEvents(isNull(), any(), any(), any(), any()))
                .thenReturn(events);

        boolean result = service.archiveEvents(cmd);

        Assert.assertTrue(result);
        verify(eventDao, times(1)).archiveEvents(events);
        verify(domainDao, never()).findById(anyLong());
        verify(accountDao, never()).getAccountIdsForDomains(any());
    }

    @Test
    public void archiveEventsProjectAccountScopesToOwnAccount() {
        ArchiveEventsCmd cmd = Mockito.mock(ArchiveEventsCmd.class);
        when(cmd.getIds()).thenReturn(null);
        when(accountService.isNormalUser(CALLER_ID)).thenReturn(false);
        when(callerAccount.getType()).thenReturn(Account.Type.PROJECT);
        when(eventDao.listToArchiveOrDeleteEvents(isNull(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>());

        boolean result = service.archiveEvents(cmd);

        Assert.assertTrue(result);
        verify(domainDao, never()).findById(anyLong());
    }

    @Test
    public void archiveEventsAdminScopesToDomainChildren() {
        ArchiveEventsCmd cmd = Mockito.mock(ArchiveEventsCmd.class);
        when(cmd.getIds()).thenReturn(null);
        when(accountService.isNormalUser(CALLER_ID)).thenReturn(false);
        when(callerAccount.getType()).thenReturn(Account.Type.ADMIN);
        DomainVO domain = Mockito.mock(DomainVO.class);
        when(domain.getPath()).thenReturn("/root/");
        when(domainDao.findById(CALLER_DOMAIN_ID)).thenReturn(domain);
        when(domainDao.getDomainChildrenIds("/root/")).thenReturn(Arrays.asList(1L, 2L));
        when(accountDao.getAccountIdsForDomains(Arrays.asList(1L, 2L)))
                .thenReturn(Arrays.asList(10L, 11L));
        when(eventDao.listToArchiveOrDeleteEvents(isNull(), any(), any(), any(),
                eq(Arrays.asList(10L, 11L)))).thenReturn(new ArrayList<>());

        boolean result = service.archiveEvents(cmd);

        Assert.assertTrue(result);
        verify(eventDao).archiveEvents(any());
    }

    @Test
    public void archiveEventsReturnsFalseWhenIdsExceedVisibleEvents() {
        ArchiveEventsCmd cmd = Mockito.mock(ArchiveEventsCmd.class);
        when(cmd.getIds()).thenReturn(Arrays.asList(1L, 2L, 3L));
        when(accountService.isNormalUser(CALLER_ID)).thenReturn(true);
        when(eventDao.listToArchiveOrDeleteEvents(any(), any(), any(), any(), any()))
                .thenReturn(Arrays.asList(Mockito.mock(EventVO.class)));

        boolean result = service.archiveEvents(cmd);

        Assert.assertFalse(result);
        verify(eventDao, never()).archiveEvents(any());
    }

    // --- deleteEvents ------------------------------------------------------

    @Test
    public void deleteEventsRemovesEachEventOneByOne() {
        DeleteEventsCmd cmd = Mockito.mock(DeleteEventsCmd.class);
        when(cmd.getIds()).thenReturn(null);
        when(accountManager.isNormalUser(CALLER_ID)).thenReturn(true);
        EventVO e1 = Mockito.mock(EventVO.class);
        when(e1.getId()).thenReturn(100L);
        EventVO e2 = Mockito.mock(EventVO.class);
        when(e2.getId()).thenReturn(101L);
        when(eventDao.listToArchiveOrDeleteEvents(any(), any(), any(), any(), any()))
                .thenReturn(Arrays.asList(e1, e2));

        boolean result = service.deleteEvents(cmd);

        Assert.assertTrue(result);
        verify(eventDao).remove(100L);
        verify(eventDao).remove(101L);
    }

    @Test
    public void deleteEventsReturnsFalseWhenIdsExceedVisibleEvents() {
        DeleteEventsCmd cmd = Mockito.mock(DeleteEventsCmd.class);
        when(cmd.getIds()).thenReturn(Arrays.asList(1L, 2L));
        when(accountManager.isNormalUser(CALLER_ID)).thenReturn(true);
        when(eventDao.listToArchiveOrDeleteEvents(any(), any(), any(), any(), any()))
                .thenReturn(new ArrayList<>());

        boolean result = service.deleteEvents(cmd);

        Assert.assertFalse(result);
        verify(eventDao, never()).remove(anyLong());
    }

    @Test
    public void deleteEventsAdminUsesDomainChildren() {
        DeleteEventsCmd cmd = Mockito.mock(DeleteEventsCmd.class);
        when(cmd.getIds()).thenReturn(null);
        when(accountManager.isNormalUser(CALLER_ID)).thenReturn(false);
        when(callerAccount.getType()).thenReturn(Account.Type.ADMIN);
        DomainVO domain = Mockito.mock(DomainVO.class);
        when(domain.getPath()).thenReturn("/root/sub/");
        when(domainDao.findById(CALLER_DOMAIN_ID)).thenReturn(domain);
        when(domainDao.getDomainChildrenIds("/root/sub/")).thenReturn(Arrays.asList(5L));
        when(accountDao.getAccountIdsForDomains(Arrays.asList(5L))).thenReturn(Arrays.asList(50L));
        when(eventDao.listToArchiveOrDeleteEvents(isNull(), any(), any(), any(),
                eq(Arrays.asList(50L)))).thenReturn(new ArrayList<>());

        boolean result = service.deleteEvents(cmd);

        Assert.assertTrue(result);
        verify(domainDao).findById(CALLER_DOMAIN_ID);
    }

    // --- searchForAlerts ---------------------------------------------------

    @Test
    public void searchForAlertsBuildsSearchCriteriaFromAllFields() {
        ListAlertsCmd cmd = Mockito.mock(ListAlertsCmd.class);
        when(cmd.getId()).thenReturn(99L);
        when(cmd.getType()).thenReturn("CPU");
        when(cmd.getKeyword()).thenReturn("oops");
        when(cmd.getName()).thenReturn("name-x");
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(20L);
        SearchCriteria<AlertVO> sc = Mockito.mock(SearchCriteria.class);
        SearchCriteria<AlertVO> ssc = Mockito.mock(SearchCriteria.class);
        when(alertDao.createSearchCriteria()).thenReturn(sc, ssc);
        when(accountManager.checkAccessAndSpecifyAuthority(any(), isNull())).thenReturn(3L);
        List<AlertVO> alertList = Arrays.asList(Mockito.mock(AlertVO.class));
        when(alertDao.searchAndCount(eq(sc), any()))
                .thenReturn(new Pair<>(alertList, 1));

        Pair<List<? extends com.cloud.alert.Alert>, Integer> result = service.searchForAlerts(cmd);

        Assert.assertEquals(1, (int) result.second());
        verify(sc).addAnd(eq("id"), eq(SearchCriteria.Op.EQ), eq(99L));
        verify(sc).addAnd(eq("data_center_id"), eq(SearchCriteria.Op.EQ), eq(3L));
        verify(sc).addAnd(eq("type"), eq(SearchCriteria.Op.EQ), eq("CPU"));
        verify(sc).addAnd(eq("name"), eq(SearchCriteria.Op.EQ), eq("name-x"));
        verify(sc).addAnd(eq("subject"), eq(SearchCriteria.Op.SC), eq(ssc));
        verify(sc).addAnd(eq("archived"), eq(SearchCriteria.Op.EQ), eq(false));
        verify(ssc).addOr(eq("subject"), eq(SearchCriteria.Op.LIKE), eq("%oops%"));
    }

    @Test
    public void searchForAlertsSkipsOptionalFieldsWhenNull() {
        ListAlertsCmd cmd = Mockito.mock(ListAlertsCmd.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getType()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);
        when(cmd.getName()).thenReturn(null);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(20L);
        SearchCriteria<AlertVO> sc = Mockito.mock(SearchCriteria.class);
        when(alertDao.createSearchCriteria()).thenReturn(sc);
        when(accountManager.checkAccessAndSpecifyAuthority(any(), isNull())).thenReturn(null);
        when(alertDao.searchAndCount(eq(sc), any()))
                .thenReturn(new Pair<>(new ArrayList<>(), 0));

        Pair<List<? extends com.cloud.alert.Alert>, Integer> result = service.searchForAlerts(cmd);

        Assert.assertEquals(0, (int) result.second());
        // No createSearchCriteria call for keyword sub-search when keyword is null.
        verify(alertDao, times(1)).createSearchCriteria();
        // archived is always pinned to false.
        verify(sc).addAnd(eq("archived"), eq(SearchCriteria.Op.EQ), eq(false));
        // No zone, no id, no type, no name filters added.
        verify(sc, never()).addAnd(eq("data_center_id"), any(), any());
        verify(sc, never()).addAnd(eq("id"), any(), any());
    }

    // --- archiveAlerts -----------------------------------------------------

    @Test
    public void archiveAlertsScopesToCallerZoneAndDelegatesToDao() {
        ArchiveAlertsCmd cmd = Mockito.mock(ArchiveAlertsCmd.class);
        List<Long> ids = Arrays.asList(1L);
        Date start = new Date();
        Date end = new Date();
        when(cmd.getIds()).thenReturn(ids);
        when(cmd.getType()).thenReturn("MEMORY");
        when(cmd.getStartDate()).thenReturn(start);
        when(cmd.getEndDate()).thenReturn(end);
        when(accountManager.checkAccessAndSpecifyAuthority(any(), isNull())).thenReturn(9L);
        when(alertDao.archiveAlert(ids, "MEMORY", start, end, 9L)).thenReturn(true);

        boolean result = service.archiveAlerts(cmd);

        Assert.assertTrue(result);
        verify(alertDao).archiveAlert(ids, "MEMORY", start, end, 9L);
    }

    @Test
    public void archiveAlertsReturnsDaoFalse() {
        ArchiveAlertsCmd cmd = Mockito.mock(ArchiveAlertsCmd.class);
        when(accountManager.checkAccessAndSpecifyAuthority(any(), isNull())).thenReturn(null);
        when(alertDao.archiveAlert(any(), any(), any(), any(), isNull())).thenReturn(false);

        Assert.assertFalse(service.archiveAlerts(cmd));
    }

    // --- deleteAlerts ------------------------------------------------------

    @Test
    public void deleteAlertsScopesToCallerZoneAndDelegatesToDao() {
        DeleteAlertsCmd cmd = Mockito.mock(DeleteAlertsCmd.class);
        List<Long> ids = Arrays.asList(7L, 8L);
        Date start = new Date();
        Date end = new Date();
        when(cmd.getIds()).thenReturn(ids);
        when(cmd.getType()).thenReturn("CAPACITY");
        when(cmd.getStartDate()).thenReturn(start);
        when(cmd.getEndDate()).thenReturn(end);
        when(accountManager.checkAccessAndSpecifyAuthority(any(), isNull())).thenReturn(2L);
        when(alertDao.deleteAlert(ids, "CAPACITY", start, end, 2L)).thenReturn(true);

        boolean result = service.deleteAlerts(cmd);

        Assert.assertTrue(result);
        verify(alertDao).deleteAlert(ids, "CAPACITY", start, end, 2L);
    }

    @Test
    public void deleteAlertsReturnsDaoFalse() {
        DeleteAlertsCmd cmd = Mockito.mock(DeleteAlertsCmd.class);
        when(accountManager.checkAccessAndSpecifyAuthority(any(), isNull())).thenReturn(null);
        when(alertDao.deleteAlert(any(), any(), any(), any(), isNull())).thenReturn(false);

        Assert.assertFalse(service.deleteAlerts(cmd));
    }

    // --- listEventTypes ----------------------------------------------------

    @Test
    public void listEventTypesReflectsPublicConstants() {
        String[] result = service.listEventTypes();
        Assert.assertNotNull(result);
        // EventTypes has well over 100 public constants; smoke test that we got a non-empty array.
        Assert.assertTrue(result.length > 0);
        boolean foundVmCreate = false;
        for (String t : result) {
            Assert.assertNotNull(t);
            if ("VM.CREATE".equals(t)) {
                foundVmCreate = true;
            }
        }
        // EventTypes.EVENT_VM_CREATE = "VM.CREATE" — verify a couple of well-known values are present.
        Assert.assertTrue("Expected to find VM.CREATE event type", foundVmCreate);
    }

    // --- helper to relax CallContext during a single test --------

    @Test
    public void wiringInjectsAllDaos() {
        AuditTrailServiceImpl s = new AuditTrailServiceImpl();
        ReflectionTestUtils.setField(s, "eventDao", eventDao);
        ReflectionTestUtils.setField(s, "alertDao", alertDao);
        ReflectionTestUtils.setField(s, "domainDao", domainDao);
        ReflectionTestUtils.setField(s, "accountDao", accountDao);
        ReflectionTestUtils.setField(s, "accountManager", accountManager);
        ReflectionTestUtils.setField(s, "accountService", accountService);

        Assert.assertSame(eventDao, ReflectionTestUtils.getField(s, "eventDao"));
        Assert.assertSame(alertDao, ReflectionTestUtils.getField(s, "alertDao"));
        Assert.assertSame(domainDao, ReflectionTestUtils.getField(s, "domainDao"));
        Assert.assertSame(accountDao, ReflectionTestUtils.getField(s, "accountDao"));
        Assert.assertSame(accountManager, ReflectionTestUtils.getField(s, "accountManager"));
        Assert.assertSame(accountService, ReflectionTestUtils.getField(s, "accountService"));
    }
}
