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

import java.util.List;

import org.apache.cloudstack.api.command.user.vpc.ListStaticRoutesCmd;

import com.cloud.exception.NetworkRuleConflictException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.user.Account;
import com.cloud.utils.Pair;

/**
 * VPC static-route CRUD, validation, conflict detection and provider
 * application — extracted from {@link VpcManagerImpl} as part of the
 * Phase 4 Spring-component decomposition.
 *
 * <p>This component owns the pure static-route logic (create, list,
 * lookup, apply against the VPC's {@link com.cloud.network.element.StaticNatServiceProvider},
 * conflict detection, CIDR denylist, next-hop validation, revoke
 * marking and route-profile materialization). {@code VpcManagerImpl}
 * keeps its public-API methods as thin delegating wrappers so the
 * {@link VpcService} and {@link com.cloud.network.vpc.VpcManager}
 * contracts and existing test spies continue to work.
 *
 * <p>Methods that orchestrate static routes against VR / VPN state
 * (e.g. {@code applyStaticRoutesForVpc},
 * {@code applyStaticRouteForVpcVpnIfNeeded},
 * {@code revokeStaticRoutesForVpc}, the {@code getVpcStaticRoutes(Long, ...)}
 * overloads) remain in {@code VpcManagerImpl} because they reach into
 * the VPC virtual-router IP allocation path.
 */
public interface StaticRouteService {

    /** Look up a single static route by id; returns {@code null} when not found. */
    StaticRoute getStaticRoute(long routeId);

    /**
     * Create a new static route on the given VPC. Exactly one of
     * {@code gatewayId} and {@code nextHop} must be supplied. Validates
     * the CIDR (well-formed, outside the VPC CIDR, outside link-local,
     * not on the zone's denied-routes list), validates the next hop
     * (within VPC CIDR, on a public-IP VLAN or on a VPC private
     * gateway) and detects conflicts against existing non-revoked
     * routes before persisting.
     */
    StaticRoute createStaticRoute(Long gatewayId, Long vpcId, String nextHop, String cidr) throws NetworkRuleConflictException;

    /** Paginated, ACL-filtered list of static routes for the listStaticRoutes API. */
    Pair<List<? extends StaticRoute>, Integer> listStaticRoutes(ListStaticRoutesCmd cmd);

    /** Materialize {@link StaticRouteProfile} objects (route + resolved {@link VpcGateway}) for a route set. */
    List<StaticRouteProfile> getVpcStaticRoutes(List<? extends StaticRoute> routes);

    /**
     * Push the given routes to the VPC's StaticNat provider and
     * optionally reconcile DB state ({@code Revoke} -> removed,
     * {@code Add} -> {@code Active}). VPN-flagged routes are not
     * reconciled because they have no persisted row.
     */
    boolean applyStaticRoutes(List<StaticRouteVO> routes, Account caller, boolean updateRoutesInDB) throws ResourceUnavailableException;

    /** Push a pre-materialized route-profile list to the VPC's StaticNat provider. */
    boolean applyStaticRoutes(List<StaticRouteProfile> routes) throws ResourceUnavailableException;

    /**
     * Throws {@link NetworkRuleConflictException} when the supplied
     * (already-persisted) route's CIDR overlaps any other non-revoked
     * route on the same VPC.
     */
    void detectRoutesConflict(StaticRoute newRoute) throws NetworkRuleConflictException;

    /**
     * Move a static route towards revoke: {@code Staged} routes are
     * deleted outright, {@code Add}/{@code Active} routes are flipped
     * to {@code Revoke}. The caller's access to the route is verified
     * when {@code caller} is non-null.
     */
    void markStaticRouteForRevoke(StaticRouteVO route, Account caller);

    /**
     * Returns {@code true} when {@code cidr} overlaps any entry in the
     * zone-scoped {@code network.denied.routes} setting.
     */
    boolean isCidrDenylisted(String cidr, long zoneId);

    /**
     * Returns {@code true} when {@code nextHop} is a valid next hop for
     * the VPC: either within the VPC CIDR, on the same network as one
     * of the VPC's public IPs, or on the same network as one of its
     * VPC private gateways.
     */
    boolean isNextHopValid(String nextHop, Vpc vpc);
}
