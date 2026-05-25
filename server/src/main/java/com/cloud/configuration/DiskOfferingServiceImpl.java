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
package com.cloud.configuration;

import static com.cloud.configuration.ConfigurationManagerImpl.ALLOW_DOMAIN_ADMINS_TO_CREATE_TAGGED_OFFERINGS;
import static com.cloud.configuration.ConfigurationManagerImpl.BYTES_MAX_READ_LENGTH;
import static com.cloud.configuration.ConfigurationManagerImpl.BYTES_MAX_WRITE_LENGTH;
import static com.cloud.configuration.ConfigurationManagerImpl.IOPS_MAX_READ_LENGTH;
import static com.cloud.configuration.ConfigurationManagerImpl.IOPS_MAX_WRITE_LENGTH;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.offering.CloneDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.CreateDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.DeleteDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.UpdateDiskOfferingCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.resourcedetail.DiskOfferingDetailVO;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.utils.jsinterpreter.TagAsRuleHelper;
import org.apache.cloudstack.utils.reflectiontostringbuilderutils.ReflectionToStringBuilderUtils;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VsphereStoragePolicyDao;
import com.cloud.domain.Domain;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Storage.ProvisioningType;
import com.cloud.storage.StoragePoolTagVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiServiceImpl;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.DomainHelper;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.google.common.base.Enums;

/**
 * Disk-offering create/clone/read/update/delete — extracted from
 * {@link ConfigurationManagerImpl}.
 *
 * <p>Holds local copies of the shared bytes/IOPS-rate validators and
 * cache-mode / domain / zone helpers so this service owns the disk-offering
 * write and read paths. Service-offering root-disk creation remains owned by
 * service-offering code.
 *
 * @see DiskOfferingService
 */
@Component
public class DiskOfferingServiceImpl implements DiskOfferingService {

    private static final String IOPS_READ_RATE = "IOPS Read";
    private static final String IOPS_WRITE_RATE = "IOPS Write";
    private static final String BYTES_READ_RATE = "Bytes Read";
    private static final String BYTES_WRITE_RATE = "Bytes Write";

    @Inject
    private DiskOfferingDao _diskOfferingDao;
    @Inject
    private DiskOfferingDetailsDao diskOfferingDetailsDao;
    @Inject
    private DataCenterDao _zoneDao;
    @Inject
    private DomainDao _domainDao;
    @Inject
    private UserDao _userDao;
    @Inject
    private AccountDao _accountDao;
    @Inject
    private EntityManager _entityMgr;
    @Inject
    private VsphereStoragePolicyDao vsphereStoragePolicyDao;
    @Inject
    private PrimaryDataStoreDao _storagePoolDao;
    @Inject
    private StoragePoolTagsDao storagePoolTagDao;
    @Inject
    private VolumeDao _volumeDao;
    @Inject
    private AnnotationDao annotationDao;
    @Inject
    private DomainHelper domainHelper;
    @Inject
    private OfferingCloneParameterService offeringCloneParameterService;

