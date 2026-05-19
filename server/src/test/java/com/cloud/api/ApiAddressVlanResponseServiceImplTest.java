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
package com.cloud.api;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.apache.cloudstack.api.response.IpQuarantineResponse;
import org.apache.cloudstack.api.response.NicSecondaryIpResponse;
import org.apache.cloudstack.api.response.VlanIpRangeResponse;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.Vlan;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.network.PublicIpQuarantine;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkVO;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.net.Ip;
import com.cloud.vm.NicSecondaryIp;
import com.cloud.vm.NicVO;

@RunWith(MockitoJUnitRunner.class)
public class ApiAddressVlanResponseServiceImplTest {

    private final ApiAddressVlanResponseServiceImpl service = new ApiAddressVlanResponseServiceImpl();

    @Mock
    private EntityManager entityManager;
    @Mock
    private IPAddressDao ipAddressDao;
    @Mock
    private AccountManager accountManager;
    @Mock
    private NicSecondaryIp secondaryIp;
    @Mock
    private NicVO nic;
    @Mock
    private NetworkVO network;
    @Mock
    private PublicIpQuarantine quarantinedIp;
    @Mock
    private IPAddressVO ipAddress;
    @Mock
    private Account previousOwner;
    @Mock
    private Account removerAccount;

    @Test
    public void createVlanIpRangeResponseFormatsIpv4AndIpv6Ranges() {
        ApiAddressVlanResponseServiceImpl serviceSpy = Mockito.spy(service);
        Vlan vlan = Mockito.mock(Vlan.class);
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        when(vlan.getId()).thenReturn(100L);
        when(vlan.getUuid()).thenReturn("vlan-uuid");
        when(vlan.getVlanType()).thenReturn(VlanType.VirtualNetwork);
        when(vlan.getVlanTag()).thenReturn("vlan://200");
        when(vlan.getDataCenterId()).thenReturn(10L);
        when(vlan.getVlanGateway()).thenReturn("192.0.2.1");
        when(vlan.getVlanNetmask()).thenReturn("255.255.255.0");
        when(vlan.getIpRange()).thenReturn("192.0.2.10-192.0.2.20");
        when(vlan.getIp6Gateway()).thenReturn("2001:db8::1");
        when(vlan.getIp6Cidr()).thenReturn("2001:db8::/64");
        when(vlan.getIp6Range()).thenReturn("2001:db8::10-2001:db8::20");
        when(vlan.getNetworkId()).thenReturn(null);
        when(vlan.getPhysicalNetworkId()).thenReturn(null);
        when(zone.getUuid()).thenReturn("zone-uuid");
        Mockito.doReturn(false).when(serviceSpy).isForSystemVms(100L);
        Mockito.doReturn(null).when(serviceSpy).getProviderFromVlanDetailKey(vlan);

        try (MockedStatic<ApiDBUtils> ignored = Mockito.mockStatic(ApiDBUtils.class)) {
            when(ApiDBUtils.getPodIdForVlan(100L)).thenReturn(null);
            when(ApiDBUtils.findZoneById(10L)).thenReturn(zone);
            when(ApiDBUtils.getVlanAccount(100L)).thenReturn(null);
            when(ApiDBUtils.getVlanDomain(100L)).thenReturn(null);

            VlanIpRangeResponse response = serviceSpy.createVlanIpRangeResponse(vlan);

            assertEquals("vlan-uuid", ReflectionTestUtils.getField(response, "id"));
            assertEquals(Boolean.TRUE, ReflectionTestUtils.getField(response, "forVirtualNetwork"));
            assertEquals("vlan://200", ReflectionTestUtils.getField(response, "vlan"));
            assertEquals("zone-uuid", ReflectionTestUtils.getField(response, "zoneId"));
            assertEquals("192.0.2.1", ReflectionTestUtils.getField(response, "gateway"));
            assertEquals("255.255.255.0", ReflectionTestUtils.getField(response, "netmask"));
            assertEquals("192.0.2.0/24", ReflectionTestUtils.getField(response, "cidr"));
            assertEquals("192.0.2.10", ReflectionTestUtils.getField(response, "startIp"));
            assertEquals("192.0.2.20", ReflectionTestUtils.getField(response, "endIp"));
            assertEquals("2001:db8::1", ReflectionTestUtils.getField(response, "ip6Gateway"));
            assertEquals("2001:db8::/64", ReflectionTestUtils.getField(response, "ip6Cidr"));
            assertEquals("2001:db8::10", ReflectionTestUtils.getField(response, "startIpv6"));
            assertEquals("2001:db8::20", ReflectionTestUtils.getField(response, "endIpv6"));
            assertEquals("vlan", response.getObjectName());
        }
    }

    @Test
    public void setResponseIpAddressPrefersIpv4WhenBothFamiliesArePresent() {
        NicSecondaryIpResponse response = new NicSecondaryIpResponse();
        when(secondaryIp.getIp4Address()).thenReturn("192.0.2.10");

        ApiAddressVlanResponseService.setResponseIpAddress(secondaryIp, response);

        assertEquals("192.0.2.10", response.getIpAddr());
    }

