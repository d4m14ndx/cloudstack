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

import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.user.volume.GetUploadParamsForVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.UploadVolumeCmd;
import org.apache.cloudstack.api.response.GetUploadParamsResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.direct.download.DirectDownloadHelper;
import org.apache.cloudstack.engine.subsystem.api.storage.DataObject;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.resourcelimit.Reserver;
import org.apache.cloudstack.storage.command.TemplateOrVolumePostUploadCommand;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO;
import org.apache.cloudstack.utils.imagestore.ImageStoreUtil;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.joda.time.DateTime;
import org.joda.time.DateTimeZone;
import org.springframework.stereotype.Component;

import com.cloud.configuration.Config;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.Domain;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.DiskOffering;
import com.cloud.org.Grouping;
import com.cloud.resource.ResourceManager;
import com.cloud.resourcelimit.ReservationHelper;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.TemplateManager;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.EncryptionUtil;
import com.cloud.utils.EnumUtils;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.UriUtils;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import com.cloud.api.ApiDBUtils;

/**
 * Business logic for inbound volume upload registration — both URL-pull and
 * POST-upload (S3-style) paths. Extracted from {@link VolumeApiServiceImpl}
 * as part of the Phase 4 Spring-component decomposition (slice 10).
 *
 * @see VolumeUploadRegistrationService
 */
@Component
public class VolumeUploadRegistrationServiceImpl implements VolumeUploadRegistrationService {

    private static final Logger LOG = LogManager.getLogger(VolumeUploadRegistrationServiceImpl.class);

    private static final String CUSTOM_DISK_OFFERING_UNIQUE_NAME = "Cloud.com-Custom";

    @Inject
    private VolumeDataFactory volFactory;
    @Inject
    private VolumeService volService;
    @Inject
    private TemplateManager _tmpltMgr;
    @Inject
    private AccountManager _accountMgr;
    @Inject
    private EntityManager _entityMgr;
    @Inject
    private AccountDao _accountDao;
    @Inject
    private DiskOfferingDao _diskOfferingDao;
    @Inject
    private ConfigurationDao _configDao;
    @Inject
    private ConfigurationManager _configMgr;
    @Inject
    private DataCenterDao _dcDao;
    @Inject
    private ResourceLimitService _resourceLimitMgr;
    @Inject
    private ResourceManager _resourceMgr;
    @Inject
    private VolumeDao _volsDao;
    @Inject
    private VolumeDataStoreDao _volumeStoreDao;
    @Inject
    private DomainDao domainDao;
    @Inject
    private DiskOfferingCompatibilityService diskOfferingCompatibilityService;

    // -------------------------------------------------------------------------
    // Public interface
    // -------------------------------------------------------------------------

    @Override
    @DB
    public VolumeVO uploadVolume(UploadVolumeCmd cmd) throws ResourceAllocationException {
        Account caller = CallContext.current().getCallingAccount();
        long ownerId = cmd.getEntityOwnerId();
        Account owner = _entityMgr.findById(Account.class, ownerId);
        Long zoneId = cmd.getZoneId();
        String volumeName = cmd.getVolumeName();
        String url = cmd.getUrl();
        String format = sanitizeFormat(cmd.getFormat());
        Long diskOfferingId = cmd.getDiskOfferingId();
        String imageStoreUuid = cmd.getImageStoreUuid();

        VolumeVO volume;

        List<Reserver> reservations = new ArrayList<>();
        try {
            validateVolume(caller, ownerId, zoneId, volumeName, url, format, diskOfferingId, reservations);
            volume = persistVolume(owner, zoneId, volumeName, url, format, diskOfferingId, Volume.State.Allocated);
        } finally {
            ReservationHelper.closeAll(reservations);
        }

        VolumeInfo vol = volFactory.getVolume(volume.getId());

        RegisterVolumePayload payload = new RegisterVolumePayload(cmd.getUrl(), cmd.getChecksum(), format);
        vol.addPayload(payload);
        DataStore store = _tmpltMgr.getImageStore(imageStoreUuid, zoneId, volume);

        volService.registerVolume(vol, store);
        return volume;
    }

