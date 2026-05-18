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
package com.cloud.api.query;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.cloudstack.api.ResponseGenerator;
import org.apache.cloudstack.api.command.admin.router.GetRouterHealthCheckResultsCmd;
import org.apache.cloudstack.api.command.admin.router.ListRoutersCmd;
import org.apache.cloudstack.api.response.RouterHealthCheckResultResponse;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.dao.DomainRouterJoinDao;
import com.cloud.api.query.vo.DomainRouterJoinVO;
import com.cloud.network.VpcVirtualNetworkApplianceService;
import com.cloud.network.dao.RouterHealthCheckResultDao;
import com.cloud.network.dao.RouterHealthCheckResultVO;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.dao.DomainRouterDao;

@RunWith(MockitoJUnitRunner.class)
public class RouterQueryServiceImplTest {

    @Mock private AccountManager accountMgr;
    @Mock private DomainRouterJoinDao routerJoinDao;
    @Mock private DomainRouterDao routerDao;
    @Mock private RouterHealthCheckResultDao routerHealthCheckResultDao;
    @Mock private VpcVirtualNetworkApplianceService routerService;
    @Mock private ResponseGenerator responseGenerator;

    @Mock private SearchBuilder<DomainRouterJoinVO> searchBuilder;
    @Mock private SearchCriteria<DomainRouterJoinVO> searchCriteria;
    @Mock private SearchCriteria<DomainRouterJoinVO> keywordSubCriteria;

    @InjectMocks
    private RouterQueryServiceImpl service;

    private AccountVO account;
    private UserVO user;

