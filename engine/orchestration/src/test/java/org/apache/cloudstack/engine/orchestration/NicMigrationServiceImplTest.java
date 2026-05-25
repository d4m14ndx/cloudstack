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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import com.cloud.dc.Vlan;
import com.cloud.dc.VlanVO;
import com.cloud.deploy.DeployDestination;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.NetworkMigrationResponder;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.AddressFormat;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.IsolationType;
import com.cloud.network.Networks.Mode;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.NicDao;
import com.cloud.dc.dao.VlanDao;

public class NicMigrationServiceImplTest {

    private static final String GURU_NAME = "testGuru";
    private static final long VM_ID = 42L;
    private static final long HOST_ID = 101L;
    private static final long DST_HOST_ID = 202L;
    private static final long NETWORK_ID = 11L;
    private static final long PUBLIC_NETWORK_ID = 22L;
    private static final long PHYSICAL_NETWORK_ID = 33L;
    private static final long VLAN_ID = 44L;
    private static final String VLAN_TAG = "123";

    NicMigrationServiceImpl service;
    DeployDestination dest;

    @Before
    public void setUp() {
        service = Mockito.spy(new NicMigrationServiceImpl());
        service.nicDao = mock(NicDao.class);
        service.networksDao = mock(NetworkDao.class);
        service.networkModel = mock(NetworkModel.class);
        service.networkServiceMapDao = mock(NetworkServiceMapDao.class);
        service.ipAddressDao = mock(IPAddressDao.class);
        service.vlanDao = mock(VlanDao.class);
        service.physicalNetworkDao = mock(PhysicalNetworkDao.class);
        service.userVmManager = mock(UserVmManager.class);
        service.networkGurus = new ArrayList<>();
        service.networkElements = new ArrayList<>();
        dest = mock(DeployDestination.class);
    }

    @Test
    public void testPrepareNicForMigrationCallsGuruAndElementResponders() {
        VirtualMachineProfile vm = newVm(Type.User, HypervisorType.KVM, HOST_ID);
        NetworkVO network = newNetwork(NETWORK_ID, GuestType.Shared, TrafficType.Guest);
        NicVO nic = newNic(NETWORK_ID);
        NetworkGuru guru = addGuru(true);
        NetworkMigrationResponder guruResponder = (NetworkMigrationResponder) guru;
        NetworkElement element = addElement(Provider.VirtualRouter, true);
        NetworkMigrationResponder elementResponder = (NetworkMigrationResponder) element;
        stubPrepareNetwork(vm, network, nic, Collections.singletonList(Provider.VirtualRouter));
        when(guruResponder.prepareMigration(any(NicProfile.class), eq(network), eq(vm), eq(dest), any(ReservationContext.class))).thenReturn(true);
        when(elementResponder.prepareMigration(any(NicProfile.class), eq(network), eq(vm), eq(dest), any(ReservationContext.class))).thenReturn(true);

        service.prepareNicForMigration(vm, dest);

        ArgumentCaptor<NicProfile> profileCaptor = ArgumentCaptor.forClass(NicProfile.class);
        verify(guruResponder).prepareMigration(profileCaptor.capture(), eq(network), eq(vm), eq(dest), any(ReservationContext.class));
        NicProfile profile = profileCaptor.getValue();
        verify(elementResponder).prepareMigration(eq(profile), eq(network), eq(vm), eq(dest), any(ReservationContext.class));
        verify(guru).updateNicProfile(profile, network);
        verify(vm).addNic(profile);
    }

    @Test
    public void testPrepareNicForMigrationSkipsNonResponderGuruAndElement() {
        VirtualMachineProfile vm = newVm(Type.User, HypervisorType.KVM, HOST_ID);
        NetworkVO network = newNetwork(NETWORK_ID, GuestType.Shared, TrafficType.Guest);
        NicVO nic = newNic(NETWORK_ID);
        NetworkGuru guru = addGuru(false);
        addElement(Provider.VirtualRouter, false);
        stubPrepareNetwork(vm, network, nic, Collections.singletonList(Provider.VirtualRouter));

        service.prepareNicForMigration(vm, dest);

        verify(guru).updateNicProfile(any(NicProfile.class), eq(network));
        verify(vm).addNic(any(NicProfile.class));
    }

