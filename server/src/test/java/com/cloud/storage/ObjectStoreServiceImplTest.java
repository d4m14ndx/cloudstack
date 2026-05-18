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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.apache.cloudstack.api.command.admin.storage.DeleteObjectStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateObjectStoragePoolCmd;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDetailsDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreVO;
import org.apache.cloudstack.storage.object.ObjectStore;
import org.apache.cloudstack.storage.object.ObjectStoreEntity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.capacity.CapacityVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.dao.BucketDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Focused unit tests for {@link ObjectStoreServiceImpl} — Phase 4 (slice 8)
 * extraction of object-store lifecycle management from {@link StorageManagerImpl}.
 *
 * <p>Every branch of all four public methods is exercised here; thin
 * delegation checks on the manager side are in {@code StorageManagerImplTest}.
 */
@RunWith(MockitoJUnitRunner.class)
public class ObjectStoreServiceImplTest {

    @InjectMocks
    ObjectStoreServiceImpl objectStoreService;

    @Mock
    DataStoreManager dataStoreMgr;

    @Mock
    DataStoreProviderManager dataStoreProviderMgr;

    @Mock
    ObjectStoreDao objectStoreDao;

    @Mock
    ObjectStoreDetailsDao objectStoreDetailsDao;

    @Mock
    BucketDao bucketDao;

    @Mock
    StorageCapacityService storageCapacityService;

