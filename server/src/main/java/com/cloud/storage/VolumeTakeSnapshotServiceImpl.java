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

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreCapabilities;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
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
import org.apache.cloudstack.resourcedetail.SnapshotPolicyDetailVO;
import org.apache.cloudstack.resourcedetail.dao.SnapshotPolicyDetailsDao;
import org.apache.cloudstack.snapshot.SnapshotHelper;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.org.Grouping;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.snapshot.SnapshotApiService;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.Predicate;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VmWorkConstants;
import com.cloud.vm.VmWorkSerializer;
import com.cloud.vm.VmWorkTakeVolumeSnapshot;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDetailsDao;

/**
 * Implementation of snapshot-creation logic extracted from
 * {@link VolumeApiServiceImpl}.
 *
 * @see VolumeTakeSnapshotService
 */
@Component
public class VolumeTakeSnapshotServiceImpl implements VolumeTakeSnapshotService {

    private static final Logger LOG = LogManager.getLogger(VolumeTakeSnapshotServiceImpl.class);

    @Inject
    private VolumeDataFactory volFactory;
    @Inject
    private VolumeService volService;
    @Inject
    private SnapshotApiService snapshotMgr;
    @Inject
    private SnapshotPolicyDetailsDao snapshotPolicyDetailsDao;
    @Inject
    private SnapshotHelper snapshotHelper;
    @Inject
    private VMSnapshotDetailsDao vmSnapshotDetailsDao;
    @Inject
    private AccountManager accountMgr;
    @Inject
    private DataCenterDao dcDao;
    @Inject
    private PrimaryDataStoreDao storagePoolDao;
    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private UserVmDao userVmDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private SnapshotDao snapshotDao;
    @Inject
    private AsyncJobManager jobMgr;
    @Inject
    private VmWorkJobDao workJobDao;
    @Inject
    private AccountManager accountManager;
    @Inject
    private DataStoreManager dataStoreMgr;
    @Inject
    private EntityManager entityMgr;

    static final String KVM_FILE_BASED_STORAGE_SNAPSHOT = VolumeApiServiceImpl.KVM_FILE_BASED_STORAGE_SNAPSHOT;

