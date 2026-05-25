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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.cloudstack.api.command.user.vm.BaseDeployVMCmd;
import org.apache.cloudstack.vm.lease.VMLeaseManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.vo.ServiceOfferingJoinVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.server.ResourceTag;
import com.cloud.storage.Storage;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.tags.ResourceTagVO;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.template.TemplateApiService;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.uservm.UserVm;

@RunWith(MockitoJUnitRunner.class)
public class VmCreationPostProcessingServiceTest {

    private static final long CALLER_ID = 7L;
    private static final long TEMPLATE_ID = 11L;
    private static final long VM_ID = 42L;
    private static final String VM_UUID = "vm-uuid";

    @Mock
    private VMTemplateDao templateDao;
    @Mock
    private TemplateApiService templateService;
    @Mock
    private ResourceTagDao resourceTagDao;
    @Mock
    private VmExtraConfigService vmExtraConfigService;
    @Mock
    private VmLeaseApplicationService vmLeaseApplicationService;
    @Mock
    private BaseDeployVMCmd cmd;
    @Mock
    private UserVm vm;
    @Mock
    private VirtualMachineTemplate template;
    @Mock
    private ServiceOfferingJoinVO serviceOffering;

    @InjectMocks
    private VmCreationPostProcessingService service;

    @Test
    public void processCreatedVmAttachesOnlyChildIsoTemplates() {
        VMTemplateVO childIso = template(Storage.ImageFormat.ISO, 21L);
        VMTemplateVO childQcow2 = template(Storage.ImageFormat.QCOW2, 22L);
        when(template.getId()).thenReturn(TEMPLATE_ID);
        when(vm.getId()).thenReturn(VM_ID);
        when(templateDao.listByParentTemplatetId(TEMPLATE_ID)).thenReturn(List.of(childIso, childQcow2));

        service.processCreatedVm(cmd, vm, template, serviceOffering, CALLER_ID, false);

        verify(templateService).attachIso(21L, VM_ID, true);
        verify(templateService, never()).attachIso(eq(22L), eq(VM_ID), eq(true));
    }

    @Test
    public void processCreatedVmRejectsExtraConfigWhenAdditionalConfigIsDisabled() {
        when(cmd.getExtraConfig()).thenReturn("guestinfo.foo=bar");

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.processCreatedVm(cmd, vm, template, serviceOffering, CALLER_ID, false));

        assertEquals("attempted setting extraconfig but enable.additional.vm.configuration is disabled", exception.getMessage());
        verify(vmExtraConfigService, never()).addExtraConfig(vm, "guestinfo.foo=bar");
    }

    @Test
    public void processCreatedVmPersistsExtraConfigWhenAdditionalConfigIsEnabled() {
        when(cmd.getExtraConfig()).thenReturn("guestinfo.foo=bar");

        service.processCreatedVm(cmd, vm, template, serviceOffering, CALLER_ID, false, true);

        verify(vmExtraConfigService).addExtraConfig(vm, "guestinfo.foo=bar");
    }

    @Test
    public void processCreatedVmCopiesImageTagsToCreatedVm() {
        VMTemplateVO templateOrIso = template(Storage.ImageFormat.ISO, TEMPLATE_ID);
        ResourceTag sourceTag = sourceTag();
        when(cmd.getCopyImageTags()).thenReturn(true);
        when(template.getId()).thenReturn(TEMPLATE_ID);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getUuid()).thenReturn(VM_UUID);
        when(templateDao.findById(TEMPLATE_ID)).thenReturn(templateOrIso);
        doReturn(List.of(sourceTag)).when(resourceTagDao).listBy(TEMPLATE_ID, ResourceTag.ResourceObjectType.ISO);

        service.processCreatedVm(cmd, vm, template, serviceOffering, CALLER_ID, false);

        ArgumentCaptor<ResourceTagVO> tagCaptor = ArgumentCaptor.forClass(ResourceTagVO.class);
        verify(resourceTagDao).persist(tagCaptor.capture());
        ResourceTagVO copy = tagCaptor.getValue();
        assertEquals("env", copy.getKey());
        assertEquals("prod", copy.getValue());
        assertEquals(3L, copy.getAccountId());
        assertEquals(4L, copy.getDomainId());
        assertEquals(VM_ID, copy.getResourceId());
        assertEquals(ResourceTag.ResourceObjectType.UserVm, copy.getResourceType());
        assertEquals("customer", copy.getCustomer());
        assertEquals(VM_UUID, copy.getResourceUuid());
    }

    @Test
    public void processCreatedVmAppliesLeaseWhenLeaseFeatureIsEnabled() {
        when(cmd.getLeaseDuration()).thenReturn(10);
        when(cmd.getLeaseExpiryAction()).thenReturn(VMLeaseManager.ExpiryAction.STOP);

        service.processCreatedVm(cmd, vm, template, serviceOffering, CALLER_ID, true);

        verify(vmLeaseApplicationService).applyLeaseOnCreateInstance(vm, 10, VMLeaseManager.ExpiryAction.STOP, serviceOffering);
    }

    private VMTemplateVO template(Storage.ImageFormat format, long id) {
        VMTemplateVO template = org.mockito.Mockito.mock(VMTemplateVO.class);
        when(template.getFormat()).thenReturn(format);
        when(template.getId()).thenReturn(id);
        return template;
    }

    private ResourceTag sourceTag() {
        ResourceTag tag = org.mockito.Mockito.mock(ResourceTag.class);
        when(tag.getKey()).thenReturn("env");
        when(tag.getValue()).thenReturn("prod");
        when(tag.getAccountId()).thenReturn(3L);
        when(tag.getDomainId()).thenReturn(4L);
        when(tag.getCustomer()).thenReturn("customer");
        return tag;
    }
}
