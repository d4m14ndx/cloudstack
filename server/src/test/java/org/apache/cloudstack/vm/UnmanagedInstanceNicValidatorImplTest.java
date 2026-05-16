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
package org.apache.cloudstack.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.apache.cloudstack.api.ServerApiException;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.DataCenter;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkVO;
import com.cloud.user.Account;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.NicVO;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Focused tests for {@link UnmanagedInstanceNicValidatorImpl} — the
 * Phase 4 extraction of NIC / network pre-flight validation out of
 * {@link UnmanagedVMsManagerImpl}.
 *
 * <p>The behaviour is also exercised indirectly through the manager's
 * delegating wrappers; these tests target the service directly so
 * future refactors of the manager don't drop coverage of these edge
 * cases.
 */
@RunWith(MockitoJUnitRunner.class)
public class UnmanagedInstanceNicValidatorImplTest {

    @Mock
    private NetworkModel networkModel;
    @Mock
    private VMInstanceDao vmDao;
    @Mock
    private NicDao nicDao;

    @InjectMocks
    private UnmanagedInstanceNicValidatorImpl validator;

    private static final String INSTANCE_NAME = "TestInstance";
    private static final String NIC_ID = "NIC-1";
    private static final String NETWORK_UUID = "net-uuid";
    private static final long NETWORK_ID = 100L;
    private static final long ZONE_ID = 1L;
    private static final String HOST_NAME = "host-1";

    private UnmanagedInstanceTO.Nic nic;
    private Network network;
    private DataCenter zone;
    private Account owner;

    @Before
    public void setUp() throws Exception {
        nic = new UnmanagedInstanceTO.Nic();
        nic.setNicId(NIC_ID);
        nic.setAdapterType("VirtualE1000E");
        nic.setMacAddress("02:00:2e:0f:00:02");

        network = mock(Network.class);
        when(network.getUuid()).thenReturn(NETWORK_UUID);
        when(network.getId()).thenReturn(NETWORK_ID);
        when(network.getDataCenterId()).thenReturn(ZONE_ID);
        when(network.getGuestType()).thenReturn(Network.GuestType.Shared);

        zone = mock(DataCenter.class);
        when(zone.getId()).thenReturn(ZONE_ID);

        owner = mock(Account.class);
    }

    // basicNetworkChecks -----------------------------------------------------

    @Test
    public void testBasicNetworkChecksThrowsWhenNicIsNull() {
        ServerApiException e = assertThrows(ServerApiException.class,
                () -> validator.basicNetworkChecks(INSTANCE_NAME, null, network));
        assertEquals(true, e.getMessage().contains(INSTANCE_NAME));
    }

    @Test
    public void testBasicNetworkChecksThrowsWhenNetworkIsNull() {
        ServerApiException e = assertThrows(ServerApiException.class,
                () -> validator.basicNetworkChecks(INSTANCE_NAME, nic, null));
        assertEquals(true, e.getMessage().contains(NIC_ID));
    }

    @Test
    public void testBasicNetworkChecksPassesWhenBothNonNull() {
        validator.basicNetworkChecks(INSTANCE_NAME, nic, network);
    }

    // checksOnlyNeededForVmware ---------------------------------------------

    @Test
    public void testChecksOnlyNeededForVmwareIsNoOpForKvm() {
        nic.setVlan(1024);
        validator.checksOnlyNeededForVmware(nic, network, Hypervisor.HypervisorType.KVM);
    }

    @Test
    public void testChecksOnlyNeededForVmwareMatchingVlanPasses() throws Exception {
        nic.setVlan(1024);
        when(network.getBroadcastUri()).thenReturn(new URI("vlan://1024"));
        validator.checksOnlyNeededForVmware(nic, network, Hypervisor.HypervisorType.VMware);
    }

    @Test
    public void testChecksOnlyNeededForVmwareMismatchedVlanThrows() throws Exception {
        nic.setVlan(1024);
        when(network.getBroadcastUri()).thenReturn(new URI("vlan://4001"));
        assertThrows(ServerApiException.class,
                () -> validator.checksOnlyNeededForVmware(nic, network, Hypervisor.HypervisorType.VMware));
    }

