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

import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.context.CallContext;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.dao.HostDao;
import com.cloud.user.Account.State;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserAccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class AccountStateServiceImpl implements AccountStateService {

    private static final org.apache.logging.log4j.Logger logger =
            org.apache.logging.log4j.LogManager.getLogger(AccountStateServiceImpl.class);

    @Inject
    protected AccountDao accountDao;
    @Inject
    protected UserDao userDao;
    @Inject
    protected UserAccountDao userAccountDao;
    @Inject
    protected VMInstanceDao vmDao;
    @Inject
    protected VirtualMachineManager itMgr;
    @Inject
    protected HostDao hostDao;
    @Inject
    @Lazy
    protected AccountManagerImpl accountManager;

    private boolean doSetUserStatus(long userId, State state) {
        UserVO userForUpdate = userDao.createForUpdate();
        userForUpdate.setState(state);
        return userDao.update(userId, userForUpdate);
    }

    @Override
    public boolean enableAccount(long accountId) {
        AccountVO acctForUpdate = accountDao.createForUpdate();
        acctForUpdate.setState(State.ENABLED);
        acctForUpdate.setNeedsCleanup(false);
        return accountDao.update(accountId, acctForUpdate);
    }

    @Override
    public boolean lockAccount(long accountId) {
        boolean success = false;
        Account account = accountDao.findById(accountId);
        if (account != null) {
            if (account.getState().equals(State.LOCKED)) {
                return true;
            } else if (account.getState().equals(State.ENABLED)) {
                AccountVO acctForUpdate = accountDao.createForUpdate();
                acctForUpdate.setState(State.LOCKED);
                success = accountDao.update(accountId, acctForUpdate);
            } else {
                if (logger.isInfoEnabled()) {
                    logger.info("Attempting to lock a non-enabled account {}, current state is {}, locking failed.", account, account.getState());
                }
            }
        } else {
            logger.warn("Failed to lock account " + accountId + ", account not found.");
        }
        return success;
    }

    @Override
    public boolean disableAccount(long accountId) throws ConcurrentOperationException, ResourceUnavailableException {
        boolean success = false;
        if (accountId <= 2) {
            if (logger.isInfoEnabled()) {
                logger.info("disableAccount -- invalid account id: " + accountId);
            }
            return false;
        }

        AccountVO account = accountDao.findById(accountId);
        if ((account == null) || (account.getState().equals(State.DISABLED) && !account.getNeedsCleanup())) {
            success = true;
        } else {
            AccountVO acctForUpdate = accountDao.createForUpdate();
            acctForUpdate.setState(State.DISABLED);
            success = accountDao.update(accountId, acctForUpdate);

            if (success) {
                boolean disableAccountResult = false;
                try {
                    disableAccountResult = doDisableAccount(accountId);
                } finally {
                    if (!disableAccountResult) {
                        logger.warn("Failed to disable account " + account + " resources as a part of disableAccount call, marking the account for cleanup");
                        accountDao.markForCleanup(accountId);
                    } else {
                        acctForUpdate = accountDao.createForUpdate();
                        account.setNeedsCleanup(false);
                        accountDao.update(accountId, account);
                    }
                }
            }
        }
        return success;
    }

    private boolean doDisableAccount(long accountId) throws ConcurrentOperationException, ResourceUnavailableException {
        List<VMInstanceVO> vms = vmDao.listByAccountId(accountId);
        boolean success = true;
        for (VMInstanceVO vm : vms) {
            try {
                try {
                    itMgr.advanceStop(vm.getUuid(), false);
                } catch (OperationTimedoutException ote) {
                    logger.warn("Operation for stopping vm timed out, unable to stop vm {}", vm, ote);
                    success = false;
                }
            } catch (AgentUnavailableException aue) {
                logger.warn("Agent running on host {} is unavailable, unable to stop vm {}", () -> hostDao.findById(vm.getHostId()), vm::toString, () -> aue);
                success = false;
            }
        }

        return success;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_USER_DISABLE, eventDescription = "disabling User", async = true)
    public UserAccount disableUser(long userId) {
        Account caller = accountManager.getCurrentCallingAccount();

        User user = userDao.findById(userId);
        if (user == null || user.getRemoved() != null) {
            throw new InvalidParameterValueException("Unable to find active user by id " + userId);
        }

        Account account = accountDao.findById(user.getAccountId());
        if (account == null) {
            throw new InvalidParameterValueException("unable to find user account " + user.getAccountId());
        }

        if (account.getType() == Account.Type.PROJECT) {
            throw new InvalidParameterValueException(String.format("Unable to find active user %s", user));
        }

        if (account.getId() == Account.ACCOUNT_ID_SYSTEM) {
            throw new InvalidParameterValueException(String.format("User: %s is a system user, disabling is not allowed", user));
        }

        accountManager.checkAccess(caller, AccessType.OperateEntry, true, account);
        accountManager.verifyCallerPrivilegeForUserOrAccountOperations(user);

        boolean success = doSetUserStatus(userId, State.DISABLED);
        if (success) {
            CallContext.current().putContextParameter(User.class, user.getUuid());
            return userAccountDao.findById(userId);
        } else {
            throw new CloudRuntimeException(String.format("Unable to disable user %s", user));
        }
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_USER_ENABLE, eventDescription = "enabling User")
    public UserAccount enableUser(final long userId) {
        Account caller = accountManager.getCurrentCallingAccount();

        final User user = userDao.findById(userId);
        if (user == null || user.getRemoved() != null) {
            throw new InvalidParameterValueException("Unable to find active user by id " + userId);
        }

        Account account = accountDao.findById(user.getAccountId());
        if (account == null) {
            throw new InvalidParameterValueException("unable to find user account " + user.getAccountId());
        }

        if (account.getType() == Account.Type.PROJECT) {
            throw new InvalidParameterValueException(String.format("Unable to find active user %s", user));
        }

        if (account.getId() == Account.ACCOUNT_ID_SYSTEM) {
            throw new InvalidParameterValueException(String.format("User: %s is a system user, enabling is not allowed", user));
        }

        accountManager.checkAccess(caller, AccessType.OperateEntry, true, account);
        accountManager.verifyCallerPrivilegeForUserOrAccountOperations(user);

        boolean success = Transaction.execute(new TransactionCallback<>() {
            @Override
            public Boolean doInTransaction(TransactionStatus status) {
                boolean success = doSetUserStatus(userId, State.ENABLED);
                success = success && enableAccount(user.getAccountId());
                return success;
            }
        });

        if (success) {
            accountManager.updateLoginAttempts(userId, 0, false);
            CallContext.current().putContextParameter(User.class, user.getUuid());
            return userAccountDao.findById(userId);
        } else {
            throw new CloudRuntimeException(String.format("Unable to enable user %s", user));
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_USER_LOCK, eventDescription = "locking User")
    public UserAccount lockUser(long userId) {
        Account caller = accountManager.getCurrentCallingAccount();

        User user = userDao.findById(userId);
        if (user == null || user.getRemoved() != null) {
            throw new InvalidParameterValueException("Unable to find user by id");
        }

        Account account = accountDao.findById(user.getAccountId());
        if (account == null) {
            throw new InvalidParameterValueException("unable to find user account " + user.getAccountId());
        }

        if (account.getType() == Account.Type.PROJECT) {
            throw new InvalidParameterValueException("Unable to find user by id");
        }

        if (account.getId() == Account.ACCOUNT_ID_SYSTEM) {
            throw new PermissionDeniedException(String.format("user: %s is a system user, locking is not allowed", user));
        }

        accountManager.checkAccess(caller, AccessType.OperateEntry, true, account);
        accountManager.verifyCallerPrivilegeForUserOrAccountOperations(user);

        boolean success;
        if (user.getState().equals(State.LOCKED)) {
            return userAccountDao.findById(userId);
        } else if (user.getState().equals(State.ENABLED)) {
            success = doSetUserStatus(user.getId(), State.LOCKED);

            boolean lockAccount = true;
            List<UserVO> allUsersByAccount = userDao.listByAccount(user.getAccountId());
            for (UserVO oneUser : allUsersByAccount) {
                if (oneUser.getState().equals(State.ENABLED)) {
                    lockAccount = false;
                    break;
                }
            }

            if (lockAccount) {
                success = (success && lockAccount(user.getAccountId()));
            }
        } else {
            if (logger.isInfoEnabled()) {
                logger.info("Attempting to lock a non-enabled user {}, current state is {}, locking failed.", user, user.getState());
            }
            success = false;
        }

        if (success) {
            CallContext.current().putContextParameter(User.class, user.getUuid());
            return userAccountDao.findById(userId);
        } else {
            throw new CloudRuntimeException(String.format("Unable to lock user %s", user));
        }
    }
}
