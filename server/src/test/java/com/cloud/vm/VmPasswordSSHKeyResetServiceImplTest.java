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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.BaseCmd.HTTPMethod;
import org.apache.cloudstack.api.command.user.vm.ResetVMPasswordCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMSSHKeyCmd;
import org.apache.cloudstack.api.command.user.vm.ResetVMUserDataCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.userdata.UserDataManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.element.UserDataServiceProvider;
import com.cloud.server.ManagementService;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.SSHKeyPairVO;
import com.cloud.user.UserVO;
import com.cloud.user.dao.SSHKeyPairDao;
import com.cloud.uservm.UserVm;
import com.cloud.utils.crypt.DBEncryptionUtil;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class VmPasswordSSHKeyResetServiceImplTest {

    @InjectMocks
    private VmPasswordSSHKeyResetServiceImpl service;

    @Mock
    private UserVmDao _vmDao;
    @Mock
    private VMTemplateDao _templateDao;
    @Mock
    private NetworkDao _networkDao;
    @Mock
    private SSHKeyPairDao _sshKeyPairDao;
    @Mock
    private AccountManager _accountMgr;
    @Mock
    private NetworkOrchestrationService _networkMgr;
    @Mock
    private NetworkModel _networkModel;
    @Mock
    private UserDataManager userDataManager;
    @Mock
    private VmCredentialResetService vmCredentialResetService;
    @Mock
    private VmRebootService vmRebootService;
    @Mock
    private ManagementService _mgr;

    @Mock
    private UserVmVO userVmVoMock;
    @Mock
    private VMTemplateVO vmTemplateVoMock;
    @Mock
    private VMInstanceVO vmInstanceMock;
    @Mock
    private AccountVO callerAccount;
    @Mock
    private UserVO callerUser;
    @Mock
    private Account ownerAccount;

    private static final long VM_ID = 1L;
    private static final long TEMPLATE_ID = 2L;
    private static final long ACCOUNT_ID = 3L;
    private static final long DOMAIN_ID = 4L;
    private static final long NETWORK_ID = 5L;
    private static final long USER_ID = 10L;

    private MockedStatic<CallContext> callContextStatic;

    @Before
    public void setUp() {
        Mockito.lenient().when(callerAccount.getType()).thenReturn(Account.Type.ADMIN);
        CallContext.register(callerUser, callerAccount);
    }

    @After
    public void tearDown() {
        CallContext.unregisterAll();
    }

    // ---- resetVMPassword tests ----

    @Test
    public void resetVMPassword_vmNotFound_throwsInvalidParameter() {
        ResetVMPasswordCmd cmd = Mockito.mock(ResetVMPasswordCmd.class);
        when(cmd.getId()).thenReturn(VM_ID);
        when(_vmDao.findById(VM_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.resetVMPassword(cmd, "pw"));
    }

    @Test
    public void resetVMPassword_templatePasswordNotEnabled_throwsInvalidParameter() {
        ResetVMPasswordCmd cmd = Mockito.mock(ResetVMPasswordCmd.class);
        when(cmd.getId()).thenReturn(VM_ID);
        when(_vmDao.findById(VM_ID)).thenReturn(userVmVoMock);
        when(userVmVoMock.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(vmTemplateVoMock);
        when(vmTemplateVoMock.isEnablePassword()).thenReturn(false);

        assertThrows(InvalidParameterValueException.class,
                () -> service.resetVMPassword(cmd, "pw"));
    }

    @Test
    public void resetVMPassword_vmInErrorState_throwsInvalidParameter() {
        ResetVMPasswordCmd cmd = Mockito.mock(ResetVMPasswordCmd.class);
        when(cmd.getId()).thenReturn(VM_ID);
        when(_vmDao.findById(VM_ID)).thenReturn(userVmVoMock);
        when(userVmVoMock.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(vmTemplateVoMock);
        when(vmTemplateVoMock.isEnablePassword()).thenReturn(true);
        when(userVmVoMock.getState()).thenReturn(State.Error);

        assertThrows(InvalidParameterValueException.class,
                () -> service.resetVMPassword(cmd, "pw"));
    }

    @Test
    public void resetVMPassword_vmNotStopped_throwsInvalidParameter() {
        ResetVMPasswordCmd cmd = Mockito.mock(ResetVMPasswordCmd.class);
        when(cmd.getId()).thenReturn(VM_ID);
        when(_vmDao.findById(VM_ID)).thenReturn(userVmVoMock);
        when(userVmVoMock.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(vmTemplateVoMock);
        when(vmTemplateVoMock.isEnablePassword()).thenReturn(true);
        when(userVmVoMock.getState()).thenReturn(State.Running);

        assertThrows(InvalidParameterValueException.class,
                () -> service.resetVMPassword(cmd, "pw"));
    }

    @Test
    public void resetVMPassword_happyPath_setsPasswordAndReturnsVm()
            throws ResourceUnavailableException, InsufficientCapacityException {
        ResetVMPasswordCmd cmd = Mockito.mock(ResetVMPasswordCmd.class);
        when(cmd.getId()).thenReturn(VM_ID);
        // First findById returns the userVm, second (in internal) also returns it
        when(_vmDao.findById(VM_ID)).thenReturn(userVmVoMock);
        when(userVmVoMock.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(vmTemplateVoMock);
        when(vmTemplateVoMock.isEnablePassword()).thenReturn(true);
        when(userVmVoMock.getState()).thenReturn(State.Stopped);

        // Stub internal: password not empty, default nic not null
        Nic nic = Mockito.mock(Nic.class);
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(_networkModel.getDefaultNic(VM_ID)).thenReturn(nic);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        when(_networkDao.findById(NETWORK_ID)).thenReturn(network);
        UserDataServiceProvider element = Mockito.mock(UserDataServiceProvider.class);
        when(element.savePassword(any(), any(), any())).thenReturn(true);
        when(_networkMgr.getPasswordResetProvider(network)).thenReturn(element);

        UserVm result = service.resetVMPassword(cmd, "newpassword");

        assertNotNull(result);
        verify(userVmVoMock).setPassword("newpassword");
    }

    // ---- resetVMPasswordInternal tests ----

    @Test
    public void resetVMPasswordInternal_emptyPassword_returnsFalse()
            throws ResourceUnavailableException, InsufficientCapacityException {
        assertFalse(service.resetVMPasswordInternal(VM_ID, ""));
    }

    @Test
    public void resetVMPasswordInternal_nullPassword_returnsFalse()
            throws ResourceUnavailableException, InsufficientCapacityException {
        assertFalse(service.resetVMPasswordInternal(VM_ID, null));
    }

    @Test
    public void resetVMPasswordInternal_defaultNicNull_returnsFalse()
            throws ResourceUnavailableException, InsufficientCapacityException {
        when(_vmDao.findById(VM_ID)).thenReturn(userVmVoMock);
        when(userVmVoMock.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(vmTemplateVoMock);
        when(vmTemplateVoMock.isEnablePassword()).thenReturn(true);
        when(_networkModel.getDefaultNic(VM_ID)).thenReturn(null);

        assertFalse(service.resetVMPasswordInternal(VM_ID, "password"));
    }

    @Test
    public void resetVMPasswordInternal_providerNull_throwsCloudRuntime()
            throws ResourceUnavailableException, InsufficientCapacityException {
        when(_vmDao.findById(VM_ID)).thenReturn(userVmVoMock);
        when(userVmVoMock.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(vmTemplateVoMock);
        when(vmTemplateVoMock.isEnablePassword()).thenReturn(true);
        Nic nic = Mockito.mock(Nic.class);
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(_networkModel.getDefaultNic(VM_ID)).thenReturn(nic);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        when(_networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(_networkMgr.getPasswordResetProvider(network)).thenReturn(null);

        assertThrows(CloudRuntimeException.class,
                () -> service.resetVMPasswordInternal(VM_ID, "password"));
    }

    @Test
    public void resetVMPasswordInternal_savePasswordFails_returnsFalse()
            throws ResourceUnavailableException, InsufficientCapacityException {
        when(_vmDao.findById(VM_ID)).thenReturn(userVmVoMock);
        when(userVmVoMock.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(vmTemplateVoMock);
        when(vmTemplateVoMock.isEnablePassword()).thenReturn(true);
        Nic nic = Mockito.mock(Nic.class);
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(_networkModel.getDefaultNic(VM_ID)).thenReturn(nic);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        when(_networkDao.findById(NETWORK_ID)).thenReturn(network);
        UserDataServiceProvider element = Mockito.mock(UserDataServiceProvider.class);
        when(element.savePassword(any(), any(), any())).thenReturn(false);
        when(_networkMgr.getPasswordResetProvider(network)).thenReturn(element);

        assertFalse(service.resetVMPasswordInternal(VM_ID, "password"));
    }

    @Test
    public void resetVMPasswordInternal_vmStopped_skipsRebootReturnsTrue()
            throws ResourceUnavailableException, InsufficientCapacityException {
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        when(_vmDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(vmTemplateVoMock);
        when(vmTemplateVoMock.isEnablePassword()).thenReturn(true);
        Nic nic = Mockito.mock(Nic.class);
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(_networkModel.getDefaultNic(VM_ID)).thenReturn(nic);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        when(_networkDao.findById(NETWORK_ID)).thenReturn(network);
        UserDataServiceProvider element = Mockito.mock(UserDataServiceProvider.class);
        when(element.savePassword(any(), any(), any())).thenReturn(true);
        when(_networkMgr.getPasswordResetProvider(network)).thenReturn(element);
        when(vm.getState()).thenReturn(State.Stopped);

        boolean result = service.resetVMPasswordInternal(VM_ID, "password");

        assertTrue(result);
        verify(vmRebootService, never()).rebootVirtualMachineInternal(anyLong(), anyLong(), anyBoolean(), anyBoolean());
        verify(vmCredentialResetService).encryptAndStorePassword(any(), anyString());
    }

    @Test
    public void resetVMPasswordInternal_rebootSucceeds_returnsTrue()
            throws ResourceUnavailableException, InsufficientCapacityException {
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        when(_vmDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(vmTemplateVoMock);
        when(vmTemplateVoMock.isEnablePassword()).thenReturn(true);
        Nic nic = Mockito.mock(Nic.class);
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(_networkModel.getDefaultNic(VM_ID)).thenReturn(nic);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        when(_networkDao.findById(NETWORK_ID)).thenReturn(network);
        UserDataServiceProvider element = Mockito.mock(UserDataServiceProvider.class);
        when(element.savePassword(any(), any(), any())).thenReturn(true);
        when(_networkMgr.getPasswordResetProvider(network)).thenReturn(element);
        when(vm.getState()).thenReturn(State.Running);
        when(vmRebootService.rebootVirtualMachineInternal(anyLong(), anyLong(), anyBoolean(), anyBoolean()))
                .thenReturn(Mockito.mock(UserVm.class));

        assertTrue(service.resetVMPasswordInternal(VM_ID, "password"));
    }

    @Test
    public void resetVMPasswordInternal_rebootFails_returnsFalse()
            throws ResourceUnavailableException, InsufficientCapacityException {
        UserVmVO vm = Mockito.mock(UserVmVO.class);
        when(_vmDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(vmTemplateVoMock);
        when(vmTemplateVoMock.isEnablePassword()).thenReturn(true);
        Nic nic = Mockito.mock(Nic.class);
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(_networkModel.getDefaultNic(VM_ID)).thenReturn(nic);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        when(_networkDao.findById(NETWORK_ID)).thenReturn(network);
        UserDataServiceProvider element = Mockito.mock(UserDataServiceProvider.class);
        when(element.savePassword(any(), any(), any())).thenReturn(true);
        when(_networkMgr.getPasswordResetProvider(network)).thenReturn(element);
        when(vm.getState()).thenReturn(State.Running);
        when(vmRebootService.rebootVirtualMachineInternal(anyLong(), anyLong(), anyBoolean(), anyBoolean()))
                .thenReturn(null);

        assertFalse(service.resetVMPasswordInternal(VM_ID, "password"));
    }

    // ---- resetVMUserData tests ----

    @Test
    public void resetVMUserData_vmNotFound_throwsInvalidParameter() {
        ResetVMUserDataCmd cmd = Mockito.mock(ResetVMUserDataCmd.class);
        when(cmd.getId()).thenReturn(VM_ID);
        when(_vmDao.findById(VM_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.resetVMUserData(cmd));
    }

    @Test
    public void resetVMUserData_sharedFsVm_throwsInvalidParameter() {
        ResetVMUserDataCmd cmd = Mockito.mock(ResetVMUserDataCmd.class);
        when(cmd.getId()).thenReturn(VM_ID);
        when(_vmDao.findById(VM_ID)).thenReturn(userVmVoMock);
        when(userVmVoMock.getUserVmType()).thenReturn(UserVmManager.SHAREDFSVM);

        assertThrows(InvalidParameterValueException.class,
                () -> service.resetVMUserData(cmd));
    }

    @Test
    public void resetVMUserData_externalHypervisor_throwsInvalidParameter() {
        ResetVMUserDataCmd cmd = Mockito.mock(ResetVMUserDataCmd.class);
        when(cmd.getId()).thenReturn(VM_ID);
        when(_vmDao.findById(VM_ID)).thenReturn(userVmVoMock);
        Mockito.lenient().when(userVmVoMock.getUserVmType()).thenReturn("User");
        when(userVmVoMock.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.External);

        assertThrows(InvalidParameterValueException.class,
                () -> service.resetVMUserData(cmd));
    }

    @Test
    public void resetVMUserData_vmNotStopped_throwsInvalidParameter() {
        ResetVMUserDataCmd cmd = Mockito.mock(ResetVMUserDataCmd.class);
        when(cmd.getId()).thenReturn(VM_ID);
        when(_vmDao.findById(VM_ID)).thenReturn(userVmVoMock);
        Mockito.lenient().when(userVmVoMock.getUserVmType()).thenReturn("User");
        when(userVmVoMock.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(userVmVoMock.getState()).thenReturn(State.Running);
        when(userVmVoMock.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(vmTemplateVoMock);

        assertThrows(InvalidParameterValueException.class,
                () -> service.resetVMUserData(cmd));
    }

    @Test
    public void resetVMUserData_happyPath_persistsUserDataAndCallsUpdateUserData()
            throws ResourceUnavailableException, InsufficientCapacityException {
        ResetVMUserDataCmd cmd = Mockito.mock(ResetVMUserDataCmd.class);
        when(cmd.getId()).thenReturn(VM_ID);

        UserVmVO vm = Mockito.mock(UserVmVO.class);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(_vmDao.findById(VM_ID)).thenReturn(vm);
        Mockito.lenient().when(vm.getUserVmType()).thenReturn("User");
        when(vm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(vm.getState()).thenReturn(State.Stopped);
        when(_templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(vmTemplateVoMock);
        when(vmCredentialResetService.finalizeUserData(any(), any(), any())).thenReturn("mydata");
        when(userDataManager.validateUserData(anyString(), any())).thenReturn("mydata");
        when(cmd.getUserData()).thenReturn("mydata");
        when(cmd.getHttpMethod()).thenReturn(HTTPMethod.GET);

        service.resetVMUserData(cmd);

        verify(_vmDao).update(eq(VM_ID), eq(vm));
        verify(vmCredentialResetService).updateUserData(vm);
    }

    // ---- resetVMSSHKey tests ----

    @Test
    public void resetVMSSHKey_vmNotFound_throwsInvalidParameter() {
        ResetVMSSHKeyCmd cmd = Mockito.mock(ResetVMSSHKeyCmd.class);
        when(cmd.getId()).thenReturn(VM_ID);
        when(_vmDao.findById(VM_ID)).thenReturn(null);
        when(_accountMgr.finalizeOwner(any(), any(), any(), any())).thenReturn(ownerAccount);

        assertThrows(InvalidParameterValueException.class,
                () -> service.resetVMSSHKey(cmd));
    }

    @Test
    public void resetVMSSHKey_emptyKeypairNames_throwsInvalidParameter() {
        ResetVMSSHKeyCmd cmd = Mockito.mock(ResetVMSSHKeyCmd.class);
        when(cmd.getId()).thenReturn(VM_ID);
        when(_accountMgr.finalizeOwner(any(), any(), any(), any())).thenReturn(ownerAccount);
        when(_vmDao.findById(VM_ID)).thenReturn(userVmVoMock);
        Mockito.lenient().when(userVmVoMock.getUserVmType()).thenReturn("User");
        when(userVmVoMock.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(userVmVoMock.getState()).thenReturn(State.Stopped);
        when(cmd.getNames()).thenReturn(Collections.emptyList());

        assertThrows(InvalidParameterValueException.class,
                () -> service.resetVMSSHKey(cmd));
    }

    @Test
    public void resetVMSSHKey_externalHypervisor_throwsInvalidParameter() {
        ResetVMSSHKeyCmd cmd = Mockito.mock(ResetVMSSHKeyCmd.class);
        when(cmd.getId()).thenReturn(VM_ID);
        when(_accountMgr.finalizeOwner(any(), any(), any(), any())).thenReturn(ownerAccount);
        when(_vmDao.findById(VM_ID)).thenReturn(userVmVoMock);
        Mockito.lenient().when(userVmVoMock.getUserVmType()).thenReturn("User");
        when(userVmVoMock.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.External);

        assertThrows(InvalidParameterValueException.class,
                () -> service.resetVMSSHKey(cmd));
    }

    // ---- resetVMSSHKeyInternal tests ----

    @Test
    public void resetVMSSHKeyInternal_pairCountMismatch_throwsInvalidParameter() {
        List<String> names = Arrays.asList("key1", "key2");
        when(ownerAccount.getAccountId()).thenReturn(ACCOUNT_ID);
        when(ownerAccount.getDomainId()).thenReturn(DOMAIN_ID);
        when(_sshKeyPairDao.findByNames(ACCOUNT_ID, DOMAIN_ID, names))
                .thenReturn(Collections.singletonList(Mockito.mock(SSHKeyPairVO.class)));

        assertThrows(InvalidParameterValueException.class,
                () -> service.resetVMSSHKeyInternal(userVmVoMock, ownerAccount, names));
    }

    @Test
    public void resetVMSSHKeyInternal_savesKeyDetailsAndCallsRemoveEncryptedPassword()
            throws ResourceUnavailableException, InsufficientCapacityException {
        List<String> names = Arrays.asList("key1");

        UserVmVO vm = Mockito.mock(UserVmVO.class);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(ownerAccount.getAccountId()).thenReturn(ACCOUNT_ID);
        when(ownerAccount.getDomainId()).thenReturn(DOMAIN_ID);

        SSHKeyPairVO pair = Mockito.mock(SSHKeyPairVO.class);
        when(pair.getPublicKey()).thenReturn("ssh-rsa AAAA...");
        when(_sshKeyPairDao.findByNames(ACCOUNT_ID, DOMAIN_ID, names))
                .thenReturn(Collections.singletonList(pair));

        // For internal overload: template + nic + network + element
        when(_vmDao.findById(VM_ID)).thenReturn(vm);
        when(_templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(vmTemplateVoMock);
        Nic nic = Mockito.mock(Nic.class);
        when(nic.getNetworkId()).thenReturn(NETWORK_ID);
        when(_networkModel.getDefaultNic(VM_ID)).thenReturn(nic);
        NetworkVO network = Mockito.mock(NetworkVO.class);
        when(_networkDao.findById(NETWORK_ID)).thenReturn(network);
        UserDataServiceProvider element = Mockito.mock(UserDataServiceProvider.class);
        when(element.saveSSHKey(any(), any(), any(), any())).thenReturn(true);
        when(_networkMgr.getSSHKeyResetProvider(network)).thenReturn(element);
        when(vm.getState()).thenReturn(State.Stopped);

        service.resetVMSSHKeyInternal(vm, ownerAccount, names);

        verify(_vmDao).saveDetails(any(UserVmVO.class));
        verify(vmCredentialResetService).removeEncryptedPasswordFromUserVmVoDetails(VM_ID);
    }

    // ---- getCurrentVmPasswordOrDefineNewPassword tests ----

    @Test
    public void getCurrentVmPasswordOrDefineNewPassword_templateNotEnabled_returnsSavedPassword() {
        when(vmTemplateVoMock.isEnablePassword()).thenReturn(false);

        String result = service.getCurrentVmPasswordOrDefineNewPassword("", userVmVoMock, vmTemplateVoMock);

        assertEquals("saved_password", result);
    }

    @Test
    public void getCurrentVmPasswordOrDefineNewPassword_vmHasPassword_decryptsAndReturns() {
        String encryptedPassword = "encryptedPwd";
        String decryptedPassword = "current_password";
        when(vmTemplateVoMock.isEnablePassword()).thenReturn(true);
        when(userVmVoMock.getDetail("password")).thenReturn(encryptedPassword);

        try (MockedStatic<DBEncryptionUtil> dbEncUtil = Mockito.mockStatic(DBEncryptionUtil.class)) {
            dbEncUtil.when(() -> DBEncryptionUtil.decrypt(encryptedPassword)).thenReturn(decryptedPassword);

            String result = service.getCurrentVmPasswordOrDefineNewPassword("", userVmVoMock, vmTemplateVoMock);

            assertEquals(decryptedPassword, result);
        }
    }

    @Test
    public void getCurrentVmPasswordOrDefineNewPassword_userDefinedPassword_setsAndReturns() {
        String newPassword = "new_password";
        when(vmTemplateVoMock.isEnablePassword()).thenReturn(true);
        when(userVmVoMock.getDetail("password")).thenReturn(null);
        Mockito.doCallRealMethod().when(userVmVoMock).setPassword(any());
        Mockito.doCallRealMethod().when(userVmVoMock).getPassword();

        String result = service.getCurrentVmPasswordOrDefineNewPassword(newPassword, userVmVoMock, vmTemplateVoMock);

        assertEquals(newPassword, result);
        assertEquals(newPassword, userVmVoMock.getPassword());
    }

    @Test
    public void getCurrentVmPasswordOrDefineNewPassword_noPasswordNoDetail_generatesRandom() {
        String randomPassword = "random_password";
        when(vmTemplateVoMock.isEnablePassword()).thenReturn(true);
        when(userVmVoMock.getDetail("password")).thenReturn(null);
        when(_mgr.generateRandomPassword()).thenReturn(randomPassword);
        Mockito.doCallRealMethod().when(userVmVoMock).setPassword(any());
        Mockito.doCallRealMethod().when(userVmVoMock).getPassword();

        String result = service.getCurrentVmPasswordOrDefineNewPassword("", userVmVoMock, vmTemplateVoMock);

        assertEquals(randomPassword, result);
        verify(_mgr).generateRandomPassword();
        assertEquals(randomPassword, userVmVoMock.getPassword());
    }
}
