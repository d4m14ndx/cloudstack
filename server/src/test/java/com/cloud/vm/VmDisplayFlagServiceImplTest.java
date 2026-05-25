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

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import org.apache.cloudstack.framework.config.ConfigKey;

import com.cloud.offering.ServiceOffering;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.ResourceLimitService;

/**
 * Unit tests for {@link VmDisplayFlagServiceImpl} — the {@code displayVm}
 * flag mutation helper extracted from {@code UserVmManagerImpl} as
 * slice 17 of the Phase 4 Spring-component decomposition.
 */
@RunWith(MockitoJUnitRunner.class)
public class VmDisplayFlagServiceImplTest {

    @Mock private ServiceOfferingDao serviceOfferingDao;
    @Mock private VMTemplateDao templateDao;
    @Mock private ResourceLimitService resourceLimitMgr;
    @Mock private VolumeDao volsDao;
    @Mock private VolumeApiService volumeService;
    @Mock private VmUsageEventPublisher vmUsageEventPublisher;

    private VmDisplayFlagServiceImpl service;
    private Object originalResourceCountRunningOnlyDefault;

    @Before
    public void setUp() throws Exception {
        service = new VmDisplayFlagServiceImpl();
        ReflectionTestUtils.setField(service, "serviceOfferingDao", serviceOfferingDao);
        ReflectionTestUtils.setField(service, "templateDao", templateDao);
        ReflectionTestUtils.setField(service, "resourceLimitMgr", resourceLimitMgr);
        ReflectionTestUtils.setField(service, "volsDao", volsDao);
        ReflectionTestUtils.setField(service, "volumeService", volumeService);
        ReflectionTestUtils.setField(service, "vmUsageEventPublisher", vmUsageEventPublisher);
        // Capture the original ResourceCountRunningVMsonly default so we can
        // restore it after each test, and force "false" up front (most tests
        // assume resource counting applies on display-flag flips).
        Field f = ConfigKey.class.getDeclaredField("_defaultValue");
        f.setAccessible(true);
        originalResourceCountRunningOnlyDefault = f.get(VirtualMachineManager.ResourceCountRunningVMsonly);
        f.set(VirtualMachineManager.ResourceCountRunningVMsonly, "false");
    }

    @After
    public void tearDown() throws Exception {
        Field f = ConfigKey.class.getDeclaredField("_defaultValue");
        f.setAccessible(true);
        f.set(VirtualMachineManager.ResourceCountRunningVMsonly, originalResourceCountRunningOnlyDefault);
    }

    private UserVmVO mockUserVm(long vmId, long accountId, long serviceOfferingId, long templateId) {
        UserVmVO vm = org.mockito.Mockito.mock(UserVmVO.class);
        when(vm.getId()).thenReturn(vmId);
        when(vm.getAccountId()).thenReturn(accountId);
        when(vm.getServiceOfferingId()).thenReturn(serviceOfferingId);
        when(vm.getTemplateId()).thenReturn(templateId);
        return vm;
    }

    @Test
    public void applyDisplayFlagSetsTheFlagOnTheVo() {
        UserVmVO vm = mockUserVm(1L, 7L, 50L, 60L);
        when(serviceOfferingDao.findByIdIncludingRemoved(1L, 50L)).thenReturn(org.mockito.Mockito.mock(ServiceOfferingVO.class));
        when(templateDao.findByIdIncludingRemoved(60L)).thenReturn(org.mockito.Mockito.mock(VMTemplateVO.class));
        when(volsDao.findByInstanceAndType(eq(1L), any())).thenReturn(Collections.emptyList());

        service.applyDisplayFlag(true, 1L, vm);

        verify(vm).setDisplayVm(true);
    }

    @Test
    public void applyDisplayFlagIncrementsResourceCountWhenFlagBecomesTrue() {
        UserVmVO vm = mockUserVm(2L, 7L, 50L, 60L);
        ServiceOfferingVO offering = org.mockito.Mockito.mock(ServiceOfferingVO.class);
        VMTemplateVO template = org.mockito.Mockito.mock(VMTemplateVO.class);
        when(serviceOfferingDao.findByIdIncludingRemoved(2L, 50L)).thenReturn(offering);
        when(templateDao.findByIdIncludingRemoved(60L)).thenReturn(template);
        when(volsDao.findByInstanceAndType(eq(2L), any())).thenReturn(Collections.emptyList());

        service.applyDisplayFlag(true, 2L, vm);

        verify(resourceLimitMgr).incrementVmResourceCount(eq(7L), eq(true), eq((ServiceOffering) offering), eq((VirtualMachineTemplate) template));
        verify(resourceLimitMgr, never()).decrementVmResourceCount(anyLong(), anyBoolean(), any(), any());
    }

