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
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.commons.collections.MapUtils;
import org.springframework.stereotype.Component;

import com.cloud.configuration.ConfigurationManagerImpl;
import com.cloud.event.UsageEventVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.utils.NumbersUtil;

/**
 * Service offering validation — extracted from {@link UserVmManagerImpl}
 * (Phase 4 god-class decomposition, Spring-component slice 2).
 *
 * @see ServiceOfferingValidator
 */
@Component
public class ServiceOfferingValidatorImpl implements ServiceOfferingValidator {

    @Inject
    private ServiceOfferingDetailsDao serviceOfferingDetailsDao;

    @Inject
    private VolumeService volumeService;

    @Override
    public void validateOfferingMaxResource(ServiceOfferingVO offering) {
        int maxCPUCores = ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_CPU_CORES.value() == 0
                ? Integer.MAX_VALUE : ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_CPU_CORES.value();
        if (offering.getCpu() > maxCPUCores) {
            throw new InvalidParameterValueException(
                    "Invalid cpu cores value, please choose another service offering with cpu cores between 1 and " + maxCPUCores);
        }
        int maxRAMSize = ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_RAM_SIZE.value() == 0
                ? Integer.MAX_VALUE : ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_RAM_SIZE.value();
        if (offering.getRamSize() > maxRAMSize) {
            throw new InvalidParameterValueException(
                    "Invalid memory value, please choose another service offering with memory between 32 and " + maxRAMSize + " MB");
        }
    }

    @Override
    public void validateCustomParameters(ServiceOfferingVO serviceOffering, Map<String, String> customParameters) {
        if (MapUtils.isEmpty(customParameters) && serviceOffering.isDynamic()) {
            throw new InvalidParameterValueException(
                    "Need to specify custom parameter values cpu, cpu speed and memory when using custom offering");
        }
        Map<String, String> offeringDetails = serviceOfferingDetailsDao.listDetailsKeyPairs(serviceOffering.getId());

        int maxCPUCores = ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_CPU_CORES.value() == 0
                ? Integer.MAX_VALUE : ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_CPU_CORES.value();
        int maxRAMSize = ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_RAM_SIZE.value() == 0
                ? Integer.MAX_VALUE : ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_RAM_SIZE.value();

        // CPU cores
        if (serviceOffering.getCpu() == null) {
            int minCPU = NumbersUtil.parseInt(offeringDetails.get(ApiConstants.MIN_CPU_NUMBER), 1);
            int maxCPU = NumbersUtil.parseInt(offeringDetails.get(ApiConstants.MAX_CPU_NUMBER), Integer.MAX_VALUE);
            int cpuNumber = NumbersUtil.parseInt(customParameters.get(UsageEventVO.DynamicParameters.cpuNumber.name()), -1);
            if (cpuNumber < minCPU || cpuNumber > maxCPU || cpuNumber > maxCPUCores) {
                throw new InvalidParameterValueException(String.format(
                        "Invalid CPU cores value, specify a value between %d and %d",
                        minCPU, Math.min(maxCPUCores, maxCPU)));
            }
        } else if (customParameters.containsKey(UsageEventVO.DynamicParameters.cpuNumber.name())) {
            throw new InvalidParameterValueException(
                    "The CPU cores of this offering id:" + serviceOffering.getUuid()
                    + " is not customizable. This is predefined in the Template.");
        }

        // CPU speed
        if (serviceOffering.getSpeed() == null) {
            String cpuSpeed = customParameters.get(UsageEventVO.DynamicParameters.cpuSpeed.name());
            if (cpuSpeed == null || NumbersUtil.parseInt(cpuSpeed, -1) <= 0) {
                throw new InvalidParameterValueException(
                        "Invalid CPU speed value, specify a value between 1 and " + Integer.MAX_VALUE);
            }
        } else if (!serviceOffering.isCustomCpuSpeedSupported()
                && customParameters.containsKey(UsageEventVO.DynamicParameters.cpuSpeed.name())) {
            throw new InvalidParameterValueException(String.format(
                    "The CPU speed of this offering id:%s is not customizable. This is predefined as %d MHz.",
                    serviceOffering.getUuid(), serviceOffering.getSpeed()));
        }

        // Memory
        if (serviceOffering.getRamSize() == null) {
            int minMemory = NumbersUtil.parseInt(offeringDetails.get(ApiConstants.MIN_MEMORY), 32);
            int maxMemory = NumbersUtil.parseInt(offeringDetails.get(ApiConstants.MAX_MEMORY), Integer.MAX_VALUE);
            int memory = NumbersUtil.parseInt(customParameters.get(UsageEventVO.DynamicParameters.memory.name()), -1);
            if (memory < minMemory || memory > maxMemory || memory > maxRAMSize) {
                throw new InvalidParameterValueException(String.format(
                        "Invalid memory value, specify a value between %d and %d",
                        minMemory, Math.min(maxRAMSize, maxMemory)));
            }
        } else if (customParameters.containsKey(UsageEventVO.DynamicParameters.memory.name())) {
            throw new InvalidParameterValueException(
                    "The memory of this offering id:" + serviceOffering.getUuid()
                    + " is not customizable. This is predefined in the Template.");
        }
    }

    @Override
    public void validateDiskOfferingChecks(ServiceOfferingVO currentServiceOffering, ServiceOfferingVO newServiceOffering) {
        if (currentServiceOffering.getDiskOfferingStrictness() != newServiceOffering.getDiskOfferingStrictness()) {
            throw new InvalidParameterValueException(
                    "Unable to Scale VM, since disk offering strictness flag is not same for new service offering and old service offering");
        }
        if (currentServiceOffering.getDiskOfferingStrictness()
                && !currentServiceOffering.getDiskOfferingId().equals(newServiceOffering.getDiskOfferingId())) {
            throw new InvalidParameterValueException(
                    "Unable to Scale VM, since disk offering id associated with the old service offering is not same for new service offering");
        }
        volumeService.validateChangeDiskOfferingEncryptionType(
                currentServiceOffering.getDiskOfferingId(), newServiceOffering.getDiskOfferingId());
    }
}
