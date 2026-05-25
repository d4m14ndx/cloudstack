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
import java.util.Map;

import jakarta.inject.Inject;

import org.springframework.stereotype.Component;

import com.cloud.utils.Pair;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VmMetadataSyncServiceImpl implements VmMetadataSyncService {

    @Inject
    protected UserVmDao userVmDao;
    @Inject
    protected VMInstanceDao vmDao;

    @Override
    public void syncVMMetaData(final Map<String, String> vmMetadatum) {
        if (vmMetadatum == null || vmMetadatum.isEmpty()) {
            return;
        }
        List<Pair<Pair<String, VirtualMachine.Type>, Pair<Long, String>>> vmDetails = userVmDao.getVmsDetailByNames(vmMetadatum.keySet(), "platform");
        for (final Map.Entry<String, String> entry : vmMetadatum.entrySet()) {
            final String name = entry.getKey();
            final String platform = entry.getValue();
            if (platform == null || platform.isEmpty()) {
                continue;
            }

            boolean found = false;
            for(Pair<Pair<String, VirtualMachine.Type>, Pair<Long, String>> vmDetail : vmDetails ) {
                Pair<String, VirtualMachine.Type> vmNameTypePair = vmDetail.first();
                if(vmNameTypePair.first().equals(name)) {
                    found = true;
                    if(vmNameTypePair.second() == VirtualMachine.Type.User) {
                        Pair<Long, String> detailPair = vmDetail.second();
                        String platformDetail = detailPair.second();

                        if (platformDetail != null && platformDetail.equals(platform)) {
                            break;
                        }
                        updateVmMetaData(detailPair.first(), platform);
                    }
                    break;
                }
            }

            if(!found) {
                VMInstanceVO vm = vmDao.findVMByInstanceName(name);
                if(vm != null && vm.getType() == VirtualMachine.Type.User) {
                    updateVmMetaData(vm.getId(), platform);
                }
            }
        }
    }

    private void updateVmMetaData(Long vmId, String platform) {
        UserVmVO userVm = userVmDao.findById(vmId);
        userVmDao.loadDetails(userVm);
        if ( userVm.details.containsKey(VmDetailConstants.TIME_OFFSET)) {
            userVm.details.remove(VmDetailConstants.TIME_OFFSET);
        }
        userVm.setDetail(VmDetailConstants.PLATFORM,  platform);
        String pvdriver = "xenserver56";
        if ( platform.contains("device_id")) {
            pvdriver = "xenserver61";
        }
        if (!userVm.details.containsKey(VmDetailConstants.HYPERVISOR_TOOLS_VERSION) || !userVm.details.get(VmDetailConstants.HYPERVISOR_TOOLS_VERSION).equals(pvdriver)) {
            userVm.setDetail(VmDetailConstants.HYPERVISOR_TOOLS_VERSION, pvdriver);
        }
        userVmDao.saveDetails(userVm);
    }
}
