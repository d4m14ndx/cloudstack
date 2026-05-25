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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.apache.cloudstack.api.ApiConstants;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@RunWith(MockitoJUnitRunner.class)
public class VmExtraConfigServiceImplTest {

    @Mock private VMInstanceDetailsDao vmInstanceDetailsDao;

    @InjectMocks
    private VmExtraConfigServiceImpl service;

    // ---- isValidKeyValuePair ----

    @Test
    public void keyValuePairs_valid_cases() {
        assertTrue(service.isValidKeyValuePair("is-a-template=true\nHVM-boot-policy=\nPV-bootloader=pygrub\nPV-args=hvc0"));
        assertTrue(service.isValidKeyValuePair("nvp.vm-uuid=34b3d5ea-1c25-4bb0-9250-8dc3388bfa9b"));
        assertTrue(service.isValidKeyValuePair("key-1=value1"));
        assertTrue(service.isValidKeyValuePair("param:key-2=value2"));
        assertTrue(service.isValidKeyValuePair("my.config.v0=False"));
    }

    @Test
    public void keyValuePairs_rejects_no_equals() {
        assertFalse(service.isValidKeyValuePair("key"));
    }

    // ---- isValidXenOrVmwareConfiguration ----

    @Test
    public void allowed_key_accepted() {
        assertTrue(service.isValidXenOrVmwareConfiguration("PV-bootloader=pygrub", new String[]{"PV-bootloader", "PV-args"}));
    }

    @Test
    public void allowed_key_case_insensitive() {
        assertTrue(service.isValidXenOrVmwareConfiguration("pv-bootloader=pygrub", new String[]{"PV-bootloader"}));
    }

    @Test
    public void unknown_key_rejected() {
        assertFalse(service.isValidXenOrVmwareConfiguration("rogue-key=value", new String[]{"PV-bootloader"}));
    }

    // ---- decodeExtraConfig ----

    @Test
    public void decodeExtraConfigRoundtripsPlainText() {
        assertEquals("PV-args=hvc0", service.decodeExtraConfig("PV-args=hvc0"));
    }

    @Test
    public void decodeExtraConfigDecodesPercentEscapes() {
        // %3D => '='
        assertEquals("key=value", service.decodeExtraConfig("key%3Dvalue"));
    }

    // ---- addExtraConfig: dispatch ----

    @Test
    public void unsupportedHypervisorDispatchRejected() {
        UserVm vm = mock(UserVm.class);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.LXC);
        assertThrows(CloudRuntimeException.class, () -> service.addExtraConfig(vm, "key=value"));
    }

    // ---- persistExtraConfigVmware ----

    @Test
    public void persistVmwareWithMalformedInputRejected() {
        UserVm vm = mock(UserVm.class);
        assertThrows(CloudRuntimeException.class,
                () -> service.persistExtraConfigVmware("not-a-pair", vm));
    }

    // ---- persistExtraConfigXenServer ----

    @Test
    public void persistXenServerWithMalformedInputRejected() {
        UserVm vm = mock(UserVm.class);
        assertThrows(CloudRuntimeException.class,
                () -> service.persistExtraConfigXenServer("not-a-pair", vm));
    }

    // ---- persistExtraConfigKvm: DPDK shortcut path ----

    @Test
    public void persistKvmDpdkPathSkipsValidation() {
        // A KVM extra-config blob containing ':' triggers the DPDK shortcut in
        // validateKvmExtraConfig — no allow-list lookup needed.
        UserVm vm = mock(UserVm.class);
        when(vm.getId()).thenReturn(42L);
        when(vm.getAccountId()).thenReturn(1L);

        service.persistExtraConfigKvm("interface:\n<source/>", vm);

        // Expect at least one detail row written.
        verify(vmInstanceDetailsDao, times(1)).addDetail(eq(42L), eq(ApiConstants.EXTRA_CONFIG + "-interface"), any(), anyBoolean());
    }

    // ---- validateExtraConfig: feature flag gating ----

    @Test
    public void validateExtraConfigRejectedWhenFlagDisabledForAccount() {
        // EnableAdditionalVmConfig defaults to false. Without raising the flag,
        // any account-scoped lookup should refuse.
        assertThrows(CloudRuntimeException.class,
                () -> service.validateExtraConfig(1L, HypervisorType.KVM, "<cfg/>"));
    }

}
