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
package com.cloud.api;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.RoleVO;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPairPermission;
import org.apache.cloudstack.acl.dao.RoleDao;
import org.apache.cloudstack.api.response.ApiKeyPairResponse;
import org.apache.cloudstack.api.response.BaseRolePermissionResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.springframework.stereotype.Component;

import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.user.AccountVO;
import com.cloud.user.ApiKeyPairState;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;

@Component
public class ApiKeyPairResponseService {

    @Inject
    private RoleDao roleDao;

    @Inject
    private AccountDao accountDao;

    @Inject
    private DomainDao domainDao;

    public ApiKeyPairResponse createKeyPairResponse(ApiKeyPair keyPair) {
        ApiKeyPairResponse apiKeyPairResponse = new ApiKeyPairResponse();

        populateApiKeyPairInApiKeyPairResponse(keyPair, apiKeyPairResponse);
        populateUserInApiKeyPairResponse(keyPair, apiKeyPairResponse);

        AccountVO account = accountDao.findByIdIncludingRemoved(keyPair.getAccountId());
        apiKeyPairResponse.setAccountId(account.getUuid());
        apiKeyPairResponse.setAccountName(account.getAccountName());
        apiKeyPairResponse.setAccountType(account.getType().toString());

        populateDomainInApiKeyPairResponse(account.getDomainId(), apiKeyPairResponse);
        populateRoleInApiKeyPairResponse(account.getRoleId(), apiKeyPairResponse);

        return apiKeyPairResponse;
    }

    protected void populateRoleInApiKeyPairResponse(Long roleId, ApiKeyPairResponse apiKeyPairResponse) {
        RoleVO roleVO = roleDao.findById(roleId);
        apiKeyPairResponse.setRoleId(roleVO.getUuid());
        apiKeyPairResponse.setRoleName(roleVO.getName());
        apiKeyPairResponse.setRoleType(roleVO.getRoleType().name());
    }

    protected static void populateApiKeyPairInApiKeyPairResponse(ApiKeyPair keyPair, ApiKeyPairResponse apiKeyPairResponse) {
        apiKeyPairResponse.setName(keyPair.getName());
        apiKeyPairResponse.setApiKey(keyPair.getApiKey());
        apiKeyPairResponse.setSecretKey(keyPair.getSecretKey());
        apiKeyPairResponse.setDescription(keyPair.getDescription());
        apiKeyPairResponse.setId(keyPair.getUuid());
        apiKeyPairResponse.setCreated(keyPair.getCreated());
        apiKeyPairResponse.setStartDate(keyPair.getStartDate());
        apiKeyPairResponse.setEndDate(keyPair.getEndDate());

        ApiKeyPairState state = ApiKeyPairState.ENABLED;
        if (keyPair.getRemoved() != null) {
            state = ApiKeyPairState.REMOVED;
        } else if (keyPair.hasEndDatePassed()) {
            state = ApiKeyPairState.EXPIRED;
        }
        apiKeyPairResponse.setState(state);
    }

    protected void populateUserInApiKeyPairResponse(ApiKeyPair keyPair, ApiKeyPairResponse apiKeyPairResponse) {
        User user = findUserById(keyPair.getUserId());
        apiKeyPairResponse.setUserId(user.getUuid());
        apiKeyPairResponse.setUsername(user.getUsername());
    }

    protected void populateDomainInApiKeyPairResponse(Long domainId, ApiKeyPairResponse apiKeyPairResponse) {
        DomainVO domainVO = domainDao.findById(domainId);
        apiKeyPairResponse.setDomainId(domainVO.getUuid());
        apiKeyPairResponse.setDomainName(domainVO.getName());
        StringBuilder domainPath = new StringBuilder("ROOT");
        (domainPath.append(domainVO.getPath())).deleteCharAt(domainPath.length() - 1);
        apiKeyPairResponse.setDomainPath(domainPath.toString());
    }

    public ListResponse<BaseRolePermissionResponse> createKeypairPermissionsResponse(final List<ApiKeyPairPermission> permissions) {
        final ListResponse<BaseRolePermissionResponse> response = new ListResponse<>();
        final List<BaseRolePermissionResponse> permissionResponses = new ArrayList<>();
        for (final ApiKeyPairPermission permission : permissions) {
            BaseRolePermissionResponse permissionResponse = new BaseRolePermissionResponse();
            permissionResponse.setRule(permission.getRule());
            permissionResponse.setRulePermission(permission.getPermission());
            permissionResponse.setDescription(permission.getDescription());
            permissionResponse.setObjectName("keypermission");
            permissionResponses.add(permissionResponse);
        }
        response.setResponses(permissionResponses);
        return response;
    }

    protected User findUserById(Long userId) {
        return ApiDBUtils.findUserById(userId);
    }
}
