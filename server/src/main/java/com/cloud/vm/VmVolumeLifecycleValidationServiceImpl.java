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

import java.util.List;

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class VmVolumeLifecycleValidationServiceImpl implements VmVolumeLifecycleValidationService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private VolumeDao volumeDao;
    @Inject
    private SnapshotDao snapshotDao;

    @Override
    public boolean checkStatusOfVolumeSnapshots(VirtualMachine vm, Volume.Type type) {
        long vmId = vm.getId();
        List<VolumeVO> listVolumes = null;
        if (type == Volume.Type.ROOT) {
            listVolumes = volumeDao.findByInstanceAndType(vmId, type);
        } else if (type == Volume.Type.DATADISK) {
            listVolumes = volumeDao.findByInstanceAndType(vmId, type);
        } else {
            listVolumes = volumeDao.findByInstance(vmId);
        }
        logger.debug("Found {} no. of volumes of type {} for vm with VM ID {}", listVolumes.size(), type, vm);
        for (VolumeVO volume : listVolumes) {
            Long volumeId = volume.getId();
            logger.debug("Checking status of snapshots for Volume: {}", volume);
            List<SnapshotVO> ongoingSnapshots = snapshotDao.listByStatus(volumeId, Snapshot.State.Creating, Snapshot.State.CreatedOnPrimary, Snapshot.State.BackingUp);
            int ongoingSnapshotsCount = ongoingSnapshots.size();
            logger.debug("The count of ongoing Snapshots for VM {} and disk type {} is {}", vm, type, ongoingSnapshotsCount);
            if (ongoingSnapshotsCount > 0) {
                logger.debug("Found " + ongoingSnapshotsCount + " no. of snapshots, on volume of type " + type + ", which snapshots are not yet backed up");
                return true;
            }
        }
        return false;
    }

    @Override
    public void checkForUnattachedVolumes(long vmId, List<VolumeVO> volumes) {
        StringBuilder sb = new StringBuilder();

        for (VolumeVO volume : volumes) {
            if (volume.getInstanceId() == null || vmId != volume.getInstanceId() || volume.getVolumeType() != Volume.Type.DATADISK) {
                sb.append(volume.toString() + "; ");
            }
        }

        if (!StringUtils.isEmpty(sb.toString())) {
            throw new InvalidParameterValueException("The following supplied volumes are not DATADISK attached to the VM: " + sb.toString());
        }
    }

    @Override
    public void validateVolumes(List<VolumeVO> volumes) {
        for (VolumeVO volume : volumes) {
            if (!(volume.getVolumeType() == Volume.Type.ROOT || volume.getVolumeType() == Volume.Type.DATADISK)) {
                throw new InvalidParameterValueException("Please specify volume of type " + Volume.Type.DATADISK.toString() + " or " + Volume.Type.ROOT.toString());
            }
            if (volume.isDeleteProtection()) {
                throw new InvalidParameterValueException(String.format(
                        "Volume [id = %s, name = %s] has delete protection enabled and cannot be deleted",
                        volume.getUuid(), volume.getName()));
            }
        }
    }

    @Override
    public void checkUnmanagingVMOngoingVolumeSnapshots(UserVmVO vm) {
        logger.debug("Checking if there are any ongoing snapshots on the ROOT volumes associated with VM {}", vm);
        if (checkStatusOfVolumeSnapshots(vm, Volume.Type.ROOT)) {
            throw new CloudRuntimeException("There is/are unbacked up snapshot(s) on ROOT volume, vm unmanage is not permitted, please try again later.");
        }
        logger.debug("Found no ongoing snapshots on volume of type ROOT, for the vm {}", vm);
    }

    @Override
    public void checkUnmanagingVMVolumes(UserVmVO vm, List<VolumeVO> volumes) {
        for (VolumeVO volume : volumes) {
            if (volume.getInstanceId() == null || !volume.getInstanceId().equals(vm.getId())) {
                throw new CloudRuntimeException(String.format("Invalid state for volume %s of VM %s: it is not attached to VM", volume, vm));
            } else if (volume.getVolumeType() != Volume.Type.ROOT && volume.getVolumeType() != Volume.Type.DATADISK) {
                throw new CloudRuntimeException(String.format("Invalid type for volume %s: ROOT or DATADISK expected but got %s", volume, volume.getVolumeType()));
            }
        }
    }
}
