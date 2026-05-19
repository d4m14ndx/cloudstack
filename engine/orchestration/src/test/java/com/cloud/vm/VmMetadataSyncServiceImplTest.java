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
import static org.junit.Assert.assertFalse;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.utils.Pair;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmMetadataSyncServiceImplTest {

    private static final String VM_NAME = "i-2-3-VM";
    private static final long VM_ID = 42L;

    @InjectMocks
    private VmMetadataSyncServiceImpl service;

    @Mock
    private UserVmDao userVmDao;
    @Mock
    private VMInstanceDao vmDao;

    @Test
    public void syncVMMetaData_nullOrEmptyInputDoesNotQueryDaos() {
        service.syncVMMetaData(null);
        service.syncVMMetaData(Collections.emptyMap());

        verifyNoInteractions(userVmDao, vmDao);
    }

    @Test
    public void syncVMMetaData_matchingUserWithSamePlatformDoesNotSaveDetails() {
        when(userVmDao.getVmsDetailByNames(eq(Set.of(VM_NAME)), eq(VmDetailConstants.PLATFORM))).thenReturn(
                List.of(vmDetail(VM_NAME, VirtualMachine.Type.User, VM_ID, "ubuntu")));

        service.syncVMMetaData(Map.of(VM_NAME, "ubuntu"));

        verify(userVmDao, never()).findById(VM_ID);
        verify(userVmDao, never()).saveDetails(org.mockito.ArgumentMatchers.any(UserVmVO.class));
        verifyNoInteractions(vmDao);
    }

    @Test
    public void syncVMMetaData_matchingUserWithNewPlatformUpdatesDetailsAndRemovesTimeOffset() {
        UserVmVO userVm = userVmWithDetails(Map.of(
                VmDetailConstants.TIME_OFFSET, "old-offset",
                VmDetailConstants.HYPERVISOR_TOOLS_VERSION, "old-driver"));
        when(userVmDao.getVmsDetailByNames(eq(Set.of(VM_NAME)), eq(VmDetailConstants.PLATFORM))).thenReturn(
                List.of(vmDetail(VM_NAME, VirtualMachine.Type.User, VM_ID, "old-platform")));
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);

        service.syncVMMetaData(Map.of(VM_NAME, "new-platform"));

        assertEquals("new-platform", userVm.getDetails().get(VmDetailConstants.PLATFORM));
        assertEquals("xenserver56", userVm.getDetails().get(VmDetailConstants.HYPERVISOR_TOOLS_VERSION));
        assertFalse(userVm.getDetails().containsKey(VmDetailConstants.TIME_OFFSET));
        verify(userVmDao).loadDetails(userVm);
        verify(userVmDao).saveDetails(userVm);
    }

    @Test
    public void syncVMMetaData_deviceIdPlatformSetsXenserver61Driver() {
        UserVmVO userVm = userVmWithDetails(Collections.emptyMap());
        when(userVmDao.getVmsDetailByNames(eq(Set.of(VM_NAME)), eq(VmDetailConstants.PLATFORM))).thenReturn(
                List.of(vmDetail(VM_NAME, VirtualMachine.Type.User, VM_ID, "old-platform")));
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);

        service.syncVMMetaData(Map.of(VM_NAME, "xenserver device_id present"));

        assertEquals("xenserver61", userVm.getDetails().get(VmDetailConstants.HYPERVISOR_TOOLS_VERSION));
        verify(userVmDao).saveDetails(userVm);
    }

    @Test
    public void syncVMMetaData_missingJoinDetailUpdatesFallbackUserVm() {
        VMInstanceVO vm = org.mockito.Mockito.mock(VMInstanceVO.class);
        UserVmVO userVm = userVmWithDetails(Collections.emptyMap());
        when(userVmDao.getVmsDetailByNames(eq(Set.of(VM_NAME)), eq(VmDetailConstants.PLATFORM))).thenReturn(Collections.emptyList());
        when(vmDao.findVMByInstanceName(VM_NAME)).thenReturn(vm);
        when(vm.getType()).thenReturn(VirtualMachine.Type.User);
        when(vm.getId()).thenReturn(VM_ID);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);

        service.syncVMMetaData(Map.of(VM_NAME, "fallback-platform"));

        assertEquals("fallback-platform", userVm.getDetails().get(VmDetailConstants.PLATFORM));
        verify(userVmDao).saveDetails(userVm);
    }

    @Test
    public void syncVMMetaData_matchingNonUserVmDoesNotFallbackOrSave() {
        when(userVmDao.getVmsDetailByNames(eq(Set.of(VM_NAME)), eq(VmDetailConstants.PLATFORM))).thenReturn(
                List.of(vmDetail(VM_NAME, VirtualMachine.Type.DomainRouter, VM_ID, "old-platform")));

        service.syncVMMetaData(Map.of(VM_NAME, "new-platform"));

        verifyNoInteractions(vmDao);
        verify(userVmDao, never()).findById(VM_ID);
        verify(userVmDao, never()).saveDetails(org.mockito.ArgumentMatchers.any(UserVmVO.class));
    }

    private UserVmVO userVmWithDetails(Map<String, String> details) {
        UserVmVO userVm = new UserVmVO();
        userVm.setDetails(new HashMap<>(details));
        return userVm;
    }

    private Pair<Pair<String, VirtualMachine.Type>, Pair<Long, String>> vmDetail(String vmName, VirtualMachine.Type type, long vmId, String platform) {
        return new Pair<>(new Pair<>(vmName, type), new Pair<>(vmId, platform));
    }
}
