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
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import jakarta.inject.Inject;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.ChapInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.HostScope;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.Scope;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.framework.jobs.AsyncJob;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.Outcome;
import org.apache.cloudstack.framework.jobs.dao.VmWorkJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.apache.cloudstack.storage.command.AttachAnswer;
import org.apache.cloudstack.storage.command.AttachCommand;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.apache.cloudstack.utils.reflectiontostringbuilderutils.ReflectionToStringBuilderUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.dc.DataCenter;
import com.cloud.dc.Pod;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.org.Cluster;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.VmDiskStatisticsVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.VmDiskStatisticsDao;
import com.cloud.utils.Pair;
import com.cloud.utils.Predicate;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.fsm.NoTransitionException;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.VmWorkAttachVolume;
import com.cloud.vm.VmWorkConstants;
import com.cloud.vm.VmWorkSerializer;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Implementation of volume-attach logic extracted from
 * {@link VolumeApiServiceImpl} as part of the Phase 4 Spring-component
 * decomposition (slice 12). The god class keeps the VmWork reflection shim
 * and one-line public wrappers.
 */
@Component
public class VolumeAttachServiceImpl implements VolumeAttachService {

    private static final Logger LOG = LogManager.getLogger(VolumeAttachServiceImpl.class);

    private static final List<Volume.State> VALID_ATTACH_STATES =
            Arrays.asList(Volume.State.Allocated, Volume.State.Ready, Volume.State.Uploaded);

    @Inject
    private VolumeDataFactory volFactory;
    @Inject
    private UserVmDao userVmDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private VolumeDao volsDao;
    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private DataCenterDao dcDao;
    @Inject
    private ClusterDao clusterDao;
    @Inject
    private HostPodDao podDao;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private PrimaryDataStoreDao storagePoolDao;
    @Inject
    private HostDao hostDao;
    @Inject
    private VmDiskStatisticsDao vmDiskStatsDao;
    @Inject
    private AccountDao accountDao;
    @Inject
    private AccountManager accountMgr;
    @Inject
    private ResourceLimitService resourceLimitMgr;
    @Inject
    private AsyncJobManager jobMgr;
    @Inject
    private VmWorkJobDao workJobDao;
    @Inject
    private ReservationDao reservationDao;
    @Inject
    private AgentManager agentMgr;
    @Inject
    private VolumeOrchestrationService volumeMgr;
    @Inject
    private VolumeService volService;
    @Inject
    private DataStoreManager dataStoreMgr;
    @Inject
    private StorageManager storageMgr;
    @Inject
    private VirtualMachineManager virtualMachineManager;
    @Inject
    private VolumeAttachValidator volumeAttachValidator;
    @Inject
    private VolumeHostTopologyService volumeHostTopologyService;

    @Override
    public VolumeVO getVmExistingVolumeForVolumeAttach(UserVmVO vm, VolumeInfo volumeToAttach) {
        VolumeVO existingVolumeOfVm = null;
        VMTemplateVO template = templateDao.findById(vm.getTemplateId());
        List<VolumeVO> rootVolumesOfVm = volsDao.findByInstanceAndType(vm.getId(), Volume.Type.ROOT);
        if (rootVolumesOfVm.size() > 1 && template != null && !template.isDeployAsIs()) {
            throw new CloudRuntimeException("The VM " + vm.getHostName() + " has more than one ROOT volume and is in an invalid state.");
        } else {
            if (!rootVolumesOfVm.isEmpty()) {
                existingVolumeOfVm = rootVolumesOfVm.get(0);
            } else {
                List<VolumeVO> diskVolumesOfVm = volsDao.findByInstanceAndType(vm.getId(), Volume.Type.DATADISK);
                for (VolumeVO diskVolume : diskVolumesOfVm) {
                    if (diskVolume.getState() != Volume.State.Allocated) {
                        existingVolumeOfVm = diskVolume;
                        break;
                    }
                }
            }
        }
        if (existingVolumeOfVm == null) {
            if (LOG.isTraceEnabled()) {
                LOG.trace(String.format("No existing volume found for VM (%s/%s) to attach volume %s/%s",
                        vm.getName(), vm.getUuid(), volumeToAttach.getName(), volumeToAttach.getUuid()));
            }
            return null;
        }
        if (LOG.isTraceEnabled()) {
            String msg = "attaching volume %s/%s to a VM (%s/%s) with an existing volume %s/%s on primary storage %s";
            LOG.trace(String.format(msg,
                    volumeToAttach.getName(), volumeToAttach.getUuid(),
                    vm.getName(), vm.getUuid(),
                    existingVolumeOfVm.getName(), existingVolumeOfVm.getUuid(),
                    existingVolumeOfVm.getPoolId()));
        }
        return existingVolumeOfVm;
    }

