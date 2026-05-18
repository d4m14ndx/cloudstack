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
import java.util.Arrays;
import java.util.List;
import java.util.ListIterator;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.user.offering.ListDiskOfferingsCmd;
import org.apache.cloudstack.api.response.DiskOfferingResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.resourcedetail.DiskOfferingDetailVO;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.DataCenterJoinDao;
import com.cloud.api.query.dao.DiskOfferingJoinDao;
import com.cloud.api.query.vo.DataCenterJoinVO;
import com.cloud.api.query.vo.DiskOfferingJoinVO;
import com.cloud.dc.DataCenter;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiServiceImpl;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;

@Component
public class DiskOfferingQueryServiceImpl implements DiskOfferingQueryService {

    @Inject
    private AccountManager accountMgr;

    @Inject
    private DiskOfferingJoinDao _diskOfferingJoinDao;

    @Inject
    private DiskOfferingDetailsDao _diskOfferingDetailsDao;

    @Inject
    private DiskOfferingDao _diskOfferingDao;

    @Inject
    private DataCenterJoinDao _dcJoinDao;

    @Inject
    private VolumeDao volumeDao;

    @Inject
    private DomainDao _domainDao;

    @Inject
    private StoragePoolTagsDao _storageTagDao;

    @Inject
    private UserVmDao userVmDao;

    @Override
    public ListResponse<DiskOfferingResponse> searchForDiskOfferings(ListDiskOfferingsCmd cmd) {
        Pair<List<DiskOfferingJoinVO>, Integer> result = searchForDiskOfferingsInternal(cmd);
        ListResponse<DiskOfferingResponse> response = new ListResponse<>();
        List<DiskOfferingResponse> offeringResponses = ViewResponseHelper.createDiskOfferingResponses(cmd.getVirtualMachineId(), result.first());
        response.setResponses(offeringResponses, result.second());
        return response;
    }

    private Pair<List<DiskOfferingJoinVO>, Integer> searchForDiskOfferingsInternal(ListDiskOfferingsCmd cmd) {
        Ternary<List<Long>, Integer, String[]> diskOfferingIdPage = searchForDiskOfferingsIdsAndCount(cmd);

        Integer count = diskOfferingIdPage.second();
        Long[] idArray = diskOfferingIdPage.first().toArray(new Long[0]);
        String[] requiredTagsArray = diskOfferingIdPage.third();

        if (count == 0) {
            return new Pair<>(new ArrayList<>(), count);
        }

        List<DiskOfferingJoinVO> diskOfferings = _diskOfferingJoinDao.searchByIds(idArray);
        if (requiredTagsArray.length == 0) {
            return new Pair<>(diskOfferings, count);
        }
        return filterDiskOfferingsByRequiredTags(diskOfferings, requiredTagsArray, count);
    }

    private Pair<List<DiskOfferingJoinVO>, Integer> filterDiskOfferingsByRequiredTags(List<DiskOfferingJoinVO> diskOfferings, String[] requiredTagsArray, int count) {
        ListIterator<DiskOfferingJoinVO> iteratorForTagsChecking = diskOfferings.listIterator();
        while (iteratorForTagsChecking.hasNext()) {
            DiskOfferingJoinVO offering = iteratorForTagsChecking.next();
            String offeringTags = offering.getTags();
            String[] offeringTagsArray = (offeringTags == null || offeringTags.isEmpty()) ? new String[0] : offeringTags.split(",");
            if (!CollectionUtils.isSubCollection(Arrays.asList(requiredTagsArray), Arrays.asList(offeringTagsArray))) {
                iteratorForTagsChecking.remove();
                count--;
            }
        }
        return new Pair<>(diskOfferings, count);
    }

