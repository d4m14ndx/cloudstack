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

import static com.cloud.configuration.ConfigurationManager.MESSAGE_DELETE_VLAN_IP_RANGE_EVENT;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.PublishScope;
import org.junit.Before;
import org.junit.Test;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpc.dao.PrivateIpDao;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.user.Account;
import com.cloud.utils.Pair;

public class NetworkVlanRangeCleanupServiceImplTest {

    private static final long NETWORK_ID = 101L;
    private static final long USER_ID = 202L;
    private static final long OFFERING_ID = 303L;
    private static final long ZONE_ID = 404L;
    private static final long PHYSICAL_NETWORK_ID = 505L;
    private static final long ACCOUNT_ID = 606L;
    private static final String RESERVATION_ID = "reservation-1";
    private static final String SENDER_ADDRESS = "NetworkManager";

    private NetworkVlanRangeCleanupServiceImpl service;
    private VlanDao vlanDao;
    private ConfigurationManager configurationManager;
    private PrivateIpDao privateIpDao;
    private NetworkOfferingDao networkOfferingDao;
    private DataCenterDao dataCenterDao;
    private NetworkOfferingVlanValidationService networkOfferingVlanValidationService;
    private MessageBus messageBus;
    private NetworkVO network;
    private Account caller;
    private NetworkOfferingVO offering;

    @Before
    public void setUp() {
        service = new NetworkVlanRangeCleanupServiceImpl();
        vlanDao = mock(VlanDao.class);
        configurationManager = mock(ConfigurationManager.class);
        privateIpDao = mock(PrivateIpDao.class);
        networkOfferingDao = mock(NetworkOfferingDao.class);
        dataCenterDao = mock(DataCenterDao.class);
        networkOfferingVlanValidationService = mock(NetworkOfferingVlanValidationService.class);
        messageBus = mock(MessageBus.class);
        network = mock(NetworkVO.class);
        caller = mock(Account.class);
        offering = mock(NetworkOfferingVO.class);

        service.vlanDao = vlanDao;
        service.configurationManager = configurationManager;
        service.privateIpDao = privateIpDao;
        service.networkOfferingDao = networkOfferingDao;
        service.dataCenterDao = dataCenterDao;
        service.networkOfferingVlanValidationService = networkOfferingVlanValidationService;
        service.messageBus = messageBus;

        when(network.getId()).thenReturn(NETWORK_ID);
        when(network.getNetworkOfferingId()).thenReturn(OFFERING_ID);
        when(network.getBroadcastUri()).thenReturn(URI.create("vlan://321"));
        when(network.getDataCenterId()).thenReturn(ZONE_ID);
        when(network.getPhysicalNetworkId()).thenReturn(PHYSICAL_NETWORK_ID);
        when(network.getAccountId()).thenReturn(ACCOUNT_ID);
        when(network.getReservationId()).thenReturn(RESERVATION_ID);
        when(networkOfferingDao.findById(OFFERING_ID)).thenReturn(offering);
        when(vlanDao.listVlansByNetworkId(NETWORK_ID)).thenReturn(Collections.emptyList());
        when(privateIpDao.countAllocatedByNetworkId(NETWORK_ID)).thenReturn(0);
    }

    @Test
    public void deleteVlansInNetworkDeletesPublicVlansAndPrivateRange() {
        VlanVO firstSource = vlan(1L, "source-1");
        VlanVO secondSource = vlan(2L, "source-2");
        VlanVO firstDeleted = vlan(11L, "deleted-1");
        VlanVO secondDeleted = vlan(12L, "deleted-2");
        when(vlanDao.listVlansByNetworkId(NETWORK_ID)).thenReturn(Arrays.asList(firstSource, secondSource));
        when(configurationManager.deleteVlanAndPublicIpRange(USER_ID, firstSource.getId(), caller)).thenReturn(firstDeleted);
        when(configurationManager.deleteVlanAndPublicIpRange(USER_ID, secondSource.getId(), caller)).thenReturn(secondDeleted);

        Pair<Boolean, List<VlanVO>> result = service.deleteVlansInNetwork(network, USER_ID, caller);

        assertTrue(result.first());
        assertEquals(Arrays.asList(firstDeleted, secondDeleted), result.second());
        verify(privateIpDao).deleteByNetworkId(NETWORK_ID);
    }

