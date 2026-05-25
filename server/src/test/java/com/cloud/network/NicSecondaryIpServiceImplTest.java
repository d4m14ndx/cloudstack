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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.configuration.Config;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.IpAddresses;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.security.SecurityGroupService;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.vm.NicSecondaryIp;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicSecondaryIpDao;
import com.cloud.vm.dao.NicSecondaryIpVO;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class NicSecondaryIpServiceImplTest {

    @Mock private NicDao nicDao;
    @Mock private NicSecondaryIpDao nicSecondaryIpDao;
    @Mock private UserVmDao userVmDao;
    @Mock private NetworkDao networksDao;
    @Mock private NetworkOfferingDao networkOfferingDao;
    @Mock private DataCenterDao dcDao;
    @Mock private IPAddressDao ipAddressDao;
    @Mock private FirewallRulesDao firewallDao;
    @Mock private PortForwardingRulesDao portForwardingDao;
    @Mock private LoadBalancerDao loadBalancerDao;
    @Mock private ConfigurationDao configDao;
    @Mock private AccountManager accountMgr;
    @Mock private IpAddressManager ipAddrMgr;
    @Mock private Ipv6AddressManager ipv6AddrMgr;
    @Mock private SecurityGroupService securityGroupService;
    @Mock private MessageBus messageBus;

    @InjectMocks
    private NicSecondaryIpServiceImpl service = new NicSecondaryIpServiceImpl();

    private AccountVO caller;

    @Before
    public void setUp() {
        caller = new AccountVO("user", 1L, "uuid-net", Account.Type.NORMAL, "uuid-account");
        caller.setId(1L);
        UserVO userVO = new UserVO(1, "u", "p", "f", "l", "e", "tz",
                java.util.UUID.randomUUID().toString(), User.Source.UNKNOWN);
        CallContext.register(userVO, caller);

        // sensible defaults
        when(configDao.getValue(Config.MaxNumberOfSecondaryIPsPerNIC.key())).thenReturn("10");
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // ----- configureNicSecondaryIp -----

    @Test
    public void configureNicSecondaryIp_skipsSecurityGroupsWhenZoneSgDisabled() {
        NicSecondaryIp secIp = mockSecIp(10L, "10.1.1.5", null);

        assertTrue(service.configureNicSecondaryIp(secIp, false));
        verify(securityGroupService, never()).securityGroupRulesForVmSecIp(anyLong(), anyString(), anyBoolean());
    }

    @Test
    public void configureNicSecondaryIp_invokesSecurityGroupsForIpv4WhenZoneSgEnabled() {
        NicSecondaryIp secIp = mockSecIp(10L, "10.1.1.5", null);
        when(securityGroupService.securityGroupRulesForVmSecIp(eq(10L), eq("10.1.1.5"), eq(true))).thenReturn(true);

        assertTrue(service.configureNicSecondaryIp(secIp, true));
        verify(securityGroupService).securityGroupRulesForVmSecIp(10L, "10.1.1.5", true);
    }

    @Test
    public void configureNicSecondaryIp_fallsBackToIpv6WhenIpv4Null() {
        NicSecondaryIp secIp = mockSecIp(11L, null, "fd00::5");
        when(securityGroupService.securityGroupRulesForVmSecIp(eq(11L), eq("fd00::5"), eq(true))).thenReturn(false);

        assertFalse(service.configureNicSecondaryIp(secIp, true));
        verify(securityGroupService).securityGroupRulesForVmSecIp(11L, "fd00::5", true);
    }

    // ----- allocateSecondaryGuestIP -----

    @Test(expected = InvalidParameterValueException.class)
    public void allocateSecondaryGuestIP_throwsWhenNicMissing() throws Exception {
        when(nicDao.findById(anyLong())).thenReturn(null);
        service.allocateSecondaryGuestIP(42L, new IpAddresses("10.1.1.7", null));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void allocateSecondaryGuestIP_throwsWhenNicNotForUserVm() throws Exception {
        NicVO nic = mock(NicVO.class);
        when(nic.getVmType()).thenReturn(VirtualMachine.Type.DomainRouter);
        when(nic.getUuid()).thenReturn("nic-uuid");
        when(nicDao.findById(42L)).thenReturn(nic);

        service.allocateSecondaryGuestIP(42L, new IpAddresses("10.1.1.7", null));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void allocateSecondaryGuestIP_throwsWhenVmMissing() throws Exception {
        NicVO nic = mock(NicVO.class);
        when(nic.getVmType()).thenReturn(VirtualMachine.Type.User);
        when(nic.getInstanceId()).thenReturn(99L);
        when(nic.getUuid()).thenReturn("nic-uuid");
        when(nicDao.findById(42L)).thenReturn(nic);
        when(userVmDao.findById(99L)).thenReturn(null);

        service.allocateSecondaryGuestIP(42L, new IpAddresses("10.1.1.7", null));
    }

    @Test(expected = InsufficientAddressCapacityException.class)
    public void allocateSecondaryGuestIP_throwsWhenMaxIpsReached() throws Exception {
        NicVO nic = mock(NicVO.class);
        when(nic.getVmType()).thenReturn(VirtualMachine.Type.User);
        when(nic.getInstanceId()).thenReturn(99L);
        when(nic.getNetworkId()).thenReturn(7L);
        when(nicDao.findById(42L)).thenReturn(nic);

        com.cloud.vm.UserVmVO vm = makeUserVm(99L, 1L);
        when(userVmDao.findById(99L)).thenReturn(vm);

        when(accountMgr.getAccount(1L)).thenReturn(caller);
        when(networksDao.findById(7L)).thenReturn(mock(NetworkVO.class));
        when(nicSecondaryIpDao.countByNicId(42L)).thenReturn(10L);

        service.allocateSecondaryGuestIP(42L, new IpAddresses("10.1.1.7", null));
    }

    @Test
    public void allocateSecondaryGuestIP_returnsNullForUnsupportedGuestType() throws Exception {
        NicVO nic = mock(NicVO.class);
        when(nic.getVmType()).thenReturn(VirtualMachine.Type.User);
        when(nic.getInstanceId()).thenReturn(99L);
        when(nic.getNetworkId()).thenReturn(7L);
        when(nicDao.findById(42L)).thenReturn(nic);

        com.cloud.vm.UserVmVO vm = makeUserVm(99L, 1L);
        when(userVmDao.findById(99L)).thenReturn(vm);
        when(accountMgr.getAccount(1L)).thenReturn(caller);

        NetworkVO net = mock(NetworkVO.class);
        when(net.getGuestType()).thenReturn(GuestType.L2);
        when(networksDao.findById(7L)).thenReturn(net);
        when(nicSecondaryIpDao.countByNicId(42L)).thenReturn(0L);

        assertNull(service.allocateSecondaryGuestIP(42L, new IpAddresses("10.1.1.7", null)));
    }

    // ----- releaseSecondaryIpFromNic -----

    @Test(expected = InvalidParameterValueException.class)
    public void releaseSecondaryIpFromNic_throwsWhenSecIpMissing() {
        when(nicSecondaryIpDao.findById(anyLong())).thenReturn(null);
        service.releaseSecondaryIpFromNic(5L);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void releaseSecondaryIpFromNic_throwsWhenVmMissing() {
        NicSecondaryIpVO secVO = mock(NicSecondaryIpVO.class);
        when(secVO.getVmId()).thenReturn(99L);
        when(nicSecondaryIpDao.findById(5L)).thenReturn(secVO);
        when(userVmDao.findById(99L)).thenReturn(null);

        service.releaseSecondaryIpFromNic(5L);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void releaseSecondaryIpFromNic_throwsWhenNetworkMissing() {
        NicSecondaryIpVO secVO = mock(NicSecondaryIpVO.class);
        when(secVO.getVmId()).thenReturn(99L);
        when(secVO.getNetworkId()).thenReturn(7L);
        when(nicSecondaryIpDao.findById(5L)).thenReturn(secVO);
        com.cloud.vm.UserVmVO vm = makeUserVm(99L, 1L);
        when(userVmDao.findById(99L)).thenReturn(vm);
        when(networksDao.findById(7L)).thenReturn(null);

        service.releaseSecondaryIpFromNic(5L);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void releaseSecondaryIpFromNic_throwsWhenZoneMissing() {
        NicSecondaryIpVO secVO = mock(NicSecondaryIpVO.class);
        when(secVO.getVmId()).thenReturn(99L);
        when(secVO.getNetworkId()).thenReturn(7L);
        when(secVO.getNicId()).thenReturn(42L);
        when(nicSecondaryIpDao.findById(5L)).thenReturn(secVO);
        com.cloud.vm.UserVmVO vm = makeUserVm(99L, 1L);
        when(userVmDao.findById(99L)).thenReturn(vm);

        NetworkVO net = mock(NetworkVO.class);
        when(net.getDataCenterId()).thenReturn(3L);
        when(net.getNetworkOfferingId()).thenReturn(11L);
        when(networksDao.findById(7L)).thenReturn(net);
        when(networkOfferingDao.findById(11L)).thenReturn(mock(NetworkOfferingVO.class));
        when(nicSecondaryIpDao.listByNicId(42L)).thenReturn(Collections.<NicSecondaryIpVO>emptyList());
        when(dcDao.findById(3L)).thenReturn(null);

        service.releaseSecondaryIpFromNic(5L);
    }

    // ----- removeNicSecondaryIP -----

    @Test
    public void removeNicSecondaryIP_publishesMessageAndReturnsTrue() {
        NicSecondaryIpVO secVO = mock(NicSecondaryIpVO.class);
        when(secVO.getNicId()).thenReturn(42L);
        when(secVO.getId()).thenReturn(5L);
        NicVO nic = mock(NicVO.class);
        when(nicDao.findById(42L)).thenReturn(nic);

        assertTrue(service.removeNicSecondaryIP(secVO, true));
        verify(nicDao).update(eq(42L), any(NicVO.class));
        verify(nicSecondaryIpDao).remove(5L);
        verify(messageBus).publish(eq(NicSecondaryIpServiceImpl.COMPONENT_NAME),
                eq(NetworkService.MESSAGE_RELEASE_NIC_SECONDARY_IP_EVENT),
                any(), eq(secVO));
    }

    @Test
    public void removeNicSecondaryIP_doesNotClearFlagWhenNotLastIp() {
        NicSecondaryIpVO secVO = mock(NicSecondaryIpVO.class);
        when(secVO.getNicId()).thenReturn(42L);
        when(secVO.getId()).thenReturn(5L);
        NicVO nic = mock(NicVO.class);
        when(nicDao.findById(42L)).thenReturn(nic);

        assertTrue(service.removeNicSecondaryIP(secVO, false));
        verify(nicDao, never()).update(anyLong(), any(NicVO.class));
        verify(nicSecondaryIpDao, times(1)).remove(5L);
    }

    // ----- getNicSecondaryIp -----

    @Test
    public void getNicSecondaryIp_returnsValueFromDao() {
        NicSecondaryIpVO secVO = mock(NicSecondaryIpVO.class);
        when(nicSecondaryIpDao.findById(99L)).thenReturn(secVO);
        assertNotNull(service.getNicSecondaryIp(99L));
    }

    @Test
    public void getNicSecondaryIp_returnsNullWhenMissing() {
        when(nicSecondaryIpDao.findById(99L)).thenReturn(null);
        assertNull(service.getNicSecondaryIp(99L));
    }

    @Test
    public void componentName_isStable() {
        // Guards against accidental renaming which would break listeners.
        assertEquals("NetworkService", NicSecondaryIpServiceImpl.COMPONENT_NAME);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private NicSecondaryIp mockSecIp(long nicId, String v4, String v6) {
        NicSecondaryIp ip = mock(NicSecondaryIp.class);
        when(ip.getNicId()).thenReturn(nicId);
        when(ip.getIp4Address()).thenReturn(v4);
        when(ip.getIp6Address()).thenReturn(v6);
        return ip;
    }

    private static com.cloud.vm.UserVmVO makeUserVm(long id, long accountId) {
        com.cloud.vm.UserVmVO vm = org.mockito.Mockito.mock(com.cloud.vm.UserVmVO.class);
        org.mockito.Mockito.lenient().when(vm.getId()).thenReturn(id);
        org.mockito.Mockito.lenient().when(vm.getAccountId()).thenReturn(accountId);
        return vm;
    }

    private static <T> T mock(Class<T> clazz) {
        return org.mockito.Mockito.mock(clazz);
    }

    @SuppressWarnings("unused")
    private static DataCenter mockZone(DataCenter.NetworkType type) {
        DataCenterVO zone = mock(DataCenterVO.class);
        when(zone.getNetworkType()).thenReturn(type);
        return zone;
    }

    @SuppressWarnings("unused")
    private static List<NicSecondaryIpVO> singleList() {
        return Arrays.asList(mock(NicSecondaryIpVO.class));
    }
}
