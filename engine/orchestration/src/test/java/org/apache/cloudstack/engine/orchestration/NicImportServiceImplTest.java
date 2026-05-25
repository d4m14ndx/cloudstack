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
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.net.URI;
import java.util.Arrays;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockedStatic;
import org.mockito.Mockito;

import com.cloud.dc.DataCenter;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.VlanDao;
import com.cloud.exception.InsufficientVirtualNetworkCapacityException;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.IpAddress.State;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.NicDao;
import com.cloud.user.dao.AccountDao;

@RunWith(JUnit4.class)
public class NicImportServiceImplTest {

    NicImportServiceImpl importService;

    private static final long networkOfferingId = 1L;

    @Before
    public void setUp() {
        importService = Mockito.spy(new NicImportServiceImpl());
        importService.nicDao = mock(NicDao.class);
        importService.networksDao = mock(NetworkDao.class);
        importService.ipAddressDao = mock(IPAddressDao.class);
        importService.vlanDao = mock(VlanDao.class);
        importService.networkModel = mock(NetworkModel.class);
        importService.ipAddrMgr = mock(IpAddressManager.class);
        importService.accountDao = mock(AccountDao.class);
        importService.nicProfileMtuService = mock(NicProfileMtuService.class);
    }

    @Test
    public void testGetNetworkGatewayAndNetmaskForNicImportAdvancedZone() {
        Network network = Mockito.mock(Network.class);
        DataCenter dataCenter = Mockito.mock(DataCenter.class);
        String ipAddress = "10.1.1.10";

        String networkGateway = "10.1.1.1";
        String networkNetmask = "255.255.255.0";
        String networkCidr = "10.1.1.0/24";
        Mockito.when(dataCenter.getNetworkType()).thenReturn(DataCenter.NetworkType.Advanced);
        Mockito.when(network.getGateway()).thenReturn(networkGateway);
        Mockito.when(network.getCidr()).thenReturn(networkCidr);
        Pair<String, String> pair = importService.getNetworkGatewayAndNetmaskForNicImport(network, dataCenter, ipAddress);
        Assert.assertNotNull(pair);
        Assert.assertEquals(networkGateway, pair.first());
        Assert.assertEquals(networkNetmask, pair.second());
    }

    @Test
    public void testGetNetworkGatewayAndNetmaskForNicImportBasicZone() {
        Network network = Mockito.mock(Network.class);
        DataCenter dataCenter = Mockito.mock(DataCenter.class);
        IPAddressVO ipAddressVO = Mockito.mock(IPAddressVO.class);
        String ipAddress = "172.1.1.10";

        String defaultNetworkGateway = "172.1.1.1";
        String defaultNetworkNetmask = "255.255.255.0";
        VlanVO vlan = Mockito.mock(VlanVO.class);
        Mockito.when(vlan.getVlanGateway()).thenReturn(defaultNetworkGateway);
        Mockito.when(vlan.getVlanNetmask()).thenReturn(defaultNetworkNetmask);
        Mockito.when(dataCenter.getNetworkType()).thenReturn(DataCenter.NetworkType.Basic);
        Mockito.when(ipAddressVO.getVlanId()).thenReturn(1L);
        Mockito.when(importService.vlanDao.findById(1L)).thenReturn(vlan);
        Mockito.when(importService.ipAddressDao.findByIp(ipAddress)).thenReturn(ipAddressVO);
        Pair<String, String> pair = importService.getNetworkGatewayAndNetmaskForNicImport(network, dataCenter, ipAddress);
        Assert.assertNotNull(pair);
        Assert.assertEquals(defaultNetworkGateway, pair.first());
        Assert.assertEquals(defaultNetworkNetmask, pair.second());
    }

    @Test
    public void testGetGuestIpForNicImportL2Network() {
        Network network = Mockito.mock(Network.class);
        DataCenter dataCenter = Mockito.mock(DataCenter.class);
        Network.IpAddresses ipAddresses = Mockito.mock(Network.IpAddresses.class);
        Mockito.when(network.getGuestType()).thenReturn(GuestType.L2);
        Assert.assertNull(importService.getSelectedIpForNicImport(network, dataCenter, ipAddresses));
    }

