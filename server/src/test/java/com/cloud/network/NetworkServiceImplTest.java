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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.net.URI;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.cloudstack.api.command.admin.network.CreateNetworkCmdByAdmin;
import org.apache.cloudstack.api.command.user.network.CreateNetworkCmd;
import org.apache.cloudstack.api.command.user.network.UpdateNetworkCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.network.RoutedIpv4Manager;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.api.to.IpAddressTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.alert.AlertManager;
import com.cloud.bgp.BGPService;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.event.UsageEventUtils;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.nsx.NsxService;
import com.cloud.network.router.CommandSetupHelper;
import com.cloud.network.router.NetworkHelper;
import com.cloud.network.vpc.VpcManager;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.org.Grouping;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.Pair;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;

@RunWith(MockitoJUnitRunner.class)
public class NetworkServiceImplTest {
    @Mock
    Object job;
    @Mock
    Object _responseObject;

    @Mock
    AccountManager accountManager;
    @Mock
    NetworkOfferingDao networkOfferingDao;
    @Mock
    PhysicalNetworkDao physicalNetworkDao;
    @Mock
    DataCenterDao dataCenterDao;
    @Mock
    NetworkOrchestrationService networkOrchestrationService;
    @Mock
    Ipv6Service ipv6Service;
    @Mock
    NetworkModel networkModel;
    @Mock
    NetworkOfferingServiceMapDao networkOfferingServiceMapDao;
    @Mock
    EntityManager entityMgr;
    @Mock
    NetworkService networkService;
    @Mock
    VpcManager vpcMgr;
    @Mock
    NetworkOrchestrationService _networkMgr;
    @Mock
    AlertManager alertManager;
    @Mock
    DataCenterDao _dcDao;
    @Mock
    UserDao userDao;
    @Mock
    NetworkDao networkDao;
    @Mock
    NicDao nicDao;
    @Mock
    IPAddressDao ipAddressDao;
    @Mock
    ConfigurationManager configMgr;
    @Mock
    ConfigKey<Integer> publicMtuKey;
    @Mock
    ConfigKey<Boolean> userChangeMtuKey;
    @Mock
    VpcDao vpcDao;
    @Mock
    DomainRouterDao routerDao;
    @Mock
    AccountService _accountService;
    @Mock
    NetworkHelper networkHelper;
    @Mock
    ServiceOfferingDao serviceOfferingDaoMock;
    @Mock
    ServiceOfferingVO serviceOfferingVoMock;

    @Mock
    ConfigKey<Integer> privateMtuKey;
    @Mock
    private CallContext callContextMock;
    @InjectMocks
    CreateNetworkCmd createNetworkCmd = new CreateNetworkCmd();

    @InjectMocks
    UpdateNetworkCmd updateNetworkCmd = new UpdateNetworkCmd();
    @Mock
    CommandSetupHelper commandSetupHelper;
    @Mock
    private Account accountMock;

    @InjectMocks
    NetworkServiceImpl service;

    @Mock
    private IpAddressManager ipAddressManagerMock;

    @Mock
    private RoutedIpv4Manager routedIpv4Manager;

    @Mock
    BGPService bgpService;

    @Mock
    private NsxProviderDao nsxProviderDao;

    @Mock
    IpAddressLifecycleService ipAddressLifecycleService;

    @Mock
    PhysicalNetworkManagementService physicalNetworkManagementService;

    @Mock
    NetworkMtuService networkMtuService;

    private static final String VLAN_ID_900 = "900";
    private static final String VLAN_ID_901 = "901";
    private static final String VLAN_ID_902 = "902";
    public static final long ACCOUNT_ID = 1;

    private static final String IP4_GATEWAY = "10.0.16.1";
    private static final String IP4_NETMASK = "255.255.255.0";
    private static final String IP6_GATEWAY = "fd17:ac56:1234:2000::1";
    private static final String IP6_CIDR = "fd17:ac56:1234:2000::/64";
    final String[] ip4Dns = {"5.5.5.5", "6.6.6.6"};
    final String[] ip6Dns = {"2001:4860:4860::5555", "2001:4860:4860::6666"};

    private AccountVO account;
    private UserVO user;
    private NetworkOfferingVO offering;
    private DataCenterVO dc;
    private Network network;
    private  PhysicalNetworkVO phyNet;
    private VpcVO vpc;

    private MockedStatic<CallContext> callContextMocked;

    private AutoCloseable closeable;

    private NetworkOfferingVO networkOfferingVO;
    private Long zoneId = 10L;
    private Long networkId = 11L;

    private void registerCallContext() {
        account = new AccountVO("testaccount", 1L, "networkdomain", Account.Type.NORMAL, "uuid");
        account.setId(ACCOUNT_ID);
        user = new UserVO(1, "testuser", "password", "firstname", "lastName", "email", "timezone",
                UUID.randomUUID().toString(), User.Source.UNKNOWN);
        CallContext.register(user, account);
    }

    Class<InvalidParameterValueException> expectedException = InvalidParameterValueException.class;

    @Before
    public void setup() throws Exception {
        closeable = MockitoAnnotations.openMocks(this);
        overrideDefaultConfigValue(NetworkService.AllowUsersToSpecifyVRMtu, "_defaultValue", "true");
        offering = Mockito.mock(NetworkOfferingVO.class);
        network = Mockito.mock(Network.class);
        dc = Mockito.mock(DataCenterVO.class);
        phyNet = Mockito.mock(PhysicalNetworkVO.class);
        vpc = Mockito.mock(VpcVO.class);
        service._networkOfferingDao = networkOfferingDao;
        service._physicalNetworkDao = physicalNetworkDao;
        service._accountMgr = accountManager;
        service.alertManager = alertManager;
        service._configMgr = configMgr;
        service._vpcDao = vpcDao;
        service._vpcMgr = vpcMgr;
        service._networksDao = networkDao;
        service._nicDao = nicDao;
        service._ipAddressDao = ipAddressDao;
        service.routerDao = routerDao;
        service.commandSetupHelper = commandSetupHelper;
        service.networkHelper = networkHelper;
        service._ipAddrMgr = ipAddressManagerMock;
        service.nsxProviderDao = nsxProviderDao;
        service.ipAddressLifecycleService = ipAddressLifecycleService;
        service.networkMtuService = networkMtuService;
        service._accountService = _accountService;
        callContextMocked = Mockito.mockStatic(CallContext.class);
        CallContext callContextMock = Mockito.mock(CallContext.class);
        callContextMocked.when(CallContext::current).thenReturn(callContextMock);
        accountMock = Mockito.mock(Account.class);
        Mockito.when(service._accountMgr.finalizeOwner(any(Account.class), nullable(String.class), nullable(Long.class), nullable(Long.class))).thenReturn(accountMock);
        Mockito.when(callContextMock.getCallingAccount()).thenReturn(accountMock);
        NetworkOffering networkOffering = Mockito.mock(NetworkOffering.class);
        Mockito.when(entityMgr.findById(NetworkOffering.class, 1L)).thenReturn(networkOffering);
        Mockito.when(networkOfferingDao.findById(1L)).thenReturn(offering);
        Mockito.when(physicalNetworkDao.findById(Mockito.anyLong())).thenReturn(phyNet);
        Mockito.lenient().when(physicalNetworkManagementService.getPhysicalNetwork(Mockito.anyLong())).thenReturn(phyNet);
        Mockito.when(_dcDao.findById(Mockito.anyLong())).thenReturn(dc);
        Mockito.when(accountManager.isRootAdmin(accountMock.getId())).thenReturn(true);
        Mockito.lenient().when(networkMtuService.validateMtuConfig(nullable(Integer.class), nullable(Integer.class), anyLong()))
                .thenAnswer(invocation -> new Pair<>(invocation.getArgument(0), invocation.getArgument(1)));
        Mockito.lenient().doNothing().when(networkMtuService).mtuCheckForVpcNetwork(nullable(Long.class), Mockito.any(), nullable(Integer.class));
    }

