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
package com.cloud.network.vpc;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.VlanDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.vpc.dao.StaticRouteDao;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.network.vpc.dao.VpcGatewayDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.db.EntityManager;

@RunWith(MockitoJUnitRunner.class)
public class StaticRouteServiceImplTest {

    @Mock private StaticRouteDao staticRouteDao;
    @Mock private VpcDao vpcDao;
    @Mock private VpcGatewayDao vpcGatewayDao;
    @Mock private IPAddressDao ipAddressDao;
    @Mock private VlanDao vlanDao;
    @Mock private AccountManager accountMgr;
    @Mock private EntityManager entityMgr;
    @Mock private VpcManager vpcManager;

    @InjectMocks
    private StaticRouteServiceImpl service;

    @Before
    public void setUp() {
        CallContext.register(mock(User.class), mock(Account.class));
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // ---- getStaticRoute ----

    @Test
    public void getStaticRouteDelegatesToDao() {
        StaticRouteVO route = mock(StaticRouteVO.class);
        when(staticRouteDao.findById(42L)).thenReturn(route);

        StaticRoute result = service.getStaticRoute(42L);

        assertSame(route, result);
        verify(staticRouteDao).findById(42L);
    }

    // ---- createStaticRoute: parameter validation ----

    @Test
    public void createStaticRouteRejectsWhenBothGatewayAndNextHopNull() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.createStaticRoute(null, 1L, null, "10.1.0.0/24"));
    }

    @Test
    public void createStaticRouteRejectsWhenBothGatewayAndNextHopSet() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.createStaticRoute(7L, 1L, "10.1.0.1", "10.1.0.0/24"));
    }

    @Test
    public void createStaticRouteRejectsUnknownGateway() {
        when(vpcGatewayDao.findById(7L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.createStaticRoute(7L, 1L, null, "10.1.0.0/24"));
    }

    @Test
    public void createStaticRouteRejectsGatewayNotReady() {
        VpcGatewayVO gw = mock(VpcGatewayVO.class);
        when(gw.getState()).thenReturn(VpcGateway.State.Creating);
        when(vpcGatewayDao.findById(7L)).thenReturn(gw);
        assertThrows(InvalidParameterValueException.class,
                () -> service.createStaticRoute(7L, 1L, null, "10.1.0.0/24"));
    }

    @Test
    public void createStaticRouteRejectsGatewayBelongingToOtherVpc() {
        VpcGatewayVO gw = mock(VpcGatewayVO.class);
        when(gw.getState()).thenReturn(VpcGateway.State.Ready);
        when(gw.getVpcId()).thenReturn(2L);
        when(vpcGatewayDao.findById(7L)).thenReturn(gw);

        assertThrows(InvalidParameterValueException.class,
                () -> service.createStaticRoute(7L, 1L, null, "10.1.0.0/24"));
    }

    @Test
    public void createStaticRouteRejectsNextHopWithoutVpcId() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.createStaticRoute(null, null, "10.1.0.1", "192.168.0.0/24"));
    }

    @Test
    public void createStaticRouteRejectsDeletedVpc() {
        when(vpcDao.getActiveVpcById(1L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.createStaticRoute(null, 1L, "10.1.0.1", "192.168.0.0/24"));
    }

    @Test
    public void createStaticRouteRejectsMalformedCidr() {
        Vpc vpc = mock(Vpc.class);
        when(vpcDao.getActiveVpcById(1L)).thenReturn(vpc);
        assertThrows(InvalidParameterValueException.class,
                () -> service.createStaticRoute(null, 1L, "10.1.0.1", "not-a-cidr"));
    }

    @Test
    public void createStaticRouteRejectsCidrOverlappingVpcCidr() {
        Vpc vpc = mock(Vpc.class);
        when(vpc.getCidr()).thenReturn("10.1.0.0/16");
        when(vpcDao.getActiveVpcById(1L)).thenReturn(vpc);
        assertThrows(InvalidParameterValueException.class,
                () -> service.createStaticRoute(null, 1L, "10.1.0.1", "10.1.5.0/24"));
    }

    @Test
    public void createStaticRouteRejectsLinkLocalCidr() {
        Vpc vpc = mock(Vpc.class);
        when(vpc.getCidr()).thenReturn("10.1.0.0/16");
        when(vpcDao.getActiveVpcById(1L)).thenReturn(vpc);
        // Link-local default is 169.254.0.0/16; provide a clearly-overlapping target.
        assertThrows(InvalidParameterValueException.class,
                () -> service.createStaticRoute(null, 1L, "10.1.0.1", "169.254.0.0/16"));
    }

    // ---- detectRoutesConflict ----

    @Test
    public void detectRoutesConflictPassesWhenNoOtherRoutes() throws NetworkRuleConflictException {
        StaticRouteVO newRoute = mock(StaticRouteVO.class);
        when(newRoute.getId()).thenReturn(1L);
        when(newRoute.getVpcId()).thenReturn(10L);
        lenient().when(newRoute.getCidr()).thenReturn("172.16.0.0/24");
        when(staticRouteDao.listByVpcIdAndNotRevoked(10L)).thenAnswer(inv -> Collections.singletonList(newRoute));

        service.detectRoutesConflict(newRoute);
    }

    @Test
    public void detectRoutesConflictThrowsOnOverlap() {
        StaticRouteVO newRoute = mock(StaticRouteVO.class);
        when(newRoute.getId()).thenReturn(1L);
        when(newRoute.getVpcId()).thenReturn(10L);
        when(newRoute.getCidr()).thenReturn("172.16.0.0/16");

        StaticRouteVO existing = mock(StaticRouteVO.class);
        when(existing.getId()).thenReturn(2L);
        when(existing.getCidr()).thenReturn("172.16.5.0/24");

        when(staticRouteDao.listByVpcIdAndNotRevoked(10L)).thenAnswer(inv -> Arrays.asList(newRoute, existing));

        assertThrows(NetworkRuleConflictException.class,
                () -> service.detectRoutesConflict(newRoute));
    }

    @Test
    public void detectRoutesConflictIgnoresSelf() throws NetworkRuleConflictException {
        StaticRouteVO newRoute = mock(StaticRouteVO.class);
        when(newRoute.getId()).thenReturn(1L);
        when(newRoute.getVpcId()).thenReturn(10L);
        lenient().when(newRoute.getCidr()).thenReturn("172.16.0.0/24");
        when(staticRouteDao.listByVpcIdAndNotRevoked(10L)).thenAnswer(inv -> Arrays.asList(newRoute, newRoute));

        service.detectRoutesConflict(newRoute);
    }

    // ---- markStaticRouteForRevoke ----

    @Test
    public void markStaticRouteForRevokeRemovesStagedRoute() {
        StaticRouteVO route = mock(StaticRouteVO.class);
        when(route.getId()).thenReturn(5L);
        when(route.getState()).thenReturn(StaticRoute.State.Staged);

        service.markStaticRouteForRevoke(route, null);

        verify(staticRouteDao).remove(5L);
        verify(staticRouteDao, never()).update(anyLong(), any(StaticRouteVO.class));
    }

    @Test
    public void markStaticRouteForRevokeFlipsAddToRevoke() {
        StaticRouteVO route = mock(StaticRouteVO.class);
        when(route.getId()).thenReturn(6L);
        when(route.getState()).thenReturn(StaticRoute.State.Add);

        service.markStaticRouteForRevoke(route, null);

        verify(route).setState(StaticRoute.State.Revoke);
        verify(staticRouteDao).update(eq(6L), eq(route));
    }

    @Test
    public void markStaticRouteForRevokeFlipsActiveToRevoke() {
        StaticRouteVO route = mock(StaticRouteVO.class);
        when(route.getId()).thenReturn(7L);
        when(route.getState()).thenReturn(StaticRoute.State.Active);

        service.markStaticRouteForRevoke(route, null);

        verify(route).setState(StaticRoute.State.Revoke);
        verify(staticRouteDao).update(eq(7L), eq(route));
    }

    @Test
    public void markStaticRouteForRevokeChecksCallerAccessWhenProvided() {
        StaticRouteVO route = mock(StaticRouteVO.class);
        when(route.getId()).thenReturn(8L);
        when(route.getState()).thenReturn(StaticRoute.State.Staged);
        Account caller = mock(Account.class);

        service.markStaticRouteForRevoke(route, caller);

        verify(accountMgr).checkAccess(eq(caller), eq(null), eq(false), eq(route));
        verify(staticRouteDao).remove(8L);
    }

    // ---- isCidrDenylisted ----

    @Test
    public void isCidrDenylistedReturnsFalseWhenNoZoneSettingSet() {
        // No DeniedRoutes value configured for the zone.
        assertFalse(service.isCidrDenylisted("10.50.0.0/24", 99L));
    }

    // ---- isNextHopValid ----

    @Test
    public void isNextHopValidWhenWithinVpcCidr() {
        Vpc vpc = mock(Vpc.class);
        when(vpc.getCidr()).thenReturn("10.1.0.0/16");
        assertTrue(service.isNextHopValid("10.1.0.50", vpc));
    }

    @Test
    public void isNextHopValidViaPublicVlan() {
        Vpc vpc = mock(Vpc.class);
        when(vpc.getId()).thenReturn(1L);
        when(vpc.getCidr()).thenReturn("10.1.0.0/16");

        IPAddressVO ip = mock(IPAddressVO.class);
        when(ip.getVlanId()).thenReturn(11L);
        when(ipAddressDao.listByAssociatedVpc(1L, null)).thenReturn(Collections.singletonList(ip));

        VlanVO vlan = mock(VlanVO.class);
        when(vlan.getVlanGateway()).thenReturn("192.0.2.1");
        when(vlan.getVlanNetmask()).thenReturn("255.255.255.0");
        when(vlanDao.findById(11L)).thenReturn(vlan);

        assertTrue(service.isNextHopValid("192.0.2.42", vpc));
    }

    @Test
    public void isNextHopValidViaPrivateGateway() {
        Vpc vpc = mock(Vpc.class);
        when(vpc.getId()).thenReturn(1L);
        when(vpc.getCidr()).thenReturn("10.1.0.0/16");
        when(ipAddressDao.listByAssociatedVpc(1L, null)).thenReturn(Collections.emptyList());

        VpcGatewayVO gw = mock(VpcGatewayVO.class);
        when(gw.getGateway()).thenReturn("172.16.0.1");
        when(gw.getNetmask()).thenReturn("255.255.255.0");
        when(vpcGatewayDao.listByVpcId(1L)).thenReturn(Collections.singletonList(gw));

        assertTrue(service.isNextHopValid("172.16.0.99", vpc));
    }

    @Test
    public void isNextHopInvalidWhenOutsideAllRanges() {
        Vpc vpc = mock(Vpc.class);
        when(vpc.getId()).thenReturn(1L);
        when(vpc.getCidr()).thenReturn("10.1.0.0/16");
        when(ipAddressDao.listByAssociatedVpc(1L, null)).thenReturn(Collections.emptyList());
        when(vpcGatewayDao.listByVpcId(1L)).thenReturn(Collections.emptyList());

        assertFalse(service.isNextHopValid("203.0.113.7", vpc));
    }

    // ---- getVpcStaticRoutes ----

    @Test
    public void getVpcStaticRoutesProducesProfilePerRoute() {
        StaticRoute r1 = mock(StaticRoute.class);
        when(r1.getVpcGatewayId()).thenReturn(null);
        StaticRoute r2 = mock(StaticRoute.class);
        when(r2.getVpcGatewayId()).thenReturn(55L);
        VpcGateway gw = mock(VpcGateway.class);
        when(gw.getId()).thenReturn(55L);
        when(entityMgr.findById(VpcGateway.class, 55L)).thenReturn(gw);

        List<StaticRouteProfile> profiles = service.getVpcStaticRoutes(Arrays.asList(r1, r2));

        assertEquals(2, profiles.size());
        assertNotNull(profiles.get(0));
        assertNotNull(profiles.get(1));
    }

    @Test
    public void getVpcStaticRoutesEmptyInput() {
        List<StaticRouteProfile> profiles = service.getVpcStaticRoutes(Collections.emptyList());
        assertEquals(0, profiles.size());
    }

    @Test
    public void getVpcStaticRoutesCachesGatewayLookup() {
        StaticRoute r1 = mock(StaticRoute.class);
        when(r1.getVpcGatewayId()).thenReturn(77L);
        StaticRoute r2 = mock(StaticRoute.class);
        when(r2.getVpcGatewayId()).thenReturn(77L);
        VpcGateway gw = mock(VpcGateway.class);
        when(gw.getId()).thenReturn(77L);
        lenient().when(entityMgr.findById(VpcGateway.class, 77L)).thenReturn(gw);

        service.getVpcStaticRoutes(Arrays.asList(r1, r2));

        verify(entityMgr, times(1)).findById(VpcGateway.class, 77L);
    }

    // ---- applyStaticRoutes (profile overload) ----

    @Test
    public void applyStaticRoutesProfileEmptyShortCircuitsToTrue() throws Exception {
        assertTrue(service.applyStaticRoutes(Collections.<StaticRouteProfile>emptyList()));
        verify(vpcManager, never()).getVpcElements();
    }
}