    @Override
    public StoragePool getSuitablePoolForAllocatedOrUploadedVolumeForAttach(VolumeInfo volumeToAttach, UserVmVO vm) {
        DataCenter zone = dcDao.findById(vm.getDataCenterId());
        Pair<Long, Long> clusterHostId = virtualMachineManager.findClusterAndHostIdForVm(vm, false);
        Long podId = vm.getPodIdToDeployIn();
        if (clusterHostId.first() != null) {
            Cluster cluster = clusterDao.findById(clusterHostId.first());
            podId = cluster.getPodId();
        }
        Pod pod = podDao.findById(podId);
        DiskOfferingVO offering = diskOfferingDao.findById(volumeToAttach.getDiskOfferingId());
        DiskProfile diskProfile = new DiskProfile(volumeToAttach.getId(), volumeToAttach.getVolumeType(),
                volumeToAttach.getName(), volumeToAttach.getId(), volumeToAttach.getSize(), offering.getTagsArray(),
                offering.isUseLocalStorage(), offering.isRecreatable(), volumeToAttach.getTemplateId());
        diskProfile.setHyperType(vm.getHypervisorType());
        return volumeMgr.findStoragePool(diskProfile, zone, pod, clusterHostId.first(),
                clusterHostId.second(), vm, Collections.emptySet());
    }

    @Override
    public VolumeInfo createVolumeOnPrimaryForAttachIfNeeded(VolumeInfo volumeToAttach, UserVmVO vm, VolumeVO existingVolumeOfVm) {
        VolumeInfo newVolumeOnPrimaryStorage = volumeToAttach;
        boolean volumeOnSecondary = volumeToAttach.getState() == Volume.State.Uploaded;
        if (!Arrays.asList(Volume.State.Allocated, Volume.State.Uploaded).contains(volumeToAttach.getState())) {
            return newVolumeOnPrimaryStorage;
        }
        StoragePool destPrimaryStorage = null;
        if (existingVolumeOfVm != null && !existingVolumeOfVm.getState().equals(Volume.State.Allocated)) {
            destPrimaryStorage = storagePoolDao.findById(existingVolumeOfVm.getPoolId());
            if (LOG.isTraceEnabled() && destPrimaryStorage != null) {
                LOG.trace("decided on target storage: {}", destPrimaryStorage);
            }
        }
        if (destPrimaryStorage == null) {
            destPrimaryStorage = getSuitablePoolForAllocatedOrUploadedVolumeForAttach(volumeToAttach, vm);
            if (destPrimaryStorage == null) {
                if (Volume.State.Allocated.equals(volumeToAttach.getState()) && State.Stopped.equals(vm.getState())) {
                    return newVolumeOnPrimaryStorage;
                }
                throw new CloudRuntimeException(String.format("Failed to find a primary storage for volume in state: %s", volumeToAttach.getState()));
            }
        }
        try {
            if (volumeOnSecondary && Storage.StoragePoolType.PowerFlex.equals(destPrimaryStorage.getPoolType())) {
                throw new InvalidParameterValueException("Cannot attach uploaded volume, this operation is unsupported on storage pool type "
                        + destPrimaryStorage.getPoolType());
            }
            newVolumeOnPrimaryStorage = volumeMgr.createVolumeOnPrimaryStorage(vm, volumeToAttach,
                    vm.getHypervisorType(), destPrimaryStorage);
        } catch (NoTransitionException e) {
            LOG.debug("Failed to create volume on primary storage", e);
            throw new CloudRuntimeException("Failed to create volume on primary storage", e);
        }
        return newVolumeOnPrimaryStorage;
    }

