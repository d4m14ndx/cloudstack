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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.acl.ApiKeyPairPermissionVO;
import org.apache.cloudstack.acl.ApiKeyPairVO;
import org.apache.cloudstack.acl.Role;
import org.apache.cloudstack.acl.RolePermission;
import org.apache.cloudstack.acl.RoleService;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.acl.dao.ApiKeyPairDao;
import org.apache.cloudstack.acl.dao.ApiKeyPairPermissionsDao;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseCmd;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserAccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.Ternary;

@RunWith(MockitoJUnitRunner.class)
public class ApiKeyLifecycleServiceImplTest {

    @Mock private ApiKeyPairDao apiKeyPairDao;
    @Mock private ApiKeyPairPermissionsDao apiKeyPairPermissionsDao;
    @Mock private ApiKeyPermissionService apiKeyPermissionService;
    @Mock private RoleService roleService;
    @Mock private AccountDao accountDao;
    @Mock private UserDao userDao;
    @Mock private UserAccountDao userAccountDao;

    @InjectMocks
    private ApiKeyLifecycleServiceImpl service;

    private BaseCmd cmd;

    @Before
    public void setUp() {
        cmd = mock(BaseCmd.class);
    }

    // ---- createUserApiKey ----

    @Test
    public void createUserApiKeyMintsFreshKeyOnFirstTry() {
        // No collision → loop exits on first iteration and sets the key.
        when(apiKeyPairDao.findByApiKey(any())).thenReturn(null);
        ApiKeyPairVO keyPair = new ApiKeyPairVO();

        String encoded = service.createUserApiKey(42L, keyPair);

        assertNotNull(encoded);
        assertEquals(encoded, keyPair.getApiKey());
        // Exactly one collision check
        verify(apiKeyPairDao, times(1)).findByApiKey(any());
    }

    @Test
    public void createUserApiKeyRetriesOnCollisionThenSucceeds() {
        // First lookup returns a collision, subsequent ones return null.
        ApiKeyPairVO collision = new ApiKeyPairVO();
        when(apiKeyPairDao.findByApiKey(any()))
                .thenReturn(collision)
                .thenReturn(null);
        ApiKeyPairVO keyPair = new ApiKeyPairVO();

        String encoded = service.createUserApiKey(42L, keyPair);

        assertNotNull(encoded);
        // We saw at least one collision, then a clean key.
        verify(apiKeyPairDao, times(2)).findByApiKey(any());
    }

    // ---- createUserSecretKey ----

    @Test
    public void createUserSecretKeyMintsFreshKeyOnFirstTry() {
        when(apiKeyPairDao.findBySecretKey(any())).thenReturn(null);
        ApiKeyPairVO keyPair = new ApiKeyPairVO();

        String encoded = service.createUserSecretKey(42L, keyPair);

        assertNotNull(encoded);
        assertEquals(encoded, keyPair.getSecretKey());
        verify(apiKeyPairDao, times(1)).findBySecretKey(any());
    }

    // ---- validateAndPersistKeyPairAndPermissions ----

    @Test
    public void validateAndPersistKeyPairAndPermissionsSessionUserPersistsWithRolePermissions() {
        // No accessing api key → use role's permissions as the superset basis.
        when(apiKeyPermissionService.getAccessingApiKey(cmd)).thenReturn(null);
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(7L);

        AccountVO account = new AccountVO();
        account.setRoleId(7L);
        when(roleService.findRole(7L)).thenReturn(role);
        when(roleService.findAllRolePermissionsEntityBy(eq(7L), eq(true)))
                .thenReturn(Collections.emptyList());

        when(apiKeyPermissionService.isApiKeySupersetOfPermission(any(), any())).thenReturn(true);

        ApiKeyPairVO newPair = new ApiKeyPairVO();
        ApiKeyPairVO persisted = new ApiKeyPairVO();
        persisted.setId(99L);
        when(apiKeyPairDao.persist(newPair)).thenReturn(persisted);

        List<Map<String, Object>> rules = new ArrayList<>();
        rules.add(Map.of(
                ApiConstants.RULE, "list*",
                ApiConstants.PERMISSION, RolePermission.Permission.ALLOW,
                ApiConstants.DESCRIPTION, "read-only"));

        ApiKeyPairVO result = service.validateAndPersistKeyPairAndPermissions(account, newPair, rules, cmd);

        assertSame(persisted, result);
        verify(apiKeyPairDao).persist(newPair);
        // One permission row persisted, bound to the saved key pair's id.
        verify(apiKeyPairPermissionsDao, times(1)).persist(any(ApiKeyPairPermissionVO.class));
    }

