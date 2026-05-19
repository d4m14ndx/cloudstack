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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.api.routing.NetworkElementCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.NetworkProfile;
import com.cloud.network.Networks.AddressFormat;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.Mode;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.NicDao;

public class NicProfileLifecycleMappingServiceImplTest {

    private static final long VM_ID = 101L;
    private static final long NETWORK_ID = 202L;
    private static final String GURU_NAME = "GuestNetworkGuru";

    private NicProfileLifecycleMappingServiceImpl service;
    private NicDao nicDao;
    private NetworkDao networksDao;
    private NetworkModel networkModel;
    private NetworkGuru guru;

    @Before
    public void setUp() {
        service = new NicProfileLifecycleMappingServiceImpl();
        nicDao = mock(NicDao.class);
        networksDao = mock(NetworkDao.class);
        networkModel = mock(NetworkModel.class);
        guru = mock(NetworkGuru.class);
        when(guru.getName()).thenReturn(GURU_NAME);
        service.nicDao = nicDao;
        service.networksDao = networksDao;
        service.networkModel = networkModel;
        service.setNetworkGurus(Collections.singletonList(guru));
    }

    @Test
    public void applyProfileToNicUsesProfileDeviceIdAndCopiesAllocationFields() {
        NicVO nic = new NicVO("reserver", VM_ID, NETWORK_ID, VirtualMachine.Type.User);
        NicProfile profile = profileWithAllocatedFields();
        profile.setDeviceId(7);

        Integer nextDeviceId = service.applyProfileToNic(nic, profile, 3);

        assertEquals(Integer.valueOf(3), nextDeviceId);
        assertEquals(7, nic.getDeviceId());
        assertEquals(Nic.ReservationStrategy.Create, nic.getReservationStrategy());
        assertTrue(nic.isDefaultNic());
        assertEquals("10.1.1.10", nic.getIPv4Address());
        assertEquals(AddressFormat.DualStack, nic.getAddressFormat());
        assertEquals("02:00:00:00:00:10", nic.getMacAddress());
        assertEquals(Mode.Static, nic.getMode());
        assertEquals("255.255.255.0", nic.getIPv4Netmask());
        assertEquals("10.1.1.1", nic.getIPv4Gateway());
        assertEquals(uri("vlan://101"), nic.getBroadcastUri());
        assertEquals(uri("vlan://201"), nic.getIsolationUri());
        assertEquals(Nic.State.Allocated, nic.getState());
        assertEquals("2001:db8::10", nic.getIPv6Address());
        assertEquals("2001:db8::1", nic.getIPv6Gateway());
        assertEquals("2001:db8::/64", nic.getIPv6Cidr());
    }

    @Test
    public void applyProfileToNicUsesCallerDeviceIdAndReturnsIncrementedValue() {
        NicVO nic = new NicVO("reserver", VM_ID, NETWORK_ID, VirtualMachine.Type.User);
        NicProfile profile = profileWithAllocatedFields();

        Integer nextDeviceId = service.applyProfileToNic(nic, profile, 3);

        assertEquals(3, nic.getDeviceId());
        assertEquals(Integer.valueOf(4), nextDeviceId);
    }

    @Test
    public void applyProfileToNicLeavesMacAndUrisUnchangedWhenProfileValuesAreNull() {
        NicVO nic = new NicVO("reserver", VM_ID, NETWORK_ID, VirtualMachine.Type.User);
        URI originalBroadcastUri = uri("vlan://301");
        URI originalIsolationUri = uri("vlan://401");
        nic.setMacAddress("02:00:00:00:00:aa");
        nic.setBroadcastUri(originalBroadcastUri);
        nic.setIsolationUri(originalIsolationUri);
        NicProfile profile = new NicProfile();

        service.applyProfileToNic(nic, profile, 1);

        assertEquals("02:00:00:00:00:aa", nic.getMacAddress());
        assertEquals(originalBroadcastUri, nic.getBroadcastUri());
        assertEquals(originalIsolationUri, nic.getIsolationUri());
    }

