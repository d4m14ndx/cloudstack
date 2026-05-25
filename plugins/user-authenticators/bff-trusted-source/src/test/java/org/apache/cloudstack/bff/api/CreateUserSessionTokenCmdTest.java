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
package org.apache.cloudstack.bff.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.net.InetAddress;
import java.util.HashMap;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.apache.cloudstack.acl.RolePermission;
import org.apache.cloudstack.acl.RolePermissionEntity;
import org.apache.cloudstack.acl.RoleService;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ApiServerService;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.ResponseObject;
import org.apache.cloudstack.api.response.LoginCmdResponse;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import com.cloud.domain.Domain;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.DomainService;
import com.cloud.user.User;
import com.cloud.user.UserAccount;

public class CreateUserSessionTokenCmdTest {

    private CreateUserSessionTokenCmd cmd;
    private FakeApiServer apiServer;
    private FakeAccountService accountService;
    private FakeDomainService domainService;
    private FakeRoleService roleService;
    private HttpSession session;
    private HttpServletResponse response;
    private InetAddress remoteAddress;
    private Map<String, Object[]> params;
    private int sequence;

    @Before
    public void setUp() throws Exception {
        cmd = new CreateUserSessionTokenCmd();
        apiServer = new FakeApiServer();
        accountService = new FakeAccountService();
        domainService = new FakeDomainService();
        roleService = new FakeRoleService();
        session = proxy(HttpSession.class, defaultHandler());
        response = proxy(HttpServletResponse.class, defaultHandler());
        remoteAddress = InetAddress.getByName("127.0.0.1");
        params = new HashMap<>();
        params.put(ApiConstants.COMMAND, new String[] {CreateUserSessionTokenCmd.APINAME});
        params.put(ApiConstants.USERNAME, new String[] {"alice"});

        cmd.apiServer = proxy(ApiServerService.class, apiServer);
        cmd._accountService = proxy(AccountService.class, accountService);
        cmd._domainService = proxy(DomainService.class, domainService);
        cmd.roleService = proxy(RoleService.class, roleService);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    @Test
    public void authenticateRejectsNonPostBeforeVerifyingSignedRequest() {
        HttpServletRequest request = requestWithMethod("GET");

        ServerApiException exception = assertThrows(ServerApiException.class, () ->
                cmd.authenticate(CreateUserSessionTokenCmd.APINAME, params, session, remoteAddress, "json", new StringBuilder(), request, response));

        assertEquals(ApiErrorCode.METHOD_NOT_ALLOWED, exception.getErrorCode());
        assertEquals(0, apiServer.verifyRequestCalls);
        assertEquals(0, accountService.getActiveUserAccountCalls);
        assertEquals(0, domainService.findDomainCalls);
    }

    @Test
    public void authenticateFailsClosedWhenSignedRequestCannotBeVerifiedBeforeTargetLookup() {
        HttpServletRequest request = requestWithMethod("POST");
        apiServer.verifyRequestResult = false;

        ServerApiException exception = assertThrows(ServerApiException.class, () ->
                cmd.authenticate(CreateUserSessionTokenCmd.APINAME, params, session, remoteAddress, "json", new StringBuilder(), request, response));

        assertEquals(ApiErrorCode.UNAUTHORIZED, exception.getErrorCode());
        assertEquals(1, apiServer.verifyRequestCalls);
        assertEquals(0, apiServer.createUserSessionTokenCalls);
        assertEquals(0, accountService.getActiveUserAccountCalls);
        assertEquals(0, domainService.findDomainCalls);
    }

    @Test
    public void authenticateRequiresImpersonateUserPermissionAfterSignedVerificationAndBeforeTargetLookup() {
        registerCallerWithRole(42L);
        HttpServletRequest request = requestWithMethod("POST");
        apiServer.verifyRequestResult = true;
        roleService.enabled = true;
        roleService.permission = null;

        ServerApiException exception = assertThrows(ServerApiException.class, () ->
                cmd.authenticate(CreateUserSessionTokenCmd.APINAME, params, session, remoteAddress, "json", new StringBuilder(), request, response));

        assertEquals(ApiErrorCode.UNAUTHORIZED, exception.getErrorCode());
        assertTrue(apiServer.verifyRequestOrder < roleService.findPermissionOrder);
        assertEquals(0, apiServer.createUserSessionTokenCalls);
        assertEquals(0, accountService.getActiveUserAccountCalls);
        assertEquals(0, domainService.findDomainCalls);
    }

    @Test
    public void authenticateResolvesTargetAndMintsSessionOnlyAfterSignedVerificationAndPermissionCheck() {
        registerCallerWithRole(42L);
        HttpServletRequest request = requestWithMethod("POST");
        apiServer.verifyRequestResult = true;
        roleService.enabled = true;
        roleService.permission = allowPermission();
        domainService.domain = domainWithId(7L);
        accountService.targetUser = userAccount("alice", 21L);
        LoginCmdResponse tokenResponse = new LoginCmdResponse();
        tokenResponse.setResponseName("createusersessiontokenresponse");
        tokenResponse.setUsername("alice");
        tokenResponse.setSessionKey("session-key");
        apiServer.tokenResponse = tokenResponse;

        String serializedResponse = cmd.authenticate(CreateUserSessionTokenCmd.APINAME, params, session, remoteAddress, "json", new StringBuilder(), request, response);

        assertTrue(serializedResponse.contains("createusersessiontokenresponse"));
        assertTrue(apiServer.verifyRequestOrder < roleService.findPermissionOrder);
        assertTrue(roleService.findPermissionOrder < domainService.findDomainOrder);
        assertTrue(domainService.findDomainOrder < accountService.getActiveUserAccountOrder);
        assertTrue(accountService.getActiveUserAccountOrder < apiServer.createUserSessionTokenOrder);
        assertSame(accountService.targetUser, apiServer.createdTargetUser);
    }

    private HttpServletRequest requestWithMethod(String method) {
        return proxy(HttpServletRequest.class, (proxy, invokedMethod, args) -> {
            if ("getMethod".equals(invokedMethod.getName())) {
                return method;
            }
            return defaultValue(invokedMethod.getReturnType());
        });
    }

    private void registerCallerWithRole(long roleId) {
        User callerUser = proxy(User.class, defaultHandler());
        Account callerAccount = accountWithRole(roleId);
        CallContext.register(callerUser, callerAccount);
    }

    private Account accountWithRole(long roleId) {
        return proxy(Account.class, (proxy, method, args) -> {
            if ("getRoleId".equals(method.getName())) {
                return roleId;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private Domain domainWithId(long id) {
        return proxy(Domain.class, (proxy, method, args) -> {
            if ("getId".equals(method.getName())) {
                return id;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private UserAccount userAccount(String username, long id) {
        return proxy(UserAccount.class, (proxy, method, args) -> {
            if ("getId".equals(method.getName())) {
                return id;
            }
            if ("getUsername".equals(method.getName())) {
                return username;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private RolePermission allowPermission() {
        return proxy(RolePermission.class, (proxy, method, args) -> {
            if ("getPermission".equals(method.getName())) {
                return RolePermissionEntity.Permission.ALLOW;
            }
            return defaultValue(method.getReturnType());
        });
    }

    private InvocationHandler defaultHandler() {
        return (proxy, method, args) -> defaultValue(method.getReturnType());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T)Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (boolean.class.equals(returnType)) {
            return false;
        }
        if (char.class.equals(returnType)) {
            return '\0';
        }
        if (byte.class.equals(returnType)) {
            return (byte)0;
        }
        if (short.class.equals(returnType)) {
            return (short)0;
        }
        if (int.class.equals(returnType)) {
            return 0;
        }
        if (long.class.equals(returnType)) {
            return 0L;
        }
        if (float.class.equals(returnType)) {
            return 0f;
        }
        if (double.class.equals(returnType)) {
            return 0d;
        }
        return null;
    }

    private class FakeApiServer implements InvocationHandler {
        boolean verifyRequestResult;
        ResponseObject tokenResponse;
        UserAccount createdTargetUser;
        int verifyRequestCalls;
        int createUserSessionTokenCalls;
        int verifyRequestOrder;
        int createUserSessionTokenOrder;

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            if ("verifyRequest".equals(method.getName())) {
                verifyRequestCalls++;
                verifyRequestOrder = ++sequence;
                return verifyRequestResult;
            }
            if ("createUserSessionToken".equals(method.getName())) {
                createUserSessionTokenCalls++;
                createUserSessionTokenOrder = ++sequence;
                createdTargetUser = (UserAccount)args[1];
                return tokenResponse;
            }
            if ("getDomainId".equals(method.getName())) {
                return null;
            }
            return defaultValue(method.getReturnType());
        }
    }

    private class FakeAccountService implements InvocationHandler {
        UserAccount targetUser;
        int getActiveUserAccountCalls;
        int getActiveUserAccountOrder;

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            if ("getActiveUserAccount".equals(method.getName())) {
                getActiveUserAccountCalls++;
                getActiveUserAccountOrder = ++sequence;
                return targetUser;
            }
            return defaultValue(method.getReturnType());
        }
    }

    private class FakeDomainService implements InvocationHandler {
        Domain domain;
        int findDomainCalls;
        int findDomainOrder;

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            if ("findDomainByIdOrPath".equals(method.getName())) {
                findDomainCalls++;
                findDomainOrder = ++sequence;
                return domain;
            }
            return defaultValue(method.getReturnType());
        }
    }

    private class FakeRoleService implements InvocationHandler {
        boolean enabled;
        RolePermission permission;
        int findPermissionOrder;

        @Override
        public Object invoke(Object proxy, java.lang.reflect.Method method, Object[] args) {
            if ("isEnabled".equals(method.getName())) {
                return enabled;
            }
            if ("findRolePermissionByRoleIdAndRule".equals(method.getName())) {
                findPermissionOrder = ++sequence;
                return permission;
            }
            return defaultValue(method.getReturnType());
        }
    }
}
