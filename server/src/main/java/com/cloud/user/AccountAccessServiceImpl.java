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
package com.cloud.user;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.ControlledEntity;
import org.apache.cloudstack.acl.InfrastructureEntity;
import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.affinity.AffinityGroup;
import org.apache.cloudstack.backup.BackupOffering;
import org.apache.cloudstack.context.CallContext;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.ApiDBUtils;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.Domain;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.network.Network;
import com.cloud.network.router.VirtualRouter;
import com.cloud.network.vpc.VpcOffering;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.exception.CloudRuntimeException;

@Component
public class AccountAccessServiceImpl implements AccountAccessService {
    private static final Logger logger = LogManager.getLogger(AccountAccessServiceImpl.class);

    @Inject
    protected AccountDao accountDao;
    @Inject
    protected DomainManager domainManager;
    @Inject
    protected DataCenterDao dataCenterDao;

    protected List<SecurityChecker> securityCheckers;

    @Inject
    public void setSecurityCheckers(List<SecurityChecker> securityCheckers) {
        this.securityCheckers = securityCheckers;
    }

    @Override
    public boolean isAdmin(Long accountId) {
        if (accountId != null) {
            AccountVO acct = accountDao.findById(accountId);
            if (acct == null) {
                return false;  //account is deleted or does not exist
            }
            return (isRootAdmin(accountId)) || (isDomainAdmin(accountId)) || (isResourceDomainAdmin(accountId)) || (acct.getType() == Account.Type.READ_ONLY_ADMIN);

        }
        return false;
    }

    @Override
    public boolean isRootAdmin(Long accountId) {
        if (accountId != null) {
            AccountVO acct = accountDao.findById(accountId);
            if (acct == null) {
                return false;  //account is deleted or does not exist
            }
            for (SecurityChecker checker : securityCheckers) {
                try {
                    if (checker.checkAccess(acct, null, null, "SystemCapability")) {
                        if (logger.isTraceEnabled()) {
                            logger.trace("Root Access granted to " + acct + " by " + checker.getName());
                        }
                        return true;
                    }
                } catch (PermissionDeniedException ex) {
                    return false;
                }
            }
        }
        return false;
    }

    @Override
    public boolean isDomainAdmin(Long accountId) {
        if (accountId != null) {
            AccountVO acct = accountDao.findById(accountId);
            if (acct == null) {
                return false;  //account is deleted or does not exist
            }
            for (SecurityChecker checker : securityCheckers) {
                try {
                    if (checker.checkAccess(acct, null, null, "DomainCapability")) {
                        if (logger.isTraceEnabled()) {
                            logger.trace("DomainAdmin Access granted to " + acct + " by " + checker.getName());
                        }
                        return true;
                    }
                } catch (PermissionDeniedException ex) {
                    return false;
                }
            }
        }
        return false;
    }

    @Override
    public boolean isNormalUser(long accountId) {
        AccountVO acct = accountDao.findById(accountId);
        return acct != null && acct.getType() == Account.Type.NORMAL;
    }

    @Override
    public boolean isResourceDomainAdmin(Long accountId) {
        if (accountId != null) {
            AccountVO acct = accountDao.findById(accountId);
            if (acct == null) {
                return false;  //account is deleted or does not exist
            }
            for (SecurityChecker checker : securityCheckers) {
                try {
                    if (checker.checkAccess(acct, null, null, "DomainResourceCapability")) {
                        if (logger.isTraceEnabled()) {
                            logger.trace("ResourceDomainAdmin Access granted to " + acct + " by " + checker.getName());
                        }
                        return true;
                    }
                } catch (PermissionDeniedException ex) {
                    return false;
                }
            }
        }
        return false;
    }

    @Override
    public boolean isInternalAccount(long accountId) {
        Account account = accountDao.findById(accountId);
        if (account == null) {
            return false;  //account is deleted or does not exist
        }
        return isRootAdmin(accountId) || (account.getType() == Account.Type.ADMIN);
    }

