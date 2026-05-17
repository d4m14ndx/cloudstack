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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Date;

import org.apache.cloudstack.api.command.user.volume.MigrateVolumeCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine.State;

/**
 * Focused unit tests for {@link VolumeMigrationValidatorImpl}.
 * Exercises the extracted component directly with mocked DAOs and
 * managers (Phase 4 slice 7).
 */
@RunWith(MockitoJUnitRunner.class)
public class VolumeMigrationValidatorImplTest {

    @Mock
    private DiskOfferingDao diskOfferingDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private DataCenterDao dataCenterDao;
    @Mock
    private AccountManager accountManager;

    @InjectMocks
    private VolumeMigrationValidatorImpl validator;

    @Before
    public void setUp() {
        Account caller = Mockito.mock(Account.class);
        Mockito.when(caller.getId()).thenReturn(1L);
        CallContext.register(Mockito.mock(User.class), caller);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    private VMInstanceVO makeVm(State state) {
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getState()).thenReturn(state);
        Mockito.when(vm.getInstanceName()).thenReturn("vm-1");
        Mockito.when(vm.getUuid()).thenReturn("vm-uuid-1");
        return vm;
    }

    private VolumeVO makeVolume() {
        VolumeVO vol = Mockito.mock(VolumeVO.class);
        Mockito.when(vol.getName()).thenReturn("vol-1");
        Mockito.when(vol.getId()).thenReturn(42L);
        return vol;
    }

    @Test
    public void checkVmStateForMigrationAllowsStopped() {
        validator.checkVmStateForMigration(makeVm(State.Stopped), makeVolume());
    }

    @Test
    public void checkVmStateForMigrationAllowsRunning() {
        validator.checkVmStateForMigration(makeVm(State.Running), makeVolume());
    }

    @Test
    public void checkVmStateForMigrationAllowsShutdown() {
        validator.checkVmStateForMigration(makeVm(State.Shutdown), makeVolume());
    }

    @Test(expected = CloudRuntimeException.class)
    public void checkVmStateForMigrationRejectsStarting() {
        validator.checkVmStateForMigration(makeVm(State.Starting), makeVolume());
    }

    @Test(expected = CloudRuntimeException.class)
    public void checkVmStateForMigrationRejectsMigrating() {
        validator.checkVmStateForMigration(makeVm(State.Migrating), makeVolume());
    }

