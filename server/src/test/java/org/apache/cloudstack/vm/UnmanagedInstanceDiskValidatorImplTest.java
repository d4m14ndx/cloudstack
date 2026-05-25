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
package org.apache.cloudstack.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.ServerApiException;
import org.apache.cloudstack.resourcelimit.Reserver;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.DataCenter;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.offering.DiskOffering;
import com.cloud.org.Cluster;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.StoragePool;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.Pair;

/**
 * Focused tests for {@link UnmanagedInstanceDiskValidatorImpl} — the
 * Phase 4 extraction of disk / storage-pool pre-flight validation
 * out of {@link UnmanagedVMsManagerImpl}.
 *
 * <p>The behaviour is also exercised indirectly through the manager's
 * delegating wrappers; these tests target the service directly so
 * future refactors of the manager don't drop coverage of these edge
 * cases.
 */
@RunWith(MockitoJUnitRunner.class)
public class UnmanagedInstanceDiskValidatorImplTest {

    @Mock
    private PrimaryDataStoreDao primaryDataStoreDao;
    @Mock
    private VolumeApiService volumeApiService;
    @Mock
    private AccountService accountService;
    @Mock
    private DiskOfferingDao diskOfferingDao;
    @Mock
    private ResourceLimitService resourceLimitService;

    @InjectMocks
    private UnmanagedInstanceDiskValidatorImpl validator;

    private static final String INSTANCE_NAME = "TestInstance";
    private static final long ZONE_ID = 1L;
    private static final long CLUSTER_ID = 100L;
    private static final long DISK_BYTES = 5L * 1024L * 1024L * 1024L;

    private DataCenter zone;
    private Cluster cluster;
    private Account owner;
    private UnmanagedInstanceTO.Disk disk;
    private DiskOfferingVO diskOffering;

    @Before
    public void setUp() {
        zone = mock(DataCenter.class);
        when(zone.getId()).thenReturn(ZONE_ID);
        when(zone.getUuid()).thenReturn("zone-uuid");

        cluster = mock(Cluster.class);
        when(cluster.getId()).thenReturn(CLUSTER_ID);

        owner = mock(Account.class);

        disk = new UnmanagedInstanceTO.Disk();
        disk.setDiskId("1000-1");
        disk.setLabel("DiskLabel");
        disk.setController("scsi");
        disk.setCapacity(DISK_BYTES);
        disk.setDatastoreName("DS");
        disk.setDatastoreHost("ds-host");
        disk.setDatastorePath("[ds] vm/ROOT-1.vmdk");
        disk.setDatastoreType("NFS");

        diskOffering = mock(DiskOfferingVO.class);
        when(diskOffering.getUuid()).thenReturn("disk-offering-uuid");
    }

    // getStoragePool ---------------------------------------------------------

    @Test
    public void testGetStoragePoolReturnsPoolMatchingHostPath() {
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getDataCenterId()).thenReturn(ZONE_ID);
        when(pool.getClusterId()).thenReturn(CLUSTER_ID);
        when(primaryDataStoreDao.listPoolByHostPath(disk.getDatastoreHost(), disk.getDatastorePath()))
                .thenReturn(Collections.singletonList(pool));
        when(volumeApiService.doesStoragePoolSupportDiskOffering(pool, diskOffering)).thenReturn(true);

