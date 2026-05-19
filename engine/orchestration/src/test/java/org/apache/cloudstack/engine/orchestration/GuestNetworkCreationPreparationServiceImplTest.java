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
package org.apache.cloudstack.engine.orchestration;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.Vlan;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.Domain;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.PVlanType;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.NetworkOffering.Availability;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.user.Account;
import com.cloud.utils.Pair;
import com.cloud.utils.db.EntityManager;

public class GuestNetworkCreationPreparationServiceImplTest {

    private static final long OFFERING_ID = 10L;
    private static final long ZONE_ID = 20L;
    private static final long PHYSICAL_NETWORK_ID = 30L;
    private static final long ACCOUNT_ID = 40L;

    private GuestNetworkCreationPreparationServiceImpl service;
    private NetworkOfferingDao networkOfferingDao;
    private DataCenterDao dcDao;
    private NetworkDao networksDao;
    private NetworkModel networkModel;
    private EntityManager entityManager;
    private NetworkOfferingVlanValidationService vlanValidationService;
    private NetworkOfferingVO offering;
    private DataCenterVO zone;
    private PhysicalNetworkVO physicalNetwork;
    private Account owner;

    @Before
    public void setUp() {
        networkOfferingDao = mock(NetworkOfferingDao.class);
        dcDao = mock(DataCenterDao.class);
        networksDao = mock(NetworkDao.class);
        networkModel = mock(NetworkModel.class);
        entityManager = mock(EntityManager.class);
        vlanValidationService = mock(NetworkOfferingVlanValidationService.class);

        service = new GuestNetworkCreationPreparationServiceImpl();
        service.networkOfferingDao = networkOfferingDao;
        service.dcDao = dcDao;
        service.networksDao = networksDao;
        service.networkModel = networkModel;
        service.entityMgr = entityManager;
        service.networkOfferingVlanValidationService = vlanValidationService;

        offering = newOffering(GuestType.Shared, true);
        zone = new DataCenterVO(ZONE_ID, "zone", "zone", "8.8.8.8", "8.8.4.4", "10.0.0.1", "10.0.0.2", "10.1.0.0/16", null, null,
                NetworkType.Advanced, "token", "example.com");
        physicalNetwork = new PhysicalNetworkVO(PHYSICAL_NETWORK_ID, ZONE_ID, null, null, null, null, "physical-network");
        physicalNetwork.setState(com.cloud.network.PhysicalNetwork.State.Enabled);
        physicalNetwork.setIsolationMethods(Collections.singletonList("vlan"));
        owner = mock(Account.class);
        when(owner.getId()).thenReturn(ACCOUNT_ID);
        when(owner.getAccountId()).thenReturn(ACCOUNT_ID);

        when(networkOfferingDao.findById(OFFERING_ID)).thenReturn(offering);
        when(dcDao.findById(ZONE_ID)).thenReturn(zone);
        when(networksDao.listByZoneAndTrafficType(ZONE_ID, TrafficType.Guest)).thenReturn(Collections.emptyList());
        when(networksDao.listByPhysicalNetworkPvlan(anyLong(), anyString())).thenReturn(Collections.emptyList());
        when(networksDao.listByPhysicalNetworkPvlan(anyLong(), anyString(), any(PVlanType.class))).thenReturn(Collections.emptyList());
        when(entityManager.findById(NetworkOffering.class, OFFERING_ID)).thenReturn(offering);
        when(vlanValidationService.encodeVlanIdIntoBroadcastUri(anyString(), eq(physicalNetwork))).thenReturn(URI.create("vlan://123"));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsDisabledOffering() {
        offering.setState(NetworkOffering.State.Disabled);

        prepare();
    }

    @Test
    public void returnsNullForNonGuestOffering() {
        NetworkOfferingVO managementOffering = new NetworkOfferingVO("management", "management", TrafficType.Management, false, false, 0, 0, false,
                Availability.Optional, null, GuestType.Shared, true, false, false, false, false, false);
        managementOffering.setState(NetworkOffering.State.Enabled);
        when(networkOfferingDao.findById(OFFERING_ID)).thenReturn(managementOffering);

        Assert.assertNull(prepare());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsDisabledPhysicalNetwork() {
        physicalNetwork.setState(com.cloud.network.PhysicalNetwork.State.Disabled);

        prepare();
    }

    @Test
    public void normalizesBasicZoneDefaults() {
        zone.setNetworkType(NetworkType.Basic);
        offering = newOffering(GuestType.Shared, false);
        when(networkOfferingDao.findById(OFFERING_ID)).thenReturn(offering);
        when(networkModel.areServicesSupportedByNetworkOffering(OFFERING_ID, Service.SourceNat)).thenReturn(false);
        when(vlanValidationService.encodeVlanIdIntoBroadcastUri(eq(Vlan.UNTAGGED), eq(physicalNetwork))).thenReturn(URI.create("vlan://untagged"));

        GuestNetworkCreationPreparation preparation = prepare(null, null, ACLType.Domain, null, null, null, null, null, null, null, null, null, null, null, null, null,
                false);

        Assert.assertTrue(preparation.getSubdomainAccess());
        Assert.assertEquals(BroadcastDomainType.Native, preparation.getPredefinedNetwork().getBroadcastDomainType());
        verify(vlanValidationService).validateGuestNetworkOfferingVlan(eq(Vlan.UNTAGGED), eq(null), anyBoolean(), eq(offering), eq(physicalNetwork), eq(zone), eq(ZONE_ID),
                eq(owner), eq(false));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsBasicZoneNonDomainAcl() {
        zone.setNetworkType(NetworkType.Basic);
        prepare(null, null, ACLType.Account, null, null, null, null, null, null, null, null, null, null, null, null, null, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsBasicZoneWithExistingGuestNetwork() {
        zone.setNetworkType(NetworkType.Basic);
        when(networksDao.listByZoneAndTrafficType(ZONE_ID, TrafficType.Guest)).thenReturn(Collections.singletonList(new NetworkVO()));

        prepare(null, null, ACLType.Domain, null, null, null, null, null, null, null, null, null, null, null, null, null, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsBasicZoneNonRootDomain() {
        zone.setNetworkType(NetworkType.Basic);
        prepare(null, 2L, ACLType.Domain, null, null, null, null, null, null, null, null, null, null, null, null, null, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsBasicZoneTaggedVlan() {
        zone.setNetworkType(NetworkType.Basic);
        prepare("123", null, ACLType.Domain, null, null, null, null, null, null, null, null, null, null, null, null, null, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsBasicZoneCidr() {
        zone.setNetworkType(NetworkType.Basic);
        prepare(null, null, ACLType.Domain, null, "10.0.0.0/24", null, null, null, null, null, null, null, null, null, null, null, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsAdvancedSecurityGroupIsolatedPvlan() {
        zone.setSecurityGroupEnabled(true);
        prepare("123", null, ACLType.Account, null, null, "456", null, null, null, null, null, null, null, null, null, null, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsAdvancedSecurityGroupNonSharedOrL2Offering() {
        zone.setSecurityGroupEnabled(true);
        offering = newOffering(GuestType.Isolated, true);
        when(networkOfferingDao.findById(OFFERING_ID)).thenReturn(offering);

        prepare();
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsAdvancedSecurityGroupSourceNat() {
        zone.setSecurityGroupEnabled(true);
        when(networkModel.areServicesSupportedByNetworkOffering(anyLong(), eq(Service.SourceNat))).thenReturn(true);

        prepare();
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsCustomNetworkDomainWhenSuffixModificationUnsupported() {
        when(networkModel.areServicesSupportedByNetworkOffering(OFFERING_ID, Service.Dns)).thenReturn(true);
        when(networkModel.getNetworkOfferingServiceCapabilities(offering, Service.Dns)).thenReturn(Collections.emptyMap());

        prepare("123", null, ACLType.Account, "custom.example.com", null, null, null, null, null, null, null, null, null, null, null, null, false);
    }

    @Test
    public void generatesDefaultNetworkDomainWhenSuffixModificationSupported() {
        when(networkModel.areServicesSupportedByNetworkOffering(OFFERING_ID, Service.Dns)).thenReturn(true);
        when(networkModel.getNetworkOfferingServiceCapabilities(offering, Service.Dns)).thenReturn(dnsCapabilities(true));
        when(networkModel.getAccountNetworkDomain(ACCOUNT_ID, ZONE_ID)).thenReturn(null);

        GuestNetworkCreationPreparation preparation = prepare("123", null, ACLType.Account, null, "10.0.0.0/24", null, null, "10.0.0.1", null, null, null, null,
                null, null, null, null, false);

        Assert.assertEquals("cs28cloud.internal", preparation.getNetworkDomain());
        Assert.assertEquals("cs28cloud.internal", preparation.getPredefinedNetwork().getNetworkDomain());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsInvalidCustomNetworkDomain() {
        when(networkModel.areServicesSupportedByNetworkOffering(OFFERING_ID, Service.Dns)).thenReturn(true);
        when(networkModel.getNetworkOfferingServiceCapabilities(offering, Service.Dns)).thenReturn(dnsCapabilities(true));

        prepare("123", null, ACLType.Account, "-bad.example.com", null, null, null, null, null, null, null, null, null, null, null, null, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void requiresCidrForAdvancedSharedNetwork() {
        prepare("123", null, ACLType.Account, null, null, null, null, null, null, null, null, null, null, null, null, null, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void requiresCidrForAdvancedIsolatedWithoutSourceNatOrGateway() {
        offering = newOffering(GuestType.Isolated, true);
        when(networkOfferingDao.findById(OFFERING_ID)).thenReturn(offering);
        when(networkModel.areServicesSupportedByNetworkOffering(anyLong(), eq(Service.SourceNat))).thenReturn(false);
        when(networkModel.areServicesSupportedByNetworkOffering(anyLong(), eq(Service.Gateway))).thenReturn(false);

        prepare("123", null, ACLType.Account, null, null, null, null, null, null, null, null, null, null, null, null, null, false);
    }

    @Test
    public void buildsPredefinedNetworkWithAddressingDnsMtuBroadcastAndRouterFields() {
        when(vlanValidationService.encodeVlanIdIntoBroadcastUri(eq("123"), eq(physicalNetwork))).thenReturn(URI.create("vlan://123"));

        GuestNetworkCreationPreparation preparation = prepare("123", null, ACLType.Account, "custom.example.com", "10.0.0.0/24", null, null, "10.0.0.1", "2001:db8::1",
                "2001:db8::/64", "ext-1", "169.254.1.1", "fe80::1", "1.1.1.1", "1.0.0.1", "2606:4700:4700::1111", "2606:4700:4700::1001", false);

        NetworkVO network = preparation.getPredefinedNetwork();
        Assert.assertEquals("10.0.0.0/24", network.getCidr());
        Assert.assertEquals("10.0.0.1", network.getGateway());
        Assert.assertEquals("2001:db8::/64", network.getIp6Cidr());
        Assert.assertEquals("2001:db8::1", network.getIp6Gateway());
        Assert.assertEquals("ext-1", network.getExternalId());
        Assert.assertEquals("169.254.1.1", network.getRouterIp());
        Assert.assertEquals("fe80::1", network.getRouterIpv6());
        Assert.assertEquals("1.1.1.1", network.getDns1());
        Assert.assertEquals("1.0.0.1", network.getDns2());
        Assert.assertEquals("2606:4700:4700::1111", network.getIp6Dns1());
        Assert.assertEquals("2606:4700:4700::1001", network.getIp6Dns2());
        Assert.assertEquals(Integer.valueOf(1400), network.getPublicMtu());
        Assert.assertEquals(Integer.valueOf(NetworkService.VRPrivateInterfaceMtu.defaultValue()), network.getPrivateMtu());
        Assert.assertEquals(URI.create("vlan://123"), network.getBroadcastUri());
        Assert.assertEquals(BroadcastDomainType.Vlan, network.getBroadcastDomainType());
        Assert.assertEquals(Integer.valueOf(24), network.getNetworkCidrSize());
        Assert.assertFalse(network.getKeepMacAddressOnPublicNic());
        Assert.assertEquals(PHYSICAL_NETWORK_ID, preparation.getPlan().getPhysicalNetworkId().longValue());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void rejectsDuplicatePvlan() {
        when(networksDao.listByPhysicalNetworkPvlan(PHYSICAL_NETWORK_ID, "pvlan://123-i456", PVlanType.Isolated)).thenReturn(Collections.singletonList(new NetworkVO()));

        prepare("123", null, ACLType.Account, null, "10.0.0.0/24", "456", PVlanType.Isolated, "10.0.0.1", null, null, null, null, null, null, null, null, false);
    }

    private GuestNetworkCreationPreparation prepare() {
        return prepare("123", null, ACLType.Account, null, "10.0.0.0/24", null, null, "10.0.0.1", null, null, null, null, null, null, null, null, true);
    }

    private GuestNetworkCreationPreparation prepare(String vlanId, Long domainId, ACLType aclType, String networkDomain, String cidr, String isolatedPvlan,
            PVlanType isolatedPvlanType, String gateway, String ip6Gateway, String ip6Cidr, String externalId, String routerIp, String routerIpv6, String ip4Dns1,
            String ip4Dns2, String ip6Dns1, boolean keepMacAddressOnPublicNic) {
        return prepare(vlanId, domainId, aclType, networkDomain, cidr, isolatedPvlan, isolatedPvlanType, gateway, ip6Gateway, ip6Cidr, externalId, routerIp, routerIpv6,
                ip4Dns1, ip4Dns2, ip6Dns1, null, keepMacAddressOnPublicNic);
    }

    private GuestNetworkCreationPreparation prepare(String vlanId, Long domainId, ACLType aclType, String networkDomain, String cidr, String isolatedPvlan,
            PVlanType isolatedPvlanType, String gateway, String ip6Gateway, String ip6Cidr, String externalId, String routerIp, String routerIpv6, String ip4Dns1,
            String ip4Dns2, String ip6Dns1, String ip6Dns2, boolean keepMacAddressOnPublicNic) {
        return service.prepareGuestNetworkCreation(OFFERING_ID, gateway, cidr, vlanId, false, networkDomain, owner, domainId == null ? Domain.ROOT_DOMAIN : domainId,
                physicalNetwork, ZONE_ID, aclType, null, ip6Gateway, ip6Cidr, isolatedPvlan, isolatedPvlanType, externalId, false, routerIp, routerIpv6, ip4Dns1, ip4Dns2,
                ip6Dns1, ip6Dns2, new Pair<>(1400, 0), 24, keepMacAddressOnPublicNic);
    }

    private NetworkOfferingVO newOffering(GuestType guestType, boolean specifyVlan) {
        NetworkOfferingVO result = new NetworkOfferingVO("offering", "offering", TrafficType.Guest, false, specifyVlan, 0, 0, false, Availability.Optional, null,
                guestType, true, false, false, false, false, false);
        result.setState(NetworkOffering.State.Enabled);
        return result;
    }

    private Map<Capability, String> dnsCapabilities(boolean allowSuffixModification) {
        Map<Capability, String> capabilities = new HashMap<>();
        capabilities.put(Capability.AllowDnsSuffixModification, Boolean.toString(allowSuffixModification));
        return capabilities;
    }
}
