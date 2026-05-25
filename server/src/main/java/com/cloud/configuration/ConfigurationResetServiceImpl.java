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
package com.cloud.configuration;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.config.ResetCfgCmd;
import org.apache.cloudstack.config.Configuration;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigDepot;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.config.impl.ConfigurationVO;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDetailVO;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDetailsDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolDetailsDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterDetailsVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterDetailsDao;
import com.cloud.domain.DomainDetailVO;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.domain.dao.DomainDetailsDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.user.AccountDetailVO;
import com.cloud.user.AccountDetailsDao;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Default {@link ConfigurationResetService} implementation — see the
 * interface Javadoc for behaviour. Extracted from
 * {@link ConfigurationManagerImpl} as slice 6 of the Phase 4
 * Spring-component decomposition.
 *
 * <p>This bean intentionally has no back-reference into the manager:
 * the small {@code encryptEventValueIfConfigIsEncrypted} and
 * {@code getParamCount} helpers are duplicated locally, matching the
 * pattern used by sibling slices ({@link PodServiceImpl},
 * {@link DiskOfferingServiceImpl}, {@link ZoneServiceImpl}, etc.).
 */
@Component
public class ConfigurationResetServiceImpl implements ConfigurationResetService {

    protected static final Logger logger = LogManager.getLogger(ConfigurationResetServiceImpl.class);

