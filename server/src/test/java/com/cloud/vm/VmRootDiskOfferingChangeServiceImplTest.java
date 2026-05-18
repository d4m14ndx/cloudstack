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

import static com.cloud.storage.Volume.IOPS_LIMIT;
import static org.apache.cloudstack.api.ApiConstants.MAX_IOPS;
import static org.apache.cloudstack.api.ApiConstants.MIN_IOPS;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.user.volume.ChangeOfferingForVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.ResizeVolumeCmd;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class VmRootDiskOfferingChangeServiceImplTest {

    private static final long GIB_TO_BYTES = 1024L * 1024L * 1024L;
    private static final long VM_ID = 11L;
    private static final long ZONE_ID = 12L;
    private static final long ROOT_VOLUME_ID = 13L;

    @InjectMocks
    private VmRootDiskOfferingChangeServiceImpl service;

    @Mock
    private VolumeDao _volsDao;
    @Mock
    private DiskOfferingDao _diskOfferingDao;
    @Mock
    private VolumeApiService _volumeService;

    private Map<ConfigKey, Object> originalConfigValues = new HashMap<>();

    @After
    public void afterTest() {
        for (Map.Entry<ConfigKey, Object> entry : originalConfigValues.entrySet()) {
            updateDefaultConfigValue(entry.getKey(), entry.getValue(), true);
        }
    }

    @Test
    public void prepareResizeVolumeCmdRejectsNullRootVolume() {
        DiskOfferingVO currentRootDiskOffering = Mockito.mock(DiskOfferingVO.class);
        DiskOfferingVO newRootDiskOffering = Mockito.mock(DiskOfferingVO.class);

        Assert.assertThrows(InvalidParameterValueException.class,
                () -> service.prepareResizeVolumeCmd(null, currentRootDiskOffering, newRootDiskOffering));
    }

    @Test
    public void prepareResizeVolumeCmdRejectsNullCurrentRootDiskOffering() {
        VolumeVO rootVolume = Mockito.mock(VolumeVO.class);
        DiskOfferingVO newRootDiskOffering = Mockito.mock(DiskOfferingVO.class);

        Assert.assertThrows(InvalidParameterValueException.class,
                () -> service.prepareResizeVolumeCmd(rootVolume, null, newRootDiskOffering));
    }

    @Test
    public void prepareResizeVolumeCmdRejectsNullNewRootDiskOffering() {
        VolumeVO rootVolume = Mockito.mock(VolumeVO.class);
        DiskOfferingVO currentRootDiskOffering = Mockito.mock(DiskOfferingVO.class);

        Assert.assertThrows(InvalidParameterValueException.class,
                () -> service.prepareResizeVolumeCmd(rootVolume, currentRootDiskOffering, null));
    }

    @Test
    public void prepareResizeVolumeCmdUsesNewOfferingIdWhenNewOfferingIsLarger() {
        DiskOfferingVO currentOffering = prepareDiskOffering(5L * GIB_TO_BYTES, 1L, 1L, 2L);
        DiskOfferingVO newOffering = prepareDiskOffering(10L * GIB_TO_BYTES, 2L, 10L, 20L);

        prepareAndRunResizeVolumeTest(2L, 10L, 20L, currentOffering, newOffering);
    }

    @Test
    public void prepareResizeVolumeCmdOmitsNewOfferingIdWhenOfferingSizeIsUnchanged() {
        DiskOfferingVO currentOffering = prepareDiskOffering(5L * GIB_TO_BYTES, 1L, 1L, 2L);

        prepareAndRunResizeVolumeTest(null, 1L, 2L, currentOffering, currentOffering);
    }

    @Test
    public void prepareResizeVolumeCmdOmitsNewOfferingIdWhenNewOfferingRootSizeIsZero() {
        DiskOfferingVO currentOffering = prepareDiskOffering(5L * GIB_TO_BYTES, 1L, 1L, 2L);
        DiskOfferingVO newOffering = prepareDiskOffering(0L, 3L, 100L, 200L);

        prepareAndRunResizeVolumeTest(null, 100L, 200L, currentOffering, newOffering);
    }

    @Test
    public void prepareResizeVolumeCmdRejectsSmallerNonZeroNewOffering() {
        DiskOfferingVO currentOffering = prepareDiskOffering(10L * GIB_TO_BYTES, 2L, 10L, 20L);
        DiskOfferingVO newOffering = prepareDiskOffering(5L * GIB_TO_BYTES, 1L, 1L, 2L);

        Assert.assertThrows(InvalidParameterValueException.class,
                () -> prepareAndRunResizeVolumeTest(2L, 10L, 20L, currentOffering, newOffering));
    }

    @Test
    public void changeDiskOfferingForRootVolumeReturnsBeforeDaoLookupWhenConfigDisabled() throws ResourceAllocationException {
        updateDefaultConfigValue(UserVmManager.AllowDiskOfferingChangeDuringScaleVm, false, false);

        service.changeDiskOfferingForRootVolume(VM_ID, Mockito.mock(DiskOfferingVO.class), new HashMap<>(), ZONE_ID);

        Mockito.verifyNoInteractions(_volsDao, _diskOfferingDao, _volumeService);
    }

    @Test
    public void changeDiskOfferingForRootVolumeDoesNothingWhenNoRootVolumesExist() throws ResourceAllocationException {
        enableDiskOfferingChangeDuringScaleVm();
        Mockito.when(_volsDao.findReadyAndAllocatedRootVolumesByInstance(VM_ID)).thenReturn(Collections.emptyList());

        service.changeDiskOfferingForRootVolume(VM_ID, Mockito.mock(DiskOfferingVO.class), new HashMap<>(), ZONE_ID);

        Mockito.verify(_volsDao).findReadyAndAllocatedRootVolumesByInstance(VM_ID);
        Mockito.verifyNoInteractions(_diskOfferingDao, _volumeService);
    }

    @Test
    public void changeDiskOfferingForRootVolumeSkipsSameNonCustomizedOffering() throws ResourceAllocationException {
        enableDiskOfferingChangeDuringScaleVm();
        VolumeVO rootVolume = prepareRootVolume(1L, 10L * GIB_TO_BYTES);
        DiskOfferingVO currentOffering = prepareDiskOffering(10L * GIB_TO_BYTES, 1L, 1L, 2L);
        DiskOfferingVO newOffering = prepareDiskOffering(10L * GIB_TO_BYTES, 1L, 1L, 2L);
        Mockito.when(newOffering.isCustomized()).thenReturn(false);
        prepareRootVolumeLookup(rootVolume, currentOffering);

        service.changeDiskOfferingForRootVolume(VM_ID, newOffering, new HashMap<>(), ZONE_ID);

        Mockito.verify(_volsDao, never()).getHypervisorType(anyLong());
        Mockito.verifyNoInteractions(_volumeService);
    }

    @Test
    public void changeDiskOfferingForRootVolumeSkipsSameCustomizedOfferingWithMatchingRequestedSize() throws ResourceAllocationException {
        enableDiskOfferingChangeDuringScaleVm();
        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(ApiConstants.ROOT_DISK_SIZE, "7");
        VolumeVO rootVolume = prepareRootVolume(1L, 7L << 30);
        DiskOfferingVO currentOffering = prepareDiskOffering(7L * GIB_TO_BYTES, 1L, 1L, 2L);
        DiskOfferingVO newOffering = prepareDiskOffering(7L * GIB_TO_BYTES, 1L, 1L, 2L);
        Mockito.when(newOffering.isCustomized()).thenReturn(true);
        prepareRootVolumeLookup(rootVolume, currentOffering);

        service.changeDiskOfferingForRootVolume(VM_ID, newOffering, customParameters, ZONE_ID);

        Mockito.verify(_volsDao, never()).getHypervisorType(anyLong());
        Mockito.verifyNoInteractions(_volumeService);
    }

    @Test
    public void changeDiskOfferingForRootVolumeBuildsChangeOfferingCommandForNonSimulator() throws ResourceAllocationException {
        enableDiskOfferingChangeDuringScaleVm();
        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(MIN_IOPS, "123");
        customParameters.put(MAX_IOPS, "999");
        customParameters.put(IOPS_LIMIT, "456");
        customParameters.put(ApiConstants.ROOT_DISK_SIZE, "17");
        customParameters.put(ApiConstants.AUTO_MIGRATE, "true");
        customParameters.put(ApiConstants.SHRINK_OK, "true");
        VolumeVO rootVolume = prepareRootVolume(1L, 10L * GIB_TO_BYTES);
        DiskOfferingVO currentOffering = prepareDiskOffering(10L * GIB_TO_BYTES, 1L, 1L, 2L);
        DiskOfferingVO newOffering = prepareDiskOffering(17L * GIB_TO_BYTES, 2L, 10L, 20L);
        prepareRootVolumeLookup(rootVolume, currentOffering, HypervisorType.KVM);
        Mockito.when(_volumeService.changeDiskOfferingForVolume(any(ChangeOfferingForVolumeCmd.class)))
                .thenReturn(Mockito.mock(Volume.class));

        service.changeDiskOfferingForRootVolume(VM_ID, newOffering, customParameters, ZONE_ID);

        ChangeOfferingForVolumeCmd command = captureChangeOfferingForVolumeCmd();
        Assert.assertEquals(ROOT_VOLUME_ID, command.getId().longValue());
        Assert.assertEquals(2L, command.getNewDiskOfferingId().longValue());
        Assert.assertEquals(123L, command.getMinIops().longValue());
        Assert.assertEquals(456L, command.getMaxIops().longValue());
        Assert.assertEquals(17L, command.getSize().longValue());
        Assert.assertTrue(command.getAutoMigrate());
        Assert.assertTrue(command.isShrinkOk());
    }

    @Test
    public void changeDiskOfferingForRootVolumeUsesMaxIopsWhenIopsLimitIsAbsent() throws ResourceAllocationException {
        enableDiskOfferingChangeDuringScaleVm();
        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(MAX_IOPS, "789");
        VolumeVO rootVolume = prepareRootVolume(1L, 10L * GIB_TO_BYTES);
        DiskOfferingVO currentOffering = prepareDiskOffering(10L * GIB_TO_BYTES, 1L, 1L, 2L);
        DiskOfferingVO newOffering = prepareDiskOffering(10L * GIB_TO_BYTES, 2L, 10L, 20L);
        prepareRootVolumeLookup(rootVolume, currentOffering, HypervisorType.KVM);
        Mockito.when(_volumeService.changeDiskOfferingForVolume(any(ChangeOfferingForVolumeCmd.class)))
                .thenReturn(Mockito.mock(Volume.class));

        service.changeDiskOfferingForRootVolume(VM_ID, newOffering, customParameters, ZONE_ID);

        ChangeOfferingForVolumeCmd command = captureChangeOfferingForVolumeCmd();
        Assert.assertEquals(789L, command.getMaxIops().longValue());
    }

    @Test
    public void changeDiskOfferingForRootVolumeThrowsWhenVolumeServiceReturnsNull() {
        enableDiskOfferingChangeDuringScaleVm();
        VolumeVO rootVolume = prepareRootVolume(1L, 10L * GIB_TO_BYTES);
        DiskOfferingVO currentOffering = prepareDiskOffering(10L * GIB_TO_BYTES, 1L, 1L, 2L);
        DiskOfferingVO newOffering = prepareDiskOffering(10L * GIB_TO_BYTES, 2L, 10L, 20L);
        prepareRootVolumeLookup(rootVolume, currentOffering, HypervisorType.KVM);

        CloudRuntimeException exception = Assert.assertThrows(CloudRuntimeException.class,
                () -> service.changeDiskOfferingForRootVolume(VM_ID, newOffering, new HashMap<>(), ZONE_ID));
        Assert.assertEquals("Failed to change disk offering of the root volume", exception.getMessage());
    }

    @Test
    public void changeDiskOfferingForRootVolumeRejectsSimulatorResizeToDifferentNonZeroSize() {
        enableDiskOfferingChangeDuringScaleVm();
        VolumeVO rootVolume = prepareRootVolume(1L, 10L * GIB_TO_BYTES);
        DiskOfferingVO currentOffering = prepareDiskOffering(10L * GIB_TO_BYTES, 1L, 1L, 2L);
        DiskOfferingVO newOffering = prepareDiskOffering(12L * GIB_TO_BYTES, 2L, 10L, 20L);
        prepareRootVolumeLookup(rootVolume, currentOffering, HypervisorType.Simulator);

        InvalidParameterValueException exception = Assert.assertThrows(InvalidParameterValueException.class,
                () -> service.changeDiskOfferingForRootVolume(VM_ID, newOffering, new HashMap<>(), ZONE_ID));
        Assert.assertTrue(exception.getMessage().contains("does not support volume resize"));
    }

    @Test
    public void changeDiskOfferingForRootVolumeAllowsSimulatorWhenSizeIsUnchanged() throws ResourceAllocationException {
        enableDiskOfferingChangeDuringScaleVm();
        VolumeVO rootVolume = prepareRootVolume(1L, 10L * GIB_TO_BYTES);
        DiskOfferingVO currentOffering = prepareDiskOffering(10L * GIB_TO_BYTES, 1L, 1L, 2L);
        DiskOfferingVO newOffering = prepareDiskOffering(10L * GIB_TO_BYTES, 2L, 10L, 20L);
        prepareRootVolumeLookup(rootVolume, currentOffering, HypervisorType.Simulator);

        service.changeDiskOfferingForRootVolume(VM_ID, newOffering, new HashMap<>(), ZONE_ID);

        Mockito.verifyNoInteractions(_volumeService);
    }

    private void prepareAndRunResizeVolumeTest(Long expectedOfferingId, long expectedMinIops, long expectedMaxIops,
            DiskOfferingVO currentRootDiskOffering, DiskOfferingVO newRootDiskOffering) {
        VolumeVO rootVolume = Mockito.mock(VolumeVO.class);
        Mockito.when(rootVolume.getId()).thenReturn(ROOT_VOLUME_ID);

        ResizeVolumeCmd resizeVolumeCmd = service.prepareResizeVolumeCmd(rootVolume, currentRootDiskOffering, newRootDiskOffering);

        Assert.assertEquals(ROOT_VOLUME_ID, resizeVolumeCmd.getId().longValue());
        Assert.assertEquals(expectedOfferingId, resizeVolumeCmd.getNewDiskOfferingId());
        Assert.assertEquals(expectedMinIops, resizeVolumeCmd.getMinIops().longValue());
        Assert.assertEquals(expectedMaxIops, resizeVolumeCmd.getMaxIops().longValue());
    }

    private DiskOfferingVO prepareDiskOffering(long rootSize, long diskOfferingId, Long offeringMinIops, Long offeringMaxIops) {
        DiskOfferingVO diskOffering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOffering.getDiskSize()).thenReturn(rootSize);
        Mockito.when(diskOffering.getId()).thenReturn(diskOfferingId);
        Mockito.when(diskOffering.getMinIops()).thenReturn(offeringMinIops);
        Mockito.when(diskOffering.getMaxIops()).thenReturn(offeringMaxIops);
        return diskOffering;
    }

    private VolumeVO prepareRootVolume(long diskOfferingId, Long size) {
        VolumeVO rootVolume = Mockito.mock(VolumeVO.class);
        Mockito.when(rootVolume.getId()).thenReturn(ROOT_VOLUME_ID);
        Mockito.when(rootVolume.getDiskOfferingId()).thenReturn(diskOfferingId);
        Mockito.when(rootVolume.getSize()).thenReturn(size);
        return rootVolume;
    }

    private void prepareRootVolumeLookup(VolumeVO rootVolume, DiskOfferingVO currentOffering) {
        Mockito.when(_volsDao.findReadyAndAllocatedRootVolumesByInstance(VM_ID)).thenReturn(Collections.singletonList(rootVolume));
        Mockito.when(_diskOfferingDao.findById(rootVolume.getDiskOfferingId())).thenReturn(currentOffering);
    }

    private void prepareRootVolumeLookup(VolumeVO rootVolume, DiskOfferingVO currentOffering, HypervisorType hypervisorType) {
        prepareRootVolumeLookup(rootVolume, currentOffering);
        Mockito.when(_volsDao.getHypervisorType(ROOT_VOLUME_ID)).thenReturn(hypervisorType);
    }

    private ChangeOfferingForVolumeCmd captureChangeOfferingForVolumeCmd() throws ResourceAllocationException {
        ArgumentCaptor<ChangeOfferingForVolumeCmd> commandCaptor = ArgumentCaptor.forClass(ChangeOfferingForVolumeCmd.class);
        Mockito.verify(_volumeService).changeDiskOfferingForVolume(commandCaptor.capture());
        return commandCaptor.getValue();
    }

    private void enableDiskOfferingChangeDuringScaleVm() {
        updateDefaultConfigValue(UserVmManager.AllowDiskOfferingChangeDuringScaleVm, true, false);
    }

    private void updateDefaultConfigValue(final ConfigKey configKey, final Object o, boolean revert) {
        try {
            final String name = "_defaultValue";
            Field f = ConfigKey.class.getDeclaredField(name);
            f.setAccessible(true);
            String stringVal = String.valueOf(o);
            if (!revert) {
                originalConfigValues.put(configKey, f.get(configKey));
            }
            f.set(configKey, stringVal);
        } catch (IllegalAccessException | NoSuchFieldException e) {
            Assert.fail("Failed to mock config " + configKey.key() + " value due to " + e.getMessage());
        }
    }
}
