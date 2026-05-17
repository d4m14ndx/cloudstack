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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.dao.StoragePoolHostDao;

/**
 * Focused tests for {@link HostStorageAccessServiceImpl} -- the Phase 4
 * (slice 4) extraction of host-to-managed-pool access checks out of
 * {@link StorageManagerImpl}.
 *
 * The same behavior is exercised through the manager's delegating
 * wrappers in {@link StorageManagerImplTest}; these tests target the
 * service directly so the rules for managed vs. non-managed pools,
 * null arguments, and the find-an-Up-host loop are covered even if a
 * future refactor of the manager drops them.
 */
@RunWith(MockitoJUnitRunner.class)
public class HostStorageAccessServiceImplTest {

    @Mock
    private StoragePoolHostDao storagePoolHostDao;

    @Mock
    private HostDao hostDao;

    @Mock
    private PrimaryDataStoreDao storagePoolDao;

    @Mock
    private DataStoreProviderManager dataStoreProviderMgr;

    @InjectMocks
    private HostStorageAccessServiceImpl service;

    @Mock
    private Host host;

    @Mock
    private StoragePool pool;

    @Mock
    private DataStoreProvider provider;

    @Mock
    private PrimaryDataStoreDriver primaryDriver;

    // ---------------------------------------------------------------------
    // canHostAccessStoragePool
    // ---------------------------------------------------------------------

    @Test
    public void canHostAccessStoragePoolReturnsFalseWhenHostNull() {
        assertFalse(service.canHostAccessStoragePool(null, pool));
        Mockito.verifyNoInteractions(dataStoreProviderMgr);
    }

    @Test
    public void canHostAccessStoragePoolReturnsFalseWhenPoolNull() {
        assertFalse(service.canHostAccessStoragePool(host, null));
        Mockito.verifyNoInteractions(dataStoreProviderMgr);
    }

    @Test
    public void canHostAccessStoragePoolReturnsTrueForNonManagedPool() {
        // Non-managed pools are always accessible -- the driver is never consulted.
        Mockito.when(pool.isManaged()).thenReturn(false);

        assertTrue(service.canHostAccessStoragePool(host, pool));
        Mockito.verifyNoInteractions(dataStoreProviderMgr);
    }

    @Test
    public void canHostAccessStoragePoolDelegatesToDriverForManagedPool() {
        Mockito.when(pool.isManaged()).thenReturn(true);
        Mockito.when(pool.getStorageProviderName()).thenReturn("SolidFire");
        Mockito.when(dataStoreProviderMgr.getDataStoreProvider("SolidFire")).thenReturn(provider);
        Mockito.when(provider.getDataStoreDriver()).thenReturn(primaryDriver);
        Mockito.when(primaryDriver.canHostAccessStoragePool(host, pool)).thenReturn(true);

        assertTrue(service.canHostAccessStoragePool(host, pool));
        Mockito.verify(primaryDriver).canHostAccessStoragePool(host, pool);
    }

    @Test
    public void canHostAccessStoragePoolReturnsFalseWhenDriverIsNotPrimary() {
        // Managed pool whose driver isn't a PrimaryDataStoreDriver should fail closed.
        Mockito.when(pool.isManaged()).thenReturn(true);
        Mockito.when(pool.getStorageProviderName()).thenReturn("OddProvider");
        Mockito.when(dataStoreProviderMgr.getDataStoreProvider("OddProvider")).thenReturn(provider);
        DataStoreDriver nonPrimary = Mockito.mock(DataStoreDriver.class);
        Mockito.when(provider.getDataStoreDriver()).thenReturn(nonPrimary);

        assertFalse(service.canHostAccessStoragePool(host, pool));
    }

    // ---------------------------------------------------------------------
    // canHostPrepareStoragePoolAccess
    // ---------------------------------------------------------------------

    @Test
    public void canHostPrepareReturnsFalseForNullArgsOrUnmanaged() {
        // Three short-circuit paths -- null host, null pool, non-managed pool.
        assertFalse(service.canHostPrepareStoragePoolAccess(null, pool));
        assertFalse(service.canHostPrepareStoragePoolAccess(host, null));

        Mockito.when(pool.isManaged()).thenReturn(false);
        assertFalse(service.canHostPrepareStoragePoolAccess(host, pool));
        Mockito.verifyNoInteractions(dataStoreProviderMgr);
    }

    @Test
    public void canHostPrepareDelegatesToDriverForManagedPool() {
        Mockito.when(pool.isManaged()).thenReturn(true);
        Mockito.when(pool.getStorageProviderName()).thenReturn("SolidFire");
        Mockito.when(dataStoreProviderMgr.getDataStoreProvider("SolidFire")).thenReturn(provider);
        Mockito.when(provider.getDataStoreDriver()).thenReturn(primaryDriver);
        Mockito.when(primaryDriver.canHostPrepareStoragePoolAccess(host, pool)).thenReturn(true);

        assertTrue(service.canHostPrepareStoragePoolAccess(host, pool));
        Mockito.verify(primaryDriver).canHostPrepareStoragePoolAccess(host, pool);
    }

