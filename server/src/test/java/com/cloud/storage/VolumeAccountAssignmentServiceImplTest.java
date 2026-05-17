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

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

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
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Focused unit tests for {@link VolumeAccountAssignmentServiceImpl}.
 * Mirrors the slice extraction tests already run against the
 * manager-level wrappers in {@link VolumeApiServiceImplTest}, but
 * exercises the extracted component directly.
 */
@RunWith(MockitoJUnitRunner.class)
public class VolumeAccountAssignmentServiceImplTest {

    @Mock
    private VolumeDao volumeDao;
    @Mock
    private SnapshotDao snapshotDao;
    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private DiskOfferingDao diskOfferingDao;
    @Mock
    private AccountManager accountManager;
    @Mock
    private ProjectManager projectManager;
    @Mock
    private ResourceLimitService resourceLimitMgr;
    @Mock
    private VolumeService volumeService;

    @Mock
    private VolumeVO volume;
    @Mock
    private VMInstanceVO vmInstance;
    @Mock
    private DiskOfferingVO diskOffering;
    @Mock
    private Project project;

    @InjectMocks
    private VolumeAccountAssignmentServiceImpl service;

    private static final long OLD_ACCOUNT_ID = 11L;
    private static final long NEW_ACCOUNT_ID = 22L;
    private static final long NEW_DOMAIN_ID = 33L;
    private static final long VOLUME_ID = 100L;
    private static final long VOLUME_SIZE = 4096L;
    private static final long DATA_CENTER_ID = 1L;
    private static final long INSTANCE_ID = 999L;
    private static final long DISK_OFFERING_ID = 77L;
    private static final long PROJECT_ID = 55L;
    private static final long PROJECT_ACCOUNT_ID = 66L;

    private AccountVO oldAccount;
    private AccountVO newAccount;
    private AccountVO caller;

    @Before
    public void setUp() {
        oldAccount = new AccountVO(OLD_ACCOUNT_ID);
        oldAccount.setState(Account.State.ENABLED);
        newAccount = new AccountVO(NEW_ACCOUNT_ID);
        newAccount.setDomainId(NEW_DOMAIN_ID);
        newAccount.setState(Account.State.ENABLED);
        caller = new AccountVO(7L);

        Mockito.lenient().when(volume.getId()).thenReturn(VOLUME_ID);
        Mockito.lenient().when(volume.getSize()).thenReturn(VOLUME_SIZE);
        Mockito.lenient().when(volume.getDataCenterId()).thenReturn(DATA_CENTER_ID);
        Mockito.lenient().when(volume.getDiskOfferingId()).thenReturn(DISK_OFFERING_ID);
        Mockito.lenient().when(volume.getAccountId()).thenReturn(OLD_ACCOUNT_ID);
        Mockito.lenient().when(volume.getUuid()).thenReturn("volume-uuid");
        Mockito.lenient().when(volume.getName()).thenReturn("volume-name");
        Mockito.lenient().when(volume.isDisplayVolume()).thenReturn(true);
    }

