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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.dao.SnapshotJoinDao;
import com.cloud.api.query.vo.SnapshotJoinVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.storage.Snapshot;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

@RunWith(MockitoJUnitRunner.class)
public class SnapshotQueryServiceImplTest {

    private static final long CALLER_ID = 42L;

    @Mock private AccountManager accountMgr;
    @Mock private SnapshotJoinDao snapshotJoinDao;
    @Mock private SnapshotDataStoreDao snapshotDataStoreDao;
    @Mock private VolumeDao volumeDao;
    @Mock private ResourceTagDao resourceTagDao;

    @Mock private SearchBuilder<SnapshotJoinVO> snapshotSearchBuilder;
    @Mock private SearchCriteria<SnapshotJoinVO> snapshotSearchCriteria;
    @Mock private SnapshotJoinVO snapshotEntity;
    @Mock private Account caller;

    @InjectMocks
    private SnapshotQueryServiceImpl service;

    private MockedStatic<org.apache.cloudstack.context.CallContext> callContextStatic;

    @Before
    public void setUp() {
        Mockito.lenient().when(snapshotJoinDao.createSearchBuilder()).thenReturn(snapshotSearchBuilder);
        Mockito.lenient().when(snapshotSearchBuilder.entity()).thenReturn(snapshotEntity);
        Mockito.lenient().when(snapshotSearchBuilder.create()).thenReturn(snapshotSearchCriteria);
        Mockito.lenient().when(snapshotSearchBuilder.and()).thenReturn(snapshotSearchBuilder);

        Mockito.lenient().when(caller.getId()).thenReturn(CALLER_ID);
        org.apache.cloudstack.context.CallContext ctx = mock(org.apache.cloudstack.context.CallContext.class);
        Mockito.lenient().when(ctx.getCallingAccount()).thenReturn(caller);
        callContextStatic = Mockito.mockStatic(org.apache.cloudstack.context.CallContext.class);
        callContextStatic.when(org.apache.cloudstack.context.CallContext::current).thenReturn(ctx);
    }

    @After
    public void tearDown() {
        callContextStatic.close();
    }

    // --- Helper ---

