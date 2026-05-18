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
package com.cloud.server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.command.user.address.ListPublicIpAddressesCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.api.ApiDBUtils;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.VlanDao;
import com.cloud.dc.dao.VlanDetailsDao;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.IpAddress;
import com.cloud.network.IpAddressManager;
import com.cloud.network.IpAddressManagerImpl;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.NetworkAccountDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDomainDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class PublicIpAddressSearchServiceImplTest {

    @Mock
    AccountManager accountManager;

    @Mock
    IPAddressDao publicIpAddressDao;

    @Mock
    VlanDao vlanDao;

    @Mock
    VlanDetailsDao vlanDetailsDao;

    @Mock
    DomainDao domainDao;

    @Mock
    AccountDao accountDao;

    @Mock
    NetworkDao networkDao;

    @Mock
    LoadBalancerDao loadBalancerDao;

    @Mock
    ResourceTagDao resourceTagDao;

    @Mock
    IpAddressManager ipAddressManager;

    @Mock
    NetworkAccountDao networkAccountDao;

    @Mock
    NetworkDomainDao networkDomainDao;

    @Mock
    NetworkModel networkModel;

    @Mock
    VpcDao vpcDao;

    @InjectMocks
    PublicIpAddressSearchServiceImpl service = new PublicIpAddressSearchServiceImpl();

    private AutoCloseable closeable;
    private MockedStatic<ApiDBUtils> apiDBUtilsMock;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        apiDBUtilsMock = Mockito.mockStatic(ApiDBUtils.class);
        Account caller = Mockito.mock(Account.class);
        Mockito.lenient().when(caller.getType()).thenReturn(Account.Type.ADMIN);
        Mockito.lenient().when(caller.getAccountId()).thenReturn(11L);
        Mockito.lenient().when(caller.getDomainId()).thenReturn(22L);
        CallContext.register(Mockito.mock(com.cloud.user.User.class), caller);
    }

    @After
    public void tearDown() throws Exception {
        if (apiDBUtilsMock != null) {
            apiDBUtilsMock.close();
        }
        CallContext.unregister();
        closeable.close();
    }

    @Test
    public void testGetStatesForIpAddressSearchReturnsEmptyListWhenStateBlank() {
        ListPublicIpAddressesCmd cmd = Mockito.mock(ListPublicIpAddressesCmd.class);
        when(cmd.getState()).thenReturn("  ");

        List<IpAddress.State> result = service.getStatesForIpAddressSearch(cmd);

        Assert.assertTrue(result.isEmpty());
    }

    @Test
    public void testGetStatesForIpAddressSearchParsesCommaSeparatedStatesCaseInsensitively() {
        ListPublicIpAddressesCmd cmd = Mockito.mock(ListPublicIpAddressesCmd.class);
        when(cmd.getState()).thenReturn("free, Reserved, allocated");

        List<IpAddress.State> result = service.getStatesForIpAddressSearch(cmd);

        Assert.assertEquals(Arrays.asList(IpAddress.State.Free, IpAddress.State.Reserved, IpAddress.State.Allocated), result);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testGetStatesForIpAddressSearchThrowsOnUnknownStateToken() {
        ListPublicIpAddressesCmd cmd = Mockito.mock(ListPublicIpAddressesCmd.class);
        when(cmd.getState()).thenReturn("free, mystery");

        service.getStatesForIpAddressSearch(cmd);
    }

    @Test
    public void testSetParametersSetsStateListAndForcesForSystemVmsFalseWhenStrictnessEnabledAndFreeRequested() throws Exception {
        setSystemVmStrictnessDefault("true");
        ListPublicIpAddressesCmd cmd = mockCommonSetParametersCmd();
        SearchCriteria<IPAddressVO> sc = Mockito.mock(SearchCriteria.class);
        List<IpAddress.State> states = Collections.singletonList(IpAddress.State.Free);

        service.setParameters(sc, cmd, VlanType.VirtualNetwork, Boolean.FALSE, states);

        verify(sc).setJoinParameters("vlanSearch", "vlanType", VlanType.VirtualNetwork);
        verify(sc).setParameters("sourceNetworkId", 10L);
        verify(sc).setParameters("state", states.toArray());
        verify(sc).setParameters("forsystemvms", false);
    }

    @Test
    public void testSetParametersFallsBackToAllocatedWhenStatesEmptyAndAllocatedTrue() throws Exception {
        setSystemVmStrictnessDefault("false");
        ListPublicIpAddressesCmd cmd = mockCommonSetParametersCmd();
        SearchCriteria<IPAddressVO> sc = Mockito.mock(SearchCriteria.class);

        service.setParameters(sc, cmd, VlanType.VirtualNetwork, Boolean.TRUE, Collections.emptyList());

        verify(sc).setParameters("state", IpAddress.State.Allocated);
    }

    @Test
    public void testSetParametersAddsTagJoinParametersWhenTagsPresent() throws Exception {
        setSystemVmStrictnessDefault("false");
        ListPublicIpAddressesCmd cmd = mockCommonSetParametersCmd();
        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("k1", "v1");
        tags.put("k2", "v2");
        when(cmd.getTags()).thenReturn(tags);
        SearchCriteria<IPAddressVO> sc = Mockito.mock(SearchCriteria.class);

        service.setParameters(sc, cmd, VlanType.VirtualNetwork, Boolean.TRUE, Collections.emptyList());

        verify(sc).setJoinParameters("tagSearch", "resourceType", "PublicIpAddress");
        verify(sc).setJoinParameters("tagSearch", "key0", "k1");
        verify(sc).setJoinParameters("tagSearch", "value0", "v1");
        verify(sc).setJoinParameters("tagSearch", "key1", "k2");
        verify(sc).setJoinParameters("tagSearch", "value1", "v2");
    }

    @Test
    public void testSetParametersAddsVlanDetailJoinParametersWhenForProviderTrue() throws Exception {
        setSystemVmStrictnessDefault("false");
        ListPublicIpAddressesCmd cmd = mockCommonSetParametersCmd();
        when(cmd.isForProvider()).thenReturn(true);
        SearchCriteria<IPAddressVO> sc = Mockito.mock(SearchCriteria.class);

        service.setParameters(sc, cmd, VlanType.VirtualNetwork, Boolean.TRUE, Collections.emptyList());

        verify(sc).setJoinParameters(eq("vlanDetailSearch"), eq("name"), any(), any());
        verify(sc).setJoinParameters("vlanDetailSearch", "value", "true");
    }

    @SuppressWarnings("unchecked")
    @Test
    public void testSearchForIPAddressesRejectsAllocatedOnlyCombinedWithFreeState() {
        ListPublicIpAddressesCmd cmd = Mockito.mock(ListPublicIpAddressesCmd.class);
        when(cmd.getState()).thenReturn("Free");
        when(cmd.isAllocatedOnly()).thenReturn(Boolean.TRUE);
        when(cmd.isForVirtualNetwork()).thenReturn(Boolean.TRUE);

        try {
            service.searchForIPAddresses(cmd);
            Assert.fail("Expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            Assert.assertTrue(expected.getMessage().contains("allocatedonly is true"));
        }

        verify(publicIpAddressDao, never()).createSearchBuilder();
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testSearchForIPAddressesThrowsWhenDirectAttachedLookupByIpIdCannotResolveIp() {
        ListPublicIpAddressesCmd cmd = Mockito.mock(ListPublicIpAddressesCmd.class);
        when(cmd.isForVirtualNetwork()).thenReturn(Boolean.FALSE);
        when(cmd.getId()).thenReturn(44L);
        Mockito.lenient().when(publicIpAddressDao.findById(44L)).thenReturn(null);

        service.searchForIPAddresses(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void testSearchForIPAddressesThrowsWhenDirectAttachedLookupResolvesNonSharedNetwork() {
        ListPublicIpAddressesCmd cmd = Mockito.mock(ListPublicIpAddressesCmd.class);
        IPAddressVO ip = Mockito.mock(IPAddressVO.class);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        when(cmd.isForVirtualNetwork()).thenReturn(Boolean.FALSE);
        when(cmd.getId()).thenReturn(55L);
        Mockito.lenient().when(ip.getSourceNetworkId()).thenReturn(66L);
        Mockito.lenient().when(publicIpAddressDao.findById(55L)).thenReturn(ip);
        Mockito.lenient().when(networkDao.findById(66L)).thenReturn(network);
        Mockito.lenient().when(network.getGuestType()).thenReturn(Network.GuestType.Isolated);

        service.searchForIPAddresses(cmd);
    }

    @Test
    public void testSearchForIPAddressesAppliesAssociatedNetworkAccessChecksOnlyWhenNetworkExists() {
        SearchBuilder<IPAddressVO> sb = mockIpSearchBuilder();
        SearchCriteria<IPAddressVO> sc = Mockito.mock(SearchCriteria.class);
        SearchBuilder<VlanVO> vlanSb = Mockito.mock(SearchBuilder.class, Mockito.RETURNS_SELF);
        when(vlanSb.entity()).thenReturn(Mockito.mock(VlanVO.class));
        when(vlanDao.createSearchBuilder()).thenReturn(vlanSb);
        when(sb.create()).thenReturn(sc);
        when(publicIpAddressDao.search(eq(sc), any())).thenReturn(Collections.emptyList());
        ListPublicIpAddressesCmd cmd = Mockito.mock(ListPublicIpAddressesCmd.class);
        when(cmd.isForVirtualNetwork()).thenReturn(Boolean.TRUE);
        when(cmd.isAllocatedOnly()).thenReturn(Boolean.TRUE);
        when(cmd.getAssociatedNetworkId()).thenReturn(77L);
        when(networkDao.findById(77L)).thenReturn(Mockito.mock(NetworkVO.class));

        service.searchForIPAddresses(cmd);

        verify(accountManager).checkAccess(any(Account.class), Mockito.isNull(), eq(false), any(NetworkVO.class));
        verify(sc).setParameters("associatedNetworkIdEq", 77L);
    }

    @Test
    public void testSearchForIPAddressesAppliesVpcAccessChecksOnlyWhenVpcExists() {
        SearchBuilder<IPAddressVO> sb = mockIpSearchBuilder();
        SearchCriteria<IPAddressVO> sc = Mockito.mock(SearchCriteria.class);
        SearchBuilder<VlanVO> vlanSb = Mockito.mock(SearchBuilder.class, Mockito.RETURNS_SELF);
        when(vlanSb.entity()).thenReturn(Mockito.mock(VlanVO.class));
        when(vlanDao.createSearchBuilder()).thenReturn(vlanSb);
        when(sb.create()).thenReturn(sc);
        when(publicIpAddressDao.search(eq(sc), any())).thenReturn(Collections.emptyList());
        VpcVO vpc = Mockito.mock(VpcVO.class);
        ListPublicIpAddressesCmd cmd = Mockito.mock(ListPublicIpAddressesCmd.class);
        when(cmd.isForVirtualNetwork()).thenReturn(Boolean.TRUE);
        when(cmd.isAllocatedOnly()).thenReturn(Boolean.TRUE);
        when(cmd.getVpcId()).thenReturn(88L);
        when(vpcDao.findById(88L)).thenReturn(vpc);

        service.searchForIPAddresses(cmd);

        verify(accountManager).checkAccess(any(Account.class), Mockito.isNull(), eq(false), eq(vpc));
        verify(sc).setParameters("vpcId", 88L);
    }

    @Test
    public void testSearchForIPAddressesReturnsPaginatedAddressSortedResults() {
        SearchBuilder<IPAddressVO> sb = mockIpSearchBuilder();
        SearchCriteria<IPAddressVO> sc = Mockito.mock(SearchCriteria.class);
        SearchBuilder<VlanVO> vlanSb = Mockito.mock(SearchBuilder.class, Mockito.RETURNS_SELF);
        when(vlanSb.entity()).thenReturn(Mockito.mock(VlanVO.class));
        when(vlanDao.createSearchBuilder()).thenReturn(vlanSb);
        when(sb.create()).thenReturn(sc);
        when(publicIpAddressDao.search(eq(sc), any())).thenReturn(new java.util.ArrayList<>(List.of(
                mockIp(2L, "10.0.0.20"),
                mockIp(1L, "10.0.0.10"))));
        ListPublicIpAddressesCmd cmd = Mockito.mock(ListPublicIpAddressesCmd.class);
        when(cmd.isForVirtualNetwork()).thenReturn(Boolean.TRUE);
        when(cmd.isAllocatedOnly()).thenReturn(Boolean.TRUE);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(1L);

        Pair<List<? extends IpAddress>, Integer> result = service.searchForIPAddresses(cmd);

        Assert.assertEquals(2, result.second().intValue());
        Assert.assertEquals(1, result.first().size());
        Assert.assertEquals("10.0.0.10", ((IPAddressVO) result.first().get(0)).getAddress().addr());
    }

    private ListPublicIpAddressesCmd mockCommonSetParametersCmd() {
        ListPublicIpAddressesCmd cmd = Mockito.mock(ListPublicIpAddressesCmd.class);
        when(cmd.getNetworkId()).thenReturn(10L);
        when(cmd.getDisplay()).thenReturn(false);
        when(cmd.getForSystemVMs()).thenReturn(false);
        when(cmd.isForProvider()).thenReturn(false);
        return cmd;
    }

    @SuppressWarnings("unchecked")
    private SearchBuilder<IPAddressVO> mockIpSearchBuilder() {
        SearchBuilder<IPAddressVO> searchBuilder = Mockito.mock(SearchBuilder.class, Mockito.RETURNS_SELF);
        IPAddressVO entity = Mockito.mock(IPAddressVO.class);
        when(searchBuilder.entity()).thenReturn(entity);
        when(publicIpAddressDao.createSearchBuilder()).thenReturn(searchBuilder);
        return searchBuilder;
    }

    private IPAddressVO mockIp(long id, String address) {
        IPAddressVO ip = new IPAddressVO(new com.cloud.utils.net.Ip(address), 1L, 1L, 1L, false);
        ReflectionTestUtils.setField(ip, "id", id);
        return ip;
    }

    private void setSystemVmStrictnessDefault(String value) throws Exception {
        Field field = org.apache.cloudstack.framework.config.ConfigKey.class.getDeclaredField("_defaultValue");
        field.setAccessible(true);
        field.set(IpAddressManagerImpl.SystemVmPublicIpReservationModeStrictness, value);
    }
}
