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

import org.apache.cloudstack.api.command.user.vm.UpdateVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.vm.dao.UserVmDao;

/**
 * Input validation and detail-merging for {@code updateVirtualMachine} —
 * extracted from {@link UserVmManagerImpl}.
 *
 * @see VmUpdateValidator
 */
@Component
public class VmUpdateValidatorImpl implements VmUpdateValidator {
    private static final Logger LOG = LogManager.getLogger(VmUpdateValidatorImpl.class);

    @Inject
    private UserVmDao userVmDao;

    @Inject
    private GuestOSDao guestOSDao;

    @Inject
    private AccountManager accountManager;

    @Inject
    private ServiceOfferingDao serviceOfferingDao;

    @Override
    public void validateInputsAndPermissionForUpdateVirtualMachineCommand(UpdateVMCmd cmd) {
        UserVmVO vmInstance = userVmDao.findById(cmd.getId());
        if (vmInstance == null) {
            throw new InvalidParameterValueException("unable to find virtual machine with id: " + cmd.getId());
        }
        validateGuestOsIdForUpdateVirtualMachineCommand(cmd);
        Account caller = CallContext.current().getCallingAccount();
        accountManager.checkAccess(caller, null, true, vmInstance);
    }

    @Override
    public void validateGuestOsIdForUpdateVirtualMachineCommand(UpdateVMCmd cmd) {
        Long osTypeId = cmd.getOsTypeId();
        if (osTypeId != null) {
            GuestOSVO guestOS = guestOSDao.findById(osTypeId);
            if (guestOS == null) {
                throw new InvalidParameterValueException("Please specify a valid guest OS ID.");
            }
        }
    }

    @Override
    public void updateInstanceDetailsMapWithCurrentValuesForAbsentDetails(Map<String, String> details,
                                                                         VirtualMachine vmInstance,
                                                                         Long newServiceOfferingId) {
        ServiceOfferingVO currentServiceOffering = serviceOfferingDao.findByIdIncludingRemoved(vmInstance.getId(), vmInstance.getServiceOfferingId());
        ServiceOfferingVO newServiceOffering = serviceOfferingDao.findById(newServiceOfferingId);
        addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(newServiceOffering.getSpeed(), details, VmDetailConstants.CPU_SPEED, currentServiceOffering.getSpeed());
        addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(newServiceOffering.getRamSize(), details, VmDetailConstants.MEMORY, currentServiceOffering.getRamSize());
        addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(newServiceOffering.getCpu(), details, VmDetailConstants.CPU_NUMBER, currentServiceOffering.getCpu());
    }

    @Override
    public void addCurrentDetailValueToInstanceDetailsMapIfNewValueWasNotSpecified(Integer newValue,
                                                                                  Map<String, String> details,
                                                                                  String detailKey,
                                                                                  Integer currentValue) {
        if (newValue == null && details.get(detailKey) == null) {
            String currentValueString = String.valueOf(currentValue);
            LOG.debug("{} was not specified, keeping the current value: {}.", detailKey, currentValueString);
            details.put(detailKey, currentValueString);
        }
    }
}