    @Test
    public void applyDisplayFlagDecrementsResourceCountWhenFlagBecomesFalse() {
        UserVmVO vm = mockUserVm(3L, 7L, 50L, 60L);
        ServiceOfferingVO offering = org.mockito.Mockito.mock(ServiceOfferingVO.class);
        VMTemplateVO template = org.mockito.Mockito.mock(VMTemplateVO.class);
        when(serviceOfferingDao.findByIdIncludingRemoved(3L, 50L)).thenReturn(offering);
        when(templateDao.findByIdIncludingRemoved(60L)).thenReturn(template);
        when(volsDao.findByInstanceAndType(eq(3L), any())).thenReturn(Collections.emptyList());

        service.applyDisplayFlag(false, 3L, vm);

        verify(resourceLimitMgr).decrementVmResourceCount(eq(7L), eq(true), eq((ServiceOffering) offering), eq((VirtualMachineTemplate) template));
        verify(resourceLimitMgr, never()).incrementVmResourceCount(anyLong(), anyBoolean(), any(), any());
    }

    @Test
    public void applyDisplayFlagSkipsResourceCountWhenRunningOnlyToggleIsOn() throws Exception {
        Field f = ConfigKey.class.getDeclaredField("_defaultValue");
        f.setAccessible(true);
        f.set(VirtualMachineManager.ResourceCountRunningVMsonly, "true");

        UserVmVO vm = mockUserVm(4L, 7L, 50L, 60L);
        when(serviceOfferingDao.findByIdIncludingRemoved(4L, 50L)).thenReturn(org.mockito.Mockito.mock(ServiceOfferingVO.class));
        when(templateDao.findByIdIncludingRemoved(60L)).thenReturn(org.mockito.Mockito.mock(VMTemplateVO.class));
        when(volsDao.findByInstanceAndType(eq(4L), any())).thenReturn(Collections.emptyList());

        service.applyDisplayFlag(true, 4L, vm);

        verify(resourceLimitMgr, never()).incrementVmResourceCount(anyLong(), anyBoolean(), any(), any());
        verify(resourceLimitMgr, never()).decrementVmResourceCount(anyLong(), anyBoolean(), any(), any());
    }

    @Test
    public void applyDisplayFlagPublishesUsageEventForTheVm() {
        UserVmVO vm = mockUserVm(5L, 7L, 50L, 60L);
        when(serviceOfferingDao.findByIdIncludingRemoved(5L, 50L)).thenReturn(org.mockito.Mockito.mock(ServiceOfferingVO.class));
        when(templateDao.findByIdIncludingRemoved(60L)).thenReturn(org.mockito.Mockito.mock(VMTemplateVO.class));
        when(volsDao.findByInstanceAndType(eq(5L), any())).thenReturn(Collections.emptyList());

        service.applyDisplayFlag(true, 5L, vm);

        verify(vmUsageEventPublisher).saveUsageEvent(vm);
    }

    @Test
    public void applyDisplayFlagUpdatesRootVolumeDisplayWhenRootExists() {
        UserVmVO vm = mockUserVm(6L, 7L, 50L, 60L);
        when(serviceOfferingDao.findByIdIncludingRemoved(6L, 50L)).thenReturn(org.mockito.Mockito.mock(ServiceOfferingVO.class));
        when(templateDao.findByIdIncludingRemoved(60L)).thenReturn(org.mockito.Mockito.mock(VMTemplateVO.class));

        VolumeVO root = org.mockito.Mockito.mock(VolumeVO.class);
        when(volsDao.findByInstanceAndType(6L, Volume.Type.ROOT)).thenReturn(Arrays.asList(root));
        when(volsDao.findByInstanceAndType(6L, Volume.Type.DATADISK)).thenReturn(Collections.emptyList());

        service.applyDisplayFlag(true, 6L, vm);

        verify(volumeService).updateDisplay(root, true);
    }

    @Test
    public void applyDisplayFlagSkipsRootVolumeWhenNoRoot() {
        UserVmVO vm = mockUserVm(7L, 7L, 50L, 60L);
        when(serviceOfferingDao.findByIdIncludingRemoved(7L, 50L)).thenReturn(org.mockito.Mockito.mock(ServiceOfferingVO.class));
        when(templateDao.findByIdIncludingRemoved(60L)).thenReturn(org.mockito.Mockito.mock(VMTemplateVO.class));
        when(volsDao.findByInstanceAndType(7L, Volume.Type.ROOT)).thenReturn(Collections.emptyList());
        when(volsDao.findByInstanceAndType(7L, Volume.Type.DATADISK)).thenReturn(Collections.emptyList());

        service.applyDisplayFlag(false, 7L, vm);

        verify(volumeService, never()).updateDisplay(any(Volume.class), anyBoolean());
    }

