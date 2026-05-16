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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.anyLong;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.offering.DiskOffering;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.utils.Pair;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Focused unit tests for {@link DiskOfferingCompatibilityServiceImpl}.
 * Mirrors the slice extraction tests run against the manager-level
 * spy paths in {@link VolumeApiServiceImplTest}, but exercises the
 * extracted component directly.
 */
@RunWith(MockitoJUnitRunner.class)
public class DiskOfferingCompatibilityServiceImplTest {

    @Mock
    private StoragePoolTagsDao storagePoolTagsDao;
    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private ServiceOfferingDao serviceOfferingDao;
    @Mock
    private DiskOfferingDao diskOfferingDao;

    @Mock
    private StoragePool storagePool;
    @Mock
    private DiskOffering diskOffering;
    @Mock
    private Volume volume;

    @InjectMocks
    private DiskOfferingCompatibilityServiceImpl service;

    private static final long POOL_ID = 100L;
    private static final long VM_ID = 200L;
    private static final long SERVICE_OFFERING_ID = 300L;
    private static final long DISK_OFFERING_ID = 400L;

    @Before
    public void setUp() {
        Mockito.lenient().when(storagePool.getId()).thenReturn(POOL_ID);
        Mockito.lenient().when(storagePool.getUuid()).thenReturn("pool-uuid");
    }

    // ------------------------------------------------------------------
    // resolveStoragePoolTags
    // ------------------------------------------------------------------

    @Test
    public void resolveStoragePoolTagsReturnsNullWhenNoTagsConfigured() {
        Mockito.when(storagePoolTagsDao.findStoragePoolTags(POOL_ID)).thenReturn(Collections.emptyList());

        Pair<List<String>, Boolean> result = service.resolveStoragePoolTags(storagePool);

        assertNull(result);
    }

    @Test
    public void resolveStoragePoolTagsReturnsPlainTagList() {
        List<StoragePoolTagVO> tags = new ArrayList<>();
        tags.add(new StoragePoolTagVO(POOL_ID, "A", false));
        tags.add(new StoragePoolTagVO(POOL_ID, "B", false));
        Mockito.when(storagePoolTagsDao.findStoragePoolTags(POOL_ID)).thenReturn(tags);

        Pair<List<String>, Boolean> result = service.resolveStoragePoolTags(storagePool);

        assertEquals(List.of("A", "B"), result.first());
        assertFalse(result.second());
    }

    @Test
    public void resolveStoragePoolTagsFlagsRuleTagsAsRule() {
        List<StoragePoolTagVO> tags = new ArrayList<>();
        tags.add(new StoragePoolTagVO(POOL_ID, "tags[0] == 'A'", true));
        Mockito.when(storagePoolTagsDao.findStoragePoolTags(POOL_ID)).thenReturn(tags);

        Pair<List<String>, Boolean> result = service.resolveStoragePoolTags(storagePool);

        assertEquals(List.of("tags[0] == 'A'"), result.first());
        assertTrue(result.second());
    }

    // ------------------------------------------------------------------
    // storagePoolTagsMatchOfferingTags
    // ------------------------------------------------------------------

    @Test
    public void matchTagsReturnsTrueWhenBothEmpty() {
        boolean result = service.storagePoolTagsMatchOfferingTags(storagePool, null, "");
        assertTrue(result);
    }

    @Test
    public void matchTagsReturnsTrueWhenPoolHasTagsAndOfferingHasNone() {
        Pair<List<String>, Boolean> poolTags = new Pair<>(List.of("A"), false);
        boolean result = service.storagePoolTagsMatchOfferingTags(storagePool, poolTags, "");
        assertTrue(result);
    }

    @Test
    public void matchTagsReturnsFalseWhenPoolHasNoTagsButOfferingDoes() {
        boolean result = service.storagePoolTagsMatchOfferingTags(storagePool, null, "A,B");
        assertFalse(result);
    }

    @Test
    public void matchTagsReturnsTrueWhenOfferingTagsAreSubset() {
        Pair<List<String>, Boolean> poolTags = new Pair<>(List.of("A", "B", "C", "D"), false);
        boolean result = service.storagePoolTagsMatchOfferingTags(storagePool, poolTags, "A,B");
        assertTrue(result);
    }

    @Test
    public void matchTagsReturnsFalseWhenOfferingTagNotInPool() {
        Pair<List<String>, Boolean> poolTags = new Pair<>(List.of("A", "B"), false);
        boolean result = service.storagePoolTagsMatchOfferingTags(storagePool, poolTags, "C");
        assertFalse(result);
    }

    @Test
    public void matchTagsReturnsFalseWhenPoolHasEmptyTagList() {
        Pair<List<String>, Boolean> poolTags = new Pair<>(Collections.emptyList(), false);
        boolean result = service.storagePoolTagsMatchOfferingTags(storagePool, poolTags, "A");
        assertFalse(result);
    }

