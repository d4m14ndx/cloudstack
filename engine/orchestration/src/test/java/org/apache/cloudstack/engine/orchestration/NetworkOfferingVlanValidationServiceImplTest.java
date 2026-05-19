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

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.DataCenterVnetVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterVnetDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.AccountGuestVlanMapDao;
import com.cloud.network.dao.AccountGuestVlanMapVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.user.Account;
import com.cloud.utils.exception.CloudRuntimeException;

public class NetworkOfferingVlanValidationServiceImplTest {

    private static final long ZONE_ID = 11L;
    private static final long PHYSICAL_NETWORK_ID = 22L;
    private static final long OWNER_ID = 33L;
    private static final String VLAN_ID = "123";

    private NetworkOfferingVlanValidationServiceImpl service;
    private DataCenterDao dataCenterDao;
    private NetworkDao networksDao;
    private DataCenterVnetDao dataCenterVnetDao;
    private AccountGuestVlanMapDao accountGuestVlanMapDao;
    private NetworkOfferingDao networkOfferingDao;
    private NetworkModel networkModel;
    private NetworkOfferingVO offering;
    private PhysicalNetworkVO physicalNetwork;
    private DataCenterVO zone;
    private Account owner;

    @Before
    public void setUp() {
        dataCenterDao = mock(DataCenterDao.class);
        networksDao = mock(NetworkDao.class);
        dataCenterVnetDao = mock(DataCenterVnetDao.class);
        accountGuestVlanMapDao = mock(AccountGuestVlanMapDao.class);
        networkOfferingDao = mock(NetworkOfferingDao.class);
        networkModel = mock(NetworkModel.class);

        service = new NetworkOfferingVlanValidationServiceImpl();
        service.dataCenterDao = dataCenterDao;
        service.networksDao = networksDao;
        service.dataCenterVnetDao = dataCenterVnetDao;
        service.accountGuestVlanMapDao = accountGuestVlanMapDao;
        service.networkOfferingDao = networkOfferingDao;
        service.networkModel = networkModel;

        offering = mock(NetworkOfferingVO.class);
        when(offering.getTrafficType()).thenReturn(TrafficType.Guest);
        when(offering.getGuestType()).thenReturn(GuestType.Isolated);
        when(offering.isSpecifyVlan()).thenReturn(true);
        when(offering.getId()).thenReturn(44L);

        NetworkOfferingVO privateGatewayOffering = mock(NetworkOfferingVO.class);
        when(privateGatewayOffering.getId()).thenReturn(99L);
        when(networkOfferingDao.findByUniqueName(NetworkOffering.SystemPrivateGatewayNetworkOfferingWithoutVlan)).thenReturn(privateGatewayOffering);

        physicalNetwork = new PhysicalNetworkVO(PHYSICAL_NETWORK_ID, ZONE_ID, null, null, null, null, "physical-network");
        physicalNetwork.setIsolationMethods(new ArrayList<>(Collections.singletonList("vlan")));

        zone = mock(DataCenterVO.class);
        when(zone.getName()).thenReturn("zone1");

        owner = mock(Account.class);
        when(owner.getAccountId()).thenReturn(OWNER_ID);
        when(owner.getAccountName()).thenReturn("owner");

        when(dataCenterDao.findVnet(anyLong(), anyLong(), anyString())).thenReturn(Collections.emptyList());
        when(networksDao.listByZoneAndUriAndGuestType(anyLong(), anyString(), org.mockito.ArgumentMatchers.isNull())).thenReturn(Collections.emptyList());
        when(networksDao.listByZoneAndUriAndGuestType(anyLong(), anyString(), org.mockito.ArgumentMatchers.eq(GuestType.Isolated))).thenReturn(Collections.emptyList());
        when(dataCenterVnetDao.findVnet(anyLong(), anyString())).thenReturn(Collections.emptyList());
        when(accountGuestVlanMapDao.listAccountGuestVlanMapsByAccount(anyLong())).thenReturn(Collections.emptyList());
    }