    @Override
    public Volume orchestrateAttachVolumeToVM(Long vmId, Long volumeId, Long deviceId) {
        VolumeInfo volumeToAttach = volFactory.getVolume(volumeId);

        if (volumeToAttach.isAttachedVM()) {
            throw new CloudRuntimeException("This volume is already attached to a VM.");
        }

        UserVmVO vm = userVmDao.findById(vmId);
        VolumeVO existingVolumeOfVm = getVmExistingVolumeForVolumeAttach(vm, volumeToAttach);
        VolumeInfo newVolumeOnPrimaryStorage = createVolumeOnPrimaryForAttachIfNeeded(volumeToAttach, vm, existingVolumeOfVm);

        newVolumeOnPrimaryStorage = volFactory.getVolume(newVolumeOnPrimaryStorage.getId());
        boolean moveVolumeNeeded = needMoveVolume(existingVolumeOfVm, newVolumeOnPrimaryStorage);
        if (LOG.isTraceEnabled()) {
            LOG.trace(String.format("is this a new volume: %s == %s ?", volumeToAttach, newVolumeOnPrimaryStorage));
            LOG.trace(String.format("is it needed to move the volume: %b?", moveVolumeNeeded));
        }

        if (moveVolumeNeeded) {
            PrimaryDataStoreInfo primaryStore = (PrimaryDataStoreInfo)newVolumeOnPrimaryStorage.getDataStore();
            if (primaryStore.isLocal()) {
                throw new CloudRuntimeException(
                        "Failed to attach local data volume " + volumeToAttach.getName() + " to VM " + vm.getDisplayName() + " as migration of local data volume is not allowed");
            }
            StoragePoolVO vmRootVolumePool = storagePoolDao.findById(existingVolumeOfVm.getPoolId());

            try {
                HypervisorType volumeToAttachHyperType = volsDao.getHypervisorType(volumeToAttach.getId());
                newVolumeOnPrimaryStorage = volumeMgr.moveVolume(newVolumeOnPrimaryStorage, vmRootVolumePool.getDataCenterId(),
                        vmRootVolumePool.getPodId(), vmRootVolumePool.getClusterId(), volumeToAttachHyperType);
            } catch (ConcurrentOperationException | StorageUnavailableException e) {
                LOG.debug("move volume failed", e);
                throw new CloudRuntimeException("move volume failed", e);
            }
        }
        VolumeVO newVol = volsDao.findById(newVolumeOnPrimaryStorage.getId());
        if (moveVolumeNeeded) {
            vm = userVmDao.findById(vmId);
            if (vm == null) {
                throw new InvalidParameterValueException("VM not found.");
            }
        }
        return sendAttachVolumeCommand(vm, newVol, deviceId);
    }

    @Override
    public Volume attachVolumeToVM(Long vmId, Long volumeId, Long deviceId, Boolean allowAttachForSharedFS) {
        Account caller = CallContext.current().getCallingAccount();

        VolumeInfo volumeToAttach = getAndCheckVolumeInfo(volumeId);
        UserVmVO vm = getAndCheckUserVmVO(vmId, volumeToAttach);

        if (!allowAttachForSharedFS && UserVmManager.SHAREDFSVM.equals(vm.getUserVmType())) {
            throw new InvalidParameterValueException("Can't attach a volume to a Shared FileSystem Instance");
        }

        volumeAttachValidator.checkDeviceId(deviceId, volumeToAttach, vm);

        HypervisorType rootDiskHyperType = vm.getHypervisorType();
        HypervisorType volumeToAttachHyperType = volsDao.getHypervisorType(volumeToAttach.getId());

        if (HypervisorType.External.equals(rootDiskHyperType)) {
            throw new InvalidParameterValueException("Volume operations are not allowed for External hypervisor type");
        }

        checkNumberOfAttachedVolumes(deviceId, vm);
        volumeAttachValidator.excludeLocalStorageIfNeeded(volumeToAttach);
        volumeAttachValidator.checkForVMSnapshots(vmId, vm);
        volumeAttachValidator.checkForBackups(vm, true);
        accountMgr.checkAccess(caller, null, true, volumeToAttach, vm);

        StoragePoolVO volumeToAttachStoragePool = storagePoolDao.findById(volumeToAttach.getPoolId());
        if (LOG.isTraceEnabled() && volumeToAttachStoragePool != null) {
            LOG.trace("volume to attach {} has a primary storage assigned to begin with {}",
                    ReflectionToStringBuilderUtils.reflectOnlySelectedFields(volumeToAttach, "id", "name", "uuid"),
                    volumeToAttachStoragePool);
        }

        volumeAttachValidator.checkForMatchingHypervisorTypesIf(volumeToAttachStoragePool != null && !volumeToAttachStoragePool.isManaged(),
                rootDiskHyperType, volumeToAttachHyperType);

        AsyncJobExecutionContext asyncExecutionContext = AsyncJobExecutionContext.getCurrentExecutionContext();
        AsyncJob job = asyncExecutionContext.getJob();

        if (LOG.isInfoEnabled()) {
            LOG.info("Trying to attach volume [{}] to VM instance [{}], update async job-{} [{}] progress status",
                    ReflectionToStringBuilderUtils.reflectOnlySelectedFields(volumeToAttach, "id", "name", "uuid"),
                    ReflectionToStringBuilderUtils.reflectOnlySelectedFields(vm, "id", "name", "uuid"),
                    job.getId(), job);
        }

        DiskOfferingVO diskOffering = diskOfferingDao.findById(volumeToAttach.getDiskOfferingId());
        if (diskOffering.getEncrypt() && rootDiskHyperType != HypervisorType.KVM) {
            throw new InvalidParameterValueException("Volume's disk offering has encryption enabled, but volume encryption is not supported for hypervisor type "
                    + rootDiskHyperType);
        }

        Account owner = accountDao.findById(volumeToAttach.getAccountId());
        List<String> resourceLimitStorageTags = resourceLimitMgr.getResourceLimitStorageTagsForResourceCountOperation(true, diskOffering);
        Long requiredPrimaryStorageSpace = volumeAttachValidator.getRequiredPrimaryStorageSizeForVolumeAttach(resourceLimitStorageTags, volumeToAttach);

        try (CheckedReservation primaryStorageReservation = new CheckedReservation(owner, ResourceType.primary_storage,
                resourceLimitStorageTags, requiredPrimaryStorageSpace, reservationDao, resourceLimitMgr)) {
            jobMgr.updateAsyncJobAttachment(job.getId(), "Volume", volumeId);

            if (asyncExecutionContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
                return safelyOrchestrateAttachVolume(vmId, volumeId, deviceId);
            }
            return getVolumeAttachJobResult(vmId, volumeId, deviceId);
        } catch (ResourceAllocationException e) {
            LOG.error("primary storage resource limit check failed", e);
            throw new InvalidParameterValueException(e.getMessage());
        }
    }

