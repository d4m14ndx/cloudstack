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

import org.apache.cloudstack.acl.ControlledEntity;

import com.cloud.api.query.vo.ControlledViewEntity;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;

/**
 * Boilerplate for the list-API ACL filter pattern — the cluster of helpers
 * every {@code list*Cmd} handler reaches for when it has to translate the
 * caller's {@code (id, accountName, domainId, projectId, listAll)} parameters
 * into a {@link SearchBuilder} / {@link SearchCriteria} pair scoped to the
 * accounts and domains the caller can actually see.
 *
 * <p>Three concerns live here:
 * <ol>
 *   <li><b>{@link #buildACLSearchParameters}</b> — driver-side parameter
 *       resolution: validates the requested domain and account, looks up the
 *       project owner-account, and (for non-root callers) narrows the
 *       {@code permittedAccounts} list to the caller's own account / domain
 *       subtree. Mutates the {@code permittedAccounts} list and the
 *       {@code domainIdRecursiveListProject} ternary in place so the
 *       follow-up builder / criteria calls see the resolved scope.</li>
 *   <li><b>{@link #buildACLSearchBuilder} / {@link #buildACLSearchCriteria}</b>
 *       — applies the {@code accountIdIN}, {@code domainId}/{@code path}
 *       and project-account-type joins to a {@link ControlledEntity}-backed
 *       {@code SearchBuilder} / {@code SearchCriteria}, with special-cases
 *       for {@code IPAddressVO} (allocated-to / allocated-in columns) and
 *       {@code ProjectInvitationVO} (for-account / in-domain columns).</li>
 *   <li><b>{@link #buildACLViewSearchBuilder} / {@link #buildACLViewSearchCriteria}</b>
 *       — view-table equivalents that operate on
 *       {@link ControlledViewEntity}, where {@code accountId},
 *       {@code domainId}, {@code domainPath} and {@code accountType} are all
 *       flat columns on the view (no joins needed).</li>
 * </ol>
 *
 * <p>Extracted from {@link AccountManagerImpl} as part of the Phase 4
 * Spring-component decomposition — the 5th parallel slice on this file.
 * {@link AccountManagerImpl} keeps one-line delegating wrappers so the
 * {@link AccountManager} contract continues to work unchanged for every
 * {@code list*Cmd} caller across the network, backup, VPN and storage
 * managers.
 */
public interface AclSearchBuilderService {

    /**
     * Wire the standard ACL filter columns and joins onto {@code sb} —
     * {@code accountIdIN} (IN), {@code domainId} (EQ), the optional
     * recursive {@code domainSearch} join keyed on path, and the optional
     * {@code accountSearch} join used when
     * {@code listProjectResourcesCriteria} is set. Special-cases
     * {@link com.cloud.network.dao.IPAddressVO} (uses
     * {@code allocatedToAccountId} / {@code allocatedInDomainId}) and
     * {@link com.cloud.projects.ProjectInvitationVO} (uses
     * {@code forAccountId} / {@code inDomainId}); falls back to
     * {@code accountId} / {@code domainId} on the controlled entity for
     * everything else.
     */
    void buildACLSearchBuilder(SearchBuilder<? extends ControlledEntity> sb, Long domainId, boolean isRecursive,
            List<Long> permittedAccounts, ListProjectResourcesCriteria listProjectResourcesCriteria);

    /**
     * Bind the runtime values to the filter columns wired by
     * {@link #buildACLSearchBuilder}: stamps {@code accountIdIN} when
     * permitted accounts are non-empty, stamps the {@code domainSearch}
     * path-LIKE join when {@code isRecursive} is true, or sets the flat
     * {@code domainId} parameter otherwise. Also binds the project
     * {@code accountSearch.type} when a
     * {@link ListProjectResourcesCriteria} is given.
     */
    void buildACLSearchCriteria(SearchCriteria<? extends ControlledEntity> sc, Long domainId, boolean isRecursive,
            List<Long> permittedAccounts, ListProjectResourcesCriteria listProjectResourcesCriteria);

    /**
     * Driver-side parameter resolution that every {@code list*Cmd} handler
     * calls before building the search criteria. Validates the requested
     * {@code domainId} and runs {@code checkAccess(caller, domain)} on it;
     * resolves {@code accountName} to a permitted account (and runs an
     * {@code AccessType}-less {@code checkAccess} on that account);
     * resolves {@code projectId} into the project's owner-account (or
     * {@code -1} → "all the caller's projects") via
     * {@link com.cloud.projects.ProjectManager#listPermittedProjectAccounts}
     * / {@link com.cloud.projects.ProjectManager#canAccessProjectAccount},
     * and finally narrows the permitted-accounts list for non-root callers.
     * Mutates the {@code permittedAccounts} list and the
     * {@code domainIdRecursiveListProject} ternary in place; throws
     * {@link com.cloud.exception.InvalidParameterValueException} or
     * {@link com.cloud.exception.PermissionDeniedException} on rejected
     * inputs. The {@code forProjectInvitation} flag short-circuits the
     * project-resolution branch (project-invitation listings own that
     * scope themselves).
     */
    void buildACLSearchParameters(com.cloud.user.Account caller, Long id, String accountName, Long projectId,
            List<Long> permittedAccounts, Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject,
            boolean listAll, boolean forProjectInvitation);

    /**
     * {@link #buildACLSearchBuilder} for {@link ControlledViewEntity}-backed
     * view tables: stamps {@code accountIdIN}, {@code domainId} and the
     * recursive {@code domainPath}-LIKE filter as flat columns on the view
     * (no join needed), plus an {@code accountType} column when a
     * {@link ListProjectResourcesCriteria} is given.
     */
    void buildACLViewSearchBuilder(SearchBuilder<? extends ControlledViewEntity> sb, Long domainId, boolean isRecursive,
            List<Long> permittedAccounts, ListProjectResourcesCriteria listProjectResourcesCriteria);

    /**
     * Runtime parameter binding for {@link #buildACLViewSearchBuilder} —
     * the view-table counterpart of {@link #buildACLSearchCriteria}. Binds
     * {@code accountIdIN}, the recursive {@code domainPath} LIKE filter or
     * the flat {@code domainId} EQ filter, and the {@code accountType}
     * column for project-resource scoping.
     */
    void buildACLViewSearchCriteria(SearchCriteria<? extends ControlledViewEntity> sc, Long domainId, boolean isRecursive,
            List<Long> permittedAccounts, ListProjectResourcesCriteria listProjectResourcesCriteria);
}