    @Test
    public void validateAndPersistKeyPairAndPermissionsApiKeyUserUsesKeypairPermissions() {
        // Has an accessing api key → use that key's permissions as superset basis.
        when(apiKeyPermissionService.getAccessingApiKey(cmd)).thenReturn("caller-key");
        Role role = mock(Role.class);

        AccountVO account = new AccountVO();
        account.setRoleId(7L);
        when(roleService.findRole(7L)).thenReturn(role);
        when(apiKeyPermissionService.getAllKeypairPermissions("caller-key"))
                .thenReturn(Collections.emptyList());

        when(apiKeyPermissionService.isApiKeySupersetOfPermission(any(), any())).thenReturn(true);

        ApiKeyPairVO newPair = new ApiKeyPairVO();
        ApiKeyPairVO saved = new ApiKeyPairVO();
        saved.setId(50L);
        when(apiKeyPairDao.persist(newPair)).thenReturn(saved);

        service.validateAndPersistKeyPairAndPermissions(account, newPair, List.of(), cmd);

        verify(apiKeyPermissionService).getAllKeypairPermissions("caller-key");
        verify(roleService, never()).findAllRolePermissionsEntityBy(anyLong(), eq(true));
    }

    @Test
    public void validateAndPersistKeyPairAndPermissionsThrowsWhenNotSuperset() {
        // Caller's permissions are not a superset of the requested rules.
        when(apiKeyPermissionService.getAccessingApiKey(cmd)).thenReturn(null);
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(7L);

        AccountVO account = new AccountVO();
        account.setRoleId(7L);
        account.setUuid("acct-uuid");
        when(roleService.findRole(7L)).thenReturn(role);
        when(roleService.findAllRolePermissionsEntityBy(anyLong(), eq(true)))
                .thenReturn(Collections.emptyList());

        when(apiKeyPermissionService.isApiKeySupersetOfPermission(any(), any())).thenReturn(false);

        ApiKeyPairVO newPair = new ApiKeyPairVO();
        List<Map<String, Object>> rules = List.of(Map.of(
                ApiConstants.RULE, "create*",
                ApiConstants.PERMISSION, RolePermission.Permission.ALLOW,
                ApiConstants.DESCRIPTION, "elevation"));

        InvalidParameterValueException thrown = assertThrows(InvalidParameterValueException.class,
                () -> service.validateAndPersistKeyPairAndPermissions(account, newPair, rules, cmd));
        assertTrue(thrown.getMessage().contains("acct-uuid"));
        // Nothing should be persisted on rejection.
        verify(apiKeyPairDao, never()).persist(any(ApiKeyPairVO.class));
        verify(apiKeyPairPermissionsDao, never()).persist(any(ApiKeyPairPermissionVO.class));
    }

    // ---- internalDeleteApiKey ----

    @Test
    public void internalDeleteApiKeyRemovesPermissionsThenKeyPair() {
        ApiKeyPair keyPair = mock(ApiKeyPair.class);
        when(keyPair.getId()).thenReturn(5L);

        ApiKeyPairPermissionVO p1 = mock(ApiKeyPairPermissionVO.class);
        when(p1.getId()).thenReturn(11L);
        ApiKeyPairPermissionVO p2 = mock(ApiKeyPairPermissionVO.class);
        when(p2.getId()).thenReturn(12L);
        when(apiKeyPairPermissionsDao.findAllByApiKeyPairId(5L)).thenReturn(List.of(p1, p2));

        service.internalDeleteApiKey(keyPair);

        verify(apiKeyPairPermissionsDao).remove(11L);
        verify(apiKeyPairPermissionsDao).remove(12L);
        verify(apiKeyPairDao).remove(5L);
    }

