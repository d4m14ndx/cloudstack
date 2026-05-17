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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.apache.cloudstack.userdata.UserDataManager;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.element.UserDataServiceProvider;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.UserData;
import com.cloud.user.UserDataVO;
import com.cloud.user.dao.UserDataDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

/**
 * Unit tests for {@link VmCredentialResetServiceImpl} — the userdata
 * propagation, finalization, password encryption, and SSH-key detail
 * helpers extracted from {@code UserVmManagerImpl} as slice 15 of the
 * Phase 4 Spring-component decomposition.
 */
@RunWith(MockitoJUnitRunner.class)
public class VmCredentialResetServiceImplTest {

    @Mock private VMTemplateDao templateDao;
    @Mock private NicDao nicDao;
    @Mock private NetworkModel networkModel;
    @Mock private NetworkDao networkDao;
    @Mock private UserDataDao userDataDao;
    @Mock private UserDataManager userDataManager;
    @Mock private UserVmDao userVmDao;
    @Mock private VMInstanceDetailsDao vmInstanceDetailsDao;

    private VmCredentialResetServiceImpl service;

    @Before
    public void setUp() {
        service = new VmCredentialResetServiceImpl();
        ReflectionTestUtils.setField(service, "templateDao", templateDao);
        ReflectionTestUtils.setField(service, "nicDao", nicDao);
        ReflectionTestUtils.setField(service, "networkModel", networkModel);
        ReflectionTestUtils.setField(service, "networkDao", networkDao);
        ReflectionTestUtils.setField(service, "userDataDao", userDataDao);
        ReflectionTestUtils.setField(service, "userDataManager", userDataManager);
        ReflectionTestUtils.setField(service, "userVmDao", userVmDao);
        ReflectionTestUtils.setField(service, "vmInstanceDetailsDao", vmInstanceDetailsDao);
    }

    // ---- updateUserData ----

