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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.cloudstack.api.command.user.vpc.ListVPCOfferingsCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.dao.VpcOfferingJoinDao;
import com.cloud.api.query.vo.VpcOfferingJoinVO;
import com.cloud.domain.Domain;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.vpc.dao.VpcOfferingDao;
import com.cloud.network.vpc.dao.VpcOfferingDetailsDao;
import com.cloud.network.vpc.dao.VpcOfferingServiceMapDao;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.utils.Pair;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class VpcOfferingQueryServiceImplTest {

    @Mock private VpcOfferingDao vpcOfferingDao;
    @Mock private VpcOfferingJoinDao vpcOfferingJoinDao;
    @Mock private VpcOfferingDetailsDao vpcOfferingDetailsDao;
    @Mock private VpcOfferingServiceMapDao vpcOfferingServiceMapDao;
    @Mock private DomainDao domainDao;
    @Mock private EntityManager entityMgr;

    @InjectMocks
    private VpcOfferingQueryServiceImpl service;

    private AccountVO adminAccount;
    private User user;

    @Before
    public void setUp() {
        adminAccount = new AccountVO("admin", 1L, "domain", Account.Type.ADMIN, "uuid");
        adminAccount.setId(2L);
        adminAccount.setDomainId(1L);
        user = new UserVO(1, "u", "p", "f", "l", "e", "tz",
                java.util.UUID.randomUUID().toString(), User.Source.UNKNOWN);
        CallContext.register(user, adminAccount);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // ---- getVpcOffering ----

    @Test
    public void getVpcOfferingReturnsNullWhenMissing() {
        when(vpcOfferingDao.findById(99L)).thenReturn(null);
        assertNull(service.getVpcOffering(99L));
    }

    @Test
    public void getVpcOfferingReturnsRow() {
        VpcOfferingVO vo = mock(VpcOfferingVO.class);
        when(vpcOfferingDao.findById(42L)).thenReturn(vo);
        assertSame(vo, service.getVpcOffering(42L));
    }

    // ---- getVpcOffSvcProvidersMap ----

    @Test
    public void getVpcOffSvcProvidersMapEmpty() {
        when(vpcOfferingServiceMapDao.listByVpcOffId(7L)).thenReturn(Collections.emptyList());
        Map<Service, Set<Provider>> result = service.getVpcOffSvcProvidersMap(7L);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void getVpcOffSvcProvidersMapBuildsMap() {
        VpcOfferingServiceMapVO row = mock(VpcOfferingServiceMapVO.class);
        when(row.getService()).thenReturn(Service.Dns.getName());
        when(row.getProvider()).thenReturn(Provider.VPCVirtualRouter.getName());
        VpcOfferingServiceMapVO row2 = mock(VpcOfferingServiceMapVO.class);
        when(row2.getService()).thenReturn(Service.Dns.getName());
        when(row2.getProvider()).thenReturn(Provider.Netscaler.getName());
        when(vpcOfferingServiceMapDao.listByVpcOffId(8L))
                .thenReturn(Arrays.asList(row, row2));

        Map<Service, Set<Provider>> result = service.getVpcOffSvcProvidersMap(8L);

        assertEquals(1, result.size());
        Set<Provider> providers = result.get(Service.Dns);
        assertEquals(2, providers.size());
        assertTrue(providers.contains(Provider.VPCVirtualRouter));
        assertTrue(providers.contains(Provider.Netscaler));
    }

    // ---- areServicesSupportedByVpcOffering ----

    @Test
    public void areServicesSupportedDelegatesToDao() {
        Service[] services = new Service[] { Service.Dns };
        when(vpcOfferingServiceMapDao.areServicesSupportedByVpcOffering(5L, services))
                .thenReturn(true);
        assertTrue(service.areServicesSupportedByVpcOffering(5L, Service.Dns));
        verify(vpcOfferingServiceMapDao).areServicesSupportedByVpcOffering(eq(5L), any(Service[].class));
    }

    // ---- getVpcOfferingDomains ----

    @Test
    public void getVpcOfferingDomainsThrowsWhenOfferingMissing() {
        when(entityMgr.findById(eq(VpcOffering.class), eq(100L))).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.getVpcOfferingDomains(100L));
    }

    @Test
    public void getVpcOfferingDomainsReturnsIds() {
        when(entityMgr.findById(eq(VpcOffering.class), eq(11L))).thenReturn(mock(VpcOfferingVO.class));
        when(vpcOfferingDetailsDao.findDomainIds(11L)).thenReturn(Arrays.asList(1L, 2L, 3L));
        List<Long> ids = service.getVpcOfferingDomains(11L);
        assertEquals(3, ids.size());
    }

    // ---- getVpcOfferingZones ----

    @Test
    public void getVpcOfferingZonesThrowsWhenOfferingMissing() {
        when(entityMgr.findById(eq(VpcOffering.class), eq(200L))).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.getVpcOfferingZones(200L));
    }

    @Test
    public void getVpcOfferingZonesReturnsIds() {
        when(entityMgr.findById(eq(VpcOffering.class), eq(12L))).thenReturn(mock(VpcOfferingVO.class));
        when(vpcOfferingDetailsDao.findZoneIds(12L)).thenReturn(Arrays.asList(10L));
        List<Long> ids = service.getVpcOfferingZones(12L);
        assertEquals(1, ids.size());
        assertEquals(Long.valueOf(10L), ids.get(0));
    }

    // ---- listVpcOfferings ----

    private ListVPCOfferingsCmd newCmd() {
        ListVPCOfferingsCmd cmd = mock(ListVPCOfferingsCmd.class);
        lenient().when(cmd.getDomainId()).thenReturn(null);
        lenient().when(cmd.getZoneId()).thenReturn(null);
        lenient().when(cmd.getId()).thenReturn(null);
        return cmd;
    }

    @Test
    public void listVpcOfferingsReturnsEmptyWhenNoneFound() {
        ListVPCOfferingsCmd cmd = newCmd();
        SearchCriteria<VpcOfferingJoinVO> sc = mock(SearchCriteria.class);
        when(vpcOfferingJoinDao.createSearchCriteria()).thenReturn(sc);
        when(vpcOfferingJoinDao.search(any(), any())).thenReturn(Collections.emptyList());

        Pair<List<? extends VpcOffering>, Integer> result = service.listVpcOfferings(cmd);

        assertNotNull(result);
        assertEquals(Integer.valueOf(0), result.second());
    }

    @Test
    public void listVpcOfferingsAppliesAllFilters() {
        ListVPCOfferingsCmd cmd = newCmd();
        when(cmd.getId()).thenReturn(7L);
        when(cmd.getVpcOffName()).thenReturn("foo");
        when(cmd.getDisplayText()).thenReturn("desc");
        when(cmd.getKeyword()).thenReturn("kw");
        when(cmd.getIsDefault()).thenReturn(Boolean.TRUE);
        when(cmd.getState()).thenReturn("Enabled");
        SearchCriteria<VpcOfferingJoinVO> sc = mock(SearchCriteria.class);
        SearchCriteria<VpcOfferingJoinVO> ssc = mock(SearchCriteria.class);
        when(vpcOfferingJoinDao.createSearchCriteria()).thenReturn(sc, ssc);
        when(vpcOfferingJoinDao.search(any(), any())).thenReturn(Collections.emptyList());

        service.listVpcOfferings(cmd);

        verify(sc).addAnd(eq("name"), eq(SearchCriteria.Op.LIKE), eq("%foo%"));
        verify(sc).addAnd(eq("displayText"), eq(SearchCriteria.Op.LIKE), eq("%desc%"));
        verify(sc).addAnd(eq("isDefault"), eq(SearchCriteria.Op.EQ), eq(Boolean.TRUE));
        verify(sc).addAnd(eq("state"), eq(SearchCriteria.Op.EQ), eq("Enabled"));
        verify(sc).addAnd(eq("id"), eq(SearchCriteria.Op.EQ), eq(7L));
    }

    @Test
    public void listVpcOfferingsAppliesZoneFilter() {
        ListVPCOfferingsCmd cmd = newCmd();
        when(cmd.getZoneId()).thenReturn(99L);
        SearchCriteria<VpcOfferingJoinVO> sc = mock(SearchCriteria.class);
        when(vpcOfferingJoinDao.createSearchCriteria()).thenReturn(sc);
        SearchBuilder<VpcOfferingJoinVO> sb = mock(SearchBuilder.class);
        VpcOfferingJoinVO entityProxy = mock(VpcOfferingJoinVO.class);
        when(vpcOfferingJoinDao.createSearchBuilder()).thenReturn(sb);
        lenient().when(sb.entity()).thenReturn(entityProxy);
        SearchCriteria<VpcOfferingJoinVO> zoneSC = mock(SearchCriteria.class);
        when(sb.create()).thenReturn(zoneSC);
        when(vpcOfferingJoinDao.search(any(), any())).thenReturn(Collections.emptyList());

        service.listVpcOfferings(cmd);

        verify(zoneSC).setParameters("zoneId", "99");
        verify(sc).addAnd(eq("zoneId"), eq(SearchCriteria.Op.SC), eq(zoneSC));
    }

    @Test
    public void listVpcOfferingsFailsWhenDomainIdIsInvalid() {
        ListVPCOfferingsCmd cmd = newCmd();
        when(cmd.getDomainId()).thenReturn(33L);
        SearchCriteria<VpcOfferingJoinVO> sc = mock(SearchCriteria.class);
        lenient().when(vpcOfferingJoinDao.createSearchCriteria()).thenReturn(sc);
        when(entityMgr.findById(eq(Domain.class), eq(33L))).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.listVpcOfferings(cmd));
    }

    @Test
    public void listVpcOfferingsFailsWhenDomainNotChildOfCaller() {
        ListVPCOfferingsCmd cmd = newCmd();
        when(cmd.getDomainId()).thenReturn(33L);
        SearchCriteria<VpcOfferingJoinVO> sc = mock(SearchCriteria.class);
        lenient().when(vpcOfferingJoinDao.createSearchCriteria()).thenReturn(sc);
        Domain domain = mock(Domain.class);
        when(domain.getUuid()).thenReturn("dom-uuid");
        when(entityMgr.findById(eq(Domain.class), eq(33L))).thenReturn(domain);
        when(domainDao.isChildDomain(anyLong(), eq(33L))).thenReturn(false);

        assertThrows(InvalidParameterValueException.class,
                () -> service.listVpcOfferings(cmd));
    }

    @Test
    public void listVpcOfferingsThrowsOnInvalidSupportedService() {
        ListVPCOfferingsCmd cmd = newCmd();
        when(cmd.getSupportedServices()).thenReturn(Arrays.asList("NotARealService"));
        SearchCriteria<VpcOfferingJoinVO> sc = mock(SearchCriteria.class);
        when(vpcOfferingJoinDao.createSearchCriteria()).thenReturn(sc);
        VpcOfferingJoinVO offering = mock(VpcOfferingJoinVO.class);
        lenient().when(offering.getDomainId()).thenReturn("");
        when(vpcOfferingJoinDao.search(any(), any()))
                .thenReturn(Collections.singletonList(offering));

        assertThrows(InvalidParameterValueException.class,
                () -> service.listVpcOfferings(cmd));
    }

    @Test
    public void listVpcOfferingsFiltersBySupportedServices() {
        ListVPCOfferingsCmd cmd = newCmd();
        when(cmd.getSupportedServices()).thenReturn(Arrays.asList(Service.Dns.getName()));
        SearchCriteria<VpcOfferingJoinVO> sc = mock(SearchCriteria.class);
        when(vpcOfferingJoinDao.createSearchCriteria()).thenReturn(sc);
        VpcOfferingJoinVO matched = mock(VpcOfferingJoinVO.class);
        when(matched.getId()).thenReturn(101L);
        lenient().when(matched.getDomainId()).thenReturn("");
        VpcOfferingJoinVO unmatched = mock(VpcOfferingJoinVO.class);
        when(unmatched.getId()).thenReturn(202L);
        lenient().when(unmatched.getDomainId()).thenReturn("");
        when(vpcOfferingJoinDao.search(any(), any()))
                .thenReturn(Arrays.asList(matched, unmatched));
        when(vpcOfferingServiceMapDao.areServicesSupportedByVpcOffering(eq(101L), any(Service[].class)))
                .thenReturn(true);
        when(vpcOfferingServiceMapDao.areServicesSupportedByVpcOffering(eq(202L), any(Service[].class)))
                .thenReturn(false);

        Pair<List<? extends VpcOffering>, Integer> result = service.listVpcOfferings(cmd);

        assertEquals(Integer.valueOf(1), result.second());
        assertEquals(101L, result.first().get(0).getId());
    }
}