    // ------------------------------------------------------------------
    // validateBasicMigrationCompatibility
    // ------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void validateBasicRejectsLocalOfferingOnSharedPool() {
        Mockito.when(storagePool.isShared()).thenReturn(true);
        Mockito.when(diskOffering.isUseLocalStorage()).thenReturn(true);
        service.validateBasicMigrationCompatibility(volume, diskOffering, storagePool);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateBasicRejectsSharedOfferingOnLocalPool() {
        Mockito.when(storagePool.isLocal()).thenReturn(true);
        Mockito.when(diskOffering.isShared()).thenReturn(true);
        service.validateBasicMigrationCompatibility(volume, diskOffering, storagePool);
    }

    @Test
    public void validateBasicAcceptsCompatibleSharedSharedPair() {
        Mockito.when(storagePool.isShared()).thenReturn(true);
        Mockito.when(diskOffering.isUseLocalStorage()).thenReturn(false);
        // Pool is shared (so isLocal check is skipped), offering is not local — should pass without throwing.
        service.validateBasicMigrationCompatibility(volume, diskOffering, storagePool);
    }

    @Test
    public void validateBasicAcceptsCompatibleLocalLocalPair() {
        Mockito.when(storagePool.isShared()).thenReturn(false);
        Mockito.when(storagePool.isLocal()).thenReturn(true);
        Mockito.when(diskOffering.isShared()).thenReturn(false);
        // Pool is local but offering is local-compatible (not shared) — should pass.
        service.validateBasicMigrationCompatibility(volume, diskOffering, storagePool);
    }

    // ------------------------------------------------------------------
    // validateRootVolumeServiceOfferingStrictness
    // ------------------------------------------------------------------

    @Test
    public void strictnessNoopWhenVolumeIsNotRoot() {
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        service.validateRootVolumeServiceOfferingStrictness(volume);
        Mockito.verifyNoInteractions(vmInstanceDao, serviceOfferingDao);
    }

    @Test
    public void strictnessNoopWhenRootVolumeNotAttachedToVm() {
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Mockito.when(volume.getInstanceId()).thenReturn(null);
        service.validateRootVolumeServiceOfferingStrictness(volume);
        Mockito.verifyNoInteractions(vmInstanceDao, serviceOfferingDao);
    }

    @Test
    public void strictnessNoopWhenVmNotFound() {
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Mockito.when(volume.getInstanceId()).thenReturn(VM_ID);
        Mockito.when(vmInstanceDao.findById(VM_ID)).thenReturn(null);
        service.validateRootVolumeServiceOfferingStrictness(volume);
        Mockito.verifyNoInteractions(serviceOfferingDao);
    }

    @Test
    public void strictnessAcceptsWhenServiceOfferingNotStrict() {
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Mockito.when(volume.getInstanceId()).thenReturn(VM_ID);
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getServiceOfferingId()).thenReturn(SERVICE_OFFERING_ID);
        Mockito.when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);
        ServiceOfferingVO so = Mockito.mock(ServiceOfferingVO.class);
        Mockito.when(so.getDiskOfferingStrictness()).thenReturn(false);
        Mockito.when(serviceOfferingDao.findById(SERVICE_OFFERING_ID)).thenReturn(so);

        service.validateRootVolumeServiceOfferingStrictness(volume);
    }

    @Test
    public void strictnessRejectsWhenServiceOfferingIsStrict() {
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Mockito.when(volume.getInstanceId()).thenReturn(VM_ID);
        Mockito.when(volume.getUuid()).thenReturn("volume-uuid");
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getServiceOfferingId()).thenReturn(SERVICE_OFFERING_ID);
        Mockito.when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);
        ServiceOfferingVO so = Mockito.mock(ServiceOfferingVO.class);
        Mockito.when(so.getDiskOfferingStrictness()).thenReturn(true);
        Mockito.when(serviceOfferingDao.findById(SERVICE_OFFERING_ID)).thenReturn(so);

        try {
            service.validateRootVolumeServiceOfferingStrictness(volume);
            fail("Expected InvalidParameterValueException");
        } catch (InvalidParameterValueException expected) {
            assertTrue(expected.getMessage().contains("volume-uuid"));
        }
    }

    // ------------------------------------------------------------------
    // logSizeMismatchOnMigration
    // ------------------------------------------------------------------

    @Test
    public void logSizeMismatchDoesNotTouchDaoWhenSizesMatch() {
        Mockito.when(volume.getSize()).thenReturn(1024L);
        Mockito.when(diskOffering.getDiskSize()).thenReturn(1024L);

        service.logSizeMismatchOnMigration(volume, diskOffering);

        Mockito.verify(diskOfferingDao, Mockito.never()).findById(anyLong());
    }

    @Test
    public void logSizeMismatchLooksUpOldOfferingWhenSizesDiffer() {
        Mockito.when(volume.getSize()).thenReturn(1024L);
        Mockito.when(volume.getDiskOfferingId()).thenReturn(DISK_OFFERING_ID);
        Mockito.when(diskOffering.getDiskSize()).thenReturn(2048L);
        DiskOfferingVO old = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOfferingDao.findById(DISK_OFFERING_ID)).thenReturn(old);

        service.logSizeMismatchOnMigration(volume, diskOffering);

        Mockito.verify(diskOfferingDao).findById(DISK_OFFERING_ID);
    }
}