    @Test
    public void applyProfileToNicForReleaseCopiesReleaseFieldsAndReservationWhenPresent() {
        NicVO nic = new NicVO("reserver", VM_ID, NETWORK_ID, VirtualMachine.Type.User);
        NicProfile profile = profileWithAllocatedFields();

        service.applyProfileToNicForRelease(nic, profile);

        assertEquals("10.1.1.1", nic.getIPv4Gateway());
        assertEquals(AddressFormat.DualStack, nic.getAddressFormat());
        assertEquals("10.1.1.10", nic.getIPv4Address());
        assertEquals("2001:db8::10", nic.getIPv6Address());
        assertEquals("02:00:00:00:00:10", nic.getMacAddress());
        assertEquals(Nic.ReservationStrategy.Create, nic.getReservationStrategy());
        assertEquals(uri("vlan://101"), nic.getBroadcastUri());
        assertEquals(uri("vlan://201"), nic.getIsolationUri());
        assertEquals("255.255.255.0", nic.getIPv4Netmask());
    }

    @Test
    public void applyProfileToNicForReleaseDoesNotOverwriteReservationStrategyWithNull() {
        NicVO nic = new NicVO("reserver", VM_ID, NETWORK_ID, VirtualMachine.Type.User);
        nic.setReservationStrategy(Nic.ReservationStrategy.Start);

        service.applyProfileToNicForRelease(nic, new NicProfile());

        assertEquals(Nic.ReservationStrategy.Start, nic.getReservationStrategy());
    }

    @Test
    public void applyProfileToNetworkCopiesBroadcastDnsAndPhysicalNetwork() {
        NetworkVO network = new NetworkVO();
        NetworkProfile profile = mock(NetworkProfile.class);
        when(profile.getBroadcastUri()).thenReturn(uri("vlan://501"));
        when(profile.getDns1()).thenReturn("1.1.1.1");
        when(profile.getDns2()).thenReturn("9.9.9.9");
        when(profile.getPhysicalNetworkId()).thenReturn(88L);

        service.applyProfileToNetwork(network, profile);

        assertEquals(uri("vlan://501"), network.getBroadcastUri());
        assertEquals("1.1.1.1", network.getDns1());
        assertEquals("9.9.9.9", network.getDns2());
        assertEquals(Long.valueOf(88L), network.getPhysicalNetworkId());
    }

    @Test
    public void toNicTOCopiesNicAndNetworkMetadata() {
        NicVO nic = nicWithFields(NETWORK_ID, VM_ID, VirtualMachine.Type.User);
        NetworkVO network = networkWithFields(NETWORK_ID, TrafficType.Guest);
        NicProfile profile = new NicProfile();
        profile.setIPv4Dns1("8.8.8.8");
        profile.setIPv4Dns2("8.8.4.4");
        when(networkModel.getNetworkRate(NETWORK_ID, null)).thenReturn(200);

        NicTO to = service.toNicTO(nic, profile, network);

        assertEquals(nic.getDeviceId(), to.getDeviceId());
        assertEquals(BroadcastDomainType.Vlan, to.getBroadcastType());
        assertEquals(TrafficType.Guest, to.getType());
        assertEquals("10.2.2.10", to.getIp());
        assertEquals("255.255.255.0", to.getNetmask());
        assertEquals("02:00:00:00:00:20", to.getMac());
        assertEquals("8.8.8.8", to.getDns1());
        assertEquals("8.8.4.4", to.getDns2());
        assertEquals("10.2.2.1", to.getGateway());
        assertTrue(to.isDefaultNic());
        assertEquals(nic.getBroadcastUri(), to.getBroadcastUri());
        assertEquals(nic.getIsolationUri(), to.getIsolationUri());
        assertEquals(Integer.valueOf(200), to.getNetworkRateMbps());
        assertEquals(network.getUuid(), to.getUuid());
        assertFalse(to.getPxeDisable());
    }

    @Test
    public void toNicTOFallsBackToConfigGatewayAndDisablesPxeForNonUserNic() {
        NicVO nic = nicWithFields(NETWORK_ID, VM_ID, VirtualMachine.Type.DomainRouter);
        nic.setIPv4Gateway(null);
        NetworkVO network = networkWithFields(NETWORK_ID, TrafficType.Control);
        network.setGateway("10.2.2.254");
        NicProfile profile = new NicProfile();
        profile.setIPv4Dns1("4.4.4.4");
        profile.setIPv4Dns2("4.4.8.8");

        NicTO to = service.toNicTO(nic, profile, network);

        assertEquals("10.2.2.254", to.getGateway());
        assertTrue(to.getPxeDisable());
    }