    @Override
    @Nullable
    public Volume getVolumeAttachJobResult(Long vmId, Long volumeId, Long deviceId) {
        Outcome<Volume> outcome = attachVolumeToVmThroughJobQueue(vmId, volumeId, deviceId);

        Volume vol = null;
        try {
            outcome.get();
        } catch (InterruptedException | ExecutionException e) {
            throw new CloudRuntimeException(String.format("Could not get attach volume job result for VM [%s], volume[%s] and device [%s], due to [%s].",
                    vmId, volumeId, deviceId, e.getMessage()), e);
        }

        Object jobResult = jobMgr.unmarshallResultObject(outcome.getJob());
        if (jobResult != null) {
            if (jobResult instanceof ConcurrentOperationException) {
                throw (ConcurrentOperationException)jobResult;
            } else if (jobResult instanceof InvalidParameterValueException) {
                throw (InvalidParameterValueException)jobResult;
            } else if (jobResult instanceof RuntimeException) {
                throw (RuntimeException)jobResult;
            } else if (jobResult instanceof Throwable) {
                throw new RuntimeException("Unexpected exception", (Throwable)jobResult);
            } else if (jobResult instanceof Long) {
                vol = volsDao.findById((Long)jobResult);
            }
        }
        return vol;
    }

    private Volume safelyOrchestrateAttachVolume(Long vmId, Long volumeId, Long deviceId) {
        VmWorkJobVO placeHolder = createPlaceHolderWork(vmId);
        try {
            return orchestrateAttachVolumeToVM(vmId, volumeId, deviceId);
        } finally {
            workJobDao.expunge(placeHolder.getId());
        }
    }

    private void checkNumberOfAttachedVolumes(Long deviceId, UserVmVO vm) {
        if (deviceId == null || deviceId.longValue() != 0) {
            List<VolumeVO> existingDataVolumes = volsDao.findByInstanceAndType(vm.getId(), Volume.Type.DATADISK);
            int maxAttachableDataVolumesSupported = volumeHostTopologyService.getMaxDataVolumesSupported(vm);
            if (existingDataVolumes.size() >= maxAttachableDataVolumesSupported) {
                throw new InvalidParameterValueException(
                        "The specified VM already has the maximum number of data disks (" + maxAttachableDataVolumesSupported + ") attached. Please specify another VM.");
            }
        }
    }

    @NotNull
    private UserVmVO getAndCheckUserVmVO(Long vmId, VolumeInfo volumeToAttach) {
        UserVmVO vm = userVmDao.findById(vmId);
        if (vm == null || vm.getType() != VirtualMachine.Type.User) {
            throw new InvalidParameterValueException("Please specify a valid User VM.");
        }
        if (vm.getState() != State.Running && vm.getState() != State.Stopped) {
            throw new InvalidParameterValueException("Please specify a VM that is either running or stopped.");
        }
        if (vm.getDataCenterId() != volumeToAttach.getDataCenterId()) {
            throw new InvalidParameterValueException("Please specify a VM that is in the same zone as the volume.");
        }
        return vm;
    }

