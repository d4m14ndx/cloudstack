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

import org.apache.commons.collections.MapUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.StopCommand;
import com.cloud.agent.api.to.DpdkTO;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VmStopCommandServiceImpl implements VmStopCommandService {

    private static final Logger logger = LogManager.getLogger(VmStopCommandServiceImpl.class);

    @Inject
    protected NicDao nicsDao;
    @Inject
    protected VMInstanceDao vmDao;
    @Inject
    protected VmVlanPersistenceMappingService vmVlanPersistenceMappingService;

    @Override
    public void decorateStopCommandWithNetworkDetails(StopCommand command, VirtualMachine vm) {
        command.setControlIp(getControlNicIpForVM(vm));
        Map<String, Boolean> vlanToPersistenceMap = vmVlanPersistenceMappingService.getVlanToPersistenceMapForVM(vm.getId());
        if (MapUtils.isNotEmpty(vlanToPersistenceMap)) {
            command.setVlanToPersistenceMap(vlanToPersistenceMap);
        }
    }

    @Override
    public StopCommand buildCleanupCommand(VirtualMachine vm, boolean executeInSequence, Map<String, DpdkTO> dpdkInterfaceMapping) {
        StopCommand command = new StopCommand(vm, executeInSequence, false);
        decorateStopCommandWithNetworkDetails(command, vm);
        if (MapUtils.isNotEmpty(dpdkInterfaceMapping)) {
            command.setDpdkInterfaceMapping(dpdkInterfaceMapping);
        }
        return command;
    }

    @Override
    public StopCommand buildCleanupCommand(String vmName, boolean executeInSequence) {
        VirtualMachine vm = vmDao.findVMByInstanceName(vmName);
        StopCommand command = new StopCommand(vmName, executeInSequence, false);
        decorateStopCommandWithNetworkDetails(command, vm);
        return command;
    }

    private String getControlNicIpForVM(VirtualMachine vm) {
        if (null == vm.getType()) {
            return null;
        }

        switch (vm.getType()) {
            case ConsoleProxy:
            case SecondaryStorageVm:
                NicVO nic = nicsDao.getControlNicForVM(vm.getId());
                return nic.getIPv4Address();
            case DomainRouter:
                return vm.getPrivateIpAddress();
            default:
                logger.debug("{} is a [{}], returning null for control Nic IP.", vm.toString(), vm.getType());
                return null;
        }
    }
}