    @Test
    public void deleteVlansInNetworkReturnsFalseWhenPublicVlanDeleteFails() {
        VlanVO firstSource = vlan(1L, "source-1");
        VlanVO secondSource = vlan(2L, "source-2");
        VlanVO secondDeleted = vlan(12L, "deleted-2");
        when(vlanDao.listVlansByNetworkId(NETWORK_ID)).thenReturn(Arrays.asList(firstSource, secondSource));
        when(configurationManager.deleteVlanAndPublicIpRange(USER_ID, firstSource.getId(), caller)).thenReturn(null);
        when(configurationManager.deleteVlanAndPublicIpRange(USER_ID, secondSource.getId(), caller)).thenReturn(secondDeleted);

        Pair<Boolean, List<VlanVO>> result = service.deleteVlansInNetwork(network, USER_ID, caller);

        assertFalse(result.first());
        assertEquals(Collections.singletonList(secondDeleted), result.second());
        verify(configurationManager).deleteVlanAndPublicIpRange(USER_ID, firstSource.getId(), caller);
        verify(configurationManager).deleteVlanAndPublicIpRange(USER_ID, secondSource.getId(), caller);
    }

    @Test
    public void deleteVlansInNetworkDoesNotDeletePrivateRangeWhenAllocatedPrivateIpsExist() {
        when(privateIpDao.countAllocatedByNetworkId(NETWORK_ID)).thenReturn(1);

        Pair<Boolean, List<VlanVO>> result = service.deleteVlansInNetwork(network, USER_ID, caller);

        assertFalse(result.first());
        verify(privateIpDao, never()).deleteByNetworkId(NETWORK_ID);
    }

    @Test
    public void deleteVlansInNetworkDeletesPrivateRangeWhenNoPrivateIpsAreAllocated() {
        Pair<Boolean, List<VlanVO>> result = service.deleteVlansInNetwork(network, USER_ID, caller);

        assertTrue(result.first());
        verify(privateIpDao).deleteByNetworkId(NETWORK_ID);
    }

    @Test
    public void deleteVlansInNetworkReleasesVnetForSharedNetworkWithoutSpecifyVlan() {
        when(networkOfferingVlanValidationService.isSharedNetworkWithoutSpecifyVlan(offering)).thenReturn(true);

        service.deleteVlansInNetwork(network, USER_ID, caller);

        verify(dataCenterDao).releaseVnet("321", ZONE_ID, PHYSICAL_NETWORK_ID, ACCOUNT_ID, RESERVATION_ID);
    }

    @Test
    public void deleteVlansInNetworkDoesNotReleaseVnetForOtherOfferings() {
        when(networkOfferingVlanValidationService.isSharedNetworkWithoutSpecifyVlan(offering)).thenReturn(false);

        service.deleteVlansInNetwork(network, USER_ID, caller);

        verify(dataCenterDao, never()).releaseVnet("321", ZONE_ID, PHYSICAL_NETWORK_ID, ACCOUNT_ID, RESERVATION_ID);
    }

    @Test
    public void deleteVlansInNetworkReturnsDeletedListWhenNoPublicVlansExist() {
        Pair<Boolean, List<VlanVO>> result = service.deleteVlansInNetwork(network, USER_ID, caller);

        assertTrue(result.first());
        assertTrue(result.second().isEmpty());
        verify(configurationManager, never()).deleteVlanAndPublicIpRange(USER_ID, 1L, caller);
    }

    @Test
    public void publishDeletedVlanRangesPublishesEachDeletedRange() {
        VlanVO firstDeleted = vlan(11L, "deleted-1");
        VlanVO secondDeleted = vlan(12L, "deleted-2");

        service.publishDeletedVlanRanges(SENDER_ADDRESS, Arrays.asList(firstDeleted, secondDeleted));

        verify(messageBus).publish(SENDER_ADDRESS, MESSAGE_DELETE_VLAN_IP_RANGE_EVENT, PublishScope.LOCAL, firstDeleted);
        verify(messageBus).publish(SENDER_ADDRESS, MESSAGE_DELETE_VLAN_IP_RANGE_EVENT, PublishScope.LOCAL, secondDeleted);
    }

    @Test
    public void publishDeletedVlanRangesIgnoresNullLists() {
        service.publishDeletedVlanRanges(SENDER_ADDRESS, null);

        verifyNoInteractions(messageBus);
    }

    @Test
    public void publishDeletedVlanRangesIgnoresEmptyLists() {
        service.publishDeletedVlanRanges(SENDER_ADDRESS, Collections.emptyList());

        verifyNoInteractions(messageBus);
    }

    private VlanVO vlan(long id, String uuid) {
        VlanVO vlan = mock(VlanVO.class);
        when(vlan.getId()).thenReturn(id);
        when(vlan.getUuid()).thenReturn(uuid);
        return vlan;
    }
}