        StoragePool resolved = validator.getStoragePool(disk, zone, cluster, diskOffering);
        assertSame(pool, resolved);
    }

    @Test
    public void testGetStoragePoolSkipsPoolInDifferentZone() {
        StoragePoolVO wrongZonePool = mock(StoragePoolVO.class);
        when(wrongZonePool.getDataCenterId()).thenReturn(99L);
        when(primaryDataStoreDao.listPoolByHostPath(any(), any()))
                .thenReturn(Collections.singletonList(wrongZonePool));
        when(primaryDataStoreDao.listPoolsByCluster(CLUSTER_ID)).thenReturn(Collections.emptyList());
        when(primaryDataStoreDao.listByDataCenterId(ZONE_ID)).thenReturn(Collections.emptyList());

        assertThrows(ServerApiException.class,
                () -> validator.getStoragePool(disk, zone, cluster, diskOffering));
    }

    @Test
    public void testGetStoragePoolFallsBackToClusterScanOnPathMatch() {
        // Disk has no datastoreType — first scan is skipped, fallback runs.
        disk.setDatastoreType(null);
        StoragePoolVO clusterPool = mock(StoragePoolVO.class);
        when(clusterPool.getPath()).thenReturn(disk.getDatastorePath());
        when(primaryDataStoreDao.listPoolsByCluster(CLUSTER_ID)).thenReturn(Collections.singletonList(clusterPool));
        when(primaryDataStoreDao.listByDataCenterId(ZONE_ID)).thenReturn(Collections.emptyList());
        when(volumeApiService.doesStoragePoolSupportDiskOffering(clusterPool, diskOffering)).thenReturn(true);

        StoragePool resolved = validator.getStoragePool(disk, zone, cluster, diskOffering);
        assertSame(clusterPool, resolved);
    }

    @Test
    public void testGetStoragePoolThrowsWhenNoPoolFound() {
        when(primaryDataStoreDao.listPoolByHostPath(any(), any())).thenReturn(Collections.emptyList());
        when(primaryDataStoreDao.listPoolsByCluster(CLUSTER_ID)).thenReturn(Collections.emptyList());
        when(primaryDataStoreDao.listByDataCenterId(ZONE_ID)).thenReturn(Collections.emptyList());

        ServerApiException e = assertThrows(ServerApiException.class,
                () -> validator.getStoragePool(disk, zone, cluster, diskOffering));
        assertEquals(true, e.getMessage().contains(disk.getDiskId()));
    }

    // storagePoolSupportsDiskOffering ----------------------------------------

    @Test
    public void testStoragePoolSupportsDiskOfferingNullPool() {
        assertEquals(false, validator.storagePoolSupportsDiskOffering(null, diskOffering));
    }

    @Test
    public void testStoragePoolSupportsDiskOfferingNullDiskOffering() {
        StoragePool pool = mock(StoragePool.class);
        assertEquals(false, validator.storagePoolSupportsDiskOffering(pool, null));
    }

    @Test
    public void testStoragePoolSupportsDiskOfferingDelegatesToVolumeApi() {
        StoragePool pool = mock(StoragePool.class);
        when(volumeApiService.doesStoragePoolSupportDiskOffering(pool, diskOffering)).thenReturn(true);
        assertEquals(true, validator.storagePoolSupportsDiskOffering(pool, diskOffering));
        verify(volumeApiService).doesStoragePoolSupportDiskOffering(pool, diskOffering);
    }

    // getRootAndDataDisks ----------------------------------------------------

    @Test
    public void testGetRootAndDataDisksThrowsWhenMappingsMissing() {
        UnmanagedInstanceTO.Disk root = disk;
        UnmanagedInstanceTO.Disk data1 = makeDisk("1000-2");
        UnmanagedInstanceTO.Disk data2 = makeDisk("1000-3");
        List<UnmanagedInstanceTO.Disk> disks = List.of(root, data1, data2);
        // Only one offering for two data disks
        Map<String, Long> offeringMap = Map.of(data1.getDiskId(), 5L);

        assertThrows(ServerApiException.class,
                () -> validator.getRootAndDataDisks(disks, offeringMap));
    }

    @Test
    public void testGetRootAndDataDisksSelectsRootCorrectly() {
        UnmanagedInstanceTO.Disk root = disk;
        UnmanagedInstanceTO.Disk data1 = makeDisk("1000-2");
        List<UnmanagedInstanceTO.Disk> disks = List.of(root, data1);
        Map<String, Long> offeringMap = new HashMap<>();
        offeringMap.put(data1.getDiskId(), 7L);

        Pair<UnmanagedInstanceTO.Disk, List<UnmanagedInstanceTO.Disk>> result =
                validator.getRootAndDataDisks(disks, offeringMap);
        assertSame(root, result.first());
        assertEquals(1, result.second().size());
        assertSame(data1, result.second().get(0));
    }

    @Test
    public void testGetRootAndDataDisksFillsMissingCapacityFromOffering() {
        UnmanagedInstanceTO.Disk root = disk;
        UnmanagedInstanceTO.Disk data1 = makeDisk("1000-2");
        data1.setCapacity(null);
        List<UnmanagedInstanceTO.Disk> disks = List.of(root, data1);
        Map<String, Long> offeringMap = new HashMap<>();
        offeringMap.put(data1.getDiskId(), 7L);

        DiskOfferingVO offering = mock(DiskOfferingVO.class);
        when(offering.getDiskSize()).thenReturn(2048L);
        when(diskOfferingDao.findById(7L)).thenReturn(offering);

        validator.getRootAndDataDisks(disks, offeringMap);
        assertEquals(Long.valueOf(2048L), data1.getCapacity());
    }

    @Test
    public void testGetRootAndDataDisksRejectsWhenOfferingMapDoesNotMatchAnyDisk() {
        UnmanagedInstanceTO.Disk d1 = disk;
        UnmanagedInstanceTO.Disk d2 = makeDisk("1000-2");
        UnmanagedInstanceTO.Disk d3 = makeDisk("1000-3");
        List<UnmanagedInstanceTO.Disk> disks = List.of(d1, d2, d3);
        // Offering map size matches disks.size()-1, but the keys reference disks
        // that don't exist — every actual disk is treated as a root candidate,
        // and getRootAndDataDisks should reject the ambiguous result.
        Map<String, Long> offeringMap = Map.of("phantom-1", 5L, "phantom-2", 6L);

        assertThrows(ServerApiException.class,
                () -> validator.getRootAndDataDisks(disks, offeringMap));
    }

    // checkUnmanagedDiskAndOfferingForImport (single-disk) -------------------

    @Test
    public void testCheckSingleDiskThrowsWhenBothOfferingsNull() {
        assertThrows(ServerApiException.class,
                () -> validator.checkUnmanagedDiskAndOfferingForImport(INSTANCE_NAME, disk, null, null,
                        owner, zone, cluster, false, new ArrayList<>()));
    }

    @Test
    public void testCheckSingleDiskThrowsForZeroCapacity() {
        disk.setCapacity(0L);
        ServerApiException e = assertThrows(ServerApiException.class,
                () -> validator.checkUnmanagedDiskAndOfferingForImport(INSTANCE_NAME, disk, diskOffering, null,
                        owner, zone, cluster, false, new ArrayList<>()));
        assertEquals(true, e.getMessage().contains(disk.getDiskId()));
    }

    @Test
    public void testCheckSingleDiskThrowsForFixedOfferingZeroSize() {
        when(diskOffering.isCustomized()).thenReturn(false);
        when(diskOffering.getDiskSize()).thenReturn(0L);
        assertThrows(ServerApiException.class,
                () -> validator.checkUnmanagedDiskAndOfferingForImport(INSTANCE_NAME, disk, diskOffering, null,
                        owner, zone, cluster, false, new ArrayList<>()));
    }

    @Test
    public void testCheckSingleDiskThrowsForFixedOfferingSmallerThanDisk() {
        when(diskOffering.isCustomized()).thenReturn(false);
        when(diskOffering.getDiskSize()).thenReturn(DISK_BYTES - 1);
        assertThrows(ServerApiException.class,
                () -> validator.checkUnmanagedDiskAndOfferingForImport(INSTANCE_NAME, disk, diskOffering, null,
                        owner, zone, cluster, false, new ArrayList<>()));
    }

    @Test
    public void testCheckSingleDiskThrowsWhenStoragePoolUnsupportedAndMigrateDisallowed() throws Exception {
        when(diskOffering.isCustomized()).thenReturn(true);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getDataCenterId()).thenReturn(ZONE_ID);
        when(pool.getClusterId()).thenReturn(CLUSTER_ID);
        when(pool.getUuid()).thenReturn("pool-uuid");
        when(primaryDataStoreDao.listPoolByHostPath(any(), any())).thenReturn(Collections.singletonList(pool));
        // doesStoragePoolSupportDiskOffering returns true for getStoragePool selection
        // but the subsequent storagePoolSupportsDiskOffering check goes through the
        // same mock — return true for the first invocation and false for the second
        // by stubbing both calls to return true except we want the inner check to
        // fail. The wrapper storagePoolSupportsDiskOffering calls
        // volumeApiService.doesStoragePoolSupportDiskOffering(pool, diskOffering)
        // again, so we need both invocations to be coherent. To force the failure,
        // set migrateAllowed=false and have the storage pool selection succeed but
        // the subsequent compatibility check fail by using a different mock instance
        // for the pool that does NOT pass the volumeApi check. We use a separate
        // pool resolved by listPoolByHostPath but unsupported for the offering.
        when(volumeApiService.doesStoragePoolSupportDiskOffering(pool, diskOffering)).thenReturn(true, false);
        doNothing().when(accountService).checkAccess(eq(owner), any(DiskOffering.class), any());

        assertThrows(InvalidParameterValueException.class,
                () -> validator.checkUnmanagedDiskAndOfferingForImport(INSTANCE_NAME, disk, diskOffering, null,
                        owner, zone, cluster, false, new ArrayList<>()));
    }

    @Test
    public void testCheckSingleDiskSucceedsAndChecksResourceLimit() throws Exception {
        when(diskOffering.isCustomized()).thenReturn(true);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getDataCenterId()).thenReturn(ZONE_ID);
        when(pool.getClusterId()).thenReturn(CLUSTER_ID);
        when(primaryDataStoreDao.listPoolByHostPath(any(), any())).thenReturn(Collections.singletonList(pool));
        when(volumeApiService.doesStoragePoolSupportDiskOffering(pool, diskOffering)).thenReturn(true);
        doNothing().when(accountService).checkAccess(eq(owner), any(DiskOffering.class), any());
        List<Reserver> reservations = new ArrayList<>();

        validator.checkUnmanagedDiskAndOfferingForImport(INSTANCE_NAME, disk, diskOffering, null,
                owner, zone, cluster, false, reservations);

        verify(resourceLimitService).checkVolumeResourceLimit(owner, true, disk.getCapacity(), diskOffering, reservations);
    }

    @Test
    public void testCheckSingleDiskFallsBackToServiceOfferingDiskOffering() throws Exception {
        ServiceOfferingVO serviceOffering = mock(ServiceOfferingVO.class);
        when(serviceOffering.getDiskOfferingId()).thenReturn(42L);
        DiskOfferingVO offering = mock(DiskOfferingVO.class);
        when(diskOfferingDao.findById(42L)).thenReturn(offering);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getDataCenterId()).thenReturn(ZONE_ID);
        when(pool.getClusterId()).thenReturn(CLUSTER_ID);
        when(primaryDataStoreDao.listPoolByHostPath(any(), any())).thenReturn(Collections.singletonList(pool));
        when(volumeApiService.doesStoragePoolSupportDiskOffering(pool, offering)).thenReturn(true);
        List<Reserver> reservations = new ArrayList<>();

        // migrateAllowed=true skips the post-resolution compatibility check, so
        // a service-offering-supplied offering is enough to make the call succeed.
        validator.checkUnmanagedDiskAndOfferingForImport(INSTANCE_NAME, disk, null, serviceOffering,
                owner, zone, cluster, true, reservations);

        // accountService.checkAccess should NOT be called when only serviceOffering supplied
        verify(accountService, never()).checkAccess(any(Account.class), any(DiskOffering.class), any());
        verify(resourceLimitService).checkVolumeResourceLimit(owner, true, disk.getCapacity(), offering, reservations);
    }

    // checkUnmanagedDiskAndOfferingForImport (multi-disk) --------------------

    @Test
    public void testCheckMultiDiskThrowsForNullDisk() {
        List<UnmanagedInstanceTO.Disk> disks = new ArrayList<>();
        disks.add(null);
        assertThrows(ServerApiException.class,
                () -> validator.checkUnmanagedDiskAndOfferingForImport(INSTANCE_NAME, disks, new HashMap<>(),
                        owner, zone, cluster, false, new ArrayList<>()));
    }

    @Test
    public void testCheckMultiDiskThrowsWhenDiskHasNoOffering() {
        List<UnmanagedInstanceTO.Disk> disks = List.of(disk);
        Map<String, Long> offeringMap = new HashMap<>(); // empty -> no entry for disk
        assertThrows(ServerApiException.class,
                () -> validator.checkUnmanagedDiskAndOfferingForImport(INSTANCE_NAME, disks, offeringMap,
                        owner, zone, cluster, false, new ArrayList<>()));
    }

    @Test
    public void testCheckMultiDiskRejectsMixedControllers() {
        UnmanagedInstanceTO.Disk other = makeDisk("1000-2");
        other.setController("ide"); // different from disk.getController() == "scsi"
        List<UnmanagedInstanceTO.Disk> disks = List.of(disk, other);
        Map<String, Long> offeringMap = new HashMap<>();
        offeringMap.put(disk.getDiskId(), 1L);
        offeringMap.put(other.getDiskId(), 2L);
        DiskOfferingVO offering = mock(DiskOfferingVO.class);
        when(diskOfferingDao.findById(anyLong())).thenReturn(offering);
        when(offering.isCustomized()).thenReturn(true);

        assertThrows(ServerApiException.class,
                () -> validator.checkUnmanagedDiskAndOfferingForImport(INSTANCE_NAME, disks, offeringMap,
                        owner, zone, cluster, false, new ArrayList<>()));
    }

    @Test
    public void testCheckMultiDiskSucceedsForMatchingControllers() throws Exception {
        UnmanagedInstanceTO.Disk d2 = makeDisk("1000-2");
        d2.setController("scsi");
        List<UnmanagedInstanceTO.Disk> disks = List.of(disk, d2);
        Map<String, Long> offeringMap = new HashMap<>();
        offeringMap.put(disk.getDiskId(), 1L);
        offeringMap.put(d2.getDiskId(), 2L);
        DiskOfferingVO offering = mock(DiskOfferingVO.class);
        when(offering.isCustomized()).thenReturn(true);
        when(diskOfferingDao.findById(anyLong())).thenReturn(offering);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getDataCenterId()).thenReturn(ZONE_ID);
        when(pool.getClusterId()).thenReturn(CLUSTER_ID);
        when(primaryDataStoreDao.listPoolByHostPath(any(), any())).thenReturn(Collections.singletonList(pool));
        when(volumeApiService.doesStoragePoolSupportDiskOffering(pool, offering)).thenReturn(true);

        validator.checkUnmanagedDiskAndOfferingForImport(INSTANCE_NAME, disks, offeringMap,
                owner, zone, cluster, true, new ArrayList<>());

        verify(resourceLimitService, org.mockito.Mockito.times(2))
                .checkVolumeResourceLimit(eq(owner), eq(true), anyLong(), any(DiskOffering.class), any());
    }

    private UnmanagedInstanceTO.Disk makeDisk(String diskId) {
        UnmanagedInstanceTO.Disk d = new UnmanagedInstanceTO.Disk();
        d.setDiskId(diskId);
        d.setLabel("Label-" + diskId);
        d.setController("scsi");
        d.setCapacity(DISK_BYTES);
        d.setDatastoreName("DS");
        d.setDatastoreHost("ds-host");
        d.setDatastorePath("[ds] vm/" + diskId + ".vmdk");
        d.setDatastoreType("NFS");
        return d;
    }
}