    // ------------------------------------------------------------------
    // DiskOfferingService contract
    // ------------------------------------------------------------------

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_DISK_OFFERING_CREATE, eventDescription = "creating disk offering")
    public DiskOffering createDiskOffering(final CreateDiskOfferingCmd cmd) {
        final String name = cmd.getOfferingName();
        final String description = cmd.getDisplayText();
        final String provisioningType = cmd.getProvisioningType();
        final Long numGibibytes = cmd.getDiskSize();
        final boolean isDisplayOfferingEnabled = cmd.getDisplayOffering() != null ? cmd.getDisplayOffering() : true;
        final boolean isCustomized = cmd.isCustomized() != null ? cmd.isCustomized() : false;
        final String tags = cmd.getTags();
        final List<Long> domainIds = cmd.getDomainIds();
        final List<Long> zoneIds = cmd.getZoneIds();
        final Map<String, String> details = cmd.getDetails();
        final Long storagePolicyId = cmd.getStoragePolicy();
        final boolean diskSizeStrictness = cmd.getDiskSizeStrictness();

        if (CollectionUtils.isNotEmpty(domainIds)) {
            for (final Long domainId : domainIds) {
                if (_domainDao.findById(domainId) == null) {
                    throw new InvalidParameterValueException("Please specify a valid domain id");
                }
            }
        }

        if (CollectionUtils.isNotEmpty(zoneIds)) {
            for (Long zoneId : zoneIds) {
                if (_zoneDao.findById(zoneId) == null) {
                    throw new InvalidParameterValueException("Please specify a valid zone id");
                }
            }
        }

        if (!isCustomized && numGibibytes == null) {
            throw new InvalidParameterValueException("Disksize is required for a non-customized disk offering");
        }

        if (isCustomized && numGibibytes != null) {
            throw new InvalidParameterValueException("Disksize is not allowed for a customized disk offering");
        }

        validateCacheMode(cmd.getCacheMode());

        boolean localStorageRequired = false;
        final String storageType = cmd.getStorageType();
        if (storageType != null) {
            if (storageType.equalsIgnoreCase(ServiceOffering.StorageType.local.toString())) {
                localStorageRequired = true;
            } else if (!storageType.equalsIgnoreCase(ServiceOffering.StorageType.shared.toString())) {
                throw new InvalidParameterValueException("Invalid storage type " + storageType + " specified, valid types are: 'local' and 'shared'");
            }
        }

        if (storagePolicyId != null) {
            if (vsphereStoragePolicyDao.findById(storagePolicyId) == null) {
                throw new InvalidParameterValueException("Please specify a valid vSphere storage policy id");
            }
        }

        final Boolean isCustomizedIops = cmd.isCustomizedIops();
        final Long minIops = cmd.getMinIops();
        final Long maxIops = cmd.getMaxIops();
        final Long bytesReadRate = cmd.getBytesReadRate();
        final Long bytesReadRateMax = cmd.getBytesReadRateMax();
        final Long bytesReadRateMaxLength = cmd.getBytesReadRateMaxLength();
        final Long bytesWriteRate = cmd.getBytesWriteRate();
        final Long bytesWriteRateMax = cmd.getBytesWriteRateMax();
        final Long bytesWriteRateMaxLength = cmd.getBytesWriteRateMaxLength();
        final Long iopsReadRate = cmd.getIopsReadRate();
        final Long iopsReadRateMax = cmd.getIopsReadRateMax();
        final Long iopsReadRateMaxLength = cmd.getIopsReadRateMaxLength();
        final Long iopsWriteRate = cmd.getIopsWriteRate();
        final Long iopsWriteRateMax = cmd.getIopsWriteRateMax();
        final Long iopsWriteRateMaxLength = cmd.getIopsWriteRateMaxLength();
        final Integer hypervisorSnapshotReserve = cmd.getHypervisorSnapshotReserve();
        final String cacheMode = cmd.getCacheMode();
        final boolean encrypt = cmd.getEncrypt();

        validateMaxRateEqualsOrGreater(iopsReadRate, iopsReadRateMax, IOPS_READ_RATE);
        validateMaxRateEqualsOrGreater(iopsWriteRate, iopsWriteRateMax, IOPS_WRITE_RATE);
        validateMaxRateEqualsOrGreater(bytesReadRate, bytesReadRateMax, BYTES_READ_RATE);
        validateMaxRateEqualsOrGreater(bytesWriteRate, bytesWriteRateMax, BYTES_WRITE_RATE);

        validateMaximumIopsAndBytesLength(iopsReadRateMaxLength, iopsWriteRateMaxLength, bytesReadRateMaxLength, bytesWriteRateMaxLength);

        final Long userId = CallContext.current().getCallingUserId();
        return persistDiskOffering(userId, domainIds, zoneIds, name, description, provisioningType, numGibibytes, tags, isCustomized,
                localStorageRequired, isDisplayOfferingEnabled, isCustomizedIops, minIops,
                maxIops, bytesReadRate, bytesReadRateMax, bytesReadRateMaxLength, bytesWriteRate, bytesWriteRateMax, bytesWriteRateMaxLength,
                iopsReadRate, iopsReadRateMax, iopsReadRateMaxLength, iopsWriteRate, iopsWriteRateMax, iopsWriteRateMaxLength,
                hypervisorSnapshotReserve, cacheMode, details, storagePolicyId, diskSizeStrictness, encrypt);
    }

    @Override
    public DiskOffering cloneDiskOffering(final CloneDiskOfferingCmd cmd) {
        final long userId = CallContext.current().getCallingUserId();
        final DiskOfferingVO sourceOffering = offeringCloneParameterService.getAndValidateSourceDiskOffering(cmd.getSourceOfferingId());
        final Map<String, String> requestParams = cmd.getFullUrlParams();

        final String name = cmd.getOfferingName();
        final String displayText = offeringCloneParameterService.getOrDefault(cmd.getDisplayText(), sourceOffering.getDisplayText());
        final String provisioningType = offeringCloneParameterService.getOrDefault(cmd.getProvisioningType(), sourceOffering.getProvisioningType().toString());
        final Long diskSize = offeringCloneParameterService.getOrDefault(cmd.getDiskSize(), sourceOffering.getDiskSize());
        final String tags = offeringCloneParameterService.getOrDefault(cmd.getTags(), sourceOffering.getTags());

        final Boolean isCustomized = offeringCloneParameterService.resolveBooleanParam(requestParams, ApiConstants.CUSTOMIZED, cmd::isCustomized, sourceOffering.isCustomized());
        final Boolean displayOffering = offeringCloneParameterService.resolveBooleanParam(requestParams, ApiConstants.DISPLAY_OFFERING, cmd::getDisplayOffering, sourceOffering.getDisplayOffering());
        final Boolean isCustomizedIops = offeringCloneParameterService.getOrDefault(cmd.isCustomizedIops(), sourceOffering.isCustomizedIops());
        final Boolean diskSizeStrictness = offeringCloneParameterService.resolveBooleanParam(requestParams, ApiConstants.DISK_SIZE_STRICTNESS, cmd::getDiskSizeStrictness, sourceOffering.getDiskSizeStrictness());
        final Boolean encrypt = offeringCloneParameterService.resolveBooleanParam(requestParams, ApiConstants.ENCRYPT, cmd::getEncrypt, sourceOffering.getEncrypt());

        final List<Long> domainIds = offeringCloneParameterService.resolveDomainIdsForDiskOffering(cmd, sourceOffering);
        final List<Long> zoneIds = offeringCloneParameterService.resolveZoneIdsForDiskOffering(cmd, sourceOffering);
        final boolean localStorageRequired = offeringCloneParameterService.resolveLocalStorageRequired(cmd, sourceOffering);
        final OfferingCloneParameterServiceImpl.ClonedDiskIopsParams iopsParams = offeringCloneParameterService.resolveDiskIopsParams(cmd, sourceOffering);
        final OfferingCloneParameterServiceImpl.ClonedDiskRateParams rateParams = offeringCloneParameterService.resolveDiskRateParams(cmd, sourceOffering);
        final Integer hypervisorSnapshotReserve = offeringCloneParameterService.getOrDefault(cmd.getHypervisorSnapshotReserve(), sourceOffering.getHypervisorSnapshotReserve());
        final String cacheMode = offeringCloneParameterService.resolveCacheMode(cmd, sourceOffering);
        final Long storagePolicy = offeringCloneParameterService.resolveStoragePolicyForDiskOffering(cmd, sourceOffering);
        final Map<String, String> mergedDetails = offeringCloneParameterService.mergeDiskOfferingDetails(cmd, sourceOffering);

        if (cmd.getCacheMode() != null) {
            validateCacheMode(cmd.getCacheMode());
        }

        validateMaxRateEqualsOrGreater(iopsParams.iopsReadRate, iopsParams.iopsReadRateMax, IOPS_READ_RATE);
        validateMaxRateEqualsOrGreater(iopsParams.iopsWriteRate, iopsParams.iopsWriteRateMax, IOPS_WRITE_RATE);
        validateMaxRateEqualsOrGreater(rateParams.bytesReadRate, rateParams.bytesReadRateMax, BYTES_READ_RATE);
        validateMaxRateEqualsOrGreater(rateParams.bytesWriteRate, rateParams.bytesWriteRateMax, BYTES_WRITE_RATE);
        validateMaximumIopsAndBytesLength(iopsParams.iopsReadRateMaxLength, iopsParams.iopsWriteRateMaxLength,
                rateParams.bytesReadRateMaxLength, rateParams.bytesWriteRateMaxLength);

        return persistDiskOffering(userId, domainIds, zoneIds, name, displayText, provisioningType, diskSize, tags,
                isCustomized, localStorageRequired, displayOffering, isCustomizedIops, iopsParams.minIops, iopsParams.maxIops,
                rateParams.bytesReadRate, rateParams.bytesReadRateMax, rateParams.bytesReadRateMaxLength,
                rateParams.bytesWriteRate, rateParams.bytesWriteRateMax, rateParams.bytesWriteRateMaxLength,
                iopsParams.iopsReadRate, iopsParams.iopsReadRateMax, iopsParams.iopsReadRateMaxLength,
                iopsParams.iopsWriteRate, iopsParams.iopsWriteRateMax, iopsParams.iopsWriteRateMaxLength,
                hypervisorSnapshotReserve, cacheMode, mergedDetails, storagePolicy, diskSizeStrictness, encrypt);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_DISK_OFFERING_EDIT, eventDescription = "updating disk offering")
    public DiskOffering updateDiskOffering(final UpdateDiskOfferingCmd cmd) {
        final Long diskOfferingId = cmd.getId();
        final String name = cmd.getDiskOfferingName();
        final String displayText = cmd.getDisplayText();
        final Integer sortKey = cmd.getSortKey();
        final Boolean displayDiskOffering = cmd.getDisplayOffering();
        final List<Long> domainIds = cmd.getDomainIds();
        final List<Long> zoneIds = cmd.getZoneIds();
        final String tags = cmd.getTags();

        Long bytesReadRate = cmd.getBytesReadRate();
        Long bytesReadRateMax = cmd.getBytesReadRateMax();
        Long bytesReadRateMaxLength = cmd.getBytesReadRateMaxLength();
        Long bytesWriteRate = cmd.getBytesWriteRate();
        Long bytesWriteRateMax = cmd.getBytesWriteRateMax();
        Long bytesWriteRateMaxLength = cmd.getBytesWriteRateMaxLength();
        Long iopsReadRate = cmd.getIopsReadRate();
        Long iopsReadRateMax = cmd.getIopsReadRateMax();
        Long iopsReadRateMaxLength = cmd.getIopsReadRateMaxLength();
        Long iopsWriteRate = cmd.getIopsWriteRate();
        Long iopsWriteRateMax = cmd.getIopsWriteRateMax();
        Long iopsWriteRateMaxLength = cmd.getIopsWriteRateMaxLength();
        String cacheMode = cmd.getCacheMode();
        DiskOffering.State state = cmd.getState();

        final DiskOffering diskOfferingHandle = _entityMgr.findById(DiskOffering.class, diskOfferingId);
        if (diskOfferingHandle == null) {
            throw new InvalidParameterValueException("Unable to find disk offering by id " + diskOfferingId);
        }

        List<Long> existingDomainIds = diskOfferingDetailsDao.findDomainIds(diskOfferingId);
        Collections.sort(existingDomainIds);

        List<Long> existingZoneIds = diskOfferingDetailsDao.findZoneIds(diskOfferingId);
        Collections.sort(existingZoneIds);

        validateDomain(domainIds);

        validateZone(zoneIds);

        Long userId = CallContext.current().getCallingUserId();
        if (userId == null) {
            userId = Long.valueOf(User.UID_SYSTEM);
        }
        final User user = _userDao.findById(userId);
        if (user == null || user.getRemoved() != null) {
            throw new InvalidParameterValueException("Unable to find active user by id " + userId);
        }
        final Account account = _accountDao.findById(user.getAccountId());

        List<Long> filteredDomainIds = domainHelper.filterChildSubDomains(domainIds);
        Collections.sort(filteredDomainIds);

        List<Long> filteredZoneIds = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(zoneIds)) {
            filteredZoneIds.addAll(zoneIds);
        }
        Collections.sort(filteredZoneIds);

        if (account.getType() == Account.Type.DOMAIN_ADMIN) {
            checkDomainAdminUpdateOfferingRestrictions(diskOfferingHandle, user, filteredZoneIds, existingZoneIds, existingDomainIds, filteredDomainIds);

            if (StringUtils.isNotBlank(tags) && !ALLOW_DOMAIN_ADMINS_TO_CREATE_TAGGED_OFFERINGS.valueIn(account.getAccountId())) {
                throw new InvalidParameterValueException(String.format("User [%s] is unable to update disk offering tags.", user));
            }

            List<Long> nonChildDomains = getAccountNonChildDomains(diskOfferingHandle, account, user, cmd, existingDomainIds);

            checkIfDomainIsChildDomain(diskOfferingHandle, account, user, filteredDomainIds);

            filteredDomainIds.addAll(nonChildDomains);
        } else if (account.getType() != Account.Type.ADMIN) {
            throw new InvalidParameterValueException(String.format("Unable to update disk offering: %s by id user: %s because it is not root-admin or domain-admin", diskOfferingHandle, user));
        }

        boolean updateNeeded = shouldUpdateDiskOffering(name, displayText, sortKey, displayDiskOffering, tags, cacheMode, state) ||
                shouldUpdateIopsRateParameters(iopsReadRate, iopsReadRateMax, iopsReadRateMaxLength, iopsWriteRate, iopsWriteRateMax, iopsWriteRateMaxLength) ||
                shouldUpdateBytesRateParameters(bytesReadRate, bytesReadRateMax, bytesReadRateMaxLength, bytesWriteRate, bytesWriteRateMax, bytesWriteRateMaxLength);

        final boolean detailsUpdateNeeded = !filteredDomainIds.equals(existingDomainIds) || !filteredZoneIds.equals(existingZoneIds);
        if (!updateNeeded && !detailsUpdateNeeded) {
            return _diskOfferingDao.findById(diskOfferingId);
        }

        final DiskOfferingVO diskOffering = _diskOfferingDao.createForUpdate(diskOfferingId);
        updateDiskOfferingIfCmdAttributeNotNull(diskOffering, cmd);

        updateOfferingTagsIfIsNotNull(tags, diskOffering);

        validateMaxRateEqualsOrGreater(iopsReadRate, iopsReadRateMax, IOPS_READ_RATE);
        validateMaxRateEqualsOrGreater(iopsWriteRate, iopsWriteRateMax, IOPS_WRITE_RATE);
        validateMaxRateEqualsOrGreater(bytesReadRate, bytesReadRateMax, BYTES_READ_RATE);
        validateMaxRateEqualsOrGreater(bytesWriteRate, bytesWriteRateMax, BYTES_WRITE_RATE);
        validateMaximumIopsAndBytesLength(iopsReadRateMaxLength, iopsWriteRateMaxLength, bytesReadRateMaxLength, bytesWriteRateMaxLength);

        setBytesRate(diskOffering, bytesReadRate, bytesReadRateMax, bytesReadRateMaxLength, bytesWriteRate, bytesWriteRateMax, bytesWriteRateMaxLength);
        setIopsRate(diskOffering, iopsReadRate, iopsReadRateMax, iopsReadRateMaxLength, iopsWriteRate, iopsWriteRateMax, iopsWriteRateMaxLength);

        if (cacheMode != null) {
            validateCacheMode(cacheMode);
            diskOffering.setCacheMode(DiskOffering.DiskCacheMode.valueOf(cacheMode.toUpperCase()));
        }

        if (state != null) {
            diskOffering.setState(state);
        }

        if (updateNeeded && !_diskOfferingDao.update(diskOfferingId, diskOffering)) {
            return null;
        }
        List<DiskOfferingDetailVO> detailsVO = new ArrayList<>();
        if (detailsUpdateNeeded) {
            updateDiskOfferingDetails(detailsVO, diskOfferingId, filteredDomainIds, existingDomainIds, filteredZoneIds, existingZoneIds);
        }
        if (!detailsVO.isEmpty()) {
            for (DiskOfferingDetailVO detailVO : detailsVO) {
                diskOfferingDetailsDao.persist(detailVO);
            }
        }
        CallContext.current().setEventDetails("Disk offering ID: " + diskOffering.getUuid());
        return _diskOfferingDao.findById(diskOfferingId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_DISK_OFFERING_DELETE, eventDescription = "deleting disk offering")
    public boolean deleteDiskOffering(final DeleteDiskOfferingCmd cmd) {
        final Long diskOfferingId = cmd.getId();

        final DiskOfferingVO offering = _diskOfferingDao.findById(diskOfferingId);

        if (offering == null) {
            throw new InvalidParameterValueException("Unable to find disk offering by id " + diskOfferingId);
        }

        Long userId = CallContext.current().getCallingUserId();
        if (userId == null) {
            userId = Long.valueOf(User.UID_SYSTEM);
        }
        final User user = _userDao.findById(userId);
        if (user == null || user.getRemoved() != null) {
            throw new InvalidParameterValueException("Unable to find active user by id " + userId);
        }
        final Account account = _accountDao.findById(user.getAccountId());
        if (account.getType() == Account.Type.DOMAIN_ADMIN) {
            List<Long> existingDomainIds = diskOfferingDetailsDao.findDomainIds(diskOfferingId);
            if (existingDomainIds.isEmpty()) {
                throw new InvalidParameterValueException(String.format("Unable to delete public disk offering: %s by admin: %s because it is domain-admin", offering, user));
            }
            for (Long domainId : existingDomainIds) {
                if (!_domainDao.isChildDomain(account.getDomainId(), domainId)) {
                    throw new InvalidParameterValueException(String.format("Unable to delete disk offering: %s as it has linked domain(s) which are not child domain for domain-admin: %s", offering, user));
                }
            }
        } else if (account.getType() != Account.Type.ADMIN) {
            throw new InvalidParameterValueException(String.format("Unable to delete disk offering: %s by user: %s because it is not root-admin or domain-admin", offering, user));
        }

        annotationDao.removeByEntityType(AnnotationService.EntityType.DISK_OFFERING.name(), offering.getUuid());
        offering.setState(DiskOffering.State.Inactive);
        if (_diskOfferingDao.update(offering.getId(), offering)) {
            CallContext.current().setEventDetails("Disk offering ID: " + offering.getUuid());
            return true;
        } else {
            return false;
        }
    }

    @Override
    public List<Long> getDiskOfferingDomains(Long diskOfferingId) {
        final DiskOffering offeringHandle = _entityMgr.findById(DiskOffering.class, diskOfferingId);
        if (offeringHandle == null) {
            throw new InvalidParameterValueException("Unable to find disk offering " + diskOfferingId);
        }
        return diskOfferingDetailsDao.findDomainIds(diskOfferingId);
    }

    @Override
    public List<Long> getDiskOfferingZones(Long diskOfferingId) {
        final DiskOffering offeringHandle = _entityMgr.findById(DiskOffering.class, diskOfferingId);
        if (offeringHandle == null) {
            throw new InvalidParameterValueException("Unable to find disk offering " + diskOfferingId);
        }
        return diskOfferingDetailsDao.findZoneIds(diskOfferingId);
    }

    // ------------------------------------------------------------------
    // Internal helpers for the disk-offering write path.
    // ------------------------------------------------------------------

    /**
     * Inner persistence path for disk-offering create and clone. Service-offering
     * root-disk creation owns a separate path in service-offering code.
     */
    protected DiskOfferingVO persistDiskOffering(final Long userId, final List<Long> domainIds, final List<Long> zoneIds, final String name, final String description, final String provisioningType,
                                                 final Long numGibibytes, String tags, boolean isCustomized, final boolean localStorageRequired,
                                                 final boolean isDisplayOfferingEnabled, final Boolean isCustomizedIops, Long minIops, Long maxIops,
                                                 Long bytesReadRate, Long bytesReadRateMax, Long bytesReadRateMaxLength,
                                                 Long bytesWriteRate, Long bytesWriteRateMax, Long bytesWriteRateMaxLength,
                                                 Long iopsReadRate, Long iopsReadRateMax, Long iopsReadRateMaxLength,
                                                 Long iopsWriteRate, Long iopsWriteRateMax, Long iopsWriteRateMaxLength,
                                                 final Integer hypervisorSnapshotReserve, String cacheMode, final Map<String, String> details, final Long storagePolicyID,
                                                 final boolean diskSizeStrictness, final boolean encrypt) {
        long diskSize = 0;
        long maxVolumeSizeInGb = VolumeOrchestrationService.MaxVolumeSize.value();
        if (numGibibytes != null && numGibibytes <= 0) {
            throw new InvalidParameterValueException("Please specify a disk size of at least 1 GB.");
        } else if (numGibibytes != null && numGibibytes > maxVolumeSizeInGb) {
            throw new InvalidParameterValueException(String.format("The maximum size for a disk is %d GB.", maxVolumeSizeInGb));
        }
        final ProvisioningType typedProvisioningType = ProvisioningType.getProvisioningType(provisioningType);

        if (numGibibytes != null) {
            diskSize = numGibibytes * 1024 * 1024 * 1024;
        }

        if (diskSize == 0) {
            isCustomized = true;
        }

        if (Boolean.TRUE.equals(isCustomizedIops) || isCustomizedIops == null) {
            minIops = null;
            maxIops = null;
        } else {
            if (minIops == null && maxIops == null) {
                minIops = 0L;
                maxIops = 0L;
            } else {
                if (minIops == null || minIops <= 0) {
                    throw new InvalidParameterValueException("The min IOPS must be greater than 0.");
                }

                if (maxIops == null) {
                    maxIops = 0L;
                }

                if (minIops > maxIops) {
                    throw new InvalidParameterValueException("The min IOPS must be less than or equal to the max IOPS.");
                }
            }
        }

        List<Long> filteredDomainIds = domainHelper.filterChildSubDomains(domainIds);

        final User user = _userDao.findById(userId);
        if (user == null || user.getRemoved() != null) {
            throw new InvalidParameterValueException("Unable to find active user by id " + userId);
        }
        final Account account = _accountDao.findById(user.getAccountId());
        if (account.getType() == Account.Type.DOMAIN_ADMIN) {
            if (filteredDomainIds.isEmpty()) {
                throw new InvalidParameterValueException(String.format("Unable to create public disk offering by admin: %s because it is domain-admin", user));
            }
            if (StringUtils.isNotBlank(tags) && !ALLOW_DOMAIN_ADMINS_TO_CREATE_TAGGED_OFFERINGS.valueIn(account.getAccountId())) {
                throw new InvalidParameterValueException(String.format("User [%s] is unable to create disk offerings with storage tags.", user));
            }
            for (Long domainId : filteredDomainIds) {
                if (domainId == null || !_domainDao.isChildDomain(account.getDomainId(), domainId)) {
                    throw new InvalidParameterValueException(String.format("Unable to create disk offering by another domain-admin: %s for domain: %s", user, _entityMgr.findById(Domain.class, domainId).getUuid()));
                }
            }
        } else if (account.getType() != Account.Type.ADMIN) {
            throw new InvalidParameterValueException(String.format("Unable to create disk offering by user: %s because it is not root-admin or domain-admin", user));
        }

        tags = com.cloud.utils.StringUtils.cleanupTags(tags);
        final DiskOfferingVO newDiskOffering = new DiskOfferingVO(name, description, typedProvisioningType, diskSize, tags, isCustomized,
                isCustomizedIops, minIops, maxIops);
        newDiskOffering.setUseLocalStorage(localStorageRequired);
        newDiskOffering.setDisplayOffering(isDisplayOfferingEnabled);

        setBytesRate(newDiskOffering, bytesReadRate, bytesReadRateMax, bytesReadRateMaxLength, bytesWriteRate, bytesWriteRateMax, bytesWriteRateMaxLength);
        setIopsRate(newDiskOffering, iopsReadRate, iopsReadRateMax, iopsReadRateMaxLength, iopsWriteRate, iopsWriteRateMax, iopsWriteRateMaxLength);

        if (cacheMode != null) {
            newDiskOffering.setCacheMode(DiskOffering.DiskCacheMode.valueOf(cacheMode.toUpperCase()));
        }

        if (hypervisorSnapshotReserve != null && hypervisorSnapshotReserve < 0) {
            throw new InvalidParameterValueException("If provided, Hypervisor Snapshot Reserve must be greater than or equal to 0.");
        }

        newDiskOffering.setEncrypt(encrypt);
        newDiskOffering.setHypervisorSnapshotReserve(hypervisorSnapshotReserve);
        newDiskOffering.setDiskSizeStrictness(diskSizeStrictness);

        CallContext.current().setEventDetails("Disk offering ID: " + newDiskOffering.getUuid());
        final DiskOfferingVO offering = _diskOfferingDao.persist(newDiskOffering);
        if (offering != null) {
            List<DiskOfferingDetailVO> detailsVO = new ArrayList<>();
            for (Long domainId : filteredDomainIds) {
                detailsVO.add(new DiskOfferingDetailVO(offering.getId(), ApiConstants.DOMAIN_ID, String.valueOf(domainId), false));
            }
            if (CollectionUtils.isNotEmpty(zoneIds)) {
                for (Long zoneId : zoneIds) {
                    detailsVO.add(new DiskOfferingDetailVO(offering.getId(), ApiConstants.ZONE_ID, String.valueOf(zoneId), false));
                }
            }

            if (MapUtils.isNotEmpty(details)) {
                details.forEach((key, value) -> {
                    boolean displayDetail = !StringUtils.equalsAny(key, Volume.BANDWIDTH_LIMIT_IN_MBPS, Volume.IOPS_LIMIT);
                    detailsVO.add(new DiskOfferingDetailVO(offering.getId(), key, value, displayDetail));
                });
            }
            if (storagePolicyID != null) {
                detailsVO.add(new DiskOfferingDetailVO(offering.getId(), ApiConstants.STORAGE_POLICY, String.valueOf(storagePolicyID), false));
            }
            if (!detailsVO.isEmpty()) {
                diskOfferingDetailsDao.saveDetails(detailsVO);
            }
            CallContext.current().setEventDetails("Disk offering ID: " + newDiskOffering.getUuid());
            CallContext.current().putContextParameter(DiskOffering.class, newDiskOffering.getId());
            return offering;
        }
        return null;
    }

    protected void validateMaxRateEqualsOrGreater(Long normalRate, Long maxRate, String rateType) {
        if (normalRate != null && maxRate != null && maxRate < normalRate) {
            throw new InvalidParameterValueException(
                    String.format("%s rate (%d) cannot be greater than %s maximum rate (%d)", rateType, normalRate, rateType, maxRate));
        }
    }

    protected void validateMaximumIopsAndBytesLength(final Long iopsReadRateMaxLength, final Long iopsWriteRateMaxLength, Long bytesReadRateMaxLength, Long bytesWriteRateMaxLength) {
        if (IOPS_MAX_READ_LENGTH.value() != null && IOPS_MAX_READ_LENGTH.value() != 0L) {
            if (iopsReadRateMaxLength != null && iopsReadRateMaxLength > IOPS_MAX_READ_LENGTH.value()) {
                throw new InvalidParameterValueException(String.format("IOPS read max length (%d seconds) cannot be greater than vm.disk.iops.maximum.read.length (%d seconds)",
                        iopsReadRateMaxLength, IOPS_MAX_READ_LENGTH.value()));
            }
        }

        if (IOPS_MAX_WRITE_LENGTH.value() != null && IOPS_MAX_WRITE_LENGTH.value() != 0L) {
            if (iopsWriteRateMaxLength != null && iopsWriteRateMaxLength > IOPS_MAX_WRITE_LENGTH.value()) {
                throw new InvalidParameterValueException(String.format("IOPS write max length (%d seconds) cannot be greater than vm.disk.iops.maximum.write.length (%d seconds)",
                        iopsWriteRateMaxLength, IOPS_MAX_WRITE_LENGTH.value()));
            }
        }

        if (BYTES_MAX_READ_LENGTH.value() != null && BYTES_MAX_READ_LENGTH.value() != 0L) {
            if (bytesReadRateMaxLength != null && bytesReadRateMaxLength > BYTES_MAX_READ_LENGTH.value()) {
                throw new InvalidParameterValueException(String.format("Bytes read max length (%d seconds) cannot be greater than vm.disk.bytes.maximum.read.length (%d seconds)",
                        bytesReadRateMaxLength, BYTES_MAX_READ_LENGTH.value()));
            }
        }

        if (BYTES_MAX_WRITE_LENGTH.value() != null && BYTES_MAX_WRITE_LENGTH.value() != 0L) {
            if (bytesWriteRateMaxLength != null && bytesWriteRateMaxLength > BYTES_MAX_WRITE_LENGTH.value()) {
                throw new InvalidParameterValueException(String.format("Bytes write max length (%d seconds) cannot be greater than vm.disk.bytes.maximum.write.length (%d seconds)",
                        bytesWriteRateMaxLength, BYTES_MAX_WRITE_LENGTH.value()));
            }
        }
    }

    protected void validateDomain(List<Long> domainIds) {
        if (CollectionUtils.isEmpty(domainIds)) {
            return;
        }

        for (final Long domainId : domainIds) {
            if (_domainDao.findById(domainId) == null) {
                throw new InvalidParameterValueException("Please specify a valid domain id.");
            }
        }
    }

    protected void validateZone(List<Long> zoneIds) {
        if (CollectionUtils.isEmpty(zoneIds)) {
            return;
        }

        for (Long zoneId : zoneIds) {
            if (_zoneDao.findById(zoneId) == null) {
                throw new InvalidParameterValueException("Please specify a valid zone id.");
            }
        }
    }

    protected void updateDiskOfferingIfCmdAttributeNotNull(DiskOfferingVO diskOffering, UpdateDiskOfferingCmd cmd) {
        if (cmd.getDiskOfferingName() != null) {
            diskOffering.setName(cmd.getDiskOfferingName());
        }

        if (cmd.getDisplayText() != null) {
            diskOffering.setDisplayText(cmd.getDisplayText());
        }

        if (cmd.getSortKey() != null) {
            diskOffering.setSortKey(cmd.getSortKey());
        }

        if (cmd.getDisplayOffering() != null) {
            diskOffering.setDisplayOffering(cmd.getDisplayOffering());
        }
    }

    protected void updateDiskOfferingDetails(List<DiskOfferingDetailVO> detailsVO, Long diskOfferingId, List<Long> filteredDomainIds,
                                             List<Long> existingDomainIds, List<Long> filteredZoneIds, List<Long> existingZoneIds) {
        SearchBuilder<DiskOfferingDetailVO> sb = diskOfferingDetailsDao.createSearchBuilder();
        sb.and("offeringId", sb.entity().getResourceId(), SearchCriteria.Op.EQ);
        sb.and("detailName", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.done();
        SearchCriteria<DiskOfferingDetailVO> sc = sb.create();
        sc.setParameters("offeringId", String.valueOf(diskOfferingId));

        updateDiskOfferingDetailsDomainIds(detailsVO, sc, diskOfferingId, filteredDomainIds, existingDomainIds);
        updateDiskOfferingDetailsZoneIds(detailsVO, sc, diskOfferingId, filteredZoneIds, existingZoneIds);
    }

    protected void updateDiskOfferingDetailsDomainIds(List<DiskOfferingDetailVO> detailsVO, SearchCriteria<DiskOfferingDetailVO> sc, Long diskOfferingId, List<Long> filteredDomainIds, List<Long> existingDomainIds) {
        if (filteredDomainIds.equals(existingDomainIds)) {
            return;
        }

        sc.setParameters("detailName", ApiConstants.DOMAIN_ID);
        diskOfferingDetailsDao.remove(sc);
        for (Long domainId : filteredDomainIds) {
            detailsVO.add(new DiskOfferingDetailVO(diskOfferingId, ApiConstants.DOMAIN_ID, String.valueOf(domainId), false));
        }
    }

    protected void updateDiskOfferingDetailsZoneIds(List<DiskOfferingDetailVO> detailsVO, SearchCriteria<DiskOfferingDetailVO> sc, Long diskOfferingId, List<Long> filteredZoneIds, List<Long> existingZoneIds) {
        if (filteredZoneIds.equals(existingZoneIds)) {
            return;
        }

        sc.setParameters("detailName", ApiConstants.ZONE_ID);
        diskOfferingDetailsDao.remove(sc);
        for (Long zoneId : filteredZoneIds) {
            detailsVO.add(new DiskOfferingDetailVO(diskOfferingId, ApiConstants.ZONE_ID, String.valueOf(zoneId), false));
        }
    }

    protected void checkDomainAdminUpdateOfferingRestrictions(DiskOffering diskOffering, User user, List<Long> filteredZoneIds, List<Long> existingZoneIds,
                                                              List<Long> existingDomainIds, List<Long> filteredDomainIds) {
        if (!filteredZoneIds.equals(existingZoneIds)) {
            throw new InvalidParameterValueException(String.format("Unable to update zone(s) for disk offering [%s] by admin [%s] as it is domain-admin.", diskOffering, user));
        }
        if (existingDomainIds.isEmpty()) {
            throw new InvalidParameterValueException(String.format("Unable to update public disk offering [%s] by user [%s] because it is domain-admin.", diskOffering, user));
        }
        if (filteredDomainIds.isEmpty()) {
            throw new InvalidParameterValueException(String.format("Unable to update disk offering [%s] to a public offering by user [%s] because it is domain-admin.", diskOffering, user));
        }
    }

    protected List<Long> getAccountNonChildDomains(DiskOffering diskOffering, Account account, User user,
                                                   UpdateDiskOfferingCmd cmd, List<Long> existingDomainIds) {
        List<Long> nonChildDomains = new ArrayList<>();
        String name = cmd.getDiskOfferingName();
        String displayText = cmd.getDisplayText();
        Integer sortKey = cmd.getSortKey();
        for (Long domainId : existingDomainIds) {
            if (_domainDao.isChildDomain(account.getDomainId(), domainId)) {
                continue;
            }

            if (ObjectUtils.anyNotNull(name, displayText, sortKey)) {
                throw new InvalidParameterValueException(String.format("Unable to update disk offering [%s] as it has linked domain(s) which are not child domain for domain-admin [%s].", diskOffering.getUuid(), user.getUuid()));
            }
            nonChildDomains.add(domainId);
        }
        return nonChildDomains;
    }

    protected void checkIfDomainIsChildDomain(DiskOffering diskOffering, Account account, User user, List<Long> filteredDomainIds) {
        for (Long domainId : filteredDomainIds) {
            if (_domainDao.isChildDomain(account.getDomainId(), domainId)) {
                continue;
            }

            Domain domain = _entityMgr.findById(Domain.class, domainId);
            throw new InvalidParameterValueException(String.format("Unable to update disk offering [%s] by domain-admin [%s] with domain [%3$s] which is not a child domain.", diskOffering.getUuid(), user.getUuid(), domain.getUuid()));
        }
    }

    protected void updateOfferingTagsIfIsNotNull(String tags, DiskOfferingVO diskOffering) {
        if (tags == null) {
            return;
        }
        if (StringUtils.isNotBlank(tags)) {
            tags = com.cloud.utils.StringUtils.cleanupTags(tags);
            List<StoragePoolVO> pools = _storagePoolDao.listStoragePoolsWithActiveVolumesByOfferingId(diskOffering.getId());
            if (CollectionUtils.isNotEmpty(pools)) {
                List<String> listOfTags = Arrays.asList(tags.split(","));
                for (StoragePoolVO storagePoolVO : pools) {
                    List<StoragePoolTagVO> tagsOnPool = storagePoolTagDao.findStoragePoolTags(storagePoolVO.getId());
                    List<String> tagsAsString = tagsOnPool.stream().map(StoragePoolTagVO::getTag).collect(Collectors.toList());

                    if ((CollectionUtils.isNotEmpty(tagsAsString) && tagsAsString.containsAll(listOfTags)) ||
                            (tagsOnPool.size() == 1 && tagsOnPool.get(0).isTagARule() &&
                                    TagAsRuleHelper.interpretTagAsRule(tagsOnPool.get(0).getTag(), tags, VolumeApiServiceImpl.storageTagRuleExecutionTimeout.value()))) {
                        continue;
                    }

                    DiskOfferingVO offeringToRetrieveInfo = _diskOfferingDao.findById(diskOffering.getId());
                    List<VolumeVO> volumes = _volumeDao.findByDiskOfferingId(diskOffering.getId());
                    String listOfVolumesNamesAndUuid = ReflectionToStringBuilderUtils.reflectOnlySelectedFields(volumes, "name", "uuid");
                    String diskOfferingInfo = ReflectionToStringBuilderUtils.reflectOnlySelectedFields(offeringToRetrieveInfo, "name", "uuid");
                    String poolInfo = ReflectionToStringBuilderUtils.reflectOnlySelectedFields(storagePoolVO, "name", "uuid");
                    throw new InvalidParameterValueException(String.format("There are active volumes using the disk offering %s, and the pool %s doesn't have the new tags. " +
                            "The following volumes are using the mentioned disk offering %s. Please first add the new tags to the mentioned storage pools before adding them" +
                            " to the disk offering.", diskOfferingInfo, poolInfo, listOfVolumesNamesAndUuid));
                }
            }
            diskOffering.setTags(tags);
        } else {
            diskOffering.setTags(null);
        }
    }

    protected boolean shouldUpdateDiskOffering(String name, String displayText, Integer sortKey, Boolean displayDiskOffering, String tags, String cacheMode, DiskOffering.State state) {
        return !StringUtils.isAllBlank(name, displayText, cacheMode) || tags != null || sortKey != null || displayDiskOffering != null || state != null;
    }

    protected boolean shouldUpdateBytesRateParameters(Long bytesReadRate, Long bytesReadRateMax, Long bytesReadRateMaxLength, Long bytesWriteRate, Long bytesWriteRateMax, Long bytesWriteRateMaxLength) {
        return bytesReadRate != null || bytesReadRateMax != null || bytesReadRateMaxLength != null || bytesWriteRate != null ||
                bytesWriteRateMax != null || bytesWriteRateMaxLength != null;
    }

    protected boolean shouldUpdateIopsRateParameters(Long iopsReadRate, Long iopsReadRateMax, Long iopsReadRateMaxLength, Long iopsWriteRate, Long iopsWriteRateMax, Long iopsWriteRateMaxLength) {
        return iopsReadRate != null || iopsReadRateMax != null || iopsReadRateMaxLength != null || iopsWriteRate != null || iopsWriteRateMax != null || iopsWriteRateMaxLength != null;
    }

    private void setIopsRate(DiskOffering offering, Long iopsReadRate, Long iopsReadRateMax, Long iopsReadRateMaxLength, Long iopsWriteRate, Long iopsWriteRateMax, Long iopsWriteRateMaxLength) {
        if (iopsReadRate != null && iopsReadRate > 0) {
            offering.setIopsReadRate(iopsReadRate);
        }
        if (iopsReadRateMax != null && iopsReadRateMax > 0) {
            offering.setIopsReadRateMax(iopsReadRateMax);
        }
        if (iopsReadRateMaxLength != null && iopsReadRateMaxLength > 0) {
            offering.setIopsReadRateMaxLength(iopsReadRateMaxLength);
        }
        if (iopsWriteRate != null && iopsWriteRate > 0) {
            offering.setIopsWriteRate(iopsWriteRate);
        }
        if (iopsWriteRateMax != null && iopsWriteRateMax > 0) {
            offering.setIopsWriteRateMax(iopsWriteRateMax);
        }
        if (iopsWriteRateMaxLength != null && iopsWriteRateMaxLength > 0) {
            offering.setIopsWriteRateMaxLength(iopsWriteRateMaxLength);
        }
    }

    private void setBytesRate(DiskOffering offering, Long bytesReadRate, Long bytesReadRateMax, Long bytesReadRateMaxLength, Long bytesWriteRate, Long bytesWriteRateMax, Long bytesWriteRateMaxLength) {
        if (bytesReadRate != null && bytesReadRate > 0) {
            offering.setBytesReadRate(bytesReadRate);
        }
        if (bytesReadRateMax != null && bytesReadRateMax > 0) {
            offering.setBytesReadRateMax(bytesReadRateMax);
        }
        if (bytesReadRateMaxLength != null && bytesReadRateMaxLength > 0) {
            offering.setBytesReadRateMaxLength(bytesReadRateMaxLength);
        }
        if (bytesWriteRate != null && bytesWriteRate > 0) {
            offering.setBytesWriteRate(bytesWriteRate);
        }
        if (bytesWriteRateMax != null && bytesWriteRateMax > 0) {
            offering.setBytesWriteRateMax(bytesWriteRateMax);
        }
        if (bytesWriteRateMaxLength != null && bytesWriteRateMaxLength > 0) {
            offering.setBytesWriteRateMaxLength(bytesWriteRateMaxLength);
        }
    }

    protected void validateCacheMode(String cacheMode) {
        if (cacheMode != null &&
                !Enums.getIfPresent(DiskOffering.DiskCacheMode.class, cacheMode.toUpperCase()).isPresent()) {
            throw new InvalidParameterValueException(String.format("Invalid cache mode (%s). Please specify one of the following " +
                    "valid cache mode parameters: none, writeback, writethrough or hypervisor_default.", cacheMode));
        }
    }
}
