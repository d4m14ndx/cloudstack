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

import jakarta.inject.Inject;

import org.apache.cloudstack.auth.UserTwoFactorAuthenticator;
import org.apache.cloudstack.resourcedetail.UserDetailVO;
import org.apache.cloudstack.resourcedetail.dao.UserDetailsDao;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.user.dao.UserAccountDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Provider-registry lookups and login-time setup-state cleanup for user
 * two-factor authentication — extracted from {@link AccountManagerImpl}.
 *
 * <p>Reads and writes the static {@link
 * AccountManagerImpl#userTwoFactorAuthenticationProvidersMap} field so the
 * orchestration methods that still live on the god class (and the
 * {@code AccountManagerImplTest} cases that stub that field directly when
 * exercising the verify / enable / disable paths) keep seeing exactly the
 * provider map this service writes.
 *
 * @see TwoFactorAuthenticationService
 */
@Component
public class TwoFactorAuthenticationServiceImpl implements TwoFactorAuthenticationService {

    private static final Logger logger = LogManager.getLogger(TwoFactorAuthenticationServiceImpl.class);

    @Inject
    private UserAccountDao userAccountDao;
    @Inject
    private UserDetailsDao userDetailsDao;
    @Inject
    private AccountService accountService;

    private List<UserTwoFactorAuthenticator> userTwoFactorAuthenticationProviders;

    @Override
    public List<UserTwoFactorAuthenticator> listUserTwoFactorAuthenticationProviders() {
        return userTwoFactorAuthenticationProviders;
    }

    @Override
    public UserTwoFactorAuthenticator getUserTwoFactorAuthenticationProvider(Long domainId) {
        final String name = AccountManagerImpl.userTwoFactorAuthenticationDefaultProvider.valueIn(domainId);
        return getUserTwoFactorAuthenticationProvider(name);
    }

    @Override
    public UserTwoFactorAuthenticator getUserTwoFactorAuthenticationProvider(final String name) {
        Map<String, UserTwoFactorAuthenticator> providersMap = AccountManagerImpl.userTwoFactorAuthenticationProvidersMap;
        if (StringUtils.isEmpty(name)) {
            throw new CloudRuntimeException("Two factor authentication provider name is empty");
        }
        if (!providersMap.containsKey(name.toLowerCase())) {
            throw new CloudRuntimeException(String.format("Failed to find two factor authentication provider by the name: %s.", name));
        }
        return providersMap.get(name.toLowerCase());
    }

    @Override
    public UserTwoFactorAuthenticator getUserTwoFactorAuthenticator(Long domainId, Long userAccountId) {
        if (userAccountId != null) {
            UserAccount userAccount = accountService.getUserAccountById(userAccountId);
            String user2FAProvider = userAccount.getUser2faProvider();
            if (user2FAProvider != null) {
                return getUserTwoFactorAuthenticator(user2FAProvider);
            }
        }
        final String name = AccountManagerImpl.userTwoFactorAuthenticationDefaultProvider.valueIn(domainId);
        return getUserTwoFactorAuthenticator(name);
    }

    @Override
    public UserTwoFactorAuthenticator getUserTwoFactorAuthenticator(final String name) {
        Map<String, UserTwoFactorAuthenticator> providersMap = AccountManagerImpl.userTwoFactorAuthenticationProvidersMap;
        if (StringUtils.isEmpty(name)) {
            throw new CloudRuntimeException("UserTwoFactorAuthenticator name provided is empty");
        }
        if (!providersMap.containsKey(name.toLowerCase())) {
            throw new CloudRuntimeException(String.format("Failed to find UserTwoFactorAuthenticator by the name: %s.", name));
        }
        return providersMap.get(name.toLowerCase());
    }

    @Override
    public UserAccount clearUserTwoFactorAuthenticationInSetupStateOnLogin(UserAccount user) {
        return Transaction.execute((TransactionCallback<UserAccount>) status -> {
            if (!user.isUser2faEnabled() && StringUtils.isBlank(user.getUser2faProvider())) {
                return user;
            }
            UserDetailVO userDetailVO = userDetailsDao.findDetail(user.getId(), UserDetailVO.Setup2FADetail);
            if (userDetailVO != null && UserAccountVO.Setup2FAstatus.VERIFIED.name().equals(userDetailVO.getValue())) {
                return user;
            }
            logger.info("Clearing 2FA configurations for {} as it is still in setup on a new login request", user);
            if (userDetailVO != null) {
                userDetailsDao.remove(userDetailVO.getId());
            }
            UserAccountVO userAccountVO = userAccountDao.findById(user.getId());
            userAccountVO.setUser2faEnabled(false);
            userAccountVO.setUser2faProvider(null);
            userAccountVO.setKeyFor2fa(null);
            userAccountDao.update(user.getId(), userAccountVO);
            return userAccountVO;
        });
    }

    @Override
    public void initializeUserTwoFactorAuthenticationProvidersMap() {
        if (userTwoFactorAuthenticationProviders != null) {
            for (final UserTwoFactorAuthenticator userTwoFactorAuthenticator : userTwoFactorAuthenticationProviders) {
                AccountManagerImpl.userTwoFactorAuthenticationProvidersMap.put(
                        userTwoFactorAuthenticator.getName().toLowerCase(), userTwoFactorAuthenticator);
            }
        }
    }

    @Override
    public void setUserTwoFactorAuthenticationProviders(final List<UserTwoFactorAuthenticator> userTwoFactorAuthenticationProviders) {
        this.userTwoFactorAuthenticationProviders = userTwoFactorAuthenticationProviders;
    }

    @Override
    public List<UserTwoFactorAuthenticator> getUserTwoFactorAuthenticationProviders() {
        return userTwoFactorAuthenticationProviders;
    }
}
