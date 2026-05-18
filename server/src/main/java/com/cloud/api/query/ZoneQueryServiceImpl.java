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

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.command.admin.zone.ListZonesCmdByAdmin;
import org.apache.cloudstack.api.command.user.zone.ListZonesCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.ZoneResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.query.QueryService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.jetbrains.annotations.NotNull;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.DataCenterJoinDao;
import com.cloud.api.query.vo.DataCenterJoinVO;
import com.cloud.dc.DedicatedResourceVO;
import com.cloud.domain.DomainVO;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.CloudAuthenticationException;
import com.cloud.org.Grouping;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;

/**
 * Implementation of zone query helpers extracted from {@link QueryManagerImpl}
 * as part of the Phase 4 Spring-component decomposition (slice 9).
 *
 * @see ZoneQueryService
 */
@Component
public class ZoneQueryServiceImpl extends MutualExclusiveIdsManagerBase implements ZoneQueryService {

    protected static final Logger logger = LogManager.getLogger(ZoneQueryServiceImpl.class);

    @Inject
    private DataCenterJoinDao _dcJoinDao;

    @Inject
    private DomainDao _domainDao;

    @Inject
    private DedicatedResourceDao _dedicatedDao;

    @Inject
    private ResourceTagDao resourceTagDao;

    @Inject
    private DomainRouterDao _routerDao;

    @Inject
    private AccountManager accountMgr;

    @Override
    public ListResponse<ZoneResponse> listDataCenters(ListZonesCmd cmd) {
        Pair<List<DataCenterJoinVO>, Integer> result = listDataCentersInternal(cmd);
        ListResponse<ZoneResponse> response = new ListResponse<>();

        ResponseView respView = ResponseView.Restricted;
        if (cmd instanceof ListZonesCmdByAdmin || CallContext.current().getCallingAccount().getType() == Account.Type.ADMIN) {
            respView = ResponseView.Full;
        }

        List<ZoneResponse> dcResponses = ViewResponseHelper.createDataCenterResponse(respView, cmd.getShowCapacities(), cmd.getShowIcon(), result.first().toArray(new DataCenterJoinVO[0]));
        response.setResponses(dcResponses, result.second());
        return response;
    }

    @Override
    public ListResponse<ZoneResponse> listDataCentersWithMinimalResponse(ListZonesCmd cmd) {
        Pair<List<DataCenterJoinVO>, Integer> result = listDataCentersInternal(cmd);
        ListResponse<ZoneResponse> response = new ListResponse<>();

        ResponseView respView = ResponseView.Restricted;
        if (cmd instanceof ListZonesCmdByAdmin || CallContext.current().getCallingAccount().getType() == Account.Type.ADMIN) {
            respView = ResponseView.Full;
        }

        List<ZoneResponse> dcResponses = ViewResponseHelper.createMinimalDataCenterResponse(respView, result.first().toArray(new DataCenterJoinVO[0]));
        response.setResponses(dcResponses, result.second());
        return response;
    }

