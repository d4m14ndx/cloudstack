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

import java.net.InetAddress;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.auth.UserAuthenticator;
import org.apache.cloudstack.auth.UserAuthenticator.ActionOnFailedAuthentication;
import org.apache.cloudstack.config.ApiServiceConfiguration;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.resourcedetail.dao.UserDetailsDao;
import org.apache.cloudstack.utils.baremetal.BaremetalUtils;
import org.apache.commons.codec.binary.Base64;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Component;

import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.exception.CloudAuthenticationException;
import com.cloud.user.dao.UserAccountDao;
import com.cloud.utils.ConstantTimeComparator;
import com.cloud.utils.Pair;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.net.NetUtils;

@Component
public class UserAuthenticationServiceImpl implements UserAuthenticationService {

    private static final org.apache.logging.log4j.Logger logger =
            org.apache.logging.log4j.LogManager.getLogger(UserAuthenticationServiceImpl.class);
    private static final String OAUTH2_PROVIDER_NAME = "oauth2";
    private static final long DEFAULT_USER_AUTH_TIME_DURATION_MS = 350L;

    @Inject
    private UserAccountDao userAccountDao;
    @Inject
    private UserDetailsDao userDetailsDao;
    @Inject
    private ConfigurationDao configDao;
    @Inject
    private DomainManager domainManager;
    @Inject
    @Lazy
    private AccountService accountService;

    private List<UserAuthenticator> userAuthenticators;
    private int allowedLoginAttempts;
    private long validUserLastAuthTimeDurationInMs;

    @Override
    @DB
    public void updateLoginAttempts(final Long id, final int attempts, final boolean toDisable) {
        try {
            Transaction.execute(new TransactionCallbackNoReturn() {
                @Override
                public void doInTransactionWithoutResult(TransactionStatus status) {
                    UserAccountVO user = userAccountDao.lockRow(id, true);
                    user.setLoginAttempts(attempts);
                    if (toDisable) {
                        user.setState(Account.State.DISABLED.toString());
                    }
                    userAccountDao.update(id, user);
                }
            });
        } catch (Exception e) {
            logger.error("Failed to update login attempts for user {}", () -> userAccountDao.findById(id));
        }
    }

    @Override
    public void logoutUser(long userId) {
        UserAccount userAcct = userAccountDao.findById(userId);
        if (userAcct != null) {
            ActionEventUtils.onActionEvent(userId, userAcct.getAccountId(), userAcct.getDomainId(),
                    EventTypes.EVENT_USER_LOGOUT, "user has logged out", userId,
                    ApiCommandResourceType.User.toString());
        }
    }

    @Override
    public UserAccount authenticateUser(final String username, final String password, final Long domainId,
            final InetAddress loginIpAddress, final Map<String, Object[]> requestParameters) {
        long authStartTimeInMs = System.currentTimeMillis();
        UserAccount user;
        final String[] oAuthProviderArray = (String[]) requestParameters.get(ApiConstants.PROVIDER);
        final String[] secretCodeArray = (String[]) requestParameters.get(ApiConstants.SECRET_CODE);
        String oauthProvider = oAuthProviderArray == null ? null : oAuthProviderArray[0];
        String secretCode = secretCodeArray == null ? null : secretCodeArray[0];

        if ((password != null && !password.isEmpty()) || (oauthProvider != null && secretCode != null)) {
            user = getUserAccount(username, password, domainId, requestParameters);
        } else {
            user = getUserAccountForSSO(username, domainId, requestParameters);
        }

        if (user != null) {
            if (user.getId() == User.UID_SYSTEM) {
                logger.error("Failed to authenticate user: " + username + " in domain " + domainId);
                return null;
            }
            if (BaremetalUtils.BAREMETAL_SYSTEM_ACCOUNT_NAME.equals(user.getUsername())) {
                logger.error("Won't authenticate user: " + username + " in domain " + domainId);
                return null;
            }

            final Account account = accountService.getAccount(user.getAccountId());
            final DomainVO domain = (DomainVO) domainManager.getDomain(account.getDomainId());

            final String accessAllowedCidrs = ApiServiceConfiguration.ApiAllowedSourceCidrList.valueIn(account.getId())
                    .replaceAll("\\s", "");
            final Boolean apiSourceCidrChecksEnabled = ApiServiceConfiguration.ApiSourceCidrChecksEnabled.value();

            if (apiSourceCidrChecksEnabled) {
                logger.debug("CIDRs from which account '{}' is allowed to perform API calls: {}", account.toString(),
                        accessAllowedCidrs);

                if (!NetUtils.isIpInCidrList(loginIpAddress, accessAllowedCidrs.split(","))) {
                    logger.warn("Request by account '{}' was denied since {} does not match {}", account.toString(),
                            loginIpAddress.toString().replace("/", ""), accessAllowedCidrs);
                    throw new CloudAuthenticationException("Failed to authenticate user '" + username + "' in domain '"
                            + domain.getPath() + "' from ip " + loginIpAddress.toString().replace("/", "")
                            + "; please provide valid credentials");
                }
            }

            ActionEventUtils.onActionEvent(user.getId(), user.getAccountId(), user.getDomainId(),
                    EventTypes.EVENT_USER_LOGIN, "user has logged in from IP Address " + loginIpAddress, user.getId(),
                    ApiCommandResourceType.User.toString());

            validUserLastAuthTimeDurationInMs = System.currentTimeMillis() - authStartTimeInMs;
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("User: %s in domain %d has successfully logged in, auth time duration - %d ms",
                        username, domainId, validUserLastAuthTimeDurationInMs));
            }

