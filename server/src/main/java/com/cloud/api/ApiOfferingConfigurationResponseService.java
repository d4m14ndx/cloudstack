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
package com.cloud.api;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.Function;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.response.ConfigurationGroupResponse;
import org.apache.cloudstack.api.response.ConfigurationResponse;
import org.apache.cloudstack.api.response.ConfigurationSubGroupResponse;
import org.apache.cloudstack.api.response.ControlledEntityResponse;
import org.apache.cloudstack.api.response.DiskOfferingResponse;
import org.apache.cloudstack.api.response.ResourceCountResponse;
import org.apache.cloudstack.api.response.ResourceLimitResponse;
import org.apache.cloudstack.api.response.ServiceOfferingResponse;
import org.apache.cloudstack.config.Configuration;
import org.apache.cloudstack.config.ConfigurationGroup;
import org.apache.cloudstack.config.ConfigurationSubGroup;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.cloud.api.query.vo.DiskOfferingJoinVO;
import com.cloud.api.query.vo.ServiceOfferingJoinVO;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.Resource.ResourceOwnerType;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.configuration.ResourceCount;
import com.cloud.configuration.ResourceLimit;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.utils.Pair;
import com.cloud.utils.crypt.DBEncryptionUtil;

@Component
public class ApiOfferingConfigurationResponseService {

    @Inject
    private ConfigurationManager _configMgr;

    public DiskOfferingResponse createDiskOfferingResponse(DiskOffering offering) {
        DiskOfferingJoinVO vOffering = ApiDBUtils.newDiskOfferingView(offering);
        return ApiDBUtils.newDiskOfferingResponse(vOffering);
    }

    public ResourceLimitResponse createResourceLimitResponse(ResourceLimit limit,
            BiConsumer<ControlledEntityResponse, Long> accountPopulator,
            BiConsumer<ControlledEntityResponse, Long> domainPopulator,
            Function<Long, Long> accountDomainIdResolver) {
        ResourceLimitResponse resourceLimitResponse = new ResourceLimitResponse();
        if (limit.getResourceOwnerType() == ResourceOwnerType.Domain) {
            domainPopulator.accept(resourceLimitResponse, limit.getOwnerId());
        } else if (limit.getResourceOwnerType() == ResourceOwnerType.Account) {
            accountPopulator.accept(resourceLimitResponse, limit.getOwnerId());
            Long domainId = accountDomainIdResolver.apply(limit.getOwnerId());
            if (domainId != null) {
                domainPopulator.accept(resourceLimitResponse, domainId);
            }
        }
        resourceLimitResponse.setResourceType(limit.getType());

        if (ResourceType.isStorageType(limit.getType()) && limit.getMax() >= 0) {
            resourceLimitResponse.setMax((long)Math.ceil((double)limit.getMax() / ResourceType.bytesToGiB));
        } else {
            resourceLimitResponse.setMax(limit.getMax());
        }
        resourceLimitResponse.setTag(limit.getTag());
        resourceLimitResponse.setObjectName("resourcelimit");

        return resourceLimitResponse;
    }

    public ResourceCountResponse createResourceCountResponse(ResourceCount resourceCount,
            BiConsumer<ControlledEntityResponse, Long> accountPopulator,
            BiConsumer<ControlledEntityResponse, Long> domainPopulator,
            Function<Long, Long> accountDomainIdResolver) {
        ResourceCountResponse resourceCountResponse = new ResourceCountResponse();

        if (resourceCount.getResourceOwnerType() == ResourceOwnerType.Account) {
            Long domainId = accountDomainIdResolver.apply(resourceCount.getOwnerId());
            if (domainId != null) {
                accountPopulator.accept(resourceCountResponse, resourceCount.getOwnerId());
                domainPopulator.accept(resourceCountResponse, domainId);
            }
        } else if (resourceCount.getResourceOwnerType() == ResourceOwnerType.Domain) {
            domainPopulator.accept(resourceCountResponse, resourceCount.getOwnerId());
        }

        resourceCountResponse.setResourceType(resourceCount.getType());
        resourceCountResponse.setResourceCount(resourceCount.getCount());
        resourceCountResponse.setObjectName(ApiConstants.RESOURCE_COUNT);
        if (StringUtils.isNotEmpty(resourceCount.getTag())) {
            resourceCountResponse.setTag(resourceCount.getTag());
        }
        return resourceCountResponse;
    }

