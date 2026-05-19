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

import static com.cloud.configuration.ConfigurationManagerImpl.MIGRATE_VM_ACROSS_CLUSTERS;

import java.util.Comparator;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@Component
public class VmStartProfilePreparationServiceImpl implements VmStartProfilePreparationService {

    private static final Logger logger = LogManager.getLogger(VmStartProfilePreparationServiceImpl.class);

    @Inject
    protected ClusterDetailsDao clusterDetailsDao;
    @Inject
    protected VMInstanceDetailsDao vmInstanceDetailsDao;
    @Inject
    protected VolumeDao volumeDao;
    @Inject
    protected NicDao nicsDao;

    @Override
    public void updateOverCommitRatioForVmProfile(VirtualMachineProfile vmProfile, long clusterId) {
        final ClusterDetailsVO clusterDetailCpu = clusterDetailsDao.findDetail(clusterId, VmDetailConstants.CPU_OVER_COMMIT_RATIO);
        final ClusterDetailsVO clusterDetailRam = clusterDetailsDao.findDetail(clusterId, VmDetailConstants.MEMORY_OVER_COMMIT_RATIO);
        final float parsedClusterCpuDetailCpu = Float.parseFloat(clusterDetailCpu.getValue());
        final float parsedClusterDetailRam = Float.parseFloat(clusterDetailRam.getValue());
        VMInstanceDetailVO vmDetailCpu = vmInstanceDetailsDao.findDetail(vmProfile.getId(), VmDetailConstants.CPU_OVER_COMMIT_RATIO);
        VMInstanceDetailVO vmDetailRam = vmInstanceDetailsDao.findDetail(vmProfile.getId(), VmDetailConstants.MEMORY_OVER_COMMIT_RATIO);

        if ((vmDetailCpu == null && parsedClusterCpuDetailCpu > 1f) ||
                (vmDetailCpu != null && Float.parseFloat(vmDetailCpu.getValue()) != parsedClusterCpuDetailCpu)) {
            vmInstanceDetailsDao.addDetail(vmProfile.getId(), VmDetailConstants.CPU_OVER_COMMIT_RATIO, clusterDetailCpu.getValue(), true);
        }
        if ((vmDetailRam == null && parsedClusterDetailRam > 1f) ||
                (vmDetailRam != null && Float.parseFloat(vmDetailRam.getValue()) != parsedClusterDetailRam)) {
            vmInstanceDetailsDao.addDetail(vmProfile.getId(), VmDetailConstants.MEMORY_OVER_COMMIT_RATIO, clusterDetailRam.getValue(), true);
        }

        vmProfile.setCpuOvercommitRatio(Float.parseFloat(clusterDetailCpu.getValue()));
        vmProfile.setMemoryOvercommitRatio(Float.parseFloat(clusterDetailRam.getValue()));
    }

    /**
     * Setting pod id to null can result in migration of Volumes across pods. This is not desirable for VMs which
     * have a volume in Ready state (happens when a VM is shutdown and started again).
     * So, we set it to null only when
     * migration of VM across cluster is enabled
     * Or, volumes are still in allocated state for that VM (happens when VM is Starting/deployed for the first time)
     */
    @Override
    public void conditionallySetPodToDeployIn(VMInstanceVO vm) {
        if (MIGRATE_VM_ACROSS_CLUSTERS.valueIn(vm.getDataCenterId()) || areAllVolumesAllocated(vm.getId())) {
            vm.setPodIdToDeployIn(null);
        }
    }

    @Override
    public boolean areAllVolumesAllocated(long vmId) {
        final List<VolumeVO> vols = volumeDao.findByInstance(vmId);
        return CollectionUtils.isEmpty(vols) || vols.stream().allMatch(v -> Volume.State.Allocated.equals(v.getState()));
    }

    @Override
    public void logBootModeParameters(Map<VirtualMachineProfile.Param, Object> params) {
        if (params == null) {
            return;
        }

        StringBuilder msgBuf = new StringBuilder("Uefi params ");
        boolean log = false;
        if (params.get(VirtualMachineProfile.Param.UefiFlag) != null) {
            msgBuf.append(String.format("UefiFlag: %s ", params.get(VirtualMachineProfile.Param.UefiFlag)));
            log = true;
        }
        if (params.get(VirtualMachineProfile.Param.BootType) != null) {
            msgBuf.append(String.format("Boot Type: %s ", params.get(VirtualMachineProfile.Param.BootType)));
            log = true;
        }
        if (params.get(VirtualMachineProfile.Param.BootMode) != null) {
            msgBuf.append(String.format("Boot Mode: %s ", params.get(VirtualMachineProfile.Param.BootMode)));
            log = true;
        }
        if (params.get(VirtualMachineProfile.Param.BootIntoSetup) != null) {
            msgBuf.append(String.format("Boot into Setup: %s ", params.get(VirtualMachineProfile.Param.BootIntoSetup)));
            log = true;
        }
        if (params.get(VirtualMachineProfile.Param.ConsiderLastHost) != null) {
            msgBuf.append(String.format("Consider last host: %s ", params.get(VirtualMachineProfile.Param.ConsiderLastHost)));
            log = true;
        }
        if (log) {
            logger.info(msgBuf.toString());
        }
    }

    @Override
    public void resetVmNicsDeviceId(Long vmId) {
        final List<NicVO> nics = nicsDao.listByVmId(vmId);
        nics.sort(Comparator.comparingInt(NicVO::getDeviceId));
        int deviceId = 0;
        for (final NicVO nic : nics) {
            if (nic.getDeviceId() != deviceId) {
                nic.setDeviceId(deviceId);
                nicsDao.update(nic.getId(), nic);
            }
            deviceId++;
        }
    }
}
