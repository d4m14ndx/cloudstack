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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;

import com.cloud.deploy.DeployDestination;
import com.cloud.network.Network;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.element.ConfigDriveNetworkElement;
import com.cloud.network.element.DhcpServiceProvider;
import com.cloud.network.element.DnsServiceProvider;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.element.UserDataServiceProvider;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;

public class NicElementPreparationServiceImplTest {

    private static final long NETWORK_ID = 42L;
    private static final Provider PROVIDER = Provider.VirtualRouter;

    private NicElementPreparationServiceImpl service;
    private NetworkModel networkModel;
    private NicDhcpCleanupService nicDhcpCleanupService;
    private Network network;
    private NicProfile nicProfile;
    private VirtualMachineProfile vmProfile;
    private DeployDestination dest;
    private ReservationContext context;

    @Before
    public void setUp() {
        networkModel = mock(NetworkModel.class);
        nicDhcpCleanupService = mock(NicDhcpCleanupService.class);
        network = mock(Network.class);
        nicProfile = new NicProfile();
        vmProfile = mock(VirtualMachineProfile.class);
        dest = mock(DeployDestination.class);
        context = mock(ReservationContext.class);

        service = new NicElementPreparationServiceImpl();
        service.networkModel = networkModel;
        service.nicDhcpCleanupService = nicDhcpCleanupService;

        when(network.getId()).thenReturn(NETWORK_ID);
        when(vmProfile.getType()).thenReturn(VirtualMachine.Type.User);
    }

    @Test
    public void prepareElementAddsDhcpEntryAfterSubnetSupportWhenDhcpIsSupported() throws Exception {
        DhcpServiceProvider element = mock(DhcpServiceProvider.class);
        when(element.getProvider()).thenReturn(PROVIDER);
        support(Service.Dhcp);
        when(nicDhcpCleanupService.isDhcpAccrossMultipleSubnetsSupported(element)).thenReturn(true);
        when(element.configDhcpSupportForSubnet(network, nicProfile, vmProfile, dest, context)).thenReturn(true);
        when(element.addDhcpEntry(network, nicProfile, vmProfile, dest, context)).thenReturn(true);

        assertTrue(service.prepareElement(element, network, nicProfile, vmProfile, dest, context));

        verify(element).prepare(network, nicProfile, vmProfile, dest, context);
        verify(element).configDhcpSupportForSubnet(network, nicProfile, vmProfile, dest, context);
        verify(element).addDhcpEntry(network, nicProfile, vmProfile, dest, context);
    }

    @Test
    public void prepareElementReturnsFalseWhenDhcpEntryFails() throws Exception {
        DhcpServiceProvider element = mock(DhcpServiceProvider.class);
        when(element.getProvider()).thenReturn(PROVIDER);
        support(Service.Dhcp);
        when(nicDhcpCleanupService.isDhcpAccrossMultipleSubnetsSupported(element)).thenReturn(false);
        when(element.addDhcpEntry(network, nicProfile, vmProfile, dest, context)).thenReturn(false);

        assertFalse(service.prepareElement(element, network, nicProfile, vmProfile, dest, context));

        verify(element, never()).configDhcpSupportForSubnet(network, nicProfile, vmProfile, dest, context);
    }

    @Test
    public void prepareElementAddsDnsEntryAndSkipsSubnetSupportForIpv6Nic() throws Exception {
        DnsServiceProvider element = mock(DnsServiceProvider.class);
        nicProfile.setIPv6Address("2001:db8::10");
        when(element.getProvider()).thenReturn(PROVIDER);
        support(Service.Dns);
        when(element.addDnsEntry(network, nicProfile, vmProfile, dest, context)).thenReturn(true);

        assertTrue(service.prepareElement(element, network, nicProfile, vmProfile, dest, context));

        verify(element, never()).configDnsSupportForSubnet(network, nicProfile, vmProfile, dest, context);
        verify(element).addDnsEntry(network, nicProfile, vmProfile, dest, context);
    }

    @Test
    public void prepareElementAddsUserDataWhenUserDataIsSupported() throws Exception {
        UserDataServiceProvider element = mock(UserDataServiceProvider.class);
        when(element.getProvider()).thenReturn(PROVIDER);
        support(Service.UserData);
        when(element.addPasswordAndUserdata(network, nicProfile, vmProfile, dest, context)).thenReturn(true);

        assertTrue(service.prepareElement(element, network, nicProfile, vmProfile, dest, context));

        verify(element).addPasswordAndUserdata(network, nicProfile, vmProfile, dest, context);
    }

    @Test
    public void prepareElementCreatesConfigDriveIsoWhenAnySideEffectServiceIsSupported() throws Exception {
        ConfigDriveNetworkElement element = mock(ConfigDriveNetworkElement.class);
        when(element.getProvider()).thenReturn(PROVIDER);
        support(Service.UserData);
        when(element.addPasswordAndUserdata(network, nicProfile, vmProfile, dest, context)).thenReturn(true);
        when(element.createConfigDriveIso(nicProfile, vmProfile, dest, null)).thenReturn(true);

        assertTrue(service.prepareElement(element, network, nicProfile, vmProfile, dest, context));

        verify(element).addPasswordAndUserdata(network, nicProfile, vmProfile, dest, context);
        verify(element).createConfigDriveIso(nicProfile, vmProfile, dest, null);
    }

    @Test
    public void prepareElementSkipsSideEffectsForNonUserVm() throws Exception {
        DhcpServiceProvider element = mock(DhcpServiceProvider.class);
        when(element.getProvider()).thenReturn(PROVIDER);
        when(vmProfile.getType()).thenReturn(VirtualMachine.Type.DomainRouter);

        assertTrue(service.prepareElement(element, network, nicProfile, vmProfile, dest, context));

        verify(element).prepare(network, nicProfile, vmProfile, dest, context);
        verify(networkModel, never()).areServicesSupportedInNetwork(NETWORK_ID, Service.Dhcp);
        verify(element, never()).addDhcpEntry(network, nicProfile, vmProfile, dest, context);
    }

    @Test
    public void prepareElementSkipsSideEffectsForNullProvider() throws Exception {
        NetworkElement element = mock(NetworkElement.class);
        when(element.getProvider()).thenReturn(null);

        assertTrue(service.prepareElement(element, network, nicProfile, vmProfile, dest, context));

        verify(element).prepare(network, nicProfile, vmProfile, dest, context);
        verify(networkModel, never()).areServicesSupportedInNetwork(NETWORK_ID, Service.Dhcp);
    }

    private void support(Service serviceName) {
        when(networkModel.areServicesSupportedInNetwork(NETWORK_ID, serviceName)).thenReturn(true);
        when(networkModel.isProviderSupportServiceInNetwork(NETWORK_ID, serviceName, PROVIDER)).thenReturn(true);
    }
}
