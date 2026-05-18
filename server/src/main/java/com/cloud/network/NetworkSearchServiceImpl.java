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
package com.cloud.network;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.apache.cloudstack.api.command.admin.network.ListNetworksCmdByAdmin;
import org.apache.cloudstack.api.command.user.network.ListNetworksCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.network.dao.NetworkPermissionDao;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.BooleanUtils;
import org.apache.commons.lang3.EnumUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.configuration.Config;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network.Service;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDetailVO;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.network.dao.NetworkDomainDao;
import com.cloud.network.dao.NetworkDomainVO;
import com.cloud.network.dao.NetworkVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectManager;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.DomainManager;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;

/**
 * NetworkSearchServiceImpl implements the searchForNetworks query cluster
 * extracted from NetworkServiceImpl (Phase 4, slice 8).
 */
@Component
public class NetworkSearchServiceImpl implements NetworkSearchService {

    protected Logger logger = LogManager.getLogger(getClass());

    // Shared DAOs (also injected in god class)
    @Inject
    NetworkDao _networksDao;
    @Inject
    AccountDao _accountDao;
    @Inject
    DomainDao _domainDao;
    @Inject
    NetworkOfferingDao _networkOfferingDao;
    @Inject
    DataCenterDao _dcDao;
    @Inject
    ResourceTagDao _resourceTagDao;

    // Exclusive DAOs (only used inside the search cluster)
    @Inject
    NetworkDomainDao _networkDomainDao;
    @Inject
    NetworkPermissionDao _networkPermissionDao;
    @Inject
    NetworkDetailsDao _networkDetailsDao;

    // Manager collaborators
    @Inject
    AccountManager _accountMgr;
    @Inject
    DomainManager _domainMgr;
    @Inject
    ProjectManager _projectMgr;
    @Inject
    NetworkModel _networkModel;
    @Inject
    ConfigurationDao _configDao;

    // ---------------------------------------------------------------------------
    // Interface method
    // ---------------------------------------------------------------------------