    @Override
    public void checkAccess(Account caller, Domain domain) throws PermissionDeniedException {
        for (SecurityChecker checker : securityCheckers) {
            if (checker.checkAccess(caller, domain)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Access granted to " + caller + " to " + domain + " by " + checker.getName());
                }
                return;
            }
        }
        throw new PermissionDeniedException("There's no way to confirm " + caller + " has access to " + domain);
    }

    @Override
    public void checkAccess(Account caller, AccessType accessType, boolean sameOwner, ControlledEntity... entities) {
        checkAccess(caller, accessType, sameOwner, null, entities);
    }

    @Override
    public void checkAccess(Account caller, AccessType accessType, boolean sameOwner, String apiName, ControlledEntity... entities) {

        //check for the same owner
        Long ownerId = null;
        ControlledEntity prevEntity = null;
        if (sameOwner) {
            for (ControlledEntity entity : entities) {
                if (ownerId == null) {
                    ownerId = entity.getAccountId();
                } else if (!ownerId.equals(entity.getAccountId())) {
                    throw new PermissionDeniedException("Entity " + entity + " and entity " + prevEntity + " belong to different accounts");
                }
                prevEntity = entity;
            }
        }

        if (caller.getId() == Account.ACCOUNT_ID_SYSTEM || isRootAdmin(caller.getId())) {
            // no need to make permission checks if the system/root admin makes the call
            if (logger.isTraceEnabled()) {
                logger.trace("No need to make permission check for System/RootAdmin account, returning true");
            }

            return;
        }

        HashMap<Long, List<ControlledEntity>> domains = new HashMap<>();

        for (ControlledEntity entity : entities) {
            long domainId = entity.getDomainId();
            if (entity.getAccountId() != -1 && domainId == -1) { // If account exists domainId should too so calculate
                // it. This condition might be hit for templates or entities which miss domainId in their tables
                Account account = ApiDBUtils.findAccountById(entity.getAccountId());
                domainId = account != null ? account.getDomainId() : -1;
            }
            if (entity.getAccountId() != -1 && domainId != -1 && !(entity instanceof VirtualMachineTemplate)
                    && !(entity instanceof Network && (accessType == AccessType.UseEntry || accessType == AccessType.OperateEntry))
                    && !(entity instanceof AffinityGroup) && !(entity instanceof VirtualRouter)) {
                List<ControlledEntity> toBeChecked = domains.get(entity.getDomainId());
                // for templates, we don't have to do cross domains check
                if (toBeChecked == null) {
                    toBeChecked = new ArrayList<>();
                    domains.put(domainId, toBeChecked);
                }
                toBeChecked.add(entity);
            }
            boolean granted = false;
            for (SecurityChecker checker : securityCheckers) {
                if (checker.checkAccess(caller, entity, accessType, apiName)) {
                    if (logger.isDebugEnabled()) {
                        User user = CallContext.current().getCallingUser();
                        String userName = "";
                        if (user != null) {
                            userName = user.getUsername();
                        }
                        logger.debug("Access to {} granted to {} by {} on behalf of user {}", entity, caller, checker.getName(), userName);
                    }
                    granted = true;
                    break;
                }
            }

            if (!granted) {
                assert false : "How can all of the security checkers pass on checking this check: " + entity;
                throw new PermissionDeniedException("There's no way to confirm " + caller + " has access to " + entity);
            }
        }

        for (Map.Entry<Long, List<ControlledEntity>> domain : domains.entrySet()) {
            for (SecurityChecker checker : securityCheckers) {
                Domain d = domainManager.getDomain(domain.getKey());
                if (d == null || d.getRemoved() != null) {
                    throw new PermissionDeniedException("Domain is not found.", caller, domain.getValue());
                }
                try {
                    checker.checkAccess(caller, d);
                } catch (PermissionDeniedException e) {
                    e.addDetails(caller, domain.getValue());
                    throw e;
                }
            }
        }

        // check that resources belong to the same account

    }

    @Override
    public void validateAccountHasAccessToResource(Account account, AccessType accessType, Object resource) {
        Class<?> resourceClass = resource.getClass();
        if (ControlledEntity.class.isAssignableFrom(resourceClass)) {
            checkAccess(account, accessType, true, (ControlledEntity) resource);
        } else if (Domain.class.isAssignableFrom(resourceClass)) {
            checkAccess(account, (Domain) resource);
        } else if (InfrastructureEntity.class.isAssignableFrom(resourceClass)) {
            logger.trace("Validation of access to infrastructure entity has been disabled in CloudStack version 4.4.");
        }
        logger.debug("Account [{}] has access to resource.", account);
    }

    @Override
    public Long checkAccessAndSpecifyAuthority(Account caller, Long zoneId) {
        // We just care for resource domain admins for now, and they should be permitted to see only their zone.
        if (isResourceDomainAdmin(caller.getAccountId())) {
            if (zoneId == null) {
                return getZoneIdForAccount(caller);
            } else if (zoneId.compareTo(getZoneIdForAccount(caller)) != 0) {
                throw new PermissionDeniedException("Caller " + caller + "is not allowed to access the zone " + zoneId);
            } else {
                return zoneId;
            }
        } else {
            return zoneId;
        }
    }

    private Long getZoneIdForAccount(Account account) {

        // Currently just for resource domain admin
        List<DataCenterVO> dcList = dataCenterDao.findZonesByDomainId(account.getDomainId());
        if (CollectionUtils.isNotEmpty(dcList)) {
            return dcList.get(0).getId();
        } else {
            throw new CloudRuntimeException("Failed to find any private zone for Resource domain admin.");
        }

    }

    @Override
    public void checkAccess(Account account, ServiceOffering so, DataCenter zone) throws PermissionDeniedException {
        for (SecurityChecker checker : securityCheckers) {
            if (checker.checkAccess(account, so, zone)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Access granted to " + account + " to " + so + " by " + checker.getName());
                }
                return;
            }
        }

        assert false : "How can all of the security checkers pass on checking this caller?";
        throw new PermissionDeniedException("There's no way to confirm " + account + " has access to " + so);
    }

    @Override
    public void checkAccess(Account account, DiskOffering dof, DataCenter zone) throws PermissionDeniedException {
        for (SecurityChecker checker : securityCheckers) {
            if (checker.checkAccess(account, dof, zone)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Access granted to " + account + " to " + dof + " by " + checker.getName());
                }
                return;
            }
        }

        assert false : "How can all of the security checkers pass on checking this caller?";
        throw new PermissionDeniedException("There's no way to confirm " + account + " has access to " + dof);
    }

    @Override
    public void checkAccess(Account account, NetworkOffering nof, DataCenter zone) throws PermissionDeniedException {
        for (SecurityChecker checker : securityCheckers) {
            if (checker.checkAccess(account, nof, zone)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Access granted to " + account + " to " + nof + " by " + checker.getName());
                }
                return;
            }
        }

        assert false : "How can all of the security checkers pass on checking this caller?";
        throw new PermissionDeniedException("There's no way to confirm " + account + " has access to " + nof);
    }

    @Override
    public void checkAccess(Account account, VpcOffering vof, DataCenter zone) throws PermissionDeniedException {
        for (SecurityChecker checker : securityCheckers) {
            if (checker.checkAccess(account, vof, zone)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Access granted to " + account + " to " + vof + " by " + checker.getName());
                }
                return;
            }
        }

        assert false : "How can all of the security checkers pass on checking this caller?";
        throw new PermissionDeniedException("There's no way to confirm " + account + " has access to " + vof);
    }

    @Override
    public void checkAccess(Account account, BackupOffering bof) throws PermissionDeniedException {
        for (SecurityChecker checker : securityCheckers) {
            if (checker.checkAccess(account, bof)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Access granted to " + account + " to " + bof + " by " + checker.getName());
                }
                return;
            }
        }

        assert false : "How can all of the security checkers pass on checking this caller?";
        throw new PermissionDeniedException("There's no way to confirm " + account + " has access to " + bof);
    }

    @Override
    public void checkAccess(User user, ControlledEntity entity) throws PermissionDeniedException {
        for (SecurityChecker checker : securityCheckers) {
            if (checker.checkAccess(user, entity)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Access granted to " + user + "to " + entity + "by " + checker.getName());
                }
                return;
            }
        }
        throw new PermissionDeniedException("There's no way to confirm " + user + " has access to " + entity);
    }
}
