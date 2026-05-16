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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.command.user.network.CreateNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.RemoveNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.ResetNetworkPermissionsCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.network.NetworkPermissionVO;
import org.apache.cloudstack.network.dao.NetworkPermissionDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectManager;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;

@RunWith(MockitoJUnitRunner.class)
public class NetworkPermissionServiceImplTest {

    @Mock private NetworkDao networksDao;
    @Mock private NetworkPermissionDao networkPermissionDao;
    @Mock private AccountManager accountMgr;
    @Mock private AccountDao accountDao;
    @Mock private DomainDao domainDao;
    @Mock private ProjectManager projectMgr;

    @InjectMocks
    private NetworkPermissionServiceImpl service;

    private static final Long NETWORK_ID = 100L;
    private static final Long DOMAIN_ID = 7L;
    private static final long OWNER_ID = 9L;
    private static final long CALLER_ID = 11L;

    private MockedStatic<CallContext> callContextMocked;
    private MockedStatic<Transaction> transactionMocked;
    private Account caller;

    @Before
    public void setUp() {
        callContextMocked = Mockito.mockStatic(CallContext.class);
        CallContext ctx = mock(CallContext.class);
        callContextMocked.when(CallContext::current).thenReturn(ctx);

        caller = mock(Account.class);
        lenient().when(caller.getId()).thenReturn(CALLER_ID);
        when(ctx.getCallingAccount()).thenReturn(caller);

        // Run the transaction body inline so the test exercises the persist logic.
        transactionMocked = Mockito.mockStatic(Transaction.class);
        transactionMocked.when(() -> Transaction.execute(any(TransactionCallbackNoReturn.class)))
                .thenAnswer(inv -> {
                    TransactionCallbackNoReturn cb = inv.getArgument(0);
                    cb.doInTransaction(null);
                    return null;
                });
    }

    @After
    public void tearDown() {
        callContextMocked.close();
        transactionMocked.close();
    }

    // ---- listNetworkPermissions ----

