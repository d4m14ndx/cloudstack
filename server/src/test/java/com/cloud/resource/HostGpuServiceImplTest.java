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
package com.cloud.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;

import org.apache.cloudstack.gpu.GpuService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetGPUStatsAnswer;
import com.cloud.agent.api.GetGPUStatsCommand;
import com.cloud.agent.api.UnsupportedAnswer;
import com.cloud.agent.api.VgpuTypesInfo;
import com.cloud.agent.api.to.GPUDeviceTO;
import com.cloud.gpu.GPU;
import com.cloud.gpu.GpuCardVO;
import com.cloud.gpu.HostGpuGroupsVO;
import com.cloud.gpu.VgpuProfileVO;
import com.cloud.gpu.dao.GpuCardDao;
import com.cloud.gpu.dao.HostGpuGroupsDao;
import com.cloud.gpu.dao.VGPUTypesDao;
import com.cloud.gpu.dao.VgpuProfileDao;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.ServiceOffering;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine;

@RunWith(MockitoJUnitRunner.class)
public class HostGpuServiceImplTest {

    private static final long HOST_ID = 42L;
    private static final long VM_ID = 100L;
    private static final long OFFERING_ID = 7L;
    private static final long PROFILE_ID = 33L;
    private static final long CARD_ID = 99L;

    @Mock private HostGpuGroupsDao hostGpuGroupsDao;
    @Mock private VGPUTypesDao vgpuTypesDao;
    @Mock private VgpuProfileDao vgpuProfileDao;
    @Mock private GpuCardDao gpuCardDao;
    @Mock private HostDao hostDao;
    @Mock private AgentManager agentManager;
    @Mock private ServiceOfferingDetailsDao serviceOfferingDetailsDao;
    @Mock private GpuService gpuService;

    @InjectMocks
    private HostGpuServiceImpl service;

    @Mock private HostVO host;
    @Mock private VirtualMachine vm;

    @SuppressWarnings("unchecked")
    @Before
    public void setUp() {
        lenient().when(host.getId()).thenReturn(HOST_ID);

        // Wire SearchBuilder chains needed by init() so PostConstruct-style
        // setup can be invoked from tests.
        SearchBuilder<HostGpuGroupsVO> outer = (SearchBuilder<HostGpuGroupsVO>) mock(SearchBuilder.class);
        SearchBuilder<com.cloud.gpu.VGPUTypesVO> inner = (SearchBuilder<com.cloud.gpu.VGPUTypesVO>) mock(SearchBuilder.class);
        HostGpuGroupsVO outerEntity = mock(HostGpuGroupsVO.class);
        com.cloud.gpu.VGPUTypesVO innerEntity = mock(com.cloud.gpu.VGPUTypesVO.class);
        lenient().when(outer.entity()).thenReturn(outerEntity);
        lenient().when(inner.entity()).thenReturn(innerEntity);
        lenient().when(hostGpuGroupsDao.createSearchBuilder()).thenReturn(outer);
        lenient().when(vgpuTypesDao.createSearchBuilder()).thenReturn(inner);

        SearchCriteria<HostGpuGroupsVO> sc = (SearchCriteria<HostGpuGroupsVO>) mock(SearchCriteria.class);
        lenient().when(outer.create()).thenReturn(sc);

        service.init();
    }

    // --- isGPUDeviceAvailable(ServiceOffering, Host, Long) ---

    @Test
    public void isGPUDeviceAvailable_offeringWithVgpuProfile_delegatesToProfilePath() {
        ServiceOffering offering = mock(ServiceOffering.class);
        when(offering.getVgpuProfileId()).thenReturn(PROFILE_ID);
        when(offering.getGpuCount()).thenReturn(2);
        VgpuProfileVO vgpuProfile = mock(VgpuProfileVO.class);
        when(vgpuProfileDao.findById(PROFILE_ID)).thenReturn(vgpuProfile);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(gpuService.isGPUDeviceAvailable(host, VM_ID, vgpuProfile, 2)).thenReturn(true);

        assertTrue(service.isGPUDeviceAvailable(offering, host, VM_ID));
        verify(gpuService).isGPUDeviceAvailable(host, VM_ID, vgpuProfile, 2);
    }

