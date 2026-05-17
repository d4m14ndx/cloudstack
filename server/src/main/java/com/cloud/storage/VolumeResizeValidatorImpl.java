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

import java.util.List;
import java.util.Objects;

import jakarta.inject.Inject;

import org.springframework.stereotype.Component;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.AccountManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;

/**
 * Pre-flight validation helpers for the volume resize flow —
 * extracted from {@link VolumeApiServiceImpl}.
 *
 * @see VolumeResizeValidator
 */
@Component
public class VolumeResizeValidatorImpl implements VolumeResizeValidator {

    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private SnapshotDao snapshotDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private UserVmDao userVmDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private DataCenterDao dataCenterDao;
    @Inject
    private com.cloud.vm.dao.VMInstanceDao vmInstanceDao;
    @Inject
    private AccountManager accountManager;
    @Inject
    private ConfigurationManager configurationManager;

    @Override
    public boolean isNotPossibleToResize(VolumeVO volume, DiskOfferingVO diskOffering) {
        Long templateId = volume.getTemplateId();
        ImageFormat format = null;
        if (templateId != null) {
            VMTemplateVO template = templateDao.findByIdIncludingRemoved(templateId);
            format = template.getFormat();
        }
        boolean isNotIso = format != null && format != ImageFormat.ISO;
        boolean isRoot = Volume.Type.ROOT.equals(volume.getVolumeType());

        boolean isOfferingEnforcingRootDiskSize = diskOffering.isComputeOnly() && diskOffering.getDiskSize() > 0;

        return isOfferingEnforcingRootDiskSize && isRoot && isNotIso;
    }

    @Override
    public void checkIfVolumeIsRootAndVmIsRunning(Long newSize, VolumeVO volume, VMInstanceVO vmInstanceVO) {
        if (!volume.getSize().equals(newSize) && volume.getVolumeType().equals(Volume.Type.ROOT) && !State.Stopped.equals(vmInstanceVO.getState())) {
            throw new InvalidParameterValueException(String.format("Cannot resize ROOT volume [%s] when VM is not on Stopped State. VM %s is in state %s", volume.getName(), vmInstanceVO
                    .getInstanceName(), vmInstanceVO.getState()));
        }
    }

    @Override
    public void validateIops(Long minIops, Long maxIops, Storage.StoragePoolType poolType) {
        if (poolType == Storage.StoragePoolType.PowerFlex) {
            // PowerFlex takes iopsLimit as input, skip minIops validation
            minIops = (maxIops != null) ? Long.valueOf(0) : null;
        }

        if ((minIops == null && maxIops != null) || (minIops != null && maxIops == null)) {
            throw new InvalidParameterValueException("Either 'miniops' and 'maxiops' must both be provided or neither must be provided.");
        }

        if (minIops != null && maxIops != null) {
            if (minIops > maxIops) {
                throw new InvalidParameterValueException("The 'miniops' parameter must be less than or equal to the 'maxiops' parameter.");
            }
        }
    }

    @Override
    public void validateVolumeReadyStateAndHypervisorChecks(VolumeVO volume, long currentSize, Long newSize) {
        // checking if there are any ongoing snapshots on the volume which is to be resized
        List<SnapshotVO> ongoingSnapshots = snapshotDao.listByStatus(volume.getId(), Snapshot.State.Creating, Snapshot.State.CreatedOnPrimary, Snapshot.State.BackingUp);
        if (ongoingSnapshots.size() > 0) {
            throw new CloudRuntimeException("There is/are unbacked up snapshot(s) on this volume, resize volume is not permitted, please try again later.");
        }

        /* Only works for KVM/XenServer/VMware (or "Any") for now, and volumes with 'None' since they're just allocated in DB */
        HypervisorType hypervisorType = volumeDao.getHypervisorType(volume.getId());

        if (!VolumeApiServiceImpl.SupportedHypervisorsForVolResize.contains(hypervisorType)) {
            throw new InvalidParameterValueException("Hypervisor " + hypervisorType + " does not support volume resize");
        }

        if (volume.getState() != Volume.State.Ready && volume.getState() != Volume.State.Allocated) {
            throw new InvalidParameterValueException("Volume should be in ready or allocated state before attempting a resize. Volume " + volume.getUuid() + " is in state " + volume.getState() + ".");
        }

        if (hypervisorType.equals(HypervisorType.VMware) && newSize < currentSize) {
            throw new InvalidParameterValueException("VMware doesn't support shrinking volume from larger size: " + currentSize + " GB to a smaller size: " + newSize + " GB");
        }

        com.cloud.vm.UserVmVO userVm = userVmDao.findById(volume.getInstanceId());
        if (userVm != null) {
            if (volume.getVolumeType().equals(Volume.Type.ROOT) && userVm.getPowerState() != VirtualMachine.PowerState.PowerOff && hypervisorType == HypervisorType.VMware) {
                throw new InvalidParameterValueException("VM current state is : " + userVm.getPowerState() + ". But VM should be in " + VirtualMachine.PowerState.PowerOff + " state.");
            }
        }
    }