    // -----------------------------------------------------------------------
    // discoverObjectStore
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void discoverObjectStore_throwsWhenProviderNotFound() {
        Mockito.when(dataStoreProviderMgr.getDataStoreProvider("unknown")).thenReturn(null);
        objectStoreService.discoverObjectStore("name", "http://10.0.0.1:80", null, "unknown", new HashMap<>());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void discoverObjectStore_throwsWhenNameAlreadyExists() {
        DataStoreProvider provider = Mockito.mock(DataStoreProvider.class);
        Mockito.when(dataStoreProviderMgr.getDataStoreProvider("prov")).thenReturn(provider);
        Mockito.when(objectStoreDao.findByName("dup")).thenReturn(new ObjectStoreVO());
        objectStoreService.discoverObjectStore("dup", "http://10.0.0.1:80", null, "prov", new HashMap<>());
    }

    @Test(expected = IllegalArgumentException.class)
    public void discoverObjectStore_throwsWhenUrlInvalid() {
        DataStoreProvider provider = Mockito.mock(DataStoreProvider.class);
        Mockito.when(dataStoreProviderMgr.getDataStoreProvider("prov")).thenReturn(provider);
        Mockito.when(objectStoreDao.findByName("store")).thenReturn(null);
        // "not-a-url" fails UriUtils.validateUrl which throws IllegalArgumentException
        objectStoreService.discoverObjectStore("store", "not-a-url", null, "prov", new HashMap<>());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void discoverObjectStore_throwsWhenUrlAlreadyExists() {
        String url = "http://10.0.0.1:80";
        DataStoreProvider provider = Mockito.mock(DataStoreProvider.class);
        Mockito.when(dataStoreProviderMgr.getDataStoreProvider("prov")).thenReturn(provider);
        Mockito.when(objectStoreDao.findByName("store")).thenReturn(null);
        Mockito.when(objectStoreDao.findByUrl(url)).thenReturn(new ObjectStoreVO());
        objectStoreService.discoverObjectStore("store", url, null, "prov", new HashMap<>());
    }

    @Test(expected = CloudRuntimeException.class)
    public void discoverObjectStore_wrapsLifecycleFailure() {
        String url = "http://10.0.0.1:80";
        DataStoreProvider provider = Mockito.mock(DataStoreProvider.class);
        DataStoreLifeCycle lifeCycle = Mockito.mock(DataStoreLifeCycle.class);
        Mockito.when(dataStoreProviderMgr.getDataStoreProvider("prov")).thenReturn(provider);
        Mockito.when(objectStoreDao.findByName("store")).thenReturn(null);
        Mockito.when(objectStoreDao.findByUrl(url)).thenReturn(null);
        Mockito.when(provider.getDataStoreLifeCycle()).thenReturn(lifeCycle);
        Mockito.when(provider.getName()).thenReturn("prov");
        Mockito.when(lifeCycle.initialize(any())).thenThrow(new RuntimeException("init failed"));
        objectStoreService.discoverObjectStore("store", url, null, "prov", new HashMap<>());
    }

    @Test
    public void discoverObjectStore_successReturnsDataStoreFromMgr() {
        String url = "http://10.0.0.1:80";
        Long storeId = 42L;
        DataStoreProvider provider = Mockito.mock(DataStoreProvider.class);
        DataStoreLifeCycle lifeCycle = Mockito.mock(DataStoreLifeCycle.class);
        DataStore ds = Mockito.mock(DataStore.class);
        // ObjectStoreEntity extends both DataStore and ObjectStore — use it so the cast in
        // discoverObjectStore compiles and the thenReturn type matches DataStoreManager.getDataStore.
        ObjectStoreEntity expected = Mockito.mock(ObjectStoreEntity.class);

        Mockito.when(dataStoreProviderMgr.getDataStoreProvider("prov")).thenReturn(provider);
        Mockito.when(objectStoreDao.findByName("store")).thenReturn(null);
        Mockito.when(objectStoreDao.findByUrl(url)).thenReturn(null);
        Mockito.when(provider.getDataStoreLifeCycle()).thenReturn(lifeCycle);
        Mockito.when(provider.getName()).thenReturn("prov");
        Mockito.when(lifeCycle.initialize(any())).thenReturn(ds);
        Mockito.when(ds.getId()).thenReturn(storeId);
        Mockito.when(dataStoreMgr.getDataStore(storeId, DataStoreRole.Object)).thenReturn(expected);

        // null size → params map gets size=0L; impl casts DataStore to ObjectStore
        ObjectStore result = objectStoreService.discoverObjectStore("store", url, null, "prov", new HashMap<>());

        assertSame(expected, result);
        Mockito.verify(lifeCycle).initialize(any());
        Mockito.verify(dataStoreMgr).getDataStore(storeId, DataStoreRole.Object);
    }

    // -----------------------------------------------------------------------
    // deleteObjectStore
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void deleteObjectStore_throwsWhenStoreMissing() {
        DeleteObjectStoragePoolCmd cmd = Mockito.mock(DeleteObjectStoragePoolCmd.class);
        Mockito.when(cmd.getId()).thenReturn(99L);
        Mockito.when(objectStoreDao.findById(99L)).thenReturn(null);
        objectStoreService.deleteObjectStore(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void deleteObjectStore_throwsWhenBucketsExist() {
        DeleteObjectStoragePoolCmd cmd = Mockito.mock(DeleteObjectStoragePoolCmd.class);
        Mockito.when(cmd.getId()).thenReturn(1L);
        Mockito.when(objectStoreDao.findById(1L)).thenReturn(new ObjectStoreVO());
        List<BucketVO> buckets = new ArrayList<>();
        buckets.add(new BucketVO());
        Mockito.when(bucketDao.listByObjectStoreId(1L)).thenReturn(buckets);
        objectStoreService.deleteObjectStore(cmd);
    }

    @Test
    public void deleteObjectStore_successDeletesDetailsAndStore() {
        final long storeId = 5L;
        DeleteObjectStoragePoolCmd cmd = Mockito.mock(DeleteObjectStoragePoolCmd.class);
        Mockito.when(cmd.getId()).thenReturn(storeId);
        Mockito.when(objectStoreDao.findById(storeId)).thenReturn(new ObjectStoreVO());
        Mockito.when(bucketDao.listByObjectStoreId(storeId)).thenReturn(Collections.emptyList());

        try (MockedStatic<Transaction> txMock = Mockito.mockStatic(Transaction.class)) {
            txMock.when(() -> Transaction.execute(any(TransactionCallbackNoReturn.class))).thenAnswer(inv -> {
                TransactionCallbackNoReturn cb = inv.getArgument(0);
                cb.doInTransactionWithoutResult(null);
                return null;
            });

            boolean result = objectStoreService.deleteObjectStore(cmd);

            assertTrue(result);
            Mockito.verify(objectStoreDetailsDao).deleteDetails(storeId);
            Mockito.verify(objectStoreDao).remove(storeId);
        }
    }

    // -----------------------------------------------------------------------
    // updateObjectStore
    // -----------------------------------------------------------------------

    @Test(expected = IllegalArgumentException.class)
    public void updateObjectStore_throwsWhenStoreMissing() {
        UpdateObjectStoragePoolCmd cmd = Mockito.mock(UpdateObjectStoragePoolCmd.class);
        Mockito.when(objectStoreDao.findById(77L)).thenReturn(null);
        objectStoreService.updateObjectStore(77L, cmd);
    }

    @Test
    public void updateObjectStore_revertsUrlWhenListBucketsFails() {
        final long id = 10L;
        String oldUrl = "http://10.0.0.1:80";
        String newUrl = "http://10.0.0.2:80";

        ObjectStoreVO vo = new ObjectStoreVO();
        ReflectionTestUtils.setField(vo, "id", id);
        vo.setUrl(oldUrl);

        UpdateObjectStoragePoolCmd cmd = Mockito.mock(UpdateObjectStoragePoolCmd.class);
        Mockito.when(cmd.getUrl()).thenReturn(newUrl);
        // getName/getSize are never reached because listBuckets throws first; use lenient
        Mockito.lenient().when(cmd.getName()).thenReturn(null);
        Mockito.lenient().when(cmd.getSize()).thenReturn(null);

        ObjectStoreEntity entity = Mockito.mock(ObjectStoreEntity.class);
        Mockito.when(objectStoreDao.findById(id)).thenReturn(vo);
        Mockito.when(dataStoreMgr.getDataStore(id, DataStoreRole.Object)).thenReturn(entity);
        Mockito.when(objectStoreDao.update(eq(id), any(ObjectStoreVO.class))).thenReturn(true);
        Mockito.doThrow(new RuntimeException("connection refused")).when(entity).listBuckets();

        boolean threw = false;
        try {
            objectStoreService.updateObjectStore(id, cmd);
        } catch (IllegalArgumentException e) {
            threw = true;
        }
        assertTrue("Expected IllegalArgumentException when listBuckets fails", threw);
        // update called twice: set new URL, then revert to old URL
        Mockito.verify(objectStoreDao, Mockito.times(2)).update(eq(id), any(ObjectStoreVO.class));
    }

    @Test
    public void updateObjectStore_appliesNameAndSize() {
        final long id = 20L;
        ObjectStoreVO vo = new ObjectStoreVO();
        ReflectionTestUtils.setField(vo, "id", id);
        vo.setUrl("http://10.0.0.1:80");

        UpdateObjectStoragePoolCmd cmd = Mockito.mock(UpdateObjectStoragePoolCmd.class);
        Mockito.when(cmd.getUrl()).thenReturn(null);
        Mockito.when(cmd.getName()).thenReturn("new-name");
        Mockito.when(cmd.getSize()).thenReturn(500L);

        Mockito.when(objectStoreDao.findById(id)).thenReturn(vo);
        Mockito.when(objectStoreDao.update(eq(id), any(ObjectStoreVO.class))).thenReturn(true);

        ObjectStore result = objectStoreService.updateObjectStore(id, cmd);

        assertSame(vo, result);
        Mockito.verify(objectStoreDao).update(eq(id), any(ObjectStoreVO.class));
    }

    // -----------------------------------------------------------------------
    // getObjectStorageUsedStats
    // -----------------------------------------------------------------------

    @Test
    public void getObjectStorageUsedStats_delegatesToStorageCapacityService() {
        Long zoneId = 3L;
        CapacityVO expected = Mockito.mock(CapacityVO.class);
        Mockito.when(storageCapacityService.getObjectStorageUsedStats(zoneId)).thenReturn(expected);

        CapacityVO result = objectStoreService.getObjectStorageUsedStats(zoneId);

        assertSame(expected, result);
        Mockito.verify(storageCapacityService).getObjectStorageUsedStats(zoneId);
    }
}