    @Test
    public void isSourceOrDestNotOnStorPoolTrueWhenSourceNotStorPool() {
        StoragePoolVO src = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO dest = Mockito.mock(StoragePoolVO.class);
        Mockito.when(src.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        // dest pool type is not read because the OR short-circuits on src.
        assertTrue(validator.isSourceOrDestNotOnStorPool(src, dest));
    }

    @Test
    public void isSourceOrDestNotOnStorPoolTrueWhenDestNotStorPool() {
        StoragePoolVO src = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO dest = Mockito.mock(StoragePoolVO.class);
        Mockito.when(src.getPoolType()).thenReturn(Storage.StoragePoolType.StorPool);
        Mockito.when(dest.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        assertTrue(validator.isSourceOrDestNotOnStorPool(src, dest));
    }

    @Test
    public void isSourceOrDestNotOnStorPoolFalseWhenBothStorPool() {
        StoragePoolVO src = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO dest = Mockito.mock(StoragePoolVO.class);
        Mockito.when(src.getPoolType()).thenReturn(Storage.StoragePoolType.StorPool);
        Mockito.when(dest.getPoolType()).thenReturn(Storage.StoragePoolType.StorPool);
        assertFalse(validator.isSourceOrDestNotOnStorPool(src, dest));
    }

    @Test
    public void isSourceAndDestOnStorPoolTrueWhenBothStorPool() {
        StoragePoolVO src = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO dest = Mockito.mock(StoragePoolVO.class);
        Mockito.when(src.getPoolType()).thenReturn(Storage.StoragePoolType.StorPool);
        Mockito.when(dest.getPoolType()).thenReturn(Storage.StoragePoolType.StorPool);
        assertTrue(validator.isSourceAndDestOnStorPool(src, dest));
    }

    @Test
    public void isSourceAndDestOnStorPoolFalseWhenMixed() {
        StoragePoolVO src = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO dest = Mockito.mock(StoragePoolVO.class);
        Mockito.when(src.getPoolType()).thenReturn(Storage.StoragePoolType.StorPool);
        Mockito.when(dest.getPoolType()).thenReturn(Storage.StoragePoolType.PowerFlex);
        assertFalse(validator.isSourceAndDestOnStorPool(src, dest));
    }

    @Test
    public void retrieveAndValidateNewDiskOfferingReturnsNullWhenNoOffering() {
        MigrateVolumeCmd cmd = Mockito.mock(MigrateVolumeCmd.class);
        Mockito.when(cmd.getNewDiskOfferingId()).thenReturn(null);
        assertNull(validator.retrieveAndValidateNewDiskOffering(cmd));
        Mockito.verifyNoInteractions(diskOfferingDao, volumeDao, dataCenterDao, accountManager);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void retrieveAndValidateNewDiskOfferingThrowsWhenOfferingMissing() {
        MigrateVolumeCmd cmd = Mockito.mock(MigrateVolumeCmd.class);
        Mockito.when(cmd.getNewDiskOfferingId()).thenReturn(99L);
        Mockito.when(diskOfferingDao.findById(99L)).thenReturn(null);
        validator.retrieveAndValidateNewDiskOffering(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void retrieveAndValidateNewDiskOfferingThrowsWhenOfferingRemoved() {
        MigrateVolumeCmd cmd = Mockito.mock(MigrateVolumeCmd.class);
        Mockito.when(cmd.getNewDiskOfferingId()).thenReturn(99L);
        DiskOfferingVO offering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(offering.getRemoved()).thenReturn(new Date());
        Mockito.when(offering.getUuid()).thenReturn("offering-uuid");
        Mockito.when(diskOfferingDao.findById(99L)).thenReturn(offering);
        validator.retrieveAndValidateNewDiskOffering(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void retrieveAndValidateNewDiskOfferingThrowsWhenVolumeMissing() {
        MigrateVolumeCmd cmd = Mockito.mock(MigrateVolumeCmd.class);
        Mockito.when(cmd.getNewDiskOfferingId()).thenReturn(99L);
        Mockito.when(cmd.getId()).thenReturn(7L);
        DiskOfferingVO offering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(offering.getRemoved()).thenReturn(null);
        Mockito.when(diskOfferingDao.findById(99L)).thenReturn(offering);
        Mockito.when(volumeDao.findById(7L)).thenReturn(null);
        validator.retrieveAndValidateNewDiskOffering(cmd);
    }

    @Test
    public void retrieveAndValidateNewDiskOfferingHappyPath() {
        MigrateVolumeCmd cmd = Mockito.mock(MigrateVolumeCmd.class);
        Mockito.when(cmd.getNewDiskOfferingId()).thenReturn(99L);
        Mockito.when(cmd.getId()).thenReturn(7L);

        DiskOfferingVO offering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(offering.getRemoved()).thenReturn(null);
        Mockito.when(diskOfferingDao.findById(99L)).thenReturn(offering);

        VolumeVO volume = Mockito.mock(VolumeVO.class);
        Mockito.when(volume.getDataCenterId()).thenReturn(5L);
        Mockito.when(volumeDao.findById(7L)).thenReturn(volume);

        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(dataCenterDao.findById(5L)).thenReturn(zone);

        DiskOfferingVO result = validator.retrieveAndValidateNewDiskOffering(cmd);
        assertSame(offering, result);

        Mockito.verify(accountManager).checkAccess(Mockito.any(Account.class), Mockito.eq(offering), Mockito.eq(zone));
    }
}