    @NotNull
    private VolumeInfo getAndCheckVolumeInfo(Long volumeId) {
        VolumeInfo volumeToAttach = volFactory.getVolume(volumeId);
        if (volumeToAttach == null || !(volumeToAttach.getVolumeType() == Volume.Type.DATADISK || volumeToAttach.getVolumeType() == Volume.Type.ROOT)) {
            throw new InvalidParameterValueException("Please specify a volume with the valid type: " + Volume.Type.ROOT + " or " + Volume.Type.DATADISK);
        }
        if (volumeToAttach.getInstanceId() != null) {
            throw new InvalidParameterValueException("Please specify a volume that is not attached to any VM.");
        }
        if (volumeToAttach.getState() == Volume.State.Destroy) {
            throw new InvalidParameterValueException("Please specify a volume that is not destroyed.");
        }
        if (!VALID_ATTACH_STATES.contains(volumeToAttach.getState())) {
            throw new InvalidParameterValueException("Volume state must be in Allocated, Ready or in Uploaded state");
        }
        return volumeToAttach;
    }

    private boolean needMoveVolume(VolumeVO existingVolume, VolumeInfo newVolume) {
        if (existingVolume == null || existingVolume.getPoolId() == null || newVolume.getPoolId() == null) {
            return false;
        }

        DataStore storeForExistingVol = dataStoreMgr.getPrimaryDataStore(existingVolume.getPoolId());
        DataStore storeForNewVol = dataStoreMgr.getPrimaryDataStore(newVolume.getPoolId());

        Scope storeForExistingStoreScope = storeForExistingVol.getScope();
        if (storeForExistingStoreScope == null) {
            throw new CloudRuntimeException(String.format("Can't get scope of data store: %s", storeForExistingVol));
        }

        Scope storeForNewStoreScope = storeForNewVol.getScope();
        if (storeForNewStoreScope == null) {
            throw new CloudRuntimeException(String.format("Can't get scope of data store: %s", storeForNewVol));
        }

        if (storeForNewStoreScope.getScopeType() == ScopeType.ZONE) {
            return false;
        }

        if (storeForExistingStoreScope.getScopeType() != storeForNewStoreScope.getScopeType()) {
            if (storeForNewStoreScope.getScopeType() == ScopeType.CLUSTER) {
                Long vmClusterId = null;
                if (storeForExistingStoreScope.getScopeType() == ScopeType.HOST) {
                    HostScope hs = (HostScope)storeForExistingStoreScope;
                    vmClusterId = hs.getClusterId();
                } else if (storeForExistingStoreScope.getScopeType() == ScopeType.ZONE) {
                    Long hostId = vmInstanceDao.findById(existingVolume.getInstanceId()).getHostId();
                    if (hostId != null) {
                        HostVO host = hostDao.findById(hostId);
                        vmClusterId = host.getClusterId();
                    }
                }
                return !storeForNewStoreScope.getScopeId().equals(vmClusterId);
            } else if (storeForNewStoreScope.getScopeType() == ScopeType.HOST
                    && (storeForExistingStoreScope.getScopeType() == ScopeType.CLUSTER || storeForExistingStoreScope.getScopeType() == ScopeType.ZONE)) {
                VMInstanceVO vm = vmInstanceDao.findById(existingVolume.getInstanceId());
                Long hostId = vm.getHostId();
                if (hostId == null) {
                    hostId = vm.getLastHostId();
                }
                if (storeForNewStoreScope.getScopeId().equals(hostId)) {
                    return false;
                }
            }
            throw new InvalidParameterValueException("Can't move volume between scope: " + storeForNewStoreScope.getScopeType()
                    + " and " + storeForExistingStoreScope.getScopeType());
        }

        return !storeForExistingStoreScope.isSameScope(storeForNewStoreScope);
    }

    private synchronized void checkAndSetAttaching(Long volumeId) {
        VolumeInfo volumeToAttach = volFactory.getVolume(volumeId);

        if (volumeToAttach.isAttachedVM()) {
            throw new CloudRuntimeException("volume: " + volumeToAttach.getName() + " is already attached to a VM: " + volumeToAttach.getAttachedVmName());
        }
        if (Volume.State.Allocated.equals(volumeToAttach.getState())) {
            return;
        }
        if (Volume.State.Ready.equals(volumeToAttach.getState())) {
            volumeToAttach.stateTransit(Volume.Event.AttachRequested);
            return;
        }

        final String error = String.format("Volume: %s is in %s. It should be in Ready or Allocated state", volumeToAttach, volumeToAttach.getState());
        LOG.error(error);
        throw new CloudRuntimeException(error);
    }

