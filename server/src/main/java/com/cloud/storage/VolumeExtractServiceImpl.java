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
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.ObjectInDataStoreStateMachine;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO;
import org.apache.cloudstack.storage.image.datastore.ImageStoreEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.ApiDBUtils;
import com.cloud.configuration.Config;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.DateUtil;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Helpers backing {@link VolumeApiServiceImpl#extractVolume} —
 * extracted from {@link VolumeApiServiceImpl}.
 *
 * @see VolumeExtractService
 */
@Component
public class VolumeExtractServiceImpl implements VolumeExtractService {
    private static final Logger LOG = LogManager.getLogger(VolumeExtractServiceImpl.class);

    @Inject
    private VolumeDao volumeDao;
    @Inject
    private VolumeDataStoreDao volumeStoreDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private PrimaryDataStoreDao storagePoolDao;
    @Inject
    private DataCenterDao dataCenterDao;
    @Inject
    private ConfigurationDao configDao;
    @Inject
    private VolumeDataFactory volFactory;
    @Inject
    private VolumeService volService;
    @Inject
    private DataStoreManager dataStoreMgr;
    @Inject
    private AccountManager accountManager;

    @Override
    public VolumeVO validateExtractRequest(Long volumeId, Long zoneId, String mode, Account caller) {
        if (!accountManager.isRootAdmin(caller.getId()) && ApiDBUtils.isExtractionDisabled()) {
            throw new PermissionDeniedException("Extraction has been disabled by admin");
        }

        VolumeVO volume = volumeDao.findById(volumeId);
        if (volume == null) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find volume with specified volumeId");
            ex.addProxyObject(String.valueOf(volumeId), "volumeId");
            throw ex;
        }

        // perform permission check
        accountManager.checkAccess(caller, null, true, volume);

        if (dataCenterDao.findById(zoneId) == null) {
            throw new InvalidParameterValueException("Please specify a valid zone.");
        }
        if (volume.getPoolId() == null) {
            throw new InvalidParameterValueException("The volume doesn't belong to a storage pool so can't extract it");
        } else {
            StoragePoolVO poolVO = storagePoolDao.findById(volume.getPoolId());
            if (poolVO != null && poolVO.getPoolType() == Storage.StoragePoolType.PowerFlex) {
                throw new InvalidParameterValueException("Cannot extract volume, this operation is unsupported for volumes on storage pool type " + poolVO.getPoolType());
            }
        }

        // Extract activity only for detached volumes or for volumes whose
        // instance is stopped
        if (volume.getInstanceId() != null && ApiDBUtils.findVMInstanceById(volume.getInstanceId()).getState() != State.Stopped) {
            LOG.debug("Invalid state of the volume: {}. It should be either detached or the VM should be in stopped state.", volume);
            PermissionDeniedException ex = new PermissionDeniedException("Invalid state of the volume with specified ID. It should be either detached or the VM should be in stopped state.");
            ex.addProxyObject(volume.getUuid(), "volumeId");
            throw ex;
        }

        if (volume.getPassphraseId() != null) {
            throw new InvalidParameterValueException("Extraction of encrypted volumes is unsupported");
        }

        if (volume.getVolumeType() != Volume.Type.DATADISK) {
            // Datadisk don't have any template dependence.

            VMTemplateVO template = ApiDBUtils.findTemplateById(volume.getTemplateId());
            if (template != null) { // For ISO based volumes template = null and
                // we allow extraction of all ISO based
                // volumes
                boolean isExtractable = template.isExtractable() && template.getTemplateType() != Storage.TemplateType.SYSTEM;
                if (!isExtractable && caller != null && !accountManager.isRootAdmin(caller.getId())) {
                    // Global admins are always allowed to extract
                    PermissionDeniedException ex = new PermissionDeniedException("The volume with specified volumeId is not allowed to be extracted");
                    ex.addProxyObject(volume.getUuid(), "volumeId");
                    throw ex;
                }
            }
        }

        if (mode == null || (!mode.equals(Upload.Mode.FTP_UPLOAD.toString()) && !mode.equals(Upload.Mode.HTTP_DOWNLOAD.toString()))) {
            throw new InvalidParameterValueException("Please specify a valid extract Mode ");
        }

        return volume;
    }

    @Override
    public Optional<String> findOrRegenerateExistingExtractUrl(SearchCriteria<VolumeDataStoreVO> sc, VolumeVO volume) {
        final long volumeId = volume.getId();
        sc.addAnd("state", SearchCriteria.Op.EQ, ObjectInDataStoreStateMachine.State.Ready.toString());
        sc.addAnd("volumeId", SearchCriteria.Op.EQ, volumeId);
        sc.addAnd("destroyed", SearchCriteria.Op.EQ, false);
        // the volume should not change (attached/detached, vm not updated) after created
        if (volume.getVolumeType() == Volume.Type.ROOT) { // for ROOT disk
            VMInstanceVO vm = vmInstanceDao.findById(volume.getInstanceId());
            sc.addAnd("updated", SearchCriteria.Op.GTEQ, vm.getUpdateTime());
        } else if (volume.getVolumeType() == Volume.Type.DATADISK && volume.getInstanceId() == null) { // for not attached DATADISK
            sc.addAnd("updated", SearchCriteria.Op.GTEQ, volume.getUpdated());
        } else { // for attached DATA DISK
            VMInstanceVO vm = vmInstanceDao.findById(volume.getInstanceId());
            sc.addAnd("updated", SearchCriteria.Op.GTEQ, vm.getUpdateTime());
            sc.addAnd("updated", SearchCriteria.Op.GTEQ, volume.getUpdated());
        }
        Filter filter = new Filter(VolumeDataStoreVO.class, "created", false, 0L, 1L);
        List<VolumeDataStoreVO> volumeStoreRefs = volumeStoreDao.search(sc, filter);
        VolumeDataStoreVO volumeStoreRef = null;
        if (volumeStoreRefs != null && !volumeStoreRefs.isEmpty()) {
            volumeStoreRef = volumeStoreRefs.get(0);
        }
        if (volumeStoreRef != null && volumeStoreRef.getExtractUrl() != null) {
            return Optional.ofNullable(volumeStoreRef.getExtractUrl());
        } else if (volumeStoreRef != null) {
            LOG.debug("volume {} is already installed on secondary storage, install path is {}", volume, volumeStoreRef.getInstallPath());
            VolumeInfo destVol = volFactory.getVolume(volumeId, DataStoreRole.Image);
            if (destVol == null) {
                throw new CloudRuntimeException("Failed to find the volume on a secondary store");
            }
            ImageStoreEntity secStore = (ImageStoreEntity) dataStoreMgr.getDataStore(volumeStoreRef.getDataStoreId(), DataStoreRole.Image);
            String extractUrl = secStore.createEntityExtractUrl(volumeStoreRef.getInstallPath(), volume.getFormat(), destVol);
            volumeStoreRef = volumeStoreDao.findByVolume(volumeId);
            volumeStoreRef.setExtractUrl(extractUrl);
            volumeStoreRef.setExtractUrlCreated(DateUtil.now());
            volumeStoreDao.update(volumeStoreRef.getId(), volumeStoreRef);
            return Optional.ofNullable(extractUrl);
        }

        return Optional.empty();
    }

    @Override
    public String orchestrateExtractVolume(long volumeId, long zoneId) {
        // get latest volume state to make sure that it is not updated by other parallel operations
        VolumeVO volume = volumeDao.findById(volumeId);
        if (volume == null || volume.getState() != Volume.State.Ready) {
            throw new InvalidParameterValueException("Volume to be extracted has been removed or not in right state!");
        }
        // perform extraction
        ImageStoreEntity secStore = (ImageStoreEntity) dataStoreMgr.getImageStoreWithFreeCapacity(zoneId);
        if (secStore == null) {
            throw new InvalidParameterValueException(String.format("Secondary storage to satisfy storage needs cannot be found for zone: %d", zoneId));
        }
        String value = configDao.getValue(Config.CopyVolumeWait.toString());
        NumbersUtil.parseInt(value, Integer.parseInt(Config.CopyVolumeWait.getDefaultValue()));

        // Copy volume from primary to secondary storage
        VolumeInfo srcVol = volFactory.getVolume(volumeId);
        VolumeInfo destVol = volFactory.getVolume(volumeId, DataStoreRole.Image);
        VolumeApiResult cvResult = null;
        if (destVol == null) {
            AsyncCallFuture<VolumeApiResult> cvAnswer = volService.copyVolume(srcVol, secStore);
            // Check if you got a valid answer.
            try {
                cvResult = cvAnswer.get();
            } catch (InterruptedException e1) {
                LOG.debug("failed copy volume", e1);
                throw new CloudRuntimeException("Failed to copy volume", e1);
            } catch (ExecutionException e1) {
                LOG.debug("failed copy volume", e1);
                throw new CloudRuntimeException("Failed to copy volume", e1);
            }
            if (cvResult == null || cvResult.isFailed()) {
                String errorString = "Failed to copy the volume from the source primary storage pool to secondary storage.";
                throw new CloudRuntimeException(errorString);
            }
        }
        VolumeInfo vol = cvResult != null ? cvResult.getVolume() : destVol;

        String extractUrl = secStore.createEntityExtractUrl(vol.getPath(), vol.getFormat(), vol);
        VolumeDataStoreVO volumeStoreRef = volumeStoreDao.findByVolume(volumeId);

        volumeStoreRef.setExtractUrl(extractUrl);
        volumeStoreRef.setExtractUrlCreated(DateUtil.now());
        volumeStoreRef.setDownloadState(VMTemplateStorageResourceAssoc.Status.DOWNLOADED);
        volumeStoreRef.setDownloadPercent(100);
        volumeStoreRef.setZoneId(zoneId);

        volumeStoreDao.update(volumeStoreRef.getId(), volumeStoreRef);

        return extractUrl;
    }
}