    @Test
    public void canHostPrepareReturnsFalseWhenDriverIsNotPrimary() {
        Mockito.when(pool.isManaged()).thenReturn(true);
        Mockito.when(pool.getStorageProviderName()).thenReturn("OddProvider");
        Mockito.when(dataStoreProviderMgr.getDataStoreProvider("OddProvider")).thenReturn(provider);
        Mockito.when(provider.getDataStoreDriver()).thenReturn(Mockito.mock(DataStoreDriver.class));

        assertFalse(service.canHostPrepareStoragePoolAccess(host, pool));
    }

    // ---------------------------------------------------------------------
    // canDisconnectHostFromStoragePool
    // ---------------------------------------------------------------------

    @Test
    public void canDisconnectReturnsTrueForNullPool() {
        // A null pool means there's nothing to disconnect from -- success-by-default.
        assertTrue(service.canDisconnectHostFromStoragePool(host, null));
        Mockito.verifyNoInteractions(dataStoreProviderMgr);
    }

    @Test
    public void canDisconnectReturnsTrueForNonManagedPool() {
        // Non-managed pools have no driver-side hold; always disconnectable.
        Mockito.when(pool.isManaged()).thenReturn(false);

        assertTrue(service.canDisconnectHostFromStoragePool(host, pool));
        Mockito.verifyNoInteractions(dataStoreProviderMgr);
    }

    @Test
    public void canDisconnectDelegatesToDriverForManagedPool() {
        Mockito.when(pool.isManaged()).thenReturn(true);
        Mockito.when(pool.getStorageProviderName()).thenReturn("SolidFire");
        Mockito.when(dataStoreProviderMgr.getDataStoreProvider("SolidFire")).thenReturn(provider);
        Mockito.when(provider.getDataStoreDriver()).thenReturn(primaryDriver);
        Mockito.when(primaryDriver.canDisconnectHostFromStoragePool(host, pool)).thenReturn(false);

        // Driver veto wins.
        assertFalse(service.canDisconnectHostFromStoragePool(host, pool));
        Mockito.verify(primaryDriver).canDisconnectHostFromStoragePool(host, pool);
    }

    @Test
    public void canDisconnectReturnsFalseWhenManagedDriverIsNotPrimary() {
        // Managed pool whose driver isn't a PrimaryDataStoreDriver -- fail closed
        // (this matches the pre-extraction behavior of StorageManagerImpl).
        Mockito.when(pool.isManaged()).thenReturn(true);
        Mockito.when(pool.getStorageProviderName()).thenReturn("OddProvider");
        Mockito.when(dataStoreProviderMgr.getDataStoreProvider("OddProvider")).thenReturn(provider);
        Mockito.when(provider.getDataStoreDriver()).thenReturn(Mockito.mock(DataStoreDriver.class));

        assertFalse(service.canDisconnectHostFromStoragePool(host, pool));
    }

    // ---------------------------------------------------------------------
    // canHostAccessStoragePools (protected helper)
    // ---------------------------------------------------------------------

    @Test
    public void canHostAccessStoragePoolsReturnsFalseForNullList() {
        assertFalse(service.canHostAccessStoragePools(host, null));
    }

    @Test
    public void canHostAccessStoragePoolsReturnsFalseForEmptyList() {
        assertFalse(service.canHostAccessStoragePools(host, Collections.emptyList()));
    }

    @Test
    public void canHostAccessStoragePoolsReturnsTrueWhenAllAccessible() {
        // Two non-managed pools -- both accept the host without driver consultation.
        StoragePoolVO p1 = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO p2 = Mockito.mock(StoragePoolVO.class);
        Mockito.when(p1.isManaged()).thenReturn(false);
        Mockito.when(p2.isManaged()).thenReturn(false);
        Mockito.when(storagePoolDao.findById(11L)).thenReturn(p1);
        Mockito.when(storagePoolDao.findById(12L)).thenReturn(p2);

        assertTrue(service.canHostAccessStoragePools(host, Arrays.asList(11L, 12L)));
    }

    @Test
    public void canHostAccessStoragePoolsShortCircuitsOnFirstReject() {
        // First pool refuses, so we never look up the second.
        StoragePoolVO p1 = Mockito.mock(StoragePoolVO.class);
        Mockito.when(p1.isManaged()).thenReturn(true);
        Mockito.when(p1.getStorageProviderName()).thenReturn("OddProvider");
        Mockito.when(storagePoolDao.findById(21L)).thenReturn(p1);
        Mockito.when(dataStoreProviderMgr.getDataStoreProvider("OddProvider")).thenReturn(provider);
        Mockito.when(provider.getDataStoreDriver()).thenReturn(Mockito.mock(DataStoreDriver.class));

        assertFalse(service.canHostAccessStoragePools(host, Arrays.asList(21L, 22L)));
        // Second pool id must never be looked up.
        Mockito.verify(storagePoolDao, Mockito.never()).findById(22L);
    }

