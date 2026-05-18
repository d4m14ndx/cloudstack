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
package com.cloud.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.cloudstack.alert.AlertService;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.impl.ConfigDepotImpl;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.to.IpAddressTO;
import com.cloud.agent.manager.Commands;
import com.cloud.alert.AlertManager;
import com.cloud.api.query.dao.DomainRouterJoinDao;
import com.cloud.api.query.vo.DomainRouterJoinVO;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.VlanDao;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.router.CommandSetupHelper;
import com.cloud.network.router.NetworkHelper;
import com.cloud.network.router.VirtualRouter;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;

@RunWith(MockitoJUnitRunner.class)
public class NetworkMtuServiceImplTest {

    private static final Long ZONE_ID = 1L;
    private static final long NETWORK_ID = 10L;
    private static final long ROUTER_ID = 20L;
    private static final long VLAN_ID = 30L;

    @Mock
    private VpcDao _vpcDao;
    @Mock
    private AlertManager alertManager;
    @Mock
    private DomainRouterDao routerDao;
    @Mock
    private DomainRouterJoinDao routerJoinDao;
    @Mock
    private IPAddressDao _ipAddressDao;
    @Mock
    private VlanDao _vlanDao;
    @Mock
    private NicDao _nicDao;
    @Mock
    private NetworkDao _networksDao;
    @Mock
    private CommandSetupHelper commandSetupHelper;
    @Mock
    private NetworkHelper networkHelper;
    @Mock
    private ConfigDepotImpl configDepot;

    @InjectMocks
    private NetworkMtuServiceImpl service;

    private final Map<String, String> zoneConfigValues = new HashMap<>();

    @Before
    public void setup() throws Exception {
        zoneConfigValues.clear();
        ConfigKey.init(configDepot);
        Mockito.lenient().when(configDepot.getConfigStringValue(anyString(), nullable(ConfigKey.Scope.class), nullable(Long.class)))
                .thenAnswer(invocation -> zoneConfigValues.get(invocation.getArgument(0)));
        stubZoneConfig(NetworkService.AllowUsersToSpecifyVRMtu, "true");
        stubZoneConfig(NetworkService.VRPublicInterfaceMtu, String.valueOf(NetworkService.DEFAULT_MTU));
        stubZoneConfig(NetworkService.VRPrivateInterfaceMtu, String.valueOf(NetworkService.DEFAULT_MTU));
    }

    private void stubZoneConfig(ConfigKey configKey, String value) {
        zoneConfigValues.put(configKey.key(), value);
    }

    private NetworkVO networkWithVpc(Long vpcId) {
        NetworkVO network = Mockito.mock(NetworkVO.class);
        when(network.getVpcId()).thenReturn(vpcId);
        return network;
    }

    @Test
    public void validateMtuConfigUsesZoneDefaultsWhenUserMtuDisabled() throws Exception {
        stubZoneConfig(NetworkService.AllowUsersToSpecifyVRMtu, "false");

        Pair<Integer, Integer> interfaceMtus = service.validateMtuConfig(1200, 1000, ZONE_ID);

        assertEquals(NetworkService.DEFAULT_MTU, interfaceMtus.first());
        assertEquals(NetworkService.DEFAULT_MTU, interfaceMtus.second());
        verifyNoInteractions(alertManager);
    }

    @Test
    public void validateMtuConfigClampsPublicAboveMaxAndSendsPublicAlert() {
        Pair<Integer, Integer> interfaceMtus = service.validateMtuConfig(1600, 1400, ZONE_ID);

        assertEquals(NetworkService.DEFAULT_MTU, interfaceMtus.first());
        assertEquals(Integer.valueOf(1400), interfaceMtus.second());
        verify(alertManager).sendAlert(eq(AlertService.AlertType.ALERT_TYPE_VR_PUBLIC_IFACE_MTU), anyLong(), nullable(Long.class), anyString(), anyString());
    }

    @Test
    public void validateMtuConfigClampsPublicBelowMinimumAndSendsPublicAlert() {
        Pair<Integer, Integer> interfaceMtus = service.validateMtuConfig(40, 1400, ZONE_ID);

        assertEquals(NetworkService.MINIMUM_MTU, interfaceMtus.first());
        assertEquals(Integer.valueOf(1400), interfaceMtus.second());
        verify(alertManager).sendAlert(eq(AlertService.AlertType.ALERT_TYPE_VR_PUBLIC_IFACE_MTU), anyLong(), nullable(Long.class), anyString(), anyString());
    }

