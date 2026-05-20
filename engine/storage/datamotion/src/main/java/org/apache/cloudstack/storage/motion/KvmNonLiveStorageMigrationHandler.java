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

import java.util.HashMap;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.CopyCommandResult;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.ChapInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.Scope;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.framework.async.AsyncCompletionCallback;
import org.apache.cloudstack.storage.command.CopyCmdAnswer;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.storage.MigrateVolumeAnswer;
import com.cloud.agent.api.storage.MigrateVolumeCommand;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.StorageManager;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.VolumeDetailVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.dao.VolumeDetailsDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;

@Component
public class KvmNonLiveStorageMigrationHandler {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected AgentManager agentManager;
    @Inject
    private DataStoreManager dataStoreMgr;
    @Inject
    private HostDao hostDao;
    @Inject
    private PrimaryDataStoreDao storagePoolDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private VolumeDetailsDao volumeDetailsDao;
    @Inject
    private VolumeDataFactory volumeDataFactory;
    @Inject
    private VolumeService volumeService;
    @Inject
    protected HostResolutionService hostResolutionService;

    public void handleVolumeMigrationForKVM(VolumeInfo srcVolumeInfo, VolumeInfo destVolumeInfo, AsyncCompletionCallback<CopyCommandResult> callback) {
        VirtualMachine vm = srcVolumeInfo.getAttachedVM();

        checkAvailableForMigration(vm);

        String errMsg = null;
        HostVO hostVO = null;
        try {
            destVolumeInfo.getDataStore().getDriver().createAsync(destVolumeInfo.getDataStore(), destVolumeInfo, null);
            VolumeVO volumeVO = volumeDao.findById(destVolumeInfo.getId());
            updatePathFromScsiName(volumeVO);
            destVolumeInfo = volumeDataFactory.getVolume(destVolumeInfo.getId(), destVolumeInfo.getDataStore());
            hostVO = getHostOnWhichToExecuteMigrationCommand(srcVolumeInfo, destVolumeInfo);

            PrimaryDataStore pds = (PrimaryDataStore)dataStoreMgr.getPrimaryDataStore(destVolumeInfo.getDataStore().getUuid());
            if (pds == null) {
                throw new CloudRuntimeException("Unable to find primary data store driver for this volume");
            }

            volumeService.grantAccess(destVolumeInfo, hostVO, destVolumeInfo.getDataStore());

            destVolumeInfo = volumeDataFactory.getVolume(destVolumeInfo.getId(), destVolumeInfo.getDataStore());

            String path = migrateVolumeForKVM(srcVolumeInfo, destVolumeInfo, hostVO, "Unable to migrate the volume from non-managed storage to managed storage");

            updateVolumePath(destVolumeInfo.getId(), path);
            volumeVO = volumeDao.findById(destVolumeInfo.getId());
            if (volumeVO.getFormat() == null) {
                volumeVO.setFormat(ImageFormat.QCOW2);
                volumeDao.update(volumeVO.getId(), volumeVO);
            }
        } catch (Exception ex) {
            errMsg = "Primary storage migration failed due to an unexpected error: " +
                    ex.getMessage();
            if (ex instanceof CloudRuntimeException) {
                throw ex;
            } else {
                throw new CloudRuntimeException(errMsg, ex);
            }
        } finally {
            if (hostVO != null) {
                try {
                    volumeService.revokeAccess(destVolumeInfo, hostVO, destVolumeInfo.getDataStore());
                } catch (Exception e) {
                    logger.warn(String.format("Failed to revoke access for volume 'name=%s,uuid=%s' after a migration attempt", destVolumeInfo.getVolume(), destVolumeInfo.getUuid()), e);
                }
            }

            destVolumeInfo = volumeDataFactory.getVolume(destVolumeInfo.getId(), destVolumeInfo.getDataStore());

            CopyCmdAnswer copyCmdAnswer;
            if (errMsg != null) {
                copyCmdAnswer = new CopyCmdAnswer(errMsg);
            }
            else {
                destVolumeInfo = volumeDataFactory.getVolume(destVolumeInfo.getId(), destVolumeInfo.getDataStore());
                DataTO dataTO = destVolumeInfo.getTO();
                copyCmdAnswer = new CopyCmdAnswer(dataTO);
            }

            CopyCommandResult result = new CopyCommandResult(null, copyCmdAnswer);
            result.setResult(errMsg);
            callback.complete(result);
        }
    }

