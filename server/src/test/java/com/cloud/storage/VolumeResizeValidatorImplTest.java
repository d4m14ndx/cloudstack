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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Focused unit tests for {@link VolumeResizeValidatorImpl}.
 * Mirrors the slice extraction tests run against the manager-level
 * spy paths in {@link VolumeApiServiceImplTest}, but exercises the
 * extracted component directly.
 */
@RunWith(MockitoJUnitRunner.class)
public class VolumeResizeValidatorImplTest {

    @Mock
    private VMTemplateDao templateDao;
    @Mock
    private SnapshotDao snapshotDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private UserVmDao userVmDao;
    @Mock
    private ServiceOfferingDao serviceOfferingDao;
    @Mock
    private DataCenterDao dataCenterDao;
    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private AccountManager accountManager;
    @Mock
    private ConfigurationManager configurationManager;

    @Mock
    private VolumeVO volume;
    @Mock
    private DiskOfferingVO existingDiskOffering;
    @Mock
    private DiskOfferingVO newDiskOffering;
    @Mock
    private VMInstanceVO vmInstance;
    @Mock
    private UserVmVO userVm;
    @Mock
    private VMTemplateVO template;
    @Mock
    private ServiceOfferingVO serviceOffering;
    @Mock
    private DataCenterVO dataCenter;

    @InjectMocks
    private VolumeResizeValidatorImpl validator;

    private static final long VOLUME_ID = 200L;
    private static final long ZONE_ID = 300L;
    private static final long TEMPLATE_ID = 400L;
    private static final long INSTANCE_ID = 500L;
    private static final long ACCOUNT_ID = 600L;