    private Pair<List<DataCenterJoinVO>, Integer> listDataCentersInternal(ListZonesCmd cmd) {
        Account account = CallContext.current().getCallingAccount();
        Long domainId = cmd.getDomainId();
        Long zoneId = cmd.getId();
        if( ! QueryService.AllowUserViewAllDataCenters.valueInScope(ConfigKey.Scope.Domain, account.getDomainId())) {
            zoneId = accountMgr.checkAccessAndSpecifyAuthority(CallContext.current().getCallingAccount(), zoneId);
            logger.debug("not allowing users to view all zones ; selected zone is = {}", zoneId);
        }
        List<Long> ids = getIdsListFromCmd(cmd.getId(), cmd.getIds());
        String keyword = cmd.getKeyword();
        String name = cmd.getName();
        String networkType = cmd.getNetworkType();
        Map<String, String> resourceTags = cmd.getTags();
        String storageAccessGroup = cmd.getStorageAccessGroup();

        SearchBuilder<DataCenterJoinVO> sb = _dcJoinDao.createSearchBuilder();
        if (resourceTags != null && !resourceTags.isEmpty()) {
            SearchBuilder<ResourceTagVO> tagSearch = resourceTagDao.createSearchBuilder();
            for (int count = 0; count < resourceTags.size(); count++) {
                tagSearch.or().op("key" + count, tagSearch.entity().getKey(), SearchCriteria.Op.EQ);
                tagSearch.and("value" + count, tagSearch.entity().getValue(), SearchCriteria.Op.EQ);
                tagSearch.cp();
            }
            tagSearch.and("resourceType", tagSearch.entity().getResourceType(), SearchCriteria.Op.EQ);
            sb.groupBy(sb.entity().getId());
            sb.join("tagSearch", tagSearch, sb.entity().getId(), tagSearch.entity().getResourceId(), JoinBuilder.JoinType.INNER);
        }
        if (storageAccessGroup != null) {
            sb.and().op("storageAccessGroupExact", sb.entity().getStorageAccessGroups(), Op.EQ);
            sb.or("storageAccessGroupPrefix", sb.entity().getStorageAccessGroups(), Op.LIKE);
            sb.or("storageAccessGroupSuffix", sb.entity().getStorageAccessGroups(), Op.LIKE);
            sb.or("storageAccessGroupMiddle", sb.entity().getStorageAccessGroups(), Op.LIKE);
            sb.cp();
        }

        Filter searchFilter = new Filter(DataCenterJoinVO.class, "sortKey", QueryService.SortKeyAscending.value(), cmd.getStartIndex(), cmd.getPageSizeVal());
        searchFilter.addOrderBy(DataCenterJoinVO.class, "id", true);
        SearchCriteria<DataCenterJoinVO> sc = sb.create();

        if (networkType != null) {
            sc.addAnd("networkType", SearchCriteria.Op.EQ, networkType);
        }

        if (CollectionUtils.isNotEmpty(ids)) {
            sc.addAnd("id", SearchCriteria.Op.IN, ids.toArray());
        }

        if (zoneId != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, zoneId);
        } else if (name != null) {
            sc.addAnd("name", SearchCriteria.Op.EQ, name);
        } else {
            if (keyword != null) {
                SearchCriteria<DataCenterJoinVO> ssc = _dcJoinDao.createSearchCriteria();
                ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
                ssc.addOr("description", SearchCriteria.Op.LIKE, "%" + keyword + "%");
                sc.addAnd("name", SearchCriteria.Op.SC, ssc);
            }

            buildSearchCriteriaForOwnedExplicitlyDedicatedResources(domainId, sc, account);

            // handle available=FALSE option, only return zones with at least
            // one VM running there
            Boolean available = cmd.isAvailable();
            if (account != null) {
                if (Boolean.FALSE.equals(available)) {
                    Set<Long> dcIds = new HashSet<>(); // data centers with
                    // at least one VM
                    // running
                    List<DomainRouterVO> routers = _routerDao.listBy(account.getId());
                    for (DomainRouterVO router : routers) {
                        dcIds.add(router.getDataCenterId());
                    }
                    if (dcIds.isEmpty()) {
                        return new Pair<>(new ArrayList<>(), 0);
                    } else {
                        sc.addAnd("id", SearchCriteria.Op.IN, dcIds.toArray());
                    }

                }
            }
        }

        buildSearchCriteriaForTags(resourceTags, sc);

        if (storageAccessGroup != null) {
            sc.setParameters("storageAccessGroupExact", storageAccessGroup);
            sc.setParameters("storageAccessGroupPrefix", storageAccessGroup + ",%");
            sc.setParameters("storageAccessGroupSuffix", "%," + storageAccessGroup);
            sc.setParameters("storageAccessGroupMiddle", "%," + storageAccessGroup + ",%");
        }

