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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.event.UsageEventVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDetailsDao;

@RunWith(MockitoJUnitRunner.class)
public class ServiceOfferingValidatorImplTest {

    @Mock private ServiceOfferingDetailsDao serviceOfferingDetailsDao;
    @Mock private VolumeService volumeService;

    @InjectMocks
    private ServiceOfferingValidatorImpl validator;

    // ---- validateCustomParameters ----

    @Test
    public void dynamicOfferingRejectsEmptyCustomParams() {
        ServiceOfferingVO offering = dynamicOffering();
        InvalidParameterValueException ex = assertThrows(
                InvalidParameterValueException.class,
                () -> validator.validateCustomParameters(offering, Collections.emptyMap()));
        assertTrue(ex.getMessage().contains("Need to specify custom parameter values"));
    }

    @Test
    public void customCpuOutsideMinMaxRejected() {
        ServiceOfferingVO offering = dynamicOffering();
        when(serviceOfferingDetailsDao.listDetailsKeyPairs(anyLong())).thenReturn(Map.of(
                ApiConstants.MIN_CPU_NUMBER, "2",
                ApiConstants.MAX_CPU_NUMBER, "8"));

        Map<String, String> params = new HashMap<>();
        params.put(UsageEventVO.DynamicParameters.cpuNumber.name(), "1");
        params.put(UsageEventVO.DynamicParameters.cpuSpeed.name(), "2000");
        params.put(UsageEventVO.DynamicParameters.memory.name(), "512");

        InvalidParameterValueException ex = assertThrows(
                InvalidParameterValueException.class,
                () -> validator.validateCustomParameters(offering, params));
        assertTrue(ex.getMessage().contains("Invalid CPU cores"));
    }

    @Test
    public void cpuOnPredefinedOfferingCannotBeOverridden() {
        ServiceOfferingVO offering = predefinedOffering();
        Map<String, String> params = new HashMap<>();
        params.put(UsageEventVO.DynamicParameters.cpuNumber.name(), "2");

        InvalidParameterValueException ex = assertThrows(
                InvalidParameterValueException.class,
                () -> validator.validateCustomParameters(offering, params));
        assertTrue(ex.getMessage().contains("not customizable"));
    }

    @Test
    public void zeroCpuSpeedRejectedOnDynamicOffering() {
        ServiceOfferingVO offering = dynamicOffering();
        when(serviceOfferingDetailsDao.listDetailsKeyPairs(anyLong())).thenReturn(Map.of());
        Map<String, String> params = new HashMap<>();
        params.put(UsageEventVO.DynamicParameters.cpuNumber.name(), "1");
        params.put(UsageEventVO.DynamicParameters.cpuSpeed.name(), "0");
        params.put(UsageEventVO.DynamicParameters.memory.name(), "512");

        InvalidParameterValueException ex = assertThrows(
                InvalidParameterValueException.class,
                () -> validator.validateCustomParameters(offering, params));
        assertTrue(ex.getMessage().contains("Invalid CPU speed"));
    }

    @Test
    public void memoryBelowMinRejected() {
        ServiceOfferingVO offering = dynamicOffering();
        when(serviceOfferingDetailsDao.listDetailsKeyPairs(anyLong())).thenReturn(Map.of(
                ApiConstants.MIN_MEMORY, "256"));
        Map<String, String> params = new HashMap<>();
        params.put(UsageEventVO.DynamicParameters.cpuNumber.name(), "1");
        params.put(UsageEventVO.DynamicParameters.cpuSpeed.name(), "1000");
        params.put(UsageEventVO.DynamicParameters.memory.name(), "128");

        InvalidParameterValueException ex = assertThrows(
                InvalidParameterValueException.class,
                () -> validator.validateCustomParameters(offering, params));
        assertTrue(ex.getMessage().contains("Invalid memory"));
    }

    // ---- validateOfferingMaxResource ----

