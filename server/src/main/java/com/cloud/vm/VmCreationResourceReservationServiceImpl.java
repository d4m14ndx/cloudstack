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

import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.apache.cloudstack.resourcelimit.Reserver;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.configuration.Resource.ResourceType;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.offering.DiskOffering;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.ResourceLimitService;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class VmCreationResourceReservationServiceImpl implements VmCreationResourceReservationService {

    protected static final Logger logger = LogManager.getLogger(VmCreationResourceReservationServiceImpl.class);

    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private ResourceLimitService resourceLimitService;
    @Inject
    private ReservationDao reservationDao;
    @Inject
    private VmRootDiskValidator vmRootDiskValidator;

    @Override
    public UserVm reserveComputeResources(Account owner, ServiceOfferingVO offering, VMTemplateVO template,
            VmCreationOperation operation) throws ResourceAllocationException {
        if (!VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
            List<String> resourceLimitHostTags = resourceLimitService.getResourceLimitHostTags(offering, template);
            try (CheckedReservation vmReservation = new CheckedReservation(owner, ResourceType.user_vm, resourceLimitHostTags, 1L, reservationDao, resourceLimitService);
                 CheckedReservation cpuReservation = new CheckedReservation(owner, ResourceType.cpu, resourceLimitHostTags, Long.valueOf(offering.getCpu()), reservationDao, resourceLimitService);
                 CheckedReservation memReservation = new CheckedReservation(owner, ResourceType.memory, resourceLimitHostTags, Long.valueOf(offering.getRamSize()), reservationDao, resourceLimitService);
                 CheckedReservation gpuReservation = offering.getGpuCount() != null && offering.getGpuCount() > 0 ?
                         new CheckedReservation(owner, ResourceType.gpu, resourceLimitHostTags, Long.valueOf(offering.getGpuCount()), reservationDao, resourceLimitService) : null) {
                return operation.create();
            } catch (ResourceAllocationException | CloudRuntimeException e) {
                throw e;
            } catch (Exception e) {
                logger.error("error during resource reservation and allocation", e);
                throw new CloudRuntimeException(e);
            }
        }

        try {
            return operation.create();
        } catch (ResourceAllocationException | CloudRuntimeException e) {
            throw e;
        } catch (Exception e) {
            logger.error("error during resource reservation and allocation", e);
            throw new CloudRuntimeException(e);
        }
    }

    @Override
    public List<String> getResourceLimitStorageTags(long diskOfferingId) {
        DiskOfferingVO diskOfferingVO = diskOfferingDao.findById(diskOfferingId);
        return resourceLimitService.getResourceLimitStorageTags(diskOfferingVO);
    }

    @Override
    public void reserveStorageResourcesForVm(List<Reserver> checkedReservations, Account owner, Long diskOfferingId,
            Long diskSize, List<VmDiskInfo> dataDiskInfoList, Long rootDiskOfferingId, ServiceOfferingVO offering,
            Long rootDiskSize) throws ResourceAllocationException {
        List<String> rootResourceLimitStorageTags = getResourceLimitStorageTags(rootDiskOfferingId != null ? rootDiskOfferingId : offering.getDiskOfferingId());
        CheckedReservation rootVolumeReservation = new CheckedReservation(owner, ResourceType.volume, rootResourceLimitStorageTags, 1L, reservationDao, resourceLimitService);
        checkedReservations.add(rootVolumeReservation);
        CheckedReservation rootPrimaryStorageReservation = new CheckedReservation(owner, ResourceType.primary_storage, rootResourceLimitStorageTags, rootDiskSize, reservationDao, resourceLimitService);
        checkedReservations.add(rootPrimaryStorageReservation);

        if (diskOfferingId != null) {
            List<String> additionalResourceLimitStorageTags = getResourceLimitStorageTags(diskOfferingId);
            DiskOfferingVO diskOffering = diskOfferingDao.findById(diskOfferingId);
            Long size = vmRootDiskValidator.verifyAndGetDiskSize(diskOffering, diskSize);
            CheckedReservation additionalVolumeReservation = new CheckedReservation(owner, ResourceType.volume, additionalResourceLimitStorageTags, 1L, reservationDao, resourceLimitService);
            checkedReservations.add(additionalVolumeReservation);
            CheckedReservation additionalPrimaryStorageReservation = new CheckedReservation(owner, ResourceType.primary_storage, additionalResourceLimitStorageTags, size, reservationDao, resourceLimitService);
            checkedReservations.add(additionalPrimaryStorageReservation);
        }

        if (dataDiskInfoList != null) {
            for (VmDiskInfo vmDiskInfo : dataDiskInfoList) {
                DiskOffering diskOffering = vmDiskInfo.getDiskOffering();
                List<String> additionalResourceLimitStorageTagsForDataDisk = getResourceLimitStorageTags(vmDiskInfo.getDiskOffering().getId());
                Long size = vmRootDiskValidator.verifyAndGetDiskSize(diskOffering, vmDiskInfo.getSize());
                CheckedReservation additionalVolumeReservation = new CheckedReservation(owner, ResourceType.volume, additionalResourceLimitStorageTagsForDataDisk, 1L, reservationDao, resourceLimitService);
                checkedReservations.add(additionalVolumeReservation);
                CheckedReservation additionalPrimaryStorageReservation = new CheckedReservation(owner, ResourceType.primary_storage, additionalResourceLimitStorageTagsForDataDisk, size, reservationDao, resourceLimitService);
                checkedReservations.add(additionalPrimaryStorageReservation);
            }
        }
    }

    @Override
    public void checkVolumesLimits(Account account, List<VolumeVO> volumes, List<Reserver> reservations)
            throws ResourceAllocationException {
        Map<Long, List<String>> diskOfferingTagsMap = new HashMap<>();

        for (VolumeVO volume : volumes) {
            if (!volume.isDisplay()) {
                continue;
            }

            Long diskOfferingId = volume.getDiskOfferingId();
            if (!diskOfferingTagsMap.containsKey(diskOfferingId)) {
                DiskOffering diskOffering = diskOfferingDao.findById(diskOfferingId);
                List<String> tagsForDiskOffering = resourceLimitService.getResourceLimitStorageTags(diskOffering);
                diskOfferingTagsMap.put(diskOfferingId, tagsForDiskOffering);
            }

            List<String> tags = diskOfferingTagsMap.get(diskOfferingId);

            CheckedReservation volumeReservation = new CheckedReservation(account, ResourceType.volume, tags, 1L, reservationDao, resourceLimitService);
            reservations.add(volumeReservation);

            long size = ObjectUtils.defaultIfNull(volume.getSize(), 0L);
            CheckedReservation primaryStorageReservation = new CheckedReservation(account, ResourceType.primary_storage, tags, size, reservationDao, resourceLimitService);
            reservations.add(primaryStorageReservation);
        }
    }
}
