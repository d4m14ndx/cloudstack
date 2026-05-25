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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.cloudstack.affinity.AffinityGroupDomainMapVO;
import org.apache.cloudstack.affinity.AffinityGroupVMMapVO;
import org.apache.cloudstack.affinity.dao.AffinityGroupDomainMapDao;
import org.apache.cloudstack.affinity.dao.AffinityGroupVMMapDao;
import org.apache.cloudstack.api.command.user.affinitygroup.ListAffinityGroupsCmd;
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

import com.cloud.api.query.dao.AffinityGroupJoinDao;
import com.cloud.api.query.vo.AffinityGroupJoinVO;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.DomainManager;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class AffinityGroupQueryServiceImplTest {

    @Mock private AccountManager accountMgr;
    @Mock private AccountDao accountDao;
    @Mock private DomainDao domainDao;
    @Mock private DomainManager domainMgr;
    @Mock private UserVmDao userVmDao;
    @Mock private AffinityGroupJoinDao affinityGroupJoinDao;
    @Mock private AffinityGroupVMMapDao affinityGroupVMMapDao;
    @Mock private AffinityGroupDomainMapDao affinityGroupDomainMapDao;

    @Mock private SearchBuilder<AffinityGroupJoinVO> searchBuilder;
    @Mock private SearchCriteria<AffinityGroupJoinVO> searchCriteria;
    @Mock private SearchCriteria<AffinityGroupJoinVO> domainSearchCriteria;
    @Mock private AffinityGroupJoinVO entityProxy;
    @Mock private Account callingAccount;

    @InjectMocks
    private AffinityGroupQueryServiceImpl service;

    private MockedStatic<CallContext> callContextStatic;

    @Before
    public void setUp() {
        // Stub the search builder so any internal builder construction succeeds.
        when(affinityGroupJoinDao.createSearchBuilder()).thenReturn(searchBuilder);
        when(searchBuilder.entity()).thenReturn(entityProxy);
        when(searchBuilder.create()).thenReturn(searchCriteria);

        // CallContext static — return calling account.
        CallContext ctx = mock(CallContext.class);
        when(ctx.getCallingAccount()).thenReturn(callingAccount);
        callContextStatic = Mockito.mockStatic(CallContext.class);
        callContextStatic.when(CallContext::current).thenReturn(ctx);
    }

    @After
    public void tearDown() {
        callContextStatic.close();
    }

    // --- Helper factory ---

    private ListAffinityGroupsCmd cmdWithDefaults() {
        ListAffinityGroupsCmd cmd = mock(ListAffinityGroupsCmd.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getAffinityGroupName()).thenReturn(null);
        when(cmd.getAffinityGroupType()).thenReturn(null);
        when(cmd.getVirtualMachineId()).thenReturn(null);
        when(cmd.getAccountName()).thenReturn(null);
        when(cmd.getDomainId()).thenReturn(null);
        when(cmd.getProjectId()).thenReturn(null);
        when(cmd.isRecursive()).thenReturn(false);
        when(cmd.listAll()).thenReturn(false);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(50L);
        when(cmd.getKeyword()).thenReturn(null);
        return cmd;
    }

    // ---- searchForAffinityGroupsInternal: vmId branch ----

    @Test
    public void searchForAffinityGroupsInternalByVmIdThrowsWhenVmMissing() {
        ListAffinityGroupsCmd cmd = cmdWithDefaults();
        when(cmd.getVirtualMachineId()).thenReturn(99L);
        when(userVmDao.findById(99L)).thenReturn(null);

        try {
            service.searchForAffinityGroupsInternal(cmd);
            fail("expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            assertTrue(expected.getMessage().contains("99"));
        }
        verify(accountMgr, never()).checkAccess(any(), any(), anyBoolean(), any(UserVmVO.class));
    }

    @Test
    public void searchForAffinityGroupsInternalByVmIdChecksAccessAndDelegatesToListByVm() {
        ListAffinityGroupsCmd cmd = cmdWithDefaults();
        when(cmd.getVirtualMachineId()).thenReturn(7L);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(20L);
        UserVmVO vm = mock(UserVmVO.class);
        when(userVmDao.findById(7L)).thenReturn(vm);
        when(affinityGroupVMMapDao.listByInstanceId(eq(7L), any()))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        Pair<List<AffinityGroupJoinVO>, Integer> result = service.searchForAffinityGroupsInternal(cmd);

        assertNotNull(result);
        assertEquals(Integer.valueOf(0), result.second());
        assertTrue(result.first().isEmpty());
        verify(accountMgr).checkAccess(eq(callingAccount), isNull(), eq(true), eq(vm));
    }

    @Test
    public void searchForAffinityGroupsInternalByVmIdPropagatesPermissionDenied() {
        ListAffinityGroupsCmd cmd = cmdWithDefaults();
        when(cmd.getVirtualMachineId()).thenReturn(3L);
        UserVmVO vm = mock(UserVmVO.class);
        when(userVmDao.findById(3L)).thenReturn(vm);
        Mockito.doThrow(new PermissionDeniedException("no"))
                .when(accountMgr).checkAccess(eq(callingAccount), isNull(), eq(true), eq(vm));

        try {
            service.searchForAffinityGroupsInternal(cmd);
            fail("expected PermissionDeniedException");
        } catch (PermissionDeniedException expected) {
            // ok
        }
        verify(affinityGroupVMMapDao, never()).listByInstanceId(anyLong(), any());
    }

    // ---- searchForAffinityGroupsInternal: filter pass-through to SearchCriteria ----

    @Test
    public void searchForAffinityGroupsInternalAppliesIdNameTypeAndKeywordFilters() {
        ListAffinityGroupsCmd cmd = cmdWithDefaults();
        when(cmd.getId()).thenReturn(42L);
        when(cmd.getAffinityGroupName()).thenReturn("my-ag");
        when(cmd.getAffinityGroupType()).thenReturn("host anti-affinity");
        when(cmd.getKeyword()).thenReturn("rabbit");
        when(affinityGroupJoinDao.searchAndCount(eq(searchCriteria), any()))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));
        when(affinityGroupJoinDao.createSearchCriteria()).thenReturn(domainSearchCriteria);

        service.searchForAffinityGroupsInternal(cmd);

        verify(searchCriteria).addAnd(eq("id"), eq(SearchCriteria.Op.EQ), eq(42L));
        verify(searchCriteria).addAnd(eq("name"), eq(SearchCriteria.Op.EQ), eq("my-ag"));
        verify(searchCriteria).addAnd(eq("type"), eq(SearchCriteria.Op.EQ), eq("host anti-affinity"));
        // keyword adds a nested SC to "name"
        verify(searchCriteria).addAnd(eq("name"), eq(SearchCriteria.Op.SC), eq(domainSearchCriteria));
        verify(domainSearchCriteria).addOr(eq("name"), eq(SearchCriteria.Op.LIKE), eq("%rabbit%"));
        verify(domainSearchCriteria).addOr(eq("type"), eq(SearchCriteria.Op.LIKE), eq("%rabbit%"));
    }

    @Test
    public void searchForAffinityGroupsInternalReturnsDaoSearchAndCountResult() {
        ListAffinityGroupsCmd cmd = cmdWithDefaults();
        AffinityGroupJoinVO row = mock(AffinityGroupJoinVO.class);
        when(row.getId()).thenReturn(11L);
        AffinityGroupJoinVO loaded = mock(AffinityGroupJoinVO.class);
        when(affinityGroupJoinDao.searchAndCount(eq(searchCriteria), any()))
                .thenReturn(new Pair<>(Collections.singletonList(row), 1));
        when(affinityGroupJoinDao.searchByIds(any(Long[].class)))
                .thenReturn(Collections.singletonList(loaded));

        Pair<List<AffinityGroupJoinVO>, Integer> result = service.searchForAffinityGroupsInternal(cmd);

        assertEquals(Integer.valueOf(1), result.second());
        assertSame(loaded, result.first().get(0));
        verify(affinityGroupJoinDao).searchByIds(any(Long[].class));
    }

    @Test
    public void searchForAffinityGroupsInternalSkipsSearchByIdsWhenCountIsZero() {
        ListAffinityGroupsCmd cmd = cmdWithDefaults();
        when(affinityGroupJoinDao.searchAndCount(eq(searchCriteria), any()))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        Pair<List<AffinityGroupJoinVO>, Integer> result = service.searchForAffinityGroupsInternal(cmd);

        assertEquals(Integer.valueOf(0), result.second());
        assertTrue(result.first().isEmpty());
        verify(affinityGroupJoinDao, never()).searchByIds(any(Long[].class));
    }

    // ---- searchForAffinityGroupsInternal: ACL parameter routing ----

    @Test
    public void searchForAffinityGroupsInternalCallsAccountManagerForAclSetup() {
        ListAffinityGroupsCmd cmd = cmdWithDefaults();
        when(cmd.getId()).thenReturn(5L);
        when(cmd.getAccountName()).thenReturn("alice");
        when(cmd.getProjectId()).thenReturn(99L);
        when(cmd.listAll()).thenReturn(true);
        when(affinityGroupJoinDao.searchAndCount(eq(searchCriteria), any()))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForAffinityGroupsInternal(cmd);

        verify(accountMgr).buildACLSearchParameters(eq(callingAccount), eq(5L), eq("alice"), eq(99L),
                anyList(), any(Ternary.class), eq(true), eq(false));
    }

    // ---- searchForAffinityGroupsInternal: domain-level path ----

    @Test
    public void searchForAffinityGroupsInternalLayersInDomainLevelForRecursiveDomainAdmin() {
        ListAffinityGroupsCmd cmd = cmdWithDefaults();
        when(cmd.getDomainId()).thenReturn(13L);
        when(cmd.isRecursive()).thenReturn(true);
        // Mutate the Ternary so that domainId stays set and isRecursive stays true.
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Ternary<Long, Boolean, ListProjectResourcesCriteria> t = (Ternary<Long, Boolean, ListProjectResourcesCriteria>) inv.getArgument(5);
            t.first(13L);
            t.second(true);
            t.third(null);
            return null;
        }).when(accountMgr).buildACLSearchParameters(any(), any(), any(), any(),
                anyList(), any(Ternary.class), anyBoolean(), anyBoolean());

        when(affinityGroupJoinDao.searchAndCount(eq(searchCriteria), any()))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));
        // domainDao.findById is consulted during recursive domain-path setup.
        DomainVO domain = mock(DomainVO.class);
        when(domain.getPath()).thenReturn("/root/");
        when(domainDao.findById(13L)).thenReturn(domain);
        // For domain-level path, an empty mapping list collapses to empty result.
        Set<Long> parents = new HashSet<>(Arrays.asList(13L));
        when(domainMgr.getDomainParentIds(13L)).thenReturn(parents);
        when(affinityGroupDomainMapDao.listByDomain(any(Object[].class)))
                .thenReturn(Collections.emptyList());

        Pair<List<AffinityGroupJoinVO>, Integer> result = service.searchForAffinityGroupsInternal(cmd);

        assertEquals(Integer.valueOf(0), result.second());
        verify(domainMgr).getDomainParentIds(13L);
        verify(affinityGroupDomainMapDao).listByDomain(any(Object[].class));
    }

    @Test
    public void searchForAffinityGroupsInternalLayersInDomainLevelPerPermittedAccount() {
        ListAffinityGroupsCmd cmd = cmdWithDefaults();
        // No domainId set, but permittedAccounts will have entries.
        doAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<Long> permitted = (List<Long>) inv.getArgument(4);
            permitted.add(101L);
            @SuppressWarnings("unchecked")
            Ternary<Long, Boolean, ListProjectResourcesCriteria> t = (Ternary<Long, Boolean, ListProjectResourcesCriteria>) inv.getArgument(5);
            t.first(null);
            t.second(false);
            t.third(null);
            return null;
        }).when(accountMgr).buildACLSearchParameters(any(), any(), any(), any(),
                anyList(), any(Ternary.class), anyBoolean(), anyBoolean());

        when(affinityGroupJoinDao.searchAndCount(eq(searchCriteria), any()))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));
        AccountVO permittedAcct = mock(AccountVO.class);
        when(permittedAcct.getDomainId()).thenReturn(202L);
        when(accountDao.findById(101L)).thenReturn(permittedAcct);
        when(domainMgr.getDomainParentIds(202L)).thenReturn(new HashSet<>(Arrays.asList(202L)));
        when(affinityGroupDomainMapDao.listByDomain(any(Object[].class)))
                .thenReturn(Collections.emptyList());

        service.searchForAffinityGroupsInternal(cmd);

        verify(accountDao).findById(101L);
        verify(domainMgr).getDomainParentIds(202L);
    }

    // ---- buildAffinityGroupViewSearchCriteria: parameter routing ----

    @Test
    public void buildAffinityGroupViewSearchCriteriaRoutesPermittedAccountsIntoInClause() {
        List<Long> permitted = Arrays.asList(1L, 2L, 3L);
        service.buildAffinityGroupViewSearchCriteria(searchCriteria, null, false, permitted, null);

        verify(searchCriteria).setParameters(eq("accountIdIN"), eq(permitted.toArray()));
    }

    @Test
    public void buildAffinityGroupViewSearchCriteriaRoutesDomainPathForRecursive() {
        DomainVO domain = mock(DomainVO.class);
        when(domain.getPath()).thenReturn("/root/sub/");
        when(domainDao.findById(5L)).thenReturn(domain);

        service.buildAffinityGroupViewSearchCriteria(searchCriteria, 5L, true, new ArrayList<>(), null);

        verify(searchCriteria).setParameters(eq("domainPath"), eq("/root/sub/%"));
    }

    @Test
    public void buildAffinityGroupViewSearchCriteriaRoutesDomainIdForNonRecursive() {
        when(domainDao.findById(5L)).thenReturn(mock(DomainVO.class));

        service.buildAffinityGroupViewSearchCriteria(searchCriteria, 5L, false, new ArrayList<>(), null);

        verify(searchCriteria).setParameters(eq("domainId"), eq(5L));
    }

    @Test
    public void buildAffinityGroupViewSearchCriteriaTagsProjectAccountTypeOnlyWhenProjectCriteriaSet() {
        service.buildAffinityGroupViewSearchCriteria(searchCriteria, null, false, new ArrayList<>(),
                ListProjectResourcesCriteria.ListProjectResourcesOnly);

        verify(searchCriteria).setParameters(eq("accountType"), eq(Account.Type.PROJECT));
    }

    @Test
    public void buildAffinityGroupViewSearchCriteriaSkipsAccountTypeWhenNullCriteria() {
        service.buildAffinityGroupViewSearchCriteria(searchCriteria, null, false, new ArrayList<>(), null);

        verify(searchCriteria, never()).setParameters(eq("accountType"), any());
    }

    // ---- listAffinityGroupsByVM: empty + populated paths ----

    @Test
    public void listAffinityGroupsByVMReturnsEmptyWhenNoMappings() {
        when(affinityGroupVMMapDao.listByInstanceId(eq(8L), any()))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        Pair<List<AffinityGroupJoinVO>, Integer> result = service.listAffinityGroupsByVM(8L, 0L, 50L);

        assertEquals(Integer.valueOf(0), result.second());
        assertTrue(result.first().isEmpty());
        verify(affinityGroupJoinDao, never()).searchByIds(any(Long[].class));
    }

    @Test
    public void listAffinityGroupsByVMReturnsLoadedAgsWhenMappingsPresent() {
        AffinityGroupVMMapVO map1 = mock(AffinityGroupVMMapVO.class);
        when(map1.getAffinityGroupId()).thenReturn(100L);
        AffinityGroupVMMapVO map2 = mock(AffinityGroupVMMapVO.class);
        when(map2.getAffinityGroupId()).thenReturn(101L);
        when(affinityGroupVMMapDao.listByInstanceId(eq(8L), any()))
                .thenReturn(new Pair<>(Arrays.asList(map1, map2), 2));
        List<AffinityGroupJoinVO> loaded = Arrays.asList(mock(AffinityGroupJoinVO.class), mock(AffinityGroupJoinVO.class));
        when(affinityGroupJoinDao.searchByIds(any(Long[].class))).thenReturn(loaded);

        Pair<List<AffinityGroupJoinVO>, Integer> result = service.listAffinityGroupsByVM(8L, 0L, 50L);

        assertEquals(Integer.valueOf(2), result.second());
        assertEquals(loaded, result.first());
    }

    // ---- listDomainLevelAffinityGroups: empty + populated paths ----

    @Test
    public void listDomainLevelAffinityGroupsReturnsEmptyWhenNoVisibleGroups() {
        when(domainMgr.getDomainParentIds(7L)).thenReturn(new HashSet<>(Arrays.asList(7L)));
        when(affinityGroupDomainMapDao.listByDomain(any(Object[].class)))
                .thenReturn(Collections.emptyList());

        Pair<List<AffinityGroupJoinVO>, Integer> result = service.listDomainLevelAffinityGroups(
                searchCriteria, null, 7L);

        assertEquals(Integer.valueOf(0), result.second());
        assertTrue(result.first().isEmpty());
    }

    @Test
    public void listDomainLevelAffinityGroupsFiltersByDomainAndSubdomainAccess() {
        // own-domain visible, foreign-domain w/ subdomainAccess true visible, foreign w/o false hidden
        AffinityGroupDomainMapVO own = mock(AffinityGroupDomainMapVO.class);
        when(own.getDomainId()).thenReturn(7L);
        when(own.isSubdomainAccess()).thenReturn(false);
        when(own.getAffinityGroupId()).thenReturn(11L);

        AffinityGroupDomainMapVO foreignWithAccess = mock(AffinityGroupDomainMapVO.class);
        when(foreignWithAccess.getDomainId()).thenReturn(8L);
        when(foreignWithAccess.isSubdomainAccess()).thenReturn(true);
        when(foreignWithAccess.getAffinityGroupId()).thenReturn(12L);

        AffinityGroupDomainMapVO foreignNoAccess = mock(AffinityGroupDomainMapVO.class);
        when(foreignNoAccess.getDomainId()).thenReturn(9L);
        when(foreignNoAccess.isSubdomainAccess()).thenReturn(false);
        // foreignNoAccess.getAffinityGroupId() intentionally unstubbed —
        // filter logic must drop this entry before reading the id.

        when(domainMgr.getDomainParentIds(7L)).thenReturn(new HashSet<>(Arrays.asList(7L, 8L, 9L)));
        when(affinityGroupDomainMapDao.listByDomain(any(Object[].class)))
                .thenReturn(Arrays.asList(own, foreignWithAccess, foreignNoAccess));

        when(affinityGroupJoinDao.createSearchCriteria()).thenReturn(domainSearchCriteria);
        when(affinityGroupJoinDao.searchAndCount(eq(searchCriteria), any()))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        Pair<List<AffinityGroupJoinVO>, Integer> result = service.listDomainLevelAffinityGroups(
                searchCriteria, null, 7L);

        // The collapsed empty branch returns 0.
        assertEquals(Integer.valueOf(0), result.second());
        // Verify only the two visible group ids were rolled into the IN clause.
        verify(domainSearchCriteria).addAnd(eq("id"), eq(SearchCriteria.Op.IN), any(Object[].class));
        verify(affinityGroupJoinDao).createSearchCriteria();
    }

    @Test
    public void listDomainLevelAffinityGroupsLoadsByIdsWhenSearchReturnsRows() {
        AffinityGroupDomainMapVO own = mock(AffinityGroupDomainMapVO.class);
        when(own.getDomainId()).thenReturn(7L);
        when(own.isSubdomainAccess()).thenReturn(false);
        when(own.getAffinityGroupId()).thenReturn(11L);
        when(domainMgr.getDomainParentIds(7L)).thenReturn(new HashSet<>(Arrays.asList(7L)));
        when(affinityGroupDomainMapDao.listByDomain(any(Object[].class)))
                .thenReturn(Collections.singletonList(own));
        when(affinityGroupJoinDao.createSearchCriteria()).thenReturn(domainSearchCriteria);
        AffinityGroupJoinVO row = mock(AffinityGroupJoinVO.class);
        when(row.getId()).thenReturn(11L);
        when(affinityGroupJoinDao.searchAndCount(eq(searchCriteria), any()))
                .thenReturn(new Pair<>(Collections.singletonList(row), 1));
        AffinityGroupJoinVO loaded = mock(AffinityGroupJoinVO.class);
        when(affinityGroupJoinDao.searchByIds(any(Long[].class)))
                .thenReturn(Collections.singletonList(loaded));

        Pair<List<AffinityGroupJoinVO>, Integer> result = service.listDomainLevelAffinityGroups(
                searchCriteria, null, 7L);

        assertEquals(Integer.valueOf(1), result.second());
        assertSame(loaded, result.first().get(0));
    }

    // ---- buildAffinityGroupSearchCriteria: distinct selection + filter routing ----

    @Test
    public void buildAffinityGroupSearchCriteriaSelectsDistinctIdsAndAppliesFilters() {
        SearchCriteria<AffinityGroupJoinVO> result = service.buildAffinityGroupSearchCriteria(
                null, false, new ArrayList<>(), null, 9L, "ag9", "host-affinity", null);

        assertSame(searchCriteria, result);
        // Distinct id selection happens once during builder construction.
        verify(searchBuilder, atLeastOnce()).select(isNull(), eq(SearchCriteria.Func.DISTINCT), any());
        verify(searchCriteria).addAnd(eq("id"), eq(SearchCriteria.Op.EQ), eq(9L));
        verify(searchCriteria).addAnd(eq("name"), eq(SearchCriteria.Op.EQ), eq("ag9"));
        verify(searchCriteria).addAnd(eq("type"), eq(SearchCriteria.Op.EQ), eq("host-affinity"));
    }
}