    // ---------------------------------------------------------------------
    // findUpAndEnabledHostWithAccessToStoragePools
    // ---------------------------------------------------------------------

    @Test
    public void findHostReturnsNullWhenNoHostsConnected() {
        Mockito.when(storagePoolHostDao.findHostsConnectedToPools(Arrays.asList(1L, 2L)))
                .thenReturn(Collections.emptyList());

        assertNull(service.findUpAndEnabledHostWithAccessToStoragePools(Arrays.asList(1L, 2L)));
        Mockito.verifyNoInteractions(hostDao);
    }

    @Test
    public void findHostReturnsFirstAccessibleHost() {
        // Single connected host that accepts both pools.
        List<Long> poolIds = Arrays.asList(31L, 32L);
        Mockito.when(storagePoolHostDao.findHostsConnectedToPools(poolIds))
                .thenReturn(Arrays.asList(100L));
        HostVO connected = Mockito.mock(HostVO.class);
        Mockito.when(hostDao.findById(100L)).thenReturn(connected);

        StoragePoolVO p1 = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO p2 = Mockito.mock(StoragePoolVO.class);
        Mockito.when(p1.isManaged()).thenReturn(false);
        Mockito.when(p2.isManaged()).thenReturn(false);
        Mockito.when(storagePoolDao.findById(31L)).thenReturn(p1);
        Mockito.when(storagePoolDao.findById(32L)).thenReturn(p2);

        Host actual = service.findUpAndEnabledHostWithAccessToStoragePools(poolIds);

        assertSame(connected, actual);
    }

    @Test
    public void findHostSkipsHostsThatRefuseAndReturnsAcceptingOne() {
        // Two connected hosts -- both look up the same managed pool. The driver
        // accepts one and refuses the other, so whichever host comes first via
        // shuffle() the loop must end with the accepting one. Stubs are lenient
        // because shuffle() randomizes which side gets visited first; on runs
        // where the accepting host comes first the "refused" stubs go unused.
        List<Long> poolIds = Arrays.asList(41L);
        Mockito.when(storagePoolHostDao.findHostsConnectedToPools(poolIds))
                .thenReturn(Arrays.asList(200L, 201L));
        HostVO refused = Mockito.mock(HostVO.class);
        HostVO accepted = Mockito.mock(HostVO.class);
        Mockito.lenient().when(hostDao.findById(200L)).thenReturn(refused);
        Mockito.lenient().when(hostDao.findById(201L)).thenReturn(accepted);

        StoragePoolVO managedPool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(managedPool.isManaged()).thenReturn(true);
        Mockito.when(managedPool.getStorageProviderName()).thenReturn("SolidFire");
        Mockito.when(storagePoolDao.findById(41L)).thenReturn(managedPool);
        Mockito.when(dataStoreProviderMgr.getDataStoreProvider("SolidFire")).thenReturn(provider);
        Mockito.when(provider.getDataStoreDriver()).thenReturn(primaryDriver);
        Mockito.lenient().when(primaryDriver.canHostAccessStoragePool(refused, managedPool)).thenReturn(false);
        Mockito.lenient().when(primaryDriver.canHostAccessStoragePool(accepted, managedPool)).thenReturn(true);

        Host actual = service.findUpAndEnabledHostWithAccessToStoragePools(poolIds);

        assertSame(accepted, actual);
    }

    @Test
    public void findHostReturnsNullWhenAllRefuse() {
        List<Long> poolIds = Arrays.asList(51L);
        Mockito.when(storagePoolHostDao.findHostsConnectedToPools(poolIds))
                .thenReturn(Arrays.asList(300L, 301L));
        Mockito.when(hostDao.findById(Mockito.anyLong())).thenReturn(Mockito.mock(HostVO.class));

        // Managed pool + non-primary driver makes every canHostAccessStoragePool return false.
        StoragePoolVO managedPool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(managedPool.isManaged()).thenReturn(true);
        Mockito.when(managedPool.getStorageProviderName()).thenReturn("OddProvider");
        Mockito.when(storagePoolDao.findById(51L)).thenReturn(managedPool);
        Mockito.when(dataStoreProviderMgr.getDataStoreProvider("OddProvider")).thenReturn(provider);
        Mockito.when(provider.getDataStoreDriver()).thenReturn(Mockito.mock(DataStoreDriver.class));

        assertNull(service.findUpAndEnabledHostWithAccessToStoragePools(poolIds));
    }

    @Test
    public void findHostQueriesDaoWithExactPoolIdList() {
        // The pool-id list is passed straight through to the DAO -- guard against
        // an accidental refactor that mutates it.
        List<Long> poolIds = Arrays.asList(61L, 62L, 63L);
        Mockito.when(storagePoolHostDao.findHostsConnectedToPools(poolIds))
                .thenReturn(Collections.emptyList());

        service.findUpAndEnabledHostWithAccessToStoragePools(poolIds);

        Mockito.verify(storagePoolHostDao).findHostsConnectedToPools(poolIds);
        assertEquals(3, poolIds.size());
    }
}
