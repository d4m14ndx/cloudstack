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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Collections;

import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.dao.HostDao;
import com.cloud.user.Account.State;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserAccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class AccountStateServiceImplTest {

    private static final long ACCOUNT_ID = 42L;
    private static final long USER_ID = 101L;

    private AccountStateServiceImpl service;

    @Mock
    private AccountDao accountDao;
    @Mock
    private UserDao userDao;
    @Mock
    private UserAccountDao userAccountDao;
    @Mock
    private VMInstanceDao vmDao;
    @Mock
    private VirtualMachineManager itMgr;
    @Mock
    private HostDao hostDao;
    @Mock
    private AccountManagerImpl accountManager;
    @Mock
    private Account caller;

    private MockedStatic<Transaction> transactionMocked;

    @Before
    public void setUp() {
        service = new AccountStateServiceImpl();
        ReflectionTestUtils.setField(service, "accountDao", accountDao);
        ReflectionTestUtils.setField(service, "userDao", userDao);
        ReflectionTestUtils.setField(service, "userAccountDao", userAccountDao);
        ReflectionTestUtils.setField(service, "vmDao", vmDao);
        ReflectionTestUtils.setField(service, "itMgr", itMgr);
        ReflectionTestUtils.setField(service, "hostDao", hostDao);
        ReflectionTestUtils.setField(service, "accountManager", accountManager);

        Mockito.when(accountManager.getCurrentCallingAccount()).thenReturn(caller);

        User callerUser = new UserVO();
        Account callerAccount = new AccountVO();
        CallContext.register(callerUser, callerAccount);

        transactionMocked = Mockito.mockStatic(Transaction.class);
        transactionMocked.when(() -> Transaction.execute(Mockito.any(TransactionCallback.class)))
                .thenAnswer(invocation -> {
                    TransactionCallback<Boolean> callback = invocation.getArgument(0);
                    return callback.doInTransaction(null);
                });
    }

    @After
    public void tearDown() {
        transactionMocked.close();
        CallContext.unregister();
    }

    @Test
    public void enableAccount_setsEnabledAndClearsCleanup() {
        AccountVO accountForUpdate = new AccountVO();
        Mockito.when(accountDao.createForUpdate()).thenReturn(accountForUpdate);
        Mockito.when(accountDao.update(ACCOUNT_ID, accountForUpdate)).thenReturn(true);

        assertTrue(service.enableAccount(ACCOUNT_ID));
        assertSame(State.ENABLED, accountForUpdate.getState());
        assertFalse(accountForUpdate.getNeedsCleanup());
    }

    @Test
    public void lockAccount_returnsTrueWhenAlreadyLocked() {
        AccountVO account = createAccount(ACCOUNT_ID, State.LOCKED, false, Account.Type.NORMAL);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);

        assertTrue(service.lockAccount(ACCOUNT_ID));
        Mockito.verify(accountDao, Mockito.never()).update(Mockito.anyLong(), Mockito.any(AccountVO.class));
    }

    @Test
    public void lockAccount_updatesEnabledAccountToLocked() {
        AccountVO account = createAccount(ACCOUNT_ID, State.ENABLED, false, Account.Type.NORMAL);
        AccountVO accountForUpdate = new AccountVO();
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);
        Mockito.when(accountDao.createForUpdate()).thenReturn(accountForUpdate);
        Mockito.when(accountDao.update(ACCOUNT_ID, accountForUpdate)).thenReturn(true);

        assertTrue(service.lockAccount(ACCOUNT_ID));
        assertSame(State.LOCKED, accountForUpdate.getState());
    }

    @Test
    public void lockAccount_returnsFalseWhenAccountMissing() {
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(null);

        assertFalse(service.lockAccount(ACCOUNT_ID));
    }

    @Test
    public void lockAccount_returnsFalseWhenAccountNotEnabled() {
        AccountVO account = createAccount(ACCOUNT_ID, State.DISABLED, false, Account.Type.NORMAL);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);

        assertFalse(service.lockAccount(ACCOUNT_ID));
        Mockito.verify(accountDao, Mockito.never()).update(Mockito.anyLong(), Mockito.any(AccountVO.class));
    }

    @Test
    public void disableAccount_rejectsSystemReservedIds() throws Exception {
        assertFalse(service.disableAccount(Account.ACCOUNT_ID_SYSTEM));
        assertFalse(service.disableAccount(Account.ACCOUNT_ID_ADMIN));
    }

    @Test
    public void disableAccount_returnsTrueForMissingAccount() throws Exception {
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(null);

        assertTrue(service.disableAccount(ACCOUNT_ID));
    }

    @Test
    public void disableAccount_returnsTrueForAlreadyDisabledWithoutCleanup() throws Exception {
        AccountVO account = createAccount(ACCOUNT_ID, State.DISABLED, false, Account.Type.NORMAL);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);

        assertTrue(service.disableAccount(ACCOUNT_ID));
        Mockito.verify(accountDao, Mockito.never()).markForCleanup(Mockito.anyLong());
    }

    @Test
    public void disableAccount_marksCleanupWhenVmStopTimesOut() throws Exception {
        AccountVO account = createAccount(ACCOUNT_ID, State.ENABLED, true, Account.Type.NORMAL);
        AccountVO accountForUpdate = new AccountVO();
        VMInstanceVO vm = createVm("vm-timeout", 900L);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);
        Mockito.when(accountDao.createForUpdate()).thenReturn(accountForUpdate);
        Mockito.when(accountDao.update(ACCOUNT_ID, accountForUpdate)).thenReturn(true);
        Mockito.when(vmDao.listByAccountId(ACCOUNT_ID)).thenReturn(Collections.singletonList(vm));
        Mockito.doThrow(new OperationTimedoutException(null, 9L, 2L, 30, true))
                .when(itMgr).advanceStop(vm.getUuid(), false);

        assertTrue(service.disableAccount(ACCOUNT_ID));
        Mockito.verify(accountDao).markForCleanup(ACCOUNT_ID);
    }

    @Test
    public void disableAccount_marksCleanupWhenAgentUnavailable() throws Exception {
        AccountVO account = createAccount(ACCOUNT_ID, State.ENABLED, true, Account.Type.NORMAL);
        AccountVO accountForUpdate = new AccountVO();
        VMInstanceVO vm = createVm("vm-agent", 901L);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);
        Mockito.when(accountDao.createForUpdate()).thenReturn(accountForUpdate);
        Mockito.when(accountDao.update(ACCOUNT_ID, accountForUpdate)).thenReturn(true);
        Mockito.when(vmDao.listByAccountId(ACCOUNT_ID)).thenReturn(Collections.singletonList(vm));
        Mockito.doThrow(new AgentUnavailableException("down", vm.getHostId()))
                .when(itMgr).advanceStop(vm.getUuid(), false);

        assertTrue(service.disableAccount(ACCOUNT_ID));
        Mockito.verify(accountDao).markForCleanup(ACCOUNT_ID);
    }

    @Test
    public void disableAccount_clearsNeedsCleanupWhenVmStopsSucceed() throws Exception {
        AccountVO account = createAccount(ACCOUNT_ID, State.ENABLED, true, Account.Type.NORMAL);
        AccountVO accountForUpdate = new AccountVO();
        VMInstanceVO vm = createVm("vm-ok", 902L);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);
        Mockito.when(accountDao.createForUpdate()).thenReturn(accountForUpdate);
        Mockito.when(accountDao.update(ACCOUNT_ID, accountForUpdate)).thenReturn(true);
        Mockito.when(accountDao.update(ACCOUNT_ID, account)).thenReturn(true);
        Mockito.when(vmDao.listByAccountId(ACCOUNT_ID)).thenReturn(Collections.singletonList(vm));

        assertTrue(service.disableAccount(ACCOUNT_ID));
        assertFalse(account.getNeedsCleanup());
        Mockito.verify(accountDao, Mockito.never()).markForCleanup(Mockito.anyLong());
        Mockito.verify(accountDao).update(ACCOUNT_ID, account);
    }

    @Test
    public void disableUser_updatesStateAndReturnsReloadedUser() {
        UserVO user = createUser(USER_ID, ACCOUNT_ID, State.ENABLED, "user-disable");
        UserVO userForUpdate = new UserVO();
        UserAccountVO reloaded = createUserAccount(USER_ID, ACCOUNT_ID, State.DISABLED);
        AccountVO account = createAccount(ACCOUNT_ID, State.ENABLED, false, Account.Type.NORMAL);
        Mockito.when(userDao.findById(USER_ID)).thenReturn(user);
        Mockito.when(userDao.createForUpdate()).thenReturn(userForUpdate);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);
        Mockito.when(userDao.update(Mockito.eq(USER_ID), Mockito.any(UserVO.class))).thenReturn(true);
        Mockito.when(userAccountDao.findById(USER_ID)).thenReturn(reloaded);

        assertSame(reloaded, service.disableUser(USER_ID));

        ArgumentCaptor<UserVO> captor = ArgumentCaptor.forClass(UserVO.class);
        Mockito.verify(userDao).update(Mockito.eq(USER_ID), captor.capture());
        assertSame(State.DISABLED, captor.getValue().getState());
        Mockito.verify(accountManager).checkAccess(caller, AccessType.OperateEntry, true, account);
        Mockito.verify(accountManager).verifyCallerPrivilegeForUserOrAccountOperations(user);
    }

    @Test
    public void enableUser_updatesState_enablesAccount_resetsAttempts_andReturnsUser() {
        UserVO user = createUser(USER_ID, ACCOUNT_ID, State.DISABLED, "user-enable");
        UserVO userForUpdate = new UserVO();
        AccountVO account = createAccount(ACCOUNT_ID, State.DISABLED, true, Account.Type.NORMAL);
        UserAccountVO reloaded = createUserAccount(USER_ID, ACCOUNT_ID, State.ENABLED);
        AccountVO userForAccountEnable = new AccountVO();
        Mockito.when(userDao.findById(USER_ID)).thenReturn(user);
        Mockito.when(userDao.createForUpdate()).thenReturn(userForUpdate);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);
        Mockito.when(userDao.update(Mockito.eq(USER_ID), Mockito.any(UserVO.class))).thenReturn(true);
        Mockito.when(accountDao.createForUpdate()).thenReturn(userForAccountEnable);
        Mockito.when(accountDao.update(ACCOUNT_ID, userForAccountEnable)).thenReturn(true);
        Mockito.when(userAccountDao.findById(USER_ID)).thenReturn(reloaded);

        assertSame(reloaded, service.enableUser(USER_ID));

        Mockito.verify(accountManager).updateLoginAttempts(USER_ID, 0, false);
        assertSame(State.ENABLED, userForAccountEnable.getState());
        assertFalse(userForAccountEnable.getNeedsCleanup());
    }

    @Test
    public void lockUser_locksAccountOnlyWhenNoEnabledUsersRemain() {
        UserVO user = createUser(USER_ID, ACCOUNT_ID, State.ENABLED, "user-lock");
        UserVO userForUpdate = new UserVO();
        UserVO secondUser = createUser(202L, ACCOUNT_ID, State.LOCKED, "other");
        AccountVO account = createAccount(ACCOUNT_ID, State.ENABLED, false, Account.Type.NORMAL);
        AccountVO accountForUpdate = new AccountVO();
        UserAccountVO reloaded = createUserAccount(USER_ID, ACCOUNT_ID, State.LOCKED);
        Mockito.when(userDao.findById(USER_ID)).thenReturn(user);
        Mockito.when(userDao.createForUpdate()).thenReturn(userForUpdate);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);
        Mockito.when(userDao.update(Mockito.eq(USER_ID), Mockito.any(UserVO.class))).thenReturn(true);
        Mockito.when(userDao.listByAccount(ACCOUNT_ID)).thenReturn(Collections.singletonList(secondUser));
        Mockito.when(accountDao.createForUpdate()).thenReturn(accountForUpdate);
        Mockito.when(accountDao.update(ACCOUNT_ID, accountForUpdate)).thenReturn(true);
        Mockito.when(userAccountDao.findById(USER_ID)).thenReturn(reloaded);

        assertSame(reloaded, service.lockUser(USER_ID));

        assertSame(State.LOCKED, accountForUpdate.getState());
    }

    @Test
    public void disableUser_rejectsProjectAccount() {
        UserVO user = createUser(USER_ID, ACCOUNT_ID, State.ENABLED, "project-user");
        AccountVO account = createAccount(ACCOUNT_ID, State.ENABLED, false, Account.Type.PROJECT);
        Mockito.when(userDao.findById(USER_ID)).thenReturn(user);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);

        assertThrows(InvalidParameterValueException.class, () -> service.disableUser(USER_ID));
    }

    @Test
    public void enableUser_rejectsSystemUser() {
        UserVO user = createUser(USER_ID, Account.ACCOUNT_ID_SYSTEM, State.DISABLED, "system-user");
        AccountVO account = createAccount(Account.ACCOUNT_ID_SYSTEM, State.DISABLED, false, Account.Type.NORMAL);
        Mockito.when(userDao.findById(USER_ID)).thenReturn(user);
        Mockito.when(accountDao.findById(Account.ACCOUNT_ID_SYSTEM)).thenReturn(account);

        assertThrows(InvalidParameterValueException.class, () -> service.enableUser(USER_ID));
    }

    @Test
    public void lockUser_returnsExistingUserWhenAlreadyLocked() {
        UserVO user = createUser(USER_ID, ACCOUNT_ID, State.LOCKED, "already-locked");
        AccountVO account = createAccount(ACCOUNT_ID, State.ENABLED, false, Account.Type.NORMAL);
        UserAccountVO reloaded = createUserAccount(USER_ID, ACCOUNT_ID, State.LOCKED);
        Mockito.when(userDao.findById(USER_ID)).thenReturn(user);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);
        Mockito.when(userAccountDao.findById(USER_ID)).thenReturn(reloaded);

        assertSame(reloaded, service.lockUser(USER_ID));
        Mockito.verify(userDao, Mockito.never()).update(Mockito.anyLong(), Mockito.any(UserVO.class));
    }

    @Test
    public void lockUser_throwsWhenUserStateIsDisabled() {
        UserVO user = createUser(USER_ID, ACCOUNT_ID, State.DISABLED, "disabled-user");
        AccountVO account = createAccount(ACCOUNT_ID, State.ENABLED, false, Account.Type.NORMAL);
        Mockito.when(userDao.findById(USER_ID)).thenReturn(user);
        Mockito.when(accountDao.findById(ACCOUNT_ID)).thenReturn(account);

        assertThrows(RuntimeException.class, () -> service.lockUser(USER_ID));
    }

    private AccountVO createAccount(long accountId, State state, boolean needsCleanup, Account.Type type) {
        AccountVO account = new AccountVO();
        account.setId(accountId);
        account.setState(state);
        account.setNeedsCleanup(needsCleanup);
        account.setType(type);
        account.setUuid("account-" + accountId);
        return account;
    }

    private UserVO createUser(long userId, long accountId, State state, String uuid) {
        UserVO user = new UserVO(userId);
        user.setAccountId(accountId);
        user.setState(state);
        user.setUuid(uuid);
        return user;
    }

    private UserAccountVO createUserAccount(long userId, long accountId, State state) {
        UserAccountVO userAccount = new UserAccountVO();
        userAccount.setId(userId);
        userAccount.setAccountId(accountId);
        userAccount.setState(state.toString());
        return userAccount;
    }

    private VMInstanceVO createVm(String uuid, long hostId) {
        VMInstanceVO vm = new VMInstanceVO();
        vm.setUuid(uuid);
        vm.setHostId(hostId);
        return vm;
    }
}