    @Test
    public void validateMtuConfigClampsPrivateAboveMaxAndSendsPrivateAlert() {
        Pair<Integer, Integer> interfaceMtus = service.validateMtuConfig(1400, 1600, ZONE_ID);

        assertEquals(Integer.valueOf(1400), interfaceMtus.first());
        assertEquals(NetworkService.DEFAULT_MTU, interfaceMtus.second());
        verify(alertManager).sendAlert(eq(AlertService.AlertType.ALERT_TYPE_VR_PRIVATE_IFACE_MTU), anyLong(), nullable(Long.class), anyString(), anyString());
    }

    @Test
    public void validateMtuConfigClampsPrivateBelowMinimumAndSendsPrivateAlert() {
        Pair<Integer, Integer> interfaceMtus = service.validateMtuConfig(1400, 40, ZONE_ID);

        assertEquals(Integer.valueOf(1400), interfaceMtus.first());
        assertEquals(NetworkService.MINIMUM_MTU, interfaceMtus.second());
        verify(alertManager).sendAlert(eq(AlertService.AlertType.ALERT_TYPE_VR_PRIVATE_IFACE_MTU), anyLong(), nullable(Long.class), anyString(), anyString());
    }

    @Test(expected = CloudRuntimeException.class)
    public void mtuCheckForVpcNetworkThrowsWhenVpcIsMissing() {
        when(_vpcDao.findById(5L)).thenReturn(null);

        service.mtuCheckForVpcNetwork(5L, new Pair<>(1400, 1300), 1400);
    }

    @Test
    public void mtuCheckForVpcNetworkReplacesPublicMtuWithVpcPublicMtu() {
        VpcVO vpc = Mockito.mock(VpcVO.class);
        when(vpc.getPublicMtu()).thenReturn(1450);
        when(_vpcDao.findById(5L)).thenReturn(vpc);
        Pair<Integer, Integer> interfaceMtus = new Pair<>(1400, 1300);

        service.mtuCheckForVpcNetwork(5L, interfaceMtus, 1400);

        assertEquals(Integer.valueOf(1450), interfaceMtus.first());
        assertEquals(Integer.valueOf(1300), interfaceMtus.second());
    }

    @Test
    public void validateMtuOnUpdateReturnsNullPairWhenUserMtuDisabled() throws Exception {
        stubZoneConfig(NetworkService.AllowUsersToSpecifyVRMtu, "false");

        Pair<Integer, Integer> interfaceMtus = service.validateMtuOnUpdate(networkWithVpc(null), ZONE_ID, 1400, 1300);

        assertNull(interfaceMtus.first());
        assertNull(interfaceMtus.second());
    }

    @Test
    public void validateMtuOnUpdateClampsPublicAndPrivateAboveZoneMaximum() {
        Pair<Integer, Integer> interfaceMtus = service.validateMtuOnUpdate(networkWithVpc(null), ZONE_ID, 1600, 1700);

        assertEquals(NetworkService.DEFAULT_MTU, interfaceMtus.first());
        assertEquals(NetworkService.DEFAULT_MTU, interfaceMtus.second());
        verifyNoInteractions(alertManager);
    }

    @Test
    public void validateMtuOnUpdateClampsPublicBelowMinimumAndSendsPublicAlert() {
        Pair<Integer, Integer> interfaceMtus = service.validateMtuOnUpdate(networkWithVpc(null), ZONE_ID, 40, 1300);

        assertEquals(NetworkService.MINIMUM_MTU, interfaceMtus.first());
        assertEquals(Integer.valueOf(1300), interfaceMtus.second());
        verify(alertManager).sendAlert(eq(AlertService.AlertType.ALERT_TYPE_VR_PUBLIC_IFACE_MTU), anyLong(), nullable(Long.class), anyString(), anyString());
    }

    @Test
    public void validateMtuOnUpdateClampsPrivateBelowMinimumAndSendsPrivateAlert() {
        Pair<Integer, Integer> interfaceMtus = service.validateMtuOnUpdate(networkWithVpc(null), ZONE_ID, 1400, 40);

        assertEquals(Integer.valueOf(1400), interfaceMtus.first());
        assertEquals(NetworkService.MINIMUM_MTU, interfaceMtus.second());
        verify(alertManager).sendAlert(eq(AlertService.AlertType.ALERT_TYPE_VR_PRIVATE_IFACE_MTU), anyLong(), nullable(Long.class), anyString(), anyString());
    }

    @Test
    public void validateMtuOnUpdateSkipsPublicMtuForVpcNetwork() {
        NetworkVO network = networkWithVpc(5L);

        Pair<Integer, Integer> interfaceMtus = service.validateMtuOnUpdate(network, ZONE_ID, 1400, 1300);

        assertNull(interfaceMtus.first());
        assertEquals(Integer.valueOf(1300), interfaceMtus.second());
    }

