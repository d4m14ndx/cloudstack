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

import org.apache.cloudstack.backup.BackupScheduleVO;
import org.apache.cloudstack.backup.dao.BackupScheduleDao;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.SnapshotPolicyVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.SnapshotPolicyDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.ResourceLimitService;
import com.cloud.vm.dao.UserVmDao;

@Component
public class VmAssignmentOwnershipServiceImpl implements VmAssignmentOwnershipService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private UserVmDao vmDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private ResourceLimitService resourceLimitService;
    @Inject
    private SnapshotPolicyDao snapshotPolicyDao;
    @Inject
    private BackupScheduleDao backupScheduleDao;

    @Override
    public void updateVmOwner(Account newAccount, UserVmVO vm, Long domainId, Long newAccountId) {
        logger.debug("Updating VM [{}] owner to [{}].", vm, newAccount);

        vm.setAccountId(newAccountId);
        vm.setDomainId(domainId);

        vmDao.persist(vm);
    }

    @Override
    public void updateVolumesOwner(final List<VolumeVO> volumes, Account oldAccount, Account newAccount, Long newAccountId) {
        logger.debug("Updating volumes owner from old account [{}] to new account [{}].", oldAccount, newAccount);

        for (VolumeVO volume : volumes) {
            logger.trace("Generating a delete volume event for volume [{}].", volume);
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_DELETE, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(),
                    Volume.class.getName(), volume.getUuid(), volume.isDisplayVolume());

            logger.trace("Decrementing volume [{}] and primary storage resource count for the old account [{}].", volume, oldAccount);
            DiskOfferingVO diskOfferingVO = diskOfferingDao.findById(volume.getDiskOfferingId());
            resourceLimitService.decrementVolumeResourceCount(oldAccount.getAccountId(), volume.isDisplay(), volume.getSize(), diskOfferingVO);

            logger.trace("Setting the new account [{}] and domain [{}] for volume [{}].", newAccount, newAccount.getDomainId(), volume);
            volume.setAccountId(newAccountId);
            volume.setDomainId(newAccount.getDomainId());

            volumeDao.persist(volume);

            logger.trace("Incrementing volume [{}] and primary storage resource count for the new account [{}].", volume, newAccount);
            resourceLimitService.incrementVolumeResourceCount(newAccount.getAccountId(), volume.isDisplay(), volume.getSize(), diskOfferingVO);

            logger.trace("Generating a create volume event for volume [{}].", volume);
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_CREATE, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(),
                    volume.getDiskOfferingId(), volume.getTemplateId(), volume.getSize(), Volume.class.getName(), volume.getUuid(), volume.getInstanceId(), volume.isDisplayVolume());
        }
    }

    @Override
    public void updateSnapshotPolicyOwnership(List<VolumeVO> volumes, Account newAccount) {
        logger.debug("Updating snapshot policy ownership for volumes of VM being assigned to account [{}]", newAccount);

        for (VolumeVO volume : volumes) {
            List<SnapshotPolicyVO> snapshotPolicies = snapshotPolicyDao.listByVolumeId(volume.getId());
            for (SnapshotPolicyVO policy : snapshotPolicies) {
                logger.trace("Updating snapshot policy [{}] ownership from account [{}] to account [{}]",
                        policy.getId(), policy.getAccountId(), newAccount.getAccountId());

                policy.setAccountId(newAccount.getAccountId());
                policy.setDomainId(newAccount.getDomainId());
                snapshotPolicyDao.update(policy.getId(), policy);
            }
        }
    }

    @Override
    public void updateBackupScheduleOwnership(UserVmVO vm, Account newAccount) {
        logger.debug("Updating backup schedule ownership for VM [{}] being assigned to account [{}]", vm, newAccount);

        List<BackupScheduleVO> backupSchedules = backupScheduleDao.listByVM(vm.getId());
        for (BackupScheduleVO schedule : backupSchedules) {
            logger.trace("Updating backup schedule [{}] ownership from account [{}] to account [{}]",
                    schedule.getId(), schedule.getAccountId(), newAccount.getAccountId());

            schedule.setAccountId(newAccount.getAccountId());
            schedule.setDomainId(newAccount.getDomainId());
            backupScheduleDao.update(schedule.getId(), schedule);
        }
    }
}
