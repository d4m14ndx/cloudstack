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

import org.apache.cloudstack.api.command.admin.config.ListCfgGroupsByCmd;
import org.apache.cloudstack.api.command.admin.config.ListCfgsByCmd;
import org.apache.cloudstack.config.Configuration;
import org.apache.cloudstack.config.ConfigurationGroup;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigDepot;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.config.dao.ConfigurationGroupDao;
import org.apache.cloudstack.framework.config.dao.ConfigurationSubGroupDao;
import org.apache.cloudstack.framework.config.impl.ConfigurationGroupVO;
import org.apache.cloudstack.framework.config.impl.ConfigurationSubGroupVO;
import org.apache.cloudstack.framework.config.impl.ConfigurationVO;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.configuration.ConfigurationManagerImpl;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchCriteria;

/**
 * @see ConfigurationListingService
 */
@Component
public class ConfigurationListingServiceImpl implements ConfigurationListingService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private ConfigurationDao configDao;
    @Inject
    private ConfigurationGroupDao configGroupDao;
    @Inject
    private ConfigurationSubGroupDao configSubGroupDao;
    @Inject
    private ConfigDepot configDepot;
    @Inject
    private AccountManager accountManager;
    @Inject
    private DomainDao domainDao;

    @Override
    public Pair<List<? extends Configuration>, Integer> searchForConfigurations(final ListCfgsByCmd cmd) {
        final Filter searchFilter = new Filter(ConfigurationVO.class, "name", true, cmd.getStartIndex(), cmd.getPageSizeVal());
        final SearchCriteria<ConfigurationVO> sc = configDao.createSearchCriteria();

        final Object name = cmd.getConfigName();
        final Object category = cmd.getCategory();
        final Object keyword = cmd.getKeyword();
        final Long zoneId = cmd.getZoneId();
        final Long clusterId = cmd.getClusterId();
        final Long storagepoolId = cmd.getStoragepoolId();
        final Long imageStoreId = cmd.getImageStoreId();
        Long accountId = cmd.getAccountId();
        Long domainId = cmd.getDomainId();
        final String groupName = cmd.getGroupName();
        final String subGroupName = cmd.getSubGroupName();
        final String parentName = cmd.getParentName();
        ConfigKey.Scope scope = null;
        Long id = null;
        int paramCountCheck = 0;

        final Account caller = CallContext.current().getCallingAccount();
        if (accountManager.isDomainAdmin(caller.getId())) {
            if (accountId == null && domainId == null) {
                domainId = caller.getDomainId();
            }
        } else if (accountManager.isNormalUser(caller.getId())) {
            if (accountId == null) {
                accountId = caller.getAccountId();
            }
        }

        if (zoneId != null) {
            scope = ConfigKey.Scope.Zone;
            id = zoneId;
            paramCountCheck++;
        }
        if (clusterId != null) {
            scope = ConfigKey.Scope.Cluster;
            id = clusterId;
            paramCountCheck++;
        }
        if (accountId != null) {
            Account account = accountManager.getAccount(accountId);
            accountManager.checkAccess(caller, null, false, account);
            scope = ConfigKey.Scope.Account;
            id = accountId;
            paramCountCheck++;
        }
        if (domainId != null) {
            accountManager.checkAccess(caller, domainDao.findById(domainId));
            scope = ConfigKey.Scope.Domain;
            id = domainId;
            paramCountCheck++;
        }
        if (storagepoolId != null) {
            scope = ConfigKey.Scope.StoragePool;
            id = storagepoolId;
            paramCountCheck++;
        }
        if (imageStoreId != null) {
            scope = ConfigKey.Scope.ImageStore;
            id = imageStoreId;
            paramCountCheck++;
        }

        if (paramCountCheck > 1) {
            throw new InvalidParameterValueException("cannot handle multiple IDs, provide only one ID corresponding to the scope");
        }

        if (keyword != null) {
            final SearchCriteria<ConfigurationVO> ssc = configDao.createSearchCriteria();
            ssc.addOr("name", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("instance", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("component", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("description", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("category", SearchCriteria.Op.LIKE, "%" + keyword + "%");
            ssc.addOr("value", SearchCriteria.Op.LIKE, "%" + keyword + "%");

            sc.addAnd("name", SearchCriteria.Op.SC, ssc);
        }

        if (name != null) {
            sc.addAnd("name", SearchCriteria.Op.LIKE, "%" + name + "%");
        }

        if (groupName != null) {
            ConfigurationGroupVO configGroupVO = configGroupDao.findByName(groupName);
            if (configGroupVO == null) {
                throw new InvalidParameterValueException("Invalid configuration group: " + groupName);
            }
            Long groupId = configGroupVO.getId();
            sc.addAnd("groupId", SearchCriteria.Op.EQ, groupId);
        }

        if (subGroupName != null) {
            ConfigurationSubGroupVO configSubGroupVO = configSubGroupDao.findByName(subGroupName);
            if (configSubGroupVO == null) {
                throw new InvalidParameterValueException("Invalid configuration subgroup: " + subGroupName);
            }

            Long subGroupId = configSubGroupVO.getId();
            sc.addAnd("subGroupId", SearchCriteria.Op.EQ, subGroupId);
        }

        if (parentName != null) {
            sc.addAnd("parent", SearchCriteria.Op.EQ, parentName);
        }

        if (category != null) {
            sc.addAnd("category", SearchCriteria.Op.EQ, category);
        }

        // hidden configurations are not displayed using the search API
        sc.addAnd("category", SearchCriteria.Op.NEQ, "Hidden");

        if (scope != null) {
            // getting the list of parameters at requested scope
            if (ConfigurationManagerImpl.ENABLE_ACCOUNT_SETTINGS_FOR_DOMAIN.value()
                && scope.equals(ConfigKey.Scope.Domain)) {
                sc.addAnd("scope", SearchCriteria.Op.BINARY_OR, (ConfigKey.Scope.Domain.getBitValue() | ConfigKey.Scope.Account.getBitValue()));
            } else {
                sc.addAnd("scope", SearchCriteria.Op.BINARY_OR, scope.getBitValue());
            }
        }

        final Pair<List<ConfigurationVO>, Integer> result = configDao.searchAndCount(sc, searchFilter);

        if (scope != null) {
            // Populate values corresponding the resource id
            final List<ConfigurationVO> configVOList = new ArrayList<>();
            for (final ConfigurationVO param : result.first()) {
                final ConfigurationVO configVo = configDao.findByName(param.getName());
                if (configVo != null) {
                    final ConfigKey<?> key = configDepot.get(param.getName());
                    if (key != null) {
                        Object value = key.valueInScope(scope, id);
                        configVo.setValue(value == null ? null : value.toString());
                        configVOList.add(configVo);
                    } else {
                        logger.warn("ConfigDepot could not find parameter " + param.getName() + " for scope " + scope);
                    }
                } else {
                    logger.warn("Configuration item  " + param.getName() + " not found in " + scope);
                }
            }

            return new Pair<>(configVOList, configVOList.size());
        }

        return new Pair<>(result.first(), result.second());
    }

    @Override
    public Pair<List<? extends ConfigurationGroup>, Integer> listConfigurationGroups(ListCfgGroupsByCmd cmd) {
        final Filter searchFilter = new Filter(ConfigurationGroupVO.class, "precedence", true, null, null);
        final SearchCriteria<ConfigurationGroupVO> sc = configGroupDao.createSearchCriteria();

        final String groupName = cmd.getGroupName();
        if (StringUtils.isNotBlank(groupName)) {
            sc.addAnd("name", SearchCriteria.Op.EQ, groupName);
        }

        final Pair<List<ConfigurationGroupVO>, Integer> result = configGroupDao.searchAndCount(sc, searchFilter);
        return new Pair<>(result.first(), result.second());
    }
}
