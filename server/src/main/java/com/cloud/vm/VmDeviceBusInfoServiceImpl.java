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

import jakarta.inject.Inject;

import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.vm.dao.UserVmDao;

@Component
public class VmDeviceBusInfoServiceImpl implements VmDeviceBusInfoService {
    private static final Logger logger = LogManager.getLogger(VmDeviceBusInfoServiceImpl.class);

    @Inject
    private UserVmDao userVmDao;

    @Override
    public void persistDeviceBusInfo(UserVmVO vm, String rootDiskController) {
        String existingVmRootDiskController = vm.getDetail(VmDetailConstants.ROOT_DISK_CONTROLLER);
        if (StringUtils.isEmpty(existingVmRootDiskController) && StringUtils.isNotEmpty(rootDiskController)) {
            vm.setDetail(VmDetailConstants.ROOT_DISK_CONTROLLER, rootDiskController);
            userVmDao.saveDetails(vm);
            if (logger.isDebugEnabled()) {
                logger.debug("Persisted device bus information rootDiskController={} for vm: {}", rootDiskController, vm);
            }
        }
    }
}
