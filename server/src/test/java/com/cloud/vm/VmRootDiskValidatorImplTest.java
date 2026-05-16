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
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.dao.VMTemplateDao;

@RunWith(MockitoJUnitRunner.class)
public class VmRootDiskValidatorImplTest {

    @Mock private VolumeApiService volumeService;
    @Mock private VMTemplateDao templateDao;

    @InjectMocks
    private VmRootDiskValidatorImpl validator;

    // ---- verifyAndGetDiskSize ----

    @Test
    public void nullDiskOfferingRejected() {
        assertThrows(InvalidParameterValueException.class,
                () -> validator.verifyAndGetDiskSize(null, 10L));
    }

    @Test
    public void customizedOfferingRequiresExplicitSize() {
        DiskOfferingVO offering = customizedOffering();
        assertThrows(InvalidParameterValueException.class,
                () -> validator.verifyAndGetDiskSize(offering, null));
    }

    @Test
    public void customizedOfferingMultipliesGibToBytes() {
        DiskOfferingVO offering = customizedOffering();
        // validateCustomDiskOfferingSizeRange is void; just allow it to be invoked.
        doNothing().when(volumeService).validateCustomDiskOfferingSizeRange(anyLong());
        lenient().when(volumeService.validateVolumeSizeInBytes(anyLong())).thenReturn(true);
        // 5 GiB → 5 * 2^30 bytes
        assertEquals(5L * 1024 * 1024 * 1024, validator.verifyAndGetDiskSize(offering, 5L));
    }

    @Test
    public void fixedSizeOfferingReturnsItsDiskSize() {
        DiskOfferingVO offering = org.mockito.Mockito.mock(DiskOfferingVO.class);
        lenient().when(offering.isCustomized()).thenReturn(false);
        lenient().when(offering.isComputeOnly()).thenReturn(false);
        lenient().when(offering.getDiskSize()).thenReturn(8589934592L); // 8 GiB
        lenient().when(volumeService.validateVolumeSizeInBytes(anyLong())).thenReturn(true);
        assertEquals(8589934592L, validator.verifyAndGetDiskSize(offering, null));
    }

    // ---- verifyIfHypervisorSupportsRootdiskSizeOverride ----

    @Test
    public void kvmSupportsRootdiskOverride() {
        validator.verifyIfHypervisorSupportsRootdiskSizeOverride(HypervisorType.KVM);
    }

    @Test
    public void hyperVDoesNotSupportRootdiskOverride() {
        // Many hypervisors don't support rootdisksize override; pick one likely-unsupported.
        try {
            validator.verifyIfHypervisorSupportsRootdiskSizeOverride(HypervisorType.LXC);
            org.junit.Assert.fail("LXC should not support rootdisksize override");
        } catch (InvalidParameterValueException expected) {
            // ok
        }
    }

    // ---- validateRootDiskResize ----

    @Test
    public void rootDiskSmallerThanTemplateRejected() {
        VMTemplateVO template = org.mockito.Mockito.mock(VMTemplateVO.class);
        when(template.getSize()).thenReturn((20L << 30)); // 20 GiB
        UserVmVO vm = org.mockito.Mockito.mock(UserVmVO.class);
        Map<String, String> params = new HashMap<>();

        assertThrows(InvalidParameterValueException.class,
                () -> validator.validateRootDiskResize(HypervisorType.KVM, 10L, template, vm, params));
    }

    @Test
    public void rootDiskEqualToTemplateClearsOverride() {
        VMTemplateVO template = org.mockito.Mockito.mock(VMTemplateVO.class);
        when(template.getSize()).thenReturn(10L << 30);
        UserVmVO vm = org.mockito.Mockito.mock(UserVmVO.class);
        Map<String, String> params = new HashMap<>();
        params.put(VmDetailConstants.ROOT_DISK_SIZE, "10");

        validator.validateRootDiskResize(HypervisorType.KVM, 10L, template, vm, params);

        assertFalse("override should be cleared when sizes match",
                params.containsKey(VmDetailConstants.ROOT_DISK_SIZE));
    }

    @Test
    public void rootDiskLargerThanTemplateAcceptedOnKvm() {
        VMTemplateVO template = org.mockito.Mockito.mock(VMTemplateVO.class);
        when(template.getSize()).thenReturn(5L << 30);
        UserVmVO vm = org.mockito.Mockito.mock(UserVmVO.class);
        Map<String, String> params = new HashMap<>();
        params.put(VmDetailConstants.ROOT_DISK_SIZE, "10");

        validator.validateRootDiskResize(HypervisorType.KVM, 10L, template, vm, params);

        // Override is preserved when sizes differ.
        assertTrue(params.containsKey(VmDetailConstants.ROOT_DISK_SIZE));
    }

    // ---- Helpers ----

    private DiskOfferingVO customizedOffering() {
        DiskOfferingVO offering = org.mockito.Mockito.mock(DiskOfferingVO.class);
        lenient().when(offering.isCustomized()).thenReturn(true);
        lenient().when(offering.isComputeOnly()).thenReturn(false);
        return offering;
    }
}
