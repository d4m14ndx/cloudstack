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

package org.apache.cloudstack.backup;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.backup.CreateImageTransferCmd;
import org.apache.cloudstack.api.command.admin.backup.DeleteVmCheckpointCmd;
import org.apache.cloudstack.api.command.admin.backup.FinalizeBackupCmd;
import org.apache.cloudstack.api.command.admin.backup.FinalizeImageTransferCmd;
import org.apache.cloudstack.api.command.admin.backup.ListImageTransfersCmd;
import org.apache.cloudstack.api.command.admin.backup.ListVmCheckpointsCmd;
import org.apache.cloudstack.api.command.admin.backup.StartBackupCmd;
import org.apache.cloudstack.api.response.CheckpointResponse;
import org.apache.cloudstack.api.response.ImageTransferResponse;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.cloudstack.backup.dao.ImageTransferDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.User;
import com.cloud.user.AccountService;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@Component
public class KVMBackupExportServiceImpl extends ManagerBase implements KVMBackupExportService {
    private static final SecureRandom TRANSFER_TOKEN_RANDOM = new SecureRandom();
    private static final int TRANSFER_TOKEN_BYTES = 32;

    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Inject
    private BackupDao backupDao;
    @Inject
    private ImageTransferDao imageTransferDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private VolumeDataFactory volumeDataFactory;
    @Inject
    private AgentManager agentManager;
    @Inject
    private HostDao hostDao;
    @Inject
    private PrimaryDataStoreDao primaryDataStoreDao;
    @Inject
    private StoragePoolHostDao storagePoolHostDao;
    @Inject
    private DataCenterDao dataCenterDao;
    @Inject
    private AccountService accountService;
    @Inject
    private VirtualMachineManager virtualMachineManager;

    @Override
    public Backup createBackup(StartBackupCmd cmd) {
        Long vmId = cmd.getVmId();
        VMInstanceVO vm = vmInstanceDao.findById(vmId);
        if (vm == null) {
            throw new CloudRuntimeException("Instance not found: " + vmId);
        }

        if (!isBackupableVmState(vm.getState())) {
            throw new CloudRuntimeException("Instance must be running or stopped to start Backup");
        }

        validateVmVolumesForBackup(vm);

        Pair<Long, Long> clusterAndHostId = virtualMachineManager.findClusterAndHostIdForVm(vm, false);
        Long hostId = clusterAndHostId == null ? null : clusterAndHostId.second();
        if (hostId == null) {
            throw new CloudRuntimeException("Host cannot be determined for Instance: " + vm.getUuid());
        }

        BackupVO backup = new BackupVO();
        backup.setVmId(vmId);
        backup.setName(StringUtils.defaultIfBlank(cmd.getName(), vmId + "-" + Instant.now()));
        if (StringUtils.isNotBlank(cmd.getDescription())) {
            backup.setDescription(cmd.getDescription());
        }
        backup.setAccountId(vm.getAccountId());
        backup.setDomainId(vm.getDomainId());
        backup.setZoneId(vm.getDataCenterId());
        backup.setStatus(Backup.Status.Queued);
        backup.setBackupOfferingId(0L);
        backup.setDate(new Date());
        backup.setType("FULL");
        backup.setHostId(hostId);
        backup.setToCheckpointId("ckp-" + UUID.randomUUID().toString().substring(0, 8));
        backup.setFromCheckpointId(vmInstanceDetailsDao.listDetailsKeyPairs(vmId).get(VmDetailConstants.ACTIVE_CHECKPOINT_ID));

        return backupDao.persist(backup);
    }

    protected void validateVmVolumesForBackup(VMInstanceVO vm) {
        List<VolumeVO> volumes = volumeDao.findByInstance(vm.getId()).stream()
                .filter(volume -> !Volume.State.Ready.equals(volume.getState()))
                .collect(Collectors.toList());
        if (CollectionUtils.isEmpty(volumes)) {
            return;
        }

        String volumeUuids = volumes.stream()
                .map(VolumeVO::getUuid)
                .collect(Collectors.joining(","));
        throw new CloudRuntimeException(String.format("Volumes [%s] of Instance: %s are not in Ready state",
                volumeUuids, vm.getUuid()));
    }

