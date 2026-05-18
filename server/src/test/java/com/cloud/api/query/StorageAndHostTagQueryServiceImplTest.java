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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.command.admin.host.ListHostTagsCmd;
import org.apache.cloudstack.api.command.admin.storage.ListStorageTagsCmd;
import org.apache.cloudstack.api.response.HostTagResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.StorageTagResponse;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.host.HostTagVO;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.storage.StoragePoolTagVO;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;

@RunWith(MockitoJUnitRunner.class)
public class StorageAndHostTagQueryServiceImplTest {

    private StorageAndHostTagQueryServiceImpl service;

    @Mock
    private StoragePoolTagsDao storageTagDao;

    @Mock
    private HostTagsDao hostTagDao;

    @Mock
    private SearchBuilder<StoragePoolTagVO> storageSearchBuilder;

    @Mock
    private SearchCriteria<StoragePoolTagVO> storageSearchCriteria;

    @Mock
    private SearchBuilder<HostTagVO> hostSearchBuilder;

    @Mock
    private SearchCriteria<HostTagVO> hostSearchCriteria;

    @Before
    public void setUp() {
        service = new StorageAndHostTagQueryServiceImpl();
        ReflectionTestUtils.setField(service, "storageTagDao", storageTagDao);
        ReflectionTestUtils.setField(service, "hostTagDao", hostTagDao);
    }

    @Test
    public void searchForStorageTagsReturnsResponseWithHydratedCount() {
        StoragePoolTagVO tag = storageTag(1L, 11L, "fast");
        StorageTagResponse tagResponse = new StorageTagResponse();
        List<StorageTagResponse> tagResponses = Collections.singletonList(tagResponse);
        stubStorageInternalSearch(Collections.singletonList(tag), 1, Collections.singletonList(tag));

        try (MockedStatic<ViewResponseHelper> viewResponseHelper = Mockito.mockStatic(ViewResponseHelper.class)) {
            viewResponseHelper.when(() -> ViewResponseHelper.createStorageTagResponse(any(StoragePoolTagVO[].class))).thenReturn(tagResponses);

            ListResponse<StorageTagResponse> response = service.searchForStorageTags(mock(ListStorageTagsCmd.class));

            assertSame(tagResponses, response.getResponses());
            assertEquals(Integer.valueOf(1), response.getCount());
        }
    }

    @Test
    public void searchForStorageTagsUsesHydratedStorageRowsForResponseCreation() {
        StoragePoolTagVO distinctTag = storageTag(1L, 11L, "fast");
        StoragePoolTagVO hydratedTag = storageTag(2L, 11L, "fast");
        stubStorageInternalSearch(Collections.singletonList(distinctTag), 1, Collections.singletonList(hydratedTag));

        try (MockedStatic<ViewResponseHelper> viewResponseHelper = Mockito.mockStatic(ViewResponseHelper.class)) {
            viewResponseHelper.when(() -> ViewResponseHelper.createStorageTagResponse(any(StoragePoolTagVO[].class))).thenReturn(Collections.emptyList());

            service.searchForStorageTags(mock(ListStorageTagsCmd.class));

            viewResponseHelper.verify(() -> ViewResponseHelper.createStorageTagResponse(eq(new StoragePoolTagVO[] {hydratedTag})));
        }
    }

    @Test
    public void searchForStorageTagsInternalReturnsEmptyPairWhenCountZero() {
        Pair<List<StoragePoolTagVO>, Integer> expected = new Pair<>(Collections.emptyList(), 0);
        stubStorageSearchBuilder();
        when(storageTagDao.searchAndCount(eq(storageSearchCriteria), any(Filter.class))).thenReturn(expected);

        Pair<List<StoragePoolTagVO>, Integer> result = service.searchForStorageTagsInternal();

        assertSame(expected, result);
    }

