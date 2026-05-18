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
package com.cloud.api.query;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.user.offering.ListServiceOfferingsCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.query.QueryService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.DataCenterJoinDao;
import com.cloud.api.query.dao.ServiceOfferingJoinDao;
import com.cloud.api.query.vo.DataCenterJoinVO;
import com.cloud.api.query.vo.ServiceOfferingJoinVO;
import com.cloud.dc.DataCenter;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.offering.ServiceOffering;
import com.cloud.service.ServiceOfferingDetailsVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeApiServiceImpl;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class ServiceOfferingQueryServiceImpl implements ServiceOfferingQueryService {

    protected static final Logger logger = LogManager.getLogger(ServiceOfferingQueryServiceImpl.class);

    @Inject
    private AccountManager accountMgr;

    @Inject
    private ServiceOfferingJoinDao _srvOfferingJoinDao;

    @Inject
    private ServiceOfferingDao _srvOfferingDao;

    @Inject
    private ServiceOfferingDetailsDao _srvOfferingDetailsDao;

    @Inject
    private DiskOfferingDao _diskOfferingDao;

    @Inject
    private DataCenterJoinDao _dcJoinDao;

    @Inject
    private DomainDao _domainDao;

    @Inject
    private VMTemplateDao _templateDao;

    @Inject
    private VMInstanceDao _vmInstanceDao;

    @Inject
    private UserVmDao userVmDao;

    @Inject
    private VirtualMachineManager virtualMachineManager;

    @Inject
    private HostDao hostDao;

    @Inject
    private HostTagsDao _hostTagDao;

    private void useStorageType(SearchCriteria<?> sc, String storageType) {
        if (storageType != null) {
            if (storageType.equalsIgnoreCase(ServiceOffering.StorageType.local.toString())) {
                sc.addAnd("useLocalStorage", Op.EQ, true);

            } else if (storageType.equalsIgnoreCase(ServiceOffering.StorageType.shared.toString())) {
                sc.addAnd("useLocalStorage", Op.EQ, false);
            }
        }
    }

    private List<Long> findRelatedDomainIds(Domain domain, boolean isRecursive) {
        List<Long> domainIds = new ArrayList<>(_domainDao.getDomainParentIds(domain.getId()));
        if (isRecursive) {
            List<Long> childrenIds = _domainDao.getDomainChildrenIds(domain.getPath());
            if (childrenIds != null && !childrenIds.isEmpty()) {
                domainIds.addAll(childrenIds);
            }
        }
        return domainIds;
    }

    @Override
    public ListResponse<ServiceOfferingResponse> searchForServiceOfferings(ListServiceOfferingsCmd cmd) {
        Pair<List<ServiceOfferingJoinVO>, Integer> result = searchForServiceOfferingsInternal(cmd);
        result.first();
        ListResponse<ServiceOfferingResponse> response = new ListResponse<>();
        List<ServiceOfferingResponse> offeringResponses = ViewResponseHelper.createServiceOfferingResponse(result.first().toArray(new ServiceOfferingJoinVO[0]));
        response.setResponses(offeringResponses, result.second());
        return response;
    }

    @Override
    public List<String> getHostTagsFromTemplateForServiceOfferingsListing(Account caller, Long templateId) {
        List<String> hostTags = new ArrayList<>();
        if (templateId == null) {
            return hostTags;
        }
        VMTemplateVO template = _templateDao.findByIdIncludingRemoved(templateId);
        if (template == null) {
            throw new InvalidParameterValueException("Unable to find template with the specified ID");
        }
        if (caller.getType() != Account.Type.ADMIN) {
            accountMgr.checkAccess(caller, null, false, template);
        }
        if (StringUtils.isNotEmpty(template.getTemplateTag())) {
            hostTags.add(template.getTemplateTag());
        }
        return hostTags;
    }

    private Pair<List<ServiceOfferingJoinVO>, Integer> searchForServiceOfferingsInternal(ListServiceOfferingsCmd cmd) {
        Pair<List<Long>, Integer> offeringIdPage = searchForServiceOfferingIdsAndCount(cmd);

        Integer count = offeringIdPage.second();
        Long[] idArray = offeringIdPage.first().toArray(new Long[0]);

        if (count == 0) {
            return new Pair<>(new ArrayList<>(), count);
        }

        List<ServiceOfferingJoinVO> srvOfferings = _srvOfferingJoinDao.searchByIds(idArray);
        return new Pair<>(srvOfferings, count);
    }

    private Pair<List<Long>, Integer> searchForServiceOfferingIdsAndCount(ListServiceOfferingsCmd cmd) {
        // Note
        // The filteredOfferings method for offerings is being modified in accordance with
        // discussion with Will/Kevin
        // For now, we will be listing the following based on the usertype
        // 1. For root, we will filteredOfferings all offerings
        // 2. For domainAdmin and regular users, we will filteredOfferings everything in
        // their domains+parent domains ... all the way
        // till
        // root
        Account caller = CallContext.current().getCallingAccount();
        Long projectId = cmd.getProjectId();
        String accountName = cmd.getAccountName();
        Object name = cmd.getServiceOfferingName();
        Object id = cmd.getId();
        Object keyword = cmd.getKeyword();
        Long vmId = cmd.getVirtualMachineId();
        Long domainId = cmd.getDomainId();
        Boolean isSystem = cmd.getIsSystem();
        String vmTypeStr = cmd.getSystemVmType();
        ServiceOfferingVO currentVmOffering = null;
        DiskOfferingVO diskOffering = null;
        boolean isRecursive = cmd.isRecursive();
        Long zoneId = cmd.getZoneId();
        Integer cpuNumber = cmd.getCpuNumber();
        Integer memory = cmd.getMemory();
        Integer cpuSpeed = cmd.getCpuSpeed();
        Boolean encryptRoot = cmd.getEncryptRoot();
        String storageType = cmd.getStorageType();
        ServiceOffering.State state = cmd.getState();
        final Long vgpuProfileId = cmd.getVgpuProfileId();
        final Boolean gpuEnabled = cmd.getGpuEnabled();

        final Account owner = accountMgr.finalizeOwner(caller, accountName, domainId, projectId);

        if (!accountMgr.isRootAdmin(caller.getId()) && isSystem) {
            throw new InvalidParameterValueException("Only ROOT admins can access system offerings.");
        }

        // Keeping this logic consistent with domain specific zones
        // if a domainId is provided, we just return the so associated with this
        // domain
        if (domainId != null && !accountMgr.isRootAdmin(caller.getId())) {
            // check if the user's domain == so's domain || user's domain is a
            // child of so's domain
            if (!isPermissible(owner.getDomainId(), domainId)) {
                throw new PermissionDeniedException("The account:" + owner.getAccountName() + " does not fall in the same domain hierarchy as the service offering");
            }
        }

        VMInstanceVO vmInstance = null;
        if (vmId != null) {
            vmInstance = _vmInstanceDao.findById(vmId);
            if ((vmInstance == null) || (vmInstance.getRemoved() != null)) {
                InvalidParameterValueException ex = new InvalidParameterValueException("unable to find a virtual machine with specified id");
                ex.addProxyObject(vmId.toString(), "vmId");
                throw ex;
            }
            accountMgr.checkAccess(owner, null, true, vmInstance);
        }

        Filter searchFilter = new Filter(ServiceOfferingVO.class, "sortKey", QueryService.SortKeyAscending.value(), cmd.getStartIndex(), cmd.getPageSizeVal());
        searchFilter.addOrderBy(ServiceOfferingVO.class, "id", true);

        SearchBuilder<ServiceOfferingVO> serviceOfferingSearch = _srvOfferingDao.createSearchBuilder();
        serviceOfferingSearch.select(null, Func.DISTINCT, serviceOfferingSearch.entity().getId()); // select distinct

        if (state != null) {
            serviceOfferingSearch.and("state", serviceOfferingSearch.entity().getState(), Op.EQ);
        }

        if (vgpuProfileId != null) {
            serviceOfferingSearch.and("vgpuProfileId", serviceOfferingSearch.entity().getVgpuProfileId(), Op.EQ);
        }

        if (gpuEnabled != null) {
            _srvOfferingDao.addCheckForGpuEnabled(serviceOfferingSearch, gpuEnabled);
        }

        if (vmId != null) {
            currentVmOffering = _srvOfferingDao.findByIdIncludingRemoved(vmInstance.getId(), vmInstance.getServiceOfferingId());
            diskOffering = _diskOfferingDao.findByIdIncludingRemoved(currentVmOffering.getDiskOfferingId());
            if (!currentVmOffering.isDynamic()) {
                serviceOfferingSearch.and("idNEQ", serviceOfferingSearch.entity().getId(), SearchCriteria.Op.NEQ);
            }

            if (currentVmOffering.getDiskOfferingStrictness()) {
                serviceOfferingSearch.and("diskOfferingId", serviceOfferingSearch.entity().getDiskOfferingId(), SearchCriteria.Op.EQ);
            }
            serviceOfferingSearch.and("diskOfferingStrictness", serviceOfferingSearch.entity().getDiskOfferingStrictness(), SearchCriteria.Op.EQ);

            // In case vm is running return only offerings greater than equal to current offering compute and offering's dynamic scalability should match
            if (vmInstance.getState() == VirtualMachine.State.Running) {
                Integer vmCpu = currentVmOffering.getCpu();
                Integer vmMemory = currentVmOffering.getRamSize();
                Integer vmSpeed = currentVmOffering.getSpeed();
                if ((vmCpu == null || vmMemory == null || vmSpeed == null) && VirtualMachine.Type.User.equals(vmInstance.getType())) {
                    UserVmVO userVmVO = userVmDao.findById(vmId);
                    userVmDao.loadDetails(userVmVO);
                    Map<String, String> details = userVmVO.getDetails();
                    vmCpu = NumbersUtil.parseInt(details.get(ApiConstants.CPU_NUMBER), 0);
                    if (vmSpeed == null) {
                        vmSpeed = NumbersUtil.parseInt(details.get(ApiConstants.CPU_SPEED), 0);
                    }
                    vmMemory = NumbersUtil.parseInt(details.get(ApiConstants.MEMORY), 0);
                }
                if (vmCpu != null && vmCpu > 0) {
                    /*
                            (service_offering.cpu >= ?)
                             OR (
                                service_offering.cpu IS NULL
                                AND (maxComputeDetailsSearch.value IS NULL OR  maxComputeDetailsSearch.value >= ?)
                            )
                     */
                    SearchBuilder<ServiceOfferingDetailsVO> maxComputeDetailsSearch = _srvOfferingDetailsDao.createSearchBuilder();

                    serviceOfferingSearch.join("maxComputeDetailsSearch", maxComputeDetailsSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                            serviceOfferingSearch.entity().getId(), maxComputeDetailsSearch.entity().getResourceId(),
                            maxComputeDetailsSearch.entity().getName(), serviceOfferingSearch.entity().setString(ApiConstants.MAX_CPU_NUMBER));

                    serviceOfferingSearch.and().op("vmCpu", serviceOfferingSearch.entity().getCpu(), Op.GTEQ);
                    serviceOfferingSearch.or().op("vmCpuNull", serviceOfferingSearch.entity().getCpu(), Op.NULL);
                    serviceOfferingSearch.and().op("maxComputeDetailsSearch", "vmMaxComputeNull", maxComputeDetailsSearch.entity().getValue(), Op.NULL);
                    serviceOfferingSearch.or("maxComputeDetailsSearch", "vmMaxComputeGTEQ", maxComputeDetailsSearch.entity().getValue(), Op.GTEQ).cp();

                    serviceOfferingSearch.cp().cp();

                }
                if (vmSpeed != null && vmSpeed > 0) {
                    serviceOfferingSearch.and().op("speedNULL", serviceOfferingSearch.entity().getSpeed(), Op.NULL);
                    serviceOfferingSearch.or("speedGTEQ", serviceOfferingSearch.entity().getSpeed(), Op.GTEQ);
                    serviceOfferingSearch.cp();
                }
                if (vmMemory != null && vmMemory > 0) {
                    /*
                        (service_offering.ram_size >= ?)
                        OR (
                          service_offering.ram_size IS NULL
                          AND (max_memory_details.value IS NULL OR max_memory_details.value >= ?)
                        )
                     */
                    SearchBuilder<ServiceOfferingDetailsVO> maxMemoryDetailsSearch = _srvOfferingDetailsDao.createSearchBuilder();

                    serviceOfferingSearch.join("maxMemoryDetailsSearch", maxMemoryDetailsSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                            serviceOfferingSearch.entity().getId(), maxMemoryDetailsSearch.entity().getResourceId(),
                            maxMemoryDetailsSearch.entity().getName(), serviceOfferingSearch.entity().setString("maxmemory"));

                    serviceOfferingSearch.and().op("vmMemory", serviceOfferingSearch.entity().getRamSize(), Op.GTEQ);
                    serviceOfferingSearch.or().op("vmMemoryNull", serviceOfferingSearch.entity().getRamSize(), Op.NULL);
                    serviceOfferingSearch.and().op("maxMemoryDetailsSearch", "vmMaxMemoryNull", maxMemoryDetailsSearch.entity().getValue(), Op.NULL);
                    serviceOfferingSearch.or("maxMemoryDetailsSearch", "vmMaxMemoryGTEQ", maxMemoryDetailsSearch.entity().getValue(), Op.GTEQ).cp();

                    serviceOfferingSearch.cp().cp();
                }
                serviceOfferingSearch.and("dynamicScalingEnabled", serviceOfferingSearch.entity().isDynamicScalingEnabled(), SearchCriteria.Op.EQ);
            }
        }

        if ((accountMgr.isNormalUser(owner.getId()) || accountMgr.isDomainAdmin(owner.getId())) || owner.getType() == Account.Type.RESOURCE_DOMAIN_ADMIN) {
            // For non-root users.
            if (isSystem) {
                throw new InvalidParameterValueException("Only root admins can access system's offering");
            }
            if (isRecursive) { // domain + all sub-domains
                if (owner.getType() == Account.Type.NORMAL) {
                    throw new InvalidParameterValueException("Only ROOT admins and Domain admins can list service offerings with isrecursive=true");
                }
            }
        } else {
            // for root users
            if (owner.getDomainId() != 1 && isSystem) { // NON ROOT admin
                throw new InvalidParameterValueException("Non ROOT admins cannot access system's offering");
            }
            if (domainId != null && accountName == null) {
                SearchBuilder<ServiceOfferingDetailsVO> srvOffrDomainDetailSearch = _srvOfferingDetailsDao.createSearchBuilder();
                srvOffrDomainDetailSearch.and("domainId", srvOffrDomainDetailSearch.entity().getValue(), Op.EQ);
                serviceOfferingSearch.join("domainDetailSearch", srvOffrDomainDetailSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                        serviceOfferingSearch.entity().getId(), srvOffrDomainDetailSearch.entity().getResourceId(),
                        srvOffrDomainDetailSearch.entity().getName(), serviceOfferingSearch.entity().setString(ApiConstants.DOMAIN_ID));
            }
        }

        if (keyword != null) {
            serviceOfferingSearch.and().op("keywordName", serviceOfferingSearch.entity().getName(), SearchCriteria.Op.LIKE);
            serviceOfferingSearch.or("keywordDisplayText", serviceOfferingSearch.entity().getDisplayText(), SearchCriteria.Op.LIKE);
            serviceOfferingSearch.cp();
        }

        if (id != null) {
            serviceOfferingSearch.and("id", serviceOfferingSearch.entity().getId(), SearchCriteria.Op.EQ);
        }

        if (isSystem != null) {
            // note that for non-root users, isSystem is always false when
            // control comes to here
            serviceOfferingSearch.and("systemUse", serviceOfferingSearch.entity().isSystemUse(), SearchCriteria.Op.EQ);
        }

        if (name != null) {
            serviceOfferingSearch.and("name", serviceOfferingSearch.entity().getName(), SearchCriteria.Op.EQ);
        }

        if (vmTypeStr != null) {
            serviceOfferingSearch.and("svmType", serviceOfferingSearch.entity().getVmType(), SearchCriteria.Op.EQ);
        }
        DataCenterJoinVO zone = null;
        if (zoneId != null) {
            SearchBuilder<ServiceOfferingDetailsVO> srvOffrZoneDetailSearch = _srvOfferingDetailsDao.createSearchBuilder();
            srvOffrZoneDetailSearch.and().op("zoneId", srvOffrZoneDetailSearch.entity().getValue(), Op.EQ);
            srvOffrZoneDetailSearch.or("idNull", srvOffrZoneDetailSearch.entity().getId(), Op.NULL);
            srvOffrZoneDetailSearch.cp();

            serviceOfferingSearch.join("ZoneDetailSearch", srvOffrZoneDetailSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                    serviceOfferingSearch.entity().getId(), srvOffrZoneDetailSearch.entity().getResourceId(),
                    srvOffrZoneDetailSearch.entity().getName(), serviceOfferingSearch.entity().setString(ApiConstants.ZONE_ID));
            zone = _dcJoinDao.findById(zoneId);
        }

        if (encryptRoot != null || vmId != null || (zone != null && DataCenter.Type.Edge.equals(zone.getType()))) {
            SearchBuilder<DiskOfferingVO> diskOfferingSearch = _diskOfferingDao.createSearchBuilder();
            diskOfferingSearch.and("useLocalStorage", diskOfferingSearch.entity().isUseLocalStorage(), SearchCriteria.Op.EQ);
            diskOfferingSearch.and("encrypt", diskOfferingSearch.entity().getEncrypt(), SearchCriteria.Op.EQ);

            if (diskOffering != null) {
                List<String> storageTags = com.cloud.utils.StringUtils.csvTagsToList(diskOffering.getTags());
                if (!storageTags.isEmpty() && VolumeApiServiceImpl.MatchStoragePoolTagsWithDiskOffering.value()) {
                    for (String tag : storageTags) {
                        diskOfferingSearch.and("storageTag" + tag, diskOfferingSearch.entity().getTags(), Op.FIND_IN_SET);
                    }
                }
            }

            serviceOfferingSearch.join("diskOfferingSearch", diskOfferingSearch, JoinBuilder.JoinType.INNER, JoinBuilder.JoinCondition.AND,
                    serviceOfferingSearch.entity().getDiskOfferingId(), diskOfferingSearch.entity().getId(),
                    serviceOfferingSearch.entity().setString("Active"), diskOfferingSearch.entity().getState());
        }

        if (cpuNumber != null) {
            SearchBuilder<ServiceOfferingDetailsVO> maxComputeDetailsSearch = (SearchBuilder<ServiceOfferingDetailsVO>) serviceOfferingSearch.getJoinSB("maxComputeDetailsSearch");
            if (maxComputeDetailsSearch == null) {
                maxComputeDetailsSearch = _srvOfferingDetailsDao.createSearchBuilder();
                serviceOfferingSearch.join("maxComputeDetailsSearch", maxComputeDetailsSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                        serviceOfferingSearch.entity().getId(), maxComputeDetailsSearch.entity().getResourceId(),
                        maxComputeDetailsSearch.entity().getName(), serviceOfferingSearch.entity().setString(ApiConstants.MAX_CPU_NUMBER));
            }

            SearchBuilder<ServiceOfferingDetailsVO> minComputeDetailsSearch = _srvOfferingDetailsDao.createSearchBuilder();

            serviceOfferingSearch.join("minComputeDetailsSearch", minComputeDetailsSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                    serviceOfferingSearch.entity().getId(), minComputeDetailsSearch.entity().getResourceId(),
                    minComputeDetailsSearch.entity().getName(), serviceOfferingSearch.entity().setString(ApiConstants.MIN_CPU_NUMBER));

            /*
                (min_cpu IS NULL AND cpu IS NULL AND max_cpu IS NULL)
                OR (cpu = X)
                OR (min_cpu <= X AND max_cpu >= X)

                AND (
                    (min_compute_details.value is NULL AND cpu is NULL)
                    OR (min_compute_details.value is NULL AND cpu >= X)
                    OR min_compute_details.value >= X
                    OR (
                        ((min_compute_details.value is NULL AND cpu <= X) OR min_compute_details.value <= X)
                        AND ((max_compute_details.value is NULL AND cpu >= X) OR max_compute_details.value >= X)
                    )
                )
             */
            serviceOfferingSearch.and().op().op("minComputeDetailsSearch", "cpuConstraintMinComputeNull", minComputeDetailsSearch.entity().getValue(), Op.NULL);
            serviceOfferingSearch.and("cpuConstraintNull", serviceOfferingSearch.entity().getCpu(), Op.NULL).cp();

            serviceOfferingSearch.or().op("minComputeDetailsSearch", "cpuConstraintMinComputeNull", minComputeDetailsSearch.entity().getValue(), Op.NULL);
            serviceOfferingSearch.and("cpuNumber", serviceOfferingSearch.entity().getCpu(), Op.GTEQ).cp();
            serviceOfferingSearch.or("cpuNumber", serviceOfferingSearch.entity().getCpu(), Op.GTEQ);

            serviceOfferingSearch.or().op().op();
            serviceOfferingSearch.op("minComputeDetailsSearch", "cpuConstraintMinComputeNull", minComputeDetailsSearch.entity().getValue(), Op.NULL);
            serviceOfferingSearch.and("cpuNumber", serviceOfferingSearch.entity().getCpu(), Op.LTEQ).cp();
            serviceOfferingSearch.or("minComputeDetailsSearch", "cpuNumber", minComputeDetailsSearch.entity().getValue(), Op.LTEQ).cp();
            serviceOfferingSearch.and().op().op("maxComputeDetailsSearch", "cpuConstraintMaxComputeNull", maxComputeDetailsSearch.entity().getValue(), Op.NULL);
            serviceOfferingSearch.and("cpuNumber", serviceOfferingSearch.entity().getCpu(), Op.GTEQ).cp();
            serviceOfferingSearch.or("maxComputeDetailsSearch", "cpuNumber", maxComputeDetailsSearch.entity().getValue(), Op.GTEQ).cp();
            serviceOfferingSearch.cp().cp();
        }

        if (memory != null) {
            SearchBuilder<ServiceOfferingDetailsVO> maxMemoryDetailsSearch = (SearchBuilder<ServiceOfferingDetailsVO>) serviceOfferingSearch.getJoinSB("maxMemoryDetailsSearch");
            if (maxMemoryDetailsSearch == null) {
                maxMemoryDetailsSearch = _srvOfferingDetailsDao.createSearchBuilder();
                serviceOfferingSearch.join("maxMemoryDetailsSearch", maxMemoryDetailsSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                        serviceOfferingSearch.entity().getId(), maxMemoryDetailsSearch.entity().getResourceId(),
                        maxMemoryDetailsSearch.entity().getName(), serviceOfferingSearch.entity().setString("maxmemory"));
            }

            SearchBuilder<ServiceOfferingDetailsVO> minMemoryDetailsSearch = _srvOfferingDetailsDao.createSearchBuilder();

            serviceOfferingSearch.join("minMemoryDetailsSearch", minMemoryDetailsSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                    serviceOfferingSearch.entity().getId(), minMemoryDetailsSearch.entity().getResourceId(),
                    minMemoryDetailsSearch.entity().getName(), serviceOfferingSearch.entity().setString("minmemory"));

            /*
                (min_ram_size IS NULL AND ram_size IS NULL AND max_ram_size IS NULL)
                OR (ram_size = X)
                OR (min_ram_size <= X AND max_ram_size >= X)
             */

            serviceOfferingSearch.and().op().op("minMemoryDetailsSearch", "memoryConstraintMinMemoryNull", minMemoryDetailsSearch.entity().getValue(), Op.NULL);
            serviceOfferingSearch.and("memoryConstraintNull", serviceOfferingSearch.entity().getRamSize(), Op.NULL).cp();

            serviceOfferingSearch.or().op("minMemoryDetailsSearch", "memoryConstraintMinMemoryNull", minMemoryDetailsSearch.entity().getValue(), Op.NULL);
            serviceOfferingSearch.and("memory", serviceOfferingSearch.entity().getRamSize(), Op.GTEQ).cp();
            serviceOfferingSearch.or("memory", serviceOfferingSearch.entity().getRamSize(), Op.GTEQ);

            serviceOfferingSearch.or().op().op();
            serviceOfferingSearch.op("minMemoryDetailsSearch", "memoryConstraintMinMemoryNull", minMemoryDetailsSearch.entity().getValue(), Op.NULL);
            serviceOfferingSearch.and("memory", serviceOfferingSearch.entity().getRamSize(), Op.LTEQ).cp();
            serviceOfferingSearch.or("minMemoryDetailsSearch", "memory", minMemoryDetailsSearch.entity().getValue(), Op.LTEQ).cp();
            serviceOfferingSearch.and().op().op("maxMemoryDetailsSearch", "memoryConstraintMaxMemoryNull", maxMemoryDetailsSearch.entity().getValue(), Op.NULL);
            serviceOfferingSearch.and("memory", serviceOfferingSearch.entity().getRamSize(), Op.GTEQ).cp();
            serviceOfferingSearch.or("maxMemoryDetailsSearch", "memory", maxMemoryDetailsSearch.entity().getValue(), Op.GTEQ).cp();
            serviceOfferingSearch.cp().cp();
        }

        if (cpuSpeed != null) {
            serviceOfferingSearch.and().op("speedNull", serviceOfferingSearch.entity().getSpeed(), Op.NULL);
            serviceOfferingSearch.or("speedGTEQ", serviceOfferingSearch.entity().getSpeed(), Op.GTEQ);
            serviceOfferingSearch.cp();
        }

        // Filter offerings that are not associated with caller's domain
        // Fetch the offering ids from the details table since theres no smart way to filter them in the join ... yet!
        if (owner.getType() != Account.Type.ADMIN) {
            SearchBuilder<ServiceOfferingDetailsVO> srvOffrDomainDetailSearch = _srvOfferingDetailsDao.createSearchBuilder();
            srvOffrDomainDetailSearch.and().op("domainIdIN", srvOffrDomainDetailSearch.entity().getValue(), Op.IN);
            srvOffrDomainDetailSearch.or("idNull", srvOffrDomainDetailSearch.entity().getValue(), Op.NULL);
            srvOffrDomainDetailSearch.cp();
            serviceOfferingSearch.join("domainDetailSearchNormalUser", srvOffrDomainDetailSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                    serviceOfferingSearch.entity().getId(), srvOffrDomainDetailSearch.entity().getResourceId(),
                    srvOffrDomainDetailSearch.entity().getName(), serviceOfferingSearch.entity().setString(ApiConstants.DOMAIN_ID));
        }

        List<String> hostTags = new ArrayList<>();
        if (currentVmOffering != null) {
            hostTags.addAll(com.cloud.utils.StringUtils.csvTagsToList(currentVmOffering.getHostTag()));
            if (UserVmManager.AllowDifferentHostTagsOfferingsForVmScale.value()) {
                addVmCurrentClusterHostTags(vmInstance, hostTags);
            }
        }

        if (!hostTags.isEmpty()) {
            serviceOfferingSearch.and().op("hostTag", serviceOfferingSearch.entity().getHostTag(), Op.NULL);
            serviceOfferingSearch.or();
            boolean flag = true;
            for(String tag : hostTags) {
                if (flag) {
                    flag = false;
                    serviceOfferingSearch.op("hostTag" + tag, serviceOfferingSearch.entity().getHostTag(), Op.FIND_IN_SET);
                } else {
                    serviceOfferingSearch.or("hostTag" + tag, serviceOfferingSearch.entity().getHostTag(), Op.FIND_IN_SET);
                }
            }
            serviceOfferingSearch.cp().cp();
        }

        SearchCriteria<ServiceOfferingVO> sc = serviceOfferingSearch.create();
        if (state != null) {
            sc.setParameters("state", state);
        }

        if (vgpuProfileId != null) {
            sc.setParameters("vgpuProfileId", vgpuProfileId);
        }

        if (vmId != null) {
            if (!currentVmOffering.isDynamic()) {
                sc.setParameters("idNEQ", currentVmOffering.getId());
            }

            if (currentVmOffering.getDiskOfferingStrictness()) {
                sc.setParameters("diskOfferingId", currentVmOffering.getDiskOfferingId());
                sc.setParameters("diskOfferingStrictness", true);
            } else {
                sc.setParameters("diskOfferingStrictness", false);
            }

            boolean isRootVolumeUsingLocalStorage = virtualMachineManager.isRootVolumeOnLocalStorage(vmId);

            // 1. Only return offerings with the same storage type than the storage pool where the VM's root volume is allocated
            sc.setJoinParameters("diskOfferingSearch", "useLocalStorage", isRootVolumeUsingLocalStorage);

            // 2.In case vm is running return only offerings greater than equal to current offering compute and offering's dynamic scalability should match
            if (vmInstance.getState() == VirtualMachine.State.Running) {
                Integer vmCpu = currentVmOffering.getCpu();
                Integer vmMemory = currentVmOffering.getRamSize();
                Integer vmSpeed = currentVmOffering.getSpeed();
                if ((vmCpu == null || vmMemory == null || vmSpeed == null) && VirtualMachine.Type.User.equals(vmInstance.getType())) {
                    UserVmVO userVmVO = userVmDao.findById(vmId);
                    userVmDao.loadDetails(userVmVO);
                    Map<String, String> details = userVmVO.getDetails();
                    vmCpu = NumbersUtil.parseInt(details.get(ApiConstants.CPU_NUMBER), 0);
                    if (vmSpeed == null) {
                        vmSpeed = NumbersUtil.parseInt(details.get(ApiConstants.CPU_SPEED), 0);
                    }
                    vmMemory = NumbersUtil.parseInt(details.get(ApiConstants.MEMORY), 0);
                }
                if (vmCpu != null && vmCpu > 0) {
                    sc.setParameters("vmCpu", vmCpu);
                    sc.setParameters("vmMaxComputeGTEQ", vmCpu);
                }
                if (vmSpeed != null && vmSpeed > 0) {
                    sc.setParameters("speedGTEQ", vmSpeed);
                }
                if (vmMemory != null && vmMemory > 0) {
                    sc.setParameters("vmMemory", vmMemory);
                    sc.setParameters("vmMaxMemoryGTEQ", vmMemory);
                }
                sc.setParameters("dynamicScalingEnabled", currentVmOffering.isDynamicScalingEnabled());
            }
        }

        if ((!accountMgr.isNormalUser(caller.getId()) && !accountMgr.isDomainAdmin(caller.getId())) && caller.getType() != Account.Type.RESOURCE_DOMAIN_ADMIN) {
            if (domainId != null && accountName == null) {
                sc.setJoinParameters("domainDetailSearch", "domainId", domainId);
            }
        }

        if (keyword != null) {
            sc.setParameters("keywordName", "%" + keyword + "%");
            sc.setParameters("keywordDisplayText", "%" + keyword + "%");
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (isSystem != null) {
            // note that for non-root users, isSystem is always false when
            // control comes to here
            sc.setParameters("systemUse", isSystem);
        }

        if (encryptRoot != null) {
            sc.setJoinParameters("diskOfferingSearch", "encrypt", encryptRoot);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (vmTypeStr != null) {
            sc.setParameters("svmType", vmTypeStr);
        }

        useStorageType(sc, storageType);

        if (zoneId != null) {
            sc.setJoinParameters("ZoneDetailSearch", "zoneId", zoneId);

            if (DataCenter.Type.Edge.equals(zone.getType())) {
                sc.setJoinParameters("diskOfferingSearch", "useLocalStorage", true);
            }
        }

        if (cpuNumber != null) {
            sc.setParameters("cpuNumber", cpuNumber);
        }

        if (memory != null) {
            sc.setParameters("memory", memory);
        }

        if (cpuSpeed != null) {
            sc.setParameters("speedGTEQ", cpuSpeed);
        }

        if (owner.getType() != Account.Type.ADMIN) {
            Domain callerDomain = _domainDao.findById(owner.getDomainId());
            List<Long> domainIds = findRelatedDomainIds(callerDomain, isRecursive);

            sc.setJoinParameters("domainDetailSearchNormalUser", "domainIdIN", domainIds.toArray());
        }

        if (diskOffering != null) {
            List<String> storageTags = com.cloud.utils.StringUtils.csvTagsToList(diskOffering.getTags());
            if (!storageTags.isEmpty() && VolumeApiServiceImpl.MatchStoragePoolTagsWithDiskOffering.value()) {
                for (String tag : storageTags) {
                    sc.setJoinParameters("diskOfferingSearch", "storageTag" + tag, tag);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(hostTags)) {
            for (String tag : hostTags) {
                sc.setParameters("hostTag" + tag, tag);
            }
        }

        Pair<List<ServiceOfferingVO>, Integer> uniquePair = _srvOfferingDao.searchAndCount(sc, searchFilter);
        Integer count = uniquePair.second();
        List<Long> offeringIds = uniquePair.first().stream().map(ServiceOfferingVO::getId).collect(Collectors.toList());
        return new Pair<>(offeringIds, count);
    }

    @Override
    public void addVmCurrentClusterHostTags(VMInstanceVO vmInstance, List<String> hostTags) {
        if (vmInstance == null) {
            return;
        }
        Long hostId = vmInstance.getHostId() == null ? vmInstance.getLastHostId() : vmInstance.getHostId();
        if (hostId == null) {
            return;
        }
        HostVO host = hostDao.findById(hostId);
        if (host == null) {
            logger.warn("Unable to find host with id " + hostId);
            return;
        }
        List<String> clusterTags = _hostTagDao.listByClusterId(host.getClusterId());
        if (CollectionUtils.isEmpty(clusterTags)) {
            logger.debug("No host tags defined for hosts in the cluster " + host.getClusterId());
            return;
        }
        Set<String> existingTagsSet = new HashSet<>(hostTags);
        clusterTags.stream()
                .filter(tag -> !existingTagsSet.contains(tag))
                .forEach(hostTags::add);
    }

    private boolean isPermissible(Long accountDomainId, Long offeringDomainId) {
        if (accountDomainId.equals(offeringDomainId)) {
            return true;
        }

        DomainVO domainRecord = _domainDao.findById(accountDomainId);
        if (domainRecord != null) {
            while (true) {
                if (domainRecord.getId() == offeringDomainId) {
                    return true;
                }

                if (domainRecord.getParent() != null) {
                    domainRecord = _domainDao.findById(domainRecord.getParent());
                } else {
                    break;
                }
            }
        }

        return false;
    }
}