    @Before
    public void setUp() {
        Mockito.lenient().when(volume.getId()).thenReturn(VOLUME_ID);
        Mockito.lenient().when(volume.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.lenient().when(volume.getAccountId()).thenReturn(ACCOUNT_ID);
        Mockito.lenient().when(volume.getInstanceId()).thenReturn(INSTANCE_ID);
        Mockito.lenient().when(volume.getUuid()).thenReturn("vol-uuid");
        Mockito.lenient().when(volume.getName()).thenReturn("vol-name");
    }

    // -------- isNotPossibleToResize --------

    @Test
    public void isNotPossibleToResizeReturnsFalseForNonRootVolume() {
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        Mockito.when(volume.getTemplateId()).thenReturn(TEMPLATE_ID);
        Mockito.when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(template);
        Mockito.when(template.getFormat()).thenReturn(ImageFormat.QCOW2);
        Mockito.when(existingDiskOffering.isComputeOnly()).thenReturn(true);
        Mockito.when(existingDiskOffering.getDiskSize()).thenReturn(10L);

        assertFalse(validator.isNotPossibleToResize(volume, existingDiskOffering));
    }

    @Test
    public void isNotPossibleToResizeReturnsFalseWhenRootDiskSizeIsZero() {
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Mockito.when(volume.getTemplateId()).thenReturn(TEMPLATE_ID);
        Mockito.when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(template);
        Mockito.when(template.getFormat()).thenReturn(ImageFormat.QCOW2);
        Mockito.when(existingDiskOffering.isComputeOnly()).thenReturn(true);
        Mockito.when(existingDiskOffering.getDiskSize()).thenReturn(0L);

        assertFalse(validator.isNotPossibleToResize(volume, existingDiskOffering));
    }

    @Test
    public void isNotPossibleToResizeReturnsFalseForIsoFormat() {
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Mockito.when(volume.getTemplateId()).thenReturn(TEMPLATE_ID);
        Mockito.when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(template);
        Mockito.when(template.getFormat()).thenReturn(ImageFormat.ISO);
        Mockito.when(existingDiskOffering.isComputeOnly()).thenReturn(true);
        Mockito.when(existingDiskOffering.getDiskSize()).thenReturn(10L);

        assertFalse(validator.isNotPossibleToResize(volume, existingDiskOffering));
    }

    @Test
    public void isNotPossibleToResizeReturnsTrueForRootComputeOnlyNonIso() {
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Mockito.when(volume.getTemplateId()).thenReturn(TEMPLATE_ID);
        Mockito.when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(template);
        Mockito.when(template.getFormat()).thenReturn(ImageFormat.QCOW2);
        Mockito.when(existingDiskOffering.isComputeOnly()).thenReturn(true);
        Mockito.when(existingDiskOffering.getDiskSize()).thenReturn(10L);

        assertTrue(validator.isNotPossibleToResize(volume, existingDiskOffering));
    }

    @Test
    public void isNotPossibleToResizeReturnsFalseWhenTemplateIdIsNull() {
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Mockito.when(volume.getTemplateId()).thenReturn(null);
        Mockito.when(existingDiskOffering.isComputeOnly()).thenReturn(true);
        Mockito.when(existingDiskOffering.getDiskSize()).thenReturn(10L);

        // format stays null -> isNotIso is false -> overall false
        assertFalse(validator.isNotPossibleToResize(volume, existingDiskOffering));
    }

    // -------- checkIfVolumeIsRootAndVmIsRunning --------

    @Test
    public void checkIfVolumeIsRootAndVmIsRunningPassesWhenSizeUnchanged() {
        Mockito.when(volume.getSize()).thenReturn(10L);
        // type/state never queried because size matches
        validator.checkIfVolumeIsRootAndVmIsRunning(10L, volume, vmInstance);
    }

    @Test
    public void checkIfVolumeIsRootAndVmIsRunningPassesForDataDisk() {
        Mockito.when(volume.getSize()).thenReturn(10L);
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        validator.checkIfVolumeIsRootAndVmIsRunning(20L, volume, vmInstance);
    }

    @Test
    public void checkIfVolumeIsRootAndVmIsRunningPassesForStoppedVm() {
        Mockito.when(volume.getSize()).thenReturn(10L);
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Mockito.when(vmInstance.getState()).thenReturn(State.Stopped);
        validator.checkIfVolumeIsRootAndVmIsRunning(20L, volume, vmInstance);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void checkIfVolumeIsRootAndVmIsRunningThrowsForRunningRootVm() {
        Mockito.when(volume.getSize()).thenReturn(10L);
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Mockito.when(vmInstance.getState()).thenReturn(State.Running);
        Mockito.when(vmInstance.getInstanceName()).thenReturn("i-vm");
        validator.checkIfVolumeIsRootAndVmIsRunning(20L, volume, vmInstance);
    }

    // -------- validateIops --------

    @Test
    public void validateIopsBothNullPasses() {
        validator.validateIops(null, null, Storage.StoragePoolType.NetworkFilesystem);
    }

    @Test
    public void validateIopsBothSetAndMinLeqMaxPasses() {
        validator.validateIops(100L, 200L, Storage.StoragePoolType.NetworkFilesystem);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateIopsMinOnlyThrows() {
        validator.validateIops(100L, null, Storage.StoragePoolType.NetworkFilesystem);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateIopsMaxOnlyThrows() {
        validator.validateIops(null, 100L, Storage.StoragePoolType.NetworkFilesystem);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateIopsMinGreaterThanMaxThrows() {
        validator.validateIops(300L, 200L, Storage.StoragePoolType.NetworkFilesystem);
    }

    @Test
    public void validateIopsPowerFlexIgnoresMin() {
        // For PowerFlex, minIops gets normalised: only maxIops matters
        validator.validateIops(500L, 200L, Storage.StoragePoolType.PowerFlex);
    }

    @Test
    public void validateIopsPowerFlexAllNullPasses() {
        validator.validateIops(null, null, Storage.StoragePoolType.PowerFlex);
    }

    // -------- validateVolumeReadyStateAndHypervisorChecks --------

    @Test(expected = CloudRuntimeException.class)
    public void validateReadyStateThrowsOnOngoingSnapshots() {
        SnapshotVO snap = Mockito.mock(SnapshotVO.class);
        List<SnapshotVO> snaps = new ArrayList<>();
        snaps.add(snap);
        Mockito.when(snapshotDao.listByStatus(VOLUME_ID,
                Snapshot.State.Creating, Snapshot.State.CreatedOnPrimary, Snapshot.State.BackingUp)).thenReturn(snaps);

        validator.validateVolumeReadyStateAndHypervisorChecks(volume, 10L, 20L);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateReadyStateThrowsForUnsupportedHypervisor() {
        Mockito.when(snapshotDao.listByStatus(Mockito.eq(VOLUME_ID),
                Mockito.any(Snapshot.State.class), Mockito.any(Snapshot.State.class), Mockito.any(Snapshot.State.class)))
                .thenReturn(Collections.emptyList());
        Mockito.when(volumeDao.getHypervisorType(VOLUME_ID)).thenReturn(HypervisorType.Hyperv);

        validator.validateVolumeReadyStateAndHypervisorChecks(volume, 10L, 20L);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateReadyStateThrowsWhenVolumeNotReady() {
        Mockito.when(snapshotDao.listByStatus(Mockito.eq(VOLUME_ID),
                Mockito.any(Snapshot.State.class), Mockito.any(Snapshot.State.class), Mockito.any(Snapshot.State.class)))
                .thenReturn(Collections.emptyList());
        Mockito.when(volumeDao.getHypervisorType(VOLUME_ID)).thenReturn(HypervisorType.KVM);
        Mockito.when(volume.getState()).thenReturn(Volume.State.Destroy);

        validator.validateVolumeReadyStateAndHypervisorChecks(volume, 10L, 20L);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateReadyStateThrowsOnVmwareShrink() {
        Mockito.when(snapshotDao.listByStatus(Mockito.eq(VOLUME_ID),
                Mockito.any(Snapshot.State.class), Mockito.any(Snapshot.State.class), Mockito.any(Snapshot.State.class)))
                .thenReturn(Collections.emptyList());
        Mockito.when(volumeDao.getHypervisorType(VOLUME_ID)).thenReturn(HypervisorType.VMware);
        Mockito.when(volume.getState()).thenReturn(Volume.State.Ready);

        validator.validateVolumeReadyStateAndHypervisorChecks(volume, 20L, 10L);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateReadyStateThrowsOnVmwareRootVmRunning() {
        Mockito.when(snapshotDao.listByStatus(Mockito.eq(VOLUME_ID),
                Mockito.any(Snapshot.State.class), Mockito.any(Snapshot.State.class), Mockito.any(Snapshot.State.class)))
                .thenReturn(Collections.emptyList());
        Mockito.when(volumeDao.getHypervisorType(VOLUME_ID)).thenReturn(HypervisorType.VMware);
        Mockito.when(volume.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Mockito.when(userVmDao.findById(INSTANCE_ID)).thenReturn(userVm);
        Mockito.when(userVm.getPowerState()).thenReturn(VirtualMachine.PowerState.PowerOn);

        validator.validateVolumeReadyStateAndHypervisorChecks(volume, 10L, 20L);
    }

    @Test
    public void validateReadyStatePassesForReadyKvmVolume() {
        Mockito.when(snapshotDao.listByStatus(Mockito.eq(VOLUME_ID),
                Mockito.any(Snapshot.State.class), Mockito.any(Snapshot.State.class), Mockito.any(Snapshot.State.class)))
                .thenReturn(Collections.emptyList());
        Mockito.when(volumeDao.getHypervisorType(VOLUME_ID)).thenReturn(HypervisorType.KVM);
        Mockito.when(volume.getState()).thenReturn(Volume.State.Ready);
        // userVm is null - no extra check
        Mockito.when(userVmDao.findById(INSTANCE_ID)).thenReturn(null);

        validator.validateVolumeReadyStateAndHypervisorChecks(volume, 10L, 20L);
    }

    // -------- setNewIopsLimits --------

    @Test
    public void setNewIopsLimitsUsesOfferingValuesWhenNotCustomized() {
        Long[] newMin = new Long[]{null};
        Long[] newMax = new Long[]{null};
        Mockito.when(newDiskOffering.isCustomizedIops()).thenReturn(false);
        Mockito.when(newDiskOffering.getMinIops()).thenReturn(50L);
        Mockito.when(newDiskOffering.getMaxIops()).thenReturn(100L);

        validator.setNewIopsLimits(volume, newDiskOffering, newMin, newMax);

        assertEquals(Long.valueOf(50L), newMin[0]);
        assertEquals(Long.valueOf(100L), newMax[0]);
    }

    @Test
    public void setNewIopsLimitsPreservesVolumeValuesWhenCustomizedAndNotProvided() {
        Long[] newMin = new Long[]{null};
        Long[] newMax = new Long[]{null};
        Mockito.when(newDiskOffering.isCustomizedIops()).thenReturn(true);
        Mockito.when(volume.getMinIops()).thenReturn(40L);
        Mockito.when(volume.getMaxIops()).thenReturn(80L);
        Mockito.when(volume.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);

        validator.setNewIopsLimits(volume, newDiskOffering, newMin, newMax);

        assertEquals(Long.valueOf(40L), newMin[0]);
        assertEquals(Long.valueOf(80L), newMax[0]);
    }

    @Test
    public void setNewIopsLimitsUsesCallerValuesWhenCustomizedAndProvided() {
        Long[] newMin = new Long[]{15L};
        Long[] newMax = new Long[]{30L};
        Mockito.when(newDiskOffering.isCustomizedIops()).thenReturn(true);
        Mockito.when(volume.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);

        validator.setNewIopsLimits(volume, newDiskOffering, newMin, newMax);

        assertEquals(Long.valueOf(15L), newMin[0]);
        assertEquals(Long.valueOf(30L), newMax[0]);
    }

    // -------- checkIfVolumeCanResizeWithNewDiskOffering --------

    @Test(expected = InvalidParameterValueException.class)
    public void checkResizeWithNewOfferingThrowsWhenSameOfferingNoCustomSize() {
        Mockito.when(existingDiskOffering.getId()).thenReturn(1L);
        Mockito.when(newDiskOffering.getId()).thenReturn(1L);
        Mockito.when(newDiskOffering.isCustomized()).thenReturn(false);
        Mockito.when(newDiskOffering.getUuid()).thenReturn("new-uuid");

        validator.checkIfVolumeCanResizeWithNewDiskOffering(volume, existingDiskOffering, newDiskOffering, 10L, vmInstance);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void checkResizeWithNewOfferingThrowsOnStrictnessMismatch() {
        Mockito.when(existingDiskOffering.getId()).thenReturn(1L);
        Mockito.when(newDiskOffering.getId()).thenReturn(2L);
        Mockito.when(existingDiskOffering.getDiskSizeStrictness()).thenReturn(true);
        Mockito.when(newDiskOffering.getDiskSizeStrictness()).thenReturn(false);

        validator.checkIfVolumeCanResizeWithNewDiskOffering(volume, existingDiskOffering, newDiskOffering, 10L, vmInstance);
    }

    @Test
    public void checkResizeWithNewOfferingPassesForCompatibleOffering() {
        Mockito.when(existingDiskOffering.getId()).thenReturn(1L);
        Mockito.when(newDiskOffering.getId()).thenReturn(2L);
        Mockito.when(existingDiskOffering.getDiskSizeStrictness()).thenReturn(false);
        Mockito.when(newDiskOffering.getDiskSizeStrictness()).thenReturn(false);
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        // MatchStoragePoolTagsWithDiskOffering will fall back to default (true) but
        // doesNewDiskOfferingHasTagsAsOldDiskOffering returns true for empty oldTags
        Mockito.when(existingDiskOffering.getTagsArray()).thenReturn(new String[]{});

        validator.checkIfVolumeCanResizeWithNewDiskOffering(volume, existingDiskOffering, newDiskOffering, 10L, vmInstance);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void checkResizeWithNewOfferingThrowsForStrictRootServiceOffering() {
        Mockito.when(existingDiskOffering.getId()).thenReturn(1L);
        Mockito.when(newDiskOffering.getId()).thenReturn(2L);
        Mockito.when(existingDiskOffering.getDiskSizeStrictness()).thenReturn(false);
        Mockito.when(newDiskOffering.getDiskSizeStrictness()).thenReturn(false);
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Mockito.when(existingDiskOffering.getTagsArray()).thenReturn(new String[]{});
        Mockito.when(vmInstance.getServiceOfferingId()).thenReturn(99L);
        Mockito.when(serviceOfferingDao.findById(99L)).thenReturn(serviceOffering);
        Mockito.when(serviceOffering.getDiskOfferingStrictness()).thenReturn(true);

        validator.checkIfVolumeCanResizeWithNewDiskOffering(volume, existingDiskOffering, newDiskOffering, 10L, vmInstance);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void checkResizeWithNewOfferingThrowsOnSizeChangeWhenExistingStrict() {
        Mockito.when(existingDiskOffering.getId()).thenReturn(1L);
        Mockito.when(newDiskOffering.getId()).thenReturn(2L);
        Mockito.when(existingDiskOffering.getDiskSizeStrictness()).thenReturn(true);
        Mockito.when(newDiskOffering.getDiskSizeStrictness()).thenReturn(true);
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        Mockito.when(existingDiskOffering.getTagsArray()).thenReturn(new String[]{});
        Mockito.when(volume.getSize()).thenReturn(5L);
        // newSize differs from volume.size
        validator.checkIfVolumeCanResizeWithNewDiskOffering(volume, existingDiskOffering, newDiskOffering, 10L, vmInstance);
    }

    // -------- validateVolumeResizeWithNewDiskOfferingAndLoad --------

    @Test(expected = InvalidParameterValueException.class)
    public void validateResizeWithNewOfferingThrowsWhenOfferingRemoved() {
        Mockito.when(newDiskOffering.getRemoved()).thenReturn(new java.util.Date());

        Long[] newSize = new Long[]{10L};
        Long[] newMinIops = new Long[]{null};
        Long[] newMaxIops = new Long[]{null};
        Integer[] newHsr = new Integer[]{null};

        validator.validateVolumeResizeWithNewDiskOfferingAndLoad(volume, existingDiskOffering, newDiskOffering, newSize, newMinIops, newMaxIops, newHsr);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateResizeWithNewOfferingThrowsWhenCustomizedAndSizeIsNull() {
        Mockito.when(newDiskOffering.getRemoved()).thenReturn(null);
        AccountVO account = Mockito.mock(AccountVO.class);
        Mockito.when(accountManager.getActiveAccountById(ACCOUNT_ID)).thenReturn(account);
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(dataCenter);
        Mockito.doNothing().when(configurationManager).checkDiskOfferingAccess(account, newDiskOffering, dataCenter);
        Mockito.when(newDiskOffering.getDiskSize()).thenReturn(0L);
        Mockito.when(newDiskOffering.isComputeOnly()).thenReturn(false);
        Mockito.when(newDiskOffering.isCustomized()).thenReturn(true);

        Long[] newSize = new Long[]{null};
        Long[] newMinIops = new Long[]{null};
        Long[] newMaxIops = new Long[]{null};
        Integer[] newHsr = new Integer[]{null};

        validator.validateVolumeResizeWithNewDiskOfferingAndLoad(volume, existingDiskOffering, newDiskOffering, newSize, newMinIops, newMaxIops, newHsr);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateResizeWithNewOfferingThrowsWhenStrictSizeChange() {
        Mockito.when(newDiskOffering.getRemoved()).thenReturn(null);
        AccountVO account = Mockito.mock(AccountVO.class);
        Mockito.when(accountManager.getActiveAccountById(ACCOUNT_ID)).thenReturn(account);
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(dataCenter);
        Mockito.doNothing().when(configurationManager).checkDiskOfferingAccess(account, newDiskOffering, dataCenter);
        // Path: newDiskOffering disk-size > 0 -> use it as newSize
        Mockito.when(newDiskOffering.getDiskSize()).thenReturn(42L);
        Mockito.when(newDiskOffering.isComputeOnly()).thenReturn(false);
        Mockito.when(newDiskOffering.isCustomizedIops()).thenReturn(false);
        Mockito.when(newDiskOffering.getMinIops()).thenReturn(null);
        Mockito.when(newDiskOffering.getMaxIops()).thenReturn(null);
        Mockito.when(existingDiskOffering.getDiskSizeStrictness()).thenReturn(true);
        Mockito.when(volume.getSize()).thenReturn(1L); // differs from newSize 42L

        Long[] newSize = new Long[]{null};
        Long[] newMinIops = new Long[]{null};
        Long[] newMaxIops = new Long[]{null};
        Integer[] newHsr = new Integer[]{null};

        validator.validateVolumeResizeWithNewDiskOfferingAndLoad(volume, existingDiskOffering, newDiskOffering, newSize, newMinIops, newMaxIops, newHsr);
    }

    @Test
    public void validateResizeWithNewOfferingNormalisesCustomizedSizeToBytes() {
        // Path: customized non-compute-only with a caller-supplied size in GiB
        Mockito.when(newDiskOffering.getRemoved()).thenReturn(null);
        AccountVO account = Mockito.mock(AccountVO.class);
        Mockito.when(accountManager.getActiveAccountById(ACCOUNT_ID)).thenReturn(account);
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(dataCenter);
        Mockito.doNothing().when(configurationManager).checkDiskOfferingAccess(account, newDiskOffering, dataCenter);
        Mockito.when(newDiskOffering.getDiskSize()).thenReturn(0L);
        Mockito.when(newDiskOffering.isComputeOnly()).thenReturn(false);
        Mockito.when(newDiskOffering.isCustomized()).thenReturn(true);
        Mockito.when(newDiskOffering.isCustomizedIops()).thenReturn(false);
        Mockito.when(newDiskOffering.getMinIops()).thenReturn(null);
        Mockito.when(newDiskOffering.getMaxIops()).thenReturn(null);
        Mockito.when(existingDiskOffering.getDiskSizeStrictness()).thenReturn(false);
        // volume / new offering distinct -> passes
        Mockito.when(existingDiskOffering.getId()).thenReturn(1L);
        Mockito.when(newDiskOffering.getId()).thenReturn(2L);
        Mockito.when(newDiskOffering.getDiskSizeStrictness()).thenReturn(false);
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        Mockito.when(existingDiskOffering.getTagsArray()).thenReturn(new String[]{});
        Mockito.when(volume.getSize()).thenReturn(10L << 30);
        Mockito.when(vmInstanceDao.findById(INSTANCE_ID)).thenReturn(vmInstance);
        Mockito.lenient().when(vmInstance.getState()).thenReturn(State.Stopped);

        Long[] newSize = new Long[]{5L}; // 5 GiB
        Long[] newMinIops = new Long[]{null};
        Long[] newMaxIops = new Long[]{null};
        Integer[] newHsr = new Integer[]{null};

        validator.validateVolumeResizeWithNewDiskOfferingAndLoad(volume, existingDiskOffering, newDiskOffering, newSize, newMinIops, newMaxIops, newHsr);

        assertEquals(Long.valueOf(5L << 30), newSize[0]);
    }
}
