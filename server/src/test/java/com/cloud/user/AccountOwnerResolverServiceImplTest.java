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
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.cloudstack.api.ServerApiException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectManager;
import com.cloud.projects.ProjectVO;
import com.cloud.user.dao.AccountDao;

@RunWith(MockitoJUnitRunner.class)
public class AccountOwnerResolverServiceImplTest {

    @Mock private AccountDao accountDao;
    @Mock private DomainManager domainManager;
    @Mock private ProjectManager projectManager;
    @Mock private AccountService accountService;

    @InjectMocks
    private AccountOwnerResolverServiceImpl service;

    private Account systemCaller;
    private Account adminCaller;
    private Account normalCaller;
    private DomainVO domain;

    @Before
    public void setUp() {
        systemCaller = makeAccount(Account.ACCOUNT_ID_SYSTEM, 1L, "system");
        adminCaller = makeAccount(2L, 1L, "admin");
        normalCaller = makeAccount(101L, 10L, "user");
        domain = new DomainVO();
        domain.setId(10L);
    }

    private Account makeAccount(long id, long domainId, String name) {
        AccountVO a = new AccountVO(name, domainId, null, Account.Type.NORMAL, "uuid-" + id);
        a.setId(id);
        return a;
    }

    // ---------------- finalizeOwner ----------------

