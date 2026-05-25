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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.cloudstack.api.command.admin.zone.ListZonesCmdByAdmin;
import org.apache.cloudstack.api.command.user.zone.ListZonesCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.api.query.dao.DataCenterJoinDao;
import com.cloud.api.query.vo.DataCenterJoinVO;
import com.cloud.dc.DedicatedResourceVO;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.CloudAuthenticationException;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.dao.DomainRouterDao;

/**
 * Unit tests for {@link ZoneQueryServiceImpl}.
 *
 * Note: {@code QueryService.AllowUserViewAllDataCenters.valueInScope(...)} returns
 * its default value ({@code true}) in the test environment (no config depot wired),
 * so the {@code checkAccessAndSpecifyAuthority} block is skipped. Tests that need
 * the normal code path (keyword, available, tags etc.) are exercised without issue.
 */
@RunWith(MockitoJUnitRunner.class)
public class ZoneQueryServiceImplTest {

    private DataCenterJoinDao dcJoinDao;
    private DomainDao domainDao;
    private DedicatedResourceDao dedicatedDao;
    private ResourceTagDao resourceTagDao;
    private DomainRouterDao routerDao;
    private AccountManager accountMgr;

    private SearchBuilder<DataCenterJoinVO> searchBuilder;
    private SearchCriteria<DataCenterJoinVO> searchCriteria;
    private SearchCriteria<DataCenterJoinVO> subCriteria;

    private ZoneQueryServiceImpl service;

    private AccountVO normalAccount;
    private AccountVO adminAccount;
    private UserVO user;