    @Override
    public Backup startBackup(StartBackupCmd cmd) {
        BackupVO backup = backupDao.findById(cmd.getEntityId());
        if (backup == null) {
            throw new CloudRuntimeException("Backup not found: " + cmd.getEntityId());
        }

        VMInstanceVO vm = vmInstanceDao.findById(cmd.getVmId());
        if (vm == null) {
            failBackup(backup);
            throw new CloudRuntimeException("Instance not found for Backup: " + backup.getUuid());
        }

        if (!isBackupableVmState(vm.getState())) {
            failBackup(backup);
            throw new CloudRuntimeException("Instance must be running or stopped to start Backup");
        }

        if (backup.getHostId() == null) {
            failBackup(backup);
            throw new CloudRuntimeException("Host cannot be found for Backup: " + backup.getUuid());
        }

        boolean stoppedVm = VirtualMachine.State.Stopped.equals(vm.getState());
        Map<String, String> diskPathUuidMap = new HashMap<>();
        Map<String, byte[]> diskPathPassphraseMap = new HashMap<>();
        for (VolumeVO volume : volumeDao.findByInstance(cmd.getVmId())) {
            String volumePath = getVolumePathForFileBasedBackend(volume);
            diskPathUuidMap.put(volumePath, volume.getUuid());
            if (stoppedVm) {
                VolumeInfo volumeInfo = volumeDataFactory.getVolume(volume.getId());
                if (volumeInfo != null) {
                    diskPathPassphraseMap.put(volumePath, volumeInfo.getPassphrase());
                }
            }
        }

        Map<String, String> vmDetails = vmInstanceDetailsDao.listDetailsKeyPairs(cmd.getVmId());
        Long fromCheckpointCreateTime = parseOptionalLong(vmDetails.get(VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME));
        StartBackupCommand startBackupCommand = new StartBackupCommand(
                vm.getInstanceName(),
                backup.getToCheckpointId(),
                backup.getFromCheckpointId(),
                fromCheckpointCreateTime,
                backup.getUuid(),
                diskPathUuidMap,
                diskPathPassphraseMap,
                stoppedVm);

        StartBackupAnswer answer = send(backup.getHostId(), startBackupCommand, StartBackupAnswer.class);
        if (!answer.getResult()) {
            failBackup(backup);
            throw new CloudRuntimeException("Failed to start Backup: " + answer.getDetails());
        }

        backup.setCheckpointCreateTime(answer.getCheckpointCreateTime());
        updateBackupState(backup, Backup.Status.ReadyForImageTransfer);
        return backup;
    }

    @Override
    public Backup finalizeBackup(FinalizeBackupCmd cmd) {
        BackupVO backup = backupDao.findById(cmd.getBackupId());
        if (backup == null) {
            throw new CloudRuntimeException("Backup not found: " + cmd.getBackupId());
        }
        if (!cmd.getVmId().equals(backup.getVmId())) {
            throw new CloudRuntimeException("Backup does not belong to VM: " + cmd.getVmId());
        }

        VMInstanceVO vm = vmInstanceDao.findById(cmd.getVmId());
        if (vm == null) {
            throw new CloudRuntimeException("VM not found: " + cmd.getVmId());
        }

        updateBackupState(backup, Backup.Status.FinalizingImageTransfer);
        for (ImageTransferVO imageTransfer : imageTransferDao.listByBackupId(backup.getId())) {
            if (!ImageTransfer.Phase.finished.equals(imageTransfer.getPhase())) {
                finalizeImageTransfer(imageTransfer.getId());
            }
        }

        if (VirtualMachine.State.Running.equals(vm.getState())) {
            StopBackupAnswer answer = send(backup.getHostId(),
                    new StopBackupCommand(vm.getInstanceName(), cmd.getVmId(), cmd.getBackupId()),
                    StopBackupAnswer.class);
            if (!answer.getResult()) {
                failBackup(backup);
                throw new CloudRuntimeException("Failed to stop backup: " + answer.getDetails());
            }
        }

        rotateVmCheckpointDetails(cmd.getVmId(), backup);
        updateBackupState(backup, Backup.Status.BackedUp);
        return backup;
    }

    @Override
    public ImageTransferResponse createImageTransfer(CreateImageTransferCmd cmd) {
        return toImageTransferResponse((ImageTransferVO) createImageTransfer(cmd.getVolumeId(), cmd.getBackupId(),
                cmd.getDirection(), cmd.getFormat()));
    }

