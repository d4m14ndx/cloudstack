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
package com.cloud.network.vpc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.user.vpc.ListStaticRoutesCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.VlanDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network.Service;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.element.StaticNatServiceProvider;
import com.cloud.network.element.VpcProvider;
import com.cloud.network.vpc.dao.StaticRouteDao;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.network.vpc.dao.VpcGatewayDao;
import com.cloud.network.vpc.dao.VpcServiceMapDao;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;

/**
 * VPC static-route CRUD, validation and provider application —
 * extracted from {@link VpcManagerImpl}.
 *
 * @see StaticRouteService
 */
@Component
public class StaticRouteServiceImpl implements StaticRouteService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private StaticRouteDao staticRouteDao;
    @Inject
    private VpcDao vpcDao;
    @Inject
    private VpcGatewayDao vpcGatewayDao;
    @Inject
    private VpcServiceMapDao vpcSrvcDao;
    @Inject
    private IPAddressDao ipAddressDao;
    @Inject
    private VlanDao vlanDao;
    @Inject
    private ResourceTagDao resourceTagDao;
    @Inject
    private AccountManager accountMgr;
    @Inject
    private EntityManager entityMgr;
    @Inject
    private VpcManager vpcManager;

    @Override
    public StaticRoute getStaticRoute(final long routeId) {
        return staticRouteDao.findById(routeId);
    }

    @Override
    public StaticRoute createStaticRoute(final Long gatewayId, Long vpcId, final String nextHop, final String cidr) throws NetworkRuleConflictException {
        final Account caller = CallContext.current().getCallingAccount();

        // parameters validation
        if (gatewayId == null && nextHop == null) {
            throw new InvalidParameterValueException("one of gatewayId and nextHop must be specified");
        }

        if (gatewayId != null && nextHop != null) {
            throw new InvalidParameterValueException("Only one of gatewayId and nextHop can be specified");
        }

        if (gatewayId != null) {
            final VpcGateway gateway = vpcGatewayDao.findById(gatewayId);
            if (gateway == null) {
                throw new InvalidParameterValueException("Invalid gateway id is given");
            }

            if (gateway.getState() != VpcGateway.State.Ready) {
                throw new InvalidParameterValueException("Gateway is not in the " + VpcGateway.State.Ready + " state: " + gateway.getState());
            }

            if (vpcId != null) {
                if (!vpcId.equals(gateway.getVpcId())) {
                    throw new InvalidParameterValueException("Invalid gateway id is given");
                }
            } else {
                vpcId = gateway.getVpcId();
            }
        } else if (nextHop != null) {
            if (vpcId == null) {
                throw new InvalidParameterValueException("vpcId must be specified");
            }
        }

        final Vpc vpc = vpcDao.getActiveVpcById(vpcId);
        if (vpc == null) {
            throw new InvalidParameterValueException("Can't add static route to VPC that is being deleted");
        }

        accountMgr.checkAccess(caller, null, false, vpc);

        if (!NetUtils.isValidIp4Cidr(cidr)) {
            throw new InvalidParameterValueException("Invalid format for cidr " + cidr);
        }

        // validate the cidr
        // 1) CIDR should be outside of VPC cidr for guest networks
        if (NetUtils.isNetworksOverlap(vpc.getCidr(), cidr)) {
            throw new InvalidParameterValueException("CIDR should be outside of VPC cidr " + vpc.getCidr());
        }

        // 2) CIDR should be outside of link-local cidr
        if (NetUtils.isNetworksOverlap(cidr, NetUtils.getLinkLocalCIDR())) {
            throw new InvalidParameterValueException("CIDR should be outside of link local cidr " + NetUtils.getLinkLocalCIDR());
        }

        // 3) Verify against denied routes
        if (isCidrDenylisted(cidr, vpc.getZoneId())) {
            throw new InvalidParameterValueException("The static gateway cidr overlaps with one of the denied routes of the zone the VPC belongs to");
        }

        // 4) validate next hop
        if (nextHop != null && !isNextHopValid(nextHop, vpc)) {
            throw new InvalidParameterValueException(String.format("Next hop %s is invalid. It must be within VPC CIDR or on the same public or private network", nextHop));
        }

        return Transaction.execute(new TransactionCallbackWithException<StaticRouteVO, NetworkRuleConflictException>() {
            @Override
            public StaticRouteVO doInTransaction(final TransactionStatus status) throws NetworkRuleConflictException {
                StaticRouteVO newRoute = new StaticRouteVO(gatewayId, cidr, vpc.getId(), vpc.getAccountId(), vpc.getDomainId(), nextHop);
                logger.debug("Adding static route " + newRoute);
                newRoute = staticRouteDao.persist(newRoute);

                detectRoutesConflict(newRoute);

                if (!staticRouteDao.setStateToAdd(newRoute)) {
                    throw new CloudRuntimeException("Unable to update the state to add for " + newRoute);
                }
                CallContext.current().setEventDetails("Static route ID: " + newRoute.getUuid());

                return newRoute;
            }
        });
    }

    @Override
    public Pair<List<? extends StaticRoute>, Integer> listStaticRoutes(final ListStaticRoutesCmd cmd) {
        final Long id = cmd.getId();
        final Long gatewayId = cmd.getGatewayId();
        final Long vpcId = cmd.getVpcId();
        Long domainId = cmd.getDomainId();
        Boolean isRecursive = cmd.isRecursive();
        final Boolean listAll = cmd.listAll();
        final String accountName = cmd.getAccountName();
        final Account caller = CallContext.current().getCallingAccount();
        final List<Long> permittedAccounts = new ArrayList<Long>();
        final Map<String, String> tags = cmd.getTags();
        final Long projectId = cmd.getProjectId();
        final String state = cmd.getState();

        final Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<Long, Boolean, ListProjectResourcesCriteria>(domainId, isRecursive,
                null);
        accountMgr.buildACLSearchParameters(caller, id, accountName, projectId, permittedAccounts, domainIdRecursiveListProject, listAll, false);
        domainId = domainIdRecursiveListProject.first();
        isRecursive = domainIdRecursiveListProject.second();
        final ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        final Filter searchFilter = new Filter(StaticRouteVO.class, "created", false, cmd.getStartIndex(), cmd.getPageSizeVal());

        final SearchBuilder<StaticRouteVO> sb = staticRouteDao.createSearchBuilder();
        accountMgr.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("vpcId", sb.entity().getVpcId(), SearchCriteria.Op.EQ);
        sb.and("vpcGatewayId", sb.entity().getVpcGatewayId(), SearchCriteria.Op.EQ);
        sb.and("state", sb.entity().getState(), SearchCriteria.Op.EQ);

        if (tags != null && !tags.isEmpty()) {
            final SearchBuilder<ResourceTagVO> tagSearch = resourceTagDao.createSearchBuilder();
            for (int count = 0; count < tags.size(); count++) {
                tagSearch.or().op("key" + String.valueOf(count), tagSearch.entity().getKey(), SearchCriteria.Op.EQ);
                tagSearch.and("value" + String.valueOf(count), tagSearch.entity().getValue(), SearchCriteria.Op.EQ);
                tagSearch.cp();
            }
            tagSearch.and("resourceType", tagSearch.entity().getResourceType(), SearchCriteria.Op.EQ);
            sb.groupBy(sb.entity().getId());
            sb.join("tagSearch", tagSearch, sb.entity().getId(), tagSearch.entity().getResourceId(), JoinBuilder.JoinType.INNER);
        }

        final SearchCriteria<StaticRouteVO> sc = sb.create();
        accountMgr.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        if (id != null) {
            sc.addAnd("id", Op.EQ, id);
        }

        if (vpcId != null) {
            sc.addAnd("vpcId", Op.EQ, vpcId);
        }

        if (gatewayId != null) {
            sc.addAnd("vpcGatewayId", Op.EQ, gatewayId);
        }

        if (state != null) {
            sc.addAnd("state", Op.EQ, state);
        }

        if (tags != null && !tags.isEmpty()) {
            int count = 0;
            sc.setJoinParameters("tagSearch", "resourceType", ResourceObjectType.StaticRoute.toString());
            for (final String key : tags.keySet()) {
                sc.setJoinParameters("tagSearch", "key" + String.valueOf(count), key);
                sc.setJoinParameters("tagSearch", "value" + String.valueOf(count), tags.get(key));
                count++;
            }
        }

        final Pair<List<StaticRouteVO>, Integer> result = staticRouteDao.searchAndCount(sc, searchFilter);
        return new Pair<List<? extends StaticRoute>, Integer>(result.first(), result.second());
    }

    @Override
    public List<StaticRouteProfile> getVpcStaticRoutes(final List<? extends StaticRoute> routes) {
        final List<StaticRouteProfile> staticRouteProfiles = new ArrayList<>(routes.size());
        final Map<Long, VpcGateway> gatewayMap = new HashMap<Long, VpcGateway>();
        for (final StaticRoute route : routes) {
            if (route.getVpcGatewayId() != null) {
                VpcGateway gateway = gatewayMap.get(route.getVpcGatewayId());
                if (gateway == null) {
                    gateway = entityMgr.findById(VpcGateway.class, route.getVpcGatewayId());
                    gatewayMap.put(gateway.getId(), gateway);
                }
                staticRouteProfiles.add(new StaticRouteProfile(route, gateway));
            } else {
                staticRouteProfiles.add(new StaticRouteProfile(route));
            }
        }
        return staticRouteProfiles;
    }

    @Override
    public boolean applyStaticRoutes(final List<StaticRouteVO> routes, final Account caller, final boolean updateRoutesInDB) throws ResourceUnavailableException {
        final boolean success = true;
        final List<StaticRouteProfile> staticRouteProfiles = getVpcStaticRoutes(routes);
        if (!applyStaticRoutes(staticRouteProfiles)) {
            logger.warn("Routes are not completely applied");
            return false;
        } else {
            if (updateRoutesInDB) {
                for (final StaticRouteVO route : routes) {
                    if (route.isForVpn()) {
                        continue;
                    }
                    if (route.getState() == StaticRoute.State.Revoke) {
                        staticRouteDao.remove(route.getId());
                        logger.debug("Removed route " + route + " from the DB");
                    } else if (route.getState() == StaticRoute.State.Add) {
                        final StaticRouteVO ruleVO = staticRouteDao.findById(route.getId());
                        ruleVO.setState(StaticRoute.State.Active);
                        staticRouteDao.update(ruleVO.getId(), ruleVO);
                        logger.debug("Marked route " + route + " with state " + StaticRoute.State.Active);
                    }
                }
            }
        }

        return success;
    }

    @Override
    public boolean applyStaticRoutes(final List<StaticRouteProfile> routes) throws ResourceUnavailableException {
        if (routes.isEmpty()) {
            logger.debug("No static routes to apply");
            return true;
        }
        final Vpc vpc = vpcDao.findById(routes.get(0).getVpcId());

        logger.debug("Applying static routes for vpc " + vpc);
        final String staticNatProvider = vpcSrvcDao.getProviderForServiceInVpc(vpc.getId(), Service.StaticNat);

        for (final VpcProvider provider : vpcManager.getVpcElements()) {
            if (!(provider instanceof StaticNatServiceProvider && provider.getName().equalsIgnoreCase(staticNatProvider))) {
                continue;
            }

            if (provider.applyStaticRoutes(vpc, routes)) {
                logger.debug("Applied static routes for vpc " + vpc);
            } else {
                logger.warn("Failed to apply static routes for vpc " + vpc);
                return false;
            }
        }

        return true;
    }

    @Override
    public void detectRoutesConflict(final StaticRoute newRoute) throws NetworkRuleConflictException {
        // Multiple private gateways can exist within Vpc. Check for conflicts
        // for all static routes in Vpc
        // and not just the gateway
        final List<? extends StaticRoute> routes = staticRouteDao.listByVpcIdAndNotRevoked(newRoute.getVpcId());
        assert routes.size() >= 1 : "For static routes, we now always first persist the route and then check for "
                + "network conflicts so we should at least have one rule at this point.";

        for (final StaticRoute route : routes) {
            if (route.getId() == newRoute.getId()) {
                continue; // Skips my own route.
            }

            if (NetUtils.isNetworksOverlap(route.getCidr(), newRoute.getCidr())) {
                throw new NetworkRuleConflictException("New static route cidr conflicts with existing route " + route);
            }
        }
    }

    @Override
    public void markStaticRouteForRevoke(final StaticRouteVO route, final Account caller) {
        logger.debug("Revoking static route " + route);
        if (caller != null) {
            accountMgr.checkAccess(caller, null, false, route);
        }

        if (route.getState() == StaticRoute.State.Staged) {
            if (logger.isDebugEnabled()) {
                logger.debug("Found a static route that is still in stage state so just removing it: " + route);
            }
            staticRouteDao.remove(route.getId());
        } else if (route.getState() == StaticRoute.State.Add || route.getState() == StaticRoute.State.Active) {
            route.setState(StaticRoute.State.Revoke);
            staticRouteDao.update(route.getId(), route);
            logger.debug("Marked static route " + route + " with state " + StaticRoute.State.Revoke);
        }
    }

    @Override
    public boolean isCidrDenylisted(final String cidr, final long zoneId) {
        final String routesStr = NetworkOrchestrationService.DeniedRoutes.valueIn(zoneId);
        if (routesStr != null && !routesStr.isEmpty()) {
            final String[] cidrDenyList = routesStr.split(",");

            if (cidrDenyList != null && cidrDenyList.length > 0) {
                for (final String denyListedRoute : cidrDenyList) {
                    if (NetUtils.isNetworksOverlap(denyListedRoute, cidr)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    @Override
    public boolean isNextHopValid(final String nextHop, final Vpc vpc) {
        // Scenario 1: VM as next hop
        if (NetUtils.isIpWithInCidrRange(nextHop, vpc.getCidr())) {
            logger.debug("The next Hop {} is valid as it is within the VPC cidr {}", nextHop, vpc.getCidr());
            return true;
        }
        // Scenario 2: Another public IP as next hop
        List<IPAddressVO> ips = ipAddressDao.listByAssociatedVpc(vpc.getId(), null);
        List<Long> vlanIds = new ArrayList<>();
        for (IPAddressVO ip : ips) {
            if (vlanIds.contains(ip.getVlanId())) {
                continue;
            }
            VlanVO vlan = vlanDao.findById(ip.getVlanId());
            if (vlan != null) {
                String vlanCidr = NetUtils.getCidrFromGatewayAndNetmask(vlan.getVlanGateway(), vlan.getVlanNetmask());
                if (NetUtils.isIpWithInCidrRange(nextHop, vlanCidr)) {
                    logger.debug("The next Hop {} is valid as it is on the same network as Public IP address {} ", nextHop, ip.getAddress());
                    return true;
                }
            }
            vlanIds.add(ip.getVlanId());
        }

        // Scenario 3: An IP on private gateway as next hop
        List<VpcGatewayVO> vpcGateways = vpcGatewayDao.listByVpcId(vpc.getId());
        for (VpcGatewayVO vpcGateway : vpcGateways) {
            String vpcGatewayCidr = NetUtils.getCidrFromGatewayAndNetmask(vpcGateway.getGateway(), vpcGateway.getNetmask());
            if (NetUtils.isIpWithInCidrRange(nextHop, vpcGatewayCidr)) {
                logger.debug("The next Hop {} is valid as it is on the same network as private gateway {} ", nextHop, vpcGateway.getIp4Address());
                return true;
            }
        }

        logger.debug("The next Hop {} is invalid", nextHop);
        return false;
    }
}
