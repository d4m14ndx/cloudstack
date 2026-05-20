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

import java.net.InetAddress;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;

import org.apache.cloudstack.acl.RolePermission;
import org.apache.cloudstack.acl.RolePermissionEntity.Permission;
import org.apache.cloudstack.acl.RoleType;
import org.apache.cloudstack.api.APICommand;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ApiServerService;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.api.Parameter;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.api.auth.APIAuthenticationType;
import org.apache.cloudstack.api.auth.APIAuthenticator;
import org.apache.cloudstack.api.auth.PluggableAPIAuthenticator;
import org.apache.cloudstack.api.response.UserSessionTokenResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.lang3.StringUtils;

import com.cloud.api.response.ApiResponseSerializer;
import com.cloud.domain.Domain;
import com.cloud.exception.CloudAuthenticationException;
import com.cloud.user.Account;
import com.cloud.user.UserAccount;

@APICommand(name = CreateUserSessionTokenCmd.APINAME,
        description = "Creates a CloudStack session token for a target user from a trusted BFF service account.",
        responseObject = UserSessionTokenResponse.class,
        requestHasSensitiveInfo = false,
        responseHasSensitiveInfo = true,
        entityType = {},
        authorized = {RoleType.Admin},
        since = "4.23.0.0",
        httpMethod = "POST")
public class CreateUserSessionTokenCmd extends BaseCmd implements APIAuthenticator {

    public static final String APINAME = "createUserSessionToken";
    public static final String IMPERSONATE_USER_PERMISSION = "impersonateUser";

    @Parameter(name = ApiConstants.USERNAME, type = CommandType.STRING, required = true,
            description = "Username of the target user to mint a session for.")
    private String username;

    @Parameter(name = ApiConstants.DOMAIN, type = CommandType.STRING,
            description = "Path of the domain that the target user belongs to. If no domain is passed in, ROOT is assumed.")
    private String domain;

    @Parameter(name = ApiConstants.DOMAIN__ID, type = CommandType.LONG,
            description = "ID or UUID of the domain that the target user belongs to. If both domain and domainId are passed in, domainId takes precedence.")
    private Long domainId;

    @Inject
    ApiServerService apiServer;

    @Override
    public long getEntityOwnerId() {
        return Account.Type.NORMAL.ordinal();
    }

    @Override
    public void execute() throws ServerApiException {
        throw new ServerApiException(ApiErrorCode.METHOD_NOT_ALLOWED, "This is an authentication api, cannot be used directly");
    }

    @Override
    public String authenticate(String command, Map<String, Object[]> params, HttpSession session, InetAddress remoteAddress, String responseType,
            StringBuilder auditTrailSb, HttpServletRequest req, HttpServletResponse resp) throws ServerApiException {
        if (!HTTPMethod.POST.name().equalsIgnoreCase(req.getMethod())) {
            throw new ServerApiException(ApiErrorCode.METHOD_NOT_ALLOWED, "Please use HTTP POST to create a user session token");
        }

        if (!apiServer.verifyRequest(params, null, remoteAddress)) {
            throw new ServerApiException(ApiErrorCode.UNAUTHORIZED, "unable to verify user credentials and/or request signature");
        }

        verifyCallerCanImpersonate();

        String targetUsername = getRequiredParameter(params, ApiConstants.USERNAME);
        Long targetDomainId = getDomainIdFromParams(params, auditTrailSb, responseType);
        String targetDomainPath = getDomainName(params, auditTrailSb);

        try {
            Domain userDomain = _domainService.findDomainByIdOrPath(targetDomainId, targetDomainPath);
            if (userDomain == null) {
                throw new CloudAuthenticationException("Unable to find the domain from the path " + targetDomainPath);
            }
            UserAccount targetUser = _accountService.getActiveUserAccount(targetUsername, userDomain.getId());
            if (targetUser == null) {
                throw new CloudAuthenticationException("Unable to find active target user " + targetUsername);
            }
            return ApiResponseSerializer.toSerializedString(apiServer.createUserSessionToken(session, targetUser, remoteAddress, params), responseType);
        } catch (CloudAuthenticationException e) {
            String message = e.getMessage() == null ? "failed to create user session token" : e.getMessage();
            auditTrailSb.append(" ").append(ApiErrorCode.ACCOUNT_ERROR).append(" ").append(message);
            throw new ServerApiException(ApiErrorCode.ACCOUNT_ERROR, message);
        }
    }

    protected Long getDomainIdFromParams(Map<String, Object[]> params, StringBuilder auditTrailSb, String responseType) {
        String domainIdString = apiServer.getDomainId(params);
        if (StringUtils.isBlank(domainIdString)) {
            return null;
        }
        try {
            Long fetchedDomainId = apiServer.fetchDomainId(domainIdString);
            Long resolvedDomainId = fetchedDomainId == null ? Long.parseLong(domainIdString) : fetchedDomainId;
            auditTrailSb.append(" domainid=").append(resolvedDomainId);
            return resolvedDomainId;
        } catch (NumberFormatException e) {
            throw new ServerApiException(ApiErrorCode.UNAUTHORIZED,
                    apiServer.getSerializedApiError(HttpServletResponse.SC_UNAUTHORIZED, "Invalid domain id entered, please enter a valid one", params, responseType));
        }
    }

    protected String getDomainName(Map<String, Object[]> params, StringBuilder auditTrailSb) {
        String[] domainName = (String[])params.get(ApiConstants.DOMAIN);
        if (domainName == null || domainName.length == 0) {
            return null;
        }
        String domainPath = domainName[0];
        auditTrailSb.append(" domain=").append(domainPath);
        if (domainPath != null) {
            if (!domainPath.endsWith("/")) {
                domainPath += "/";
            }
            if (!domainPath.startsWith("/")) {
                domainPath = "/" + domainPath;
            }
        }
        return domainPath;
    }

    protected void verifyCallerCanImpersonate() {
        if (roleService == null || !roleService.isEnabled()) {
            throw new ServerApiException(ApiErrorCode.UNAUTHORIZED, "Caller is missing the impersonateUser permission");
        }

        Account callingAccount = CallContext.current().getCallingAccount();
        RolePermission permission = roleService.findRolePermissionByRoleIdAndRule(callingAccount.getRoleId(), IMPERSONATE_USER_PERMISSION);
        if (permission == null || !Permission.ALLOW.equals(permission.getPermission())) {
            throw new ServerApiException(ApiErrorCode.UNAUTHORIZED, "Caller is missing the impersonateUser permission");
        }
    }

    protected String getRequiredParameter(Map<String, Object[]> params, String name) {
        String[] values = (String[])params.get(name);
        if (values == null || values.length == 0 || StringUtils.isBlank(values[0])) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "Missing required parameter " + name);
        }
        return values[0];
    }

    @Override
    public APIAuthenticationType getAPIType() {
        return APIAuthenticationType.LOGIN_API;
    }

    @Override
    public void setAuthenticators(List<PluggableAPIAuthenticator> authenticators) {
    }
}