    @Override
    public ImageTransfer createImageTransfer(long volumeId, Long backupId, ImageTransfer.Direction direction, ImageTransfer.Format format) {
        VolumeVO volume = volumeDao.findById(volumeId);
        if (volume == null) {
            throw new CloudRuntimeException("Volume not found with the specified Id");
        }

        User callingUser = CallContext.current().getCallingUser();
        accountService.checkAccess(callingUser, volume);

        ImageTransferVO existingTransfer = imageTransferDao.findUnfinishedByVolume(volume.getId());
        if (existingTransfer != null) {
            throw new CloudRuntimeException("Image transfer already exists for volume: " + volume.getUuid());
        }

        ImageTransfer.Backend backend = getImageTransferBackend(format, direction);
        if (ImageTransfer.Direction.upload.equals(direction)) {
            return createUploadImageTransfer(volume, backend);
        }
        if (ImageTransfer.Direction.download.equals(direction)) {
            return createDownloadImageTransfer(backupId, volume, backend);
        }
        throw new CloudRuntimeException("Invalid direction: " + direction);
    }

    @Override
    public boolean cancelImageTransfer(long imageTransferId) {
        return finalizeImageTransfer(imageTransferId);
    }

    @Override
    public boolean finalizeImageTransfer(FinalizeImageTransferCmd cmd) {
        return finalizeImageTransfer(cmd.getImageTransferId());
    }

    @Override
    public boolean finalizeImageTransfer(long imageTransferId) {
        ImageTransferVO imageTransfer = imageTransferDao.findById(imageTransferId);
        if (imageTransfer == null) {
            throw new CloudRuntimeException("Image transfer not found: " + imageTransferId);
        }

        Answer finalizeAnswer = send(imageTransfer.getHostId(),
                new FinalizeImageTransferCommand(imageTransfer.getUuid()),
                Answer.class);
        if (!finalizeAnswer.getResult()) {
            throw new CloudRuntimeException("Failed to finalize image transfer: " + finalizeAnswer.getDetails());
        }

        if (ImageTransfer.Backend.nbd.equals(imageTransfer.getBackend())) {
            Answer stopAnswer = send(imageTransfer.getHostId(),
                    new StopNBDServerCommand(imageTransfer.getUuid(), imageTransfer.getDirection().toString()),
                    Answer.class);
            if (!stopAnswer.getResult()) {
                throw new CloudRuntimeException("Failed to stop the nbd server: " + stopAnswer.getDetails());
            }
        }

        imageTransfer.setPhase(ImageTransfer.Phase.finished);
        imageTransferDao.update(imageTransfer.getId(), imageTransfer);
        imageTransferDao.remove(imageTransfer.getId());
        return true;
    }

    @Override
    public List<ImageTransferResponse> listImageTransfers(ListImageTransfersCmd cmd) {
        List<ImageTransferVO> transfers;
        if (cmd.getId() != null) {
            ImageTransferVO transfer = imageTransferDao.findById(cmd.getId());
            transfers = transfer == null ? Collections.emptyList() : List.of(transfer);
        } else if (cmd.getBackupId() != null) {
            transfers = imageTransferDao.listByBackupId(cmd.getBackupId());
        } else {
            transfers = imageTransferDao.listAll();
        }
        return transfers.stream()
                .map(this::toImageTransferResponse)
                .collect(Collectors.toList());
    }

    @Override
    public List<CheckpointResponse> listVmCheckpoints(ListVmCheckpointsCmd cmd) {
        VMInstanceVO vm = vmInstanceDao.findById(cmd.getVmId());
        if (vm == null) {
            throw new CloudRuntimeException("VM not found: " + cmd.getVmId());
        }

        Map<String, String> details = vmInstanceDetailsDao.listDetailsKeyPairs(cmd.getVmId());
        List<CheckpointResponse> responses = new ArrayList<>();
        addCheckpointResponse(responses, details.get(VmDetailConstants.ACTIVE_CHECKPOINT_ID),
                details.get(VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME), true);
        addCheckpointResponse(responses, details.get(VmDetailConstants.LAST_CHECKPOINT_ID),
                details.get(VmDetailConstants.LAST_CHECKPOINT_CREATE_TIME), false);
        return responses;
    }

