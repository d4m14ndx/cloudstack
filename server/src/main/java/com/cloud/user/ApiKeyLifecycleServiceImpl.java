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

import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import jakarta.inject.Inject;

import org.apache.cloudstack.acl.ApiKeyPairPermissionVO;
import org.apache.cloudstack.acl.ApiKeyPairVO;
import org.apache.cloudstack.acl.Role;
import org.apache.cloudstack.acl.RolePermission;
import org.apache.cloudstack.acl.RolePermissionEntity;
import org.apache.cloudstack.acl.RoleService;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.acl.dao.ApiKeyPairDao;
import org.apache.cloudstack.acl.dao.ApiKeyPairPermissionsDao;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.commons.codec.binary.Base64;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.ApiDBUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserAccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.Ternary;

/**
 * Generation, persistence and removal of CloudStack API key pairs — extracted
 * from {@link AccountManagerImpl}.
 *
 * @see ApiKeyLifecycleService
 */
@Component
public class ApiKeyLifecycleServiceImpl implements ApiKeyLifecycleService {

    private static final Logger logger = LogManager.getLogger(ApiKeyLifecycleServiceImpl.class);

    @Inject
    private ApiKeyPairDao apiKeyPairDao;
    @Inject
    private ApiKeyPairPermissionsDao apiKeyPairPermissionsDao;
    @Inject
    private ApiKeyPermissionService apiKeyPermissionService;
    @Inject
    private RoleService roleService;
    @Inject
    private AccountDao accountDao;
    @Inject
    private UserDao userDao;
    @Inject
    private UserAccountDao userAccountDao;

    @Override
    public String createUserApiKey(long userId, ApiKeyPairVO newApiKeyPair) {
        try {
            String encodedKey;
            ApiKeyPair keyPair;
            int retryLimit = 10;
            do {
                // FIXME: what algorithm should we use for API keys?
                KeyGenerator generator = KeyGenerator.getInstance("HmacSHA1");
                SecretKey key = generator.generateKey();
                encodedKey = Base64.encodeBase64URLSafeString(key.getEncoded());
                keyPair = apiKeyPairDao.findByApiKey(encodedKey);
                retryLimit--;
            } while ((keyPair != null) && (retryLimit >= 0));

            if (keyPair != null) {
                return null;
            }
            newApiKeyPair.setApiKey(encodedKey);
            return encodedKey;
        } catch (NoSuchAlgorithmException ex) {
            logger.error("error generating secret key for user {}", userAccountDao.findById(userId), ex);
        }
        return null;
    }

    @Override
    public String createUserSecretKey(long userId, ApiKeyPairVO newApiKeyPair) {
        try {
            String encodedKey;
            int retryLimit = 10;
            ApiKeyPairVO keyPairVO;
            do {
                KeyGenerator generator = KeyGenerator.getInstance("HmacSHA1");
                SecretKey key = generator.generateKey();
                encodedKey = Base64.encodeBase64URLSafeString(key.getEncoded());
                keyPairVO = apiKeyPairDao.findBySecretKey(encodedKey);
                retryLimit--;
            } while ((keyPairVO != null) && (retryLimit >= 0));

            if (keyPairVO != null) {
                return null;
            }

            newApiKeyPair.setSecretKey(encodedKey);
            return encodedKey;
        } catch (NoSuchAlgorithmException ex) {
            logger.error("error generating secret key for user {}", userAccountDao.findById(userId), ex);
        }
        return null;
    }

    @Override
    public ApiKeyPairVO validateAndPersistKeyPairAndPermissions(Account account, ApiKeyPairVO newApiKeyPair,
                                                                List<Map<String, Object>> rules, BaseCmd cmd) {
        String accessingApiKey = apiKeyPermissionService.getAccessingApiKey(cmd);
        final Role accountRole = roleService.findRole(account.getRoleId());
        List<RolePermissionEntity> allPermissions = accessingApiKey == null ?
                roleService.findAllRolePermissionsEntityBy(accountRole.getId(), true) : apiKeyPermissionService.getAllKeypairPermissions(accessingApiKey);

        List<RolePermissionEntity> permissions = new ArrayList<>();
        for (Map<String, Object> ruleDetail : rules) {
            String rule = ruleDetail.get(ApiConstants.RULE).toString();
            RolePermission.Permission rulePermission = (RolePermission.Permission) ruleDetail.get(ApiConstants.PERMISSION);
            String ruleDescription = (String) ruleDetail.get(ApiConstants.DESCRIPTION);
            permissions.add(new ApiKeyPairPermissionVO(0, rule, rulePermission, ruleDescription));
        }

        if (!apiKeyPermissionService.isApiKeySupersetOfPermission(allPermissions, permissions)) {
            throw new InvalidParameterValueException(String.format("The key pair being created has a bigger set of permissions than the account [%s] " +
                    "that owns it. This is not allowed.", account.getUuid()));
        }

        ApiKeyPairVO savedApiKeyPair = apiKeyPairDao.persist(newApiKeyPair);
        permissions.forEach(permission -> {
            ApiKeyPairPermissionVO permissionVO = (ApiKeyPairPermissionVO) permission;
            permissionVO.setApiKeyPairId(savedApiKeyPair.getId());
            apiKeyPairPermissionsDao.persist(permissionVO);
        });
        return savedApiKeyPair;
    }

    @Override
    public void internalDeleteApiKey(ApiKeyPair keyPair) {
        List<ApiKeyPairPermissionVO> permissions = apiKeyPairPermissionsDao.findAllByApiKeyPairId(keyPair.getId());
        for (org.apache.cloudstack.acl.apikeypair.ApiKeyPairPermission permission : permissions) {
            apiKeyPairPermissionsDao.remove(permission.getId());
        }
        apiKeyPairDao.remove(keyPair.getId());
    }

    @Override
    public void removeApiKeyPairIfExpired(ApiKeyPair apiKeyPair) {
        if (apiKeyPair.hasEndDatePassed()) {
            internalDeleteApiKey(apiKeyPair);
        }
    }

    @Override
    public ApiKeyPair getKeyPairById(Long id) {
        return apiKeyPairDao.findById(id);
    }

    @Override
    public ApiKeyPair getKeyPairByApiKey(String apiKey) {
        return apiKeyPairDao.findByApiKey(apiKey);
    }

    @Override
    public ApiKeyPair getLatestUserKeyPair(Long userId) {
        return ApiDBUtils.searchForLatestUserKeyPair(userId);
    }

    @Override
    public Ternary<User, Account, ApiKeyPair> findUserByApiKey(String apiKey) {
        ApiKeyPairVO keyPairVO = apiKeyPairDao.findByApiKey(apiKey);
        if (keyPairVO == null) {
            return null;
        }

        User user = userDao.getUser(keyPairVO.getUserId());
        Account account = accountDao.findById(keyPairVO.getAccountId());
        return new Ternary<>(user, account, keyPairVO);
    }
}