    @Test
    public void testPrepareNicForMigrationSetsUpPvlanForUserL2Network() {
        VirtualMachineProfile vm = newVm(Type.User, HypervisorType.KVM, HOST_ID);
        NetworkVO network = newNetwork(NETWORK_ID, GuestType.L2, TrafficType.Guest);
        NicVO nic = newNic(NETWORK_ID);
        addGuru(false);
        stubPrepareNetwork(vm, network, nic, Collections.emptyList());

        service.prepareNicForMigration(vm, dest);

        verify(service.userVmManager).setupVmForPvlan(eq(false), eq(HOST_ID), any(NicProfile.class));
    }

    @Test
    public void testPrepareNicForMigrationThrowsWhenProviderDisabled() {
        VirtualMachineProfile vm = newVm(Type.User, HypervisorType.KVM, HOST_ID);
        NetworkVO network = newNetwork(NETWORK_ID, GuestType.Shared, TrafficType.Guest);
        NicVO nic = newNic(NETWORK_ID);
        addGuru(false);
        addElement(Provider.VirtualRouter, false);
        stubPrepareNetwork(vm, network, nic, Collections.singletonList(Provider.VirtualRouter));
        when(service.networkModel.isProviderEnabledInPhysicalNetwork(PHYSICAL_NETWORK_ID, Provider.VirtualRouter.getName())).thenReturn(false);

        try {
            service.prepareNicForMigration(vm, dest);
            fail("Expected CloudRuntimeException");
        } catch (CloudRuntimeException e) {
            assertTrue(e.getMessage().contains("physical network id: " + PHYSICAL_NETWORK_ID));
        }
    }

    @Test
    public void testPrepareNicForMigrationDomainRouterKvmUsesAllNicsPath() {
        VirtualMachineProfile vm = newVm(Type.DomainRouter, HypervisorType.KVM, HOST_ID);
        NetworkVO guestNetwork = newNetwork(NETWORK_ID, GuestType.Isolated, TrafficType.Guest);
        NetworkVO publicNetwork = newNetwork(PUBLIC_NETWORK_ID, GuestType.Shared, TrafficType.Public);
        NicVO nic = newNic(NETWORK_ID);
        IPAddressVO publicIp = newPublicIp("203.0.113.10");
        VlanVO vlan = newVlan();
        addGuru(false);
        stubPrepareNetwork(vm, guestNetwork, nic, Collections.emptyList());
        stubNetworkLookups(vm, publicNetwork);
        when(service.ipAddressDao.listByAssociatedNetwork(NETWORK_ID, null)).thenReturn(Collections.singletonList(publicIp));
        when(service.vlanDao.findById(VLAN_ID)).thenReturn(vlan);
        when(service.nicDao.findByNetworkIdInstanceIdAndBroadcastUri(eq(PUBLIC_NETWORK_ID), eq(VM_ID), any(String.class))).thenReturn(null);

        service.prepareNicForMigration(vm, dest);

        verify(service.ipAddressDao).listByAssociatedNetwork(NETWORK_ID, null);
        verify(vm, times(2)).addNic(any(NicProfile.class));
    }