    @Override
    public boolean deleteVmCheckpoint(DeleteVmCheckpointCmd cmd) {
        VMInstanceVO vm = vmInstanceDao.findById(cmd.getVmId());
        if (vm == null) {
            throw new CloudRuntimeException("VM not found: " + cmd.getVmId());
        }
        if (!isBackupableVmState(vm.getState())) {
            throw new CloudRuntimeException("VM must be running or stopped to delete checkpoint");
        }

        Map<String, String> details = vmInstanceDetailsDao.listDetailsKeyPairs(cmd.getVmId());
        String activeCheckpointId = details.get(VmDetailConstants.ACTIVE_CHECKPOINT_ID);
        if (!cmd.getCheckpointId().equals(activeCheckpointId)) {
            return true;
        }

        Answer answer = send(getHostIdForCheckpointCommand(vm),
                createDeleteCheckpointCommand(vm, activeCheckpointId),
                Answer.class);
        if (!answer.getResult()) {
            throw new CloudRuntimeException("Failed to delete checkpoint: " + answer.getDetails());
        }
        promoteLastCheckpointAfterActiveDelete(cmd.getVmId(), details);
        return true;
    }

    @Override
    public List<Long> listCompatibleDataCenterIds() {
        return dataCenterDao.listAllIds();
    }

    @Override
    public List<Class<?>> getCommands() {
        List<Class<?>> cmdList = new ArrayList<>();
        if (ExposeKVMBackupExportServiceApis.value()) {
            cmdList.add(StartBackupCmd.class);
            cmdList.add(FinalizeBackupCmd.class);
            cmdList.add(CreateImageTransferCmd.class);
            cmdList.add(FinalizeImageTransferCmd.class);
            cmdList.add(ListImageTransfersCmd.class);
            cmdList.add(ListVmCheckpointsCmd.class);
            cmdList.add(DeleteVmCheckpointCmd.class);
        }
        return cmdList;
    }

    @Override
    public String getConfigComponentName() {
        return KVMBackupExportService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {ImageTransferIdleTimeoutSeconds, ExposeKVMBackupExportServiceApis};
    }

    private boolean isBackupableVmState(VirtualMachine.State state) {
        return VirtualMachine.State.Running.equals(state) || VirtualMachine.State.Stopped.equals(state);
    }

    private void failBackup(BackupVO backup) {
        backup.setStatus(Backup.Status.Error);
        backupDao.update(backup.getId(), backup);
    }

    private void updateBackupState(BackupVO backup, Backup.Status status) {
        backup.setStatus(status);
        backupDao.update(backup.getId(), backup);
    }

    private ImageTransferVO createDownloadImageTransfer(Long backupId, VolumeVO volume, ImageTransfer.Backend backend) {
        if (backupId == null) {
            throw new CloudRuntimeException("Backup ID is required for download image transfer");
        }
        if (ImageTransfer.Backend.file.equals(backend)) {
            throw new CloudRuntimeException("File backend is not supported for download");
        }

        BackupVO backup = backupDao.findById(backupId);
        if (backup == null) {
            throw new CloudRuntimeException("Backup not found: " + backupId);
        }

        String transferId = UUID.randomUUID().toString();
        String transferToken = generateTransferAuthToken();
        String socket = backup.getUuid();
        boolean nbdServerStarted = false;
        VMInstanceVO vm = vmInstanceDao.findById(backup.getVmId());
        try {
            if (vm != null && VirtualMachine.State.Stopped.equals(vm.getState())) {
                socket = transferId;
                VolumeInfo volumeInfo = volumeDataFactory.getVolume(volume.getId());
                startNBDServer(transferId, backup.getHostId(), volume.getUuid(), getVolumePathForFileBasedBackend(volume),
                        ImageTransfer.Direction.download, backup.getFromCheckpointId(), volumeInfo == null ? null : volumeInfo.getPassphrase());
                nbdServerStarted = true;
            }

            HostVO host = hostDao.findById(backup.getHostId());
            if (host == null) {
                throw new CloudRuntimeException("Host not found for backup: " + backupId);
            }

            CreateImageTransferCommand command = new CreateImageTransferCommand(transferId,
                    ImageTransfer.Direction.download.toString(), volume.getUuid(), socket,
                    backup.getFromCheckpointId(), ImageTransferIdleTimeoutSeconds.valueIn(host.getDataCenterId()), transferToken);
            CreateImageTransferAnswer answer = send(backup.getHostId(), command, CreateImageTransferAnswer.class);
            if (!answer.getResult()) {
                throw new CloudRuntimeException("Failed to create image transfer: " + answer.getDetails());
            }

            ImageTransferVO imageTransfer = new ImageTransferVO(transferId, backupId, volume.getId(), backup.getHostId(), socket,
                    ImageTransfer.Phase.transferring, ImageTransfer.Direction.download, backup.getAccountId(),
                    backup.getDomainId(), backup.getZoneId());
            imageTransfer.setTransferUrl(answer.getTransferUrl());
            imageTransfer.setSignedTicketId(transferToken);
            return imageTransferDao.persist(imageTransfer);
        } catch (RuntimeException e) {
            if (nbdServerStarted) {
                stopNBDServerBestEffort(transferId, backup.getHostId(), ImageTransfer.Direction.download);
            }
            throw e;
        }
    }

