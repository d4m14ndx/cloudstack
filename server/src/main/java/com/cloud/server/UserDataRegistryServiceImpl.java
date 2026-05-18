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
package com.cloud.server;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.command.user.userdata.BaseRegisterUserDataCmd;
import org.apache.cloudstack.api.command.user.userdata.DeleteCniConfigurationCmd;
import org.apache.cloudstack.api.command.user.userdata.DeleteUserDataCmd;
import org.apache.cloudstack.api.command.user.userdata.ListUserDataCmd;
import org.apache.cloudstack.api.command.user.userdata.RegisterCniConfigurationCmd;
import org.apache.cloudstack.api.command.user.userdata.RegisterUserDataCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.userdata.UserDataManager;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Component;

import com.cloud.api.ApiDBUtils;
import com.cloud.domain.DomainVO;
import com.cloud.projects.Project.ListProjectResourcesCriteria;
import com.cloud.user.AccountManager;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.UserData;
import com.cloud.user.UserDataVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDataDao;
import com.cloud.utils.Pair;
import com.cloud.utils.Ternary;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;

@Component
public class UserDataRegistryServiceImpl implements UserDataRegistryService {

    @Inject
    protected AccountManager accountManager;
    @Inject
    protected AccountDao accountDao;
    @Inject
    protected UserDataDao userDataDao;
    @Inject
    protected VMTemplateDao templateDao;
    @Inject
    protected UserVmDao userVmDao;
    @Inject
    protected AnnotationDao annotationDao;
    @Inject
    protected UserDataManager userDataManager;

    @Override
    public boolean deleteUserData(final DeleteUserDataCmd cmd) {
        final Account caller = CallContext.current().getCallingAccount();
        final String accountName = cmd.getAccountName();
        final Long domainId = cmd.getDomainId();
        final Long projectId = cmd.getProjectId();

        Account owner = null;
        try {
            owner = accountManager.finalizeOwner(caller, accountName, domainId, projectId);
        } catch (InvalidParameterValueException ex) {
            if (caller.getType() == Account.Type.ADMIN && accountName != null && domainId != null) {
                owner = accountDao.findAccountIncludingRemoved(accountName, domainId);
            }
            if (owner == null) {
                throw ex;
            }
        }

        final UserDataVO userData = userDataDao.findById(cmd.getId());
        if (userData == null) {
            final InvalidParameterValueException ex = new InvalidParameterValueException(
                    "A UserData with id '" + cmd.getId() + "' does not exist for account " + owner.getAccountName() + " in specified domain id");
            final DomainVO domain = ApiDBUtils.findDomainById(owner.getDomainId());
            String domainUuid = String.valueOf(owner.getDomainId());
            if (domain != null) {
                domainUuid = domain.getUuid();
            }
            ex.addProxyObject(domainUuid, "domainId");
            throw ex;
        }

        List<VMTemplateVO> templatesLinkedToUserData = templateDao.findTemplatesLinkedToUserdata(userData.getId());
        if (CollectionUtils.isNotEmpty(templatesLinkedToUserData)) {
            throw new CloudRuntimeException(String.format("Userdata %s cannot be removed as it is linked to active template/templates", userData.getName()));
        }

        List<UserVmVO> userVMsHavingUserdata = userVmDao.findByUserDataId(userData.getId());
        if (CollectionUtils.isNotEmpty(userVMsHavingUserdata)) {
            throw new CloudRuntimeException(String.format("Userdata %s cannot be removed as it is being used by some instances", userData.getName()));
        }

        annotationDao.removeByEntityType(AnnotationService.EntityType.USER_DATA.name(), userData.getUuid());

        return userDataDao.remove(userData.getId());
    }

    @Override
    public boolean deleteCniConfiguration(DeleteCniConfigurationCmd cmd) {
        return deleteUserData(cmd);
    }