    @Test
    public void isSharedNetworkWithoutSpecifyVlanRequiresGuestSharedAndNoSpecifiedVlan() {
        Assert.assertFalse(service.isSharedNetworkWithoutSpecifyVlan(null));

        NetworkOfferingVO nonGuest = mock(NetworkOfferingVO.class);
        when(nonGuest.getTrafficType()).thenReturn(TrafficType.Management);
        Assert.assertFalse(service.isSharedNetworkWithoutSpecifyVlan(nonGuest));

        when(offering.getGuestType()).thenReturn(GuestType.Isolated);
        when(offering.isSpecifyVlan()).thenReturn(false);
        Assert.assertFalse(service.isSharedNetworkWithoutSpecifyVlan(offering));

        when(offering.getGuestType()).thenReturn(GuestType.Shared);
        Assert.assertTrue(service.isSharedNetworkWithoutSpecifyVlan(offering));
    }

    @Test
    public void encodeVlanIdIntoBroadcastUriUsesVxlanIsolationMethod() {
        physicalNetwork.setIsolationMethods(new ArrayList<>(Collections.singletonList("VXLAN")));

        URI uri = service.encodeVlanIdIntoBroadcastUri(VLAN_ID, physicalNetwork);

        Assert.assertEquals("vxlan", uri.getScheme());
        Assert.assertEquals("vxlan://123", uri.toString());
    }

