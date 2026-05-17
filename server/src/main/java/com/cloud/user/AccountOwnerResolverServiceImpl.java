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

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ApiErrorCode;
import org.apache.cloudstack.api.ServerApiException;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Component;

import com.cloud.domain.Domain;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.projects.Project;
import com.cloud.projects.ProjectManager;
import com.cloud.user.dao.AccountDao;

/**
 * Default {@link AccountOwnerResolverService} — owner / account-id resolution
 * extracted from {@link AccountManagerImpl}. Delegates back to
 * {@link AccountService} for {@code isAdmin}, the active-account lookups and
 * the security-checker-backed {@code checkAccess(caller, domain)} call so the
 * existing chain in {@link AccountManagerImpl} remains the single source of
 * truth.
 *
 * @see AccountOwnerResolverService
 */
@Component
public class AccountOwnerResolverServiceImpl implements AccountOwnerResolverService {

    @Inject
    protected AccountDao accountDao;

    @Inject
    protected DomainManager domainManager;

    @Inject
    protected ProjectManager projectManager;

    @Inject
    protected AccountService accountService;

    @Override
    public Account finalizeOwner(Account caller, String accountName, Long domainId, Long projectId) {
        // don't default the owner to the system account
        if (caller.getId() == Account.ACCOUNT_ID_SYSTEM && ((accountName == null || domainId == null) && projectId == null)) {
            throw new InvalidParameterValueException("Account and domainId are needed for resource creation");
        }

        // projectId and account/domainId can't be specified together
        if ((accountName != null && domainId != null) && projectId != null) {
            throw new InvalidParameterValueException("ProjectId and account/domainId can't be specified together");
        }

        if (projectId != null) {
            Project project = projectManager.getProject(projectId);
            if (project == null) {
                throw new InvalidParameterValueException("Unable to find project by id=" + projectId);
            }

            if (!projectManager.canAccessProjectAccount(caller, project.getProjectAccountId())) {
                throw new PermissionDeniedException("Account " + caller + " is unauthorised to use project id=" + projectId);
            }

            return accountService.getAccount(project.getProjectAccountId());
        }

        if (accountService.isAdmin(caller.getId()) && accountName != null && domainId != null) {
            Domain domain = domainManager.getDomain(domainId);
            if (domain == null) {
                throw new InvalidParameterValueException("Unable to find the domain by id=" + domainId);
            }

            Account owner = accountDao.findActiveAccount(accountName, domainId);
            if (owner == null) {
                throw new InvalidParameterValueException(String.format("Unable to find account %s in domain %s", accountName, domain));
            }
            accountService.checkAccess(caller, domain);

            return owner;
        } else if (!accountService.isAdmin(caller.getId()) && accountName != null && domainId != null) {
            if (!accountName.equals(caller.getAccountName()) || domainId != caller.getDomainId()) {
                throw new PermissionDeniedException("Can't create/list resources for account " + accountName + " in domain " + domainId + ", permission denied");
            } else {
                return caller;
            }
        } else {
            if (accountName != null && domainId == null) {
                throw new InvalidParameterValueException("AccountName and domainId must be specified together");
            }
            // regular user can't create/list resources for other people
            return caller;
        }
    }

    @Override
    public Long finalizeAccountId(final String accountName, final Long domainId, final Long projectId, final boolean enabledOnly) {
        if (accountName != null) {
            if (domainId == null) {
                throw new InvalidParameterValueException("Account must be specified with domainId parameter");
            }

            final Domain domain = domainManager.getDomain(domainId);
            if (domain == null) {
                throw new InvalidParameterValueException("Unable to find domain by id");
            }

            final Account account = accountService.getActiveAccountByName(accountName, domainId);
            if (account != null && account.getType() != Account.Type.PROJECT) {
                if (!enabledOnly || account.getState() == Account.State.ENABLED) {
                    return account.getId();
                } else {
                    throw new PermissionDeniedException(String.format("Can't add resources to the account %s in state=%s as it's no longer active", account, account.getState()));
                }
            } else {
                throw new InvalidParameterValueException("Unable to find account by name " + accountName + " in domain with specified id");
            }
        }

        if (projectId != null) {
            final Project project = projectManager.getProject(projectId);
            if (project != null) {
                if (!enabledOnly || project.getState() == Project.State.Active) {
                    return project.getProjectAccountId();
                } else {
                    final PermissionDeniedException ex = new PermissionDeniedException(
                            "Can't add resources to the project with specified projectId in state=" + project.getState() + " as it's no longer active");
                    ex.addProxyObject(project.getUuid(), "projectId");
                    throw ex;
                }
            } else {
                throw new InvalidParameterValueException("Unable to find project by id");
            }
        }
        return null;
    }

    @Override
    public Long finalizeAccountId(Long accountId, String accountName, Long domainId, Long projectId) {
        if (projectId != null) {
            if (ObjectUtils.anyNotNull(accountId, accountName)) {
                throw new ServerApiException(ApiErrorCode.PARAM_ERROR, "Project and account can not be specified together.");
            }
            return getActiveProjectAccountByProjectId(projectId);
        }
        if (accountId != null) {
            if (accountService.getActiveAccountById(accountId) != null) {
                return accountId;
            }
            throw new InvalidParameterValueException(String.format("Unable to find account with ID [%s].", accountId));
        }

        if (accountName == null && domainId == null) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, String.format("Either %s or %s must be informed.", ApiConstants.ACCOUNT_ID, ApiConstants.PROJECT_ID));
        }

        try {
            Account activeAccount = accountService.getActiveAccountByName(accountName, domainId);
            if (activeAccount != null) {
                return activeAccount.getId();
            }
        } catch (InvalidParameterValueException exception) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, String.format("Both %s and %s are needed if using either. Consider using %s instead.",
                    ApiConstants.ACCOUNT, ApiConstants.DOMAIN_ID, ApiConstants.ACCOUNT_ID));
        }
        throw new InvalidParameterValueException(String.format("Unable to find account by name [%s] on domain [%s].", accountName, domainId));
    }

    @Override
    public long getActiveProjectAccountByProjectId(long projectId) {
        Project project = projectManager.getProject(projectId);
        if (project == null) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, String.format("Unable to find project with ID [%s].", projectId));
        }
        if (project.getState() != Project.State.Active) {
            throw new ServerApiException(ApiErrorCode.PARAM_ERROR, String.format("Project with ID [%s] is not active.", projectId));
        }
        return project.getProjectAccountId();
    }
}
