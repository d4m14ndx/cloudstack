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
import java.util.List;

import jakarta.inject.Inject;

import org.springframework.stereotype.Component;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.command.user.network.CreateNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.RemoveNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.ResetNetworkPermissionsCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.network.NetworkPermissionVO;
import org.apache.cloudstack.network.dao.NetworkPermissionDao;

import com.cloud.domain.Domain;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectManager;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;

/**
 * Per-account network sharing operations — extracted from
 * {@link NetworkServiceImpl}.
 *
 * @see NetworkPermissionService
 */
@Component
public class NetworkPermissionServiceImpl implements NetworkPermissionService {

    @Inject
    private NetworkDao networksDao;
    @Inject
    private NetworkPermissionDao networkPermissionDao;
    @Inject
    private AccountManager accountMgr;
    @Inject
    private AccountDao accountDao;
    @Inject
    private DomainDao domainDao;
    @Inject
    private ProjectManager projectMgr;

    @Override
    public List<? extends NetworkPermission> listNetworkPermissions(ListNetworkPermissionsCmd cmd) {
        final Long networkId = cmd.getNetworkId();
        NetworkVO network = networksDao.findById(networkId);
        if (network == null) {
            throw new InvalidParameterValueException("unable to find network with id " + networkId);
        }
        final Account caller = CallContext.current().getCallingAccount();
        accountMgr.checkAccess(caller, AccessType.OperateEntry, true, network);

        return networkPermissionDao.findByNetwork(networkId);
    }

    @Override
    public boolean createNetworkPermissions(CreateNetworkPermissionsCmd cmd) {
        final Long id = cmd.getNetworkId();
        List<String> accountNames = cmd.getAccountNames();
        List<Long> accountIds = cmd.getAccountIds();
        List<Long> projectIds = cmd.getProjectIds();

        final Account caller = CallContext.current().getCallingAccount();
        NetworkVO network = validateNetworkPermissionParameters(caller, id);

        accountIds = populateAccounts(caller, accountIds, network.getDomainId(), accountNames, projectIds);

        final List<Long> accountIdsFinal = accountIds;
        final Account owner = accountMgr.getAccount(network.getAccountId());
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                for (Long accountId : accountIdsFinal) {
                    Account permittedAccount = accountDao.findActiveAccountById(accountId, network.getDomainId());
                    if (permittedAccount != null) {
                        if (permittedAccount.getId() == owner.getId()) {
                            continue; // don't grant permission to the network owner, they implicitly have permission
                        }
                        NetworkPermissionVO existingPermission = networkPermissionDao.findByNetworkAndAccount(id, permittedAccount.getId());
                        if (existingPermission == null) {
                            NetworkPermissionVO networkPermission = new NetworkPermissionVO(id, permittedAccount.getId());
                            networkPermissionDao.persist(networkPermission);
                        }
                    } else {
                        throw new InvalidParameterValueException("Unable to find account " + accountId + " in the domain of network " + network + ". No permissions is added");
                    }
                }
            }
        });

        return true;
    }

    @Override
    public boolean removeNetworkPermissions(RemoveNetworkPermissionsCmd cmd) {
        final Long id = cmd.getNetworkId();
        List<String> accountNames = cmd.getAccountNames();
        List<Long> accountIds = cmd.getAccountIds();
        List<Long> projectIds = cmd.getProjectIds();

        final Account caller = CallContext.current().getCallingAccount();
        NetworkVO network = validateNetworkPermissionParameters(caller, id);

        accountIds = populateAccounts(caller, accountIds, network.getDomainId(), accountNames, projectIds);

        networkPermissionDao.removePermissions(id, accountIds);

        return true;
    }

    @Override
    public boolean resetNetworkPermissions(ResetNetworkPermissionsCmd cmd) {
        final Long id = cmd.getNetworkId();

        final Account caller = CallContext.current().getCallingAccount();
        validateNetworkPermissionParameters(caller, id);

        networkPermissionDao.removeAllPermissions(id);

        return true;
    }

    protected NetworkVO validateNetworkPermissionParameters(Account caller, Long id) {

        final NetworkVO network = networksDao.findById(id);

        if (network == null) {
            throw new InvalidParameterValueException("unable to find network with id " + id);
        }

        if (network.getAclType() == ACLType.Domain) {
            throw new InvalidParameterValueException("network is already shared in domain");
        }

        if (network.getVpcId() != null) {
            throw new InvalidParameterValueException("VPC tiers cannot be shared");
        }

        accountMgr.checkAccess(caller, AccessType.OperateEntry, true, network);

        final Account owner = accountMgr.getAccount(network.getAccountId());
        if (owner.getType() == Account.Type.PROJECT) {
            // Currently project owned networks cannot be shared outside project but is available to all users within project by default.
            throw new InvalidParameterValueException("Update network permissions is an invalid operation on network " + network.getName()
                    + ". Project owned networks cannot be shared outside network.");
        }

        //Only admin or owner of the network should be able to change its permissions
        if (caller.getId() != owner.getId() && !accountMgr.isAdmin(caller.getId())) {
            throw new InvalidParameterValueException("Unable to grant permission to account " + caller.getAccountName() + " as it is neither admin nor owner or the network");
        }

        return network;
    }

    protected List<Long> populateAccounts(Account caller, List<Long> accountIds, Long domainId, List<String> accountNames, List<Long> projectIds) {
        if (accountIds == null) {
            accountIds = new ArrayList<Long>();
        }
        // convert projectIds to accountIds
        if (projectIds != null) {
            accountIds.addAll(convertProjectIdsToAccountIds(caller, projectIds));
        }
        // convert accountNames to accountIds
        if (accountNames != null) {
            accountIds.addAll(convertAccountNamesToAccountIds(caller, domainId, accountNames));
        }
        final Domain domain = domainDao.findById(domainId);
        for (Long accountId : accountIds) {
            Account permittedAccount = accountDao.findActiveAccountById(accountId, domain.getId());
            if (permittedAccount == null) {
                throw new InvalidParameterValueException("Unable to find account " + accountId + " in domain id=" + domain.getUuid() + ". No permissions is removed");
            }
        }
        return accountIds;
    }

    protected List<Long> convertProjectIdsToAccountIds(final Account caller, final List<Long> projectIds) {
        List<Long> accountIds = new ArrayList<Long>();
        for (Long projectId : projectIds) {
            Project project = projectMgr.getProject(projectId);
            if (project == null) {
                throw new InvalidParameterValueException("Unable to find project by id " + projectId);
            }

            if (!projectMgr.canAccessProjectAccount(caller, project.getProjectAccountId())) {
                throw new InvalidParameterValueException(String.format("Account %s can't access project id=%s", caller, project.getUuid()));
            }
            accountIds.add(project.getProjectAccountId());
        }
        return accountIds;
    }

    protected List<Long> convertAccountNamesToAccountIds(final Account caller, final Long domainId, final List<String> accountNames) {
        List<Long> accountIds = new ArrayList<Long>();
        for (String accountName : accountNames) {
            Account permittedAccount = accountDao.findActiveAccount(accountName, domainId);
            if (permittedAccount == null) {
                throw new InvalidParameterValueException("Unable to find account by name " + accountName);
            }
            if (permittedAccount.getId() != caller.getId()) {
                accountIds.add(permittedAccount.getId());
            }
        }
        return accountIds;
    }
}
