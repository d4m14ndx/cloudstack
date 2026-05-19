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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.VlanDao;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.IpAddress.State;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;
import com.cloud.vm.NicProfile;

public class RequestedNicIpReservationServiceImplTest {

    private static final long NETWORK_ID = 101L;
    private static final long IP_ID = 202L;
    private static final String REQUESTED_IPV4 = "192.168.100.150";
    private static final String GATEWAY = "192.168.100.1";
    private static final String NETMASK = "255.255.255.0";
    private static final String MAC = "00-88-14-4D-4C-FB";
    private static final String NEXT_MAC = "02:00:00:00:00:01";

    private RequestedNicIpReservationServiceImpl service;
    private VlanDao vlanDao;
    private IPAddressDao ipAddressDao;
    private NetworkModel networkModel;
    private Network network;

    @Before
    public void setUp() {
        service = new RequestedNicIpReservationServiceImpl();
        vlanDao = mock(VlanDao.class);
        ipAddressDao = mock(IPAddressDao.class);
        networkModel = mock(NetworkModel.class);
        service.vlanDao = vlanDao;
        service.ipAddressDao = ipAddressDao;
        service.networkModel = networkModel;

        network = mock(Network.class);
        when(network.getId()).thenReturn(NETWORK_ID);
    }

    @Test
    public void configureNicProfileBasedOnRequestedIpAssignsAddressGatewayNetmaskAndMacWhenMacMissing() throws Exception {
        NicProfile requested = requestedProfile(REQUESTED_IPV4);
        NicProfile profile = new NicProfile();
        IPAddressVO ip = stubVlanAndFreeIp(GATEWAY, NETMASK);
        when(networkModel.getNextAvailableMacAddressInNetwork(NETWORK_ID)).thenReturn(NEXT_MAC);

        service.configureNicProfileBasedOnRequestedIp(requested, profile, network);

        assertEquals(REQUESTED_IPV4, profile.getIPv4Address());
        assertEquals(GATEWAY, profile.getIPv4Gateway());
        assertEquals(NETMASK, profile.getIPv4Netmask());
        assertEquals(NEXT_MAC, profile.getMacAddress());
        assertEquals(State.Allocated, ip.getState());
        assertNotNull(ip.getAllocatedTime());
        verify(ipAddressDao).update(IP_ID, ip);
        verify(ipAddressDao).releaseFromLockTable(IP_ID);
    }

    @Test
    public void configureNicProfileBasedOnRequestedIpKeepsUniqueExistingMac() throws Exception {
        NicProfile requested = requestedProfile(REQUESTED_IPV4);
        NicProfile profile = new NicProfile();
        profile.setMacAddress(MAC);
        stubVlanAndFreeIp(GATEWAY, NETMASK);
        when(networkModel.isMACUnique(MAC, NETWORK_ID)).thenReturn(true);

        service.configureNicProfileBasedOnRequestedIp(requested, profile, network);

        assertEquals(MAC, profile.getMacAddress());
        verify(networkModel, never()).getNextAvailableMacAddressInNetwork(anyLong());
    }

    @Test
    public void configureNicProfileBasedOnRequestedIpReplacesDuplicateExistingMac() throws Exception {
        NicProfile requested = requestedProfile(REQUESTED_IPV4);
        NicProfile profile = new NicProfile();
        profile.setMacAddress(MAC);
        stubVlanAndFreeIp(GATEWAY, NETMASK);
        when(networkModel.isMACUnique(MAC, NETWORK_ID)).thenReturn(false);
        when(networkModel.getNextAvailableMacAddressInNetwork(NETWORK_ID)).thenReturn(NEXT_MAC);

        service.configureNicProfileBasedOnRequestedIp(requested, profile, network);

        assertEquals(NEXT_MAC, profile.getMacAddress());
    }

    @Test
    public void configureNicProfileBasedOnRequestedIpReturnsWhenRequestedProfileIsNull() {
        service.configureNicProfileBasedOnRequestedIp(null, new NicProfile(), network);

        verify(vlanDao, never()).findByNetworkIdAndIpv4(anyLong(), anyString());
        verify(ipAddressDao, never()).findByIpAndSourceNetworkId(anyLong(), anyString());
    }

    @Test
    public void configureNicProfileBasedOnRequestedIpReturnsWhenRequestedIpv4IsNull() {
        service.configureNicProfileBasedOnRequestedIp(new NicProfile(), new NicProfile(), network);

        verify(vlanDao, never()).findByNetworkIdAndIpv4(anyLong(), anyString());
        verify(ipAddressDao, never()).findByIpAndSourceNetworkId(anyLong(), anyString());
    }