    public void checkAvailableForMigration(VirtualMachine vm) {
        if (vm != null && (vm.getState() != VirtualMachine.State.Stopped && vm.getState() != VirtualMachine.State.Migrating)) {
            throw new CloudRuntimeException("Currently, if a volume to migrate from non-managed storage to managed storage on KVM is attached to " +
                    "a VM, the VM must be in the Stopped or Migrating state.");
        }
    }

    protected void updatePathFromScsiName(VolumeVO volumeVO) {
        if (volumeVO.get_iScsiName() != null) {
            volumeVO.setPath(volumeVO.get_iScsiName());
            volumeDao.update(volumeVO.getId(), volumeVO);
        }
    }

    protected HostVO getHostOnWhichToExecuteMigrationCommand(VolumeInfo srcVolumeInfo, VolumeInfo destVolumeInfo) {
        long srcStoragePoolId = srcVolumeInfo.getPoolId();
        StoragePoolVO srcStoragePoolVO = storagePoolDao.findById(srcStoragePoolId);

        HostVO hostVO;

        Scope srcScope = srcVolumeInfo.getDataStore().getScope();
        Scope destScope = destVolumeInfo.getDataStore().getScope();
        if (ScopeType.HOST.equals(srcScope.getScopeType())) {
            hostVO = hostDao.findById(srcScope.getScopeId());
        } else if (ScopeType.HOST.equals(destScope.getScopeType())) {
            hostVO = hostDao.findById(destScope.getScopeId());
        } else {
            if (srcStoragePoolVO.getClusterId() != null) {
                hostVO = hostResolutionService.getHostInCluster(srcStoragePoolVO);
            } else {
                hostVO = hostResolutionService.getHost(destVolumeInfo, HypervisorType.KVM, false);
            }
        }

        return hostVO;
    }

    public String migrateVolumeForKVM(VolumeInfo srcVolumeInfo, VolumeInfo destVolumeInfo, HostVO hostVO, String errMsg) {
        try {
            Map<String, String> srcDetails = getVolumeDetails(srcVolumeInfo);
            Map<String, String> destDetails = getVolumeDetails(destVolumeInfo);

            volumeService.grantAccess(srcVolumeInfo, hostVO, srcVolumeInfo.getDataStore());

            MigrateVolumeCommand migrateVolumeCommand = new MigrateVolumeCommand(srcVolumeInfo.getTO(), destVolumeInfo.getTO(),
                    srcDetails, destDetails, StorageManager.KvmStorageOfflineMigrationWait.value());

            volumeService.grantAccess(srcVolumeInfo, hostVO, srcVolumeInfo.getDataStore());
            handleQualityOfServiceForVolumeMigration(destVolumeInfo, PrimaryDataStoreDriver.QualityOfServiceState.MIGRATION);
            volumeService.grantAccess(destVolumeInfo, hostVO, destVolumeInfo.getDataStore());

            MigrateVolumeAnswer migrateVolumeAnswer = (MigrateVolumeAnswer)agentManager.send(hostVO.getId(), migrateVolumeCommand);
            if (migrateVolumeAnswer == null || !migrateVolumeAnswer.getResult()) {
                if (migrateVolumeAnswer != null && StringUtils.isNotEmpty(migrateVolumeAnswer.getDetails())) {
                    throw new CloudRuntimeException(migrateVolumeAnswer.getDetails());
                }
                else {
                    throw new CloudRuntimeException(errMsg);
                }
            }
            return migrateVolumeAnswer.getVolumePath();
        } catch (CloudRuntimeException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new CloudRuntimeException("Unexpected error during volume migration: " + ex.getMessage(), ex);
        } finally {
            try {
                volumeService.revokeAccess(srcVolumeInfo, hostVO, srcVolumeInfo.getDataStore());
                volumeService.revokeAccess(destVolumeInfo, hostVO, destVolumeInfo.getDataStore());
                handleQualityOfServiceForVolumeMigration(destVolumeInfo, PrimaryDataStoreDriver.QualityOfServiceState.NO_MIGRATION);
            } catch (Throwable e) {
                logger.warn("During cleanup post-migration and exception occured: " + e);
                if (logger.isDebugEnabled()) {
                    logger.debug("Exception during post-migration cleanup.", e);
                }
            }
        }
    }