    @Test
    public void setResponseIpAddressFallsBackToIpv6WhenIpv4IsAbsent() {
        NicSecondaryIpResponse response = new NicSecondaryIpResponse();
        when(secondaryIp.getIp4Address()).thenReturn(null);
        when(secondaryIp.getIp6Address()).thenReturn("2001:db8::10");

        ApiAddressVlanResponseService.setResponseIpAddress(secondaryIp, response);

        assertEquals("2001:db8::10", response.getIpAddr());
    }

    @Test
    public void setResponseIpAddressLeavesIpAddressEmptyWhenNoAddressExists() {
        NicSecondaryIpResponse response = new NicSecondaryIpResponse();
        when(secondaryIp.getIp4Address()).thenReturn(null);
        when(secondaryIp.getIp6Address()).thenReturn(null);

        ApiAddressVlanResponseService.setResponseIpAddress(secondaryIp, response);

        assertNull(response.getIpAddr());
    }

    @Test
    public void createSecondaryIPToNicResponsePopulatesNicNetworkAndIpv4Address() {
        wireSecondaryIpDependencies();
        when(secondaryIp.getIp4Address()).thenReturn("192.0.2.10");

        NicSecondaryIpResponse response = service.createSecondaryIPToNicResponse(secondaryIp);

        assertEquals("secondary-ip-uuid", response.getId());
        assertEquals("192.0.2.10", response.getIpAddr());
        assertEquals("nic-uuid", response.getNicId());
        assertEquals("network-uuid", response.getNwId());
        assertEquals("nicsecondaryip", response.getObjectName());
    }

    @Test
    public void createSecondaryIPToNicResponseUsesIpv6AddressWhenIpv4IsAbsent() {
        wireSecondaryIpDependencies();
        when(secondaryIp.getIp4Address()).thenReturn(null);
        when(secondaryIp.getIp6Address()).thenReturn("2001:db8::10");

        NicSecondaryIpResponse response = service.createSecondaryIPToNicResponse(secondaryIp);

        assertEquals("2001:db8::10", response.getIpAddr());
    }

    @Test
    public void createQuarantinedIpsResponsePopulatesBasicsWithoutRemoverAccount() {
        wireQuarantineDependencies(null);
        when(quarantinedIp.getRemoverAccountId()).thenReturn(null);

        IpQuarantineResponse response = service.createQuarantinedIpsResponse(quarantinedIp);

        assertEquals("quarantined-ip-uuid", response.getId());
        assertEquals("203.0.113.10", response.getPublicIpAddress());
        assertEquals("previous-owner-uuid", response.getPreviousOwnerId());
        assertEquals("previous-owner-name", response.getPreviousOwnerName());
        assertEquals(new Date(100L), response.getCreated());
        assertEquals(new Date(200L), response.getRemoved());
        assertEquals(new Date(300L), response.getEndDate());
        assertEquals("expired", response.getRemovalReason());
        assertNull(response.getRemoverAccountId());
        assertEquals("quarantinedip", response.getResponseName());
    }

    @Test
    public void createQuarantinedIpsResponseIncludesRemoverAccountWhenPresent() {
        wireQuarantineDependencies(400L);
        when(removerAccount.getUuid()).thenReturn("remover-account-uuid");
        when(accountManager.getAccount(400L)).thenReturn(removerAccount);

        IpQuarantineResponse response = service.createQuarantinedIpsResponse(quarantinedIp);

        assertEquals("remover-account-uuid", response.getRemoverAccountId());
    }

    private void wireSecondaryIpDependencies() {
        ReflectionTestUtils.setField(service, "_entityMgr", entityManager);
        when(secondaryIp.getUuid()).thenReturn("secondary-ip-uuid");
        when(secondaryIp.getNicId()).thenReturn(10L);
        when(secondaryIp.getNetworkId()).thenReturn(20L);
        when(entityManager.findById(NicVO.class, 10L)).thenReturn(nic);
        when(entityManager.findById(NetworkVO.class, 20L)).thenReturn(network);
        when(nic.getUuid()).thenReturn("nic-uuid");
        when(network.getUuid()).thenReturn("network-uuid");
    }

    private void wireQuarantineDependencies(Long removerAccountId) {
        ReflectionTestUtils.setField(service, "userIpAddressDao", ipAddressDao);
        ReflectionTestUtils.setField(service, "_accountMgr", accountManager);
        when(quarantinedIp.getUuid()).thenReturn("quarantined-ip-uuid");
        when(quarantinedIp.getPublicIpAddressId()).thenReturn(500L);
        when(ipAddressDao.findById(500L)).thenReturn(ipAddress);
        when(ipAddress.getAddress()).thenReturn(new Ip("203.0.113.10"));
        when(quarantinedIp.getPreviousOwnerId()).thenReturn(300L);
        when(accountManager.getAccount(300L)).thenReturn(previousOwner);
        when(previousOwner.getUuid()).thenReturn("previous-owner-uuid");
        when(previousOwner.getName()).thenReturn("previous-owner-name");
        when(quarantinedIp.getCreated()).thenReturn(new Date(100L));
        when(quarantinedIp.getRemoved()).thenReturn(new Date(200L));
        when(quarantinedIp.getEndDate()).thenReturn(new Date(300L));
        when(quarantinedIp.getRemovalReason()).thenReturn("expired");
        when(quarantinedIp.getRemoverAccountId()).thenReturn(removerAccountId);
    }
}
