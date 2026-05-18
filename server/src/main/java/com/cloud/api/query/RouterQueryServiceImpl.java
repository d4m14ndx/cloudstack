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
import java.util.Collections;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.BaseListProjectAndAccountResourcesCmd;
import org.apache.cloudstack.api.ResponseGenerator;
import org.apache.cloudstack.api.command.admin.internallb.ListInternalLBVMsCmd;
import org.apache.cloudstack.api.command.admin.router.GetRouterHealthCheckResultsCmd;
import org.apache.cloudstack.api.command.admin.router.ListRoutersCmd;
import org.apache.cloudstack.api.response.DomainRouterResponse;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.RouterHealthCheckResultResponse;
import org.apache.cloudstack.context.CallContext;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.DomainRouterJoinDao;
import com.cloud.api.query.vo.DomainRouterJoinVO;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.network.RouterHealthCheckResult;
import com.cloud.network.VpcVirtualNetworkApplianceService;
import com.cloud.network.dao.RouterHealthCheckResultDao;
import com.cloud.network.dao.RouterHealthCheckResultVO;
import com.cloud.network.router.VirtualNetworkApplianceManager;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.DomainRouterVO;

/**
 * Implementation of router and health-check query helpers extracted from
 * {@link QueryManagerImpl} as part of the Phase 4 Spring-component
 * decomposition (slice 7).
 *
 * @see RouterQueryService
 */
@Component
public class RouterQueryServiceImpl implements RouterQueryService {

    @Inject
    private AccountManager accountMgr;

    @Inject
    private DomainRouterJoinDao routerJoinDao;

    @Inject
    private DomainRouterDao routerDao;

    @Inject
    private RouterHealthCheckResultDao routerHealthCheckResultDao;

    @Inject
    private VpcVirtualNetworkApplianceService routerService;

    @Inject
    private ResponseGenerator responseGenerator;

    @Override
    public ListResponse<DomainRouterResponse> searchForRouters(ListRoutersCmd cmd) {
        Pair<List<DomainRouterJoinVO>, Integer> result = searchForRoutersInternal(cmd, cmd.getId(), cmd.getRouterName(),
                cmd.getState(), cmd.getZoneId(), cmd.getPodId(), cmd.getClusterId(), cmd.getHostId(),
                cmd.getKeyword(), cmd.getNetworkId(), cmd.getVpcId(), cmd.getForVpc(), cmd.getRole(),
                cmd.getVersion(), cmd.isHealthCheckFailed());
        ListResponse<DomainRouterResponse> response = new ListResponse<>();
        List<DomainRouterResponse> routerResponses = ViewResponseHelper.createDomainRouterResponse(result.first().toArray(new DomainRouterJoinVO[0]));
        if (VirtualNetworkApplianceManager.RouterHealthChecksEnabled.value()) {
            for (DomainRouterResponse res : routerResponses) {
                DomainRouterVO resRouter = routerDao.findByUuid(res.getId());
                res.setHealthChecksFailed(routerHealthCheckResultDao.hasFailingChecks(resRouter.getId()));
                if (cmd.shouldFetchHealthCheckResults()) {
                    res.setHealthCheckResults(responseGenerator.createHealthCheckResponse(resRouter,
                            new ArrayList<>(routerHealthCheckResultDao.getHealthCheckResults(resRouter.getId()))));
                }
            }
        }
        response.setResponses(routerResponses, result.second());
        return response;
    }

