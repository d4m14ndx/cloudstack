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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.offering.CloneDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.CloneServiceOfferingCmd;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.apache.cloudstack.vm.lease.VMLeaseManager;
import org.apache.commons.lang3.ObjectUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.offering.ServiceOffering;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Storage;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.vm.VirtualMachine;

@Component
public class OfferingCloneParameterServiceImpl implements OfferingCloneParameterService {

    protected Logger logger = LoggerFactory.getLogger(OfferingCloneParameterServiceImpl.class);

    @Inject
    ServiceOfferingDao _serviceOfferingDao;

    @Inject
    ServiceOfferingDetailsDao _serviceOfferingDetailsDao;

    @Inject
    DiskOfferingDao _diskOfferingDao;

    @Inject
    DiskOfferingDetailsDao diskOfferingDetailsDao;

    @Override
    public ServiceOfferingVO getAndValidateSourceOffering(Long sourceOfferingId) {
        final ServiceOfferingVO sourceOffering = _serviceOfferingDao.findById(sourceOfferingId);
        if (sourceOffering == null) {
            throw new InvalidParameterValueException("Unable to find service offering with ID: " + sourceOfferingId);
        }
        return sourceOffering;
    }

    @Override
    public DiskOfferingVO getSourceDiskOffering(ServiceOfferingVO sourceOffering) {
        final Long sourceDiskOfferingId = sourceOffering.getDiskOfferingId();
        return sourceDiskOfferingId != null ? _diskOfferingDao.findById(sourceDiskOfferingId) : null;
    }

    @Override
    public <T> T getOrDefault(T cmdValue, T defaultValue) {
        return cmdValue != null ? cmdValue : defaultValue;
    }

    @Override
    public Boolean resolveBooleanParam(Map<String, String> requestParams, String paramKey,
                                       Supplier<Boolean> cmdValueSupplier, Boolean defaultValue) {
        return requestParams != null && requestParams.containsKey(paramKey) ? cmdValueSupplier.get() : defaultValue;
    }

    @Override
    public String resolveProvisioningType(CloneServiceOfferingCmd cmd, DiskOfferingVO sourceDiskOffering) {
        if (cmd.getProvisioningType() != null) {
            return cmd.getProvisioningType();
        }
        if (sourceDiskOffering != null) {
            return sourceDiskOffering.getProvisioningType().toString();
        }
        return Storage.ProvisioningType.THIN.toString();
    }

    @Override
    public String resolveStorageType(CloneServiceOfferingCmd cmd, DiskOfferingVO sourceDiskOffering) {
        if (cmd.getStorageType() != null) {
            return cmd.getStorageType();
        }
        if (sourceDiskOffering != null && sourceDiskOffering.isUseLocalStorage()) {
            return ServiceOffering.StorageType.local.toString();
        }
        return ServiceOffering.StorageType.shared.toString();
    }

    @Override
    public List<Long> resolveDomainIds(CloneServiceOfferingCmd cmd, ServiceOfferingVO sourceOffering) {
        List<Long> domainIds = cmd.getDomainIds();
        if (domainIds == null || domainIds.isEmpty()) {
            domainIds = _serviceOfferingDetailsDao.findDomainIds(sourceOffering.getId());
        }
        return domainIds;
    }

    @Override
    public List<Long> resolveZoneIds(CloneServiceOfferingCmd cmd, ServiceOfferingVO sourceOffering) {
        List<Long> zoneIds = cmd.getZoneIds();
        if (zoneIds == null || zoneIds.isEmpty()) {
            zoneIds = _serviceOfferingDetailsDao.findZoneIds(sourceOffering.getId());
        }
        return zoneIds;
    }