    @Test
    public void testPrepareAllNicsForMigrationCreatesDummyPublicIpProfileWhenDbNicMissing() {
        VirtualMachineProfile vm = newVm(Type.User, HypervisorType.KVM, HOST_ID);
        NetworkVO guestNetwork = newNetwork(NETWORK_ID, GuestType.Isolated, TrafficType.Guest);
        NetworkVO publicNetwork = newNetwork(PUBLIC_NETWORK_ID, GuestType.Shared, TrafficType.Public);
        NicVO nic = newNic(NETWORK_ID);
        IPAddressVO userIp = newPublicIp("203.0.113.10");
        VlanVO vlan = newVlan();
        addGuru(false);
        stubPrepareNetwork(vm, guestNetwork, nic, Collections.emptyList());
        stubNetworkLookups(vm, publicNetwork);
        when(service.ipAddressDao.listByAssociatedNetwork(NETWORK_ID, null)).thenReturn(Collections.singletonList(userIp));
        when(service.vlanDao.findById(VLAN_ID)).thenReturn(vlan);
        when(service.nicDao.findByNetworkIdInstanceIdAndBroadcastUri(eq(PUBLIC_NETWORK_ID), eq(VM_ID), any(String.class))).thenReturn(null);

        service.prepareAllNicsForMigration(vm, dest);

        ArgumentCaptor<NicProfile> profileCaptor = ArgumentCaptor.forClass(NicProfile.class);
        verify(vm, times(2)).addNic(profileCaptor.capture());
        NicProfile dummyProfile = findDummyProfile(profileCaptor.getAllValues());
        assertNotNull(dummyProfile);
        assertEquals(Integer.valueOf(255), dummyProfile.getDeviceId());
        assertEquals(userIp.getAddress().toString(), dummyProfile.getIPv4Address());
        assertEquals(vlan.getVlanGateway(), dummyProfile.getIPv4Gateway());
        assertEquals(vlan.getVlanNetmask(), dummyProfile.getIPv4Netmask());
        assertTrue(dummyProfile.getMacAddress().startsWith("1e:01:"));
        assertTrue(dummyProfile.getMacAddress().endsWith(":00:04:d2"));
        assertEquals(BroadcastDomainType.Vlan.toUri(VLAN_TAG), dummyProfile.getBroadCastUri());
        assertEquals(IsolationType.Vlan.toUri(VLAN_TAG), dummyProfile.getIsolationUri());
        assertEquals(PUBLIC_NETWORK_ID, dummyProfile.getNetworkId());
        assertEquals(Integer.valueOf(200), dummyProfile.getNetworkRate());
        assertEquals("tag-" + PUBLIC_NETWORK_ID, dummyProfile.getName());
    }

    @Test
    public void testPrepareAllNicsForMigrationDoesNotCreateDummyProfileWhenDbNicExists() {
        VirtualMachineProfile vm = newVm(Type.User, HypervisorType.KVM, HOST_ID);
        NetworkVO guestNetwork = newNetwork(NETWORK_ID, GuestType.Isolated, TrafficType.Guest);
        NicVO nic = newNic(NETWORK_ID);
        IPAddressVO userIp = newPublicIp("203.0.113.10");
        VlanVO vlan = newVlan();
        addGuru(false);
        stubPrepareNetwork(vm, guestNetwork, nic, Collections.emptyList());
        when(service.ipAddressDao.listByAssociatedNetwork(NETWORK_ID, null)).thenReturn(Collections.singletonList(userIp));
        when(service.vlanDao.findById(VLAN_ID)).thenReturn(vlan);
        when(service.nicDao.findByNetworkIdInstanceIdAndBroadcastUri(eq(PUBLIC_NETWORK_ID), eq(VM_ID), any(String.class))).thenReturn(mock(NicVO.class));

        service.prepareAllNicsForMigration(vm, dest);

        verify(vm, times(1)).addNic(any(NicProfile.class));
    }

    @Test
    public void testPrepareAllNicsForMigrationDeduplicatesPublicIpBroadcastUris() {
        VirtualMachineProfile vm = newVm(Type.User, HypervisorType.KVM, HOST_ID);
        NetworkVO guestNetwork = newNetwork(NETWORK_ID, GuestType.Isolated, TrafficType.Guest);
        NetworkVO publicNetwork = newNetwork(PUBLIC_NETWORK_ID, GuestType.Shared, TrafficType.Public);
        NicVO nic = newNic(NETWORK_ID);
        IPAddressVO firstIp = newPublicIp("203.0.113.10");
        IPAddressVO secondIp = newPublicIp("203.0.113.11");
        VlanVO vlan = newVlan();
        addGuru(false);
        stubPrepareNetwork(vm, guestNetwork, nic, Collections.emptyList());
        stubNetworkLookups(vm, publicNetwork);
        when(service.ipAddressDao.listByAssociatedNetwork(NETWORK_ID, null)).thenReturn(Arrays.asList(firstIp, secondIp));
        when(service.vlanDao.findById(VLAN_ID)).thenReturn(vlan);
        when(service.nicDao.findByNetworkIdInstanceIdAndBroadcastUri(eq(PUBLIC_NETWORK_ID), eq(VM_ID), any(String.class))).thenReturn(null);

        service.prepareAllNicsForMigration(vm, dest);

        verify(vm, times(2)).addNic(any(NicProfile.class));
    }

