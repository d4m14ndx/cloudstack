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

import static com.cloud.storage.Volume.IOPS_LIMIT;
import static org.apache.cloudstack.api.ApiConstants.MAX_IOPS;
import static org.apache.cloudstack.api.ApiConstants.MIN_IOPS;

import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.user.volume.ChangeOfferingForVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.ResizeVolumeCmd;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class VmRootDiskOfferingChangeServiceImpl implements VmRootDiskOfferingChangeService {

    private static final long GIB_TO_BYTES = 1024L * 1024L * 1024L;

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private VolumeDao _volsDao;
    @Inject
    private DiskOfferingDao _diskOfferingDao;
    @Inject
    private VolumeApiService _volumeService;

    /**
     * Prepares the Resize Volume Command and verifies if the disk offering from the new service offering can be resized.
     * <br>
     * If the Service Offering was configured with a root disk size (size > 0) then it can only resize to an offering with a larger disk
     * or to an offering with a root size of zero, which is the default behavior.
     */
    @Override
    public ResizeVolumeCmd prepareResizeVolumeCmd(VolumeVO rootVolume, DiskOfferingVO currentRootDiskOffering, DiskOfferingVO newRootDiskOffering) {
        if (rootVolume == null) {
            throw new InvalidParameterValueException("Could not find Root volume for the VM while preparing the Resize Volume Command.");
        }
        if (currentRootDiskOffering == null) {
            throw new InvalidParameterValueException("Could not find Disk Offering matching the provided current Root Offering ID.");
        }
        if (newRootDiskOffering == null) {
            throw new InvalidParameterValueException("Could not find Disk Offering matching the provided Offering ID for resizing Root volume.");
        }

        ResizeVolumeCmd resizeVolumeCmd = new ResizeVolumeCmd(rootVolume.getId(), newRootDiskOffering.getMinIops(), newRootDiskOffering.getMaxIops());

        long newNewOfferingRootSizeInBytes = newRootDiskOffering.getDiskSize();
        long newNewOfferingRootSizeInGiB = newNewOfferingRootSizeInBytes / GIB_TO_BYTES;
        long currentRootDiskOfferingGiB = currentRootDiskOffering.getDiskSize() / GIB_TO_BYTES;
        if (newNewOfferingRootSizeInBytes > currentRootDiskOffering.getDiskSize()) {
            resizeVolumeCmd = new ResizeVolumeCmd(rootVolume.getId(), newRootDiskOffering.getMinIops(), newRootDiskOffering.getMaxIops(), newRootDiskOffering.getId());
            logger.debug("Preparing command to resize VM Root disk from {} GB to {} GB; current offering: {}, new offering: {}.",
                    currentRootDiskOfferingGiB, newNewOfferingRootSizeInGiB, currentRootDiskOffering, newRootDiskOffering);
        } else if (newNewOfferingRootSizeInBytes > 0l && newNewOfferingRootSizeInBytes < currentRootDiskOffering.getDiskSize()) {
            throw new InvalidParameterValueException(String.format(
                    "Failed to resize Root volume. The new Service Offering [%s] has a smaller disk size [%d GB] than the current disk [%d GB].",
                    newRootDiskOffering, newNewOfferingRootSizeInGiB, currentRootDiskOfferingGiB));
        }
        return resizeVolumeCmd;
    }

    @Override
    public void changeDiskOfferingForRootVolume(Long vmId, DiskOfferingVO newDiskOffering, Map<String, String> customParameters, Long zoneId) throws ResourceAllocationException {

        if (!UserVmManager.AllowDiskOfferingChangeDuringScaleVm.valueIn(zoneId)) {
            if (logger.isDebugEnabled()) {
                logger.debug(String.format("Changing the disk offering of the root volume during the compute offering change operation is disabled. Please check the setting [%s].",
                        UserVmManager.AllowDiskOfferingChangeDuringScaleVm.key()));
            }
            return;
        }

        List<VolumeVO> vols = _volsDao.findReadyAndAllocatedRootVolumesByInstance(vmId);

        for (final VolumeVO rootVolumeOfVm : vols) {
            DiskOfferingVO currentRootDiskOffering = _diskOfferingDao.findById(rootVolumeOfVm.getDiskOfferingId());
            Long rootDiskSize= null;
            Long rootDiskSizeBytes = null;
            if (customParameters.containsKey(ApiConstants.ROOT_DISK_SIZE)) {
                rootDiskSize = Long.parseLong(customParameters.get(ApiConstants.ROOT_DISK_SIZE));
                rootDiskSizeBytes = rootDiskSize << 30;
            }
            if (currentRootDiskOffering.getId() == newDiskOffering.getId() &&
                    (!newDiskOffering.isCustomized() || (newDiskOffering.isCustomized() && Objects.equals(rootVolumeOfVm.getSize(), rootDiskSizeBytes)))) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Volume {} is already having disk offering {}", rootVolumeOfVm, newDiskOffering);
                }
                continue;
            }
            HypervisorType hypervisorType = _volsDao.getHypervisorType(rootVolumeOfVm.getId());
            if (HypervisorType.Simulator != hypervisorType) {
                Long minIopsInNewDiskOffering = null;
                Long maxIopsInNewDiskOffering = null;
                boolean autoMigrate = false;
                boolean shrinkOk = false;
                if (customParameters.containsKey(MIN_IOPS)) {
                    minIopsInNewDiskOffering = Long.parseLong(customParameters.get(MIN_IOPS));
                }

                if (customParameters.containsKey(IOPS_LIMIT)) {
                    maxIopsInNewDiskOffering = Long.parseLong(customParameters.get(IOPS_LIMIT));
                } else if (customParameters.containsKey(MAX_IOPS)) {
                    maxIopsInNewDiskOffering = Long.parseLong(customParameters.get(MAX_IOPS));
                }
                if (customParameters.containsKey(ApiConstants.AUTO_MIGRATE)) {
                    autoMigrate = Boolean.parseBoolean(customParameters.get(ApiConstants.AUTO_MIGRATE));
                }
                if (customParameters.containsKey(ApiConstants.SHRINK_OK)) {
                    shrinkOk = Boolean.parseBoolean(customParameters.get(ApiConstants.SHRINK_OK));
                }
                ChangeOfferingForVolumeCmd changeOfferingForVolumeCmd = new ChangeOfferingForVolumeCmd(rootVolumeOfVm.getId(), newDiskOffering.getId(), minIopsInNewDiskOffering, maxIopsInNewDiskOffering, autoMigrate, shrinkOk);
                if (rootDiskSize != null) {
                    changeOfferingForVolumeCmd.setSize(rootDiskSize);
                }
                Volume result = _volumeService.changeDiskOfferingForVolume(changeOfferingForVolumeCmd);
                if (result == null) {
                    throw new CloudRuntimeException("Failed to change disk offering of the root volume");
                }
            } else if (newDiskOffering.getDiskSize() > 0 && currentRootDiskOffering.getDiskSize() != newDiskOffering.getDiskSize()) {
                throw new InvalidParameterValueException("Hypervisor " + hypervisorType + " does not support volume resize");
            }
        }
    }
}
