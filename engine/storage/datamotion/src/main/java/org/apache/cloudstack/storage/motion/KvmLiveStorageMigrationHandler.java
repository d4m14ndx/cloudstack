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
package org.apache.cloudstack.storage.motion;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.Event;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckVirtualMachineAnswer;
import com.cloud.agent.api.CheckVirtualMachineCommand;
import com.cloud.agent.api.MigrateAnswer;
import com.cloud.agent.api.MigrateCommand;
import com.cloud.agent.api.MigrateCommand.MigrateDiskInfo;
import com.cloud.agent.api.PrepareForMigrationAnswer;
import com.cloud.agent.api.PrepareForMigrationCommand;
import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.MigrationOptions;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StorageManager;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class KvmLiveStorageMigrationHandler {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected AgentManager agentManager;
    @Inject
    private GuestOSCategoryDao guestOsCategoryDao;
    @Inject
    private GuestOSDao guestOsDao;
    @Inject
    protected PrimaryDataStoreDao storagePoolDao;
    @Inject
    private VMInstanceDao vmDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private VolumeDataFactory volumeDataFactory;
    @Inject
    private VolumeService volumeService;

    public void handle(Map<VolumeInfo, DataStore> volumeDataStoreMap, VirtualMachineTO vmTO, Host srcHost, Host destHost,
            AsyncCompletionCallback<CopyCommandResult> callback, StorageSystemDataMotionStrategy context) {
        String errMsg = null;
        boolean success = false;
        Map<VolumeInfo, VolumeInfo> srcVolumeInfoToDestVolumeInfo = new HashMap<>();

        try {
            if (srcHost.getHypervisorType() != HypervisorType.KVM) {
                throw new CloudRuntimeException("Invalid hypervisor type (only KVM supported for this operation at the time being)");
            }

            verifyLiveMigrationForKVM(volumeDataStoreMap);

            VMInstanceVO vmInstance = vmDao.findById(vmTO.getId());
            vmTO.setState(vmInstance.getState());
            List<MigrateDiskInfo> migrateDiskInfoList = new ArrayList<>();

            Map<String, MigrateCommand.MigrateDiskInfo> migrateStorage = new HashMap<>();

            boolean managedStorageDestination = false;
            boolean migrateNonSharedInc = false;
            for (Map.Entry<VolumeInfo, DataStore> entry : volumeDataStoreMap.entrySet()) {
                VolumeInfo srcVolumeInfo = entry.getKey();
                DataStore destDataStore = entry.getValue();

                VolumeVO srcVolume = volumeDao.findById(srcVolumeInfo.getId());
                StoragePoolVO destStoragePool = storagePoolDao.findById(destDataStore.getId());
                StoragePoolVO sourceStoragePool = storagePoolDao.findById(srcVolumeInfo.getPoolId());

                if (sourceStoragePool.getId() == destStoragePool.getId() && sourceStoragePool.getPoolType() == Storage.StoragePoolType.PowerFlex) {
                    continue;
                }

                if (!context.shouldMigrateVolume(sourceStoragePool, destHost, destStoragePool)) {
                    continue;
                }

                MigrationOptions.Type migrationType = context.decideMigrationTypeAndCopyTemplateIfNeeded(destHost, vmInstance, srcVolumeInfo,
                        sourceStoragePool, destStoragePool, destDataStore);
                migrateNonSharedInc = migrateNonSharedInc || MigrationOptions.Type.LinkedClone.equals(migrationType);

                VolumeVO destVolume = context.duplicateVolumeOnAnotherStorage(srcVolume, destStoragePool);
                VolumeInfo destVolumeInfo = volumeDataFactory.getVolume(destVolume.getId(), destDataStore);

                destVolumeInfo.processEvent(Event.MigrationCopyRequested);
                destVolumeInfo.processEvent(Event.MigrationCopySucceeded);
                destVolumeInfo.processEvent(Event.MigrationRequested);

                context.setVolumeMigrationOptions(srcVolumeInfo, destVolumeInfo, vmTO, srcHost, destStoragePool, migrationType);

                destDataStore.getDriver().createAsync(destDataStore, destVolumeInfo, null);

                managedStorageDestination = destStoragePool.isManaged();
                String volumeIdentifier = managedStorageDestination ? destVolumeInfo.get_iScsiName() : destVolumeInfo.getUuid();

                destVolume = volumeDao.findById(destVolume.getId());
                destVolume.setPath(volumeIdentifier);

                context.setVolumePath(destVolume);

                volumeDao.update(destVolume.getId(), destVolume);

                context.postVolumeCreationActions(srcVolumeInfo, destVolumeInfo);

                destVolumeInfo = volumeDataFactory.getVolume(destVolume.getId(), destDataStore);

                context.handleQualityOfServiceForVolumeMigration(destVolumeInfo, PrimaryDataStoreDriver.QualityOfServiceState.MIGRATION);

                volumeService.grantAccess(destVolumeInfo, destHost, destDataStore);

                String destPath = context.generateDestPath(destHost, destStoragePool, destVolumeInfo);

                MigrateCommand.MigrateDiskInfo migrateDiskInfo;

                boolean isNonManagedToNfs = context.supportStoragePoolType(sourceStoragePool.getPoolType(), StoragePoolType.Filesystem) &&
                        destStoragePool.getPoolType() == StoragePoolType.NetworkFilesystem && !managedStorageDestination;
                if (isNonManagedToNfs) {
                    migrateDiskInfo = new MigrateCommand.MigrateDiskInfo(srcVolumeInfo.getPath(),
                            MigrateCommand.MigrateDiskInfo.DiskType.FILE,
                            MigrateCommand.MigrateDiskInfo.DriverType.QCOW2,
                            MigrateCommand.MigrateDiskInfo.Source.FILE,
                            context.connectHostToVolume(destHost, destVolumeInfo.getPoolId(), volumeIdentifier));
                } else {
                    String backingPath = context.generateBackingPath(destStoragePool, destVolumeInfo);
                    migrateDiskInfo = context.configureMigrateDiskInfo(srcVolumeInfo, destPath, backingPath);
                    migrateDiskInfo.setSourceDiskOnStorageFileSystem(context.isStoragePoolTypeOfFile(sourceStoragePool));
                    migrateDiskInfoList.add(migrateDiskInfo);
                }
                context.prepareDiskWithSecretConsumerDetail(vmTO, srcVolumeInfo, destVolumeInfo.getPath());

                migrateStorage.put(srcVolumeInfo.getPath(), migrateDiskInfo);

                srcVolumeInfoToDestVolumeInfo.put(srcVolumeInfo, destVolumeInfo);
            }

            PrepareForMigrationCommand pfmc = new PrepareForMigrationCommand(vmTO);
            Answer pfma;

            try {
                pfma = agentManager.send(destHost.getId(), pfmc);

                if (pfma == null || !pfma.getResult()) {
                    String details = pfma != null ? pfma.getDetails() : "null answer returned";
                    String msg = "Unable to prepare for migration due to the following: " + details;

                    throw new AgentUnavailableException(msg, destHost.getId());
                }
            } catch (final OperationTimedoutException e) {
                throw new AgentUnavailableException("Operation timed out", destHost.getId());
            }

            VMInstanceVO vm = vmDao.findById(vmTO.getId());
            boolean isWindows = guestOsCategoryDao.findById(guestOsDao.findById(vm.getGuestOSId()).getCategoryId()).getName().equalsIgnoreCase("Windows");

            MigrateCommand migrateCommand = new MigrateCommand(vmTO.getName(), destHost.getPrivateIpAddress(), isWindows, vmTO, true);
            migrateCommand.setWait(StorageManager.KvmStorageOnlineMigrationWait.value());
            migrateCommand.setMigrateStorage(migrateStorage);
            migrateCommand.setMigrateDiskInfoList(migrateDiskInfoList);
            migrateCommand.setMigrateStorageManaged(managedStorageDestination);
            migrateCommand.setMigrateNonSharedInc(migrateNonSharedInc);

            Integer newVmCpuShares = ((PrepareForMigrationAnswer) pfma).getNewVmCpuShares();
            if (newVmCpuShares != null) {
                logger.debug(String.format("Setting CPU shares to [%d] as part of migrate VM with volumes command for VM [%s].", newVmCpuShares, vmTO));
                migrateCommand.setNewVmCpuShares(newVmCpuShares);
            }

            boolean kvmAutoConvergence = StorageManager.KvmAutoConvergence.value();
            migrateCommand.setAutoConvergence(kvmAutoConvergence);

            MigrateAnswer migrateAnswer = null;
            try {
                migrateAnswer = (MigrateAnswer)agentManager.send(srcHost.getId(), migrateCommand);
                success = migrateAnswer != null && migrateAnswer.getResult();
            } catch (OperationTimedoutException ex) {
                if (HypervisorType.KVM.equals(vm.getHypervisorType())) {
                    final Answer answer = agentManager.send(destHost.getId(), new CheckVirtualMachineCommand(vm.getInstanceName()));
                    if (answer != null && answer.getResult() && answer instanceof CheckVirtualMachineAnswer) {
                        final CheckVirtualMachineAnswer vmAnswer = (CheckVirtualMachineAnswer)answer;
                        if (VirtualMachine.PowerState.PowerOn.equals(vmAnswer.getState())) {
                            logger.info(String.format("Vm %s is found on destination host %s. Migration is successful", vm, destHost));
                            success = true;
                        }
                    }
                }
                if (!success) {
                    throw ex;
                }
            }

            context.handlePostMigration(success, srcVolumeInfoToDestVolumeInfo, vmTO, destHost);

            if (!success) {
                if (migrateAnswer == null) {
                    throw new CloudRuntimeException("Unable to get an answer to the migrate command");
                }

                if (!migrateAnswer.getResult()) {
                    errMsg = migrateAnswer.getDetails();

                    throw new CloudRuntimeException(errMsg);
                }
            }
        } catch (AgentUnavailableException | OperationTimedoutException | CloudRuntimeException ex) {
            String volumesAndStorages = volumeDataStoreMap.entrySet().stream()
                    .map(entry -> formatEntryOfVolumesAndStoragesAsJsonToDisplayOnLog(entry)).collect(Collectors.joining(","));

            errMsg = String.format("Copy volume(s) to storage(s) [%s] and VM to host [%s] failed in StorageSystemDataMotionStrategy.copyAsync. Error message: [%s].",
                    volumesAndStorages, formatMigrationElementsAsJsonToDisplayOnLog("vm", vmTO.getId(), srcHost.getId(), destHost.getId()), ex.getMessage());
            logger.error(errMsg, ex);

            throw new CloudRuntimeException(errMsg);
        } finally {
            if (!success && !srcVolumeInfoToDestVolumeInfo.isEmpty()) {
                for (VolumeInfo destVolumeInfo : srcVolumeInfoToDestVolumeInfo.values()) {
                    logger.info(String.format("Expunging dest volume [id: %s, state: %s] as part of failed VM migration with volumes command for VM [%s].",
                            destVolumeInfo.getId(), destVolumeInfo.getState(), vmTO.getId()));
                    destVolumeInfo.processEvent(Event.OperationFailed);
                    destVolumeInfo.processEvent(Event.DestroyRequested);
                    volumeService.expungeVolumeAsync(destVolumeInfo);
                }
            }

            CopyCmdAnswer copyCmdAnswer = new CopyCmdAnswer(errMsg);
            CopyCommandResult result = new CopyCommandResult(null, copyCmdAnswer);
            result.setResult(errMsg);
            callback.complete(result);
        }
    }

    protected void verifyLiveMigrationForKVM(Map<VolumeInfo, DataStore> volumeDataStoreMap) {
        Boolean storageTypeConsistency = null;
        for (Map.Entry<VolumeInfo, DataStore> entry : volumeDataStoreMap.entrySet()) {
            VolumeInfo volumeInfo = entry.getKey();

            Long storagePoolId = volumeInfo.getPoolId();
            StoragePoolVO srcStoragePoolVO = storagePoolDao.findById(storagePoolId);

            if (srcStoragePoolVO == null) {
                throw new CloudRuntimeException("Volume with ID " + volumeInfo.getId() + " is not associated with a storage pool.");
            }

            DataStore dataStore = entry.getValue();
            StoragePoolVO destStoragePoolVO = storagePoolDao.findById(dataStore.getId());

            if (destStoragePoolVO == null) {
                throw new CloudRuntimeException("Destination storage pool with ID " + dataStore.getId() + " was not located.");
            }

            if (srcStoragePoolVO.isManaged() && srcStoragePoolVO.getId() != destStoragePoolVO.getId()) {
                throw new CloudRuntimeException("Migrating a volume online with KVM from managed storage is not currently supported.");
            }

            if (storageTypeConsistency == null) {
                storageTypeConsistency = destStoragePoolVO.isManaged();
            } else if (storageTypeConsistency != destStoragePoolVO.isManaged()) {
                throw new CloudRuntimeException("Destination storage pools must be either all managed or all not managed");
            }
        }
    }

    protected String formatMigrationElementsAsJsonToDisplayOnLog(String objectName, Object object, Object from, Object to) {
        return String.format("{%s: \"%s\", from: \"%s\", to:\"%s\"}", objectName, object, from, to);
    }

    protected String formatEntryOfVolumesAndStoragesAsJsonToDisplayOnLog(Map.Entry<VolumeInfo, DataStore> entry) {
        VolumeInfo srcVolumeInfo = entry.getKey();
        DataStore destDataStore = entry.getValue();
        return formatMigrationElementsAsJsonToDisplayOnLog("volume", srcVolumeInfo.getId(), srcVolumeInfo.getPoolId(), destDataStore.getId());
    }
}