    @Test
    public void testGetGuestIpForNicImportAdvancedZone() {
        Network network = Mockito.mock(Network.class);
        DataCenter dataCenter = Mockito.mock(DataCenter.class);
        Network.IpAddresses ipAddresses = Mockito.mock(Network.IpAddresses.class);
        Mockito.when(network.getGuestType()).thenReturn(GuestType.Isolated);
        Mockito.when(dataCenter.getNetworkType()).thenReturn(DataCenter.NetworkType.Advanced);
        String ipAddress = "10.1.10.10";
        Mockito.when(ipAddresses.getIp4Address()).thenReturn(ipAddress);
        Mockito.when(importService.ipAddrMgr.acquireGuestIpAddress(network, ipAddress)).thenReturn(ipAddress);
        String guestIp = importService.getSelectedIpForNicImport(network, dataCenter, ipAddresses);
        Assert.assertEquals(ipAddress, guestIp);
    }

    @Test
    public void testGetGuestIpForNicImportBasicZoneAutomaticIP() {
        Network network = Mockito.mock(Network.class);
        DataCenter dataCenter = Mockito.mock(DataCenter.class);
        Network.IpAddresses ipAddresses = Mockito.mock(Network.IpAddresses.class);
        Mockito.when(network.getGuestType()).thenReturn(GuestType.Shared);
        Mockito.when(dataCenter.getNetworkType()).thenReturn(DataCenter.NetworkType.Basic);
        long networkId = 1L;
        long dataCenterId = 1L;
        String freeIp = "172.10.10.10";
        IPAddressVO ipAddressVO = Mockito.mock(IPAddressVO.class);
        Ip ip = mock(Ip.class);
        Mockito.when(ip.addr()).thenReturn(freeIp);
        Mockito.when(ipAddressVO.getAddress()).thenReturn(ip);
        Mockito.when(ipAddressVO.getState()).thenReturn(State.Free);
        Mockito.when(network.getId()).thenReturn(networkId);
        Mockito.when(dataCenter.getId()).thenReturn(dataCenterId);
        Mockito.when(importService.ipAddressDao.findBySourceNetworkIdAndDatacenterIdAndState(networkId, dataCenterId, State.Free)).thenReturn(ipAddressVO);
        String ipAddress = importService.getSelectedIpForNicImport(network, dataCenter, ipAddresses);
        Assert.assertEquals(freeIp, ipAddress);
    }

