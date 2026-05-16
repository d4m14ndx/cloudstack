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
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.utils.jsinterpreter.TagAsRuleHelper;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.offering.DiskOffering;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.utils.Pair;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Volume/storage-pool/disk-offering compatibility checks — extracted
 * from {@link VolumeApiServiceImpl}.
 *
 * @see DiskOfferingCompatibilityService
 */
@Component
public class DiskOfferingCompatibilityServiceImpl implements DiskOfferingCompatibilityService {
    private static final Logger LOG = LogManager.getLogger(DiskOfferingCompatibilityServiceImpl.class);

    @Inject
    private StoragePoolTagsDao storagePoolTagsDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private DiskOfferingDao diskOfferingDao;

    @Override
    public Pair<List<String>, Boolean> resolveStoragePoolTags(StoragePool destPool) {
        List<StoragePoolTagVO> destPoolTags = storagePoolTagsDao.findStoragePoolTags(destPool.getId());
        if (CollectionUtils.isEmpty(destPoolTags)) {
            return null;
        }
        return new Pair<>(destPoolTags.parallelStream().map(StoragePoolTagVO::getTag).collect(Collectors.toList()), destPoolTags.get(0).isTagARule());
    }

    @Override
    public boolean storagePoolTagsMatchOfferingTags(StoragePool destPool,
                                                   Pair<List<String>, Boolean> storagePoolTags,
                                                   String diskOfferingTags) {
        if ((storagePoolTags == null || !storagePoolTags.second()) && org.apache.commons.lang.StringUtils.isBlank(diskOfferingTags)) {
            if (storagePoolTags == null) {
                LOG.debug("Storage pool [{}] does not have any tags, and so does the disk offering. Therefore, they are compatible", destPool.getUuid());
            } else {
                LOG.debug("Storage pool has tags [%s], and the disk offering has no tags. Therefore, they are compatible.", destPool.getUuid());
            }
            return true;
        }
        if (storagePoolTags == null || CollectionUtils.isEmpty(storagePoolTags.first())) {
            LOG.debug("Destination storage pool [{}] has no tags, while disk offering has tags [{}]. Therefore, they are not compatible", destPool.getUuid(),
                    diskOfferingTags);
            return false;
        }
        List<String> storageTagsList = storagePoolTags.first();
        String[] newDiskOfferingTagsAsStringArray = org.apache.commons.lang.StringUtils.split(diskOfferingTags, ",");

        boolean result;
        if (storagePoolTags.second()) {
            result = TagAsRuleHelper.interpretTagAsRule(storageTagsList.get(0), diskOfferingTags, VolumeApiServiceImpl.storageTagRuleExecutionTimeout.value());
        } else {
            result = CollectionUtils.isSubCollection(Arrays.asList(newDiskOfferingTagsAsStringArray), storageTagsList);
        }
        LOG.debug(String.format("Destination storage pool [{}] accepts tags [{}]? {}", destPool.getUuid(), diskOfferingTags, result));
        return result;
    }

    @Override
    public void validateBasicMigrationCompatibility(Volume volume, DiskOffering newDiskOffering, StoragePool destPool) {
        if (destPool.isShared() && newDiskOffering.isUseLocalStorage()) {
            throw new InvalidParameterValueException("You cannot move the volume to shared storage, with the disk offering configured for local storage.");
        }
        if (destPool.isLocal() && newDiskOffering.isShared()) {
            throw new InvalidParameterValueException("You cannot move the volume to local storage, with the disk offering configured for shared storage.");
        }
    }

    @Override
    public void validateRootVolumeServiceOfferingStrictness(Volume volume) {
        if (!volume.getVolumeType().equals(Volume.Type.ROOT)) {
            return;
        }
        if (volume.getInstanceId() == null) {
            return;
        }
        VMInstanceVO vm = vmInstanceDao.findById(volume.getInstanceId());
        if (vm == null) {
            return;
        }
        ServiceOfferingVO serviceOffering = serviceOfferingDao.findById(vm.getServiceOfferingId());
        if (serviceOffering != null && serviceOffering.getDiskOfferingStrictness()) {
            throw new InvalidParameterValueException(String.format("Disk offering cannot be changed to the volume %s since existing disk offering is strictly associated with the volume", volume.getUuid()));
        }
    }

    @Override
    public void logSizeMismatchOnMigration(Volume volume, DiskOffering newDiskOffering) {
        if (volume.getSize() == newDiskOffering.getDiskSize()) {
            return;
        }
        DiskOfferingVO oldDiskOffering = diskOfferingDao.findById(volume.getDiskOfferingId());
        LOG.warn("You are migrating a volume [{}] and changing the disk offering[from {} to {}] to reflect this migration. However, the sizes of the volume and the new disk offering are different.",
                volume, oldDiskOffering, newDiskOffering);
    }
}