    @Test
    public void testPrepareAllNicsForMigrationThrowsWithPhysicalNetworkInMessageWhenProviderDisabled() {
        VirtualMachineProfile vm = newVm(Type.User, HypervisorType.KVM, HOST_ID);
        NetworkVO network = newNetwork(NETWORK_ID, GuestType.Shared, TrafficType.Guest);
        NicVO nic = newNic(NETWORK_ID);
        addGuru(false);
        addElement(Provider.VirtualRouter, false);
        stubPrepareNetwork(vm, network, nic, Collections.singletonList(Provider.VirtualRouter));
        when(service.networkModel.isProviderEnabledInPhysicalNetwork(PHYSICAL_NETWORK_ID, Provider.VirtualRouter.getName())).thenReturn(false);
        when(service.physicalNetworkDao.findById(PHYSICAL_NETWORK_ID)).thenReturn(mock(PhysicalNetworkVO.class));

        try {
            service.prepareAllNicsForMigration(vm, dest);
            fail("Expected CloudRuntimeException");
        } catch (CloudRuntimeException e) {
            assertTrue(e.getMessage().contains("physical network:"));
            verify(service.physicalNetworkDao).findById(PHYSICAL_NETWORK_ID);
        }
    }

    @Test
    public void testCommitNicForMigrationCallsRespondersPvlanAndPersistsReservation() {
        NetworkVO network = newNetwork(NETWORK_ID, GuestType.L2, TrafficType.Guest);
        VirtualMachineProfile src = newVmWithNics(Type.User, HypervisorType.KVM, HOST_ID, nicProfile(9L, NETWORK_ID, "src-reservation"));
        VirtualMachineProfile dst = newVmWithNics(Type.User, HypervisorType.KVM, DST_HOST_ID, nicProfile(9L, NETWORK_ID, "dst-reservation"));
        NetworkGuru guru = addGuru(true);
        NetworkMigrationResponder guruResponder = (NetworkMigrationResponder) guru;
        NetworkElement element = addElement(Provider.VirtualRouter, true);
        NetworkMigrationResponder elementResponder = (NetworkMigrationResponder) element;
        NicVO persistedNic = mock(NicVO.class);
        stubNetworkLookups(src, network, Collections.singletonList(Provider.VirtualRouter));
        when(service.nicDao.findById(9L)).thenReturn(persistedNic);

        service.commitNicForMigration(src, dst);

        NicProfile nicSrc = src.getNics().get(0);
        verify(guruResponder).commitMigration(eq(nicSrc), eq(network), eq(src), any(ReservationContext.class), any(ReservationContext.class));
        verify(elementResponder).commitMigration(eq(nicSrc), eq(network), eq(src), any(ReservationContext.class), any(ReservationContext.class));
        verify(service.userVmManager).setupVmForPvlan(eq(true), eq(HOST_ID), eq(nicSrc));
        verify(service.nicDao).findById(9L);
        verify(persistedNic).setReservationId("dst-reservation");
        verify(service.nicDao).persist(persistedNic);
    }