    @Test
    public void finalizeOwnerSystemCallerWithoutOwnerThrows() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.finalizeOwner(systemCaller, null, null, null));
    }

    @Test
    public void finalizeOwnerAccountAndProjectTogetherThrows() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.finalizeOwner(adminCaller, "acct", 10L, 99L));
    }

    @Test
    public void finalizeOwnerProjectMissingThrows() {
        when(projectManager.getProject(99L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.finalizeOwner(adminCaller, null, null, 99L));
    }

    @Test
    public void finalizeOwnerProjectAccessDeniedThrows() {
        Project p = mock(Project.class);
        when(p.getProjectAccountId()).thenReturn(500L);
        when(projectManager.getProject(99L)).thenReturn(p);
        when(projectManager.canAccessProjectAccount(adminCaller, 500L)).thenReturn(false);
        assertThrows(PermissionDeniedException.class,
                () -> service.finalizeOwner(adminCaller, null, null, 99L));
    }

    @Test
    public void finalizeOwnerProjectReturnsProjectAccount() {
        Project p = mock(Project.class);
        when(p.getProjectAccountId()).thenReturn(500L);
        when(projectManager.getProject(99L)).thenReturn(p);
        when(projectManager.canAccessProjectAccount(adminCaller, 500L)).thenReturn(true);
        Account projectAcct = makeAccount(500L, 1L, "proj");
        when(accountService.getAccount(500L)).thenReturn(projectAcct);

        Account result = service.finalizeOwner(adminCaller, null, null, 99L);
        assertSame(projectAcct, result);
    }

    @Test
    public void finalizeOwnerAdminMissingDomainThrows() {
        when(accountService.isAdmin(adminCaller.getId())).thenReturn(true);
        when(domainManager.getDomain(10L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.finalizeOwner(adminCaller, "target", 10L, null));
    }

    @Test
    public void finalizeOwnerAdminMissingAccountThrows() {
        when(accountService.isAdmin(adminCaller.getId())).thenReturn(true);
        when(domainManager.getDomain(10L)).thenReturn(domain);
        when(accountDao.findActiveAccount("target", 10L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.finalizeOwner(adminCaller, "target", 10L, null));
    }

    @Test
    public void finalizeOwnerAdminReturnsResolvedAccount() {
        when(accountService.isAdmin(adminCaller.getId())).thenReturn(true);
        when(domainManager.getDomain(10L)).thenReturn(domain);
        AccountVO target = (AccountVO) makeAccount(401L, 10L, "target");
        when(accountDao.findActiveAccount("target", 10L)).thenReturn(target);

        Account result = service.finalizeOwner(adminCaller, "target", 10L, null);
        assertSame(target, result);
        verify(accountService).checkAccess(eq(adminCaller), eq((Domain) domain));
    }

    @Test
    public void finalizeOwnerNonAdminForeignAccountDenied() {
        when(accountService.isAdmin(normalCaller.getId())).thenReturn(false);
        assertThrows(PermissionDeniedException.class,
                () -> service.finalizeOwner(normalCaller, "someoneElse", 10L, null));
    }

    @Test
    public void finalizeOwnerNonAdminOwnAccountReturnsCaller() {
        when(accountService.isAdmin(normalCaller.getId())).thenReturn(false);
        Account result = service.finalizeOwner(normalCaller, normalCaller.getAccountName(), 10L, null);
        assertSame(normalCaller, result);
    }

    @Test
    public void finalizeOwnerAccountWithoutDomainThrows() {
        when(accountService.isAdmin(normalCaller.getId())).thenReturn(false);
        assertThrows(InvalidParameterValueException.class,
                () -> service.finalizeOwner(normalCaller, "name", null, null));
    }

    @Test
    public void finalizeOwnerNoOwnerFallsBackToCaller() {
        when(accountService.isAdmin(normalCaller.getId())).thenReturn(false);
        Account result = service.finalizeOwner(normalCaller, null, null, null);
        assertSame(normalCaller, result);
    }

    // ---------------- finalizeAccountId(String,Long,Long,boolean) ----------------

    @Test
    public void finalizeAccountIdLegacyMissingDomainThrows() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.finalizeAccountId("name", null, null, false));
    }

    @Test
    public void finalizeAccountIdLegacyDomainNotFoundThrows() {
        when(domainManager.getDomain(10L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.finalizeAccountId("name", 10L, null, false));
    }

    @Test
    public void finalizeAccountIdLegacyResolvesActiveAccount() {
        when(domainManager.getDomain(10L)).thenReturn(domain);
        AccountVO acct = (AccountVO) makeAccount(401L, 10L, "name");
        acct.setState(Account.State.ENABLED);
        when(accountService.getActiveAccountByName("name", 10L)).thenReturn(acct);
        assertEquals(Long.valueOf(401L), service.finalizeAccountId("name", 10L, null, true));
    }

    @Test
    public void finalizeAccountIdLegacyDisabledAccountWhenEnabledOnlyThrows() {
        when(domainManager.getDomain(10L)).thenReturn(domain);
        AccountVO acct = (AccountVO) makeAccount(401L, 10L, "name");
        acct.setState(Account.State.DISABLED);
        when(accountService.getActiveAccountByName("name", 10L)).thenReturn(acct);
        assertThrows(PermissionDeniedException.class,
                () -> service.finalizeAccountId("name", 10L, null, true));
    }

    @Test
    public void finalizeAccountIdLegacyProjectAccountTypeRejected() {
        when(domainManager.getDomain(10L)).thenReturn(domain);
        AccountVO acct = new AccountVO("p", 10L, null, Account.Type.PROJECT, "u");
        acct.setId(401L);
        when(accountService.getActiveAccountByName("p", 10L)).thenReturn(acct);
        assertThrows(InvalidParameterValueException.class,
                () -> service.finalizeAccountId("p", 10L, null, false));
    }

    @Test
    public void finalizeAccountIdLegacyProjectActiveReturnsProjectAccount() {
        ProjectVO project = mock(ProjectVO.class);
        when(project.getState()).thenReturn(Project.State.Active);
        when(project.getProjectAccountId()).thenReturn(777L);
        when(projectManager.getProject(99L)).thenReturn(project);
        assertEquals(Long.valueOf(777L), service.finalizeAccountId(null, null, 99L, true));
    }

    @Test
    public void finalizeAccountIdLegacyProjectInactiveThrows() {
        ProjectVO project = mock(ProjectVO.class);
        when(project.getState()).thenReturn(Project.State.Disabled);
        when(project.getUuid()).thenReturn("project-uuid");
        when(projectManager.getProject(99L)).thenReturn(project);
        assertThrows(PermissionDeniedException.class,
                () -> service.finalizeAccountId(null, null, 99L, true));
    }

    @Test
    public void finalizeAccountIdLegacyProjectMissingThrows() {
        when(projectManager.getProject(99L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.finalizeAccountId(null, null, 99L, false));
    }

    @Test
    public void finalizeAccountIdLegacyNothingReturnsNull() {
        assertNull(service.finalizeAccountId(null, null, null, false));
    }

    // ---------------- finalizeAccountId(Long,String,Long,Long) ----------------

    @Test
    public void finalizeAccountIdProjectAndAccountTogetherThrows() {
        assertThrows(ServerApiException.class,
                () -> service.finalizeAccountId(11L, null, null, 99L));
    }

    @Test
    public void finalizeAccountIdProjectActiveReturnsProjectAccount() {
        ProjectVO project = mock(ProjectVO.class);
        when(project.getState()).thenReturn(Project.State.Active);
        when(project.getProjectAccountId()).thenReturn(777L);
        when(projectManager.getProject(99L)).thenReturn(project);
        assertEquals(Long.valueOf(777L), service.finalizeAccountId(null, null, null, 99L));
    }

    @Test
    public void finalizeAccountIdByIdResolves() {
        AccountVO acct = (AccountVO) makeAccount(401L, 10L, "name");
        when(accountService.getActiveAccountById(401L)).thenReturn(acct);
        assertEquals(Long.valueOf(401L), service.finalizeAccountId(401L, null, null, null));
    }

    @Test
    public void finalizeAccountIdByIdMissingThrows() {
        when(accountService.getActiveAccountById(401L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.finalizeAccountId(401L, null, null, null));
    }

    @Test
    public void finalizeAccountIdNoKeysThrowsServerApiException() {
        assertThrows(ServerApiException.class,
                () -> service.finalizeAccountId(null, null, null, null));
    }

    @Test
    public void finalizeAccountIdByNameMissingDomainSurfacesAsServerApi() {
        when(accountService.getActiveAccountByName("name", null))
                .thenThrow(new InvalidParameterValueException("must specify domain"));
        assertThrows(ServerApiException.class,
                () -> service.finalizeAccountId(null, "name", null, null));
    }

    @Test
    public void finalizeAccountIdByNameResolves() {
        AccountVO acct = (AccountVO) makeAccount(401L, 10L, "name");
        when(accountService.getActiveAccountByName("name", 10L)).thenReturn(acct);
        assertEquals(Long.valueOf(401L), service.finalizeAccountId(null, "name", 10L, null));
    }

    @Test
    public void finalizeAccountIdByNameUnresolvedThrows() {
        when(accountService.getActiveAccountByName("name", 10L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.finalizeAccountId(null, "name", 10L, null));
    }

    // ---------------- getActiveProjectAccountByProjectId ----------------

    @Test
    public void getActiveProjectAccountByProjectIdMissingThrows() {
        when(projectManager.getProject(anyLong())).thenReturn(null);
        assertThrows(ServerApiException.class,
                () -> service.getActiveProjectAccountByProjectId(99L));
    }

    @Test
    public void getActiveProjectAccountByProjectIdInactiveThrows() {
        ProjectVO project = mock(ProjectVO.class);
        when(project.getState()).thenReturn(Project.State.Suspended);
        when(projectManager.getProject(99L)).thenReturn(project);
        assertThrows(ServerApiException.class,
                () -> service.getActiveProjectAccountByProjectId(99L));
    }

    @Test
    public void getActiveProjectAccountByProjectIdActiveReturnsId() {
        ProjectVO project = mock(ProjectVO.class);
        when(project.getState()).thenReturn(Project.State.Active);
        when(project.getProjectAccountId()).thenReturn(888L);
        when(projectManager.getProject(99L)).thenReturn(project);
        assertEquals(888L, service.getActiveProjectAccountByProjectId(99L));
    }
}
