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
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.resourcedetail.dao.UserDetailsDao;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserAccountDao;
import com.cloud.user.dao.UserDao;

/**
 * DAO-fronted lookups for accounts and users — extracted from
 * {@link AccountManagerImpl}.
 *
 * @see AccountLookupService
 */
@Component
public class AccountLookupServiceImpl implements AccountLookupService {

    @Inject
    private AccountDao accountDao;
    @Inject
    private UserDao userDao;
    @Inject
    private UserAccountDao userAccountDao;
    @Inject
    private UserDetailsDao userDetailsDao;

    @Override
    public Account getActiveAccountByName(String accountName, Long domainId) {
        if (accountName == null || domainId == null) {
            throw new InvalidParameterValueException("Both accountName and domainId are required for finding active account in the system");
        }
        return accountDao.findActiveAccount(accountName, domainId);
    }

    @Override
    public UserAccount getActiveUserAccount(String username, Long domainId) {
        return userAccountDao.getUserAccount(username, domainId);
    }

    @Override
    public List<UserAccount> getActiveUserAccountByEmail(String email, Long domainId) {
        List<UserAccountVO> userAccountByEmail = userAccountDao.getUserAccountByEmail(email, domainId);
        return userAccountByEmail.stream()
                .map(userAccountVO -> (UserAccount) userAccountVO)
                .collect(Collectors.toList());
    }

    @Override
    public Account getActiveAccountById(long accountId) {
        return accountDao.findById(accountId);
    }

    @Override
    public Account getAccount(long accountId) {
        return accountDao.findByIdIncludingRemoved(accountId);
    }

    @Override
    public RoleType getRoleType(Account account) {
        if (account == null) {
            return RoleType.Unknown;
        }
        return RoleType.getByAccountType(account.getType());
    }

    @Override
    public User getActiveUser(long userId) {
        return userDao.findById(userId);
    }

    @Override
    public User getUserIncludingRemoved(long userId) {
        return userDao.findByIdIncludingRemoved(userId);
    }

    @Override
    public User getActiveUserByRegistrationToken(String registrationToken) {
        return userDao.findUserByRegistrationToken(registrationToken);
    }

    @Override
    public void markUserRegistered(long userId) {
        UserVO userForUpdate = userDao.createForUpdate();
        userForUpdate.setRegistered(true);
        userDao.update(userId, userForUpdate);
    }

    @Override
    public UserAccount getUserAccountById(Long userId) {
        UserAccount userAccount = userAccountDao.findById(userId);
        if (userAccount != null) {
            Map<String, String> details = userDetailsDao.listDetailsKeyPairs(userId);
            userAccount.setDetails(details);
        }
        return userAccount;
    }
}