    @Override
    public void setNewIopsLimits(VolumeVO volume, DiskOfferingVO newDiskOffering, Long[] newMinIops, Long[] newMaxIops) {
        if (Boolean.TRUE.equals(newDiskOffering.isCustomizedIops())) {
            newMinIops[0] = newMinIops[0] != null ? newMinIops[0] : volume.getMinIops();
            newMaxIops[0] = newMaxIops[0] != null ? newMaxIops[0] : volume.getMaxIops();

            validateIops(newMinIops[0], newMaxIops[0], volume.getPoolType());
        } else {
            newMinIops[0] = newDiskOffering.getMinIops();
            newMaxIops[0] = newDiskOffering.getMaxIops();
        }
    }

    @Override
    public void checkIfVolumeCanResizeWithNewDiskOffering(VolumeVO volume, DiskOfferingVO existingDiskOffering, DiskOfferingVO newDiskOffering, Long newSize, VMInstanceVO vmInstanceVO) {
        if (existingDiskOffering.getId() == newDiskOffering.getId() &&
                (!newDiskOffering.isCustomized() || (newDiskOffering.isCustomized() && Objects.equals(volume.getSize(), newSize << 30)))) {
            throw new InvalidParameterValueException(String.format("Volume %s is already having disk offering %s", volume, newDiskOffering.getUuid()));
        }

        if (existingDiskOffering.getDiskSizeStrictness() != newDiskOffering.getDiskSizeStrictness()) {
            throw new InvalidParameterValueException("Disk offering size strictness does not match with new disk offering.");
        }

        if (VolumeApiServiceImpl.MatchStoragePoolTagsWithDiskOffering.valueIn(volume.getDataCenterId())
                && !VolumeApiServiceImpl.doesNewDiskOfferingHasTagsAsOldDiskOffering(existingDiskOffering, newDiskOffering)) {
            throw new InvalidParameterValueException(String.format("Selected disk offering %s does not have tags as in existing disk offering of volume %s", existingDiskOffering.getUuid(), volume.getUuid()));
        }

        if (volume.getVolumeType().equals(Volume.Type.ROOT)) {
            ServiceOfferingVO serviceOffering = serviceOfferingDao.findById(vmInstanceVO.getServiceOfferingId());
            if (serviceOffering != null && serviceOffering.getDiskOfferingStrictness()) {
                throw new InvalidParameterValueException(String.format("Cannot resize ROOT volume [%s] with new disk offering since existing disk offering is strictly assigned to the ROOT volume.", volume.getName()));
            }
        }

        if (existingDiskOffering.getDiskSizeStrictness() && !(volume.getSize().equals(newSize))) {
            throw new InvalidParameterValueException(String.format("Resize volume for %s is not allowed since disk offering's size is fixed", volume.getName()));
        }
    }

    @Override
    public void validateVolumeResizeWithNewDiskOfferingAndLoad(VolumeVO volume, DiskOfferingVO existingDiskOffering, DiskOfferingVO newDiskOffering, Long[] newSize, Long[] newMinIops, Long[] newMaxIops, Integer[] newHypervisorSnapshotReserve) {
        if (newDiskOffering.getRemoved() != null) {
            throw new InvalidParameterValueException("Requested disk offering has been removed.");
        }

        configurationManager.checkDiskOfferingAccess(accountManager.getActiveAccountById(volume.getAccountId()), newDiskOffering, dataCenterDao.findById(volume.getDataCenterId()));

        if (newDiskOffering.getDiskSize() > 0 && !newDiskOffering.isComputeOnly()) {
            newSize[0] = (Long) newDiskOffering.getDiskSize();
        } else if (newDiskOffering.isCustomized() && !newDiskOffering.isComputeOnly()) {
            if (newSize[0] == null) {
                throw new InvalidParameterValueException("The new disk offering requires that a size be specified.");
            }

            // convert from GiB to bytes
            newSize[0] = newSize[0] << 30;
        } else {
            if (newSize[0] != null) {
                throw new InvalidParameterValueException("You cannot pass in a custom disk size to a non-custom disk offering.");
            }

            if (newDiskOffering.isComputeOnly() && newDiskOffering.getDiskSize() == 0) {
                newSize[0] = volume.getSize();
            } else {
                newSize[0] = newDiskOffering.getDiskSize();
            }

            // if the hypervisor snapshot reserve value is null, it must remain null (currently only KVM uses null and null is all KVM uses for a value here)
            newHypervisorSnapshotReserve[0] = volume.getHypervisorSnapshotReserve() != null ? newDiskOffering.getHypervisorSnapshotReserve() : null;
        }

        setNewIopsLimits(volume, newDiskOffering, newMinIops, newMaxIops);

        if (existingDiskOffering.getDiskSizeStrictness() && !(volume.getSize().equals(newSize[0]))) {
            throw new InvalidParameterValueException(String.format("Resize volume for %s is not allowed since disk offering's size is fixed", volume.getName()));
        }

        Long instanceId = volume.getInstanceId();
        VMInstanceVO vmInstanceVO = vmInstanceDao.findById(instanceId);

        checkIfVolumeCanResizeWithNewDiskOffering(volume, existingDiskOffering, newDiskOffering, newSize[0], vmInstanceVO);
        checkIfVolumeIsRootAndVmIsRunning(newSize[0], volume, vmInstanceVO);
    }
}