    @Test
    public void testChecksOnlyNeededForVmwareMatchingPvlanPasses() throws Exception {
        nic.setVlan(1024);
        nic.setPvlan(1025);
        nic.setPvlanType("Isolated");
        when(network.getBroadcastUri()).thenReturn(new URI("pvlan://1024-i1025"));
        validator.checksOnlyNeededForVmware(nic, network, Hypervisor.HypervisorType.VMware);
    }

    @Test
    public void testChecksOnlyNeededForVmwareMismatchedPvlanThrows() throws Exception {
        nic.setVlan(1024);
        nic.setPvlan(1025);
        nic.setPvlanType("Isolated");
        when(network.getBroadcastUri()).thenReturn(new URI("pvlan://1024-i9999"));
        assertThrows(ServerApiException.class,
                () -> validator.checksOnlyNeededForVmware(nic, network, Hypervisor.HypervisorType.VMware));
    }

    @Test
    public void testChecksOnlyNeededForVmwareZeroVlanIsSkipped() {
        nic.setVlan(0);
        validator.checksOnlyNeededForVmware(nic, network, Hypervisor.HypervisorType.VMware);
    }

    // checkUnmanagedNicAndNetworkForImport -----------------------------------

    @Test
    public void testCheckUnmanagedNicAndNetworkForImportRejectsCrossZoneNetwork() {
        when(network.getDataCenterId()).thenReturn(99L);
        assertThrows(ServerApiException.class,
                () -> validator.checkUnmanagedNicAndNetworkForImport(INSTANCE_NAME, nic, network, zone, owner, false, Hypervisor.HypervisorType.VMware));
    }

    @Test
    public void testCheckUnmanagedNicAndNetworkForImportShortCircuitsForNonAutoIsolated() {
        when(network.getGuestType()).thenReturn(Network.GuestType.Isolated);
        doNothing().when(networkModel).checkNetworkPermissions(any(Account.class), any(Network.class));
        // Even with VMware and mismatched VLAN, a non-autoAssign Isolated network should short-circuit out
        nic.setVlan(1024);
        validator.checkUnmanagedNicAndNetworkForImport(INSTANCE_NAME, nic, network, zone, owner, false, Hypervisor.HypervisorType.VMware);
    }

    @Test
    public void testCheckUnmanagedNicAndNetworkForImportRunsVmwareChecksForAutoAssignIsolated() throws Exception {
        when(network.getBroadcastUri()).thenReturn(new URI("vlan://4001"));
        doNothing().when(networkModel).checkNetworkPermissions(any(Account.class), any(Network.class));
        nic.setVlan(1024);
        assertThrows(ServerApiException.class,
                () -> validator.checkUnmanagedNicAndNetworkForImport(INSTANCE_NAME, nic, network, zone, owner, true, Hypervisor.HypervisorType.VMware));
    }

    @Test
    public void testCheckUnmanagedNicAndNetworkForImportSucceedsForKvmSharedNetwork() {
        when(network.getGuestType()).thenReturn(Network.GuestType.Shared);
        doNothing().when(networkModel).checkNetworkPermissions(any(Account.class), any(Network.class));
        validator.checkUnmanagedNicAndNetworkForImport(INSTANCE_NAME, nic, network, zone, owner, false, Hypervisor.HypervisorType.KVM);
        verify(networkModel).checkNetworkPermissions(owner, network);
    }

    // checkUnmanagedNicAndNetworkHostnameForImport ---------------------------

    @Test
    public void testHostnameCheckRejectsDuplicateHostnameInNetwork() {
        when(vmDao.listDistinctHostNames(NETWORK_ID)).thenReturn(List.of(HOST_NAME, "other"));
        assertThrows(InvalidParameterValueException.class,
                () -> validator.checkUnmanagedNicAndNetworkHostnameForImport(INSTANCE_NAME, nic, network, HOST_NAME));
    }

    @Test
    public void testHostnameCheckPassesWhenNoDuplicate() {
        when(vmDao.listDistinctHostNames(NETWORK_ID)).thenReturn(Collections.singletonList("other"));
        validator.checkUnmanagedNicAndNetworkHostnameForImport(INSTANCE_NAME, nic, network, HOST_NAME);
    }

    @Test
    public void testHostnameCheckPassesWhenNetworkHasNoHostnames() {
        when(vmDao.listDistinctHostNames(NETWORK_ID)).thenReturn(Collections.emptyList());
        validator.checkUnmanagedNicAndNetworkHostnameForImport(INSTANCE_NAME, nic, network, HOST_NAME);
    }

