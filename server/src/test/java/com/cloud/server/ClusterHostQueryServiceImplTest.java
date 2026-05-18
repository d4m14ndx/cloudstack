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

import org.apache.cloudstack.api.command.admin.cluster.ListClustersCmd;
import org.apache.cloudstack.api.command.admin.host.ListHostsCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.stubbing.Answer;

import com.cloud.cpu.CPU;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.host.HostTagVO;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class ClusterHostQueryServiceImplTest {

    @Mock
    private AccountManager _accountMgr;
    @Mock
    private ClusterDao _clusterDao;
    @Mock
    private HostDao _hostDao;
    @Mock
    private HighAvailabilityManager _haMgr;
    @Mock
    private HostTagsDao _hostTagsDao;

    @InjectMocks
    private ClusterHostQueryServiceImpl service = new ClusterHostQueryServiceImpl();

    @Mock
    private Account callerAccount;
    @Mock
    private User callerUser;

    private AutoCloseable closeable;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        CallContext.register(callerUser, callerAccount);
    }

    @After
    public void tearDown() throws Exception {
        CallContext.unregister();
        closeable.close();
    }

    @Test
    public void searchForClustersByZoneChecksAuthorityAndHypervisor() {
        SearchCriteria<ClusterVO> sc = Mockito.mock(SearchCriteria.class);
        when(_clusterDao.createSearchCriteria()).thenReturn(sc);
        when(_accountMgr.checkAccessAndSpecifyAuthority(callerAccount, 11L)).thenReturn(21L);
        when(_clusterDao.search(eq(sc), any(Filter.class))).thenReturn(Collections.emptyList());

        service.searchForClusters(11L, 3L, 7L, "KVM");

        verify(_accountMgr).checkAccessAndSpecifyAuthority(callerAccount, 11L);
        verify(sc).addAnd("dataCenterId", SearchCriteria.Op.EQ, 21L);
        verify(sc).addAnd("hypervisorType", SearchCriteria.Op.EQ, "KVM");
    }

    @Test
    public void searchForClustersByZoneBuildsAscendingIdFilter() {
        SearchCriteria<ClusterVO> sc = Mockito.mock(SearchCriteria.class);
        when(_clusterDao.createSearchCriteria()).thenReturn(sc);
        when(_accountMgr.checkAccessAndSpecifyAuthority(callerAccount, 11L)).thenReturn(11L);
        when(_clusterDao.search(eq(sc), any(Filter.class))).thenReturn(Collections.emptyList());
        ArgumentCaptor<Filter> filterCaptor = ArgumentCaptor.forClass(Filter.class);

        service.searchForClusters(11L, 4L, 9L, "KVM");

        verify(_clusterDao).search(eq(sc), filterCaptor.capture());
        Filter filter = filterCaptor.getValue();
        Assert.assertEquals(Long.valueOf(4L), filter.getOffset());
        Assert.assertEquals(Long.valueOf(9L), filter.getLimit());
        Assert.assertTrue(filter.getOrderBy().contains("id"));
        Assert.assertTrue(filter.getOrderBy().contains("ASC"));
    }

    @Test
    public void searchForClustersCommandSetsIdNamePodAndAuthorizedZone() {
        ListClustersCmd cmd = Mockito.mock(ListClustersCmd.class);
        when(cmd.getId()).thenReturn(9L);
        when(cmd.getClusterName()).thenReturn("alpha");
        when(cmd.getPodId()).thenReturn(10L);
        when(cmd.getZoneId()).thenReturn(11L);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(25L);
        when(_accountMgr.checkAccessAndSpecifyAuthority(callerAccount, 11L)).thenReturn(12L);

        SearchBuilder<ClusterVO> sb = newSearchBuilder(Mockito.mock(ClusterVO.class));
        SearchCriteria<ClusterVO> sc = Mockito.mock(SearchCriteria.class);
        when(_clusterDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_clusterDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForClusters(cmd);

        verify(sc).setParameters("id", 9L);
        verify(sc).setParameters("name", "alpha");
        verify(sc).setParameters("podId", 10L);
        verify(sc).setParameters("dataCenterId", 12L);
    }

    @Test
    public void searchForClustersCommandNormalizesHypervisorType() {
        ListClustersCmd cmd = Mockito.mock(ListClustersCmd.class);
        when(cmd.getZoneId()).thenReturn(null);
        when(cmd.getHypervisorType()).thenReturn("vmware");
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(25L);

        SearchBuilder<ClusterVO> sb = newSearchBuilder(Mockito.mock(ClusterVO.class));
        SearchCriteria<ClusterVO> sc = Mockito.mock(SearchCriteria.class);
        when(_clusterDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_clusterDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForClusters(cmd);

        verify(sc).setParameters("hypervisorType", HypervisorType.getType("vmware").toString());
    }

    @Test
    public void searchForClustersCommandAppliesClusterTypeAllocationStateAndArch() {
        ListClustersCmd cmd = Mockito.mock(ListClustersCmd.class);
        when(cmd.getZoneId()).thenReturn(null);
        when(cmd.getClusterType()).thenReturn("CloudManaged");
        when(cmd.getAllocationState()).thenReturn("Enabled");
        when(cmd.getArch()).thenReturn(CPU.CPUArch.amd64);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(25L);

        SearchBuilder<ClusterVO> sb = newSearchBuilder(Mockito.mock(ClusterVO.class));
        SearchCriteria<ClusterVO> sc = Mockito.mock(SearchCriteria.class);
        when(_clusterDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_clusterDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForClusters(cmd);

        verify(sc).setParameters("clusterType", "CloudManaged");
        verify(sc).setParameters("allocationState", "Enabled");
        verify(sc).setParameters("arch", CPU.CPUArch.amd64);
    }

    @Test
    public void searchForClustersCommandBuildsKeywordSecondaryCriteria() {
        ListClustersCmd cmd = Mockito.mock(ListClustersCmd.class);
        when(cmd.getZoneId()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn("metal");
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(25L);

        SearchBuilder<ClusterVO> sb = newSearchBuilder(Mockito.mock(ClusterVO.class));
        SearchCriteria<ClusterVO> sc = Mockito.mock(SearchCriteria.class);
        SearchCriteria<ClusterVO> ssc = Mockito.mock(SearchCriteria.class);
        when(_clusterDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_clusterDao.createSearchCriteria()).thenReturn(ssc);
        when(_clusterDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForClusters(cmd);

        verify(ssc).addOr("name", SearchCriteria.Op.LIKE, "%metal%");
        verify(ssc).addOr("hypervisorType", SearchCriteria.Op.LIKE, "%metal%");
        verify(sc).addAnd("name", SearchCriteria.Op.SC, ssc);
    }

    @Test
    public void searchForClustersCommandAddsStorageAccessGroupClausesWhenPresent() {
        ListClustersCmd cmd = Mockito.mock(ListClustersCmd.class);
        when(cmd.getZoneId()).thenReturn(null);
        when(cmd.getStorageAccessGroup()).thenReturn("edge");
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(25L);

        SearchBuilder<ClusterVO> sb = newSearchBuilder(Mockito.mock(ClusterVO.class));
        SearchCriteria<ClusterVO> sc = Mockito.mock(SearchCriteria.class);
        when(_clusterDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_clusterDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForClusters(cmd);

        verify(sb).and();
        verify(sb).op(eq("storageAccessGroupExact"), Mockito.any(), eq(SearchCriteria.Op.EQ));
        verify(sb).or(eq("storageAccessGroupPrefix"), Mockito.any(), eq(SearchCriteria.Op.LIKE));
        verify(sb).or(eq("storageAccessGroupSuffix"), Mockito.any(), eq(SearchCriteria.Op.LIKE));
        verify(sb).or(eq("storageAccessGroupMiddle"), Mockito.any(), eq(SearchCriteria.Op.LIKE));
        verify(sb).cp();
        verify(sc).setParameters("storageAccessGroupExact", "edge");
        verify(sc).setParameters("storageAccessGroupPrefix", "edge,%");
        verify(sc).setParameters("storageAccessGroupSuffix", "%,edge");
        verify(sc).setParameters("storageAccessGroupMiddle", "%,edge,%");
    }

    @Test
    public void searchForClustersCommandSkipsStorageAccessGroupBranchWhenAbsent() {
        ListClustersCmd cmd = Mockito.mock(ListClustersCmd.class);
        when(cmd.getZoneId()).thenReturn(null);
        when(cmd.getStorageAccessGroup()).thenReturn(null);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(25L);

        SearchBuilder<ClusterVO> sb = newSearchBuilder(Mockito.mock(ClusterVO.class));
        SearchCriteria<ClusterVO> sc = Mockito.mock(SearchCriteria.class);
        when(_clusterDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_clusterDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForClusters(cmd);

        verify(sb, never()).op(eq("storageAccessGroupExact"), Mockito.any(), eq(SearchCriteria.Op.EQ));
        verify(sc, never()).setParameters(eq("storageAccessGroupExact"), any());
    }

    @Test
    public void searchForServersCommandChecksAuthorityAndForwardsFields() {
        ListHostsCmd cmd = Mockito.mock(ListHostsCmd.class);
        when(cmd.getZoneId()).thenReturn(13L);
        when(cmd.getHostName()).thenReturn("host-a");
        when(cmd.getType()).thenReturn("Routing");
        when(cmd.getState()).thenReturn("Up");
        when(cmd.getPodId()).thenReturn(14L);
        when(cmd.getClusterId()).thenReturn(15L);
        when(cmd.getId()).thenReturn(16L);
        when(cmd.getKeyword()).thenReturn("kw");
        when(cmd.getResourceState()).thenReturn("Enabled");
        when(cmd.getHaHost()).thenReturn(Boolean.TRUE);
        when(cmd.getStartIndex()).thenReturn(2L);
        when(cmd.getPageSizeVal()).thenReturn(20L);
        when(_accountMgr.checkAccessAndSpecifyAuthority(callerAccount, 13L)).thenReturn(23L);

        SearchBuilder<HostVO> sb = newSearchBuilder(Mockito.mock(HostVO.class));
        SearchCriteria<HostVO> sc = Mockito.mock(SearchCriteria.class);
        SearchCriteria<HostVO> ssc = Mockito.mock(SearchCriteria.class);
        when(_hostDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_hostDao.createSearchCriteria()).thenReturn(ssc);
        when(_haMgr.getHaTag()).thenReturn("");
        when(_hostDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForServers(cmd);

        verify(_accountMgr).checkAccessAndSpecifyAuthority(callerAccount, 13L);
        verify(sc).setParameters("name", "host-a");
        verify(sc).setParameters("type", "%Routing");
        verify(sc).setParameters("status", "Up");
        verify(sc).setParameters("dataCenterId", 23L);
        verify(sc).setParameters("podId", 14L);
        verify(sc).setParameters("clusterId", 15L);
        verify(sc).setParameters("id", 16L);
        verify(sc).setParameters("resourceState", "Enabled");
    }

    @Test
    public void searchForServersHelperBuildsKeywordSecondaryCriteria() {
        SearchBuilder<HostVO> sb = newSearchBuilder(Mockito.mock(HostVO.class));
        SearchCriteria<HostVO> sc = Mockito.mock(SearchCriteria.class);
        SearchCriteria<HostVO> ssc = Mockito.mock(SearchCriteria.class);
        when(_hostDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_hostDao.createSearchCriteria()).thenReturn(ssc);
        when(_haMgr.getHaTag()).thenReturn("");
        when(_hostDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForServers(0L, 50L, null, null, null, null, null, null, null, "findme", null, null, null, null);

        verify(ssc).addOr("name", SearchCriteria.Op.LIKE, "%findme%");
        verify(ssc).addOr("status", SearchCriteria.Op.LIKE, "%findme%");
        verify(ssc).addOr("type", SearchCriteria.Op.LIKE, "%findme%");
        verify(sc).addAnd("name", SearchCriteria.Op.SC, ssc);
    }

    @Test
    public void searchForServersHelperSetsExcludedIdsWhenProvided() {
        SearchBuilder<HostVO> sb = newSearchBuilder(Mockito.mock(HostVO.class));
        SearchCriteria<HostVO> sc = Mockito.mock(SearchCriteria.class);
        when(_hostDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_haMgr.getHaTag()).thenReturn("");
        when(_hostDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForServers(0L, 50L, null, null, null, null, null, null, null, null, null, null, null, null, 44L, 45L);

        verify(sc).setParameters("idsNotIn", new Object[] {44L, 45L});
    }

    @Test
    public void searchForServersHelperUsesEqJoinForHaHostsTrue() {
        SearchBuilder<HostVO> sb = newSearchBuilder(Mockito.mock(HostVO.class));
        SearchBuilder<HostTagVO> hostTagSearch = newSearchBuilder(Mockito.mock(HostTagVO.class));
        SearchCriteria<HostVO> sc = Mockito.mock(SearchCriteria.class);
        when(_hostDao.createSearchBuilder()).thenReturn(sb);
        when(_hostTagsDao.createSearchBuilder()).thenReturn(hostTagSearch);
        when(sb.create()).thenReturn(sc);
        when(_haMgr.getHaTag()).thenReturn("ha-tag");
        when(_hostDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForServers(0L, 50L, null, null, null, null, null, null, null, null, null, Boolean.TRUE, null, null);

        verify(hostTagSearch).op(eq("tag"), Mockito.any(), eq(SearchCriteria.Op.EQ));
        verify(sb).join(eq("hostTagSearch"), eq(hostTagSearch), Mockito.any(), Mockito.any(), eq(JoinBuilder.JoinType.LEFTOUTER));
        verify(sc).setJoinParameters("hostTagSearch", "tag", "ha-tag");
    }

    @Test
    public void searchForServersHelperUsesNeqAndNullJoinForHaHostsFalse() {
        SearchBuilder<HostVO> sb = newSearchBuilder(Mockito.mock(HostVO.class));
        SearchBuilder<HostTagVO> hostTagSearch = newSearchBuilder(Mockito.mock(HostTagVO.class));
        SearchCriteria<HostVO> sc = Mockito.mock(SearchCriteria.class);
        when(_hostDao.createSearchBuilder()).thenReturn(sb);
        when(_hostTagsDao.createSearchBuilder()).thenReturn(hostTagSearch);
        when(sb.create()).thenReturn(sc);
        when(_haMgr.getHaTag()).thenReturn("ha-tag");
        when(_hostDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForServers(0L, 50L, null, null, null, null, null, null, null, null, null, Boolean.FALSE, null, null);

        verify(hostTagSearch).op(eq("tag"), Mockito.any(), eq(SearchCriteria.Op.NEQ));
        verify(hostTagSearch).or(eq("tagNull"), Mockito.any(), eq(SearchCriteria.Op.NULL));
        verify(sc).setJoinParameters("hostTagSearch", "tag", "ha-tag");
    }

    @Test
    public void searchForServersHelperSkipsHaJoinWhenTagBlank() {
        SearchBuilder<HostVO> sb = newSearchBuilder(Mockito.mock(HostVO.class));
        SearchCriteria<HostVO> sc = Mockito.mock(SearchCriteria.class);
        when(_hostDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_haMgr.getHaTag()).thenReturn("");
        when(_hostDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForServers(0L, 50L, null, null, null, null, null, null, null, null, null, Boolean.TRUE, null, null);

        verify(_hostTagsDao, never()).createSearchBuilder();
        verify(sc, never()).setJoinParameters(eq("hostTagSearch"), eq("tag"), any());
    }

    @Test
    public void searchForServersHelperAppliesHypervisorVersionAndResourceState() {
        SearchBuilder<HostVO> sb = newSearchBuilder(Mockito.mock(HostVO.class));
        SearchCriteria<HostVO> sc = Mockito.mock(SearchCriteria.class);
        when(_hostDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        when(_haMgr.getHaTag()).thenReturn("");
        when(_hostDao.searchAndCount(eq(sc), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForServers(0L, 50L, null, null, null, null, null, null, null, null, "Enabled", null, HypervisorType.KVM.toString(), "8.0");

        verify(sc).setParameters("hypervisorType", HypervisorType.KVM.toString());
        verify(sc).setParameters("hypervisorVersion", "8.0");
        verify(sc).setParameters("resourceState", "Enabled");
    }

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
}