    @Before
    public void setUp() {
        DomainRouterJoinVO viewEntity = mock(DomainRouterJoinVO.class);
        when(routerJoinDao.createSearchBuilder()).thenReturn(searchBuilder);
        when(searchBuilder.entity()).thenReturn(viewEntity);
        when(searchBuilder.create()).thenReturn(searchCriteria);
        when(routerJoinDao.createSearchCriteria()).thenReturn(keywordSubCriteria);

        account = new AccountVO("testuser", 1L, "networkdomain", Account.Type.NORMAL, UUID.randomUUID().toString());
        user = new UserVO(1L, "testuser", "password", "Test", "User", "test@example.com", "UTC",
                UUID.randomUUID().toString(), User.Source.UNKNOWN);
        CallContext.register(user, account);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // -----------------------------------------------------------------------
    // searchForRoutersInternal — empty result
    // -----------------------------------------------------------------------

    @Test
    public void testSearchForRoutersInternalEmptyResult() {
        ListRoutersCmd cmd = mock(ListRoutersCmd.class);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(20L);

        Pair<List<DomainRouterJoinVO>, Integer> emptyPair = new Pair<>(Collections.emptyList(), 0);
        when(routerJoinDao.searchAndCount(any(), any(Filter.class))).thenReturn(emptyPair);

        Pair<List<DomainRouterJoinVO>, Integer> result = service.searchForRoutersInternal(
                cmd, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertNotNull(result);
        assertEquals(0, (int) result.second());
        assertTrue(result.first().isEmpty());
    }

    // -----------------------------------------------------------------------
    // searchForRoutersInternal — single result hydrated via searchByIds
    // -----------------------------------------------------------------------

    @Test
    public void testSearchForRoutersInternalSingleResult() {
        ListRoutersCmd cmd = mock(ListRoutersCmd.class);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(20L);

        DomainRouterJoinVO vrJoin = mock(DomainRouterJoinVO.class);
        when(vrJoin.getId()).thenReturn(42L);
        List<DomainRouterJoinVO> singleList = Collections.singletonList(vrJoin);

        Pair<List<DomainRouterJoinVO>, Integer> uniquePair = new Pair<>(singleList, 1);
        when(routerJoinDao.searchAndCount(any(), any(Filter.class))).thenReturn(uniquePair);
        when(routerJoinDao.searchByIds(eq(new Long[]{42L}))).thenReturn(singleList);

        Pair<List<DomainRouterJoinVO>, Integer> result = service.searchForRoutersInternal(
                cmd, null, null, null, null, null, null, null, null, null, null, null, null, null, null);

        assertEquals(1, (int) result.second());
        assertEquals(1, result.first().size());
    }

    // -----------------------------------------------------------------------
    // searchForRoutersInternal — isHealthCheckFailed=true returns empty when no failures
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Test
    public void testSearchForRoutersInternalHealthCheckFailedNoFailures() {
        ListRoutersCmd cmd = mock(ListRoutersCmd.class);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(20L);

        // routerHealthCheckResultDao.customSearch returns empty => isHealthCheckFailed=true => early empty return
        RouterHealthCheckResultVO hcEntity = mock(RouterHealthCheckResultVO.class);
        GenericSearchBuilder<RouterHealthCheckResultVO, Long> hcSearchBuilder =
                (GenericSearchBuilder<RouterHealthCheckResultVO, Long>) mock(GenericSearchBuilder.class);
        SearchCriteria<Long> hcSearchCriteria = (SearchCriteria<Long>) mock(SearchCriteria.class);
        when(routerHealthCheckResultDao.createSearchBuilder(Long.class)).thenReturn(hcSearchBuilder);
        when(hcSearchBuilder.entity()).thenReturn(hcEntity);
        when(hcSearchBuilder.create()).thenReturn(hcSearchCriteria);
        when(routerHealthCheckResultDao.customSearch(any(), any())).thenReturn(Collections.emptyList());

        Pair<List<DomainRouterJoinVO>, Integer> result = service.searchForRoutersInternal(
                cmd, null, null, null, null, null, null, null, null, null, null, null, null, null, Boolean.TRUE);

        assertNotNull(result);
        assertEquals(0, (int) result.second());
        assertTrue(result.first().isEmpty());
        verify(routerJoinDao, never()).searchAndCount(any(), any());
    }

    // -----------------------------------------------------------------------
    // listRouterHealthChecks — no fresh check requested, results returned
    // -----------------------------------------------------------------------

    @Test
    public void testListRouterHealthChecksNoFreshCheck() {
        GetRouterHealthCheckResultsCmd cmd = mock(GetRouterHealthCheckResultsCmd.class);
        when(cmd.getRouterId()).thenReturn(10L);
        when(cmd.shouldPerformFreshChecks()).thenReturn(false);

        // Default value of RouterHealthChecksEnabled is true
        RouterHealthCheckResultVO hcResult = mock(RouterHealthCheckResultVO.class);
        List<RouterHealthCheckResultVO> resultList = Collections.singletonList(hcResult);
        when(routerHealthCheckResultDao.getHealthCheckResults(anyLong())).thenReturn(resultList);

        DomainRouterVO routerVO = mock(DomainRouterVO.class);
        when(routerDao.findById(anyLong())).thenReturn(routerVO);

        RouterHealthCheckResultResponse resp = mock(RouterHealthCheckResultResponse.class);
        when(responseGenerator.createHealthCheckResponse(any(), any()))
                .thenReturn(Collections.singletonList(resp));

        List<RouterHealthCheckResultResponse> responses = service.listRouterHealthChecks(cmd);

        assertNotNull(responses);
        assertEquals(1, responses.size());
        // No fresh check was performed
        verify(routerService, never()).performRouterHealthChecks(anyLong());
    }

    // -----------------------------------------------------------------------
    // listRouterHealthChecks — empty results throw exception
    // -----------------------------------------------------------------------

    @Test(expected = CloudRuntimeException.class)
    public void testListRouterHealthChecksEmptyResults() {
        GetRouterHealthCheckResultsCmd cmd = mock(GetRouterHealthCheckResultsCmd.class);
        when(cmd.getRouterId()).thenReturn(10L);
        when(cmd.shouldPerformFreshChecks()).thenReturn(false);

        // Default value of RouterHealthChecksEnabled is true — no field swap needed
        when(routerHealthCheckResultDao.getHealthCheckResults(anyLong()))
                .thenReturn(Collections.emptyList());

        service.listRouterHealthChecks(cmd);
    }

    // -----------------------------------------------------------------------
    // listRouterHealthChecks — fresh checks requested, success
    // -----------------------------------------------------------------------

    @Test
    public void testListRouterHealthChecksFreshSuccess() {
        GetRouterHealthCheckResultsCmd cmd = mock(GetRouterHealthCheckResultsCmd.class);
        when(cmd.getRouterId()).thenReturn(10L);
        when(cmd.shouldPerformFreshChecks()).thenReturn(true);

        // Default value is true
        Pair<Boolean, String> freshResult = new Pair<>(true, "ok");
        when(routerService.performRouterHealthChecks(anyLong())).thenReturn(freshResult);

        RouterHealthCheckResultVO hcResult = mock(RouterHealthCheckResultVO.class);
        List<RouterHealthCheckResultVO> resultList = Collections.singletonList(hcResult);
        when(routerHealthCheckResultDao.getHealthCheckResults(anyLong())).thenReturn(resultList);

        DomainRouterVO routerVO = mock(DomainRouterVO.class);
        when(routerDao.findById(anyLong())).thenReturn(routerVO);

        RouterHealthCheckResultResponse resp = mock(RouterHealthCheckResultResponse.class);
        when(responseGenerator.createHealthCheckResponse(any(), any()))
                .thenReturn(Collections.singletonList(resp));

        List<RouterHealthCheckResultResponse> responses = service.listRouterHealthChecks(cmd);

        assertNotNull(responses);
        assertEquals(1, responses.size());
        verify(routerService).performRouterHealthChecks(eq(10L));
    }

    // -----------------------------------------------------------------------
    // listRouterHealthChecks — fresh checks requested, failure
    // -----------------------------------------------------------------------

    @Test(expected = CloudRuntimeException.class)
    public void testListRouterHealthChecksFreshFailure() {
        GetRouterHealthCheckResultsCmd cmd = mock(GetRouterHealthCheckResultsCmd.class);
        when(cmd.getRouterId()).thenReturn(10L);
        when(cmd.shouldPerformFreshChecks()).thenReturn(true);

        // Default value is true
        Pair<Boolean, String> freshResult = new Pair<>(false, "error occurred");
        when(routerService.performRouterHealthChecks(anyLong())).thenReturn(freshResult);

        service.listRouterHealthChecks(cmd);
    }

    // -----------------------------------------------------------------------
    // listRouterHealthChecks — fresh checks requested, null returned
    // -----------------------------------------------------------------------

    @Test(expected = CloudRuntimeException.class)
    public void testListRouterHealthChecksFreshNullResult() {
        GetRouterHealthCheckResultsCmd cmd = mock(GetRouterHealthCheckResultsCmd.class);
        when(cmd.getRouterId()).thenReturn(10L);
        when(cmd.shouldPerformFreshChecks()).thenReturn(true);

        // Default value is true
        when(routerService.performRouterHealthChecks(anyLong())).thenReturn(null);

        service.listRouterHealthChecks(cmd);
    }

    // -----------------------------------------------------------------------
    // searchForRoutersInternal — version filter produces LIKE parameter
    // -----------------------------------------------------------------------

    @Test
    public void testSearchForRoutersInternalVersionFilter() {
        ListRoutersCmd cmd = mock(ListRoutersCmd.class);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(20L);

        Pair<List<DomainRouterJoinVO>, Integer> emptyPair = new Pair<>(Collections.emptyList(), 0);
        when(routerJoinDao.searchAndCount(any(), any(Filter.class))).thenReturn(emptyPair);

        service.searchForRoutersInternal(cmd, null, null, null, null, null, null, null, null,
                null, null, null, null, "4.18", null);

        verify(searchCriteria).setParameters("version", "Cloudstack Release 4.18%");
    }

    // -----------------------------------------------------------------------
    // searchForRoutersInternal — keyword filter applies OR sub-criteria
    // -----------------------------------------------------------------------

    @Test
    public void testSearchForRoutersInternalKeywordFilter() {
        ListRoutersCmd cmd = mock(ListRoutersCmd.class);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(20L);

        Pair<List<DomainRouterJoinVO>, Integer> emptyPair = new Pair<>(Collections.emptyList(), 0);
        when(routerJoinDao.searchAndCount(any(), any(Filter.class))).thenReturn(emptyPair);

        service.searchForRoutersInternal(cmd, null, null, null, null, null, null, null, "mykeyword",
                null, null, null, null, null, null);

        verify(keywordSubCriteria).addOr("name", SearchCriteria.Op.LIKE, "%mykeyword%");
        verify(searchCriteria).addAnd(eq("instanceName"), eq(SearchCriteria.Op.SC), eq(keywordSubCriteria));
    }

    // -----------------------------------------------------------------------
    // searchForRoutersInternal — zoneId, podId, clusterId, hostId filters
    // -----------------------------------------------------------------------

    @Test
    public void testSearchForRoutersInternalZonePodClusterHostFilters() {
        ListRoutersCmd cmd = mock(ListRoutersCmd.class);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(20L);

        Pair<List<DomainRouterJoinVO>, Integer> emptyPair = new Pair<>(Collections.emptyList(), 0);
        when(routerJoinDao.searchAndCount(any(), any(Filter.class))).thenReturn(emptyPair);

        service.searchForRoutersInternal(cmd, null, null, null, 1L, 2L, 3L, 4L,
                null, null, null, null, null, null, null);

        verify(searchCriteria).setParameters("dataCenterId", 1L);
        verify(searchCriteria).setParameters("podId", 2L);
        verify(searchCriteria).setParameters("clusterId", 3L);
        verify(searchCriteria).setParameters("hostId", 4L);
    }
}
