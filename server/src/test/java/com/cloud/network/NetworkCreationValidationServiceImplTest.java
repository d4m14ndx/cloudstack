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
package com.cloud.network;

import java.util.Arrays;
import java.util.Collections;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.network.RoutedIpv4Manager;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.element.NsxProviderVO;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class NetworkCreationValidationServiceImplTest {

    @InjectMocks
    private NetworkCreationValidationServiceImpl service;

    @Mock
    private NetworkOfferingVO networkOfferingVO;

    @Mock
    private DataCenterDao _dcDao;
    @Mock
    private NetworkDao _networksDao;
    @Mock
    private PhysicalNetworkDao _physicalNetworkDao;
    @Mock
    private ConfigurationDao _configDao;
    @Mock
    private RoutedIpv4Manager routedIpv4Manager;
    @Mock
    private AccountManager _accountMgr;
    @Mock
    private NsxProviderDao nsxProviderDao;

    private static final String VLAN_ID_900 = "900";
    private static final String VLAN_ID_901 = "901";
    private static final String VLAN_ID_902 = "902";
    private static final String IP4_GATEWAY = "10.0.16.1";
    private static final String IP4_NETMASK = "255.255.255.0";
    private static final String IP6_CIDR = "fd17:ac56:1234:2000::/64";

    @Test(expected = InvalidParameterValueException.class)
    public void getAndValidateSupportForKeepMacAddressOnPublicNicParameterThrowsWhenParamIsSpecifiedOnVpcTierCreation() {
        Mockito.when(networkOfferingVO.isForVpc()).thenReturn(true);

        service.getAndValidateSupportForKeepMacAddressOnPublicNicParameter(true, networkOfferingVO);
    }

    @Test
    public void testGetPrivateVlanPairNoVlans() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(null, null, null);
        Assert.assertNull(pair.first());
        Assert.assertNull(pair.second());
    }

    @Test
    public void testGetPrivateVlanPairVlanPrimaryOnly() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(null, null, VLAN_ID_900);
        Assert.assertNull(pair.first());
        Assert.assertNull(pair.second());
    }

    @Test
    public void testGetPrivateVlanPairVlanPrimaryPromiscuousType() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(null, Network.PVlanType.Promiscuous.toString(), VLAN_ID_900);
        Assert.assertEquals(VLAN_ID_900, pair.first());
        Assert.assertEquals(Network.PVlanType.Promiscuous, pair.second());
    }

    @Test
    public void testGetPrivateVlanPairPromiscuousType() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(VLAN_ID_900, Network.PVlanType.Promiscuous.toString(), VLAN_ID_900);
        Assert.assertEquals(VLAN_ID_900, pair.first());
        Assert.assertEquals(Network.PVlanType.Promiscuous, pair.second());
    }

    @Test
    public void testGetPrivateVlanPairPromiscuousTypeOnSecondaryVlanId() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(VLAN_ID_900, "promiscuous", VLAN_ID_900);
        Assert.assertEquals(VLAN_ID_900, pair.first());
        Assert.assertEquals(Network.PVlanType.Promiscuous, pair.second());
    }

    @Test
    public void testGetPrivateVlanPairIsolatedType() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(VLAN_ID_901, Network.PVlanType.Isolated.toString(), VLAN_ID_900);
        Assert.assertEquals(VLAN_ID_901, pair.first());
        Assert.assertEquals(Network.PVlanType.Isolated, pair.second());
    }

    @Test
    public void testGetPrivateVlanPairIsolatedTypeOnSecondaryVlanId() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(VLAN_ID_901, "isolated", VLAN_ID_900);
        Assert.assertEquals(VLAN_ID_901, pair.first());
        Assert.assertEquals(Network.PVlanType.Isolated, pair.second());
    }

    @Test
    public void testGetPrivateVlanPairCommunityType() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(VLAN_ID_902, Network.PVlanType.Community.toString(), VLAN_ID_900);
        Assert.assertEquals(VLAN_ID_902, pair.first());
        Assert.assertEquals(Network.PVlanType.Community, pair.second());
    }

    @Test
    public void testGetPrivateVlanPairCommunityTypeOnSecondaryVlanId() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(VLAN_ID_902, "community", VLAN_ID_900);
        Assert.assertEquals(VLAN_ID_902, pair.first());
        Assert.assertEquals(Network.PVlanType.Community, pair.second());
    }

    @Test(expected = CloudRuntimeException.class)
    public void testPerformBasicChecksPromiscuousTypeExpectedIsolatedSet() {
        service.performBasicPrivateVlanChecks(VLAN_ID_900, VLAN_ID_900, Network.PVlanType.Isolated);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testPerformBasicChecksPromiscuousTypeExpectedCommunitySet() {
        service.performBasicPrivateVlanChecks(VLAN_ID_900, VLAN_ID_900, Network.PVlanType.Community);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testPerformBasicChecksPromiscuousTypeExpectedSecondaryVlanNullIsolatedSet() {
        service.performBasicPrivateVlanChecks(VLAN_ID_900, null, Network.PVlanType.Isolated);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testPerformBasicChecksPromiscuousTypeExpectedSecondaryVlanNullCommunitySet() {
        service.performBasicPrivateVlanChecks(VLAN_ID_900, null, Network.PVlanType.Community);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testPerformBasicChecksPromiscuousTypeExpectedDifferentVlanIds() {
        service.performBasicPrivateVlanChecks(VLAN_ID_900, VLAN_ID_901, Network.PVlanType.Promiscuous);
    }

    @Test
    public void validateNotSharedNetworkRouterIPv4() {
        NetworkOffering ntwkOff = Mockito.mock(NetworkOffering.class);
        Mockito.when(ntwkOff.getGuestType()).thenReturn(Network.GuestType.L2);
        service.validateSharedNetworkRouterIPs(null, null, null, null, null, null, null, null, null, ntwkOff);
    }

    @Test
    public void validateSharedNetworkRouterIPs() {
        String startIP = "10.0.16.2";
        String endIP = "10.0.16.100";
        String routerIPv4 = "10.0.16.100";
        String routerPv6 = "fd17:ac56:1234:2000::fb";
        String startIPv6 = "fd17:ac56:1234:2000::1";
        String endIPv6 = "fd17:ac56:1234:2000::fc";
        NetworkOffering ntwkOff = Mockito.mock(NetworkOffering.class);
        Mockito.when(ntwkOff.getGuestType()).thenReturn(Network.GuestType.Shared);
        service.validateSharedNetworkRouterIPs(IP4_GATEWAY, startIP, endIP, IP4_NETMASK, routerIPv4, routerPv6, startIPv6, endIPv6, IP6_CIDR, ntwkOff);
    }

    @Test
    public void validateSharedNetworkWrongRouterIPv4() {
        String startIP = "10.0.16.2";
        String endIP = "10.0.16.100";
        String routerIPv4 = "10.0.16.101";
        String routerPv6 = "fd17:ac56:1234:2000::fb";
        String startIPv6 = "fd17:ac56:1234:2000::1";
        String endIPv6 = "fd17:ac56:1234:2000::fc";
        NetworkOffering ntwkOff = Mockito.mock(NetworkOffering.class);
        Mockito.when(ntwkOff.getGuestType()).thenReturn(Network.GuestType.Shared);
        boolean passing = false;
        try {
            service.validateSharedNetworkRouterIPs(IP4_GATEWAY, startIP, endIP, IP4_NETMASK, routerIPv4, routerPv6, startIPv6, endIPv6, IP6_CIDR, ntwkOff);
        } catch (CloudRuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("Router IPv4 IP provided is not within the specified range: "));
            passing = true;
        }
        Assert.assertTrue(passing);
    }

    @Test
    public void validateSharedNetworkNoEndOfIPv6Range() {
        String routerPv6 = "fd17:ac56:1234:2000::1";
        String startIPv6 = "fd17:ac56:1234:2000::1";
        NetworkOffering ntwkOff = Mockito.mock(NetworkOffering.class);
        Mockito.when(ntwkOff.getGuestType()).thenReturn(Network.GuestType.Shared);
        service.validateSharedNetworkRouterIPs(IP4_GATEWAY, null, null, IP4_NETMASK, null, routerPv6, startIPv6, null, IP6_CIDR, ntwkOff);
    }

    @Test
    public void validateSharedNetworkIPv6RouterNotInRange() {
        String routerIPv6 = "fd17:ac56:1234:2001::1";
        NetworkOffering ntwkOff = Mockito.mock(NetworkOffering.class);
        Mockito.when(ntwkOff.getGuestType()).thenReturn(Network.GuestType.Shared);
        boolean passing = true;
        try {
            service.validateSharedNetworkRouterIPs(IP4_GATEWAY, null, null, IP4_NETMASK, null, routerIPv6, null, null, IP6_CIDR, ntwkOff);
            passing = false;
        } catch (CloudRuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("Router IPv6 address provided is not with the network range"));
        }
        Assert.assertTrue(passing);
    }

    @Test
    public void invalidateSharedNetworkIPv6RouterAddress() {
        String routerIPv6 = "fd17:ac56:1234:2000::fg";
        NetworkOffering ntwkOff = Mockito.mock(NetworkOffering.class);
        Mockito.when(ntwkOff.getGuestType()).thenReturn(Network.GuestType.Shared);
        boolean passing = false;
        try {
            service.validateSharedNetworkRouterIPs(IP4_GATEWAY, null, null, IP4_NETMASK, null, routerIPv6, null, null, IP6_CIDR, ntwkOff);
        } catch (CloudRuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("Router IPv6 address provided is of incorrect format"));
            passing = true;
        }
        Assert.assertTrue(passing);
    }

    @Test
    public void invalidateSharedNetworkIPv4RouterAddress() {
        String routerIPv4 = "10.100.1000.1";
        NetworkOffering ntwkOff = Mockito.mock(NetworkOffering.class);
        Mockito.when(ntwkOff.getGuestType()).thenReturn(Network.GuestType.Shared);
        boolean passing = false;
        try {
            service.validateSharedNetworkRouterIPs(IP4_GATEWAY, null, null, IP4_NETMASK, routerIPv4, null, null, null, IP6_CIDR, ntwkOff);
        } catch (CloudRuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("Router IPv4 IP provided is of incorrect format"));
            passing = true;
        }
        Assert.assertTrue(passing);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void getAndValidateSupportForKeepMacAddressOnPublicNicParameterTestThrowExceptionWhenParamIsSpecifiedOnTiersCreation() {
        Mockito.when(networkOfferingVO.isForVpc()).thenReturn(true);

        service.getAndValidateSupportForKeepMacAddressOnPublicNicParameter(true, networkOfferingVO);
    }

    @Test
    public void getAndValidateSupportForKeepMacAddressOnPublicNicParameterTestReturnTrueByDefaultOnTiersCreation() {
        Mockito.when(networkOfferingVO.isForVpc()).thenReturn(true);

        Assert.assertTrue(service.getAndValidateSupportForKeepMacAddressOnPublicNicParameter(null, networkOfferingVO));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void getAndValidateSupportForKeepMacAddressOnPublicNicParameterTestThrowExceptionWhenParamIsSpecifiedOnNonIsolatedNetworksCreation() {
        Mockito.when(networkOfferingVO.getGuestType()).thenReturn(Network.GuestType.Shared);

        service.getAndValidateSupportForKeepMacAddressOnPublicNicParameter(true, networkOfferingVO);
    }

    @Test
    public void getAndValidateSupportForKeepMacAddressOnPublicNicParameterTestReturnTrueByDefaultOnNonIsolatedNetworksCreation() {
        Mockito.when(networkOfferingVO.getGuestType()).thenReturn(Network.GuestType.L2);

        Assert.assertTrue(service.getAndValidateSupportForKeepMacAddressOnPublicNicParameter(null, networkOfferingVO));
    }

    @Test
    public void getAndValidateSupportForKeepMacAddressOnPublicNicParameterTestReturnTrueByDefaultOnIsolatedNetworksCreation() {
        Mockito.when(networkOfferingVO.getGuestType()).thenReturn(Network.GuestType.Isolated);

        Assert.assertTrue(service.getAndValidateSupportForKeepMacAddressOnPublicNicParameter(null, networkOfferingVO));
    }

    @Test
    public void getAndValidateSupportForKeepMacAddressOnPublicNicParameterTestReturnSpecifiedValueOnIsolatedNetworksCreation() {
        Mockito.when(networkOfferingVO.getGuestType()).thenReturn(Network.GuestType.Isolated);

        Assert.assertFalse(service.getAndValidateSupportForKeepMacAddressOnPublicNicParameter(false, networkOfferingVO));
    }

    @Test
    public void isNonVpcNetworkSupportingDynamicRoutingReturnsTrueForNonVpcDynamicOffering() {
        NetworkOffering offering = Mockito.mock(NetworkOffering.class);
        Mockito.when(offering.isForVpc()).thenReturn(false);
        Mockito.when(offering.getRoutingMode()).thenReturn(NetworkOffering.RoutingMode.Dynamic);

        Assert.assertTrue(service.isNonVpcNetworkSupportingDynamicRouting(offering));
    }

    @Test
    public void validateNetworkCreationSupportedThrowsForL2NetworkInNsxZone() {
        NsxProviderVO nsxProviderVO = Mockito.mock(NsxProviderVO.class);
        Mockito.when(nsxProviderDao.findByZoneId(10L)).thenReturn(nsxProviderVO);

        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class,
                () -> service.validateNetworkCreationSupported(10L, "zone-a", Network.GuestType.L2));

        Assert.assertTrue(exception.getMessage().contains("Creation of L2 networks is not supported in NSX enabled zone zone-a"));
    }

    @Test
    public void validateNetworkOfferingForNonRootAdminUserAllowsSharedOfferingWithoutSpecifyVlan() {
        NetworkOffering offering = Mockito.mock(NetworkOffering.class);
        Mockito.when(offering.getTrafficType()).thenReturn(TrafficType.Guest);
        Mockito.when(offering.getGuestType()).thenReturn(Network.GuestType.Shared);
        Mockito.when(offering.isSpecifyVlan()).thenReturn(false);

        service.validateNetworkOfferingForNonRootAdminUser(offering);
    }

    @Test
    public void checkSharedNetworkCidrOverlapReturnsWithoutDaoInteractionWhenZoneIdIsNull() {
        service.checkSharedNetworkCidrOverlap(null, 1L, "10.1.1.0/24");

        Mockito.verifyNoInteractions(_dcDao, _networksDao, _physicalNetworkDao);
    }

    @Test
    public void checkSharedNetworkCidrOverlapReturnsWithoutDaoInteractionWhenCidrIsNull() {
        service.checkSharedNetworkCidrOverlap(1L, 1L, null);

        Mockito.verifyNoInteractions(_dcDao, _networksDao, _physicalNetworkDao);
    }

    @Test
    public void checkSharedNetworkCidrOverlapThrowsForOverlappingSharedNetworkCidr() {
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        PhysicalNetworkVO physicalNetwork = Mockito.mock(PhysicalNetworkVO.class);
        NetworkVO sharedNetwork = Mockito.mock(NetworkVO.class);
        Mockito.when(_dcDao.findById(1L)).thenReturn(zone);
        Mockito.when(_physicalNetworkDao.findById(2L)).thenReturn(physicalNetwork);
        Mockito.when(physicalNetwork.getVnet()).thenReturn(null);
        Mockito.when(_networksDao.listByZone(1L)).thenReturn(Collections.singletonList(sharedNetwork));
        Mockito.when(sharedNetwork.getGuestType()).thenReturn(GuestType.Shared);
        Mockito.when(sharedNetwork.getCidr()).thenReturn("10.1.1.0/24");
        Mockito.when(sharedNetwork.getId()).thenReturn(3L);

        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class,
                () -> service.checkSharedNetworkCidrOverlap(1L, 2L, "10.1.1.128/25"));

        Assert.assertEquals("Specified CIDR for shared network conflict with CIDR of a shared network in the zone.", exception.getMessage());
    }

    @Test
    public void checkSharedNetworkCidrOverlapIgnoresIsolatedNetworkCidrsInZone() {
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        PhysicalNetworkVO physicalNetwork = Mockito.mock(PhysicalNetworkVO.class);
        NetworkVO isolatedNetwork = Mockito.mock(NetworkVO.class);
        Mockito.when(_dcDao.findById(1L)).thenReturn(zone);
        Mockito.when(_physicalNetworkDao.findById(2L)).thenReturn(physicalNetwork);
        Mockito.when(physicalNetwork.getVnet()).thenReturn(null);
        Mockito.when(_networksDao.listByZone(1L)).thenReturn(Arrays.asList(isolatedNetwork));
        Mockito.when(isolatedNetwork.getGuestType()).thenReturn(GuestType.Isolated);

        service.checkSharedNetworkCidrOverlap(1L, 2L, "10.1.1.128/25");
    }
}
