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
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.apache.cloudstack.affinity.AffinityGroupDomainMapVO;
import org.apache.cloudstack.affinity.AffinityGroupVMMapVO;
import org.apache.cloudstack.affinity.dao.AffinityGroupDomainMapDao;
import org.apache.cloudstack.affinity.dao.AffinityGroupVMMapDao;
import org.apache.cloudstack.api.command.user.affinitygroup.ListAffinityGroupsCmd;
import org.apache.cloudstack.context.CallContext;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.AffinityGroupJoinDao;
import com.cloud.api.query.vo.AffinityGroupJoinVO;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.security.SecurityGroupVMMapVO;
import com.cloud.projects.Project;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.DomainManager;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;

/**
 * Implementation of the affinity-group listing helpers extracted from
 * {@link QueryManagerImpl}.
 *
 * @see AffinityGroupQueryService
 */
@Component
public class AffinityGroupQueryServiceImpl implements AffinityGroupQueryService {

    private static final String ID_FIELD = "id";

    @Inject
    private AccountManager accountMgr;
    @Inject
    private AccountDao accountDao;
    @Inject
    private DomainDao domainDao;
    @Inject
    private DomainManager domainMgr;
    @Inject
    private UserVmDao userVmDao;
    @Inject
    private AffinityGroupJoinDao affinityGroupJoinDao;
    @Inject
    private AffinityGroupVMMapDao affinityGroupVMMapDao;
    @Inject
    private AffinityGroupDomainMapDao affinityGroupDomainMapDao;

    @Override
    public Pair<List<AffinityGroupJoinVO>, Integer> searchForAffinityGroupsInternal(ListAffinityGroupsCmd cmd) {

        final Long affinityGroupId = cmd.getId();
        final String affinityGroupName = cmd.getAffinityGroupName();
        final String affinityGroupType = cmd.getAffinityGroupType();
        final Long vmId = cmd.getVirtualMachineId();
        final String accountName = cmd.getAccountName();
        Long domainId = cmd.getDomainId();
        final Long projectId = cmd.getProjectId();
        Boolean isRecursive = cmd.isRecursive();
        final boolean listAll = cmd.listAll();
        final Long startIndex = cmd.getStartIndex();
        final Long pageSize = cmd.getPageSizeVal();
        final String keyword = cmd.getKeyword();

        Account caller = CallContext.current().getCallingAccount();

        if (vmId != null) {
            UserVmVO userVM = userVmDao.findById(vmId);
            if (userVM == null) {
                throw new InvalidParameterValueException("Unable to list affinity groups for virtual machine instance " + vmId + "; instance not found.");
            }
            accountMgr.checkAccess(caller, null, true, userVM);
            return listAffinityGroupsByVM(vmId, startIndex, pageSize);
        }

        List<Long> permittedAccounts = new ArrayList<>();
        Ternary<Long, Boolean, ListProjectResourcesCriteria> ternary = new Ternary<>(domainId, isRecursive, null);

        accountMgr.buildACLSearchParameters(caller, affinityGroupId, accountName, projectId, permittedAccounts, ternary, listAll, false);

        domainId = ternary.first();
        isRecursive = ternary.second();
        ListProjectResourcesCriteria listProjectResourcesCriteria = ternary.third();

        Filter searchFilter = new Filter(AffinityGroupJoinVO.class, ID_FIELD, true, startIndex, pageSize);

        SearchCriteria<AffinityGroupJoinVO> sc = buildAffinityGroupSearchCriteria(domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria, affinityGroupId, affinityGroupName,
                affinityGroupType, keyword);

        Pair<List<AffinityGroupJoinVO>, Integer> uniqueGroupsPair = affinityGroupJoinDao.searchAndCount(sc, searchFilter);

        // search group details by ids
        List<AffinityGroupJoinVO> affinityGroups = new ArrayList<>();

        Integer count = uniqueGroupsPair.second();
        if (count != 0) {
            List<AffinityGroupJoinVO> uniqueGroups = uniqueGroupsPair.first();
            Long[] vrIds = new Long[uniqueGroups.size()];
            int i = 0;
            for (AffinityGroupJoinVO v : uniqueGroups) {
                vrIds[i++] = v.getId();
            }
            affinityGroups = affinityGroupJoinDao.searchByIds(vrIds);
        }

        if (!permittedAccounts.isEmpty()) {
            // add domain level affinity groups
            if (domainId != null) {
                SearchCriteria<AffinityGroupJoinVO> scDomain = buildAffinityGroupSearchCriteria(null, isRecursive, new ArrayList<>(), listProjectResourcesCriteria, affinityGroupId,
                        affinityGroupName, affinityGroupType, keyword);
                Pair<List<AffinityGroupJoinVO>, Integer> groupsPair = listDomainLevelAffinityGroups(scDomain, searchFilter, domainId);
                affinityGroups.addAll(groupsPair.first());
                count += groupsPair.second();
            } else {

                for (Long permAcctId : permittedAccounts) {
                    Account permittedAcct = accountDao.findById(permAcctId);
                    SearchCriteria<AffinityGroupJoinVO> scDomain = buildAffinityGroupSearchCriteria(null, isRecursive, new ArrayList<>(), listProjectResourcesCriteria, affinityGroupId,
                            affinityGroupName, affinityGroupType, keyword);
                    Pair<List<AffinityGroupJoinVO>, Integer> groupsPair = listDomainLevelAffinityGroups(scDomain, searchFilter, permittedAcct.getDomainId());
                    affinityGroups.addAll(groupsPair.first());
                    count += groupsPair.second();
                }
            }
        } else if (((permittedAccounts.isEmpty()) && (domainId != null) && isRecursive)) {
            // list all domain level affinity groups for the domain admin case
            SearchCriteria<AffinityGroupJoinVO> scDomain = buildAffinityGroupSearchCriteria(null, isRecursive, new ArrayList<>(), listProjectResourcesCriteria, affinityGroupId, affinityGroupName,
                    affinityGroupType, keyword);
            Pair<List<AffinityGroupJoinVO>, Integer> groupsPair = listDomainLevelAffinityGroups(scDomain, searchFilter, domainId);
            affinityGroups.addAll(groupsPair.first());
            count += groupsPair.second();
        }

        return new Pair<>(affinityGroups, count);

    }

