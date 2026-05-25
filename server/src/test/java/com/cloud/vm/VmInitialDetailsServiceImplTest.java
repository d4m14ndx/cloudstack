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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.to.deployasis.OVFPropertyTO;
import com.cloud.configuration.Config;
import com.cloud.deployasis.UserVmDeployAsIsDetailVO;
import com.cloud.deployasis.dao.TemplateDeployAsIsDetailsDao;
import com.cloud.deployasis.dao.UserVmDeployAsIsDetailsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.GuestOSVO;
import com.cloud.utils.crypt.DBEncryptionUtil;

@RunWith(MockitoJUnitRunner.class)
public class VmInitialDetailsServiceImplTest {

    @Mock
    private ConfigurationDao configDao;
    @Mock
    private TemplateDeployAsIsDetailsDao templateDeployAsIsDetailsDao;
    @Mock
    private UserVmDeployAsIsDetailsDao userVmDeployAsIsDetailsDao;

    @InjectMocks
    private VmInitialDetailsServiceImpl service;

    @Test
    public void updateDiskControllerUsesMacOsVmwareDefaults() {
        UserVmVO vm = new UserVmVO(1L, "i-1-1-vm", "vm", 10L, HypervisorType.VMware, 20L,
                false, false, 1L, 1L, 1L, 1L, null, null, null, "vm");
        GuestOSVO guestOs = new GuestOSVO();
        guestOs.setDisplayName("Apple Mac OS X 12");

        service.updateVMDiskController(vm, Map.of(), guestOs);

        assertEquals("TRUE", vm.getDetail(VmDetailConstants.SMC_PRESENT));
        assertEquals("scsi", vm.getDetail(VmDetailConstants.ROOT_DISK_CONTROLLER));
        assertEquals("scsi", vm.getDetail(VmDetailConstants.DATA_DISK_CONTROLLER));
        assertEquals("efi", vm.getDetail(VmDetailConstants.FIRMWARE));
        verify(configDao, never()).getValue(Config.VmwareRootDiskControllerType.key());
    }

    @Test
    public void updateDiskControllerDefaultsDataDiskToRootControllerForScsiRoot() {
        UserVmVO vm = new UserVmVO(1L, "i-1-1-vm", "vm", 10L, HypervisorType.VMware, 20L,
                false, false, 1L, 1L, 1L, 1L, null, null, null, "vm");
        GuestOSVO guestOs = new GuestOSVO();
        guestOs.setDisplayName("Ubuntu");
        Map<String, String> customParameters = new HashMap<>();
        customParameters.put(VmDetailConstants.ROOT_DISK_CONTROLLER, "pvscsi");

        service.updateVMDiskController(vm, customParameters, guestOs);

        assertEquals("pvscsi", vm.getDetail(VmDetailConstants.ROOT_DISK_CONTROLLER));
        assertEquals("pvscsi", vm.getDetail(VmDetailConstants.DATA_DISK_CONTROLLER));
    }

    @Test
    public void persistDeployAsIsPropertiesNormalizesBooleansNullsAndEncryptsPasswords() {
        UserVmVO vm = new UserVmVO(7L, "i-1-1-vm", "vm", 99L, HypervisorType.VMware, 20L,
                false, false, 1L, 1L, 1L, 1L, null, null, null, "vm");
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("enabled", "true");
        properties.put("missing", null);
        properties.put("password", "clearText");
        OVFPropertyTO passwordProperty = new OVFPropertyTO();
        passwordProperty.setPassword(true);
        when(templateDeployAsIsDetailsDao.findPropertyByTemplateAndKey(99L, "password")).thenReturn(passwordProperty);
        try (MockedStatic<DBEncryptionUtil> encryptionUtil = mockStatic(DBEncryptionUtil.class)) {
            encryptionUtil.when(() -> DBEncryptionUtil.encrypt("clearText")).thenReturn("encrypted");

            service.persistVMDeployAsIsProperties(vm, properties);
        }

        ArgumentCaptor<UserVmDeployAsIsDetailVO> captor = ArgumentCaptor.forClass(UserVmDeployAsIsDetailVO.class);
        verify(userVmDeployAsIsDetailsDao, times(3)).persist(captor.capture());
        assertEquals("True", captor.getAllValues().get(0).getValue());
        assertEquals("", captor.getAllValues().get(1).getValue());
        assertEquals("encrypted", captor.getAllValues().get(2).getValue());
        verify(templateDeployAsIsDetailsDao).findPropertyByTemplateAndKey(eq(99L), eq("password"));
    }

    @Test
    public void setVncPasswordForKvmOnlyUsesNonEmptyParameter() {
        UserVmVO vm = new UserVmVO(1L, "i-1-1-vm", "vm", 10L, HypervisorType.KVM, 20L,
                false, false, 1L, 1L, 1L, 1L, null, null, null, "vm");

        service.setVncPasswordForKvmIfAvailable(Map.of(VmDetailConstants.KVM_VNC_PASSWORD, "secret"), vm);

        assertEquals("secret", vm.getVncPassword());
        service.setVncPasswordForKvmIfAvailable(Map.of(VmDetailConstants.KVM_VNC_PASSWORD, ""), vm);
        assertEquals("secret", vm.getVncPassword());
        verify(userVmDeployAsIsDetailsDao, never()).persist(any());
    }
}
