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

import static com.cloud.hypervisor.Hypervisor.HypervisorType.Functionality;
import static com.cloud.utils.NumbersUtil.toHumanReadableSize;

import java.util.Map;

import jakarta.inject.Inject;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.DiskOffering;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.utils.NumbersUtil;

/**
 * Root disk validation/sizing — extracted from {@link UserVmManagerImpl}.
 *
 * @see VmRootDiskValidator
 */
@Component
public class VmRootDiskValidatorImpl implements VmRootDiskValidator {
    private static final Logger LOG = LogManager.getLogger(VmRootDiskValidatorImpl.class);
    private static final long GIB_TO_BYTES = 1024L * 1024L * 1024L;

    @Inject
    private VolumeApiService volumeService;

    @Inject
    private VMTemplateDao templateDao;

    @Override
    public long verifyAndGetDiskSize(DiskOffering diskOffering, Long diskSize) {
        if (diskOffering == null) {
            throw new InvalidParameterValueException("Specified disk offering cannot be found");
        }
        long size;
        if (diskOffering.isCustomized() && !diskOffering.isComputeOnly()) {
            if (diskSize == null) {
                throw new InvalidParameterValueException("This disk offering requires a custom size specified");
            }
            volumeService.validateCustomDiskOfferingSizeRange(diskSize);
            size = diskSize * GIB_TO_BYTES;
        } else {
            size = diskOffering.getDiskSize();
        }
        volumeService.validateVolumeSizeInBytes(size);
        return size;
    }

    @Override
    public long configureCustomRootDiskSize(Map<String, String> customParameters,
                                            VMTemplateVO template,
                                            HypervisorType hypervisorType,
                                            DiskOfferingVO rootDiskOffering) {
        verifyIfHypervisorSupportsRootdiskSizeOverride(hypervisorType);

        Long rootDiskSizeCustomParam = null;
        if (customParameters.containsKey(VmDetailConstants.ROOT_DISK_SIZE)) {
            rootDiskSizeCustomParam = NumbersUtil.parseLong(customParameters.get(VmDetailConstants.ROOT_DISK_SIZE), -1);
            if (rootDiskSizeCustomParam <= 0) {
                throw new InvalidParameterValueException("Root disk size should be a positive number.");
            }
        }
        long rootDiskSizeInBytes = verifyAndGetDiskSize(rootDiskOffering, rootDiskSizeCustomParam);
        // If the service offering dictates a non-zero size, it takes priority and is written back.
        if (rootDiskSizeInBytes > 0) {
            volumeService.validateVolumeSizeInBytes(rootDiskSizeInBytes);
            long rootDiskSizeInGiB = rootDiskSizeInBytes / GIB_TO_BYTES;
            customParameters.put(VmDetailConstants.ROOT_DISK_SIZE, String.valueOf(rootDiskSizeInGiB));
            return rootDiskSizeInBytes;
        }

        if (customParameters.containsKey(VmDetailConstants.ROOT_DISK_SIZE)) {
            Long rootDiskSize = NumbersUtil.parseLong(customParameters.get(VmDetailConstants.ROOT_DISK_SIZE), -1);
            if (rootDiskSize <= 0) {
                throw new InvalidParameterValueException("Root disk size should be a positive number.");
            }
            rootDiskSize = rootDiskSizeCustomParam * GIB_TO_BYTES;
            volumeService.validateVolumeSizeInBytes(rootDiskSize);
            return rootDiskSize;
        }

        // For baremetal/size-less templates the size may be 0.
        Long templateSize = templateDao.findById(template.getId()).getSize();
        return templateSize != null ? templateSize : 0;
    }

    @Override
    public void verifyIfHypervisorSupportsRootdiskSizeOverride(HypervisorType hypervisorType) {
        if (!hypervisorType.isFunctionalitySupported(Functionality.RootDiskSizeOverride)) {
            throw new InvalidParameterValueException("Hypervisor " + hypervisorType + " does not support rootdisksize override");
        }
    }

    @Override
    public void validateRootDiskResize(HypervisorType hypervisorType,
                                       Long rootDiskSize,
                                       VMTemplateVO templateVO,
                                       UserVmVO vm,
                                       Map<String, String> customParameters) {
        boolean isIso = ImageFormat.ISO == templateVO.getFormat();
        if ((rootDiskSize << 30) < templateVO.getSize()) {
            String error = String.format("Unsupported: rootdisksize override (%s GB) is smaller than template size %s",
                    rootDiskSize, toHumanReadableSize(templateVO.getSize()));
            LOG.error(error);
            throw new InvalidParameterValueException(error);
        } else if ((rootDiskSize << 30) > templateVO.getSize()) {
            if (hypervisorType == HypervisorType.VMware
                    && (vm.getDetails() == null || vm.getDetails().get(VmDetailConstants.ROOT_DISK_CONTROLLER) == null)) {
                LOG.warn("If Root disk controller parameter is not overridden, then Root disk resize may fail because current Root disk controller value is NULL.");
            } else if (hypervisorType == HypervisorType.VMware
                    && vm.getDetails().get(VmDetailConstants.ROOT_DISK_CONTROLLER).toLowerCase().contains("ide")
                    && !isIso) {
                String error = String.format("Found unsupported root disk controller [%s].",
                        vm.getDetails().get(VmDetailConstants.ROOT_DISK_CONTROLLER));
                LOG.error(error);
                throw new InvalidParameterValueException(error);
            } else {
                LOG.debug("Rootdisksize override validation successful. Template root disk size {} Root disk size specified {} GB",
                        toHumanReadableSize(templateVO.getSize()), rootDiskSize);
            }
        } else {
            LOG.debug("Root disk size specified is {} and Template root disk size is {}. Both are equal so no need to override",
                    toHumanReadableSize(rootDiskSize << 30), toHumanReadableSize(templateVO.getSize()));
            customParameters.remove(VmDetailConstants.ROOT_DISK_SIZE);
        }
    }
}
