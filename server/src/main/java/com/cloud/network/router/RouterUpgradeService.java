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
package com.cloud.network.router;

import java.util.List;

import org.apache.cloudstack.api.command.admin.router.UpgradeRouterCmd;
import org.apache.cloudstack.api.command.admin.router.UpgradeRouterTemplateCmd;

import com.cloud.vm.DomainRouterVO;

/**
 * Helpers for virtual-router upgrade flows: switching a router to a new system
 * service offering, and refreshing the router template by rebooting routers
 * whose template version is out of date.
 *
 * <p>Extracted from {@link VirtualNetworkApplianceManagerImpl} as part of the
 * Phase 4 Spring-component decomposition (parallel slice — VNApp). The
 * {@code VirtualNetworkApplianceManagerImpl} keeps thin delegating wrappers so
 * that the {@link com.cloud.network.VirtualNetworkApplianceService} contract
 * and any existing test spies continue to work unchanged.
 */
public interface RouterUpgradeService {

    /**
     * Switch a stopped virtual router over to a new system service offering.
     *
     * <p>Validates that the router exists, that the caller has access to it,
     * that the new offering is a system offering, that the router is stopped,
     * and that the new disk offering's local-storage preference matches the
     * router's current root volume placement. If validation passes, the
     * router's service offering id is updated in the database.
     *
     * @param cmd the {@link UpgradeRouterCmd} containing the router id and the
     *            target service offering id
     * @return the refreshed router record after the upgrade
     */
    com.cloud.network.router.VirtualRouter upgradeRouter(UpgradeRouterCmd cmd);

    /**
     * Schedule async reboot jobs to refresh the template of any routers
     * matched by the command's filters (router / domain+account / cluster /
     * pod / zone — exactly one is permitted).
     *
     * @param cmd the {@link UpgradeRouterTemplateCmd} describing the scope of
     *            routers to refresh
     * @return the list of {@code AsyncJob} ids that were submitted, or
     *         {@code null} if no routers matched
     */
    List<Long> upgradeRouterTemplate(UpgradeRouterTemplateCmd cmd);

    /**
     * For every router in the list whose template is out of date, submit an
     * async {@code RebootRouterCmd} job. Throws
     * {@link com.cloud.utils.exception.CloudRuntimeException} for the first
     * router that is already on the latest template (preserves existing
     * behaviour).
     *
     * @param routers candidate routers to reboot for template refresh
     * @return list of submitted async job ids (one per router that needed a
     *         reboot)
     */
    List<Long> rebootRoutersForTemplateUpgrade(List<DomainRouterVO> routers);
}