    private VolumeVO sendAttachVolumeCommand(UserVmVO vm, VolumeVO volumeToAttach, Long deviceId) {
        String errorMsg = "Failed to attach volume " + volumeToAttach.getName() + " to VM " + vm.getHostName();
        boolean sendCommand = vm.getState() == State.Running;
        AttachAnswer answer = null;
        HypervisorType rootDiskHyperType = vm.getHypervisorType();
        StoragePoolVO volumeToAttachStoragePool = storagePoolDao.findById(volumeToAttach.getPoolId());
        if (HypervisorType.External.equals(rootDiskHyperType)) {
            throw new InvalidParameterValueException("Volume operations are not allowed for External hypervisor type");
        }

        if (LOG.isTraceEnabled() && volumeToAttachStoragePool != null) {
            LOG.trace("storage is gotten from volume to attach: {}", volumeToAttachStoragePool);
        }
        HostVO host = volumeHostTopologyService.getHostForVmVolumeAttachDetach(vm, volumeToAttachStoragePool);
        Long hostId = host != null ? host.getId() : null;
        sendCommand = sendCommand || volumeHostTopologyService.isSendCommandForVmVolumeAttachDetach(host, volumeToAttachStoragePool);

        if (host != null) {
            hostDao.loadDetails(host);
            boolean hostSupportsEncryption = Boolean.parseBoolean(host.getDetail(Host.HOST_VOLUME_ENCRYPTION));
            if (volumeToAttach.getPassphraseId() != null && !hostSupportsEncryption) {
                throw new CloudRuntimeException(errorMsg + " because target host " + host + " doesn't support volume encryption");
            }
        }

        if (volumeToAttachStoragePool != null) {
            volumeHostTopologyService.verifyManagedStorage(volumeToAttachStoragePool.getId(), hostId);
        }

        DataStore dataStore = volumeToAttachStoragePool != null
                ? dataStoreMgr.getDataStore(volumeToAttachStoragePool.getId(), DataStoreRole.Primary)
                : null;

        checkAndSetAttaching(volumeToAttach.getId());

        boolean attached = false;
        try {
            if (host != null) {
                try {
                    volService.checkAndRepairVolumeBasedOnConfig(volFactory.getVolume(volumeToAttach.getId()), host);
                } catch (Exception e) {
                    LOG.debug("Unable to check and repair volume [{}] on host [{}], due to {}.", volumeToAttach, host, e.getMessage());
                }

                try {
                    volService.grantAccess(volFactory.getVolume(volumeToAttach.getId()), host, dataStore);
                } catch (Exception e) {
                    volService.revokeAccess(volFactory.getVolume(volumeToAttach.getId()), host, dataStore);
                    throw new CloudRuntimeException(e.getMessage());
                }
            }

            if (sendCommand) {
                if (host != null && host.getHypervisorType() == HypervisorType.KVM && volumeToAttachStoragePool.isManaged() && volumeToAttach.getPath() == null) {
                    volumeToAttach.setPath(volumeToAttach.get_iScsiName());
                    volsDao.update(volumeToAttach.getId(), volumeToAttach);
                }

                DataTO volTO = volFactory.getVolume(volumeToAttach.getId()).getTO();
                deviceId = volumeHostTopologyService.getDeviceId(vm, deviceId);

                DiskTO disk = storageMgr.getDiskWithThrottling(volTO, volumeToAttach.getVolumeType(), deviceId, volumeToAttach.getPath(),
                        vm.getServiceOfferingId(), volumeToAttach.getDiskOfferingId());

                AttachCommand cmd = new AttachCommand(disk, vm.getInstanceName());
                ChapInfo chapInfo = volService.getChapInfo(volFactory.getVolume(volumeToAttach.getId()), dataStore);
                Map<String, String> details = new HashMap<>();

                disk.setDetails(details);

                details.put(DiskTO.MANAGED, String.valueOf(volumeToAttachStoragePool.isManaged()));
                details.put(DiskTO.STORAGE_HOST, volumeToAttachStoragePool.getHostAddress());
                details.put(DiskTO.STORAGE_PORT, String.valueOf(volumeToAttachStoragePool.getPort()));
                details.put(DiskTO.VOLUME_SIZE, String.valueOf(volumeToAttach.getSize()));
                details.put(DiskTO.IQN, volumeToAttach.get_iScsiName());
                details.put(DiskTO.MOUNT_POINT, volumeToAttach.get_iScsiName());
                details.put(DiskTO.PROTOCOL_TYPE, volumeToAttach.getPoolType() != null ? volumeToAttach.getPoolType().toString() : null);
                details.put(StorageManager.STORAGE_POOL_DISK_WAIT.toString(),
                        String.valueOf(StorageManager.STORAGE_POOL_DISK_WAIT.valueIn(volumeToAttachStoragePool.getId())));

                userVmDao.loadDetails(vm);
                if (volumeHostTopologyService.isIothreadsSupported(vm)) {
                    details.put(VmDetailConstants.IOTHREADS, VmDetailConstants.IOTHREADS);
                }

                String ioPolicy = volumeHostTopologyService.getIoPolicy(vm, volumeToAttachStoragePool.getId());
                if (ioPolicy != null) {
                    details.put(VmDetailConstants.IO_POLICY, ioPolicy);
                }

                if (chapInfo != null) {
                    details.put(DiskTO.CHAP_INITIATOR_USERNAME, chapInfo.getInitiatorUsername());
                    details.put(DiskTO.CHAP_INITIATOR_SECRET, chapInfo.getInitiatorSecret());
                    details.put(DiskTO.CHAP_TARGET_USERNAME, chapInfo.getTargetUsername());
                    details.put(DiskTO.CHAP_TARGET_SECRET, chapInfo.getTargetSecret());
                }

                if (volumeToAttach.getPoolId() != null) {
                    StoragePoolVO poolVO = storagePoolDao.findById(volumeToAttach.getPoolId());
                    if (poolVO.getParent() != 0L) {
                        details.put(DiskTO.PROTOCOL_TYPE, Storage.StoragePoolType.DatastoreCluster.toString());
                    }
                }

                Map<String, String> controllerInfo = new HashMap<>();
                controllerInfo.put(VmDetailConstants.ROOT_DISK_CONTROLLER, vm.getDetail(VmDetailConstants.ROOT_DISK_CONTROLLER));
                controllerInfo.put(VmDetailConstants.DATA_DISK_CONTROLLER, vm.getDetail(VmDetailConstants.DATA_DISK_CONTROLLER));
                cmd.setControllerInfo(controllerInfo);
                LOG.debug("Attach volume {} on VM {} has controller info: {}", volumeToAttach, vm, controllerInfo);

                try {
                    answer = (AttachAnswer)agentMgr.send(hostId, cmd);
                } catch (AgentUnavailableException e) {
                    if (host != null) {
                        volService.revokeAccess(volFactory.getVolume(volumeToAttach.getId()), host, dataStore);
                    }
                    throw new CloudRuntimeException(String.format("%s. Please contact your system administrator.", errorMsg));
                } catch (Exception e) {
                    if (host != null) {
                        volService.revokeAccess(volFactory.getVolume(volumeToAttach.getId()), host, dataStore);
                    }
                    throw new CloudRuntimeException(errorMsg + " due to: " + e.getMessage());
                }
            }

            if (!sendCommand || (answer != null && answer.getResult())) {
                if (sendCommand) {
                    DiskTO disk = answer.getDisk();
                    volsDao.attachVolume(volumeToAttach.getId(), vm.getId(), disk.getDiskSeq());
                    volumeToAttach = volsDao.findById(volumeToAttach.getId());

                    if (volumeToAttachStoragePool.isManaged() && volumeToAttach.getPath() == null) {
                        volumeToAttach.setPath(answer.getDisk().getPath());
                        volsDao.update(volumeToAttach.getId(), volumeToAttach);
                    }

                    if (answer.getContextParam("vdiskUuid") != null) {
                        volumeToAttach = volsDao.findById(volumeToAttach.getId());
                        volumeToAttach.setExternalUuid(answer.getContextParam("vdiskUuid"));
                        volsDao.update(volumeToAttach.getId(), volumeToAttach);
                    }

                    String chainInfo = answer.getContextParam("chainInfo");
                    if (chainInfo != null) {
                        volumeToAttach = volsDao.findById(volumeToAttach.getId());
                        volumeToAttach.setChainInfo(chainInfo);
                        volsDao.update(volumeToAttach.getId(), volumeToAttach);
                    }
                } else {
                    deviceId = volumeHostTopologyService.getDeviceId(vm, deviceId);
                    volsDao.attachVolume(volumeToAttach.getId(), vm.getId(), deviceId);
                    volumeToAttach = volsDao.findById(volumeToAttach.getId());

                    if (vm.getHypervisorType() == HypervisorType.KVM
                            && volumeToAttachStoragePool != null && volumeToAttachStoragePool.isManaged()
                            && volumeToAttach.getPath() == null && volumeToAttach.get_iScsiName() != null) {
                        volumeToAttach.setPath(volumeToAttach.get_iScsiName());
                        volsDao.update(volumeToAttach.getId(), volumeToAttach);
                    }

                    if (host != null && volumeToAttachStoragePool != null && volumeToAttachStoragePool.getPoolType() == Storage.StoragePoolType.PowerFlex) {
                        volService.revokeAccess(volFactory.getVolume(volumeToAttach.getId()), host, dataStore);
                    }
                }

                VmDiskStatisticsVO diskstats = vmDiskStatsDao.findBy(vm.getAccountId(), vm.getDataCenterId(), vm.getId(), volumeToAttach.getId());
                if (diskstats == null) {
                    diskstats = new VmDiskStatisticsVO(vm.getAccountId(), vm.getDataCenterId(), vm.getId(), volumeToAttach.getId());
                    vmDiskStatsDao.persist(diskstats);
                }

                attached = true;
            } else {
                if (answer != null) {
                    String details = answer.getDetails();
                    if (details != null && !details.isEmpty()) {
                        errorMsg += "; " + details;
                    }
                }
                if (host != null) {
                    volService.revokeAccess(volFactory.getVolume(volumeToAttach.getId()), host, dataStore);
                }
                throw new CloudRuntimeException(errorMsg);
            }
        } finally {
            Volume.Event ev = Volume.Event.OperationFailed;
            VolumeInfo volInfo = volFactory.getVolume(volumeToAttach.getId());
            if (attached) {
                ev = Volume.Event.OperationSucceeded;
                LOG.debug("Volume: {} successfully attached to VM: {}", volInfo.getVolume(), volInfo.getAttachedVM());
                UsageEventUtils.publishUsageEvent(EventTypes.EVENT_VOLUME_ATTACH, volumeToAttach.getAccountId(), volumeToAttach.getDataCenterId(),
                        volumeToAttach.getId(), volumeToAttach.getName(), volumeToAttach.getDiskOfferingId(), volumeToAttach.getTemplateId(),
                        volumeToAttach.getSize(), Volume.class.getName(), volumeToAttach.getUuid(), vm.getId(), volumeToAttach.isDisplay());
                volumeHostTopologyService.provideVMInfo(dataStore, vm.getId(), volInfo.getId());
            } else {
                LOG.debug("Volume: {} failed to attach to VM: {}", volInfo.getVolume(), volInfo.getAttachedVM());
            }
            volInfo.stateTransit(ev);
        }
        return volsDao.findById(volumeToAttach.getId());
    }