    @Test
    public void configureNicProfileBasedOnRequestedIpRejectsInvalidRequestedIpv4() {
        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.configureNicProfileBasedOnRequestedIp(requestedProfile("123"), new NicProfile(), network));

        assertEquals("The requested [IPv4 address='123'] is not a valid IP address", exception.getMessage());
        verify(vlanDao, never()).findByNetworkIdAndIpv4(anyLong(), anyString());
    }

    @Test
    public void configureNicProfileBasedOnRequestedIpRejectsMissingVlan() {
        when(vlanDao.findByNetworkIdAndIpv4(NETWORK_ID, REQUESTED_IPV4)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.configureNicProfileBasedOnRequestedIp(requestedProfile(REQUESTED_IPV4), new NicProfile(), network));
        verify(ipAddressDao, never()).findByIpAndSourceNetworkId(anyLong(), anyString());
    }

    @Test
    public void configureNicProfileBasedOnRequestedIpRejectsInvalidGatewayBeforeLockingIp() {
        VlanVO vlan = vlan("123", NETMASK);
        when(vlanDao.findByNetworkIdAndIpv4(NETWORK_ID, REQUESTED_IPV4)).thenReturn(vlan);

        assertThrows(InvalidParameterValueException.class,
                () -> service.configureNicProfileBasedOnRequestedIp(requestedProfile(REQUESTED_IPV4), new NicProfile(), network));
        verify(ipAddressDao, never()).findByIpAndSourceNetworkId(anyLong(), anyString());
    }

    @Test
    public void configureNicProfileBasedOnRequestedIpRejectsInvalidNetmaskBeforeLockingIp() {
        VlanVO vlan = vlan(GATEWAY, "123");
        when(vlanDao.findByNetworkIdAndIpv4(NETWORK_ID, REQUESTED_IPV4)).thenReturn(vlan);

        assertThrows(InvalidParameterValueException.class,
                () -> service.configureNicProfileBasedOnRequestedIp(requestedProfile(REQUESTED_IPV4), new NicProfile(), network));
        verify(ipAddressDao, never()).findByIpAndSourceNetworkId(anyLong(), anyString());
    }

    @Test
    public void configureNicProfileBasedOnRequestedIpWrapsMacCapacityFailure() throws Exception {
        stubVlanAndFreeIp(GATEWAY, NETMASK);
        when(networkModel.getNextAvailableMacAddressInNetwork(NETWORK_ID)).thenThrow(new InsufficientAddressCapacityException("no mac", Network.class, NETWORK_ID));

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.configureNicProfileBasedOnRequestedIp(requestedProfile(REQUESTED_IPV4), new NicProfile(), network));

        assertEquals("Cannot get next available mac address in [network " + network + "]", exception.getMessage());
    }

    @Test
    public void acquireLockAndCheckIfIpv4IsFreeAllocatesAndReleasesFreeIp() {
        IPAddressVO ip = ipAddress(REQUESTED_IPV4, State.Free);
        when(ipAddressDao.findByIpAndSourceNetworkId(NETWORK_ID, REQUESTED_IPV4)).thenReturn(ip);
        when(ipAddressDao.acquireInLockTable(IP_ID)).thenReturn(ip);
        when(ipAddressDao.update(IP_ID, ip)).thenReturn(true);
        when(ipAddressDao.releaseFromLockTable(IP_ID)).thenReturn(true);

        service.acquireLockAndCheckIfIpv4IsFree(network, REQUESTED_IPV4);

        assertEquals(State.Allocated, ip.getState());
        assertNotNull(ip.getAllocatedTime());
        verify(ipAddressDao).update(IP_ID, ip);
        verify(ipAddressDao).releaseFromLockTable(IP_ID);
    }

    @Test
    public void acquireLockAndCheckIfIpv4IsFreeThrowsWithoutReleaseWhenIpVoMissing() {
        when(ipAddressDao.findByIpAndSourceNetworkId(NETWORK_ID, REQUESTED_IPV4)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class, () -> service.acquireLockAndCheckIfIpv4IsFree(network, REQUESTED_IPV4));

        verify(ipAddressDao, never()).acquireInLockTable(anyLong());
        verify(ipAddressDao, never()).releaseFromLockTable(anyLong());
    }

    @Test
    public void acquireLockAndCheckIfIpv4IsFreeReleasesWhenLockedIpMissing() {
        IPAddressVO ip = ipAddress(REQUESTED_IPV4, State.Free);
        when(ipAddressDao.findByIpAndSourceNetworkId(NETWORK_ID, REQUESTED_IPV4)).thenReturn(ip);
        when(ipAddressDao.acquireInLockTable(IP_ID)).thenReturn(null);
        when(ipAddressDao.releaseFromLockTable(IP_ID)).thenReturn(true);

        assertThrows(InvalidParameterValueException.class, () -> service.acquireLockAndCheckIfIpv4IsFree(network, REQUESTED_IPV4));

        verify(ipAddressDao, never()).update(anyLong(), any(IPAddressVO.class));
        verify(ipAddressDao).releaseFromLockTable(IP_ID);
    }

    @Test
    public void acquireLockAndCheckIfIpv4IsFreeReleasesAndDoesNotUpdateNonFreeIp() {
        IPAddressVO ip = ipAddress(REQUESTED_IPV4, State.Allocated);
        when(ipAddressDao.findByIpAndSourceNetworkId(NETWORK_ID, REQUESTED_IPV4)).thenReturn(ip);
        when(ipAddressDao.acquireInLockTable(IP_ID)).thenReturn(ip);
        when(ipAddressDao.releaseFromLockTable(IP_ID)).thenReturn(true);

        assertThrows(InvalidParameterValueException.class, () -> service.acquireLockAndCheckIfIpv4IsFree(network, REQUESTED_IPV4));

        verify(ipAddressDao, never()).update(anyLong(), any(IPAddressVO.class));
        verify(ipAddressDao).releaseFromLockTable(IP_ID);
    }

    @Test
    public void validateLockedRequestedIpRejectsNullLockedIp() {
        IPAddressVO ip = ipAddress(REQUESTED_IPV4, State.Free);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class, () -> service.validateLockedRequestedIp(ip, null));

        assertEquals("Cannot acquire guest [IPv4 address='" + REQUESTED_IPV4 + "'] as it was removed while acquiring lock", exception.getMessage());
    }

    @Test
    public void validateLockedRequestedIpRejectsEveryNonFreeState() {
        IPAddressVO ip = ipAddress(REQUESTED_IPV4, State.Free);
        for (State state : State.values()) {
            if (state == State.Free) {
                continue;
            }
            IPAddressVO lockedIp = ipAddress(REQUESTED_IPV4, state);
            assertThrows(InvalidParameterValueException.class, () -> service.validateLockedRequestedIp(ip, lockedIp));
        }
    }

    @Test
    public void validateLockedRequestedIpAcceptsFreeState() {
        IPAddressVO ip = ipAddress(REQUESTED_IPV4, State.Free);

        service.validateLockedRequestedIp(ip, ip);
    }

    private IPAddressVO stubVlanAndFreeIp(String gateway, String netmask) {
        IPAddressVO ip = ipAddress(REQUESTED_IPV4, State.Free);
        VlanVO vlan = vlan(gateway, netmask);
        when(vlanDao.findByNetworkIdAndIpv4(NETWORK_ID, REQUESTED_IPV4)).thenReturn(vlan);
        when(ipAddressDao.findByIpAndSourceNetworkId(NETWORK_ID, REQUESTED_IPV4)).thenReturn(ip);
        when(ipAddressDao.acquireInLockTable(IP_ID)).thenReturn(ip);
        when(ipAddressDao.update(IP_ID, ip)).thenReturn(true);
        when(ipAddressDao.releaseFromLockTable(IP_ID)).thenReturn(true);
        return ip;
    }

    private VlanVO vlan(String gateway, String netmask) {
        VlanVO vlan = mock(VlanVO.class);
        when(vlan.getVlanGateway()).thenReturn(gateway);
        when(vlan.getVlanNetmask()).thenReturn(netmask);
        when(vlan.getId()).thenReturn(303L);
        when(vlan.getUuid()).thenReturn("vlan-uuid");
        return vlan;
    }

    private NicProfile requestedProfile(String requestedIpv4) {
        NicProfile profile = new NicProfile();
        profile.setRequestedIPv4(requestedIpv4);
        return profile;
    }

    private IPAddressVO ipAddress(String address, State state) {
        IPAddressVO ip = Mockito.spy(new IPAddressVO(new Ip(address), 0L, 0L, 0L, true));
        Mockito.doReturn(IP_ID).when(ip).getId();
        ip.setState(state);
        return ip;
    }
}