    @Override
    public Pair<List<? extends Network>, Integer> searchForNetworks(ListNetworksCmd cmd) {
        Long id = cmd.getId();
        String name = cmd.getName();
        String keyword = cmd.getKeyword();
        Long zoneId = cmd.getZoneId();
        Account caller = CallContext.current().getCallingAccount();
        Long domainId = cmd.getDomainId();
        String accountName = cmd.getAccountName();
        String guestIpType = cmd.getGuestIpType();
        String trafficType = cmd.getTrafficType();
        Boolean isSystem = cmd.getIsSystem();
        String aclType = cmd.getAclType();
        Long projectId = cmd.getProjectId();
        List<Long> permittedAccounts = new ArrayList<>();
        String path = null;
        Long physicalNetworkId = cmd.getPhysicalNetworkId();
        List<String> supportedServicesStr = cmd.getSupportedServices();
        Boolean restartRequired = cmd.isRestartRequired();
        boolean listAll = cmd.listAll();
        boolean isRecursive = cmd.isRecursive();
        Boolean specifyIpRanges = cmd.isSpecifyIpRanges();
        Long vpcId = cmd.getVpcId();
        Boolean canUseForDeploy = cmd.canUseForDeploy();
        Map<String, String> tags = cmd.getTags();
        Boolean forVpc = cmd.getForVpc();
        Boolean display = cmd.getDisplay();
        Long networkOfferingId = cmd.getNetworkOfferingId();
        Long associatedNetworkId = cmd.getAssociatedNetworkId();
        String networkFilterStr = cmd.getNetworkFilter();

        boolean applyManualPagination = CollectionUtils.isNotEmpty(supportedServicesStr) ||
                Boolean.TRUE.equals(canUseForDeploy);

        String vlanId = null;
        if (cmd instanceof ListNetworksCmdByAdmin) {
            vlanId = ((ListNetworksCmdByAdmin)cmd).getVlan();
        }

        // 1) default is system to false if not specified
        // 2) reset parameter to false if it's specified by a non-ROOT user
        if (isSystem == null || !_accountMgr.isRootAdmin(caller.getId())) {
            isSystem = false;
        }

        // check network filter
        if (networkFilterStr != null && !EnumUtils.isValidEnumIgnoreCase(Network.NetworkFilter.class, networkFilterStr)) {
            throw new InvalidParameterValueException("Invalid value of networkfilter: " + networkFilterStr);
        }
        Network.NetworkFilter networkFilter = networkFilterStr != null ? EnumUtils.getEnumIgnoreCase(Network.NetworkFilter.class, networkFilterStr) : Network.NetworkFilter.All;

        // Account/domainId parameters and isSystem are mutually exclusive
        if (isSystem != null && isSystem && (accountName != null || domainId != null)) {
            throw new InvalidParameterValueException("System network belongs to system, account and domainId parameters can't be specified");
        }

        if (domainId != null) {
            DomainVO domain = _domainDao.findById(domainId);
            if (domain == null) {
                // see DomainVO.java
                throw new InvalidParameterValueException("Specified domain id doesn't exist in the system");
            }

            _accountMgr.checkAccess(caller, domain);
            if (accountName != null) {
                Account owner = _accountMgr.getActiveAccountByName(accountName, domainId);
                if (owner == null) {
                    // see DomainVO.java
                    throw new InvalidParameterValueException("Unable to find account " + accountName + " in specified domain");
                }

                _accountMgr.checkAccess(caller, null, true, owner);
                permittedAccounts.add(owner.getId());
            }
        }

        if (!_accountMgr.isAdmin(caller.getId()) || (projectId != null && projectId.longValue() != -1 && domainId == null)) {
            permittedAccounts.add(caller.getId());
            domainId = caller.getDomainId();
        }

        // set project information
        boolean skipProjectNetworks = true;
        if (projectId != null) {
            if (projectId.longValue() == -1) {
                if (!_accountMgr.isAdmin(caller.getId())) {
                    permittedAccounts.addAll(_projectMgr.listPermittedProjectAccounts(caller.getId()));
                }
            } else {
                permittedAccounts.clear();
                Project project = _projectMgr.getProject(projectId);
                if (project == null) {
                    throw new InvalidParameterValueException("Unable to find project by specified id");
                }
                if (!_projectMgr.canAccessProjectAccount(caller, project.getProjectAccountId())) {
                    // getProject() returns type ProjectVO.
                    throwInvalidIdException("Account " + caller + " cannot access specified project id", project.getUuid(), "projectId");
                }

                //add project account
                permittedAccounts.add(project.getProjectAccountId());
                //add caller account (if admin)
                if (_accountMgr.isAdmin(caller.getId())) {
                    permittedAccounts.add(caller.getId());
                }
            }
            skipProjectNetworks = false;
        }

        if (domainId != null) {
            path = _domainDao.findById(domainId).getPath();
        } else {
            path = _domainDao.findById(caller.getDomainId()).getPath();
        }

        if (listAll && domainId == null) {
            isRecursive = true;
        }

        Long offset = cmd.getStartIndex();
        Long limit = cmd.getPageSizeVal();
        if (applyManualPagination) {
            offset = null;
            limit = null;
        }
        Filter searchFilter = new Filter(NetworkVO.class, "id", false, offset, limit);
        SearchBuilder<NetworkVO> sb = _networksDao.createSearchBuilder();

        if (forVpc != null) {
            if (forVpc) {
                sb.and("vpc", sb.entity().getVpcId(), Op.NNULL);
            } else {
                sb.and("vpc", sb.entity().getVpcId(), Op.NULL);
            }
        }

        // Don't display networks created of system network offerings
        SearchBuilder<NetworkOfferingVO> networkOfferingSearch = _networkOfferingDao.createSearchBuilder();
        networkOfferingSearch.and("systemOnly", networkOfferingSearch.entity().isSystemOnly(), SearchCriteria.Op.EQ);
        if (isSystem != null && isSystem) {
            networkOfferingSearch.and("trafficType", networkOfferingSearch.entity().getTrafficType(), SearchCriteria.Op.EQ);
        }
        sb.join("networkOfferingSearch", networkOfferingSearch, sb.entity().getNetworkOfferingId(), networkOfferingSearch.entity().getId(), JoinBuilder.JoinType.INNER);

        SearchBuilder<DataCenterVO> zoneSearch = _dcDao.createSearchBuilder();
        zoneSearch.and("networkType", zoneSearch.entity().getNetworkType(), SearchCriteria.Op.EQ);
        sb.join("zoneSearch", zoneSearch, sb.entity().getDataCenterId(), zoneSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        sb.and("removed", sb.entity().getRemoved(), Op.NULL);

        if (tags != null && !tags.isEmpty()) {
            SearchBuilder<ResourceTagVO> tagSearch = _resourceTagDao.createSearchBuilder();
            for (int count = 0; count < tags.size(); count++) {
                tagSearch.or().op("key" + String.valueOf(count), tagSearch.entity().getKey(), SearchCriteria.Op.EQ);
                tagSearch.and("value" + String.valueOf(count), tagSearch.entity().getValue(), SearchCriteria.Op.EQ);
                tagSearch.cp();
            }
            tagSearch.and("resourceType", tagSearch.entity().getResourceType(), SearchCriteria.Op.EQ);
            sb.groupBy(sb.entity().getId());
            sb.join("tagSearch", tagSearch, sb.entity().getId(), tagSearch.entity().getResourceId(), JoinBuilder.JoinType.INNER);
        }

        if (permittedAccounts.isEmpty()) {
            SearchBuilder<DomainVO> domainSearch = _domainDao.createSearchBuilder();
            domainSearch.and("path", domainSearch.entity().getPath(), SearchCriteria.Op.LIKE);
            sb.join("domain", domainSearch, sb.entity().getDomainId(), domainSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        }

        SearchBuilder<AccountVO> accountSearch = _accountDao.createSearchBuilder();
        accountSearch.and("typeNEQ", accountSearch.entity().getType(), SearchCriteria.Op.NEQ);
        accountSearch.and("typeEQ", accountSearch.entity().getType(), SearchCriteria.Op.EQ);

        sb.join("account", accountSearch, sb.entity().getAccountId(), accountSearch.entity().getId(), JoinBuilder.JoinType.INNER);

        if (associatedNetworkId != null) {
            SearchBuilder<NetworkDetailVO> associatedNetworkSearch = _networkDetailsDao.createSearchBuilder();
            associatedNetworkSearch.and("name", associatedNetworkSearch.entity().getName(), SearchCriteria.Op.EQ);
            associatedNetworkSearch.and("value", associatedNetworkSearch.entity().getValue(), SearchCriteria.Op.EQ);
            sb.join("associatedNetworkSearch", associatedNetworkSearch, sb.entity().getId(), associatedNetworkSearch.entity().getResourceId(), JoinBuilder.JoinType.INNER);
        }

        Pair<List<NetworkVO>, Integer> result = new Pair<>(new ArrayList<>(), 0);
        if (BooleanUtils.isTrue(isSystem)) {
            SearchCriteria<NetworkVO> sc = createNetworkSearchCriteria(sb, name, keyword, id, isSystem, zoneId, guestIpType, trafficType,
                    physicalNetworkId, networkOfferingId, null, restartRequired, specifyIpRanges,
                    vpcId, tags, display, vlanId, associatedNetworkId);
            addProjectNetworksConditionToSearch(sc, true);
            result = _networksDao.searchAndCount(sc, searchFilter);
        } else {
            SearchCriteria<NetworkVO> additionalSC = _networksDao.createSearchCriteria();

            addAccountSpecificNetworksToSearch(additionalSC, sb, networkFilter, skipProjectNetworks, permittedAccounts, path, isRecursive, projectId);
            addDomainSpecificNetworksToSearch(additionalSC, sb, networkFilter, permittedAccounts, domainId, path, isRecursive);
            addSharedNetworksToSearch(additionalSC, sb, networkFilter, permittedAccounts, path, isRecursive);

            if (CollectionUtils.isNotEmpty(additionalSC.getValues())) {
                SearchCriteria<NetworkVO> sc = createNetworkSearchCriteria(sb, name, keyword, id, isSystem, zoneId, guestIpType,
                        trafficType, physicalNetworkId, networkOfferingId, aclType, restartRequired, specifyIpRanges, vpcId,
                        tags, display, vlanId, associatedNetworkId);
                sc.addAnd("id", SearchCriteria.Op.SC, additionalSC);
                result = _networksDao.searchAndCount(sc, searchFilter);
            }
        }
        List<NetworkVO> networksToReturn = result.first();

        if (supportedServicesStr != null && !supportedServicesStr.isEmpty() && !networksToReturn.isEmpty()) {
            List<NetworkVO> supportedNetworks = new ArrayList<>();
            Service[] supportedServices = new Service[supportedServicesStr.size()];
            int i = 0;
            for (String supportedServiceStr : supportedServicesStr) {
                Service service = Service.getService(supportedServiceStr);
                if (service == null) {
                    throw new InvalidParameterValueException("Invalid service specified " + supportedServiceStr);
                } else {
                    supportedServices[i] = service;
                }
                i++;
            }
            for (NetworkVO network : networksToReturn) {
                if (_networkModel.areServicesSupportedInNetwork(network.getId(), supportedServices)) {
                    supportedNetworks.add(network);
                }
            }
            networksToReturn = supportedNetworks;
        }

        if (canUseForDeploy != null) {
            List<NetworkVO> networksForDeploy = new ArrayList<>();
            for (NetworkVO network : networksToReturn) {
                if (_networkModel.canUseForDeploy(network) == canUseForDeploy) {
                    networksForDeploy.add(network);
                }
            }
            networksToReturn = networksForDeploy;
        }

        if (applyManualPagination) {
            //Now apply pagination
            List<? extends Network> wPagination = com.cloud.utils.StringUtils.applyPagination(networksToReturn, cmd.getStartIndex(), cmd.getPageSizeVal());
            if (wPagination != null) {
                Pair<List<? extends Network>, Integer> listWPagination = new Pair<>(wPagination, networksToReturn.size());
                return listWPagination;
            }
            return new Pair<>(networksToReturn, networksToReturn.size());
        }

        return new Pair<>(result.first(), result.second());
    }

    // ---------------------------------------------------------------------------
    // Private / protected helpers (verbatim from NetworkServiceImpl)
    // ---------------------------------------------------------------------------

    private void addAccountSpecificNetworksToSearch(SearchCriteria<NetworkVO> additionalSC, SearchBuilder<NetworkVO> sb,
                                                    Network.NetworkFilter networkFilter, boolean skipProjectNetworks,
                                                    List<Long> permittedAccounts, String path, boolean isRecursive, Long projectId) {
        if (!Arrays.asList(Network.NetworkFilter.Account, Network.NetworkFilter.AccountDomain, Network.NetworkFilter.All).contains(networkFilter)) {
            return;
        }

        SearchCriteria<NetworkVO> accountSC = sb.create();
        accountSC.addAnd("aclType", SearchCriteria.Op.EQ, ACLType.Account.toString());
        if (permittedAccounts.isEmpty()) {
            if (path != null) {
                accountSC.getJoin("domain").addAnd("path", SearchCriteria.Op.LIKE, isRecursive ? path + "%" : path);
                accountSC.addAnd("id", SearchCriteria.Op.SC, accountSC.getJoin("domain"));
            }
        } else {
            accountSC.addAnd("accountId", SearchCriteria.Op.IN, permittedAccounts.toArray());
        }
        addProjectNetworksConditionToSearch(accountSC, skipProjectNetworks, projectId);
        additionalSC.addOr("id", SearchCriteria.Op.SC, accountSC);
    }

    private void addDomainSpecificNetworksToSearch(SearchCriteria<NetworkVO> additionalSC, SearchBuilder<NetworkVO> sb, Network.NetworkFilter networkFilter,
                                                   List<Long> permittedAccounts, Long domainId, String path, boolean isRecursive) {
        if (!Arrays.asList(Network.NetworkFilter.Domain, Network.NetworkFilter.AccountDomain, Network.NetworkFilter.All).contains(networkFilter)) {
            return;
        }

        if (permittedAccounts.isEmpty()) {
            // Add domain specific networks of domain + parent domains
            addDomainNetworksByDomainPathToSearch(additionalSC, sb, path, isRecursive);
            if (domainId == null) {
                // Add networks of subdomains
                Account caller = CallContext.current().getCallingAccount();
                addDomainLevelNetworksToSearch(additionalSC, sb, caller.getDomainId(), true);
            }
        } else {
            if (domainId != null) {
                // Add domain level networks
                addDomainLevelNetworksToSearch(additionalSC, sb, domainId, false);
            }
        }
    }

    private void addSharedNetworksToSearch(SearchCriteria<NetworkVO> additionalSC, SearchBuilder<NetworkVO> sb, Network.NetworkFilter networkFilter,
                                           List<Long> permittedAccounts, String path, boolean isRecursive) {
        if (!Arrays.asList(Network.NetworkFilter.Shared, Network.NetworkFilter.All).contains(networkFilter)) {
            return;
        }

        if (permittedAccounts.isEmpty()) {
            addSharedNetworksByDomainPathToSearch(additionalSC, sb, path, isRecursive);
        } else {
            addSharedNetworksByAccountsToSearch(additionalSC, sb, permittedAccounts);
        }
    }

    private SearchCriteria<NetworkVO> createNetworkSearchCriteria(SearchBuilder<NetworkVO> sb, String name, String keyword, Long id,
                                                                  Boolean isSystem, Long zoneId, String guestIpType, String trafficType, Long physicalNetworkId,
                                                                  Long networkOfferingId, String aclType, Boolean restartRequired,
                                                                  Boolean specifyIpRanges, Long vpcId, Map<String, String> tags, Boolean display, String vlanId, Long associatedNetworkId) {

        SearchCriteria<NetworkVO> sc = sb.create();

        if (isSystem != null) {
            sc.setJoinParameters("networkOfferingSearch", "systemOnly", isSystem);
        }

        if (name != null) {
            sc.addAnd("name", SearchCriteria.Op.EQ, name);
        }

        if (keyword != null) {
            SearchCriteria<NetworkVO> ssc = _networksDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (display != null) {
            sc.addAnd("displayNetwork", SearchCriteria.Op.EQ, display);
        }

        if (id != null) {
            sc.addAnd("id", SearchCriteria.Op.EQ, id);
        }

        if (zoneId != null) {
            sc.addAnd("dataCenterId", SearchCriteria.Op.EQ, zoneId);
        }

        if (guestIpType != null) {
            sc.addAnd("guestType", SearchCriteria.Op.EQ, guestIpType);
        }

        if (trafficType != null) {
            sc.addAnd("trafficType", SearchCriteria.Op.EQ, trafficType);
        }

        if (aclType != null) {
            sc.addAnd("aclType", SearchCriteria.Op.EQ, aclType.toString());
        }

        if (physicalNetworkId != null) {
            sc.addAnd("physicalNetworkId", SearchCriteria.Op.EQ, physicalNetworkId);
        }

        if (restartRequired != null) {
            sc.addAnd("restartRequired", SearchCriteria.Op.EQ, restartRequired);
        }

        if (specifyIpRanges != null) {
            sc.addAnd("specifyIpRanges", SearchCriteria.Op.EQ, specifyIpRanges);
        }

        if (vpcId != null) {
            sc.addAnd("vpcId", SearchCriteria.Op.EQ, vpcId);
        }

        if (tags != null && !tags.isEmpty()) {
            int count = 0;
            sc.setJoinParameters("tagSearch", "resourceType", ResourceObjectType.Network.toString());
            for (Map.Entry<String, String> entry : tags.entrySet()) {
                sc.setJoinParameters("tagSearch", "key" + String.valueOf(count), entry.getKey());
                sc.setJoinParameters("tagSearch", "value" + String.valueOf(count), entry.getValue());
                count++;
            }
        }

        if (networkOfferingId != null) {
            sc.addAnd("networkOfferingId", SearchCriteria.Op.EQ, networkOfferingId);
        }

        if (associatedNetworkId != null) {
            sc.setJoinParameters("associatedNetworkSearch", "name", Network.AssociatedNetworkId);
            sc.setJoinParameters("associatedNetworkSearch", "value", String.valueOf(associatedNetworkId));
        }

        if (vlanId != null) {
            SearchCriteria<NetworkVO> ssc = _networksDao.createSearchCriteria();
            ssc.addOr("broadcastUri", SearchCriteria.Op.EQ, vlanId);
            ssc.addOr("broadcastUri", SearchCriteria.Op.LIKE, "%://" + vlanId);
            sc.addAnd("broadcastUri", SearchCriteria.Op.SC, ssc);
        }
        return sc;
    }

    private void addDomainLevelNetworksToSearch(SearchCriteria<NetworkVO> additionalSC, SearchBuilder<NetworkVO> sb,
                                                long domainId, boolean parentDomainsOnly) {
        List<Long> networkIds = new ArrayList<>();
        Set<Long> allowedDomains = _domainMgr.getDomainParentIds(domainId);
        List<NetworkDomainVO> maps = _networkDomainDao.listDomainNetworkMapByDomain(allowedDomains.toArray());

        for (NetworkDomainVO map : maps) {
            if (map.getDomainId() == domainId && parentDomainsOnly) {
                continue;
            }
            boolean subdomainAccess = (map.isSubdomainAccess() != null) ? map.isSubdomainAccess() : getAllowSubdomainAccessGlobal();
            if (map.getDomainId() == domainId || subdomainAccess) {
                networkIds.add(map.getNetworkId());
            }
        }

        if (!networkIds.isEmpty()) {
            SearchCriteria<NetworkVO> domainSC = sb.create();
            domainSC.addAnd("id", SearchCriteria.Op.IN, networkIds.toArray());
            domainSC.addAnd("aclType", SearchCriteria.Op.EQ, ACLType.Domain.toString());
            addProjectNetworksConditionToSearch(domainSC, true);
            additionalSC.addOr("id", SearchCriteria.Op.SC, domainSC);
        }
    }

    private void addDomainNetworksByDomainPathToSearch(SearchCriteria<NetworkVO> additionalSC, SearchBuilder<NetworkVO> sb,
                                                       String path, boolean isRecursive) {
        Set<Long> allowedDomains = new HashSet<>();
        if (path != null) {
            if (isRecursive) {
                allowedDomains = _domainMgr.getDomainChildrenIds(path);
            } else {
                Domain domain = _domainDao.findDomainByPath(path);
                allowedDomains.add(domain.getId());
            }
        }

        List<Long> networkIds = new ArrayList<>();

        List<NetworkDomainVO> maps = _networkDomainDao.listDomainNetworkMapByDomain(allowedDomains.toArray());
        for (NetworkDomainVO map : maps) {
            networkIds.add(map.getNetworkId());
        }

        if (!networkIds.isEmpty()) {
            SearchCriteria<NetworkVO> domainSC = sb.create();
            domainSC.addAnd("id", SearchCriteria.Op.IN, networkIds.toArray());
            domainSC.addAnd("aclType", SearchCriteria.Op.EQ, ACLType.Domain.toString());
            addProjectNetworksConditionToSearch(domainSC, true);
            additionalSC.addOr("id", SearchCriteria.Op.SC, domainSC);
        }
    }

    protected void addProjectNetworksConditionToSearch(SearchCriteria<NetworkVO> sc, boolean skipProjectNetworks) {
        addProjectNetworksConditionToSearch(sc, skipProjectNetworks, null);
    }

    protected void addProjectNetworksConditionToSearch(SearchCriteria<NetworkVO> sc, boolean skipProjectNetworks,
               Long projectId) {
        if (!skipProjectNetworks && projectId == -1) {
            sc.getJoin("account").addAnd("type", Op.NNULL);
        } else {
            sc.getJoin("account").addAnd("type", skipProjectNetworks ? Op.NEQ : Op.EQ, Account.Type.PROJECT);
        }
        sc.addAnd("id", Op.SC, sc.getJoin("account"));
    }

    private void addSharedNetworksByAccountsToSearch(SearchCriteria<NetworkVO> additionalSC, SearchBuilder<NetworkVO> sb,
                                                     List<Long> permittedAccounts) {
        List<Long> sharedNetworkIds = _networkPermissionDao.listPermittedNetworkIdsByAccounts(permittedAccounts);
        if (!sharedNetworkIds.isEmpty()) {
            SearchCriteria<NetworkVO> ssc = sb.create();
            ssc.addAnd("id", SearchCriteria.Op.IN, sharedNetworkIds.toArray());
            addProjectNetworksConditionToSearch(ssc, true);
            additionalSC.addOr("id", SearchCriteria.Op.SC, ssc);
        }
    }

    private void addSharedNetworksByDomainPathToSearch(SearchCriteria<NetworkVO> additionalSC, SearchBuilder<NetworkVO> sb, String path, boolean isRecursive) {
        Set<Long> allowedDomains = new HashSet<>();
        if (path != null) {
            if (isRecursive) {
                allowedDomains = _domainMgr.getDomainChildrenIds(path);
            } else {
                Domain domain = _domainDao.findDomainByPath(path);
                allowedDomains.add(domain.getId());
            }
        }
        List<Long> allowedDomainsList = new ArrayList<>(allowedDomains);

        if (!allowedDomainsList.isEmpty()) {
            GenericSearchBuilder<AccountVO, Long> accountIdSearch = _accountDao.createSearchBuilder(Long.class);
            accountIdSearch.and("domainId", accountIdSearch.entity().getDomainId(), SearchCriteria.Op.IN);
            accountIdSearch.selectFields(accountIdSearch.entity().getId());
            accountIdSearch.done();
            SearchCriteria<Long> scAccount = accountIdSearch.create();
            scAccount.setParameters("domainId", allowedDomainsList.toArray());
            List<Long> allowedAccountsList = _accountDao.customSearch(scAccount, null);

            List<Long> sharedNetworkIds = _networkPermissionDao.listPermittedNetworkIdsByAccounts(allowedAccountsList);
            if (!sharedNetworkIds.isEmpty()) {
                SearchCriteria<NetworkVO> ssc = sb.create();
                ssc.addAnd("id", SearchCriteria.Op.IN, sharedNetworkIds.toArray());
                addProjectNetworksConditionToSearch(ssc, true);
                additionalSC.addOr("id", SearchCriteria.Op.SC, ssc);
            }
        }
    }

    // ---------------------------------------------------------------------------
    // Duplicated private helpers (no back-reference to god class)
    // ---------------------------------------------------------------------------

    private void throwInvalidIdException(String message, String uuid, String description) {
        InvalidParameterValueException ex = new InvalidParameterValueException(message);
        ex.addProxyObject(uuid, description);
        throw ex;
    }

    private boolean getAllowSubdomainAccessGlobal() {
        String value = _configDao.getValue(Config.SubDomainNetworkAccess.key());
        return Boolean.parseBoolean(value);
    }
}