    @Test
    public void applyDisplayFlagUpdatesEveryDataVolume() {
        UserVmVO vm = mockUserVm(8L, 7L, 50L, 60L);
        when(serviceOfferingDao.findByIdIncludingRemoved(8L, 50L)).thenReturn(org.mockito.Mockito.mock(ServiceOfferingVO.class));
        when(templateDao.findByIdIncludingRemoved(60L)).thenReturn(org.mockito.Mockito.mock(VMTemplateVO.class));

        VolumeVO data1 = org.mockito.Mockito.mock(VolumeVO.class);
        VolumeVO data2 = org.mockito.Mockito.mock(VolumeVO.class);
        VolumeVO data3 = org.mockito.Mockito.mock(VolumeVO.class);
        when(volsDao.findByInstanceAndType(8L, Volume.Type.ROOT)).thenReturn(Collections.emptyList());
        when(volsDao.findByInstanceAndType(8L, Volume.Type.DATADISK)).thenReturn(Arrays.asList(data1, data2, data3));

        service.applyDisplayFlag(false, 8L, vm);

        verify(volumeService).updateDisplay(data1, false);
        verify(volumeService).updateDisplay(data2, false);
        verify(volumeService).updateDisplay(data3, false);
        verify(volumeService, times(3)).updateDisplay(any(Volume.class), eq(false));
    }

    @Test
    public void applyDisplayFlagCascadesRootAndDataInOneCall() {
        UserVmVO vm = mockUserVm(9L, 7L, 50L, 60L);
        when(serviceOfferingDao.findByIdIncludingRemoved(9L, 50L)).thenReturn(org.mockito.Mockito.mock(ServiceOfferingVO.class));
        when(templateDao.findByIdIncludingRemoved(60L)).thenReturn(org.mockito.Mockito.mock(VMTemplateVO.class));

        VolumeVO root = org.mockito.Mockito.mock(VolumeVO.class);
        VolumeVO data = org.mockito.Mockito.mock(VolumeVO.class);
        when(volsDao.findByInstanceAndType(9L, Volume.Type.ROOT)).thenReturn(Arrays.asList(root));
        when(volsDao.findByInstanceAndType(9L, Volume.Type.DATADISK)).thenReturn(Arrays.asList(data));

        service.applyDisplayFlag(true, 9L, vm);

        verify(volumeService).updateDisplay(root, true);
        verify(volumeService).updateDisplay(data, true);
    }

    @Test
    public void applyDisplayFlagPassesDisplayVmTrueToResourceCountEvenWhenFlagIsFalse() {
        // The contract: resource counts always pass displayVm=true here
        // regardless of the new flag value (the displayVm flag governs
        // increment vs decrement direction, not which counters are
        // affected).
        UserVmVO vm = mockUserVm(10L, 7L, 50L, 60L);
        ServiceOfferingVO offering = org.mockito.Mockito.mock(ServiceOfferingVO.class);
        when(serviceOfferingDao.findByIdIncludingRemoved(10L, 50L)).thenReturn(offering);
        when(templateDao.findByIdIncludingRemoved(60L)).thenReturn(org.mockito.Mockito.mock(VMTemplateVO.class));
        when(volsDao.findByInstanceAndType(eq(10L), any())).thenReturn(Collections.emptyList());

        service.applyDisplayFlag(false, 10L, vm);

        verify(resourceLimitMgr).decrementVmResourceCount(anyLong(), eq(true), any(), any());
    }

    @Test
    public void applyDisplayFlagLooksUpRootVolumesBeforeDataVolumes() {
        // Ensures the call order is ROOT first, then DATADISK — preserving
        // the original UserVmManagerImpl sequence so downstream observers
        // see the same ordering.
        UserVmVO vm = mockUserVm(11L, 7L, 50L, 60L);
        when(serviceOfferingDao.findByIdIncludingRemoved(11L, 50L)).thenReturn(org.mockito.Mockito.mock(ServiceOfferingVO.class));
        when(templateDao.findByIdIncludingRemoved(60L)).thenReturn(org.mockito.Mockito.mock(VMTemplateVO.class));
        when(volsDao.findByInstanceAndType(11L, Volume.Type.ROOT)).thenReturn(Collections.emptyList());
        when(volsDao.findByInstanceAndType(11L, Volume.Type.DATADISK)).thenReturn(Collections.emptyList());

        service.applyDisplayFlag(true, 11L, vm);

        org.mockito.InOrder inOrder = org.mockito.Mockito.inOrder(volsDao);
        inOrder.verify(volsDao).findByInstanceAndType(11L, Volume.Type.ROOT);
        inOrder.verify(volsDao).findByInstanceAndType(11L, Volume.Type.DATADISK);
    }

