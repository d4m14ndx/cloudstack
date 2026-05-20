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
package com.cloud.vm;

import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.user.vm.BaseDeployVMCmd;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.api.query.vo.ServiceOfferingJoinVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.server.ResourceTag;
import com.cloud.storage.Storage;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.template.TemplateApiService;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.uservm.UserVm;

@Component
public class VmCreationPostProcessingService {
    private static final Logger LOG = LogManager.getLogger(VmCreationPostProcessingService.class);

    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private TemplateApiService templateService;
    @Inject
    private ResourceTagDao resourceTagDao;
    @Inject
    private VmExtraConfigService vmExtraConfigService;
    @Inject
    private VmLeaseApplicationService vmLeaseApplicationService;

    public void processCreatedVm(BaseDeployVMCmd cmd, UserVm vm, VirtualMachineTemplate template,
                                 ServiceOfferingJoinVO serviceOffering, Long callerId, boolean isLeaseFeatureEnabled) {
        attachChildIsoTemplates(vm, template);
        persistExtraConfig(cmd, vm, callerId);
        copyImageTags(cmd, vm, template);
        applyLease(cmd, vm, serviceOffering, isLeaseFeatureEnabled);
    }

    void processCreatedVm(BaseDeployVMCmd cmd, UserVm vm, VirtualMachineTemplate template,
                          ServiceOfferingJoinVO serviceOffering, Long callerId, boolean isLeaseFeatureEnabled,
                          boolean isAdditionalVmConfigEnabled) {
        attachChildIsoTemplates(vm, template);
        persistExtraConfig(cmd, vm, isAdditionalVmConfigEnabled);
        copyImageTags(cmd, vm, template);
        applyLease(cmd, vm, serviceOffering, isLeaseFeatureEnabled);
    }

    private void attachChildIsoTemplates(UserVm vm, VirtualMachineTemplate template) {
        List<VMTemplateVO> childTemplates = templateDao.listByParentTemplatetId(template.getId());
        for (VMTemplateVO tmpl: childTemplates) {
            if (tmpl.getFormat() == Storage.ImageFormat.ISO) {
                LOG.info("MDOV trying to attach disk {} to the VM {}", tmpl, vm);
                templateService.attachIso(tmpl.getId(), vm.getId(), true);
            }
        }
    }

    private void persistExtraConfig(BaseDeployVMCmd cmd, UserVm vm, Long callerId) {
        String extraConfig = cmd.getExtraConfig();
        if (StringUtils.isNotBlank(extraConfig)) {
            persistExtraConfig(vm, extraConfig, UserVmManager.EnableAdditionalVmConfig.valueIn(callerId));
        }
    }

    private void persistExtraConfig(BaseDeployVMCmd cmd, UserVm vm, boolean isAdditionalVmConfigEnabled) {
        String extraConfig = cmd.getExtraConfig();
        if (StringUtils.isNotBlank(extraConfig)) {
            persistExtraConfig(vm, extraConfig, isAdditionalVmConfigEnabled);
        }
    }

    private void persistExtraConfig(UserVm vm, String extraConfig, boolean isAdditionalVmConfigEnabled) {
        if (isAdditionalVmConfigEnabled) {
            LOG.info("Adding extra configuration to user vm: {}", vm);
            vmExtraConfigService.addExtraConfig(vm, extraConfig);
        } else {
            throw new InvalidParameterValueException("attempted setting extraconfig but enable.additional.vm.configuration is disabled");
        }
    }

    private void copyImageTags(BaseDeployVMCmd cmd, UserVm vm, VirtualMachineTemplate template) {
        if (cmd.getCopyImageTags()) {
            VMTemplateVO templateOrIso = templateDao.findById(template.getId());
            if (templateOrIso != null) {
                final ResourceTag.ResourceObjectType templateType = (templateOrIso.getFormat() == ImageFormat.ISO) ? ResourceTag.ResourceObjectType.ISO : ResourceTag.ResourceObjectType.Template;
                final List<? extends ResourceTag> resourceTags = resourceTagDao.listBy(template.getId(), templateType);
                for (ResourceTag resourceTag : resourceTags) {
                    final ResourceTagVO copyTag = new ResourceTagVO(resourceTag.getKey(), resourceTag.getValue(), resourceTag.getAccountId(),
                            resourceTag.getDomainId(), vm.getId(), ResourceTag.ResourceObjectType.UserVm, resourceTag.getCustomer(), vm.getUuid());
                    resourceTagDao.persist(copyTag);
                }
            }
        }
    }

    private void applyLease(BaseDeployVMCmd cmd, UserVm vm, ServiceOfferingJoinVO serviceOffering, boolean isLeaseFeatureEnabled) {
        if (isLeaseFeatureEnabled) {
            vmLeaseApplicationService.applyLeaseOnCreateInstance(vm, cmd.getLeaseDuration(), cmd.getLeaseExpiryAction(), serviceOffering);
        }
    }
}
