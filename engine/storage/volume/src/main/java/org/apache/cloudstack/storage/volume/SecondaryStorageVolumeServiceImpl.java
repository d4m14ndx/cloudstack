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
package org.apache.cloudstack.storage.volume;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.function.BiFunction;

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine.Event;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.command.MoveVolumeCommand;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO;
import org.apache.cloudstack.storage.to.VolumeObjectTO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.storage.ListVolumeAnswer;
import com.cloud.agent.api.storage.ListVolumeCommand;
import com.cloud.alert.AlertManager;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.RegisterVolumePayload;
import com.cloud.storage.ScopeType;
import com.cloud.storage.VMTemplateStorageResourceAssoc;
import com.cloud.storage.VMTemplateStorageResourceAssoc.Status;
import com.cloud.storage.Volume;
import com.cloud.storage.Volume.State;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.template.TemplateConstants;
import com.cloud.storage.template.TemplateProp;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Image-store side of the volume data plane: reconcile volume_store_ref
 * with what's actually on the image store, and move volume files between
 * account folders.
 *
 * <p>Extracted from {@link VolumeServiceImpl} as a cohesive engine-side
 * slice covering operations that touch secondary storage rather than
 * primary pools.</p>
 *
 * @see SecondaryStorageVolumeService
 */