    private ImageTransferVO createUploadImageTransfer(VolumeVO volume, ImageTransfer.Backend backend) {
        StoragePoolVO storagePool = getStoragePool(volume);
        HostVO host = getHostFromStoragePool(storagePool);
        String transferId = UUID.randomUUID().toString();
        String transferToken = generateTransferAuthToken();
        String volumePath = getVolumePathForFileBasedBackend(volume);
        ImageTransferVO imageTransfer;
        CreateImageTransferCommand command;
        boolean nbdServerStarted = false;

        try {
            if (ImageTransfer.Backend.file.equals(backend)) {
                imageTransfer = new ImageTransferVO(transferId, volume.getId(), host.getId(), volumePath,
                        ImageTransfer.Phase.transferring, ImageTransfer.Direction.upload, volume.getAccountId(),
                        volume.getDomainId(), volume.getDataCenterId());
                command = new CreateImageTransferCommand(transferId, ImageTransfer.Direction.upload.toString(),
                        transferId, volumePath, ImageTransferIdleTimeoutSeconds.valueIn(host.getDataCenterId()), transferToken);
            } else {
                VolumeInfo volumeInfo = volumeDataFactory.getVolume(volume.getId());
                startNBDServer(transferId, host.getId(), volume.getUuid(), volumePath,
                        ImageTransfer.Direction.upload, null, volumeInfo == null ? null : volumeInfo.getPassphrase());
                nbdServerStarted = true;
                imageTransfer = new ImageTransferVO(transferId, null, volume.getId(), host.getId(), transferId,
                        ImageTransfer.Phase.transferring, ImageTransfer.Direction.upload, volume.getAccountId(),
                        volume.getDomainId(), volume.getDataCenterId());
                command = new CreateImageTransferCommand(transferId, ImageTransfer.Direction.upload.toString(),
                        volume.getUuid(), transferId, null, ImageTransferIdleTimeoutSeconds.valueIn(host.getDataCenterId()), transferToken);
            }

            CreateImageTransferAnswer answer = send(imageTransfer.getHostId(), command, CreateImageTransferAnswer.class);
            if (!answer.getResult()) {
                throw new CloudRuntimeException("Failed to create image transfer: " + answer.getDetails());
            }

            imageTransfer.setTransferUrl(answer.getTransferUrl());
            imageTransfer.setSignedTicketId(transferToken);
            return imageTransferDao.persist(imageTransfer);
        } catch (RuntimeException e) {
            if (nbdServerStarted) {
                stopNBDServerBestEffort(transferId, host.getId(), ImageTransfer.Direction.upload);
            }
            throw e;
        }
    }