    @Test
    public void isGPUDeviceAvailable_offeringWithMissingVgpuProfile_returnsFalse() {
        ServiceOffering offering = mock(ServiceOffering.class);
        when(offering.getVgpuProfileId()).thenReturn(PROFILE_ID);
        when(vgpuProfileDao.findById(PROFILE_ID)).thenReturn(null);

        assertFalse(service.isGPUDeviceAvailable(offering, host, VM_ID));
    }

    @Test
    public void isGPUDeviceAvailable_offeringWithVgpuProfileNoCapacity_returnsFalse() {
        ServiceOffering offering = mock(ServiceOffering.class);
        when(offering.getVgpuProfileId()).thenReturn(PROFILE_ID);
        when(offering.getGpuCount()).thenReturn(null);
        VgpuProfileVO vgpuProfile = mock(VgpuProfileVO.class);
        when(vgpuProfileDao.findById(PROFILE_ID)).thenReturn(vgpuProfile);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(gpuService.isGPUDeviceAvailable(host, VM_ID, vgpuProfile, 1)).thenReturn(false);

        assertFalse(service.isGPUDeviceAvailable(offering, host, VM_ID));
    }

    @Test
    public void isGPUDeviceAvailable_offeringWithoutVgpuProfile_noLegacyDetail_returnsTrue() {
        ServiceOffering offering = mock(ServiceOffering.class);
        when(offering.getVgpuProfileId()).thenReturn(null);
        when(offering.getId()).thenReturn(OFFERING_ID);
        when(serviceOfferingDetailsDao.findDetail(OFFERING_ID, GPU.Keys.vgpuType.toString())).thenReturn(null);

        assertTrue(service.isGPUDeviceAvailable(offering, host, VM_ID));
    }

    // --- isGPUDeviceAvailable(Host, Long, VgpuProfileVO, int) ---