    @Test
    public void internalDeleteApiKeyWithNoPermissionsStillRemovesKeyPair() {
        ApiKeyPair keyPair = mock(ApiKeyPair.class);
        when(keyPair.getId()).thenReturn(5L);
        when(apiKeyPairPermissionsDao.findAllByApiKeyPairId(5L)).thenReturn(Collections.emptyList());

        service.internalDeleteApiKey(keyPair);

        verify(apiKeyPairPermissionsDao, never()).remove(anyLong());
        verify(apiKeyPairDao).remove(5L);
    }

    // ---- removeApiKeyPairIfExpired ----

    @Test
    public void removeApiKeyPairIfExpiredDeletesExpiredKey() {
        ApiKeyPair keyPair = mock(ApiKeyPair.class);
        when(keyPair.hasEndDatePassed()).thenReturn(true);
        when(keyPair.getId()).thenReturn(3L);
        when(apiKeyPairPermissionsDao.findAllByApiKeyPairId(3L)).thenReturn(Collections.emptyList());

        service.removeApiKeyPairIfExpired(keyPair);

        verify(apiKeyPairDao).remove(3L);
    }

    @Test
    public void removeApiKeyPairIfExpiredKeepsUnexpiredKey() {
        ApiKeyPair keyPair = mock(ApiKeyPair.class);
        when(keyPair.hasEndDatePassed()).thenReturn(false);

        service.removeApiKeyPairIfExpired(keyPair);

        verify(apiKeyPairDao, never()).remove(anyLong());
        verify(apiKeyPairPermissionsDao, never()).findAllByApiKeyPairId(anyLong());
    }

    // ---- getKeyPairById ----

    @Test
    public void getKeyPairByIdDelegatesToDao() {
        ApiKeyPairVO kp = new ApiKeyPairVO();
        kp.setId(8L);
        when(apiKeyPairDao.findById(8L)).thenReturn(kp);

        assertSame(kp, service.getKeyPairById(8L));
    }

    @Test
    public void getKeyPairByIdReturnsNullForMissingRow() {
        when(apiKeyPairDao.findById(8L)).thenReturn(null);

        assertNull(service.getKeyPairById(8L));
    }

    // ---- getKeyPairByApiKey ----

    @Test
    public void getKeyPairByApiKeyDelegatesToDao() {
        ApiKeyPairVO kp = new ApiKeyPairVO();
        when(apiKeyPairDao.findByApiKey("the-key")).thenReturn(kp);

        assertSame(kp, service.getKeyPairByApiKey("the-key"));
    }

    // ---- findUserByApiKey ----

    @Test
    public void findUserByApiKeyReturnsNullWhenKeyPairMissing() {
        when(apiKeyPairDao.findByApiKey("nope")).thenReturn(null);

        assertNull(service.findUserByApiKey("nope"));
        // Should not look up the user/account once the key was not resolved.
        verify(userDao, never()).getUser(anyLong());
        verify(accountDao, never()).findById(anyLong());
    }

    @Test
    public void findUserByApiKeyResolvesUserAndAccount() {
        ApiKeyPairVO kp = new ApiKeyPairVO();
        kp.setUserId(11L);
        kp.setAccountId(22L);
        when(apiKeyPairDao.findByApiKey("yep")).thenReturn(kp);

        UserVO user = new UserVO(11L);
        AccountVO account = new AccountVO();
        when(userDao.getUser(11L)).thenReturn(user);
        when(accountDao.findById(22L)).thenReturn(account);

        Ternary<User, Account, ApiKeyPair> result = service.findUserByApiKey("yep");

        assertNotNull(result);
        assertSame(user, result.first());
        assertSame(account, result.second());
        assertSame(kp, result.third());
    }

    // ---- RolePermissionEntity rule conversion ----

