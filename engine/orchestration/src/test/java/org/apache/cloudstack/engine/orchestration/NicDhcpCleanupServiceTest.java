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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.ArgumentMatchers;
import org.mockito.Mockito;

import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.element.DhcpServiceProvider;
import com.cloud.network.element.NetworkElement;
import com.cloud.vm.Nic;
import com.cloud.vm.NicIpAlias;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicIpAliasDao;
import com.cloud.vm.dao.NicIpAliasVO;

@RunWith(JUnit4.class)
public class NicDhcpCleanupServiceTest {

    private NicDhcpCleanupServiceImpl service;

    private NetworkServiceMapDao networkServiceMapDao;
    private NetworkModel networkModel;
    private NetworkDao networksDao;
    private NicDao nicDao;
    private NicIpAliasDao nicIpAliasDao;
    private com.cloud.network.dao.IPAddressDao publicIpAddressDao;
    private NetworkProviderResolutionService networkProviderResolutionService;

    private static final long NETWORK_ID = 10L;

    @Before
    public void setUp() {
        service = new NicDhcpCleanupServiceImpl();
        networkServiceMapDao = mock(NetworkServiceMapDao.class);
        networkModel = mock(NetworkModel.class);
        networksDao = mock(NetworkDao.class);
        nicDao = mock(NicDao.class);
        nicIpAliasDao = mock(NicIpAliasDao.class);
        publicIpAddressDao = mock(com.cloud.network.dao.IPAddressDao.class);
        networkProviderResolutionService = mock(NetworkProviderResolutionService.class);

        service.networkServiceMapDao = networkServiceMapDao;
        service.networkModel = networkModel;
        service.networksDao = networksDao;
        service.nicDao = nicDao;
        service.nicIpAliasDao = nicIpAliasDao;
        service.publicIpAddressDao = publicIpAddressDao;
        service.networkProviderResolutionService = networkProviderResolutionService;
        service.networkElements = new ArrayList<>();
    }

    // -----------------------------------------------------------------------
    // isDhcpAccrossMultipleSubnetsSupported
    // -----------------------------------------------------------------------

    @Test
    public void testIsDhcpAccrossMultipleSubnetsSupportedTrue() {
        DhcpServiceProvider provider = mock(DhcpServiceProvider.class);
        Map<Network.Capability, String> caps = new HashMap<>();
        caps.put(Network.Capability.DhcpAccrossMultipleSubnets, "true");
        Map<Network.Service, Map<Network.Capability, String>> services = new HashMap<>();
        services.put(Network.Service.Dhcp, caps);
        when(provider.getCapabilities()).thenReturn(services);

        assertTrue(service.isDhcpAccrossMultipleSubnetsSupported(provider));
    }

    @Test
    public void testIsDhcpAccrossMultipleSubnetsSupportedFalse() {
        DhcpServiceProvider provider = mock(DhcpServiceProvider.class);
        Map<Network.Capability, String> caps = new HashMap<>();
        caps.put(Network.Capability.DhcpAccrossMultipleSubnets, "false");
        Map<Network.Service, Map<Network.Capability, String>> services = new HashMap<>();
        services.put(Network.Service.Dhcp, caps);
        when(provider.getCapabilities()).thenReturn(services);

        assertFalse(service.isDhcpAccrossMultipleSubnetsSupported(provider));
    }

    @Test
    public void testIsDhcpAccrossMultipleSubnetsSupportedCapabilityNull() {
        DhcpServiceProvider provider = mock(DhcpServiceProvider.class);
        Map<Network.Capability, String> caps = new HashMap<>();
        // DhcpAccrossMultipleSubnets not present
        Map<Network.Service, Map<Network.Capability, String>> services = new HashMap<>();
        services.put(Network.Service.Dhcp, caps);
        when(provider.getCapabilities()).thenReturn(services);

        assertFalse(service.isDhcpAccrossMultipleSubnetsSupported(provider));
    }