    @Test
    public void testCommitNicForMigrationSkipsNonResponderGuruAndElementButPersistsReservation() {
        NetworkVO network = newNetwork(NETWORK_ID, GuestType.Shared, TrafficType.Guest);
        VirtualMachineProfile src = newVmWithNics(Type.User, HypervisorType.KVM, HOST_ID, nicProfile(9L, NETWORK_ID, "src-reservation"));
        VirtualMachineProfile dst = newVmWithNics(Type.User, HypervisorType.KVM, DST_HOST_ID, nicProfile(9L, NETWORK_ID, "dst-reservation"));
        NetworkGuru guru = addGuru(false);
        NetworkElement element = addElement(Provider.VirtualRouter, false);
        NicVO persistedNic = mock(NicVO.class);
        stubNetworkLookups(src, network, Collections.singletonList(Provider.VirtualRouter));
        when(service.nicDao.findById(9L)).thenReturn(persistedNic);

        service.commitNicForMigration(src, dst);

        verify(guru, never()).release(any(NicProfile.class), any(VirtualMachineProfile.class), any(String.class));
        verify(element, times(2)).getProvider();
        verify(persistedNic).setReservationId("dst-reservation");
        verify(service.nicDao).persist(persistedNic);
    }

    @Test
    public void testRollbackNicForMigrationCallsRespondersAndPvlan() {
        NetworkVO network = newNetwork(NETWORK_ID, GuestType.L2, TrafficType.Guest);
        VirtualMachineProfile src = newVmWithNics(Type.User, HypervisorType.KVM, HOST_ID, nicProfile(9L, NETWORK_ID, "src-reservation"));
        VirtualMachineProfile dst = newVmWithNics(Type.User, HypervisorType.KVM, DST_HOST_ID, nicProfile(9L, NETWORK_ID, "dst-reservation"));
        NetworkGuru guru = addGuru(true);
        NetworkMigrationResponder guruResponder = (NetworkMigrationResponder) guru;
        NetworkElement element = addElement(Provider.VirtualRouter, true);
        NetworkMigrationResponder elementResponder = (NetworkMigrationResponder) element;
        stubNetworkLookups(dst, network, Collections.singletonList(Provider.VirtualRouter));

        service.rollbackNicForMigration(src, dst);

        NicProfile nicDst = dst.getNics().get(0);
        verify(guruResponder).rollbackMigration(eq(nicDst), eq(network), eq(dst), any(ReservationContext.class), any(ReservationContext.class));
        verify(elementResponder).rollbackMigration(eq(nicDst), eq(network), eq(dst), any(ReservationContext.class), any(ReservationContext.class));
        verify(service.userVmManager).setupVmForPvlan(eq(true), eq(DST_HOST_ID), eq(nicDst));
    }

