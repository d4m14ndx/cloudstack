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

import com.cloud.api.query.dao.NetworkOfferingJoinDao;
import com.cloud.api.query.vo.NetworkOfferingJoinVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import org.apache.cloudstack.context.CallContext;
import com.cloud.network.vpc.VpcManager;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingDetailsDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.user.AccountManager;
import com.cloud.utils.DomainHelper;
import com.cloud.utils.Pair;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchCriteria;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.command.admin.network.UpdateNetworkOfferingCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkOfferingsCmd;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.Spy;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class NetworkOfferingServiceImplTest {

    @InjectMocks
    @Spy
    NetworkOfferingServiceImpl networkOfferingServiceSpy;

    @Mock
    NetworkOfferingDao networkOfferingDao;
    @Mock
    NetworkOfferingJoinDao networkOfferingJoinDao;
    @Mock
    NetworkOfferingDetailsDao networkOfferingDetailsDao;
    @Mock
    NetworkOfferingServiceMapDao ntwkOffServiceMapDao;
    @Mock
    ConfigurationDao configDao;
    @Mock
    EntityManager entityMgr;
    @Mock
    AccountManager accountMgr;
    @Mock
    VpcManager vpcMgr;
    @Mock
    NetworkService networkSvc;
    @Mock
    NetworkModel networkModel;
    @Mock
    AnnotationDao annotationDao;
    @Mock
    MessageBus messageBus;
    @Mock
    DomainHelper domainHelper;

    private AutoCloseable closeable;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        AccountVO account = new AccountVO("testaccount", 1L, "networkdomain",
                Account.Type.NORMAL, java.util.UUID.randomUUID().toString());
        UserVO user = new UserVO(1, "testuser", "password", "firstname", "lastName",
                "email", "timezone", java.util.UUID.randomUUID().toString(), User.Source.UNKNOWN);
        CallContext.register(user, account);
    }

    @After
    public void tearDown() throws Exception {
        CallContext.unregister();
        closeable.close();
    }

    // -----------------------------------------------------------------------
    // verifyRoutingMode
    // -----------------------------------------------------------------------

    @Test
    public void verifyRoutingModeNullReturnsNull() {
        Assert.assertNull(NetworkOfferingServiceImpl.verifyRoutingMode(null));
    }

    @Test
    public void verifyRoutingModeDynamicReturnsEnum() {
        NetworkOffering.RoutingMode mode = NetworkOfferingServiceImpl.verifyRoutingMode("Dynamic");
        Assert.assertEquals(NetworkOffering.RoutingMode.Dynamic, mode);
    }

    @Test
    public void verifyRoutingModeStaticReturnsEnum() {
        NetworkOffering.RoutingMode mode = NetworkOfferingServiceImpl.verifyRoutingMode("Static");
        Assert.assertEquals(NetworkOffering.RoutingMode.Static, mode);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void verifyRoutingModeInvalidStringThrows() {
        NetworkOfferingServiceImpl.verifyRoutingMode("InvalidMode");
    }

    // -----------------------------------------------------------------------
    // getExternalNetworkProvider
    // -----------------------------------------------------------------------

    @Test
    public void getExternalNetworkProviderReturnsNonEmptyDetected() {
        String result = NetworkOfferingServiceImpl.getExternalNetworkProvider(
                "CustomProvider", Collections.emptyMap());
        Assert.assertEquals("CustomProvider", result);
    }

    @Test
    public void getExternalNetworkProviderDetectsNsxFromMap() {
        Map<Service, Set<Provider>> serviceProviderMap = new HashMap<>();
        Set<Provider> providers = new HashSet<>();
        providers.add(Provider.Nsx);
        serviceProviderMap.put(Service.Firewall, providers);
        String result = NetworkOfferingServiceImpl.getExternalNetworkProvider("", serviceProviderMap);
        Assert.assertEquals("NSX", result);
    }

    @Test
    public void getExternalNetworkProviderDetectsNetrisFromMap() {
        Map<Service, Set<Provider>> serviceProviderMap = new HashMap<>();
        Set<Provider> providers = new HashSet<>();
        providers.add(Provider.Netris);
        serviceProviderMap.put(Service.Connectivity, providers);
        String result = NetworkOfferingServiceImpl.getExternalNetworkProvider("", serviceProviderMap);
        Assert.assertEquals(Provider.Netris.getName(), result);
    }

    @Test
    public void getExternalNetworkProviderReturnsNullWhenNoMatchingProviders() {
        Map<Service, Set<Provider>> serviceProviderMap = new HashMap<>();
        Set<Provider> providers = new HashSet<>();
        providers.add(Provider.VirtualRouter);
        serviceProviderMap.put(Service.Dhcp, providers);
        String result = NetworkOfferingServiceImpl.getExternalNetworkProvider("", serviceProviderMap);
        Assert.assertNull(result);
    }

    // -----------------------------------------------------------------------
    // isRedundantRouter
    // -----------------------------------------------------------------------

    @Test
    public void isRedundantRouterReturnsTrueWhenCapabilitySetTrue() {
        Map<Capability, String> map = new HashMap<>();
        map.put(Capability.RedundantRouter, "true");
        Assert.assertTrue(networkOfferingServiceSpy.isRedundantRouter(
                Collections.emptySet(), Service.SourceNat, map));
    }

    @Test
    public void isRedundantRouterReturnsFalseWhenCapabilitySetFalse() {
        Map<Capability, String> map = new HashMap<>();
        map.put(Capability.RedundantRouter, "false");
        Assert.assertFalse(networkOfferingServiceSpy.isRedundantRouter(
                Collections.emptySet(), Service.SourceNat, map));
    }

    // -----------------------------------------------------------------------
    // isSharedSourceNat
    // -----------------------------------------------------------------------

    @Test
    public void isSharedSourceNatReturnsTrueForPerZone() {
        Map<Capability, String> map = new HashMap<>();
        map.put(Capability.SupportedSourceNatTypes, "perzone");
        Assert.assertTrue(networkOfferingServiceSpy.isSharedSourceNat(
                Collections.emptyMap(), map));
    }

    @Test
    public void isSharedSourceNatReturnsFalseForPerAccount() {
        Map<Capability, String> map = new HashMap<>();
        map.put(Capability.SupportedSourceNatTypes, "peraccount");
        Assert.assertFalse(networkOfferingServiceSpy.isSharedSourceNat(
                Collections.emptyMap(), map));
    }

    // -----------------------------------------------------------------------
    // sourceNatCapabilitiesContainValidValues
    // -----------------------------------------------------------------------

    @Test
    public void sourceNatCapabilitiesContainValidValuesReturnsTrueForValid() {
        Map<Capability, String> map = new HashMap<>();
        map.put(Capability.SupportedSourceNatTypes, "peraccount");
        map.put(Capability.RedundantRouter, "true");
        Assert.assertTrue(networkOfferingServiceSpy.sourceNatCapabilitiesContainValidValues(map));
    }

    @Test
    public void sourceNatCapabilitiesContainValidValuesReturnsFalseForUnknownCapability() {
        // An unrecognized capability (not SupportedSourceNatTypes or RedundantRouter) returns false
        Map<Capability, String> map = new HashMap<>();
        map.put(Capability.ElasticIp, "true");
        Assert.assertFalse(networkOfferingServiceSpy.sourceNatCapabilitiesContainValidValues(map));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void sourceNatCapabilitiesContainValidValuesThrowsForInvalidBoolValue() {
        // RedundantRouter with non-boolean value throws InvalidParameterValueException
        Map<Capability, String> map = new HashMap<>();
        map.put(Capability.RedundantRouter, "notaboolean");
        networkOfferingServiceSpy.sourceNatCapabilitiesContainValidValues(map);
    }

    // -----------------------------------------------------------------------
    // validateSourceNatServiceCapablities
    // -----------------------------------------------------------------------

    @Test
    public void validateSourceNatServiceCapablitiesPassesForEmptyMap() {
        // should not throw
        networkOfferingServiceSpy.validateSourceNatServiceCapablities(Collections.emptyMap());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateSourceNatServiceCapablitiesThrowsForInvalidNatType() {
        Map<Capability, String> map = new HashMap<>();
        map.put(Capability.SupportedSourceNatTypes, "perDomain");
        networkOfferingServiceSpy.validateSourceNatServiceCapablities(map);
    }

    // -----------------------------------------------------------------------
    // validateStaticNatServiceCapablities
    // -----------------------------------------------------------------------

    @Test
    public void validateStaticNatServiceCapablitiesPassesForEmptyMap() {
        networkOfferingServiceSpy.validateStaticNatServiceCapablities(Collections.emptyMap());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateStaticNatServiceCapablitiesThrowsWhenAssociatePublicIpSetWithoutElasticIp() {
        // associatePublicIP=true (default) but eipEnabled=false → throws
        Map<Capability, String> map = new HashMap<>();
        map.put(Capability.ElasticIp, "false");
        networkOfferingServiceSpy.validateStaticNatServiceCapablities(map);
    }

    // -----------------------------------------------------------------------
    // isOfferingForVpc
    // -----------------------------------------------------------------------

    @Test
    public void isOfferingForVpcReturnsTrueWhenFlagSet() {
        NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        when(offering.isForVpc()).thenReturn(true);
        Assert.assertTrue(networkOfferingServiceSpy.isOfferingForVpc(offering));
    }

    @Test
    public void isOfferingForVpcReturnsFalseWhenFlagUnset() {
        NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        when(offering.isForVpc()).thenReturn(false);
        Assert.assertFalse(networkOfferingServiceSpy.isOfferingForVpc(offering));
    }

    // -----------------------------------------------------------------------
    // searchForNetworkOfferings
    // -----------------------------------------------------------------------

    @Test
    public void searchForNetworkOfferingsFiltersForVpcFalse() {
        NetworkOfferingJoinVO forVpcOffering = new NetworkOfferingJoinVO();
        forVpcOffering.setForVpc(true);
        List<NetworkOfferingJoinVO> offerings = Arrays.asList(
                new NetworkOfferingJoinVO(), new NetworkOfferingJoinVO(), forVpcOffering);

        when(networkOfferingJoinDao.createSearchCriteria()).thenReturn(mock(SearchCriteria.class));
        when(networkOfferingJoinDao.search(any(SearchCriteria.class), any(Filter.class))).thenReturn(offerings);

        // Stub Long fields to null to avoid zone/domain/network lookup branches
        ListNetworkOfferingsCmd cmd = mock(ListNetworkOfferingsCmd.class);
        when(cmd.getPageSize()).thenReturn(10);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getDomainId()).thenReturn(null);
        when(cmd.getZoneId()).thenReturn(null);
        when(cmd.getNetworkId()).thenReturn(null);
        when(cmd.getForVpc()).thenReturn(Boolean.FALSE);

        Pair<List<? extends NetworkOffering>, Integer> result =
                networkOfferingServiceSpy.searchForNetworkOfferings(cmd);

        Assert.assertEquals(2, (int) result.second());
    }

    @Test
    public void searchForNetworkOfferingsReturnsAllWhenForVpcNull() {
        NetworkOfferingJoinVO forVpcOffering = new NetworkOfferingJoinVO();
        forVpcOffering.setForVpc(true);
        List<NetworkOfferingJoinVO> offerings = Arrays.asList(
                new NetworkOfferingJoinVO(), new NetworkOfferingJoinVO(), forVpcOffering);

        when(networkOfferingJoinDao.createSearchCriteria()).thenReturn(mock(SearchCriteria.class));
        when(networkOfferingJoinDao.search(any(SearchCriteria.class), any(Filter.class))).thenReturn(offerings);

        // Stub Long/Boolean fields to null to avoid zone/domain/network lookup branches
        ListNetworkOfferingsCmd cmd = mock(ListNetworkOfferingsCmd.class);
        when(cmd.getPageSize()).thenReturn(10);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getDomainId()).thenReturn(null);
        when(cmd.getZoneId()).thenReturn(null);
        when(cmd.getNetworkId()).thenReturn(null);
        when(cmd.getForVpc()).thenReturn(null);

        Pair<List<? extends NetworkOffering>, Integer> result =
                networkOfferingServiceSpy.searchForNetworkOfferings(cmd);

        Assert.assertEquals(3, (int) result.second());
    }

    // -----------------------------------------------------------------------
    // setField / findField (static reflection utilities)
    // -----------------------------------------------------------------------

    @Test
    public void findFieldLocatesFieldInParentClass() throws Exception {
        // NetworkOfferingVO inherits from NetworkOfferingBase which has fields
        // We just test with a simple inner class hierarchy
        java.lang.reflect.Field f = NetworkOfferingServiceImpl.findField(
                NetworkOfferingVO.class, "name");
        Assert.assertNotNull(f);
    }

    @Test
    public void findFieldReturnsNullForNonexistentField() {
        java.lang.reflect.Field f = NetworkOfferingServiceImpl.findField(
                NetworkOfferingVO.class, "nonExistentField123");
        Assert.assertNull(f);
    }

    @Test
    public void setFieldSetsValueViaReflection() throws Exception {
        NetworkOfferingVO offering = new NetworkOfferingVO();
        NetworkOfferingServiceImpl.setField(offering, "name", "test-name");
        Assert.assertEquals("test-name", offering.getName());
    }

    // -----------------------------------------------------------------------
    // deleteNetworkOffering – validate offering not found
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void deleteNetworkOfferingThrowsWhenOfferingNotFound() {
        org.apache.cloudstack.api.command.admin.network.DeleteNetworkOfferingCmd cmd =
                mock(org.apache.cloudstack.api.command.admin.network.DeleteNetworkOfferingCmd.class);
        when(cmd.getId()).thenReturn(42L);
        when(networkOfferingDao.findById(42L)).thenReturn(null);
        networkOfferingServiceSpy.deleteNetworkOffering(cmd);
    }

    // -----------------------------------------------------------------------
    // updateNetworkOffering – validate offering not found
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void updateNetworkOfferingThrowsWhenOfferingNotFound() {
        UpdateNetworkOfferingCmd cmd = mock(UpdateNetworkOfferingCmd.class);
        when(cmd.getId()).thenReturn(99L);
        when(networkOfferingDao.findById(99L)).thenReturn(null);
        networkOfferingServiceSpy.updateNetworkOffering(cmd);
    }
}