    public ServiceOfferingResponse createServiceOfferingResponse(ServiceOffering offering) {
        ServiceOfferingJoinVO vOffering = ApiDBUtils.newServiceOfferingView(offering);
        return ApiDBUtils.newServiceOfferingResponse(vOffering);
    }

    public ConfigurationResponse createConfigurationResponse(Configuration cfg) {
        ConfigurationResponse cfgResponse = new ConfigurationResponse();
        cfgResponse.setCategory(cfg.getCategory());
        Pair<String, String> configGroupAndSubGroup = _configMgr.getConfigurationGroupAndSubGroup(cfg.getName());
        cfgResponse.setGroup(configGroupAndSubGroup.first());
        cfgResponse.setSubGroup(configGroupAndSubGroup.second());
        cfgResponse.setDescription(cfg.getDescription());
        cfgResponse.setName(cfg.getName());
        if (cfg.isEncrypted()) {
            cfgResponse.setValue(DBEncryptionUtil.encrypt(cfg.getValue()));
        } else {
            cfgResponse.setValue(cfg.getValue());
        }
        cfgResponse.setDefaultValue(cfg.getDefaultValue());
        cfgResponse.setIsDynamic(cfg.isDynamic());
        cfgResponse.setComponent(cfg.getComponent());
        if (cfg.getParent() != null) {
            cfgResponse.setParent(cfg.getParent());
        }
        cfgResponse.setDisplayText(cfg.getDisplayText());
        cfgResponse.setType(_configMgr.getConfigurationType(cfg.getName()));
        if (cfg.getOptions() != null) {
            cfgResponse.setOptions(cfg.getOptions());
        }
        cfgResponse.setObjectName("configuration");

        return cfgResponse;
    }

    public ConfigurationGroupResponse createConfigurationGroupResponse(ConfigurationGroup cfgGroup) {
        ConfigurationGroupResponse cfgGroupResponse = new ConfigurationGroupResponse();
        cfgGroupResponse.setGroupName(cfgGroup.getName());
        cfgGroupResponse.setDescription(cfgGroup.getDescription());
        cfgGroupResponse.setPrecedence(cfgGroup.getPrecedence());

        List<? extends ConfigurationSubGroup> subgroups = _configMgr.getConfigurationSubGroups(cfgGroup.getId());
        List<ConfigurationSubGroupResponse> cfgSubGroupResponses = new ArrayList<>();
        for (ConfigurationSubGroup subgroup : subgroups) {
            ConfigurationSubGroupResponse cfgSubGroupResponse = createConfigurationSubGroupResponse(subgroup);
            cfgSubGroupResponses.add(cfgSubGroupResponse);
        }
        cfgGroupResponse.setSubGroups(cfgSubGroupResponses);
        cfgGroupResponse.setObjectName("configurationgroup");
        return cfgGroupResponse;
    }

    protected ConfigurationSubGroupResponse createConfigurationSubGroupResponse(ConfigurationSubGroup cfgSubGroup) {
        ConfigurationSubGroupResponse cfgSubGroupResponse = new ConfigurationSubGroupResponse();
        cfgSubGroupResponse.setSubGroupName(cfgSubGroup.getName());
        cfgSubGroupResponse.setPrecedence(cfgSubGroup.getPrecedence());
        cfgSubGroupResponse.setObjectName("subgroup");
        return cfgSubGroupResponse;
    }
}
