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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Map;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.StartAnswer;
import com.cloud.agent.api.StartCommand;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;

@RunWith(MockitoJUnitRunner.class)
public class VmCommandSpecPostProcessingServiceImplTest {

    @InjectMocks
    private VmCommandSpecPostProcessingServiceImpl service;

    @Mock
    private VolumeDao volumeDao;
    @Mock
    private VolumeOrchestrationService volumeMgr;
    @Mock
    private Host host;

    private static final long VOLUME_ID = 42L;
    private static final String IQN = "iqn.2026-05.example:volume-42";
    private static final String ANSWER_PATH = "answer-path";
    private static final String CHAIN_INFO = "chain-info";
    private static final String DATASTORE_UUID = "datastore-uuid";

    @Test
    public void setEnterSetupMode_nullParams_setsFalse() {
        VirtualMachineTO vmTo = newVmTo();

        service.setEnterSetupMode(vmTo, null);

        assertFalse(vmTo.isEnterHardwareSetup());
    }

    @Test
    public void setEnterSetupMode_trueParam_setsTrue() {
        VirtualMachineTO vmTo = newVmTo();

        service.setEnterSetupMode(vmTo, Map.of(VirtualMachineProfile.Param.BootIntoSetup, Boolean.TRUE));

        assertTrue(vmTo.isEnterHardwareSetup());
    }

    @Test
    public void addExtraConfig_copiesOnlyExtraConfigDetails() {
        VirtualMachineTO vmTo = newVmTo();
        vmTo.setDetails(Map.of(
                ApiConstants.EXTRA_CONFIG + "-0", "value-0",
                ApiConstants.EXTRA_CONFIG + ".nested", "value-1",
                "unrelated", "ignored"));

        service.addExtraConfig(vmTo);

        assertEquals(2, vmTo.getExtraConfig().size());
        assertEquals("value-0", vmTo.getExtraConfig().get(ApiConstants.EXTRA_CONFIG + "-0"));
        assertEquals("value-1", vmTo.getExtraConfig().get(ApiConstants.EXTRA_CONFIG + ".nested"));
        assertNull(vmTo.getExtraConfig().get("unrelated"));
    }

    @Test
    public void addExtraConfig_nullDetails_preservesExistingNullPointerBehavior() {
        VirtualMachineTO vmTo = newVmTo();
        vmTo.setDetails(null);

        assertThrows(NullPointerException.class, () -> service.addExtraConfig(vmTo));
    }

    @Test
    public void prepareManagedKvmDiskPath_setsDiskVolumeObjectAndVolumePathWhenMissing() {
        VolumeObjectTO volumeObjectTO = newVolumeObjectTo(VOLUME_ID);
        DiskTO disk = newDisk(volumeObjectTO, Volume.Type.ROOT, null, true);
        VolumeVO volume = newVolume(IQN, null);
        when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);

        service.prepareManagedDiskPaths(new DiskTO[] { disk }, HypervisorType.KVM);

