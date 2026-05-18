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

import java.util.Collections;
import java.util.UUID;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.Storage.ProvisioningType;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.UserVO;

@RunWith(MockitoJUnitRunner.class)
public class VolumeUpdateDisplayServiceImplTest {

    private static final long VOLUME_ID = 101L;
    private static final long ACCOUNT_ID = 11L;
    private static final long DOMAIN_ID = 12L;
    private static final long ZONE_ID = 1L;
    private static final long OTHER_ZONE_ID = 2L;
    private static final long DISK_OFFERING_ID = 21L;
    private static final long TEMPLATE_ID = 31L;
    private static final long INSTANCE_ID = 41L;
    private static final long VOLUME_SIZE = 4096L;

    @Spy
    @InjectMocks
    private VolumeUpdateDisplayServiceImpl service = new VolumeUpdateDisplayServiceImpl();

    @Mock
    private AccountManager accountMgr;
    @Mock
    private VolumeDao volsDao;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private ResourceLimitService resourceLimitMgr;
    @Mock
    private DiskOfferingDao diskOfferingDao;

    @Mock
    private DiskOfferingVO diskOffering;

    private AccountVO caller;

    @Before
    public void setUp() {
        UserVO user = new UserVO(1, "testuser", "password", "first", "last", "email", "tz",
                UUID.randomUUID().toString(), User.Source.UNKNOWN);
        caller = new AccountVO("admin", 1L, "domain", Account.Type.ADMIN, UUID.randomUUID().toString());
        CallContext.register(user, caller);
    }

    @After
    public void tearDown() {
        CallContext.unregisterAll();
    }

    @Test
    public void updateVolumeNonRootRejectsAdminOnlyFields() {
        Mockito.when(accountMgr.isRootAdmin(caller.getId())).thenReturn(false);

        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class, () ->
                service.updateVolume(VOLUME_ID, "path", null, null, null, null, null, ACCOUNT_ID, null, null));

