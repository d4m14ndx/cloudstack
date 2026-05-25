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
import static com.cloud.configuration.ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_CPU_CORES;
import static com.cloud.configuration.ConfigurationManagerImpl.VM_SERVICE_OFFERING_MAX_RAM_SIZE;

import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.offering.CreateServiceOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.DeleteServiceOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.UpdateServiceOfferingCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.utils.reflectiontostringbuilderutils.ReflectionToStringBuilderUtils;
import org.apache.cloudstack.vm.lease.VMLeaseManager;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VsphereStoragePolicyDao;
import com.cloud.domain.Domain;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.UnsupportedServiceException;
import com.cloud.gpu.GPU;
import com.cloud.gpu.VgpuProfileVO;
import com.cloud.gpu.dao.VgpuProfileDao;
import com.cloud.host.HostTagVO;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.hypervisor.kvm.dpdk.DpdkHelper;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.server.ManagementService;
import com.cloud.service.ServiceOfferingDetailsVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
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
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VmDetailConstants;
import com.cloud.vm.dao.VMInstanceDao;
import com.google.common.base.Enums;
import org.apache.cloudstack.resourcedetail.DiskOfferingDetailVO;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.apache.cloudstack.utils.jsinterpreter.TagAsRuleHelper;

/**
 * Service-offering CRUD — extracted from {@link ConfigurationManagerImpl}.
 *
 * <p>Holds the inner write path ({@code createServiceOffering(userId, ...)},
 * {@code createDiskOfferingInternal}), the rate / cache-mode / tag validators,
 * and the lease / vGPU / extra-config helpers so this slice has no
 * back-reference into the manager. {@link ConfigurationManagerImpl} retains
 * its own equivalent helpers because the protected
 * {@code createServiceOffering(userId, ...)} overload is still called from
 * {@code cloneServiceOffering}.
 *
 * @see ServiceOfferingService
 */
@Component
public class ServiceOfferingServiceImpl implements ServiceOfferingService {

    protected static final Logger LOGGER = LoggerFactory.getLogger(ServiceOfferingServiceImpl.class);

    @Inject
    private ServiceOfferingDao _serviceOfferingDao;
    @Inject
    private ServiceOfferingDetailsDao _serviceOfferingDetailsDao;
    @Inject
    private DiskOfferingDao _diskOfferingDao;
    @Inject
    private DiskOfferingDetailsDao diskOfferingDetailsDao;
    @Inject
    private VMInstanceDao _vmInstanceDao;
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
    private VgpuProfileDao vgpuProfileDao;
    @Inject
    private HostDao _hostDao;
    @Inject
    private HostTagsDao hostTagDao;
    @Inject
    private AnnotationDao annotationDao;
    @Inject
    private DomainHelper domainHelper;
    @Inject
    private ManagementService _mgr;
    @Inject
    private PrimaryDataStoreDao _storagePoolDao;
    @Inject
    private StoragePoolTagsDao storagePoolTagDao;
    @Inject
    private VolumeDao _volumeDao;