    protected void buildAffinityGroupViewSearchBuilder(SearchBuilder<AffinityGroupJoinVO> sb, Long domainId, boolean isRecursive, List<Long> permittedAccounts,
            ListProjectResourcesCriteria listProjectResourcesCriteria) {

        sb.and("accountIdIN", sb.entity().getAccountId(), SearchCriteria.Op.IN);
        sb.and("domainId", sb.entity().getDomainId(), SearchCriteria.Op.EQ);

        if (((permittedAccounts.isEmpty()) && (domainId != null) && isRecursive)) {
            // if accountId isn't specified, we can do a domain match for the
            // admin case if isRecursive is true
            sb.and("domainPath", sb.entity().getDomainPath(), SearchCriteria.Op.LIKE);
        }

        if (listProjectResourcesCriteria != null) {
            if (listProjectResourcesCriteria == Project.ListProjectResourcesCriteria.ListProjectResourcesOnly) {
                sb.and("accountType", sb.entity().getAccountType(), SearchCriteria.Op.EQ);
            } else if (listProjectResourcesCriteria == Project.ListProjectResourcesCriteria.SkipProjectResources) {
                sb.and("accountType", sb.entity().getAccountType(), SearchCriteria.Op.NEQ);
            }
        }

    }

    protected void buildAffinityGroupViewSearchCriteria(SearchCriteria<AffinityGroupJoinVO> sc, Long domainId, boolean isRecursive, List<Long> permittedAccounts,
            ListProjectResourcesCriteria listProjectResourcesCriteria) {

        if (listProjectResourcesCriteria != null) {
            sc.setParameters("accountType", Account.Type.PROJECT);
        }

        if (!permittedAccounts.isEmpty()) {
            sc.setParameters("accountIdIN", permittedAccounts.toArray());
        } else if (domainId != null) {
            DomainVO domain = domainDao.findById(domainId);
            if (isRecursive) {
                sc.setParameters("domainPath", domain.getPath() + "%");
            } else {
                sc.setParameters("domainId", domainId);
            }
        }
    }