    @Override
    public GetUploadParamsResponse uploadVolume(final GetUploadParamsForVolumeCmd cmd)
            throws ResourceAllocationException, MalformedURLException {
        Account caller = CallContext.current().getCallingAccount();
        long ownerId = cmd.getEntityOwnerId();
        final Account owner = _entityMgr.findById(Account.class, ownerId);
        final Long zoneId = cmd.getZoneId();
        final String volumeName = cmd.getName();
        String format = sanitizeFormat(cmd.getFormat());
        final Long diskOfferingId = cmd.getDiskOfferingId();
        String imageStoreUuid = cmd.getImageStoreUuid();

        List<Reserver> reservations = new ArrayList<>();
        try {
            validateVolume(caller, ownerId, zoneId, volumeName, null, format, diskOfferingId, reservations);

            return Transaction.execute(new TransactionCallbackWithException<GetUploadParamsResponse, MalformedURLException>() {
                @Override
                public GetUploadParamsResponse doInTransaction(TransactionStatus status) throws MalformedURLException {

                    VolumeVO volume = persistVolume(owner, zoneId, volumeName, null, format, diskOfferingId, Volume.State.NotUploaded);

                    final DataStore store = _tmpltMgr.getImageStore(imageStoreUuid, zoneId, volume);

                    VolumeInfo vol = volFactory.getVolume(volume.getId());

                    RegisterVolumePayload payload = new RegisterVolumePayload(null, cmd.getChecksum(), format);
                    vol.addPayload(payload);

                    Pair<EndPoint, DataObject> pair = volService.registerVolumeForPostUpload(vol, store);
                    EndPoint ep = pair.first();
                    DataObject dataObject = pair.second();

                    GetUploadParamsResponse response = new GetUploadParamsResponse();

                    String ssvmUrlDomain = _configDao.getValue(Config.SecStorageSecureCopyCert.key());
                    String protocol = VolumeApiServiceImpl.UseHttpsToUpload.value() ? "https" : "http";

                    String url = ImageStoreUtil.generatePostUploadUrl(ssvmUrlDomain, ep.getPublicAddr(), vol.getUuid(), protocol);
                    response.setPostURL(new URL(url));

                    // set the post url, this is used in the monitoring thread to determine the SSVM
                    VolumeDataStoreVO volumeStore = _volumeStoreDao.findByVolume(vol.getId());
                    assert (volumeStore != null) : "sincle volume is registered, volumestore cannot be null at this stage";
                    volumeStore.setExtractUrl(url);
                    _volumeStoreDao.persist(volumeStore);

                    response.setId(UUID.fromString(vol.getUuid()));

                    int timeout = ImageStoreUploadMonitorImpl.getUploadOperationTimeout();
                    DateTime currentDateTime = new DateTime(DateTimeZone.UTC);
                    String expires = currentDateTime.plusMinutes(timeout).toString();
                    response.setTimeout(expires);

                    String key = _configDao.getValue(Config.SSVMPSK.key());
                    /*
                     * encoded metadata using the post upload config key
                     */
                    TemplateOrVolumePostUploadCommand command = new TemplateOrVolumePostUploadCommand(vol.getId(),
                            vol.getUuid(), volumeStore.getInstallPath(), cmd.getChecksum(), vol.getType().toString(),
                            vol.getName(), vol.getFormat().toString(), dataObject.getDataStore().getUri(),
                            dataObject.getDataStore().getRole().toString(), zoneId);
                    command.setLocalPath(volumeStore.getLocalDownloadPath());
                    //using the existing max upload size configuration
                    command.setProcessTimeout(NumbersUtil.parseLong(_configDao.getValue("vmware.package.ova.timeout"), 3600));
                    command.setMaxUploadSize(_configDao.getValue(Config.MaxUploadVolumeSize.key()));

                    long accountId = vol.getAccountId();
                    Account account = _accountDao.findById(accountId);
                    com.cloud.domain.Domain domain = domainDao.findById(account.getDomainId());

                    command.setDefaultMaxSecondaryStorageInBytes(_resourceLimitMgr.findCorrectResourceLimitForAccountAndDomain(account, domain, ResourceType.secondary_storage, null));
                    command.setAccountId(accountId);
                    Gson gson = new GsonBuilder().create();
                    String metadata = EncryptionUtil.encodeData(gson.toJson(command), key);
                    response.setMetadata(metadata);

                    /*
                     * signature calculated on the url, expiry, metadata.
                     */
                    response.setSignature(EncryptionUtil.generateSignature(metadata + url + expires, key));
                    return response;
                }
            });

        } finally {
            ReservationHelper.closeAll(reservations);
        }
    }