    private VirtualMachineProfile newVm(Type type, HypervisorType hypervisorType, Long hostId) {
        VirtualMachineProfile vm = mock(VirtualMachineProfile.class);
        VirtualMachine virtualMachine = mock(VirtualMachine.class);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getType()).thenReturn(type);
        when(vm.getHypervisorType()).thenReturn(hypervisorType);
        when(vm.getVirtualMachine()).thenReturn(virtualMachine);
        when(virtualMachine.getHostId()).thenReturn(hostId);
        return vm;
    }

    private VirtualMachineProfile newVmWithNics(Type type, HypervisorType hypervisorType, Long hostId, NicProfile... nics) {
        VirtualMachineProfile vm = newVm(type, hypervisorType, hostId);
        when(vm.getNics()).thenReturn(Arrays.asList(nics));
        return vm;
    }

    private NetworkVO newNetwork(long id, GuestType guestType, TrafficType trafficType) {
        NetworkVO network = mock(NetworkVO.class);
        when(network.getId()).thenReturn(id);
        when(network.getGuruName()).thenReturn(GURU_NAME);
        when(network.getGuestType()).thenReturn(guestType);
        when(network.getTrafficType()).thenReturn(trafficType);
        when(network.getPhysicalNetworkId()).thenReturn(PHYSICAL_NETWORK_ID);
        when(network.getMode()).thenReturn(Mode.Dhcp);
        when(network.getBroadcastDomainType()).thenReturn(BroadcastDomainType.Vlan);
        return network;
    }

    private NicVO newNic(long networkId) {
        NicVO nic = new NicVO(GURU_NAME, VM_ID, networkId, Type.User);
        nic.setAddressFormat(AddressFormat.Ip4);
        nic.setBroadcastUri(URI.create("vlan://" + networkId));
        nic.setIsolationUri(IsolationType.Vlan.toUri(String.valueOf(networkId)));
        nic.setIPv4Address("10.0.0.10");
        nic.setIPv4Gateway("10.0.0.1");
        nic.setIPv4Netmask("255.255.255.0");
        nic.setMacAddress("02:00:00:00:00:01");
        nic.setDeviceId(1);
        nic.setReservationId("reservation-" + networkId);
        nic.setReservationStrategy(Nic.ReservationStrategy.Start);
        return nic;
    }

    private NicProfile nicProfile(long id, long networkId, String reservationId) {
        NicProfile profile = new NicProfile();
        profile.setId(id);
        profile.setNetworkId(networkId);
        profile.setReservationId(reservationId);
        return profile;
    }

    private NetworkGuru addGuru(boolean responder) {
        NetworkGuru guru = responder ? mock(NetworkGuru.class, Mockito.withSettings().extraInterfaces(NetworkMigrationResponder.class)) : mock(NetworkGuru.class);
        when(guru.getName()).thenReturn(GURU_NAME);
        service.networkGurus.add(guru);
        return guru;
    }

    private NetworkElement addElement(Provider provider, boolean responder) {
        NetworkElement element = responder ? mock(NetworkElement.class, Mockito.withSettings().extraInterfaces(NetworkMigrationResponder.class)) : mock(NetworkElement.class);
        when(element.getProvider()).thenReturn(provider);
        when(element.getName()).thenReturn(provider.getName());
        service.networkElements.add(element);
        return element;
    }

    private void stubPrepareNetwork(VirtualMachineProfile vm, NetworkVO network, NicVO nic, List<Provider> providers) {
        when(service.nicDao.listByVmId(VM_ID)).thenReturn(Collections.singletonList(nic));
        stubNetworkLookups(vm, network, providers);
    }

    private void stubNetworkLookups(VirtualMachineProfile vm, NetworkVO network) {
        stubNetworkLookups(vm, network, Collections.emptyList());
    }

    private void stubNetworkLookups(VirtualMachineProfile vm, NetworkVO network, List<Provider> providers) {
        long networkId = network.getId();
        HypervisorType hypervisorType = vm.getHypervisorType();
        when(service.networksDao.findById(networkId)).thenReturn(network);
        when(service.networkModel.getNetworkRate(networkId, VM_ID)).thenReturn(networkId == PUBLIC_NETWORK_ID ? 200 : 100);
        when(service.networkModel.isSecurityGroupSupportedInNetwork(network)).thenReturn(false);
        when(service.networkModel.getNetworkTag(hypervisorType, network)).thenReturn("tag-" + networkId);
        when(service.networkModel.getPhysicalNetworkId(network)).thenReturn(PHYSICAL_NETWORK_ID);
        when(service.networkModel.isProviderEnabledInPhysicalNetwork(anyLong(), any(String.class))).thenReturn(true);
        when(service.networkServiceMapDao.getDistinctProviders(networkId)).thenReturn(providerNames(providers));
    }

    private List<String> providerNames(List<Provider> providers) {
        List<String> names = new ArrayList<>();
        for (Provider provider : providers) {
            names.add(provider.getName());
        }
        return names;
    }

    private IPAddressVO newPublicIp(String address) {
        IPAddressVO ip = new IPAddressVO(new Ip(address), 1L, 1234L, VLAN_ID, false);
        ip.setAssociatedWithNetworkId(NETWORK_ID);
        return ip;
    }

    private VlanVO newVlan() {
        return new VlanVO(Vlan.VlanType.VirtualNetwork, VLAN_TAG, "203.0.113.1", "255.255.255.0", 1L, "203.0.113.2-203.0.113.254", PUBLIC_NETWORK_ID,
                PHYSICAL_NETWORK_ID, null, null, null);
    }

    private NicProfile findDummyProfile(List<NicProfile> profiles) {
        for (NicProfile profile : profiles) {
            if (Integer.valueOf(255).equals(profile.getDeviceId())) {
                return profile;
            }
        }
        return null;
    }
}
