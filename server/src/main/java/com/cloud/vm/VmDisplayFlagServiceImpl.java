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

import java.util.List;

import jakarta.inject.Inject;

import org.springframework.stereotype.Component;

import com.cloud.offering.ServiceOffering;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.ResourceLimitService;

/**
 * {@code displayVm} flag mutation helper extracted from
 * {@link UserVmManagerImpl}. Persists the flag, adjusts the owner's VM
 * resource count (subject to
 * {@link VirtualMachineManager#ResourceCountRunningVMsonly}), publishes
 * the state-aware bulk usage events through {@link VmUsageEventPublisher},
 * and cascades the new value to the VM's ROOT volume and every DATADISK.
 *
 * @see VmDisplayFlagService
 */
@Component
public class VmDisplayFlagServiceImpl implements VmDisplayFlagService {

    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private ResourceLimitService resourceLimitMgr;
    @Inject
    private VolumeDao volsDao;
    @Inject
    private VolumeApiService volumeService;
    @Inject
    private VmUsageEventPublisher vmUsageEventPublisher;

    @Override
    public void applyDisplayFlag(Boolean isDisplayVm, Long vmId, UserVmVO vmInstance) {
        vmInstance.setDisplayVm(isDisplayVm);

        // Resource limit changes
        ServiceOffering offering = serviceOfferingDao.findByIdIncludingRemoved(vmInstance.getId(), vmInstance.getServiceOfferingId());
        VMTemplateVO template = templateDao.findByIdIncludingRemoved(vmInstance.getTemplateId());
        if (isDisplayVm) {
            resourceCountIncrement(vmInstance.getAccountId(), true, offering, template);
        } else {
            resourceCountDecrement(vmInstance.getAccountId(), true, offering, template);
        }

        // Usage
        vmUsageEventPublisher.saveUsageEvent(vmInstance);

        // take care of the root volume as well.
        List<VolumeVO> rootVols = volsDao.findByInstanceAndType(vmId, Volume.Type.ROOT);
        if (!rootVols.isEmpty()) {
            volumeService.updateDisplay(rootVols.get(0), isDisplayVm);
        }

        // take care of the data volumes as well.
        List<VolumeVO> dataVols = volsDao.findByInstanceAndType(vmId, Volume.Type.DATADISK);
        for (Volume dataVol : dataVols) {
            volumeService.updateDisplay(dataVol, isDisplayVm);
        }
    }

    /**
     * Mirror of the protected {@code resourceCountIncrement} that
     * remains on {@link UserVmManagerImpl}: when the
     * {@code resource.count.running.vms.only} global toggle is on,
     * resource counts are managed on start/stop instead, so we skip
     * the increment here.
     */
    protected void resourceCountIncrement(long accountId, Boolean displayVm, ServiceOffering serviceOffering, VirtualMachineTemplate template) {
        if (!VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
            resourceLimitMgr.incrementVmResourceCount(accountId, displayVm, serviceOffering, template);
        }
    }

    /**
     * Symmetric to {@link #resourceCountIncrement} — see that method
     * for why this is gated on the global config toggle.
     */
    protected void resourceCountDecrement(long accountId, Boolean displayVm, ServiceOffering serviceOffering, VirtualMachineTemplate template) {
        if (!VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
            resourceLimitMgr.decrementVmResourceCount(accountId, displayVm, serviceOffering, template);
        }
    }
}