    @Override
    public ListResponse<DomainRouterResponse> searchForInternalLbVms(ListInternalLBVMsCmd cmd) {
        Pair<List<DomainRouterJoinVO>, Integer> result = searchForRoutersInternal(cmd, cmd.getId(), cmd.getRouterName(),
                cmd.getState(), cmd.getZoneId(), cmd.getPodId(), null, cmd.getHostId(),
                cmd.getKeyword(), cmd.getNetworkId(), cmd.getVpcId(), cmd.getForVpc(), cmd.getRole(), null, null);
        ListResponse<DomainRouterResponse> response = new ListResponse<>();
        List<DomainRouterResponse> routerResponses = ViewResponseHelper.createDomainRouterResponse(result.first().toArray(new DomainRouterJoinVO[0]));
        if (VirtualNetworkApplianceManager.RouterHealthChecksEnabled.value()) {
            for (DomainRouterResponse res : routerResponses) {
                DomainRouterVO resRouter = routerDao.findByUuid(res.getId());
                res.setHealthChecksFailed(routerHealthCheckResultDao.hasFailingChecks(resRouter.getId()));
                if (cmd.shouldFetchHealthCheckResults()) {
                    res.setHealthCheckResults(responseGenerator.createHealthCheckResponse(resRouter,
                            new ArrayList<>(routerHealthCheckResultDao.getHealthCheckResults(resRouter.getId()))));
                }
            }
        }
        response.setResponses(routerResponses, result.second());
        return response;
    }

    @Override
    public List<RouterHealthCheckResultResponse> listRouterHealthChecks(GetRouterHealthCheckResultsCmd cmd) {
        long routerId = cmd.getRouterId();
        if (!VirtualNetworkApplianceManager.RouterHealthChecksEnabled.value()) {
            throw new CloudRuntimeException("Router health checks are not enabled for router " + routerId);
        }

        if (cmd.shouldPerformFreshChecks()) {
            Pair<Boolean, String> healthChecksresult = routerService.performRouterHealthChecks(routerId);
            if (healthChecksresult == null) {
                throw new CloudRuntimeException("Failed to initiate fresh checks on router.");
            } else if (!healthChecksresult.first()) {
                throw new CloudRuntimeException("Unable to perform fresh checks on router - " + healthChecksresult.second());
            }
        }

        List<RouterHealthCheckResult> result = new ArrayList<>(routerHealthCheckResultDao.getHealthCheckResults(routerId));
        if (result.isEmpty()) {
            throw new CloudRuntimeException("No health check results found for the router. This could happen for " +
                    "a newly created router. Please wait for periodic results to populate or manually call for checks to execute.");
        }

        return responseGenerator.createHealthCheckResponse(routerDao.findById(routerId), result);
    }

