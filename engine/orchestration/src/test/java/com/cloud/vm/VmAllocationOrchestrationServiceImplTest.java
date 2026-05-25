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

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.apache.cloudstack.api.ApiCommandResourceType;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.offering.DiskOffering;
import com.cloud.offering.DiskOfferingInfo;
import com.cloud.storage.Snapshot;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VMTemplateZoneVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class VmAllocationOrchestrationServiceImplTest {

    @InjectMocks
    private VmAllocationOrchestrationServiceImpl service;

    @Mock
    private VolumeDao volsDao;
    @Mock
    private VMTemplateDao templateDao;
    @Mock
    private VMTemplateZoneDao templateZoneDao;
    @Mock
    private VolumeOrchestrationService volumeMgr;

    @Test
    public void checkIfTemplateNeededForCreatingVmVolumes_existingRootVolumesReturnsWithoutTemplateLookup() {
        long vmId = 1L;
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(vmId);
        when(volsDao.findReadyRootVolumesByInstance(vmId)).thenReturn(List.of(mock(VolumeVO.class)));

        service.checkIfTemplateNeededForCreatingVmVolumes(vm);

        verify(templateDao, never()).findById(any());
    }

    @Test
    public void checkIfTemplateNeededForCreatingVmVolumes_missingTemplateThrows() {
        long vmId = 1L;
        long templateId = 2L;
        VMInstanceVO vm = mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(vmId);
        when(vm.getTemplateId()).thenReturn(templateId);
        when(volsDao.findReadyRootVolumesByInstance(vmId)).thenReturn(null);
        when(templateDao.findById(templateId)).thenReturn(null);

        assertThrows(CloudRuntimeException.class, () -> service.checkIfTemplateNeededForCreatingVmVolumes(vm));
    }

    @Test
    public void checkIfTemplateNeededForCreatingVmVolumes_missingZoneTemplateThrows() {
        long vmId = 1L;
        long templateId = 2L;
        long dataCenterId = 3L;
        VMInstanceVO vm = mock(VMInstanceVO.class);
        VMTemplateVO template = mock(VMTemplateVO.class);
        when(vm.getId()).thenReturn(vmId);
        when(vm.getTemplateId()).thenReturn(templateId);
        when(vm.getDataCenterId()).thenReturn(dataCenterId);
        when(volsDao.findReadyRootVolumesByInstance(vmId)).thenReturn(null);
        when(templateDao.findById(templateId)).thenReturn(template);
        when(template.getId()).thenReturn(templateId);

        assertThrows(CloudRuntimeException.class, () -> service.checkIfTemplateNeededForCreatingVmVolumes(vm));
    }

    @Test
    public void checkIfTemplateNeededForCreatingVmVolumes_templateAvailableReturns() {
        long vmId = 1L;
        long templateId = 2L;
        long dataCenterId = 3L;
        VMInstanceVO vm = mock(VMInstanceVO.class);
        VMTemplateVO template = mock(VMTemplateVO.class);
        when(vm.getId()).thenReturn(vmId);
        when(vm.getTemplateId()).thenReturn(templateId);
        when(vm.getDataCenterId()).thenReturn(dataCenterId);
        when(volsDao.findReadyRootVolumesByInstance(vmId)).thenReturn(new ArrayList<>());
        when(templateDao.findById(templateId)).thenReturn(template);
        when(template.getId()).thenReturn(templateId);
        when(templateZoneDao.findByZoneTemplate(dataCenterId, templateId)).thenReturn(mock(VMTemplateZoneVO.class));

        service.checkIfTemplateNeededForCreatingVmVolumes(vm);
    }

    @Test
    public void allocateRootVolume_isoTemplateAllocatesRawRootVolumeInVolumeContext() {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        DiskOfferingInfo rootDiskOfferingInfo = mock(DiskOfferingInfo.class);
        DiskOffering diskOffering = mock(DiskOffering.class);
        Account owner = mock(Account.class);
        when(vm.getId()).thenReturn(11L);
        when(template.getFormat()).thenReturn(ImageFormat.ISO);
        when(rootDiskOfferingInfo.getDiskOffering()).thenReturn(diskOffering);
        when(rootDiskOfferingInfo.getSize()).thenReturn(50L);
        when(rootDiskOfferingInfo.getMinIops()).thenReturn(10L);
        when(rootDiskOfferingInfo.getMaxIops()).thenReturn(20L);

        try (MockedStatic<CallContext> callContext = Mockito.mockStatic(CallContext.class)) {
            CallContext currentContext = mock(CallContext.class);
            callContext.when(CallContext::current).thenReturn(currentContext);
            callContext.when(() -> CallContext.register(currentContext, ApiCommandResourceType.Volume)).thenReturn(mock(CallContext.class));

            service.allocateRootVolume(vm, template, rootDiskOfferingInfo, owner, 100L, mock(Volume.class), mock(Snapshot.class));

            verify(volumeMgr).allocateRawVolume(Volume.Type.ROOT, "ROOT-11", diskOffering, 50L, 10L, 20L, vm, template, owner, null, true);
            callContext.verify(CallContext::unregister);
        }
    }

    @Test
    public void allocateRootVolume_externalTemplateSkipsRootVolumeAllocation() {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        DiskOfferingInfo rootDiskOfferingInfo = mock(DiskOfferingInfo.class);
        when(vm.getId()).thenReturn(11L);
        when(template.getFormat()).thenReturn(ImageFormat.EXTERNAL);

        try (MockedStatic<CallContext> callContext = Mockito.mockStatic(CallContext.class)) {
            CallContext currentContext = mock(CallContext.class);
            callContext.when(CallContext::current).thenReturn(currentContext);
            callContext.when(() -> CallContext.register(currentContext, ApiCommandResourceType.Volume)).thenReturn(mock(CallContext.class));

            service.allocateRootVolume(vm, template, rootDiskOfferingInfo, mock(Account.class), 100L, mock(Volume.class), mock(Snapshot.class));

            verify(volumeMgr, never()).allocateRawVolume(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), anyBoolean());
            verify(volumeMgr, never()).allocateTemplatedVolumes(any(), any(), any(), any(), any(), any(), any(), any(), any(), any(), any());
            callContext.verify(CallContext::unregister);
        }
    }

    @Test
    public void allocateRootVolume_regularTemplateAllocatesTemplatedRootVolumeInVolumeContext() {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        DiskOfferingInfo rootDiskOfferingInfo = mock(DiskOfferingInfo.class);
        DiskOffering diskOffering = mock(DiskOffering.class);
        Account owner = mock(Account.class);
        Volume volume = mock(Volume.class);
        Snapshot snapshot = mock(Snapshot.class);
        when(vm.getId()).thenReturn(11L);
        when(template.getFormat()).thenReturn(ImageFormat.QCOW2);
        when(rootDiskOfferingInfo.getDiskOffering()).thenReturn(diskOffering);
        when(rootDiskOfferingInfo.getMinIops()).thenReturn(10L);
        when(rootDiskOfferingInfo.getMaxIops()).thenReturn(20L);

        try (MockedStatic<CallContext> callContext = Mockito.mockStatic(CallContext.class)) {
            CallContext currentContext = mock(CallContext.class);
            callContext.when(CallContext::current).thenReturn(currentContext);
            callContext.when(() -> CallContext.register(currentContext, ApiCommandResourceType.Volume)).thenReturn(mock(CallContext.class));

            service.allocateRootVolume(vm, template, rootDiskOfferingInfo, owner, 100L, volume, snapshot);

            verify(volumeMgr).allocateTemplatedVolumes(Volume.Type.ROOT, "ROOT-11", diskOffering, 100L, 10L, 20L, template, vm, owner, volume, snapshot);
            callContext.verify(CallContext::unregister);
        }
    }
}