    @Test
    public void isGPUDeviceAvailable_xenServer_usesLegacyLookup() {
        VgpuProfileVO vgpuProfile = mock(VgpuProfileVO.class);
        when(vgpuProfile.getCardId()).thenReturn(CARD_ID);
        when(vgpuProfile.getName()).thenReturn("type-x");
        GpuCardVO card = mock(GpuCardVO.class);
        when(card.getGroupName()).thenReturn("group-1");
        when(gpuCardDao.findById(CARD_ID)).thenReturn(card);
        when(host.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        // hostGpuGroupsDao.customSearch returns empty -> no availability -> false
        when(hostGpuGroupsDao.customSearch(any(), any())).thenReturn(Collections.emptyList());

        assertFalse(service.isGPUDeviceAvailable(host, VM_ID, vgpuProfile, 1));
    }

    @Test
    public void isGPUDeviceAvailable_nonXenServer_delegatesToGpuService() {
        VgpuProfileVO vgpuProfile = mock(VgpuProfileVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(gpuService.isGPUDeviceAvailable(host, VM_ID, vgpuProfile, 4)).thenReturn(true);

        assertTrue(service.isGPUDeviceAvailable(host, VM_ID, vgpuProfile, 4));
        verify(gpuService).isGPUDeviceAvailable(host, VM_ID, vgpuProfile, 4);
    }

    // --- getGPUDevice(VirtualMachine, ...) ---

    @Test
    public void getGPUDevice_xenServer_resolvesViaLegacyLookup() {
        VgpuProfileVO vgpuProfile = mock(VgpuProfileVO.class);
        when(vgpuProfile.getCardId()).thenReturn(CARD_ID);
        when(vgpuProfile.getName()).thenReturn("type-y");
        GpuCardVO card = mock(GpuCardVO.class);
        when(card.getGroupName()).thenReturn("group-2");
        when(gpuCardDao.findById(CARD_ID)).thenReturn(card);
        when(vm.getHostId()).thenReturn(HOST_ID);
        when(host.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        when(hostDao.findById(HOST_ID)).thenReturn(host);

        HostGpuGroupsVO grp = mock(HostGpuGroupsVO.class);
        when(grp.getGroupName()).thenReturn("group-2");
        when(hostGpuGroupsDao.customSearch(any(), any())).thenReturn(List.of(grp));

        GPUDeviceTO to = service.getGPUDevice(vm, HOST_ID, vgpuProfile, 1);
        assertEquals("group-2", to.getGpuGroup());
        assertEquals("type-y", to.getVgpuType());
    }

    @Test
    public void getGPUDevice_nonXenServer_delegatesToGpuService() {
        VgpuProfileVO vgpuProfile = mock(VgpuProfileVO.class);
        when(vm.getHostId()).thenReturn(HOST_ID);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        GPUDeviceTO expected = mock(GPUDeviceTO.class);
        when(gpuService.getGPUDevice(vm, HOST_ID, vgpuProfile, 3)).thenReturn(expected);

        assertSame(expected, service.getGPUDevice(vm, HOST_ID, vgpuProfile, 3));
    }

    // --- getGPUDevice(hostId, groupName, vgpuType) ---

    @Test
    public void getGPUDevice_legacy_emptyAvailability_throws() {
        when(hostGpuGroupsDao.customSearch(any(), any())).thenReturn(Collections.emptyList());
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        assertThrows(CloudRuntimeException.class,
                () -> service.getGPUDevice(HOST_ID, "group-x", "type-x"));
    }

    @Test
    public void getGPUDevice_legacy_returnsDeviceTO() {
        HostGpuGroupsVO grp = mock(HostGpuGroupsVO.class);
        when(grp.getGroupName()).thenReturn("group-z");
        when(hostGpuGroupsDao.customSearch(any(), any())).thenReturn(List.of(grp));
        GPUDeviceTO to = service.getGPUDevice(HOST_ID, "group-z", "type-z");
        assertEquals("group-z", to.getGpuGroup());
        assertEquals("type-z", to.getVgpuType());
        // legacy constructor leaves the GPUDeviceTO.gpuDevices default (empty list)
        assertTrue(to.getGpuDevices() == null || to.getGpuDevices().isEmpty());
    }

    // --- isHostGpuEnabled / listAvailableGPUDevice ---

    @Test
    public void isHostGpuEnabled_returnsFalseWhenNoGroups() {
        when(hostGpuGroupsDao.customSearch(any(), any())).thenReturn(Collections.emptyList());
        assertFalse(service.isHostGpuEnabled(HOST_ID));
    }

    @Test
    public void isHostGpuEnabled_returnsTrueWhenGroupExists() {
        when(hostGpuGroupsDao.customSearch(any(), any())).thenReturn(List.of(mock(HostGpuGroupsVO.class)));
        assertTrue(service.isHostGpuEnabled(HOST_ID));
    }

    // --- updateGPUDetailsForVmStart / Stop ---

    @Test
    public void updateGPUDetailsForVmStart_withGpuDevices_allocatesAndRefreshes() {
        TransactionLegacy txn = TransactionLegacy.open("updateGPUDetailsForVmStart_withGpuDevices");
        try {
            GPUDeviceTO gpuDevice = mock(GPUDeviceTO.class);
            List<VgpuTypesInfo> devs = List.of(mock(VgpuTypesInfo.class));
            when(gpuDevice.getGpuDevices()).thenReturn(devs);
            HashMap<String, HashMap<String, VgpuTypesInfo>> details = new HashMap<>();
            details.put("g", new HashMap<>());
            when(gpuService.getGpuGroupDetailsFromGpuDevicesOnHost(HOST_ID)).thenReturn(details);

            service.updateGPUDetailsForVmStart(HOST_ID, VM_ID, gpuDevice);

            verify(gpuService).allocateGpuDevicesToVmOnHost(VM_ID, HOST_ID, devs);
            verify(gpuService).getGpuGroupDetailsFromGpuDevicesOnHost(HOST_ID);
        } finally {
            txn.close();
        }
    }

    @Test
    public void updateGPUDetailsForVmStart_withoutDeviceList_usesProvidedGroupDetails() {
        TransactionLegacy txn = TransactionLegacy.open("updateGPUDetailsForVmStart_withoutDeviceList");
        try {
            GPUDeviceTO gpuDevice = mock(GPUDeviceTO.class);
            when(gpuDevice.getGpuDevices()).thenReturn(null);
            HashMap<String, HashMap<String, VgpuTypesInfo>> details = new HashMap<>();
            when(gpuDevice.getGroupDetails()).thenReturn(details);

            service.updateGPUDetailsForVmStart(HOST_ID, VM_ID, gpuDevice);

            verify(gpuService, never()).allocateGpuDevicesToVmOnHost(anyLong(), anyLong(), any());
        } finally {
            txn.close();
        }
    }

    // --- getGPUStatistics ---

    @Test
    public void getGPUStatistics_unsupportedAnswer_returnsNull() {
        when(host.getGuid()).thenReturn("guid-1");
        when(host.getName()).thenReturn("h1");
        UnsupportedAnswer unsupported = mock(UnsupportedAnswer.class);
        when(agentManager.easySend(eq(HOST_ID), any(GetGPUStatsCommand.class))).thenReturn(unsupported);

        assertNull(service.getGPUStatistics(host));
    }

    @Test
    public void getGPUStatistics_nullAnswer_returnsNull() {
        when(host.getGuid()).thenReturn("guid-2");
        when(host.getName()).thenReturn("h2");
        when(agentManager.easySend(eq(HOST_ID), any(GetGPUStatsCommand.class))).thenReturn(null);

        assertNull(service.getGPUStatistics(host));
    }

    @Test
    public void getGPUStatistics_failedAnswer_returnsNull() {
        when(host.getGuid()).thenReturn("guid-3");
        when(host.getName()).thenReturn("h3");
        Answer failed = mock(Answer.class);
        when(failed.getResult()).thenReturn(false);
        when(agentManager.easySend(eq(HOST_ID), any(GetGPUStatsCommand.class))).thenReturn(failed);

        assertNull(service.getGPUStatistics(host));
    }

    @Test
    public void getGPUStatistics_gpuStatsAnswer_mergesGroupDetails() {
        when(host.getGuid()).thenReturn("guid-4");
        when(host.getName()).thenReturn("h4");
        GetGPUStatsAnswer answer = mock(GetGPUStatsAnswer.class);
        List<VgpuTypesInfo> devs = new ArrayList<>();
        devs.add(mock(VgpuTypesInfo.class));
        HashMap<String, HashMap<String, VgpuTypesInfo>> agentReported = new HashMap<>();
        when(answer.getResult()).thenReturn(true);
        when(answer.getGpuDevices()).thenReturn(devs);
        when(answer.getGroupDetails()).thenReturn(agentReported);
        when(agentManager.easySend(eq(HOST_ID), any(GetGPUStatsCommand.class))).thenReturn(answer);
        HashMap<String, HashMap<String, VgpuTypesInfo>> refreshed = new HashMap<>();
        when(gpuService.getGpuGroupDetailsFromGpuDevicesOnHost(HOST_ID)).thenReturn(refreshed);

        HashMap<String, HashMap<String, VgpuTypesInfo>> result = service.getGPUStatistics(host);
        assertSame(refreshed, result);
        verify(gpuService).addGpuDevicesToHost(host, devs);
    }

    @Test
    public void getGPUStatistics_gpuStatsAnswer_emptyDevices_returnsAgentDetails() {
        when(host.getGuid()).thenReturn("guid-5");
        when(host.getName()).thenReturn("h5");
        GetGPUStatsAnswer answer = mock(GetGPUStatsAnswer.class);
        HashMap<String, HashMap<String, VgpuTypesInfo>> agentReported = new HashMap<>();
        agentReported.put("g", new HashMap<>());
        when(answer.getResult()).thenReturn(true);
        when(answer.getGpuDevices()).thenReturn(Collections.emptyList());
        when(answer.getGroupDetails()).thenReturn(agentReported);
        when(agentManager.easySend(eq(HOST_ID), any(GetGPUStatsCommand.class))).thenReturn(answer);

        HashMap<String, HashMap<String, VgpuTypesInfo>> result = service.getGPUStatistics(host);
        assertSame(agentReported, result);
        verify(gpuService, times(1)).addGpuDevicesToHost(host, Collections.emptyList());
        verify(gpuService, never()).getGpuGroupDetailsFromGpuDevicesOnHost(anyLong());
    }

    // --- mock helper ---

    private static <T> T mock(Class<T> clazz) {
        return org.mockito.Mockito.mock(clazz);
    }
}