    @After
    public void tearDown() throws Exception {
        callContextMocked.close();
        closeable.close();
    }

    private void overrideDefaultConfigValue(final ConfigKey configKey, final String name, final Object o) throws IllegalAccessException, NoSuchFieldException {
        Field depotField = ConfigKey.class.getDeclaredField("s_depot");
        depotField.setAccessible(true);
        depotField.set(null, null);
        Field f = ConfigKey.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(configKey, o);
        Field valueField = ConfigKey.class.getDeclaredField("_value");
        valueField.setAccessible(true);
        valueField.set(configKey, null);
    }

    @Test
    public void testGetPrivateVlanPairNoVlans() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(null, null, null);
        Assert.assertNull(pair.first());
        Assert.assertNull(pair.second());
    }

    @Test
    public void testGetPrivateVlanPairVlanPrimaryOnly() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(null, null, VLAN_ID_900);
        Assert.assertNull(pair.first());
        Assert.assertNull(pair.second());
    }

    @Test
    public void testGetPrivateVlanPairVlanPrimaryPromiscuousType() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(null, Network.PVlanType.Promiscuous.toString(), VLAN_ID_900);
        Assert.assertEquals(VLAN_ID_900, pair.first());
        Assert.assertEquals(Network.PVlanType.Promiscuous, pair.second());
    }

    @Test
    public void testGetPrivateVlanPairPromiscuousType() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(VLAN_ID_900, Network.PVlanType.Promiscuous.toString(), VLAN_ID_900);
        Assert.assertEquals(VLAN_ID_900, pair.first());
        Assert.assertEquals(Network.PVlanType.Promiscuous, pair.second());
    }

    @Test
    public void testGetPrivateVlanPairPromiscuousTypeOnSecondaryVlanId() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(VLAN_ID_900, "promiscuous", VLAN_ID_900);
        Assert.assertEquals(VLAN_ID_900, pair.first());
        Assert.assertEquals(Network.PVlanType.Promiscuous, pair.second());
    }

    @Test
    public void testGetPrivateVlanPairIsolatedType() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(VLAN_ID_901, Network.PVlanType.Isolated.toString(), VLAN_ID_900);
        Assert.assertEquals(VLAN_ID_901, pair.first());
        Assert.assertEquals(Network.PVlanType.Isolated, pair.second());
    }

    @Test
    public void testGetPrivateVlanPairIsolatedTypeOnSecondaryVlanId() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(VLAN_ID_901, "isolated", VLAN_ID_900);
        Assert.assertEquals(VLAN_ID_901, pair.first());
        Assert.assertEquals(Network.PVlanType.Isolated, pair.second());
    }

    @Test
    public void testGetPrivateVlanPairCommunityType() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(VLAN_ID_902, Network.PVlanType.Community.toString(), VLAN_ID_900);
        Assert.assertEquals(VLAN_ID_902, pair.first());
        Assert.assertEquals(Network.PVlanType.Community, pair.second());
    }

    @Test
    public void testGetPrivateVlanPairCommunityTypeOnSecondaryVlanId() {
        Pair<String, Network.PVlanType> pair = service.getPrivateVlanPair(VLAN_ID_902, "community", VLAN_ID_900);
        Assert.assertEquals(VLAN_ID_902, pair.first());
        Assert.assertEquals(Network.PVlanType.Community, pair.second());
    }

    @Test(expected = CloudRuntimeException.class)
    public void testPerformBasicChecksPromiscuousTypeExpectedIsolatedSet() {
        service.performBasicPrivateVlanChecks(VLAN_ID_900, VLAN_ID_900, Network.PVlanType.Isolated);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testPerformBasicChecksPromiscuousTypeExpectedCommunitySet() {
        service.performBasicPrivateVlanChecks(VLAN_ID_900, VLAN_ID_900, Network.PVlanType.Community);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testPerformBasicChecksPromiscuousTypeExpectedSecondaryVlanNullIsolatedSet() {
        service.performBasicPrivateVlanChecks(VLAN_ID_900, null, Network.PVlanType.Isolated);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testPerformBasicChecksPromiscuousTypeExpectedSecondaryVlanNullCommunitySet() {
        service.performBasicPrivateVlanChecks(VLAN_ID_900, null, Network.PVlanType.Community);
    }

    @Test(expected = CloudRuntimeException.class)
    public void testPerformBasicChecksPromiscuousTypeExpectedDifferentVlanIds() {
        service.performBasicPrivateVlanChecks(VLAN_ID_900, VLAN_ID_901, Network.PVlanType.Promiscuous);
    }

    @Test
    public void testCreateGuestNetwork() throws InsufficientCapacityException, ResourceAllocationException {
        Integer publicMtu = 2450;
        Integer privateMtu = 1200;
        ReflectionTestUtils.setField(createNetworkCmd, "name", "testNetwork");
        ReflectionTestUtils.setField(createNetworkCmd, "displayText", "Test Network");
        ReflectionTestUtils.setField(createNetworkCmd, "networkOfferingId", 1L);
        ReflectionTestUtils.setField(createNetworkCmd, "zoneId", 1L);
        ReflectionTestUtils.setField(createNetworkCmd, "publicMtu", publicMtu);
        ReflectionTestUtils.setField(createNetworkCmd, "privateMtu", privateMtu);
        ReflectionTestUtils.setField(createNetworkCmd, "physicalNetworkId", null);
        Mockito.when(offering.isSystemOnly()).thenReturn(false);
        Mockito.when(dc.getId()).thenReturn(1L);
        Mockito.when(dc.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);
        Map<String, String> networkProvidersMap = new HashMap<String, String>();
        Mockito.when(_networkMgr.finalizeServicesAndProvidersForNetwork(ArgumentMatchers.any(NetworkOffering.class), anyLong())).thenReturn(networkProvidersMap);
        Mockito.when(configMgr.isOfferingForVpc(offering)).thenReturn(false);
        Mockito.when(offering.isInternalLb()).thenReturn(false);
        Mockito.when(networkMtuService.validateMtuConfig(publicMtu, privateMtu, 1L)).thenReturn(new Pair<>(NetworkService.DEFAULT_MTU, privateMtu));

        service.createGuestNetwork(createNetworkCmd);
        Mockito.verify(_networkMgr, times(1)).createGuestNetwork(1L, "testNetwork", "Test Network", null,
                null, null, false, null, accountMock, null, phyNet,
                1L, null, null, null, null, null,
                true, null, null, null, null, null,
                null, null, null, null, new Pair<>(1500, privateMtu), null, true);
    }

    @Test
    public void testValidateMtuConfigDelegatesToNetworkMtuService() {
        Pair<Integer, Integer> expected = new Pair<>(1500, 1400);
        Mockito.when(networkMtuService.validateMtuConfig(1600, 1400, zoneId)).thenReturn(expected);

        Assert.assertSame(expected, service.validateMtuConfig(1600, 1400, zoneId));
        Mockito.verify(networkMtuService).validateMtuConfig(1600, 1400, zoneId);
    }

    @Test
    public void testMtuCheckForVpcNetworkDelegatesToNetworkMtuService() {
        Pair<Integer, Integer> mtus = new Pair<>(1500, 1400);

        service.mtuCheckForVpcNetwork(5L, mtus, 1500);

        Mockito.verify(networkMtuService).mtuCheckForVpcNetwork(5L, mtus, 1500);
    }

    @Test
    public void testValidateMtuOnUpdateDelegatesToNetworkMtuService() {
        NetworkVO networkVO = Mockito.mock(NetworkVO.class);
        Pair<Integer, Integer> expected = new Pair<>(1500, 1400);
        Mockito.when(networkMtuService.validateMtuOnUpdate(networkVO, zoneId, 1500, 1400)).thenReturn(expected);

        Assert.assertSame(expected, service.validateMtuOnUpdate(networkVO, zoneId, 1500, 1400));
        Mockito.verify(networkMtuService).validateMtuOnUpdate(networkVO, zoneId, 1500, 1400);
    }

    @Test
    public void testUpdateMtuOnVrDelegatesToNetworkMtuService() {
        Map<Long, Set<IpAddressTO>> routersToIpList = new HashMap<>();
        Mockito.when(networkMtuService.updateMtuOnVr(routersToIpList)).thenReturn(true);

        Assert.assertTrue(service.updateMtuOnVr(routersToIpList));
        Mockito.verify(networkMtuService).updateMtuOnVr(routersToIpList);
    }

    @Test
    public void testValidateBypassingPublicMtuPassedDuringNetworkTierCreationForVpcs() throws InsufficientCapacityException, ResourceAllocationException {
        Integer publicMtu = 1250;
        Integer privateMtu = 1000;
        Long zoneId = 1L;
        ReflectionTestUtils.setField(createNetworkCmd, "name", "testNetwork");
        ReflectionTestUtils.setField(createNetworkCmd, "displayText", "Test Network");
        ReflectionTestUtils.setField(createNetworkCmd, "networkOfferingId", 1L);
        ReflectionTestUtils.setField(createNetworkCmd, "zoneId", zoneId);
        ReflectionTestUtils.setField(createNetworkCmd, "publicMtu", publicMtu);
        ReflectionTestUtils.setField(createNetworkCmd, "privateMtu", privateMtu);
        ReflectionTestUtils.setField(createNetworkCmd, "physicalNetworkId", null);
        ReflectionTestUtils.setField(createNetworkCmd, "vpcId", 1L);
        Mockito.when(dc.getId()).thenReturn(1L);
        Mockito.when(configMgr.isOfferingForVpc(offering)).thenReturn(true);
        Mockito.lenient().when(vpcDao.findById(anyLong())).thenReturn(vpc);
        Mockito.when(networkMtuService.validateMtuConfig(publicMtu, privateMtu, zoneId)).thenReturn(new Pair<>(0, privateMtu));

        service.createGuestNetwork(createNetworkCmd);
        Mockito.verify(vpcMgr, times(1)).createVpcGuestNetwork(1L, "testNetwork", "Test Network", null,
                null, null, null, accountMock, null, phyNet,
                1L, null, null, 1L, null, accountMock,
                true, null, null, null, null, null, null, null, new Pair<>(0, 1000), null);
    }

    @Test
    public void testUpdateGuestNetworkDelegatesMtuUpdate() {
        Integer publicMtu = 1400;
        Integer privateMtu = 1300;
        long oldNetworkOfferingId = 20L;
        long callerUserId = 40L;
        long callerAccountId = 50L;
        ReflectionTestUtils.setField(updateNetworkCmd, "id", networkId);
        ReflectionTestUtils.setField(updateNetworkCmd, "publicMtu", publicMtu);
        ReflectionTestUtils.setField(updateNetworkCmd, "privateMtu", privateMtu);
        CallContext callContext = Mockito.mock(CallContext.class);
        User callerUser = Mockito.mock(User.class);
        Account callerAccount = Mockito.mock(Account.class);
        Account networkAccount = Mockito.mock(Account.class);
        NetworkVO networkVO = Mockito.mock(NetworkVO.class);
        NetworkOfferingVO oldOffering = Mockito.mock(NetworkOfferingVO.class);
        callContextMocked.when(CallContext::current).thenReturn(callContext);
        Mockito.when(callContext.getCallingUserId()).thenReturn(callerUserId);
        Mockito.when(_accountService.getActiveUser(callerUserId)).thenReturn(callerUser);
        Mockito.when(callerUser.getAccountId()).thenReturn(callerAccountId);
        Mockito.when(_accountService.getActiveAccountById(callerAccountId)).thenReturn(callerAccount);
        Mockito.when(networkDao.findById(networkId)).thenReturn(networkVO);
        Mockito.when(networkVO.getName()).thenReturn("network");
        Mockito.when(networkVO.getState()).thenReturn(Network.State.Implemented);
        Mockito.when(networkVO.getNetworkOfferingId()).thenReturn(oldNetworkOfferingId);
        Mockito.when(networkVO.getTrafficType()).thenReturn(TrafficType.Guest);
        Mockito.when(networkVO.getAccountId()).thenReturn(ACCOUNT_ID);
        Mockito.when(networkVO.getDataCenterId()).thenReturn(zoneId);
        Mockito.when(networkVO.getId()).thenReturn(networkId);
        Mockito.when(networkVO.getGuruName()).thenReturn("missing-guru");
        Mockito.when(networkOfferingDao.findByIdIncludingRemoved(oldNetworkOfferingId)).thenReturn(oldOffering);
        Mockito.when(oldOffering.isSystemOnly()).thenReturn(false);
        Mockito.when(accountManager.getActiveAccountById(ACCOUNT_ID)).thenReturn(networkAccount);
        Mockito.when(_dcDao.findById(zoneId)).thenReturn(dc);
        Mockito.when(dc.getId()).thenReturn(zoneId);
        service.setNetworkGurus(new ArrayList<>());

        try (MockedStatic<UsageEventUtils> usageEventUtils = Mockito.mockStatic(UsageEventUtils.class)) {
            Network updatedNetwork = service.updateGuestNetwork(updateNetworkCmd);

            Assert.assertSame(networkVO, updatedNetwork);
            usageEventUtils.verify(() -> UsageEventUtils.publishNetworkUpdate(networkVO));
        }
        Mockito.verify(networkMtuService).updateNetworkMtu(networkVO, networkId, zoneId, publicMtu, privateMtu, false);
    }

    private void prepareCreateNetworkDnsMocks(CreateNetworkCmd cmd, Network.GuestType guestType, boolean ipv6, boolean isVpc, boolean dnsServiceSupported) {
        long networkOfferingId = 1L;
        Mockito.when(cmd.getNetworkOfferingId()).thenReturn(networkOfferingId);
        networkOfferingVO = Mockito.mock(NetworkOfferingVO.class);
        Mockito.when(networkOfferingVO.getId()).thenReturn(networkOfferingId);
        Mockito.when(networkOfferingVO.getGuestType()).thenReturn(guestType);
        Mockito.when(networkOfferingDao.findById(networkOfferingId)).thenReturn(networkOfferingVO);
        if (Network.GuestType.Shared.equals(guestType)) {
            Mockito.when(networkModel.isProviderForNetworkOffering(Mockito.any(), Mockito.anyLong())).thenReturn(true);
            Mockito.when(cmd.getGateway()).thenReturn(IP4_GATEWAY);
            Mockito.when(cmd.getNetmask()).thenReturn(IP4_NETMASK);
        }
        PhysicalNetworkVO aPhyNet = Mockito.mock(PhysicalNetworkVO.class);
        Mockito.lenient().when(physicalNetworkDao.findById(Mockito.anyLong())).thenReturn(aPhyNet);
        Mockito.lenient().when(physicalNetworkManagementService.getPhysicalNetwork(Mockito.anyLong())).thenReturn(aPhyNet);
        Mockito.when(networkOfferingServiceMapDao.areServicesSupportedByNetworkOffering(networkOfferingId, Network.Service.Dns)).thenReturn(dnsServiceSupported);
        if(ipv6 && Network.GuestType.Isolated.equals(guestType)) {
            Mockito.when(networkOfferingDao.isIpv6Supported(networkOfferingId)).thenReturn(true);
            try {
                Mockito.when(ipv6Service.preAllocateIpv6SubnetForNetwork(Mockito.any())).thenReturn(new Pair<>(IP6_GATEWAY, IP6_CIDR));
            } catch (ResourceAllocationException e) {
                Assert.fail(String.format("failure with exception: %s", e.getMessage()));
            }
        }
        Mockito.when(cmd.getSubdomainAccess()).thenReturn(null);
        Mockito.when(cmd.getAssociatedNetworkId()).thenReturn(null);
        if (isVpc) {
            Mockito.when(cmd.getVpcId()).thenReturn(1L);
        } else {
            Mockito.when(cmd.getVpcId()).thenReturn(null);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCreateL2NetworkDnsFailure() {
        registerCallContext();
        CreateNetworkCmd cmd = Mockito.mock(CreateNetworkCmd.class);
        prepareCreateNetworkDnsMocks(cmd, Network.GuestType.L2, false, false, true);
        Mockito.when(cmd.getIp4Dns1()).thenReturn(ip4Dns[0]);
        try {
            service.createGuestNetwork(cmd);
        } catch (InsufficientCapacityException | ResourceAllocationException e) {
            Assert.fail(String.format("failure with exception: %s", e.getMessage()));
        }
    }

    @Test
    public void testCreateNetworkDnsVpcFailure() {
        registerCallContext();
        CreateNetworkCmd cmd = Mockito.mock(CreateNetworkCmd.class);
        prepareCreateNetworkDnsMocks(cmd, Network.GuestType.Isolated, false, false, true);
        Mockito.when(cmd.getIp4Dns1()).thenReturn(ip4Dns[0]);
        Mockito.when(cmd.getCidrSize()).thenReturn(null);
        try {
            service.createGuestNetwork(cmd);
        } catch (InsufficientCapacityException | ResourceAllocationException e) {
            Assert.fail(String.format("failure with exception: %s", e.getMessage()));
        }
    }

    @Test(expected = CloudRuntimeException.class)
    public void testCreateNetworkDnsOfferingServiceFailure() {
        registerCallContext();
        CreateNetworkCmd cmd = Mockito.mock(CreateNetworkCmd.class);
        Mockito.when(cmd.getDomainId()).thenReturn(null);
        Mockito.when(cmd.getProjectId()).thenReturn(null);
        prepareCreateNetworkDnsMocks(cmd, Network.GuestType.Isolated, false, false, false);
        Mockito.when(cmd.getIp4Dns1()).thenReturn(ip4Dns[0]);
        try {
            service.createGuestNetwork(cmd);
        } catch (InsufficientCapacityException | ResourceAllocationException e) {
            Assert.fail(String.format("failure with exception: %s", e.getMessage()));
        }
    }

    @Test(expected = CloudRuntimeException.class)
    public void testCreateIp4NetworkIp6DnsFailure() {
        registerCallContext();
        CreateNetworkCmd cmd = Mockito.mock(CreateNetworkCmd.class);
        prepareCreateNetworkDnsMocks(cmd, Network.GuestType.Isolated, false, false, true);
        Mockito.when(cmd.getIp6Dns1()).thenReturn(ip4Dns[0]);
        try {
            service.createGuestNetwork(cmd);
        } catch (InsufficientCapacityException | ResourceAllocationException e) {
            Assert.fail(String.format("failure with exception: %s", e.getMessage()));
        }
    }

    @Test
    public void testCheckAndUpdateNetworkNoUpdate() {
        Assert.assertFalse(service.checkAndUpdateNetworkDns(Mockito.mock(NetworkVO.class), Mockito.mock(NetworkOffering.class), null, null, null, null));
        NetworkVO network1 = Mockito.mock(NetworkVO.class);
        Mockito.when(network1.getDns1()).thenReturn(ip4Dns[0]);
        Long offeringId = 1L;
        NetworkOffering offering = Mockito.mock(NetworkOffering.class);
        Mockito.when(offering.getId()).thenReturn(offeringId);
        Mockito.when(networkOfferingServiceMapDao.areServicesSupportedByNetworkOffering(offeringId, Network.Service.Dns)).thenReturn(true);
        Assert.assertFalse(service.checkAndUpdateNetworkDns(network1, offering, null, null, null, null));
        Assert.assertFalse(service.checkAndUpdateNetworkDns(network1, Mockito.mock(NetworkOffering.class), ip4Dns[0], null, null, null));
        Mockito.when(network1.getIp6Dns1()).thenReturn(ip6Dns[0]);
        Assert.assertFalse(service.checkAndUpdateNetworkDns(network1, Mockito.mock(NetworkOffering.class), ip4Dns[0], null, ip6Dns[0], null));
    }

    @Test
    public void testCheckAndUpdateNetworkOfferingChangeReset() {
        NetworkVO networkVO = new NetworkVO();
        networkVO.setDns1(ip4Dns[0]);
        Long offeringId = 1L;
        NetworkOffering offering = Mockito.mock(NetworkOffering.class);
        Mockito.when(offering.getId()).thenReturn(offeringId);
        Mockito.when(networkOfferingServiceMapDao.areServicesSupportedByNetworkOffering(offeringId, Network.Service.Dns)).thenReturn(false);
        Assert.assertTrue(service.checkAndUpdateNetworkDns(networkVO, offering, null, null, null, null));
        Assert.assertNull(networkVO.getDns1());
        Assert.assertNull(networkVO.getDns2());
        Assert.assertNull(networkVO.getIp6Dns1());
        Assert.assertNull(networkVO.getIp6Dns2());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCheckAndUpdateNetworkDnsL2NetworkFailure() {
        NetworkVO networkVO = Mockito.mock(NetworkVO.class);
        NetworkOffering offering = Mockito.mock(NetworkOffering.class);
        Mockito.when(offering.getGuestType()).thenReturn(Network.GuestType.L2);
        service.checkAndUpdateNetworkDns(networkVO, offering, ip4Dns[0], null, null, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCheckAndUpdateNetworkDnsVpcTierFailure() {
        NetworkVO networkVO = Mockito.mock(NetworkVO.class);
        Mockito.when(networkVO.getVpcId()).thenReturn(1L);
        NetworkOffering offering = Mockito.mock(NetworkOffering.class);
        Mockito.when(offering.getGuestType()).thenReturn(Network.GuestType.Shared);
        service.checkAndUpdateNetworkDns(networkVO, offering, ip4Dns[0], null, null, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCheckAndUpdateNetworkDnsServiceFailure() {
        NetworkVO networkVO = Mockito.mock(NetworkVO.class);
        Mockito.when(networkVO.getVpcId()).thenReturn(null);
        Long offeringId = 1L;
        NetworkOffering offering = Mockito.mock(NetworkOffering.class);
        Mockito.when(offering.getId()).thenReturn(offeringId);
        Mockito.when(offering.getGuestType()).thenReturn(Network.GuestType.Shared);
        Mockito.when(networkOfferingServiceMapDao.areServicesSupportedByNetworkOffering(offeringId, Network.Service.Dns)).thenReturn(false);
        service.checkAndUpdateNetworkDns(networkVO, offering, ip4Dns[0], null, null, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCheckAndUpdateNetworkNotSharedIp6Failure() {
        NetworkVO networkVO = Mockito.mock(NetworkVO.class);
        Mockito.when(networkVO.getVpcId()).thenReturn(null);
        Mockito.when(networkVO.getIp6Cidr()).thenReturn(null);
        Mockito.when(networkVO.getGuestType()).thenReturn(Network.GuestType.Shared);
        Long offeringId = 1L;
        NetworkOffering offering = Mockito.mock(NetworkOffering.class);
        Mockito.when(offering.getId()).thenReturn(offeringId);
        Mockito.when(networkOfferingServiceMapDao.areServicesSupportedByNetworkOffering(offeringId, Network.Service.Dns)).thenReturn(true);
        service.checkAndUpdateNetworkDns(networkVO, offering, null, null, ip6Dns[0], null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testCheckAndUpdateNetworkNotIsolatedIp6Failure() {
        NetworkVO networkVO = Mockito.mock(NetworkVO.class);
        Mockito.when(networkVO.getVpcId()).thenReturn(null);
        Mockito.when(networkVO.getGuestType()).thenReturn(Network.GuestType.Isolated);
        Long offeringId = 1L;
        NetworkOffering offering = Mockito.mock(NetworkOffering.class);
        Mockito.when(offering.getId()).thenReturn(offeringId);
        Mockito.when(networkOfferingDao.isIpv6Supported(offeringId)).thenReturn(false);
        Mockito.when(networkOfferingServiceMapDao.areServicesSupportedByNetworkOffering(offeringId, Network.Service.Dns)).thenReturn(true);
        service.checkAndUpdateNetworkDns(networkVO, offering, null, null, ip6Dns[0], null);
    }

    @Test
    public void testCheckAndUpdateNetworkSuccess() {
        NetworkVO networkVO = new NetworkVO();
        networkVO.setVpcId(null);
        try {
            Field id = networkVO.getClass().getDeclaredField("guestType");
            id.setAccessible(true);
            id.set(networkVO, Network.GuestType.Shared);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Assert.fail(String.format("Unable to set network guestType, %s", e.getMessage()));
        }
        networkVO.setIp6Cidr("cidr");
        Long offeringId = 1L;
        NetworkOffering offering = Mockito.mock(NetworkOffering.class);
        Mockito.when(offering.getId()).thenReturn(offeringId);
        Mockito.when(networkOfferingServiceMapDao.areServicesSupportedByNetworkOffering(offeringId, Network.Service.Dns)).thenReturn(true);
        boolean updated = service.checkAndUpdateNetworkDns(networkVO, offering, ip4Dns[0], null, ip6Dns[0], null);
        Assert.assertTrue(updated);
        Assert.assertEquals(ip4Dns[0], networkVO.getDns1());
        Assert.assertNull(networkVO.getDns2());
        Assert.assertEquals(ip6Dns[0], networkVO.getIp6Dns1());
        Assert.assertNull(networkVO.getIp6Dns2());
    }

    @Test
    public void testCreateIpv4RoutedNetwork() {
        registerCallContext();
        CreateNetworkCmd cmd = Mockito.mock(CreateNetworkCmd.class);
        Mockito.when(cmd.getCidrSize()).thenReturn(24);
        prepareCreateNetworkDnsMocks(cmd, Network.GuestType.Isolated, false, false, true);
        when(networkOfferingVO.getNetworkMode()).thenReturn(NetworkOffering.NetworkMode.ROUTED);
        when(networkOfferingVO.getRoutingMode()).thenReturn(NetworkOffering.RoutingMode.Static);
        when(routedIpv4Manager.isRoutedNetworkVpcEnabled(nullable(Long.class))).thenReturn(true);
        when(routedIpv4Manager.isVirtualRouterGateway(networkOfferingVO)).thenReturn(true);
        doNothing().when(routedIpv4Manager).assignIpv4SubnetToNetwork(nullable(Network.class));

        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        when(cmd.getZoneId()).thenReturn(zoneId);
        when(_dcDao.findById(zoneId)).thenReturn(zone);
        when(zone.getId()).thenReturn(zoneId);

        try {
            service.createGuestNetwork(cmd);
        } catch (InsufficientCapacityException | ResourceAllocationException e) {
            Assert.fail(String.format("failure with exception: %s", e.getMessage()));
        }

        Mockito.verify(routedIpv4Manager).assignIpv4SubnetToNetwork(nullable(Network.class));
    }

    @Test
    public void testCreateVpcTier() throws InsufficientCapacityException, ResourceAllocationException, NoSuchFieldException, IllegalAccessException {
        Integer privateMtu = 1200;
        Long networkOfferingId = 1L;
        Long vpcId = 2L;

        ReflectionTestUtils.setField(createNetworkCmd, "name", "testNetwork");
        ReflectionTestUtils.setField(createNetworkCmd, "displayText", "Test Network");
        ReflectionTestUtils.setField(createNetworkCmd, "networkOfferingId", networkOfferingId);
        ReflectionTestUtils.setField(createNetworkCmd, "zoneId", zoneId);
        ReflectionTestUtils.setField(createNetworkCmd, "privateMtu", privateMtu);
        ReflectionTestUtils.setField(createNetworkCmd, "vpcId", vpcId);

        dc = Mockito.mock(DataCenterVO.class);
        Mockito.when(_dcDao.findById(zoneId)).thenReturn(dc);
        Mockito.when(dc.getId()).thenReturn(zoneId);
        vpc = Mockito.mock(VpcVO.class);
        Mockito.when(vpc.getName()).thenReturn("Vpc 1");
        Mockito.when(vpc.getPublicMtu()).thenReturn(0);
        Mockito.when(vpcDao.findById(vpcId)).thenReturn(vpc);
        networkOfferingVO = Mockito.mock(NetworkOfferingVO.class);
        Mockito.when(networkOfferingDao.findById(networkOfferingId)).thenReturn(networkOfferingVO);
        Mockito.when(configMgr.isOfferingForVpc(networkOfferingVO)).thenReturn(true);
        Mockito.when(networkMtuService.validateMtuConfig(NetworkService.DEFAULT_MTU, privateMtu, zoneId)).thenReturn(new Pair<>(NetworkService.DEFAULT_MTU, privateMtu));
        Mockito.doAnswer(invocation -> {
            Pair<Integer, Integer> interfaceMtus = invocation.getArgument(1);
            interfaceMtus.set(vpc.getPublicMtu(), interfaceMtus.second());
            return null;
        }).when(networkMtuService).mtuCheckForVpcNetwork(ArgumentMatchers.eq(vpcId), ArgumentMatchers.any(), ArgumentMatchers.eq(NetworkService.DEFAULT_MTU));

        overrideDefaultConfigValue(VpcManager.VpcTierNamePrepend, "_defaultValue", "true");
        overrideDefaultConfigValue(VpcManager.VpcTierNamePrependDelimiter, "_defaultValue", " -- ");

        service.createGuestNetwork(createNetworkCmd);

        overrideDefaultConfigValue(VpcManager.VpcTierNamePrepend, "_defaultValue", "false");

        Mockito.verify(vpcMgr, times(1)).createVpcGuestNetwork(networkOfferingId, "Vpc 1 -- testNetwork", "Test Network", null, null,
                null, null, accountMock, null, phyNet, zoneId, null, null, vpcId, null, accountMock, true,
                null, null, null, null, null, null, null, new Pair<>(0, privateMtu), null);
    }

    public void testCreateIpv4RoutedNetworkWithBgpPeersFailure1() {
        registerCallContext();
        CreateNetworkCmdByAdmin cmd = Mockito.mock(CreateNetworkCmdByAdmin.class);
        Mockito.when(cmd.getCidrSize()).thenReturn(24);
        List<Long> bgpPeerIds = Arrays.asList(11L, 12L);
        Mockito.when(cmd.getBgpPeerIds()).thenReturn(bgpPeerIds);

        prepareCreateNetworkDnsMocks(cmd, Network.GuestType.Isolated, false, true, true);
        when(networkOfferingVO.getNetworkMode()).thenReturn(NetworkOffering.NetworkMode.ROUTED);
        when(networkOfferingVO.getRoutingMode()).thenReturn(NetworkOffering.RoutingMode.Static);
        when(routedIpv4Manager.isRoutedNetworkVpcEnabled(nullable(Long.class))).thenReturn(true);
        when(routedIpv4Manager.isVirtualRouterGateway(networkOfferingVO)).thenReturn(true);

        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        when(cmd.getZoneId()).thenReturn(zoneId);
        when(_dcDao.findById(zoneId)).thenReturn(zone);
        when(zone.getId()).thenReturn(zoneId);

        try {
            service.createGuestNetwork(cmd);
        } catch (InsufficientCapacityException | ResourceAllocationException e) {
            Assert.fail(String.format("failure with exception: %s", e.getMessage()));
        } catch (InvalidParameterValueException ex) {
            Assert.assertEquals("The BGP peers of VPC tiers will inherit from the VPC, do not add separately.", ex.getMessage());
        }
    }

    @Test
    public void testCreateIpv4RoutedNetworkWithBgpPeersFailure2() {
        registerCallContext();
        CreateNetworkCmdByAdmin cmd = Mockito.mock(CreateNetworkCmdByAdmin.class);
        Mockito.when(cmd.getCidrSize()).thenReturn(24);
        List<Long> bgpPeerIds = Arrays.asList(11L, 12L);
        Mockito.when(cmd.getBgpPeerIds()).thenReturn(bgpPeerIds);

        prepareCreateNetworkDnsMocks(cmd, Network.GuestType.Isolated, false, false, true);
        when(networkOfferingVO.getNetworkMode()).thenReturn(NetworkOffering.NetworkMode.ROUTED);
        when(networkOfferingVO.getRoutingMode()).thenReturn(NetworkOffering.RoutingMode.Static);
        when(routedIpv4Manager.isRoutedNetworkVpcEnabled(nullable(Long.class))).thenReturn(true);
        when(routedIpv4Manager.isVirtualRouterGateway(networkOfferingVO)).thenReturn(true);

        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        when(cmd.getZoneId()).thenReturn(zoneId);
        when(_dcDao.findById(zoneId)).thenReturn(zone);
        when(zone.getId()).thenReturn(zoneId);

        try {
            service.createGuestNetwork(cmd);
        } catch (InsufficientCapacityException | ResourceAllocationException e) {
            Assert.fail(String.format("failure with exception: %s", e.getMessage()));
        } catch (InvalidParameterValueException ex) {
            Assert.assertEquals("The network offering does not support Dynamic routing", ex.getMessage());
        }
    }

    @Test
    public void testCreateIpv4RoutedNetworkWithBgpPeersFailure3() {
        registerCallContext();
        CreateNetworkCmdByAdmin cmd = Mockito.mock(CreateNetworkCmdByAdmin.class);
        Mockito.when(cmd.getCidrSize()).thenReturn(24);
        List<Long> bgpPeerIds = Arrays.asList(11L, 12L);
        Mockito.when(cmd.getBgpPeerIds()).thenReturn(bgpPeerIds);

        prepareCreateNetworkDnsMocks(cmd, Network.GuestType.Isolated, false, false, true);
        when(networkOfferingVO.getNetworkMode()).thenReturn(NetworkOffering.NetworkMode.ROUTED);
        when(networkOfferingVO.getRoutingMode()).thenReturn(NetworkOffering.RoutingMode.Static);
        when(routedIpv4Manager.isRoutedNetworkVpcEnabled(nullable(Long.class))).thenReturn(true);
        when(routedIpv4Manager.isVirtualRouterGateway(networkOfferingVO)).thenReturn(true);
        when(routedIpv4Manager.isDynamicRoutedNetwork(networkOfferingVO)).thenReturn(true);
        doThrow(new InvalidParameterValueException("validation error")).when(routedIpv4Manager).validateBgpPeers(nullable(Account.class), nullable(Long.class), any(List.class));

        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        when(cmd.getZoneId()).thenReturn(zoneId);
        when(_dcDao.findById(zoneId)).thenReturn(zone);
        when(zone.getId()).thenReturn(zoneId);

        try {
            service.createGuestNetwork(cmd);
        } catch (InsufficientCapacityException | ResourceAllocationException e) {
            Assert.fail(String.format("failure with exception: %s", e.getMessage()));
        } catch (InvalidParameterValueException ex) {
            Assert.assertEquals("validation error", ex.getMessage());
        }
    }

    @Test
    public void testCheckAndUpdateNetworkResetSuccess() {
        NetworkVO networkVO = new NetworkVO();
        networkVO.setVpcId(null);
        networkVO.setDns1(ip4Dns[0]);
        networkVO.setIp6Dns1(ip6Dns[0]);
        try {
            Field id = networkVO.getClass().getDeclaredField("guestType");
            id.setAccessible(true);
            id.set(networkVO, Network.GuestType.Shared);
        } catch (NoSuchFieldException | IllegalAccessException e) {
            Assert.fail(String.format("Unable to set network guestType, %s", e.getMessage()));
        }
        networkVO.setIp6Cidr("cidr");
        NetworkOffering offering = Mockito.mock(NetworkOffering.class);
        boolean updated = service.checkAndUpdateNetworkDns(networkVO, offering, "", null, "", null);
        Assert.assertTrue(updated);
        Assert.assertNull(networkVO.getDns1());
        Assert.assertNull(networkVO.getDns2());
        Assert.assertNull(networkVO.getIp6Dns1());
        Assert.assertNull(networkVO.getIp6Dns2());
    }
    @Test
    public void validateIfServiceOfferingIsActiveAndSystemVmTypeIsDomainRouterTestMustThrowInvalidParameterValueExceptionWhenServiceOfferingIsNull() {
        doReturn(null).when(serviceOfferingDaoMock).findById(anyLong());

        String expectedMessage = String.format("Could not find specified service offering [%s].", 1l);
        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedException, () -> {
            service.validateIfServiceOfferingIsActiveAndSystemVmTypeIsDomainRouter(1l);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfServiceOfferingIsActiveAndSystemVmTypeIsDomainRouterTestMustThrowInvalidParameterValueExceptionWhenServiceOfferingStateIsInactive() {
        doReturn(serviceOfferingVoMock).when(serviceOfferingDaoMock).findById(anyLong());
        doReturn(ServiceOffering.State.Inactive).when(serviceOfferingVoMock).getState();

        String expectedMessage = String.format("The specified service offering [%s] is inactive.", serviceOfferingVoMock);
        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedException, () -> {
            service.validateIfServiceOfferingIsActiveAndSystemVmTypeIsDomainRouter(1l);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfServiceOfferingIsActiveAndSystemVmTypeIsDomainRouterTestMustThrowInvalidParameterValueExceptionWhenSystemVmTypeIsNotDomainRouter() {
        doReturn(serviceOfferingVoMock).when(serviceOfferingDaoMock).findById(anyLong());
        doReturn(ServiceOffering.State.Active).when(serviceOfferingVoMock).getState();
        doReturn(VirtualMachine.Type.ElasticLoadBalancerVm.toString()).when(serviceOfferingVoMock).getVmType();

        String expectedMessage = String.format("The specified service offering [%s] is of type [%s]. Virtual routers can only be created with service offering of type [%s].",
                serviceOfferingVoMock, serviceOfferingVoMock.getVmType(), VirtualMachine.Type.DomainRouter.toString().toLowerCase());
        InvalidParameterValueException assertThrows = Assert.assertThrows(expectedException, () -> {
            service.validateIfServiceOfferingIsActiveAndSystemVmTypeIsDomainRouter(1l);
        });

        Assert.assertEquals(expectedMessage, assertThrows.getMessage());
    }

    @Test
    public void validateIfServiceOfferingIsActiveAndSystemVmTypeIsDomainRouterTestMustNotThrowInvalidParameterValueExceptionWhenSystemVmTypeIsDomainRouter() {
        NetworkServiceImpl networkServiceImplMock = mock(NetworkServiceImpl.class);

        networkServiceImplMock.validateIfServiceOfferingIsActiveAndSystemVmTypeIsDomainRouter(1l);
    }

    @Test
    public void validateNotSharedNetworkRouterIPv4() {
        NetworkOffering ntwkOff = Mockito.mock(NetworkOffering.class);
        when(ntwkOff.getGuestType()).thenReturn(Network.GuestType.L2);
        service.validateSharedNetworkRouterIPs(null, null, null, null, null, null, null, null, null, ntwkOff);
    }

    @Test
    public void validateSharedNetworkRouterIPs() {
        String startIP = "10.0.16.2";
        String endIP = "10.0.16.100";
        String routerIPv4 = "10.0.16.100";
        String routerPv6 = "fd17:ac56:1234:2000::fb";
        String startIPv6 = "fd17:ac56:1234:2000::1";
        String endIPv6 = "fd17:ac56:1234:2000::fc";
        NetworkOffering ntwkOff = Mockito.mock(NetworkOffering.class);
        when(ntwkOff.getGuestType()).thenReturn(Network.GuestType.Shared);
        service.validateSharedNetworkRouterIPs(IP4_GATEWAY, startIP, endIP, IP4_NETMASK, routerIPv4, routerPv6, startIPv6, endIPv6, IP6_CIDR, ntwkOff);
    }

    @Test
    public void validateSharedNetworkWrongRouterIPv4() {
        String startIP = "10.0.16.2";
        String endIP = "10.0.16.100";
        String routerIPv4 = "10.0.16.101";
        String routerPv6 = "fd17:ac56:1234:2000::fb";
        String startIPv6 = "fd17:ac56:1234:2000::1";
        String endIPv6 = "fd17:ac56:1234:2000::fc";
        NetworkOffering ntwkOff = Mockito.mock(NetworkOffering.class);
        when(ntwkOff.getGuestType()).thenReturn(Network.GuestType.Shared);
        boolean passing = false;
        try {
            service.validateSharedNetworkRouterIPs(IP4_GATEWAY, startIP, endIP, IP4_NETMASK, routerIPv4, routerPv6, startIPv6, endIPv6, IP6_CIDR, ntwkOff);
        } catch (CloudRuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("Router IPv4 IP provided is not within the specified range: "));
            passing = true;
        }
        Assert.assertTrue(passing);
    }

    @Test
    public void validateSharedNetworkNoEndOfIPv6Range() {
        String startIP = null;
        String endIP = null;
        String routerIPv4 = null;
        String routerPv6 = "fd17:ac56:1234:2000::1";
        String startIPv6 = "fd17:ac56:1234:2000::1";
        String endIPv6 = null;
        NetworkOffering ntwkOff = Mockito.mock(NetworkOffering.class);
        when(ntwkOff.getGuestType()).thenReturn(Network.GuestType.Shared);
        service.validateSharedNetworkRouterIPs(IP4_GATEWAY, startIP, endIP, IP4_NETMASK, routerIPv4, routerPv6, startIPv6, endIPv6, IP6_CIDR, ntwkOff);
    }

    @Test
    public void validateSharedNetworkIPv6RouterNotInRange() {
        String routerIPv4 = null;
        String routerIPv6 = "fd17:ac56:1234:2001::1";
        NetworkOffering ntwkOff = Mockito.mock(NetworkOffering.class);
        when(ntwkOff.getGuestType()).thenReturn(Network.GuestType.Shared);
        boolean passing = true;
        try {
            service.validateSharedNetworkRouterIPs(IP4_GATEWAY, null, null, IP4_NETMASK, routerIPv4, routerIPv6, null, null, IP6_CIDR, ntwkOff);
            passing = false;
        } catch (CloudRuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("Router IPv6 address provided is not with the network range"));
        }
        Assert.assertTrue(passing);
    }

    @Test
    public void invalidateSharedNetworkIPv6RouterAddress() {
        String routerIPv6 = "fd17:ac56:1234:2000::fg";
        NetworkOffering ntwkOff = Mockito.mock(NetworkOffering.class);
        when(ntwkOff.getGuestType()).thenReturn(Network.GuestType.Shared);
        boolean passing = false;
        try {
            service.validateSharedNetworkRouterIPs(IP4_GATEWAY, null, null, IP4_NETMASK, null, routerIPv6, null, null, IP6_CIDR, ntwkOff);
        } catch (CloudRuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("Router IPv6 address provided is of incorrect format"));
            passing = true;
        }
        Assert.assertTrue(passing);
    }

    @Test
    public void invalidateSharedNetworkIPv4RouterAddress() {
        String routerIPv4 = "10.100.1000.1";
        NetworkOffering ntwkOff = Mockito.mock(NetworkOffering.class);
        when(ntwkOff.getGuestType()).thenReturn(Network.GuestType.Shared);
        boolean passing = false;
        try {
            service.validateSharedNetworkRouterIPs(IP4_GATEWAY, null, null, IP4_NETMASK, routerIPv4, null, null, null, IP6_CIDR, ntwkOff);
        } catch (CloudRuntimeException e) {
            Assert.assertTrue(e.getMessage().contains("Router IPv4 IP provided is of incorrect format"));
            passing = true;
        }
        Assert.assertTrue(passing);
    }

    @Test
    public void checkAndDontSetSourceNatIp() {
        CreateNetworkCmd cmd = new CreateNetworkCmd();
        try {
            service.checkAndSetRouterSourceNatIp(account, cmd, null);
        } catch (InsufficientAddressCapacityException | ResourceAllocationException e) {
            Assert.fail(e.getMessage());
        }
    }

    @Test
    public void checkAndSetSourceNatIp() {
        String srcNatIp = "10.100.1000.10000";
        Long networkOfferingId = 2l;
        Long zoneId = 3l;
        registerCallContext();
        ReflectionTestUtils.setField(createNetworkCmd, "networkOfferingId", networkOfferingId);
        ReflectionTestUtils.setField(createNetworkCmd, "sourceNatIP", srcNatIp);
        ReflectionTestUtils.setField(createNetworkCmd, "zoneId", zoneId);
        ReflectionTestUtils.setField(createNetworkCmd, "physicalNetworkId", null);
        NetworkVO networkVO = Mockito.spy(NetworkVO.class);
        IpAddress ipAddress = Mockito.mock(IPAddressVO.class);
        NetworkOffering ntwkOff = Mockito.mock(NetworkOffering.class);
        Long networkId = 7l;
        when(networkVO.getId()).thenReturn(networkId);
        when(ipAddress.getId()).thenReturn(5l);
        when(entityMgr.findById(NetworkOffering.class, networkOfferingId)).thenReturn(ntwkOff);
        try {
            when(ipAddressLifecycleService.allocateIP(any(), anyLong(), any(), any(), any())).thenReturn(ipAddress);
            when(ipAddressLifecycleService.associateIPToNetwork(anyLong(), anyLong())).thenReturn(ipAddress);
            service.checkAndSetRouterSourceNatIp(account, createNetworkCmd, networkVO);
        } catch (InsufficientAddressCapacityException | ResourceAllocationException
                | com.cloud.exception.ResourceUnavailableException
                | com.cloud.exception.ConcurrentOperationException e) {
            Assert.fail(e.getMessage());
        }
    }

    @Test
    public void testGetNicVlanValueForExternalVm_NonNsx() {
        NicTO nic = mock(NicTO.class);
        String broadcastUri = "vlan://123";
        when(nic.getBroadcastUri()).thenReturn(URI.create(broadcastUri));
        String result = service.getNicVlanValueForExternalVm(nic);
        assertEquals("123", result);
    }

    @Test
    public void testGetNicVlanValueForExternalVm_Nsx() {
        NicTO nic = mock(NicTO.class);
        NetworkVO networkVO = mock(NetworkVO.class);
        NsxService nsxService = mock(NsxService.class);
        String broadcastUri = "nsx://segment";
        when(nic.getBroadcastUri()).thenReturn(URI.create(broadcastUri));
        when(nic.getNetworkId()).thenReturn(42L);
        when(networkDao.findById(42L)).thenReturn(networkVO);
        when(networkVO.getDomainId()).thenReturn(1L);
        when(networkVO.getDataCenterId()).thenReturn(2L);
        when(networkVO.getAccountId()).thenReturn(3L);
        when(networkVO.getVpcId()).thenReturn(4L);
        when(networkVO.getId()).thenReturn(5L);
        when(nsxService.getSegmentId(1L, 2L, 3L, 4L, 5L)).thenReturn("segment-123");
        try (MockedStatic<ComponentContext> ignored = Mockito.mockStatic(ComponentContext.class)) {
            when(ComponentContext.getDelegateComponentOfType(NsxService.class)).thenReturn(nsxService);
            String result = service.getNicVlanValueForExternalVm(nic);
            assertEquals("segment-123", result);
        }
    }

    @Test(expected = CloudRuntimeException.class)
    public void testGetNicVlanValueForExternalVm_Nsx_NoBean() {
        NicTO nic = mock(NicTO.class);
        NetworkVO networkVO = mock(NetworkVO.class);
        String broadcastUri = "nsx://segment";
        when(nic.getBroadcastUri()).thenReturn(URI.create(broadcastUri));
        when(nic.getNetworkId()).thenReturn(42L);
        when(networkDao.findById(42L)).thenReturn(networkVO);
        try (MockedStatic<ComponentContext> ignored = Mockito.mockStatic(ComponentContext.class)) {
            when(ComponentContext.getDelegateComponentOfType(NsxService.class)).thenThrow(NoSuchBeanDefinitionException.class);
            service.getNicVlanValueForExternalVm(nic);
        }
    }

    @Test
    public void testGetIpAddressesFromIps() {
        // Test with valid IPv4, IPv6 and MAC address
        Network.IpAddresses result = service.getIpAddressesFromIps("192.168.1.1", "2001:db8::1", "00:11:22:33:44:55");
        Assert.assertEquals("192.168.1.1", result.getIp4Address());
        Assert.assertEquals("2001:db8::1", result.getIp6Address());
        Assert.assertEquals("00:11:22:33:44:55", result.getMacAddress());

        // Test with all null values
        result = service.getIpAddressesFromIps(null, null, null);
        Assert.assertNull(result.getIp4Address());
        Assert.assertNull(result.getIp6Address());
        Assert.assertNull(result.getMacAddress());

        // Test with invalid MAC address (non-unicast)
        try {
            service.getIpAddressesFromIps(null, null, "ff:ff:ff:ff:ff:ff");
            Assert.fail("Expected InvalidParameterValueException for non-unicast MAC address");
        } catch (InvalidParameterValueException e) {
            Assert.assertEquals("Mac address is not unicast: ff:ff:ff:ff:ff:ff", e.getMessage());
        }

        // Test with invalid MAC address (invalid format)
        try {
            service.getIpAddressesFromIps(null, null, "invalid-mac");
            Assert.fail("Expected InvalidParameterValueException for invalid MAC address format");
        } catch (InvalidParameterValueException e) {
            Assert.assertEquals("Mac address is not valid: invalid-mac", e.getMessage());
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void getAndValidateSupportForKeepMacAddressOnPublicNicParameterTestThrowExceptionWhenParamIsSpecifiedOnTiersCreation() {
        networkOfferingVO = Mockito.mock(NetworkOfferingVO.class);
        Mockito.when(networkOfferingVO.isForVpc()).thenReturn(true);

        service.getAndValidateSupportForKeepMacAddressOnPublicNicParameter(true, networkOfferingVO);
    }

    @Test
    public void getAndValidateSupportForKeepMacAddressOnPublicNicParameterTestReturnTrueByDefaultOnTiersCreation() {
        networkOfferingVO = Mockito.mock(NetworkOfferingVO.class);
        Mockito.when(networkOfferingVO.isForVpc()).thenReturn(true);

        Assert.assertTrue(service.getAndValidateSupportForKeepMacAddressOnPublicNicParameter(null, networkOfferingVO));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void getAndValidateSupportForKeepMacAddressOnPublicNicParameterTestThrowExceptionWhenParamIsSpecifiedOnNonIsolatedNetworksCreation() {
        networkOfferingVO = Mockito.mock(NetworkOfferingVO.class);
        Mockito.when(networkOfferingVO.getGuestType()).thenReturn(Network.GuestType.Shared);

        service.getAndValidateSupportForKeepMacAddressOnPublicNicParameter(true, networkOfferingVO);
    }

    @Test
    public void getAndValidateSupportForKeepMacAddressOnPublicNicParameterTestReturnTrueByDefaultOnNonIsolatedNetworksCreation() {
        networkOfferingVO = Mockito.mock(NetworkOfferingVO.class);
        Mockito.when(networkOfferingVO.getGuestType()).thenReturn(Network.GuestType.L2);

        Assert.assertTrue(service.getAndValidateSupportForKeepMacAddressOnPublicNicParameter(null, networkOfferingVO));
    }

    @Test
    public void getAndValidateSupportForKeepMacAddressOnPublicNicParameterTestReturnTrueByDefaultOnIsolatedNetworksCreation() {
        networkOfferingVO = Mockito.mock(NetworkOfferingVO.class);
        Mockito.when(networkOfferingVO.getGuestType()).thenReturn(Network.GuestType.Isolated);

        Assert.assertTrue(service.getAndValidateSupportForKeepMacAddressOnPublicNicParameter(null, networkOfferingVO));
    }

    @Test
    public void getAndValidateSupportForKeepMacAddressOnPublicNicParameterTestReturnSpecifiedValueOnIsolatedNetworksCreation() {
        networkOfferingVO = Mockito.mock(NetworkOfferingVO.class);
        Mockito.when(networkOfferingVO.getGuestType()).thenReturn(Network.GuestType.Isolated);

        Assert.assertFalse(service.getAndValidateSupportForKeepMacAddressOnPublicNicParameter(false, networkOfferingVO));
    }
}
