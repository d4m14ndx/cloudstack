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

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.backup.BackupScheduleVO;
import org.apache.cloudstack.backup.dao.BackupScheduleDao;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

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

@RunWith(MockitoJUnitRunner.class)
public class VmAssignmentOwnershipServiceImplTest {

    private static final long OLD_ACCOUNT_ID = 10L;
    private static final long NEW_ACCOUNT_ID = 20L;
    private static final long NEW_DOMAIN_ID = 30L;
    private static final long VM_ID = 40L;
    private static final long VOLUME_ID = 50L;
    private static final long DATA_CENTER_ID = 60L;
    private static final long DISK_OFFERING_ID = 70L;
    private static final long TEMPLATE_ID = 80L;
    private static final long VOLUME_SIZE = 90L;
    private static final long SNAPSHOT_POLICY_ID = 100L;
    private static final long BACKUP_SCHEDULE_ID = 110L;

    @Mock private UserVmDao vmDao;
    @Mock private VolumeDao volumeDao;
    @Mock private DiskOfferingDao diskOfferingDao;
    @Mock private ResourceLimitService resourceLimitService;
    @Mock private SnapshotPolicyDao snapshotPolicyDao;
    @Mock private BackupScheduleDao backupScheduleDao;
    @Mock private Account oldAccount;
    @Mock private Account newAccount;
    @Mock private UserVmVO vm;
    @Mock private VolumeVO volume;
    @Mock private DiskOfferingVO diskOffering;
    @Mock private SnapshotPolicyVO snapshotPolicy;
    @Mock private BackupScheduleVO backupSchedule;

    private VmAssignmentOwnershipServiceImpl service;

    @Before
    public void setUp() {
        service = new VmAssignmentOwnershipServiceImpl();
        ReflectionTestUtils.setField(service, "vmDao", vmDao);
        ReflectionTestUtils.setField(service, "volumeDao", volumeDao);
        ReflectionTestUtils.setField(service, "diskOfferingDao", diskOfferingDao);
        ReflectionTestUtils.setField(service, "resourceLimitService", resourceLimitService);
        ReflectionTestUtils.setField(service, "snapshotPolicyDao", snapshotPolicyDao);
        ReflectionTestUtils.setField(service, "backupScheduleDao", backupScheduleDao);

        when(oldAccount.getAccountId()).thenReturn(OLD_ACCOUNT_ID);
        when(newAccount.getAccountId()).thenReturn(NEW_ACCOUNT_ID);
        when(newAccount.getDomainId()).thenReturn(NEW_DOMAIN_ID);
    }

    @Test
    public void updateVmOwnerSetsAccountAndDomainAndPersistsVm() {
        service.updateVmOwner(newAccount, vm, NEW_DOMAIN_ID, NEW_ACCOUNT_ID);

        verify(vm).setAccountId(NEW_ACCOUNT_ID);
        verify(vm).setDomainId(NEW_DOMAIN_ID);
        verify(vmDao).persist(vm);
    }

    @Test
    public void updateVolumesOwnerPublishesEventsAdjustsResourceCountsAndPersistsVolume() {
        stubVolume();
        when(diskOfferingDao.findById(DISK_OFFERING_ID)).thenReturn(diskOffering);

        try (MockedStatic<UsageEventUtils> usageEventUtils = Mockito.mockStatic(UsageEventUtils.class)) {
            service.updateVolumesOwner(Collections.singletonList(volume), oldAccount, newAccount, NEW_ACCOUNT_ID);

            usageEventUtils.verify(() -> UsageEventUtils.publishUsageEvent(
                    EventTypes.EVENT_VOLUME_DELETE,
                    OLD_ACCOUNT_ID,
                    DATA_CENTER_ID,
                    VOLUME_ID,
                    "volume-name",
                    Volume.class.getName(),
                    "volume-uuid",
                    true));
            verify(resourceLimitService).decrementVolumeResourceCount(OLD_ACCOUNT_ID, true, VOLUME_SIZE, diskOffering);
            verify(volume).setAccountId(NEW_ACCOUNT_ID);
            verify(volume).setDomainId(NEW_DOMAIN_ID);
            verify(volumeDao).persist(volume);
            verify(resourceLimitService).incrementVolumeResourceCount(NEW_ACCOUNT_ID, true, VOLUME_SIZE, diskOffering);
            usageEventUtils.verify(() -> UsageEventUtils.publishUsageEvent(
                    EventTypes.EVENT_VOLUME_CREATE,
                    NEW_ACCOUNT_ID,
                    DATA_CENTER_ID,
                    VOLUME_ID,
                    "volume-name",
                    DISK_OFFERING_ID,
                    TEMPLATE_ID,
                    VOLUME_SIZE,
                    Volume.class.getName(),
                    "volume-uuid",
                    VM_ID,
                    true));
        }
    }

    @Test
    public void updateSnapshotPolicyOwnershipUpdatesPoliciesForEachVolume() {
        when(volume.getId()).thenReturn(VOLUME_ID);
        when(snapshotPolicy.getId()).thenReturn(SNAPSHOT_POLICY_ID);
        when(snapshotPolicyDao.listByVolumeId(VOLUME_ID)).thenReturn(List.of(snapshotPolicy));

        service.updateSnapshotPolicyOwnership(Collections.singletonList(volume), newAccount);

        verify(snapshotPolicy).setAccountId(NEW_ACCOUNT_ID);
        verify(snapshotPolicy).setDomainId(NEW_DOMAIN_ID);
        verify(snapshotPolicyDao).update(SNAPSHOT_POLICY_ID, snapshotPolicy);
    }

    @Test
    public void updateBackupScheduleOwnershipUpdatesSchedulesForVm() {
        when(vm.getId()).thenReturn(VM_ID);
        when(backupSchedule.getId()).thenReturn(BACKUP_SCHEDULE_ID);
        when(backupScheduleDao.listByVM(VM_ID)).thenReturn(List.of(backupSchedule));

        service.updateBackupScheduleOwnership(vm, newAccount);

        verify(backupSchedule).setAccountId(NEW_ACCOUNT_ID);
        verify(backupSchedule).setDomainId(NEW_DOMAIN_ID);
        verify(backupScheduleDao).update(BACKUP_SCHEDULE_ID, backupSchedule);
    }

    private void stubVolume() {
        when(volume.getAccountId()).thenReturn(OLD_ACCOUNT_ID, NEW_ACCOUNT_ID);
        when(volume.getDataCenterId()).thenReturn(DATA_CENTER_ID);
        when(volume.getId()).thenReturn(VOLUME_ID);
        when(volume.getName()).thenReturn("volume-name");
        when(volume.getUuid()).thenReturn("volume-uuid");
        when(volume.isDisplayVolume()).thenReturn(true);
        when(volume.isDisplay()).thenReturn(true);
        when(volume.getSize()).thenReturn(VOLUME_SIZE);
        when(volume.getDiskOfferingId()).thenReturn(DISK_OFFERING_ID);
        when(volume.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(volume.getInstanceId()).thenReturn(VM_ID);
    }
}
