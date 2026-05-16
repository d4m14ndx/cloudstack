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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.acl.RolePermissionEntity;
import org.apache.cloudstack.acl.RoleService;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPair;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPairPermission;
import org.apache.cloudstack.acl.apikeypair.ApiKeyPairService;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.BaseAsyncCmd;
import org.apache.cloudstack.api.BaseCmd;
import org.apache.cloudstack.framework.jobs.AsyncJob;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.user.dao.AccountDao;

@RunWith(MockitoJUnitRunner.class)
public class ApiKeyPermissionServiceImplTest {

    @Mock private ApiKeyPairService apiKeyPairService;
    @Mock private RoleService roleService;
    @Mock private AccountDao accountDao;

    @InjectMocks
    private ApiKeyPermissionServiceImpl service;

    private BaseCmd cmd;
    private BaseAsyncCmd asyncCmd;

    @Before
    public void setUp() {
        cmd = mock(BaseCmd.class);
        asyncCmd = mock(BaseAsyncCmd.class);
    }

    // ---- getAccessingApiKey ----

    @Test
    public void getAccessingApiKeyWithSignatureAndApiKey() {
        Map<String, String> params = Map.of(ApiConstants.SIGNATURE, "sig123", "apiKey", "abc-key");
        when(cmd.getFullUrlParams()).thenReturn(params);

        String result = service.getAccessingApiKey(cmd);

        assertEquals("abc-key", result);
    }

    @Test
    public void getAccessingApiKeyWithoutSignatureReturnsNull() {
        // No signature param -> session-authenticated, returns null
        when(cmd.getFullUrlParams()).thenReturn(Map.of("apiKey", "abc-key"));

        assertNull(service.getAccessingApiKey(cmd));
    }

    @Test
    public void getAccessingApiKeyNullParamsReturnsNullViaNpeFallback() {
        // getFullUrlParams() returns null -> NPE caught -> returns null
        when(cmd.getFullUrlParams()).thenReturn(null);

        assertNull(service.getAccessingApiKey(cmd));
    }

    @Test
    public void getAccessingApiKeyAsyncJobWithSignatureParsesFromJob() {
        AsyncJob job = mock(AsyncJob.class);
        // Mimic the serialized async-job payload that contains a "signature".
        String jobPayload = "{\"apiKey\":\"asyncKey42\",\"signature\":\"abcd\"}";
        when(job.toString()).thenReturn(jobPayload);
        when(asyncCmd.getJob()).thenReturn(job);
        // The async-cmd branch never reads URL params, but BaseAsyncCmd extends
        // BaseCmd so getFullUrlParams() is still on the surface — leave it
        // un-stubbed and rely on the early return.

        String result = service.getAccessingApiKey(asyncCmd);

        assertEquals("asyncKey42", result);
    }

    @Test
    public void getAccessingApiKeyAsyncJobWithoutSignatureFallsThroughToUrlParams() {
        AsyncJob job = mock(AsyncJob.class);
        when(job.toString()).thenReturn("{\"apiKey\":\"x\"}");
        when(asyncCmd.getJob()).thenReturn(job);
        when(asyncCmd.getFullUrlParams()).thenReturn(
                Map.of(ApiConstants.SIGNATURE, "sig", "apiKey", "url-key"));

        assertEquals("url-key", service.getAccessingApiKey(asyncCmd));
    }

    // ---- getAllKeypairPermissions ----