        return _dcJoinDao.searchAndCount(sc, searchFilter);
    }

    private static void buildSearchCriteriaForTags(Map<String, String> resourceTags, SearchCriteria<DataCenterJoinVO> sc) {
        if (resourceTags != null && !resourceTags.isEmpty()) {
            int count = 0;
            sc.setJoinParameters("tagSearch", "resourceType", ResourceObjectType.Zone.toString());
            for (Map.Entry<String, String> entry : resourceTags.entrySet()) {
                sc.setJoinParameters("tagSearch", "key" + count, entry.getKey());
                sc.setJoinParameters("tagSearch", "value" + count, entry.getValue());
                count++;
            }
        }
    }

    /**
     * List all resources due to Explicit Dedication except the
     * dedicated resources of other account
     */
    private void buildSearchCriteriaForOwnedExplicitlyDedicatedResources(Long domainId, SearchCriteria<DataCenterJoinVO> sc, Account account) {
        if (domainId != null) {
            buildSearchCriteriaForZonesBelongingToDomain(domainId, sc, account);
        } else if (accountMgr.isNormalUser(account.getId())) {
            buildSearchCriteriaForUserDomainAndAbove(sc, account);
        } else if (accountMgr.isDomainAdmin(account.getId()) || accountMgr.isResourceDomainAdmin(account.getId())) {
            buildSearchCriteriaForDomainAdmins(sc, account);
        }
    }

    /**
     * Return all zones for the domain admin, and everything above till root, as well as zones till the domain leaf
     */
    private void buildSearchCriteriaForDomainAdmins(SearchCriteria<DataCenterJoinVO> sc, Account account) {
        List<Long> domainIds = new ArrayList<>();
        DomainVO domainRecord = getDomainForAccount(account);
        logger.trace("adding caller's domain {} to the list of domains to search for zones", account.getDomainId());
        domainIds.add(domainRecord.getId());
        // find all domain Ids till leaf
        List<DomainVO> allChildDomains = _domainDao.findAllChildren(domainRecord.getPath(), domainRecord.getId());
        for (DomainVO domain : allChildDomains) {
            logger.trace("adding caller domain's child {} to the list of domains to search for zones", domain.getId());
            domainIds.add(domain.getId());
        }
        // then find all domain Id up to root domain for this account
        while (domainRecord.getParent() != null) {
            domainRecord = _domainDao.findById(domainRecord.getParent());
            logger.trace("adding caller domain's ancestor {} to the list of domains to search for zones", domainRecord.getId());
            domainIds.add(domainRecord.getId());
        }

        // so search for domainId == null (public zones) or domainId this user has access to
        SearchCriteria<DataCenterJoinVO> sdc = _dcJoinDao.createSearchCriteria();
        sdc.addOr("domainId", Op.IN, domainIds.toArray());
        sdc.addOr("domainId", Op.NULL);
        sc.addAnd("domainId", Op.SC, sdc);

        // remove disabled zones
        sc.addAnd("allocationState", Op.NEQ, Grouping.AllocationState.Disabled);

        // remove Dedicated zones not dedicated to this domainId or
        // subdomainId
        List<Long> dedicatedZoneIds = removeDedicatedZoneNotSuitable(domainIds);
        if (!dedicatedZoneIds.isEmpty()) {
            sdc.addAnd("id", Op.NIN, dedicatedZoneIds.toArray(new Object[0]));
        }
    }

    @NotNull
    private DomainVO getDomainForAccount(Account account) {
        DomainVO domainRecord = _domainDao.findById(account.getDomainId());
        if (domainRecord == null) {
            logger.error("Could not find the domainId for account: {}", account);
            throw new CloudAuthenticationException("Could not find the domainId for account:" + account.getAccountName());
        }
        return domainRecord;
    }

    /**
     * Return all zones for the user's domain, and everything above till root
     * list all zones belonging to this domain, and all of its parents
     * check the parent, if not null, add zones for that parent to list
     */
    private void buildSearchCriteriaForUserDomainAndAbove(SearchCriteria<DataCenterJoinVO> sc, Account account) {

        // find all domain Id up to root domain for this account
        List<Long> domainIds = new ArrayList<>();
        DomainVO domainRecord = getDomainForAccount(account);
        domainIds.add(domainRecord.getId());
        while (domainRecord.getParent() != null) {
            domainRecord = _domainDao.findById(domainRecord.getParent());
            domainIds.add(domainRecord.getId());
        }
        // domainId == null (public zones) or domainId IN [all domain id
        // up to root domain]
        SearchCriteria<DataCenterJoinVO> sdc = _dcJoinDao.createSearchCriteria();
        sdc.addOr("domainId", Op.IN, domainIds.toArray());
        sdc.addOr("domainId", Op.NULL);
        sc.addAnd("domainId", Op.SC, sdc);

        // remove disabled zones
        sc.addAnd("allocationState", Op.NEQ, Grouping.AllocationState.Disabled);

        // accountId == null (zones dedicated to a domain) or
        // accountId = caller
        SearchCriteria<DataCenterJoinVO> sdc2 = _dcJoinDao.createSearchCriteria();
        sdc2.addOr("accountId", Op.EQ, account.getId());
        sdc2.addOr("accountId", Op.NULL);

        sc.addAnd("accountId", Op.SC, sdc2);

        // remove Dedicated zones not dedicated to this domainId or
        // subdomainId
        List<Long> dedicatedZoneIds = removeDedicatedZoneNotSuitable(domainIds);
        if (!dedicatedZoneIds.isEmpty()) {
            sdc.addAnd("id", Op.NIN, dedicatedZoneIds.toArray(new Object[0]));
        }
    }

    private void buildSearchCriteriaForZonesBelongingToDomain(Long domainId, SearchCriteria<DataCenterJoinVO> sc, Account account) {
        // for domainId != null // right now, we made the decision to
        // only list zones associated // with this domain, private zone
        sc.addAnd("domainId", Op.EQ, domainId);

        if (accountMgr.isNormalUser(account.getId())) {
            // accountId == null (zones dedicated to a domain) or
            // accountId = caller
            SearchCriteria<DataCenterJoinVO> sdc = _dcJoinDao.createSearchCriteria();
            sdc.addOr("accountId", Op.EQ, account.getId());
            sdc.addOr("accountId", Op.NULL);

            sc.addAnd("accountId", Op.SC, sdc);
        }
    }

    private List<Long> removeDedicatedZoneNotSuitable(List<Long> domainIds) {
        // remove dedicated zone of other domain
        List<Long> dedicatedZoneIds = new ArrayList<>();
        List<DedicatedResourceVO> dedicatedResources = _dedicatedDao.listZonesNotInDomainIds(domainIds);
        for (DedicatedResourceVO dr : dedicatedResources) {
            if (dr != null) {
                logger.trace("adding zone to exclude from callers list zones result: {}.", dr.getDataCenterId());
                dedicatedZoneIds.add(dr.getDataCenterId());
            }
        }
        return dedicatedZoneIds;
    }
}
