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

import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.ControlledEntity;
import org.springframework.stereotype.Component;

import com.cloud.api.query.vo.ControlledViewEntity;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.projects.Project;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.projects.ProjectInvitationVO;
import com.cloud.projects.ProjectManager;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

/**
 * Default {@link AclSearchBuilderService} — pure SearchBuilder / SearchCriteria
 * plumbing extracted from {@link AccountManagerImpl}. Delegates access checks
 * back to the {@link AccountService} interface so the existing
 * {@code SecurityChecker} chain in {@link AccountManagerImpl} continues to be
 * the single source of truth for "can this caller see this domain / account".
 *
 * @see AclSearchBuilderService
 */
@Component
public class AclSearchBuilderServiceImpl implements AclSearchBuilderService {

    @Inject
    protected AccountDao accountDao;

    @Inject
    protected DomainDao domainDao;

    @Inject
    protected ProjectManager projectManager;

    @Inject
    protected AccountService accountService;

    @Override
    public void buildACLSearchBuilder(SearchBuilder<? extends ControlledEntity> sb, Long domainId, boolean isRecursive,
            List<Long> permittedAccounts, ListProjectResourcesCriteria listProjectResourcesCriteria) {

        if (sb.entity() instanceof IPAddressVO) {
            sb.and("accountIdIN", ((IPAddressVO)sb.entity()).getAllocatedToAccountId(), SearchCriteria.Op.IN);
            sb.and("domainId", ((IPAddressVO)sb.entity()).getAllocatedInDomainId(), SearchCriteria.Op.EQ);
        } else if (sb.entity() instanceof ProjectInvitationVO) {
            sb.and("accountIdIN", ((ProjectInvitationVO)sb.entity()).getForAccountId(), SearchCriteria.Op.IN);
            sb.and("domainId", ((ProjectInvitationVO)sb.entity()).getInDomainId(), SearchCriteria.Op.EQ);
        } else {
            sb.and("accountIdIN", sb.entity().getAccountId(), SearchCriteria.Op.IN);
            sb.and("domainId", sb.entity().getDomainId(), SearchCriteria.Op.EQ);
        }

        if (((permittedAccounts.isEmpty()) && (domainId != null) && isRecursive)) {
            // if accountId isn't specified, we can do a domain match for the admin case if isRecursive is true
            SearchBuilder<DomainVO> domainSearch = domainDao.createSearchBuilder();
            domainSearch.and("path", domainSearch.entity().getPath(), SearchCriteria.Op.LIKE);

            if (sb.entity() instanceof IPAddressVO) {
                sb.join("domainSearch", domainSearch, ((IPAddressVO)sb.entity()).getAllocatedInDomainId(), domainSearch.entity().getId(), JoinBuilder.JoinType.INNER);
            } else if (sb.entity() instanceof ProjectInvitationVO) {
                sb.join("domainSearch", domainSearch, ((ProjectInvitationVO)sb.entity()).getInDomainId(), domainSearch.entity().getId(), JoinBuilder.JoinType.INNER);
            } else {
                sb.join("domainSearch", domainSearch, sb.entity().getDomainId(), domainSearch.entity().getId(), JoinBuilder.JoinType.INNER);
            }

        }
        if (listProjectResourcesCriteria != null) {
            SearchBuilder<AccountVO> accountSearch = accountDao.createSearchBuilder();
            if (listProjectResourcesCriteria == Project.ListProjectResourcesCriteria.ListProjectResourcesOnly) {
                accountSearch.and("type", accountSearch.entity().getType(), SearchCriteria.Op.EQ);
            } else if (listProjectResourcesCriteria == Project.ListProjectResourcesCriteria.SkipProjectResources) {
                accountSearch.and("type", accountSearch.entity().getType(), SearchCriteria.Op.NEQ);
            }

            if (sb.entity() instanceof IPAddressVO) {
                sb.join("accountSearch", accountSearch, ((IPAddressVO)sb.entity()).getAllocatedToAccountId(), accountSearch.entity().getId(), JoinBuilder.JoinType.INNER);
            } else if (sb.entity() instanceof ProjectInvitationVO) {
                sb.join("accountSearch", accountSearch, ((ProjectInvitationVO)sb.entity()).getForAccountId(), accountSearch.entity().getId(), JoinBuilder.JoinType.INNER);
            } else {
                sb.join("accountSearch", accountSearch, sb.entity().getAccountId(), accountSearch.entity().getId(), JoinBuilder.JoinType.INNER);
            }
        }
    }