    @Test
    public void testGetGuestIpForNicImportBasicZoneManualIP() {
        Network network = Mockito.mock(Network.class);
        DataCenter dataCenter = Mockito.mock(DataCenter.class);
        Network.IpAddresses ipAddresses = Mockito.mock(Network.IpAddresses.class);
        Mockito.when(network.getGuestType()).thenReturn(GuestType.Shared);
        Mockito.when(dataCenter.getNetworkType()).thenReturn(DataCenter.NetworkType.Basic);
        long networkId = 1L;
        long dataCenterId = 1L;
        String requestedIp = "172.10.10.10";
        IPAddressVO ipAddressVO = Mockito.mock(IPAddressVO.class);
        Ip ip = mock(Ip.class);
        Mockito.when(ip.addr()).thenReturn(requestedIp);
        Mockito.when(ipAddressVO.getAddress()).thenReturn(ip);
        Mockito.when(ipAddressVO.getState()).thenReturn(State.Free);
        Mockito.when(network.getId()).thenReturn(networkId);
        Mockito.when(dataCenter.getId()).thenReturn(dataCenterId);
        Mockito.when(ipAddresses.getIp4Address()).thenReturn(requestedIp);
        Mockito.when(importService.ipAddressDao.findByIpAndSourceNetworkId(networkId, requestedIp)).thenReturn(ipAddressVO);
        String ipAddress = importService.getSelectedIpForNicImport(network, dataCenter, ipAddresses);
        Assert.assertEquals(requestedIp, ipAddress);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testGetGuestIpForNicImportBasicUsedIP() {
        Network network = Mockito.mock(Network.class);
        DataCenter dataCenter = Mockito.mock(DataCenter.class);
        Network.IpAddresses ipAddresses = Mockito.mock(Network.IpAddresses.class);
        Mockito.when(network.getGuestType()).thenReturn(GuestType.Shared);
        Mockito.when(dataCenter.getNetworkType()).thenReturn(DataCenter.NetworkType.Basic);
        long networkId = 1L;
        long dataCenterId = 1L;
        String requestedIp = "172.10.10.10";
        IPAddressVO ipAddressVO = Mockito.mock(IPAddressVO.class);
        Ip ip = mock(Ip.class);
        Mockito.when(ip.addr()).thenReturn(requestedIp);
        Mockito.when(ipAddressVO.getAddress()).thenReturn(ip);
        Mockito.when(ipAddressVO.getState()).thenReturn(State.Allocated);
        Mockito.when(network.getId()).thenReturn(networkId);
        Mockito.when(dataCenter.getId()).thenReturn(dataCenterId);
        Mockito.when(ipAddresses.getIp4Address()).thenReturn(requestedIp);
        Mockito.when(importService.ipAddressDao.findByIp(requestedIp)).thenReturn(ipAddressVO);
        importService.getSelectedIpForNicImport(network, dataCenter, ipAddresses);
    }

    @Test(expected = InsufficientVirtualNetworkCapacityException.class)
    public void testImportNicAcquireGuestIPFailed() throws Exception {
        DataCenter dataCenter = Mockito.mock(DataCenter.class);
        VirtualMachine vm = mock(VirtualMachine.class);
        Network network = Mockito.mock(Network.class);
        Mockito.when(network.getGuestType()).thenReturn(GuestType.Isolated);
        Mockito.when(network.getNetworkOfferingId()).thenReturn(networkOfferingId);
        long dataCenterId = 1L;
        Mockito.when(network.getDataCenterId()).thenReturn(dataCenterId);
        Network.IpAddresses ipAddresses = Mockito.mock(Network.IpAddresses.class);
        String ipAddress = "10.1.10.10";
        Mockito.when(ipAddresses.getIp4Address()).thenReturn(ipAddress);
        Mockito.doReturn(null).when(importService).getSelectedIpForNicImport(network, dataCenter, ipAddresses);
        Mockito.when(importService.networkModel.listNetworkOfferingServices(networkOfferingId)).thenReturn(Arrays.asList(Service.Dns, Service.Dhcp));
        String macAddress = "02:01:01:82:00:01";
        int deviceId = 0;
        importService.importNic(macAddress, deviceId, network, true, vm, ipAddresses, dataCenter, false);
    }

    @Test(expected = InsufficientVirtualNetworkCapacityException.class)
    public void testImportNicAutoAcquireGuestIPFailed() throws Exception {
        DataCenter dataCenter = Mockito.mock(DataCenter.class);
        VirtualMachine vm = mock(VirtualMachine.class);
        Network network = Mockito.mock(Network.class);
        Mockito.when(network.getGuestType()).thenReturn(GuestType.Isolated);
        Mockito.when(network.getNetworkOfferingId()).thenReturn(networkOfferingId);
        long dataCenterId = 1L;
        Mockito.when(network.getDataCenterId()).thenReturn(dataCenterId);
        Network.IpAddresses ipAddresses = Mockito.mock(Network.IpAddresses.class);
        String ipAddress = "auto";
        Mockito.when(ipAddresses.getIp4Address()).thenReturn(ipAddress);
        Mockito.doReturn(null).when(importService).getSelectedIpForNicImport(network, dataCenter, ipAddresses);
        Mockito.when(importService.networkModel.listNetworkOfferingServices(networkOfferingId)).thenReturn(Arrays.asList(Service.Dns, Service.Dhcp));
        String macAddress = "02:01:01:82:00:01";
        int deviceId = 0;
        importService.importNic(macAddress, deviceId, network, true, vm, ipAddresses, dataCenter, false);
    }

    @Test
    public void testImportNicNoIP4Address() throws Exception {
        DataCenter dataCenter = Mockito.mock(DataCenter.class);
        Long vmId = 1L;
        Hypervisor.HypervisorType hypervisorType = Hypervisor.HypervisorType.KVM;
        VirtualMachine vm = mock(VirtualMachine.class);
        Mockito.when(vm.getId()).thenReturn(vmId);
        Mockito.when(vm.getHypervisorType()).thenReturn(hypervisorType);
        Long networkId = 1L;
        Network network = Mockito.mock(Network.class);
        Mockito.when(network.getId()).thenReturn(networkId);
        Network.IpAddresses ipAddresses = Mockito.mock(Network.IpAddresses.class);
        Mockito.when(ipAddresses.getIp4Address()).thenReturn(null);
        URI broadcastUri = URI.create("vlan://123");
        NicVO nic = mock(NicVO.class);
        Mockito.when(nic.getBroadcastUri()).thenReturn(broadcastUri);
        String macAddress = "02:01:01:82:00:01";
        int deviceId = 1;
        Integer networkRate = 200;
        Mockito.when(importService.networkModel.getNetworkRate(networkId, vmId)).thenReturn(networkRate);
        Mockito.when(importService.networkModel.isSecurityGroupSupportedInNetwork(network)).thenReturn(false);
        Mockito.when(importService.networkModel.getNetworkTag(hypervisorType, network)).thenReturn("testtag");
        try (MockedStatic<Transaction> transactionMocked = Mockito.mockStatic(Transaction.class)) {
            transactionMocked.when(() -> Transaction.execute(any(TransactionCallback.class))).thenReturn(nic);
            Pair<NicProfile, Integer> nicProfileIntegerPair = importService.importNic(macAddress, deviceId, network, true, vm, ipAddresses, dataCenter, false);
            verify(importService.networkModel, times(1)).getNetworkRate(networkId, vmId);
            verify(importService.networkModel, times(1)).isSecurityGroupSupportedInNetwork(network);
            verify(importService.networkModel, times(1)).getNetworkTag(Hypervisor.HypervisorType.KVM, network);
            assertEquals(deviceId, nicProfileIntegerPair.second().intValue());
            NicProfile nicProfile = nicProfileIntegerPair.first();
            assertEquals(broadcastUri, nicProfile.getBroadCastUri());
            assertEquals(networkRate, nicProfile.getNetworkRate());
            assertFalse(nicProfile.isSecurityGroupEnabled());
            assertEquals("testtag", nicProfile.getName());
        }
    }

    @Test
    public void testImportNicWithIP4Address() throws Exception {
        DataCenter dataCenter = Mockito.mock(DataCenter.class);
        Long vmId = 1L;
        Hypervisor.HypervisorType hypervisorType = Hypervisor.HypervisorType.KVM;
        VirtualMachine vm = mock(VirtualMachine.class);
        Mockito.when(vm.getId()).thenReturn(vmId);
        Mockito.when(vm.getHypervisorType()).thenReturn(hypervisorType);
        Long networkId = 1L;
        Network network = Mockito.mock(Network.class);
        Mockito.when(network.getId()).thenReturn(networkId);
        String ipAddress = "10.1.10.10";
        Network.IpAddresses ipAddresses = Mockito.mock(Network.IpAddresses.class);
        Mockito.when(ipAddresses.getIp4Address()).thenReturn(ipAddress);
        URI broadcastUri = URI.create("vlan://123");
        NicVO nic = mock(NicVO.class);
        Mockito.when(nic.getBroadcastUri()).thenReturn(broadcastUri);
        String macAddress = "02:01:01:82:00:01";
        int deviceId = 1;
        Integer networkRate = 200;
        Mockito.when(importService.networkModel.getNetworkRate(networkId, vmId)).thenReturn(networkRate);
        Mockito.when(importService.networkModel.isSecurityGroupSupportedInNetwork(network)).thenReturn(false);
        Mockito.when(importService.networkModel.getNetworkTag(hypervisorType, network)).thenReturn("testtag");
        try (MockedStatic<Transaction> transactionMocked = Mockito.mockStatic(Transaction.class)) {
            transactionMocked.when(() -> Transaction.execute(any(TransactionCallback.class))).thenReturn(nic);
            Pair<NicProfile, Integer> nicProfileIntegerPair = importService.importNic(macAddress, deviceId, network, true, vm, ipAddresses, dataCenter, false);
            verify(importService, times(1)).getSelectedIpForNicImport(network, dataCenter, ipAddresses);
            verify(importService.networkModel, times(1)).getNetworkRate(networkId, vmId);
            verify(importService.networkModel, times(1)).isSecurityGroupSupportedInNetwork(network);
            verify(importService.networkModel, times(1)).getNetworkTag(Hypervisor.HypervisorType.KVM, network);
            assertEquals(deviceId, nicProfileIntegerPair.second().intValue());
            NicProfile nicProfile = nicProfileIntegerPair.first();
            assertEquals(broadcastUri, nicProfile.getBroadCastUri());
            assertEquals(networkRate, nicProfile.getNetworkRate());
            assertFalse(nicProfile.isSecurityGroupEnabled());
            assertEquals("testtag", nicProfile.getName());
        }
    }
}
