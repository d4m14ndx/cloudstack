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

package com.cloud.vm;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.cloudstack.engine.subsystem.api.storage.StoragePoolAllocator;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.dao.ClusterDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class VmVolumeMigrationPlanningServiceImplTest {

    @InjectMocks
    private VmVolumeMigrationPlanningServiceImpl service;

    @Mock
    private VolumeDao volumeDao;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private StoragePoolHostDao poolHostDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private ClusterDao clusterDao;
    @Mock
    private DiskOfferingDao diskOfferingDao;

    @Mock
    private VirtualMachineProfile profileMock;
    @Mock
    private StoragePoolVO currentPoolMock;
    @Mock
    private VolumeVO volumeMock;
    @Mock
    private HostVO hostMock;

    private final long hostId = 1L;
    private final long currentPoolId = 10L;
    private final long targetPoolId = 20L;
    private final long clusterAId = 100L;
    private final long clusterBId = 200L;

    @Before
    public void setUp() {
        when(hostMock.getId()).thenReturn(hostId);
        when(currentPoolMock.getId()).thenReturn(currentPoolId);
        when(volumeMock.getPoolId()).thenReturn(currentPoolId);
        when(volumeMock.getUuid()).thenReturn(UUID.randomUUID().toString());
        when(currentPoolMock.getUuid()).thenReturn(UUID.randomUUID().toString());

        List<StoragePoolAllocator> allocators = new ArrayList<>();
        service.setStoragePoolAllocators(allocators);
    }

    // -------------------------------------------------------------------------
    // isStorageCrossClusterMigration (4 cases)
    // -------------------------------------------------------------------------

    @Test
    public void isStorageCrossClusterMigration_nullClusterId_returnsFalse() {
        assertFalse(service.isStorageCrossClusterMigration(null, currentPoolMock));
        verify(currentPoolMock, never()).getScope();
    }

    @Test
    public void isStorageCrossClusterMigration_nonClusterScopePool_returnsFalse() {
        when(currentPoolMock.getScope()).thenReturn(ScopeType.ZONE);
        assertFalse(service.isStorageCrossClusterMigration(clusterAId, currentPoolMock));
    }

    @Test
    public void isStorageCrossClusterMigration_sameCluster_returnsFalse() {
        when(currentPoolMock.getScope()).thenReturn(ScopeType.CLUSTER);
        when(currentPoolMock.getClusterId()).thenReturn(clusterAId);
        assertFalse(service.isStorageCrossClusterMigration(clusterAId, currentPoolMock));
    }

    @Test
    public void isStorageCrossClusterMigration_differentCluster_returnsTrue() {
        when(currentPoolMock.getScope()).thenReturn(ScopeType.CLUSTER);
        when(currentPoolMock.getClusterId()).thenReturn(clusterBId);
        assertTrue(service.isStorageCrossClusterMigration(clusterAId, currentPoolMock));
    }

    // -------------------------------------------------------------------------
    // executeManagedStorageChecksWhenTargetStoragePoolProvided (3 cases)
    // -------------------------------------------------------------------------

    @Test
    public void executeManagedStorageChecksWhenTargetStoragePoolProvided_nonManaged_noOp() {
        StoragePoolVO targetPool = mock(StoragePoolVO.class);
        when(currentPoolMock.isManaged()).thenReturn(false);
        // Should not throw
        service.executeManagedStorageChecksWhenTargetStoragePoolProvided(currentPoolMock, volumeMock, targetPool);
    }

    @Test
    public void executeManagedStorageChecksWhenTargetStoragePoolProvided_managedSamePool_noOp() {
        when(currentPoolMock.isManaged()).thenReturn(true);
        when(currentPoolMock.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        StoragePoolVO targetPool = mock(StoragePoolVO.class);
        when(targetPool.getId()).thenReturn(currentPoolId);
        // Same pool id — should not throw
        service.executeManagedStorageChecksWhenTargetStoragePoolProvided(currentPoolMock, volumeMock, targetPool);
    }

    @Test
    public void executeManagedStorageChecksWhenTargetStoragePoolProvided_managedDifferentPool_throws() {
        when(currentPoolMock.isManaged()).thenReturn(true);
        when(currentPoolMock.getPoolType()).thenReturn(Storage.StoragePoolType.RBD);
        StoragePoolVO targetPool = mock(StoragePoolVO.class);
        when(targetPool.getId()).thenReturn(targetPoolId);
        when(storagePoolDao.getDetails(currentPoolId)).thenReturn(null);

        assertThrows(CloudRuntimeException.class, () ->
                service.executeManagedStorageChecksWhenTargetStoragePoolProvided(currentPoolMock, volumeMock, targetPool));
    }

    // -------------------------------------------------------------------------
    // executeManagedStorageChecksWhenTargetStoragePoolNotProvided (3 cases)
    // -------------------------------------------------------------------------

    @Test
    public void executeManagedStorageChecksWhenTargetStoragePoolNotProvided_nonManaged_noOp() {
        when(currentPoolMock.isManaged()).thenReturn(false);
        // Should not throw
        service.executeManagedStorageChecksWhenTargetStoragePoolNotProvided(hostMock, currentPoolMock, volumeMock);
    }

    @Test
    public void executeManagedStorageChecksWhenTargetStoragePoolNotProvided_managedConnected_noOp() {
        when(currentPoolMock.isManaged()).thenReturn(true);
        when(poolHostDao.findByPoolHost(currentPoolId, hostId)).thenReturn(mock(com.cloud.storage.StoragePoolHostVO.class));
        // Has access — should not throw
        service.executeManagedStorageChecksWhenTargetStoragePoolNotProvided(hostMock, currentPoolMock, volumeMock);
    }

    @Test
    public void executeManagedStorageChecksWhenTargetStoragePoolNotProvided_managedDisconnected_throws() {
        when(currentPoolMock.isManaged()).thenReturn(true);
        when(poolHostDao.findByPoolHost(currentPoolId, hostId)).thenReturn(null);
        when(hostMock.getUuid()).thenReturn(UUID.randomUUID().toString());

        assertThrows(CloudRuntimeException.class, () ->
                service.executeManagedStorageChecksWhenTargetStoragePoolNotProvided(hostMock, currentPoolMock, volumeMock));
    }

    // -------------------------------------------------------------------------
    // findVolumesThatWereNotMappedByTheUser (2 cases)
    // -------------------------------------------------------------------------

    @Test
    public void findVolumesThatWereNotMappedByTheUser_partialMap_returnsUnmapped() {
        VolumeVO vol1 = mock(VolumeVO.class);
        VolumeVO vol2 = mock(VolumeVO.class);
        List<VolumeVO> allVolumes = new ArrayList<>();
        allVolumes.add(vol1);
        allVolumes.add(vol2);

        when(profileMock.getId()).thenReturn(1L);
        when(volumeDao.findUsableVolumesForInstance(1L)).thenReturn(allVolumes);

        Map<Volume, StoragePool> alreadyMapped = new HashMap<>();
        alreadyMapped.put(vol1, mock(StoragePool.class));

        List<Volume> unmapped = service.findVolumesThatWereNotMappedByTheUser(profileMock, alreadyMapped);
        assertEquals(1, unmapped.size());
        assertTrue(unmapped.contains(vol2));
    }

    @Test
    public void findVolumesThatWereNotMappedByTheUser_emptyMap_returnsAll() {
        VolumeVO vol1 = mock(VolumeVO.class);
        VolumeVO vol2 = mock(VolumeVO.class);
        List<VolumeVO> allVolumes = new ArrayList<>();
        allVolumes.add(vol1);
        allVolumes.add(vol2);

        when(profileMock.getId()).thenReturn(2L);
        when(volumeDao.findUsableVolumesForInstance(2L)).thenReturn(allVolumes);

        List<Volume> unmapped = service.findVolumesThatWereNotMappedByTheUser(profileMock, new HashMap<>());
        assertEquals(2, unmapped.size());
    }

    // -------------------------------------------------------------------------
    // buildMapUsingUserInformation (2 cases)
    // -------------------------------------------------------------------------

    @Test
    public void buildMapUsingUserInformation_emptyInput_returnsEmptyMap() {
        Map<Volume, StoragePool> result = service.buildMapUsingUserInformation(profileMock, hostMock, new HashMap<>());
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    @Test
    public void buildMapUsingUserInformation_validEntry_addsToMap() {
        long volumeId = 55L;
        long poolId = 66L;
        VolumeVO vol = mock(VolumeVO.class);
        StoragePoolVO targetPool = mock(StoragePoolVO.class);
        StoragePoolVO currentPool = mock(StoragePoolVO.class);

        when(vol.getPoolId()).thenReturn(poolId);
        when(vol.getUuid()).thenReturn(UUID.randomUUID().toString());
        when(targetPool.getId()).thenReturn(poolId);
        when(currentPool.getId()).thenReturn(poolId);
        when(currentPool.isManaged()).thenReturn(false);
        when(targetPool.getId()).thenReturn(poolId);

        when(volumeDao.findById(volumeId)).thenReturn(vol);
        when(storagePoolDao.findById(poolId)).thenReturn(targetPool).thenReturn(currentPool);
        // Host access check — pool accessible
        when(poolHostDao.findByPoolHost(anyLong(), anyLong())).thenReturn(mock(com.cloud.storage.StoragePoolHostVO.class));

        Map<Long, Long> userMap = new HashMap<>();
        userMap.put(volumeId, poolId);

        Map<Volume, StoragePool> result = service.buildMapUsingUserInformation(profileMock, hostMock, userMap);
        assertFalse(result.isEmpty());
        assertTrue(result.containsKey(vol));
    }

    @Test
    public void createMappingVolumeAndStoragePool_planWithoutHostMapsUserDefinedVolumesWithoutHostAccessCheck() {
        long volumeId = 77L;
        long poolId = 88L;
        long vmId = 99L;
        VolumeVO vol = mock(VolumeVO.class);
        StoragePoolVO targetPool = mock(StoragePoolVO.class);
        StoragePoolVO currentPool = mock(StoragePoolVO.class);
        DataCenterDeployment plan = new DataCenterDeployment(1L, 2L, 3L, null, null, null);

        when(profileMock.getId()).thenReturn(vmId);
        when(vol.getPoolId()).thenReturn(currentPoolId);
        when(targetPool.getId()).thenReturn(poolId);
        when(currentPool.getId()).thenReturn(currentPoolId);
        when(currentPool.isManaged()).thenReturn(false);
        when(volumeDao.findById(volumeId)).thenReturn(vol);
        when(volumeDao.findUsableVolumesForInstance(vmId)).thenReturn(List.of(vol));
        when(storagePoolDao.findById(poolId)).thenReturn(targetPool);
        when(storagePoolDao.findById(currentPoolId)).thenReturn(currentPool);

        Map<Long, Long> userMap = new HashMap<>();
        userMap.put(volumeId, poolId);

        Map<Volume, StoragePool> result = service.createMappingVolumeAndStoragePool(profileMock, plan, userMap);

        assertEquals(1, result.size());
        assertEquals(targetPool, result.get(vol));
        verify(poolHostDao, never()).findByPoolHost(anyLong(), anyLong());
    }

    // -------------------------------------------------------------------------
    // getCandidateStoragePoolsToMigrateLocalVolume (2 cases)
    // -------------------------------------------------------------------------

    @Test
    public void getCandidateStoragePoolsToMigrateLocalVolume_localVolume_includesLocalPools() {
        StoragePoolAllocator allocator = mock(StoragePoolAllocator.class);
        List<StoragePoolAllocator> allocators = new ArrayList<>();
        allocators.add(allocator);
        service.setStoragePoolAllocators(allocators);

        DiskOfferingVO diskOffering = mock(DiskOfferingVO.class);
        StoragePool localPool = mock(StoragePool.class);
        DataCenterDeployment plan = mock(DataCenterDeployment.class);

        when(volumeMock.getDiskOfferingId()).thenReturn(5L);
        when(diskOfferingDao.findById(5L)).thenReturn(diskOffering);
        when(storagePoolDao.findById(currentPoolId)).thenReturn(currentPoolMock);
        when(currentPoolMock.isLocal()).thenReturn(true);
        when(profileMock.getHypervisorType()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);

        List<StoragePool> fromAllocator = new ArrayList<>();
        fromAllocator.add(localPool);
        when(localPool.isLocal()).thenReturn(true);
        when(allocator.allocateToPool(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any(), Mockito.anyInt()))
                .thenReturn(fromAllocator);

        List<StoragePool> result = service.getCandidateStoragePoolsToMigrateLocalVolume(profileMock, plan, volumeMock);
        assertFalse(result.isEmpty());
        assertTrue(result.contains(localPool));
    }

    @Test
    public void getCandidateStoragePoolsToMigrateLocalVolume_noAllocators_returnsEmpty() {
        service.setStoragePoolAllocators(new ArrayList<>());

        DiskOfferingVO diskOffering = mock(DiskOfferingVO.class);
        DataCenterDeployment plan = mock(DataCenterDeployment.class);

        when(volumeMock.getDiskOfferingId()).thenReturn(6L);
        when(diskOfferingDao.findById(6L)).thenReturn(diskOffering);
        when(storagePoolDao.findById(currentPoolId)).thenReturn(currentPoolMock);
        when(currentPoolMock.isLocal()).thenReturn(false);
        when(profileMock.getHypervisorType()).thenReturn(com.cloud.hypervisor.Hypervisor.HypervisorType.KVM);

        List<StoragePool> result = service.getCandidateStoragePoolsToMigrateLocalVolume(profileMock, plan, volumeMock);
        assertTrue(result.isEmpty());
    }
}
