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
package com.cloud.configuration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.command.admin.config.ResetCfgCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigDepot;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.config.impl.ConfigurationVO;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDetailVO;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDetailsDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterDetailsDao;
import com.cloud.domain.DomainDetailVO;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.domain.dao.DomainDetailsDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.AccountDetailVO;
import com.cloud.user.AccountDetailsDao;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Focused tests for {@link ConfigurationResetServiceImpl} — slice 6 of
 * the Phase 4 decomposition of {@link ConfigurationManagerImpl}.
 *
 * <p>Each scope (Global, Zone, Cluster, StoragePool, Domain, Account,
 * ImageStore) is exercised, plus the validation paths (unknown config,
 * multiple ids, invalid scope, non-existent scoped entity, failed
 * config-table update). Behaviour is also exercised through the
 * manager's delegating wrapper in {@code ConfigurationManagerImplTest}.
 */
@RunWith(MockitoJUnitRunner.class)
public class ConfigurationResetServiceImplTest {

    @Mock private ConfigurationDao configDao;
    @Mock private ConfigDepot configDepot;
    @Mock private DataCenterDao zoneDao;
    @Mock private DataCenterDetailsDao dcDetailsDao;
    @Mock private ClusterDao clusterDao;
    @Mock private ClusterDetailsDao clusterDetailsDao;
    @Mock private PrimaryDataStoreDao storagePoolDao;
    @Mock private StoragePoolDetailsDao storagePoolDetailsDao;
    @Mock private DomainDao domainDao;
    @Mock private DomainDetailsDao domainDetailsDao;
    @Mock private AccountDao accountDao;
    @Mock private AccountDetailsDao accountDetailsDao;
    @Mock private ImageStoreDao imageStoreDao;
    @Mock private ImageStoreDetailsDao imageStoreDetailsDao;

    @InjectMocks
    private ConfigurationResetServiceImpl service;

    private ResetCfgCmd cmd;
    private ConfigurationVO config;
    private CallContext callContext;
    private static final String NAME = "some.cfg";

    @Before
    public void setUp() {
        cmd = Mockito.mock(ResetCfgCmd.class);
        when(cmd.getCfgName()).thenReturn(NAME);
        // Mockito's RETURNS_DEFAULTS returns 0L for boxed-Long getters; force null
        // so the scope-resolver sees an empty scopeMap unless a test stubs one in.
        when(cmd.getZoneId()).thenReturn(null);
        when(cmd.getClusterId()).thenReturn(null);
        when(cmd.getStoragepoolId()).thenReturn(null);
        when(cmd.getAccountId()).thenReturn(null);
        when(cmd.getDomainId()).thenReturn(null);
        when(cmd.getImageStoreId()).thenReturn(null);

        config = new ConfigurationVO("Advanced", "DEFAULT", "test", NAME, null, "description");
        // setScope takes an int bitmask; pick the one matching the scenario per test.
        config.setDefaultValue("default-val");
        when(configDao.findByName(NAME)).thenReturn(config);

        // For scoped resets, currentValueInScope is computed via configDepot — return
        // a non-null ConfigKey mock by default so the lenient lookup doesn't NPE.
        ConfigKey<?> defaultKey = Mockito.mock(ConfigKey.class);
        Mockito.lenient().when(configDepot.get(anyString())).thenReturn((ConfigKey) defaultKey);

        // Anchor the CallContext so resetConfiguration's calling-user lookup and
        // setEventDetails(...) calls don't NPE — register and tear down per test.
        callContext = CallContext.register(new UserVO(User.UID_SYSTEM), new AccountVO(2L));
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    /**
     * scope bitmask for "all scopes allowed" so configScope.contains(scope) passes
     * for any scope the test exercises.
     */
    private void allowAllScopes() {
        // Bitmask covering Zone(2) | Cluster(4) | StoragePool(8) | Account(16) |
        // Domain(32) | ImageStore(128). Using 254 keeps all six bits set.
        config.setScope(254);
    }

    @Test
    public void resetGlobalScopeUpdatesConfigDaoAndReturnsDefault() {
        config.setScope(1); // Global only
        when(configDao.update(NAME, "Advanced", "default-val")).thenReturn(true);

        Pair<org.apache.cloudstack.config.Configuration, String> result = service.resetConfiguration(cmd);

        assertEquals("default-val", result.second());
        verify(configDao, times(1)).update(NAME, "Advanced", "default-val");
        verify(configDepot, times(1)).invalidateConfigCache(eq(NAME), eq(ConfigKey.Scope.Global), Mockito.isNull());
    }

    @Test
    public void resetGlobalScopeThrowsWhenConfigDaoUpdateFails() {
        config.setScope(1);
        when(configDao.update(anyString(), anyString(), anyString())).thenReturn(false);

        assertThrows(CloudRuntimeException.class, () -> service.resetConfiguration(cmd));
    }

    @Test
    public void resetUnknownConfigThrowsInvalidParameter() {
        when(configDao.findByName(NAME)).thenReturn(null);
        when(configDepot.get(NAME)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class, () -> service.resetConfiguration(cmd));
    }

    @Test
    public void resetWithMultipleScopeIdsThrowsInvalidParameter() {
        allowAllScopes();
        when(cmd.getZoneId()).thenReturn(1L);
        when(cmd.getClusterId()).thenReturn(2L);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.resetConfiguration(cmd));
        assertNotNull(ex.getMessage());
    }

