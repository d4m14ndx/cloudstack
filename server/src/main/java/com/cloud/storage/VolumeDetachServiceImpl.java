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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.user.volume.DetachVolumeCmd;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.backup.Backup;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
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
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.cloudstack.utils.reflectiontostringbuilderutils.ReflectionToStringBuilderUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.ModifyTargetsCommand;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.serializer.GsonHelper;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.Predicate;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmService;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VmWorkConstants;
import com.cloud.vm.VmWorkDetachVolume;
import com.cloud.vm.VmWorkSerializer;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

/**
 * Implementation of volume-detach logic extracted from
 * {@link VolumeApiServiceImpl} as part of the Phase 4 Spring-component
 * decomposition (slice 9).
 *
 * @see VolumeDetachService
 */
@Component
public class VolumeDetachServiceImpl implements VolumeDetachService {

    private static final Logger LOG = LogManager.getLogger(VolumeDetachServiceImpl.class);

    @Inject
    private VolumeDao volsDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private UserVmDao userVmDao;
    @Inject
    private VMSnapshotDao vmSnapshotDao;
    @Inject
    private PrimaryDataStoreDao storagePoolDao;
    @Inject
    private AccountManager accountMgr;
    @Inject
    private AgentManager agentMgr;
    @Inject
    private AsyncJobManager jobMgr;
    @Inject
    private VmWorkJobDao workJobDao;
    @Inject
    private VolumeDataFactory volFactory;
    @Inject
    private VolumeService volService;
    @Inject
    private DataStoreManager dataStoreMgr;
    @Inject
    private VolumeOrchestrationService volumeMgr;
    @Inject
    private VolumeAttachValidator volumeAttachValidator;
    @Inject
    private HostDao hostDao;
    @Inject
    private UserVmService userVmService;
    @Inject
    private VolumeHostTopologyService volumeHostTopologyService;
    @Inject
    private DiskOfferingDao diskOfferingDao;

    // -------------------------------------------------------------------------
    // VolumeDetachService interface
    // -------------------------------------------------------------------------