    @Test
    public void offeringWithinDefaultBoundsAccepted() {
        ServiceOfferingVO offering = predefinedOffering();
        // 4 cores / 1024 MB — within the unlimited defaults.
        validator.validateOfferingMaxResource(offering);
    }

    // ---- validateDiskOfferingChecks ----

    @Test
    public void mismatchedStrictnessFlagRejected() {
        ServiceOfferingVO current = predefinedOffering();
        ServiceOfferingVO incoming = predefinedOffering();
        org.mockito.Mockito.when(current.getDiskOfferingStrictness()).thenReturn(true);
        org.mockito.Mockito.when(incoming.getDiskOfferingStrictness()).thenReturn(false);

        InvalidParameterValueException ex = assertThrows(
                InvalidParameterValueException.class,
                () -> validator.validateDiskOfferingChecks(current, incoming));
        assertTrue(ex.getMessage().contains("strictness flag"));
    }

    @Test
    public void strictModeRequiresSameDiskOfferingId() {
        ServiceOfferingVO current = predefinedOffering();
        ServiceOfferingVO incoming = predefinedOffering();
        org.mockito.Mockito.when(current.getDiskOfferingStrictness()).thenReturn(true);
        org.mockito.Mockito.when(incoming.getDiskOfferingStrictness()).thenReturn(true);
        org.mockito.Mockito.when(current.getDiskOfferingId()).thenReturn(10L);
        org.mockito.Mockito.when(incoming.getDiskOfferingId()).thenReturn(11L);

        InvalidParameterValueException ex = assertThrows(
                InvalidParameterValueException.class,
                () -> validator.validateDiskOfferingChecks(current, incoming));
        assertTrue(ex.getMessage().contains("disk offering id"));
    }

    @Test
    public void compatibleDiskOfferingsDelegateToVolumeService() {
        ServiceOfferingVO current = predefinedOffering();
        ServiceOfferingVO incoming = predefinedOffering();
        org.mockito.Mockito.when(current.getDiskOfferingStrictness()).thenReturn(false);
        org.mockito.Mockito.when(incoming.getDiskOfferingStrictness()).thenReturn(false);
        org.mockito.Mockito.when(current.getDiskOfferingId()).thenReturn(10L);
        org.mockito.Mockito.when(incoming.getDiskOfferingId()).thenReturn(11L);

        validator.validateDiskOfferingChecks(current, incoming);

        verify(volumeService).validateChangeDiskOfferingEncryptionType(10L, 11L);
    }

    // ---- Helpers ----

    private ServiceOfferingVO dynamicOffering() {
        ServiceOfferingVO offering = org.mockito.Mockito.mock(ServiceOfferingVO.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
        org.mockito.Mockito.lenient().when(offering.isDynamic()).thenReturn(true);
        org.mockito.Mockito.lenient().when(offering.getId()).thenReturn(1L);
        org.mockito.Mockito.lenient().when(offering.getUuid()).thenReturn("uuid-1");
        org.mockito.Mockito.lenient().when(offering.getCpu()).thenReturn(null);
        org.mockito.Mockito.lenient().when(offering.getSpeed()).thenReturn(null);
        org.mockito.Mockito.lenient().when(offering.getRamSize()).thenReturn(null);
        return offering;
    }

    private ServiceOfferingVO predefinedOffering() {
        ServiceOfferingVO offering = org.mockito.Mockito.mock(ServiceOfferingVO.class);
        org.mockito.Mockito.lenient().when(offering.isDynamic()).thenReturn(false);
        org.mockito.Mockito.lenient().when(offering.getId()).thenReturn(2L);
        org.mockito.Mockito.lenient().when(offering.getUuid()).thenReturn("uuid-2");
        org.mockito.Mockito.lenient().when(offering.getCpu()).thenReturn(4);
        org.mockito.Mockito.lenient().when(offering.getSpeed()).thenReturn(2400);
        org.mockito.Mockito.lenient().when(offering.getRamSize()).thenReturn(1024);
        return offering;
    }
}