    @Inject
    protected ConfigurationDao _configDao;
    @Inject
    protected ConfigDepot _configDepot;
    @Inject
    protected DataCenterDao _zoneDao;
    @Inject
    protected DataCenterDetailsDao _dcDetailsDao;
    @Inject
    protected ClusterDao _clusterDao;
    @Inject
    protected ClusterDetailsDao _clusterDetailsDao;
    @Inject
    protected PrimaryDataStoreDao _storagePoolDao;
    @Inject
    protected StoragePoolDetailsDao _storagePoolDetailsDao;
    @Inject
    protected DomainDao _domainDao;
    @Inject
    protected DomainDetailsDao _domainDetailsDao;
    @Inject
    protected AccountDao _accountDao;
    @Inject
    protected AccountDetailsDao _accountDetailsDao;
    @Inject
    protected ImageStoreDao _imageStoreDao;
    @Inject
    protected ImageStoreDetailsDao _imageStoreDetailsDao;

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_CONFIGURATION_VALUE_EDIT, eventDescription = "resetting configuration")
    public Pair<Configuration, String> resetConfiguration(final ResetCfgCmd cmd) throws InvalidParameterValueException {
        // Preserve historical side effect: anchor the calling-user lookup so the
        // CallContext is touched in the same order as the original god-class path.
        CallContext.current().getCallingUserId();
        final String name = cmd.getCfgName();
        final Long zoneId = cmd.getZoneId();
        final Long clusterId = cmd.getClusterId();
        final Long storagepoolId = cmd.getStoragepoolId();
        final Long accountId = cmd.getAccountId();
        final Long domainId = cmd.getDomainId();
        final Long imageStoreId = cmd.getImageStoreId();
        ConfigKey<?> configKey = null;
        Optional<?> optionalValue;
        String defaultValue;
        String category;
        java.util.List<ConfigKey.Scope> configScope;
        final ConfigurationVO config = _configDao.findByName(name);
        if (config == null) {
            configKey = _configDepot.get(name);
            if (configKey == null) {
                logger.warn("Probably the component manager where configuration variable {} is defined needs to implement Configurable interface", name);
                throw new InvalidParameterValueException("Config parameter with name " + name + " doesn't exist");
            }
            defaultValue = configKey.defaultValue();
            category = configKey.category();
            configScope = configKey.getScopes();
        } else {
            defaultValue = config.getDefaultValue();
            category = config.getCategory();
            configScope = config.getScopes();
        }

        String scopeVal;
        Map<String, Long> scopeMap = new LinkedHashMap<>();

        Long id;
        int paramCountCheck;

        scopeMap.put(ConfigKey.Scope.Zone.toString(), zoneId);
        scopeMap.put(ConfigKey.Scope.Cluster.toString(), clusterId);
        scopeMap.put(ConfigKey.Scope.Domain.toString(), domainId);
        scopeMap.put(ConfigKey.Scope.Account.toString(), accountId);
        scopeMap.put(ConfigKey.Scope.StoragePool.toString(), storagepoolId);
        scopeMap.put(ConfigKey.Scope.ImageStore.toString(), imageStoreId);

        ParamCountTriple paramCountPair = getParamCount(scopeMap);
        id = paramCountPair.id;
        paramCountCheck = paramCountPair.paramCount;
        scopeVal = paramCountPair.scope;

        if (paramCountCheck > 1) {
            throw new InvalidParameterValueException("cannot handle multiple IDs, provide only one ID corresponding to the scope");
        }

        if (scopeVal != null) {
            ConfigKey.Scope scope = ConfigKey.Scope.valueOf(scopeVal);
            if (!scopeVal.equals(ConfigKey.Scope.Global.toString()) && !configScope.contains(scope)) {
                throw new InvalidParameterValueException("Invalid scope id provided for the parameter " + name);
            }
        }

        String newValue = null;
        ConfigKey.Scope scope = ConfigKey.Scope.valueOf(scopeVal);
        String currentValueInScope = getConfigurationValueInScope(config, name, scope, id);
        switch (scope) {
            case Zone:
                final DataCenterVO zone = _zoneDao.findById(id);
                if (zone == null) {
                    throw new InvalidParameterValueException("unable to find zone by id " + id);
                }
                _dcDetailsDao.removeDetail(id, name);
                optionalValue = Optional.ofNullable(configKey != null ? configKey.valueIn(id) : config.getValue());
                newValue = optionalValue.isPresent() ? optionalValue.get().toString() : defaultValue;
                break;

            case Cluster:
                final ClusterVO cluster = _clusterDao.findById(id);
                if (cluster == null) {
                    throw new InvalidParameterValueException("unable to find cluster by id " + id);
                }
                ClusterDetailsVO clusterDetailsVO = _clusterDetailsDao.findDetail(id, name);
                newValue = configKey != null ? configKey.value().toString() : config.getValue();
                if (name.equalsIgnoreCase("cpu.overprovisioning.factor") || name.equalsIgnoreCase("mem.overprovisioning.factor")) {
                    _clusterDetailsDao.persist(id, name, newValue);
                } else if (clusterDetailsVO != null) {
                    _clusterDetailsDao.remove(clusterDetailsVO.getId());
                }
                optionalValue = Optional.ofNullable(configKey != null ? configKey.valueIn(id) : config.getValue());
                newValue = optionalValue.isPresent() ? optionalValue.get().toString() : defaultValue;
                break;

            case StoragePool:
                final StoragePoolVO pool = _storagePoolDao.findById(id);
                if (pool == null) {
                    throw new InvalidParameterValueException("unable to find storage pool by id " + id);
                }
                _storagePoolDetailsDao.removeDetail(id, name);
                optionalValue = Optional.ofNullable(configKey != null ? configKey.valueIn(id) : config.getValue());
                newValue = optionalValue.isPresent() ? optionalValue.get().toString() : defaultValue;
                break;

            case Domain:
                final DomainVO domain = _domainDao.findById(id);
                if (domain == null) {
                    throw new InvalidParameterValueException("unable to find domain by id " + id);
                }
                DomainDetailVO domainDetailVO = _domainDetailsDao.findDetail(id, name);
                if (domainDetailVO != null) {
                    _domainDetailsDao.remove(domainDetailVO.getId());
                }
                optionalValue = Optional.ofNullable(configKey != null ? configKey.valueIn(id) : config.getValue());
                newValue = optionalValue.isPresent() ? optionalValue.get().toString() : defaultValue;
                break;

            case Account:
                final AccountVO account = _accountDao.findById(id);
                if (account == null) {
                    throw new InvalidParameterValueException("Unable to find Account by id " + id);
                }
                AccountDetailVO accountDetailVO = _accountDetailsDao.findDetail(id, name);
                if (accountDetailVO != null) {
                    _accountDetailsDao.remove(accountDetailVO.getId());
                }
                optionalValue = Optional.ofNullable(configKey != null ? configKey.valueIn(id) : config.getValue());
                newValue = optionalValue.isPresent() ? optionalValue.get().toString() : defaultValue;
                break;

            case ImageStore:
                final ImageStoreVO imageStoreVO = _imageStoreDao.findById(id);
                if (imageStoreVO == null) {
                    throw new InvalidParameterValueException("unable to find the image store by id " + id);
                }
                ImageStoreDetailVO imageStoreDetailVO = _imageStoreDetailsDao.findDetail(id, name);
                if (imageStoreDetailVO != null) {
                    _imageStoreDetailsDao.remove(imageStoreDetailVO.getId());
                }
                optionalValue = Optional.ofNullable(configKey != null ? configKey.valueIn(id) : config.getValue());
                newValue = optionalValue.isPresent() ? optionalValue.get().toString() : defaultValue;
                break;

            default:
                if (!_configDao.update(name, category, defaultValue)) {
                    logger.error("Failed to reset configuration option, name: {}, defaultValue: {}", name, defaultValue);
                    throw new CloudRuntimeException("Failed to reset configuration value. Please contact Cloud Support.");
                }
                optionalValue = Optional.ofNullable(configKey != null ? configKey.value() : _configDao.findByName(name).getValue());
                newValue = optionalValue.isPresent() ? optionalValue.get().toString() : defaultValue;
        }

        logger.debug("Config: {} value is updated from: {} to {} for scope: {}", name,
                encryptEventValueIfConfigIsEncrypted(config, currentValueInScope),
                encryptEventValueIfConfigIsEncrypted(config, newValue), scope);

        _configDepot.invalidateConfigCache(name, scope, id);

        CallContext.current().setEventDetails(" Name: " + name + " New Value: "
                + (name.toLowerCase().contains("password") ? "*****" : defaultValue == null ? "" : defaultValue));
        return new Pair<>(_configDao.findByName(name), newValue);
    }

    @Override
    public String getConfigurationValueInScope(ConfigurationVO config, String name, ConfigKey.Scope scope, Long id) {
        String configValue;
        if (scope == null || ConfigKey.Scope.Global.equals(scope)) {
            configValue = config == null ? null : config.getValue();
        } else {
            ConfigKey<?> configKey = _configDepot.get(name);
            Object currentValue = configKey.valueInScope(scope, id);
            configValue = currentValue != null ? currentValue.toString() : null;
        }
        return configValue;
    }

    private String encryptEventValueIfConfigIsEncrypted(ConfigurationVO config, String value) {
        return ConfigurationValueValidator.maskEventValueIfEncrypted(config, value);
    }

    /**
     * Walks the (scope-name -> id) map and returns the single non-null entry,
     * defaulting to {@code Global} when none are supplied. The {@code paramCount}
     * lets the caller reject ambiguous input (multiple ids).
     */
    ParamCountTriple getParamCount(Map<String, Long> scopeMap) {
        Long id = null;
        int paramCount = 0;
        String scope = ConfigKey.Scope.Global.toString();

        for (Map.Entry<String, Long> entry : scopeMap.entrySet()) {
            if (entry.getValue() != null) {
                id = entry.getValue();
                scope = entry.getKey();
                paramCount++;
            }
        }

        return new ParamCountTriple(id, paramCount, scope);
    }

    /** Small immutable triple — package-private to keep the reset path self-contained. */
    static final class ParamCountTriple {
        final Long id;
        final int paramCount;
        final String scope;

        ParamCountTriple(Long id, int paramCount, String scope) {
            this.id = id;
            this.paramCount = paramCount;
            this.scope = scope;
        }
    }
}
