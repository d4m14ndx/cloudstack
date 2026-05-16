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

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.router.RebootRouterCmd;
import org.apache.cloudstack.api.command.admin.router.UpgradeRouterCmd;
import org.apache.cloudstack.api.command.admin.router.UpgradeRouterTemplateCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import com.cloud.api.ApiAsyncJobDispatcher;
import com.cloud.api.ApiGsonHelper;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.DomainRouterDao;

/**
 * Virtual-router upgrade helpers — extracted from
 * {@link VirtualNetworkApplianceManagerImpl}.
 *
 * @see RouterUpgradeService
 */
@Component
public class RouterUpgradeServiceImpl implements RouterUpgradeService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected DomainRouterDao routerDao;
    @Inject
    protected AccountManager accountManager;
    @Inject
    protected EntityManager entityManager;
    @Inject
    protected VirtualMachineManager itManager;
    @Inject
    protected AsyncJobManager asyncManager;
    @Inject
    protected ApiAsyncJobDispatcher asyncDispatcher;

    @Autowired
    @Qualifier("networkHelper")
    protected NetworkHelper networkHelper;

    @Override
    @DB
    public VirtualRouter upgradeRouter(final UpgradeRouterCmd cmd) {
        final Long routerId = cmd.getId();
        final Long serviceOfferingId = cmd.getServiceOfferingId();
        final Account caller = CallContext.current().getCallingAccount();

        final DomainRouterVO router = routerDao.findById(routerId);
        if (router == null) {
            throw new InvalidParameterValueException("Unable to find router with id " + routerId);
        }

        accountManager.checkAccess(caller, null, true, router);

        if (router.getServiceOfferingId() == serviceOfferingId) {
            logger.debug("Router: {} already has service offering: {}", router, serviceOfferingId);
            return routerDao.findById(routerId);
        }

        final ServiceOffering newServiceOffering = entityManager.findById(ServiceOffering.class, serviceOfferingId);
        if (newServiceOffering == null) {
            throw new InvalidParameterValueException("Unable to find service offering with id " + serviceOfferingId);
        }
        DiskOffering newDiskOffering = entityManager.findById(DiskOffering.class, newServiceOffering.getDiskOfferingId());
        if (newDiskOffering == null) {
            throw new InvalidParameterValueException("Unable to find disk offering: " + newServiceOffering.getDiskOfferingId());
        }

        // check if it is a system service offering, if yes return with error as
        // it cannot be used for user vms
        if (!newServiceOffering.isSystemUse()) {
            throw new InvalidParameterValueException(String.format("Cannot upgrade router vm to a non system service offering %s", newServiceOffering));
        }

        // Check that the router is stopped
        if (!router.getState().equals(VirtualMachine.State.Stopped)) {
            logger.warn("Unable to upgrade router " + router + " in state " + router.getState());
            throw new InvalidParameterValueException("Unable to upgrade router " + router + " in state " + router.getState()
                    + "; make sure the router is stopped and not in an error state before upgrading.");
        }

        // Check that the service offering being upgraded to has the same
        // storage pool preference as the VM's current service
        // offering
        if (itManager.isRootVolumeOnLocalStorage(routerId) != newDiskOffering.isUseLocalStorage()) {
            throw new InvalidParameterValueException(String.format(
                    "Can't upgrade, due to new local storage status : %s is different from current local storage status of router %s",
                    newDiskOffering.isUseLocalStorage(), router));
        }

        router.setServiceOfferingId(serviceOfferingId);
        if (routerDao.update(routerId, router)) {
            return routerDao.findById(routerId);
        } else {
            throw new CloudRuntimeException("Unable to upgrade router " + router);
        }
    }

    @Override
    public List<Long> upgradeRouterTemplate(final UpgradeRouterTemplateCmd cmd) {

        List<DomainRouterVO> routers = new ArrayList<>();
        int params = 0;

        final Long routerId = cmd.getId();
        if (routerId != null) {
            params++;
            final DomainRouterVO router = routerDao.findById(routerId);
            if (router != null) {
                routers.add(router);
            }
        }

        final Long domainId = cmd.getDomainId();
        if (domainId != null) {
            final String accountName = cmd.getAccount();
            // List by account, if account Name is specified along with domainId
            if (accountName != null) {
                final Account account = accountManager.getActiveAccountByName(accountName, domainId);
                if (account == null) {
                    throw new InvalidParameterValueException("Account :" + accountName + " does not exist in domain: " + domainId);
                }
                routers = routerDao.listRunningByAccountId(account.getId());
            } else {
                // List by domainId, account name not specified
                routers = routerDao.listRunningByDomain(domainId);
            }
            params++;
        }

        final Long clusterId = cmd.getClusterId();
        if (clusterId != null) {
            params++;
            routers = routerDao.listRunningByClusterId(clusterId);
        }

        final Long podId = cmd.getPodId();
        if (podId != null) {
            params++;
            routers = routerDao.listRunningByPodId(podId);
        }

        final Long zoneId = cmd.getZoneId();
        if (zoneId != null) {
            params++;
            routers = routerDao.listRunningByDataCenter(zoneId);
        }

        if (params > 1) {
            throw new InvalidParameterValueException("Multiple parameters not supported. Specify only one among routerId/zoneId/podId/clusterId/accountId/domainId");
        }

        if (routers != null) {
            return rebootRoutersForTemplateUpgrade(routers);
        }

        return null;
    }

    @Override
    public List<Long> rebootRoutersForTemplateUpgrade(final List<DomainRouterVO> routers) {
        final List<Long> jobIds = new ArrayList<>();
        for (final DomainRouterVO router : routers) {
            if (!networkHelper.checkRouterTemplateVersion(router)) {
                logger.debug("Upgrading template for router: {}", router);
                final Map<String, String> params = new HashMap<>();
                params.put("ctxUserId", "1");
                params.put("ctxAccountId", "" + router.getAccountId());

                final RebootRouterCmd rebootCmd = new RebootRouterCmd();
                ComponentContext.inject(rebootCmd);
                params.put("id", "" + router.getId());
                params.put("ctxStartEventId", "1");
                final AsyncJobVO job = new AsyncJobVO("", User.UID_SYSTEM, router.getAccountId(), RebootRouterCmd.class.getName(),
                        ApiGsonHelper.getBuilder().create().toJson(params),
                        router.getId(), rebootCmd.getApiResourceType() != null ? rebootCmd.getApiResourceType().toString() : null, null);
                job.setDispatcher(asyncDispatcher.getName());
                final long jobId = asyncManager.submitAsyncJob(job);
                jobIds.add(jobId);
            } else {
                logger.debug("Router: {} is already at the latest version. No upgrade required", router);
                throw new CloudRuntimeException("Router is already at the latest version. No upgrade required");
            }
        }
        return jobIds;
    }
}
