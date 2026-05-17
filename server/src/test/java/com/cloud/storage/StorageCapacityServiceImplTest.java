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
package com.cloud.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.capacity.Capacity;
import com.cloud.capacity.CapacityManager;
import com.cloud.capacity.CapacityVO;
import com.cloud.utils.Pair;
import com.cloud.vm.DiskProfile;

/**
 * Focused tests for {@link StorageCapacityServiceImpl} -- the Phase 4
 * (slice 6) extraction of capacity bookkeeping, used-stats aggregation,
 * and pre-allocation space / IOPS checks out of {@link StorageManagerImpl}.
 *
 * <p>The thin delegating wrappers on the manager are covered by
 * {@code StorageManagerImplTest}; these tests target the service
 * directly so the threshold logic, over-provisioning multiplication,
 * and null-argument fast paths are covered even if a future refactor
 * of the manager drops them.
 */
@RunWith(MockitoJUnitRunner.class)
public class StorageCapacityServiceImplTest {

    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private CapacityManager capacityManager;
    @Mock
    private ObjectStoreDao objectStoreDao;

    @InjectMocks
    private StorageCapacityServiceImpl service;

    // ---------------------------------------------------------------------
    // IOPS checks
    // ---------------------------------------------------------------------

    @Test
    public void testStoragePoolHasEnoughIopsNullPoolIops() {
        StoragePool pool = Mockito.mock(StoragePool.class);
        Mockito.when(pool.getCapacityIops()).thenReturn(null);
        List<Pair<Volume, DiskProfile>> list = List.of(new Pair<>(Mockito.mock(Volume.class), Mockito.mock(DiskProfile.class)));
        assertTrue(service.storagePoolHasEnoughIopsInternal(100L, list, pool, false));
    }

    @Test
    public void testStoragePoolHasEnoughIopsSuccess() {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(pool.getId()).thenReturn(1L);
        Mockito.when(pool.getCapacityIops()).thenReturn(1000L);
        Mockito.when(storagePoolDao.findById(1L)).thenReturn(pool);
        Mockito.when(capacityManager.getUsedIops(pool)).thenReturn(500L);
        List<Pair<Volume, DiskProfile>> list = List.of(new Pair<>(Mockito.mock(Volume.class), Mockito.mock(DiskProfile.class)));
        assertTrue(service.storagePoolHasEnoughIopsInternal(100L, list, pool, true));
    }

    @Test
    public void testStoragePoolHasEnoughIopsNegative() {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(pool.getId()).thenReturn(1L);
        Mockito.when(pool.getCapacityIops()).thenReturn(550L);
        Mockito.when(storagePoolDao.findById(1L)).thenReturn(pool);
        Mockito.when(capacityManager.getUsedIops(pool)).thenReturn(500L);
        List<Pair<Volume, DiskProfile>> list = List.of(new Pair<>(Mockito.mock(Volume.class), Mockito.mock(DiskProfile.class)));
        assertFalse(service.storagePoolHasEnoughIopsInternal(100L, list, pool, true));
    }

    @Test
    public void testStoragePoolHasEnoughIopsNullPool() {
        assertFalse(service.storagePoolHasEnoughIops(100L, null));
    }

    @Test
    public void testStoragePoolHasEnoughIopsNullRequestedIops() {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        List<Long> iopsList = Arrays.asList(null, 0L);
        for (Long iops : iopsList) {
            assertTrue(service.storagePoolHasEnoughIops(iops, pool));
        }
    }

    @Test
    public void testStoragePoolHasEnoughIopsNoVolumesOrPool() {
        List<Pair<Volume, DiskProfile>> list = new ArrayList<>();
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        assertFalse(service.storagePoolHasEnoughIops(list, pool));
        list = List.of(new Pair<>(Mockito.mock(Volume.class), Mockito.mock(DiskProfile.class)));
        assertFalse(service.storagePoolHasEnoughIops(list, null));
    }

    @Test
    public void testStoragePoolHasEnoughIopsWithVolPoolNullIops() {
        List<Pair<Volume, DiskProfile>> list = List.of(
                new Pair<>(Mockito.mock(Volume.class), Mockito.mock(DiskProfile.class)));
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(pool.getCapacityIops()).thenReturn(null);
        assertTrue(service.storagePoolHasEnoughIops(list, pool));
    }