    @Test
    public void resetWithScopeNotAllowedByConfigThrows() {
        // configScope = Global only — passing a zoneId is invalid
        config.setScope(1);
        when(cmd.getZoneId()).thenReturn(7L);

        assertThrows(InvalidParameterValueException.class, () -> service.resetConfiguration(cmd));
    }

    @Test
    public void resetZoneScopeRemovesDetailAndReturnsValue() {
        allowAllScopes();
        when(cmd.getZoneId()).thenReturn(7L);
        when(zoneDao.findById(7L)).thenReturn(Mockito.mock(DataCenterVO.class));
        config.setValue("zone-val");

        Pair<org.apache.cloudstack.config.Configuration, String> result = service.resetConfiguration(cmd);

        verify(dcDetailsDao, times(1)).removeDetail(7L, NAME);
        assertEquals("zone-val", result.second());
    }

    @Test
    public void resetZoneScopeWithUnknownZoneThrows() {
        allowAllScopes();
        when(cmd.getZoneId()).thenReturn(7L);
        when(zoneDao.findById(7L)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class, () -> service.resetConfiguration(cmd));
    }

    @Test
    public void resetClusterScopeRemovesDetailWhenNotOverprovisioning() {
        allowAllScopes();
        when(cmd.getClusterId()).thenReturn(11L);
        when(clusterDao.findById(11L)).thenReturn(Mockito.mock(ClusterVO.class));
        ClusterDetailsVO detail = Mockito.mock(ClusterDetailsVO.class);
        when(detail.getId()).thenReturn(99L);
        when(clusterDetailsDao.findDetail(11L, NAME)).thenReturn(detail);
        config.setValue("cluster-val");

        service.resetConfiguration(cmd);

        verify(clusterDetailsDao, times(1)).remove(99L);
        verify(clusterDetailsDao, never()).persist(anyLong(), anyString(), anyString());
    }

    @Test
    public void resetClusterScopeForOverprovisioningPersistsDefault() {
        // For cpu.overprovisioning.factor the value is re-persisted, not removed.
        when(cmd.getCfgName()).thenReturn("cpu.overprovisioning.factor");
        ConfigurationVO overCfg = new ConfigurationVO("Advanced", "DEFAULT", "test", "cpu.overprovisioning.factor", null, "");
        overCfg.setScope(254);
        overCfg.setValue("1.0");
        overCfg.setDefaultValue("1.0");
        when(configDao.findByName("cpu.overprovisioning.factor")).thenReturn(overCfg);
        when(cmd.getClusterId()).thenReturn(11L);
        when(clusterDao.findById(11L)).thenReturn(Mockito.mock(ClusterVO.class));

        service.resetConfiguration(cmd);

        verify(clusterDetailsDao, times(1)).persist(eq(11L), eq("cpu.overprovisioning.factor"), anyString());
    }

