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

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.to.deployasis.OVFPropertyTO;
import com.cloud.configuration.Config;
import com.cloud.deployasis.UserVmDeployAsIsDetailVO;
import com.cloud.deployasis.dao.TemplateDeployAsIsDetailsDao;
import com.cloud.deployasis.dao.UserVmDeployAsIsDetailsDao;
import com.cloud.storage.GuestOSVO;
import com.cloud.utils.crypt.DBEncryptionUtil;

@Component
public class VmInitialDetailsServiceImpl implements VmInitialDetailsService {
    private static final Logger LOG = LogManager.getLogger(VmInitialDetailsServiceImpl.class);

    @Inject
    private ConfigurationDao configDao;
    @Inject
    private TemplateDeployAsIsDetailsDao templateDeployAsIsDetailsDao;
    @Inject
    private UserVmDeployAsIsDetailsDao userVmDeployAsIsDetailsDao;

    @Override
    public void updateVMDiskController(UserVmVO vm, Map<String, String> customParameters, GuestOSVO guestOS) {
        // If hypervisor is vSphere and OS is OS X, set special settings.
        if (guestOS.getDisplayName().toLowerCase().contains("apple mac os")) {
            vm.setDetail(VmDetailConstants.SMC_PRESENT, "TRUE");
            vm.setDetail(VmDetailConstants.ROOT_DISK_CONTROLLER, "scsi");
            vm.setDetail(VmDetailConstants.DATA_DISK_CONTROLLER, "scsi");
            vm.setDetail(VmDetailConstants.FIRMWARE, "efi");
            LOG.info("guestOS is OSX : overwrite root disk controller to scsi, use smc and efi");
        } else {
            String rootDiskControllerSetting = customParameters.get(VmDetailConstants.ROOT_DISK_CONTROLLER);
            String dataDiskControllerSetting = customParameters.get(VmDetailConstants.DATA_DISK_CONTROLLER);
            if (StringUtils.isNotEmpty(rootDiskControllerSetting)) {
                vm.setDetail(VmDetailConstants.ROOT_DISK_CONTROLLER, rootDiskControllerSetting);
            }

            if (StringUtils.isNotEmpty(dataDiskControllerSetting)) {
                vm.setDetail(VmDetailConstants.DATA_DISK_CONTROLLER, dataDiskControllerSetting);
            }

            // Don't override if VM already has root/data disk controller detail
            if (vm.getDetail(VmDetailConstants.ROOT_DISK_CONTROLLER) == null) {
                String vmwareRootDiskControllerTypeFromSetting = StringUtils.defaultIfEmpty(configDao.getValue(Config.VmwareRootDiskControllerType.key()),
                        Config.VmwareRootDiskControllerType.getDefaultValue());
                vm.setDetail(VmDetailConstants.ROOT_DISK_CONTROLLER, vmwareRootDiskControllerTypeFromSetting);
            }

            if (vm.getDetail(VmDetailConstants.DATA_DISK_CONTROLLER) == null) {
                String finalRootDiskController = vm.getDetail(VmDetailConstants.ROOT_DISK_CONTROLLER);
                // Set the data disk controller detail same as the final scsi root disk controller if VM doesn't have data disk controller detail
                // This is to ensure the disk controller is available for the data disks, as all the SCSI controllers are created with same controller type
                String scsiControllerPattern = "(?i)\\b(scsi|lsilogic|lsilogicsas|lsisas1068|buslogic|pvscsi)\\b";
                if (finalRootDiskController.matches(scsiControllerPattern)) {
                    LOG.info(String.format("Data disk controller was not defined, but root disk is using SCSI controller [%s]." +
                            "To ensure disk controllers are available for the data disks, the data disk controller is updated to match the root disk controller.", finalRootDiskController));
                    vm.setDetail(VmDetailConstants.DATA_DISK_CONTROLLER, finalRootDiskController);
                } else {
                    LOG.info("Data disk controller was not defined; defaulting to 'osdefault'.");
                    vm.setDetail(VmDetailConstants.DATA_DISK_CONTROLLER, "osdefault");
                }
            }
        }
    }

    @Override
    public void persistVMDeployAsIsProperties(UserVmVO vm, Map<String, String> userVmOVFPropertiesMap) {
        if (MapUtils.isNotEmpty(userVmOVFPropertiesMap)) {
            for (String key : userVmOVFPropertiesMap.keySet()) {
                String detailKey = key;
                String value = userVmOVFPropertiesMap.get(key);

                // Sanitize boolean values to expected format and encrypt passwords
                if (StringUtils.isNotBlank(value)) {
                    if (value.equalsIgnoreCase("True")) {
                        value = "True";
                    } else if (value.equalsIgnoreCase("False")) {
                        value = "False";
                    } else {
                        OVFPropertyTO propertyTO = templateDeployAsIsDetailsDao.findPropertyByTemplateAndKey(vm.getTemplateId(), key);
                        if (propertyTO != null && propertyTO.isPassword()) {
                            value = DBEncryptionUtil.encrypt(value);
                        }
                    }
                } else if (value == null) {
                    value = "";
                }
                if (LOG.isTraceEnabled()) {
                    LOG.trace(String.format("setting property '%s' as '%s' with value '%s'", key, detailKey, value));
                }
                UserVmDeployAsIsDetailVO detail = new UserVmDeployAsIsDetailVO(vm.getId(), detailKey, value);
                userVmDeployAsIsDetailsDao.persist(detail);
            }
        }
    }

    @Override
    public void setVncPasswordForKvmIfAvailable(Map<String, String> customParameters, UserVmVO vm) {
        if (customParameters.containsKey(VmDetailConstants.KVM_VNC_PASSWORD)
                && StringUtils.isNotEmpty(customParameters.get(VmDetailConstants.KVM_VNC_PASSWORD))) {
            vm.setVncPassword(customParameters.get(VmDetailConstants.KVM_VNC_PASSWORD));
        }
    }
}