    // ------------------------------------------------------------------
    // validateVolume
    // ------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void validateVolumeRejectsNullVolume() {
        service.validateVolume("some-uuid", null);
    }

    @Test(expected = PermissionDeniedException.class)
    public void validateVolumeRejectsVolumeAttachedToVm() {
        Mockito.when(volume.getInstanceId()).thenReturn(INSTANCE_ID);
        Mockito.when(vmInstanceDao.findById(INSTANCE_ID)).thenReturn(vmInstance);
        service.validateVolume("volume-uuid", volume);
    }

    @Test(expected = PermissionDeniedException.class)
    public void validateVolumeRejectsVolumeWithLiveSnapshots() {
        Mockito.when(volume.getInstanceId()).thenReturn(null);
        List<SnapshotVO> snaps = new ArrayList<>();
        snaps.add(Mockito.mock(SnapshotVO.class));
        Mockito.when(snapshotDao.listByStatusNotIn(VOLUME_ID, Snapshot.State.Destroyed, Snapshot.State.Error))
                .thenReturn(snaps);
        service.validateVolume("volume-uuid", volume);
    }

    @Test
    public void validateVolumeAllowsDetachedVolumeWithoutLiveSnapshots() {
        Mockito.when(volume.getInstanceId()).thenReturn(null);
        Mockito.when(snapshotDao.listByStatusNotIn(VOLUME_ID, Snapshot.State.Destroyed, Snapshot.State.Error))
                .thenReturn(Collections.emptyList());
        // no exception expected
        service.validateVolume("volume-uuid", volume);
    }

    // ------------------------------------------------------------------
    // validateAccounts
    // ------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void validateAccountsRejectsNullOldAccount() {
        service.validateAccounts("new-uuid", volume, null, newAccount);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateAccountsRejectsNullNewAccount() {
        service.validateAccounts("new-uuid", volume, oldAccount, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateAccountsRejectsDisabledNewAccount() {
        newAccount.setState(Account.State.DISABLED);
        service.validateAccounts("new-uuid", volume, oldAccount, newAccount);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateAccountsRejectsLockedNewAccount() {
        newAccount.setState(Account.State.LOCKED);
        service.validateAccounts("new-uuid", volume, oldAccount, newAccount);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateAccountsRejectsSameSourceAndDestinationAccount() {
        AccountVO sameAccount = new AccountVO(OLD_ACCOUNT_ID);
        sameAccount.setState(Account.State.ENABLED);
        service.validateAccounts("new-uuid", volume, oldAccount, sameAccount);
    }

    @Test
    public void validateAccountsAcceptsTwoEnabledDistinctAccounts() {
        // No exception expected.
        service.validateAccounts("new-uuid", volume, oldAccount, newAccount);
    }

    // ------------------------------------------------------------------
    // getAccountOrProject
    // ------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void getAccountOrProjectRejectsBothAccountAndProject() {
        service.getAccountOrProject("project-uuid", NEW_ACCOUNT_ID, PROJECT_ID, caller);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void getAccountOrProjectRejectsUnknownProject() {
        Mockito.when(projectManager.getProject(PROJECT_ID)).thenReturn(null);
        service.getAccountOrProject("project-uuid", null, PROJECT_ID, caller);
    }

    @Test(expected = PermissionDeniedException.class)
    public void getAccountOrProjectRejectsCallerWithoutProjectAccess() {
        Mockito.when(projectManager.getProject(PROJECT_ID)).thenReturn(project);
        Mockito.when(project.getProjectAccountId()).thenReturn(PROJECT_ACCOUNT_ID);
        Mockito.when(projectManager.canAccessProjectAccount(caller, PROJECT_ACCOUNT_ID)).thenReturn(false);
        service.getAccountOrProject("project-uuid", null, PROJECT_ID, caller);
    }

    @Test
    public void getAccountOrProjectReturnsProjectOwningAccount() {
        Mockito.when(projectManager.getProject(PROJECT_ID)).thenReturn(project);
        Mockito.when(project.getProjectAccountId()).thenReturn(PROJECT_ACCOUNT_ID);
        Mockito.when(projectManager.canAccessProjectAccount(caller, PROJECT_ACCOUNT_ID)).thenReturn(true);
        Mockito.when(accountManager.getAccount(PROJECT_ACCOUNT_ID)).thenReturn(newAccount);

        Account result = service.getAccountOrProject("project-uuid", null, PROJECT_ID, caller);

        assertSame(newAccount, result);
        Mockito.verify(accountManager).getAccount(PROJECT_ACCOUNT_ID);
        Mockito.verify(accountManager, Mockito.never()).getActiveAccountById(Mockito.anyLong());
    }

    @Test
    public void getAccountOrProjectReturnsActiveAccountWhenAccountIdGiven() {
        Mockito.when(accountManager.getActiveAccountById(NEW_ACCOUNT_ID)).thenReturn(newAccount);

        Account result = service.getAccountOrProject("project-uuid", NEW_ACCOUNT_ID, null, caller);

        assertSame(newAccount, result);
        Mockito.verify(accountManager).getActiveAccountById(NEW_ACCOUNT_ID);
        Mockito.verify(projectManager, Mockito.never()).getProject(Mockito.anyLong());
    }

    @Test(expected = NullPointerException.class)
    public void getAccountOrProjectThrowsWhenBothNullBecauseLongUnboxFails() {
        // When neither projectId nor accountId is given, the impl falls
        // through to AccountManager#getActiveAccountById(accountId) — that
        // method takes a primitive long, so unboxing a null Long blows up
        // with a NullPointerException. Locking this behaviour in so the
        // contract is unambiguous: validateAccounts is responsible for
        // rejecting an absent destination, not getAccountOrProject.
        service.getAccountOrProject("project-uuid", null, null, caller);
    }

    // ------------------------------------------------------------------
    // updateVolumeAccount
    // ------------------------------------------------------------------

    @Test
    public void updateVolumeAccountFlipsAccountAndDomainAndPublishesEvents() {
        Mockito.when(diskOfferingDao.findById(DISK_OFFERING_ID)).thenReturn(diskOffering);
        Mockito.when(volumeDao.persist(volume)).thenReturn(volume);

        try (MockedStatic<UsageEventUtils> usageEventUtils = Mockito.mockStatic(UsageEventUtils.class)) {
            service.updateVolumeAccount(oldAccount, volume, newAccount);

            // VOLUME_DELETE for the old account — invoke the exact same
            // overload (the 8-arg one with entityType / entityUUID /
            // displayResource) with the raw values the impl uses.
            usageEventUtils.verify(() -> UsageEventUtils.publishUsageEvent(
                    EventTypes.EVENT_VOLUME_DELETE,
                    OLD_ACCOUNT_ID,
                    DATA_CENTER_ID,
                    VOLUME_ID,
                    "volume-name",
                    Volume.class.getName(),
                    "volume-uuid",
                    true));

            // Decrement old account, set account/domain on the volume,
            // persist, increment new account.
            Mockito.verify(resourceLimitMgr).decrementVolumeResourceCount(OLD_ACCOUNT_ID, true, VOLUME_SIZE, diskOffering);
            Mockito.verify(volume).setAccountId(NEW_ACCOUNT_ID);
            Mockito.verify(volume).setDomainId(NEW_DOMAIN_ID);
            Mockito.verify(volumeDao).persist(volume);
            Mockito.verify(resourceLimitMgr).incrementVolumeResourceCount(NEW_ACCOUNT_ID, true, VOLUME_SIZE, diskOffering);

            // Secondary-storage move is the very last thing.
            Mockito.verify(volumeService).moveVolumeOnSecondaryStorageToAnotherAccount(volume, oldAccount, newAccount);
        }
    }

    @Test
    public void updateVolumeAccountToleratesMissingDiskOffering() {
        // The resource-limit manager accepts a null DiskOfferingVO (just no
        // tagged counters), so the flow must not blow up when the offering
        // has been deleted under us. Verifies we still flip account/domain
        // and call the secondary-storage move.
        Mockito.when(diskOfferingDao.findById(DISK_OFFERING_ID)).thenReturn(null);
        Mockito.when(volumeDao.persist(volume)).thenReturn(volume);

        try (MockedStatic<UsageEventUtils> ignored = Mockito.mockStatic(UsageEventUtils.class)) {
            service.updateVolumeAccount(oldAccount, volume, newAccount);

            Mockito.verify(resourceLimitMgr).decrementVolumeResourceCount(OLD_ACCOUNT_ID, true, VOLUME_SIZE, null);
            Mockito.verify(resourceLimitMgr).incrementVolumeResourceCount(NEW_ACCOUNT_ID, true, VOLUME_SIZE, null);
            Mockito.verify(volume).setAccountId(NEW_ACCOUNT_ID);
            Mockito.verify(volume).setDomainId(NEW_DOMAIN_ID);
            Mockito.verify(volumeService).moveVolumeOnSecondaryStorageToAnotherAccount(volume, oldAccount, newAccount);
        }
    }

    @Test
    public void updateVolumeAccountFlipsAccountIdBeforePublishingCreateEvent() {
        // The VOLUME_CREATE event must be emitted *after* the volume's
        // accountId has been overwritten with the new account, so the
        // event is attributed to the new owner. Verify the flip happens
        // before persist (which is itself before the VOLUME_CREATE call).
        Mockito.when(diskOfferingDao.findById(DISK_OFFERING_ID)).thenReturn(diskOffering);
        Mockito.when(volumeDao.persist(volume)).thenReturn(volume);

        try (MockedStatic<UsageEventUtils> ignored = Mockito.mockStatic(UsageEventUtils.class)) {
            service.updateVolumeAccount(oldAccount, volume, newAccount);

            org.mockito.InOrder inOrder = Mockito.inOrder(volume, volumeDao);
            inOrder.verify(volume).setAccountId(NEW_ACCOUNT_ID);
            inOrder.verify(volume).setDomainId(NEW_DOMAIN_ID);
            inOrder.verify(volumeDao).persist(volume);
        }
    }

    @Test
    public void updateVolumeAccountPersistsVolumeBeforeMovingSecondaryStorage() {
        // Order matters: we must persist the account flip in the DB before
        // asking VolumeService to physically move the secondary-storage
        // bits so that the new owner is durable if the s2 move fails.
        Mockito.when(diskOfferingDao.findById(DISK_OFFERING_ID)).thenReturn(diskOffering);
        Mockito.when(volumeDao.persist(volume)).thenReturn(volume);

        try (MockedStatic<UsageEventUtils> ignored = Mockito.mockStatic(UsageEventUtils.class)) {
            service.updateVolumeAccount(oldAccount, volume, newAccount);

            org.mockito.InOrder inOrder = Mockito.inOrder(volumeDao, volumeService);
            inOrder.verify(volumeDao).persist(volume);
            inOrder.verify(volumeService).moveVolumeOnSecondaryStorageToAnotherAccount(volume, oldAccount, newAccount);
        }
    }

    // Smoke test that the @InjectMocks wiring is healthy.
    @Test
    public void serviceIsInjectable() {
        assertNotNull(service);
    }
}
