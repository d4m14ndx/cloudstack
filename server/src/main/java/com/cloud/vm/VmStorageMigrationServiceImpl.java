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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.snapshot.SnapshotHelper;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.ScopeType;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VmStorageMigrationServiceImpl implements VmStorageMigrationService {

    @Inject
    private VmMigrationValidator vmMigrationValidator;
    @Inject
    private VolumeDao _volsDao;
    @Inject
    private PrimaryDataStoreDao _storagePoolDao;
    @Inject
    protected SnapshotHelper snapshotHelper;
    @Inject
    private StorageManager storageManager;
    @Inject
    private VirtualMachineManager _itMgr;
    @Inject
    private UserVmDao _vmDao;
    @Inject
    private VMInstanceDao _vmInstanceDao;

    @Override
    public VirtualMachine vmStorageMigration(Long vmId, StoragePool destPool) {
        VMInstanceVO vm = preVmStorageMigrationCheck(vmId);
        Map<Long, Long> volumeToPoolIds = new HashMap<>();
        checkDestinationHypervisorType(destPool, vm);
        checkIfDestinationPoolHasSameStorageAccessGroups(destPool, vm);
        List<VolumeVO> volumes = _volsDao.findByInstance(vm.getId());
        StoragePoolVO destinationPoolVo = _storagePoolDao.findById(destPool.getId());
        Long destPoolPodId = ScopeType.CLUSTER.equals(destinationPoolVo.getScope()) || ScopeType.HOST.equals(destinationPoolVo.getScope()) ?
                destinationPoolVo.getPodId() : null;
        for (VolumeVO volume : volumes) {
            snapshotHelper.checkKvmVolumeSnapshotsOnlyInPrimaryStorage(volume, vm.getHypervisorType());
            if (!VirtualMachine.Type.User.equals(vm.getType())) {
                // Migrate within same pod as source storage and same cluster for all disks only. Hypervisor check already done
                StoragePoolVO pool = _storagePoolDao.findById(volume.getPoolId());
                if (destPoolPodId != null &&
                        (ScopeType.CLUSTER.equals(pool.getScope()) || ScopeType.HOST.equals(pool.getScope())) &&
                        !destPoolPodId.equals(pool.getPodId())) {
                    throw new InvalidParameterValueException("Storage migration of non-user VMs cannot be done between storage pools of different pods");
                }
            }
            Pair<Boolean, String> checkResult = storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(destPool, volume);
            if (!checkResult.first()) {
                throw new CloudRuntimeException(String.format("Storage suitability check failed for volume %s with error, %s", volume, checkResult.second()));
            }
            volumeToPoolIds.put(volume.getId(), destPool.getId());
        }
        _itMgr.storageMigration(vm.getUuid(), volumeToPoolIds);
        return findMigratedVm(vm.getId(), vm.getType());
    }

    @Override
    public VirtualMachine vmStorageMigration(Long vmId, Map<String, String> volumeToPool) {
        VMInstanceVO vm = preVmStorageMigrationCheck(vmId);
        Map<Long, Long> volumeToPoolIds = new HashMap<>();
        Long poolClusterId = null;
        for (Map.Entry<String, String> entry : volumeToPool.entrySet()) {
            VolumeVO volume = _volsDao.findByUuid(entry.getKey());
            snapshotHelper.checkKvmVolumeSnapshotsOnlyInPrimaryStorage(volume, vm.getHypervisorType());
            StoragePoolVO pool = _storagePoolDao.findPoolByUUID(entry.getValue());
            if (poolClusterId != null &&
                    (ScopeType.CLUSTER.equals(pool.getScope()) || ScopeType.HOST.equals(pool.getScope())) &&
                    !poolClusterId.equals(pool.getClusterId())) {
                throw new InvalidParameterValueException("VM's disk cannot be migrated, input destination storage pools belong to different clusters");
            }
            if (pool.getClusterId() != null) {
                poolClusterId = pool.getClusterId();
            }
            checkDestinationHypervisorType(pool, vm);
            Pair<Boolean, String> checkResult = storageManager.checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups(pool, volume);
            if (!checkResult.first()) {
                throw new CloudRuntimeException(String.format("Storage suitability check failed for volume %s with error %s", volume, checkResult.second()));
            }

            volumeToPoolIds.put(volume.getId(), pool.getId());
        }
        _itMgr.storageMigration(vm.getUuid(), volumeToPoolIds);
        return findMigratedVm(vm.getId(), vm.getType());
    }

    private VMInstanceVO preVmStorageMigrationCheck(Long vmId) {
        return vmMigrationValidator.preVmStorageMigrationCheck(vmId);
    }

    private void checkIfDestinationPoolHasSameStorageAccessGroups(StoragePool destPool, VMInstanceVO vm) {
        vmMigrationValidator.checkIfDestinationPoolHasSameStorageAccessGroups(destPool, vm);
    }

    private void checkDestinationHypervisorType(StoragePool destPool, VMInstanceVO vm) {
        vmMigrationValidator.checkDestinationHypervisorType(destPool, vm);
    }

    private VirtualMachine findMigratedVm(long vmId, VirtualMachine.Type vmType) {
        if (VirtualMachine.Type.User.equals(vmType)) {
            return _vmDao.findById(vmId);
        }
        return _vmInstanceDao.findById(vmId);
    }
}