    @Override
    public ClonedDiskOfferingParams resolveDiskOfferingParams(CloneServiceOfferingCmd cmd, DiskOfferingVO sourceDiskOffering) {
        final ClonedDiskOfferingParams params = new ClonedDiskOfferingParams();

        params.rootDiskSize = getOrDefault(cmd.getRootDiskSize(), sourceDiskOffering != null ? sourceDiskOffering.getDiskSize() : null);
        params.bytesReadRate = getOrDefault(cmd.getBytesReadRate(), sourceDiskOffering != null ? sourceDiskOffering.getBytesReadRate() : null);
        params.bytesReadRateMax = getOrDefault(cmd.getBytesReadRateMax(), sourceDiskOffering != null ? sourceDiskOffering.getBytesReadRateMax() : null);
        params.bytesReadRateMaxLength = getOrDefault(cmd.getBytesReadRateMaxLength(), sourceDiskOffering != null ? sourceDiskOffering.getBytesReadRateMaxLength() : null);
        params.bytesWriteRate = getOrDefault(cmd.getBytesWriteRate(), sourceDiskOffering != null ? sourceDiskOffering.getBytesWriteRate() : null);
        params.bytesWriteRateMax = getOrDefault(cmd.getBytesWriteRateMax(), sourceDiskOffering != null ? sourceDiskOffering.getBytesWriteRateMax() : null);
        params.bytesWriteRateMaxLength = getOrDefault(cmd.getBytesWriteRateMaxLength(), sourceDiskOffering != null ? sourceDiskOffering.getBytesWriteRateMaxLength() : null);
        params.iopsReadRate = getOrDefault(cmd.getIopsReadRate(), sourceDiskOffering != null ? sourceDiskOffering.getIopsReadRate() : null);
        params.iopsReadRateMax = getOrDefault(cmd.getIopsReadRateMax(), sourceDiskOffering != null ? sourceDiskOffering.getIopsReadRateMax() : null);
        params.iopsReadRateMaxLength = getOrDefault(cmd.getIopsReadRateMaxLength(), sourceDiskOffering != null ? sourceDiskOffering.getIopsReadRateMaxLength() : null);
        params.iopsWriteRate = getOrDefault(cmd.getIopsWriteRate(), sourceDiskOffering != null ? sourceDiskOffering.getIopsWriteRate() : null);
        params.iopsWriteRateMax = getOrDefault(cmd.getIopsWriteRateMax(), sourceDiskOffering != null ? sourceDiskOffering.getIopsWriteRateMax() : null);
        params.iopsWriteRateMaxLength = getOrDefault(cmd.getIopsWriteRateMaxLength(), sourceDiskOffering != null ? sourceDiskOffering.getIopsWriteRateMaxLength() : null);
        params.isCustomizedIops = getOrDefault(cmd.isCustomizedIops(), sourceDiskOffering != null ? sourceDiskOffering.isCustomizedIops() : null);
        params.minIops = getOrDefault(cmd.getMinIops(), sourceDiskOffering != null ? sourceDiskOffering.getMinIops() : null);
        params.maxIops = getOrDefault(cmd.getMaxIops(), sourceDiskOffering != null ? sourceDiskOffering.getMaxIops() : null);
        params.hypervisorSnapshotReserve = getOrDefault(cmd.getHypervisorSnapshotReserve(), sourceDiskOffering != null ? sourceDiskOffering.getHypervisorSnapshotReserve() : null);

        if (cmd.getCacheMode() != null) {
            params.cacheMode = cmd.getCacheMode();
        } else if (sourceDiskOffering != null && sourceDiskOffering.getCacheMode() != null) {
            params.cacheMode = sourceDiskOffering.getCacheMode().toString();
        }

        return params;
    }

    @Override
    public CustomOfferingParams resolveCustomOfferingParams(CloneServiceOfferingCmd cmd, ServiceOfferingVO sourceOffering, Boolean isCustomized) {
        final CustomOfferingParams params = new CustomOfferingParams();

        params.maxCPU = resolveDetailParameter(cmd.getMaxCPUs(), sourceOffering.getId(), ApiConstants.MAX_CPU_NUMBER);
        params.minCPU = resolveDetailParameter(cmd.getMinCPUs(), sourceOffering.getId(), ApiConstants.MIN_CPU_NUMBER);
        params.maxMemory = resolveDetailParameter(cmd.getMaxMemory(), sourceOffering.getId(), ApiConstants.MAX_MEMORY);
        params.minMemory = resolveDetailParameter(cmd.getMinMemory(), sourceOffering.getId(), ApiConstants.MIN_MEMORY);
        params.storagePolicy = resolveDetailParameterAsLong(cmd.getStoragePolicy(), sourceOffering.getId(), ApiConstants.STORAGE_POLICY);

        return params;
    }