    @Test
    public void getNicProfileForVmUsesBroadcastLookupWhenRequestedBroadcastMatchesIp() {
        Network network = mockNetwork(NETWORK_ID);
        VirtualMachine vm = mockVm(VM_ID, HypervisorType.KVM);
        NicProfile requested = new NicProfile();
        requested.setBroadcastUri(uri("vlan://601"));
        requested.setIPv4Address("10.3.3.10");
        NicVO nic = nicWithFields(NETWORK_ID, VM_ID, VirtualMachine.Type.User);
        nic.setIPv4Address("10.3.3.10");
        NicProfile expected = new NicProfile();
        when(nicDao.findByNetworkIdInstanceIdAndBroadcastUri(NETWORK_ID, VM_ID, "vlan://601")).thenReturn(nic);
        when(networkModel.getNicProfile(vm, NETWORK_ID, "vlan://601")).thenReturn(expected);

        NicProfile result = service.getNicProfileForVm(network, requested, vm);

        assertSame(expected, result);
        verify(nicDao).findByNetworkIdInstanceIdAndBroadcastUri(NETWORK_ID, VM_ID, "vlan://601");
        verify(networkModel).getNicProfile(vm, NETWORK_ID, "vlan://601");
    }

    @Test
    public void getNicProfileForVmRejectsRequestedBroadcastWhenIpv4Differs() {
        Network network = mockNetwork(NETWORK_ID);
        VirtualMachine vm = mockVm(VM_ID, HypervisorType.KVM);
        NicProfile requested = new NicProfile();
        requested.setBroadcastUri(uri("vlan://601"));
        requested.setIPv4Address("10.3.3.99");
        NicVO nic = nicWithFields(NETWORK_ID, VM_ID, VirtualMachine.Type.User);
        nic.setIPv4Address("10.3.3.10");
        when(nicDao.findByNetworkIdInstanceIdAndBroadcastUri(NETWORK_ID, VM_ID, "vlan://601")).thenReturn(nic);

        NicProfile result = service.getNicProfileForVm(network, requested, vm);

        assertNull(result);
        verify(networkModel, never()).getNicProfile(eq(vm), eq(NETWORK_ID), eq("vlan://601"));
    }

    @Test
    public void getNicProfileForVmFallsBackToNetworkVmLookupWithoutRequestedBroadcast() {
        Network network = mockNetwork(NETWORK_ID);
        VirtualMachine vm = mockVm(VM_ID, HypervisorType.KVM);
        NicVO nic = nicWithFields(NETWORK_ID, VM_ID, VirtualMachine.Type.User);
        NicProfile expected = new NicProfile();
        when(nicDao.findByNtwkIdAndInstanceId(NETWORK_ID, VM_ID)).thenReturn(nic);
        when(networkModel.getNicProfile(vm, NETWORK_ID, null)).thenReturn(expected);

        NicProfile result = service.getNicProfileForVm(network, null, vm);

        assertSame(expected, result);
        verify(nicDao).findByNtwkIdAndInstanceId(NETWORK_ID, VM_ID);
        verify(networkModel).getNicProfile(vm, NETWORK_ID, null);
    }

    @Test
    public void getNicProfileDefaultNicReturnsRequestedValueOrFalse() {
        NicProfile requested = new NicProfile();
        requested.setDefaultNic(true);
        assertTrue(service.getNicProfileDefaultNic(requested));

        requested.setDefaultNic(false);
        assertFalse(service.getNicProfileDefaultNic(requested));

        assertFalse(service.getNicProfileDefaultNic(null));
    }