    /**
     * Run a simple search with no optional filters — all nulls — and a stubbed
     * empty result from the DAO layer.
     */
    private Pair<List<SnapshotJoinVO>, Integer> runBasicSearch() {
        when(snapshotJoinDao.searchAndDistinctCount(any(), any(Filter.class), any(String[].class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));
        return service.searchForSnapshotsWithParams(
                null, null, null, null, null, null,
                null, null, null, null,
                false,
                null, null, null, null, null,
                0L, 50L, true, false, caller);
    }

    // ---- Location type validation ----

    @Test
    public void searchForSnapshotsThrowsForInvalidLocationType() {
        try {
            service.searchForSnapshotsWithParams(
                    null, null, null, null, null, null,
                    null, null, null, "INVALID_LOCATION",
                    false, null, null, null, null, null,
                    0L, 50L, true, false, caller);
            fail("expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            // expected
        }
    }

    @Test
    public void searchForSnapshotsAcceptsPrimaryLocationType() {
        when(snapshotJoinDao.searchAndDistinctCount(any(), any(Filter.class), any(String[].class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        Pair<List<SnapshotJoinVO>, Integer> result = service.searchForSnapshotsWithParams(
                null, null, null, null, null, null,
                null, null, null, "PRIMARY",
                false, null, null, null, null, null,
                0L, 50L, true, false, caller);

        assertNotNull(result);
        verify(snapshotSearchCriteria).setParameters(eq("locationType"), eq("PRIMARY"));
    }

    @Test
    public void searchForSnapshotsAcceptsSecondaryLocationType() {
        when(snapshotJoinDao.searchAndDistinctCount(any(), any(Filter.class), any(String[].class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForSnapshotsWithParams(
                null, null, null, null, null, null,
                null, null, null, "SECONDARY",
                false, null, null, null, null, null,
                0L, 50L, true, false, caller);

        // SECONDARY maps to DataStoreRole.Image.name()
        verify(snapshotSearchCriteria).setParameters(eq("locationType"), eq("Image"));
    }

    // ---- Snapshot type validation ----

    @Test
    public void searchForSnapshotsThrowsForUnsupportedSnapshotType() {
        try {
            service.searchForSnapshotsWithParams(
                    null, null, null, null, null, null,
                    "BOGUS_TYPE", null, null, null,
                    false, null, null, null, null, null,
                    0L, 50L, true, false, caller);
            fail("expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            // expected
        }
    }

    @Test
    public void searchForSnapshotsExpandsRecurringTypeToFourIntervals() {
        when(snapshotJoinDao.searchAndDistinctCount(any(), any(Filter.class), any(String[].class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForSnapshotsWithParams(
                null, null, null, null, null, null,
                "RECURRING", null, null, null,
                false, null, null, null, null, null,
                0L, 50L, true, false, caller);

        // RECURRING expands to HOURLY/DAILY/WEEKLY/MONTHLY ordinals
        verify(snapshotSearchCriteria).setParameters(
                eq("snapshotTypeEQ"),
                eq(Snapshot.Type.HOURLY.ordinal()),
                eq(Snapshot.Type.DAILY.ordinal()),
                eq(Snapshot.Type.WEEKLY.ordinal()),
                eq(Snapshot.Type.MONTHLY.ordinal()));
    }

    @Test
    public void searchForSnapshotsThrowsForUnsupportedIntervalType() {
        try {
            service.searchForSnapshotsWithParams(
                    null, null, 99L, null, null, null,
                    null, "BOGUS_INTERVAL", null, null,
                    false, null, null, null, null, null,
                    0L, 50L, true, false, caller);
            fail("expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            // expected
        }
    }

    @Test
    public void searchForSnapshotsWithNoTypeFiltersExcludesTemplateAndGroup() {
        runBasicSearch();
        // Default: show only MANUAL and RECURRING types by excluding TEMPLATE and GROUP
        verify(snapshotSearchCriteria).setParameters(
                eq("snapshotTypeNEQ"),
                eq(Snapshot.Type.TEMPLATE.ordinal()),
                eq(Snapshot.Type.GROUP.ordinal()));
    }

    // ---- Volume access check ----

    @Test
    public void searchForSnapshotsChecksVolumeAccessWhenVolumeFound() {
        VolumeVO volume = mock(VolumeVO.class);
        when(volumeDao.findById(77L)).thenReturn(volume);
        when(snapshotJoinDao.searchAndDistinctCount(any(), any(Filter.class), any(String[].class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForSnapshotsWithParams(
                null, null, 77L, null, null, null,
                null, null, null, null,
                false, null, null, null, null, null,
                0L, 50L, true, false, caller);

        verify(accountMgr).checkAccess(eq(caller), eq(null), eq(true), eq(volume));
    }

    @Test
    public void searchForSnapshotsSkipsAccessCheckWhenVolumeNotFound() {
        when(volumeDao.findById(99L)).thenReturn(null);
        when(snapshotJoinDao.searchAndDistinctCount(any(), any(Filter.class), any(String[].class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForSnapshotsWithParams(
                null, null, 99L, null, null, null,
                null, null, null, null,
                false, null, null, null, null, null,
                0L, 50L, true, false, caller);

        verify(accountMgr, never()).checkAccess(eq(caller), any(), anyBoolean(), any());
    }

    @Test
    public void searchForSnapshotsPropagatesPermissionDeniedForVolume() {
        VolumeVO volume = mock(VolumeVO.class);
        when(volumeDao.findById(55L)).thenReturn(volume);
        doThrow(new PermissionDeniedException("denied"))
                .when(accountMgr).checkAccess(eq(caller), eq(null), eq(true), eq(volume));

        try {
            service.searchForSnapshotsWithParams(
                    null, null, 55L, null, null, null,
                    null, null, null, null,
                    false, null, null, null, null, null,
                    0L, 50L, true, false, caller);
            fail("expected PermissionDeniedException");
        } catch (PermissionDeniedException expected) {
            // expected
        }
    }

    // ---- ACL routing ----

    @Test
    public void searchForSnapshotsRoutesAclParametersToAccountManager() {
        when(snapshotJoinDao.searchAndDistinctCount(any(), any(Filter.class), any(String[].class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForSnapshotsWithParams(
                10L, null, null, null, null, null,
                null, null, null, null,
                false, "alice", 5L, 3L, null, null,
                0L, 50L, true, true, caller);

        verify(accountMgr).buildACLSearchParameters(eq(caller), eq(10L), eq("alice"), eq(3L),
                anyList(), any(Ternary.class), eq(true), eq(false));
    }

    @Test
    public void searchForSnapshotsSetsDistinctSelectAndInvokesAclBuilder() {
        runBasicSearch();
        verify(snapshotSearchBuilder, atLeastOnce()).select(eq(null), eq(SearchCriteria.Func.DISTINCT), any());
        verify(accountMgr).buildACLSearchBuilder(eq(snapshotSearchBuilder), any(), anyBoolean(),
                anyList(), any());
        verify(accountMgr).buildACLSearchCriteria(eq(snapshotSearchCriteria), any(), anyBoolean(),
                anyList(), any());
    }

    // ---- ImageStoreId forces SECONDARY locationType ----

    @Test
    public void searchForSnapshotsWithImageStoreIdForcesSecondaryLocationType() {
        when(snapshotJoinDao.searchAndDistinctCount(any(), any(Filter.class), any(String[].class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForSnapshotsWithParams(
                null, null, null, null, null, null,
                null, null, null, null,
                false, null, null, null, null, 88L,
                0L, 50L, true, false, caller);

        verify(snapshotSearchCriteria).setParameters(eq("imageStoreId"), eq(88L));
        // locationType becomes SECONDARY -> maps to DataStoreRole.Image
        verify(snapshotSearchCriteria).setParameters(eq("locationType"), eq("Image"));
    }

    // ---- ShowUnique flag ----

    @Test
    public void searchForSnapshotsSelectsDistinctByIdWhenShowUnique() {
        when(snapshotJoinDao.searchAndDistinctCount(any(), any(Filter.class), any(String[].class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForSnapshotsWithParams(
                null, null, null, null, null, null,
                null, null, null, null,
                true, null, null, null, null, null,
                0L, 50L, true, false, caller);

        // searchAndDistinctCount is called with "snapshot_view.id" column group
        verify(snapshotJoinDao).searchAndDistinctCount(any(), any(Filter.class),
                eq(new String[]{"snapshot_view.id"}));
    }

    @Test
    public void searchForSnapshotsSelectsDistinctByStorePairWhenNotShowUnique() {
        runBasicSearch();
        verify(snapshotJoinDao).searchAndDistinctCount(any(), any(Filter.class),
                eq(new String[]{"snapshot_view.snapshot_store_pair"}));
    }

    // ---- Empty result passthrough ----

    @Test
    public void searchForSnapshotsReturnsEmptyPairWhenCountIsZero() {
        Pair<List<SnapshotJoinVO>, Integer> result = runBasicSearch();

        assertNotNull(result);
        assertEquals(Integer.valueOf(0), result.second());
        assertTrue(result.first().isEmpty());
        // findByDistinctIds / searchBySnapshotStorePair must NOT be called
        verify(snapshotJoinDao, never()).findByDistinctIds(any(), any());
        verify(snapshotJoinDao, never()).searchBySnapshotStorePair(any());
    }

    // ---- Hydration ----

    @Test
    public void searchForSnapshotsHydratesViaStorePairWhenNotShowUnique() {
        SnapshotJoinVO row = mock(SnapshotJoinVO.class);
        when(row.getSnapshotStorePair()).thenReturn("1:IMAGE:5");
        when(snapshotJoinDao.searchAndDistinctCount(any(), any(Filter.class), any(String[].class)))
                .thenReturn(new Pair<>(Collections.singletonList(row), 1));
        SnapshotJoinVO full = mock(SnapshotJoinVO.class);
        when(snapshotJoinDao.searchBySnapshotStorePair(any(String[].class)))
                .thenReturn(Collections.singletonList(full));

        Pair<List<SnapshotJoinVO>, Integer> result = service.searchForSnapshotsWithParams(
                null, null, null, null, null, null,
                null, null, null, null,
                false, null, null, null, null, null,
                0L, 50L, true, false, caller);

        assertEquals(Integer.valueOf(1), result.second());
        assertEquals(1, result.first().size());
        assertSame(full, result.first().get(0));
    }

    @Test
    public void searchForSnapshotsHydratesViaIdWhenShowUnique() {
        SnapshotJoinVO row = mock(SnapshotJoinVO.class);
        when(row.getId()).thenReturn(100L);
        when(snapshotJoinDao.searchAndDistinctCount(any(), any(Filter.class), any(String[].class)))
                .thenReturn(new Pair<>(Collections.singletonList(row), 1));
        SnapshotJoinVO full = mock(SnapshotJoinVO.class);
        when(snapshotJoinDao.findByDistinctIds(any(), any(Long[].class)))
                .thenReturn(Collections.singletonList(full));

        Pair<List<SnapshotJoinVO>, Integer> result = service.searchForSnapshotsWithParams(
                null, null, null, null, null, null,
                null, null, null, null,
                true, null, null, null, null, null,
                0L, 50L, true, false, caller);

        assertEquals(Integer.valueOf(1), result.second());
        assertSame(full, result.first().get(0));
        verify(snapshotJoinDao).findByDistinctIds(any(), any(Long[].class));
    }

    // ---- Tag join ----

    @Test
    public void searchForSnapshotsBuildTagJoinWhenTagsProvided() {
        SearchBuilder<ResourceTagVO> tagSearchBuilder = mock(SearchBuilder.class);
        ResourceTagVO tagEntity = mock(ResourceTagVO.class);
        when(resourceTagDao.createSearchBuilder()).thenReturn(tagSearchBuilder);
        when(tagSearchBuilder.entity()).thenReturn(tagEntity);
        Mockito.lenient().when(tagSearchBuilder.or()).thenReturn(tagSearchBuilder);
        Mockito.lenient().when(tagSearchBuilder.and()).thenReturn(tagSearchBuilder);
        when(snapshotJoinDao.searchAndDistinctCount(any(), any(Filter.class), any(String[].class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        Map<String, String> tags = new LinkedHashMap<>();
        tags.put("env", "prod");

        service.searchForSnapshotsWithParams(
                null, null, null, null, null, tags,
                null, null, null, null,
                false, null, null, null, null, null,
                0L, 50L, true, false, caller);

        verify(resourceTagDao).createSearchBuilder();
        verify(snapshotSearchCriteria).setJoinParameters(eq("tagSearch"), eq("resourceType"),
                eq("Snapshot"));
    }

    // ---- StoragePool join ----

    @Test
    public void searchForSnapshotsBuildStoragePoolJoinWhenPoolIdProvided() {
        SearchBuilder<SnapshotDataStoreVO> poolSb = mock(SearchBuilder.class);
        SnapshotDataStoreVO poolEntity = mock(SnapshotDataStoreVO.class);
        when(snapshotDataStoreDao.createSearchBuilder()).thenReturn(poolSb);
        when(poolSb.entity()).thenReturn(poolEntity);
        when(snapshotJoinDao.searchAndDistinctCount(any(), any(Filter.class), any(String[].class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForSnapshotsWithParams(
                null, null, null, null, null, null,
                null, null, null, null,
                false, null, null, null, 77L, null,
                0L, 50L, true, false, caller);

        verify(snapshotDataStoreDao).createSearchBuilder();
        verify(snapshotSearchCriteria).setJoinParameters(eq("storagePoolSb"), eq("poolId"), eq(77L));
    }

    // ---- Zone and keyword filters ----

    @Test
    public void searchForSnapshotsSetsZoneFilterWhenZoneIdProvided() {
        when(snapshotJoinDao.searchAndDistinctCount(any(), any(Filter.class), any(String[].class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForSnapshotsWithParams(
                null, null, null, null, null, null,
                null, null, 7L, null,
                false, null, null, null, null, null,
                0L, 50L, true, false, caller);

        verify(snapshotSearchCriteria).setParameters(eq("dataCenterId"), eq(7L));
    }

    @Test
    public void searchForSnapshotsSetsNameFilterAndKeywordSearch() {
        SearchCriteria<SnapshotJoinVO> keywordSsc = mock(SearchCriteria.class);
        when(snapshotJoinDao.createSearchCriteria()).thenReturn(keywordSsc);
        when(snapshotJoinDao.searchAndDistinctCount(any(), any(Filter.class), any(String[].class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForSnapshotsWithParams(
                null, null, null, "mySnap", "snap", null,
                null, null, null, null,
                false, null, null, null, null, null,
                0L, 50L, true, false, caller);

        verify(snapshotSearchCriteria).setParameters(eq("name"), eq("mySnap"));
        verify(keywordSsc).addOr(eq("name"), eq(SearchCriteria.Op.LIKE), eq("%snap%"));
    }
}