    @Override
    public String getRandomVolumeName() {
        return UUID.randomUUID().toString();
    }

    @Override
    @DB
    public VolumeVO persistVolume(final Account owner, final Long zoneId, final String volumeName,
            final String url, final String format, final Long diskOfferingId, final Volume.State state) {
        return Transaction.execute(new TransactionCallbackWithException<VolumeVO, CloudRuntimeException>() {
            @Override
            public VolumeVO doInTransaction(TransactionStatus status) {
                VolumeVO volume = new VolumeVO(volumeName, zoneId, -1, -1, -1, -1L, null, null, Storage.ProvisioningType.THIN, 0, Volume.Type.DATADISK);
                DataCenter zone = _dcDao.findById(zoneId);
                volume.setPoolId(null);
                volume.setDataCenterId(zoneId);
                volume.setPodId(null);
                volume.setState(state); // initialize the state
                // to prevent a null pointer deref I put the system account id here when no owner is given.
                // TODO Decide if this is valid or whether throwing a CloudRuntimeException is more appropriate
                volume.setAccountId((owner == null) ? Account.ACCOUNT_ID_SYSTEM : owner.getAccountId());
                volume.setDomainId((owner == null) ? Domain.ROOT_DOMAIN : owner.getDomainId());

                Long volumeDiskOfferingId = diskOfferingId;
                if (volumeDiskOfferingId == null) {
                    volumeDiskOfferingId = getCustomDiskOfferingIdForVolumeUpload(owner, zone);
                    if (volumeDiskOfferingId == null) {
                        throw new CloudRuntimeException(String.format("Unable to find custom disk offering in zone: %s for volume upload", zone.getUuid()));
                    }
                }

                volume.setDiskOfferingId(volumeDiskOfferingId);
                DiskOfferingVO diskOfferingVO = _diskOfferingDao.findById(volumeDiskOfferingId);

                Boolean isCustomizedIops = diskOfferingVO != null && diskOfferingVO.isCustomizedIops() != null ? diskOfferingVO.isCustomizedIops() : false;

                if (isCustomizedIops == null || !isCustomizedIops) {
                    volume.setMinIops(diskOfferingVO.getMinIops());
                    volume.setMaxIops(diskOfferingVO.getMaxIops());
                }

                // volume.setSize(size);
                volume.setInstanceId(null);
                volume.setUpdated(new Date());
                volume.setDomainId((owner == null) ? Domain.ROOT_DOMAIN : owner.getDomainId());
                volume.setFormat(ImageFormat.valueOf(format));
                volume = _volsDao.persist(volume);
                CallContext.current().setEventDetails("Volume ID: " + volume.getUuid());
                CallContext.current().putContextParameter(Volume.class, volume.getUuid());

                // Increment resource count during allocation; if actual creation fails,
                // decrement it
                _resourceLimitMgr.incrementVolumeResourceCount(volume.getAccountId(), true, null, diskOfferingVO);
                //url can be null incase of postupload
                if (url != null) {
                    long remoteSize = UriUtils.getRemoteSize(url, StorageManager.DataStoreDownloadFollowRedirects.value());
                    _resourceLimitMgr.incrementResourceCount(volume.getAccountId(), ResourceType.secondary_storage,
                            remoteSize);
                    volume.setSize(remoteSize);
                }

                return volume;
            }
        });
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private String sanitizeFormat(String format) {
        if (StringUtils.isBlank(format)) {
            throw new CloudRuntimeException("Please provide a format");
        }

        String uppercase = format.toUpperCase();
        try {
            ImageFormat.valueOf(uppercase);
        } catch (IllegalArgumentException e) {
            String msg = "Image format: " + format + " is incorrect. Supported formats are " + EnumUtils.listValues(ImageFormat.values());
            LOG.error("ImageFormat IllegalArgumentException: " + e.getMessage(), e);
            throw new IllegalArgumentException(msg);
        }
        return uppercase;
    }

    private boolean validateVolume(Account caller, long ownerId, Long zoneId, String volumeName, String url,
            String format, Long diskOfferingId, List<Reserver> reservations) throws ResourceAllocationException {

        // permission check
        Account volumeOwner = _accountMgr.getActiveAccountById(ownerId);
        DiskOfferingVO diskOffering = null;
        if (diskOfferingId != null) {
            diskOffering = _diskOfferingDao.findById(diskOfferingId);
        }
        _accountMgr.checkAccess(caller, null, true, volumeOwner);

        // Check that the resource limit for volumes won't be exceeded
        _resourceLimitMgr.checkVolumeResourceLimit(volumeOwner, true, null, diskOffering, reservations);

        // Verify that zone exists
        DataCenterVO zone = _dcDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Unable to find zone by id " + zoneId);
        }

        // Check if zone is disabled
        if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !_accountMgr.isRootAdmin(caller.getId())) {
            throw new PermissionDeniedException("Cannot perform this operation, Zone is currently disabled: " + zoneId);
        }

