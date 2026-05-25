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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@RunWith(MockitoJUnitRunner.class)
public class VmStartProfilePreparationServiceImplTest {

    private static final long VM_ID = 42L;
    private static final long CLUSTER_ID = 7L;
    private static final long ZONE_ID = 11L;
    private static final long POD_ID = 13L;

    @InjectMocks
    private VmStartProfilePreparationServiceImpl service;

    @Mock
    private ClusterDetailsDao clusterDetailsDao;
    @Mock
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private NicDao nicsDao;
    @Mock
    private VirtualMachineProfile vmProfile;

    @Test
    public void updateOverCommitRatioForVmProfileSetsRatiosAndDoesNotAddDetailsWhenClusterRatiosAreOneAndVmDetailsAbsent() {
        stubVmProfile();
        stubClusterRatios("1.0", "1.0");
        when(vmInstanceDetailsDao.findDetail(VM_ID, VmDetailConstants.CPU_OVER_COMMIT_RATIO)).thenReturn(null);
        when(vmInstanceDetailsDao.findDetail(VM_ID, VmDetailConstants.MEMORY_OVER_COMMIT_RATIO)).thenReturn(null);

        service.updateOverCommitRatioForVmProfile(vmProfile, CLUSTER_ID);

        verify(vmProfile).setCpuOvercommitRatio(1.0f);
        verify(vmProfile).setMemoryOvercommitRatio(1.0f);
        verify(vmInstanceDetailsDao, never()).addDetail(anyLong(), anyString(), anyString(), anyBoolean());
    }

    @Test
    public void updateOverCommitRatioForVmProfileAddsDetailsWhenClusterRatiosAreGreaterThanOneAndVmDetailsAbsent() {
        stubVmProfile();
        stubClusterRatios("2.0", "1.5");
        when(vmInstanceDetailsDao.findDetail(VM_ID, VmDetailConstants.CPU_OVER_COMMIT_RATIO)).thenReturn(null);
        when(vmInstanceDetailsDao.findDetail(VM_ID, VmDetailConstants.MEMORY_OVER_COMMIT_RATIO)).thenReturn(null);

        service.updateOverCommitRatioForVmProfile(vmProfile, CLUSTER_ID);

        verify(vmInstanceDetailsDao).addDetail(VM_ID, VmDetailConstants.CPU_OVER_COMMIT_RATIO, "2.0", true);
        verify(vmInstanceDetailsDao).addDetail(VM_ID, VmDetailConstants.MEMORY_OVER_COMMIT_RATIO, "1.5", true);
        verify(vmProfile).setCpuOvercommitRatio(2.0f);
        verify(vmProfile).setMemoryOvercommitRatio(1.5f);
    }

    @Test
    public void updateOverCommitRatioForVmProfileReplacesDetailsWhenVmDetailValuesDifferFromClusterDetails() {
        stubVmProfile();
        stubClusterRatios("2.0", "1.5");
        when(vmInstanceDetailsDao.findDetail(VM_ID, VmDetailConstants.CPU_OVER_COMMIT_RATIO))
                .thenReturn(new VMInstanceDetailVO(VM_ID, VmDetailConstants.CPU_OVER_COMMIT_RATIO, "1.0", true));
        when(vmInstanceDetailsDao.findDetail(VM_ID, VmDetailConstants.MEMORY_OVER_COMMIT_RATIO))
                .thenReturn(new VMInstanceDetailVO(VM_ID, VmDetailConstants.MEMORY_OVER_COMMIT_RATIO, "1.0", true));

        service.updateOverCommitRatioForVmProfile(vmProfile, CLUSTER_ID);

        verify(vmInstanceDetailsDao).addDetail(VM_ID, VmDetailConstants.CPU_OVER_COMMIT_RATIO, "2.0", true);
        verify(vmInstanceDetailsDao).addDetail(VM_ID, VmDetailConstants.MEMORY_OVER_COMMIT_RATIO, "1.5", true);
    }

    @Test
    public void areAllVolumesAllocatedReturnsTrueForNullOrEmptyVolumeList() {
        when(volumeDao.findByInstance(VM_ID)).thenReturn(null, List.of());

        assertTrue(service.areAllVolumesAllocated(VM_ID));
        assertTrue(service.areAllVolumesAllocated(VM_ID));
    }