    protected SearchCriteria<AffinityGroupJoinVO> buildAffinityGroupSearchCriteria(Long domainId, boolean isRecursive, List<Long> permittedAccounts,
            ListProjectResourcesCriteria listProjectResourcesCriteria, Long affinityGroupId, String affinityGroupName, String affinityGroupType, String keyword) {

        SearchBuilder<AffinityGroupJoinVO> groupSearch = affinityGroupJoinDao.createSearchBuilder();
        buildAffinityGroupViewSearchBuilder(groupSearch, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        groupSearch.select(null, Func.DISTINCT, groupSearch.entity().getId()); // select
        // distinct

        SearchCriteria<AffinityGroupJoinVO> sc = groupSearch.create();
        buildAffinityGroupViewSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (affinityGroupId != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, affinityGroupId);
        }

        if (affinityGroupName != null) {
            sc.addAnd("name", SearchCriteria.Op.EQ, affinityGroupName);
        }

        if (affinityGroupType != null) {
            sc.addAnd("type", SearchCriteria.Op.EQ, affinityGroupType);
        }

        if (keyword != null) {
            SearchCriteria<AffinityGroupJoinVO> ssc = affinityGroupJoinDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("type", SearchCriteria.Op.LIKE, "%" + keyword + "%");

            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        return sc;
    }

    protected Pair<List<AffinityGroupJoinVO>, Integer> listAffinityGroupsByVM(long vmId, long pageInd, long pageSize) {
        Filter sf = new Filter(SecurityGroupVMMapVO.class, null, true, pageInd, pageSize);
        Pair<List<AffinityGroupVMMapVO>, Integer> agVmMappingPair = affinityGroupVMMapDao.listByInstanceId(vmId, sf);
        Integer count = agVmMappingPair.second();
        if (count == 0) {
            // handle empty result cases
            return new Pair<>(new ArrayList<>(), count);
        }
        List<AffinityGroupVMMapVO> agVmMappings = agVmMappingPair.first();
        Long[] agIds = new Long[agVmMappings.size()];
        int i = 0;
        for (AffinityGroupVMMapVO agVm : agVmMappings) {
            agIds[i++] = agVm.getAffinityGroupId();
        }
        List<AffinityGroupJoinVO> ags = affinityGroupJoinDao.searchByIds(agIds);
        return new Pair<>(ags, count);
    }

    protected Pair<List<AffinityGroupJoinVO>, Integer> listDomainLevelAffinityGroups(SearchCriteria<AffinityGroupJoinVO> sc, Filter searchFilter, long domainId) {
        List<Long> affinityGroupIds = new ArrayList<>();
        Set<Long> allowedDomains = domainMgr.getDomainParentIds(domainId);
        List<AffinityGroupDomainMapVO> maps = affinityGroupDomainMapDao.listByDomain(allowedDomains.toArray());

        for (AffinityGroupDomainMapVO map : maps) {
            boolean subdomainAccess = map.isSubdomainAccess();
            if (map.getDomainId() == domainId || subdomainAccess) {
                affinityGroupIds.add(map.getAffinityGroupId());
            }
        }

        if (!affinityGroupIds.isEmpty()) {
            SearchCriteria<AffinityGroupJoinVO> domainSC = affinityGroupJoinDao.createSearchCriteria();
            domainSC.addAnd("id", SearchCriteria.Op.IN, affinityGroupIds.toArray());
            domainSC.addAnd("aclType", SearchCriteria.Op.EQ, ACLType.Domain.toString());

            sc.addAnd("id", SearchCriteria.Op.SC, domainSC);

            Pair<List<AffinityGroupJoinVO>, Integer> uniqueGroupsPair = affinityGroupJoinDao.searchAndCount(sc, searchFilter);
            // search group by ids
            Integer count = uniqueGroupsPair.second();
            if (count == 0) {
                // empty result
                return new Pair<>(new ArrayList<>(), 0);
            }
            List<AffinityGroupJoinVO> uniqueGroups = uniqueGroupsPair.first();
            Long[] vrIds = new Long[uniqueGroups.size()];
            int i = 0;
            for (AffinityGroupJoinVO v : uniqueGroups) {
                vrIds[i++] = v.getId();
            }
            List<AffinityGroupJoinVO> vrs = affinityGroupJoinDao.searchByIds(vrIds);
            return new Pair<>(vrs, count);
        } else {
            return new Pair<>(new ArrayList<>(), 0);
        }
    }
}