        assertEquals(IQN, disk.getPath());
        assertEquals(IQN, volumeObjectTO.getPath());
        assertEquals(IQN, volume.getPath());
        verify(volumeDao).update(VOLUME_ID, volume);
    }

    @Test
    public void prepareManagedKvmDiskPath_nonKvmNoops() {
        VolumeObjectTO volumeObjectTO = newVolumeObjectTo(VOLUME_ID);
        DiskTO disk = newDisk(volumeObjectTO, Volume.Type.ROOT, null, true);

        service.prepareManagedDiskPaths(new DiskTO[] { disk }, HypervisorType.VMware);

        assertNull(disk.getPath());
        assertNull(volumeObjectTO.getPath());
        verify(volumeDao, never()).findById(VOLUME_ID);
    }

    @Test
    public void prepareManagedKvmDiskPath_existingPathDoesNotPersist() {
        VolumeObjectTO volumeObjectTO = newVolumeObjectTo(VOLUME_ID);
        DiskTO disk = newDisk(volumeObjectTO, Volume.Type.ROOT, "existing-path", true);

        service.prepareManagedDiskPaths(new DiskTO[] { disk }, HypervisorType.KVM);

        assertEquals("existing-path", disk.getPath());
        verify(volumeDao, never()).findById(VOLUME_ID);
    }

    @Test
    public void prepareManagedKvmDiskPath_unmanagedDiskNoops() {
        VolumeObjectTO volumeObjectTO = newVolumeObjectTo(VOLUME_ID);
        DiskTO disk = newDisk(volumeObjectTO, Volume.Type.ROOT, null, false);

        service.prepareManagedDiskPaths(new DiskTO[] { disk }, HypervisorType.KVM);

        assertNull(disk.getPath());
        verify(volumeDao, never()).findById(VOLUME_ID);
    }

    @Test
    public void applyStartAnswerDiskMetadata_updatesPathAndImageFormatForMatchingIqn() {
        VolumeObjectTO volumeObjectTO = newVolumeObjectTo(VOLUME_ID);
        DiskTO disk = newDisk(volumeObjectTO, Volume.Type.ROOT, null, true);
        VolumeVO volume = newVolume(IQN, null);
        when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);

        service.applyStartAnswerDiskMetadata(new DiskTO[] { disk },
                Map.of(IQN, Map.of(StartAnswer.PATH, ANSWER_PATH, StartAnswer.IMAGE_FORMAT, ImageFormat.QCOW2.name())));

        assertEquals(ANSWER_PATH, volume.getPath());
        assertEquals(ImageFormat.QCOW2, volume.getFormat());
        verify(volumeDao).update(VOLUME_ID, volume);
    }

    @Test
    public void applyStartAnswerDiskMetadata_noIqnDataNoops() {
        VolumeObjectTO volumeObjectTO = newVolumeObjectTo(VOLUME_ID);
        DiskTO disk = newDisk(volumeObjectTO, Volume.Type.ROOT, null, true);
        VolumeVO volume = newVolume(IQN, null);
        when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);

        service.applyStartAnswerDiskMetadata(new DiskTO[] { disk }, Map.of("other-iqn", Map.of(StartAnswer.PATH, ANSWER_PATH)));

        assertNull(volume.getPath());
        verify(volumeDao, never()).update(eq(VOLUME_ID), eq(volume));
    }

    @Test
    public void applyStartAnswerDiskMetadata_nullIqnMapNoops() {
        VolumeObjectTO volumeObjectTO = newVolumeObjectTo(VOLUME_ID);
        DiskTO disk = newDisk(volumeObjectTO, Volume.Type.ROOT, null, true);

        service.applyStartAnswerDiskMetadata(new DiskTO[] { disk }, null);

        verify(volumeDao, never()).findById(VOLUME_ID);
    }

    @Test
    public void syncDiskChainChange_skipsIsoDisks() {
        VolumeObjectTO volumeObjectTO = newVolumeObjectTo(VOLUME_ID);
        DiskTO disk = newDisk(volumeObjectTO, Volume.Type.ISO, null, true);
        VirtualMachineTO vmTo = newVmTo();
        vmTo.setDisks(new DiskTO[] { disk });

        service.syncDiskChainChange(newStartAnswer(vmTo));

        verify(volumeDao, never()).findById(VOLUME_ID);
        verify(volumeMgr, never()).updateVolumeDiskChain(eq(VOLUME_ID), eq(null), eq(CHAIN_INFO), eq(DATASTORE_UUID));
    }

    @Test
    public void syncDiskChainChange_usesAnswerPathWhenPresent() {
        VolumeObjectTO volumeObjectTO = newVolumeObjectTo(VOLUME_ID);
        volumeObjectTO.setPath(ANSWER_PATH);
        DiskTO disk = newDisk(volumeObjectTO, Volume.Type.ROOT, null, true);
        VirtualMachineTO vmTo = newVmTo();
        vmTo.setDisks(new DiskTO[] { disk });
        VolumeVO volume = newVolume(IQN, "persisted-path");
        when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);

        service.syncDiskChainChange(newStartAnswer(vmTo));

        verify(volumeMgr).updateVolumeDiskChain(VOLUME_ID, ANSWER_PATH, CHAIN_INFO, DATASTORE_UUID);
    }

    @Test
    public void syncDiskChainChange_usesPersistedVolumePathWhenAnswerPathMissing() {
        VolumeObjectTO volumeObjectTO = newVolumeObjectTo(VOLUME_ID);
        DiskTO disk = newDisk(volumeObjectTO, Volume.Type.ROOT, null, true);
        VirtualMachineTO vmTo = newVmTo();
        vmTo.setDisks(new DiskTO[] { disk });
        VolumeVO volume = newVolume(IQN, "persisted-path");
        when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);

        service.syncDiskChainChange(newStartAnswer(vmTo));

        verify(volumeMgr).updateVolumeDiskChain(VOLUME_ID, "persisted-path", CHAIN_INFO, DATASTORE_UUID);
    }

    @Test
    public void syncDiskChainChange_deployAsIsPersistsVolumeObjectPath() {
        VolumeObjectTO volumeObjectTO = newVolumeObjectTo(VOLUME_ID);
        volumeObjectTO.setPath(ANSWER_PATH);
        DiskTO disk = newDisk(volumeObjectTO, Volume.Type.ROOT, null, true);
        VirtualMachineTO vmTo = newVmTo();
        vmTo.setDisks(new DiskTO[] { disk });
        vmTo.setDeployAsIsInfo(new com.cloud.agent.api.to.DeployAsIsInfoTO());
        VolumeVO volume = newVolume(IQN, "persisted-path");
        when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);

        service.syncDiskChainChange(newStartAnswer(vmTo));

        ArgumentCaptor<VolumeVO> volumeCaptor = ArgumentCaptor.forClass(VolumeVO.class);
        verify(volumeDao).update(eq(VOLUME_ID), volumeCaptor.capture());
        assertEquals(ANSWER_PATH, volumeCaptor.getValue().getPath());
        verify(volumeMgr).updateVolumeDiskChain(VOLUME_ID, ANSWER_PATH, CHAIN_INFO, DATASTORE_UUID);
    }

    private VirtualMachineTO newVmTo() {
        VirtualMachineTO vmTo = new VirtualMachineTO(1L, "i-2-VM", VirtualMachine.Type.User, 1, 1000, 1024L, 1024L, null, "Other", false, false, null);
        return vmTo;
    }

    private VolumeObjectTO newVolumeObjectTo(long id) {
        VolumeObjectTO volumeObjectTO = new VolumeObjectTO();
        volumeObjectTO.setId(id);
        volumeObjectTO.setPath(null);
        volumeObjectTO.setChainInfo(CHAIN_INFO);
        volumeObjectTO.setUpdatedDataStoreUUID(DATASTORE_UUID);
        return volumeObjectTO;
    }

    private DiskTO newDisk(VolumeObjectTO volumeObjectTO, Volume.Type type, String path, boolean managed) {
        DiskTO disk = new DiskTO(volumeObjectTO, 0L, path, type);
        disk.setDetails(Map.of(DiskTO.MANAGED, Boolean.toString(managed)));
        return disk;
    }

    private VolumeVO newVolume(String iScsiName, String path) {
        VolumeVO volume = org.mockito.Mockito.spy(new VolumeVO() {});
        when(volume.getId()).thenReturn(VOLUME_ID);
        volume.set_iScsiName(iScsiName);
        volume.setPath(path);
        return volume;
    }

    private StartAnswer newStartAnswer(VirtualMachineTO vmTo) {
        when(host.getPrivateIpAddress()).thenReturn("192.0.2.10");
        StartCommand command = new StartCommand(vmTo, host, true);
        return new StartAnswer(command);
    }
}