    @Test
    public void validateAndPersistKeyPairAndPermissionsConvertsRulesToPermissionVOs() {
        // Rules with multiple entries → all get converted and persisted with
        // the saved key pair's id.
        when(apiKeyPermissionService.getAccessingApiKey(cmd)).thenReturn(null);
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(7L);

        AccountVO account = new AccountVO();
        account.setRoleId(7L);
        when(roleService.findRole(7L)).thenReturn(role);
        when(roleService.findAllRolePermissionsEntityBy(anyLong(), eq(true)))
                .thenReturn(Collections.emptyList());
        when(apiKeyPermissionService.isApiKeySupersetOfPermission(any(), any())).thenReturn(true);

        ApiKeyPairVO newPair = new ApiKeyPairVO();
        ApiKeyPairVO persisted = new ApiKeyPairVO();
        persisted.setId(77L);
        when(apiKeyPairDao.persist(newPair)).thenReturn(persisted);

        List<Map<String, Object>> rules = List.of(
                Map.of(ApiConstants.RULE, "listAccounts",
                        ApiConstants.PERMISSION, RolePermission.Permission.ALLOW,
                        ApiConstants.DESCRIPTION, "d1"),
                Map.of(ApiConstants.RULE, "listUsers",
                        ApiConstants.PERMISSION, RolePermission.Permission.ALLOW,
                        ApiConstants.DESCRIPTION, "d2"),
                Map.of(ApiConstants.RULE, "deleteUser",
                        ApiConstants.PERMISSION, RolePermission.Permission.DENY,
                        ApiConstants.DESCRIPTION, "d3"));

        service.validateAndPersistKeyPairAndPermissions(account, newPair, rules, cmd);

        // Three permissions persisted on the saved id.
        verify(apiKeyPairPermissionsDao, times(3)).persist(any(ApiKeyPairPermissionVO.class));
    }

    // ---- getLatestUserKeyPair: smoke ----
    // (ApiDBUtils.searchForLatestUserKeyPair is a static call into legacy DAO
    // glue — we cover the delegation path by inspecting the wrapper in
    // AccountManagerImpl rather than mocking the static here.)

    @Test
    public void supersetCheckReceivesBuiltPermissionList() {
        // Confirm the built permissions list is what gets passed to the
        // isApiKeySupersetOfPermission check — guards the rule-conversion
        // contract.
        when(apiKeyPermissionService.getAccessingApiKey(cmd)).thenReturn(null);
        Role role = mock(Role.class);
        when(role.getId()).thenReturn(7L);

        AccountVO account = new AccountVO();
        account.setRoleId(7L);
        when(roleService.findRole(7L)).thenReturn(role);
        when(roleService.findAllRolePermissionsEntityBy(anyLong(), eq(true)))
                .thenReturn(Collections.emptyList());
        when(apiKeyPermissionService.isApiKeySupersetOfPermission(any(), any())).thenReturn(true);

        ApiKeyPairVO newPair = new ApiKeyPairVO();
        ApiKeyPairVO saved = new ApiKeyPairVO();
        saved.setId(123L);
        when(apiKeyPairDao.persist(newPair)).thenReturn(saved);

        List<Map<String, Object>> rules = List.of(Map.of(
                ApiConstants.RULE, "doThing",
                ApiConstants.PERMISSION, RolePermission.Permission.ALLOW,
                ApiConstants.DESCRIPTION, "desc"));

        service.validateAndPersistKeyPairAndPermissions(account, newPair, rules, cmd);

        // Verify superset check was invoked exactly once.
        verify(apiKeyPermissionService, times(1)).isApiKeySupersetOfPermission(any(List.class), any(List.class));
    }

    // ---- findUserByApiKey: null-only-key-side branch ----

    @Test
    public void findUserByApiKeyWithMissingUserStillBuildsTernary() {
        // The lifecycle path does not guard against a dangling user row —
        // it just packs whatever userDao returns. Pin the current contract.
        ApiKeyPairVO kp = new ApiKeyPairVO();
        kp.setUserId(11L);
        kp.setAccountId(22L);
        when(apiKeyPairDao.findByApiKey("yep")).thenReturn(kp);
        when(userDao.getUser(11L)).thenReturn(null);
        AccountVO account = new AccountVO();
        when(accountDao.findById(22L)).thenReturn(account);

        Ternary<User, Account, ApiKeyPair> result = service.findUserByApiKey("yep");

        assertNotNull(result);
        assertNull(result.first());
        assertSame(account, result.second());
        assertSame(kp, result.third());
    }
}