    @Test
    public void getNicProfilesBuildsProfilesAndLetsGuruUpdateEachProfile() {
        NicVO nic = nicWithFields(NETWORK_ID, VM_ID, VirtualMachine.Type.User);
        setEntityId(nic, 77L);
        NetworkVO network = networkWithFields(NETWORK_ID, TrafficType.Guest);
        when(nicDao.listByVmId(VM_ID)).thenReturn(Collections.singletonList(nic));
        when(networksDao.findById(NETWORK_ID)).thenReturn(network);
        when(networkModel.getNetworkRate(NETWORK_ID, VM_ID)).thenReturn(300);
        when(networkModel.isSecurityGroupSupportedInNetwork(network)).thenReturn(true);
        when(networkModel.getNetworkTag(HypervisorType.KVM, network)).thenReturn("cloudbr0");

        List<NicProfile> profiles = service.getNicProfiles(VM_ID, HypervisorType.KVM);

        assertEquals(1, profiles.size());
        NicProfile profile = profiles.get(0);
        assertEquals(NETWORK_ID, profile.getNetworkId());
        assertEquals(77L, profile.getId());
        assertEquals(Integer.valueOf(300), profile.getNetworkRate());
        assertTrue(profile.isSecurityGroupEnabled());
        assertEquals("cloudbr0", profile.getName());
        ArgumentCaptor<NicProfile> profileCaptor = ArgumentCaptor.forClass(NicProfile.class);
        verify(guru).updateNicProfile(profileCaptor.capture(), eq(network));
        assertSame(profile, profileCaptor.getValue());
        verify(networkModel).getNetworkRate(NETWORK_ID, VM_ID);
        verify(networkModel).isSecurityGroupSupportedInNetwork(network);
        verify(networkModel).getNetworkTag(HypervisorType.KVM, network);
    }

    @Test
    public void getNicProfilesReturnsEmptyListWhenDaoReturnsNull() {
        when(nicDao.listByVmId(VM_ID)).thenReturn(null);

        List<NicProfile> profiles = service.getNicProfiles(VM_ID, HypervisorType.KVM);

        assertTrue(profiles.isEmpty());
    }

    @Test
    public void getNicProfilesVirtualMachineUsesVmIdAndHypervisorType() {
        VirtualMachine vm = mockVm(VM_ID, HypervisorType.KVM);
        NicVO nic = nicWithFields(NETWORK_ID, VM_ID, VirtualMachine.Type.User);
        NetworkVO network = networkWithFields(NETWORK_ID, TrafficType.Guest);
        when(nicDao.listByVmId(VM_ID)).thenReturn(Collections.singletonList(nic));
        when(networksDao.findById(NETWORK_ID)).thenReturn(network);

        List<NicProfile> profiles = service.getNicProfiles(vm);

        assertEquals(1, profiles.size());
        verify(nicDao).listByVmId(VM_ID);
    }

    @Test
    public void getSystemVMAccessDetailsMapsTrafficTypeAddressesAndRouterName() {
        VirtualMachine vm = mockVm(VM_ID, HypervisorType.KVM);
        when(vm.getInstanceName()).thenReturn("r-101");
        NicVO controlNic = nicWithAddress(301L, VM_ID, "10.4.0.10");
        NicVO guestNic = nicWithAddress(302L, VM_ID, "10.5.0.10");
        NicVO managementNic = nicWithAddress(303L, VM_ID, "10.6.0.10");
        when(nicDao.listByVmId(VM_ID)).thenReturn(Arrays.asList(controlNic, guestNic, managementNic));
        when(networksDao.findById(301L)).thenReturn(networkWithFields(301L, TrafficType.Control));
        when(networksDao.findById(302L)).thenReturn(networkWithFields(302L, TrafficType.Guest));
        when(networksDao.findById(303L)).thenReturn(networkWithFields(303L, TrafficType.Management));

        Map<String, String> accessDetails = service.getSystemVMAccessDetails(vm);

        assertEquals("r-101", accessDetails.get(NetworkElementCommand.ROUTER_NAME));
        assertEquals("10.4.0.10", accessDetails.get(NetworkElementCommand.ROUTER_IP));
        assertEquals("10.5.0.10", accessDetails.get(NetworkElementCommand.ROUTER_GUEST_IP));
        assertEquals("10.4.0.10", accessDetails.get(TrafficType.Control.name()));
        assertEquals("10.5.0.10", accessDetails.get(TrafficType.Guest.name()));
        assertEquals("10.6.0.10", accessDetails.get(TrafficType.Management.name()));
    }

    @Test
    public void getSystemVMAccessDetailsUsesManagementAddressAsRouterIpWhenControlAddressMissing() {
        VirtualMachine vm = mockVm(VM_ID, HypervisorType.KVM);
        when(vm.getInstanceName()).thenReturn("r-102");
        NicVO managementNic = nicWithAddress(303L, VM_ID, "10.6.0.10");
        when(nicDao.listByVmId(VM_ID)).thenReturn(Collections.singletonList(managementNic));
        when(networksDao.findById(303L)).thenReturn(networkWithFields(303L, TrafficType.Management));

        Map<String, String> accessDetails = service.getSystemVMAccessDetails(vm);

        assertEquals("10.6.0.10", accessDetails.get(NetworkElementCommand.ROUTER_IP));
    }