    protected Pair<List<DomainRouterJoinVO>, Integer> searchForRoutersInternal(BaseListProjectAndAccountResourcesCmd cmd,
            Long id, String name, String state, Long zoneId, Long podId, Long clusterId, Long hostId,
            String keyword, Long networkId, Long vpcId, Boolean forVpc, String role, String version,
            Boolean isHealthCheckFailed) {

        Account caller = CallContext.current().getCallingAccount();
        List<Long> permittedAccounts = new ArrayList<>();

        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject =
                new Ternary<>(cmd.getDomainId(), cmd.isRecursive(), null);
        accountMgr.buildACLSearchParameters(caller, id, cmd.getAccountName(), cmd.getProjectId(),
                permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
        Long domainId = domainIdRecursiveListProject.first();
        Boolean isRecursive = domainIdRecursiveListProject.second();
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        Filter searchFilter = new Filter(DomainRouterJoinVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());

        SearchBuilder<DomainRouterJoinVO> sb = routerJoinDao.createSearchBuilder();
        sb.select(null, Func.DISTINCT, sb.entity().getId());
        accountMgr.buildACLViewSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        sb.and("name", sb.entity().getInstanceName(), SearchCriteria.Op.EQ);
        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("accountId", sb.entity().getAccountId(), SearchCriteria.Op.IN);
        sb.and("state", sb.entity().getState(), SearchCriteria.Op.EQ);
        sb.and("dataCenterId", sb.entity().getDataCenterId(), SearchCriteria.Op.EQ);
        sb.and("podId", sb.entity().getPodId(), SearchCriteria.Op.EQ);
        sb.and("clusterId", sb.entity().getClusterId(), SearchCriteria.Op.EQ);
        sb.and("hostId", sb.entity().getHostId(), SearchCriteria.Op.EQ);
        sb.and("vpcId", sb.entity().getVpcId(), SearchCriteria.Op.EQ);
        sb.and("role", sb.entity().getRole(), SearchCriteria.Op.EQ);
        sb.and("version", sb.entity().getTemplateVersion(), SearchCriteria.Op.LIKE);

        if (forVpc != null) {
            if (forVpc) {
                sb.and("forVpc", sb.entity().getVpcId(), SearchCriteria.Op.NNULL);
            } else {
                sb.and("forVpc", sb.entity().getVpcId(), SearchCriteria.Op.NULL);
            }
        }

        if (networkId != null) {
            sb.and("networkId", sb.entity().getNetworkId(), SearchCriteria.Op.EQ);
        }

        List<Long> routersWithFailures = null;
        if (isHealthCheckFailed != null) {
            GenericSearchBuilder<RouterHealthCheckResultVO, Long> routerHealthCheckResultSearch =
                    routerHealthCheckResultDao.createSearchBuilder(Long.class);
            routerHealthCheckResultSearch.and("checkResult", routerHealthCheckResultSearch.entity().getCheckResult(), SearchCriteria.Op.EQ);
            routerHealthCheckResultSearch.selectFields(routerHealthCheckResultSearch.entity().getRouterId());
            routerHealthCheckResultSearch.done();
            SearchCriteria<Long> ssc = routerHealthCheckResultSearch.create();
            ssc.setParameters("checkResult", false);
            routersWithFailures = routerHealthCheckResultDao.customSearch(ssc, null);

            if (routersWithFailures != null && !routersWithFailures.isEmpty()) {
                if (isHealthCheckFailed) {
                    sb.and("routerId", sb.entity().getId(), SearchCriteria.Op.IN);
                } else {
                    sb.and("routerId", sb.entity().getId(), SearchCriteria.Op.NIN);
                }
            } else if (isHealthCheckFailed) {
                return new Pair<>(Collections.emptyList(), 0);
            }
        }

        SearchCriteria<DomainRouterJoinVO> sc = sb.create();
        accountMgr.buildACLViewSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (keyword != null) {
            SearchCriteria<DomainRouterJoinVO> ssc = routerJoinDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("instanceName", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("state", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("networkName", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("vpcName", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("redundantState", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            sc.addAnd("instanceName", SearchCriteria.Op.SC, ssc);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (state != null) {
            sc.setParameters("state", state);
        }

        if (zoneId != null) {
            sc.setParameters("dataCenterId", zoneId);
        }

        if (podId != null) {
            sc.setParameters("podId", podId);
        }

        if (clusterId != null) {
            sc.setParameters("clusterId", clusterId);
        }

        if (hostId != null) {
            sc.setParameters("hostId", hostId);
        }

        if (networkId != null) {
            sc.setParameters("networkId", networkId);
        }

        if (vpcId != null) {
            sc.setParameters("vpcId", vpcId);
        }

        if (role != null) {
            sc.setParameters("role", role);
        }

        if (version != null) {
            sc.setParameters("version", "Cloudstack Release " + version + "%");
        }

        if (routersWithFailures != null && !routersWithFailures.isEmpty()) {
            sc.setParameters("routerId", routersWithFailures.toArray(new Object[0]));
        }

        Pair<List<DomainRouterJoinVO>, Integer> uniqueVrPair = routerJoinDao.searchAndCount(sc, searchFilter);
        Integer count = uniqueVrPair.second();
        if (count == 0) {
            return uniqueVrPair;
        }
        List<DomainRouterJoinVO> uniqueVrs = uniqueVrPair.first();
        Long[] vrIds = new Long[uniqueVrs.size()];
        int i = 0;
        for (DomainRouterJoinVO v : uniqueVrs) {
            vrIds[i++] = v.getId();
        }
        List<DomainRouterJoinVO> vrs = routerJoinDao.searchByIds(vrIds);
        return new Pair<>(vrs, count);
    }
}
