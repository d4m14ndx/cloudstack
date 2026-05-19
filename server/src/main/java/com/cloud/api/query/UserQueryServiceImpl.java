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
import java.util.List;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.RoleService;
import org.apache.cloudstack.acl.RoleVO;
import org.apache.cloudstack.acl.dao.RoleDao;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.command.admin.user.ListUsersCmd;
import org.apache.cloudstack.api.response.ListResponse;
import org.apache.cloudstack.api.response.UserResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.utils.baremetal.BaremetalUtils;
import org.apache.commons.lang3.EnumUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.UserAccountJoinDao;
import com.cloud.api.query.vo.UserAccountJoinVO;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;

@Component
public class UserQueryServiceImpl implements UserQueryService {

    @Inject
    private AccountManager accountMgr;

    @Inject
    private RoleService roleService;

    @Inject
    private DomainDao domainDao;

    @Inject
    private UserAccountJoinDao userAccountJoinDao;

    @Inject
    private AccountDao accountDao;

    @Inject
    private UserDao userDao;

    @Inject
    private RoleDao roleDao;

    @Override
    public ListResponse<UserResponse> searchForUsers(ResponseView responseView, ListUsersCmd cmd) throws PermissionDeniedException {
        Pair<List<UserAccountJoinVO>, Integer> result = searchForUsersInternal(cmd);
        ListResponse<UserResponse> response = new ListResponse<>();
        if (CallContext.current().getCallingAccount().getType() == Account.Type.ADMIN) {
            responseView = ResponseView.Full;
        }
        List<UserResponse> userResponses = ViewResponseHelper.createUserResponse(responseView, CallContext.current().getCallingAccount().getDomainId(),
                result.first().toArray(new UserAccountJoinVO[0]));
        response.setResponses(userResponses, result.second());
        return response;
    }

    @Override
    public ListResponse<UserResponse> searchForUsers(Long domainId, boolean recursive) throws PermissionDeniedException {
        Account caller = CallContext.current().getCallingAccount();

        List<Long> permittedAccounts = new ArrayList<>();

        boolean listAll = true;
        Long id = null;

        if (caller.getType() == Account.Type.NORMAL) {
            id = CallContext.current().getCallingUser().getId();
        }
        Object username = null;
        Object type = null;
        String accountName = null;
        Object state = null;
        String keyword = null;

        Pair<List<UserAccountJoinVO>, Integer> result = getUserListInternal(caller, permittedAccounts, listAll, id,
                username, type, accountName, state, keyword, null, domainId, recursive, null, null);
        ListResponse<UserResponse> response = new ListResponse<>();
        List<UserResponse> userResponses = ViewResponseHelper.createUserResponse(ResponseView.Restricted, CallContext.current().getCallingAccount().getDomainId(),
                result.first().toArray(new UserAccountJoinVO[0]));
        response.setResponses(userResponses, result.second());
        return response;
    }

    private Pair<List<UserAccountJoinVO>, Integer> searchForUsersInternal(ListUsersCmd cmd) throws PermissionDeniedException {
        Account caller = CallContext.current().getCallingAccount();

        List<Long> permittedAccounts = new ArrayList<>();

        boolean listAll = cmd.listAll();
        Long id = cmd.getId();
        if (caller.getType() == Account.Type.NORMAL) {
            long currentId = CallContext.current().getCallingUser().getId();
            if (id != null && currentId != id) {
                throw new PermissionDeniedException("Calling user is not authorized to see the user requested by id");
            }
            id = currentId;
        }
        Object username = cmd.getUsername();
        Object type = cmd.getAccountType();
        String accountName = cmd.getAccountName();
        Object state = cmd.getState();
        String keyword = cmd.getKeyword();
        String apiKeyAccess = cmd.getApiKeyAccess();
        User.Source userSource = cmd.getUserSource();

        Long domainId = cmd.getDomainId();
        boolean recursive = cmd.isRecursive();
        Long pageSizeVal = cmd.getPageSizeVal();
        Long startIndex = cmd.getStartIndex();

        Filter searchFilter = new Filter(UserAccountJoinVO.class, "id", true, startIndex, pageSizeVal);

        return getUserListInternal(caller, permittedAccounts, listAll, id, username, type, accountName, state, keyword, apiKeyAccess, domainId, recursive, searchFilter, userSource);
    }