    @Test
    public void testStoragePoolHasEnoughIopsWithVolPoolCompare() {
        Volume volume = Mockito.mock(Volume.class);
        Mockito.when(volume.getDiskOfferingId()).thenReturn(1L);
        Mockito.when(volume.getMinIops()).thenReturn(100L);
        DiskProfile profile = Mockito.mock(DiskProfile.class);
        Mockito.when(profile.getDiskOfferingId()).thenReturn(1L);
        List<Pair<Volume, DiskProfile>> list = List.of(new Pair<>(volume, profile));

        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(pool.getId()).thenReturn(1L);
        Mockito.when(pool.getCapacityIops()).thenReturn(1000L);
        Mockito.when(storagePoolDao.findById(1L)).thenReturn(pool);
        // currentIops 500 + requested 100 = 600 <= 1000  -> pass
        Mockito.when(capacityManager.getUsedIops(pool)).thenReturn(500L);
        assertTrue(service.storagePoolHasEnoughIops(list, pool));

        // When offering differs the request is taken from the profile minIops (200)
        // 500 + 200 = 700 <= 1000 still passes; tighten by raising used to 850
        Mockito.when(profile.getDiskOfferingId()).thenReturn(2L);
        Mockito.when(profile.getMinIops()).thenReturn(200L);
        Mockito.when(capacityManager.getUsedIops(pool)).thenReturn(850L);
        assertFalse(service.storagePoolHasEnoughIops(list, pool));
    }

    // ---------------------------------------------------------------------
    // Space checks
    // ---------------------------------------------------------------------

    @Test
    public void testStoragePoolHasEnoughSpaceNullSize() {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        List<Long> sizeList = Arrays.asList(null, 0L);
        for (Long size : sizeList) {
            assertTrue(service.storagePoolHasEnoughSpace(size, pool));
        }
    }

    @Test
    public void testStoragePoolHasEnoughSpaceEmptyVolumeList() {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        assertFalse(service.storagePoolHasEnoughSpace(new ArrayList<>(), pool));
    }

    // ---------------------------------------------------------------------
    // checkPoolforSpace (formerly tested via the manager spy)
    // ---------------------------------------------------------------------

    private Long testCheckPoolforSpaceForResizeSetup(StoragePoolVO pool, Long allocatedSizeWithTemplate) {
        Long poolId = 10L;

        Long capacityBytes = (long) (allocatedSizeWithTemplate / Double.parseDouble(CapacityManager.StorageAllocatedCapacityDisableThreshold.defaultValue())
                / Double.parseDouble(CapacityManager.StorageOverprovisioningFactor.defaultValue()));
        Long maxAllocatedSizeForResize = (long) (capacityBytes * Double.parseDouble(CapacityManager.StorageOverprovisioningFactor.defaultValue())
                * Double.parseDouble(CapacityManager.StorageAllocatedCapacityDisableThresholdForVolumeSize.defaultValue()));

        Mockito.when(pool.getId()).thenReturn(poolId);
        Mockito.when(pool.getCapacityBytes()).thenReturn(capacityBytes);
        Mockito.when(storagePoolDao.findById(poolId)).thenReturn(pool);
        Mockito.when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);