    @Override
    public Pair<List<? extends UserData>, Integer> listUserDatas(final ListUserDataCmd cmd, final boolean forCks) {
        final Long id = cmd.getId();
        final String name = cmd.getName();
        final String keyword = cmd.getKeyword();

        final Account caller = CallContext.current().getCallingAccount();
        final List<Long> permittedAccounts = new ArrayList<>();

        final Ternary<Long, Boolean, ListProjectResourcesCriteria> domainIdRecursiveListProject = new Ternary<>(cmd.getDomainId(), cmd.isRecursive(), null);
        accountManager.buildACLSearchParameters(caller, null, cmd.getAccountName(), cmd.getProjectId(), permittedAccounts, domainIdRecursiveListProject, cmd.listAll(), false);
        final Long domainId = domainIdRecursiveListProject.first();
        final Boolean isRecursive = domainIdRecursiveListProject.second();
        final ListProjectResourcesCriteria listProjectResourcesCriteria = domainIdRecursiveListProject.third();
        final SearchBuilder<UserDataVO> sb = userDataDao.createSearchBuilder();
        accountManager.buildACLSearchBuilder(sb, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);
        final Filter searchFilter = new Filter(UserDataVO.class, "id", false, cmd.getStartIndex(), cmd.getPageSizeVal());

        sb.and("id", sb.entity().getId(), SearchCriteria.Op.EQ);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("keyword", sb.entity().getName(), SearchCriteria.Op.LIKE);
        sb.and("name", sb.entity().getName(), SearchCriteria.Op.EQ);
        sb.and("forCks", sb.entity().isForCks(), SearchCriteria.Op.EQ);
        final SearchCriteria<UserDataVO> sc = sb.create();
        accountManager.buildACLSearchCriteria(sc, domainId, isRecursive, permittedAccounts, listProjectResourcesCriteria);

        if (id != null) {
            sc.setParameters("id", id);
        }

        if (name != null) {
            sc.setParameters("name", name);
        }

        if (keyword != null) {
            sc.setParameters("keyword", "%" + keyword + "%");
        }

        sc.setParameters("forCks", forCks);

        final Pair<List<UserDataVO>, Integer> result = userDataDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }

    @Override
    public UserData registerCniConfiguration(RegisterCniConfigurationCmd cmd) {
        final Account owner = getOwner(cmd);
        checkForUserDataByName(cmd, owner);
        final String name = cmd.getName();

        String userdata = cmd.getCniConfig();
        final String params = cmd.getParams();

        userdata = userDataManager.validateUserData(userdata, cmd.getHttpMethod());

        return createAndSaveUserData(name, userdata, params, owner, true);
    }

    @Override
    public UserData registerUserData(final RegisterUserDataCmd cmd) {
        final Account owner = getOwner(cmd);
        checkForUserDataByName(cmd, owner);
        final String name = cmd.getName();

        String userdata = cmd.getUserData();
        checkForUserData(cmd, owner);
        final String params = cmd.getParams();

        userdata = userDataManager.validateUserData(userdata, cmd.getHttpMethod());

        return createAndSaveUserData(name, userdata, params, owner, false);
    }

    private void checkForUserData(final RegisterUserDataCmd cmd, final Account owner) throws InvalidParameterValueException {
        final UserDataVO userData = userDataDao.findByUserData(owner.getAccountId(), owner.getDomainId(), cmd.getUserData());
        if (userData != null) {
            throw new InvalidParameterValueException(String.format("Userdata %s with same content already exists for this account.", userData.getName()));
        }
    }

    private void checkForUserDataByName(final BaseRegisterUserDataCmd cmd, final Account owner) throws InvalidParameterValueException {
        final UserDataVO userData = userDataDao.findByName(owner.getAccountId(), owner.getDomainId(), cmd.getName());
        if (userData != null) {
            throw new InvalidParameterValueException(String.format("A userdata with name %s already exists for this account.", cmd.getName()));
        }
    }

    private Account getOwner(final BaseRegisterUserDataCmd cmd) {
        final Account caller = CallContext.current().getCallingAccount();
        return accountManager.finalizeOwner(caller, cmd.getAccountName(), cmd.getDomainId(), cmd.getProjectId());
    }

    private UserData createAndSaveUserData(final String name, final String userdata, final String params, final Account owner, final boolean isForCks) {
        final UserDataVO userDataVO = new UserDataVO();

        userDataVO.setAccountId(owner.getAccountId());
        userDataVO.setDomainId(owner.getDomainId());
        userDataVO.setName(name);
        userDataVO.setUserData(userdata);
        userDataVO.setParams(params);
        userDataVO.setForCks(isForCks);

        userDataDao.persist(userDataVO);

        return userDataVO;
    }
}