    @Test
    public void searchForStorageTagsInternalDoesNotHydrateWhenCountZero() {
        stubStorageSearchBuilder();
        when(storageTagDao.searchAndCount(eq(storageSearchCriteria), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForStorageTagsInternal();

        verify(storageTagDao, never()).searchByIds(any(Long[].class));
    }

    @Test
    public void searchForStorageTagsInternalSelectsDistinctId() {
        stubStorageSearchBuilder();
        when(storageTagDao.searchAndCount(eq(storageSearchCriteria), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForStorageTagsInternal();

        verify(storageSearchBuilder).select(eq(null), eq(Func.DISTINCT), any());
    }

    @Test
    public void searchForStorageTagsInternalHydratesDistinctIdsInOrder() {
        StoragePoolTagVO first = storageTag(3L, 11L, "fast");
        StoragePoolTagVO second = storageTag(9L, 12L, "slow");
        List<StoragePoolTagVO> hydrated = Arrays.asList(second, first);
        stubStorageInternalSearch(Arrays.asList(first, second), 2, hydrated);

        Pair<List<StoragePoolTagVO>, Integer> result = service.searchForStorageTagsInternal();

        ArgumentCaptor<Long[]> idsCaptor = ArgumentCaptor.forClass(Long[].class);
        verify(storageTagDao).searchByIds(idsCaptor.capture());
        assertArrayEquals(new Long[] {3L, 9L}, idsCaptor.getValue());
        assertSame(hydrated, result.first());
        assertEquals(Integer.valueOf(2), result.second());
    }

    @Test
    public void searchForHostTagsReturnsResponseWithHydratedCount() {
        HostTagVO tag = hostTag(1L, 21L, "gpu");
        HostTagResponse tagResponse = new HostTagResponse();
        List<HostTagResponse> tagResponses = Collections.singletonList(tagResponse);
        stubHostInternalSearch(Collections.singletonList(tag), 1, Collections.singletonList(tag));

        try (MockedStatic<ViewResponseHelper> viewResponseHelper = Mockito.mockStatic(ViewResponseHelper.class)) {
            viewResponseHelper.when(() -> ViewResponseHelper.createHostTagResponse(any(HostTagVO[].class))).thenReturn(tagResponses);

            ListResponse<HostTagResponse> response = service.searchForHostTags(mock(ListHostTagsCmd.class));

            assertSame(tagResponses, response.getResponses());
            assertEquals(Integer.valueOf(1), response.getCount());
        }
    }

    @Test
    public void searchForHostTagsUsesHydratedHostRowsForResponseCreation() {
        HostTagVO distinctTag = hostTag(1L, 21L, "gpu");
        HostTagVO hydratedTag = hostTag(2L, 21L, "gpu");
        stubHostInternalSearch(Collections.singletonList(distinctTag), 1, Collections.singletonList(hydratedTag));

        try (MockedStatic<ViewResponseHelper> viewResponseHelper = Mockito.mockStatic(ViewResponseHelper.class)) {
            viewResponseHelper.when(() -> ViewResponseHelper.createHostTagResponse(any(HostTagVO[].class))).thenReturn(Collections.emptyList());

            service.searchForHostTags(mock(ListHostTagsCmd.class));

            viewResponseHelper.verify(() -> ViewResponseHelper.createHostTagResponse(eq(new HostTagVO[] {hydratedTag})));
        }
    }

    @Test
    public void searchForHostTagsInternalReturnsEmptyPairWhenCountZero() {
        Pair<List<HostTagVO>, Integer> expected = new Pair<>(Collections.emptyList(), 0);
        stubHostSearchBuilder();
        when(hostTagDao.searchAndCount(eq(hostSearchCriteria), any(Filter.class))).thenReturn(expected);

        Pair<List<HostTagVO>, Integer> result = service.searchForHostTagsInternal();

        assertSame(expected, result);
    }

    @Test
    public void searchForHostTagsInternalDoesNotHydrateWhenCountZero() {
        stubHostSearchBuilder();
        when(hostTagDao.searchAndCount(eq(hostSearchCriteria), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForHostTagsInternal();

        verify(hostTagDao, never()).searchByIds(any(Long[].class));
    }

    @Test
    public void searchForHostTagsInternalSelectsDistinctId() {
        stubHostSearchBuilder();
        when(hostTagDao.searchAndCount(eq(hostSearchCriteria), any(Filter.class))).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForHostTagsInternal();

        verify(hostSearchBuilder).select(eq(null), eq(Func.DISTINCT), any());
    }

    @Test
    public void searchForHostTagsInternalHydratesDistinctIdsInOrder() {
        HostTagVO first = hostTag(4L, 21L, "gpu");
        HostTagVO second = hostTag(8L, 22L, "ssd");
        List<HostTagVO> hydrated = Arrays.asList(second, first);
        stubHostInternalSearch(Arrays.asList(first, second), 2, hydrated);

        Pair<List<HostTagVO>, Integer> result = service.searchForHostTagsInternal();

        ArgumentCaptor<Long[]> idsCaptor = ArgumentCaptor.forClass(Long[].class);
        verify(hostTagDao).searchByIds(idsCaptor.capture());
        assertArrayEquals(new Long[] {4L, 8L}, idsCaptor.getValue());
        assertSame(hydrated, result.first());
        assertEquals(Integer.valueOf(2), result.second());
    }

    private void stubStorageInternalSearch(List<StoragePoolTagVO> distinctTags, int count, List<StoragePoolTagVO> hydratedTags) {
        stubStorageSearchBuilder();
        when(storageTagDao.searchAndCount(eq(storageSearchCriteria), any(Filter.class))).thenReturn(new Pair<>(distinctTags, count));
        when(storageTagDao.searchByIds(any(Long[].class))).thenReturn(hydratedTags);
    }

    private void stubStorageSearchBuilder() {
        StoragePoolTagVO entity = mock(StoragePoolTagVO.class);
        when(storageTagDao.createSearchBuilder()).thenReturn(storageSearchBuilder);
        when(storageSearchBuilder.entity()).thenReturn(entity);
        when(storageSearchBuilder.create()).thenReturn(storageSearchCriteria);
    }

    private void stubHostInternalSearch(List<HostTagVO> distinctTags, int count, List<HostTagVO> hydratedTags) {
        stubHostSearchBuilder();
        when(hostTagDao.searchAndCount(eq(hostSearchCriteria), any(Filter.class))).thenReturn(new Pair<>(distinctTags, count));
        when(hostTagDao.searchByIds(any(Long[].class))).thenReturn(hydratedTags);
    }

    private void stubHostSearchBuilder() {
        HostTagVO entity = mock(HostTagVO.class);
        when(hostTagDao.createSearchBuilder()).thenReturn(hostSearchBuilder);
        when(hostSearchBuilder.entity()).thenReturn(entity);
        when(hostSearchBuilder.create()).thenReturn(hostSearchCriteria);
    }

    private StoragePoolTagVO storageTag(long id, long poolId, String tag) {
        StoragePoolTagVO storageTag = new StoragePoolTagVO(poolId, tag);
        ReflectionTestUtils.setField(storageTag, "id", id);
        return storageTag;
    }

    private HostTagVO hostTag(long id, long hostId, String tag) {
        HostTagVO hostTag = new HostTagVO(hostId, tag);
        ReflectionTestUtils.setField(hostTag, "id", id);
        return hostTag;
    }
}
