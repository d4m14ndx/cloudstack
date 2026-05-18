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

import static com.cloud.vm.UserVmManagerImpl.ROOT_DEVICE_ID;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.vm.RecoverVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.resourcelimit.Reserver;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.ha.HighAvailabilityManager;
import com.cloud.resourcelimit.ReservationHelper;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.offering.ServiceOffering;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.dao.AccountDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackWithExceptionNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;

@Component
public class VmRecoveryServiceImpl implements VmRecoveryService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private UserVmDao _vmDao;
    @Inject
    private AccountManager _accountMgr;
    @Inject
    private AccountDao _accountDao;
    @Inject
    private VMTemplateDao _templateDao;
    @Inject
    private VolumeDao _volsDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private ResourceLimitService resourceLimitService;
    @Inject
    private HighAvailabilityManager _haMgr;
    @Inject
    private VirtualMachineManager _itMgr;
    @Inject
    private VolumeApiService _volumeService;

    @Override
    @DB
    public UserVm recoverVirtualMachine(RecoverVMCmd cmd) throws ResourceAllocationException, CloudRuntimeException {

        final Long vmId = cmd.getId();
        Account caller = CallContext.current().getCallingAccount();
        final Long userId = caller.getAccountId();

        // Verify input parameters
        final UserVmVO vm = _vmDao.findById(vmId);

        if (vm == null) {
            throw new InvalidParameterValueException("Unable to find an Instance with id " + vmId);
        }
        if (UserVmManager.SHAREDFSVM.equals(vm.getUserVmType())) {
            throw new InvalidParameterValueException("Operation not supported on Shared FileSystem Instance");
        }

        // When trying to expunge, permission is denied when the caller is not an admin and the AllowUserExpungeRecoverVm is false for the caller.
        if (!_accountMgr.isAdmin(userId) && !UserVmManager.AllowUserExpungeRecoverVm.valueIn(userId)) {
            throw new PermissionDeniedException("Recovering a vm can only be done by an Admin. Or when the allow.user.expunge.recover.vm key is set.");
        }

        if (vm.getRemoved() != null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Unable to find vm. vm is removed: {}", vm);
            }
            throw new InvalidParameterValueException("Unable to find vm by id " + vm.getUuid());
        }

        if (vm.getState() != State.Destroyed) {
            if (logger.isDebugEnabled()) {
                logger.debug("vm {} is not in the Destroyed state. current sate: {}", vm, vm.getState());
            }
            throw new InvalidParameterValueException("Vm with id " + vm.getUuid() + " is not in the right state");
        }

        if (logger.isDebugEnabled()) {
            logger.debug("Recovering vm {}", vm);
        }

        Transaction.execute(new TransactionCallbackWithExceptionNoReturn<ResourceAllocationException>() {
            @Override public void doInTransactionWithoutResult(TransactionStatus status) throws ResourceAllocationException {

                Account account = _accountDao.lockRow(vm.getAccountId(), true);

                // if the account is deleted, throw error
                if (account.getRemoved() != null) {
                    throw new CloudRuntimeException("Unable to recover Instance as the Account is deleted");
                }

                // Get serviceOffering for Virtual Machine
                ServiceOfferingVO serviceOffering = serviceOfferingDao.findById(vm.getId(), vm.getServiceOfferingId());
                VMTemplateVO template = _templateDao.findByIdIncludingRemoved(vm.getTemplateId());

                List<Reserver> reservations = new ArrayList<>();
                try {
                    // First check that the maximum number of UserVMs, CPU and Memory limit for the given
                    // accountId will not be exceeded
                    if (!VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
                        resourceLimitService.checkVmResourceLimit(account, vm.isDisplayVm(), serviceOffering, template, reservations);
                    }

                    _haMgr.cancelDestroy(vm, vm.getHostId());

                    try {
                        if (!_itMgr.stateTransitTo(vm, VirtualMachine.Event.RecoveryRequested, null)) {
                            logger.debug("Unable to recover the vm {} because it is not in the correct state. current state: {}", vm, vm.getState());
                            throw new InvalidParameterValueException(String.format("Unable to recover the vm %s because it is not in the correct state. current state: %s", vm, vm.getState()));
                        }
                    } catch (NoTransitionException e) {
                        throw new InvalidParameterValueException(String.format("Unable to recover the vm %s because it is not in the correct state. current state: %s", vm, vm.getState()));
                    }

                    // Recover the VM's disks
                    List<VolumeVO> volumes = _volsDao.findByInstance(vmId);
                    for (VolumeVO volume : volumes) {
                        if (volume.getVolumeType().equals(Volume.Type.ROOT)) {
                            recoverRootVolume(volume, vmId);
                            break;
                        }
                    }

                    //Update Resource Count for the given account
                    resourceCountIncrement(account.getId(), vm.isDisplayVm(), serviceOffering, template);

                } finally {
                    ReservationHelper.closeAll(reservations);
                }
            }
        });

        return _vmDao.findById(vmId);
    }

    @Override
    public void recoverRootVolume(VolumeVO volume, Long vmId) {
        if (Volume.State.Destroy.equals(volume.getState())) {
            _volumeService.recoverVolume(volume.getId());
            _volsDao.attachVolume(volume.getId(), vmId, ROOT_DEVICE_ID);
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_ATTACH, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(),
                    volume.getDiskOfferingId(), volume.getTemplateId(), volume.getSize(), Volume.class.getName(), volume.getUuid(), vmId, volume.isDisplay());
        } else {
            _volumeService.publishVolumeCreationUsageEvent(volume);
        }
    }

    /**
     * Mirror of the protected {@code resourceCountIncrement} that remains on
     * {@link UserVmManagerImpl}: when the
     * {@code resource.count.running.vms.only} global toggle is on, resource
     * counts are managed on start/stop instead, so we skip the increment here.
     */
    protected void resourceCountIncrement(long accountId, Boolean displayVm, ServiceOffering serviceOffering, VirtualMachineTemplate template) {
        if (!VirtualMachineManager.ResourceCountRunningVMsonly.value()) {
            resourceLimitService.incrementVmResourceCount(accountId, displayVm, serviceOffering, template);
        }
    }
}
