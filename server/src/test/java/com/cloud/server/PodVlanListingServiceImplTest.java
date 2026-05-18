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

import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.command.admin.pod.ListPodsByCmd;
import org.apache.cloudstack.api.command.admin.vlan.ListVlanIpRangesCmd;
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
import org.mockito.stubbing.Answer;

import com.cloud.api.ApiDBUtils;
import com.cloud.dc.AccountVlanMapVO;
import com.cloud.dc.DomainVlanMapVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.Pod;
import com.cloud.dc.PodVlanMapVO;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.AccountVlanMapDao;
import com.cloud.dc.dao.DomainVlanMapDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.dc.dao.PodVlanMapDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.domain.DomainVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectManager;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class PodVlanListingServiceImplTest {

    @Mock
    private AccountManager _accountMgr;
    @Mock
    private HostPodDao _hostPodDao;
    @Mock
    private VlanDao _vlanDao;
    @Mock
    private AccountVlanMapDao _accountVlanMapDao;
    @Mock
    private DomainVlanMapDao _domainVlanMapDao;
    @Mock
    private PodVlanMapDao _podVlanMapDao;
    @Mock
    private AccountDao _accountDao;
    @Mock
    private ProjectManager _projectMgr;

    @InjectMocks
    private PodVlanListingServiceImpl service = new PodVlanListingServiceImpl();

    @Mock
    private Account callerAccount;
    @Mock
    private User callerUser;

    private AutoCloseable closeable;
    private MockedStatic<ApiDBUtils> apiDBUtilsMock;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        CallContext.register(callerUser, callerAccount);
        apiDBUtilsMock = Mockito.mockStatic(ApiDBUtils.class);
    }

    @After
    public void tearDown() throws Exception {
        if (apiDBUtilsMock != null) {
            apiDBUtilsMock.close();
        }
        CallContext.unregister();
        closeable.close();
    }

    // ---------------------------------------------------------------
    // Helper: create a well-behaved SearchBuilder mock whose entity()
    // returns the supplied entity instance; fluent builder methods
    // return "this" so the code can chain .and().op()... calls.
    // ---------------------------------------------------------------
    @SuppressWarnings("unchecked")
    private <T> SearchBuilder<T> newSearchBuilder(T entity) {
        SearchBuilder<T> sb = Mockito.mock(SearchBuilder.class, (Answer<Object>) invocation -> {
            String name = invocation.getMethod().getName();
            if ("entity".equals(name)) {
                return entity;
            }
            Class<?> returnType = invocation.getMethod().getReturnType();
            if (returnType.isInstance(invocation.getMock())) {
                return invocation.getMock();
            }
            return Mockito.RETURNS_DEFAULTS.answer(invocation);
        });
        return sb;
    }

    // Helper: minimal cmd stub for searchForPods — all optional fields return null
    private ListPodsByCmd minimalPodsCmd() {
        ListPodsByCmd cmd = Mockito.mock(ListPodsByCmd.class);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(20L);
        when(cmd.getZoneId()).thenReturn(null);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getPodName()).thenReturn(null);
        when(cmd.getAllocationState()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);
        when(cmd.getStorageAccessGroup()).thenReturn(null);
        return cmd;
    }

    // Helper: minimal cmd stub for searchForVlans — all optional fields return null
    private ListVlanIpRangesCmd minimalVlansCmd() {
        ListVlanIpRangesCmd cmd = Mockito.mock(ListVlanIpRangesCmd.class);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(20L);
        when(cmd.getAccountName()).thenReturn(null);
        when(cmd.getDomainId()).thenReturn(null);
        when(cmd.getProjectId()).thenReturn(null);
        when(cmd.isForVirtualNetwork()).thenReturn(null);
        when(cmd.getPodId()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getVlan()).thenReturn(null);
        when(cmd.getZoneId()).thenReturn(null);
        when(cmd.getNetworkId()).thenReturn(null);
        when(cmd.getPhysicalNetworkId()).thenReturn(null);
        return cmd;
    }

    // ---------------------------------------------------------------
    // searchForPods tests
    // ---------------------------------------------------------------

    @Test
    public void searchForPods_minimal_callsAccountMgrAndReturnsResult() {
        ListPodsByCmd cmd = minimalPodsCmd();
        when(_accountMgr.checkAccessAndSpecifyAuthority(callerAccount, null)).thenReturn(null);

        HostPodVO podVO = Mockito.mock(HostPodVO.class);
        SearchBuilder<HostPodVO> sb = newSearchBuilder(podVO);
        SearchCriteria<HostPodVO> sc = Mockito.mock(SearchCriteria.class);
        when(_hostPodDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        List<HostPodVO> pods = Collections.singletonList(podVO);
        when(_hostPodDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(pods, 1));

        Pair<List<? extends Pod>, Integer> result = service.searchForPods(cmd);

        verify(_accountMgr).checkAccessAndSpecifyAuthority(callerAccount, null);
        Assert.assertEquals(1, (int) result.second());
        Assert.assertSame(pods, result.first());
    }

    @Test
    public void searchForPods_withId_setsIdParameter() {
        ListPodsByCmd cmd = minimalPodsCmd();
        when(cmd.getId()).thenReturn(42L);
        when(_accountMgr.checkAccessAndSpecifyAuthority(callerAccount, null)).thenReturn(null);

        SearchBuilder<HostPodVO> sb = newSearchBuilder(Mockito.mock(HostPodVO.class));
        SearchCriteria<HostPodVO> sc = Mockito.mock(SearchCriteria.class);
        when(_hostPodDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_hostPodDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForPods(cmd);

        verify(sc).setParameters("id", 42L);
    }

    @Test
    public void searchForPods_withName_setsNameParameter() {
        ListPodsByCmd cmd = minimalPodsCmd();
        when(cmd.getPodName()).thenReturn("myPod");
        when(_accountMgr.checkAccessAndSpecifyAuthority(callerAccount, null)).thenReturn(null);

        SearchBuilder<HostPodVO> sb = newSearchBuilder(Mockito.mock(HostPodVO.class));
        SearchCriteria<HostPodVO> sc = Mockito.mock(SearchCriteria.class);
        when(_hostPodDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_hostPodDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForPods(cmd);

        verify(sc).setParameters("name", "myPod");
    }

    @Test
    public void searchForPods_withZoneOverride_usesAuthorizedZoneId() {
        ListPodsByCmd cmd = minimalPodsCmd();
        when(cmd.getZoneId()).thenReturn(10L);
        when(_accountMgr.checkAccessAndSpecifyAuthority(callerAccount, 10L)).thenReturn(99L);

        SearchBuilder<HostPodVO> sb = newSearchBuilder(Mockito.mock(HostPodVO.class));
        SearchCriteria<HostPodVO> sc = Mockito.mock(SearchCriteria.class);
        when(_hostPodDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_hostPodDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForPods(cmd);

        verify(sc).setParameters("dataCenterId", 99L);
    }

    @Test
    public void searchForPods_withAllocationState_setsParameter() {
        ListPodsByCmd cmd = minimalPodsCmd();
        when(cmd.getAllocationState()).thenReturn("Enabled");
        when(_accountMgr.checkAccessAndSpecifyAuthority(callerAccount, null)).thenReturn(null);

        SearchBuilder<HostPodVO> sb = newSearchBuilder(Mockito.mock(HostPodVO.class));
        SearchCriteria<HostPodVO> sc = Mockito.mock(SearchCriteria.class);
        when(_hostPodDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_hostPodDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForPods(cmd);

        verify(sc).setParameters("allocationState", "Enabled");
    }

    @Test
    public void searchForPods_withKeyword_addsNestedSearchCriteria() {
        ListPodsByCmd cmd = minimalPodsCmd();
        when(cmd.getKeyword()).thenReturn("alpha");
        when(_accountMgr.checkAccessAndSpecifyAuthority(callerAccount, null)).thenReturn(null);

        SearchBuilder<HostPodVO> sb = newSearchBuilder(Mockito.mock(HostPodVO.class));
        SearchCriteria<HostPodVO> sc = Mockito.mock(SearchCriteria.class);
        SearchCriteria<HostPodVO> ssc = Mockito.mock(SearchCriteria.class);
        when(_hostPodDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_hostPodDao.createSearchCriteria()).thenReturn(ssc);
        when(_hostPodDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForPods(cmd);

        verify(ssc).addOr("name", SearchCriteria.Op.LIKE, "%alpha%");
        verify(ssc).addOr("description", SearchCriteria.Op.LIKE, "%alpha%");
        verify(sc).addAnd("name", SearchCriteria.Op.SC, ssc);
    }

    @Test
    public void searchForPods_withStorageAccessGroup_addsFourLikePatterns() {
        ListPodsByCmd cmd = minimalPodsCmd();
        when(cmd.getStorageAccessGroup()).thenReturn("sag1");
        when(_accountMgr.checkAccessAndSpecifyAuthority(callerAccount, null)).thenReturn(null);

        SearchBuilder<HostPodVO> sb = newSearchBuilder(Mockito.mock(HostPodVO.class));
        SearchCriteria<HostPodVO> sc = Mockito.mock(SearchCriteria.class);
        when(_hostPodDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_hostPodDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForPods(cmd);

        verify(sc).setParameters("storageAccessGroupExact", "sag1");
        verify(sc).setParameters("storageAccessGroupPrefix", "sag1,%");
        verify(sc).setParameters("storageAccessGroupSuffix", "%,sag1");
        verify(sc).setParameters("storageAccessGroupMiddle", "%,sag1,%");
    }

    // ---------------------------------------------------------------
    // searchForVlans tests
    // ---------------------------------------------------------------

    @Test
    public void searchForVlans_byAccountAndDomain_resolvesAccountId() {
        ListVlanIpRangesCmd cmd = minimalVlansCmd();
        when(cmd.getAccountName()).thenReturn("alice");
        when(cmd.getDomainId()).thenReturn(5L);

        Account account = Mockito.mock(Account.class);
        when(account.getId()).thenReturn(77L);
        when(_accountDao.findActiveAccount("alice", 5L)).thenReturn(account);

        // domainId != null → ApiDBUtils.findDomainById called for domain join
        DomainVO domain = Mockito.mock(DomainVO.class);
        apiDBUtilsMock.when(() -> ApiDBUtils.findDomainById(5L)).thenReturn(domain);

        SearchBuilder<VlanVO> sb = newSearchBuilder(Mockito.mock(VlanVO.class));
        SearchBuilder<AccountVlanMapVO> acctSearch = newSearchBuilder(Mockito.mock(AccountVlanMapVO.class));
        SearchBuilder<DomainVlanMapVO> domSearch = newSearchBuilder(Mockito.mock(DomainVlanMapVO.class));
        SearchCriteria<VlanVO> sc = Mockito.mock(SearchCriteria.class);
        when(_vlanDao.createSearchBuilder()).thenReturn(sb);
        Mockito.lenient().when(_accountVlanMapDao.createSearchBuilder()).thenReturn(acctSearch);
        Mockito.lenient().when(_domainVlanMapDao.createSearchBuilder()).thenReturn(domSearch);
        when(sb.create()).thenReturn(sc);
        when(_vlanDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForVlans(cmd);

        verify(sc).setJoinParameters("accountVlanMapSearch", "accountId", 77L);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void searchForVlans_accountNotFound_throwsInvalidParameterValueExceptionWithDomainProxy() {
        ListVlanIpRangesCmd cmd = minimalVlansCmd();
        when(cmd.getAccountName()).thenReturn("ghost");
        when(cmd.getDomainId()).thenReturn(7L);
        when(_accountDao.findActiveAccount("ghost", 7L)).thenReturn(null);

        DomainVO domain = Mockito.mock(DomainVO.class);
        when(domain.getUuid()).thenReturn("dom-7-uuid");
        apiDBUtilsMock.when(() -> ApiDBUtils.findDomainById(7L)).thenReturn(domain);

        service.searchForVlans(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void searchForVlans_accountAndProjectIdMutuallyExclusive_throws() {
        ListVlanIpRangesCmd cmd = minimalVlansCmd();
        when(cmd.getAccountName()).thenReturn("alice");
        when(cmd.getDomainId()).thenReturn(5L);
        when(cmd.getProjectId()).thenReturn(10L);

        service.searchForVlans(cmd);
    }

    @Test
    public void searchForVlans_byProjectId_setsAccountIdFromProject() {
        ListVlanIpRangesCmd cmd = minimalVlansCmd();
        when(cmd.getProjectId()).thenReturn(20L);

        Project project = Mockito.mock(Project.class);
        when(project.getProjectAccountId()).thenReturn(55L);
        when(_projectMgr.getProject(20L)).thenReturn(project);

        SearchBuilder<VlanVO> sb = newSearchBuilder(Mockito.mock(VlanVO.class));
        SearchBuilder<AccountVlanMapVO> acctSearch = newSearchBuilder(Mockito.mock(AccountVlanMapVO.class));
        SearchCriteria<VlanVO> sc = Mockito.mock(SearchCriteria.class);
        when(_vlanDao.createSearchBuilder()).thenReturn(sb);
        Mockito.lenient().when(_accountVlanMapDao.createSearchBuilder()).thenReturn(acctSearch);
        when(sb.create()).thenReturn(sc);
        when(_vlanDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForVlans(cmd);

        verify(sc).setJoinParameters("accountVlanMapSearch", "accountId", 55L);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void searchForVlans_unknownProjectId_throwsInvalidParameterValueException() {
        ListVlanIpRangesCmd cmd = minimalVlansCmd();
        when(cmd.getProjectId()).thenReturn(99L);
        when(_projectMgr.getProject(99L)).thenReturn(null);

        service.searchForVlans(cmd);
    }

    @Test
    public void searchForVlans_forVirtualTrue_setsVirtualNetworkVlanType() {
        ListVlanIpRangesCmd cmd = minimalVlansCmd();
        when(cmd.isForVirtualNetwork()).thenReturn(Boolean.TRUE);

        SearchBuilder<VlanVO> sb = newSearchBuilder(Mockito.mock(VlanVO.class));
        SearchCriteria<VlanVO> sc = Mockito.mock(SearchCriteria.class);
        when(_vlanDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_vlanDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForVlans(cmd);

        verify(sc).setParameters("vlanType", VlanType.VirtualNetwork.toString());
    }

    @Test
    public void searchForVlans_forVirtualFalse_setsDirectAttachedVlanType() {
        ListVlanIpRangesCmd cmd = minimalVlansCmd();
        when(cmd.isForVirtualNetwork()).thenReturn(Boolean.FALSE);

        SearchBuilder<VlanVO> sb = newSearchBuilder(Mockito.mock(VlanVO.class));
        SearchCriteria<VlanVO> sc = Mockito.mock(SearchCriteria.class);
        when(_vlanDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_vlanDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForVlans(cmd);

        verify(sc).setParameters("vlanType", VlanType.DirectAttached.toString());
    }

    @Test
    public void searchForVlans_byPodId_addsPodVlanMapJoin() {
        ListVlanIpRangesCmd cmd = minimalVlansCmd();
        when(cmd.getPodId()).thenReturn(15L);

        SearchBuilder<VlanVO> sb = newSearchBuilder(Mockito.mock(VlanVO.class));
        SearchBuilder<PodVlanMapVO> podSearch = newSearchBuilder(Mockito.mock(PodVlanMapVO.class));
        SearchCriteria<VlanVO> sc = Mockito.mock(SearchCriteria.class);
        when(_vlanDao.createSearchBuilder()).thenReturn(sb);
        when(_podVlanMapDao.createSearchBuilder()).thenReturn(podSearch);
        when(sb.create()).thenReturn(sc);
        when(_vlanDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForVlans(cmd);

        verify(sb).join(eq("podVlanMapSearch"), eq(podSearch), Mockito.any(), Mockito.any(), eq(JoinBuilder.JoinType.INNER));
        verify(sc).setJoinParameters("podVlanMapSearch", "podId", 15L);
    }

    @Test
    public void searchForVlans_byDomainId_addsDomainVlanMapJoinAndSetsParam() {
        ListVlanIpRangesCmd cmd = minimalVlansCmd();
        when(cmd.getDomainId()).thenReturn(8L);

        DomainVO domain = Mockito.mock(DomainVO.class);
        apiDBUtilsMock.when(() -> ApiDBUtils.findDomainById(8L)).thenReturn(domain);

        SearchBuilder<VlanVO> sb = newSearchBuilder(Mockito.mock(VlanVO.class));
        SearchBuilder<DomainVlanMapVO> domSearch = newSearchBuilder(Mockito.mock(DomainVlanMapVO.class));
        SearchCriteria<VlanVO> sc = Mockito.mock(SearchCriteria.class);
        when(_vlanDao.createSearchBuilder()).thenReturn(sb);
        when(_domainVlanMapDao.createSearchBuilder()).thenReturn(domSearch);
        when(sb.create()).thenReturn(sc);
        when(_vlanDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForVlans(cmd);

        verify(sb).join(eq("domainVlanMapSearch"), eq(domSearch), Mockito.any(), Mockito.any(), eq(JoinBuilder.JoinType.INNER));
        verify(sc).setJoinParameters("domainVlanMapSearch", "domainId", 8L);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void searchForVlans_byUnknownDomainId_throws() {
        ListVlanIpRangesCmd cmd = minimalVlansCmd();
        when(cmd.getDomainId()).thenReturn(999L);

        apiDBUtilsMock.when(() -> ApiDBUtils.findDomainById(999L)).thenReturn(null);

        SearchBuilder<VlanVO> sb = newSearchBuilder(Mockito.mock(VlanVO.class));
        SearchCriteria<VlanVO> sc = Mockito.mock(SearchCriteria.class);
        when(_vlanDao.createSearchBuilder()).thenReturn(sb);
        Mockito.lenient().when(sb.create()).thenReturn(sc);

        service.searchForVlans(cmd);
    }

    @Test
    public void searchForVlans_byKeyword_skipsScalarParameterBranch() {
        ListVlanIpRangesCmd cmd = minimalVlansCmd();
        when(cmd.getKeyword()).thenReturn("key");

        SearchBuilder<VlanVO> sb = newSearchBuilder(Mockito.mock(VlanVO.class));
        SearchCriteria<VlanVO> sc = Mockito.mock(SearchCriteria.class);
        SearchCriteria<VlanVO> ssc = Mockito.mock(SearchCriteria.class);
        when(_vlanDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_vlanDao.createSearchCriteria()).thenReturn(ssc);
        when(_vlanDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForVlans(cmd);

        verify(ssc).addOr("vlanTag", SearchCriteria.Op.LIKE, "%key%");
        verify(ssc).addOr("ipRange", SearchCriteria.Op.LIKE, "%key%");
        verify(sc).addAnd("vlanTag", SearchCriteria.Op.SC, ssc);
        // Scalar branch must NOT be executed when keyword is set
        verify(sc, never()).setParameters(eq("id"), any());
        verify(sc, never()).setParameters(eq("vlan"), any());
    }
}
