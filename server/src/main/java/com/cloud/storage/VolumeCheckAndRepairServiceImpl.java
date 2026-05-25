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
package com.cloud.storage;

import java.util.Arrays;
import java.util.concurrent.ExecutionException;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.user.volume.CheckAndRepairVolumeCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.framework.jobs.AsyncJob;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.Outcome;
import org.apache.cloudstack.framework.jobs.dao.VmWorkJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.framework.jobs.impl.OutcomeImpl;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.jobs.JobInfo;
import org.springframework.stereotype.Component;
import org.apache.cloudstack.utils.identity.ManagementServerNode;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.Predicate;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VmWorkCheckAndRepairVolume;
import com.cloud.vm.VmWorkConstants;
import com.cloud.vm.VmWorkSerializer;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Implementation of the check-and-repair-volume slice extracted from
 * {@link VolumeApiServiceImpl}.
 *
 * @see VolumeCheckAndRepairService
 */
@Component
public class VolumeCheckAndRepairServiceImpl implements VolumeCheckAndRepairService {

    @Inject
    private VolumeDao volsDao;
    @Inject
    private AccountManager accountMgr;
    @Inject
    private UserVmDao userVmDao;
    @Inject
    private VolumeDataFactory volFactory;
    @Inject
    private VolumeService volService;
    @Inject
    private AsyncJobManager jobMgr;
    @Inject
    private VmWorkJobDao workJobDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private EntityManager entityMgr;

    @Override
    public Pair<String, String> checkAndRepairVolume(CheckAndRepairVolumeCmd cmd) throws ResourceAllocationException {
        long volumeId = cmd.getId();
        String repair = cmd.getRepair();

        final VolumeVO volume = volsDao.findById(volumeId);
        validationsForCheckVolumeOperation(volume);

        Long vmId = volume.getInstanceId();
        if (vmId != null) {
            return handleCheckAndRepairVolumeJob(vmId, volumeId, repair);
        }
        return handleCheckAndRepairVolume(volumeId, repair);
    }

    @Override
    public Pair<String, String> handleCheckAndRepairVolume(Long volumeId, String repair) {
        CheckAndRepairVolumePayload payload = new CheckAndRepairVolumePayload(repair);
        VolumeInfo volumeInfo = volFactory.getVolume(volumeId);
        volumeInfo.addPayload(payload);
        return volService.checkAndRepairVolume(volumeInfo);
    }

    @Override
    public Pair<String, String> handleCheckAndRepairVolumeJob(Long vmId, Long volumeId, String repair) throws ResourceAllocationException {
        AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            VmWorkJobVO placeHolder = createPlaceHolderWork(vmId);
            try {
                return orchestrateCheckAndRepairVolume(volumeId, repair);
            } finally {
                workJobDao.expunge(placeHolder.getId());
            }
        }

        Outcome<Pair> outcome = checkAndRepairVolumeThroughJobQueue(vmId, volumeId, repair);
        try {
            outcome.get();
        } catch (InterruptedException e) {
            throw new RuntimeException("Operation is interrupted", e);
        } catch (ExecutionException e) {
            throw new RuntimeException("Execution exception--", e);
        }

        Object jobResult = jobMgr.unmarshallResultObject(outcome.getJob());
        if (jobResult != null) {
            if (jobResult instanceof ConcurrentOperationException) {
                throw (ConcurrentOperationException) jobResult;
            } else if (jobResult instanceof ResourceAllocationException) {
                throw (ResourceAllocationException) jobResult;
            } else if (jobResult instanceof Throwable) {
                Throwable throwable = (Throwable) jobResult;
                throw new RuntimeException(String.format("Unexpected exception: %s", throwable.getMessage()), throwable);
            }
        }

        if (jobResult instanceof Pair) {
            return (Pair<String, String>) jobResult;
        }

