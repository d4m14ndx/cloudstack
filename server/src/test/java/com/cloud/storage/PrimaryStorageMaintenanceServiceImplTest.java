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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.command.admin.storage.CancelPrimaryStorageMaintenanceCmd;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Focused tests for {@link PrimaryStorageMaintenanceServiceImpl} -- the
 * Phase 4 extraction of primary-storage maintenance state transitions
 * out of {@link StorageManagerImpl}.
 *
 * <p>Covers both the {@code preparePrimaryStorageForMaintenance} and
 * {@code cancelPrimaryStorageForMaintenance} workflows, including the
 * datastore-cluster fan-out and the various error paths (pool missing,
 * wrong state, child failure rollback to {@code ErrorInMaintenance}).
 */
@RunWith(MockitoJUnitRunner.class)
public class PrimaryStorageMaintenanceServiceImplTest {

    private static final long POOL_ID = 7L;

    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private DataStoreManager dataStoreMgr;
    @Mock
    private DataStoreProviderManager dataStoreProviderMgr;

    @Mock
    private DataStoreProvider provider;
    @Mock
    private DataStoreLifeCycle lifeCycle;
    @Mock
    private DataStore store;
    @Mock
    private StoragePoolVO primaryStorage;
    @Mock
    private CancelPrimaryStorageMaintenanceCmd cancelCmd;

    /**
     * Real concrete data store returned on the *second* invocation of
     * {@link DataStoreManager#getDataStore} in the production code (it
     * is downcast to {@link PrimaryDataStoreInfo}). Mocking a type that
     * implements both interfaces lets a single stub answer both calls.
     */
    private DataStoreAndPrimary refreshedStoreImpl;

    @InjectMocks
    private PrimaryStorageMaintenanceServiceImpl service;

    @Before
    public void setUp() {
        refreshedStoreImpl = org.mockito.Mockito.mock(DataStoreAndPrimary.class);
        // Default wiring used by the happy paths; specific tests override.
        when(storagePoolDao.findById(POOL_ID)).thenReturn(primaryStorage);
        when(primaryStorage.getId()).thenReturn(POOL_ID);
        when(primaryStorage.getStorageProviderName()).thenReturn("nfs");
        when(dataStoreProviderMgr.getDataStoreProvider("nfs")).thenReturn(provider);
        when(provider.getDataStoreLifeCycle()).thenReturn(lifeCycle);
        // First call returns the plain DataStore used for maintain()/cancelMaintain();
        // second call (after the lifecycle action) is downcast to PrimaryDataStoreInfo.
        when(dataStoreMgr.getDataStore(POOL_ID, DataStoreRole.Primary))
                .thenReturn(store, refreshedStoreImpl);
    }

    /** Test-only interface that implements both DataStore and PrimaryDataStoreInfo so a single mock can satisfy both return-type constraints. */
    public interface DataStoreAndPrimary extends DataStore, PrimaryDataStoreInfo {
    }

    // ---------------------------------------------------------------------
    // preparePrimaryStorageForMaintenance
    // ---------------------------------------------------------------------

    @Test
    public void preparePrimaryStorageForMaintenancePoolMissingThrows() throws Exception {
        when(storagePoolDao.findById(POOL_ID)).thenReturn(null);

        try {
            service.preparePrimaryStorageForMaintenance(POOL_ID);
            fail("Expected InvalidParameterValueException for missing pool");
        } catch (InvalidParameterValueException e) {
            // expected
        }
        verify(lifeCycle, never()).maintain(any());
    }

    @Test
    public void preparePrimaryStorageForMaintenanceWrongStateThrows() throws Exception {
        when(primaryStorage.getStatus()).thenReturn(StoragePoolStatus.Maintenance);

        try {
            service.preparePrimaryStorageForMaintenance(POOL_ID);
            fail("Expected InvalidParameterValueException for non-Up status");
        } catch (InvalidParameterValueException e) {
            // expected
        }
        verify(lifeCycle, never()).maintain(any());
    }

    @Test
    public void preparePrimaryStorageForMaintenanceSinglePoolUpInvokesMaintain() throws Exception {
        when(primaryStorage.getStatus()).thenReturn(StoragePoolStatus.Up);
        when(primaryStorage.getPoolType()).thenReturn(StoragePoolType.NetworkFilesystem);

        PrimaryDataStoreInfo result = service.preparePrimaryStorageForMaintenance(POOL_ID);

        assertSame(refreshedStoreImpl, result);
        verify(lifeCycle, times(1)).maintain(store);
        verify(storagePoolDao, never()).listChildStoragePoolsInDatastoreCluster(eq(POOL_ID));
    }

    @Test
    public void preparePrimaryStorageForMaintenanceErrorInMaintenanceIsAlsoAllowed() throws Exception {
        when(primaryStorage.getStatus()).thenReturn(StoragePoolStatus.ErrorInMaintenance);
        when(primaryStorage.getPoolType()).thenReturn(StoragePoolType.NetworkFilesystem);

        PrimaryDataStoreInfo result = service.preparePrimaryStorageForMaintenance(POOL_ID);

        assertSame(refreshedStoreImpl, result);
        verify(lifeCycle).maintain(store);
    }

