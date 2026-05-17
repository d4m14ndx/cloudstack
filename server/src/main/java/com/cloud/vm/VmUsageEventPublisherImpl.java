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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.springframework.stereotype.Component;

import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.event.UsageEventVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.NicDao;

/**
 * VM-level and per-NIC usage-event publication, plus the state-aware
 * bulk-publish fired when {@code displayVm} flips. Extracted from
 * {@link UserVmManagerImpl}.
 *
 * @see VmUsageEventPublisher
 */
@Component
public class VmUsageEventPublisherImpl implements VmUsageEventPublisher {

    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private NicDao nicDao;
    @Inject
    private NetworkDao networkDao;

    @Override
    public void generateUsageEvent(VirtualMachine vm, boolean isDisplay, String eventType) {
        ServiceOfferingVO serviceOffering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
        if (!serviceOffering.isDynamic()) {
            UsageEventUtils.publishUsageEvent(eventType, vm.getAccountId(), vm.getDataCenterId(), vm.getId(),
                    vm.getHostName(), serviceOffering.getId(), vm.getTemplateId(), vm.getHypervisorType().toString(),
                    VirtualMachine.class.getName(), vm.getUuid(), isDisplay);
        } else {
            Map<String, String> customParameters = new HashMap<>();
            customParameters.put(UsageEventVO.DynamicParameters.cpuNumber.name(), serviceOffering.getCpu().toString());
            customParameters.put(UsageEventVO.DynamicParameters.cpuSpeed.name(), serviceOffering.getSpeed().toString());
            customParameters.put(UsageEventVO.DynamicParameters.memory.name(), serviceOffering.getRamSize().toString());
            UsageEventUtils.publishUsageEvent(eventType, vm.getAccountId(), vm.getDataCenterId(), vm.getId(),
                    vm.getHostName(), serviceOffering.getId(), vm.getTemplateId(), vm.getHypervisorType().toString(),
                    VirtualMachine.class.getName(), vm.getUuid(), customParameters, isDisplay);
        }
    }

    @Override
    public void generateNetworkUsageForVm(VirtualMachine vm, boolean isDisplay, String eventType) {
        List<NicVO> nics = nicDao.listByVmId(vm.getId());
        for (NicVO nic : nics) {
            NetworkVO network = networkDao.findById(nic.getNetworkId());
            long isDefault = (nic.isDefaultNic()) ? 1 : 0;
            UsageEventUtils.publishUsageEvent(eventType, vm.getAccountId(), vm.getDataCenterId(), vm.getId(),
                    Long.toString(nic.getId()), network.getNetworkOfferingId(), null, isDefault,
                    vm.getClass().getName(), vm.getUuid(), isDisplay);
        }
    }

    @Override
    public void saveUsageEvent(UserVmVO vm) {
        // If vm not destroyed
        if (vm.getState() != State.Destroyed && vm.getState() != State.Expunging && vm.getState() != State.Error) {

            if (vm.isDisplayVm()) {
                //1. Allocated VM Usage Event
                generateUsageEvent(vm, true, EventTypes.EVENT_VM_CREATE);

                if (vm.getState() == State.Running || vm.getState() == State.Stopping) {
                    //2. Running VM Usage Event
                    generateUsageEvent(vm, true, EventTypes.EVENT_VM_START);

                    // 3. Network offering usage
                    generateNetworkUsageForVm(vm, true, EventTypes.EVENT_NETWORK_OFFERING_ASSIGN);
                }
            } else {
                //1. Allocated VM Usage Event
                generateUsageEvent(vm, true, EventTypes.EVENT_VM_DESTROY);

                if (vm.getState() == State.Running || vm.getState() == State.Stopping) {
                    //2. Running VM Usage Event
                    generateUsageEvent(vm, true, EventTypes.EVENT_VM_STOP);

                    // 3. Network offering usage
                    generateNetworkUsageForVm(vm, true, EventTypes.EVENT_NETWORK_OFFERING_REMOVE);
                }
            }
        }
    }
}