    @Test
    public void resetClusterScopeWithUnknownClusterThrows() {
        allowAllScopes();
        when(cmd.getClusterId()).thenReturn(11L);
        when(clusterDao.findById(11L)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class, () -> service.resetConfiguration(cmd));
    }

    @Test
    public void resetStoragePoolScopeRemovesDetailAndReturnsValue() {
        allowAllScopes();
        when(cmd.getStoragepoolId()).thenReturn(5L);
        when(storagePoolDao.findById(5L)).thenReturn(Mockito.mock(StoragePoolVO.class));
        config.setValue("pool-val");

        Pair<org.apache.cloudstack.config.Configuration, String> result = service.resetConfiguration(cmd);

        verify(storagePoolDetailsDao, times(1)).removeDetail(5L, NAME);
        assertEquals("pool-val", result.second());
    }

    @Test
    public void resetStoragePoolScopeWithUnknownPoolThrows() {
        allowAllScopes();
        when(cmd.getStoragepoolId()).thenReturn(5L);
        when(storagePoolDao.findById(5L)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class, () -> service.resetConfiguration(cmd));
    }

    @Test
    public void resetDomainScopeRemovesDetailWhenPresent() {
        allowAllScopes();
        when(cmd.getDomainId()).thenReturn(20L);
        when(domainDao.findById(20L)).thenReturn(Mockito.mock(DomainVO.class));
        DomainDetailVO detail = Mockito.mock(DomainDetailVO.class);
        when(detail.getId()).thenReturn(101L);
        when(domainDetailsDao.findDetail(20L, NAME)).thenReturn(detail);
        config.setValue("domain-val");

        service.resetConfiguration(cmd);

        verify(domainDetailsDao, times(1)).remove(101L);
    }

    @Test
    public void resetDomainScopeWhenNoDetailDoesNotRemove() {
        allowAllScopes();
        when(cmd.getDomainId()).thenReturn(20L);
        when(domainDao.findById(20L)).thenReturn(Mockito.mock(DomainVO.class));
        when(domainDetailsDao.findDetail(20L, NAME)).thenReturn(null);
        config.setValue("domain-val");

        service.resetConfiguration(cmd);

        verify(domainDetailsDao, never()).remove(anyLong());
    }

    @Test
    public void resetAccountScopeRemovesDetailWhenPresent() {
        allowAllScopes();
        when(cmd.getAccountId()).thenReturn(30L);
        when(accountDao.findById(30L)).thenReturn(Mockito.mock(AccountVO.class));
        AccountDetailVO detail = Mockito.mock(AccountDetailVO.class);
        when(detail.getId()).thenReturn(202L);
        when(accountDetailsDao.findDetail(30L, NAME)).thenReturn(detail);
        config.setValue("acct-val");

        service.resetConfiguration(cmd);

        verify(accountDetailsDao, times(1)).remove(202L);
    }

    @Test
    public void resetImageStoreScopeRemovesDetailWhenPresent() {
        allowAllScopes();
        when(cmd.getImageStoreId()).thenReturn(40L);
        when(imageStoreDao.findById(40L)).thenReturn(Mockito.mock(ImageStoreVO.class));
        ImageStoreDetailVO detail = Mockito.mock(ImageStoreDetailVO.class);
        when(detail.getId()).thenReturn(303L);
        when(imageStoreDetailsDao.findDetail(40L, NAME)).thenReturn(detail);
        config.setValue("img-val");

        service.resetConfiguration(cmd);

        verify(imageStoreDetailsDao, times(1)).remove(303L);
    }

    @Test
    public void resetImageStoreScopeWithUnknownImageStoreThrows() {
        allowAllScopes();
        when(cmd.getImageStoreId()).thenReturn(40L);
        when(imageStoreDao.findById(40L)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class, () -> service.resetConfiguration(cmd));
    }

    @Test
    public void resetUsesConfigKeyFromDepotWhenConfigVOMissing() {
        // configDao returns null → service falls through to _configDepot.get(name)
        when(configDao.findByName(NAME)).thenReturn(null);
        ConfigKey<?> key = Mockito.mock(ConfigKey.class);
        Mockito.<List<ConfigKey.Scope>>when(key.getScopes()).thenReturn(List.of(ConfigKey.Scope.Global));
        when(key.defaultValue()).thenReturn("key-default");
        when(key.category()).thenReturn("Advanced");
        when(key.value()).thenReturn("key-default");
        when(configDepot.get(NAME)).thenReturn((ConfigKey) key);
        when(configDao.update(NAME, "Advanced", "key-default")).thenReturn(true);

        Pair<org.apache.cloudstack.config.Configuration, String> result = service.resetConfiguration(cmd);

        assertEquals("key-default", result.second());
    }