    protected String generateTransferAuthToken() {
        byte[] tokenBytes = new byte[TRANSFER_TOKEN_BYTES];
        TRANSFER_TOKEN_RANDOM.nextBytes(tokenBytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(tokenBytes);
    }

    private void startNBDServer(String transferId, long hostId, String exportName, String volumePath,
                                ImageTransfer.Direction direction, String checkpointId, byte[] passphrase) {
        StartNBDServerCommand command = new StartNBDServerCommand(transferId, exportName, volumePath, transferId,
                direction.toString(), checkpointId, passphrase);
        StartNBDServerAnswer answer = send(hostId, command, StartNBDServerAnswer.class);
        if (!answer.getResult()) {
            throw new CloudRuntimeException("Failed to start the NBD server: " + answer.getDetails());
        }
    }

    private void stopNBDServerBestEffort(String transferId, long hostId, ImageTransfer.Direction direction) {
        try {
            Answer answer = send(hostId, new StopNBDServerCommand(transferId, direction.toString()), Answer.class);
            if (!answer.getResult()) {
                logger.warn("Failed to clean up NBD server for image transfer [{}]: {}", transferId, answer.getDetails());
            }
        } catch (RuntimeException e) {
            logger.warn("Failed to clean up NBD server for image transfer [{}].", transferId, e);
        }
    }

    private ImageTransfer.Backend getImageTransferBackend(ImageTransfer.Format format, ImageTransfer.Direction direction) {
        if (ImageTransfer.Format.cow.equals(format) && ImageTransfer.Direction.upload.equals(direction)) {
            return ImageTransfer.Backend.file;
        }
        return ImageTransfer.Backend.nbd;
    }

    private StoragePoolVO getStoragePool(Volume volume) {
        if (volume.getPoolId() == null) {
            throw new CloudRuntimeException("Storage pool cannot be determined for volume: " + volume.getUuid());
        }
        StoragePoolVO storagePool = primaryDataStoreDao.findById(volume.getPoolId());
        if (storagePool == null) {
            throw new CloudRuntimeException("Storage pool cannot be determined for volume: " + volume.getUuid());
        }
        return storagePool;
    }

    private String getVolumePathForFileBasedBackend(Volume volume) {
        StoragePoolVO storagePool = getStoragePool(volume);
        return getVolumePathPrefix(storagePool) + "/" + volume.getPath();
    }

    private String getVolumePathPrefix(StoragePoolVO storagePool) {
        if (ScopeType.HOST.equals(storagePool.getScope())) {
            return storagePool.getPath();
        }
        Storage.StoragePoolType poolType = storagePool.getPoolType();
        if (Storage.StoragePoolType.NetworkFilesystem.equals(poolType)) {
            return String.format("/mnt/%s", storagePool.getUuid());
        }
        if (Storage.StoragePoolType.SharedMountPoint.equals(poolType)) {
            return storagePool.getPath();
        }
        throw new CloudRuntimeException("Unsupported storage pool type for file based image transfer: " + poolType);
    }

    private HostVO getHostFromStoragePool(StoragePoolVO storagePool) {
        List<HostVO> hosts;
        if (ScopeType.CLUSTER.equals(storagePool.getScope())) {
            hosts = hostDao.findByClusterId(storagePool.getClusterId());
        } else if (ScopeType.ZONE.equals(storagePool.getScope())) {
            hosts = hostDao.findByDataCenterId(storagePool.getDataCenterId());
        } else if (ScopeType.HOST.equals(storagePool.getScope())) {
            List<StoragePoolHostVO> poolHosts = storagePoolHostDao.listByPoolId(storagePool.getId());
            if (CollectionUtils.isEmpty(poolHosts)) {
                throw new CloudRuntimeException("No host found for storage pool: " + storagePool.getUuid());
            }
            return hostDao.findById(poolHosts.get(0).getHostId());
        } else {
            throw new CloudRuntimeException("Unsupported storage pool scope: " + storagePool.getScope());
        }

        if (CollectionUtils.isEmpty(hosts)) {
            throw new CloudRuntimeException("No host found for storage pool: " + storagePool.getUuid());
        }
        Collections.shuffle(hosts);
        return hosts.get(0);
    }

    private void rotateVmCheckpointDetails(long vmId, BackupVO backup) {
        Map<String, String> details = vmInstanceDetailsDao.listDetailsKeyPairs(vmId);
        String activeCheckpointId = details.get(VmDetailConstants.ACTIVE_CHECKPOINT_ID);
        String activeCheckpointCreateTime = details.get(VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME);
        if (StringUtils.isNotBlank(activeCheckpointId)) {
            vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.LAST_CHECKPOINT_ID, activeCheckpointId, false);
            if (activeCheckpointCreateTime != null) {
                vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.LAST_CHECKPOINT_CREATE_TIME, activeCheckpointCreateTime, false);
            }
        }
        if (StringUtils.isNotBlank(backup.getToCheckpointId())) {
            vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.ACTIVE_CHECKPOINT_ID, backup.getToCheckpointId(), false);
            if (backup.getCheckpointCreateTime() != null) {
                vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME,
                        String.valueOf(backup.getCheckpointCreateTime()), false);
            }
        }
    }

    private DeleteVmCheckpointCommand createDeleteCheckpointCommand(VMInstanceVO vm, String checkpointId) {
        boolean stoppedVm = VirtualMachine.State.Stopped.equals(vm.getState());
        Map<String, String> diskPathUuidMap = new HashMap<>();
        Map<String, byte[]> diskPathPassphraseMap = new HashMap<>();
        if (stoppedVm) {
            for (VolumeVO volume : volumeDao.findByInstance(vm.getId())) {
                String volumePath = getVolumePathForFileBasedBackend(volume);
                diskPathUuidMap.put(volumePath, volume.getUuid());
                VolumeInfo volumeInfo = volumeDataFactory.getVolume(volume.getId());
                if (volumeInfo != null) {
                    diskPathPassphraseMap.put(volumePath, volumeInfo.getPassphrase());
                }
            }
        }
        return new DeleteVmCheckpointCommand(vm.getInstanceName(), checkpointId, diskPathUuidMap,
                diskPathPassphraseMap, stoppedVm);
    }

    private Long getHostIdForCheckpointCommand(VMInstanceVO vm) {
        Long hostId = vm.getHostId() == null ? vm.getLastHostId() : vm.getHostId();
        if (hostId == null) {
            throw new CloudRuntimeException("Host cannot be determined for Instance: " + vm.getUuid());
        }
        return hostId;
    }

    private void promoteLastCheckpointAfterActiveDelete(long vmId, Map<String, String> detailsBeforeDelete) {
        String lastCheckpointId = detailsBeforeDelete.get(VmDetailConstants.LAST_CHECKPOINT_ID);
        String lastCheckpointCreateTime = detailsBeforeDelete.get(VmDetailConstants.LAST_CHECKPOINT_CREATE_TIME);
        if (StringUtils.isNotBlank(lastCheckpointId)) {
            vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.ACTIVE_CHECKPOINT_ID, lastCheckpointId, false);
            vmInstanceDetailsDao.addDetail(vmId, VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME, lastCheckpointCreateTime, false);
            vmInstanceDetailsDao.removeDetail(vmId, VmDetailConstants.LAST_CHECKPOINT_ID);
            vmInstanceDetailsDao.removeDetail(vmId, VmDetailConstants.LAST_CHECKPOINT_CREATE_TIME);
        } else {
            vmInstanceDetailsDao.removeDetail(vmId, VmDetailConstants.ACTIVE_CHECKPOINT_ID);
            vmInstanceDetailsDao.removeDetail(vmId, VmDetailConstants.ACTIVE_CHECKPOINT_CREATE_TIME);
        }
    }

    private void addCheckpointResponse(List<CheckpointResponse> responses, String checkpointId, String createTime, boolean active) {
        if (StringUtils.isBlank(checkpointId)) {
            return;
        }
        CheckpointResponse response = new CheckpointResponse();
        response.setObjectName("checkpoint");
        response.setId(checkpointId);
        response.setCreated(Date.from(Instant.ofEpochSecond(parseOptionalLong(createTime) == null ? 0L : parseOptionalLong(createTime))));
        response.setIsActive(active);
        responses.add(response);
    }

    private ImageTransferResponse toImageTransferResponse(ImageTransferVO imageTransfer) {
        ImageTransferResponse response = new ImageTransferResponse();
        response.setId(imageTransfer.getUuid());
        if (imageTransfer.getBackupId() != null) {
            BackupVO backup = backupDao.findByIdIncludingRemoved(imageTransfer.getBackupId());
            if (backup != null) {
                response.setBackupId(backup.getUuid());
            }
        }
        VolumeVO volume = volumeDao.findByIdIncludingRemoved(imageTransfer.getVolumeId());
        if (volume != null) {
            response.setDiskId(volume.getUuid());
        }
        response.setTransferUrl(imageTransfer.getTransferUrl());
        response.setPhase(imageTransfer.getPhase().toString());
        response.setDirection(imageTransfer.getDirection().toString());
        response.setCreated(imageTransfer.getCreated());
        return response;
    }

    private Long parseOptionalLong(String value) {
        return value == null ? null : NumbersUtil.parseLong(value, 0L);
    }

    private <T extends Answer> T send(long hostId, Command command, Class<T> answerType) {
        Answer answer;
        try {
            answer = agentManager.send(hostId, command);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            throw new CloudRuntimeException("Failed to communicate with agent", e);
        }
        if (!answerType.isInstance(answer)) {
            throw new CloudRuntimeException("Unexpected answer type from agent: " + (answer == null ? "null" : answer.getClass().getName()));
        }
        return answerType.cast(answer);
    }
}