    @Test
    public void applyDisplayFlagPassesNewFlagValueToRootAndDataVolumeUpdates() {
        UserVmVO vm = mockUserVm(12L, 7L, 50L, 60L);
        when(serviceOfferingDao.findByIdIncludingRemoved(12L, 50L)).thenReturn(org.mockito.Mockito.mock(ServiceOfferingVO.class));
        when(templateDao.findByIdIncludingRemoved(60L)).thenReturn(org.mockito.Mockito.mock(VMTemplateVO.class));

        VolumeVO root = org.mockito.Mockito.mock(VolumeVO.class);
        VolumeVO data = org.mockito.Mockito.mock(VolumeVO.class);
        when(volsDao.findByInstanceAndType(12L, Volume.Type.ROOT)).thenReturn(Arrays.asList(root));
        when(volsDao.findByInstanceAndType(12L, Volume.Type.DATADISK)).thenReturn(Arrays.asList(data));

        service.applyDisplayFlag(false, 12L, vm);

        verify(volumeService).updateDisplay(root, false);
        verify(volumeService).updateDisplay(data, false);
    }

    @Test
    public void applyDisplayFlagUsesIncludingRemovedLookupsForOfferingAndTemplate() {
        // Defensive: the helper must use findByIdIncludingRemoved so it
        // still works when the offering or template has been soft-deleted
        // after the VM was created.
        UserVmVO vm = mockUserVm(13L, 7L, 50L, 60L);
        when(serviceOfferingDao.findByIdIncludingRemoved(13L, 50L)).thenReturn(org.mockito.Mockito.mock(ServiceOfferingVO.class));
        when(templateDao.findByIdIncludingRemoved(60L)).thenReturn(org.mockito.Mockito.mock(VMTemplateVO.class));
        when(volsDao.findByInstanceAndType(eq(13L), any())).thenReturn(Collections.emptyList());

        service.applyDisplayFlag(true, 13L, vm);

        verify(serviceOfferingDao).findByIdIncludingRemoved(13L, 50L);
        verify(templateDao).findByIdIncludingRemoved(60L);
    }

    @Test
    public void applyDisplayFlagDelegatesUsageEventEvenWhenNoVolumesExist() {
        UserVmVO vm = mockUserVm(14L, 7L, 50L, 60L);
        when(serviceOfferingDao.findByIdIncludingRemoved(14L, 50L)).thenReturn(org.mockito.Mockito.mock(ServiceOfferingVO.class));
        when(templateDao.findByIdIncludingRemoved(60L)).thenReturn(org.mockito.Mockito.mock(VMTemplateVO.class));
        when(volsDao.findByInstanceAndType(eq(14L), any())).thenReturn(Collections.emptyList());

        service.applyDisplayFlag(false, 14L, vm);

        verify(vmUsageEventPublisher).saveUsageEvent(vm);
        verify(volumeService, never()).updateDisplay(any(Volume.class), anyBoolean());
    }

    @Test
    public void applyDisplayFlagOnVmWithMultipleDataDisksUpdatesAllInListOrder() {
        UserVmVO vm = mockUserVm(15L, 7L, 50L, 60L);
        when(serviceOfferingDao.findByIdIncludingRemoved(15L, 50L)).thenReturn(org.mockito.Mockito.mock(ServiceOfferingVO.class));
        when(templateDao.findByIdIncludingRemoved(60L)).thenReturn(org.mockito.Mockito.mock(VMTemplateVO.class));

        VolumeVO d1 = org.mockito.Mockito.mock(VolumeVO.class);
        VolumeVO d2 = org.mockito.Mockito.mock(VolumeVO.class);
        when(volsDao.findByInstanceAndType(15L, Volume.Type.ROOT)).thenReturn(Collections.emptyList());
        List<VolumeVO> data = Arrays.asList(d1, d2);
        when(volsDao.findByInstanceAndType(15L, Volume.Type.DATADISK)).thenReturn(data);

        service.applyDisplayFlag(true, 15L, vm);

        org.mockito.InOrder order = org.mockito.Mockito.inOrder(volumeService);
        order.verify(volumeService).updateDisplay(d1, true);
        order.verify(volumeService).updateDisplay(d2, true);
    }
}