    private Pair<List<UserAccountJoinVO>, Integer> getUserListInternal(Account caller, List<Long> permittedAccounts, boolean listAll, Long id, Object username, Object type,
            String accountName, Object state, String keyword, String apiKeyAccess, Long domainId, boolean recursive, Filter searchFilter, User.Source userSource) {
        Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(domainId, recursive, null);
        accountMgr.buildACLSearchParameters(caller, id, accountName, null, permittedAccounts, domainIdRecursiveListProject, listAll, false);
        domainId = domainIdRecursiveListProject.first();
        Boolean isRecursive = domainIdRecursiveListProject.second();
        ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();

        SearchBuilder<UserAccountJoinVO> sb = userAccountJoinDao.createSearchBuilder();
        accountMgr.buildACLViewSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        sb.and("username", sb.entity().getUsername(), Op.LIKE);
        if (id != null && id == 1) {
            // system user should NOT be searchable
            List<UserAccountJoinVO> emptyList = new ArrayList<>();
            return new Pair<>(emptyList, 0);
        } else if (id != null) {
            sb.and("id", sb.entity().getId(), Op.EQ);
        } else {
            // this condition is used to exclude system user from the search
            // results
            sb.and("id", sb.entity().getId(), Op.NEQ);
        }

        sb.and("type", sb.entity().getAccountType(), Op.EQ);
        sb.and("domainId", sb.entity().getDomainId(), Op.EQ);
        sb.and("accountName", sb.entity().getAccountName(), Op.EQ);
        sb.and("state", sb.entity().getState(), Op.EQ);
        sb.and("userSource", sb.entity().getSource(), Op.EQ);
        if (apiKeyAccess != null) {
            sb.and("apiKeyAccess", sb.entity().getApiKeyAccess(), Op.EQ);
        }

        if ((accountName == null) && (domainId != null)) {
            sb.and("domainPath", sb.entity().getDomainPath(), Op.LIKE);
        }

        SearchCriteria<UserAccountJoinVO> sc = sb.create();

        // building ACL condition
        accountMgr.buildACLViewSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (keyword != null) {
            SearchCriteria<UserAccountJoinVO> ssc = userAccountJoinDao.createSearchCriteria();
            ssc.addOr("username", Op.LIKE, "%" + keyword + "%");
            ssc.addOr("firstname", Op.LIKE, "%" + keyword + "%");
            ssc.addOr("lastname", Op.LIKE, "%" + keyword + "%");
            ssc.addOr("email", Op.LIKE, "%" + keyword + "%");
            ssc.addOr("state", Op.LIKE, "%" + keyword + "%");
            ssc.addOr("accountName", Op.LIKE, "%" + keyword + "%");
            if (EnumUtils.isValidEnum(Account.Type.class, keyword.toUpperCase())) {
                ssc.addOr("accountType", Op.EQ, EnumUtils.getEnum(Account.Type.class, keyword.toUpperCase()));
            }

            sc.addAnd("username", Op.SC, ssc);
        }

        if (username != null) {
            sc.setParameters("username", username);
        }

        if (id != null) {
            sc.setParameters("id", id);
        } else {
            // Don't return system user, search builder with NEQ
            sc.setParameters("id", 1);
        }

        if (type != null) {
            sc.setParameters("type", type);
        }

        if (accountName != null) {
            sc.setParameters("accountName", accountName);
            if (domainId != null) {
                sc.setParameters("domainId", domainId);
            }
        } else if (domainId != null) {
            DomainVO domainVO = domainDao.findById(domainId);
            sc.setParameters("domainPath", domainVO.getPath() + "%");
        }

        if (state != null) {
            sc.setParameters("state", state);
        }

        if (apiKeyAccess != null) {
            try {
                ApiConstants.ApiKeyAccess access = ApiConstants.ApiKeyAccess.valueOf(apiKeyAccess.toUpperCase());
                sc.setParameters("apiKeyAccess", access.toBoolean());
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("ApiKeyAccess value can only be Enabled/Disabled/Inherit");
            }
        }

        if (userSource != null) {
            sc.setParameters("userSource", userSource.toString());
        }

        return userAccountJoinDao.searchAndCount(sc, searchFilter);
    }

    @Override
    public List<Long> searchForAccessibleUsers() {
        List<Long> permittedAccounts = new ArrayList<>();
        Account callingAccount = CallContext.current().getCallingAccount();
        Filter searchFilter = new Filter(UserAccountJoinVO.class, "id", true);
        List<RoleVO> allowedRoles = roleDao.listAll();
        roleService.removeRolesIfNeeded(allowedRoles);
        List<Long> allowedRolesId = allowedRoles.stream().map(RoleVO::getId).collect(Collectors.toList());

        Pair<List<UserAccountJoinVO>, Integer> usersPair = getUserListInternal(callingAccount, permittedAccounts,
                true, null, null, null, null, null, null, null, callingAccount.getDomainId(), true, searchFilter, null);
        return usersPair.first().stream().filter(userAccount -> {
            if (BaremetalUtils.BAREMETAL_SYSTEM_ACCOUNT_NAME.equals(userAccount.getUsername()) && !accountMgr.isRootAdmin(callingAccount.getId())) {
                return false;
            }

            AccountVO accountVO = accountDao.findByIdIncludingRemoved(userAccount.getAccountId());
            UserVO userVO = userDao.findByIdIncludingRemoved(userAccount.getId());
            if (ObjectUtils.anyNull(accountVO, userVO)) {
                return false;
            }

            try {
                accountMgr.checkCallerRoleTypeAllowedForUserOrAccountOperations(accountVO, userVO);
            } catch (PermissionDeniedException exception) {
                return false;
            }
            return allowedRolesId.contains(userAccount.getAccountRoleId());
        }).map(UserAccountJoinVO::getId).collect(Collectors.toList());
    }
}