    @Test
    public void listMissingNetworkRejected() {
        ListNetworkPermissionsCmd cmd = mock(ListNetworkPermissionsCmd.class);
        when(cmd.getNetworkId()).thenReturn(NETWORK_ID);
        when(networksDao.findById(NETWORK_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.listNetworkPermissions(cmd));
    }

    @Test
    public void listReturnsPermissionsAfterAccessCheck() {
        ListNetworkPermissionsCmd cmd = mock(ListNetworkPermissionsCmd.class);
        when(cmd.getNetworkId()).thenReturn(NETWORK_ID);
        NetworkVO network = mock(NetworkVO.class);
        when(networksDao.findById(NETWORK_ID)).thenReturn(network);
        List<NetworkPermissionVO> expected = Arrays.asList(new NetworkPermissionVO(NETWORK_ID, 42L));
        when(networkPermissionDao.findByNetwork(NETWORK_ID)).thenReturn(expected);

        List<? extends NetworkPermission> actual = service.listNetworkPermissions(cmd);

        verify(accountMgr).checkAccess(eq(caller), eq(AccessType.OperateEntry), anyBoolean(), eq(network));
        assertSame(expected, actual);
    }

    // ---- validateNetworkPermissionParameters (exercised through createNetworkPermissions) ----

    private NetworkVO standardNetwork() {
        NetworkVO network = mock(NetworkVO.class);
        when(network.getDomainId()).thenReturn(DOMAIN_ID);
        when(network.getAccountId()).thenReturn(OWNER_ID);
        lenient().when(network.getAclType()).thenReturn(ACLType.Account);
        lenient().when(network.getVpcId()).thenReturn(null);
        return network;
    }

    private Account standardOwner() {
        Account owner = mock(Account.class);
        lenient().when(owner.getId()).thenReturn(OWNER_ID);
        lenient().when(owner.getType()).thenReturn(Account.Type.NORMAL);
        return owner;
    }

    @Test
    public void validateMissingNetworkRejected() {
        ResetNetworkPermissionsCmd cmd = mock(ResetNetworkPermissionsCmd.class);
        when(cmd.getNetworkId()).thenReturn(NETWORK_ID);
        when(networksDao.findById(NETWORK_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.resetNetworkPermissions(cmd));
    }

    @Test
    public void validateDomainAclTypeRejected() {
        ResetNetworkPermissionsCmd cmd = mock(ResetNetworkPermissionsCmd.class);
        when(cmd.getNetworkId()).thenReturn(NETWORK_ID);
        NetworkVO network = standardNetwork();
        when(network.getAclType()).thenReturn(ACLType.Domain);
        when(networksDao.findById(NETWORK_ID)).thenReturn(network);

        assertThrows(InvalidParameterValueException.class,
                () -> service.resetNetworkPermissions(cmd));
    }

    @Test
    public void validateVpcTierRejected() {
        ResetNetworkPermissionsCmd cmd = mock(ResetNetworkPermissionsCmd.class);
        when(cmd.getNetworkId()).thenReturn(NETWORK_ID);
        NetworkVO network = standardNetwork();
        when(network.getVpcId()).thenReturn(55L);
        when(networksDao.findById(NETWORK_ID)).thenReturn(network);

        assertThrows(InvalidParameterValueException.class,
                () -> service.resetNetworkPermissions(cmd));
    }

    @Test
    public void validateProjectOwnedNetworkRejected() {
        ResetNetworkPermissionsCmd cmd = mock(ResetNetworkPermissionsCmd.class);
        when(cmd.getNetworkId()).thenReturn(NETWORK_ID);
        NetworkVO network = standardNetwork();
        when(networksDao.findById(NETWORK_ID)).thenReturn(network);
        Account owner = standardOwner();
        when(owner.getType()).thenReturn(Account.Type.PROJECT);
        when(accountMgr.getAccount(OWNER_ID)).thenReturn(owner);

        assertThrows(InvalidParameterValueException.class,
                () -> service.resetNetworkPermissions(cmd));
    }

    @Test
    public void validateNonOwnerNonAdminCallerRejected() {
        ResetNetworkPermissionsCmd cmd = mock(ResetNetworkPermissionsCmd.class);
        when(cmd.getNetworkId()).thenReturn(NETWORK_ID);
        NetworkVO network = standardNetwork();
        when(networksDao.findById(NETWORK_ID)).thenReturn(network);
        Account owner = standardOwner();
        when(accountMgr.getAccount(OWNER_ID)).thenReturn(owner);
        when(accountMgr.isAdmin(CALLER_ID)).thenReturn(false);
        when(caller.getAccountName()).thenReturn("baduser");

        assertThrows(InvalidParameterValueException.class,
                () -> service.resetNetworkPermissions(cmd));
    }

    @Test
    public void resetSucceedsForOwnerCaller() {
        ResetNetworkPermissionsCmd cmd = mock(ResetNetworkPermissionsCmd.class);
        when(cmd.getNetworkId()).thenReturn(NETWORK_ID);
        NetworkVO network = standardNetwork();
        when(networksDao.findById(NETWORK_ID)).thenReturn(network);
        when(caller.getId()).thenReturn(OWNER_ID);
        Account owner = standardOwner();
        when(accountMgr.getAccount(OWNER_ID)).thenReturn(owner);

        assertTrue(service.resetNetworkPermissions(cmd));
        verify(networkPermissionDao).removeAllPermissions(NETWORK_ID);
    }

    @Test
    public void resetSucceedsForAdminCaller() {
        ResetNetworkPermissionsCmd cmd = mock(ResetNetworkPermissionsCmd.class);
        when(cmd.getNetworkId()).thenReturn(NETWORK_ID);
        NetworkVO network = standardNetwork();
        when(networksDao.findById(NETWORK_ID)).thenReturn(network);
        Account owner = standardOwner();
        when(accountMgr.getAccount(OWNER_ID)).thenReturn(owner);
        when(accountMgr.isAdmin(CALLER_ID)).thenReturn(true);

        assertTrue(service.resetNetworkPermissions(cmd));
        verify(networkPermissionDao).removeAllPermissions(NETWORK_ID);
    }

    // ---- createNetworkPermissions ----

    private CreateNetworkPermissionsCmd createCmd(List<Long> accountIds, List<String> accountNames, List<Long> projectIds) {
        CreateNetworkPermissionsCmd cmd = mock(CreateNetworkPermissionsCmd.class);
        when(cmd.getNetworkId()).thenReturn(NETWORK_ID);
        lenient().when(cmd.getAccountIds()).thenReturn(accountIds);
        lenient().when(cmd.getAccountNames()).thenReturn(accountNames);
        lenient().when(cmd.getProjectIds()).thenReturn(projectIds);
        return cmd;
    }

    private void wireValidNetworkAndAdmin() {
        NetworkVO network = standardNetwork();
        when(networksDao.findById(NETWORK_ID)).thenReturn(network);
        Account owner = standardOwner();
        when(accountMgr.getAccount(OWNER_ID)).thenReturn(owner);
        when(accountMgr.isAdmin(CALLER_ID)).thenReturn(true);
        DomainVO domain = mock(DomainVO.class);
        lenient().when(domain.getId()).thenReturn(DOMAIN_ID);
        lenient().when(domain.getUuid()).thenReturn("dom-uuid");
        when(domainDao.findById(DOMAIN_ID)).thenReturn(domain);
    }

    @Test
    public void createGrantsPermissionToNewAccount() {
        wireValidNetworkAndAdmin();
        long targetAccountId = 21L;
        CreateNetworkPermissionsCmd cmd = createCmd(new java.util.ArrayList<>(Arrays.asList(targetAccountId)), null, null);

        Account permitted = mock(Account.class);
        when(permitted.getId()).thenReturn(targetAccountId);
        when(accountDao.findActiveAccountById(targetAccountId, DOMAIN_ID)).thenReturn(permitted);
        when(networkPermissionDao.findByNetworkAndAccount(NETWORK_ID, targetAccountId)).thenReturn(null);

        assertTrue(service.createNetworkPermissions(cmd));

        ArgumentCaptor<NetworkPermissionVO> persistedCaptor = ArgumentCaptor.forClass(NetworkPermissionVO.class);
        verify(networkPermissionDao).persist(persistedCaptor.capture());
        assertEquals(targetAccountId, persistedCaptor.getValue().getAccountId());
        assertEquals(NETWORK_ID.longValue(), persistedCaptor.getValue().getNetworkId());
    }

    @Test
    public void createSkipsOwnerAccount() {
        wireValidNetworkAndAdmin();
        CreateNetworkPermissionsCmd cmd = createCmd(new java.util.ArrayList<>(Arrays.asList(OWNER_ID)), null, null);

        Account ownerAcct = mock(Account.class);
        when(ownerAcct.getId()).thenReturn(OWNER_ID);
        when(accountDao.findActiveAccountById(OWNER_ID, DOMAIN_ID)).thenReturn(ownerAcct);

        assertTrue(service.createNetworkPermissions(cmd));
        verify(networkPermissionDao, never()).persist(any(NetworkPermissionVO.class));
    }

    @Test
    public void createSkipsAlreadyGrantedAccount() {
        wireValidNetworkAndAdmin();
        long targetAccountId = 31L;
        CreateNetworkPermissionsCmd cmd = createCmd(new java.util.ArrayList<>(Arrays.asList(targetAccountId)), null, null);

        Account permitted = mock(Account.class);
        when(permitted.getId()).thenReturn(targetAccountId);
        when(accountDao.findActiveAccountById(targetAccountId, DOMAIN_ID)).thenReturn(permitted);
        when(networkPermissionDao.findByNetworkAndAccount(NETWORK_ID, targetAccountId))
                .thenReturn(new NetworkPermissionVO(NETWORK_ID, targetAccountId));

        assertTrue(service.createNetworkPermissions(cmd));
        verify(networkPermissionDao, never()).persist(any(NetworkPermissionVO.class));
    }

    @Test
    public void createRejectsAccountMissingFromDomain() {
        // populateAccounts checks every id resolves against the network's domain before the transaction runs.
        wireValidNetworkAndAdmin();
        long targetAccountId = 41L;
        CreateNetworkPermissionsCmd cmd = createCmd(new java.util.ArrayList<>(Arrays.asList(targetAccountId)), null, null);

        when(accountDao.findActiveAccountById(targetAccountId, DOMAIN_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.createNetworkPermissions(cmd));
        verify(networkPermissionDao, never()).persist(any(NetworkPermissionVO.class));
    }

    @Test
    public void createResolvesProjectIdsToAccountIds() {
        wireValidNetworkAndAdmin();
        Long projectId = 77L;
        long projectAccountId = 88L;
        CreateNetworkPermissionsCmd cmd = createCmd(new java.util.ArrayList<>(), null, Arrays.asList(projectId));

        Project project = mock(Project.class);
        when(project.getProjectAccountId()).thenReturn(projectAccountId);
        when(projectMgr.getProject(projectId)).thenReturn(project);
        when(projectMgr.canAccessProjectAccount(caller, projectAccountId)).thenReturn(true);

        Account permitted = mock(Account.class);
        when(permitted.getId()).thenReturn(projectAccountId);
        when(accountDao.findActiveAccountById(projectAccountId, DOMAIN_ID)).thenReturn(permitted);

        assertTrue(service.createNetworkPermissions(cmd));
        verify(networkPermissionDao).persist(any(NetworkPermissionVO.class));
    }

    @Test
    public void createRejectsUnknownProject() {
        wireValidNetworkAndAdmin();
        Long projectId = 78L;
        CreateNetworkPermissionsCmd cmd = createCmd(new java.util.ArrayList<>(), null, Arrays.asList(projectId));

        when(projectMgr.getProject(projectId)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.createNetworkPermissions(cmd));
    }

    @Test
    public void createRejectsInaccessibleProject() {
        wireValidNetworkAndAdmin();
        Long projectId = 79L;
        long projectAccountId = 89L;
        CreateNetworkPermissionsCmd cmd = createCmd(new java.util.ArrayList<>(), null, Arrays.asList(projectId));

        Project project = mock(Project.class);
        when(project.getProjectAccountId()).thenReturn(projectAccountId);
        when(project.getUuid()).thenReturn("proj-uuid");
        when(projectMgr.getProject(projectId)).thenReturn(project);
        when(projectMgr.canAccessProjectAccount(caller, projectAccountId)).thenReturn(false);

        assertThrows(InvalidParameterValueException.class,
                () -> service.createNetworkPermissions(cmd));
    }

    @Test
    public void createResolvesAccountNamesAndSkipsCaller() {
        wireValidNetworkAndAdmin();
        CreateNetworkPermissionsCmd cmd = createCmd(new java.util.ArrayList<>(), Arrays.asList("self", "other"), null);

        AccountVO callerAcct = mock(AccountVO.class);
        when(callerAcct.getId()).thenReturn(CALLER_ID);
        when(accountDao.findActiveAccount("self", DOMAIN_ID)).thenReturn(callerAcct);

        AccountVO otherAcct = mock(AccountVO.class);
        long otherId = 51L;
        when(otherAcct.getId()).thenReturn(otherId);
        when(accountDao.findActiveAccount("other", DOMAIN_ID)).thenReturn(otherAcct);

        Account permitted = mock(Account.class);
        when(permitted.getId()).thenReturn(otherId);
        when(accountDao.findActiveAccountById(otherId, DOMAIN_ID)).thenReturn(permitted);

        assertTrue(service.createNetworkPermissions(cmd));
        // Only one account ("other") survived caller filtering and reached the persistence path.
        verify(networkPermissionDao, times(1)).persist(any(NetworkPermissionVO.class));
    }

    @Test
    public void createRejectsUnknownAccountName() {
        wireValidNetworkAndAdmin();
        CreateNetworkPermissionsCmd cmd = createCmd(new java.util.ArrayList<>(), Arrays.asList("ghost"), null);

        when(accountDao.findActiveAccount("ghost", DOMAIN_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.createNetworkPermissions(cmd));
    }

    // ---- removeNetworkPermissions ----

    @Test
    public void removeForwardsResolvedAccountIdsToDao() {
        wireValidNetworkAndAdmin();
        long targetAccountId = 61L;
        RemoveNetworkPermissionsCmd cmd = mock(RemoveNetworkPermissionsCmd.class);
        when(cmd.getNetworkId()).thenReturn(NETWORK_ID);
        when(cmd.getAccountIds()).thenReturn(new java.util.ArrayList<>(Arrays.asList(targetAccountId)));
        lenient().when(cmd.getAccountNames()).thenReturn(null);
        lenient().when(cmd.getProjectIds()).thenReturn(null);

        Account permitted = mock(Account.class);
        when(accountDao.findActiveAccountById(targetAccountId, DOMAIN_ID)).thenReturn(permitted);

        assertTrue(service.removeNetworkPermissions(cmd));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Long>> captor = ArgumentCaptor.forClass(List.class);
        verify(networkPermissionDao).removePermissions(eq(NETWORK_ID), captor.capture());
        assertEquals(Collections.singletonList(targetAccountId), captor.getValue());
    }

    @Test
    public void removeAccessDeniedSurfaces() {
        ResetNetworkPermissionsCmd cmd = mock(ResetNetworkPermissionsCmd.class);
        when(cmd.getNetworkId()).thenReturn(NETWORK_ID);
        NetworkVO network = standardNetwork();
        when(networksDao.findById(NETWORK_ID)).thenReturn(network);
        doAnswer(inv -> { throw new PermissionDeniedException("nope"); })
                .when(accountMgr).checkAccess(eq(caller), eq(AccessType.OperateEntry), anyBoolean(), eq(network));

        assertThrows(PermissionDeniedException.class,
                () -> service.resetNetworkPermissions(cmd));
    }
}