    @Test
    public void updateUserDataThrowsWhenNoNicsFound() {
        UserVm vm = mock(UserVm.class);
        when(vm.getId()).thenReturn(1L);
        when(vm.getTemplateId()).thenReturn(10L);

        VMTemplateVO template = mock(VMTemplateVO.class);
        when(templateDao.findByIdIncludingRemoved(10L)).thenReturn(template);
        when(nicDao.listByVmId(1L)).thenReturn(Collections.emptyList());

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.updateUserData(vm));
        assertTrue(ex.getMessage().contains("Failed to reset userdata"));
    }

    @Test
    public void updateUserDataThrowsWhenNicsNull() throws Exception {
        UserVm vm = mock(UserVm.class);
        when(vm.getId()).thenReturn(2L);
        when(vm.getTemplateId()).thenReturn(10L);

        VMTemplateVO template = mock(VMTemplateVO.class);
        when(templateDao.findByIdIncludingRemoved(10L)).thenReturn(template);
        when(nicDao.listByVmId(2L)).thenReturn(null);

        assertThrows(CloudRuntimeException.class, () -> service.updateUserData(vm));
    }

    @Test
    public void updateUserDataSucceedsWhenOneNicAcceptsData()
            throws ResourceUnavailableException, InsufficientCapacityException {
        UserVm vm = mock(UserVm.class);
        when(vm.getId()).thenReturn(3L);
        when(vm.getTemplateId()).thenReturn(10L);

        VMTemplateVO template = mock(VMTemplateVO.class);
        when(template.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(templateDao.findByIdIncludingRemoved(10L)).thenReturn(template);

        NicVO nic = mock(NicVO.class);
        when(nic.getNetworkId()).thenReturn(100L);
        when(nicDao.listByVmId(3L)).thenReturn(Arrays.asList(nic));

        NetworkVO network = mock(NetworkVO.class);
        when(network.getNetworkOfferingId()).thenReturn(200L);
        when(networkDao.findById(100L)).thenReturn(network);
        when(networkModel.areServicesSupportedByNetworkOffering(200L, Network.Service.UserData)).thenReturn(true);

        UserDataServiceProvider element = mock(UserDataServiceProvider.class);
        when(networkModel.getUserDataUpdateProvider(network)).thenReturn(element);
        when(element.saveUserData(any(), any(), any())).thenReturn(true);

        // should not throw
        service.updateUserData(vm);
    }

    // ---- applyUserData ----

    @Test
    public void applyUserDataReturnsTrueWhenProviderAcceptsData()
            throws ResourceUnavailableException, InsufficientCapacityException {
        UserVm vm = mock(UserVm.class);
        NicVO nic = mock(NicVO.class);
        when(nic.getNetworkId()).thenReturn(100L);

        NetworkVO network = mock(NetworkVO.class);
        when(network.getNetworkOfferingId()).thenReturn(200L);
        when(networkDao.findById(100L)).thenReturn(network);
        when(networkModel.areServicesSupportedByNetworkOffering(200L, Network.Service.UserData)).thenReturn(true);

        UserDataServiceProvider element = mock(UserDataServiceProvider.class);
        when(networkModel.getUserDataUpdateProvider(network)).thenReturn(element);
        when(element.saveUserData(any(), any(), any())).thenReturn(true);

        assertTrue(service.applyUserData(HypervisorType.KVM, vm, nic));
    }

    @Test
    public void applyUserDataReturnsFalseWhenNetworkDoesNotSupportUserData()
            throws ResourceUnavailableException, InsufficientCapacityException {
        UserVm vm = mock(UserVm.class);
        NicVO nic = mock(NicVO.class);
        when(nic.getNetworkId()).thenReturn(100L);

        NetworkVO network = mock(NetworkVO.class);
        when(network.getNetworkOfferingId()).thenReturn(200L);
        when(networkDao.findById(100L)).thenReturn(network);
        when(networkModel.areServicesSupportedByNetworkOffering(200L, Network.Service.UserData)).thenReturn(false);

        assertFalse(service.applyUserData(HypervisorType.KVM, vm, nic));
        verify(networkModel, never()).getUserDataUpdateProvider(any());
    }

    @Test
    public void applyUserDataThrowsWhenProviderElementIsNull()
            throws ResourceUnavailableException, InsufficientCapacityException {
        UserVm vm = mock(UserVm.class);
        NicVO nic = mock(NicVO.class);
        when(nic.getNetworkId()).thenReturn(100L);

        NetworkVO network = mock(NetworkVO.class);
        when(network.getNetworkOfferingId()).thenReturn(200L);
        when(networkDao.findById(100L)).thenReturn(network);
        when(networkModel.areServicesSupportedByNetworkOffering(200L, Network.Service.UserData)).thenReturn(true);
        when(networkModel.getUserDataUpdateProvider(network)).thenReturn(null);

        assertThrows(CloudRuntimeException.class, () -> service.applyUserData(HypervisorType.KVM, vm, nic));
    }

    @Test
    public void applyUserDataReturnsFalseWhenProviderReturnsFalse()
            throws ResourceUnavailableException, InsufficientCapacityException {
        UserVm vm = mock(UserVm.class);
        NicVO nic = mock(NicVO.class);
        when(nic.getNetworkId()).thenReturn(100L);

        NetworkVO network = mock(NetworkVO.class);
        when(network.getNetworkOfferingId()).thenReturn(200L);
        when(networkDao.findById(100L)).thenReturn(network);
        when(networkModel.areServicesSupportedByNetworkOffering(200L, Network.Service.UserData)).thenReturn(true);

        UserDataServiceProvider element = mock(UserDataServiceProvider.class);
        when(networkModel.getUserDataUpdateProvider(network)).thenReturn(element);
        when(element.saveUserData(any(), any(), any())).thenReturn(false);

        assertFalse(service.applyUserData(HypervisorType.KVM, vm, nic));
    }

    // ---- finalizeUserData ----

    @Test
    public void finalizeUserDataReturnsNullWhenNothingProvided() {
        assertNull(service.finalizeUserData(null, null, null));
    }

    @Test
    public void finalizeUserDataReturnsNullWhenTemplateHasNoUserData() {
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(null);
        assertNull(service.finalizeUserData(null, null, template));
    }

    @Test
    public void finalizeUserDataRejectsBothInputs() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.finalizeUserData("raw", 1L, null));
    }

    @Test
    public void finalizeUserDataReturnsRawUserDataWhenNoTemplate() {
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(null);
        assertEquals("mydata", service.finalizeUserData("mydata", null, template));
    }

    @Test
    public void finalizeUserDataFetchesFromDaoWhenUserDataIdProvided() {
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(null);

        UserDataVO ud = mock(UserDataVO.class);
        when(ud.getUserData()).thenReturn("fetched");
        when(userDataDao.findById(5L)).thenReturn(ud);

        assertEquals("fetched", service.finalizeUserData(null, 5L, template));
    }

    @Test
    public void finalizeUserDataThrowsOnDenyOverrideWhenInputProvided() {
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(99L);
        when(template.getUserDataOverridePolicy()).thenReturn(UserData.UserDataOverridePolicy.DENYOVERRIDE);

        assertThrows(CloudRuntimeException.class,
                () -> service.finalizeUserData(null, 1L, template));
    }

    @Test
    public void finalizeUserDataAllowOverrideFetchesApiUserData() {
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(99L);
        when(template.getUserDataOverridePolicy()).thenReturn(UserData.UserDataOverridePolicy.ALLOWOVERRIDE);

        UserDataVO apiUd = mock(UserDataVO.class);
        when(apiUd.getUserData()).thenReturn("api-data");
        when(userDataDao.findById(1L)).thenReturn(apiUd);

        assertEquals("api-data", service.finalizeUserData(null, 1L, template));
    }

    @Test
    public void finalizeUserDataAppendsUserdataWhenPolicyIsAppend() {
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        when(template.getUserDataId()).thenReturn(99L);
        when(template.getUserDataOverridePolicy()).thenReturn(UserData.UserDataOverridePolicy.APPEND);

        UserDataVO templateUd = mock(UserDataVO.class);
        when(templateUd.getUserData()).thenReturn("base");
        when(userDataDao.findById(99L)).thenReturn(templateUd);

        when(userDataManager.concatenateUserData("base", "extra", null)).thenReturn("base+extra");

        assertEquals("base+extra", service.finalizeUserData("extra", null, template));
    }

    // ---- encryptAndStorePassword ----

    @Test
    public void encryptAndStorePasswordNoOpWhenNoSshKey() {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getDetail(VmDetailConstants.SSH_PUBLIC_KEY)).thenReturn(null);

        service.encryptAndStorePassword(vm, "secret");
        verify(userVmDao, never()).saveDetails(any());
    }

    @Test
    public void encryptAndStorePasswordNoOpWhenPasswordNull() {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getDetail(VmDetailConstants.SSH_PUBLIC_KEY)).thenReturn("ssh-rsa AAAA");

        service.encryptAndStorePassword(vm, null);
        verify(userVmDao, never()).saveDetails(any());
    }

    @Test
    public void encryptAndStorePasswordNoOpWhenSavedPasswordSentinel() {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getDetail(VmDetailConstants.SSH_PUBLIC_KEY)).thenReturn("ssh-rsa AAAA");

        service.encryptAndStorePassword(vm, "saved_password");
        verify(userVmDao, never()).saveDetails(any());
    }

    @Test
    public void encryptAndStorePasswordWarnsAndSkipsForNonRsaKey() {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getDetail(VmDetailConstants.SSH_PUBLIC_KEY)).thenReturn("ecdsa-sha2-nistp256 BBBB");

        // Should not throw, should not call saveDetails
        service.encryptAndStorePassword(vm, "secret");
        verify(userVmDao, never()).saveDetails(any());
    }

    // ---- removeEncryptedPasswordFromUserVmVoDetails ----

    @Test
    public void removeEncryptedPasswordDelegatesToDao() {
        service.removeEncryptedPasswordFromUserVmVoDetails(42L);
        verify(vmInstanceDetailsDao).removeDetail(42L, VmDetailConstants.ENCRYPTED_PASSWORD);
    }

    @Test
    public void removeEncryptedPasswordUsesCorrectVmId() {
        service.removeEncryptedPasswordFromUserVmVoDetails(99L);
        verify(vmInstanceDetailsDao).removeDetail(eq(99L), anyString());
    }
}
