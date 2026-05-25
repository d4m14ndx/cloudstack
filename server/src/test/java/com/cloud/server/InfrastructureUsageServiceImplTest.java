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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.command.admin.resource.ListCapacityCmd;
import org.apache.cloudstack.backup.BackupManager;
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

import com.cloud.alert.AlertManager;
import com.cloud.api.ApiDBUtils;
import com.cloud.capacity.Capacity;
import com.cloud.capacity.CapacityVO;
import com.cloud.capacity.dao.CapacityDao;
import com.cloud.capacity.dao.CapacityDaoImpl.SummedCapacity;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.StorageManager;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.UserVO;
import com.cloud.utils.Pair;

/**
 * Focused unit tests for {@link InfrastructureUsageServiceImpl}.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Resource-limit tag listing collapses host and storage tag sources
 *       into a deduplicated list that always includes the {@code null}
 *       (untagged) bucket</li>
 *   <li>{@code getHostIdsForCapacityListing} short-circuits when no tag is
 *       supplied and rejects storage capacity types up-front</li>
 *   <li>{@code getStoragePoolIdsForCapacityListing} short-circuits when no
 *       tag is supplied and rejects non-storage capacity types up-front</li>
 *   <li>{@code listTopConsumedResources} rejects {@code clusterId} as
 *       unsupported</li>
 *   <li>{@code listTopConsumedResources} triggers an alert-manager
 *       recalculate pass when {@code fetchLatest} is set</li>
 *   <li>{@code listTopConsumedResources} sorts rows by descending percent
 *       used and clips to the caller's page size</li>
 *   <li>{@code listCapacities} expands a missing scope into the full list
 *       of enabled zones via {@link ApiDBUtils#listZones()}</li>
 *   <li>{@code listCapacities} delegates a zone-scoped call straight
 *       through without consulting {@link ApiDBUtils}</li>
 *   <li>{@code addZoneWideCapacitiesByType} appends secondary/object/backup
 *       buckets when no capacity type filter is applied</li>
 *   <li>{@code addZoneWideCapacitiesByType} filters down to the requested
 *       capacity type when one is supplied</li>
 *   <li>{@code getMemoryOrCpuCapacityByHost} returns {@code 0} when the DAO
 *       finds no row and otherwise sums used and reserved</li>
 *   <li>Wiring smoke test confirms all collaborators are injected</li>
 * </ul>
 */
@RunWith(MockitoJUnitRunner.class)
public class InfrastructureUsageServiceImplTest {

    @Mock
    private CapacityDao capacityDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private DataCenterDao dcDao;
    @Mock
    private StoragePoolTagsDao storagePoolTagsDao;
    @Mock
    private ResourceLimitService resourceLimitService;
    @Mock
    private StorageManager storageMgr;
    @Mock
    private BackupManager backupManager;
    @Mock
    private AlertManager alertMgr;
    @Mock
    private AccountManager accountMgr;

    @InjectMocks
    private InfrastructureUsageServiceImpl service;