    // -----------------------------------------------------------------------
    // isLastNicInSubnet
    // -----------------------------------------------------------------------

    @Test
    public void testIsLastNicInSubnetTrueWhenSingleNic() {
        NicVO nic = mock(NicVO.class);
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(nic.getIPv4Gateway()).thenReturn("10.0.0.1");
        URI broadcastUri = URI.create("vlan://100");
        when(nic.getBroadcastUri()).thenReturn(broadcastUri);
        // Only this NIC in the subnet
        when(nicDao.listByNetworkIdTypeAndGatewayAndBroadcastUri(NETWORK_ID, VirtualMachine.Type.User, "10.0.0.1", broadcastUri))
                .thenReturn(List.of(nic));

        assertTrue(service.isLastNicInSubnet(nic));
    }

    @Test
    public void testIsLastNicInSubnetFalseWhenMultipleNics() {
        NicVO nic = mock(NicVO.class);
        NicVO nic2 = mock(NicVO.class);
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(nic.getIPv4Gateway()).thenReturn("10.0.0.1");
        URI broadcastUri = URI.create("vlan://100");
        when(nic.getBroadcastUri()).thenReturn(broadcastUri);
        // Two NICs in subnet
        when(nicDao.listByNetworkIdTypeAndGatewayAndBroadcastUri(NETWORK_ID, VirtualMachine.Type.User, "10.0.0.1", broadcastUri))
                .thenReturn(List.of(nic, nic2));

        assertFalse(service.isLastNicInSubnet(nic));
    }

    // -----------------------------------------------------------------------
    // cleanupNicDhcpDnsEntry
    // -----------------------------------------------------------------------

    @Test
    public void testCleanupNicDhcpDnsEntryNoProviders() {
        Network network = mock(Network.class);
        when(network.getId()).thenReturn(NETWORK_ID);
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        NicProfile nicProfile = mock(NicProfile.class);

        when(networkServiceMapDao.getDistinctProviders(NETWORK_ID)).thenReturn(new ArrayList<>());
        service.networkElements = new ArrayList<>();

        // Should complete without error and do nothing
        service.cleanupNicDhcpDnsEntry(network, vmProfile, nicProfile);
    }