    Integer resolveDetailParameter(Integer cmdValue, Long offeringId, String detailKey) {
        if (cmdValue != null) {
            return cmdValue;
        }
        String detailValue = _serviceOfferingDetailsDao.getDetail(offeringId, detailKey);
        return detailValue != null ? Integer.parseInt(detailValue) : null;
    }

    Long resolveDetailParameterAsLong(Long cmdValue, Long offeringId, String detailKey) {
        if (cmdValue != null) {
            return cmdValue;
        }
        String detailValue = _serviceOfferingDetailsDao.getDetail(offeringId, detailKey);
        return detailValue != null ? Long.parseLong(detailValue) : null;
    }

    @Override
    public Boolean resolvePurgeResources(CloneServiceOfferingCmd cmd, Map<String, String> requestParams, ServiceOfferingVO sourceOffering) {
        if (requestParams != null && requestParams.containsKey(ApiConstants.PURGE_RESOURCES)) {
            return cmd.isPurgeResources();
        }
        String purgeResourcesStr = _serviceOfferingDetailsDao.getDetail(sourceOffering.getId(), ServiceOffering.PURGE_DB_ENTITIES_KEY);
        return Boolean.parseBoolean(purgeResourcesStr);
    }

    @Override
    public LeaseParams resolveLeaseParams(CloneServiceOfferingCmd cmd, ServiceOfferingVO sourceOffering) {
        final LeaseParams params = new LeaseParams();

        params.leaseDuration = resolveDetailParameter(cmd.getLeaseDuration(), sourceOffering.getId(), ApiConstants.INSTANCE_LEASE_DURATION);

        if (cmd.getLeaseExpiryAction() != null) {
            params.leaseExpiryAction = cmd.getLeaseExpiryAction();
        } else {
            String leaseExpiryActionStr = _serviceOfferingDetailsDao.getDetail(sourceOffering.getId(), ApiConstants.INSTANCE_LEASE_EXPIRY_ACTION);
            if (leaseExpiryActionStr != null) {
                params.leaseExpiryAction = VMLeaseManager.ExpiryAction.valueOf(leaseExpiryActionStr);
            }
        }

        params.leaseExpiryAction = validateAndGetLeaseExpiryAction(params.leaseDuration, params.leaseExpiryAction);
        return params;
    }

    VMLeaseManager.ExpiryAction validateAndGetLeaseExpiryAction(Integer leaseDuration, VMLeaseManager.ExpiryAction cmdExpiryAction) {
        if (!VMLeaseManager.InstanceLeaseEnabled.value() || ObjectUtils.allNull(leaseDuration, cmdExpiryAction)) {
            return null;
        }

        if (ObjectUtils.anyNull(leaseDuration, cmdExpiryAction)) {
            throw new InvalidParameterValueException("Provide values for both: leaseduration and leaseexpiryaction");
        }

        if (leaseDuration < 1L || leaseDuration > VMLeaseManager.MAX_LEASE_DURATION_DAYS) {
            throw new InvalidParameterValueException("Invalid leaseduration: must be a natural number (>=1), max supported value is 36500");
        }

        return cmdExpiryAction;
    }

    @Override
    public Map<String, String> mergeOfferingDetails(CloneServiceOfferingCmd cmd, ServiceOfferingVO sourceOffering, CustomOfferingParams customParams) {
        final Map<String, String> cmdDetails = cmd.getDetails();
        final Map<String, String> mergedDetails = new HashMap<>();

        if (cmdDetails == null || cmdDetails.isEmpty()) {
            Map<String, String> sourceDetails = _serviceOfferingDetailsDao.listDetailsKeyPairs(sourceOffering.getId());
            if (sourceDetails != null) {
                mergedDetails.putAll(sourceDetails);
            }
        } else {
            mergedDetails.putAll(cmdDetails);
        }

        if (customParams.minCPU != null && customParams.maxCPU != null &&
            customParams.minMemory != null && customParams.maxMemory != null) {
            mergedDetails.put(ApiConstants.MIN_MEMORY, customParams.minMemory.toString());
            mergedDetails.put(ApiConstants.MAX_MEMORY, customParams.maxMemory.toString());
            mergedDetails.put(ApiConstants.MIN_CPU_NUMBER, customParams.minCPU.toString());
            mergedDetails.put(ApiConstants.MAX_CPU_NUMBER, customParams.maxCPU.toString());
        }

        return mergedDetails;
    }