    private AutoCloseable closeable;
    private AccountVO callerAccount;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        callerAccount = new AccountVO("admin", 1L, "ROOT", Account.Type.ADMIN, "uuid-admin");
        callerAccount.setId(1L);
        UserVO callerUser = new UserVO();
        callerUser.setUuid("user-uuid");
        CallContext.register(callerUser, callerAccount);
    }

    @After
    public void tearDown() throws Exception {
        CallContext.unregister();
        closeable.close();
    }

    @Test
    public void getResourceLimitTagsForCapacityListingMergesAndDedupes() {
        when(resourceLimitService.getResourceLimitHostTags()).thenReturn(Arrays.asList("gpu", "ssd"));
        when(resourceLimitService.getResourceLimitStorageTags()).thenReturn(Arrays.asList("ssd", "tier1"));

        List<String> tags = service.getResourceLimitTagsForCapacityListing();

        Assert.assertEquals(4, tags.size());
        Assert.assertTrue(tags.contains(null));
        Assert.assertTrue(tags.contains("gpu"));
        Assert.assertTrue(tags.contains("ssd"));
        Assert.assertTrue(tags.contains("tier1"));
    }

    @Test
    public void getHostIdsForCapacityListingReturnsTrueWhenNoTag() {
        Pair<Boolean, List<Long>> result = service.getHostIdsForCapacityListing(1L, null, null, null, null);
        Assert.assertTrue(result.first());
        Assert.assertNull(result.second());
    }

    @Test
    public void getHostIdsForCapacityListingRejectsStorageCapacityTypes() {
        // CAPACITY_TYPE_STORAGE is in STORAGE_CAPACITY_TYPES; combined with a tag
        // the lookup should bail out as false.
        Pair<Boolean, List<Long>> result = service.getHostIdsForCapacityListing(
                1L, null, null, (int) Capacity.CAPACITY_TYPE_STORAGE, "ssd");
        Assert.assertFalse(result.first());
        Assert.assertNull(result.second());
    }

    @Test
    public void getStoragePoolIdsForCapacityListingReturnsTrueWhenNoTag() {
        Pair<Boolean, List<Long>> result = service.getStoragePoolIdsForCapacityListing(null, null);
        Assert.assertTrue(result.first());
        Assert.assertNull(result.second());
    }

    @Test
    public void getStoragePoolIdsForCapacityListingRejectsNonStorageType() {
        // CAPACITY_TYPE_CPU is NOT in STORAGE_CAPACITY_TYPES; with a tag the
        // lookup should bail out as false.
        Pair<Boolean, List<Long>> result = service.getStoragePoolIdsForCapacityListing(
                (int) Capacity.CAPACITY_TYPE_CPU, "ssd");
        Assert.assertFalse(result.first());
        Assert.assertNull(result.second());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void listTopConsumedResourcesRejectsClusterId() {
        ListCapacityCmd cmd = Mockito.mock(ListCapacityCmd.class);
        when(cmd.getClusterId()).thenReturn(7L);
        service.listTopConsumedResources(cmd);
    }

    @Test
    public void listTopConsumedResourcesRecalculatesWhenFetchLatest() {
        ListCapacityCmd cmd = Mockito.mock(ListCapacityCmd.class);
        when(cmd.getClusterId()).thenReturn(null);
        when(cmd.getZoneId()).thenReturn(1L);
        when(cmd.getPodId()).thenReturn(null);
        when(cmd.getFetchLatest()).thenReturn(true);
        when(cmd.getTag()).thenReturn(null);
        when(cmd.getPageSizeVal()).thenReturn(50L);
        when(cmd.getType()).thenReturn(null);
        when(accountMgr.checkAccessAndSpecifyAuthority(any(Account.class), eq(1L))).thenReturn(1L);
        when(resourceLimitService.getResourceLimitHostTags()).thenReturn(Collections.emptyList());
        when(resourceLimitService.getResourceLimitStorageTags()).thenReturn(Collections.emptyList());
        when(capacityDao.listCapacitiesGroupedByLevelAndType(isNull(), anyLong(), isNull(), isNull(), Mockito.anyInt(),
                isNull(), isNull(), anyLong())).thenReturn(Collections.emptyList());

        try (MockedStatic<ApiDBUtils> apiDb = Mockito.mockStatic(ApiDBUtils.class)) {
            // Returning null from findZoneById short-circuits getStorageCapacities
            apiDb.when(() -> ApiDBUtils.findZoneById(anyLong())).thenReturn(null);

            service.listTopConsumedResources(cmd);

            verify(alertMgr, times(1)).recalculateCapacity();
        }
    }

    @Test
    public void listTopConsumedResourcesSortsAndClips() {
        ListCapacityCmd cmd = Mockito.mock(ListCapacityCmd.class);
        when(cmd.getClusterId()).thenReturn(null);
        when(cmd.getZoneId()).thenReturn(null);
        when(cmd.getPodId()).thenReturn(2L);
        when(cmd.getFetchLatest()).thenReturn(false);
        when(cmd.getTag()).thenReturn(null);
        when(cmd.getPageSizeVal()).thenReturn(2L);
        when(cmd.getType()).thenReturn(null);
        when(accountMgr.checkAccessAndSpecifyAuthority(any(Account.class), isNull())).thenReturn(null);
        when(resourceLimitService.getResourceLimitHostTags()).thenReturn(Collections.emptyList());
        when(resourceLimitService.getResourceLimitStorageTags()).thenReturn(Collections.emptyList());

        SummedCapacity low = makeSummed(0.10f, 1, 100, (short) Capacity.CAPACITY_TYPE_CPU);
        SummedCapacity mid = makeSummed(0.50f, 5, 100, (short) Capacity.CAPACITY_TYPE_CPU);
        SummedCapacity high = makeSummed(0.90f, 9, 100, (short) Capacity.CAPACITY_TYPE_CPU);
        when(capacityDao.listCapacitiesGroupedByLevelAndType(isNull(), isNull(), eq(2L), isNull(), Mockito.anyInt(),
                isNull(), isNull(), eq(2L))).thenReturn(Arrays.asList(low, high, mid));
        when(dcDao.listEnabledZones()).thenReturn(Collections.emptyList());

        List<CapacityVO> result = service.listTopConsumedResources(cmd);

        Assert.assertEquals(2, result.size());
        Assert.assertEquals(Float.valueOf(0.90f), result.get(0).getUsedPercentage());
        Assert.assertEquals(Float.valueOf(0.50f), result.get(1).getUsedPercentage());
    }

    @Test
    public void listCapacitiesExpandsMissingScopeToAllZones() {
        ListCapacityCmd cmd = Mockito.mock(ListCapacityCmd.class);
        when(cmd.getZoneId()).thenReturn(null);
        when(cmd.getPodId()).thenReturn(null);
        when(cmd.getClusterId()).thenReturn(null);
        when(cmd.getFetchLatest()).thenReturn(false);
        when(cmd.getTag()).thenReturn(null);
        when(cmd.getType()).thenReturn(null);
        when(accountMgr.checkAccessAndSpecifyAuthority(any(Account.class), isNull())).thenReturn(null);
        when(resourceLimitService.getResourceLimitHostTags()).thenReturn(Collections.emptyList());
        when(resourceLimitService.getResourceLimitStorageTags()).thenReturn(Collections.emptyList());
        when(capacityDao.findFilteredCapacityBy(isNull(), isNull(), isNull(), isNull(), isNull(), isNull()))
                .thenReturn(Collections.emptyList());

        DataCenterVO zone1 = Mockito.mock(DataCenterVO.class);
        when(zone1.getId()).thenReturn(11L);
        DataCenterVO zone2 = Mockito.mock(DataCenterVO.class);
        when(zone2.getId()).thenReturn(22L);

        try (MockedStatic<ApiDBUtils> apiDb = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDb.when(ApiDBUtils::listZones).thenReturn(Arrays.asList(zone1, zone2));
            when(storageMgr.getStoragePoolUsedStats(anyLong(), isNull(), isNull(), Mockito.<List<Long>>isNull()))
                    .thenReturn(makeCapacityVO((short) Capacity.CAPACITY_TYPE_STORAGE));
            when(storageMgr.getSecondaryStorageUsedStats(isNull(), anyLong()))
                    .thenReturn(makeCapacityVO((short) Capacity.CAPACITY_TYPE_SECONDARY_STORAGE));
            when(storageMgr.getObjectStorageUsedStats(anyLong()))
                    .thenReturn(makeCapacityVO((short) Capacity.CAPACITY_TYPE_OBJECT_STORAGE));
            when(backupManager.getBackupStorageUsedStats(anyLong()))
                    .thenReturn(makeCapacityVO((short) Capacity.CAPACITY_TYPE_BACKUP_STORAGE));

            List<CapacityVO> result = service.listCapacities(cmd);

            Assert.assertNotNull(result);
            apiDb.verify(ApiDBUtils::listZones, times(1));
        }
    }

    @Test
    public void listCapacitiesShortCircuitsForZoneScope() {
        ListCapacityCmd cmd = Mockito.mock(ListCapacityCmd.class);
        when(cmd.getZoneId()).thenReturn(3L);
        when(cmd.getPodId()).thenReturn(null);
        when(cmd.getClusterId()).thenReturn(null);
        when(cmd.getFetchLatest()).thenReturn(false);
        when(cmd.getTag()).thenReturn("ssd");
        when(cmd.getType()).thenReturn(null);
        when(accountMgr.checkAccessAndSpecifyAuthority(any(Account.class), eq(3L))).thenReturn(3L);
        when(storagePoolTagsDao.listPoolIdsByTag("ssd")).thenReturn(Arrays.asList(100L));
        when(hostDao.listByHostTag(Mockito.any(), isNull(), isNull(), eq(3L), eq("ssd")))
                .thenReturn(Collections.singletonList(Mockito.mock(HostVO.class)));
        when(capacityDao.findFilteredCapacityBy(isNull(), eq(3L), isNull(), isNull(), any(), any()))
                .thenReturn(Collections.emptyList());
        when(storageMgr.getStoragePoolUsedStats(eq(3L), isNull(), isNull(), Mockito.<List<Long>>any()))
                .thenReturn(makeCapacityVO((short) Capacity.CAPACITY_TYPE_STORAGE));

        try (MockedStatic<ApiDBUtils> apiDb = Mockito.mockStatic(ApiDBUtils.class)) {
            List<CapacityVO> result = service.listCapacities(cmd);
            Assert.assertNotNull(result);
            apiDb.verify(ApiDBUtils::listZones, never());
        }
    }

    @Test
    public void addZoneWideCapacitiesByTypeWithNullAppendsAll() {
        List<CapacityVO> bucket = new ArrayList<>();
        when(storageMgr.getSecondaryStorageUsedStats(isNull(), eq(5L))).thenReturn(makeCapacityVO((short) Capacity.CAPACITY_TYPE_SECONDARY_STORAGE));
        when(storageMgr.getObjectStorageUsedStats(eq(5L))).thenReturn(makeCapacityVO((short) Capacity.CAPACITY_TYPE_OBJECT_STORAGE));
        when(backupManager.getBackupStorageUsedStats(eq(5L))).thenReturn(makeCapacityVO((short) Capacity.CAPACITY_TYPE_BACKUP_STORAGE));

        service.addZoneWideCapacitiesByType(null, 5L, bucket);

        Assert.assertEquals(3, bucket.size());
    }

    @Test
    public void addZoneWideCapacitiesByTypeFiltersDownToOneType() {
        List<CapacityVO> bucket = new ArrayList<>();
        when(storageMgr.getObjectStorageUsedStats(eq(5L))).thenReturn(makeCapacityVO((short) Capacity.CAPACITY_TYPE_OBJECT_STORAGE));

        service.addZoneWideCapacitiesByType((int) Capacity.CAPACITY_TYPE_OBJECT_STORAGE, 5L, bucket);

        Assert.assertEquals(1, bucket.size());
        verify(storageMgr, never()).getSecondaryStorageUsedStats(isNull(), anyLong());
        verify(backupManager, never()).getBackupStorageUsedStats(anyLong());
    }

    @Test
    public void getMemoryOrCpuCapacityByHostReturnsZeroWhenAbsent() {
        when(capacityDao.findByHostIdType(7L, Capacity.CAPACITY_TYPE_CPU)).thenReturn(null);
        Assert.assertEquals(0L, service.getMemoryOrCpuCapacityByHost(7L, Capacity.CAPACITY_TYPE_CPU));
    }

    @Test
    public void getMemoryOrCpuCapacityByHostSumsUsedAndReserved() {
        CapacityVO cap = Mockito.mock(CapacityVO.class);
        when(cap.getUsedCapacity()).thenReturn(40L);
        when(cap.getReservedCapacity()).thenReturn(10L);
        when(capacityDao.findByHostIdType(7L, Capacity.CAPACITY_TYPE_CPU)).thenReturn(cap);
        Assert.assertEquals(50L, service.getMemoryOrCpuCapacityByHost(7L, Capacity.CAPACITY_TYPE_CPU));
    }

    @Test
    public void wiringSmokeTest() {
        Assert.assertNotNull(service);
        // All @InjectMocks collaborators present
        Assert.assertNotNull(capacityDao);
        Assert.assertNotNull(hostDao);
        Assert.assertNotNull(dcDao);
        Assert.assertNotNull(storagePoolTagsDao);
        Assert.assertNotNull(resourceLimitService);
        Assert.assertNotNull(storageMgr);
        Assert.assertNotNull(backupManager);
        Assert.assertNotNull(alertMgr);
        Assert.assertNotNull(accountMgr);
    }

    private SummedCapacity makeSummed(float percent, long used, long total, short capacityType) {
        SummedCapacity sc = new SummedCapacity(used, total, percent, capacityType, 1L, 2L, 3L);
        return sc;
    }

    private CapacityVO makeCapacityVO(short capacityType) {
        CapacityVO cap = new CapacityVO(null, 1L, 2L, 3L, 10L, 100L, capacityType);
        return cap;
    }
}