    @Test
    public void encodeVlanIdIntoBroadcastUriFallsBackToVlanUri() {
        URI uri = service.encodeVlanIdIntoBroadcastUri(VLAN_ID, physicalNetwork);

        Assert.assertEquals("vlan", uri.getScheme());
        Assert.assertEquals("vlan://123", uri.toString());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void encodeVlanIdIntoBroadcastUriRejectsNullPhysicalNetwork() {
        service.encodeVlanIdIntoBroadcastUri(VLAN_ID, null);
    }

    @Test(expected = CloudRuntimeException.class)
    public void encodeVlanIdIntoBroadcastUriPreservesBlankVlanFailure() {
        service.encodeVlanIdIntoBroadcastUri(" ", physicalNetwork);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateRejectsSpecifiedVlanWhenOfferingDoesNotAllowIt() {
        when(offering.isSpecifyVlan()).thenReturn(false);

        validate(VLAN_ID, null, false, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateRejectsMissingVlanWhenOfferingRequiresIt() {
        validate(null, null, false, false);
    }

    @Test
    public void validateAllowsSharedOfferingWithoutSpecifyVlanAndSkipsDynamicAllocationConflict() {
        when(offering.isSpecifyVlan()).thenReturn(false);
        when(offering.getGuestType()).thenReturn(GuestType.Shared);
        when(dataCenterDao.findVnet(ZONE_ID, PHYSICAL_NETWORK_ID, "123")).thenReturn(Collections.singletonList(new DataCenterVnetVO("123", ZONE_ID, PHYSICAL_NETWORK_ID)));

        validate(VLAN_ID, null, false, false);

        verify(networksDao, never()).listByZoneAndUriAndGuestType(ZONE_ID, "vlan://123", null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateRejectsPrimaryDynamicAllocationConflictForNonBypassOffering() {
        when(dataCenterDao.findVnet(ZONE_ID, PHYSICAL_NETWORK_ID, "123")).thenReturn(Collections.singletonList(new DataCenterVnetVO("123", ZONE_ID, PHYSICAL_NETWORK_ID)));

        validate(VLAN_ID, null, false, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateRejectsSecondaryPvlanDynamicAllocationConflict() {
        when(dataCenterDao.findVnet(ZONE_ID, PHYSICAL_NETWORK_ID, "456")).thenReturn(Collections.singletonList(new DataCenterVnetVO("456", ZONE_ID, PHYSICAL_NETWORK_ID)));

        validate(VLAN_ID, "456", false, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateRejectsExistingPrimaryNetworkUriOverlap() {
        when(networksDao.listByZoneAndUriAndGuestType(ZONE_ID, "vlan://123", null)).thenReturn(Collections.singletonList(mock(NetworkVO.class)));

        validate(VLAN_ID, null, false, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateRejectsExistingSecondaryNetworkUriOverlap() {
        when(networksDao.listByZoneAndUriAndGuestType(ZONE_ID, "vlan://456", null)).thenReturn(Collections.singletonList(mock(NetworkVO.class)));

        validate(VLAN_ID, "456", false, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateRejectsDedicatedVlanMappedToDifferentAccount() {
        DataCenterVnetVO vnet = new DataCenterVnetVO("123", ZONE_ID, PHYSICAL_NETWORK_ID);
        vnet.setAccountGuestVlanMapId(55L);
        AccountGuestVlanMapVO map = mock(AccountGuestVlanMapVO.class);
        when(map.getAccountId()).thenReturn(OWNER_ID + 1);
        when(dataCenterVnetDao.findVnet(ZONE_ID, "123")).thenReturn(Collections.singletonList(vnet));
        when(accountGuestVlanMapDao.findById(55L)).thenReturn(map);

        validate(VLAN_ID, null, false, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateRejectsSystemPoolVlanWhenOwnerStillHasUnusedDedicatedRange() {
        when(dataCenterVnetDao.findVnet(ZONE_ID, "123")).thenReturn(Collections.singletonList(new DataCenterVnetVO("123", ZONE_ID, PHYSICAL_NETWORK_ID)));
        when(accountGuestVlanMapDao.listAccountGuestVlanMapsByAccount(OWNER_ID)).thenReturn(Collections.singletonList(new AccountGuestVlanMapVO(OWNER_ID, PHYSICAL_NETWORK_ID)));
        when(dataCenterVnetDao.countVnetsAllocatedToAccount(ZONE_ID, OWNER_ID)).thenReturn(0);
        when(dataCenterVnetDao.countVnetsDedicatedToAccount(ZONE_ID, OWNER_ID)).thenReturn(1);

        validate(VLAN_ID, null, false, false);
    }

    @Test
    public void validateAllowsUuidVlanWithoutExistingNetworkOrDedicatedRangeChecks() {
        String uuidVlan = "7ee3f1f0-b527-4b8b-85bb-232fc7601b6d";
        physicalNetwork.setIsolationMethods(new ArrayList<>(Collections.singletonList("vxlan")));

        validate(uuidVlan, null, false, false);

        verify(networksDao, never()).listByZoneAndUriAndGuestType(anyLong(), anyString(), org.mockito.ArgumentMatchers.isNull());
        verify(dataCenterVnetDao, never()).findVnet(anyLong(), anyString());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void checkL2OfferingServicesRejectsMultipleServices() {
        when(offering.getGuestType()).thenReturn(GuestType.L2);
        when(networkModel.listNetworkOfferingServices(offering.getId())).thenReturn(Arrays.asList(Service.UserData, Service.Dhcp));
        when(networkModel.areServicesSupportedByNetworkOffering(offering.getId(), Service.UserData)).thenReturn(true);

        service.checkL2OfferingServices(offering);
    }

    @Test
    public void checkL2OfferingServicesAllowsUserDataOnly() {
        when(offering.getGuestType()).thenReturn(GuestType.L2);
        when(networkModel.listNetworkOfferingServices(offering.getId())).thenReturn(Collections.singletonList(Service.UserData));
        when(networkModel.areServicesSupportedByNetworkOffering(offering.getId(), Service.UserData)).thenReturn(true);

        service.checkL2OfferingServices(offering);
    }

    private void validate(String vlanId, String isolatedPvlan, boolean bypassVlanOverlapCheck, boolean isPrivateNetwork) {
        service.validateGuestNetworkOfferingVlan(
                vlanId,
                isolatedPvlan,
                bypassVlanOverlapCheck,
                offering,
                physicalNetwork,
                zone,
                ZONE_ID,
                owner,
                isPrivateNetwork);
    }
}