        return null;
    }

    @Override
    public void validationsForCheckVolumeOperation(VolumeVO volume) {
        Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, null, true, volume);

        String volumeName = volume.getName();
        Long vmId = volume.getInstanceId();
        if (vmId != null) {
            validateVMforCheckVolumeOperation(vmId, volumeName);
        }

        if (volume.getState() != Volume.State.Ready) {
            throw new InvalidParameterValueException(String.format("Volume: %s is not in Ready state", volumeName));
        }

        HypervisorType hypervisorType = volsDao.getHypervisorType(volume.getId());
        if (!HypervisorType.KVM.equals(hypervisorType)) {
            throw new InvalidParameterValueException("Check and Repair volumes is supported only for KVM hypervisor");
        }

        if (!Arrays.asList(ImageFormat.QCOW2, ImageFormat.VDI).contains(volume.getFormat())) {
            throw new InvalidParameterValueException("Volume format is not supported for checking and repair");
        }
    }

    @Override
    public void validateVMforCheckVolumeOperation(Long vmId, String volumeName) {
        Account caller = CallContext.current().getCallingAccount();
        UserVmVO vm = userVmDao.findById(vmId);
        if (vm == null) {
            throw new InvalidParameterValueException(String.format("VM not found, please check the VM to which this volume %s is attached", volumeName));
        }

        accountMgr.checkAccess(caller, null, true, vm);

        if (vm.getState() != State.Stopped) {
            throw new InvalidParameterValueException(String.format("VM to which the volume %s is attached should be in stopped state", volumeName));
        }
    }

    @Override
    public Pair<String, String> orchestrateCheckAndRepairVolume(Long volumeId, String repair) {
        VolumeInfo volume = volFactory.getVolume(volumeId);
        if (volume == null) {
            throw new InvalidParameterValueException("Checking volume and repairing failed due to volume:" + volumeId + " doesn't exist");
        }

        CheckAndRepairVolumePayload payload = new CheckAndRepairVolumePayload(repair);
        volume.addPayload(payload);
        return volService.checkAndRepairVolume(volume);
    }

    @Override
    public Outcome<Pair> checkAndRepairVolumeThroughJobQueue(Long vmId, Long volumeId, String repair) {
        final CallContext context = CallContext.current();
        final User callingUser = context.getCallingUser();
        final Account callingAccount = context.getCallingAccount();

        final VMInstanceVO vm = vmInstanceDao.findById(vmId);

        VmWorkJobVO workJob = new VmWorkJobVO(context.getContextId());
        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
        workJob.setCmd(VmWorkCheckAndRepairVolume.class.getName());
        workJob.setAccountId(callingAccount.getId());
        workJob.setUserId(callingUser.getId());
        workJob.setStep(VmWorkJobVO.Step.Starting);
        workJob.setVmType(VirtualMachine.Type.Instance);
        workJob.setVmInstanceId(vm.getId());
        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

        VmWorkCheckAndRepairVolume workInfo = new VmWorkCheckAndRepairVolume(callingUser.getId(), callingAccount.getId(), vm.getId(),
                VolumeApiServiceImpl.VM_WORK_JOB_HANDLER, volumeId, repair);
        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

        jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return newCheckAndRepairVolumeOutcome(workJob);
    }

    private Outcome<Pair> newCheckAndRepairVolumeOutcome(final AsyncJob job) {
        return new OutcomeImpl<>(Pair.class, job, VolumeApiServiceImpl.VmJobCheckInterval.value(), new Predicate() {
            @Override
            public boolean checkCondition() {
                AsyncJobVO jobVo = entityMgr.findById(AsyncJobVO.class, job.getId());
                return jobVo == null || jobVo.getStatus() != JobInfo.Status.IN_PROGRESS;
            }
        }, AsyncJob.Topics.JOB_STATE);
    }

    private VmWorkJobVO createPlaceHolderWork(long instanceId) {
        VmWorkJobVO workJob = new VmWorkJobVO("");
        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_PLACEHOLDER);
        workJob.setCmd("");
        workJob.setCmdInfo("");
        workJob.setAccountId(0);
        workJob.setUserId(0);
        workJob.setStep(VmWorkJobVO.Step.Starting);
        workJob.setVmType(VirtualMachine.Type.Instance);
        workJob.setVmInstanceId(instanceId);
        workJob.setInitMsid(ManagementServerNode.getManagementServerId());
        workJobDao.persist(workJob);
        return workJob;
    }
}
