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

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.user.account.ListAccountsCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.query.QueryService;
import org.springframework.stereotype.Component;

import com.cloud.api.query.dao.AccountJoinDao;
import com.cloud.api.query.vo.AccountJoinVO;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;
import com.cloud.utils.db.SearchCriteria.Op;

/**
 * Implementation of the {@code listAccounts} search helpers extracted from
 * {@link QueryManagerImpl}.
 *
 * @see AccountQueryService
 */
@Component
public class AccountQueryServiceImpl implements AccountQueryService {

    @Inject
    private AccountManager accountMgr;
    @Inject
    private DomainDao domainDao;
    @Inject
    private AccountDao accountDao;
    @Inject
    private AccountJoinDao accountJoinDao;

    @Override
    public Pair<List<AccountJoinVO>, Integer> searchForAccountsInternal(ListAccountsCmd cmd) {

        Pair<List<Long>, Integer> accountIdPage = searchForAccountIdsAndCount(cmd);

        Integer count = accountIdPage.second();
        Long[] idArray = accountIdPage.first().toArray(new Long[0]);

        if (count == 0) {
            return new Pair<>(new ArrayList<>(), count);
        }

        List<AccountJoinVO> accounts = accountJoinDao.searchByIds(idArray);
        return new Pair<>(accounts, count);
    }

