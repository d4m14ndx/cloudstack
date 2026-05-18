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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.Snapshot;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class VmVolumeLifecycleValidationServiceImplTest {

    private static final long VM_ID = 101L;
    private static final long OTHER_VM_ID = 202L;
    private static final long VOLUME_ID = 303L;

    @InjectMocks
    private VmVolumeLifecycleValidationServiceImpl service;

    @Mock
    private VolumeDao volumeDao;
    @Mock
    private SnapshotDao snapshotDao;
    @Mock
    private VirtualMachine vm;
    @Mock
    private UserVmVO userVm;

    @Before
    public void setUp() {
        lenient().when(vm.getId()).thenReturn(VM_ID);
        lenient().when(userVm.getId()).thenReturn(VM_ID);
    }

    @Test
    public void checkStatusOfVolumeSnapshotsUsesRootVolumeLookupForRootType() {
        VolumeVO volume = volume(VOLUME_ID, VM_ID, Volume.Type.ROOT);
        when(volumeDao.findByInstanceAndType(VM_ID, Volume.Type.ROOT)).thenReturn(List.of(volume));
        when(snapshotDao.listByStatus(eq(VOLUME_ID), eq(Snapshot.State.Creating),
                eq(Snapshot.State.CreatedOnPrimary), eq(Snapshot.State.BackingUp))).thenReturn(Collections.emptyList());

        assertFalse(service.checkStatusOfVolumeSnapshots(vm, Volume.Type.ROOT));

        verify(volumeDao).findByInstanceAndType(VM_ID, Volume.Type.ROOT);
        verify(volumeDao, never()).findByInstance(VM_ID);
    }

    @Test
    public void checkStatusOfVolumeSnapshotsUsesDataVolumeLookupForDataDiskType() {
        VolumeVO volume = volume(VOLUME_ID, VM_ID, Volume.Type.DATADISK);
        when(volumeDao.findByInstanceAndType(VM_ID, Volume.Type.DATADISK)).thenReturn(List.of(volume));
        when(snapshotDao.listByStatus(eq(VOLUME_ID), eq(Snapshot.State.Creating),
                eq(Snapshot.State.CreatedOnPrimary), eq(Snapshot.State.BackingUp))).thenReturn(Collections.emptyList());

        assertFalse(service.checkStatusOfVolumeSnapshots(vm, Volume.Type.DATADISK));

        verify(volumeDao).findByInstanceAndType(VM_ID, Volume.Type.DATADISK);
        verify(volumeDao, never()).findByInstance(VM_ID);
    }

    @Test
    public void checkStatusOfVolumeSnapshotsUsesAllVolumesLookupWhenTypeIsNull() {
        VolumeVO volume = volume(VOLUME_ID, VM_ID, Volume.Type.DATADISK);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(List.of(volume));
        when(snapshotDao.listByStatus(eq(VOLUME_ID), eq(Snapshot.State.Creating),
                eq(Snapshot.State.CreatedOnPrimary), eq(Snapshot.State.BackingUp))).thenReturn(Collections.emptyList());

        assertFalse(service.checkStatusOfVolumeSnapshots(vm, null));

        verify(volumeDao).findByInstance(VM_ID);
        verify(volumeDao, never()).findByInstanceAndType(eq(VM_ID), eq(Volume.Type.DATADISK));
    }

    @Test
    public void checkStatusOfVolumeSnapshotsReturnsTrueWhenAnyVolumeHasOngoingSnapshots() {
        VolumeVO volume = volume(VOLUME_ID, VM_ID, Volume.Type.ROOT);
        when(volumeDao.findByInstanceAndType(VM_ID, Volume.Type.ROOT)).thenReturn(List.of(volume));
        when(snapshotDao.listByStatus(eq(VOLUME_ID), eq(Snapshot.State.Creating),
                eq(Snapshot.State.CreatedOnPrimary), eq(Snapshot.State.BackingUp))).thenReturn(List.of(mock(SnapshotVO.class)));

        assertTrue(service.checkStatusOfVolumeSnapshots(vm, Volume.Type.ROOT));
    }

    @Test
    public void checkStatusOfVolumeSnapshotsReturnsFalseWhenNoVolumesHaveOngoingSnapshots() {
        VolumeVO rootVolume = volume(VOLUME_ID, VM_ID, Volume.Type.ROOT);
        VolumeVO dataVolume = volume(404L, VM_ID, Volume.Type.DATADISK);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(List.of(rootVolume, dataVolume));
        when(snapshotDao.listByStatus(eq(VOLUME_ID), eq(Snapshot.State.Creating),
                eq(Snapshot.State.CreatedOnPrimary), eq(Snapshot.State.BackingUp))).thenReturn(Collections.emptyList());
        when(snapshotDao.listByStatus(eq(404L), eq(Snapshot.State.Creating),
                eq(Snapshot.State.CreatedOnPrimary), eq(Snapshot.State.BackingUp))).thenReturn(Collections.emptyList());

        assertFalse(service.checkStatusOfVolumeSnapshots(vm, null));
    }

    @Test
    public void checkForUnattachedVolumesRejectsVolumeWithoutInstanceId() {
        VolumeVO volume = volume(VOLUME_ID, null, Volume.Type.DATADISK);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.checkForUnattachedVolumes(VM_ID, List.of(volume)));

        assertTrue(exception.getMessage().startsWith("The following supplied volumes are not DATADISK attached to the VM: "));
    }

    @Test
    public void checkForUnattachedVolumesRejectsVolumeAttachedToDifferentVm() {
        VolumeVO volume = volume(VOLUME_ID, OTHER_VM_ID, Volume.Type.DATADISK);

        assertThrows(InvalidParameterValueException.class,
                () -> service.checkForUnattachedVolumes(VM_ID, List.of(volume)));
    }

    @Test
    public void checkForUnattachedVolumesRejectsAttachedNonDataDiskVolume() {
        VolumeVO volume = volume(VOLUME_ID, VM_ID, Volume.Type.ROOT);

        assertThrows(InvalidParameterValueException.class,
                () -> service.checkForUnattachedVolumes(VM_ID, List.of(volume)));
    }

    @Test
    public void validateVolumesRejectsNonRootOrDataDiskVolumeTypes() {
        VolumeVO volume = volume(VOLUME_ID, VM_ID, Volume.Type.ISO);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.validateVolumes(List.of(volume)));

        assertTrue(exception.getMessage().contains("Please specify volume of type DATADISK or ROOT"));
    }

    @Test
    public void validateVolumesRejectsDeleteProtectedVolumes() {
        VolumeVO volume = volume(VOLUME_ID, VM_ID, Volume.Type.DATADISK);
        when(volume.isDeleteProtection()).thenReturn(true);
        when(volume.getUuid()).thenReturn("volume-uuid");
        when(volume.getName()).thenReturn("data-volume");

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.validateVolumes(List.of(volume)));

        assertTrue(exception.getMessage().contains("Volume [id = volume-uuid, name = data-volume] has delete protection enabled and cannot be deleted"));
    }

    @Test
    public void checkUnmanagingVMOngoingVolumeSnapshotsRejectsVmWhenRootSnapshotIsOngoing() {
        VolumeVO volume = volume(VOLUME_ID, VM_ID, Volume.Type.ROOT);
        when(volumeDao.findByInstanceAndType(VM_ID, Volume.Type.ROOT)).thenReturn(List.of(volume));
        when(snapshotDao.listByStatus(eq(VOLUME_ID), eq(Snapshot.State.Creating),
                eq(Snapshot.State.CreatedOnPrimary), eq(Snapshot.State.BackingUp))).thenReturn(List.of(mock(SnapshotVO.class)));

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.checkUnmanagingVMOngoingVolumeSnapshots(userVm));

        assertTrue(exception.getMessage().contains("vm unmanage is not permitted"));
    }

    @Test
    public void checkUnmanagingVMVolumesRejectsUnattachedVolumes() {
        VolumeVO volume = volume(VOLUME_ID, null, Volume.Type.DATADISK);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.checkUnmanagingVMVolumes(userVm, List.of(volume)));

        assertTrue(exception.getMessage().contains("it is not attached to VM"));
    }

    @Test
    public void checkUnmanagingVMVolumesRejectsInvalidVolumeTypes() {
        VolumeVO volume = volume(VOLUME_ID, VM_ID, Volume.Type.ISO);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.checkUnmanagingVMVolumes(userVm, List.of(volume)));

        assertTrue(exception.getMessage().contains("ROOT or DATADISK expected"));
    }

    @Test
    public void checkUnmanagingVMVolumesAcceptsAttachedRootAndDataVolumes() {
        service.checkUnmanagingVMVolumes(userVm, List.of(
                volume(VOLUME_ID, VM_ID, Volume.Type.ROOT),
                volume(404L, VM_ID, Volume.Type.DATADISK)));
    }

    private VolumeVO volume(long volumeId, Long instanceId, Volume.Type volumeType) {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getId()).thenReturn(volumeId);
        when(volume.getInstanceId()).thenReturn(instanceId);
        when(volume.getVolumeType()).thenReturn(volumeType);
        return volume;
    }
}