    @Test
    public void areAllVolumesAllocatedReturnsTrueWhenAllVmVolumesAreAllocated() {
        when(volumeDao.findByInstance(VM_ID)).thenReturn(List.of(volume(Volume.State.Allocated), volume(Volume.State.Allocated)));

        assertTrue(service.areAllVolumesAllocated(VM_ID));
    }

    @Test
    public void areAllVolumesAllocatedReturnsFalseWhenAnyVmVolumeIsNotAllocated() {
        when(volumeDao.findByInstance(VM_ID)).thenReturn(List.of(volume(Volume.State.Allocated), volume(Volume.State.Ready)));

        assertFalse(service.areAllVolumesAllocated(VM_ID));
    }

    @Test
    public void conditionallySetPodToDeployInNullsPodWhenAllVolumesAreAllocated() {
        VMInstanceVO vm = vmWithPod();
        when(volumeDao.findByInstance(VM_ID)).thenReturn(List.of(volume(Volume.State.Allocated)));

        service.conditionallySetPodToDeployIn(vm);

        assertNull(vm.getPodIdToDeployIn());
    }

    @Test
    public void conditionallySetPodToDeployInKeepsPodWhenMigrationAcrossClustersIsFalseAndVolumeIsNotAllocated() {
        VMInstanceVO vm = vmWithPod();
        when(volumeDao.findByInstance(VM_ID)).thenReturn(List.of(volume(Volume.State.Ready)));

        service.conditionallySetPodToDeployIn(vm);

        assertTrue(POD_ID == vm.getPodIdToDeployIn());
    }

    @Test
    public void resetVmNicsDeviceIdSortsByExistingDeviceIdAndUpdatesOnlyChangedNics() {
        NicVO first = nic(100L, 0);
        NicVO second = nic(200L, 2);
        NicVO third = nic(300L, 5);
        when(nicsDao.listByVmId(VM_ID)).thenReturn(new ArrayList<>(List.of(third, first, second)));

        service.resetVmNicsDeviceId(VM_ID);

        verify(nicsDao, never()).update(100L, first);
        verify(nicsDao).update(200L, second);
        verify(nicsDao).update(300L, third);
        assertTrue(first.getDeviceId() == 0);
        assertTrue(second.getDeviceId() == 1);
        assertTrue(third.getDeviceId() == 2);
    }

    @Test
    public void logBootModeParametersWithNullParamsIsNoOp() {
        service.logBootModeParameters(null);

        verifyNoInteractions(clusterDetailsDao, vmInstanceDetailsDao, volumeDao, nicsDao);
    }

    private void stubVmProfile() {
        when(vmProfile.getId()).thenReturn(VM_ID);
    }

    private void stubClusterRatios(String cpuRatio, String memoryRatio) {
        when(clusterDetailsDao.findDetail(CLUSTER_ID, VmDetailConstants.CPU_OVER_COMMIT_RATIO))
                .thenReturn(new ClusterDetailsVO(CLUSTER_ID, VmDetailConstants.CPU_OVER_COMMIT_RATIO, cpuRatio));
        when(clusterDetailsDao.findDetail(CLUSTER_ID, VmDetailConstants.MEMORY_OVER_COMMIT_RATIO))
                .thenReturn(new ClusterDetailsVO(CLUSTER_ID, VmDetailConstants.MEMORY_OVER_COMMIT_RATIO, memoryRatio));
    }

    private VolumeVO volume(Volume.State state) {
        VolumeVO volume = new VolumeVO("volume", ZONE_ID, POD_ID, 1L, 1L, VM_ID, "folder", "path", null, 1L, Volume.Type.ROOT);
        volume.setState(state);
        return volume;
    }

    private VMInstanceVO vmWithPod() {
        VMInstanceVO vm = new VMInstanceVO();
        ReflectionTestUtils.setField(vm, "id", VM_ID);
        ReflectionTestUtils.setField(vm, "dataCenterId", ZONE_ID);
        vm.setPodIdToDeployIn(POD_ID);
        return vm;
    }

    private NicVO nic(long id, int deviceId) {
        NicVO nic = new NicVO("reserver", VM_ID, 1L, VirtualMachine.Type.User);
        nic.id = id;
        nic.setDeviceId(deviceId);
        return nic;
    }
}
