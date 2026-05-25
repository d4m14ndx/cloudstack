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
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.domain.ListDomainsCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.collections.MapUtils;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.DomainJoinDao;
import com.cloud.api.query.vo.DomainJoinVO;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;
import com.cloud.utils.db.SearchCriteria.Op;

/**
 * Implementation of the {@code listDomains} search helpers extracted from
 * {@link QueryManagerImpl}.
 *
 * @see DomainQueryService
 */
@Component
public class DomainQueryServiceImpl implements DomainQueryService {

    @Inject
    private AccountManager accountMgr;
    @Inject
    private DomainDao domainDao;
    @Inject
    private DomainJoinDao domainJoinDao;
    @Inject
    private ResourceTagDao resourceTagDao;

    @Override
    public Pair<List<DomainJoinVO>, Integer> searchForDomainsInternal(ListDomainsCmd cmd) {
        Pair<List<Long>, Integer> domainIdPage = searchForDomainIdsAndCount(cmd);

        Integer count = domainIdPage.second();
        Long[] idArray = domainIdPage.first().toArray(new Long[0]);

        if (count == 0) {
            return new Pair<>(new ArrayList<>(), count);
        }

        List<DomainJoinVO> domains = domainJoinDao.searchByIds(idArray);
        return new Pair<>(domains, count);
    }

    protected Pair<List<Long>, Integer> searchForDomainIdsAndCount(ListDomainsCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Long domainId = cmd.getId();
        boolean listAll = cmd.listAll();
        boolean isRecursive = false;
        Domain domain = null;
        Map<String, String> tags = cmd.getTags();

        if (domainId != null) {
            domain = domainDao.findById(domainId);
            if (domain == null) {
                throw new InvalidParameterValueException("Domain id=" + domainId + " doesn't exist");
            }
            accountMgr.checkAccess(caller, domain);
        } else {
            if (caller.getType() != Account.Type.ADMIN) {
                domainId = caller.getDomainId();
            }
            if (listAll) {
                isRecursive = true;
            }
        }

        Filter searchFilter = new Filter(DomainVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        String domainName = cmd.getDomainName();
        Integer level = cmd.getLevel();
        Object keyword = cmd.getKeyword();

        SearchBuilder<DomainVO> domainSearchBuilder = domainDao.createSearchBuilder();
        domainSearchBuilder.select(null, Func.DISTINCT, domainSearchBuilder.entity().getId()); // select distinct
        domainSearchBuilder.and("id", domainSearchBuilder.entity().getId(), SearchCriteria.Op.EQ);
        domainSearchBuilder.and("name", domainSearchBuilder.entity().getName(), SearchCriteria.Op.EQ);
        domainSearchBuilder.and("level", domainSearchBuilder.entity().getLevel(), SearchCriteria.Op.EQ);
        domainSearchBuilder.and("path", domainSearchBuilder.entity().getPath(), SearchCriteria.Op.LIKE);
        domainSearchBuilder.and("state", domainSearchBuilder.entity().getState(), SearchCriteria.Op.EQ);

        if (MapUtils.isNotEmpty(tags)) {
            SearchBuilder<ResourceTagVO> resourceTagSearch = resourceTagDao.createSearchBuilder();
            resourceTagSearch.and("resourceType", resourceTagSearch.entity().getResourceType(), Op.EQ);
            resourceTagSearch.and().op();
            for (int count = 0; count < tags.size(); count++) {
                if (count == 0) {
                    resourceTagSearch.op("tagKey" + count, resourceTagSearch.entity().getKey(), Op.EQ);
                } else {
                    resourceTagSearch.or().op("tagKey" + count, resourceTagSearch.entity().getKey(), Op.EQ);
                }
                resourceTagSearch.and("tagValue" + count, resourceTagSearch.entity().getValue(), Op.EQ);
                resourceTagSearch.cp();
            }
            resourceTagSearch.cp();

            domainSearchBuilder.join("tags", resourceTagSearch, resourceTagSearch.entity().getResourceId(), domainSearchBuilder.entity().getId(), JoinBuilder.JoinType.INNER);
        }

        if (keyword != null) {
            domainSearchBuilder.and("keywordName", domainSearchBuilder.entity().getName(), SearchCriteria.Op.LIKE);
        }

        SearchCriteria<DomainVO> sc = domainSearchBuilder.create();

        if (keyword != null) {
            sc.setParameters("keywordName", "%" + keyword + "%");
        }

        if (domainName != null) {
            sc.setParameters("name", domainName);
        }

        if (level != null) {
            sc.setParameters("level", level);
        }

        if (domainId != null) {
            if (isRecursive) {
                if (domain == null) {
                    domain = domainDao.findById(domainId);
                }
                sc.setParameters("path", domain.getPath() + "%");
            } else {
                sc.setParameters("id", domainId);
            }
        }

        if (MapUtils.isNotEmpty(tags)) {
            int count = 0;
            sc.setJoinParameters("tags", "resourceType", ResourceObjectType.Domain);
            for (Map.Entry<String, String> entry  : tags.entrySet()) {
                sc.setJoinParameters("tags", "tagKey" + count, entry.getKey());
                sc.setJoinParameters("tags", "tagValue" + count, entry.getValue());
                count++;
            }
        }

        // return only Active domains to the API
        sc.setParameters("state", Domain.State.Active);

        Pair<List<DomainVO>, Integer> uniqueDomainPair = domainDao.searchAndCount(sc, searchFilter);
        Integer count = uniqueDomainPair.second();
        List<Long> domainIds = uniqueDomainPair.first().stream().map(DomainVO::getId).collect(Collectors.toList());
        return new Pair<>(domainIds, count);
    }
}