    @Override
    public Outcome<Volume> attachVolumeToVmThroughJobQueue(Long vmId, Long volumeId, Long deviceId) {
        final CallContext context = CallContext.current();
        final User callingUser = context.getCallingUser();
        final Account callingAccount = context.getCallingAccount();
        final VMInstanceVO vm = vmInstanceDao.findById(vmId);

        VmWorkJobVO workJob = new VmWorkJobVO(context.getContextId());
        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
        workJob.setCmd(VmWorkAttachVolume.class.getName());
        workJob.setAccountId(callingAccount.getId());
        workJob.setUserId(callingUser.getId());
        workJob.setStep(VmWorkJobVO.Step.Starting);
        workJob.setVmType(VirtualMachine.Type.Instance);
        workJob.setVmInstanceId(vm.getId());
        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

        VmWorkAttachVolume workInfo = new VmWorkAttachVolume(callingUser.getId(), callingAccount.getId(), vm.getId(),
                VolumeApiServiceImpl.VM_WORK_JOB_HANDLER, volumeId, deviceId);
        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

        jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());

        AsyncJobVO jobVo = jobMgr.getAsyncJob(workJob.getId());
        LOG.debug("New job {}, result field: {}", workJob, jobVo.getResult());

        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());
        return new VmJobVolumeOutcome(workJob, volumeId);
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

    private class VmJobVolumeOutcome implements Outcome<Volume> {
        private final AsyncJob job;
        private final long volumeId;

        VmJobVolumeOutcome(AsyncJob job, long volumeId) {
            this.job = job;
            this.volumeId = volumeId;
        }

        @Override
        public AsyncJob getJob() {
            return jobMgr.getAsyncJob(job.getId());
        }

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            return false;
        }

        @Override
        public Volume get() throws InterruptedException, ExecutionException {
            jobMgr.waitAndCheck(getJob(), new String[]{AsyncJob.Topics.JOB_STATE}, VolumeApiServiceImpl.VmJobCheckInterval.value(), -1, new Predicate() {
                @Override
                public boolean checkCondition() {
                    AsyncJobVO jobVo = jobMgr.getAsyncJob(job.getId());
                    return jobVo == null || jobVo.getStatus() != org.apache.cloudstack.jobs.JobInfo.Status.IN_PROGRESS;
                }
            });
            try {
                AsyncJobExecutionContext.getCurrentExecutionContext().disjoinJob(job.getId());
            } catch (Exception e) {
                throw new ExecutionException("Job task has trouble executing", e);
            }
            return volsDao.findById(volumeId);
        }

        @Override
        public Volume get(long timeout, TimeUnit unit) throws InterruptedException, ExecutionException, TimeoutException {
            get();
            return volsDao.findById(volumeId);
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean isDone() {
            return false;
        }

        @Override
        public void execute(Task<Volume> task) {
        }

        @Override
        public void execute(Task<Volume> task, long wait, TimeUnit unit) {
        }
    }
}