    protected Pair<List<Long>, Integer> searchForAccountIdsAndCount(ListAccountsCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Long domainId = cmd.getDomainId();
        Long accountId = cmd.getId();
        String accountName = cmd.getSearchName();
        boolean isRecursive = cmd.isRecursive();
        boolean listAll = cmd.listAll();
        boolean callerIsAdmin = accountMgr.isAdmin(caller.getId());
        Account account;
        Domain domain = null;

        // if "domainid" specified, perform validation
        if (domainId != null) {
            // ensure existence...
            domain = domainDao.findById(domainId);
            if (domain == null) {
                throw new InvalidParameterValueException("Domain id=" + domainId + " doesn't exist");
            }
            // ... and check access rights.
            accountMgr.checkAccess(caller, domain);
        }

        // if no "id" specified...
        if (accountId == null) {
            // listall only has significance if they are an admin
            boolean isDomainListAllAllowed = QueryService.AllowUserViewAllDomainAccounts.valueIn(caller.getDomainId());
            if ((listAll && callerIsAdmin) || isDomainListAllAllowed) {
                // if no domain id specified, use caller's domain
                if (domainId == null) {
                    domainId = caller.getDomainId();
                }
                // mark recursive
                isRecursive = true;
            } else if (!callerIsAdmin || domainId == null) {
                accountId = caller.getAccountId();
            }
        } else if (domainId != null && accountName != null) {
            // if they're looking for an account by name
            account = accountDao.findActiveAccount(accountName, domainId);
            if (account == null || account.getId() == Account.ACCOUNT_ID_SYSTEM) {
                throw new InvalidParameterValueException("Unable to find account by name " + accountName + " in domain " + domainId);
            }
            accountMgr.checkAccess(caller, null, true, account);
        } else {
            // if they specified an "id"...
            if (domainId == null) {
                account = accountDao.findById(accountId);
            } else {
                account = accountDao.findActiveAccountById(accountId, domainId);
            }
            if (account == null || account.getId() == Account.ACCOUNT_ID_SYSTEM) {
                throw new InvalidParameterValueException("Unable to find account by id " + accountId + (domainId == null ? "" : " in domain " + domainId));
            }
            accountMgr.checkAccess(caller, null, true, account);
        }

        Filter searchFilter = new Filter(AccountVO.class, "id", true, cmd.getStartIndex(), cmd.getPageSizeVal());

        Object type = cmd.getAccountType();
        Object state = cmd.getState();
        Object isCleanupRequired = cmd.isCleanupRequired();
        Object keyword = cmd.getKeyword();
        String apiKeyAccess = cmd.getApiKeyAccess();

        SearchBuilder<AccountVO> accountSearchBuilder = accountDao.createSearchBuilder();
        accountSearchBuilder.select(null, Func.DISTINCT, accountSearchBuilder.entity().getId()); // select distinct
        accountSearchBuilder.and("accountName", accountSearchBuilder.entity().getAccountName(), SearchCriteria.Op.EQ);
        accountSearchBuilder.and("domainId", accountSearchBuilder.entity().getDomainId(), SearchCriteria.Op.EQ);
        accountSearchBuilder.and("id", accountSearchBuilder.entity().getId(), SearchCriteria.Op.EQ);
        accountSearchBuilder.and("type", accountSearchBuilder.entity().getType(), SearchCriteria.Op.EQ);
        accountSearchBuilder.and("state", accountSearchBuilder.entity().getState(), SearchCriteria.Op.EQ);
        accountSearchBuilder.and("needsCleanup", accountSearchBuilder.entity().getNeedsCleanup(), SearchCriteria.Op.EQ);
        accountSearchBuilder.and("typeNEQ", accountSearchBuilder.entity().getType(), SearchCriteria.Op.NEQ);
        accountSearchBuilder.and("idNEQ", accountSearchBuilder.entity().getId(), SearchCriteria.Op.NEQ);
        accountSearchBuilder.and("type2NEQ", accountSearchBuilder.entity().getType(), SearchCriteria.Op.NEQ);
        if (apiKeyAccess != null) {
            accountSearchBuilder.and("apiKeyAccess", accountSearchBuilder.entity().getApiKeyAccess(), Op.EQ);
        }

        if (domainId != null && isRecursive) {
            SearchBuilder<DomainVO> domainSearch = domainDao.createSearchBuilder();
            domainSearch.and("path", domainSearch.entity().getPath(), SearchCriteria.Op.LIKE);
            accountSearchBuilder.join("domainSearch", domainSearch, domainSearch.entity().getId(), accountSearchBuilder.entity().getDomainId(), JoinBuilder.JoinType.INNER);
        }

        if (keyword != null) {
            accountSearchBuilder.and().op("keywordAccountName", accountSearchBuilder.entity().getAccountName(), SearchCriteria.Op.LIKE);
            accountSearchBuilder.or("keywordState", accountSearchBuilder.entity().getState(), SearchCriteria.Op.LIKE);
            accountSearchBuilder.cp();
        }

        SearchCriteria<AccountVO> sc = accountSearchBuilder.create();

        // don't return account of type project to the end user
        sc.setParameters("typeNEQ", Account.Type.PROJECT);

        // don't return system account...
        sc.setParameters("idNEQ", Account.ACCOUNT_ID_SYSTEM);

        // do not return account of type domain admin to the end user
        if (!callerIsAdmin) {
            sc.setParameters("type2NEQ", Account.Type.DOMAIN_ADMIN);
        }

        if (keyword != null) {
            sc.setParameters("keywordAccountName", "%" + keyword + "%");
            sc.setParameters("keywordState", "%" + keyword + "%");
        }

        if (type != null) {
            sc.setParameters("type", type);
        }

        if (state != null) {
            sc.setParameters("state", state);
        }

        if (isCleanupRequired != null) {
            sc.setParameters("needsCleanup", isCleanupRequired);
        }

        if (accountName != null) {
            sc.setParameters("accountName", accountName);
        }

        if (accountId != null) {
            sc.setParameters("id", accountId);
        }

        if (domainId != null) {
            if (isRecursive) {
                // will happen if no "domainid" was specified in the request...
                if (domain == null) {
                    domain = domainDao.findById(domainId);
                }
                sc.setJoinParameters("domainSearch", "path", domain.getPath() + "%");
            } else {
                sc.setParameters("domainId", domainId);
            }
        }

        if (apiKeyAccess != null) {
            try {
                ApiConstants.ApiKeyAccess access = ApiConstants.ApiKeyAccess.valueOf(apiKeyAccess.toUpperCase());
                sc.setParameters("apiKeyAccess", access.toBoolean());
            } catch (IllegalArgumentException ex) {
                throw new InvalidParameterValueException("ApiKeyAccess value can only be Enabled/Disabled/Inherit");
            }
        }

        Pair<List<AccountVO>, Integer> uniqueAccountPair = accountDao.searchAndCount(sc, searchFilter);
        Integer count = uniqueAccountPair.second();
        List<Long> accountIds = uniqueAccountPair.first().stream().map(AccountVO::getId).collect(Collectors.toList());
        return new Pair<>(accountIds, count);
    }
}