        Assert.assertEquals("The domain admin and normal user are not allowed to update volume except volume name & delete protection",
                exception.getMessage());
        Mockito.verify(volsDao, Mockito.never()).findById(Mockito.anyLong());
    }

    @Test
    public void updateVolumeNonRootAllowsNameAndDeleteProtection() {
        VolumeVO volume = newVolume(true, Volume.State.Ready);
        Mockito.when(accountMgr.isRootAdmin(caller.getId())).thenReturn(false);
        Mockito.when(volsDao.findById(VOLUME_ID)).thenReturn(volume);

        Volume result = service.updateVolume(VOLUME_ID, null, null, null, null, true, null, ACCOUNT_ID, null, "new-name");

        Assert.assertSame(volume, result);
        Assert.assertEquals("new-name", volume.getName());
        Assert.assertTrue(volume.isDeleteProtection());
        Mockito.verify(accountMgr).checkAccess(caller, null, true, volume);
        Mockito.verify(storagePoolDao, Mockito.never()).findById(Mockito.anyLong());
        Mockito.verify(volsDao).update(VOLUME_ID, volume);
    }

    @Test
    public void updateVolumeMissingVolumeThrowsInvalidParameterValueException() {
        Mockito.when(accountMgr.isRootAdmin(caller.getId())).thenReturn(true);
        Mockito.when(volsDao.findById(VOLUME_ID)).thenReturn(null);

        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class, () ->
                service.updateVolume(VOLUME_ID, null, null, null, null, null, null, ACCOUNT_ID, null, null));

        Assert.assertEquals("The volume id doesn't exist", exception.getMessage());
    }

    @Test
    public void updateVolumeChecksAccessBeforeMutating() {
        VolumeVO volume = newVolume(true, Volume.State.Ready);
        Mockito.when(accountMgr.isRootAdmin(caller.getId())).thenReturn(true);
        Mockito.when(volsDao.findById(VOLUME_ID)).thenReturn(volume);

        service.updateVolume(VOLUME_ID, null, null, null, null, null, null, ACCOUNT_ID, null, "new-name");

        InOrder inOrder = Mockito.inOrder(accountMgr, volsDao);
        inOrder.verify(accountMgr).checkAccess(caller, null, true, volume);
        inOrder.verify(volsDao).update(VOLUME_ID, volume);
        Assert.assertEquals("new-name", volume.getName());
    }

    @Test
    public void updateVolumeInvalidStateThrowsInvalidParameterValueException() {
        VolumeVO volume = newVolume(true, Volume.State.Ready);
        Mockito.when(accountMgr.isRootAdmin(caller.getId())).thenReturn(true);
        Mockito.when(volsDao.findById(VOLUME_ID)).thenReturn(volume);

        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class, () ->
                service.updateVolume(VOLUME_ID, null, "NotAState", null, null, null, null, ACCOUNT_ID, null, null));

        Assert.assertEquals("Invalid volume state specified", exception.getMessage());
        Mockito.verify(volsDao, Mockito.never()).update(Mockito.anyLong(), Mockito.any(VolumeVO.class));
    }

    @Test
    public void updateVolumeUpdatesPathStateChainInfoCustomIdNameAndDeleteProtection() {
        VolumeVO volume = newVolume(true, Volume.State.Allocated);
        Mockito.when(accountMgr.isRootAdmin(caller.getId())).thenReturn(true);
        Mockito.when(volsDao.findById(VOLUME_ID)).thenReturn(volume);

        service.updateVolume(VOLUME_ID, "new-path", "Ready", null, null, true, "custom-id",
                ACCOUNT_ID, "chain-info", "new-name");

        Assert.assertEquals("new-path", volume.getPath());
        Assert.assertEquals(Volume.State.Ready, volume.getState());
        Assert.assertEquals("chain-info", volume.getChainInfo());
        Assert.assertEquals("custom-id", volume.getUuid());
        Assert.assertEquals("new-name", volume.getName());
        Assert.assertTrue(volume.isDeleteProtection());
        Mockito.verify(volsDao).update(VOLUME_ID, volume);
    }

    @Test
    public void updateVolumeRejectsStoragePoolOutsideVolumeZone() {
        VolumeVO volume = newVolume(true, Volume.State.Ready);
        StoragePoolVO pool = storagePool(10L, Storage.StoragePoolType.NetworkFilesystem, OTHER_ZONE_ID);
        Mockito.when(accountMgr.isRootAdmin(caller.getId())).thenReturn(true);
        Mockito.when(volsDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(storagePoolDao.findById(10L)).thenReturn(pool);

        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class, () ->
                service.updateVolume(VOLUME_ID, null, null, 10L, null, null, null, ACCOUNT_ID, null, null));

        Assert.assertEquals("Invalid storageId specified; refers to the pool outside of the volume's zone",
                exception.getMessage());
    }

    @Test
    public void updateVolumeSetsDirectStoragePoolAndPoolType() {
        VolumeVO volume = newVolume(true, Volume.State.Ready);
        StoragePoolVO pool = storagePool(10L, Storage.StoragePoolType.NetworkFilesystem, ZONE_ID);
        Mockito.when(accountMgr.isRootAdmin(caller.getId())).thenReturn(true);
        Mockito.when(volsDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(storagePoolDao.findById(10L)).thenReturn(pool);

        service.updateVolume(VOLUME_ID, null, null, 10L, null, null, null, ACCOUNT_ID, null, null);

        Assert.assertEquals(Long.valueOf(10L), volume.getPoolId());
        Assert.assertEquals(Storage.StoragePoolType.NetworkFilesystem, volume.getPoolType());
    }

    @Test
    public void updateVolumeDatastoreClusterSelectsChildPool() {
        VolumeVO volume = newVolume(true, Volume.State.Ready);
        StoragePoolVO clusterPool = storagePool(10L, Storage.StoragePoolType.DatastoreCluster, ZONE_ID);
        StoragePoolVO childPool = storagePool(11L, Storage.StoragePoolType.NetworkFilesystem, ZONE_ID);
        Mockito.when(accountMgr.isRootAdmin(caller.getId())).thenReturn(true);
        Mockito.when(volsDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(storagePoolDao.findById(10L)).thenReturn(clusterPool);
        Mockito.when(storagePoolDao.listChildStoragePoolsInDatastoreCluster(10L)).thenReturn(Collections.singletonList(childPool));

        service.updateVolume(VOLUME_ID, null, null, 10L, null, null, null, ACCOUNT_ID, null, null);

        Assert.assertEquals(Long.valueOf(11L), volume.getPoolId());
        Assert.assertEquals(Storage.StoragePoolType.NetworkFilesystem, volume.getPoolType());
    }

    @Test
    public void updateDisplayNullDisplayFlagDoesNothing() {
        VolumeVO volume = newVolume(true, Volume.State.Ready);

        try (MockedStatic<UsageEventUtils> usageEventUtils = Mockito.mockStatic(UsageEventUtils.class)) {
            service.updateDisplay(volume, null);

            Mockito.verifyNoInteractions(resourceLimitMgr);
            usageEventUtils.verifyNoInteractions();
            Mockito.verify(volsDao, Mockito.never()).update(Mockito.anyLong(), Mockito.any(VolumeVO.class));
        }
    }

    @Test
    public void updateDisplaySameDisplayFlagDoesNothing() {
        VolumeVO volume = newVolume(true, Volume.State.Ready);

        try (MockedStatic<UsageEventUtils> usageEventUtils = Mockito.mockStatic(UsageEventUtils.class)) {
            service.updateDisplay(volume, true);

            Mockito.verifyNoInteractions(resourceLimitMgr);
            usageEventUtils.verifyNoInteractions();
            Mockito.verify(volsDao, Mockito.never()).update(Mockito.anyLong(), Mockito.any(VolumeVO.class));
        }
    }

    @Test
    public void updateDisplayFalseDecrementsResourceCountPublishesDeleteAndPersistsFlag() {
        VolumeVO volume = newVolume(true, Volume.State.Ready);
        Mockito.when(diskOfferingDao.findById(DISK_OFFERING_ID)).thenReturn(diskOffering);

        try (MockedStatic<UsageEventUtils> usageEventUtils = Mockito.mockStatic(UsageEventUtils.class)) {
            service.updateDisplay(volume, false);

            Mockito.verify(resourceLimitMgr).decrementVolumeResourceCount(ACCOUNT_ID, true, VOLUME_SIZE, diskOffering);
            usageEventUtils.verify(() -> UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_DELETE, ACCOUNT_ID,
                    ZONE_ID, VOLUME_ID, "volume-name", Volume.class.getName(), "volume-uuid"));
            Assert.assertFalse(volume.isDisplayVolume());
            Mockito.verify(volsDao).update(VOLUME_ID, volume);
        }
    }

    @Test
    public void updateDisplayTrueIncrementsResourceCountPublishesCreateAndPersistsFlag() {
        VolumeVO volume = newVolume(false, Volume.State.Ready);
        Mockito.when(diskOfferingDao.findById(DISK_OFFERING_ID)).thenReturn(diskOffering);

        try (MockedStatic<UsageEventUtils> usageEventUtils = Mockito.mockStatic(UsageEventUtils.class)) {
            service.updateDisplay(volume, true);

            Mockito.verify(resourceLimitMgr).incrementVolumeResourceCount(ACCOUNT_ID, true, VOLUME_SIZE, diskOffering);
            usageEventUtils.verify(() -> UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_CREATE, ACCOUNT_ID,
                    ZONE_ID, VOLUME_ID, "volume-name", DISK_OFFERING_ID, TEMPLATE_ID, VOLUME_SIZE,
                    Volume.class.getName(), "volume-uuid", INSTANCE_ID, true));
            Assert.assertTrue(volume.isDisplayVolume());
            Mockito.verify(volsDao).update(VOLUME_ID, volume);
        }
    }

    @Test
    public void updateDisplayDestroyStateSuppressesUsageEventButStillUpdatesResourceCountAndFlag() {
        VolumeVO volume = newVolume(true, Volume.State.Destroy);
        Mockito.when(diskOfferingDao.findById(DISK_OFFERING_ID)).thenReturn(diskOffering);

        try (MockedStatic<UsageEventUtils> usageEventUtils = Mockito.mockStatic(UsageEventUtils.class)) {
            service.updateDisplay(volume, false);

            Mockito.verify(resourceLimitMgr).decrementVolumeResourceCount(ACCOUNT_ID, true, VOLUME_SIZE, diskOffering);
            usageEventUtils.verifyNoInteractions();
            Assert.assertFalse(volume.isDisplayVolume());
            Mockito.verify(volsDao).update(VOLUME_ID, volume);
        }
    }

    @Test
    public void updateDisplayExpungingAndExpungedStatesStillPublishUsageEvents() {
        assertDeleteUsageEventPublishedForState(Volume.State.Expunging);
        assertDeleteUsageEventPublishedForState(Volume.State.Expunged);
    }

    private void assertDeleteUsageEventPublishedForState(Volume.State state) {
        VolumeVO volume = newVolume(true, state);

        try (MockedStatic<UsageEventUtils> usageEventUtils = Mockito.mockStatic(UsageEventUtils.class)) {
            service.updateDisplay(volume, false);

            usageEventUtils.verify(() -> UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_DELETE, ACCOUNT_ID,
                    ZONE_ID, VOLUME_ID, "volume-name", Volume.class.getName(), "volume-uuid"));
        }
    }

    private VolumeVO newVolume(boolean displayVolume, Volume.State state) {
        VolumeVO volume = new VolumeVO("volume-name", ZONE_ID, 1L, ACCOUNT_ID, DOMAIN_ID, INSTANCE_ID, "folder",
                "path", ProvisioningType.THIN, VOLUME_SIZE, null, null, "iscsi", Volume.Type.DATADISK);
        ReflectionTestUtils.setField(volume, "id", VOLUME_ID);
        volume.setUuid("volume-uuid");
        volume.setDiskOfferingId(DISK_OFFERING_ID);
        volume.setTemplateId(TEMPLATE_ID);
        volume.setDisplayVolume(displayVolume);
        volume.setState(state);
        return volume;
    }

    private StoragePoolVO storagePool(long id, Storage.StoragePoolType poolType, long zoneId) {
        StoragePoolVO storagePool = new StoragePoolVO();
        storagePool.setId(id);
        storagePool.setPoolType(poolType);
        storagePool.setDataCenterId(zoneId);
        return storagePool;
    }
}
