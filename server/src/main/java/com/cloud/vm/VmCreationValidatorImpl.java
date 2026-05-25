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

import static org.apache.cloudstack.api.ApiConstants.EXTRA_CONFIG;
import static org.apache.cloudstack.api.ApiConstants.MAX_IOPS;
import static org.apache.cloudstack.api.ApiConstants.MIN_IOPS;

import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.user.vm.BaseDeployVMCmd;
import org.apache.cloudstack.api.command.user.vm.DeployVnfApplianceCmd;
import org.apache.cloudstack.storage.template.VnfTemplateManager;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.ServiceOfferingJoinDao;
import com.cloud.api.query.vo.ServiceOfferingJoinVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.Storage.TemplateType;
import com.cloud.template.VirtualMachineTemplate;

/**
 * Deploy-time parameter validation — extracted from
 * {@link UserVmManagerImpl}.
 *
 * @see VmCreationValidator
 */
@Component
public class VmCreationValidatorImpl implements VmCreationValidator {

    @Inject
    private ServiceOfferingJoinDao serviceOfferingJoinDao;

    @Inject
    private VnfTemplateManager vnfTemplateManager;

    @Override
    public void verifyServiceOffering(BaseDeployVMCmd cmd, ServiceOffering serviceOffering) {
        if (ServiceOffering.State.Inactive.equals(serviceOffering.getState())) {
            throw new InvalidParameterValueException(String.format("Service offering is inactive: [%s].", serviceOffering.getUuid()));
        }

        Long overrideDiskOfferingId = cmd.getOverrideDiskOfferingId();
        if (serviceOffering.getDiskOfferingStrictness() && overrideDiskOfferingId != null) {
            throw new InvalidParameterValueException(String.format("Cannot override disk offering id %d since provided service offering is strictly mapped to its disk offering", overrideDiskOfferingId));
        }

        if (!serviceOffering.isDynamic()) {
            for (String detail : cmd.getDetails().keySet()) {
                if (detail.equalsIgnoreCase(VmDetailConstants.CPU_NUMBER) || detail.equalsIgnoreCase(VmDetailConstants.CPU_SPEED) || detail.equalsIgnoreCase(VmDetailConstants.MEMORY)) {
                    throw new InvalidParameterValueException("cpuNumber or cpuSpeed or memory should not be specified for static service offering");
                }
            }
        }
    }

    @Override
    public void verifyTemplate(BaseDeployVMCmd cmd, VirtualMachineTemplate template, Long serviceOfferingId) {
        if (TemplateType.VNF.equals(template.getTemplateType())) {
            vnfTemplateManager.validateVnfApplianceNics(template, cmd.getNetworkIds(), cmd.getVmNetworkMap());
        } else if (cmd instanceof DeployVnfApplianceCmd) {
            throw new InvalidParameterValueException("Can't deploy VNF appliance from a non-VNF template");
        }

        ServiceOfferingJoinVO svcOffering = serviceOfferingJoinDao.findById(serviceOfferingId);

        if (template.isDeployAsIs()) {
            if (svcOffering != null && svcOffering.getRootDiskSize() != null && svcOffering.getRootDiskSize() > 0) {
                throw new InvalidParameterValueException("Failed to deploy Virtual Machine as a service offering with root disk size specified cannot be used with a deploy as-is template");
            }

            if (cmd.getDetails().get("rootdisksize") != null) {
                throw new InvalidParameterValueException("Overriding root disk size isn't supported for VMs deployed from deploy as-is Templates");
            }

            if (cmd.getBootMode() != null || cmd.getBootType() != null) {
                throw new InvalidParameterValueException("Boot type and boot mode are not supported on VMware for Templates registered as deploy-as-is, as we honour what is defined in the template.");
            }
        }
    }

    @Override
    public void verifyDetails(Map<String, String> details) {
        if (details != null) {
            verifyMinAndMaxIops(details.get(MIN_IOPS), details.get(MAX_IOPS));
            verifyMinAndMaxIops(details.get("minIopsDo"), details.get("maxIopsDo"));

            if (details.containsKey("extraconfig")) {
                throw new InvalidParameterValueException("'extraconfig' should not be included in details as key");
            }

            for (String detailName : details.keySet()) {
                if (detailName != null && detailName.startsWith(EXTRA_CONFIG)) {
                    throw new InvalidParameterValueException("detail name should not start with extraconfig");
                }
            }
        }
    }

    @Override
    public void verifyMinAndMaxIops(String minIops, String maxIops) {
        if ((minIops != null && maxIops == null) || (minIops == null && maxIops != null)) {
            throw new InvalidParameterValueException("Either 'Min IOPS' and 'Max IOPS' must both be specified or neither be specified.");
        }

        long lMinIops;
        try {
            lMinIops = minIops != null ? Long.parseLong(minIops) : 0;
        } catch (NumberFormatException ex) {
            throw new InvalidParameterValueException("'Min IOPS' must be a whole number.");
        }

        long lMaxIops;
        try {
            lMaxIops = maxIops != null ? Long.parseLong(maxIops) : 0;
        } catch (NumberFormatException ex) {
            throw new InvalidParameterValueException("'Max IOPS' must be a whole number.");
        }

        if (lMinIops > lMaxIops) {
            throw new InvalidParameterValueException("'Min IOPS' must be less than or equal to 'Max IOPS'.");
        }
    }
}