            user.setDetails(userDetailsDao.listDetailsKeyPairs(user.getId()));
            return user;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("User: " + username + " in domain " + domainId + " has failed to log in");
        }

        long waitTimeDurationInMs;
        long invalidUserAuthTimeDurationInMs = System.currentTimeMillis() - authStartTimeInMs;
        if (validUserLastAuthTimeDurationInMs > 0) {
            waitTimeDurationInMs = validUserLastAuthTimeDurationInMs - invalidUserAuthTimeDurationInMs;
        } else {
            waitTimeDurationInMs = DEFAULT_USER_AUTH_TIME_DURATION_MS - invalidUserAuthTimeDurationInMs;
        }

        if (waitTimeDurationInMs > 0) {
            try {
                Thread.sleep(waitTimeDurationInMs);
            } catch (InterruptedException ignored) {
            }
        }

        return null;
    }

    @Override
    public UserAccount getUserAccount(String username, String password, Long domainId,
            Map<String, Object[]> requestParameters) {
        if (logger.isDebugEnabled()) {
            logger.debug("Attempting to log in user: " + username + " in domain " + domainId);
        }
        UserAccount userAccount = userAccountDao.getUserAccount(username, domainId);

        boolean authenticated = false;
        HashSet<ActionOnFailedAuthentication> actionsOnFailedAuthenticaion = new HashSet<>();
        User.Source userSource = userAccount != null ? userAccount.getSource() : User.Source.UNKNOWN;
        for (UserAuthenticator authenticator : userAuthenticators) {
            final String[] secretCodeArray = (String[]) requestParameters.get(ApiConstants.SECRET_CODE);
            String secretCode = secretCodeArray == null ? null : secretCodeArray[0];
            if (userSource != User.Source.UNKNOWN && secretCode == null) {
                if (!authenticator.getName().equalsIgnoreCase(userSource.name())) {
                    continue;
                }
            }
            if ((secretCode != null && !authenticator.getName().equals(OAUTH2_PROVIDER_NAME))
                    || (secretCode == null && authenticator.getName().equals(OAUTH2_PROVIDER_NAME))) {
                continue;
            }
            Pair<Boolean, ActionOnFailedAuthentication> result =
                    authenticator.authenticate(username, password, domainId, requestParameters);
            if (result.first()) {
                authenticated = true;
                break;
            } else if (result.second() != null) {
                actionsOnFailedAuthenticaion.add(result.second());
            }
        }

        boolean updateIncorrectLoginCount = actionsOnFailedAuthenticaion
                .contains(ActionOnFailedAuthentication.INCREMENT_INCORRECT_LOGIN_ATTEMPT_COUNT);

        if (authenticated) {
            Domain domain = domainManager.getDomain(domainId);
            userAccount = userAccountDao.getUserAccount(username, domainId);

            if (!userAccount.getState().equalsIgnoreCase(Account.State.ENABLED.toString())
                    || !userAccount.getAccountState().equalsIgnoreCase(Account.State.ENABLED.toString())) {
                if (logger.isInfoEnabled()) {
                    logger.info("User {} in domain {} is disabled/locked (or account is disabled/locked)", userAccount,
                            domain);
                }
                throw new CloudAuthenticationException(String.format(
                        "User %s (or their account) in domain %s is disabled/locked. Please contact the administrator.",
                        userAccount, domain));
            }
            if (!isInternalAccount(userAccount.getAccountId())) {
                updateLoginAttempts(userAccount.getId(), 0, false);
            }

            return userAccount;
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Unable to authenticate user with username " + username + " in domain " + domainId);
        }

        if (userAccount == null) {
            logger.warn("Unable to find an user with username " + username + " in domain " + domainId);
            return null;
        }

        if (userAccount.getState().equalsIgnoreCase(Account.State.ENABLED.toString())) {
            if (!isInternalAccount(userAccount.getAccountId())) {
                updateLoginAttemptsWhenIncorrectLoginAttemptsEnabled(userAccount, updateIncorrectLoginCount,
                        allowedLoginAttempts);
            }
        } else {
            logger.info("User " + userAccount.getUsername() + " is disabled/locked");
        }
        return null;
    }

    @Override
    public UserAccount getUserAccountForSSO(String username, Long domainId, Map<String, Object[]> requestParameters) {
        String key = configDao.getValue("security.singlesignon.key");
        if (key == null) {
            return null;
        }

        String singleSignOnTolerance = configDao.getValue("security.singlesignon.tolerance.millis");
        if (singleSignOnTolerance == null) {
            return null;
        }

        UserAccount user = null;
        long tolerance = Long.parseLong(singleSignOnTolerance);
        String signature = null;
        long timestamp = 0L;
        StringBuffer unsignedRequestBuffer = new StringBuffer();

        List<String> parameterNames = new ArrayList<>(requestParameters.keySet());
        Collections.sort(parameterNames);

        try {
            for (String paramName : parameterNames) {
                String paramValue = ((String[]) requestParameters.get(paramName))[0];

                if ("signature".equalsIgnoreCase(paramName)) {
                    signature = paramValue;
                } else {
                    if ("timestamp".equalsIgnoreCase(paramName)) {
                        String timestampStr = paramValue;
                        try {
                            timestamp = Long.parseLong(timestampStr);
                            long currentTime = System.currentTimeMillis();
                            if (Math.abs(currentTime - timestamp) > tolerance) {
                                logger.debug("Expired timestamp passed in to login, current time = {}, timestamp = {}",
                                        currentTime, timestamp);
                                return null;
                            }
                        } catch (NumberFormatException nfe) {
                            logger.debug("Invalid timestamp passed in to login: {}", timestampStr);
                            return null;
                        }
                    }

                    if (unsignedRequestBuffer.length() != 0) {
                        unsignedRequestBuffer.append("&");
                    }
                    unsignedRequestBuffer.append(paramName).append("=")
                            .append(URLEncoder.encode(paramValue, com.cloud.utils.StringUtils.getPreferredCharset()));
                }
            }

            if (signature == null || timestamp == 0L) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Missing parameters in login request, signature = " + signature + ", timestamp = "
                            + timestamp);
                }
                return null;
            }

            String unsignedRequest = unsignedRequestBuffer.toString().toLowerCase().replaceAll("\\+", "%20");

            Mac mac = Mac.getInstance("HmacSHA1");
            SecretKeySpec keySpec = new SecretKeySpec(key.getBytes(), "HmacSHA1");
            mac.init(keySpec);
            mac.update(unsignedRequest.getBytes());
            byte[] encryptedBytes = mac.doFinal();
            String computedSignature = new String(Base64.encodeBase64(encryptedBytes));
            boolean equalSig = ConstantTimeComparator.compareStrings(signature, computedSignature);
            if (!equalSig) {
                logger.info("User signature: " + signature + " is not equaled to computed signature: "
                        + computedSignature);
            } else {
                user = userAccountDao.getUserAccount(username, domainId);
            }
        } catch (Exception ex) {
            logger.error("Exception authenticating user", ex);
            return null;
        }

        return user;
    }

    @Override
    public void updateLoginAttemptsWhenIncorrectLoginAttemptsEnabled(UserAccount account,
            boolean updateIncorrectLoginCount, int allowedLoginAttempts) {
        int attemptsMade = account.getLoginAttempts() + 1;
        if (allowedLoginAttempts <= 0 || !updateIncorrectLoginCount) {
            return;
        }
        if (attemptsMade < allowedLoginAttempts) {
            updateLoginAttempts(account.getId(), attemptsMade, false);
            logger.warn("Login attempt failed. You have " + (allowedLoginAttempts - attemptsMade)
                    + " attempt(s) remaining");
        } else {
            updateLoginAttempts(account.getId(), allowedLoginAttempts, true);
            logger.warn("User {} has been disabled due to multiple failed login attempts. Please contact admin.",
                    account);
        }
    }

    private boolean isInternalAccount(long accountId) {
        Account activeAccount = accountService.getActiveAccountById(accountId);
        if (activeAccount == null) {
            return false;
        }
        return accountService.isRootAdmin(accountId) || activeAccount.getType() == Account.Type.ADMIN;
    }

    @Override
    public void setUserAuthenticators(List<UserAuthenticator> userAuthenticators) {
        this.userAuthenticators = userAuthenticators;
    }

    @Override
    public void setAllowedLoginAttempts(int allowedLoginAttempts) {
        this.allowedLoginAttempts = allowedLoginAttempts;
    }
}
