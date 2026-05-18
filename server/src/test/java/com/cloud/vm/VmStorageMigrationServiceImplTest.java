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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.cloudstack.snapshot.SnapshotHelper;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.ScopeType;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmStorageMigrationServiceImplTest {

    private static final long VM_ID = 42L;
    private static final String VM_UUID = "vm-uuid";
    private static final long DEST_POOL_ID = 200L;

    @Mock private VmMigrationValidator vmMigrationValidator;
    @Mock private VolumeDao volsDao;
    @Mock private PrimaryDataStoreDao storagePoolDao;
    @Mock private SnapshotHelper snapshotHelper;
    @Mock private StorageManager storageManager;
    @Mock private VirtualMachineManager itMgr;
    @Mock private UserVmDao vmDao;
    @Mock private VMInstanceDao vmInstanceDao;

    @InjectMocks
    private VmStorageMigrationServiceImpl service;

    @Test
    public void vmStorageMigrationToSinglePoolDelegatesPrecheckAndDestinationValidation() {
        VMInstanceVO vm = stubVm(VirtualMachine.Type.User);
        StoragePool destPool = stubDestinationPool();
        StoragePoolVO destinationPoolVo = stubPoolVo(DEST_POOL_ID, ScopeType.CLUSTER, 10L, 100L);
        VolumeVO volume = stubVolume(1L, 101L, "volume-1");
        stubSinglePoolSuccess(vm, destPool, destinationPoolVo, volume);

        service.vmStorageMigration(VM_ID, destPool);

        verify(vmMigrationValidator).preVmStorageMigrationCheck(VM_ID);
        verify(vmMigrationValidator).checkDestinationHypervisorType(destPool, vm);
        verify(vmMigrationValidator).checkIfDestinationPoolHasSameStorageAccessGroups(destPool, vm);
    }

    @Test
    public void vmStorageMigrationToSinglePoolMapsEveryVolumeToDestinationPool() {
        VMInstanceVO vm = stubVm(VirtualMachine.Type.User);
        StoragePool destPool = stubDestinationPool();
        StoragePoolVO destinationPoolVo = stubPoolVo(DEST_POOL_ID, ScopeType.CLUSTER, 10L, 100L);
        VolumeVO volume1 = stubVolume(1L, 101L, "volume-1");
        VolumeVO volume2 = stubVolume(2L, 102L, "volume-2");
        stubSinglePoolSuccess(vm, destPool, destinationPoolVo, volume1, volume2);
        Map<Long, Long> expectedMap = new LinkedHashMap<>();
        expectedMap.put(1L, DEST_POOL_ID);
        expectedMap.put(2L, DEST_POOL_ID);

        service.vmStorageMigration(VM_ID, destPool);

        verify(itMgr).storageMigration(VM_UUID, expectedMap);
    }

    @Test
    public void vmStorageMigrationToSinglePoolChecksKvmSnapshotsForEveryVolume() {
        VMInstanceVO vm = stubVm(VirtualMachine.Type.User);
        StoragePool destPool = stubDestinationPool();
        StoragePoolVO destinationPoolVo = stubPoolVo(DEST_POOL_ID, ScopeType.CLUSTER, 10L, 100L);
        VolumeVO volume1 = stubVolume(1L, 101L, "volume-1");
        VolumeVO volume2 = stubVolume(2L, 102L, "volume-2");
        stubSinglePoolSuccess(vm, destPool, destinationPoolVo, volume1, volume2);

        service.vmStorageMigration(VM_ID, destPool);

        verify(snapshotHelper).checkKvmVolumeSnapshotsOnlyInPrimaryStorage(volume1, HypervisorType.KVM);
        verify(snapshotHelper).checkKvmVolumeSnapshotsOnlyInPrimaryStorage(volume2, HypervisorType.KVM);
    }

    @Test
    public void vmStorageMigrationToSinglePoolRejectsNonUserVmAcrossPods() {
        stubVm(VirtualMachine.Type.ConsoleProxy);
        StoragePool destPool = stubDestinationPool();
        StoragePoolVO destinationPoolVo = stubPoolVo(DEST_POOL_ID, ScopeType.CLUSTER, 20L, 200L);
        VolumeVO volume = stubVolume(1L, 101L, "volume-1");
        StoragePoolVO sourcePool = stubPoolVo(101L, ScopeType.CLUSTER, 10L, 100L);
        when(volsDao.findByInstance(VM_ID)).thenReturn(Collections.singletonList(volume));
        when(storagePoolDao.findById(DEST_POOL_ID)).thenReturn(destinationPoolVo);
        when(storagePoolDao.findById(101L)).thenReturn(sourcePool);

        assertThrows(InvalidParameterValueException.class, () -> service.vmStorageMigration(VM_ID, destPool));
    }

    @Test
    public void vmStorageMigrationToSinglePoolAllowsNonUserVmWhenDestinationIsZoneWide() {
        stubVm(VirtualMachine.Type.ConsoleProxy);
        StoragePool destPool = stubDestinationPool();
        StoragePoolVO destinationPoolVo = stubPoolVo(DEST_POOL_ID, ScopeType.ZONE, null, null);
        VolumeVO volume = stubVolume(1L, 101L, "volume-1");
        StoragePoolVO sourcePool = stubPoolVo(101L, ScopeType.CLUSTER, 10L, 100L);
        when(volsDao.findByInstance(VM_ID)).thenReturn(Collections.singletonList(volume));
        when(storagePoolDao.findById(DEST_POOL_ID)).thenReturn(destinationPoolVo);
        when(storagePoolDao.findById(101L)).thenReturn(sourcePool);
        when(storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(destPool, volume))
                .thenReturn(new Pair<>(true, null));
        VMInstanceVO migratedVm = mock(VMInstanceVO.class);
        when(vmInstanceDao.findById(VM_ID)).thenReturn(migratedVm);

        VirtualMachine result = service.vmStorageMigration(VM_ID, destPool);

        assertSame(migratedVm, result);
        verify(itMgr).storageMigration(VM_UUID, Collections.singletonMap(1L, DEST_POOL_ID));
    }

    @Test
    public void vmStorageMigrationToSinglePoolRejectsFailedStorageSuitability() {
        stubVm(VirtualMachine.Type.User);
        StoragePool destPool = stubDestinationPool();
        StoragePoolVO destinationPoolVo = stubPoolVo(DEST_POOL_ID, ScopeType.CLUSTER, 10L, 100L);
        VolumeVO volume = stubVolume(1L, 101L, "volume-1");
        when(volsDao.findByInstance(VM_ID)).thenReturn(Collections.singletonList(volume));
        when(storagePoolDao.findById(DEST_POOL_ID)).thenReturn(destinationPoolVo);
        when(storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(destPool, volume))
                .thenReturn(new Pair<>(false, "not enough capacity"));

        assertThrows(CloudRuntimeException.class, () -> service.vmStorageMigration(VM_ID, destPool));
        verify(itMgr, never()).storageMigration(any(), any());
    }

    @Test
    public void vmStorageMigrationToSinglePoolReturnsUserVmLookupForUserType() {
        VMInstanceVO vm = stubVm(VirtualMachine.Type.User);
        StoragePool destPool = stubDestinationPool();
        StoragePoolVO destinationPoolVo = stubPoolVo(DEST_POOL_ID, ScopeType.CLUSTER, 10L, 100L);
        VolumeVO volume = stubVolume(1L, 101L, "volume-1");
        stubSinglePoolSuccess(vm, destPool, destinationPoolVo, volume);
        UserVmVO expectedVm = mock(UserVmVO.class);
        when(vmDao.findById(VM_ID)).thenReturn(expectedVm);

        VirtualMachine result = service.vmStorageMigration(VM_ID, destPool);

        assertSame(expectedVm, result);
        verify(vmInstanceDao, never()).findById(VM_ID);
    }

    @Test
    public void vmStorageMigrationToSinglePoolReturnsInstanceLookupForNonUserType() {
        stubVm(VirtualMachine.Type.ConsoleProxy);
        StoragePool destPool = stubDestinationPool();
        StoragePoolVO destinationPoolVo = stubPoolVo(DEST_POOL_ID, ScopeType.CLUSTER, 10L, 100L);
        VolumeVO volume = stubVolume(1L, 101L, "volume-1");
        StoragePoolVO sourcePool = stubPoolVo(101L, ScopeType.CLUSTER, 10L, 100L);
        when(volsDao.findByInstance(VM_ID)).thenReturn(Collections.singletonList(volume));
        when(storagePoolDao.findById(DEST_POOL_ID)).thenReturn(destinationPoolVo);
        when(storagePoolDao.findById(101L)).thenReturn(sourcePool);
        when(storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(destPool, volume))
                .thenReturn(new Pair<>(true, null));
        VMInstanceVO expectedVm = mock(VMInstanceVO.class);
        when(vmInstanceDao.findById(VM_ID)).thenReturn(expectedVm);

        VirtualMachine result = service.vmStorageMigration(VM_ID, destPool);

        assertSame(expectedVm, result);
        verify(vmDao, never()).findById(VM_ID);
    }

    @Test
    public void vmStorageMigrationWithVolumePoolMapResolvesVolumesAndPoolsByUuid() {
        stubVm(VirtualMachine.Type.User);
        VolumeVO volume = stubVolume(1L, 101L, "volume-uuid");
        StoragePoolVO pool = stubPoolVo(201L, ScopeType.CLUSTER, 10L, 100L);
        Map<String, String> volumeToPool = new LinkedHashMap<>();
        volumeToPool.put("volume-uuid", "pool-uuid");
        stubMapEntry("volume-uuid", volume, "pool-uuid", pool);
        when(storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(pool, volume))
                .thenReturn(new Pair<>(true, null));
        when(vmDao.findById(VM_ID)).thenReturn(mock(UserVmVO.class));

        service.vmStorageMigration(VM_ID, volumeToPool);

        verify(volsDao).findByUuid("volume-uuid");
        verify(storagePoolDao).findPoolByUUID("pool-uuid");
    }

    @Test
    public void vmStorageMigrationWithVolumePoolMapBuildsVolumeToPoolIdMap() {
        stubVm(VirtualMachine.Type.User);
        VolumeVO volume1 = stubVolume(1L, 101L, "volume-1");
        VolumeVO volume2 = stubVolume(2L, 102L, "volume-2");
        StoragePoolVO pool1 = stubPoolVo(201L, ScopeType.CLUSTER, 10L, 100L);
        StoragePoolVO pool2 = stubPoolVo(202L, ScopeType.CLUSTER, 10L, 100L);
        Map<String, String> volumeToPool = new LinkedHashMap<>();
        volumeToPool.put("volume-1", "pool-1");
        volumeToPool.put("volume-2", "pool-2");
        stubMapEntry("volume-1", volume1, "pool-1", pool1);
        stubMapEntry("volume-2", volume2, "pool-2", pool2);
        when(storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(pool1, volume1))
                .thenReturn(new Pair<>(true, null));
        when(storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(pool2, volume2))
                .thenReturn(new Pair<>(true, null));
        when(vmDao.findById(VM_ID)).thenReturn(mock(UserVmVO.class));
        Map<Long, Long> expectedMap = new LinkedHashMap<>();
        expectedMap.put(1L, 201L);
        expectedMap.put(2L, 202L);

        service.vmStorageMigration(VM_ID, volumeToPool);

        verify(itMgr).storageMigration(VM_UUID, expectedMap);
    }

    @Test
    public void vmStorageMigrationWithVolumePoolMapRejectsClusterOrHostPoolsFromDifferentClusters() {
        stubVm(VirtualMachine.Type.User);
        VolumeVO volume1 = stubVolume(1L, 101L, "volume-1");
        VolumeVO volume2 = stubVolume(2L, 102L, "volume-2");
        StoragePoolVO pool1 = stubPoolVo(201L, ScopeType.CLUSTER, 10L, 100L);
        StoragePoolVO pool2 = stubPoolVo(202L, ScopeType.HOST, 20L, 200L);
        Map<String, String> volumeToPool = new LinkedHashMap<>();
        volumeToPool.put("volume-1", "pool-1");
        volumeToPool.put("volume-2", "pool-2");
        stubMapEntry("volume-1", volume1, "pool-1", pool1);
        stubMapEntry("volume-2", volume2, "pool-2", pool2);
        when(storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(pool1, volume1))
                .thenReturn(new Pair<>(true, null));

        assertThrows(InvalidParameterValueException.class, () -> service.vmStorageMigration(VM_ID, volumeToPool));
        verify(itMgr, never()).storageMigration(any(), any());
    }

    @Test
    public void vmStorageMigrationWithVolumePoolMapAllowsZoneWidePoolsWithoutChangingClusterLock() {
        stubVm(VirtualMachine.Type.User);
        VolumeVO volume1 = stubVolume(1L, 101L, "volume-1");
        VolumeVO volume2 = stubVolume(2L, 102L, "volume-2");
        StoragePoolVO pool1 = stubPoolVo(201L, ScopeType.ZONE, null, null);
        StoragePoolVO pool2 = stubPoolVo(202L, ScopeType.CLUSTER, 10L, 100L);
        Map<String, String> volumeToPool = new LinkedHashMap<>();
        volumeToPool.put("volume-1", "pool-1");
        volumeToPool.put("volume-2", "pool-2");
        stubMapEntry("volume-1", volume1, "pool-1", pool1);
        stubMapEntry("volume-2", volume2, "pool-2", pool2);
        when(storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(pool1, volume1))
                .thenReturn(new Pair<>(true, null));
        when(storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(pool2, volume2))
                .thenReturn(new Pair<>(true, null));
        when(vmDao.findById(VM_ID)).thenReturn(mock(UserVmVO.class));
        Map<Long, Long> expectedMap = new LinkedHashMap<>();
        expectedMap.put(1L, 201L);
        expectedMap.put(2L, 202L);

        service.vmStorageMigration(VM_ID, volumeToPool);

        verify(itMgr).storageMigration(VM_UUID, expectedMap);
    }

    @Test
    public void vmStorageMigrationWithVolumePoolMapChecksDestinationHypervisorForEachPool() {
        VMInstanceVO vm = stubVm(VirtualMachine.Type.User);
        VolumeVO volume1 = stubVolume(1L, 101L, "volume-1");
        VolumeVO volume2 = stubVolume(2L, 102L, "volume-2");
        StoragePoolVO pool1 = stubPoolVo(201L, ScopeType.CLUSTER, 10L, 100L);
        StoragePoolVO pool2 = stubPoolVo(202L, ScopeType.CLUSTER, 10L, 100L);
        Map<String, String> volumeToPool = new LinkedHashMap<>();
        volumeToPool.put("volume-1", "pool-1");
        volumeToPool.put("volume-2", "pool-2");
        stubMapEntry("volume-1", volume1, "pool-1", pool1);
        stubMapEntry("volume-2", volume2, "pool-2", pool2);
        when(storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(pool1, volume1))
                .thenReturn(new Pair<>(true, null));
        when(storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(pool2, volume2))
                .thenReturn(new Pair<>(true, null));
        when(vmDao.findById(VM_ID)).thenReturn(mock(UserVmVO.class));

        service.vmStorageMigration(VM_ID, volumeToPool);

        verify(vmMigrationValidator).checkDestinationHypervisorType(pool1, vm);
        verify(vmMigrationValidator).checkDestinationHypervisorType(pool2, vm);
    }

    @Test
    public void vmStorageMigrationWithVolumePoolMapRejectsFailedStorageSuitability() {
        stubVm(VirtualMachine.Type.User);
        VolumeVO volume = stubVolume(1L, 101L, "volume-uuid");
        StoragePoolVO pool = stubPoolVo(201L, ScopeType.CLUSTER, 10L, 100L);
        Map<String, String> volumeToPool = new LinkedHashMap<>();
        volumeToPool.put("volume-uuid", "pool-uuid");
        stubMapEntry("volume-uuid", volume, "pool-uuid", pool);
        when(storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(pool, volume))
                .thenReturn(new Pair<>(false, "policy mismatch"));

        assertThrows(CloudRuntimeException.class, () -> service.vmStorageMigration(VM_ID, volumeToPool));
        verify(itMgr, never()).storageMigration(any(), any());
    }

    private VMInstanceVO stubVm(VirtualMachine.Type type) {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vmMigrationValidator.preVmStorageMigrationCheck(VM_ID)).thenReturn(vm);
        lenient().when(vm.getId()).thenReturn(VM_ID);
        lenient().when(vm.getUuid()).thenReturn(VM_UUID);
        lenient().when(vm.getType()).thenReturn(type);
        lenient().when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        return vm;
    }

    private StoragePool stubDestinationPool() {
        StoragePool destPool = mock(StoragePool.class);
        when(destPool.getId()).thenReturn(DEST_POOL_ID);
        return destPool;
    }

    private VolumeVO stubVolume(long id, long poolId, String uuid) {
        VolumeVO volume = mock(VolumeVO.class);
        lenient().when(volume.getId()).thenReturn(id);
        lenient().when(volume.getPoolId()).thenReturn(poolId);
        lenient().when(volume.getUuid()).thenReturn(uuid);
        return volume;
    }

    private StoragePoolVO stubPoolVo(long id, ScopeType scope, Long podId, Long clusterId) {
        StoragePoolVO pool = mock(StoragePoolVO.class);
        lenient().when(pool.getId()).thenReturn(id);
        lenient().when(pool.getScope()).thenReturn(scope);
        lenient().when(pool.getPodId()).thenReturn(podId);
        lenient().when(pool.getClusterId()).thenReturn(clusterId);
        return pool;
    }

    private void stubSinglePoolSuccess(VMInstanceVO vm, StoragePool destPool, StoragePoolVO destinationPoolVo, VolumeVO... volumes) {
        when(volsDao.findByInstance(VM_ID)).thenReturn(Arrays.asList(volumes));
        when(storagePoolDao.findById(DEST_POOL_ID)).thenReturn(destinationPoolVo);
        for (VolumeVO volume : volumes) {
            when(storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(destPool, volume))
                    .thenReturn(new Pair<>(true, null));
        }
        if (VirtualMachine.Type.User.equals(vm.getType())) {
            lenient().when(vmDao.findById(VM_ID)).thenReturn(mock(UserVmVO.class));
        } else {
            lenient().when(vmInstanceDao.findById(VM_ID)).thenReturn(mock(VMInstanceVO.class));
        }
    }

    private void stubMapEntry(String volumeUuid, VolumeVO volume, String poolUuid, StoragePoolVO pool) {
        when(volsDao.findByUuid(volumeUuid)).thenReturn(volume);
        when(storagePoolDao.findPoolByUUID(poolUuid)).thenReturn(pool);
    }
}