    @Override
    public Volume detachVolumeFromVM(DetachVolumeCmd cmmd) {
        Account caller = CallContext.current().getCallingAccount();
        if ((cmmd.getId() == null && cmmd.getDeviceId() == null && cmmd.getVirtualMachineId() == null)
                || (cmmd.getId() != null && (cmmd.getDeviceId() != null || cmmd.getVirtualMachineId() != null))
                || (cmmd.getId() == null && (cmmd.getDeviceId() == null || cmmd.getVirtualMachineId() == null))) {
            throw new InvalidParameterValueException("Please provide either a volume id, or a tuple(device id, instance id)");
        }

        Long volumeId = cmmd.getId();
        VolumeVO volume = null;

        if (volumeId != null) {
            volume = volsDao.findById(volumeId);
        } else {
            volume = volsDao.findByInstanceAndDeviceId(cmmd.getVirtualMachineId(), cmmd.getDeviceId()).get(0);
        }

        // Check that the volume ID is valid
        if (volume == null) {
            throw new InvalidParameterValueException("Unable to find volume with ID: " + volumeId);
        }

        Long vmId = null;

        if (cmmd.getVirtualMachineId() == null) {
            vmId = volume.getInstanceId();
        } else {
            vmId = cmmd.getVirtualMachineId();
        }

        // Permissions check
        accountMgr.checkAccess(caller, null, true, volume);

        // Check that the volume is currently attached to a VM
        if (vmId == null) {
            throw new InvalidParameterValueException("The specified volume is not attached to a VM.");
        }

        // Check that the VM is in the correct state
        UserVmVO vm = userVmDao.findById(vmId);

        if (UserVmManager.SHAREDFSVM.equals(vm.getUserVmType())) {
            throw new InvalidParameterValueException("Can't detach a volume from a Shared FileSystem Instance");
        }

        if (vm.getState() != State.Running && vm.getState() != State.Stopped && vm.getState() != State.Destroyed) {
            throw new InvalidParameterValueException("Please specify a VM that is either running or stopped.");
        }

        // Check that the volume is a data/root volume
        if (!(volume.getVolumeType() == Volume.Type.ROOT || volume.getVolumeType() == Volume.Type.DATADISK)) {
            throw new InvalidParameterValueException("Please specify volume of type " + Volume.Type.DATADISK.toString() + " or " + Volume.Type.ROOT.toString());
        }

        // Root volume detach is allowed for following hypervisors: Xen/KVM/VmWare
        if (volume.getVolumeType() == Volume.Type.ROOT) {
            volumeAttachValidator.validateRootVolumeDetachAttach(volume, vm);
        }

        // Don't allow detach if target VM has associated VM snapshots
        List<VMSnapshotVO> vmSnapshots = vmSnapshotDao.findByVm(vmId);
        if (CollectionUtils.isNotEmpty(vmSnapshots)) {
            throw new InvalidParameterValueException("Unable to detach volume, please specify an Instance that does not have Instance Snapshots");
        }

        volumeAttachValidator.checkForBackups(vm, false);

        AsyncJobExecutionContext asyncExecutionContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (asyncExecutionContext != null) {
            AsyncJob job = asyncExecutionContext.getJob();

            if (LOG.isInfoEnabled()) {
                LOG.info("Trying to attach volume {} to VM instance {}, update async job-{} progress status",
                        ReflectionToStringBuilderUtils.reflectOnlySelectedFields(volume, "id", "name", "uuid"),
                        ReflectionToStringBuilderUtils.reflectOnlySelectedFields(vm, "id", "name", "uuid"),
                        job.getId());
            }

            jobMgr.updateAsyncJobAttachment(job.getId(), "Volume", volumeId);
        }

        AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
            // avoid re-entrance
            VmWorkJobVO placeHolder = createPlaceHolderWork(vmId);
            try {
                return orchestrateDetachVolumeFromVM(vmId, volumeId);
            } finally {
                workJobDao.expunge(placeHolder.getId());
            }
        } else {
            Outcome<Volume> outcome = detachVolumeFromVmThroughJobQueue(vmId, volumeId);

            Volume vol = null;
            try {
                outcome.get();
            } catch (InterruptedException e) {
                throw new RuntimeException("Operation is interrupted", e);
            } catch (java.util.concurrent.ExecutionException e) {
                throw new CloudRuntimeException("Execution exception getting the outcome of the asynchronous detach volume job", e);
            }

            Object jobResult = jobMgr.unmarshallResultObject(outcome.getJob());
            if (jobResult != null) {
                if (jobResult instanceof ConcurrentOperationException) {
                    throw (ConcurrentOperationException) jobResult;
                } else if (jobResult instanceof RuntimeException) {
                    throw (RuntimeException) jobResult;
                } else if (jobResult instanceof Throwable) {
                    throw new RuntimeException("Unexpected exception", (Throwable) jobResult);
                } else if (jobResult instanceof Long) {
                    vol = volsDao.findById((Long) jobResult);
                }
            }
            if (vm.getBackupOfferingId() != null) {
                vm.setBackupVolumes(createVolumeInfoFromVolumes(volsDao.findByInstance(vm.getId())));
                vmInstanceDao.update(vm.getId(), vm);
            }
            return vol;
        }
    }

    @Override
    public Volume detachVolumeViaDestroyVM(long vmId, long volumeId) {
        Account caller = CallContext.current().getCallingAccount();
        Volume volume = volsDao.findById(volumeId);
        // Permissions check
        accountMgr.checkAccess(caller, null, true, volume);
        return orchestrateDetachVolumeFromVM(vmId, volumeId);
    }

    @Override
    public Volume orchestrateDetachVolumeFromVM(long vmId, long volumeId) {
        Volume volume = volsDao.findById(volumeId);
        VMInstanceVO vm = vmInstanceDao.findById(vmId);

        String errorMsg = "Failed to detach volume " + volume.getName() + " from VM " + vm.getHostName();
        boolean sendCommand = vm.getState() == State.Running;

        StoragePoolVO volumePool = storagePoolDao.findByIdIncludingRemoved(volume.getPoolId());
        HostVO host = volumeHostTopologyService.getHostForVmVolumeAttachDetach(vm, volumePool);
        Long hostId = host != null ? host.getId() : null;
        sendCommand = sendCommand || volumeHostTopologyService.isSendCommandForVmVolumeAttachDetach(host, volumePool);

        Answer answer = null;

        if (sendCommand) {
            // collect vm disk statistics before detach a volume
            UserVmVO userVm = userVmDao.findById(vmId);
            if (userVm != null && userVm.getType() == VirtualMachine.Type.User) {
                userVmService.collectVmDiskStatistics(userVm);
            }

            DataTO volTO = volFactory.getVolume(volume.getId()).getTO();
            ((VolumeObjectTO) volTO).setCheckpointPaths(volumeMgr.getVolumeCheckpointPathsAndImageStoreUrls(volumeId, vm.getHypervisorType()).first());
            DiskTO disk = new DiskTO(volTO, volume.getDeviceId(), volume.getPath(), volume.getVolumeType());
            Map<String, String> details = new HashMap<>();
            disk.setDetails(details);
            if (volume.getPoolId() != null) {
                StoragePoolVO poolVO = storagePoolDao.findById(volume.getPoolId());
                if (poolVO.getParent() != 0L) {
                    details.put(DiskTO.PROTOCOL_TYPE, Storage.StoragePoolType.DatastoreCluster.toString());
                }
            }

            DettachCommand cmd = new DettachCommand(disk, vm.getInstanceName());

            cmd.setManaged(volumePool.isManaged());

            cmd.setStorageHost(volumePool.getHostAddress());
            cmd.setStoragePort(volumePool.getPort());

            cmd.set_iScsiName(volume.get_iScsiName());
            cmd.setWaitDetachDevice(VolumeApiServiceImpl.WaitDetachDevice.value());

            try {
                answer = agentMgr.send(hostId, cmd);
            } catch (AgentUnavailableException e) {
                throw new CloudRuntimeException(String.format("%s. Please contact your system administrator.", errorMsg));
            } catch (Exception e) {
                throw new CloudRuntimeException(errorMsg + " due to: " + e.getMessage());
            }
        }

        if (!sendCommand || (answer != null && answer.getResult())) {
            // Mark the volume as detached
            volsDao.detachVolume(volume.getId());

            if (answer != null) {
                String datastoreName = answer.getContextParam("datastoreName");
                if (datastoreName != null) {
                    StoragePoolVO storagePoolVO = storagePoolDao.findByUuid(datastoreName);
                    if (storagePoolVO != null) {
                        VolumeVO volumeVO = volsDao.findById(volumeId);
                        volumeVO.setPoolId(storagePoolVO.getId());
                        volumeVO.setPoolType(storagePoolVO.getPoolType());
                        volsDao.update(volumeVO.getId(), volumeVO);
                    } else {
                        LOG.warn("Unable to find datastore {} while updating the new datastore of the volume {}", datastoreName, volume);
                    }
                }

                String volumePath = answer.getContextParam("volumePath");
                if (volumePath != null) {
                    VolumeVO volumeVO = volsDao.findById(volumeId);
                    volumeVO.setPath(volumePath);
                    volsDao.update(volumeVO.getId(), volumeVO);
                }

                String chainInfo = answer.getContextParam("chainInfo");
                if (chainInfo != null) {
                    VolumeVO volumeVO = volsDao.findById(volumeId);
                    volumeVO.setChainInfo(chainInfo);
                    volsDao.update(volumeVO.getId(), volumeVO);
                }
            }

            // volume.getPoolId() should be null if the VM we are detaching the disk from has never been started before
            if (volume.getPoolId() != null) {
                DataStore dataStore = dataStoreMgr.getDataStore(volume.getPoolId(), DataStoreRole.Primary);
                volService.revokeAccess(volFactory.getVolume(volume.getId()), host, dataStore);
                volumeHostTopologyService.provideVMInfo(dataStore, vmId, volumeId);
            }
            if (volumePool != null && hostId != null) {
                handleTargetsForVMware(hostId, volumePool.getHostAddress(), volumePool.getPort(), volume.get_iScsiName());
            }

            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_DETACH, volume.getAccountId(), volume.getDataCenterId(), volume.getId(), volume.getName(),
                    volume.getDiskOfferingId(), null, volume.getSize(), Volume.class.getName(), volume.getUuid(), null, volume.isDisplay());
            return volsDao.findById(volumeId);
        } else {

            if (answer != null) {
                String details = answer.getDetails();
                if (details != null && !details.isEmpty()) {
                    errorMsg += "; " + details;
                }
            }

            throw new CloudRuntimeException(errorMsg);
        }
    }

    @Override
    public Outcome<Volume> detachVolumeFromVmThroughJobQueue(final Long vmId, final Long volumeId) {

        final CallContext context = CallContext.current();
        final User callingUser = context.getCallingUser();
        final Account callingAccount = context.getCallingAccount();

        final VMInstanceVO vm = vmInstanceDao.findById(vmId);

        VmWorkJobVO workJob = new VmWorkJobVO(context.getContextId());

        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
        workJob.setCmd(VmWorkDetachVolume.class.getName());

        workJob.setAccountId(callingAccount.getId());
        workJob.setUserId(callingUser.getId());
        workJob.setStep(VmWorkJobVO.Step.Starting);
        workJob.setVmType(VirtualMachine.Type.Instance);
        workJob.setVmInstanceId(vm.getId());
        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

        // save work context info (there are some duplications)
        VmWorkDetachVolume workInfo = new VmWorkDetachVolume(callingUser.getId(), callingAccount.getId(), vm.getId(),
                VolumeApiServiceImpl.VM_WORK_JOB_HANDLER, volumeId);
        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

        jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());

        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        final VolumeDao finalVolsDao = volsDao;
        final long finalVolumeId = volumeId;
        return new OutcomeImpl<Volume>(Volume.class, workJob, VolumeApiServiceImpl.VmJobCheckInterval.value(),
                new Predicate() {
                    @Override
                    public boolean checkCondition() {
                        AsyncJobVO jobVo = jobMgr.getAsyncJob(workJob.getId());
                        assert (jobVo != null);
                        if (jobVo == null || jobVo.getStatus() != JobInfo.Status.IN_PROGRESS) {
                            return true;
                        }
                        return false;
                    }
                }, AsyncJob.Topics.JOB_STATE) {
            @Override
            protected Volume retrieve() {
                return finalVolsDao.findById(finalVolumeId);
            }
        };
    }

    // -------------------------------------------------------------------------
    // private helpers
    // -------------------------------------------------------------------------

    private void handleTargetsForVMware(long hostId, String storageAddress, int storagePort, String iScsiName) {
        HostVO host = hostDao.findById(hostId);

        if (host.getHypervisorType() == HypervisorType.VMware) {
            ModifyTargetsCommand cmd = new ModifyTargetsCommand();

            List<Map<String, String>> targets = new ArrayList<>();

            Map<String, String> target = new HashMap<>();

            target.put(ModifyTargetsCommand.STORAGE_HOST, storageAddress);
            target.put(ModifyTargetsCommand.STORAGE_PORT, String.valueOf(storagePort));
            target.put(ModifyTargetsCommand.IQN, iScsiName);

            targets.add(target);

            cmd.setTargets(targets);
            cmd.setApplyToAllHostsInCluster(true);
            cmd.setAdd(false);
            cmd.setTargetTypeToRemove(ModifyTargetsCommand.TargetTypeToRemove.DYNAMIC);

            sendModifyTargetsCommand(cmd, host);
        }
    }

    private void sendModifyTargetsCommand(ModifyTargetsCommand cmd, Host host) {
        Answer answer = agentMgr.easySend(host.getId(), cmd);

        if (answer == null) {
            String msg = "Unable to get an answer to the modify targets command";
            LOG.warn(msg);
        } else if (!answer.getResult()) {
            String msg = String.format("Unable to modify target on the following host: %s", host);
            LOG.warn(msg);
        }
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
        workJob.setInitMsid(org.apache.cloudstack.utils.identity.ManagementServerNode.getManagementServerId());

        workJobDao.persist(workJob);

        return workJob;
    }

    /**
     * Duplicated from {@link VolumeApiServiceImpl#createVolumeInfoFromVolumes} — no
     * back-reference to the god class (PLAYBOOK shared-helper precedent).
     */
    private String createVolumeInfoFromVolumes(List<VolumeVO> vmVolumes) {
        try {
            List<Backup.VolumeInfo> list = new ArrayList<>();
            for (VolumeVO vol : vmVolumes) {
                DiskOfferingVO diskOffering = diskOfferingDao.findById(vol.getDiskOfferingId());
                String diskOfferingUuid = diskOffering != null ? diskOffering.getUuid() : null;
                list.add(new Backup.VolumeInfo(vol.getUuid(), vol.getPath(), vol.getVolumeType(), vol.getSize(),
                        vol.getDeviceId(), diskOfferingUuid, vol.getMinIops(), vol.getMaxIops()));
            }
            return GsonHelper.getGson().toJson(list.toArray(), Backup.VolumeInfo[].class);
        } catch (Exception e) {
            if (CollectionUtils.isEmpty(vmVolumes) || vmVolumes.get(0).getInstanceId() == null) {
                LOG.error(String.format("Failed to create VolumeInfo of VM [id: null] volumes due to: [%s].", e.getMessage()), e);
            } else {
                LOG.error(String.format("Failed to create VolumeInfo of VM [id: %s] volumes due to: [%s].",
                        vmVolumes.get(0).getInstanceId(), e.getMessage()), e);
            }
            throw e;
        }
    }
}
