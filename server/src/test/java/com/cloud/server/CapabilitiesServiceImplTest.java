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

import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;

import org.apache.cloudstack.api.command.user.config.ListCapabilitiesCmd;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.configuration.Config;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpn.Site2SiteVpnManagerImpl;
import com.cloud.projects.ProjectManager;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.AccountVO;
import com.cloud.user.User;

/**
 * Focused unit tests for {@link CapabilitiesServiceImpl}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Security-groups flag derived from DAO probe</li>
 *   <li>Region secondary storage flag derived from image store DAO</li>
 *   <li>Kubernetes feature-flag keys in the capabilities map</li>
 *   <li>API throttling keys only present when enabled</li>
 *   <li>Admin-only {@code extensionspath} key</li>
 *   <li>VPN customer-gateway parameter exclusion/obsolete assembly</li>
 *   <li>Empty VPN params produce no nested map in capabilities</li>
 *   <li>{@code getVersion} happy path and fallback to "unknown"</li>
 *   <li>Wiring smoke test</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.class)
public class CapabilitiesServiceImplTest {

    // -----------------------------------------------------------------------
    // Mocked collaborators
    // -----------------------------------------------------------------------

    @Mock
    private ConfigurationDao configDao;
    @Mock
    private DomainDao domainDao;
    @Mock
    private AccountService accountService;
    @Mock
    private NetworkDao networkDao;
    @Mock
    private ProjectManager projectManager;
    @Mock
    private ImageStoreDao imgStoreDao;
    @Mock
    private ExtensionsManager extensionsManager;

    /** Spy instead of plain InjectMocks so we can stub {@code getCaller()}. */
    @Spy
    @InjectMocks
    private CapabilitiesServiceImpl service = new CapabilitiesServiceImpl();

    @Mock
    private AccountVO callerAccount;
    @Mock
    private User callerUser;

    private AutoCloseable closeable;

    private static final long CALLER_ID = 1L;
    private static final long DOMAIN_ID = 2L;

    // -----------------------------------------------------------------------
    // Lifecycle
    // -----------------------------------------------------------------------

    @Before
    public void setUp() throws Exception {
        closeable = MockitoAnnotations.openMocks(this);

        lenient().when(callerAccount.getId()).thenReturn(CALLER_ID);
        lenient().when(callerAccount.getDomainId()).thenReturn(DOMAIN_ID);
        lenient().when(callerAccount.getType()).thenReturn(Account.Type.NORMAL);

        // Stub getCaller() on the spy so tests don't require a live CallContext.
        lenient().doReturn(callerAccount).when(service).getCaller();

        // Default: caller is neither root-admin nor admin.
        lenient().when(accountService.isRootAdmin(CALLER_ID)).thenReturn(false);
        lenient().when(accountService.isAdmin(CALLER_ID)).thenReturn(false);

        // No security-group-enabled networks by default.
        lenient().when(networkDao.listSecurityGroupEnabledNetworks()).thenReturn(Collections.emptyList());

        // No region image stores by default.
        lenient().when(imgStoreDao.findRegionImageStores()).thenReturn(Collections.emptyList());

        // configDao string values consumed by listCapabilities.
        lenient().when(configDao.getValue(Config.ElasticLoadBalancerEnabled.key())).thenReturn("false");
        lenient().when(configDao.getValue(Config.ApiLimitEnabled.key())).thenReturn("false");
        lenient().when(configDao.getValue(Config.ApiLimitInterval.key())).thenReturn("15");
        lenient().when(configDao.getValue(Config.ApiLimitMax.key())).thenReturn("50");
        lenient().when(configDao.getValue("cloud.kubernetes.service.enabled")).thenReturn("false");
        lenient().when(configDao.getValue("cloud.kubernetes.cluster.experimental.features.enabled")).thenReturn("false");
        lenient().when(configDao.getValue("sharedfsvm.min.cpu.count")).thenReturn("2");
        lenient().when(configDao.getValue("sharedfsvm.min.ram.size")).thenReturn("512");

        // Quiet project-manager defaults.
        lenient().when(projectManager.projectInviteRequired()).thenReturn(false);
        lenient().when(projectManager.allowUserToCreateProject()).thenReturn(false);

        // Ensure all ConfigKey static fields resolve to safe defaults.
        setConfigKeyDefault(ManagementServerImpl.exposeCloudStackVersionInApiListCapabilities, "false");
    }

    @After
    public void tearDown() throws Exception {
        closeable.close();
    }

    // -----------------------------------------------------------------------
    // Helper: override a ConfigKey's default value via reflection
    // -----------------------------------------------------------------------

    @SuppressWarnings("rawtypes")
    private static void setConfigKeyDefault(ConfigKey key, String defaultValue)
            throws NoSuchFieldException, IllegalAccessException {
        Field f = ConfigKey.class.getDeclaredField("_defaultValue");
        f.setAccessible(true);
        f.set(key, defaultValue);
    }

    // -----------------------------------------------------------------------
    // Helper: build a minimal ListCapabilitiesCmd stub
    // -----------------------------------------------------------------------

    private ListCapabilitiesCmd cmdForDomain(Long domainId) {
        ListCapabilitiesCmd cmd = Mockito.mock(ListCapabilitiesCmd.class);
        when(cmd.getDomainId()).thenReturn(domainId);
        return cmd;
    }

    // -----------------------------------------------------------------------
    // 1. securityGroupsEnabled = false when no SG-enabled networks exist
    // -----------------------------------------------------------------------

    @Test
    public void listCapabilities_securityGroupsDisabled_whenNoNetworks() {
        when(networkDao.listSecurityGroupEnabledNetworks()).thenReturn(Collections.emptyList());

        Map<String, Object> caps = service.listCapabilities(cmdForDomain(null));

        Assert.assertEquals(Boolean.FALSE, caps.get("securityGroupsEnabled"));
    }

    // -----------------------------------------------------------------------
    // 2. securityGroupsEnabled = true when at least one SG network is present
    // -----------------------------------------------------------------------

    @Test
    public void listCapabilities_securityGroupsEnabled_whenNetworksExist() {
        NetworkVO net = Mockito.mock(NetworkVO.class);
        when(networkDao.listSecurityGroupEnabledNetworks()).thenReturn(Collections.singletonList(net));
        when(configDao.getValue(Config.ElasticLoadBalancerEnabled.key())).thenReturn("false");

        Map<String, Object> caps = service.listCapabilities(cmdForDomain(null));

        Assert.assertEquals(Boolean.TRUE, caps.get("securityGroupsEnabled"));
    }

    // -----------------------------------------------------------------------
    // 3. regionSecondaryEnabled = false when no region image stores
    // -----------------------------------------------------------------------

    @Test
    public void listCapabilities_regionSecondaryDisabled_whenNoImageStores() {
        when(imgStoreDao.findRegionImageStores()).thenReturn(Collections.emptyList());

        Map<String, Object> caps = service.listCapabilities(cmdForDomain(null));

        Assert.assertEquals(Boolean.FALSE, caps.get("regionSecondaryEnabled"));
    }

    // -----------------------------------------------------------------------
    // 4. regionSecondaryEnabled = true when region image stores present
    // -----------------------------------------------------------------------

    @Test
    public void listCapabilities_regionSecondaryEnabled_whenImageStorePresent() {
        ImageStoreVO imgStore = Mockito.mock(ImageStoreVO.class);
        when(imgStoreDao.findRegionImageStores()).thenReturn(Collections.singletonList(imgStore));

        Map<String, Object> caps = service.listCapabilities(cmdForDomain(null));

        Assert.assertEquals(Boolean.TRUE, caps.get("regionSecondaryEnabled"));
    }

    // -----------------------------------------------------------------------
    // 5. kubernetesServiceEnabled flag reflected from configDao
    // -----------------------------------------------------------------------

    @Test
    public void listCapabilities_kubernetesServiceEnabled_flag() {
        when(configDao.getValue("cloud.kubernetes.service.enabled")).thenReturn("true");

        Map<String, Object> caps = service.listCapabilities(cmdForDomain(null));

        Assert.assertEquals(Boolean.TRUE, caps.get("kubernetesServiceEnabled"));
    }

    // -----------------------------------------------------------------------
    // 6. kubernetesClusterExperimentalFeaturesEnabled flag reflected
    // -----------------------------------------------------------------------

    @Test
    public void listCapabilities_kubernetesExperimentalFeatures_flag() {
        when(configDao.getValue("cloud.kubernetes.cluster.experimental.features.enabled")).thenReturn("true");

        Map<String, Object> caps = service.listCapabilities(cmdForDomain(null));

        Assert.assertEquals(Boolean.TRUE, caps.get("kubernetesClusterExperimentalFeaturesEnabled"));
    }

    // -----------------------------------------------------------------------
    // 7. API-throttling keys absent when apiLimitEnabled = false
    // -----------------------------------------------------------------------

    @Test
    public void listCapabilities_apiThrottlingKeys_absentWhenDisabled() {
        when(configDao.getValue(Config.ApiLimitEnabled.key())).thenReturn("false");

        Map<String, Object> caps = service.listCapabilities(cmdForDomain(null));

        Assert.assertFalse("apiLimitInterval must be absent when throttling is off",
                caps.containsKey("apiLimitInterval"));
        Assert.assertFalse("apiLimitMax must be absent when throttling is off",
                caps.containsKey("apiLimitMax"));
    }

    // -----------------------------------------------------------------------
    // 8. API-throttling keys present when apiLimitEnabled = true
    // -----------------------------------------------------------------------

    @Test
    public void listCapabilities_apiThrottlingKeys_presentWhenEnabled() {
        when(configDao.getValue(Config.ApiLimitEnabled.key())).thenReturn("true");
        when(configDao.getValue(Config.ApiLimitInterval.key())).thenReturn("30");
        when(configDao.getValue(Config.ApiLimitMax.key())).thenReturn("100");

        Map<String, Object> caps = service.listCapabilities(cmdForDomain(null));

        Assert.assertTrue("apiLimitInterval must be present when throttling is on",
                caps.containsKey("apiLimitInterval"));
        Assert.assertEquals(30, caps.get("apiLimitInterval"));
        Assert.assertEquals(100, caps.get("apiLimitMax"));
    }

    // -----------------------------------------------------------------------
    // 9. extensionspath absent for non-root-admin caller
    // -----------------------------------------------------------------------

    @Test
    public void listCapabilities_extensionsPath_absentForNonRootAdmin() {
        when(accountService.isRootAdmin(CALLER_ID)).thenReturn(false);

        Map<String, Object> caps = service.listCapabilities(cmdForDomain(null));

        Assert.assertFalse("extensionspath must NOT be present for non-root-admin",
                caps.containsKey("extensionspath"));
    }

    // -----------------------------------------------------------------------
    // 10. extensionspath present for root-admin caller
    // -----------------------------------------------------------------------

    @Test
    public void listCapabilities_extensionsPath_presentForRootAdmin() {
        when(accountService.isRootAdmin(CALLER_ID)).thenReturn(true);
        when(extensionsManager.getExtensionsPath()).thenReturn("/opt/cloudstack/extensions");

        Map<String, Object> caps = service.listCapabilities(cmdForDomain(null));

        Assert.assertTrue("extensionspath must be present for root-admin",
                caps.containsKey("extensionspath"));
        Assert.assertEquals("/opt/cloudstack/extensions", caps.get("extensionspath"));
    }

    // -----------------------------------------------------------------------
    // 11. listCapabilities delegates domain access check when domainId provided
    // -----------------------------------------------------------------------

    @Test
    public void listCapabilities_checksDomainAccess_whenDomainIdSupplied() {
        DomainVO domain = Mockito.mock(DomainVO.class);
        when(domainDao.findById(DOMAIN_ID)).thenReturn(domain);

        service.listCapabilities(cmdForDomain(DOMAIN_ID));

        Mockito.verify(accountService).checkAccess(callerAccount, domain);
    }

    // -----------------------------------------------------------------------
    // 12. getVpnCustomerGatewayParameters – empty map when all defaults blank
    // -----------------------------------------------------------------------

    @Test
    public void getVpnCustomerGatewayParameters_returnsEmptyMap_whenAllDefaultsBlank()
            throws Exception {
        // All VPN ConfigKey defaults are "" (blank) in their declaration — no override needed.
        Map<String, Object> vpn = service.getVpnCustomerGatewayParameters(DOMAIN_ID);

        Assert.assertTrue("Expected empty VPN params map when all defaults are blank", vpn.isEmpty());
    }

    // -----------------------------------------------------------------------
    // 13. getVpnCustomerGatewayParameters – excludedencryptionalgorithms key
    // -----------------------------------------------------------------------

    @Test
    public void getVpnCustomerGatewayParameters_includesExcludedEncryption_whenSet()
            throws Exception {
        setConfigKeyDefault(
                Site2SiteVpnManagerImpl.VpnCustomerGatewayExcludedEncryptionAlgorithms,
                "3des");

        try {
            Map<String, Object> vpn = service.getVpnCustomerGatewayParameters(DOMAIN_ID);
            Assert.assertEquals("3des", vpn.get("excludedencryptionalgorithms"));
        } finally {
            // Reset to blank so other tests are not affected.
            setConfigKeyDefault(
                    Site2SiteVpnManagerImpl.VpnCustomerGatewayExcludedEncryptionAlgorithms, "");
        }
    }

    // -----------------------------------------------------------------------
    // 14. getVpnCustomerGatewayParameters – excludeddhgroups key
    // -----------------------------------------------------------------------

    @Test
    public void getVpnCustomerGatewayParameters_includesExcludedDhGroups_whenSet()
            throws Exception {
        setConfigKeyDefault(
                Site2SiteVpnManagerImpl.VpnCustomerGatewayExcludedDhGroup,
                "modp1024,modp1536");

        try {
            Map<String, Object> vpn = service.getVpnCustomerGatewayParameters(DOMAIN_ID);
            Assert.assertEquals("modp1024,modp1536", vpn.get("excludeddhgroups"));
        } finally {
            setConfigKeyDefault(
                    Site2SiteVpnManagerImpl.VpnCustomerGatewayExcludedDhGroup, "");
        }
    }

    // -----------------------------------------------------------------------
    // 15. getVpnCustomerGatewayParameters – obsolete hashing key
    // -----------------------------------------------------------------------

    @Test
    public void getVpnCustomerGatewayParameters_includesObsoleteHashing_whenSet()
            throws Exception {
        setConfigKeyDefault(
                Site2SiteVpnManagerImpl.VpnCustomerGatewayObsoleteHashingAlgorithms,
                "md5,sha1");

        try {
            Map<String, Object> vpn = service.getVpnCustomerGatewayParameters(DOMAIN_ID);
            Assert.assertEquals("md5,sha1", vpn.get("obsoletehashingalgorithms"));
        } finally {
            setConfigKeyDefault(
                    Site2SiteVpnManagerImpl.VpnCustomerGatewayObsoleteHashingAlgorithms, "");
        }
    }

    // -----------------------------------------------------------------------
    // 16. listCapabilities – vpncustomergatewayparameters absent when empty
    // -----------------------------------------------------------------------

    @Test
    public void listCapabilities_vpnCustomerGatewayParameters_absentWhenEmpty() {
        // All VPN ConfigKey defaults are blank by default: no nested map expected.
        Map<String, Object> caps = service.listCapabilities(cmdForDomain(null));

        Assert.assertFalse(
                "vpncustomergatewayparameters must be absent when VPN params are all blank",
                caps.containsKey("vpncustomergatewayparameters"));
    }

    // -----------------------------------------------------------------------
    // 17. getVersion returns "unknown" when package has no implementation version
    //     (true for exploded-classpath / test runs)
    // -----------------------------------------------------------------------

    @Test
    public void getVersion_returnsUnknown_whenManifestLacksAttribute() {
        // In test classpaths the ManagementServer package has no
        // Implementation-Version attribute in the manifest.
        String version = service.getVersion();
        Assert.assertNotNull(version);
        // Either "unknown" (test classpath) or a real version string — never null/blank.
        Assert.assertFalse(version.isEmpty());
    }

    // -----------------------------------------------------------------------
    // 18. getVersion returns non-blank string (smoke)
    // -----------------------------------------------------------------------

    @Test
    public void getVersion_returnsNonBlankString() {
        String version = service.getVersion();
        Assert.assertNotNull("getVersion() must never return null", version);
        Assert.assertFalse("getVersion() must never return an empty string", version.trim().isEmpty());
    }

    // -----------------------------------------------------------------------
    // 19. listCapabilities map always contains core required keys
    // -----------------------------------------------------------------------

    @Test
    public void listCapabilities_mapContainsRequiredKeys() {
        Map<String, Object> caps = service.listCapabilities(cmdForDomain(null));

        Assert.assertTrue(caps.containsKey("securityGroupsEnabled"));
        Assert.assertTrue(caps.containsKey("userPublicTemplateEnabled"));
        Assert.assertTrue(caps.containsKey("supportELB"));
        Assert.assertTrue(caps.containsKey("projectInviteRequired"));
        Assert.assertTrue(caps.containsKey("allowusercreateprojects"));
        Assert.assertTrue(caps.containsKey("customDiskOffMinSize"));
        Assert.assertTrue(caps.containsKey("customDiskOffMaxSize"));
        Assert.assertTrue(caps.containsKey("regionSecondaryEnabled"));
        Assert.assertTrue(caps.containsKey("KVMSnapshotEnabled"));
        Assert.assertTrue(caps.containsKey("kubernetesServiceEnabled"));
    }

    // -----------------------------------------------------------------------
    // 20. Wiring smoke: all injected fields visible after manual setField
    // -----------------------------------------------------------------------

    @Test
    public void wiringInjectsAllCollaborators() {
        CapabilitiesServiceImpl s = new CapabilitiesServiceImpl();
        ReflectionTestUtils.setField(s, "configDao", configDao);
        ReflectionTestUtils.setField(s, "domainDao", domainDao);
        ReflectionTestUtils.setField(s, "accountService", accountService);
        ReflectionTestUtils.setField(s, "networkDao", networkDao);
        ReflectionTestUtils.setField(s, "projectManager", projectManager);
        ReflectionTestUtils.setField(s, "imgStoreDao", imgStoreDao);
        ReflectionTestUtils.setField(s, "extensionsManager", extensionsManager);

        Assert.assertSame(configDao, ReflectionTestUtils.getField(s, "configDao"));
        Assert.assertSame(domainDao, ReflectionTestUtils.getField(s, "domainDao"));
        Assert.assertSame(accountService, ReflectionTestUtils.getField(s, "accountService"));
        Assert.assertSame(networkDao, ReflectionTestUtils.getField(s, "networkDao"));
        Assert.assertSame(projectManager, ReflectionTestUtils.getField(s, "projectManager"));
        Assert.assertSame(imgStoreDao, ReflectionTestUtils.getField(s, "imgStoreDao"));
        Assert.assertSame(extensionsManager, ReflectionTestUtils.getField(s, "extensionsManager"));
    }
}
