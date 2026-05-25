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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.RolePermissionEntity;
import org.apache.cloudstack.acl.RoleService;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPairPermission;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPairService;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.dao.AccountDao;

/**
 * API key permission and superset checks — extracted from
 * {@link AccountManagerImpl}.
 *
 * @see ApiKeyPermissionService
 */
@Component
public class ApiKeyPermissionServiceImpl implements ApiKeyPermissionService {

    private static final Logger logger = LogManager.getLogger(ApiKeyPermissionServiceImpl.class);

    @Inject
    private ApiKeyPairService apiKeyPairService;
    @Inject
    private RoleService roleService;
    @Inject
    private AccountDao accountDao;

    @Override
    public String getAccessingApiKey(BaseCmd cmd) {
        try {
            if (cmd instanceof BaseAsyncCmd && ((BaseAsyncCmd) cmd).getJob().toString().contains("\"signature\"")) {
                return parseApiKeyFromAsyncJob((BaseAsyncCmd) cmd);
            }
            boolean accessedByApiKey = cmd.getFullUrlParams().containsKey(ApiConstants.SIGNATURE);
            String accessingApiKey = cmd.getFullUrlParams().get("apiKey");
            if (accessedByApiKey) {
                return accessingApiKey;
            }
        } catch (NullPointerException e) {
            logger.info("Accessing API through session.");
        }
        return null;
    }

    private String parseApiKeyFromAsyncJob(BaseAsyncCmd cmd) {
        String jobString = cmd.getJob().toString();
        int indexOfApiKey = jobString.indexOf("apiKey") + 9;
        return jobString.substring(indexOfApiKey, jobString.indexOf("\"", indexOfApiKey));
    }

    @Override
    public List<RolePermissionEntity> getAllKeypairPermissions(String apiKey) {
        if (apiKey == null) {
            throw new InvalidParameterValueException("API key not present in the request's URL and, thus, unable to fetch API key rules.");
        }
        ApiKeyPair apiKeyPair = apiKeyPairService.findByApiKey(apiKey);
        Account account = accountDao.findById(apiKeyPair.getAccountId());
        List<ApiKeyPairPermission> keyPairPermissions = apiKeyPairService.findAllPermissionsByKeyPairId(apiKeyPair.getId(), account.getRoleId());
        return new ArrayList<>(keyPairPermissions);
    }

    @Override
    public Boolean isAccessingKeypairSuperset(ApiKeyPair accessedKeyPair, BaseCmd cmd) {
        String apiKey = getAccessingApiKey(cmd);
        if (apiKey == null) {
            return Boolean.TRUE;
        }
        ApiKeyPair accessingKeyPair = apiKeyPairService.findByApiKey(apiKey);
        return isApiKeySupersetOfPermission(new ArrayList<>(getAllKeypairPermissions(accessingKeyPair.getApiKey())), new ArrayList<>(getAllKeypairPermissions(accessedKeyPair.getApiKey())));
    }

    @Override
    public Boolean isApiKeySupersetOfPermission(List<RolePermissionEntity> baseKeyPairPermissions, List<RolePermissionEntity> comparedPermissions) {
        Map<String, RolePermissionEntity> apiNameToBaseKeyPermissions = roleService.getRoleRulesAndPermissions(baseKeyPairPermissions);
        return roleService.roleHasPermission(apiNameToBaseKeyPermissions, comparedPermissions);
    }

    @Override
    public void validateKeyPairIsNotNull(ApiKeyPair keyPair) {
        if (keyPair == null) {
            logger.info("Keypair not found.");
            throw new InvalidParameterValueException("Could not complete request.");
        }
    }

    @Override
    public void validateAccessingKeyPairPermissionsIsSupersetOfAccessedKeyPair(ApiKeyPair keyPair, BaseCmd cmd) {
        if (!isAccessingKeypairSuperset(keyPair, cmd)) {
            logger.info("Accessing API key pair [{}] has less permissions than accessed API key pair.", keyPair.getId());
            throw new PermissionDeniedException("Could not complete request.");
        }
    }
}