    // ------------------------------------------------------------------
    // ServiceOfferingService contract
    // ------------------------------------------------------------------

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_OFFERING_CREATE, eventDescription = "creating service offering")
    public ServiceOffering createServiceOffering(final CreateServiceOfferingCmd cmd) {
        final Long userId = CallContext.current().getCallingUserId();
        final Map<String, String> details = cmd.getDetails();
        final String offeringName = cmd.getServiceOfferingName();

        final String name = cmd.getServiceOfferingName();
        if (name == null || name.length() == 0) {
            throw new InvalidParameterValueException("Failed to create service offering: specify the name that has non-zero length");
        }

        final String displayText = cmd.getDisplayText();
        if (displayText == null || displayText.length() == 0) {
            throw new InvalidParameterValueException("Failed to create service offering " + name + ": specify the display text that has non-zero length");
        }

        final Integer cpuNumber = cmd.getCpuNumber();
        final Integer cpuSpeed = cmd.getCpuSpeed();
        final Integer memory = cmd.getMemory();

        // Optional Custom Parameters
        Integer maxCPU = cmd.getMaxCPUs();
        Integer minCPU = cmd.getMinCPUs();
        Integer maxMemory = cmd.getMaxMemory();
        Integer minMemory = cmd.getMinMemory();

        // Check if service offering is Custom,
        // If Customized, the following conditions must hold
        // 1. cpuNumber, cpuSpeed and memory should be all null
        // 2. minCPU, maxCPU, minMemory and maxMemory should all be null or all specified
        boolean isCustomized = cmd.isCustomized();
        if (isCustomized) {
            // validate specs
            //restricting the createserviceoffering to allow setting all or none of the dynamic parameters to null
            if (cpuNumber != null || memory != null) {
                throw new InvalidParameterValueException("For creating a custom compute offering cpu and memory all should be null");
            }
            // if any of them is null, then all of them shoull be null
            if (maxCPU == null || minCPU == null || maxMemory == null || minMemory == null || cpuSpeed == null) {
                if (maxCPU != null || minCPU != null || maxMemory != null || minMemory != null || cpuSpeed != null) {
                    throw new InvalidParameterValueException("For creating a custom compute offering min/max cpu and min/max memory/cpu speed should all be null or all specified");
                }
            } else {
                if (cpuSpeed.intValue() < 0 || cpuSpeed.longValue() > Integer.MAX_VALUE) {
                    throw new InvalidParameterValueException("Failed to create service offering " + offeringName + ": specify the cpu speed value between 1 and " + Integer.MAX_VALUE);
                }
                if ((maxCPU <= 0 || maxCPU.longValue() > Integer.MAX_VALUE) || (minCPU <= 0 || minCPU.longValue() > Integer.MAX_VALUE)) {
                    throw new InvalidParameterValueException("Failed to create service offering " + offeringName + ": specify the minimum or minimum cpu number value between 1 and " + Integer.MAX_VALUE);
                }
                if (minMemory < 32 || (minMemory.longValue() > Integer.MAX_VALUE) || (maxMemory.longValue() > Integer.MAX_VALUE)) {
                    throw new InvalidParameterValueException("Failed to create service offering " + offeringName + ": specify the memory value between 32 and " + Integer.MAX_VALUE + " MB");
                }
                // Persist min/max CPU and Memory parameters in the service_offering_details table
                details.put(ApiConstants.MIN_MEMORY, minMemory.toString());
                details.put(ApiConstants.MAX_MEMORY, maxMemory.toString());
                details.put(ApiConstants.MIN_CPU_NUMBER, minCPU.toString());
                details.put(ApiConstants.MAX_CPU_NUMBER, maxCPU.toString());
            }
        } else {
            Integer maxCPUCores = VM_SERVICE_OFFERING_MAX_CPU_CORES.value() == 0 ? Integer.MAX_VALUE : VM_SERVICE_OFFERING_MAX_CPU_CORES.value();
            Integer maxRAMSize = VM_SERVICE_OFFERING_MAX_RAM_SIZE.value() == 0 ? Integer.MAX_VALUE : VM_SERVICE_OFFERING_MAX_RAM_SIZE.value();
            if (cpuNumber != null && (cpuNumber.intValue() <= 0 || cpuNumber.longValue() > maxCPUCores)) {
                throw new InvalidParameterValueException("Failed to create service offering " + offeringName + ": specify the cpu number value between 1 and " + maxCPUCores);
            }
            if (cpuSpeed == null || (cpuSpeed.intValue() < 0 || cpuSpeed.longValue() > Integer.MAX_VALUE)) {
                throw new InvalidParameterValueException("Failed to create service offering " + offeringName + ": specify the cpu speed value between 0 and " + Integer.MAX_VALUE);
            }
            if (memory != null && (memory.intValue() < 32 || memory.longValue() > maxRAMSize)) {
                throw new InvalidParameterValueException("Failed to create service offering " + offeringName + ": specify the memory value between 32 and " + maxRAMSize + " MB");
            }
        }

        // check if valid domain
        if (CollectionUtils.isNotEmpty(cmd.getDomainIds())) {
            for (final Long domainId : cmd.getDomainIds()) {
                if (_domainDao.findById(domainId) == null) {
                    throw new InvalidParameterValueException("Please specify a valid domain id");
                }
            }
        }

        // check if valid zone
        if (CollectionUtils.isNotEmpty(cmd.getZoneIds())) {
            for (Long zoneId : cmd.getZoneIds()) {
                if (_zoneDao.findById(zoneId) == null)
                    throw new InvalidParameterValueException("Please specify a valid zone id");
            }
        }

        // check if cache_mode parameter is valid
        validateCacheMode(cmd.getCacheMode());

        final Boolean offerHA = cmd.isOfferHa();

        boolean localStorageRequired = false;
        final String storageType = cmd.getStorageType();
        if (storageType != null) {
            if (storageType.equalsIgnoreCase(ServiceOffering.StorageType.local.toString())) {
                if (offerHA) {
                    throw new InvalidParameterValueException("HA offering with local storage is not supported. ");
                }
                localStorageRequired = true;
            } else if (!storageType.equalsIgnoreCase(ServiceOffering.StorageType.shared.toString())) {
                throw new InvalidParameterValueException("Invalid storage type " + storageType + " specified, valid types are: 'local' and 'shared'");
            }
        }

        final Boolean limitCpuUse = cmd.isLimitCpuUse();
        final Boolean volatileVm = cmd.isVolatileVm();

        final String vmTypeString = cmd.getSystemVmType();
        VirtualMachine.Type vmType = null;
        boolean allowNetworkRate = false;

        Boolean isCustomizedIops;

        if (cmd.isSystem()) {
            if (vmTypeString == null || VirtualMachine.Type.DomainRouter.toString().toLowerCase().equals(vmTypeString)) {
                vmType = VirtualMachine.Type.DomainRouter;
                allowNetworkRate = true;
            } else if (VirtualMachine.Type.ConsoleProxy.toString().toLowerCase().equals(vmTypeString)) {
                vmType = VirtualMachine.Type.ConsoleProxy;
            } else if (VirtualMachine.Type.SecondaryStorageVm.toString().toLowerCase().equals(vmTypeString)) {
                vmType = VirtualMachine.Type.SecondaryStorageVm;
            } else if (VirtualMachine.Type.InternalLoadBalancerVm.toString().toLowerCase().equals(vmTypeString)) {
                vmType = VirtualMachine.Type.InternalLoadBalancerVm;
            } else {
                throw new InvalidParameterValueException("Invalid systemVmType. Supported types are: " + VirtualMachine.Type.DomainRouter + ", " + VirtualMachine.Type.ConsoleProxy
                        + ", " + VirtualMachine.Type.SecondaryStorageVm);
            }

            if (cmd.isCustomizedIops() != null) {
                throw new InvalidParameterValueException("Customized IOPS is not a valid parameter for a system VM.");
            }

            isCustomizedIops = false;

            if (cmd.getHypervisorSnapshotReserve() != null) {
                throw new InvalidParameterValueException("Hypervisor Snapshot reserve is not a valid parameter for a system VM.");
            }
        } else {
            allowNetworkRate = true;
            isCustomizedIops = cmd.isCustomizedIops();
        }

        if (cmd.getNetworkRate() != null) {
            if (!allowNetworkRate) {
                throw new InvalidParameterValueException("Network rate can be specified only for non-System offering and system offerings having \"domainrouter\" systemvmtype");
            }
            if (cmd.getNetworkRate().intValue() < 0) {
                throw new InvalidParameterValueException("Failed to create service offering " + name + ": specify the network rate value more than 0");
            }
        }

        if (cmd.getDeploymentPlanner() != null) {
            final List<String> planners = _mgr.listDeploymentPlanners();
            if (planners != null && !planners.isEmpty()) {
                if (!planners.contains(cmd.getDeploymentPlanner())) {
                    throw new InvalidParameterValueException("Invalid name for Deployment Planner specified, please use listDeploymentPlanners to get the valid set");
                }
            } else {
                throw new InvalidParameterValueException("No deployment planners found");
            }
        }

        final Long storagePolicyId = cmd.getStoragePolicy();
        if (storagePolicyId != null) {
            if (vsphereStoragePolicyDao.findById(storagePolicyId) == null) {
                throw new InvalidParameterValueException("Please specify a valid vSphere storage policy id");
            }
        }

        final Long diskOfferingId = cmd.getDiskOfferingId();
        if (diskOfferingId != null) {
            DiskOfferingVO diskOffering = _diskOfferingDao.findById(diskOfferingId);
            if ((diskOffering == null) || diskOffering.isComputeOnly()) {
                throw new InvalidParameterValueException("Please specify a valid disk offering.");
            }
        }

        // validate lease properties and set leaseExpiryAction
        Integer leaseDuration = cmd.getLeaseDuration();
        VMLeaseManager.ExpiryAction leaseExpiryAction = validateAndGetLeaseExpiryAction(leaseDuration, cmd.getLeaseExpiryAction());

        final Long vgpuProfileId = cmd.getVgpuProfileId();
        Integer gpuCount = validateVgpuProfileAndGetGpuCount(vgpuProfileId, cmd.getGpuCount());

        return createServiceOffering(userId, cmd.isSystem(), vmType, cmd.getServiceOfferingName(), cpuNumber, memory, cpuSpeed, cmd.getDisplayText(),
                cmd.getProvisioningType(), localStorageRequired, offerHA, limitCpuUse, volatileVm, cmd.getTags(), cmd.getDomainIds(), cmd.getZoneIds(), cmd.getHostTag(),
                cmd.getNetworkRate(), cmd.getDeploymentPlanner(), details, cmd.getRootDiskSize(), isCustomizedIops, cmd.getMinIops(), cmd.getMaxIops(),
                cmd.getBytesReadRate(), cmd.getBytesReadRateMax(), cmd.getBytesReadRateMaxLength(),
                cmd.getBytesWriteRate(), cmd.getBytesWriteRateMax(), cmd.getBytesWriteRateMaxLength(),
                cmd.getIopsReadRate(), cmd.getIopsReadRateMax(), cmd.getIopsReadRateMaxLength(),
                cmd.getIopsWriteRate(), cmd.getIopsWriteRateMax(), cmd.getIopsWriteRateMaxLength(),
                cmd.getHypervisorSnapshotReserve(), cmd.getCacheMode(), storagePolicyId, cmd.getDynamicScalingEnabled(), diskOfferingId,
                cmd.getDiskOfferingStrictness(), cmd.isCustomized(), cmd.getEncryptRoot(), vgpuProfileId, gpuCount, cmd.getGpuDisplay(), cmd.isPurgeResources(), leaseDuration, leaseExpiryAction);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_OFFERING_EDIT, eventDescription = "updating service offering")
    public ServiceOffering updateServiceOffering(final UpdateServiceOfferingCmd cmd) {
        final String displayText = cmd.getDisplayText();
        final Long id = cmd.getId();
        final String name = cmd.getServiceOfferingName();
        final Integer sortKey = cmd.getSortKey();
        Long userId = CallContext.current().getCallingUserId();
        final List<Long> domainIds = cmd.getDomainIds();
        final List<Long> zoneIds = cmd.getZoneIds();
        String storageTags = cmd.getStorageTags();
        String hostTags = cmd.getHostTags();
        ServiceOffering.State state = cmd.getState();
        boolean purgeResources = cmd.isPurgeResources();
        final Map<String, String> externalDetails = cmd.getExternalDetails();
        final boolean cleanupExternalDetails = cmd.isCleanupExternalDetails();

        if (userId == null) {
            userId = Long.valueOf(User.UID_SYSTEM);
        }

        // Verify input parameters
        final ServiceOffering offeringHandle = _entityMgr.findById(ServiceOffering.class, id);
        if (offeringHandle == null) {
            throw new InvalidParameterValueException("unable to find service offering " + id);
        }

        List<Long> existingDomainIds = _serviceOfferingDetailsDao.findDomainIds(id);
        Collections.sort(existingDomainIds);

        List<Long> existingZoneIds = _serviceOfferingDetailsDao.findZoneIds(id);
        Collections.sort(existingZoneIds);

        Map<String, String> offeringDetails = _serviceOfferingDetailsDao.listDetailsKeyPairs(id);
        String purgeResourceStr = offeringDetails.get(ServiceOffering.PURGE_DB_ENTITIES_KEY);
        boolean existingPurgeResources = false;
        if (StringUtils.isNotBlank(purgeResourceStr)) {
            existingPurgeResources = Boolean.parseBoolean(purgeResourceStr);
        }

        // check if valid domain
        if (CollectionUtils.isNotEmpty(domainIds)) {
            for (final Long domainId : domainIds) {
                if (_domainDao.findById(domainId) == null) {
                    throw new InvalidParameterValueException("Please specify a valid domain id");
                }
            }
        }

        // check if valid zone
        if (CollectionUtils.isNotEmpty(zoneIds)) {
            for (Long zoneId : zoneIds) {
                if (_zoneDao.findById(zoneId) == null)
                    throw new InvalidParameterValueException("Please specify a valid zone id");
            }
        }

        final User user = _userDao.findById(userId);
        if (user == null || user.getRemoved() != null) {
            throw new InvalidParameterValueException("Unable to find active user by id " + userId);
        }
        final Account account = _accountDao.findById(user.getAccountId());

        // Filter child domains when both parent and child domains are present
        List<Long> filteredDomainIds = domainHelper.filterChildSubDomains(domainIds);
        Collections.sort(filteredDomainIds);

        // avoid domain update of service offering if any instance is associated to it
        int instanceCount = _vmInstanceDao.getVmCountByOfferingNotInDomain(offeringHandle.getId(), filteredDomainIds);
        if (instanceCount > 0) {
            throw new UnsupportedServiceException("There are Instances associated to this service offering outside of the specified domains.");
        }

        List<Long> filteredZoneIds = new ArrayList<>();
        if (CollectionUtils.isNotEmpty(zoneIds)) {
            filteredZoneIds.addAll(zoneIds);
        }
        Collections.sort(filteredZoneIds);

        if (account.getType() == Account.Type.DOMAIN_ADMIN) {
            if (!filteredZoneIds.equals(existingZoneIds)) { // Domain-admins cannot update zone(s) for offerings
                throw new InvalidParameterValueException(String.format("Unable to update zone(s) for service offering: %s by admin: %s as it is domain-admin", offeringHandle, user));
            }
            if (existingDomainIds.isEmpty()) {
                throw new InvalidParameterValueException(String.format("Unable to update public service offering: %s by user: %s because it is domain-admin", offeringHandle, user));
            } else {
                if (filteredDomainIds.isEmpty()) {
                    throw new InvalidParameterValueException(String.format("Unable to update service offering: %s to a public offering by user: %s because it is domain-admin", offeringHandle, user));
                }
            }
            if (!StringUtils.isAllBlank(hostTags, storageTags) && !ALLOW_DOMAIN_ADMINS_TO_CREATE_TAGGED_OFFERINGS.valueIn(account.getAccountId())) {
                throw new InvalidParameterValueException(String.format("User [%s] is unable to update storage tags or host tags.", user));
            }
            List<Long> nonChildDomains = new ArrayList<>();
            for (Long domainId : existingDomainIds) {
                if (!_domainDao.isChildDomain(account.getDomainId(), domainId)) {
                    if (name != null || displayText != null || sortKey != null) { // Domain-admins cannot update name, display text, sort key for offerings with domain which are not child domains for domain-admin
                        throw new InvalidParameterValueException(String.format("Unable to update service offering: %s as it has linked domain(s) which are not child domain for domain-admin: %s", offeringHandle, user));
                    }
                    nonChildDomains.add(domainId);
                }
            }
            for (Long domainId : filteredDomainIds) {
                if (!_domainDao.isChildDomain(account.getDomainId(), domainId)) {
                    Domain domain = _entityMgr.findById(Domain.class, domainId);
                    throw new InvalidParameterValueException(String.format("Unable to update service offering: %s by domain-admin: %s with domain: %s which is not a child domain", offeringHandle, user, domain));
                }
            }
            filteredDomainIds.addAll(nonChildDomains); // Final list must include domains which were not child domain for domain-admin but specified for this offering prior to update
        } else if (account.getType() != Account.Type.ADMIN) {
            throw new InvalidParameterValueException(String.format("Unable to update service offering: %s by id user: %s because it is not root-admin or domain-admin", offeringHandle, user));
        }

        final boolean updateNeeded = name != null || displayText != null || sortKey != null || storageTags != null || hostTags != null || state != null;
        final boolean serviceOfferingExternalDetailsNeedUpdate =
                serviceOfferingExternalDetailsNeedUpdate(offeringDetails, externalDetails, cleanupExternalDetails);
        final boolean detailsUpdateNeeded = !filteredDomainIds.equals(existingDomainIds) ||
                !filteredZoneIds.equals(existingZoneIds) || purgeResources != existingPurgeResources ||
                serviceOfferingExternalDetailsNeedUpdate;
        if (!updateNeeded && !detailsUpdateNeeded) {
            return _serviceOfferingDao.findById(id);
        }

        ServiceOfferingVO offering = _serviceOfferingDao.createForUpdate(id);

        if (name != null) {
            offering.setName(name);
        }

        if (displayText != null) {
            offering.setDisplayText(displayText);
        }

        if (sortKey != null) {
            offering.setSortKey(sortKey);
        }

        if (state != null) {
            offering.setState(state);
        }

        DiskOfferingVO diskOffering = _diskOfferingDao.findById(offeringHandle.getDiskOfferingId());
        updateOfferingTagsIfIsNotNull(storageTags, diskOffering);

        if (diskOffering.isComputeOnly() && state != null) {
            diskOffering.setState(state == ServiceOffering.State.Active ? DiskOffering.State.Active : DiskOffering.State.Inactive);
        }

        _diskOfferingDao.update(diskOffering.getId(), diskOffering);

        updateServiceOfferingHostTagsIfNotNull(hostTags, offering);

        if (updateNeeded && !_serviceOfferingDao.update(id, offering)) {
            return null;
        }
        List<ServiceOfferingDetailsVO> detailsVO = new ArrayList<>();
        if (detailsUpdateNeeded) {
            SearchBuilder<ServiceOfferingDetailsVO> sb = _serviceOfferingDetailsDao.createSearchBuilder();
            sb.and("offeringId", sb.entity().getResourceId(), SearchCriteria.Op.EQ);
            sb.and("detailName", sb.entity().getName(), SearchCriteria.Op.EQ);
            sb.and("detailNameLike", sb.entity().getName(), SearchCriteria.Op.LIKE);
            sb.done();
            SearchCriteria<ServiceOfferingDetailsVO> sc = sb.create();
            sc.setParameters("offeringId", String.valueOf(id));
            if (!filteredDomainIds.equals(existingDomainIds)) {
                sc.setParameters("detailName", ApiConstants.DOMAIN_ID);
                _serviceOfferingDetailsDao.remove(sc);
                for (Long domainId : filteredDomainIds) {
                    detailsVO.add(new ServiceOfferingDetailsVO(id, ApiConstants.DOMAIN_ID, String.valueOf(domainId), false));
                }
            }
            if (!filteredZoneIds.equals(existingZoneIds)) {
                sc.setParameters("detailName", ApiConstants.ZONE_ID);
                _serviceOfferingDetailsDao.remove(sc);
                for (Long zoneId : filteredZoneIds) {
                    detailsVO.add(new ServiceOfferingDetailsVO(id, ApiConstants.ZONE_ID, String.valueOf(zoneId), false));
                }
            }
            if (purgeResources != existingPurgeResources) {
                sc.setParameters("detailName", ServiceOffering.PURGE_DB_ENTITIES_KEY);
                _serviceOfferingDetailsDao.remove(sc);
                if (purgeResources) {
                    detailsVO.add(new ServiceOfferingDetailsVO(id, ServiceOffering.PURGE_DB_ENTITIES_KEY,
                            "true", false));
                }
            }
            if (serviceOfferingExternalDetailsNeedUpdate) {
                SearchCriteria<ServiceOfferingDetailsVO> externalDetailsRemoveSC = sb.create();
                externalDetailsRemoveSC.setParameters("detailNameLike", VmDetailConstants.EXTERNAL_DETAIL_PREFIX + "%");
                _serviceOfferingDetailsDao.remove(externalDetailsRemoveSC);
                if (!cleanupExternalDetails) {
                    for (Map.Entry<String, String> entry : externalDetails.entrySet()) {
                        detailsVO.add(new ServiceOfferingDetailsVO(id, entry.getKey(), entry.getValue(), true));
                    }
                }
            }
        }
        if (!detailsVO.isEmpty()) {
            for (ServiceOfferingDetailsVO detailVO : detailsVO) {
                _serviceOfferingDetailsDao.persist(detailVO);
            }
        }
        offering = _serviceOfferingDao.findById(id);
        CallContext.current().setEventDetails("Service offering ID:" + offering.getUuid());
        return offering;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_OFFERING_DELETE, eventDescription = "deleting service offering")
    public boolean deleteServiceOffering(final DeleteServiceOfferingCmd cmd) {

        final Long offeringId = cmd.getId();
        Long userId = CallContext.current().getCallingUserId();

        if (userId == null) {
            userId = Long.valueOf(User.UID_SYSTEM);
        }

        // Verify service offering id
        final ServiceOfferingVO offering = _serviceOfferingDao.findById(offeringId);
        if (offering == null) {
            throw new InvalidParameterValueException("unable to find service offering " + offeringId);
        }

        // Verify disk offering id mapped to the service offering
        final DiskOfferingVO diskOffering = _diskOfferingDao.findById(offering.getDiskOfferingId());
        if (diskOffering == null) {
            throw new InvalidParameterValueException("unable to find disk offering " + offering.getDiskOfferingId() + " mapped to the service offering " + offering);
        }

        if (offering.getDefaultUse()) {
            throw new InvalidParameterValueException(String.format("The system service offering [%s] is marked for default use and cannot be deleted", offering.getDisplayText()));
        }

        final User user = _userDao.findById(userId);
        if (user == null || user.getRemoved() != null) {
            throw new InvalidParameterValueException("Unable to find active user by id " + userId);
        }
        final Account account = _accountDao.findById(user.getAccountId());
        if (account.getType() == Account.Type.DOMAIN_ADMIN) {
            List<Long> existingDomainIds = _serviceOfferingDetailsDao.findDomainIds(offeringId);
            if (existingDomainIds.isEmpty()) {
                throw new InvalidParameterValueException(String.format("Unable to delete public service offering: %s by admin: %s because it is domain-admin", offering, user));
            }
            for (Long domainId : existingDomainIds) {
                if (!_domainDao.isChildDomain(account.getDomainId(), domainId)) {
                    throw new InvalidParameterValueException(String.format("Unable to delete service offering: %s as it has linked domain(s) which are not child domain for domain-admin: %s", offering, user));
                }
            }
        } else if (account.getType() != Account.Type.ADMIN) {
            throw new InvalidParameterValueException(String.format("Unable to delete service offering: %s by user: %s because it is not root-admin or domain-admin", offering, user));
        }

        annotationDao.removeByEntityType(AnnotationService.EntityType.SERVICE_OFFERING.name(), offering.getUuid());
        if (diskOffering.isComputeOnly()) {
            diskOffering.setState(DiskOffering.State.Inactive);
            if (!_diskOfferingDao.update(diskOffering.getId(), diskOffering)) {
                throw new CloudRuntimeException(String.format("Unable to delete disk offering %s mapped to the service offering %s", diskOffering, offering));
            }
        }
        offering.setState(ServiceOffering.State.Inactive);
        if (_serviceOfferingDao.update(offeringId, offering)) {
            CallContext.current().setEventDetails("Service offering ID: " + offering.getUuid());
            return true;
        } else {
            return false;
        }
    }

    @Override
    public List<Long> getServiceOfferingDomains(Long serviceOfferingId) {
        final ServiceOffering offeringHandle = _entityMgr.findById(ServiceOffering.class, serviceOfferingId);
        if (offeringHandle == null) {
            throw new InvalidParameterValueException("Unable to find service offering " + serviceOfferingId);
        }
        return _serviceOfferingDetailsDao.findDomainIds(serviceOfferingId);
    }

    @Override
    public List<Long> getServiceOfferingZones(Long serviceOfferingId) {
        final ServiceOffering offeringHandle = _entityMgr.findById(ServiceOffering.class, serviceOfferingId);
        if (offeringHandle == null) {
            throw new InvalidParameterValueException("Unable to find service offering " + serviceOfferingId);
        }
        return _serviceOfferingDetailsDao.findZoneIds(serviceOfferingId);
    }

    // ------------------------------------------------------------------
    // Internal helpers — local copies of the manager helpers so this slice
    // has no back-reference into ConfigurationManagerImpl.
    // ------------------------------------------------------------------

    /**
     * Inner persistence path for {@code createServiceOffering(cmd)} and the
     * manager's protected clone-compatible wrapper.
     */
    @Override
    public ServiceOfferingVO createServiceOffering(final long userId, final boolean isSystem, final VirtualMachine.Type vmType,
                                                   final String name, final Integer cpu, final Integer ramSize, final Integer speed, final String displayText, final String provisioningType, final boolean localStorageRequired,
                                                   final boolean offerHA, final boolean limitResourceUse, final boolean volatileVm, String tags, final List<Long> domainIds, List<Long> zoneIds, final String hostTag,
                                                   final Integer networkRate, final String deploymentPlanner, final Map<String, String> details, Long rootDiskSizeInGiB, final Boolean isCustomizedIops, Long minIops, Long maxIops,
                                                   Long bytesReadRate, Long bytesReadRateMax, Long bytesReadRateMaxLength,
                                                   Long bytesWriteRate, Long bytesWriteRateMax, Long bytesWriteRateMaxLength,
                                                   Long iopsReadRate, Long iopsReadRateMax, Long iopsReadRateMaxLength,
                                                   Long iopsWriteRate, Long iopsWriteRateMax, Long iopsWriteRateMaxLength,
                                                   final Integer hypervisorSnapshotReserve, String cacheMode, final Long storagePolicyID,
                                                   final boolean dynamicScalingEnabled, final Long diskOfferingId, final boolean diskOfferingStrictness,
                                                   final boolean isCustomized, final boolean encryptRoot, Long vgpuProfileId, Integer gpuCount, Boolean gpuDisplay, final boolean purgeResources, Integer leaseDuration, VMLeaseManager.ExpiryAction leaseExpiryAction) {

        // Filter child domains when both parent and child domains are present
        List<Long> filteredDomainIds = domainHelper.filterChildSubDomains(domainIds);

        // Check if user exists in the system
        final User user = _userDao.findById(userId);
        if (user == null || user.getRemoved() != null) {
            throw new InvalidParameterValueException("Unable to find active user by id " + userId);
        }
        final Account account = _accountDao.findById(user.getAccountId());
        if (account.getType() == Account.Type.DOMAIN_ADMIN) {
            if (filteredDomainIds.isEmpty()) {
                throw new InvalidParameterValueException(String.format("Unable to create public service offering by admin: %s because it is domain-admin", user));
            }
            if (!StringUtils.isAllBlank(tags, hostTag) && !ALLOW_DOMAIN_ADMINS_TO_CREATE_TAGGED_OFFERINGS.valueIn(account.getAccountId())) {
                throw new InvalidParameterValueException(String.format("User [%s] is unable to create service offerings with storage tags or host tags.", user));
            }
            for (Long domainId : filteredDomainIds) {
                if (!_domainDao.isChildDomain(account.getDomainId(), domainId)) {
                    throw new InvalidParameterValueException(String.format("Unable to create service offering by another domain-admin: %s for domain: %s", user, _entityMgr.findById(Domain.class, domainId).getUuid()));
                }
            }
        } else if (account.getType() != Account.Type.ADMIN) {
            throw new InvalidParameterValueException(String.format("Unable to create service offering by user: %s because it is not root-admin or domain-admin", user));
        }

        final ProvisioningType typedProvisioningType = ProvisioningType.getProvisioningType(provisioningType);

        tags = com.cloud.utils.StringUtils.cleanupTags(tags);

        ServiceOfferingVO serviceOffering = new ServiceOfferingVO(name, cpu, ramSize, speed, networkRate, null, offerHA,
                limitResourceUse, volatileVm, displayText, isSystem, vmType,
                hostTag, deploymentPlanner, dynamicScalingEnabled, isCustomized);

        List<ServiceOfferingDetailsVO> detailsVOList = new ArrayList<>();
        if (details != null) {
            // To have correct input, either both gpu card name and VGPU type should be passed or nothing should be passed.
            // Use XOR condition to verify that.
            final boolean entry1 = details.containsKey(GPU.Keys.pciDevice.toString());
            final boolean entry2 = details.containsKey(GPU.Keys.vgpuType.toString());
            if ((entry1 || entry2) && !(entry1 && entry2)) {
                throw new InvalidParameterValueException("Please specify the pciDevice and vgpuType correctly.");
            }
            for (final Entry<String, String> detailEntry : details.entrySet()) {
                String detailEntryValue = detailEntry.getValue();
                if (detailEntry.getKey().equals(GPU.Keys.pciDevice.toString())) {
                    if (detailEntryValue == null) {
                        throw new InvalidParameterValueException("Please specify a GPU Card.");
                    }
                }
                if (detailEntry.getKey().equals(GPU.Keys.vgpuType.toString())) {
                    if (detailEntryValue == null) {
                        throw new InvalidParameterValueException("vGPUType value cannot be null");
                    }
                }
                if (detailEntry.getKey().startsWith(ApiConstants.EXTRA_CONFIG)) {
                    validateExtraConfigInServiceOfferingDetail(detailEntry.getKey());
                    try {
                        detailEntryValue = URLDecoder.decode(detailEntry.getValue(), "UTF-8");
                    } catch (UnsupportedEncodingException | IllegalArgumentException e) {
                        LOGGER.error("Cannot decode extra configuration value for key: " + detailEntry.getKey() + ", skipping it");
                        continue;
                    }
                }
                if (detailEntry.getKey().equalsIgnoreCase(Volume.BANDWIDTH_LIMIT_IN_MBPS) || detailEntry.getKey().equalsIgnoreCase(Volume.IOPS_LIMIT)) {
                    // Add in disk offering details
                    continue;
                }
                detailsVOList.add(new ServiceOfferingDetailsVO(serviceOffering.getId(), detailEntry.getKey(), detailEntryValue, true));
            }
        }

        if (storagePolicyID != null) {
            detailsVOList.add(new ServiceOfferingDetailsVO(serviceOffering.getId(), ApiConstants.STORAGE_POLICY, String.valueOf(storagePolicyID), false));
        }
        if (purgeResources) {
            detailsVOList.add(new ServiceOfferingDetailsVO(serviceOffering.getId(),
                    ServiceOffering.PURGE_DB_ENTITIES_KEY, Boolean.TRUE.toString(), false));
        }

        serviceOffering.setDiskOfferingStrictness(diskOfferingStrictness);
        serviceOffering.setVgpuProfileId(vgpuProfileId);
        serviceOffering.setGpuCount(gpuCount);
        serviceOffering.setGpuDisplay(gpuDisplay);

        DiskOfferingVO diskOffering = null;
        if (diskOfferingId == null) {
            diskOffering = createDiskOfferingInternal(
                    name, displayText, typedProvisioningType, localStorageRequired,
                    tags, details, rootDiskSizeInGiB, isCustomizedIops, minIops, maxIops,
                    bytesReadRate, bytesReadRateMax, bytesReadRateMaxLength,
                    bytesWriteRate, bytesWriteRateMax, bytesWriteRateMaxLength,
                    iopsReadRate, iopsReadRateMax, iopsReadRateMaxLength,
                    iopsWriteRate, iopsWriteRateMax, iopsWriteRateMaxLength,
                    hypervisorSnapshotReserve, cacheMode, storagePolicyID, encryptRoot);
        } else {
            diskOffering = _diskOfferingDao.findById(diskOfferingId);
            String diskStoragePolicyId = diskOfferingDetailsDao.getDetail(diskOfferingId, ApiConstants.STORAGE_POLICY);
            if (storagePolicyID != null && diskStoragePolicyId != null) {
                throw new InvalidParameterValueException("Storage policy cannot be defined on both compute and disk offering");
            }
        }
        if (diskOffering != null) {
            serviceOffering.setDiskOfferingId(diskOffering.getId());
        } else {
            return null;
        }

        if ((serviceOffering = _serviceOfferingDao.persist(serviceOffering)) != null) {
            //persist lease properties if leaseExpiryAction is valid
            if (leaseExpiryAction != null) {
                detailsVOList.add(new ServiceOfferingDetailsVO(serviceOffering.getId(), ApiConstants.INSTANCE_LEASE_DURATION, String.valueOf(leaseDuration), false));
                detailsVOList.add(new ServiceOfferingDetailsVO(serviceOffering.getId(), ApiConstants.INSTANCE_LEASE_EXPIRY_ACTION, leaseExpiryAction.name(), false));
            }

            for (Long domainId : filteredDomainIds) {
                detailsVOList.add(new ServiceOfferingDetailsVO(serviceOffering.getId(), ApiConstants.DOMAIN_ID, String.valueOf(domainId), false));
            }
            if (CollectionUtils.isNotEmpty(zoneIds)) {
                for (Long zoneId : zoneIds) {
                    detailsVOList.add(new ServiceOfferingDetailsVO(serviceOffering.getId(), ApiConstants.ZONE_ID, String.valueOf(zoneId), false));
                }
            }
            if (CollectionUtils.isNotEmpty(detailsVOList)) {
                for (ServiceOfferingDetailsVO detail : detailsVOList) {
                    detail.setResourceId(serviceOffering.getId());
                }
                _serviceOfferingDetailsDao.saveDetails(detailsVOList);
            }

            CallContext.current().setEventDetails("Service offering ID: " + serviceOffering.getUuid());
            CallContext.current().putContextParameter(ServiceOffering.class, serviceOffering.getId());
            return serviceOffering;
        } else {
            return null;
        }
    }

    private DiskOfferingVO createDiskOfferingInternal(final String name, final String displayText, final ProvisioningType typedProvisioningType, final boolean localStorageRequired,
                                                      String tags, final Map<String, String> details, Long rootDiskSizeInGiB, final Boolean isCustomizedIops, Long minIops, Long maxIops,
                                                      Long bytesReadRate, Long bytesReadRateMax, Long bytesReadRateMaxLength,
                                                      Long bytesWriteRate, Long bytesWriteRateMax, Long bytesWriteRateMaxLength,
                                                      Long iopsReadRate, Long iopsReadRateMax, Long iopsReadRateMaxLength,
                                                      Long iopsWriteRate, Long iopsWriteRateMax, Long iopsWriteRateMaxLength,
                                                      final Integer hypervisorSnapshotReserve, String cacheMode, final Long storagePolicyID, boolean encrypt) {

        DiskOfferingVO diskOffering = new DiskOfferingVO(name, displayText, typedProvisioningType, false, tags, false, localStorageRequired, false);

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

        if (rootDiskSizeInGiB != null && rootDiskSizeInGiB <= 0L) {
            throw new InvalidParameterValueException(String.format("The Root disk size is of %s GB but it must be greater than 0.", rootDiskSizeInGiB));
        } else if (rootDiskSizeInGiB != null) {
            long maxVolumeSizeInGb = VolumeOrchestrationService.MaxVolumeSize.value();
            if (rootDiskSizeInGiB > maxVolumeSizeInGb) {
                throw new InvalidParameterValueException(String.format("The maximum size for a disk is %d GB.", maxVolumeSizeInGb));
            }
            long rootDiskSizeInBytes = rootDiskSizeInGiB * 1024L * 1024L * 1024L;
            diskOffering.setDiskSize(rootDiskSizeInBytes);
        }

        diskOffering.setCustomizedIops(isCustomizedIops);
        diskOffering.setMinIops(minIops);
        diskOffering.setMaxIops(maxIops);
        diskOffering.setEncrypt(encrypt);

        setBytesRate(diskOffering, bytesReadRate, bytesReadRateMax, bytesReadRateMaxLength, bytesWriteRate, bytesWriteRateMax, bytesWriteRateMaxLength);
        setIopsRate(diskOffering, iopsReadRate, iopsReadRateMax, iopsReadRateMaxLength, iopsWriteRate, iopsWriteRateMax, iopsWriteRateMaxLength);

        if (cacheMode != null) {
            diskOffering.setCacheMode(DiskOffering.DiskCacheMode.valueOf(cacheMode.toUpperCase()));
        }

        if (hypervisorSnapshotReserve != null && hypervisorSnapshotReserve < 0) {
            throw new InvalidParameterValueException("If provided, Hypervisor Snapshot Reserve must be greater than or equal to 0.");
        }

        diskOffering.setHypervisorSnapshotReserve(hypervisorSnapshotReserve);

        if ((diskOffering = _diskOfferingDao.persist(diskOffering)) != null) {
            Map<String, String> diskDetails = details == null ? Collections.emptyMap() : details;
            if (!diskDetails.isEmpty() || storagePolicyID != null) {
                List<DiskOfferingDetailVO> diskDetailsVO = new ArrayList<>();
                // Support disk offering details for below parameters
                if (diskDetails.containsKey(Volume.BANDWIDTH_LIMIT_IN_MBPS)) {
                    diskDetailsVO.add(new DiskOfferingDetailVO(diskOffering.getId(), Volume.BANDWIDTH_LIMIT_IN_MBPS, diskDetails.get(Volume.BANDWIDTH_LIMIT_IN_MBPS), false));
                }
                if (diskDetails.containsKey(Volume.IOPS_LIMIT)) {
                    diskDetailsVO.add(new DiskOfferingDetailVO(diskOffering.getId(), Volume.IOPS_LIMIT, diskDetails.get(Volume.IOPS_LIMIT), false));
                }

                if (storagePolicyID != null) {
                    diskDetailsVO.add(new DiskOfferingDetailVO(diskOffering.getId(), ApiConstants.STORAGE_POLICY, String.valueOf(storagePolicyID), false));
                }

                if (!diskDetailsVO.isEmpty()) {
                    diskOfferingDetailsDao.saveDetails(diskDetailsVO);
                }
            }
        } else {
            return null;
        }

        return diskOffering;
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

    protected void validateExtraConfigInServiceOfferingDetail(String detailName) {
        if (!detailName.equals(DpdkHelper.DPDK_NUMA) && !detailName.equals(DpdkHelper.DPDK_HUGE_PAGES)
                && !detailName.startsWith(DpdkHelper.DPDK_INTERFACE_PREFIX)) {
            throw new InvalidParameterValueException("Only extraconfig for DPDK are supported in service offering details");
        }
    }

    protected VMLeaseManager.ExpiryAction validateAndGetLeaseExpiryAction(Integer leaseDuration, VMLeaseManager.ExpiryAction cmdExpiryAction) {
        if (!VMLeaseManager.InstanceLeaseEnabled.value() || ObjectUtils.allNull(leaseDuration, cmdExpiryAction)) { // both are null
            return null;
        }

        // one of them is non-null
        if (ObjectUtils.anyNull(leaseDuration, cmdExpiryAction)) {
            throw new InvalidParameterValueException("Provide values for both: leaseduration and leaseexpiryaction");
        }

        if (leaseDuration < 1L || leaseDuration > VMLeaseManager.MAX_LEASE_DURATION_DAYS) {
            throw new InvalidParameterValueException("Invalid leaseduration: must be a natural number (>=1), max supported value is 36500");
        }

        return cmdExpiryAction;
    }

    protected Integer validateVgpuProfileAndGetGpuCount(final Long vgpuProfileId, Integer gpuCount) {
        Integer finalGpuCount = gpuCount;
        if (vgpuProfileId != null) {
            VgpuProfileVO vgpuProfile = vgpuProfileDao.findById(vgpuProfileId);
            if (vgpuProfile == null) {
                throw new InvalidParameterValueException("Please specify a valid vgpu profile.");
            }
            if (gpuCount != null && gpuCount < 1) {
                throw new InvalidParameterValueException("GPU count must be greater than 0.");
            }
            if (gpuCount == null) {
                finalGpuCount = 1;
            }
        }
        return finalGpuCount;
    }

    /**
     * Update tag(s) on the disk offering linked to a compute offering. Mirrors
     * {@code ConfigurationManagerImpl.updateOfferingTagsIfIsNotNull} including
     * the active-volume guard that fails the update if the pools backing any
     * active volume don't already carry the requested tags.
     */
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

    protected void updateServiceOfferingHostTagsIfNotNull(String hostTags, ServiceOfferingVO offering) {
        if (hostTags == null) {
            return;
        }
        if (StringUtils.isNotBlank(hostTags)) {
            hostTags = com.cloud.utils.StringUtils.cleanupTags(hostTags);
            List<HostVO> hosts = _hostDao.listHostsWithActiveVMs(offering.getId());
            if (CollectionUtils.isNotEmpty(hosts)) {
                List<String> listOfHostTags = Arrays.asList(hostTags.split(","));
                for (HostVO host : hosts) {
                    List<HostTagVO> tagsOnHost = hostTagDao.getHostTags(host.getId());
                    List<String> tagsAsString = tagsOnHost.stream().map(HostTagVO::getTag).collect(Collectors.toList());

                    if ((CollectionUtils.isNotEmpty(tagsAsString) && tagsAsString.containsAll(listOfHostTags)) ||
                            (tagsOnHost.size() == 1 && tagsOnHost.get(0).getIsTagARule() &&
                                    TagAsRuleHelper.interpretTagAsRule(tagsOnHost.get(0).getTag(), hostTags, HostTagsDao.hostTagRuleExecutionTimeout.value()))) {
                        continue;
                    }

                    throw new InvalidParameterValueException(String.format("There are active VMs using offering [%s], and the hosts [%s] don't have the new tags",
                            offering, hosts));
                }
            }
            offering.setHostTag(hostTags);
        } else {
            offering.setHostTag(null);
        }
    }

    protected boolean serviceOfferingExternalDetailsNeedUpdate(final Map<String, String> offeringDetails,
                                                               final Map<String, String> externalDetails, final boolean cleanupExternalDetails) {
        if (MapUtils.isEmpty(externalDetails) && !cleanupExternalDetails) {
            return false;
        }

        Map<String, String> existingExternalDetails = offeringDetails.entrySet().stream()
                .filter(detail -> detail.getKey().startsWith(VmDetailConstants.EXTERNAL_DETAIL_PREFIX))
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        if (cleanupExternalDetails) {
            return !MapUtils.isEmpty(existingExternalDetails);
        }

        if (MapUtils.isEmpty(existingExternalDetails) || existingExternalDetails.size() != externalDetails.size()) {
            return true;
        }

        for (Map.Entry<String, String> entry : externalDetails.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if (!value.equals(existingExternalDetails.get(key))) {
                return true;
            }
        }
        return false;
    }
}
