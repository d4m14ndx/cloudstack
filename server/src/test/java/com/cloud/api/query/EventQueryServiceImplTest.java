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
package com.cloud.api.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.command.user.event.ListEventsCmd;
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

import com.cloud.api.query.vo.EventJoinVO;
import com.cloud.event.EventVO;
import com.cloud.event.dao.EventDao;
import com.cloud.event.dao.EventJoinDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.network.Network;
import com.cloud.network.dao.NetworkVO;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class EventQueryServiceImplTest {

    private static final long CALLER_ID = 7L;

    @Mock private AccountManager accountMgr;
    @Mock private EntityManager entityManager;
    @Mock private EventDao eventDao;
    @Mock private EventJoinDao eventJoinDao;

    @Mock private SearchBuilder<EventVO> eventSearchBuilder;
    @Mock private SearchCriteria<EventVO> eventSearchCriteria;
    @Mock private EventVO eventEntity;
    @Mock private Account caller;

    @InjectMocks
    private EventQueryServiceImpl service;

    private MockedStatic<CallContext> callContextStatic;

    @Before
    public void setUp() {
        when(eventDao.createSearchBuilder()).thenReturn(eventSearchBuilder);
        when(eventSearchBuilder.entity()).thenReturn(eventEntity);
        when(eventSearchBuilder.create()).thenReturn(eventSearchCriteria);
        when(eventSearchBuilder.and()).thenReturn(eventSearchBuilder);

        when(caller.getId()).thenReturn(CALLER_ID);
        CallContext ctx = mock(CallContext.class);
        when(ctx.getCallingAccount()).thenReturn(caller);
        callContextStatic = Mockito.mockStatic(CallContext.class);
        callContextStatic.when(CallContext::current).thenReturn(ctx);
    }

    @After
    public void tearDown() {
        callContextStatic.close();
    }

    // --- Helper factory ---

    private ListEventsCmd cmdWithDefaults() {
        ListEventsCmd cmd = mock(ListEventsCmd.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getType()).thenReturn(null);
        when(cmd.getLevel()).thenReturn(null);
        when(cmd.getStartDate()).thenReturn(null);
        when(cmd.getEndDate()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);
        when(cmd.getEntryTime()).thenReturn(null);
        when(cmd.getDuration()).thenReturn(null);
        when(cmd.getStartId()).thenReturn(null);
        when(cmd.getResourceId()).thenReturn(null);
        when(cmd.getResourceType()).thenReturn(null);
        when(cmd.getState()).thenReturn(null);
        when(cmd.getDomainId()).thenReturn(null);
        when(cmd.getAccountName()).thenReturn(null);
        when(cmd.getProjectId()).thenReturn(null);
        when(cmd.isRecursive()).thenReturn(false);
        when(cmd.listAll()).thenReturn(true);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(50L);
        when(cmd.getArchived()).thenReturn(false);
        return cmd;
    }

    // ---- Resource UUID/type validation ----

    @Test
    public void searchForEventsInternalThrowsWhenResourceUuidPresentButResourceTypeNull() {
        ListEventsCmd cmd = cmdWithDefaults();
        when(cmd.getResourceId()).thenReturn(UUID.randomUUID().toString());
        when(cmd.getResourceType()).thenReturn(null);

        try {
            service.searchForEventsInternal(cmd);
            fail("expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            // expected
        }
        verify(entityManager, never()).findByUuidIncludingRemoved(any(), any(String.class));
    }

    @Test
    public void searchForEventsInternalThrowsWhenResourceIdMalformed() {
        ListEventsCmd cmd = cmdWithDefaults();
        when(cmd.getResourceId()).thenReturn("not-a-uuid");

        try {
            service.searchForEventsInternal(cmd);
            fail("expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            // expected
        }
    }

    @Test
    public void searchForEventsInternalThrowsWhenResourceTypeStringInvalid() {
        ListEventsCmd cmd = cmdWithDefaults();
        when(cmd.getResourceId()).thenReturn(UUID.randomUUID().toString());
        when(cmd.getResourceType()).thenReturn("NoSuchType");

        try {
            service.searchForEventsInternal(cmd);
            fail("expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            // expected
        }
    }

    @Test
    public void searchForEventsInternalThrowsWhenResourceLookupReturnsNull() {
        ListEventsCmd cmd = cmdWithDefaults();
        String uuid = UUID.randomUUID().toString();
        when(cmd.getResourceId()).thenReturn(uuid);
        when(cmd.getResourceType()).thenReturn("Network");
        when(entityManager.findByUuidIncludingRemoved(Network.class, uuid)).thenReturn(null);

        try {
            service.searchForEventsInternal(cmd);
            fail("expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            // expected
        }
    }

    @Test
    public void searchForEventsInternalEnforcesAccessForNonRootAdminControlledEntity() {
        ListEventsCmd cmd = cmdWithDefaults();
        String uuid = UUID.randomUUID().toString();
        when(cmd.getResourceId()).thenReturn(uuid);
        when(cmd.getResourceType()).thenReturn("Network");
        NetworkVO network = mock(NetworkVO.class);
        when(network.getId()).thenReturn(1L);
        when(network.getAccountId()).thenReturn(99L);
        when(entityManager.findByUuidIncludingRemoved(Network.class, uuid)).thenReturn(network);
        when(accountMgr.isRootAdmin(CALLER_ID)).thenReturn(false);
        doThrow(new PermissionDeniedException("nope")).when(accountMgr).checkAccess(
                eq(caller), eq(SecurityChecker.AccessType.ListEntry), anyBoolean(), any(ControlledEntity.class));

        try {
            service.searchForEventsInternal(cmd);
            fail("expected PermissionDeniedException");
        } catch (PermissionDeniedException expected) {
            // expected
        }
    }

    @Test
    public void searchForEventsInternalSkipsAccessCheckForRootAdmin() {
        ListEventsCmd cmd = cmdWithDefaults();
        String uuid = UUID.randomUUID().toString();
        when(cmd.getResourceId()).thenReturn(uuid);
        when(cmd.getResourceType()).thenReturn("Network");
        NetworkVO network = mock(NetworkVO.class);
        when(network.getId()).thenReturn(5L);
        when(entityManager.findByUuidIncludingRemoved(Network.class, uuid)).thenReturn(network);
        when(accountMgr.isRootAdmin(CALLER_ID)).thenReturn(true);
        when(eventDao.searchAndCount(eq(eventSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForEventsInternal(cmd);

        verify(accountMgr, never()).checkAccess(eq(caller), eq(SecurityChecker.AccessType.ListEntry),
                anyBoolean(), any(ControlledEntity.class));
        verify(eventSearchCriteria).setParameters(eq("resourceId"), eq(5L));
        verify(eventSearchCriteria).setParameters(eq("resourceType"), eq("Network"));
    }

    // ---- State validation ----

    @Test
    public void searchForEventsInternalThrowsWhenStateInvalid() {
        ListEventsCmd cmd = cmdWithDefaults();
        when(cmd.getState()).thenReturn("NotARealState");

        try {
            service.searchForEventsInternal(cmd);
            fail("expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            // expected
        }
    }

    @Test
    public void searchForEventsInternalSetsStateParameterWhenValid() {
        ListEventsCmd cmd = cmdWithDefaults();
        when(cmd.getState()).thenReturn("Completed");
        when(eventDao.searchAndCount(eq(eventSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForEventsInternal(cmd);

        verify(eventSearchCriteria).setParameters(eq("state"), eq(com.cloud.event.Event.State.Completed));
    }

    // ---- Filter pass-through to SearchCriteria ----

    @Test
    public void searchForEventsInternalAppliesIdLevelAndTypeFilters() {
        ListEventsCmd cmd = cmdWithDefaults();
        when(cmd.getId()).thenReturn(42L);
        when(cmd.getLevel()).thenReturn("INFO");
        when(cmd.getType()).thenReturn("VM.CREATE");
        when(eventDao.searchAndCount(eq(eventSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForEventsInternal(cmd);

        verify(eventSearchCriteria).setParameters(eq("id"), eq(42L));
        verify(eventSearchCriteria).setParameters(eq("levelEQ"), eq("INFO"));
        verify(eventSearchCriteria).setParameters(eq("type"), eq("VM.CREATE"));
    }

    @Test
    public void searchForEventsInternalAppliesKeywordTripleLikeFilter() {
        ListEventsCmd cmd = cmdWithDefaults();
        when(cmd.getKeyword()).thenReturn("boom");
        when(eventDao.searchAndCount(eq(eventSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForEventsInternal(cmd);

        verify(eventSearchCriteria).setParameters(eq("keywordType"), eq("%boom%"));
        verify(eventSearchCriteria).setParameters(eq("keywordDescription"), eq("%boom%"));
        verify(eventSearchCriteria).setParameters(eq("keywordLevel"), eq("%boom%"));
    }

    @Test
    public void searchForEventsInternalAppliesStartIdAndImplicitIdWhenNoIdSet() {
        ListEventsCmd cmd = cmdWithDefaults();
        when(cmd.getStartId()).thenReturn(123L);
        when(eventDao.searchAndCount(eq(eventSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForEventsInternal(cmd);

        verify(eventSearchCriteria).setParameters(eq("startId"), eq(123L));
        verify(eventSearchCriteria).setParameters(eq("id"), eq(123L));
    }

    @Test
    public void searchForEventsInternalDoesNotShadowIdWithStartId() {
        ListEventsCmd cmd = cmdWithDefaults();
        when(cmd.getId()).thenReturn(55L);
        when(cmd.getStartId()).thenReturn(123L);
        when(eventDao.searchAndCount(eq(eventSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForEventsInternal(cmd);

        verify(eventSearchCriteria).setParameters(eq("id"), eq(55L));
        verify(eventSearchCriteria).setParameters(eq("startId"), eq(123L));
        verify(eventSearchCriteria, never()).setParameters(eq("id"), eq(123L));
    }

    // ---- Date range routing ----

    @Test
    public void searchForEventsInternalUsesBetweenWhenBothDatesProvided() {
        ListEventsCmd cmd = cmdWithDefaults();
        Date start = new Date(1_000L);
        Date end = new Date(2_000L);
        when(cmd.getStartDate()).thenReturn(start);
        when(cmd.getEndDate()).thenReturn(end);
        when(eventDao.searchAndCount(eq(eventSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForEventsInternal(cmd);

        verify(eventSearchCriteria).setParameters(eq("createDateB"), eq(start), eq(end));
        verify(eventSearchCriteria, never()).setParameters(eq("createDateG"), any());
        verify(eventSearchCriteria, never()).setParameters(eq("createDateL"), any());
    }

    @Test
    public void searchForEventsInternalUsesGteWhenOnlyStartDateProvided() {
        ListEventsCmd cmd = cmdWithDefaults();
        Date start = new Date(1_000L);
        when(cmd.getStartDate()).thenReturn(start);
        when(eventDao.searchAndCount(eq(eventSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForEventsInternal(cmd);

        verify(eventSearchCriteria).setParameters(eq("createDateG"), eq(start));
        verify(eventSearchCriteria, never()).setParameters(eq("createDateB"), any(), any());
    }

    @Test
    public void searchForEventsInternalUsesLteWhenOnlyEndDateProvided() {
        ListEventsCmd cmd = cmdWithDefaults();
        Date end = new Date(2_000L);
        when(cmd.getEndDate()).thenReturn(end);
        when(eventDao.searchAndCount(eq(eventSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForEventsInternal(cmd);

        verify(eventSearchCriteria).setParameters(eq("createDateL"), eq(end));
    }

    // ---- displayEvent / archived flag routing ----

    @Test
    public void searchForEventsInternalRestrictsToDisplayEventForNonRootAdmin() {
        ListEventsCmd cmd = cmdWithDefaults();
        when(accountMgr.isRootAdmin(CALLER_ID)).thenReturn(false);
        when(eventDao.searchAndCount(eq(eventSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForEventsInternal(cmd);

        verify(eventSearchCriteria).setParameters(eq("displayEvent"), eq(true));
    }

    @Test
    public void searchForEventsInternalSkipsDisplayEventRestrictionForRootAdmin() {
        ListEventsCmd cmd = cmdWithDefaults();
        when(accountMgr.isRootAdmin(CALLER_ID)).thenReturn(true);
        when(eventDao.searchAndCount(eq(eventSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForEventsInternal(cmd);

        verify(eventSearchCriteria, never()).setParameters(eq("displayEvent"), any());
    }

    @Test
    public void searchForEventsInternalAppliesArchivedFlagWhenNoIdSet() {
        ListEventsCmd cmd = cmdWithDefaults();
        when(cmd.getArchived()).thenReturn(true);
        when(eventDao.searchAndCount(eq(eventSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForEventsInternal(cmd);

        verify(eventSearchCriteria).setParameters(eq("archived"), eq(true));
    }

    @Test
    public void searchForEventsInternalSkipsArchivedFlagWhenIdSet() {
        ListEventsCmd cmd = cmdWithDefaults();
        when(cmd.getId()).thenReturn(11L);
        when(eventDao.searchAndCount(eq(eventSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForEventsInternal(cmd);

        verify(eventSearchCriteria, never()).setParameters(eq("archived"), any());
    }

    // ---- Entry-time/duration short-circuit ----

    @Test
    public void searchForEventsInternalShortCircuitsToEmptyWhenEntryTimeAndDurationBothSet() {
        ListEventsCmd cmd = cmdWithDefaults();
        when(cmd.getEntryTime()).thenReturn(30);
        when(cmd.getDuration()).thenReturn(10);
        // No stubbing on eventDao.searchAndCount — must not be invoked.
        when(eventJoinDao.searchByIds(any(Long[].class))).thenReturn(Collections.emptyList());

        Pair<List<EventJoinVO>, Integer> result = service.searchForEventsInternal(cmd);

        assertNotNull(result);
        assertEquals(Integer.valueOf(0), result.second());
        verify(eventDao, never()).searchAndCount(any(SearchCriteria.class), any(Filter.class));
    }

    // ---- Distinct id selection + ACL builder hooks ----

    @Test
    public void searchForEventsInternalSelectsDistinctIdsAndInvokesAclBuilder() {
        ListEventsCmd cmd = cmdWithDefaults();
        when(eventDao.searchAndCount(eq(eventSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForEventsInternal(cmd);

        verify(eventSearchBuilder, atLeastOnce()).select(eq(null),
                eq(SearchCriteria.Func.DISTINCT), any());
        verify(accountMgr).buildACLSearchBuilder(same(eventSearchBuilder), any(), anyBoolean(),
                anyList(), any());
        verify(accountMgr).buildACLSearchCriteria(same(eventSearchCriteria), any(), anyBoolean(),
                anyList(), any());
    }

    @Test
    public void searchForEventsInternalRoutesAclParametersToAccountManager() {
        ListEventsCmd cmd = cmdWithDefaults();
        when(cmd.getId()).thenReturn(8L);
        when(cmd.getAccountName()).thenReturn("alice");
        when(cmd.getProjectId()).thenReturn(33L);
        when(cmd.getDomainId()).thenReturn(2L);
        when(cmd.isRecursive()).thenReturn(true);
        when(eventDao.searchAndCount(eq(eventSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForEventsInternal(cmd);

        verify(accountMgr).buildACLSearchParameters(eq(caller), eq(8L), eq("alice"), eq(33L),
                anyList(), any(Ternary.class), eq(true), eq(false));
    }

    // ---- Hydration: ids -> EventJoinVO rows ----

    @Test
    public void searchForEventsInternalHydratesJoinRowsFromIds() {
        ListEventsCmd cmd = cmdWithDefaults();
        EventVO ev1 = mock(EventVO.class);
        when(ev1.getId()).thenReturn(101L);
        EventVO ev2 = mock(EventVO.class);
        when(ev2.getId()).thenReturn(102L);
        when(eventDao.searchAndCount(eq(eventSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Arrays.asList(ev1, ev2), 2));
        EventJoinVO row1 = mock(EventJoinVO.class);
        EventJoinVO row2 = mock(EventJoinVO.class);
        when(eventJoinDao.searchByIds(any(Long[].class)))
                .thenReturn(Arrays.asList(row1, row2));

        Pair<List<EventJoinVO>, Integer> result = service.searchForEventsInternal(cmd);

        assertEquals(Integer.valueOf(2), result.second());
        assertEquals(2, result.first().size());
        assertSame(row1, result.first().get(0));
        assertSame(row2, result.first().get(1));
        verify(eventJoinDao).searchByIds(any(Long[].class));
    }

    @Test
    public void searchForEventsInternalCollapsesCountToZeroWhenIdListEmpty() {
        ListEventsCmd cmd = cmdWithDefaults();
        // Simulate the race where searchAndCount reports a non-zero count but
        // returns no ids — the count must collapse to zero so the caller does
        // not advertise a phantom row.
        when(eventDao.searchAndCount(eq(eventSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 1));
        when(eventJoinDao.searchByIds(any(Long[].class))).thenReturn(Collections.emptyList());

        Pair<List<EventJoinVO>, Integer> result = service.searchForEventsInternal(cmd);

        assertEquals(Integer.valueOf(0), result.second());
        assertTrue(result.first().isEmpty());
    }

    // ---- ResourceIdSupport getters ----

    @Test
    public void getEntityManagerAndGetAccountManagerExposeInjectedDependencies() {
        assertSame(entityManager, service.getEntityManager());
        assertSame(accountMgr, service.getAccountManager());
    }
}
