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

import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.vm.lease.VMLeaseManager;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.query.vo.ServiceOfferingJoinVO;
import com.cloud.event.ActionEventUtils;
import com.cloud.event.EventTypes;
import com.cloud.uservm.UserVm;
import com.cloud.utils.DateUtil;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@Component
public class VmLeaseApplicationServiceImpl implements VmLeaseApplicationService {
    private static final Logger LOG = LogManager.getLogger(VmLeaseApplicationServiceImpl.class);

    @Inject
    private VmLeaseService vmLeaseService;
    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;

    @Override
    public void validateLeaseProperties(Integer leaseDuration, VMLeaseManager.ExpiryAction leaseExpiryAction) {
        vmLeaseService.validateLeaseProperties(leaseDuration, leaseExpiryAction);
    }

    @Override
    public void applyLeaseOnCreateInstance(UserVm vm,
                                           Integer leaseDuration,
                                           VMLeaseManager.ExpiryAction leaseExpiryAction,
                                           ServiceOfferingJoinVO serviceOfferingJoinVO) {
        if (leaseDuration == null) {
            leaseDuration = serviceOfferingJoinVO.getLeaseDuration();
        }
        if (leaseDuration == null || leaseDuration < 1) {
            return;
        }
        leaseExpiryAction = leaseExpiryAction != null ? leaseExpiryAction : serviceOfferingJoinVO.getLeaseExpiryAction();
        if (leaseExpiryAction == null) {
            return;
        }
        vmLeaseService.addLeaseDetailsForInstance(vm, leaseDuration, leaseExpiryAction);
    }

    @Override
    public void applyLeaseOnUpdateInstance(UserVm instance,
                                           Integer leaseDuration,
                                           VMLeaseManager.ExpiryAction leaseExpiryAction) {
        vmLeaseService.validateLeaseProperties(leaseDuration, leaseExpiryAction);
        String instanceUuid = instance.getUuid();

        Map<String, String> vmDetails = vmInstanceDetailsDao.listDetailsKeyPairs(instance.getId(),
                List.of(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE, VmDetailConstants.INSTANCE_LEASE_EXECUTION));
        String leaseExecution = vmDetails.get(VmDetailConstants.INSTANCE_LEASE_EXECUTION);
        String leaseExpiryDate = vmDetails.get(VmDetailConstants.INSTANCE_LEASE_EXPIRY_DATE);

        if (StringUtils.isEmpty(leaseExpiryDate)) {
            String errorMsg = "Lease can't be applied on instance with id: " + instanceUuid + ", it doesn't have lease associated during deployment";
            LOG.debug(errorMsg);
            throw new CloudRuntimeException(errorMsg);
        }

        if (!VMLeaseManager.LeaseActionExecution.PENDING.name().equals(leaseExecution)) {
            String errorMsg = "Lease can't be applied on instance with id: " + instanceUuid + ", it doesn't have active lease";
            LOG.debug(errorMsg);
            throw new CloudRuntimeException(errorMsg);
        }

        long leaseExpiryTimeDiff;
        try {
            leaseExpiryTimeDiff = DateUtil.getTimeDifference(
                    DateUtil.parseDateString(TimeZone.getTimeZone("UTC"), leaseExpiryDate), new Date());
        } catch (Exception ex) {
            LOG.error("Error occurred computing time difference for instance lease expiry, " +
                    "will skip applying lease for vm with id: {}", instanceUuid, ex);
            return;
        }
        if (leaseExpiryTimeDiff < 0) {
            LOG.debug("Lease has expired for instance with id: {}, can't modify lease information", instanceUuid);
            throw new CloudRuntimeException("Lease is not allowed to be redefined on expired leased instance");
        }

        if (leaseDuration < 1) {
            vmInstanceDetailsDao.addDetail(instance.getId(), VmDetailConstants.INSTANCE_LEASE_EXECUTION,
                    VMLeaseManager.LeaseActionExecution.DISABLED.name(), false);
            ActionEventUtils.onActionEvent(CallContext.current().getCallingUserId(), instance.getAccountId(), instance.getDomainId(),
                    EventTypes.VM_LEASE_DISABLED, "Disabling lease on the instance", instance.getId(), ApiCommandResourceType.VirtualMachine.toString());
            return;
        }
        vmLeaseService.addLeaseDetailsForInstance(instance, leaseDuration, leaseExpiryAction);
    }

    @Override
    public void addLeaseDetailsForInstance(UserVm vm, Integer leaseDuration, VMLeaseManager.ExpiryAction leaseExpiryAction) {
        vmLeaseService.addLeaseDetailsForInstance(vm, leaseDuration, leaseExpiryAction);
    }
}