    @Override
    public VirtualMachine.Type resolveVmType(ServiceOfferingVO sourceOffering) {
        if (sourceOffering.getVmType() == null) {
            return null;
        }
        try {
            return VirtualMachine.Type.valueOf(sourceOffering.getVmType());
        } catch (IllegalArgumentException e) {
            logger.warn("Invalid VM type in source offering: {}", sourceOffering.getVmType());
            return null;
        }
    }

    @Override
    public DiskOfferingVO getAndValidateSourceDiskOffering(Long sourceOfferingId) {
        final DiskOfferingVO sourceOffering = _diskOfferingDao.findById(sourceOfferingId);
        if (sourceOffering == null) {
            throw new InvalidParameterValueException("Unable to find disk offering with ID: " + sourceOfferingId);
        }
        return sourceOffering;
    }

    @Override
    public List<Long> resolveDomainIdsForDiskOffering(CloneDiskOfferingCmd cmd, DiskOfferingVO sourceOffering) {
        List<Long> domainIds = cmd.getDomainIds();
        if (domainIds == null || domainIds.isEmpty()) {
            domainIds = diskOfferingDetailsDao.findDomainIds(sourceOffering.getId());
        }
        return domainIds;
    }

    @Override
    public List<Long> resolveZoneIdsForDiskOffering(CloneDiskOfferingCmd cmd, DiskOfferingVO sourceOffering) {
        List<Long> zoneIds = cmd.getZoneIds();
        if (zoneIds == null || zoneIds.isEmpty()) {
            zoneIds = diskOfferingDetailsDao.findZoneIds(sourceOffering.getId());
        }
        return zoneIds;
    }

    @Override
    public boolean resolveLocalStorageRequired(CloneDiskOfferingCmd cmd, DiskOfferingVO sourceOffering) {
        if (cmd.getStorageType() != null) {
            return ServiceOffering.StorageType.local.toString().equalsIgnoreCase(cmd.getStorageType());
        }
        return sourceOffering.isUseLocalStorage();
    }

    @Override
    public String resolveCacheMode(CloneDiskOfferingCmd cmd, DiskOfferingVO sourceOffering) {
        if (cmd.getCacheMode() != null) {
            return cmd.getCacheMode();
        }
        if (sourceOffering.getCacheMode() != null) {
            return sourceOffering.getCacheMode().toString();
        }
        return null;
    }

    @Override
    public Long resolveStoragePolicyForDiskOffering(CloneDiskOfferingCmd cmd, DiskOfferingVO sourceOffering) {
        Long storagePolicy = cmd.getStoragePolicy();
        if (storagePolicy == null) {
            String storagePolicyStr = diskOfferingDetailsDao.getDetail(sourceOffering.getId(), ApiConstants.STORAGE_POLICY);
            if (storagePolicyStr != null) {
                storagePolicy = Long.parseLong(storagePolicyStr);
            }
        }
        return storagePolicy;
    }

    @Override
    public ClonedDiskIopsParams resolveDiskIopsParams(CloneDiskOfferingCmd cmd, DiskOfferingVO sourceOffering) {
        final ClonedDiskIopsParams params = new ClonedDiskIopsParams();

        params.minIops = getOrDefault(cmd.getMinIops(), sourceOffering.getMinIops());
        params.maxIops = getOrDefault(cmd.getMaxIops(), sourceOffering.getMaxIops());
        params.iopsReadRate = getOrDefault(cmd.getIopsReadRate(), sourceOffering.getIopsReadRate());
        params.iopsReadRateMax = getOrDefault(cmd.getIopsReadRateMax(), sourceOffering.getIopsReadRateMax());
        params.iopsReadRateMaxLength = getOrDefault(cmd.getIopsReadRateMaxLength(), sourceOffering.getIopsReadRateMaxLength());
        params.iopsWriteRate = getOrDefault(cmd.getIopsWriteRate(), sourceOffering.getIopsWriteRate());
        params.iopsWriteRateMax = getOrDefault(cmd.getIopsWriteRateMax(), sourceOffering.getIopsWriteRateMax());
        params.iopsWriteRateMaxLength = getOrDefault(cmd.getIopsWriteRateMaxLength(), sourceOffering.getIopsWriteRateMaxLength());

        return params;
    }

