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

import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.StartAnswer;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;

@Component
public class VmCommandSpecPostProcessingServiceImpl implements VmCommandSpecPostProcessingService {

    private static final Logger logger = LogManager.getLogger(VmCommandSpecPostProcessingServiceImpl.class);

    @Inject
    protected VolumeDao volumeDao;
    @Inject
    protected VolumeOrchestrationService volumeMgr;

    @Override
    public void setEnterSetupMode(VirtualMachineTO vmTo, Map<VirtualMachineProfile.Param, Object> params) {
        Boolean enterSetup = null;
        if (params != null) {
            enterSetup = (Boolean) params.get(VirtualMachineProfile.Param.BootIntoSetup);
        }
        logger.debug("Orchestrating VM reboot for '{}' {} set to {}", vmTo.getName(), VirtualMachineProfile.Param.BootIntoSetup, enterSetup);
        vmTo.setEnterHardwareSetup(enterSetup == null ? false : enterSetup);
    }

    @Override
    public void addExtraConfig(VirtualMachineTO vmTO) {
        Map<String, String> details = vmTO.getDetails();
        for (String key : details.keySet()) {
            if (key.startsWith(ApiConstants.EXTRA_CONFIG)) {
                vmTO.addExtraConfig(key, details.get(key));
            }
        }
    }

    @Override
    public void prepareManagedDiskPaths(final DiskTO[] disks, final HypervisorType hypervisorType) {
        if (hypervisorType != HypervisorType.KVM) {
            return;
        }

        if (disks != null) {
            for (final DiskTO disk : disks) {
                final Map<String, String> details = disk.getDetails();
                final boolean isManaged = details != null && Boolean.parseBoolean(details.get(DiskTO.MANAGED));

                if (isManaged && disk.getPath() == null) {
                    final Long volumeId = disk.getData().getId();
                    final VolumeVO volume = volumeDao.findById(volumeId);

                    disk.setPath(volume.get_iScsiName());

                    if (disk.getData() instanceof VolumeObjectTO) {
                        final VolumeObjectTO volTo = (VolumeObjectTO)disk.getData();

                        volTo.setPath(volume.get_iScsiName());
                    }

                    volume.setPath(volume.get_iScsiName());

                    volumeDao.update(volumeId, volume);
                }
            }
        }
    }

    @Override
    public void applyStartAnswerDiskMetadata(final DiskTO[] disks, final Map<String, Map<String, String>> iqnToData) {
        if (disks != null && iqnToData != null) {
            for (final DiskTO disk : disks) {
                final Map<String, String> details = disk.getDetails();
                final boolean isManaged = details != null && Boolean.parseBoolean(details.get(DiskTO.MANAGED));

                if (isManaged) {
                    final Long volumeId = disk.getData().getId();
                    final VolumeVO volume = volumeDao.findById(volumeId);
                    final String iScsiName = volume.get_iScsiName();

                    boolean update = false;

                    final Map<String, String> data = iqnToData.get(iScsiName);

                    if (data != null) {
                        final String path = data.get(StartAnswer.PATH);

                        if (path != null) {
                            volume.setPath(path);

                            update = true;
                        }

                        final String imageFormat = data.get(StartAnswer.IMAGE_FORMAT);

                        if (imageFormat != null) {
                            volume.setFormat(ImageFormat.valueOf(imageFormat));

                            update = true;
                        }

                        if (update) {
                            volumeDao.update(volumeId, volume);
                        }
                    }
                }
            }
        }
    }

    @Override
    public void syncDiskChainChange(final StartAnswer answer) {
        final VirtualMachineTO vmSpec = answer.getVirtualMachine();

        for (final DiskTO disk : vmSpec.getDisks()) {
            if (disk.getType() != Volume.Type.ISO) {
                final VolumeObjectTO vol = (VolumeObjectTO)disk.getData();
                final VolumeVO volume = volumeDao.findById(vol.getId());
                if (vmSpec.getDeployAsIsInfo() != null && org.apache.commons.lang3.StringUtils.isNotBlank(vol.getPath())) {
                    volume.setPath(vol.getPath());
                    volumeDao.update(volume.getId(), volume);
                }

                if(vol.getPath() != null) {
                    volumeMgr.updateVolumeDiskChain(vol.getId(), vol.getPath(), vol.getChainInfo(), vol.getUpdatedDataStoreUUID());
                } else {
                    volumeMgr.updateVolumeDiskChain(vol.getId(), volume.getPath(), vol.getChainInfo(), vol.getUpdatedDataStoreUUID());
                }
            }
        }
    }
}
