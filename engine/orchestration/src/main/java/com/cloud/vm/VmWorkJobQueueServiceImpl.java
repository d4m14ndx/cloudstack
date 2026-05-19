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

import java.net.URI;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;
import jakarta.persistence.EntityExistsException;

import org.apache.cloudstack.api.command.admin.vm.MigrateVMCmd;
import org.apache.cloudstack.api.command.admin.volume.MigrateVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.user.volume.MigrateVolumeCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.jobs.AsyncJob;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.Outcome;
import org.apache.cloudstack.framework.jobs.dao.VmWorkJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.framework.jobs.impl.OutcomeImpl;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.jobs.JobInfo;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlan;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.Predicate;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine.PowerState;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VmWorkJobQueueServiceImpl implements VmWorkJobQueueService {
    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected EntityManager entityMgr;
    @Inject
    protected VMInstanceDao vmDao;
    @Inject
    protected VmWorkJobDao workJobDao;
    @Inject
    protected AsyncJobManager jobMgr;
    @Inject
    protected PrimaryDataStoreDao storagePoolDao;

    public class VmStateSyncOutcome extends OutcomeImpl<VirtualMachine> {
        private long vmId;

        public VmStateSyncOutcome(final AsyncJob job, final PowerState desiredPowerState, final long vmId, final Long srcHostIdForMigration) {
            super(VirtualMachine.class, job, VirtualMachineManagerImpl.VmJobCheckInterval.value(), new Predicate() {
                @Override
                public boolean checkCondition() {
                    final AsyncJobVO jobVo = entityMgr.findById(AsyncJobVO.class, job.getId());
                    return jobVo == null || jobVo.getStatus() != JobInfo.Status.IN_PROGRESS;
                }
            }, VirtualMachineManager.Topics.VM_POWER_STATE, AsyncJob.Topics.JOB_STATE);
            this.vmId = vmId;
        }

        @Override
        protected VirtualMachine retrieve() {
            return vmDao.findById(vmId);
        }
    }

    public class VmJobVirtualMachineOutcome extends OutcomeImpl<VirtualMachine> {
        private long vmId;

        public VmJobVirtualMachineOutcome(final AsyncJob job, final long vmId) {
            super(VirtualMachine.class, job, VirtualMachineManagerImpl.VmJobCheckInterval.value(), new Predicate() {
                @Override
                public boolean checkCondition() {
                    final AsyncJobVO jobVo = entityMgr.findById(AsyncJobVO.class, job.getId());
                    return jobVo == null || jobVo.getStatus() != JobInfo.Status.IN_PROGRESS;
                }
            }, AsyncJob.Topics.JOB_STATE);
            this.vmId = vmId;
        }

        @Override
        protected VirtualMachine retrieve() {
            return vmDao.findById(vmId);
        }
    }

    @Override
    public VmWorkJobVO createPlaceHolderWork(final long instanceId) {
        return createPlaceHolderWork(instanceId, null);
    }

    @Override
    public VmWorkJobVO createPlaceHolderWork(final long instanceId, String secondaryObjectIdentifier) {
        final VmWorkJobVO workJob = new VmWorkJobVO("");

        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_PLACEHOLDER);
        workJob.setCmd("");
        workJob.setCmdInfo("");

        workJob.setAccountId(0);
        workJob.setUserId(0);
        workJob.setStep(VmWorkJobVO.Step.Starting);
        workJob.setVmType(VirtualMachine.Type.Instance);
        workJob.setVmInstanceId(instanceId);
        if (StringUtils.isNotBlank(secondaryObjectIdentifier)) {
            workJob.setSecondaryObjectIdentifier(secondaryObjectIdentifier);
        }
        workJob.setInitMsid(ManagementServerNode.getManagementServerId());

        workJobDao.persist(workJob);

        return workJob;
    }

    @Override
    public void expungePlaceHolderWork(VmWorkJobVO placeHolder) {
        if (placeHolder != null) {
            workJobDao.expunge(placeHolder.getId());
        }
    }

    @Override
    public Outcome<VirtualMachine> startVmThroughJobQueue(final String vmUuid,
            final Map<VirtualMachineProfile.Param, Object> params,
            final DeploymentPlan planToDeploy, final DeploymentPlanner planner) {
        String commandName = VmWorkStart.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmUuid, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();
        Long vmId = pendingWorkJob.second();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, VmWorkJobVO.Step.Starting, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkStart workInfo = new VmWorkStart(newVmWorkJobAndInfo.second());

            workInfo.setPlan(planToDeploy);
            workInfo.setParams(params);
            if (planner != null) {
                workInfo.setDeploymentPlanner(planner.getName());
            }
            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }

        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmStateSyncOutcome(workJob,
                VirtualMachine.PowerState.PowerOn, vmId, null);
    }

    @Override
    public Outcome<VirtualMachine> stopVmThroughJobQueue(final String vmUuid, final boolean cleanup) {
        String commandName = VmWorkStop.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(null, vmUuid, null, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();
        Long vmId = pendingWorkJob.second();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, VmWorkJobVO.Step.Prepare, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkStop workInfo = new VmWorkStop(newVmWorkJobAndInfo.second(), cleanup);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }

        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmStateSyncOutcome(workJob,
                VirtualMachine.PowerState.PowerOff, vmId, null);
    }

    @Override
    public Outcome<VirtualMachine> rebootVmThroughJobQueue(final String vmUuid,
            final Map<VirtualMachineProfile.Param, Object> params) {
        String commandName = VmWorkReboot.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmUuid, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();
        Long vmId = pendingWorkJob.second();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, VmWorkJobVO.Step.Prepare, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkReboot workInfo = new VmWorkReboot(newVmWorkJobAndInfo.second(), params);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }

        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob,
                vmId);
    }

    @Override
    public Outcome<VirtualMachine> migrateVmThroughJobQueue(final String vmUuid, final long srcHostId, final DeployDestination dest) {
        Map<Volume, StoragePool> volumeStorageMap = dest.getStorageForDisks();
        if (volumeStorageMap != null) {
            for (Volume vol : volumeStorageMap.keySet()) {
                checkConcurrentJobsPerDatastoreThreshhold(volumeStorageMap.get(vol));
            }
        }

        VMInstanceVO vm = vmDao.findByUuid(vmUuid);
        Long vmId = vm.getId();

        String commandName = VmWorkMigrate.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmId, vmUuid, VirtualMachine.Type.Instance, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkMigrate workInfo = new VmWorkMigrate(newVmWorkJobAndInfo.second(), srcHostId, dest);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }

        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmStateSyncOutcome(workJob,
                VirtualMachine.PowerState.PowerOn, vmId, vm.getPowerHostId());
    }

    @Override
    public Outcome<VirtualMachine> migrateVmAwayThroughJobQueue(final String vmUuid, final long srcHostId) {
        VMInstanceVO vm = vmDao.findByUuid(vmUuid);
        Long vmId = vm.getId();

        String commandName = VmWorkMigrateAway.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmId, vmUuid, VirtualMachine.Type.Instance, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkMigrateAway workInfo = new VmWorkMigrateAway(newVmWorkJobAndInfo.second(), srcHostId);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }


        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmStateSyncOutcome(workJob, VirtualMachine.PowerState.PowerOn, vmId, vm.getPowerHostId());
    }

    @Override
    public Outcome<VirtualMachine> migrateVmWithStorageThroughJobQueue(
            final String vmUuid, final long srcHostId, final long destHostId,
            final Map<Long, Long> volumeToPool) {
        String commandName = VmWorkMigrateWithStorage.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmUuid, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();
        Long vmId = pendingWorkJob.second();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkMigrateWithStorage workInfo = new VmWorkMigrateWithStorage(newVmWorkJobAndInfo.second(), srcHostId, destHostId, volumeToPool);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmStateSyncOutcome(workJob,
                VirtualMachine.PowerState.PowerOn, vmId, destHostId);
    }

    @Override
    public Outcome<VirtualMachine> migrateVmForScaleThroughJobQueue(
            final String vmUuid, final long srcHostId, final DeployDestination dest, final Long newSvcOfferingId) {
        String commandName = VmWorkMigrateForScale.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmUuid, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();
        Long vmId = pendingWorkJob.second();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkMigrateForScale workInfo = new VmWorkMigrateForScale(newVmWorkJobAndInfo.second(), srcHostId, dest, newSvcOfferingId);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob, vmId);
    }

    void checkConcurrentJobsPerDatastoreThreshhold(final StoragePool destPool) {
        final Long threshold = VolumeApiService.ConcurrentMigrationsThresholdPerDatastore.value();
        if (threshold != null && threshold > 0) {
            long count = jobMgr.countPendingJobs("\"storageid\":\"" + destPool.getUuid() + "\"", MigrateVMCmd.class.getName(), MigrateVolumeCmd.class.getName(), MigrateVolumeCmdByAdmin.class.getName());
            if (count > threshold) {
                throw new CloudRuntimeException("Number of concurrent migration jobs per datastore exceeded the threshold: " + threshold.toString() + ". Please try again after some time.");
            }
        }
    }

    @Override
    public Outcome<VirtualMachine> migrateVmStorageThroughJobQueue(final String vmUuid, final Map<Long, Long> volumeToPool) {
        Collection<Long> poolIds = volumeToPool.values();
        Set<Long> uniquePoolIds = new HashSet<>(poolIds);
        for (Long poolId : uniquePoolIds) {
            StoragePoolVO pool = storagePoolDao.findById(poolId);
            checkConcurrentJobsPerDatastoreThreshhold(pool);
        }

        String commandName = VmWorkStorageMigration.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmUuid, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();
        Long vmId = pendingWorkJob.second();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkStorageMigration workInfo = new VmWorkStorageMigration(newVmWorkJobAndInfo.second(),  volumeToPool);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob, vmId);
    }

    @Override
    public Outcome<VirtualMachine> addVmToNetworkThroughJobQueue(
            final VirtualMachine vm, final Network network, final NicProfile requested) {
        Long vmId = vm.getId();
        String commandName = VmWorkAddVmToNetwork.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmId, commandName);

        final CallContext context = CallContext.current();
        final User user = context.getCallingUser();
        final Account account = context.getCallingAccount();

        final List<VmWorkJobVO> pendingWorkJobs = workJobDao.listPendingWorkJobs(
                VirtualMachine.Type.Instance, vm.getId(),
                VmWorkAddVmToNetwork.class.getName(), network.getUuid());

        VmWorkJobVO workJob = null;
        if (pendingWorkJobs != null && pendingWorkJobs.size() > 0) {
            if (pendingWorkJobs.size() > 1) {
                throw new CloudRuntimeException(String.format("The number of jobs to add network %s to vm %s are %d", network.getUuid(), vm.getInstanceName(), pendingWorkJobs.size()));
            }
            workJob = pendingWorkJobs.get(0);
        } else {
            logger.trace("no jobs to add network {} for vm {} yet", network, vm);

            workJob = createVmWorkJobToAddNetwork(vm, network, requested, context, user, account);
        }
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob, vm.getId());
    }

    VmWorkJobVO createVmWorkJobToAddNetwork(
            VirtualMachine vm,
            Network network,
            NicProfile requested,
            CallContext context,
            User user,
            Account account) {
        VmWorkJobVO workJob;
        workJob = new VmWorkJobVO(context.getContextId());

        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
        workJob.setCmd(VmWorkAddVmToNetwork.class.getName());

        workJob.setAccountId(account.getId());
        workJob.setUserId(user.getId());
        workJob.setVmType(VirtualMachine.Type.Instance);
        workJob.setVmInstanceId(vm.getId());
        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());
        workJob.setSecondaryObjectIdentifier(network.getUuid());

        // save work context info as there might be some duplicates
        final VmWorkAddVmToNetwork workInfo = new VmWorkAddVmToNetwork(user.getId(), account.getId(), vm.getId(),
                VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER, network.getId(), requested);
        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

        try {
            jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());
        } catch (CloudRuntimeException e) {
            if (e.getCause() instanceof EntityExistsException) {
                String msg = String.format("A job to add a nic for network %s to vm %s already exists", network.getUuid(), vm.getUuid());
                logger.warn(msg, e);
            }
            throw e;
        }

        return workJob;
    }

    @Override
    public Outcome<VirtualMachine> removeNicFromVmThroughJobQueue(
            final VirtualMachine vm, final Nic nic) {
        Long vmId = vm.getId();
        String commandName = VmWorkRemoveNicFromVm.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmId, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkRemoveNicFromVm workInfo = new VmWorkRemoveNicFromVm(newVmWorkJobAndInfo.second(), nic.getId());

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob, vmId);
    }

    @Override
    public Outcome<VirtualMachine> removeVmFromNetworkThroughJobQueue(
            final VirtualMachine vm, final Network network, final URI broadcastUri) {
        Long vmId = vm.getId();
        String commandName = VmWorkRemoveVmFromNetwork.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmId, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkRemoveVmFromNetwork workInfo = new VmWorkRemoveVmFromNetwork(newVmWorkJobAndInfo.second(), network, broadcastUri);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }

        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob, vmId);
    }

    @Override
    public Outcome<VirtualMachine> reconfigureVmThroughJobQueue(
            final String vmUuid, final ServiceOffering oldServiceOffering, final ServiceOffering newServiceOffering, Map<String, String> customParameters, final boolean reconfiguringOnExistingHost) {
        String commandName = VmWorkReconfigure.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmUuid, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();
        Long vmId = pendingWorkJob.second();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkReconfigure workInfo = new VmWorkReconfigure(newVmWorkJobAndInfo.second(), oldServiceOffering.getId(), newServiceOffering.getId(), customParameters, reconfiguringOnExistingHost);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob, vmId);
    }

    @Override
    public Outcome<VirtualMachine> restoreVirtualMachineThroughJobQueue(final long vmId, final Long newTemplateId, final Long rootDiskOfferingId, final boolean expunge, Map<String, String> details) {
        String commandName = VmWorkRestore.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmId, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkRestore workInfo = new VmWorkRestore(newVmWorkJobAndInfo.second(), newTemplateId, rootDiskOfferingId, expunge, details);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob, vmId);
    }

    @Override
    public Outcome<VirtualMachine> updateDefaultNicForVMThroughJobQueue(final VirtualMachine vm, final Nic nic, final Nic defaultNic) {
        Long vmId = vm.getId();
        String commandName = VmWorkUpdateDefaultNic.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmId, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkUpdateDefaultNic workInfo = new VmWorkUpdateDefaultNic(newVmWorkJobAndInfo.second(), nic.getId(), defaultNic.getId());

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob, vmId);
    }

    @Override
    public Outcome<VirtualMachine> updateVmNicThroughJobQueue(final VirtualMachine vm, final Nic nic, final Boolean isNicEnabled) {
        Long vmId = vm.getId();
        String commandName = VmWorkUpdateNic.class.getName();
        Pair<VmWorkJobVO, Long> pendingWorkJob = retrievePendingWorkJob(vmId, commandName);

        VmWorkJobVO workJob = pendingWorkJob.first();

        if (workJob == null) {
            Pair<VmWorkJobVO, VmWork> newVmWorkJobAndInfo = createWorkJobAndWorkInfo(commandName, vmId);

            workJob = newVmWorkJobAndInfo.first();
            VmWorkUpdateNic workInfo = new VmWorkUpdateNic(newVmWorkJobAndInfo.second(), nic.getId(), isNicEnabled);

            setCmdInfoAndSubmitAsyncJob(workJob, workInfo, vmId);
        }
        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        return new VmJobVirtualMachineOutcome(workJob, vmId);
    }

    @Override
    public VirtualMachine retrieveVmFromJobOutcome(Outcome<VirtualMachine> jobOutcome, String vmUuid, String jobName) {
        try {
            return jobOutcome.get();
        } catch (InterruptedException | java.util.concurrent.ExecutionException e) {
            throw new RuntimeException(String.format("Unable to retrieve result from job \"%s\" due to [%s]. VM {\"uuid\": \"%s\"}.", jobName, e.getMessage(), vmUuid), e);
        }
    }

    @Override
    public Object retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(Outcome<VirtualMachine> outcome) throws ResourceUnavailableException, InsufficientCapacityException {
        Object jobResult = jobMgr.unmarshallResultObject(outcome.getJob());

        if (jobResult == null) {
            return null;
        }

        if (jobResult instanceof AgentUnavailableException) {
           throw (AgentUnavailableException) jobResult;
        }

        if (jobResult instanceof InsufficientServerCapacityException) {
           throw (InsufficientServerCapacityException) jobResult;
        }

        if (jobResult instanceof ResourceUnavailableException) {
           throw (ResourceUnavailableException) jobResult;
        }

        if (jobResult instanceof InsufficientCapacityException) {
           throw (InsufficientCapacityException) jobResult;
        }

        if (jobResult instanceof ConcurrentOperationException) {
           throw (ConcurrentOperationException) jobResult;
        }

        if (jobResult instanceof RuntimeException) {
           throw (RuntimeException) jobResult;
        }

        if (jobResult instanceof Throwable) {
           throw new RuntimeException("Unexpected exception", (Throwable)jobResult);
        }

        return jobResult;
    }

    Pair<VmWorkJobVO, Long> retrievePendingWorkJob(String vmUuid, String commandName) {
        return retrievePendingWorkJob(null, vmUuid, VirtualMachine.Type.Instance, commandName);
    }

    Pair<VmWorkJobVO, Long> retrievePendingWorkJob(Long id, String commandName) {
        return retrievePendingWorkJob(id, null, VirtualMachine.Type.Instance, commandName);
    }

    Pair<VmWorkJobVO, Long> retrievePendingWorkJob(Long vmId, String vmUuid, VirtualMachine.Type vmType, String commandName) {
        if (vmId == null) {
            VMInstanceVO vm = vmDao.findByUuid(vmUuid);

            if (vm == null) {
                String message = String.format("Could not find a VM with the uuid [%s]. Unable to continue validations with command [%s] through job queue.", vmUuid, commandName);
                logger.error(message);
                throw new RuntimeException(message);
            }

            vmId = vm.getId();

            if (vmType == null) {
                vmType = vm.getType();
            }
        }

        List<VmWorkJobVO> pendingWorkJobs = workJobDao.listPendingWorkJobs(vmType, vmId, commandName);

        if (CollectionUtils.isNotEmpty(pendingWorkJobs)) {
            return new Pair<>(pendingWorkJobs.get(0), vmId);
        }

        return new Pair<>(null, vmId);
    }

    Pair<VmWorkJobVO, VmWork> createWorkJobAndWorkInfo(String commandName, Long vmId) {
        return createWorkJobAndWorkInfo(commandName, null, vmId);
    }

    Pair<VmWorkJobVO, VmWork> createWorkJobAndWorkInfo(String commandName, VmWorkJobVO.Step step, Long vmId) {
        CallContext context = CallContext.current();
        long userId = context.getCallingUser().getId();
        long accountId = context.getCallingAccount().getId();

        VmWorkJobVO workJob = new VmWorkJobVO(context.getContextId());
        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
        workJob.setCmd(commandName);
        workJob.setAccountId(accountId);
        workJob.setUserId(userId);

        if (step != null) {
            workJob.setStep(step);
        }

        workJob.setVmType(VirtualMachine.Type.Instance);
        workJob.setVmInstanceId(vmId);
        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

        VmWork workInfo = new VmWork(userId,  accountId, vmId, VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER);

        return new Pair<>(workJob, workInfo);
    }

    void setCmdInfoAndSubmitAsyncJob(VmWorkJobVO workJob, VmWork workInfo, Long vmId) {
        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));
        jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vmId);
    }
}
