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
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.api.command.user.vm.BaseDeployVMCmd;
import org.apache.cloudstack.api.command.user.vm.DeployVMCmd;
import org.apache.cloudstack.api.command.user.vm.DeployVnfApplianceCmd;
import org.apache.cloudstack.storage.template.VnfTemplateManager;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.dao.ServiceOfferingJoinDao;
import com.cloud.api.query.vo.ServiceOfferingJoinVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.Storage.TemplateType;
import com.cloud.template.VirtualMachineTemplate;

@RunWith(MockitoJUnitRunner.class)
public class VmCreationValidatorImplTest {

    @Mock private ServiceOfferingJoinDao serviceOfferingJoinDao;
    @Mock private VnfTemplateManager vnfTemplateManager;

    @InjectMocks
    private VmCreationValidatorImpl validator;

    // ---- verifyServiceOffering ----

    @Test
    public void inactiveOfferingRejected() {
        BaseDeployVMCmd cmd = new DeployVMCmd();
        ServiceOffering offering = mock(ServiceOffering.class);
        when(offering.getState()).thenReturn(ServiceOffering.State.Inactive);
        assertThrows(InvalidParameterValueException.class, () -> validator.verifyServiceOffering(cmd, offering));
    }

    @Test
    public void strictOfferingWithOverrideDiskOfferingRejected() {
        DeployVMCmd cmd = new DeployVMCmd();
        org.springframework.test.util.ReflectionTestUtils.setField(cmd, "overrideDiskOfferingId", 7L);
        ServiceOffering offering = mock(ServiceOffering.class);
        when(offering.getState()).thenReturn(ServiceOffering.State.Active);
        when(offering.getDiskOfferingStrictness()).thenReturn(true);
        assertThrows(InvalidParameterValueException.class, () -> validator.verifyServiceOffering(cmd, offering));
    }

    @Test
    public void staticOfferingWithCpuDetailRejected() {
        DeployVMCmd cmd = new DeployVMCmd();
        setRawDetails(cmd, VmDetailConstants.CPU_NUMBER, "2");
        ServiceOffering offering = mock(ServiceOffering.class);
        when(offering.getState()).thenReturn(ServiceOffering.State.Active);
        when(offering.isDynamic()).thenReturn(false);
        assertThrows(InvalidParameterValueException.class, () -> validator.verifyServiceOffering(cmd, offering));
    }

    @Test
    public void dynamicOfferingWithCpuDetailsAccepted() {
        DeployVMCmd cmd = new DeployVMCmd();
        setRawDetails(cmd, VmDetailConstants.CPU_NUMBER, "2");
        ServiceOffering offering = mock(ServiceOffering.class);
        when(offering.getState()).thenReturn(ServiceOffering.State.Active);
        when(offering.isDynamic()).thenReturn(true);
        validator.verifyServiceOffering(cmd, offering);
    }

    /**
     * BaseDeployVMCmd's raw {@code details} field uses the API "list of maps"
     * encoding — a Map keyed by index with HashMap<String,String> values —
     * not a flat Map<String,String>. {@code BaseCmd.convertDetailsToMap}
     * collapses it back to a flat map when callers invoke
     * {@code cmd.getDetails()}.
     */
    private static void setRawDetails(BaseDeployVMCmd cmd, String key, String value) {
        Map<Integer, HashMap<String, String>> raw = new HashMap<>();
        HashMap<String, String> nested = new HashMap<>();
        nested.put(key, value);
        raw.put(0, nested);
        org.springframework.test.util.ReflectionTestUtils.setField(cmd, "details", raw);
    }

    // ---- verifyTemplate ----

    @Test
    public void vnfTemplateValidatesNics() {
        DeployVMCmd cmd = new DeployVMCmd();
        org.springframework.test.util.ReflectionTestUtils.setField(cmd, "details", new HashMap<>());
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        when(template.getTemplateType()).thenReturn(TemplateType.VNF);
        validator.verifyTemplate(cmd, template, 1L);
        // Verifies that the VNF NIC validator was invoked
        org.mockito.Mockito.verify(vnfTemplateManager).validateVnfApplianceNics(template, cmd.getNetworkIds(), cmd.getVmNetworkMap());
    }

    @Test
    public void vnfApplianceCmdOnNonVnfTemplateRejected() {
        DeployVnfApplianceCmd cmd = new DeployVnfApplianceCmd();
        org.springframework.test.util.ReflectionTestUtils.setField(cmd, "details", new HashMap<>());
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        when(template.getTemplateType()).thenReturn(TemplateType.USER);
        assertThrows(InvalidParameterValueException.class, () -> validator.verifyTemplate(cmd, template, 1L));
    }

