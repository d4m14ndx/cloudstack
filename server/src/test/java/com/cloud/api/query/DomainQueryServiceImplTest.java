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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.command.admin.domain.ListDomainsCmd;
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

import com.cloud.api.query.dao.DomainJoinDao;
import com.cloud.api.query.vo.DomainJoinVO;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class DomainQueryServiceImplTest {

    private static final long CALLER_ID = 7L;
    private static final long CALLER_DOMAIN_ID = 11L;

    @Mock private AccountManager accountMgr;
    @Mock private DomainDao domainDao;
    @Mock private DomainJoinDao domainJoinDao;
    @Mock private ResourceTagDao resourceTagDao;

    @Mock private SearchBuilder<DomainVO> domainSearchBuilder;
    @Mock private SearchBuilder<ResourceTagVO> tagSearchBuilder;
    @Mock private SearchCriteria<DomainVO> domainSearchCriteria;
    @Mock private DomainVO domainEntity;
    @Mock private Account caller;

    @InjectMocks
    private DomainQueryServiceImpl service;

    private MockedStatic<CallContext> callContextStatic;

    @Before
    public void setUp() {
        // Common — every code path through searchForDomainIdsAndCount builds a
        // DomainVO search.
        when(domainDao.createSearchBuilder()).thenReturn(domainSearchBuilder);
        when(domainSearchBuilder.entity()).thenReturn(domainEntity);
        when(domainSearchBuilder.create()).thenReturn(domainSearchCriteria);

        // CallContext: always asked for the calling account.
        when(caller.getDomainId()).thenReturn(CALLER_DOMAIN_ID);
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

    private ListDomainsCmd cmdWithDefaults() {
        ListDomainsCmd cmd = mock(ListDomainsCmd.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getDomainName()).thenReturn(null);
        when(cmd.getLevel()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);
        when(cmd.getTags()).thenReturn(Collections.emptyMap());
        when(cmd.listAll()).thenReturn(false);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(50L);
        return cmd;
    }

    private void stubEmptyDomainSearch() {
        when(domainDao.searchAndCount(eq(domainSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));
    }

    // ---- Id-path: bad id ----

    @Test
    public void searchForDomainsInternalThrowsWhenIdSetButDomainNotFound() {
        ListDomainsCmd cmd = cmdWithDefaults();
        when(cmd.getId()).thenReturn(99L);
        when(domainDao.findById(99L)).thenReturn(null);

        try {
            service.searchForDomainsInternal(cmd);
            fail("expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            // expected
        }
        verify(domainDao, never()).searchAndCount(any(SearchCriteria.class), any(Filter.class));
        verify(accountMgr, never()).checkAccess(any(Account.class), any(Domain.class));
    }

    // ---- Id-path: access check ----

    @Test
    public void searchForDomainsInternalEnforcesAccessForCallerOnExplicitDomainId() {
        ListDomainsCmd cmd = cmdWithDefaults();
        when(cmd.getId()).thenReturn(42L);
        DomainVO target = mock(DomainVO.class);
        when(domainDao.findById(42L)).thenReturn(target);
        doThrow(new PermissionDeniedException("nope"))
                .when(accountMgr).checkAccess(eq(caller), any(Domain.class));

        try {
            service.searchForDomainsInternal(cmd);
            fail("expected PermissionDeniedException");
        } catch (PermissionDeniedException expected) {
            // expected
        }
        verify(accountMgr).checkAccess(eq(caller), any(Domain.class));
        verify(domainDao, never()).searchAndCount(any(SearchCriteria.class), any(Filter.class));
    }

    @Test
    public void searchForDomainsInternalAppliesIdFilterWhenIdSetAndNotRecursive() {
        ListDomainsCmd cmd = cmdWithDefaults();
        when(cmd.getId()).thenReturn(42L);
        // listAll defaults to false → isRecursive stays false → id filter, not path
        DomainVO target = mock(DomainVO.class);
        when(domainDao.findById(42L)).thenReturn(target);
        stubEmptyDomainSearch();

        service.searchForDomainsInternal(cmd);

        verify(domainSearchCriteria).setParameters(eq("id"), eq(42L));
        verify(domainSearchCriteria, never()).setParameters(eq("path"), any());
    }

    // ---- Caller-domain defaulting for non-admins ----

    @Test
    public void searchForDomainsInternalDefaultsToCallerDomainForNonAdmin() {
        ListDomainsCmd cmd = cmdWithDefaults();
        when(caller.getType()).thenReturn(Account.Type.NORMAL);
        // id null + non-admin → domainId = caller.getDomainId(); listAll false → not recursive
        stubEmptyDomainSearch();

        service.searchForDomainsInternal(cmd);

        verify(domainSearchCriteria).setParameters(eq("id"), eq(CALLER_DOMAIN_ID));
        verify(domainDao, never()).findById(anyLong()); // id-branch not entered
    }

    @Test
    public void searchForDomainsInternalDoesNotConstrainAdminWhenNoIdGiven() {
        ListDomainsCmd cmd = cmdWithDefaults();
        when(caller.getType()).thenReturn(Account.Type.ADMIN);
        stubEmptyDomainSearch();

        service.searchForDomainsInternal(cmd);

        // No id parameter and no path parameter when admin asks unscoped
        verify(domainSearchCriteria, never()).setParameters(eq("id"), any());
        verify(domainSearchCriteria, never()).setParameters(eq("path"), any());
    }

    // ---- Recursive (listAll) path-LIKE ----

    @Test
    public void searchForDomainsInternalUsesPathLikeWhenRecursiveAndNonAdminListAll() {
        ListDomainsCmd cmd = cmdWithDefaults();
        when(cmd.listAll()).thenReturn(true);
        when(caller.getType()).thenReturn(Account.Type.NORMAL);

        DomainVO callerDomain = mock(DomainVO.class);
        when(callerDomain.getPath()).thenReturn("/acme/eng/");
        when(domainDao.findById(CALLER_DOMAIN_ID)).thenReturn(callerDomain);
        stubEmptyDomainSearch();

        service.searchForDomainsInternal(cmd);

        verify(domainSearchCriteria).setParameters(eq("path"), eq("/acme/eng/%"));
        verify(domainSearchCriteria, never()).setParameters(eq("id"), eq(CALLER_DOMAIN_ID));
    }

    @Test
    public void searchForDomainsInternalReusesAlreadyResolvedDomainForRecursivePathLike() {
        // Original code special-cases the recursive branch to refind the domain
        // when it's null (i.e. the id-branch was skipped). When the id-branch
        // is taken, the recursive flag stays false. Make sure we don't refetch.
        ListDomainsCmd cmd = cmdWithDefaults();
        when(cmd.getId()).thenReturn(42L);
        when(cmd.listAll()).thenReturn(true); // ignored — id-branch takes over
        DomainVO target = mock(DomainVO.class);
        when(domainDao.findById(42L)).thenReturn(target);
        stubEmptyDomainSearch();

        service.searchForDomainsInternal(cmd);

        verify(domainDao).findById(42L); // exactly once
        verify(domainSearchCriteria).setParameters(eq("id"), eq(42L));
        verify(domainSearchCriteria, never()).setParameters(eq("path"), any());
    }

    // ---- Plain filter pass-through ----

    @Test
    public void searchForDomainsInternalAppliesNameLevelFilters() {
        ListDomainsCmd cmd = cmdWithDefaults();
        when(cmd.getDomainName()).thenReturn("acme");
        when(cmd.getLevel()).thenReturn(2);
        stubEmptyDomainSearch();

        service.searchForDomainsInternal(cmd);

        verify(domainSearchCriteria).setParameters(eq("name"), eq("acme"));
        verify(domainSearchCriteria).setParameters(eq("level"), eq(2));
    }

    @Test
    public void searchForDomainsInternalAppliesKeywordLikeFilter() {
        ListDomainsCmd cmd = cmdWithDefaults();
        when(cmd.getKeyword()).thenReturn("dev");
        stubEmptyDomainSearch();

        service.searchForDomainsInternal(cmd);

        verify(domainSearchCriteria).setParameters(eq("keywordName"), eq("%dev%"));
    }

    // ---- State guard always set ----

    @Test
    public void searchForDomainsInternalAlwaysRestrictsToActiveDomains() {
        ListDomainsCmd cmd = cmdWithDefaults();
        stubEmptyDomainSearch();

        service.searchForDomainsInternal(cmd);

        verify(domainSearchCriteria).setParameters(eq("state"), eq(Domain.State.Active));
    }

    // ---- Distinct id selection ----

    @Test
    public void searchForDomainsInternalSelectsDistinctIds() {
        ListDomainsCmd cmd = cmdWithDefaults();
        stubEmptyDomainSearch();

        service.searchForDomainsInternal(cmd);

        verify(domainSearchBuilder, atLeastOnce()).select(eq(null),
                eq(SearchCriteria.Func.DISTINCT), any());
    }

    // ---- Tag join wiring ----

    @Test
    public void searchForDomainsInternalAppliesTagJoinParametersWhenTagsPresent() {
        ListDomainsCmd cmd = cmdWithDefaults();
        Map<String, String> tags = new HashMap<>();
        tags.put("env", "prod");
        when(cmd.getTags()).thenReturn(tags);
        // Tag-search builder chain — only walked when tags are present.
        when(resourceTagDao.createSearchBuilder()).thenReturn(tagSearchBuilder);
        when(tagSearchBuilder.entity()).thenReturn(mock(ResourceTagVO.class));
        when(tagSearchBuilder.and()).thenReturn(tagSearchBuilder);
        stubEmptyDomainSearch();

        service.searchForDomainsInternal(cmd);

        verify(domainSearchCriteria).setJoinParameters(eq("tags"), eq("resourceType"), eq(ResourceObjectType.Domain));
        verify(domainSearchCriteria).setJoinParameters(eq("tags"), eq("tagKey0"), eq("env"));
        verify(domainSearchCriteria).setJoinParameters(eq("tags"), eq("tagValue0"), eq("prod"));
    }

    @Test
    public void searchForDomainsInternalSkipsTagJoinWhenNoTags() {
        ListDomainsCmd cmd = cmdWithDefaults();
        // tags map is empty by default
        stubEmptyDomainSearch();

        service.searchForDomainsInternal(cmd);

        verify(domainSearchCriteria, never()).setJoinParameters(eq("tags"), eq("resourceType"), any());
    }

    // ---- Empty-result short-circuit ----

    @Test
    public void searchForDomainsInternalReturnsEmptyWhenCountIsZero() {
        ListDomainsCmd cmd = cmdWithDefaults();
        when(domainDao.searchAndCount(eq(domainSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        Pair<List<DomainJoinVO>, Integer> result = service.searchForDomainsInternal(cmd);

        assertNotNull(result);
        assertEquals(Integer.valueOf(0), result.second());
        assertTrue(result.first().isEmpty());
        // hydration must NOT be invoked when count is zero
        verify(domainJoinDao, never()).searchByIds(any(Long[].class));
    }

    // ---- Hydration: ids -> DomainJoinVO ----

    @Test
    public void searchForDomainsInternalHydratesJoinRowsFromIds() {
        ListDomainsCmd cmd = cmdWithDefaults();
        DomainVO d1 = mock(DomainVO.class);
        when(d1.getId()).thenReturn(101L);
        DomainVO d2 = mock(DomainVO.class);
        when(d2.getId()).thenReturn(102L);
        when(domainDao.searchAndCount(eq(domainSearchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(java.util.Arrays.asList(d1, d2), 2));
        DomainJoinVO row1 = mock(DomainJoinVO.class);
        DomainJoinVO row2 = mock(DomainJoinVO.class);
        when(domainJoinDao.searchByIds(any(Long[].class)))
                .thenReturn(java.util.Arrays.asList(row1, row2));

        Pair<List<DomainJoinVO>, Integer> result = service.searchForDomainsInternal(cmd);

        assertEquals(Integer.valueOf(2), result.second());
        assertEquals(2, result.first().size());
        assertSame(row1, result.first().get(0));
        assertSame(row2, result.first().get(1));
        verify(domainJoinDao).searchByIds(any(Long[].class));
    }
}