    @Test
    public void getParamCountWithEmptyMapReturnsGlobalScope() {
        Map<String, Long> empty = new LinkedHashMap<>();
        ConfigurationResetServiceImpl.ParamCountTriple t = service.getParamCount(empty);
        assertEquals(0, t.paramCount);
        assertEquals(ConfigKey.Scope.Global.toString(), t.scope);
        assertNull(t.id);
    }

    @Test
    public void getParamCountWithSingleEntryPicksThatScope() {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put(ConfigKey.Scope.Zone.toString(), 9L);
        map.put(ConfigKey.Scope.Cluster.toString(), null);
        ConfigurationResetServiceImpl.ParamCountTriple t = service.getParamCount(map);
        assertEquals(1, t.paramCount);
        assertEquals(ConfigKey.Scope.Zone.toString(), t.scope);
        assertEquals(Long.valueOf(9L), t.id);
    }

    @Test
    public void getParamCountCountsMultipleEntries() {
        Map<String, Long> map = new LinkedHashMap<>();
        map.put(ConfigKey.Scope.Zone.toString(), 1L);
        map.put(ConfigKey.Scope.Cluster.toString(), 2L);
        ConfigurationResetServiceImpl.ParamCountTriple t = service.getParamCount(map);
        assertEquals(2, t.paramCount);
    }

    @Test
    public void getConfigurationValueInScopeReturnsConfigValueForGlobal() {
        config.setValue("the-value");
        String v = service.getConfigurationValueInScope(config, NAME, ConfigKey.Scope.Global, null);
        assertEquals("the-value", v);
    }

    @Test
    public void getConfigurationValueInScopeReturnsConfigValueForNullScope() {
        config.setValue("the-value");
        String v = service.getConfigurationValueInScope(config, NAME, null, null);
        assertEquals("the-value", v);
    }

    @Test
    public void getConfigurationValueInScopeUsesConfigKeyForNonGlobal() {
        ConfigKey<?> key = Mockito.mock(ConfigKey.class);
        when(configDepot.get(NAME)).thenReturn((ConfigKey) key);
        Mockito.<Object>when(key.valueInScope(ConfigKey.Scope.Zone, 5L)).thenReturn("scoped");

        String v = service.getConfigurationValueInScope(config, NAME, ConfigKey.Scope.Zone, 5L);
        assertEquals("scoped", v);
    }

    @Test
    public void getConfigurationValueInScopeReturnsNullWhenScopedValueAbsent() {
        ConfigKey<?> key = Mockito.mock(ConfigKey.class);
        when(configDepot.get(NAME)).thenReturn((ConfigKey) key);
        Mockito.<Object>when(key.valueInScope(ConfigKey.Scope.Zone, 5L)).thenReturn(null);

        String v = service.getConfigurationValueInScope(config, NAME, ConfigKey.Scope.Zone, 5L);
        assertNull(v);
    }

    @Test
    public void resetMasksPasswordInEventDetails() {
        // Name contains "password" — the setEventDetails branch substitutes *****.
        when(cmd.getCfgName()).thenReturn("admin.password");
        ConfigurationVO pwd = new ConfigurationVO("Advanced", "DEFAULT", "test", "admin.password", null, "");
        pwd.setScope(1);
        pwd.setDefaultValue("secret-default");
        when(configDao.findByName("admin.password")).thenReturn(pwd);
        when(configDao.update(anyString(), anyString(), anyString())).thenReturn(true);

        service.resetConfiguration(cmd);

        // The CallContext event details should not leak the plaintext default.
        String details = callContext.getEventDetails();
        assertNotNull(details);
        org.junit.Assert.assertTrue(details.contains("*****"));
        org.junit.Assert.assertFalse(details.contains("secret-default"));
    }
}
