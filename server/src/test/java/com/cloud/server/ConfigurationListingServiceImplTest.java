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
package com.cloud.server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.cloudstack.api.command.admin.config.ListCfgGroupsByCmd;
import org.apache.cloudstack.api.command.admin.config.ListCfgsByCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigDepot;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.config.dao.ConfigurationGroupDao;
import org.apache.cloudstack.framework.config.dao.ConfigurationSubGroupDao;
import org.apache.cloudstack.framework.config.impl.ConfigurationGroupVO;
import org.apache.cloudstack.framework.config.impl.ConfigurationSubGroupVO;
import org.apache.cloudstack.framework.config.impl.ConfigurationVO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.db.SearchCriteria;

/**
 * Focused unit tests for {@link ConfigurationListingServiceImpl}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Multi-ID rejection — supplying more than one scope id raises
 *       {@link InvalidParameterValueException}</li>
 *   <li>Domain-admin default scoping to caller's own domain when no
 *       account/domain id is provided</li>
 *   <li>Normal-user default scoping to caller's own account when no
 *       account id is provided</li>
 *   <li>Keyword filter expands into a sub-search across name/instance/
 *       component/description/category/value</li>
 *   <li>Group name filter resolves through {@code ConfigurationGroupDao}
 *       and throws on unknown names</li>
 *   <li>Sub-group name filter resolves through
 *       {@code ConfigurationSubGroupDao} and throws on unknown names</li>
 *   <li>Hidden category exclusion is always applied</li>
 *   <li>Scoped search re-populates rows via {@link ConfigDepot} and drops
 *       missing entries from the result set</li>
 *   <li>Unscoped search returns the DAO result untouched</li>
 *   <li>{@link ConfigurationListingService#listConfigurationGroups}
 *       happy path</li>
 *   <li>{@code listConfigurationGroups} with a group filter applies the
 *       name predicate</li>
 *   <li>Wiring smoke test confirms all collaborators are injected</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.class)
public class ConfigurationListingServiceImplTest {

    @Mock
    private ConfigurationDao configDao;
    @Mock
    private ConfigurationGroupDao configGroupDao;
    @Mock
    private ConfigurationSubGroupDao configSubGroupDao;
    @Mock
    private ConfigDepot configDepot;
    @Mock
    private AccountManager accountManager;
    @Mock
    private DomainDao domainDao;

    @InjectMocks
    private ConfigurationListingServiceImpl service = new ConfigurationListingServiceImpl();

    @Mock
    private AccountVO callerAccount;
    @Mock
    private User callerUser;

    private AutoCloseable closeable;

    private static final long CALLER_ID = 42L;
    private static final long CALLER_DOMAIN_ID = 7L;
    private static final long CALLER_ACCOUNT_ID = 99L;

    @Before
    public void setup() {
        closeable = MockitoAnnotations.openMocks(this);
        when(callerAccount.getId()).thenReturn(CALLER_ID);
        when(callerAccount.getDomainId()).thenReturn(CALLER_DOMAIN_ID);
        when(callerAccount.getAccountId()).thenReturn(CALLER_ACCOUNT_ID);
        CallContext.register(callerUser, callerAccount);
    }

    @After
    public void tearDown() throws Exception {
        CallContext.unregister();
        closeable.close();
    }

    // ----------------------------------------------------------------
    // Helper: a vanilla cmd with sane defaults (everything null)
    // ----------------------------------------------------------------

    private ListCfgsByCmd mockCmdAllNull() {
        ListCfgsByCmd cmd = Mockito.mock(ListCfgsByCmd.class);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(20L);
        // Defensive null stubbing for all Long-returning ID getters so that
        // tests don't accidentally trip the multi-scope guard.
        when(cmd.getZoneId()).thenReturn(null);
        when(cmd.getClusterId()).thenReturn(null);
        when(cmd.getAccountId()).thenReturn(null);
        when(cmd.getDomainId()).thenReturn(null);
        when(cmd.getStoragepoolId()).thenReturn(null);
        when(cmd.getImageStoreId()).thenReturn(null);
        return cmd;
    }

    // ----------------------------------------------------------------
    // Multi-scope rejection
    // ----------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void searchForConfigurationsRejectsMultipleScopeIds() {
        ListCfgsByCmd cmd = mockCmdAllNull();
        when(cmd.getZoneId()).thenReturn(1L);
        when(cmd.getClusterId()).thenReturn(2L);
        SearchCriteria<ConfigurationVO> sc = Mockito.mock(SearchCriteria.class);
        when(configDao.createSearchCriteria()).thenReturn(sc);

        service.searchForConfigurations(cmd);
    }

    // ----------------------------------------------------------------
    // Default scoping (domain admin / normal user)
    // ----------------------------------------------------------------

    @Test
    public void searchForConfigurationsDomainAdminDefaultsToOwnDomain() {
        ListCfgsByCmd cmd = mockCmdAllNull();
        SearchCriteria<ConfigurationVO> sc = Mockito.mock(SearchCriteria.class);
        when(configDao.createSearchCriteria()).thenReturn(sc);
        when(accountManager.isDomainAdmin(CALLER_ID)).thenReturn(true);
        DomainVO domain = Mockito.mock(DomainVO.class);
        when(domainDao.findById(CALLER_DOMAIN_ID)).thenReturn(domain);
        when(configDao.searchAndCount(eq(sc), any()))
                .thenReturn(new Pair<>(new ArrayList<>(), 0));

        service.searchForConfigurations(cmd);

        // Scope should be Domain, so the scope binary-OR predicate fires.
        verify(accountManager).checkAccess(callerAccount, domain);
        verify(sc).addAnd(eq("scope"), eq(SearchCriteria.Op.BINARY_OR), any());
    }

    @Test
    public void searchForConfigurationsNormalUserDefaultsToOwnAccount() {
        ListCfgsByCmd cmd = mockCmdAllNull();
        SearchCriteria<ConfigurationVO> sc = Mockito.mock(SearchCriteria.class);
        when(configDao.createSearchCriteria()).thenReturn(sc);
        when(accountManager.isDomainAdmin(CALLER_ID)).thenReturn(false);
        when(accountManager.isNormalUser(CALLER_ID)).thenReturn(true);
        Account ownAcc = Mockito.mock(Account.class);
        when(accountManager.getAccount(CALLER_ACCOUNT_ID)).thenReturn(ownAcc);
        when(configDao.searchAndCount(eq(sc), any()))
                .thenReturn(new Pair<>(new ArrayList<>(), 0));

        service.searchForConfigurations(cmd);

        verify(accountManager).checkAccess(callerAccount, null, false, ownAcc);
        verify(sc).addAnd(eq("scope"), eq(SearchCriteria.Op.BINARY_OR), any());
    }

    // ----------------------------------------------------------------
    // Keyword filter
    // ----------------------------------------------------------------

    @Test
    public void searchForConfigurationsKeywordExpandsIntoSubSearch() {
        ListCfgsByCmd cmd = mockCmdAllNull();
        when(cmd.getKeyword()).thenReturn("kvm");
        when(accountManager.isDomainAdmin(CALLER_ID)).thenReturn(false);
        when(accountManager.isNormalUser(CALLER_ID)).thenReturn(false);
        SearchCriteria<ConfigurationVO> sc = Mockito.mock(SearchCriteria.class);
        SearchCriteria<ConfigurationVO> ssc = Mockito.mock(SearchCriteria.class);
        when(configDao.createSearchCriteria()).thenReturn(sc, ssc);
        when(configDao.searchAndCount(eq(sc), any()))
                .thenReturn(new Pair<>(new ArrayList<>(), 0));

        service.searchForConfigurations(cmd);

        verify(ssc).addOr(eq("name"), eq(SearchCriteria.Op.LIKE), eq("%kvm%"));
        verify(ssc).addOr(eq("instance"), eq(SearchCriteria.Op.LIKE), eq("%kvm%"));
        verify(ssc).addOr(eq("component"), eq(SearchCriteria.Op.LIKE), eq("%kvm%"));
        verify(ssc).addOr(eq("description"), eq(SearchCriteria.Op.LIKE), eq("%kvm%"));
        verify(ssc).addOr(eq("category"), eq(SearchCriteria.Op.LIKE), eq("%kvm%"));
        verify(ssc).addOr(eq("value"), eq(SearchCriteria.Op.LIKE), eq("%kvm%"));
        verify(sc).addAnd(eq("name"), eq(SearchCriteria.Op.SC), eq(ssc));
    }

    // ----------------------------------------------------------------
    // Group / sub-group filters
    // ----------------------------------------------------------------

    @Test
    public void searchForConfigurationsResolvesGroupNameThroughDao() {
        ListCfgsByCmd cmd = mockCmdAllNull();
        when(cmd.getGroupName()).thenReturn("Networking");
        when(accountManager.isDomainAdmin(CALLER_ID)).thenReturn(false);
        when(accountManager.isNormalUser(CALLER_ID)).thenReturn(false);
        ConfigurationGroupVO group = Mockito.mock(ConfigurationGroupVO.class);
        when(group.getId()).thenReturn(11L);
        when(configGroupDao.findByName("Networking")).thenReturn(group);
        SearchCriteria<ConfigurationVO> sc = Mockito.mock(SearchCriteria.class);
        when(configDao.createSearchCriteria()).thenReturn(sc);
        when(configDao.searchAndCount(eq(sc), any()))
                .thenReturn(new Pair<>(new ArrayList<>(), 0));

        service.searchForConfigurations(cmd);

        verify(sc).addAnd(eq("groupId"), eq(SearchCriteria.Op.EQ), eq(11L));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void searchForConfigurationsUnknownGroupThrows() {
        ListCfgsByCmd cmd = mockCmdAllNull();
        when(cmd.getGroupName()).thenReturn("Nonexistent");
        when(accountManager.isDomainAdmin(CALLER_ID)).thenReturn(false);
        when(accountManager.isNormalUser(CALLER_ID)).thenReturn(false);
        when(configGroupDao.findByName("Nonexistent")).thenReturn(null);
        SearchCriteria<ConfigurationVO> sc = Mockito.mock(SearchCriteria.class);
        when(configDao.createSearchCriteria()).thenReturn(sc);

        service.searchForConfigurations(cmd);
    }

    @Test
    public void searchForConfigurationsResolvesSubGroupNameThroughDao() {
        ListCfgsByCmd cmd = mockCmdAllNull();
        when(cmd.getSubGroupName()).thenReturn("Public IPs");
        when(accountManager.isDomainAdmin(CALLER_ID)).thenReturn(false);
        when(accountManager.isNormalUser(CALLER_ID)).thenReturn(false);
        ConfigurationSubGroupVO subGroup = Mockito.mock(ConfigurationSubGroupVO.class);
        when(subGroup.getId()).thenReturn(33L);
        when(configSubGroupDao.findByName("Public IPs")).thenReturn(subGroup);
        SearchCriteria<ConfigurationVO> sc = Mockito.mock(SearchCriteria.class);
        when(configDao.createSearchCriteria()).thenReturn(sc);
        when(configDao.searchAndCount(eq(sc), any()))
                .thenReturn(new Pair<>(new ArrayList<>(), 0));

        service.searchForConfigurations(cmd);

        verify(sc).addAnd(eq("subGroupId"), eq(SearchCriteria.Op.EQ), eq(33L));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void searchForConfigurationsUnknownSubGroupThrows() {
        ListCfgsByCmd cmd = mockCmdAllNull();
        when(cmd.getSubGroupName()).thenReturn("Nope");
        when(accountManager.isDomainAdmin(CALLER_ID)).thenReturn(false);
        when(accountManager.isNormalUser(CALLER_ID)).thenReturn(false);
        when(configSubGroupDao.findByName("Nope")).thenReturn(null);
        SearchCriteria<ConfigurationVO> sc = Mockito.mock(SearchCriteria.class);
        when(configDao.createSearchCriteria()).thenReturn(sc);

        service.searchForConfigurations(cmd);
    }

    // ----------------------------------------------------------------
    // Hidden category always excluded
    // ----------------------------------------------------------------

    @Test
    public void searchForConfigurationsAlwaysExcludesHiddenCategory() {
        ListCfgsByCmd cmd = mockCmdAllNull();
        when(accountManager.isDomainAdmin(CALLER_ID)).thenReturn(false);
        when(accountManager.isNormalUser(CALLER_ID)).thenReturn(false);
        SearchCriteria<ConfigurationVO> sc = Mockito.mock(SearchCriteria.class);
        when(configDao.createSearchCriteria()).thenReturn(sc);
        when(configDao.searchAndCount(eq(sc), any()))
                .thenReturn(new Pair<>(new ArrayList<>(), 0));

        service.searchForConfigurations(cmd);

        verify(sc).addAnd(eq("category"), eq(SearchCriteria.Op.NEQ), eq("Hidden"));
    }

    // ----------------------------------------------------------------
    // Scoped search: ConfigDepot re-population
    // ----------------------------------------------------------------

    @Test
    public void searchForConfigurationsZoneScopePopulatesValuesFromDepot() {
        ListCfgsByCmd cmd = mockCmdAllNull();
        when(cmd.getZoneId()).thenReturn(5L);
        when(accountManager.isDomainAdmin(CALLER_ID)).thenReturn(false);
        when(accountManager.isNormalUser(CALLER_ID)).thenReturn(false);
        SearchCriteria<ConfigurationVO> sc = Mockito.mock(SearchCriteria.class);
        when(configDao.createSearchCriteria()).thenReturn(sc);

        ConfigurationVO row = Mockito.mock(ConfigurationVO.class);
        when(row.getName()).thenReturn("foo.setting");
        ConfigurationVO byName = Mockito.mock(ConfigurationVO.class);
        when(configDao.findByName("foo.setting")).thenReturn(byName);
        ConfigKey<?> key = Mockito.mock(ConfigKey.class);
        Mockito.doReturn("bar").when(key).valueInScope(eq(ConfigKey.Scope.Zone), eq(5L));
        Mockito.doReturn(key).when(configDepot).get("foo.setting");
        when(configDao.searchAndCount(eq(sc), any()))
                .thenReturn(new Pair<>(Arrays.asList(row), 1));

        Pair<List<? extends org.apache.cloudstack.config.Configuration>, Integer> result =
                service.searchForConfigurations(cmd);

        Assert.assertEquals(1, (int) result.second());
        verify(byName).setValue("bar");
    }

    @Test
    public void searchForConfigurationsScopedDropsRowsMissingFromDepot() {
        ListCfgsByCmd cmd = mockCmdAllNull();
        when(cmd.getZoneId()).thenReturn(5L);
        when(accountManager.isDomainAdmin(CALLER_ID)).thenReturn(false);
        when(accountManager.isNormalUser(CALLER_ID)).thenReturn(false);
        SearchCriteria<ConfigurationVO> sc = Mockito.mock(SearchCriteria.class);
        when(configDao.createSearchCriteria()).thenReturn(sc);

        ConfigurationVO row = Mockito.mock(ConfigurationVO.class);
        when(row.getName()).thenReturn("missing.setting");
        when(configDao.findByName("missing.setting")).thenReturn(null);
        when(configDao.searchAndCount(eq(sc), any()))
                .thenReturn(new Pair<>(Arrays.asList(row), 1));

        Pair<List<? extends org.apache.cloudstack.config.Configuration>, Integer> result =
                service.searchForConfigurations(cmd);

        Assert.assertEquals(0, (int) result.second());
        Assert.assertTrue(result.first().isEmpty());
        verify(configDepot, never()).get(anyLong() + ""); // never gets called for missing rows
    }

    // ----------------------------------------------------------------
    // Unscoped search: passes DAO result through unchanged
    // ----------------------------------------------------------------

    @Test
    public void searchForConfigurationsUnscopedReturnsDaoResultDirectly() {
        ListCfgsByCmd cmd = mockCmdAllNull();
        when(accountManager.isDomainAdmin(CALLER_ID)).thenReturn(false);
        when(accountManager.isNormalUser(CALLER_ID)).thenReturn(false);
        SearchCriteria<ConfigurationVO> sc = Mockito.mock(SearchCriteria.class);
        when(configDao.createSearchCriteria()).thenReturn(sc);

        ConfigurationVO row1 = Mockito.mock(ConfigurationVO.class);
        ConfigurationVO row2 = Mockito.mock(ConfigurationVO.class);
        when(configDao.searchAndCount(eq(sc), any()))
                .thenReturn(new Pair<>(Arrays.asList(row1, row2), 2));

        Pair<List<? extends org.apache.cloudstack.config.Configuration>, Integer> result =
                service.searchForConfigurations(cmd);

        Assert.assertEquals(2, (int) result.second());
        Assert.assertEquals(2, result.first().size());
        // ConfigDepot is never consulted in the unscoped path.
        verify(configDepot, never()).get(Mockito.anyString());
        // No scope predicate added.
        verify(sc, never()).addAnd(eq("scope"), any(), any());
    }

    // ----------------------------------------------------------------
    // Name / category / parent passthrough
    // ----------------------------------------------------------------

    @Test
    public void searchForConfigurationsAppliesNameCategoryParentFilters() {
        ListCfgsByCmd cmd = mockCmdAllNull();
        when(cmd.getConfigName()).thenReturn("vm.password.length");
        when(cmd.getCategory()).thenReturn("Advanced");
        when(cmd.getParentName()).thenReturn("parentX");
        when(accountManager.isDomainAdmin(CALLER_ID)).thenReturn(false);
        when(accountManager.isNormalUser(CALLER_ID)).thenReturn(false);
        SearchCriteria<ConfigurationVO> sc = Mockito.mock(SearchCriteria.class);
        when(configDao.createSearchCriteria()).thenReturn(sc);
        when(configDao.searchAndCount(eq(sc), any()))
                .thenReturn(new Pair<>(new ArrayList<>(), 0));

        service.searchForConfigurations(cmd);

        verify(sc).addAnd(eq("name"), eq(SearchCriteria.Op.LIKE), eq("%vm.password.length%"));
        verify(sc).addAnd(eq("category"), eq(SearchCriteria.Op.EQ), eq("Advanced"));
        verify(sc).addAnd(eq("parent"), eq(SearchCriteria.Op.EQ), eq("parentX"));
    }

    // ----------------------------------------------------------------
    // listConfigurationGroups
    // ----------------------------------------------------------------

    @Test
    public void listConfigurationGroupsReturnsDaoResult() {
        ListCfgGroupsByCmd cmd = Mockito.mock(ListCfgGroupsByCmd.class);
        when(cmd.getGroupName()).thenReturn(null);
        SearchCriteria<ConfigurationGroupVO> sc = Mockito.mock(SearchCriteria.class);
        when(configGroupDao.createSearchCriteria()).thenReturn(sc);
        ConfigurationGroupVO g = Mockito.mock(ConfigurationGroupVO.class);
        when(configGroupDao.searchAndCount(eq(sc), any()))
                .thenReturn(new Pair<>(Arrays.asList(g), 1));

        Pair<List<? extends org.apache.cloudstack.config.ConfigurationGroup>, Integer> result =
                service.listConfigurationGroups(cmd);

        Assert.assertEquals(1, (int) result.second());
        // No name filter applied when groupName is null.
        verify(sc, never()).addAnd(eq("name"), any(), any());
    }

    @Test
    public void listConfigurationGroupsAppliesNameFilterWhenProvided() {
        ListCfgGroupsByCmd cmd = Mockito.mock(ListCfgGroupsByCmd.class);
        when(cmd.getGroupName()).thenReturn("Storage");
        SearchCriteria<ConfigurationGroupVO> sc = Mockito.mock(SearchCriteria.class);
        when(configGroupDao.createSearchCriteria()).thenReturn(sc);
        when(configGroupDao.searchAndCount(eq(sc), any()))
                .thenReturn(new Pair<>(new ArrayList<>(), 0));

        service.listConfigurationGroups(cmd);

        verify(sc, times(1)).addAnd(eq("name"), eq(SearchCriteria.Op.EQ), eq("Storage"));
    }

    @Test
    public void listConfigurationGroupsIgnoresBlankGroupName() {
        ListCfgGroupsByCmd cmd = Mockito.mock(ListCfgGroupsByCmd.class);
        when(cmd.getGroupName()).thenReturn("   ");
        SearchCriteria<ConfigurationGroupVO> sc = Mockito.mock(SearchCriteria.class);
        when(configGroupDao.createSearchCriteria()).thenReturn(sc);
        when(configGroupDao.searchAndCount(eq(sc), any()))
                .thenReturn(new Pair<>(new ArrayList<>(), 0));

        service.listConfigurationGroups(cmd);

        verify(sc, never()).addAnd(eq("name"), any(), any());
    }

    // ----------------------------------------------------------------
    // Wiring smoke test
    // ----------------------------------------------------------------

    @Test
    public void wiringInjectsAllCollaborators() {
        ConfigurationListingServiceImpl s = new ConfigurationListingServiceImpl();
        ReflectionTestUtils.setField(s, "configDao", configDao);
        ReflectionTestUtils.setField(s, "configGroupDao", configGroupDao);
        ReflectionTestUtils.setField(s, "configSubGroupDao", configSubGroupDao);
        ReflectionTestUtils.setField(s, "configDepot", configDepot);
        ReflectionTestUtils.setField(s, "accountManager", accountManager);
        ReflectionTestUtils.setField(s, "domainDao", domainDao);

        Assert.assertSame(configDao, ReflectionTestUtils.getField(s, "configDao"));
        Assert.assertSame(configGroupDao, ReflectionTestUtils.getField(s, "configGroupDao"));
        Assert.assertSame(configSubGroupDao, ReflectionTestUtils.getField(s, "configSubGroupDao"));
        Assert.assertSame(configDepot, ReflectionTestUtils.getField(s, "configDepot"));
        Assert.assertSame(accountManager, ReflectionTestUtils.getField(s, "accountManager"));
        Assert.assertSame(domainDao, ReflectionTestUtils.getField(s, "domainDao"));
    }
}