        //validating the url only when url is not null. url can be null incase of form based post upload
        if (url != null) {
            if (url.toLowerCase().contains("file://")) {
                throw new InvalidParameterValueException("File:// type urls are currently unsupported");
            }
            UriUtils.validateUrl(format, url);
            boolean followRedirects = StorageManager.DataStoreDownloadFollowRedirects.value();
            if (VolumeApiServiceImpl.VolumeUrlCheck.value()) { // global setting that can be set when their MS does not have internet access
                LOG.debug("Checking url: " + url);
                DirectDownloadHelper.checkUrlExistence(url, followRedirects);
            }
            // Check that the resource limit for secondary storage won't be exceeded
            _resourceLimitMgr.checkResourceLimit(_accountMgr.getAccount(ownerId), ResourceType.secondary_storage,
                    UriUtils.getRemoteSize(url, followRedirects));
        } else {
            _resourceLimitMgr.checkResourceLimit(_accountMgr.getAccount(ownerId), ResourceType.secondary_storage);
        }

        checkFormatWithSupportedHypervisorsInZone(format, zoneId);

        // Check that the disk offering specified is valid
        if (diskOfferingId != null) {
            if ((diskOffering == null) || diskOffering.getRemoved() != null || diskOffering.isComputeOnly()) {
                throw new InvalidParameterValueException("Please specify a valid disk offering.");
            }
            if (!diskOffering.isCustomized()) {
                throw new InvalidParameterValueException("Please specify a custom sized disk offering.");
            }
            _configMgr.checkDiskOfferingAccess(volumeOwner, diskOffering, zone);
        }

        return false;
    }

    private void checkFormatWithSupportedHypervisorsInZone(String format, Long zoneId) {
        ImageFormat imageformat = ImageFormat.valueOf(format);
        final List<HypervisorType> supportedHypervisorTypesInZone = _resourceMgr.getSupportedHypervisorTypes(zoneId, false, null);
        final HypervisorType hypervisorTypeFromFormat = ApiDBUtils.getHypervisorTypeFromFormat(zoneId, imageformat);
        if (!(supportedHypervisorTypesInZone.contains(hypervisorTypeFromFormat))) {
            throw new InvalidParameterValueException(String.format("The %s hypervisor supported for %s file format, is not found on the zone", hypervisorTypeFromFormat.toString(), format));
        }
    }

    private Long getDefaultCustomOfferingId(Account owner, DataCenter zone) {
        DiskOfferingVO diskOfferingVO = _diskOfferingDao.findByUniqueName(CUSTOM_DISK_OFFERING_UNIQUE_NAME);
        if (diskOfferingVO == null || !DiskOffering.State.Active.equals(diskOfferingVO.getState())) {
            return null;
        }
        try {
            _configMgr.checkDiskOfferingAccess(owner, diskOfferingVO, zone);
            return diskOfferingVO.getId();
        } catch (PermissionDeniedException ignored) {
        }
        return null;
    }

    private Long getCustomDiskOfferingIdForVolumeUpload(Account owner, DataCenter zone) {
        Long offeringId = getDefaultCustomOfferingId(owner, zone);
        if (offeringId != null) {
            return offeringId;
        }
        List<DiskOfferingVO> offerings = _diskOfferingDao.findCustomDiskOfferings();
        for (DiskOfferingVO offering : offerings) {
            try {
                _configMgr.checkDiskOfferingAccess(owner, offering, zone);
                return offering.getId();
            } catch (PermissionDeniedException ignored) {}
        }
        return null;
    }
}
