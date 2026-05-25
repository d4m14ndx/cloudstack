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
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.auth.UserAuthenticator;

/**
 * Login/logout/authentication helpers extracted from {@link AccountManagerImpl}.
 *
 * <p>{@link AccountManagerImpl} keeps the public wrappers for
 * {@code authenticateUser}, {@code logoutUser}, and {@code updateLoginAttempts}.
 * The god class also keeps the spy-pinned
 * {@code updateLoginAttemptsWhenIncorrectLoginAttemptsEnabled} wrapper body so
 * {@code AccountManagerImplTest} can still verify the inner
 * {@code updateLoginAttempts(...)} call on the spy.
 */
public interface UserAuthenticationService {

    void updateLoginAttempts(Long id, int attempts, boolean toDisable);

    void logoutUser(long userId);

    UserAccount authenticateUser(String username, String password, Long domainId,
            InetAddress loginIpAddress, Map<String, Object[]> requestParameters);

    UserAccount getUserAccount(String username, String password, Long domainId,
            Map<String, Object[]> requestParameters);

    UserAccount getUserAccountForSSO(String username, Long domainId,
            Map<String, Object[]> requestParameters);

    void updateLoginAttemptsWhenIncorrectLoginAttemptsEnabled(UserAccount account,
            boolean updateIncorrectLoginCount, int allowedLoginAttempts);

    void setUserAuthenticators(List<UserAuthenticator> userAuthenticators);

    void setAllowedLoginAttempts(int allowedLoginAttempts);
}