@Component
public class SecondaryStorageVolumeServiceImpl implements SecondaryStorageVolumeService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected VolumeDataStoreDao volumeStoreDao;
    @Inject
    protected VolumeDao volDao;
    @Inject
    protected VolumeDataFactory volFactory;
    @Inject
    protected EndPointSelector epSelector;
    @Inject
    protected ResourceLimitService resourceLimitMgr;
    @Inject
    protected AccountManager accountMgr;
    @Inject
    protected AlertManager alertMgr;

    @Override
    public void handleVolumeSync(DataStore store,
            BiFunction<VolumeInfo, DataStore, AsyncCallFuture<VolumeApiResult>> downloader) {
        if (store == null) {
            logger.warn("Huh? image store is null");
            return;
        }
        long storeId = store.getId();

        // add lock to make template sync for a data store only be done once
        String lockString = "volumesync.storeId:" + storeId;
        GlobalLock syncLock = GlobalLock.getInternLock(lockString);
        try {
            if (syncLock.lock(3)) {
                try {
                    Map<Long, TemplateProp> volumeInfos = listVolume(store);
                    if (volumeInfos == null) {
                        return;
                    }

                    // find all the db volumes including those with NULL url column to avoid accidentally deleting volumes on image store later.
                    List<VolumeDataStoreVO> dbVolumes = volumeStoreDao.listByStoreId(storeId);
                    List<VolumeDataStoreVO> toBeDownloaded = new ArrayList<>(dbVolumes);
                    for (VolumeDataStoreVO volumeStore : dbVolumes) {
                        VolumeVO volume = volDao.findById(volumeStore.getVolumeId());
                        if (volume == null) {
                            logger.warn("Volume_store_ref table shows that volume {} is " +
                                    "on image store {}, but the volume is not found in volumes " +
                                    "table, potentially some bugs in deleteVolume, so we just " +
                                    "treat this volume to be deleted and mark it as destroyed",
                                    volumeStore.getVolumeId(), store);
                            volumeStore.setDestroyed(true);
                            volumeStoreDao.update(volumeStore.getId(), volumeStore);
                            continue;
                        }
                        // Exists then don't download
                        if (volumeInfos.containsKey(volume.getId())) {
                            TemplateProp volInfo = volumeInfos.remove(volume.getId());
                            toBeDownloaded.remove(volumeStore);
                            logger.info("Volume Sync found {} already in the volume image store table", volume);
                            if (volumeStore.getDownloadState() != Status.DOWNLOADED) {
                                volumeStore.setErrorString("");
                            }
                            if (volInfo.isCorrupted()) {
                                volumeStore.setDownloadState(Status.DOWNLOAD_ERROR);
                                String msg = String.format("Volume %s is corrupted on image store", volume);
                                volumeStore.setErrorString(msg);
                                logger.info(msg);
                                if (volume.getState() == State.NotUploaded || volume.getState() == State.UploadInProgress) {
                                    logger.info("Volume Sync found {} uploaded using SSVM on image store {} as corrupted, marking it as failed", volume, store);
                                    volumeStoreDao.update(volumeStore.getId(), volumeStore);
                                    // mark volume as failed, so that storage GC will clean it up
                                    VolumeObject volObj = (VolumeObject)volFactory.getVolume(volume.getId());
                                    volObj.processEvent(Event.OperationFailed);
                                } else if (volumeStore.getDownloadUrl() == null) {
                                    msg = String.format("Volume (%s) with install path %s is corrupted, please check in image store: %s", volume, volInfo.getInstallPath(), store);
                                    logger.warn(msg);
                                } else {
                                    logger.info("Removing volume_store_ref entry for corrupted volume {}", volume);
                                    volumeStoreDao.remove(volumeStore.getId());
                                    toBeDownloaded.add(volumeStore);
                                }
                            } else { // Put them in right status
                                volumeStore.setDownloadPercent(100);
                                volumeStore.setDownloadState(Status.DOWNLOADED);
                                volumeStore.setState(ObjectInDataStoreStateMachine.State.Ready);
                                volumeStore.setInstallPath(volInfo.getInstallPath());
                                volumeStore.setSize(volInfo.getSize());
                                volumeStore.setPhysicalSize(volInfo.getPhysicalSize());
                                volumeStore.setLastUpdated(new Date());
                                volumeStoreDao.update(volumeStore.getId(), volumeStore);

                                if (volume.getSize() == 0) {
                                    // Set volume size in volumes table
                                    volume.setSize(volInfo.getSize());
                                    volDao.update(volumeStore.getVolumeId(), volume);
                                }

                                if (volume.getState() == State.NotUploaded || volume.getState() == State.UploadInProgress) {
                                    VolumeObject volObj = (VolumeObject)volFactory.getVolume(volume.getId());
                                    volObj.processEvent(Event.OperationSucceeded);
                                }

                                if (volInfo.getSize() > 0) {
                                    try {
                                        resourceLimitMgr.checkResourceLimit(accountMgr.getAccount(volume.getAccountId()), com.cloud.configuration.Resource.ResourceType.secondary_storage,
                                                volInfo.getSize() - volInfo.getPhysicalSize());
                                    } catch (ResourceAllocationException e) {
                                        logger.warn(e.getMessage());
                                        alertMgr.sendAlert(AlertManager.AlertType.ALERT_TYPE_RESOURCE_LIMIT_EXCEEDED, volume.getDataCenterId(), volume.getPodId(), e.getMessage(), e.getMessage());
                                    } finally {
                                        resourceLimitMgr.recalculateResourceCount(volume.getAccountId(), volume.getDomainId(),
                                                com.cloud.configuration.Resource.ResourceType.secondary_storage.getOrdinal());
                                    }
                                }
                            }
                            continue;
                        } else if (volume.getState() == State.NotUploaded || volume.getState() == State.UploadInProgress) { // failed uploads through SSVM
                            logger.info("Volume Sync did not find {} uploaded using SSVM on image store {}, marking it as failed", volume, store);
                            toBeDownloaded.remove(volumeStore);
                            volumeStore.setDownloadState(Status.DOWNLOAD_ERROR);
                            String msg = String.format("Volume %s is corrupted on image store", volume);
                            volumeStore.setErrorString(msg);
                            volumeStoreDao.update(volumeStore.getId(), volumeStore);
                            // mark volume as failed, so that storage GC will clean it up
                            VolumeObject volObj = (VolumeObject)volFactory.getVolume(volume.getId());
                            volObj.processEvent(Event.OperationFailed);
                            continue;
                        }
                        // Volume is not on secondary but we should download.
                        if (volumeStore.getDownloadState() != Status.DOWNLOADED) {
                            logger.info("Volume Sync did not find {} ready on image store {}, will request download to start/resume shortly", volume, store);
                        }
                    }

                    // Download volumes which haven't been downloaded yet.
                    if (toBeDownloaded.size() > 0) {
                        for (VolumeDataStoreVO volumeHost : toBeDownloaded) {
                            if (volumeHost.getDownloadUrl() == null) { // If url is null, skip downloading
                                logger.info("Skip downloading volume " + volumeHost.getVolumeId() + " since no download url is specified.");
                                continue;
                            }

                            // if this is a region store, and there is already an DOWNLOADED entry there without install_path information, which
                            // means that this is a duplicate entry from migration of previous NFS to staging.
                            if (store.getScope().getScopeType() == ScopeType.REGION) {
                                if (volumeHost.getDownloadState() == VMTemplateStorageResourceAssoc.Status.DOWNLOADED && volumeHost.getInstallPath() == null) {
                                    logger.info("Skip sync volume for migration of previous NFS to object store");
                                    continue;
                                }
                            }

                            // reset volume status back to Allocated
                            VolumeObject vol = (VolumeObject)volFactory.getVolume(volumeHost.getVolumeId());
                            logger.debug("Volume {} needs to be downloaded to {}", vol, store);

                            vol.processEvent(Event.OperationFailed); // reset back volume status
                            // remove leftover volume_store_ref entry since re-download will create it again
                            volumeStoreDao.remove(volumeHost.getId());
                            // get an updated volumeVO
                            vol = (VolumeObject)volFactory.getVolume(volumeHost.getVolumeId());
                            RegisterVolumePayload payload = new RegisterVolumePayload(volumeHost.getDownloadUrl(), volumeHost.getChecksum(), vol.getFormat().toString());
                            vol.addPayload(payload);
                            downloader.apply(vol, store);
                        }
                    }

                    // Delete volumes which are not present on DB.
                    for (Map.Entry<Long, TemplateProp> entry : volumeInfos.entrySet()) {
                        TemplateProp tInfo = entry.getValue();

                        // we cannot directly call expungeVolumeAsync here to reuse delete logic since in this case db does not have this volume at all.
                        VolumeObjectTO tmplTO = new VolumeObjectTO();
                        tmplTO.setDataStore(store.getTO());
                        tmplTO.setPath(tInfo.getInstallPath());
                        tmplTO.setId(tInfo.getId());
                        DeleteCommand dtCommand = new DeleteCommand(tmplTO);
                        EndPoint ep = epSelector.select(store);
                        Answer answer = null;
                        if (ep == null) {
                            String errMsg = "No remote endpoint to send command, check if host or ssvm is down?";
                            logger.error(errMsg);
                            answer = new Answer(dtCommand, false, errMsg);
                        } else {
                            answer = ep.sendMessage(dtCommand);
                        }
                        if (answer == null || !answer.getResult()) {
                            logger.info("Failed to deleted volume at store: {}", store);

                        } else {
                            String description = String.format("Deleted volume %s on secondary storage %s", tInfo.getTemplateName(), store);
                            logger.info(description);
                        }
                    }
                } finally {
                    syncLock.unlock();
                }
            } else {
                logger.info("Couldn't get global lock on {}, another thread may be doing volume sync on data store {} now.", lockString, store);
            }
        } finally {
            syncLock.releaseRef();
        }
    }

    protected Map<Long, TemplateProp> listVolume(DataStore store) {
        ListVolumeCommand cmd = new ListVolumeCommand(store.getTO(), store.getUri());
        EndPoint ep = epSelector.select(store);
        Answer answer = null;
        if (ep == null) {
            String errMsg = "No remote endpoint to send command, check if host or ssvm is down?";
            logger.error(errMsg);
            answer = new Answer(cmd, false, errMsg);
        } else {
            answer = ep.sendMessage(cmd);
        }
        if (answer != null && answer.getResult()) {
            ListVolumeAnswer tanswer = (ListVolumeAnswer)answer;
            return tanswer.getTemplateInfo();
        } else {
            if (logger.isDebugEnabled()) {
                logger.debug("Can not list volumes for image store {}", store);
            }
        }

        return null;
    }

    @Override
    public void moveVolumeOnSecondaryStorageToAnotherAccount(Volume volume, Account sourceAccount, Account destAccount) {
        VolumeDataStoreVO volumeStore = volumeStoreDao.findByVolume(volume.getId());

        if (volumeStore == null) {
            logger.debug(String.format("Volume [%s] is not present in the secondary storage. Therefore we do not need to move it in the secondary storage.", volume));
            return;
        }
        logger.debug("Volume [{}] is present in secondary storage. It will be necessary to move it from the source account's [{}] folder to the destination "
                + "account's [{}] folder.", volume, sourceAccount, destAccount);

        VolumeInfo volumeInfo = volFactory.getVolume(volume.getId(), DataStoreRole.Image);
        String datastoreUri = volumeInfo.getDataStore().getUri();
        Path srcPath = Paths.get(volumeInfo.getPath());
        String destPath = buildVolumePath(destAccount.getAccountId(), volume.getId());

        EndPoint ssvm = epSelector.findSsvm(volume.getDataCenterId());

        MoveVolumeCommand cmd = new MoveVolumeCommand(volume.getUuid(), volume.getName(), destPath, srcPath.getParent().toString(), datastoreUri);

        Answer answer = ssvm.sendMessage(cmd);

        if (!answer.getResult()) {
            String msg = String.format("Unable to move volume [%s] from [%s] (source account's [%s] folder) to [%s] (destination account's [%s] folder) in the secondary storage, due "
                            + "to [%s].",
                    volume, srcPath.getParent(), sourceAccount, destPath, destAccount, answer.getDetails());
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }

        logger.debug("Volume [{}] was moved from [{}] (source account's [{}] folder) to [{}] (destination account's [{}] folder) in the secondary storage.",
                volume, srcPath.getParent(), sourceAccount, destPath, destAccount);

        volumeStore.setInstallPath(String.format("%s/%s", destPath, srcPath.getFileName().toString()));
        if (!volumeStoreDao.update(volumeStore.getId(), volumeStore)) {
            String msg = String.format("Unable to update volume [%s] install path in the DB.", volume);
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }
    }

    @Override
    public String buildVolumePath(long accountId, long volumeId) {
        return String.format("%s/%s/%s", TemplateConstants.DEFAULT_VOLUME_ROOT_DIR, accountId, volumeId);
    }
}