    @Override
    public Snapshot takeSnapshotInternal(Long volumeId, Long policyId, Long snapshotId, Account account,
            boolean quiescevm, Snapshot.LocationType locationType, boolean asyncBackup,
            List<Long> zoneIds, List<Long> poolIds, Boolean useStorageReplication)
            throws ResourceAllocationException {

        Account caller = CallContext.current().getCallingAccount();
        VolumeInfo volume = volFactory.getVolume(volumeId);
        poolIds = snapshotHelper.addStoragePoolsForCopyToPrimary(volume, zoneIds, poolIds, useStorageReplication);
        canCopyOnPrimary(poolIds, volume, CollectionUtils.isEmpty(poolIds));
        if (volume == null) {
            throw new InvalidParameterValueException("Creating snapshot failed due to volume:" + volumeId + " doesn't exist");
        }
        if (HypervisorType.External.equals(volume.getHypervisorType())) {
            throw new InvalidParameterValueException("Snapshot operations are not allowed for External hypervisor type");
        }
        if (policyId != null && policyId > 0) {
            if (CollectionUtils.isNotEmpty(zoneIds)) {
                throw new InvalidParameterValueException(String.format("%s can not be specified for snapshots linked with snapshot policy", ApiConstants.ZONE_ID_LIST));
            }
            List<SnapshotPolicyDetailVO> details = snapshotPolicyDetailsDao.findDetails(policyId, ApiConstants.ZONE_ID);
            zoneIds = details.stream().map(d -> Long.valueOf(d.getValue())).collect(Collectors.toList());
            poolIds = getPoolIdsByPolicy(policyId, poolIds);
        }
        if (CollectionUtils.isNotEmpty(zoneIds)) {
            for (Long destZoneId : zoneIds) {
                DataCenterVO dstZone = dcDao.findById(destZoneId);
                if (dstZone == null) {
                    throw new InvalidParameterValueException("Please specify a valid destination zone.");
                }
            }
        }

        accountMgr.checkAccess(caller, null, true, volume);

        if (volume.getState() != Volume.State.Ready) {
            throw new InvalidParameterValueException(String.format("Volume: %s is not in %s state but %s. Cannot take snapshot.", volume.getVolume(), Volume.State.Ready, volume.getState()));
        }

        StoragePoolVO storagePoolVO = storagePoolDao.findById(volume.getPoolId());

        if (storagePoolVO.isManaged() && locationType == null) {
            locationType = Snapshot.LocationType.PRIMARY;
        }

        VMInstanceVO vm = null;
        if (volume.getInstanceId() != null) {
            vm = vmInstanceDao.findById(volume.getInstanceId());
        }

        if (vm != null) {
            accountMgr.checkAccess(caller, null, true, vm);
            // serialize VM operation
            AsyncJobExecutionContext jobContext = AsyncJobExecutionContext.getCurrentExecutionContext();
            if (jobContext.isJobDispatchedBy(VmWorkConstants.VM_WORK_JOB_DISPATCHER)) {
                // avoid re-entrance
                VmWorkJobVO placeHolder = createPlaceHolderWork(vm.getId());
                try {
                    return orchestrateTakeVolumeSnapshot(volumeId, policyId, snapshotId, account, quiescevm,
                            locationType, asyncBackup, zoneIds, poolIds);
                } finally {
                    workJobDao.expunge(placeHolder.getId());
                }
            } else {
                Outcome<Snapshot> outcome = takeVolumeSnapshotThroughJobQueue(vm.getId(), volumeId, policyId,
                        snapshotId, account.getId(), quiescevm, locationType, asyncBackup, zoneIds, poolIds);

                try {
                    outcome.get();
                } catch (InterruptedException e) {
                    throw new RuntimeException("Operation is interrupted", e);
                } catch (ExecutionException e) {
                    throw new CloudRuntimeException("Execution exception getting the outcome of the asynchronous take volume snapshot job", e);
                }

                Object jobResult = jobMgr.unmarshallResultObject(outcome.getJob());
                if (jobResult != null) {
                    if (jobResult instanceof ConcurrentOperationException) {
                        throw (ConcurrentOperationException) jobResult;
                    } else if (jobResult instanceof ResourceAllocationException) {
                        throw (ResourceAllocationException) jobResult;
                    } else if (jobResult instanceof Throwable) {
                        throw new RuntimeException("Unexpected exception", (Throwable) jobResult);
                    }
                }

                return snapshotDao.findById(snapshotId);
            }
        } else {
            CreateSnapshotPayload payload = new CreateSnapshotPayload();
            payload.setSnapshotId(snapshotId);
            payload.setSnapshotPolicyId(policyId);
            payload.setAccount(account);
            payload.setQuiescevm(quiescevm);
            payload.setAsyncBackup(asyncBackup);
            if (CollectionUtils.isNotEmpty(zoneIds)) {
                payload.setZoneIds(zoneIds);
            }
            if (CollectionUtils.isNotEmpty(poolIds)) {
                payload.setStoragePoolIds(poolIds);
            }
            volume.addPayload(payload);
            return volService.takeSnapshot(volume);
        }
    }

    @Override
    public Snapshot orchestrateTakeVolumeSnapshot(Long volumeId, Long policyId, Long snapshotId,
            Account account, boolean quiescevm, Snapshot.LocationType locationType,
            boolean asyncBackup, List<Long> zoneIds, List<Long> poolIds)
            throws ResourceAllocationException {

        VolumeInfo volume = volFactory.getVolume(volumeId);

        if (volume == null) {
            throw new InvalidParameterValueException("Creating snapshot failed due to volume:" + volumeId + " doesn't exist");
        }

        if (volume.getState() != Volume.State.Ready) {
            throw new InvalidParameterValueException(String.format("Volume: %s is not in %s state but %s. Cannot take snapshot.", volume.getVolume(), Volume.State.Ready, volume.getState()));
        }

        boolean isSnapshotOnStorPoolOnly = volume.getStoragePoolType() == StoragePoolType.StorPool && SnapshotInfo.BackupSnapshotAfterTakingSnapshot.value();
        if (volume.getEncryptFormat() != null && volume.getAttachedVM() != null && volume.getAttachedVM().getState() != State.Stopped && !isSnapshotOnStorPoolOnly) {
            LOG.debug(String.format("Refusing to take snapshot of encrypted volume (%s) on running VM (%s)", volume, volume.getAttachedVM()));
            throw new UnsupportedOperationException("Volume snapshots for encrypted volumes are not supported if VM is running");
        }

        CreateSnapshotPayload payload = new CreateSnapshotPayload();

        payload.setSnapshotId(snapshotId);
        payload.setSnapshotPolicyId(policyId);
        payload.setAccount(account);
        payload.setQuiescevm(quiescevm);
        payload.setLocationType(locationType);
        payload.setAsyncBackup(asyncBackup);
        if (CollectionUtils.isNotEmpty(zoneIds)) {
            payload.setZoneIds(zoneIds);
        }
        if (CollectionUtils.isNotEmpty(poolIds)) {
            payload.setStoragePoolIds(poolIds);
        }

        volume.addPayload(payload);

        return volService.takeSnapshot(volume);
    }

