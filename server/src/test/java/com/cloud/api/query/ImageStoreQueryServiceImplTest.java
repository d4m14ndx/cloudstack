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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.cloudstack.api.command.admin.storage.ListImageStoresCmd;
import org.apache.cloudstack.api.command.admin.storage.ListSecondaryStagingStoresCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.dao.ImageStoreJoinDao;
import com.cloud.api.query.vo.ImageStoreJoinVO;
import com.cloud.storage.DataStoreRole;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class ImageStoreQueryServiceImplTest {

    @Mock private AccountManager accountMgr;
    @Mock private ImageStoreJoinDao imageStoreJoinDao;

    @Mock private SearchBuilder<ImageStoreJoinVO> searchBuilder;
    @Mock private SearchCriteria<ImageStoreJoinVO> searchCriteria;
    @Mock private SearchCriteria<ImageStoreJoinVO> keywordSubCriteria;

    @InjectMocks
    private ImageStoreQueryServiceImpl service;

    private AccountVO account;
    private UserVO user;

    @Before
    public void setUp() {
        // The SearchBuilder needs an entity() returning a non-null pojo so the
        // generated .and(...) sb-style calls don't NPE.
        ImageStoreJoinVO viewEntity = mock(ImageStoreJoinVO.class);
        when(searchBuilder.entity()).thenReturn(viewEntity);
        when(imageStoreJoinDao.createSearchBuilder()).thenReturn(searchBuilder);
        when(searchBuilder.create()).thenReturn(searchCriteria);
        when(imageStoreJoinDao.createSearchCriteria()).thenReturn(keywordSubCriteria);

        account = new AccountVO("testaccount", 1L, "networkdomain", Account.Type.NORMAL, "uuid");
        account.setId(2L);
        user = new UserVO(1, "testuser", "password", "firstname", "lastName", "email", "timezone",
                UUID.randomUUID().toString(), User.Source.UNKNOWN);
        CallContext.register(user, account);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // ---------- searchForImageStoresInternal ----------

    private ListImageStoresCmd mockListCmd() {
        ListImageStoresCmd cmd = mock(ListImageStoresCmd.class);
        when(cmd.getZoneId()).thenReturn(null);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getStoreName()).thenReturn(null);
        when(cmd.getProvider()).thenReturn(null);
        when(cmd.getProtocol()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(50L);
        when(cmd.getReadonly()).thenReturn(null);
        return cmd;
    }

    @Test
    public void searchForImageStoresInternalReturnsEmptyWhenCountZero() {
        ListImageStoresCmd cmd = mockListCmd();
        when(imageStoreJoinDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        Pair<List<ImageStoreJoinVO>, Integer> result = service.searchForImageStoresInternal(cmd);

        assertNotNull(result);
        assertEquals(Integer.valueOf(0), result.second());
        assertEquals(0, result.first().size());
        verify(imageStoreJoinDao, never()).searchByIds(any(Long[].class));
    }

    @Test
    public void searchForImageStoresInternalRunsSecondQueryWhenResultsNonEmpty() {
        ListImageStoresCmd cmd = mockListCmd();
        ImageStoreJoinVO v1 = mock(ImageStoreJoinVO.class);
        when(v1.getId()).thenReturn(11L);
        ImageStoreJoinVO v2 = mock(ImageStoreJoinVO.class);
        when(v2.getId()).thenReturn(12L);
        when(imageStoreJoinDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Arrays.asList(v1, v2), 2));
        List<ImageStoreJoinVO> joined = Arrays.asList(v1, v2);
        when(imageStoreJoinDao.searchByIds(any(Long[].class))).thenReturn(joined);

        Pair<List<ImageStoreJoinVO>, Integer> result = service.searchForImageStoresInternal(cmd);

        assertEquals(Integer.valueOf(2), result.second());
        assertEquals(2, result.first().size());
        verify(imageStoreJoinDao).searchByIds(any(Long[].class));
    }

    @Test
    public void searchForImageStoresInternalPinsRoleToImage() {
        ListImageStoresCmd cmd = mockListCmd();
        when(imageStoreJoinDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForImageStoresInternal(cmd);

        verify(searchCriteria).setParameters("role", DataStoreRole.Image);
    }

    @Test
    public void searchForImageStoresInternalDelegatesZoneAuthorityCheck() {
        ListImageStoresCmd cmd = mockListCmd();
        when(cmd.getZoneId()).thenReturn(7L);
        when(accountMgr.checkAccessAndSpecifyAuthority(account, 7L)).thenReturn(7L);
        when(imageStoreJoinDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForImageStoresInternal(cmd);

        verify(accountMgr).checkAccessAndSpecifyAuthority(account, 7L);
        verify(searchCriteria).setParameters("dataCenterId", 7L);
    }

    @Test
    public void searchForImageStoresInternalAppliesIdFilter() {
        ListImageStoresCmd cmd = mockListCmd();
        when(cmd.getId()).thenReturn(42L);
        when(imageStoreJoinDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForImageStoresInternal(cmd);

        verify(searchCriteria).setParameters("id", 42L);
    }

    @Test
    public void searchForImageStoresInternalAppliesNameProviderProtocol() {
        ListImageStoresCmd cmd = mockListCmd();
        when(cmd.getStoreName()).thenReturn("nfs1");
        when(cmd.getProvider()).thenReturn("NFS");
        when(cmd.getProtocol()).thenReturn("nfs");
        when(imageStoreJoinDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForImageStoresInternal(cmd);

        verify(searchCriteria).setParameters("name", "nfs1");
        verify(searchCriteria).setParameters("provider", "NFS");
        verify(searchCriteria).setParameters("protocol", "nfs");
    }

    @Test
    public void searchForImageStoresInternalAppliesReadonlyFilterWhenSet() {
        ListImageStoresCmd cmd = mockListCmd();
        when(cmd.getReadonly()).thenReturn(Boolean.TRUE);
        when(imageStoreJoinDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForImageStoresInternal(cmd);

        verify(searchCriteria).setParameters("readonly", Boolean.TRUE);
    }

    @Test
    public void searchForImageStoresInternalSkipsReadonlyWhenNull() {
        ListImageStoresCmd cmd = mockListCmd();
        when(imageStoreJoinDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForImageStoresInternal(cmd);

        verify(searchCriteria, never()).setParameters(eq("readonly"), any());
    }

    @Test
    public void searchForImageStoresInternalAppliesKeywordAsSubCriteria() {
        ListImageStoresCmd cmd = mockListCmd();
        when(cmd.getKeyword()).thenReturn("hot");
        when(imageStoreJoinDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForImageStoresInternal(cmd);

        verify(imageStoreJoinDao).createSearchCriteria();
        verify(searchCriteria).addAnd(eq("name"), eq(SearchCriteria.Op.SC), eq(keywordSubCriteria));
    }

    // ---------- searchForCacheStoresInternal ----------

    private ListSecondaryStagingStoresCmd mockCacheCmd() {
        ListSecondaryStagingStoresCmd cmd = mock(ListSecondaryStagingStoresCmd.class);
        when(cmd.getZoneId()).thenReturn(null);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getStoreName()).thenReturn(null);
        when(cmd.getProvider()).thenReturn(null);
        when(cmd.getProtocol()).thenReturn(null);
        when(cmd.getKeyword()).thenReturn(null);
        when(cmd.getStartIndex()).thenReturn(0L);
        when(cmd.getPageSizeVal()).thenReturn(50L);
        return cmd;
    }

    @Test
    public void searchForCacheStoresInternalReturnsEmptyWhenCountZero() {
        ListSecondaryStagingStoresCmd cmd = mockCacheCmd();
        when(imageStoreJoinDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        Pair<List<ImageStoreJoinVO>, Integer> result = service.searchForCacheStoresInternal(cmd);

        assertEquals(Integer.valueOf(0), result.second());
        verify(imageStoreJoinDao, never()).searchByIds(any(Long[].class));
    }

    @Test
    public void searchForCacheStoresInternalPinsRoleToImageCache() {
        ListSecondaryStagingStoresCmd cmd = mockCacheCmd();
        when(imageStoreJoinDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForCacheStoresInternal(cmd);

        verify(searchCriteria).setParameters("role", DataStoreRole.ImageCache);
    }

    @Test
    public void searchForCacheStoresInternalRunsSecondQueryWhenResultsNonEmpty() {
        ListSecondaryStagingStoresCmd cmd = mockCacheCmd();
        ImageStoreJoinVO v1 = mock(ImageStoreJoinVO.class);
        when(v1.getId()).thenReturn(101L);
        when(imageStoreJoinDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.singletonList(v1), 1));
        when(imageStoreJoinDao.searchByIds(any(Long[].class)))
                .thenReturn(Collections.singletonList(v1));

        Pair<List<ImageStoreJoinVO>, Integer> result = service.searchForCacheStoresInternal(cmd);

        assertEquals(Integer.valueOf(1), result.second());
        assertEquals(1, result.first().size());
        verify(imageStoreJoinDao, times(1)).searchByIds(any(Long[].class));
    }

    @Test
    public void searchForCacheStoresInternalAppliesAllFiltersWhenProvided() {
        ListSecondaryStagingStoresCmd cmd = mockCacheCmd();
        when(cmd.getId()).thenReturn(99L);
        when(cmd.getStoreName()).thenReturn("cache1");
        when(cmd.getProvider()).thenReturn("NFS");
        when(cmd.getProtocol()).thenReturn("nfs");
        when(cmd.getZoneId()).thenReturn(3L);
        when(accountMgr.checkAccessAndSpecifyAuthority(account, 3L)).thenReturn(3L);
        when(imageStoreJoinDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForCacheStoresInternal(cmd);

        verify(searchCriteria).setParameters("id", 99L);
        verify(searchCriteria).setParameters("name", "cache1");
        verify(searchCriteria).setParameters("dataCenterId", 3L);
        verify(searchCriteria).setParameters("provider", "NFS");
        verify(searchCriteria).setParameters("protocol", "nfs");
    }

    @Test
    public void searchForCacheStoresInternalAppliesKeywordAsSubCriteria() {
        ListSecondaryStagingStoresCmd cmd = mockCacheCmd();
        when(cmd.getKeyword()).thenReturn("k1");
        when(imageStoreJoinDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForCacheStoresInternal(cmd);

        verify(imageStoreJoinDao).createSearchCriteria();
        verify(searchCriteria).addAnd(eq("name"), eq(SearchCriteria.Op.SC), eq(keywordSubCriteria));
    }

    @Test
    public void searchForCacheStoresInternalSkipsAuthorityWhenZoneNull() {
        ListSecondaryStagingStoresCmd cmd = mockCacheCmd();
        when(accountMgr.checkAccessAndSpecifyAuthority(eq(account), isNull())).thenReturn(null);
        when(imageStoreJoinDao.searchAndCount(eq(searchCriteria), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForCacheStoresInternal(cmd);

        verify(searchCriteria, never()).setParameters(eq("dataCenterId"), anyLong());
    }
}