    @Override
    public ClonedDiskRateParams resolveDiskRateParams(CloneDiskOfferingCmd cmd, DiskOfferingVO sourceOffering) {
        final ClonedDiskRateParams params = new ClonedDiskRateParams();

        params.bytesReadRate = getOrDefault(cmd.getBytesReadRate(), sourceOffering.getBytesReadRate());
        params.bytesReadRateMax = getOrDefault(cmd.getBytesReadRateMax(), sourceOffering.getBytesReadRateMax());
        params.bytesReadRateMaxLength = getOrDefault(cmd.getBytesReadRateMaxLength(), sourceOffering.getBytesReadRateMaxLength());
        params.bytesWriteRate = getOrDefault(cmd.getBytesWriteRate(), sourceOffering.getBytesWriteRate());
        params.bytesWriteRateMax = getOrDefault(cmd.getBytesWriteRateMax(), sourceOffering.getBytesWriteRateMax());
        params.bytesWriteRateMaxLength = getOrDefault(cmd.getBytesWriteRateMaxLength(), sourceOffering.getBytesWriteRateMaxLength());

        return params;
    }

    @Override
    public Map<String, String> mergeDiskOfferingDetails(CloneDiskOfferingCmd cmd, DiskOfferingVO sourceOffering) {
        final Map<String, String> cmdDetails = cmd.getDetails();
        final Map<String, String> mergedDetails = new HashMap<>();

        if (cmdDetails == null || cmdDetails.isEmpty()) {
            Map<String, String> sourceDetails = diskOfferingDetailsDao.listDetailsKeyPairs(sourceOffering.getId());
            if (sourceDetails != null) {
                mergedDetails.putAll(sourceDetails);
            }
        } else {
            mergedDetails.putAll(cmdDetails);
        }

        return mergedDetails;
    }

    public static class ClonedDiskOfferingParams {
        public Long rootDiskSize;
        public Long bytesReadRate;
        public Long bytesReadRateMax;
        public Long bytesReadRateMaxLength;
        public Long bytesWriteRate;
        public Long bytesWriteRateMax;
        public Long bytesWriteRateMaxLength;
        public Long iopsReadRate;
        public Long iopsReadRateMax;
        public Long iopsReadRateMaxLength;
        public Long iopsWriteRate;
        public Long iopsWriteRateMax;
        public Long iopsWriteRateMaxLength;
        public Boolean isCustomizedIops;
        public Long minIops;
        public Long maxIops;
        public Integer hypervisorSnapshotReserve;
        public String cacheMode;
    }

    public static class CustomOfferingParams {
        public Integer maxCPU;
        public Integer minCPU;
        public Integer maxMemory;
        public Integer minMemory;
        public Long storagePolicy;
    }

    public static class LeaseParams {
        public Integer leaseDuration;
        public VMLeaseManager.ExpiryAction leaseExpiryAction;
    }

    public static class ClonedDiskIopsParams {
        public Long minIops;
        public Long maxIops;
        public Long iopsReadRate;
        public Long iopsReadRateMax;
        public Long iopsReadRateMaxLength;
        public Long iopsWriteRate;
        public Long iopsWriteRateMax;
        public Long iopsWriteRateMaxLength;
    }

    public static class ClonedDiskRateParams {
        public Long bytesReadRate;
        public Long bytesReadRateMax;
        public Long bytesReadRateMaxLength;
        public Long bytesWriteRate;
        public Long bytesWriteRateMax;
        public Long bytesWriteRateMaxLength;
    }
}