    @Override
    public Snapshot allocSnapshot(Long volumeId, Long policyId, String snapshotName,
            Snapshot.LocationType locationType, List<Long> zoneIds, List<Long> poolIds,
            Boolean useStorageReplication) throws ResourceAllocationException {

        Account caller = CallContext.current().getCallingAccount();

        VolumeInfo volume = volFactory.getVolume(volumeId);
        if (volume == null) {
            throw new InvalidParameterValueException("Creating snapshot failed due to volume:" + volumeId + " doesn't exist");
        }
        DataCenter zone = dcDao.findById(volume.getDataCenterId());
        if (zone == null) {
            throw new InvalidParameterValueException(String.format("Can't find zone for the volume ID: %s", volume.getUuid()));
        }

        if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !accountMgr.isRootAdmin(caller.getId())) {
            throw new PermissionDeniedException("Cannot perform this operation, Zone is currently disabled: " + zone.getName());
        }

        if (volume.getState() != Volume.State.Ready) {
            throw new InvalidParameterValueException(String.format("Volume: %s is not in %s state but %s. Cannot take snapshot.", volume.getVolume(), Volume.State.Ready, volume.getState()));
        }

        if (ImageFormat.DIR.equals(volume.getFormat())) {
            throw new InvalidParameterValueException(String.format("Snapshot not supported for volume: %s", volume.getVolume()));
        }
        if (volume.getTemplateId() != null) {
            VMTemplateVO template = templateDao.findById(volume.getTemplateId());
            Long instanceId = volume.getInstanceId();
            UserVmVO userVmVO = null;
            if (instanceId != null) {
                userVmVO = userVmDao.findById(instanceId);
            }
            if (!isOperationSupported(template, userVmVO)) {
                throw new InvalidParameterValueException(String.format("Volume: %s is for System VM , Creating snapshot against System VM volumes is not supported", volume.getVolume()));
            }
        }
        snapshotHelper.addStoragePoolsForCopyToPrimary(volume, zoneIds, poolIds, useStorageReplication);
        canCopyOnPrimary(poolIds, volume, CollectionUtils.isEmpty(poolIds));

        StoragePoolVO storagePoolVO = storagePoolDao.findById(volume.getPoolId());

        if (!storagePoolVO.isManaged() && locationType != null) {
            throw new InvalidParameterValueException("VolumeId: " + volumeId + " LocationType is supported only for managed storage");
        }

        if (storagePoolVO.isManaged() && locationType == null) {
            locationType = Snapshot.LocationType.PRIMARY;
        }

        StoragePool storagePool = (StoragePool) volume.getDataStore();
        if (storagePool == null) {
            throw new InvalidParameterValueException(String.format("Volume: %s please attach this volume to a VM before create snapshot for it", volume.getVolume()));
        }
        boolean canCopyOnPrimary = Boolean.TRUE.equals(useStorageReplication);

        if (CollectionUtils.isNotEmpty(zoneIds)) {
            if (policyId != null && policyId > 0) {
                throw new InvalidParameterValueException(String.format("%s parameter can not be specified with %s parameter", ApiConstants.ZONE_ID_LIST, ApiConstants.POLICY_ID));
            }
            if (Snapshot.LocationType.PRIMARY.equals(locationType)) {
                throw new InvalidParameterValueException(String.format("%s cannot be specified with snapshot %s as %s", ApiConstants.ZONE_ID_LIST, ApiConstants.LOCATION_TYPE, Snapshot.LocationType.PRIMARY));
            }
            if (Boolean.FALSE.equals(SnapshotInfo.BackupSnapshotAfterTakingSnapshot.value()) && !canCopyOnPrimary) {
                throw new InvalidParameterValueException("Backing up of snapshot has been disabled. Snapshot can not be taken for multiple zones");
            }
            if (DataCenter.Type.Edge.equals(zone.getType())) {
                throw new InvalidParameterValueException("Backing up of snapshot is not supported by the zone of the volume. Snapshot can not be taken for multiple zones");
            }
            for (Long zoneId : zoneIds) {
                DataCenter dataCenter = dcDao.findById(zoneId);
                if (dataCenter == null) {
                    throw new InvalidParameterValueException("Unable to find the specified zone");
                }
                if (Grouping.AllocationState.Disabled.equals(dataCenter.getAllocationState()) && !accountMgr.isRootAdmin(caller.getId())) {
                    throw new PermissionDeniedException("Cannot perform this operation, Zone is currently disabled: " + dataCenter.getName());
                }
                if (DataCenter.Type.Edge.equals(dataCenter.getType())) {
                    throw new InvalidParameterValueException("Snapshot functionality is not supported on zone %s");
                }
            }
        }