    private Ternary<List<Long>, Integer, String[]> searchForDiskOfferingsIdsAndCount(ListDiskOfferingsCmd cmd) {
        Account account = CallContext.current().getCallingAccount();
        Object name = cmd.getDiskOfferingName();
        Object id = cmd.getId();
        Object keyword = cmd.getKeyword();
        Long domainId = cmd.getDomainId();
        boolean isRootAdmin = accountMgr.isRootAdmin(account.getAccountId());
        Long projectId = cmd.getProjectId();
        String accountName = cmd.getAccountName();
        boolean isRecursive = cmd.isRecursive();
        Long zoneId = cmd.getZoneId();
        Long volumeId = cmd.getVolumeId();
        Long storagePoolId = cmd.getStoragePoolId();
        Boolean encrypt = cmd.getEncrypt();
        String storageType = cmd.getStorageType();
        DiskOffering.State state = cmd.getState();
        final Long vmId = cmd.getVirtualMachineId();

        Filter searchFilter = new Filter(DiskOfferingVO.class, "sortKey", QueryManagerImpl.SortKeyAscending.value(), cmd.getStartIndex(), cmd.getPageSizeVal());
        searchFilter.addOrderBy(DiskOfferingVO.class, "id", true);
        SearchBuilder<DiskOfferingVO> diskOfferingSearch = _diskOfferingDao.createSearchBuilder();
        diskOfferingSearch.select(null, Func.DISTINCT, diskOfferingSearch.entity().getId());
        diskOfferingSearch.and("computeOnly", diskOfferingSearch.entity().isComputeOnly(), Op.EQ);

        if (state != null) {
            diskOfferingSearch.and("state", diskOfferingSearch.entity().getState(), Op.EQ);
        }

        if (domainId != null && accountName == null) {
            if (accountMgr.isRootAdmin(account.getId()) || isPermissible(account.getDomainId(), domainId)) {
                SearchBuilder<DiskOfferingDetailVO> domainDetailsSearch = _diskOfferingDetailsDao.createSearchBuilder();
                domainDetailsSearch.and("domainId", domainDetailsSearch.entity().getValue(), Op.EQ);

                diskOfferingSearch.join("domainDetailsSearch", domainDetailsSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                        diskOfferingSearch.entity().getId(), domainDetailsSearch.entity().getResourceId(),
                        domainDetailsSearch.entity().getName(), diskOfferingSearch.entity().setString(ApiConstants.DOMAIN_ID));

                if (!isRootAdmin) {
                    diskOfferingSearch.and("displayOffering", diskOfferingSearch.entity().getDisplayOffering(), Op.EQ);
                }

                SearchCriteria<DiskOfferingVO> sc = diskOfferingSearch.create();
                sc.setParameters("computeOnly", false);
                if (state != null) {
                    sc.setParameters("state", state);
                }

                sc.setJoinParameters("domainDetailsSearch", "domainId", domainId);

                Pair<List<DiskOfferingVO>, Integer> uniquePairs = _diskOfferingDao.searchAndCount(sc, searchFilter);
                List<Long> idsArray = uniquePairs.first().stream().map(DiskOfferingVO::getId).collect(Collectors.toList());
                return new Ternary<>(idsArray, uniquePairs.second(), new String[0]);
            }
            throw new PermissionDeniedException("The account:" + account.getAccountName() + " does not fall in the same domain hierarchy as the disk offering");
        }

        if ((accountMgr.isNormalUser(account.getId()) || accountMgr.isDomainAdmin(account.getId())) || account.getType() == Account.Type.RESOURCE_DOMAIN_ADMIN) {
            if (isRecursive) {
                if (account.getType() == Account.Type.NORMAL) {
                    throw new InvalidParameterValueException("Only ROOT admins and Domain admins can list disk offerings with isrecursive=true");
                }
            }
        }

        if (volumeId != null && storagePoolId != null) {
            throw new InvalidParameterValueException("Both volume ID and storage pool ID are not allowed at the same time");
        }

        if (keyword != null) {
            diskOfferingSearch.and().op("keywordDisplayText", diskOfferingSearch.entity().getDisplayText(), Op.LIKE);
            diskOfferingSearch.or("keywordName", diskOfferingSearch.entity().getName(), Op.LIKE);
            diskOfferingSearch.cp();
        }

        if (id != null) {
            diskOfferingSearch.and("id", diskOfferingSearch.entity().getId(), Op.EQ);
        }

        if (name != null) {
            diskOfferingSearch.and("name", diskOfferingSearch.entity().getName(), Op.EQ);
        }

        if (encrypt != null) {
            diskOfferingSearch.and("encrypt", diskOfferingSearch.entity().getEncrypt(), Op.EQ);
        }

        if (storageType != null || zoneId != null) {
            diskOfferingSearch.and("useLocalStorage", diskOfferingSearch.entity().isUseLocalStorage(), Op.EQ);
        }

        if (zoneId != null) {
            SearchBuilder<DiskOfferingDetailVO> zoneDetailSearch = _diskOfferingDetailsDao.createSearchBuilder();
            zoneDetailSearch.and().op("zoneId", zoneDetailSearch.entity().getValue(), Op.EQ);
            zoneDetailSearch.or("zoneIdNull", zoneDetailSearch.entity().getId(), Op.NULL);
            zoneDetailSearch.cp();

            diskOfferingSearch.join("zoneDetailSearch", zoneDetailSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                    diskOfferingSearch.entity().getId(), zoneDetailSearch.entity().getResourceId(),
                    zoneDetailSearch.entity().getName(), diskOfferingSearch.entity().setString(ApiConstants.ZONE_ID));
        }

        DiskOffering currentDiskOffering = null;
        Volume volume = null;
        if (volumeId != null) {
            volume = volumeDao.findById(volumeId);
            if (volume == null) {
                throw new InvalidParameterValueException(String.format("Unable to find a volume with specified id %s", volumeId));
            }
            currentDiskOffering = _diskOfferingDao.findByIdIncludingRemoved(volume.getDiskOfferingId());
            if (!currentDiskOffering.isComputeOnly() && currentDiskOffering.getDiskSizeStrictness()) {
                diskOfferingSearch.and().op("diskSize", diskOfferingSearch.entity().getDiskSize(), Op.EQ);
                diskOfferingSearch.or("customized", diskOfferingSearch.entity().isCustomized(), Op.EQ);
                diskOfferingSearch.cp();
            }
            diskOfferingSearch.and("idNEQ", diskOfferingSearch.entity().getId(), Op.NEQ);
            diskOfferingSearch.and("diskSizeStrictness", diskOfferingSearch.entity().getDiskSizeStrictness(), Op.EQ);
        }

        account = accountMgr.finalizeOwner(account, accountName, domainId, projectId);
        if (!Account.Type.ADMIN.equals(account.getType())) {
            SearchBuilder<DiskOfferingDetailVO> domainDetailsSearch = _diskOfferingDetailsDao.createSearchBuilder();
            domainDetailsSearch.and().op("domainIdIN", domainDetailsSearch.entity().getValue(), Op.IN);
            domainDetailsSearch.or("domainIdNull", domainDetailsSearch.entity().getId(), Op.NULL);
            domainDetailsSearch.cp();

            diskOfferingSearch.join("domainDetailsSearch", domainDetailsSearch, JoinBuilder.JoinType.LEFT, JoinBuilder.JoinCondition.AND,
                    diskOfferingSearch.entity().getId(), domainDetailsSearch.entity().getResourceId(),
                    domainDetailsSearch.entity().getName(), diskOfferingSearch.entity().setString(ApiConstants.DOMAIN_ID));
        }

        SearchCriteria<DiskOfferingVO> sc = diskOfferingSearch.create();
        sc.setParameters("computeOnly", false);

        if (state != null) {
            sc.setParameters("state", state);
        }

        if (keyword != null) {
            sc.setParameters("keywordDisplayText", "%" + keyword + "%");
            sc.setParameters("keywordName", "%" + keyword + "%");
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (encrypt != null) {
            sc.setParameters("encrypt", encrypt);
        }

        if (storageType != null) {
            if (storageType.equalsIgnoreCase(ServiceOffering.StorageType.local.toString())) {
                sc.setParameters("useLocalStorage", true);
            } else if (storageType.equalsIgnoreCase(ServiceOffering.StorageType.shared.toString())) {
                sc.setParameters("useLocalStorage", false);
            }
        }

        if (zoneId != null) {
            sc.setJoinParameters("zoneDetailSearch", "zoneId", zoneId);

            DataCenterJoinVO zone = _dcJoinDao.findById(zoneId);
            if (DataCenter.Type.Edge.equals(zone.getType())) {
                sc.setParameters("useLocalStorage", true);
            }
        }

        if (volumeId != null) {
            if (!currentDiskOffering.isComputeOnly() && currentDiskOffering.getDiskSizeStrictness()) {
                sc.setParameters("diskSize", volume.getSize());
                sc.setParameters("customized", true);
            }
            sc.setParameters("idNEQ", currentDiskOffering.getId());
            sc.setParameters("diskSizeStrictness", currentDiskOffering.getDiskSizeStrictness());
        }

        if (!Account.Type.ADMIN.equals(account.getType())) {
            Domain callerDomain = _domainDao.findById(account.getDomainId());
            List<Long> domainIds = findRelatedDomainIds(callerDomain, isRecursive);
            sc.setJoinParameters("domainDetailsSearch", "domainIdIN", domainIds.toArray());
        }

        if (vmId != null) {
            UserVmVO vm = userVmDao.findById(vmId);
            if (vm == null) {
                throw new InvalidParameterValueException("Unable to find the VM instance with the specified ID");
            }
            if (!isRootAdmin) {
                accountMgr.checkAccess(account, null, false, vm);
            }
        }

        Pair<List<DiskOfferingVO>, Integer> uniquePairs = _diskOfferingDao.searchAndCount(sc, searchFilter);
        String[] requiredTagsArray = new String[0];
        if (CollectionUtils.isNotEmpty(uniquePairs.first()) && VolumeApiServiceImpl.MatchStoragePoolTagsWithDiskOffering.valueIn(zoneId)) {
            if (volumeId != null) {
                requiredTagsArray = currentDiskOffering.getTagsArray();
            } else if (storagePoolId != null) {
                requiredTagsArray = _storageTagDao.getStoragePoolTags(storagePoolId).toArray(new String[0]);
            }
        }
        List<Long> idsArray = uniquePairs.first().stream().map(DiskOfferingVO::getId).collect(Collectors.toList());

        return new Ternary<>(idsArray, uniquePairs.second(), requiredTagsArray);
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
