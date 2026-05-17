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
package com.cloud.user;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.vo.ControlledViewEntity;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.projects.Project;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.projects.ProjectManager;
import com.cloud.projects.ProjectVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBase;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class AclSearchBuilderServiceImplTest {

    @Mock private AccountDao accountDao;
    @Mock private DomainDao domainDao;
    @Mock private ProjectManager projectManager;
    @Mock private AccountService accountService;

    @InjectMocks
    private AclSearchBuilderServiceImpl service;

    private Account normalCaller;
    private Account domainAdminCaller;
    private Account rootAdminCaller;

    @Before
    public void setUp() {
        normalCaller = makeAccount(101L, 10L, Account.Type.NORMAL);
        domainAdminCaller = makeAccount(102L, 11L, Account.Type.DOMAIN_ADMIN);
        rootAdminCaller = makeAccount(103L, 1L, Account.Type.ADMIN);
    }

    private Account makeAccount(long id, long domainId, Account.Type type) {
        Account a = mock(Account.class);
        when(a.getId()).thenReturn(id);
        when(a.getDomainId()).thenReturn(domainId);
        when(a.getType()).thenReturn(type);
        return a;
    }

    // ---- buildACLSearchParameters: rejected inputs ----

    @Test
    public void buildACLSearchParametersUnknownDomainRejected() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(999L, false, null);
        when(domainDao.findById(999L)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.buildACLSearchParameters(rootAdminCaller, null, null, null,
                        new ArrayList<>(), dirlp, false, false));
    }

    @Test
    public void buildACLSearchParametersAccountAndProjectTogetherRejected() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(null, false, null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.buildACLSearchParameters(rootAdminCaller, null, "alice", 5L,
                        new ArrayList<>(), dirlp, false, false));
    }

    @Test
    public void buildACLSearchParametersAccountNameUnknownRejected() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(null, false, null);
        when(accountDao.findActiveAccount("alice", normalCaller.getDomainId())).thenReturn(null);
        when(domainDao.findById(normalCaller.getDomainId())).thenReturn(new DomainVO());

        assertThrows(InvalidParameterValueException.class,
                () -> service.buildACLSearchParameters(normalCaller, null, "alice", null,
                        new ArrayList<>(), dirlp, false, false));
    }

    @Test
    public void buildACLSearchParametersUnknownProjectRejected() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(null, false, null);
        when(projectManager.getProject(7L)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.buildACLSearchParameters(rootAdminCaller, null, null, 7L,
                        new ArrayList<>(), dirlp, false, false));
    }

    @Test
    public void buildACLSearchParametersProjectNoAccessRejected() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(null, false, null);
        ProjectVO project = mock(ProjectVO.class);
        when(project.getProjectAccountId()).thenReturn(202L);
        when(projectManager.getProject(7L)).thenReturn(project);
        when(projectManager.canAccessProjectAccount(rootAdminCaller, 202L)).thenReturn(false);

        assertThrows(PermissionDeniedException.class,
                () -> service.buildACLSearchParameters(rootAdminCaller, null, null, 7L,
                        new ArrayList<>(), dirlp, false, false));
    }

    // ---- buildACLSearchParameters: accountName resolves and adds to permittedAccounts ----

    @Test
    public void buildACLSearchParametersResolvesAccountNameAndChecksAccess() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(null, false, null);
        Account resolved = makeAccount(555L, 11L, Account.Type.NORMAL);
        when(accountDao.findActiveAccount("alice", normalCaller.getDomainId())).thenReturn(resolved);
        when(domainDao.findById(normalCaller.getDomainId())).thenReturn(new DomainVO());

        List<Long> permitted = new ArrayList<>();
        service.buildACLSearchParameters(normalCaller, null, "alice", null, permitted,
                dirlp, false, false);

        assertEquals(Collections.singletonList(555L), permitted);
        verify(accountService, times(1)).checkAccess(eq(normalCaller), any(), eq(false),
                eq(resolved));
    }

    @Test
    public void buildACLSearchParametersChecksDomainAccessWhenDomainSupplied() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(22L, false, null);
        DomainVO dom = new DomainVO();
        when(domainDao.findById(22L)).thenReturn(dom);

        service.buildACLSearchParameters(rootAdminCaller, null, null, null, new ArrayList<>(),
                dirlp, true, false);

        verify(accountService).checkAccess(eq(rootAdminCaller), any(Domain.class));
    }

    // ---- buildACLSearchParameters: projectId == -1 paths ----

    @Test
    public void buildACLSearchParametersProjectMinusOneForAdminListsAllProjectResourcesOnly() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(null, false, null);

        List<Long> permitted = new ArrayList<>();
        service.buildACLSearchParameters(rootAdminCaller, null, null, -1L, permitted, dirlp,
                false, false);

        assertTrue("admin should not get any per-account narrowing for project -1",
                permitted.isEmpty());
        assertEquals(ListProjectResourcesCriteria.ListProjectResourcesOnly, dirlp.third());
        verify(projectManager, never()).listPermittedProjectAccounts(rootAdminCaller.getId());
    }

    @Test
    public void buildACLSearchParametersProjectMinusOneForNormalCallerWithProjectsAddsThem() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(null, false, null);
        when(projectManager.listPermittedProjectAccounts(normalCaller.getId()))
                .thenReturn(Arrays.asList(301L, 302L));

        List<Long> permitted = new ArrayList<>();
        service.buildACLSearchParameters(normalCaller, null, null, -1L, permitted, dirlp,
                false, false);

        assertEquals(Arrays.asList(301L, 302L), permitted);
        assertEquals(ListProjectResourcesCriteria.ListProjectResourcesOnly, dirlp.third());
    }

    @Test
    public void buildACLSearchParametersProjectMinusOneForNormalCallerWithNoProjectsFallsBackToCaller() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(null, false, null);
        when(projectManager.listPermittedProjectAccounts(normalCaller.getId()))
                .thenReturn(Collections.emptyList());

        List<Long> permitted = new ArrayList<>();
        service.buildACLSearchParameters(normalCaller, null, null, -1L, permitted, dirlp,
                false, false);

        assertEquals(Collections.singletonList(normalCaller.getId()), permitted);
    }

    @Test
    public void buildACLSearchParametersProjectMinusOneWithListAllSwitchesToListAllIncluding() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(null, false, null);
        when(projectManager.listPermittedProjectAccounts(domainAdminCaller.getId()))
                .thenReturn(Arrays.asList(401L));

        service.buildACLSearchParameters(domainAdminCaller, null, null, -1L, new ArrayList<>(),
                dirlp, true, false);

        assertEquals(ListProjectResourcesCriteria.ListAllIncludingProjectResources, dirlp.third());
    }

    @Test
    public void buildACLSearchParametersProjectIdResolvesToOwnerAccount() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(null, false, null);
        ProjectVO project = mock(ProjectVO.class);
        when(project.getProjectAccountId()).thenReturn(777L);
        when(projectManager.getProject(7L)).thenReturn(project);
        when(projectManager.canAccessProjectAccount(rootAdminCaller, 777L)).thenReturn(true);

        List<Long> permitted = new ArrayList<>();
        service.buildACLSearchParameters(rootAdminCaller, null, null, 7L, permitted, dirlp,
                false, false);

        assertEquals(Collections.singletonList(777L), permitted);
    }

    @Test
    public void buildACLSearchParametersForProjectInvitationDoesNotResolveProject() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(null, false, null);

        service.buildACLSearchParameters(rootAdminCaller, null, null, 7L, new ArrayList<>(),
                dirlp, false, true);

        verify(projectManager, never()).getProject(any(Long.class));
    }

    // ---- buildACLSearchParameters: caller-type narrowing when projectId is null ----

    @Test
    public void buildACLSearchParametersNoProjectIdSetsSkipProjectResources() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(null, false, null);

        service.buildACLSearchParameters(rootAdminCaller, null, null, null, new ArrayList<>(),
                dirlp, false, false);

        assertEquals(ListProjectResourcesCriteria.SkipProjectResources, dirlp.third());
    }

    @Test
    public void buildACLSearchParametersWithIdPresentDoesNotSetSkipProjectResources() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(null, false, null);

        service.buildACLSearchParameters(rootAdminCaller, 99L, null, null, new ArrayList<>(),
                dirlp, true, false);

        assertNull("with id, criteria should stay unset",
                dirlp.third());
    }

    @Test
    public void buildACLSearchParametersNormalCallerNarrowsToSelfWhenNoDomainOrAccount() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(null, false, null);
        List<Long> permitted = new ArrayList<>();

        service.buildACLSearchParameters(normalCaller, null, null, null, permitted, dirlp,
                true, false);

        assertEquals(Collections.singletonList(normalCaller.getId()), permitted);
    }

    @Test
    public void buildACLSearchParametersDomainAdminWithListAllAndNoDomainNarrowsToOwnDomainRecursive() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(null, false, null);

        service.buildACLSearchParameters(domainAdminCaller, null, null, null, new ArrayList<>(),
                dirlp, true, false);

        assertEquals(Long.valueOf(domainAdminCaller.getDomainId()), dirlp.first());
        assertTrue(dirlp.second());
    }

    @Test
    public void buildACLSearchParametersNonAdminWithIdAndNotListAllSwitchesToOwnDomainRecursive() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(null, false, null);

        service.buildACLSearchParameters(domainAdminCaller, 42L, null, null, new ArrayList<>(),
                dirlp, false, false);

        assertEquals(Long.valueOf(domainAdminCaller.getDomainId()), dirlp.first());
        assertTrue(dirlp.second());
    }

    @Test
    public void buildACLSearchParametersNormalCallerWithDomainAddsSelfToPermitted() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(22L, false, null);
        when(domainDao.findById(22L)).thenReturn(new DomainVO());

        List<Long> permitted = new ArrayList<>();
        service.buildACLSearchParameters(normalCaller, null, null, null, permitted, dirlp,
                true, false);

        assertEquals(Collections.singletonList(normalCaller.getId()), permitted);
    }

    @Test
    public void buildACLSearchParametersAdminWithoutNarrowingLeavesPermittedEmpty() {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> dirlp =
                new Ternary<>(null, false, null);
        List<Long> permitted = new ArrayList<>();

        service.buildACLSearchParameters(rootAdminCaller, null, null, null, permitted, dirlp,
                true, false);

        assertTrue(permitted.isEmpty());
    }

    // ---- buildACLSearchBuilder / buildACLSearchCriteria — controlled-entity flat path ----

    @Test
    public void buildACLSearchBuilderForGenericEntityWiresAccountAndDomain() {
        @SuppressWarnings("unchecked")
        SearchBuilder<ControlledEntityVO> sb = mock(SearchBuilder.class);
        ControlledEntityVO ent = mock(ControlledEntityVO.class);
        when(sb.entity()).thenReturn(ent);

        service.buildACLSearchBuilder(sb, null, false, new ArrayList<>(), null);

        verify(sb).and(eq("accountIdIN"), any(), eq(SearchCriteria.Op.IN));
        verify(sb).and(eq("domainId"), any(), eq(SearchCriteria.Op.EQ));
        verify(sb, never()).join(any(String.class), any(SearchBase.class), any(), any(),
                any(JoinBuilder.JoinType.class));
    }

    @Test
    public void buildACLSearchBuilderRecursiveWithDomainAddsDomainJoin() {
        @SuppressWarnings("unchecked")
        SearchBuilder<ControlledEntityVO> sb = mock(SearchBuilder.class);
        ControlledEntityVO ent = mock(ControlledEntityVO.class);
        when(sb.entity()).thenReturn(ent);

        @SuppressWarnings("unchecked")
        SearchBuilder<DomainVO> domSb = mock(SearchBuilder.class);
        when(domSb.entity()).thenReturn(new DomainVO());
        when(domainDao.createSearchBuilder()).thenReturn(domSb);

        service.buildACLSearchBuilder(sb, 22L, true, new ArrayList<>(), null);

        verify(sb).join(eq("domainSearch"), eq(domSb), any(), any(),
                eq(JoinBuilder.JoinType.INNER));
    }

    @Test
    public void buildACLSearchBuilderWithProjectCriteriaAddsAccountJoin() {
        @SuppressWarnings("unchecked")
        SearchBuilder<ControlledEntityVO> sb = mock(SearchBuilder.class);
        ControlledEntityVO ent = mock(ControlledEntityVO.class);
        when(sb.entity()).thenReturn(ent);

        @SuppressWarnings("unchecked")
        SearchBuilder<AccountVO> acctSb = mock(SearchBuilder.class);
        when(acctSb.entity()).thenReturn(new AccountVO());
        when(accountDao.createSearchBuilder()).thenReturn(acctSb);

        service.buildACLSearchBuilder(sb, null, false, new ArrayList<>(),
                Project.ListProjectResourcesCriteria.ListProjectResourcesOnly);

        verify(sb).join(eq("accountSearch"), eq(acctSb), any(), any(),
                eq(JoinBuilder.JoinType.INNER));
        verify(acctSb).and(eq("type"), any(), eq(SearchCriteria.Op.EQ));
    }

    @Test
    public void buildACLSearchBuilderSkipProjectUsesNeqOnType() {
        @SuppressWarnings("unchecked")
        SearchBuilder<ControlledEntityVO> sb = mock(SearchBuilder.class);
        ControlledEntityVO ent = mock(ControlledEntityVO.class);
        when(sb.entity()).thenReturn(ent);

        @SuppressWarnings("unchecked")
        SearchBuilder<AccountVO> acctSb = mock(SearchBuilder.class);
        when(acctSb.entity()).thenReturn(new AccountVO());
        when(accountDao.createSearchBuilder()).thenReturn(acctSb);

        service.buildACLSearchBuilder(sb, null, false, new ArrayList<>(),
                Project.ListProjectResourcesCriteria.SkipProjectResources);

        verify(acctSb).and(eq("type"), any(), eq(SearchCriteria.Op.NEQ));
    }

    @Test
    public void buildACLSearchCriteriaWithPermittedAccountsSetsAccountIdInParam() {
        @SuppressWarnings("unchecked")
        SearchCriteria<ControlledEntityVO> sc = mock(SearchCriteria.class);
        List<Long> permitted = Arrays.asList(7L, 8L);

        service.buildACLSearchCriteria(sc, 22L, true, permitted, null);

        ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
        verify(sc).setParameters(eq("accountIdIN"), captor.capture());
        Object[] values = captor.getValue();
        assertEquals(Long.valueOf(7L), values[0]);
        assertEquals(Long.valueOf(8L), values[1]);
        verify(sc, never()).setParameters(eq("domainId"), any());
        verify(sc, never()).setJoinParameters(eq("domainSearch"), any(), any());
    }

    @Test
    public void buildACLSearchCriteriaWithDomainRecursiveSetsDomainSearchPathJoinParam() {
        @SuppressWarnings("unchecked")
        SearchCriteria<ControlledEntityVO> sc = mock(SearchCriteria.class);
        DomainVO dom = new DomainVO();
        // Inject path
        org.springframework.test.util.ReflectionTestUtils.setField(dom, "path", "/ROOT/Eng/");
        when(domainDao.findById(22L)).thenReturn(dom);

        service.buildACLSearchCriteria(sc, 22L, true, new ArrayList<>(), null);

        verify(sc).setJoinParameters(eq("domainSearch"), eq("path"), eq("/ROOT/Eng/%"));
    }

    @Test
    public void buildACLSearchCriteriaWithDomainNonRecursiveSetsFlatDomainIdParam() {
        @SuppressWarnings("unchecked")
        SearchCriteria<ControlledEntityVO> sc = mock(SearchCriteria.class);
        when(domainDao.findById(22L)).thenReturn(new DomainVO());

        service.buildACLSearchCriteria(sc, 22L, false, new ArrayList<>(), null);

        verify(sc).setParameters("domainId", 22L);
    }

    @Test
    public void buildACLSearchCriteriaProjectCriteriaSetsAccountSearchType() {
        @SuppressWarnings("unchecked")
        SearchCriteria<ControlledEntityVO> sc = mock(SearchCriteria.class);

        service.buildACLSearchCriteria(sc, null, false, new ArrayList<>(),
                Project.ListProjectResourcesCriteria.ListProjectResourcesOnly);

        verify(sc).setJoinParameters("accountSearch", "type", Account.Type.PROJECT);
    }

    // ---- buildACLViewSearchBuilder / buildACLViewSearchCriteria — view-table flat columns ----

    @Test
    public void buildACLViewSearchBuilderRecursiveDomainAddsDomainPathColumn() {
        @SuppressWarnings("unchecked")
        SearchBuilder<TestViewEntity> sb = mock(SearchBuilder.class);
        TestViewEntity ent = mock(TestViewEntity.class);
        when(sb.entity()).thenReturn(ent);

        service.buildACLViewSearchBuilder(sb, 22L, true, new ArrayList<>(), null);

        verify(sb).and(eq("accountIdIN"), any(), eq(SearchCriteria.Op.IN));
        verify(sb).and(eq("domainId"), any(), eq(SearchCriteria.Op.EQ));
        verify(sb).and(eq("domainPath"), any(), eq(SearchCriteria.Op.LIKE));
    }

    @Test
    public void buildACLViewSearchBuilderNonRecursiveOmitsDomainPath() {
        @SuppressWarnings("unchecked")
        SearchBuilder<TestViewEntity> sb = mock(SearchBuilder.class);
        TestViewEntity ent = mock(TestViewEntity.class);
        when(sb.entity()).thenReturn(ent);

        service.buildACLViewSearchBuilder(sb, 22L, false, new ArrayList<>(), null);

        verify(sb, never()).and(eq("domainPath"), any(), any(SearchCriteria.Op.class));
    }

    @Test
    public void buildACLViewSearchBuilderListProjectResourcesOnlyAddsAccountTypeEq() {
        @SuppressWarnings("unchecked")
        SearchBuilder<TestViewEntity> sb = mock(SearchBuilder.class);
        TestViewEntity ent = mock(TestViewEntity.class);
        when(sb.entity()).thenReturn(ent);

        service.buildACLViewSearchBuilder(sb, null, false, new ArrayList<>(),
                Project.ListProjectResourcesCriteria.ListProjectResourcesOnly);

        verify(sb).and(eq("accountType"), any(), eq(SearchCriteria.Op.EQ));
    }

    @Test
    public void buildACLViewSearchBuilderSkipProjectResourcesAddsAccountTypeNeq() {
        @SuppressWarnings("unchecked")
        SearchBuilder<TestViewEntity> sb = mock(SearchBuilder.class);
        TestViewEntity ent = mock(TestViewEntity.class);
        when(sb.entity()).thenReturn(ent);

        service.buildACLViewSearchBuilder(sb, null, false, new ArrayList<>(),
                Project.ListProjectResourcesCriteria.SkipProjectResources);

        verify(sb).and(eq("accountType"), any(), eq(SearchCriteria.Op.NEQ));
    }

    @Test
    public void buildACLViewSearchCriteriaPermittedAccountsBindsAccountIdIn() {
        @SuppressWarnings("unchecked")
        SearchCriteria<TestViewEntity> sc = mock(SearchCriteria.class);

        service.buildACLViewSearchCriteria(sc, 22L, true, Arrays.asList(7L), null);

        verify(sc).setParameters(eq("accountIdIN"), any(Object[].class));
        verify(sc, never()).setParameters(eq("domainPath"), any());
    }

    @Test
    public void buildACLViewSearchCriteriaRecursiveBindsDomainPathLikeParam() {
        @SuppressWarnings("unchecked")
        SearchCriteria<TestViewEntity> sc = mock(SearchCriteria.class);
        DomainVO dom = new DomainVO();
        org.springframework.test.util.ReflectionTestUtils.setField(dom, "path", "/ROOT/Eng/");
        when(domainDao.findById(22L)).thenReturn(dom);

        service.buildACLViewSearchCriteria(sc, 22L, true, new ArrayList<>(), null);

        verify(sc).setParameters("domainPath", "/ROOT/Eng/%");
    }

    @Test
    public void buildACLViewSearchCriteriaNonRecursiveBindsFlatDomainId() {
        @SuppressWarnings("unchecked")
        SearchCriteria<TestViewEntity> sc = mock(SearchCriteria.class);
        when(domainDao.findById(22L)).thenReturn(new DomainVO());

        service.buildACLViewSearchCriteria(sc, 22L, false, new ArrayList<>(), null);

        verify(sc).setParameters("domainId", 22L);
    }

    @Test
    public void buildACLViewSearchCriteriaProjectCriteriaBindsAccountType() {
        @SuppressWarnings("unchecked")
        SearchCriteria<TestViewEntity> sc = mock(SearchCriteria.class);

        service.buildACLViewSearchCriteria(sc, null, false, new ArrayList<>(),
                Project.ListProjectResourcesCriteria.ListProjectResourcesOnly);

        verify(sc).setParameters("accountType", Account.Type.PROJECT);
    }

    @Test
    public void buildACLSearchBuilderNoListProjectCriteriaSkipsAccountSearchJoin() {
        @SuppressWarnings("unchecked")
        SearchBuilder<ControlledEntityVO> sb = mock(SearchBuilder.class);
        ControlledEntityVO ent = mock(ControlledEntityVO.class);
        when(sb.entity()).thenReturn(ent);

        service.buildACLSearchBuilder(sb, null, false, new ArrayList<>(), null);

        verify(accountDao, never()).createSearchBuilder();
    }

    // ---- Test fixture entity types ----

    /** Minimal ControlledEntity used to exercise the "fall through" non-IP, non-invitation branch. */
    public static abstract class ControlledEntityVO implements org.apache.cloudstack.acl.ControlledEntity {
    }

    /** Minimal ControlledViewEntity for the view-table builder/criteria tests. */
    public static abstract class TestViewEntity implements ControlledViewEntity {
    }
}
