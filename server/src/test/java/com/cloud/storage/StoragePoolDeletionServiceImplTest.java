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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.concurrent.ExecutionException;

import org.apache.cloudstack.api.command.admin.storage.DeletePoolCmd;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class StoragePoolDeletionServiceImplTest {

    private static final long POOL_ID = 1L;
    private static final long CHILD_POOL_ID = 2L;
    private static final long SECOND_CHILD_POOL_ID = 3L;
    private static final String PROVIDER_NAME = "provider";

    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private VolumeService volService;
    @Mock
    private VolumeDataFactory volFactory;
    @Mock
    private DataStoreProviderManager dataStoreProviderMgr;
    @Mock
    private DataStoreManager dataStoreMgr;
    @Mock
    private VMInstanceDao vmInstanceDao;

    @Mock
    private DeletePoolCmd cmd;
    @Mock
    private DataStoreProvider dataStoreProvider;
    @Mock
    private DataStoreLifeCycle lifeCycle;
    @Mock
    private DataStore dataStore;
    @Mock
    private VolumeInfo volumeInfo1;
    @Mock
    private VolumeInfo volumeInfo2;
    @Mock
    private AsyncCallFuture<VolumeApiResult> future1;
    @Mock
    private AsyncCallFuture<VolumeApiResult> future2;

    @Spy
    @InjectMocks
    private StoragePoolDeletionServiceImpl service;

    @Test
    public void deletePoolMissingPoolThrowsInvalidParameterValueException() {
        when(cmd.getId()).thenReturn(POOL_ID);
        when(storagePoolDao.findById(POOL_ID)).thenReturn(null);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.deletePool(cmd));

        assertEquals("Unable to find pool by id 1", exception.getMessage());
    }

    @Test
    public void deletePoolRequiresMaintenanceState() {
        StoragePoolVO pool = storagePool(POOL_ID, StoragePoolType.NetworkFilesystem, StoragePoolStatus.Up);
        when(cmd.getId()).thenReturn(POOL_ID);
        when(storagePoolDao.findById(POOL_ID)).thenReturn(pool);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.deletePool(cmd));

        assertEquals(String.format("Unable to delete storage due to it is not in Maintenance state, pool: %s", pool), exception.getMessage());
    }

    @Test
    public void deletePoolDatastoreClusterRejectsWhenChildPoolHasVolumesAndForceIsFalse() {
        StoragePoolVO parentPool = storagePool(POOL_ID, StoragePoolType.DatastoreCluster, StoragePoolStatus.Maintenance);
        StoragePoolVO childPool = storagePool(CHILD_POOL_ID, StoragePoolType.NetworkFilesystem, StoragePoolStatus.Maintenance);
        when(cmd.getId()).thenReturn(POOL_ID);
        when(storagePoolDao.findById(POOL_ID)).thenReturn(parentPool);
        when(storagePoolDao.listChildStoragePoolsInDatastoreCluster(POOL_ID)).thenReturn(List.of(childPool));
        when(volumeDao.getCountAndTotalByPool(CHILD_POOL_ID)).thenReturn(volumeCount(1L));

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.deletePool(cmd));

        assertEquals(String.format("Cannot delete pool %s as there are associated non-destroyed vols for this pool", parentPool), exception.getMessage());
        verify(service, never()).deleteDataStoreInternal(any(), anyBoolean());
    }

    @Test
    public void deletePoolDatastoreClusterRejectsWhenChildPoolHasNonDestroyedVolumesAndForceIsTrue() {
        StoragePoolVO parentPool = storagePool(POOL_ID, StoragePoolType.DatastoreCluster, StoragePoolStatus.Maintenance);
        StoragePoolVO childPool = storagePool(CHILD_POOL_ID, StoragePoolType.NetworkFilesystem, StoragePoolStatus.Maintenance);
        when(cmd.getId()).thenReturn(POOL_ID);
        when(cmd.isForced()).thenReturn(true);
        when(storagePoolDao.findById(POOL_ID)).thenReturn(parentPool);
        when(storagePoolDao.listChildStoragePoolsInDatastoreCluster(POOL_ID)).thenReturn(List.of(childPool));
        when(volumeDao.getCountAndTotalByPool(CHILD_POOL_ID)).thenReturn(volumeCount(1L));
        when(volumeDao.getNonDestroyedCountAndTotalByPool(CHILD_POOL_ID)).thenReturn(volumeCount(1L));

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.deletePool(cmd));

        assertEquals(String.format("Cannot delete pool %s as there are associated non-destroyed vols for this pool", parentPool), exception.getMessage());
        verify(service, never()).deleteDataStoreInternal(any(), anyBoolean());
    }

    @Test
    public void deletePoolDatastoreClusterDeletesChildrenThenParent() {
        StoragePoolVO parentPool = storagePool(POOL_ID, StoragePoolType.DatastoreCluster, StoragePoolStatus.Maintenance);
        StoragePoolVO childPool1 = storagePool(CHILD_POOL_ID, StoragePoolType.NetworkFilesystem, StoragePoolStatus.Maintenance);
        StoragePoolVO childPool2 = storagePool(SECOND_CHILD_POOL_ID, StoragePoolType.NetworkFilesystem, StoragePoolStatus.Maintenance);
        when(cmd.getId()).thenReturn(POOL_ID);
        when(storagePoolDao.findById(POOL_ID)).thenReturn(parentPool);
        when(storagePoolDao.listChildStoragePoolsInDatastoreCluster(POOL_ID)).thenReturn(List.of(childPool1, childPool2));
        doReturn(true).when(service).checkIfDataStoreClusterCanbeDeleted(parentPool, false);
        doReturn(true).when(service).deleteDataStoreInternal(childPool1, false);
        doReturn(true).when(service).deleteDataStoreInternal(childPool2, false);
        doReturn(true).when(service).deleteDataStoreInternal(parentPool, false);

        assertTrue(service.deletePool(cmd));

        verify(service).deleteDataStoreInternal(childPool1, false);
        verify(service).deleteDataStoreInternal(childPool2, false);
        verify(service).deleteDataStoreInternal(parentPool, false);
    }

    @Test
    public void deletePoolNonClusterDelegatesToDeleteDataStoreInternal() {
        StoragePoolVO pool = storagePool(POOL_ID, StoragePoolType.NetworkFilesystem, StoragePoolStatus.Maintenance);
        when(cmd.getId()).thenReturn(POOL_ID);
        when(cmd.isForced()).thenReturn(true);
        when(storagePoolDao.findById(POOL_ID)).thenReturn(pool);
        doReturn(true).when(service).deleteDataStoreInternal(pool, true);

        assertTrue(service.deletePool(cmd));

        verify(storagePoolDao, never()).listChildStoragePoolsInDatastoreCluster(anyLong());
        verify(service).deleteDataStoreInternal(pool, true);
    }

    @Test
    public void deleteDataStoreInternalRejectsVolumesWhenForceIsFalse() {
        StoragePoolVO pool = storagePool(POOL_ID, StoragePoolType.NetworkFilesystem, StoragePoolStatus.Maintenance);
        when(volumeDao.getCountAndTotalByPool(POOL_ID)).thenReturn(volumeCount(1L));

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.deleteDataStoreInternal(pool, false));

        assertEquals(String.format("Cannot delete pool %s as there are non-destroyed volumes associated to this pool.", pool), exception.getMessage());
        verify(storagePoolDao, never()).acquireInLockTable(anyLong());
    }

    @Test
    public void deleteDataStoreInternalRejectsNonDestroyedVolumesWhenForceIsTrue() {
        StoragePoolVO pool = storagePool(POOL_ID, StoragePoolType.NetworkFilesystem, StoragePoolStatus.Maintenance);
        when(volumeDao.getCountAndTotalByPool(POOL_ID)).thenReturn(volumeCount(2L));
        when(volumeDao.getNonDestroyedCountAndTotalByPool(POOL_ID)).thenReturn(volumeCount(1L));

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.deleteDataStoreInternal(pool, true));

        assertEquals(String.format("Cannot delete pool %s as there are non-destroyed volumes associated to this pool.", pool), exception.getMessage());
        verify(volumeDao, never()).listVolumesToBeDestroyed();
        verify(storagePoolDao, never()).acquireInLockTable(anyLong());
    }

    @Test
    public void deleteDataStoreInternalForceExpungesVolumesAndIgnoresExecutionException() throws Exception {
        StoragePoolVO pool = storagePool(POOL_ID, StoragePoolType.NetworkFilesystem, StoragePoolStatus.Maintenance);
        StoragePoolVO lock = storagePool(99L, StoragePoolType.NetworkFilesystem, StoragePoolStatus.Maintenance);
        VolumeVO volume1 = Mockito.mock(VolumeVO.class);
        VolumeVO volume2 = Mockito.mock(VolumeVO.class);
        when(volume1.getId()).thenReturn(101L);
        when(volume2.getId()).thenReturn(102L);
        when(volumeDao.getCountAndTotalByPool(POOL_ID)).thenReturn(volumeCount(2L));
        when(volumeDao.getNonDestroyedCountAndTotalByPool(POOL_ID)).thenReturn(volumeCount(0L));
        when(volumeDao.listVolumesToBeDestroyed()).thenReturn(List.of(volume1, volume2));
        when(volFactory.getVolume(101L)).thenReturn(volumeInfo1);
        when(volFactory.getVolume(102L)).thenReturn(volumeInfo2);
        when(volService.expungeVolumeAsync(volumeInfo1)).thenReturn(future1);
        when(volService.expungeVolumeAsync(volumeInfo2)).thenReturn(future2);
        when(future2.get()).thenThrow(new ExecutionException("expunge failed", new RuntimeException("boom")));
        stubSuccessfulLifecycle(pool, lock, true);

        assertTrue(service.deleteDataStoreInternal(pool, true));

        verify(future1).get();
        verify(future2).get();
        verify(storagePoolDao).releaseFromLockTable(lock.getId());
        verify(lifeCycle).deleteDataStore(dataStore);
    }

    @Test
    public void deleteDataStoreInternalReturnsFalseWhenLockCannotBeAcquired() {
        StoragePoolVO pool = storagePool(POOL_ID, StoragePoolType.NetworkFilesystem, StoragePoolStatus.Maintenance);
        when(volumeDao.getCountAndTotalByPool(POOL_ID)).thenReturn(volumeCount(0L));
        when(storagePoolDao.acquireInLockTable(POOL_ID)).thenReturn(null);

        assertFalse(service.deleteDataStoreInternal(pool, false));

        verify(storagePoolDao, never()).releaseFromLockTable(anyLong());
        verify(dataStoreProviderMgr, never()).getDataStoreProvider(any());
    }

    @Test
    public void deleteDataStoreInternalDeletesDataStoreWhenLifecycleAvailable() {
        StoragePoolVO pool = storagePool(POOL_ID, StoragePoolType.NetworkFilesystem, StoragePoolStatus.Maintenance);
        StoragePoolVO lock = storagePool(99L, StoragePoolType.NetworkFilesystem, StoragePoolStatus.Maintenance);
        when(volumeDao.getCountAndTotalByPool(POOL_ID)).thenReturn(volumeCount(0L));
        stubSuccessfulLifecycle(pool, lock, false);

        assertFalse(service.deleteDataStoreInternal(pool, false));

        verify(storagePoolDao).releaseFromLockTable(lock.getId());
        verify(lifeCycle).deleteDataStore(dataStore);
    }

    @Test
    public void getStoragePoolNonDestroyedVolumesLogFormatsAttachedVolumes() {
        VolumeVO volume1 = mockVolume("786633d1-a942-4374-9d56-322dd4b0d202", 1L);
        VolumeVO volume2 = mockVolume("ffb46333-e983-4c21-b5f0-51c5877a3805", 1L);
        VMInstanceVO vmInstance = mockVm("58760044-928f-4c4e-9fef-d0e48423595e");
        when(volumeDao.findNonDestroyedVolumesByPoolId(POOL_ID, null)).thenReturn(List.of(volume1, volume2));
        when(vmInstanceDao.findById(anyLong())).thenReturn(vmInstance);

        String log = service.getStoragePoolNonDestroyedVolumesLog(POOL_ID);

        assertEquals("[Volume [786633d1-a942-4374-9d56-322dd4b0d202] (attached to VM [58760044-928f-4c4e-9fef-d0e48423595e]), "
                + "Volume [ffb46333-e983-4c21-b5f0-51c5877a3805] (attached to VM [58760044-928f-4c4e-9fef-d0e48423595e])]", log);
    }

    @Test
    public void getStoragePoolNonDestroyedVolumesLogFormatsMixedAttachedAndDetachedVolumes() {
        VolumeVO volume1 = mockVolume("786633d1-a942-4374-9d56-322dd4b0d202", null);
        VolumeVO volume2 = mockVolume("ffb46333-e983-4c21-b5f0-51c5877a3805", 1L);
        VMInstanceVO vmInstance = mockVm("58760044-928f-4c4e-9fef-d0e48423595e");
        when(volumeDao.findNonDestroyedVolumesByPoolId(POOL_ID, null)).thenReturn(List.of(volume1, volume2));
        when(vmInstanceDao.findById(anyLong())).thenReturn(vmInstance);

        String log = service.getStoragePoolNonDestroyedVolumesLog(POOL_ID);

        assertEquals("[Volume [786633d1-a942-4374-9d56-322dd4b0d202] (not attached to any VM), "
                + "Volume [ffb46333-e983-4c21-b5f0-51c5877a3805] (attached to VM [58760044-928f-4c4e-9fef-d0e48423595e])]", log);
    }

    @Test
    public void getStoragePoolNonDestroyedVolumesLogFormatsDetachedVolumes() {
        VolumeVO volume1 = mockVolume("786633d1-a942-4374-9d56-322dd4b0d202", null);
        VolumeVO volume2 = mockVolume("ffb46333-e983-4c21-b5f0-51c5877a3805", null);
        when(volumeDao.findNonDestroyedVolumesByPoolId(POOL_ID, null)).thenReturn(List.of(volume1, volume2));

        String log = service.getStoragePoolNonDestroyedVolumesLog(POOL_ID);

        assertEquals("[Volume [786633d1-a942-4374-9d56-322dd4b0d202] (not attached to any VM), "
                + "Volume [ffb46333-e983-4c21-b5f0-51c5877a3805] (not attached to any VM)]", log);
    }

    @Test
    public void getStoragePoolNonDestroyedVolumesLogFormatsMissingVmReferences() {
        VolumeVO volume1 = mockVolume("786633d1-a942-4374-9d56-322dd4b0d202", 1L);
        VolumeVO volume2 = mockVolume("ffb46333-e983-4c21-b5f0-51c5877a3805", 1L);
        when(volumeDao.findNonDestroyedVolumesByPoolId(POOL_ID, null)).thenReturn(List.of(volume1, volume2));
        when(vmInstanceDao.findById(anyLong())).thenReturn(null);

        String log = service.getStoragePoolNonDestroyedVolumesLog(POOL_ID);

        assertEquals("[Volume [786633d1-a942-4374-9d56-322dd4b0d202] (attached VM with ID [1] doesn't exists), "
                + "Volume [ffb46333-e983-4c21-b5f0-51c5877a3805] (attached VM with ID [1] doesn't exists)]", log);
    }

    @Test
    public void getStoragePoolNonDestroyedVolumesLogReturnsEmptyBracketsWhenNoVolumesExist() {
        when(volumeDao.findNonDestroyedVolumesByPoolId(POOL_ID, null)).thenReturn(List.of());

        assertEquals("[]", service.getStoragePoolNonDestroyedVolumesLog(POOL_ID));
    }

    private StoragePoolVO storagePool(long id, StoragePoolType type, StoragePoolStatus status) {
        StoragePoolVO pool = new StoragePoolVO();
        pool.setId(id);
        pool.setName("pool-" + id);
        pool.setUuid("pool-" + id);
        pool.setPoolType(type);
        pool.setStatus(status);
        pool.setStorageProviderName(PROVIDER_NAME);
        return pool;
    }

    private Pair<Long, Long> volumeCount(long count) {
        return new Pair<>(count, count);
    }

    private void stubSuccessfulLifecycle(StoragePoolVO pool, StoragePoolVO lock, boolean lifecycleResult) {
        when(storagePoolDao.acquireInLockTable(pool.getId())).thenReturn(lock);
        when(dataStoreProviderMgr.getDataStoreProvider(PROVIDER_NAME)).thenReturn(dataStoreProvider);
        when(dataStoreProvider.getDataStoreLifeCycle()).thenReturn(lifeCycle);
        when(dataStoreMgr.getDataStore(eq(pool.getId()), eq(DataStoreRole.Primary))).thenReturn(dataStore);
        when(lifeCycle.deleteDataStore(dataStore)).thenReturn(lifecycleResult);
    }

    private VolumeVO mockVolume(String uuid, Long instanceId) {
        VolumeVO volume = Mockito.mock(VolumeVO.class);
        when(volume.getUuid()).thenReturn(uuid);
        when(volume.getInstanceId()).thenReturn(instanceId);
        return volume;
    }

    private VMInstanceVO mockVm(String uuid) {
        VMInstanceVO vmInstance = Mockito.mock(VMInstanceVO.class);
        when(vmInstance.getUuid()).thenReturn(uuid);
        return vmInstance;
    }
}