    @Test
    public void getAllKeypairPermissionsNullApiKeyRejected() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.getAllKeypairPermissions(null));
    }

    @Test
    public void getAllKeypairPermissionsResolvesViaKeyPairAndAccount() {
        ApiKeyPair keyPair = mock(ApiKeyPair.class);
        when(keyPair.getId()).thenReturn(7L);
        when(keyPair.getAccountId()).thenReturn(42L);
        when(apiKeyPairService.findByApiKey("k1")).thenReturn(keyPair);

        AccountVO acct = new AccountVO();
        acct.setRoleId(99L);
        when(accountDao.findById(42L)).thenReturn(acct);

        ApiKeyPairPermission p1 = mock(ApiKeyPairPermission.class);
        ApiKeyPairPermission p2 = mock(ApiKeyPairPermission.class);
        when(apiKeyPairService.findAllPermissionsByKeyPairId(7L, 99L))
                .thenReturn(List.of(p1, p2));

        List<RolePermissionEntity> result = service.getAllKeypairPermissions("k1");

        assertEquals(2, result.size());
        // The returned list wraps the ApiKeyPairPermission entries — they
        // implement RolePermissionEntity, so identity is preserved.
        assertTrue(result.contains(p1));
        assertTrue(result.contains(p2));
    }

    @Test
    public void getAllKeypairPermissionsEmptyResultReturnsEmptyList() {
        ApiKeyPair keyPair = mock(ApiKeyPair.class);
        when(keyPair.getId()).thenReturn(7L);
        when(keyPair.getAccountId()).thenReturn(42L);
        when(apiKeyPairService.findByApiKey("k1")).thenReturn(keyPair);

        AccountVO acct = new AccountVO();
        acct.setRoleId(99L);
        when(accountDao.findById(42L)).thenReturn(acct);
        when(apiKeyPairService.findAllPermissionsByKeyPairId(anyLong(), anyLong()))
                .thenReturn(Collections.emptyList());

        assertTrue(service.getAllKeypairPermissions("k1").isEmpty());
    }

    // ---- isAccessingKeypairSuperset ----

    @Test
    public void isAccessingKeypairSupersetSessionRequestAlwaysAllowed() {
        // No signature on URL -> session auth -> always allowed (true)
        when(cmd.getFullUrlParams()).thenReturn(Map.of());
        ApiKeyPair accessed = mock(ApiKeyPair.class);

        assertTrue(service.isAccessingKeypairSuperset(accessed, cmd));

        // No DAO interaction needed for session path
        verify(apiKeyPairService, never()).findByApiKey("");
    }

    @Test
    public void isAccessingKeypairSupersetApiKeyRequestComparesPermissions() {
        when(cmd.getFullUrlParams()).thenReturn(
                Map.of(ApiConstants.SIGNATURE, "sig", "apiKey", "callerKey"));

        // Caller's key pair
        ApiKeyPair callerKp = mock(ApiKeyPair.class);
        when(callerKp.getApiKey()).thenReturn("callerKey");
        when(callerKp.getId()).thenReturn(1L);
        when(callerKp.getAccountId()).thenReturn(10L);
        when(apiKeyPairService.findByApiKey("callerKey")).thenReturn(callerKp);

        // Accessed key pair
        ApiKeyPair accessedKp = mock(ApiKeyPair.class);
        when(accessedKp.getApiKey()).thenReturn("accessedKey");
        when(accessedKp.getId()).thenReturn(2L);
        when(accessedKp.getAccountId()).thenReturn(20L);
        when(apiKeyPairService.findByApiKey("accessedKey")).thenReturn(accessedKp);

        AccountVO acct1 = new AccountVO();
        acct1.setRoleId(100L);
        AccountVO acct2 = new AccountVO();
        acct2.setRoleId(200L);
        when(accountDao.findById(10L)).thenReturn(acct1);
        when(accountDao.findById(20L)).thenReturn(acct2);
        when(apiKeyPairService.findAllPermissionsByKeyPairId(anyLong(), anyLong()))
                .thenReturn(Collections.emptyList());
        when(roleService.getRoleRulesAndPermissions(any())).thenReturn(Map.of());
        when(roleService.roleHasPermission(any(), any())).thenReturn(true);

        assertTrue(service.isAccessingKeypairSuperset(accessedKp, cmd));
    }

    @Test
    public void isAccessingKeypairSupersetReturnsFalseWhenRoleServiceDenies() {
        when(cmd.getFullUrlParams()).thenReturn(
                Map.of(ApiConstants.SIGNATURE, "sig", "apiKey", "callerKey"));

        ApiKeyPair callerKp = mock(ApiKeyPair.class);
        when(callerKp.getApiKey()).thenReturn("callerKey");
        when(callerKp.getId()).thenReturn(1L);
        when(callerKp.getAccountId()).thenReturn(10L);
        when(apiKeyPairService.findByApiKey("callerKey")).thenReturn(callerKp);

        ApiKeyPair accessedKp = mock(ApiKeyPair.class);
        when(accessedKp.getApiKey()).thenReturn("accessedKey");
        when(accessedKp.getId()).thenReturn(2L);
        when(accessedKp.getAccountId()).thenReturn(20L);
        when(apiKeyPairService.findByApiKey("accessedKey")).thenReturn(accessedKp);

        AccountVO acct = new AccountVO();
        acct.setRoleId(100L);
        when(accountDao.findById(anyLong())).thenReturn(acct);
        when(apiKeyPairService.findAllPermissionsByKeyPairId(anyLong(), anyLong()))
                .thenReturn(Collections.emptyList());
        when(roleService.getRoleRulesAndPermissions(any())).thenReturn(Map.of());
        when(roleService.roleHasPermission(any(), any())).thenReturn(false);

        assertFalse(service.isAccessingKeypairSuperset(accessedKp, cmd));
    }

    // ---- isApiKeySupersetOfPermission ----

    @Test
    public void isApiKeySupersetOfPermissionDelegatesToRoleService() {
        RolePermissionEntity base = mock(RolePermissionEntity.class);
        RolePermissionEntity compared = mock(RolePermissionEntity.class);
        Map<String, RolePermissionEntity> ruleMap = Map.of("api1", base);
        when(roleService.getRoleRulesAndPermissions(List.of(base))).thenReturn(ruleMap);
        when(roleService.roleHasPermission(eq(ruleMap), eq(List.of(compared)))).thenReturn(true);

        assertTrue(service.isApiKeySupersetOfPermission(List.of(base), List.of(compared)));
    }

    @Test
    public void isApiKeySupersetOfPermissionFalseWhenRoleServiceDenies() {
        RolePermissionEntity base = mock(RolePermissionEntity.class);
        RolePermissionEntity compared = mock(RolePermissionEntity.class);
        when(roleService.getRoleRulesAndPermissions(any())).thenReturn(Map.of());
        when(roleService.roleHasPermission(any(), any())).thenReturn(false);

        assertFalse(service.isApiKeySupersetOfPermission(List.of(base), List.of(compared)));
    }

    // ---- validateKeyPairIsNotNull ----

    @Test
    public void validateKeyPairIsNotNullThrowsForNull() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.validateKeyPairIsNotNull(null));
    }

    @Test
    public void validateKeyPairIsNotNullPassesForNonNull() {
        // No throw expected
        service.validateKeyPairIsNotNull(mock(ApiKeyPair.class));
    }

    // ---- validateAccessingKeyPairPermissionsIsSupersetOfAccessedKeyPair ----

    @Test
    public void validateAccessingKeyPairSupersetPassesWhenSuperset() {
        // Session auth (no signature) → isAccessingKeypairSuperset returns true
        // → no exception.
        when(cmd.getFullUrlParams()).thenReturn(Map.of());
        ApiKeyPair kp = mock(ApiKeyPair.class);

        service.validateAccessingKeyPairPermissionsIsSupersetOfAccessedKeyPair(kp, cmd);
        // No exception means pass.
    }

    @Test
    public void validateAccessingKeyPairSupersetThrowsWhenNotSuperset() {
        // API-key-authenticated request, but role service says caller does NOT
        // have superset of accessed key's permissions → PermissionDeniedException.
        when(cmd.getFullUrlParams()).thenReturn(
                Map.of(ApiConstants.SIGNATURE, "sig", "apiKey", "callerKey"));

        ApiKeyPair callerKp = mock(ApiKeyPair.class);
        when(callerKp.getApiKey()).thenReturn("callerKey");
        when(callerKp.getId()).thenReturn(1L);
        when(callerKp.getAccountId()).thenReturn(10L);
        when(apiKeyPairService.findByApiKey("callerKey")).thenReturn(callerKp);

        ApiKeyPair accessedKp = mock(ApiKeyPair.class);
        lenient().when(accessedKp.getId()).thenReturn(2L);
        when(accessedKp.getApiKey()).thenReturn("accessedKey");
        when(accessedKp.getAccountId()).thenReturn(20L);
        when(apiKeyPairService.findByApiKey("accessedKey")).thenReturn(accessedKp);

        AccountVO acct = new AccountVO();
        acct.setRoleId(100L);
        when(accountDao.findById(anyLong())).thenReturn(acct);
        when(apiKeyPairService.findAllPermissionsByKeyPairId(anyLong(), anyLong()))
                .thenReturn(Collections.emptyList());
        when(roleService.getRoleRulesAndPermissions(any())).thenReturn(Map.of());
        when(roleService.roleHasPermission(any(), any())).thenReturn(false);

        assertThrows(PermissionDeniedException.class,
                () -> service.validateAccessingKeyPairPermissionsIsSupersetOfAccessedKeyPair(accessedKp, cmd));
    }
}