    @Override
    public void buildACLSearchCriteria(SearchCriteria<? extends ControlledEntity> sc, Long domainId, boolean isRecursive,
            List<Long> permittedAccounts, ListProjectResourcesCriteria listProjectResourcesCriteria) {

        if (listProjectResourcesCriteria != null) {
            sc.setJoinParameters("accountSearch", "type", Account.Type.PROJECT);
        }

        if (!permittedAccounts.isEmpty()) {
            sc.setParameters("accountIdIN", permittedAccounts.toArray());
        } else if (domainId != null) {
            DomainVO domain = domainDao.findById(domainId);
            if (isRecursive) {
                sc.setJoinParameters("domainSearch", "path", domain.getPath() + "%");
            } else {
                sc.setParameters("domainId", domainId);
            }
        }
    }

    @Override
    public void buildACLSearchParameters(Account caller, Long id, String accountName, Long projectId, List<Long> permittedAccounts,
            Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject, boolean listAll, boolean forProjectInvitation) {
        Long domainId = domainIdRecursiveListProject.first();
        if (domainId != null) {
            Domain domain = domainDao.findById(domainId);
            if (domain == null) {
                throw new InvalidParameterValueException("Unable to find domain by id " + domainId);
            }
            // check permissions
            accountService.checkAccess(caller, domain);
        }

        if (accountName != null) {
            if (projectId != null) {
                throw new InvalidParameterValueException("Account and projectId can't be specified together");
            }

            Account userAccount;
            Domain domain;
            if (domainId != null) {
                userAccount = accountDao.findActiveAccount(accountName, domainId);
                domain = domainDao.findById(domainId);
            } else {
                userAccount = accountDao.findActiveAccount(accountName, caller.getDomainId());
                domain = domainDao.findById(caller.getDomainId());
            }

            if (userAccount != null) {
                accountService.checkAccess(caller, null, false, userAccount);
                // check permissions
                permittedAccounts.add(userAccount.getId());
            } else {
                throw new InvalidParameterValueException("could not find account " + accountName + " in domain " + domain);
            }
        }

        // set project information
        if (projectId != null) {
            if (!forProjectInvitation) {
                if (projectId == -1L) {
                    domainIdRecursiveListProject.third(Project.ListProjectResourcesCriteria.ListProjectResourcesOnly);
                    if (caller.getType() != Account.Type.ADMIN) {
                        permittedAccounts.addAll(projectManager.listPermittedProjectAccounts(caller.getId()));
                        // permittedAccounts can be empty when the caller is not a part of any project (a domain account)
                        if (permittedAccounts.isEmpty() || listAll) {
                            permittedAccounts.add(caller.getId());
                        }
                    }
                    if (listAll) {
                        domainIdRecursiveListProject.third(ListProjectResourcesCriteria.ListAllIncludingProjectResources);
                    }
                } else {
                    Project project = projectManager.getProject(projectId);
                    if (project == null) {
                        throw new InvalidParameterValueException("Unable to find project by id " + projectId);
                    }
                    if (!projectManager.canAccessProjectAccount(caller, project.getProjectAccountId())) {
                        throw new PermissionDeniedException("Account " + caller + " can't access project id=" + projectId);
                    }
                    permittedAccounts.add(project.getProjectAccountId());
                }
            }
        } else {
            if (id == null) {
                domainIdRecursiveListProject.third(Project.ListProjectResourcesCriteria.SkipProjectResources);
            }
            if (permittedAccounts.isEmpty() && domainId == null) {
                if (caller.getType() == Account.Type.NORMAL) {
                    permittedAccounts.add(caller.getId());
                } else if (!listAll) {
                    if (id == null) {
                        permittedAccounts.add(caller.getId());
                    } else if (caller.getType() != Account.Type.ADMIN) {
                        domainIdRecursiveListProject.first(caller.getDomainId());
                        domainIdRecursiveListProject.second(true);
                    }
                } else if (domainId == null) {
                    if (caller.getType() == Account.Type.DOMAIN_ADMIN) {
                        domainIdRecursiveListProject.first(caller.getDomainId());
                        domainIdRecursiveListProject.second(true);
                    }
                }
            } else if (domainId != null) {
                if (caller.getType() == Account.Type.NORMAL) {
                    permittedAccounts.add(caller.getId());
                }
            }

        }

    }

    @Override
    public void buildACLViewSearchBuilder(SearchBuilder<? extends ControlledViewEntity> sb, Long domainId, boolean isRecursive,
            List<Long> permittedAccounts, ListProjectResourcesCriteria listProjectResourcesCriteria) {

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

    @Override
    public void buildACLViewSearchCriteria(SearchCriteria<? extends ControlledViewEntity> sc, Long domainId, boolean isRecursive,
            List<Long> permittedAccounts, ListProjectResourcesCriteria listProjectResourcesCriteria) {
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
}