    @Test
    public void testCleanupNicDhcpDnsEntrySkipsNonUserVm() throws ResourceUnavailableException {
        Network network = mock(Network.class);
        when(network.getId()).thenReturn(NETWORK_ID);
        when(network.getPhysicalNetworkId()).thenReturn(1L);
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        when(vmProfile.getType()).thenReturn(VirtualMachine.Type.DomainRouter);
        NicProfile nicProfile = mock(NicProfile.class);

        DhcpServiceProvider dhcpElement = mock(DhcpServiceProvider.class);
        Network.Provider provider = Network.Provider.VirtualRouter;
        when(dhcpElement.getProvider()).thenReturn(provider);

        List<NetworkElement> elements = new ArrayList<>();
        elements.add(dhcpElement);
        service.networkElements = elements;

        when(networkServiceMapDao.getDistinctProviders(NETWORK_ID)).thenReturn(List.of("VirtualRouter"));
        when(networkModel.isProviderEnabledInPhysicalNetwork(anyLong(), anyString())).thenReturn(true);
        when(networkModel.getPhysicalNetworkId(network)).thenReturn(1L);

        service.cleanupNicDhcpDnsEntry(network, vmProfile, nicProfile);

        // removeDhcpEntry should NOT be called for non-User VMs
        verify(dhcpElement, never()).removeDhcpEntry(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    public void testCleanupNicDhcpDnsEntryCallsRemoveDhcpEntry() throws ResourceUnavailableException {
        Network network = mock(Network.class);
        when(network.getId()).thenReturn(NETWORK_ID);
        when(network.getPhysicalNetworkId()).thenReturn(1L);
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        when(vmProfile.getType()).thenReturn(VirtualMachine.Type.User);
        NicProfile nicProfile = mock(NicProfile.class);

        DhcpServiceProvider dhcpElement = mock(DhcpServiceProvider.class);
        Network.Provider provider = Network.Provider.VirtualRouter;
        when(dhcpElement.getProvider()).thenReturn(provider);

        List<NetworkElement> elements = new ArrayList<>();
        elements.add(dhcpElement);
        service.networkElements = elements;

        when(networkServiceMapDao.getDistinctProviders(NETWORK_ID)).thenReturn(List.of("VirtualRouter"));
        when(networkModel.isProviderEnabledInPhysicalNetwork(anyLong(), anyString())).thenReturn(true);
        when(networkModel.getPhysicalNetworkId(network)).thenReturn(1L);
        when(networkModel.areServicesSupportedInNetwork(NETWORK_ID, Network.Service.Dhcp)).thenReturn(true);
        when(networkModel.isProviderSupportServiceInNetwork(NETWORK_ID, Network.Service.Dhcp, provider)).thenReturn(true);

        service.cleanupNicDhcpDnsEntry(network, vmProfile, nicProfile);

        verify(dhcpElement, Mockito.times(1)).removeDhcpEntry(network, nicProfile, vmProfile);
    }

    @Test
    public void testCleanupNicDhcpDnsEntrySwallowsResourceUnavailable() throws ResourceUnavailableException {
        Network network = mock(Network.class);
        when(network.getId()).thenReturn(NETWORK_ID);
        when(network.getPhysicalNetworkId()).thenReturn(1L);
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        when(vmProfile.getType()).thenReturn(VirtualMachine.Type.User);
        NicProfile nicProfile = mock(NicProfile.class);

        DhcpServiceProvider dhcpElement = mock(DhcpServiceProvider.class);
        Network.Provider provider = Network.Provider.VirtualRouter;
        when(dhcpElement.getProvider()).thenReturn(provider);
        when(dhcpElement.removeDhcpEntry(ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any()))
                .thenThrow(new ResourceUnavailableException("test", Network.class, 1L));

        List<NetworkElement> elements = new ArrayList<>();
        elements.add(dhcpElement);
        service.networkElements = elements;

        when(networkServiceMapDao.getDistinctProviders(NETWORK_ID)).thenReturn(List.of("VirtualRouter"));
        when(networkModel.isProviderEnabledInPhysicalNetwork(anyLong(), anyString())).thenReturn(true);
        when(networkModel.getPhysicalNetworkId(network)).thenReturn(1L);
        when(networkModel.areServicesSupportedInNetwork(NETWORK_ID, Network.Service.Dhcp)).thenReturn(true);
        when(networkModel.isProviderSupportServiceInNetwork(NETWORK_ID, Network.Service.Dhcp, provider)).thenReturn(true);

        // Should not throw; swallows ResourceUnavailableException
        service.cleanupNicDhcpDnsEntry(network, vmProfile, nicProfile);
    }

    // -----------------------------------------------------------------------
    // removeDhcpServiceInSubnet
    // -----------------------------------------------------------------------

    @Test
    public void testRemoveDhcpServiceInSubnetNoAlias() {
        Nic nic = mock(Nic.class);
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(nic.getIPv4Gateway()).thenReturn("10.0.0.1");

        NetworkVO network = mock(NetworkVO.class);
        when(network.getId()).thenReturn(NETWORK_ID);
        when(networksDao.findById(NETWORK_ID)).thenReturn(network);

        DhcpServiceProvider dhcpProvider = mock(DhcpServiceProvider.class);
        when(networkProviderResolutionService.getDhcpServiceProvider(network)).thenReturn(dhcpProvider);

        when(nicIpAliasDao.findByGatewayAndNetworkIdAndState("10.0.0.1", NETWORK_ID, NicIpAlias.State.active))
                .thenReturn(null);

        // Should complete without error
        service.removeDhcpServiceInSubnet(nic);

        verify(nicIpAliasDao, never()).update(anyLong(), ArgumentMatchers.any());
    }

    @Test
    public void testRemoveDhcpServiceInSubnetWithAlias() throws ResourceUnavailableException {
        Nic nic = mock(Nic.class);
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(nic.getIPv4Gateway()).thenReturn("10.0.0.1");

        NetworkVO network = mock(NetworkVO.class);
        when(network.getId()).thenReturn(NETWORK_ID);
        when(networksDao.findById(NETWORK_ID)).thenReturn(network);

        DhcpServiceProvider dhcpProvider = mock(DhcpServiceProvider.class);
        when(networkProviderResolutionService.getDhcpServiceProvider(network)).thenReturn(dhcpProvider);
        when(dhcpProvider.removeDhcpSupportForSubnet(network)).thenReturn(true);

        NicIpAliasVO ipAlias = mock(NicIpAliasVO.class);
        when(ipAlias.getNetworkId()).thenReturn(NETWORK_ID);
        when(ipAlias.getIp4Address()).thenReturn("10.0.0.100");
        when(ipAlias.getId()).thenReturn(1L);

        when(nicIpAliasDao.findByGatewayAndNetworkIdAndState("10.0.0.1", NETWORK_ID, NicIpAlias.State.active))
                .thenReturn(ipAlias);

        IPAddressVO aliasIp = mock(IPAddressVO.class);
        when(aliasIp.getId()).thenReturn(5L);
        when(publicIpAddressDao.findByIpAndSourceNetworkId(NETWORK_ID, "10.0.0.100")).thenReturn(aliasIp);
        when(nicIpAliasDao.update(anyLong(), ArgumentMatchers.any())).thenReturn(true);

        service.removeDhcpServiceInSubnet(nic);

        verify(ipAlias, Mockito.times(1)).setState(NicIpAlias.State.revoked);
        verify(dhcpProvider, Mockito.times(1)).removeDhcpSupportForSubnet(network);
    }

    @Test
    public void testRemoveDhcpServiceInSubnetSwallowsResourceUnavailable() throws ResourceUnavailableException {
        Nic nic = mock(Nic.class);
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(nic.getIPv4Gateway()).thenReturn("10.0.0.1");

        NetworkVO network = mock(NetworkVO.class);
        when(network.getId()).thenReturn(NETWORK_ID);
        when(networksDao.findById(NETWORK_ID)).thenReturn(network);

        DhcpServiceProvider dhcpProvider = mock(DhcpServiceProvider.class);
        when(networkProviderResolutionService.getDhcpServiceProvider(network)).thenReturn(dhcpProvider);
        when(dhcpProvider.removeDhcpSupportForSubnet(network)).thenThrow(new ResourceUnavailableException("unreachable", Network.class, 1L));

        NicIpAliasVO ipAlias = mock(NicIpAliasVO.class);
        when(ipAlias.getNetworkId()).thenReturn(NETWORK_ID);
        when(ipAlias.getIp4Address()).thenReturn("10.0.0.100");
        when(ipAlias.getId()).thenReturn(1L);

        when(nicIpAliasDao.findByGatewayAndNetworkIdAndState("10.0.0.1", NETWORK_ID, NicIpAlias.State.active))
                .thenReturn(ipAlias);

        IPAddressVO aliasIp = mock(IPAddressVO.class);
        when(aliasIp.getId()).thenReturn(5L);
        when(publicIpAddressDao.findByIpAndSourceNetworkId(NETWORK_ID, "10.0.0.100")).thenReturn(aliasIp);
        when(nicIpAliasDao.update(anyLong(), ArgumentMatchers.any())).thenReturn(true);

        // Should not throw
        service.removeDhcpServiceInSubnet(nic);
    }
}