    @Test
    public void getSystemVMAccessDetailsSkipsNullProfilesAndMissingNetworks() {
        NicProfileLifecycleMappingServiceImpl partialService = new NicProfileLifecycleMappingServiceImpl() {
            @Override
            public List<NicProfile> getNicProfiles(final VirtualMachine vm) {
                NicProfile profile = new NicProfile();
                profile.setNetworkId(NETWORK_ID);
                profile.setIPv4Address("10.7.0.10");
                return Arrays.asList(null, profile);
            }
        };
        partialService.networksDao = networksDao;
        VirtualMachine vm = mockVm(VM_ID, HypervisorType.KVM);
        when(vm.getInstanceName()).thenReturn("r-103");
        when(networksDao.findById(NETWORK_ID)).thenReturn(null);

        Map<String, String> accessDetails = partialService.getSystemVMAccessDetails(vm);

        assertEquals(Collections.singletonMap(NetworkElementCommand.ROUTER_NAME, "r-103"), accessDetails);
    }

    private NicProfile profileWithAllocatedFields() {
        NicProfile profile = new NicProfile();
        profile.setReservationStrategy(Nic.ReservationStrategy.Create);
        profile.setDefaultNic(true);
        profile.setIPv4Address("10.1.1.10");
        profile.setFormat(AddressFormat.DualStack);
        profile.setMacAddress("02:00:00:00:00:10");
        profile.setMode(Mode.Static);
        profile.setIPv4Netmask("255.255.255.0");
        profile.setIPv4Gateway("10.1.1.1");
        profile.setBroadcastUri(uri("vlan://101"));
        profile.setIsolationUri(uri("vlan://201"));
        profile.setIPv6Address("2001:db8::10");
        profile.setIPv6Gateway("2001:db8::1");
        profile.setIPv6Cidr("2001:db8::/64");
        return profile;
    }

    private NicVO nicWithFields(long networkId, long vmId, VirtualMachine.Type vmType) {
        NicVO nic = new NicVO("reserver", vmId, networkId, vmType);
        nic.setDeviceId(4);
        nic.setIPv4Address("10.2.2.10");
        nic.setIPv4Netmask("255.255.255.0");
        nic.setIPv4Gateway("10.2.2.1");
        nic.setMacAddress("02:00:00:00:00:20");
        nic.setAddressFormat(AddressFormat.Ip4);
        nic.setMode(Mode.Static);
        nic.setDefaultNic(true);
        nic.setReservationStrategy(Nic.ReservationStrategy.Start);
        nic.setBroadcastUri(uri("vlan://102"));
        nic.setIsolationUri(uri("vlan://202"));
        return nic;
    }

    private NicVO nicWithAddress(long networkId, long vmId, String ipv4Address) {
        NicVO nic = nicWithFields(networkId, vmId, VirtualMachine.Type.DomainRouter);
        nic.setIPv4Address(ipv4Address);
        return nic;
    }

    private NetworkVO networkWithFields(long networkId, TrafficType trafficType) {
        NetworkVO network = new NetworkVO(networkId, trafficType, Mode.Static, BroadcastDomainType.Vlan, 55L, 66L, 77L, networkId, "network-" + networkId,
                "display-" + networkId, "example.local", Network.GuestType.Isolated, 88L, 99L, ACLType.Account, false, null, false);
        network.setGateway("10.2.2.254");
        network.setBroadcastUri(uri("vlan://102"));
        network.setGuruName(GURU_NAME);
        return network;
    }

    private Network mockNetwork(long networkId) {
        Network network = mock(Network.class);
        when(network.getId()).thenReturn(networkId);
        return network;
    }

    private VirtualMachine mockVm(long vmId, HypervisorType hypervisorType) {
        VirtualMachine vm = mock(VirtualMachine.class);
        when(vm.getId()).thenReturn(vmId);
        when(vm.getHypervisorType()).thenReturn(hypervisorType);
        return vm;
    }

    private void setEntityId(NicVO nic, long id) {
        ReflectionTestUtils.setField(nic, "id", id);
    }

    private URI uri(String value) {
        return URI.create(value);
    }
}