    @Test
    public void updateNetworkMtuDoesNothingWhenNoRouterIpsNeedMtuUpdates() {
        NetworkVO network = networkWithVpc(null);
        when(routerDao.findByNetwork(NETWORK_ID)).thenReturn(List.of());

        service.updateNetworkMtu(network, NETWORK_ID, ZONE_ID, 1400, 1300, false);

        verify(routerDao).findByNetwork(NETWORK_ID);
        verifyNoInteractions(routerJoinDao, networkHelper, _networksDao);
    }

    @Test
    public void updateNetworkMtuSkipsRouterCommandWhenRestartNetworkIsTrue() {
        NetworkVO network = networkWithVpc(null);
        DomainRouterVO router = router(ROUTER_ID);
        DomainRouterJoinVO guestJoin = routerJoin(TrafficType.Guest, "10.0.0.2", "255.255.255.0");
        when(routerDao.findByNetwork(NETWORK_ID)).thenReturn(List.of(router));
        when(routerJoinDao.getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Guest, TrafficType.Public)).thenReturn(List.of(guestJoin));

        service.updateNetworkMtu(network, NETWORK_ID, ZONE_ID, 1400, 1300, true);

        verifyNoInteractions(commandSetupHelper, networkHelper, _networksDao);
    }

    @Test
    public void updateNetworkMtuBuildsGuestAndPublicTargetsAndPersistsNetworkDetails() {
        Integer publicMtu = 1400;
        Integer privateMtu = 1300;
        NetworkVO network = networkWithVpc(null);
        DomainRouterVO router = router(ROUTER_ID);
        DomainRouterJoinVO guestJoin = routerJoin(TrafficType.Guest, "10.0.0.2", "255.255.255.0");
        DomainRouterJoinVO publicJoin = routerJoin(TrafficType.Public, "198.51.100.9", "255.255.255.0");
        IPAddressVO isolatedPublicIp = Mockito.mock(IPAddressVO.class);
        VlanVO vlan = Mockito.mock(VlanVO.class);
        NicVO guestNic = nic(101L);
        NicVO publicNic = nic(102L);
        NicVO isolatedPublicNic = nic(103L);
        when(network.getId()).thenReturn(NETWORK_ID);
        when(network.getGuestType()).thenReturn(GuestType.Isolated);
        when(router.getRedundantState()).thenReturn(VirtualRouter.RedundantState.PRIMARY);
        when(routerDao.findByNetwork(NETWORK_ID)).thenReturn(List.of(router));
        when(routerDao.findById(ROUTER_ID)).thenReturn(router);
        when(routerJoinDao.getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Guest, TrafficType.Public)).thenReturn(Arrays.asList(guestJoin, publicJoin));
        when(_ipAddressDao.listByNetworkId(NETWORK_ID)).thenReturn(List.of(isolatedPublicIp));
        when(isolatedPublicIp.getAddress()).thenReturn(new Ip("203.0.113.10"));
        when(isolatedPublicIp.getVlanId()).thenReturn(VLAN_ID);
        when(_vlanDao.findById(VLAN_ID)).thenReturn(vlan);
        when(vlan.getVlanNetmask()).thenReturn("255.255.255.0");
        when(_nicDao.findByInstanceIdAndIpAddressAndVmtype(ROUTER_ID, "10.0.0.2", VirtualMachine.Type.DomainRouter)).thenReturn(guestNic);
        when(_nicDao.findByInstanceIdAndIpAddressAndVmtype(ROUTER_ID, "198.51.100.9", VirtualMachine.Type.DomainRouter)).thenReturn(publicNic);
        when(_nicDao.findByInstanceIdAndIpAddressAndVmtype(ROUTER_ID, "203.0.113.10", VirtualMachine.Type.DomainRouter)).thenReturn(isolatedPublicNic);
        stubUpdateNetworkCommandAnswer(true);
        ArgumentCaptor<Set<IpAddressTO>> ipsCaptor = ArgumentCaptor.forClass(Set.class);

        service.updateNetworkMtu(network, NETWORK_ID, ZONE_ID, publicMtu, privateMtu, false);

        verify(commandSetupHelper).setupUpdateNetworkCommands(eq(router), ipsCaptor.capture(), any(Commands.class));
        Set<IpAddressTO> sentIps = ipsCaptor.getValue();
        assertEquals(3, sentIps.size());
        assertTarget(sentIps, "10.0.0.2", privateMtu, TrafficType.Guest);
        assertTarget(sentIps, "198.51.100.9", publicMtu, TrafficType.Public);
        assertTarget(sentIps, "203.0.113.10", publicMtu, null);
        assertEquals(VirtualRouter.RedundantState.PRIMARY.name(), target(sentIps, "10.0.0.2").getDetails().get(ApiConstants.REDUNDANT_STATE));
        verify(guestNic).setMtu(privateMtu);
        verify(publicNic).setMtu(publicMtu);
        verify(isolatedPublicNic).setMtu(publicMtu);
        verify(_nicDao).update(101L, guestNic);
        verify(_nicDao).update(102L, publicNic);
        verify(_nicDao).update(103L, isolatedPublicNic);
        verify(network).setPublicMtu(publicMtu);
        verify(network).setPrivateMtu(privateMtu);
        verify(_networksDao).update(NETWORK_ID, network);
    }

    @Test(expected = CloudRuntimeException.class)
    public void updateNetworkMtuThrowsWhenRouterCommandUpdateFails() {
        NetworkVO network = networkWithVpc(null);
        DomainRouterVO router = router(ROUTER_ID);
        DomainRouterJoinVO guestJoin = routerJoin(TrafficType.Guest, "10.0.0.2", "255.255.255.0");
        when(routerDao.findByNetwork(NETWORK_ID)).thenReturn(List.of(router));
        when(routerDao.findById(ROUTER_ID)).thenReturn(router);
        when(routerJoinDao.getRouterByIdAndTrafficType(ROUTER_ID, TrafficType.Guest, TrafficType.Public)).thenReturn(List.of(guestJoin));
        stubUpdateNetworkCommandAnswer(false);

        service.updateNetworkMtu(network, NETWORK_ID, ZONE_ID, 1400, 1300, false);
    }

    @Test
    public void updateMtuOnVrReturnsFalseWhenRouterCommandIsUnavailable() throws Exception {
        DomainRouterVO router = router(ROUTER_ID);
        Set<IpAddressTO> ips = new HashSet<>();
        ips.add(new IpAddressTO("10.0.0.2", 1300, "255.255.255.0"));
        Map<Long, Set<IpAddressTO>> routerToIps = new HashMap<>();
        routerToIps.put(ROUTER_ID, ips);
        when(router.getRedundantState()).thenReturn(VirtualRouter.RedundantState.BACKUP);
        when(routerDao.findById(ROUTER_ID)).thenReturn(router);
        stubUpdateNetworkCommandAnswer(true);
        doThrow(new ResourceUnavailableException("offline", Network.class, NETWORK_ID)).when(networkHelper).sendCommandsToRouter(any(), any());

        assertFalse(service.updateMtuOnVr(routerToIps));
        assertEquals(VirtualRouter.RedundantState.BACKUP.name(), ips.iterator().next().getDetails().get(ApiConstants.REDUNDANT_STATE));
    }

    @Test
    public void updateMtuOnVrSkipsMissingRouterAndReturnsFalse() {
        Map<Long, Set<IpAddressTO>> routerToIps = new HashMap<>();
        routerToIps.put(ROUTER_ID, Set.of(new IpAddressTO("10.0.0.2", 1300, "255.255.255.0")));
        when(routerDao.findById(ROUTER_ID)).thenReturn(null);

        assertFalse(service.updateMtuOnVr(routerToIps));
        verifyNoInteractions(commandSetupHelper, networkHelper);
    }

    private DomainRouterVO router(long routerId) {
        DomainRouterVO router = Mockito.mock(DomainRouterVO.class);
        when(router.getId()).thenReturn(routerId);
        return router;
    }

    private DomainRouterJoinVO routerJoin(TrafficType trafficType, String ipAddress, String netmask) {
        DomainRouterJoinVO routerJoin = Mockito.mock(DomainRouterJoinVO.class);
        when(routerJoin.getTrafficType()).thenReturn(trafficType);
        when(routerJoin.getIpAddress()).thenReturn(ipAddress);
        when(routerJoin.getNetmask()).thenReturn(netmask);
        return routerJoin;
    }

    private NicVO nic(long nicId) {
        NicVO nic = Mockito.mock(NicVO.class);
        when(nic.getId()).thenReturn(nicId);
        return nic;
    }

    private void stubUpdateNetworkCommandAnswer(boolean result) {
        doAnswer(invocation -> {
            Commands cmds = invocation.getArgument(2);
            cmds.addCommand("updateNetwork", Mockito.mock(Command.class));
            cmds.setAnswers(new Answer[] {new Answer(null, result, result ? null : "failed")});
            return null;
        }).when(commandSetupHelper).setupUpdateNetworkCommands(any(), any(), any());
    }

    private void assertTarget(Set<IpAddressTO> targets, String ipAddress, Integer mtu, TrafficType trafficType) {
        IpAddressTO target = target(targets, ipAddress);
        assertNotNull(target);
        assertEquals(mtu, target.getMtu());
        assertSame(trafficType, target.getTrafficType());
    }

    private IpAddressTO target(Set<IpAddressTO> targets, String ipAddress) {
        return targets.stream()
                .filter(target -> ipAddress.equals(target.getPublicIp()))
                .findFirst()
                .orElse(null);
    }
}
