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

import java.util.Arrays;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

/**
 * Pre-flight validation helpers for the volume attach/detach flows —
 * extracted from {@link VolumeApiServiceImpl}.
 *
 * @see VolumeAttachValidator
 */
@Component
public class VolumeAttachValidatorImpl implements VolumeAttachValidator {
    private static final Logger LOG = LogManager.getLogger(VolumeAttachValidatorImpl.class);

    @Inject
    private VMSnapshotDao vmSnapshotDao;
    @Inject
    private DataCenterDao dataCenterDao;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private PrimaryDataStoreDao storagePoolDao;
    @Inject
    private VolumeDao volumeDao;

    @Override
    public void checkForMatchingHypervisorTypesIf(boolean checkNeeded,
                                                  HypervisorType rootDiskHyperType,
                                                  HypervisorType volumeToAttachHyperType) {
        if (checkNeeded && volumeToAttachHyperType != HypervisorType.None && rootDiskHyperType != volumeToAttachHyperType) {
            throw new InvalidParameterValueException("Can't attach a volume created by: " + volumeToAttachHyperType + " to a " + rootDiskHyperType + " vm");
        }
    }

    @Override
    public void checkForVMSnapshots(Long vmId, UserVmVO vm) {
        // if target VM has associated VM snapshots
        List<VMSnapshotVO> vmSnapshots = vmSnapshotDao.findByVm(vmId);
        if (vmSnapshots.size() > 0) {
            throw new InvalidParameterValueException(String.format("Unable to attach volume to Instance %s/%s, please specify an Instance that does not have Instance Snapshots", vm.getName(), vm.getUuid()));
        }
    }

    @Override
    public void excludeLocalStorageIfNeeded(VolumeInfo volumeToAttach) {
        DataCenterVO dataCenter = dataCenterDao.findById(volumeToAttach.getDataCenterId());
        if (!dataCenter.isLocalStorageEnabled()) {
            DiskOfferingVO diskOffering = diskOfferingDao.findById(volumeToAttach.getDiskOfferingId());
            if (diskOffering.isUseLocalStorage()) {
                throw new InvalidParameterValueException("Zone is not configured to use local storage but volume's disk offering " + diskOffering.getName() + " uses it");
            }
        }
    }

    @Override
    public void checkDeviceId(Long deviceId, VolumeInfo volumeToAttach, UserVmVO vm) {
        if (deviceId != null && deviceId.longValue() == 0) {
            validateRootVolumeDetachAttach(volumeDao.findById(volumeToAttach.getId()), vm);
            if (!volumeDao.findByInstanceAndDeviceId(vm.getId(), 0).isEmpty()) {
                throw new InvalidParameterValueException("Vm already has root volume attached to it");
            }
        }
    }

    @Override
    public void validateRootVolumeDetachAttach(VolumeVO volume, UserVmVO vm) {
        if (!(vm.getHypervisorType() == HypervisorType.XenServer || vm.getHypervisorType() == HypervisorType.VMware || vm.getHypervisorType() == HypervisorType.KVM
                || vm.getHypervisorType() == HypervisorType.Simulator)) {
            throw new InvalidParameterValueException("Root volume detach is not supported for hypervisor type " + vm.getHypervisorType());
        }
        if (!(vm.getState() == State.Stopped) || (vm.getState() == State.Destroyed)) {
            throw new InvalidParameterValueException("Root volume detach can happen only when vm is in states: " + State.Stopped.toString() + " or " + State.Destroyed.toString());
        }

        if (volume.getPoolId() != null) {
            StoragePoolVO pool = storagePoolDao.findById(volume.getPoolId());
            if (pool.isManaged()) {
                throw new InvalidParameterValueException("Root volume detach is not supported for Managed DataStores");
            }
        }
    }

    @Override
    public Long getRequiredPrimaryStorageSizeForVolumeAttach(List<String> resourceLimitStorageTags, VolumeInfo volumeToAttach) {
        if (CollectionUtils.isEmpty(resourceLimitStorageTags) || Arrays.asList(Volume.State.Allocated, Volume.State.Ready).contains(volumeToAttach.getState())) {
            return 0L;
        }
        return volumeToAttach.getSize();
    }

    @Override
    public void checkForBackups(UserVmVO vm, boolean attach) {
        if ((vm.getBackupOfferingId() == null || CollectionUtils.isEmpty(vm.getBackupVolumeList())) || BooleanUtils.isTrue(BackupManager.BackupEnableAttachDetachVolumes.value())) {
            return;
        }
        String errorMsg = String.format("Unable to detach volume, cannot detach volume from a VM that has backups. First remove the VM from the backup offering or "
                + "set the global configuration '%s' to true.", BackupManager.BackupEnableAttachDetachVolumes.key());
        if (attach) {
            errorMsg = String.format("Unable to attach volume, please specify a VM that does not have any backups or set the global configuration "
                    + "'%s' to true.", BackupManager.BackupEnableAttachDetachVolumes.key());
        }
        throw new InvalidParameterValueException(errorMsg);
    }
}