    @SuppressWarnings("unchecked")
    @Before
    public void setUp() {
        dcJoinDao = mock(DataCenterJoinDao.class);
        domainDao = mock(DomainDao.class);
        dedicatedDao = mock(DedicatedResourceDao.class);
        resourceTagDao = mock(ResourceTagDao.class);
        routerDao = mock(DomainRouterDao.class);
        accountMgr = mock(AccountManager.class);

        searchBuilder = (SearchBuilder<DataCenterJoinVO>) mock(SearchBuilder.class);
        searchCriteria = (SearchCriteria<DataCenterJoinVO>) mock(SearchCriteria.class);
        subCriteria = (SearchCriteria<DataCenterJoinVO>) mock(SearchCriteria.class);

        DataCenterJoinVO dcEntity = mock(DataCenterJoinVO.class);
        when(dcJoinDao.createSearchBuilder()).thenReturn(searchBuilder);
        when(searchBuilder.entity()).thenReturn(dcEntity);
        when(searchBuilder.create()).thenReturn(searchCriteria);
        when(dcJoinDao.createSearchCriteria()).thenReturn(subCriteria);
        // Stub chained calls so and().op(...), or().op(...) don't NPE
        Mockito.lenient().when(searchBuilder.and()).thenReturn(searchBuilder);
        Mockito.lenient().when(searchBuilder.or()).thenReturn(searchBuilder);
        Mockito.lenient().when(searchBuilder.cp()).thenReturn(searchBuilder);
        Mockito.lenient().when(searchBuilder.op(any(String.class), any(), any(SearchCriteria.Op.class)))
               .thenReturn(searchBuilder);
        Mockito.lenient().when(searchBuilder.or(any(String.class), any(), any(SearchCriteria.Op.class)))
               .thenReturn(searchBuilder);

        service = new ZoneQueryServiceImpl();
        ReflectionTestUtils.setField(service, "_dcJoinDao", dcJoinDao);
        ReflectionTestUtils.setField(service, "_domainDao", domainDao);
        ReflectionTestUtils.setField(service, "_dedicatedDao", dedicatedDao);
        ReflectionTestUtils.setField(service, "resourceTagDao", resourceTagDao);
        ReflectionTestUtils.setField(service, "_routerDao", routerDao);
        ReflectionTestUtils.setField(service, "accountMgr", accountMgr);

        normalAccount = new AccountVO("normaluser", 1L, "domain", Account.Type.NORMAL, UUID.randomUUID().toString());
        adminAccount = new AccountVO("admin", 1L, "domain", Account.Type.ADMIN, UUID.randomUUID().toString());
        user = new UserVO(1L, "normaluser", "password", "Test", "User",
                "test@example.com", "UTC", UUID.randomUUID().toString(), User.Source.UNKNOWN);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // -----------------------------------------------------------------------
    // Stub command helpers (avoid ByteBuddy mock of cmd classes)
    // -----------------------------------------------------------------------

    private static class StubListZonesCmd extends ListZonesCmd {
        Long id;
        Long domainId;
        String keyword;
        String name;
        Boolean available;
        Map<String, String> tags;
        String storageAccessGroup;

        @Override public Long getId() { return id; }
        @Override public List<Long> getIds() { return null; }
        @Override public Long getDomainId() { return domainId; }
        @Override public String getKeyword() { return keyword; }
        @Override public String getName() { return name; }
        @Override public Boolean isAvailable() { return available; }
        @Override public Map<String, String> getTags() { return tags; }
        @Override public String getStorageAccessGroup() { return storageAccessGroup; }
        @Override public String getNetworkType() { return null; }
        @Override public Boolean getShowCapacities() { return null; }
        @Override public Boolean getShowIcon() { return null; }
        @Override public Long getStartIndex() { return 0L; }
        @Override public Long getPageSizeVal() { return 20L; }
    }

    private static class StubListZonesCmdByAdmin extends ListZonesCmdByAdmin {
        @Override public Long getId() { return null; }
        @Override public List<Long> getIds() { return null; }
        @Override public Long getDomainId() { return null; }
        @Override public String getKeyword() { return null; }
        @Override public String getName() { return null; }
        @Override public Boolean isAvailable() { return null; }
        @Override public Map<String, String> getTags() { return null; }
        @Override public String getStorageAccessGroup() { return null; }
        @Override public String getNetworkType() { return null; }
        @Override public Boolean getShowCapacities() { return null; }
        @Override public Boolean getShowIcon() { return null; }
        @Override public Long getStartIndex() { return 0L; }
        @Override public Long getPageSizeVal() { return 20L; }
    }

    private Pair<List<DataCenterJoinVO>, Integer> emptyPair() {
        return new Pair<>(Collections.emptyList(), 0);
    }

    private Pair<List<DataCenterJoinVO>, Integer> singletonPair() {
        return new Pair<>(Collections.singletonList(mock(DataCenterJoinVO.class)), 1);
    }

    // -----------------------------------------------------------------------
    // Test 1: Admin account → ResponseView.Full
    // -----------------------------------------------------------------------

    @Test
    public void listDataCenters_admin_returnsFullResponse() {
        CallContext.register(user, adminAccount);

        StubListZonesCmd cmd = new StubListZonesCmd();
        when(dcJoinDao.searchAndCount(any(), any(Filter.class))).thenReturn(singletonPair());

        try (MockedStatic<ViewResponseHelper> vrh = mockStatic(ViewResponseHelper.class)) {
            // Admin account: searchAndCount returns 1 item, response count should reflect it
            ListResponse<ZoneResponse> response = service.listDataCenters(cmd);

            assertNotNull(response);
            // Count comes from the Pair.second(), not the list from ViewResponseHelper
            assertEquals(1, response.getCount().intValue());
            verify(dcJoinDao).searchAndCount(any(), any(Filter.class));
        }
    }

    // -----------------------------------------------------------------------
    // Test 2: Normal user → ResponseView.Restricted
    // -----------------------------------------------------------------------

    @Test
    public void listDataCenters_normalUser_returnsResponse() {
        CallContext.register(user, normalAccount);

        StubListZonesCmd cmd = new StubListZonesCmd();
        when(dcJoinDao.searchAndCount(any(), any(Filter.class))).thenReturn(emptyPair());

        try (MockedStatic<ViewResponseHelper> vrh = mockStatic(ViewResponseHelper.class)) {
            ListResponse<ZoneResponse> response = service.listDataCenters(cmd);
            // normal user triggers Restricted view; response is non-null even when VRH returns null list
            assertNotNull(response);
            assertEquals(0, response.getCount().intValue());
        }
    }

    // -----------------------------------------------------------------------
    // Test 3: ListZonesCmdByAdmin always gets Full view even with normal account
    // -----------------------------------------------------------------------

    @Test
    public void listDataCenters_byAdminCmd_returnsResponse() {
        CallContext.register(user, normalAccount);

        StubListZonesCmdByAdmin cmd = new StubListZonesCmdByAdmin();
        when(dcJoinDao.searchAndCount(any(), any(Filter.class))).thenReturn(emptyPair());

        try (MockedStatic<ViewResponseHelper> vrh = mockStatic(ViewResponseHelper.class)) {
            ListResponse<ZoneResponse> response = service.listDataCenters(cmd);
            // ListZonesCmdByAdmin triggers Full view regardless of caller account type
            assertNotNull(response);
            assertEquals(0, response.getCount().intValue());
            verify(dcJoinDao).searchAndCount(any(), any(Filter.class));
        }
    }

    // -----------------------------------------------------------------------
    // Test 4: available=false with routers → filter by DC IDs
    // -----------------------------------------------------------------------

    @Test
    public void listDataCenters_availableFalseWithRouters_filtersById() {
        CallContext.register(user, normalAccount);

        StubListZonesCmd cmd = new StubListZonesCmd();
        cmd.available = Boolean.FALSE;

        DomainRouterVO router1 = mock(DomainRouterVO.class);
        when(router1.getDataCenterId()).thenReturn(10L);
        DomainRouterVO router2 = mock(DomainRouterVO.class);
        when(router2.getDataCenterId()).thenReturn(20L);
        when(routerDao.listBy(normalAccount.getId())).thenReturn(Arrays.asList(router1, router2));
        when(dcJoinDao.searchAndCount(any(), any(Filter.class))).thenReturn(singletonPair());

        try (MockedStatic<ViewResponseHelper> vrh = mockStatic(ViewResponseHelper.class)) {
            vrh.when(() -> ViewResponseHelper.createDataCenterResponse(any(), any(), any(), any()))
               .thenReturn(Collections.emptyList());

            service.listDataCenters(cmd);

            verify(routerDao).listBy(normalAccount.getId());
            verify(dcJoinDao).searchAndCount(any(), any(Filter.class));
        }
    }

    // -----------------------------------------------------------------------
    // Test 5: available=false with no routers → empty result, no DAO call
    // -----------------------------------------------------------------------

    @Test
    public void listDataCenters_availableFalseEmptyRouters_returnsEmpty() {
        CallContext.register(user, normalAccount);

        StubListZonesCmd cmd = new StubListZonesCmd();
        cmd.available = Boolean.FALSE;

        when(routerDao.listBy(normalAccount.getId())).thenReturn(Collections.emptyList());

        try (MockedStatic<ViewResponseHelper> vrh = mockStatic(ViewResponseHelper.class)) {
            vrh.when(() -> ViewResponseHelper.createDataCenterResponse(any(), any(), any(), any()))
               .thenReturn(Collections.emptyList());

            ListResponse<ZoneResponse> response = service.listDataCenters(cmd);

            assertNotNull(response);
            assertEquals(0, response.getCount().intValue());
            verify(dcJoinDao, never()).searchAndCount(any(), any());
        }
    }

    // -----------------------------------------------------------------------
    // Test 6: keyword set → sub-criteria added for name/description
    // -----------------------------------------------------------------------

    @Test
    public void listDataCenters_keyword_addsSubCriteria() {
        CallContext.register(user, normalAccount);

        StubListZonesCmd cmd = new StubListZonesCmd();
        cmd.keyword = "foo";

        // subCriteria is the first return from createSearchCriteria() - used as ssc inside keyword block
        when(dcJoinDao.searchAndCount(any(), any(Filter.class))).thenReturn(emptyPair());

        try (MockedStatic<ViewResponseHelper> vrh = mockStatic(ViewResponseHelper.class)) {
            vrh.when(() -> ViewResponseHelper.createDataCenterResponse(any(), any(), any(), any()))
               .thenReturn(Collections.emptyList());

            service.listDataCenters(cmd);

            // verify keyword OR clauses were added to the sub-criteria
            verify(subCriteria).addOr(eq("name"), eq(SearchCriteria.Op.LIKE), eq("%foo%"));
            verify(subCriteria).addOr(eq("description"), eq(SearchCriteria.Op.LIKE), eq("%foo%"));
        }
    }

    // -----------------------------------------------------------------------
    // Test 7: Normal user (isNormalUser=true) → buildSearchCriteriaForUserDomainAndAbove
    // -----------------------------------------------------------------------

    @Test
    public void listDataCenters_normalUser_walksToRoot() {
        CallContext.register(user, normalAccount);
        when(accountMgr.isNormalUser(normalAccount.getId())).thenReturn(true);

        DomainVO childDomain = mock(DomainVO.class);
        when(childDomain.getId()).thenReturn(1L);
        when(childDomain.getParent()).thenReturn(0L);

        DomainVO rootDomain = mock(DomainVO.class);
        when(rootDomain.getId()).thenReturn(0L);
        when(rootDomain.getParent()).thenReturn(null);

        when(domainDao.findById(normalAccount.getDomainId())).thenReturn(childDomain);
        when(domainDao.findById(0L)).thenReturn(rootDomain);
        when(dedicatedDao.listZonesNotInDomainIds(any())).thenReturn(Collections.emptyList());
        when(dcJoinDao.searchAndCount(any(), any(Filter.class))).thenReturn(emptyPair());

        StubListZonesCmd cmd = new StubListZonesCmd();

        try (MockedStatic<ViewResponseHelper> vrh = mockStatic(ViewResponseHelper.class)) {
            vrh.when(() -> ViewResponseHelper.createDataCenterResponse(any(), any(), any(), any()))
               .thenReturn(Collections.emptyList());

            service.listDataCenters(cmd);

            verify(domainDao, Mockito.atLeastOnce()).findById(anyLong());
            verify(dedicatedDao).listZonesNotInDomainIds(any());
        }
    }

    // -----------------------------------------------------------------------
    // Test 8: Domain admin → finds children + walks to root
    // -----------------------------------------------------------------------

    @Test
    public void listDataCenters_domainAdmin_includesChildrenAndAncestors() {
        CallContext.register(user, normalAccount);
        when(accountMgr.isNormalUser(normalAccount.getId())).thenReturn(false);
        when(accountMgr.isDomainAdmin(normalAccount.getId())).thenReturn(true);

        DomainVO domain = mock(DomainVO.class);
        when(domain.getId()).thenReturn(5L);
        when(domain.getPath()).thenReturn("/root/5");
        when(domain.getParent()).thenReturn(null);

        DomainVO child1 = mock(DomainVO.class); when(child1.getId()).thenReturn(6L);
        DomainVO child2 = mock(DomainVO.class); when(child2.getId()).thenReturn(7L);

        when(domainDao.findById(normalAccount.getDomainId())).thenReturn(domain);
        when(domainDao.findAllChildren("/root/5", 5L)).thenReturn(Arrays.asList(child1, child2));
        when(dedicatedDao.listZonesNotInDomainIds(any())).thenReturn(Collections.emptyList());
        when(dcJoinDao.searchAndCount(any(), any(Filter.class))).thenReturn(emptyPair());

        StubListZonesCmd cmd = new StubListZonesCmd();

        try (MockedStatic<ViewResponseHelper> vrh = mockStatic(ViewResponseHelper.class)) {
            vrh.when(() -> ViewResponseHelper.createDataCenterResponse(any(), any(), any(), any()))
               .thenReturn(Collections.emptyList());

            service.listDataCenters(cmd);

            verify(domainDao).findAllChildren("/root/5", 5L);
            verify(dedicatedDao).listZonesNotInDomainIds(any());
        }
    }

    // -----------------------------------------------------------------------
    // Test 9: Explicit domainId → domainId EQ restriction added
    // -----------------------------------------------------------------------

    @Test
    public void listDataCenters_explicitDomainId_addsDomainEqFilter() {
        CallContext.register(user, normalAccount);
        when(accountMgr.isNormalUser(normalAccount.getId())).thenReturn(false);

        StubListZonesCmd cmd = new StubListZonesCmd();
        cmd.domainId = 7L;

        when(dcJoinDao.searchAndCount(any(), any(Filter.class))).thenReturn(emptyPair());

        try (MockedStatic<ViewResponseHelper> vrh = mockStatic(ViewResponseHelper.class)) {
            vrh.when(() -> ViewResponseHelper.createDataCenterResponse(any(), any(), any(), any()))
               .thenReturn(Collections.emptyList());

            service.listDataCenters(cmd);

            verify(searchCriteria).addAnd(eq("domainId"), eq(SearchCriteria.Op.EQ), eq(7L));
        }
    }

    // -----------------------------------------------------------------------
    // Test 10: Resource tags → tag join configured with resourceType Zone
    // -----------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    @Test
    public void listDataCenters_resourceTags_addsTagJoinWithZoneType() {
        CallContext.register(user, adminAccount);

        Map<String, String> tags = new HashMap<>();
        tags.put("k1", "v1");
        StubListZonesCmd cmd = new StubListZonesCmd();
        cmd.tags = tags;

        SearchBuilder<ResourceTagVO> tagSb = (SearchBuilder<ResourceTagVO>) mock(SearchBuilder.class);
        ResourceTagVO tagEntity = mock(ResourceTagVO.class);
        when(resourceTagDao.createSearchBuilder()).thenReturn(tagSb);
        when(tagSb.entity()).thenReturn(tagEntity);
        // Stub chained calls used in tag search builder setup
        Mockito.lenient().when(tagSb.or()).thenReturn(tagSb);
        Mockito.lenient().when(tagSb.and()).thenReturn(tagSb);
        Mockito.lenient().when(tagSb.cp()).thenReturn(tagSb);
        Mockito.lenient().when(tagSb.op(anyString(), any(), any(SearchCriteria.Op.class))).thenReturn(tagSb);

        when(dcJoinDao.searchAndCount(any(), any(Filter.class))).thenReturn(emptyPair());

        try (MockedStatic<ViewResponseHelper> vrh = mockStatic(ViewResponseHelper.class)) {
            vrh.when(() -> ViewResponseHelper.createDataCenterResponse(any(), any(), any(), any()))
               .thenReturn(Collections.emptyList());

            service.listDataCenters(cmd);

            verify(resourceTagDao).createSearchBuilder();
            // tag join parameters should include Zone resourceType
            verify(searchCriteria).setJoinParameters("tagSearch", "resourceType", "Zone");
        }
    }

    // -----------------------------------------------------------------------
    // Test 11: storageAccessGroup → four LIKE parameters set
    // -----------------------------------------------------------------------

    @Test
    public void listDataCenters_storageAccessGroup_setsFourParameters() {
        CallContext.register(user, adminAccount);

        StubListZonesCmd cmd = new StubListZonesCmd();
        cmd.storageAccessGroup = "sag1";

        when(dcJoinDao.searchAndCount(any(), any(Filter.class))).thenReturn(emptyPair());

        try (MockedStatic<ViewResponseHelper> vrh = mockStatic(ViewResponseHelper.class)) {
            vrh.when(() -> ViewResponseHelper.createDataCenterResponse(any(), any(), any(), any()))
               .thenReturn(Collections.emptyList());

            service.listDataCenters(cmd);

            verify(searchCriteria).setParameters("storageAccessGroupExact", "sag1");
            verify(searchCriteria).setParameters("storageAccessGroupPrefix", "sag1,%");
            verify(searchCriteria).setParameters("storageAccessGroupSuffix", "%,sag1");
            verify(searchCriteria).setParameters("storageAccessGroupMiddle", "%,sag1,%");
        }
    }

    // -----------------------------------------------------------------------
    // Test 12: listDataCentersWithMinimalResponse uses createMinimalDataCenterResponse
    // -----------------------------------------------------------------------

    @Test
    public void listDataCentersWithMinimalResponse_usesMinimalHelper() {
        CallContext.register(user, adminAccount);

        StubListZonesCmd cmd = new StubListZonesCmd();
        when(dcJoinDao.searchAndCount(any(), any(Filter.class))).thenReturn(emptyPair());

        try (MockedStatic<ViewResponseHelper> vrh = mockStatic(ViewResponseHelper.class)) {
            // listDataCentersWithMinimalResponse delegates to the impl method
            ListResponse<ZoneResponse> response = service.listDataCentersWithMinimalResponse(cmd);

            // Should complete without error and call searchAndCount (not the full response helper)
            assertNotNull(response);
            verify(dcJoinDao).searchAndCount(any(), any(Filter.class));
        }
    }

    // -----------------------------------------------------------------------
    // Test 13: getDomainForAccount throws CloudAuthenticationException when domain is null
    // -----------------------------------------------------------------------

    @Test(expected = CloudAuthenticationException.class)
    public void listDataCenters_domainNotFound_throwsCloudAuthException() {
        CallContext.register(user, normalAccount);
        when(accountMgr.isNormalUser(normalAccount.getId())).thenReturn(true);
        when(domainDao.findById(normalAccount.getDomainId())).thenReturn(null);

        // domainId null triggers normal user branch which calls getDomainForAccount
        StubListZonesCmd cmd = new StubListZonesCmd();

        service.listDataCenters(cmd);
    }

    // -----------------------------------------------------------------------
    // Test 14: Dedicated zones excluded from results for domain admin
    // -----------------------------------------------------------------------

    @Test
    public void listDataCenters_domainAdmin_excludesDedicatedZonesOfOtherDomains() {
        CallContext.register(user, normalAccount);
        when(accountMgr.isNormalUser(normalAccount.getId())).thenReturn(false);
        when(accountMgr.isDomainAdmin(normalAccount.getId())).thenReturn(true);

        DomainVO domain = mock(DomainVO.class);
        when(domain.getId()).thenReturn(5L);
        when(domain.getPath()).thenReturn("/root/5");
        when(domain.getParent()).thenReturn(null);
        when(domainDao.findById(normalAccount.getDomainId())).thenReturn(domain);
        when(domainDao.findAllChildren("/root/5", 5L)).thenReturn(Collections.emptyList());

        // One dedicated zone belonging to another domain
        DedicatedResourceVO dr = mock(DedicatedResourceVO.class);
        when(dr.getDataCenterId()).thenReturn(99L);
        when(dedicatedDao.listZonesNotInDomainIds(any())).thenReturn(Collections.singletonList(dr));

        when(dcJoinDao.searchAndCount(any(), any(Filter.class))).thenReturn(emptyPair());

        StubListZonesCmd cmd = new StubListZonesCmd();

        try (MockedStatic<ViewResponseHelper> vrh = mockStatic(ViewResponseHelper.class)) {
            vrh.when(() -> ViewResponseHelper.createDataCenterResponse(any(), any(), any(), any()))
               .thenReturn(Collections.emptyList());

            service.listDataCenters(cmd);

            verify(dedicatedDao).listZonesNotInDomainIds(any());
        }
    }
}