    @Test
    public void preparePrimaryStorageForMaintenanceDatastoreClusterAlreadyPreparingThrows() throws Exception {
        // Up satisfies the initial gate; PrepareForMaintenance triggers the
        // duplicate-job guard inside the DatastoreCluster branch.
        when(primaryStorage.getStatus())
                .thenReturn(StoragePoolStatus.Up)
                .thenReturn(StoragePoolStatus.PrepareForMaintenance);
        when(primaryStorage.getPoolType()).thenReturn(StoragePoolType.DatastoreCluster);

        try {
            service.preparePrimaryStorageForMaintenance(POOL_ID);
            fail("Expected CloudRuntimeException for duplicate prepare job");
        } catch (CloudRuntimeException e) {
            // expected
        }
        verify(lifeCycle, never()).maintain(any());
    }

    @Test
    public void preparePrimaryStorageForMaintenanceDatastoreClusterFansOutToChildren() throws Exception {
        when(primaryStorage.getStatus()).thenReturn(StoragePoolStatus.Up);
        when(primaryStorage.getPoolType()).thenReturn(StoragePoolType.DatastoreCluster);

        StoragePoolVO child1 = mock(StoragePoolVO.class);
        StoragePoolVO child2 = mock(StoragePoolVO.class);
        when(child1.getId()).thenReturn(101L);
        when(child2.getId()).thenReturn(102L);

        DataStore childStore1 = mock(DataStore.class);
        DataStore childStore2 = mock(DataStore.class);
        when(dataStoreMgr.getDataStore(101L, DataStoreRole.Primary)).thenReturn(childStore1);
        when(dataStoreMgr.getDataStore(102L, DataStoreRole.Primary)).thenReturn(childStore2);

        List<StoragePoolVO> children = Arrays.asList(child1, child2);
        when(storagePoolDao.listChildStoragePoolsInDatastoreCluster(POOL_ID)).thenReturn(children);

        PrimaryDataStoreInfo result = service.preparePrimaryStorageForMaintenance(POOL_ID);

        assertSame(refreshedStoreImpl, result);
        // Parent pool was flipped to PrepareForMaintenance once, then maintained at the end.
        verify(primaryStorage).setStatus(StoragePoolStatus.PrepareForMaintenance);
        verify(storagePoolDao).update(eq(POOL_ID), eq(primaryStorage));
        // Each child got the same status flip and then maintain.
        verify(child1).setStatus(StoragePoolStatus.PrepareForMaintenance);
        verify(child2).setStatus(StoragePoolStatus.PrepareForMaintenance);
        verify(lifeCycle).maintain(childStore1);
        verify(lifeCycle).maintain(childStore2);
        verify(lifeCycle).maintain(store);
    }

    @Test
    public void preparePrimaryStorageForMaintenanceDatastoreClusterChildFailureRollsBackToError() throws Exception {
        when(primaryStorage.getStatus()).thenReturn(StoragePoolStatus.Up);
        when(primaryStorage.getPoolType()).thenReturn(StoragePoolType.DatastoreCluster);

        StoragePoolVO child = mock(StoragePoolVO.class);
        when(child.getId()).thenReturn(201L);
        DataStore childStore = mock(DataStore.class);
        when(dataStoreMgr.getDataStore(201L, DataStoreRole.Primary)).thenReturn(childStore);
        when(storagePoolDao.listChildStoragePoolsInDatastoreCluster(POOL_ID))
                .thenReturn(Collections.singletonList(child));

        // Simulate a child failing to enter maintenance.
        org.mockito.Mockito.doThrow(new RuntimeException("kaboom")).when(lifeCycle).maintain(childStore);

        try {
            service.preparePrimaryStorageForMaintenance(POOL_ID);
            fail("Expected CloudRuntimeException for child maintenance failure");
        } catch (CloudRuntimeException e) {
            // expected
        }

        // Child and parent should both have been flipped to ErrorInMaintenance.
        verify(child).setStatus(StoragePoolStatus.ErrorInMaintenance);
        verify(primaryStorage).setStatus(StoragePoolStatus.ErrorInMaintenance);
        // Parent's own maintain() must NOT have been called once a child failed.
        verify(lifeCycle, never()).maintain(store);
    }

    // ---------------------------------------------------------------------
    // cancelPrimaryStorageForMaintenance
    // ---------------------------------------------------------------------

    @Test
    public void cancelPrimaryStorageForMaintenancePoolMissingThrows() throws Exception {
        when(cancelCmd.getId()).thenReturn(POOL_ID);
        when(storagePoolDao.findById(POOL_ID)).thenReturn(null);

        try {
            service.cancelPrimaryStorageForMaintenance(cancelCmd);
            fail("Expected InvalidParameterValueException for missing pool");
        } catch (InvalidParameterValueException e) {
            // expected
        }
        verify(lifeCycle, never()).cancelMaintain(any());
    }