    protected void handleQualityOfServiceForVolumeMigration(VolumeInfo volumeInfo, PrimaryDataStoreDriver.QualityOfServiceState qualityOfServiceState) {
        try {
            ((PrimaryDataStoreDriver)volumeInfo.getDataStore().getDriver()).handleQualityOfServiceForVolumeMigration(volumeInfo, qualityOfServiceState);
        }
        catch (Exception ex) {
            logger.warn(ex);
        }
    }

    protected Map<String, String> getVolumeDetails(VolumeInfo volumeInfo) {
        long storagePoolId = volumeInfo.getPoolId();
        StoragePoolVO storagePoolVO = storagePoolDao.findById(storagePoolId);

        if (!storagePoolVO.isManaged()) {
            return null;
        }

        Map<String, String> volumeDetails = new HashMap<>();

        VolumeVO volumeVO = volumeDao.findById(volumeInfo.getId());

        volumeDetails.put(DiskTO.STORAGE_HOST, storagePoolVO.getHostAddress());
        volumeDetails.put(DiskTO.STORAGE_PORT, String.valueOf(storagePoolVO.getPort()));
        volumeDetails.put(DiskTO.IQN, volumeVO.get_iScsiName());
        volumeDetails.put(DiskTO.PROTOCOL_TYPE, (volumeVO.getPoolType() != null) ? volumeVO.getPoolType().toString() : null);
        volumeDetails.put(StorageManager.STORAGE_POOL_DISK_WAIT.toString(), String.valueOf(StorageManager.STORAGE_POOL_DISK_WAIT.valueIn(storagePoolVO.getId())));
        volumeDetails.put(DiskTO.VOLUME_SIZE, String.valueOf(volumeVO.getSize()));
        volumeDetails.put(DiskTO.SCSI_NAA_DEVICE_ID, getVolumeProperty(volumeInfo.getId(), DiskTO.SCSI_NAA_DEVICE_ID));

        ChapInfo chapInfo = volumeService.getChapInfo(volumeInfo, volumeInfo.getDataStore());

        if (chapInfo != null) {
            volumeDetails.put(DiskTO.CHAP_INITIATOR_USERNAME, chapInfo.getInitiatorUsername());
            volumeDetails.put(DiskTO.CHAP_INITIATOR_SECRET, chapInfo.getInitiatorSecret());
            volumeDetails.put(DiskTO.CHAP_TARGET_USERNAME, chapInfo.getTargetUsername());
            volumeDetails.put(DiskTO.CHAP_TARGET_SECRET, chapInfo.getTargetSecret());
        }

        return volumeDetails;
    }

    protected String getVolumeProperty(long volumeId, String property) {
        VolumeDetailVO volumeDetails = volumeDetailsDao.findDetail(volumeId, property);

        if (volumeDetails != null) {
            return volumeDetails.getValue();
        }

        return null;
    }

    public void updateVolumePath(long volumeId, String path) {
        VolumeVO volumeVO = volumeDao.findById(volumeId);

        volumeVO.setPath(path);

        volumeDao.update(volumeId, volumeVO);
    }
}