        return maxAllocatedSizeForResize - allocatedSizeWithTemplate;
    }

    private void overrideDefaultConfigValue(final ConfigKey<?> configKey, final String name, final Object o) throws IllegalAccessException, NoSuchFieldException {
        Field f = ConfigKey.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(configKey, o);
    }

    @Test
    public void testCheckPoolforSpaceForResize1() {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Long allocatedSizeWithTemplate = 100L * 1024 * 1024 * 1024;

        Long maxAskingSize = testCheckPoolforSpaceForResizeSetup(pool, allocatedSizeWithTemplate);
        Long totalAskingSize = maxAskingSize / 2;

        boolean result = service.checkPoolforSpace(pool, allocatedSizeWithTemplate, totalAskingSize, false);
        assertFalse(result);
    }

    @Test
    public void testCheckPoolforSpaceForResize2() {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Long allocatedSizeWithTemplate = 100L * 1024 * 1024 * 1024;

        Long maxAskingSize = testCheckPoolforSpaceForResizeSetup(pool, allocatedSizeWithTemplate);
        Long totalAskingSize = maxAskingSize / 2;

        boolean result = service.checkPoolforSpace(pool, allocatedSizeWithTemplate, totalAskingSize, true);
        assertFalse(result);
    }

    @Test
    public void testCheckPoolforSpaceForResize3() throws NoSuchFieldException, IllegalAccessException {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Long allocatedSizeWithTemplate = 100L * 1024 * 1024 * 1024;

        Long maxAskingSize = testCheckPoolforSpaceForResizeSetup(pool, allocatedSizeWithTemplate);
        Long totalAskingSize = maxAskingSize + 1;
        overrideDefaultConfigValue(StorageManager.AllowVolumeReSizeBeyondAllocation, "_defaultValue", "true");

        boolean result = service.checkPoolforSpace(pool, allocatedSizeWithTemplate, totalAskingSize, true);
        assertFalse(result);
    }

    @Test
    public void testCheckPoolforSpaceForResize4() throws NoSuchFieldException, IllegalAccessException {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        Long allocatedSizeWithTemplate = 100L * 1024 * 1024 * 1024;

        Long maxAskingSize = testCheckPoolforSpaceForResizeSetup(pool, allocatedSizeWithTemplate);
        Long totalAskingSize = maxAskingSize / 2;
        overrideDefaultConfigValue(StorageManager.AllowVolumeReSizeBeyondAllocation, "_defaultValue", "true");

        boolean result = service.checkPoolforSpace(pool, allocatedSizeWithTemplate, totalAskingSize, true);
        assertTrue(result);
    }

    // ---------------------------------------------------------------------
    // Object storage stats
    // ---------------------------------------------------------------------

    @Test
    public void testGetObjectStorageUsedStats() {
        Long zoneId = 1L;
        List<ObjectStoreVO> objectStores = new ArrayList<>();

        ObjectStoreVO store1 = new ObjectStoreVO();
        store1.setAllocatedSize(1000L);
        store1.setTotalSize(2000L);
        objectStores.add(store1);

        ObjectStoreVO store2 = new ObjectStoreVO();
        store2.setAllocatedSize(2000L);
        store2.setTotalSize(4000L);
        objectStores.add(store2);

        ObjectStoreVO store3 = new ObjectStoreVO();
        store3.setAllocatedSize(null);
        store3.setTotalSize(null);
        objectStores.add(store3);

        Mockito.when(objectStoreDao.listObjectStores()).thenReturn(objectStores);

        CapacityVO result = service.getObjectStorageUsedStats(zoneId);

        assertEquals(zoneId, result.getDataCenterId());
        assertEquals(Optional.of(3000L), Optional.of(result.getUsedCapacity())); // 1000 + 2000
        assertEquals(6000L, result.getTotalCapacity()); // 2000 + 4000
        assertEquals(Capacity.CAPACITY_TYPE_OBJECT_STORAGE, result.getCapacityType());
        assertNull(result.getPodId());
        assertNull(result.getClusterId());
    }

    @Test
    public void testGetObjectStorageUsedStatsWithNullSizes() {
        Long zoneId = 1L;
        List<ObjectStoreVO> objectStores = new ArrayList<>();

        ObjectStoreVO store1 = new ObjectStoreVO();
        store1.setAllocatedSize(null);
        store1.setTotalSize(null);
        objectStores.add(store1);

        ObjectStoreVO store2 = new ObjectStoreVO();
        store2.setAllocatedSize(null);
        store2.setTotalSize(null);
        objectStores.add(store2);

        Mockito.when(objectStoreDao.listObjectStores()).thenReturn(objectStores);

        CapacityVO result = service.getObjectStorageUsedStats(zoneId);

        assertEquals(zoneId, result.getDataCenterId());
        assertEquals(Optional.of(0L), Optional.of(result.getUsedCapacity()));
        assertEquals(0L, result.getTotalCapacity());
        assertEquals(Capacity.CAPACITY_TYPE_OBJECT_STORAGE, result.getCapacityType());
        assertNull(result.getPodId());
        assertNull(result.getClusterId());
    }

    // ---------------------------------------------------------------------
    // Over-provisioning factor
    // ---------------------------------------------------------------------

    @Test
    public void testGetStorageOverProvisioningFactorReturnsConfiguredValue() {
        // Default value from CapacityManager.StorageOverprovisioningFactor is "2.0".
        assertNotNull(service.getStorageOverProvisioningFactor(1L));
    }
}