    @Test
    public void cancelPrimaryStorageForMaintenanceUpStatusThrowsStorageUnavailable() throws Exception {
        when(cancelCmd.getId()).thenReturn(POOL_ID);
        when(primaryStorage.getStatus()).thenReturn(StoragePoolStatus.Up);

        try {
            service.cancelPrimaryStorageForMaintenance(cancelCmd);
            fail("Expected StorageUnavailableException for Up pool");
        } catch (StorageUnavailableException e) {
            // expected
        }
        verify(lifeCycle, never()).cancelMaintain(any());
    }

    @Test
    public void cancelPrimaryStorageForMaintenancePrepareForMaintenanceStatusThrows() throws Exception {
        when(cancelCmd.getId()).thenReturn(POOL_ID);
        when(primaryStorage.getStatus()).thenReturn(StoragePoolStatus.PrepareForMaintenance);

        try {
            service.cancelPrimaryStorageForMaintenance(cancelCmd);
            fail("Expected StorageUnavailableException for PrepareForMaintenance pool");
        } catch (StorageUnavailableException e) {
            // expected
        }
        verify(lifeCycle, never()).cancelMaintain(any());
    }

    @Test
    public void cancelPrimaryStorageForMaintenanceSinglePoolMaintenanceInvokesCancel() throws Exception {
        when(cancelCmd.getId()).thenReturn(POOL_ID);
        when(primaryStorage.getStatus()).thenReturn(StoragePoolStatus.Maintenance);
        when(primaryStorage.getPoolType()).thenReturn(StoragePoolType.NetworkFilesystem);

        PrimaryDataStoreInfo result = service.cancelPrimaryStorageForMaintenance(cancelCmd);

        assertSame(refreshedStoreImpl, result);
        verify(lifeCycle).cancelMaintain(store);
        verify(storagePoolDao, never()).listChildStoragePoolsInDatastoreCluster(eq(POOL_ID));
    }

    @Test
    public void cancelPrimaryStorageForMaintenanceErrorInMaintenanceIsAlsoAllowed() throws Exception {
        when(cancelCmd.getId()).thenReturn(POOL_ID);
        when(primaryStorage.getStatus()).thenReturn(StoragePoolStatus.ErrorInMaintenance);
        when(primaryStorage.getPoolType()).thenReturn(StoragePoolType.NetworkFilesystem);

        PrimaryDataStoreInfo result = service.cancelPrimaryStorageForMaintenance(cancelCmd);

        assertSame(refreshedStoreImpl, result);
        verify(lifeCycle).cancelMaintain(store);
    }

    @Test
    public void cancelPrimaryStorageForMaintenanceDatastoreClusterFlipsParentAndCancelsChildren() throws Exception {
        when(cancelCmd.getId()).thenReturn(POOL_ID);
        when(primaryStorage.getStatus()).thenReturn(StoragePoolStatus.Maintenance);
        when(primaryStorage.getPoolType()).thenReturn(StoragePoolType.DatastoreCluster);

        StoragePoolVO child1 = mock(StoragePoolVO.class);
        StoragePoolVO child2 = mock(StoragePoolVO.class);
        when(child1.getId()).thenReturn(301L);
        when(child2.getId()).thenReturn(302L);
        DataStore childStore1 = mock(DataStore.class);
        DataStore childStore2 = mock(DataStore.class);
        when(dataStoreMgr.getDataStore(301L, DataStoreRole.Primary)).thenReturn(childStore1);
        when(dataStoreMgr.getDataStore(302L, DataStoreRole.Primary)).thenReturn(childStore2);
        when(storagePoolDao.listChildStoragePoolsInDatastoreCluster(POOL_ID))
                .thenReturn(Arrays.asList(child1, child2));

        PrimaryDataStoreInfo result = service.cancelPrimaryStorageForMaintenance(cancelCmd);

        assertSame(refreshedStoreImpl, result);
        // Parent flipped to Up *before* children are cancelled.
        verify(primaryStorage).setStatus(StoragePoolStatus.Up);
        verify(storagePoolDao).update(eq(POOL_ID), eq(primaryStorage));
        verify(lifeCycle).cancelMaintain(childStore1);
        verify(lifeCycle).cancelMaintain(childStore2);
        verify(lifeCycle).cancelMaintain(store);
    }

    @Test
    public void cancelPrimaryStorageForMaintenanceDatastoreClusterNoChildrenStillCancelsParent() throws Exception {
        when(cancelCmd.getId()).thenReturn(POOL_ID);
        when(primaryStorage.getStatus()).thenReturn(StoragePoolStatus.Maintenance);
        when(primaryStorage.getPoolType()).thenReturn(StoragePoolType.DatastoreCluster);
        when(storagePoolDao.listChildStoragePoolsInDatastoreCluster(POOL_ID))
                .thenReturn(Collections.emptyList());

        PrimaryDataStoreInfo result = service.cancelPrimaryStorageForMaintenance(cancelCmd);

        assertEquals(refreshedStoreImpl, result);
        verify(primaryStorage).setStatus(StoragePoolStatus.Up);
        verify(lifeCycle, times(1)).cancelMaintain(store);
    }

    // ---------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------

    private static <T> T mock(Class<T> klass) {
        return org.mockito.Mockito.mock(klass);
    }
}