    @Test
    public void deployAsIsWithRootDiskSizedOfferingRejected() {
        DeployVMCmd cmd = new DeployVMCmd();
        org.springframework.test.util.ReflectionTestUtils.setField(cmd, "details", new HashMap<>());
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        when(template.getTemplateType()).thenReturn(TemplateType.USER);
        when(template.isDeployAsIs()).thenReturn(true);
        ServiceOfferingJoinVO offering = mock(ServiceOfferingJoinVO.class);
        when(offering.getRootDiskSize()).thenReturn(10L);
        when(serviceOfferingJoinDao.findById(anyLong())).thenReturn(offering);
        assertThrows(InvalidParameterValueException.class, () -> validator.verifyTemplate(cmd, template, 1L));
    }

    @Test
    public void deployAsIsWithRootDiskSizeOverrideRejected() {
        DeployVMCmd cmd = new DeployVMCmd();
        setRawDetails(cmd, "rootdisksize", "20");
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        when(template.getTemplateType()).thenReturn(TemplateType.USER);
        when(template.isDeployAsIs()).thenReturn(true);
        when(serviceOfferingJoinDao.findById(anyLong())).thenReturn(null);
        assertThrows(InvalidParameterValueException.class, () -> validator.verifyTemplate(cmd, template, 1L));
    }

    @Test
    public void deployAsIsWithBootModeRejected() {
        DeployVMCmd cmd = new DeployVMCmd();
        org.springframework.test.util.ReflectionTestUtils.setField(cmd, "details", new HashMap<>());
        org.springframework.test.util.ReflectionTestUtils.setField(cmd, "bootMode", "LEGACY");
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        when(template.getTemplateType()).thenReturn(TemplateType.USER);
        when(template.isDeployAsIs()).thenReturn(true);
        when(serviceOfferingJoinDao.findById(anyLong())).thenReturn(null);
        assertThrows(InvalidParameterValueException.class, () -> validator.verifyTemplate(cmd, template, 1L));
    }

    @Test
    public void nonDeployAsIsTemplatePasses() {
        DeployVMCmd cmd = new DeployVMCmd();
        org.springframework.test.util.ReflectionTestUtils.setField(cmd, "details", new HashMap<>());
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        when(template.getTemplateType()).thenReturn(TemplateType.USER);
        lenient().when(template.isDeployAsIs()).thenReturn(false);
        validator.verifyTemplate(cmd, template, 1L);
    }

    // ---- verifyDetails ----

    @Test
    public void nullDetailsAccepted() {
        validator.verifyDetails(null);
    }

    @Test
    public void emptyDetailsAccepted() {
        validator.verifyDetails(new HashMap<>());
    }

    @Test
    public void extraconfigKeyInDetailsRejected() {
        Map<String, String> details = new HashMap<>();
        details.put("extraconfig", "anything");
        assertThrows(InvalidParameterValueException.class, () -> validator.verifyDetails(details));
    }

    @Test
    public void extraconfigPrefixInDetailsRejected() {
        Map<String, String> details = new HashMap<>();
        details.put("extraconfig-1", "anything");
        assertThrows(InvalidParameterValueException.class, () -> validator.verifyDetails(details));
    }

    @Test
    public void detailsWithSaneIopsAccepted() {
        Map<String, String> details = new HashMap<>();
        details.put("minIops", "100");
        details.put("maxIops", "200");
        validator.verifyDetails(details);
    }

    // ---- verifyMinAndMaxIops ----

    @Test
    public void minWithoutMaxRejected() {
        assertThrows(InvalidParameterValueException.class, () -> validator.verifyMinAndMaxIops("100", null));
    }

    @Test
    public void maxWithoutMinRejected() {
        assertThrows(InvalidParameterValueException.class, () -> validator.verifyMinAndMaxIops(null, "200"));
    }

    @Test
    public void nonNumericMinRejected() {
        assertThrows(InvalidParameterValueException.class, () -> validator.verifyMinAndMaxIops("abc", "200"));
    }

    @Test
    public void nonNumericMaxRejected() {
        assertThrows(InvalidParameterValueException.class, () -> validator.verifyMinAndMaxIops("100", "xyz"));
    }

    @Test
    public void minAboveMaxRejected() {
        assertThrows(InvalidParameterValueException.class, () -> validator.verifyMinAndMaxIops("300", "200"));
    }

    @Test
    public void bothAbsentAccepted() {
        validator.verifyMinAndMaxIops(null, null);
    }

    @Test
    public void minLessThanMaxAccepted() {
        validator.verifyMinAndMaxIops("100", "200");
    }

    @Test
    public void equalMinAndMaxAccepted() {
        validator.verifyMinAndMaxIops("100", "100");
    }
}
