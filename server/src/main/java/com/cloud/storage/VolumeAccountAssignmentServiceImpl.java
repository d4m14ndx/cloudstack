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

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.utils.reflectiontostringbuilderutils.ReflectionToStringBuilderUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectManager;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Helpers backing {@link VolumeApiServiceImpl#assignVolumeToAccount} —
 * extracted from {@link VolumeApiServiceImpl}.
 *
 * @see VolumeAccountAssignmentService
 */
@Component
public class VolumeAccountAssignmentServiceImpl implements VolumeAccountAssignmentService {
    private static final Logger LOG = LogManager.getLogger(VolumeAccountAssignmentServiceImpl.class);

    @Inject
    private VolumeDao volumeDao;
    @Inject
    private SnapshotDao snapshotDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private AccountManager accountManager;
    @Inject
    private ProjectManager projectManager;
    @Inject
    private ResourceLimitService resourceLimitMgr;
    @Inject
    private VolumeService volumeService;

    @Override
    public void validateVolume(String volumeUuid, VolumeVO volume) {
        if (volume == null) {
            throw new InvalidParameterValueException(String.format("No volume was found with UUID [%s].", volumeUuid));
        }

        String volumeToString = ReflectionToStringBuilderUtils.reflectOnlySelectedFields(volume, "id", "name", "uuid");

        if (volume.getInstanceId() != null) {
            VMInstanceVO vmInstanceVo = vmInstanceDao.findById(volume.getInstanceId());
            String msg = String.format("Volume [%s] is attached to [%s], so it cannot be moved to a different account.", volumeToString, vmInstanceVo);
            LOG.error(msg);
            throw new PermissionDeniedException(msg);
        }

        List<SnapshotVO> snapshots = snapshotDao.listByStatusNotIn(volume.getId(), Snapshot.State.Destroyed, Snapshot.State.Error);
        if (CollectionUtils.isNotEmpty(snapshots)) {
            throw new PermissionDeniedException(String.format("Volume [%s] has snapshots. Remove the volume's snapshots before assigning it to another account.", volumeToString));
        }
    }

    @Override
    public void validateAccounts(String newAccountUuid, VolumeVO volume, Account oldAccount, Account newAccount) {
        if (oldAccount == null) {
            throw new InvalidParameterValueException(String.format("The current account of the volume [%s] is invalid.",
                    ReflectionToStringBuilderUtils.reflectOnlySelectedFields(volume, "name", "uuid")));
        }

        if (newAccount == null) {
            throw new InvalidParameterValueException(String.format("UUID of the destination account is invalid. No account was found with UUID [%s].", newAccountUuid));
        }

        if (newAccount.getState() == Account.State.DISABLED || newAccount.getState() == Account.State.LOCKED) {
            throw new InvalidParameterValueException(String.format("Unable to assign volume to destination account [%s], as it is in [%s] state.", newAccount,
                    newAccount.getState().toString()));
        }

        if (oldAccount.getAccountId() == newAccount.getAccountId()) {
            throw new InvalidParameterValueException(String.format("The new account and the old account are the same [%s].", oldAccount));
        }
    }

    @Override
    public Account getAccountOrProject(String projectUuid, Long accountId, Long projectId, Account caller) {
        if (projectId != null && accountId != null) {
            throw new InvalidParameterValueException("Both 'accountid' and 'projectid' were informed. You must inform only one of them.");
        }

        if (projectId != null) {
            Project project = projectManager.getProject(projectId);
            if (project == null) {
                throw new InvalidParameterValueException(String.format("Unable to find project [%s]", projectUuid));
            }

            if (!projectManager.canAccessProjectAccount(caller, project.getProjectAccountId())) {
                throw new PermissionDeniedException(String.format("Account [%s] does not have access to project [%s].", caller, projectUuid));
            }

            return accountManager.getAccount(project.getProjectAccountId());
        }

        return accountManager.getActiveAccountById(accountId);
    }

    @Override
    public void updateVolumeAccount(Account oldAccount, VolumeVO volume, Account newAccount) {
        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_DELETE, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(),
                Volume.class.getName(), volume.getUuid(), volume.isDisplayVolume());
        DiskOfferingVO diskOfferingVO = diskOfferingDao.findById(volume.getDiskOfferingId());
        resourceLimitMgr.decrementVolumeResourceCount(oldAccount.getAccountId(), true, volume.getSize(),
                diskOfferingVO);

        volume.setAccountId(newAccount.getAccountId());
        volume.setDomainId(newAccount.getDomainId());
        volumeDao.persist(volume);
        resourceLimitMgr.incrementVolumeResourceCount(newAccount.getAccountId(), true, volume.getSize(),
                diskOfferingVO);
        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_CREATE, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(),
                volume.getDiskOfferingId(), volume.getTemplateId(), volume.getSize(), Volume.class.getName(),
                volume.getUuid(), volume.getInstanceId(), volume.isDisplayVolume());

        volumeService.moveVolumeOnSecondaryStorageToAnotherAccount(volume, oldAccount, newAccount);
    }
}