    // checkUnmanagedNicIpAndNetworkForImport ---------------------------------

    @Test
    public void testIpCheckSkipsForL2Networks() {
        when(network.getGuestType()).thenReturn(Network.GuestType.L2);
        validator.checkUnmanagedNicIpAndNetworkForImport(INSTANCE_NAME, nic, network, null);
        verify(networkModel, never()).getAvailableIps(any(Network.class), anyString());
    }

    @Test
    public void testIpCheckRejectsMissingIpForNonL2() {
        when(network.getGuestType()).thenReturn(Network.GuestType.Shared);
        assertThrows(ServerApiException.class,
                () -> validator.checkUnmanagedNicIpAndNetworkForImport(INSTANCE_NAME, nic, network, null));
    }

    @Test
    public void testIpCheckRejectsEmptyIp4ForNonL2() {
        when(network.getGuestType()).thenReturn(Network.GuestType.Shared);
        Network.IpAddresses ipAddresses = new Network.IpAddresses("", "");
        assertThrows(ServerApiException.class,
                () -> validator.checkUnmanagedNicIpAndNetworkForImport(INSTANCE_NAME, nic, network, ipAddresses));
    }

    @Test
    public void testIpCheckAcceptsAutoSentinel() {
        when(network.getGuestType()).thenReturn(Network.GuestType.Shared);
        Network.IpAddresses ipAddresses = new Network.IpAddresses("auto", "");
        validator.checkUnmanagedNicIpAndNetworkForImport(INSTANCE_NAME, nic, network, ipAddresses);
        verify(networkModel, never()).getAvailableIps(any(Network.class), anyString());
    }

    @Test
    public void testIpCheckRejectsUnavailableIp() {
        when(network.getGuestType()).thenReturn(Network.GuestType.Shared);
        Network.IpAddresses ipAddresses = new Network.IpAddresses("10.0.0.5", "");
        when(networkModel.getAvailableIps(network, "10.0.0.5")).thenReturn(Collections.emptySet());
        assertThrows(ServerApiException.class,
                () -> validator.checkUnmanagedNicIpAndNetworkForImport(INSTANCE_NAME, nic, network, ipAddresses));
    }

    @Test
    public void testIpCheckAcceptsAvailableIp() {
        when(network.getGuestType()).thenReturn(Network.GuestType.Shared);
        Network.IpAddresses ipAddresses = new Network.IpAddresses("10.0.0.5", "");
        Set<Long> available = new HashSet<>();
        available.add(NetUtils.ip2Long("10.0.0.5"));
        when(networkModel.getAvailableIps(network, "10.0.0.5")).thenReturn(available);
        validator.checkUnmanagedNicIpAndNetworkForImport(INSTANCE_NAME, nic, network, ipAddresses);
    }

    // checkUnmanagedNicAndNetworkMacAddressForImport -------------------------

    @Test
    public void testMacCheckRejectsExistingMacWhenNotForced() {
        NetworkVO networkVO = mock(NetworkVO.class);
        when(networkVO.getId()).thenReturn(NETWORK_ID);
        when(nicDao.findByNetworkIdAndMacAddress(anyLong(), anyString())).thenReturn(mock(NicVO.class));
        assertThrows(CloudRuntimeException.class,
                () -> validator.checkUnmanagedNicAndNetworkMacAddressForImport(networkVO, nic, false));
    }

    @Test
    public void testMacCheckPassesWhenNoConflict() {
        NetworkVO networkVO = mock(NetworkVO.class);
        when(networkVO.getId()).thenReturn(NETWORK_ID);
        when(nicDao.findByNetworkIdAndMacAddress(anyLong(), anyString())).thenReturn(null);
        validator.checkUnmanagedNicAndNetworkMacAddressForImport(networkVO, nic, false);
    }

    @Test
    public void testMacCheckPassesWhenForcedFlagSet() {
        NetworkVO networkVO = mock(NetworkVO.class);
        when(networkVO.getId()).thenReturn(NETWORK_ID);
        when(nicDao.findByNetworkIdAndMacAddress(anyLong(), anyString())).thenReturn(mock(NicVO.class));
        validator.checkUnmanagedNicAndNetworkMacAddressForImport(networkVO, nic, true);
    }
}