        return snapshotMgr.allocSnapshot(volumeId, policyId, snapshotName, locationType, false, zoneIds);
    }

    @Override
    public Snapshot allocSnapshotForVm(Long vmId, Long volumeId, String snapshotName, Long vmSnapshotId)
            throws ResourceAllocationException {

        Account caller = CallContext.current().getCallingAccount();
        VMInstanceVO vm = vmInstanceDao.findById(vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("Creating snapshot failed due to vm:" + vmId + " doesn't exist");
        }
        accountMgr.checkAccess(caller, null, true, vm);

        VolumeInfo volume = volFactory.getVolume(volumeId);
        if (volume == null) {
            throw new InvalidParameterValueException("Creating snapshot failed due to volume:" + volumeId + " doesn't exist");
        }
        accountMgr.checkAccess(caller, null, true, volume);
        VirtualMachine attachVM = volume.getAttachedVM();
        if (attachVM == null || attachVM.getId() != vm.getId()) {
            throw new InvalidParameterValueException(String.format("Creating snapshot failed due to volume:%s doesn't attach to vm :%s", volume.getVolume(), vm));
        }

        DataCenter zone = dcDao.findById(volume.getDataCenterId());
        if (zone == null) {
            throw new InvalidParameterValueException("Can't find zone by id " + volume.getDataCenterId());
        }

        if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !accountMgr.isRootAdmin(caller.getId())) {
            throw new PermissionDeniedException("Cannot perform this operation, Zone is currently disabled: " + zone.getName());
        }

        if (volume.getState() != Volume.State.Ready) {
            throw new InvalidParameterValueException(String.format("Volume: %s is not in %s state but %s. Cannot take snapshot.", volume.getVolume(), Volume.State.Ready, volume.getState()));
        }

        if (volume.getTemplateId() != null) {
            VMTemplateVO template = templateDao.findById(volume.getTemplateId());
            Long instanceId = volume.getInstanceId();
            UserVmVO userVmVO = null;
            if (instanceId != null) {
                userVmVO = userVmDao.findById(instanceId);
            }
            if (!isOperationSupported(template, userVmVO)) {
                throw new InvalidParameterValueException(String.format("Volume: %s is for System VM , Creating snapshot against System VM volumes is not supported", volume.getVolume()));
            }
        }

        StoragePool storagePool = (StoragePool) volume.getDataStore();
        if (storagePool == null) {
            throw new InvalidParameterValueException(String.format("Volume: %s please attach this volume to a VM before create snapshot for it", volume.getVolume()));
        }

        if (storagePool.getPoolType() == Storage.StoragePoolType.PowerFlex) {
            throw new InvalidParameterValueException("Cannot perform this operation, unsupported on storage pool type " + storagePool.getPoolType());
        }

        if (vmSnapshotDetailsDao.listDetails(vmSnapshotId).stream().anyMatch(vmSnapshotDetailsVO -> KVM_FILE_BASED_STORAGE_SNAPSHOT.equals(vmSnapshotDetailsVO.getName()))) {
            throw new InvalidParameterValueException("Cannot perform this operation, unsupported VM snapshot type.");
        }

        return snapshotMgr.allocSnapshot(volumeId, Snapshot.MANUAL_POLICY_ID, snapshotName, null, true, null);
    }

    @Override
    public Outcome<Snapshot> takeVolumeSnapshotThroughJobQueue(final Long vmId, final Long volumeId,
            final Long policyId, final Long snapshotId, final Long accountId, final boolean quiesceVm,
            final Snapshot.LocationType locationType, final boolean asyncBackup,
            final List<Long> zoneIds, final List<Long> poolIds) {

        final CallContext context = CallContext.current();
        final User callingUser = context.getCallingUser();
        final Account callingAccount = context.getCallingAccount();

        final VMInstanceVO vm = vmInstanceDao.findById(vmId);

        VmWorkJobVO workJob = new VmWorkJobVO(context.getContextId());

        workJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
        workJob.setCmd(VmWorkTakeVolumeSnapshot.class.getName());

        workJob.setAccountId(callingAccount.getId());
        workJob.setUserId(callingUser.getId());
        workJob.setStep(VmWorkJobVO.Step.Starting);
        workJob.setVmType(VirtualMachine.Type.Instance);
        workJob.setVmInstanceId(vm.getId());
        workJob.setRelated(AsyncJobExecutionContext.getOriginJobId());

        // save work context info (there are some duplications)
        VmWorkTakeVolumeSnapshot workInfo = new VmWorkTakeVolumeSnapshot(callingUser.getId(),
                accountId != null ? accountId : callingAccount.getId(), vm.getId(),
                VolumeApiServiceImpl.VM_WORK_JOB_HANDLER, volumeId, policyId, snapshotId,
                quiesceVm, locationType, asyncBackup, zoneIds, poolIds);
        workJob.setCmdInfo(VmWorkSerializer.serialize(workInfo));

        jobMgr.submitAsyncJob(workJob, VmWorkConstants.VM_WORK_QUEUE, vm.getId());

        AsyncJobExecutionContext.getCurrentExecutionContext().joinJob(workJob.getId());

        final SnapshotDao finalSnapshotDao = snapshotDao;
        final EntityManager finalEntityMgr = entityMgr;
        final long finalSnapshotId = snapshotId != null ? snapshotId : 0L;
        return new OutcomeImpl<Snapshot>(Snapshot.class, workJob, VolumeApiServiceImpl.VmJobCheckInterval.value(),
                new Predicate() {
                    @Override
                    public boolean checkCondition() {
                        AsyncJobVO jobVo = finalEntityMgr.findById(AsyncJobVO.class, workJob.getId());
                        assert (jobVo != null);
                        if (jobVo == null || jobVo.getStatus() != JobInfo.Status.IN_PROGRESS) {
                            return true;
                        }
                        return false;
                    }
                }, AsyncJob.Topics.JOB_STATE) {
            @Override
            protected Snapshot retrieve() {
                return finalSnapshotDao.findById(finalSnapshotId);
            }
        };
    }

    // -------------------------------------------------------------------------
    // private helpers
    // -------------------------------------------------------------------------

    private List<Long> getPoolIdsByPolicy(Long policyId, List<Long> poolIds) {
        if (CollectionUtils.isNotEmpty(poolIds)) {
            throw new InvalidParameterValueException(String.format("%s can not be specified for snapshots linked with snapshot policy", ApiConstants.STORAGE_ID_LIST));
        }
        List<SnapshotPolicyDetailVO> poolDetails = snapshotPolicyDetailsDao.findDetails(policyId, ApiConstants.STORAGE_ID);
        poolIds = poolDetails.stream().map(d -> Long.valueOf(d.getValue())).collect(Collectors.toList());
        return poolIds;
    }

    private boolean canCopyOnPrimary(List<Long> poolIds, VolumeInfo volume, boolean isPoolIdsEmpty) {
        if (!isPoolIdsEmpty) {
            for (Long poolId : poolIds) {
                DataStore dataStore = dataStoreMgr.getDataStore(poolId, DataStoreRole.Primary);
                StoragePoolVO sPool = storagePoolDao.findById(poolId);
                if (dataStore != null
                        && !dataStore.getDriver().getCapabilities().containsKey(DataStoreCapabilities.CAN_COPY_SNAPSHOT_BETWEEN_ZONES_AND_SAME_POOL_TYPE.toString())
                        && sPool.getPoolType() != volume.getStoragePoolType()
                        && volume.getPoolId() == poolId) {
                    throw new InvalidParameterValueException("The specified pool doesn't support copying snapshots between zones" + poolId);
                }
            }
        } else {
            return false;
        }
        snapshotHelper.checkIfThereAreMoreThanOnePoolInTheZone(poolIds);
        return true;
    }

    private boolean isOperationSupported(VMTemplateVO template, UserVmVO userVm) {
        if (template != null && template.getTemplateType() == Storage.TemplateType.SYSTEM
                && (userVm == null || !UserVmManager.CKS_NODE.equals(userVm.getUserVmType())
                        || !UserVmManager.SHAREDFSVM.equals(userVm.getUserVmType()))) {
            return false;
        }
        return true;
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
}
