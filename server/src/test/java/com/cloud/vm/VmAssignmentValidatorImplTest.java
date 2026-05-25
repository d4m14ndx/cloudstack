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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVMMapVO;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.vm.VirtualMachine.State;

@RunWith(MockitoJUnitRunner.class)
public class VmAssignmentValidatorImplTest {

    @Mock private PortForwardingRulesDao portForwardingDao;
    @Mock private FirewallRulesDao rulesDao;
    @Mock private LoadBalancerVMMapDao loadBalancerVMMapDao;
    @Mock private IPAddressDao ipAddressDao;
    @Mock private SnapshotDao snapshotDao;
    @Mock private AccountManager accountManager;

    @InjectMocks
    private VmAssignmentValidatorImpl validator;

    // ---- validateIfVmSupportsMigration ----

    @Test
    public void missingVmRejected() {
        assertThrows(InvalidParameterValueException.class,
                () -> validator.validateIfVmSupportsMigration(null, 1L));
    }

    @Test
    public void runningVmRejected() {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getState()).thenReturn(State.Running);
        assertThrows(InvalidParameterValueException.class,
                () -> validator.validateIfVmSupportsMigration(vm, 1L));
    }

    @Test
    public void sharedFileSystemVmRejected() {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getState()).thenReturn(State.Stopped);
        when(vm.getUserVmType()).thenReturn(UserVmManager.SHAREDFSVM);
        assertThrows(InvalidParameterValueException.class,
                () -> validator.validateIfVmSupportsMigration(vm, 1L));
    }

    @Test
    public void stoppedRegularVmAccepted() {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getState()).thenReturn(State.Stopped);
        validator.validateIfVmSupportsMigration(vm, 1L);
    }

    // ---- validateIfVmHasNoRules ----

    @Test
    public void portForwardingRulePresentRejects() {
        UserVmVO vm = mock(UserVmVO.class);
        when(portForwardingDao.listByVm(1L)).thenReturn(List.of(mock(PortForwardingRuleVO.class)));
        assertThrows(InvalidParameterValueException.class,
                () -> validator.validateIfVmHasNoRules(vm, 1L));
    }

    @Test
    public void staticNatRulePresentRejects() {
        UserVmVO vm = mock(UserVmVO.class);
        when(portForwardingDao.listByVm(1L)).thenReturn(Collections.emptyList());
        when(rulesDao.listStaticNatByVmId(1L)).thenReturn(List.of(mock(FirewallRuleVO.class)));
        assertThrows(InvalidParameterValueException.class,
                () -> validator.validateIfVmHasNoRules(vm, 1L));
    }

    @Test
    public void loadBalancerMapPresentRejects() {
        UserVmVO vm = mock(UserVmVO.class);
        when(portForwardingDao.listByVm(1L)).thenReturn(Collections.emptyList());
        when(rulesDao.listStaticNatByVmId(1L)).thenReturn(Collections.emptyList());
        when(loadBalancerVMMapDao.listByInstanceId(1L)).thenReturn(List.of(mock(LoadBalancerVMMapVO.class)));
        assertThrows(InvalidParameterValueException.class,
                () -> validator.validateIfVmHasNoRules(vm, 1L));
    }

    @Test
    public void oneToOneNatRejects() {
        UserVmVO vm = mock(UserVmVO.class);
        when(portForwardingDao.listByVm(1L)).thenReturn(Collections.emptyList());
        when(rulesDao.listStaticNatByVmId(1L)).thenReturn(Collections.emptyList());
        when(loadBalancerVMMapDao.listByInstanceId(1L)).thenReturn(Collections.emptyList());
        IPAddressVO ip = mock(IPAddressVO.class);
        when(ip.isOneToOneNat()).thenReturn(true);
        when(ipAddressDao.findAllByAssociatedVmId(1L)).thenReturn(List.of(ip));
        assertThrows(InvalidParameterValueException.class,
                () -> validator.validateIfVmHasNoRules(vm, 1L));
    }

    @Test
    public void noRulesPasses() {
        UserVmVO vm = mock(UserVmVO.class);
        when(portForwardingDao.listByVm(1L)).thenReturn(Collections.emptyList());
        when(rulesDao.listStaticNatByVmId(1L)).thenReturn(Collections.emptyList());
        when(loadBalancerVMMapDao.listByInstanceId(1L)).thenReturn(Collections.emptyList());
        when(ipAddressDao.findAllByAssociatedVmId(1L)).thenReturn(Collections.emptyList());

        validator.validateIfVmHasNoRules(vm, 1L);
    }

    // ---- validateIfVolumesHaveNoSnapshots ----

    @Test
    public void liveSnapshotsBlockAssignment() {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getId()).thenReturn(11L);
        when(snapshotDao.listByStatusNotIn(anyLong(), any(), any()))
                .thenReturn(List.of(mock(SnapshotVO.class)));
        assertThrows(InvalidParameterValueException.class,
                () -> validator.validateIfVolumesHaveNoSnapshots(List.of(volume)));
    }

    @Test
    public void noVolumesPasses() {
        validator.validateIfVolumesHaveNoSnapshots(Collections.emptyList());
    }

    @Test
    public void noLiveSnapshotsPasses() {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getId()).thenReturn(11L);
        when(snapshotDao.listByStatusNotIn(anyLong(), any(), any())).thenReturn(Collections.emptyList());
        validator.validateIfVolumesHaveNoSnapshots(List.of(volume));
    }

    // ---- validateIfNewOwnerHasAccessToTemplate ----

    @Test
    public void missingTemplateRejected() {
        UserVmVO vm = mock(UserVmVO.class);
        lenient().when(vm.getUuid()).thenReturn(UUID.randomUUID().toString());
        Account newOwner = mock(Account.class);
        assertThrows(InvalidParameterValueException.class,
                () -> validator.validateIfNewOwnerHasAccessToTemplate(vm, newOwner, null));
    }

    @Test
    public void newOwnerWithoutTemplateAccessRejected() {
        UserVmVO vm = mock(UserVmVO.class);
        Account newOwner = mock(Account.class);
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        lenient().when(template.getUuid()).thenReturn(UUID.randomUUID().toString());
        doThrow(new PermissionDeniedException("denied"))
                .when(accountManager).checkAccess(newOwner, AccessType.UseEntry, true, template);

        assertThrows(PermissionDeniedException.class,
                () -> validator.validateIfNewOwnerHasAccessToTemplate(vm, newOwner, template));
    }

    @Test
    public void newOwnerWithTemplateAccessPasses() {
        UserVmVO vm = mock(UserVmVO.class);
        Account newOwner = mock(Account.class);
        VirtualMachineTemplate template = mock(VirtualMachineTemplate.class);
        lenient().when(template.getUuid()).thenReturn(UUID.randomUUID().toString());

        validator.validateIfNewOwnerHasAccessToTemplate(vm, newOwner, template);

        verify(accountManager).checkAccess(newOwner, AccessType.UseEntry, true, template);
    }

    // ---- validateOldAndNewAccounts ----

    @Test
    public void oldAccountMissingRejected() {
        Account newAccount = mock(Account.class);
        assertThrows(InvalidParameterValueException.class,
                () -> validator.validateOldAndNewAccounts(null, newAccount, 1L, "new", 2L));
    }

    @Test
    public void newAccountMissingRejected() {
        Account oldAccount = mock(Account.class);
        assertThrows(InvalidParameterValueException.class,
                () -> validator.validateOldAndNewAccounts(oldAccount, null, 1L, "new", 2L));
    }

    @Test
    public void disabledNewAccountRejected() {
        Account oldAccount = mock(Account.class);
        Account newAccount = mock(Account.class);
        when(newAccount.getState()).thenReturn(Account.State.DISABLED);
        assertThrows(InvalidParameterValueException.class,
                () -> validator.validateOldAndNewAccounts(oldAccount, newAccount, 1L, "new", 2L));
    }

    @Test
    public void sameAccountIsNoOpRejected() {
        Account oldAccount = mock(Account.class);
        Account newAccount = mock(Account.class);
        when(oldAccount.getAccountId()).thenReturn(7L);
        when(newAccount.getAccountId()).thenReturn(7L);
        when(newAccount.getState()).thenReturn(Account.State.ENABLED);
        assertThrows(InvalidParameterValueException.class,
                () -> validator.validateOldAndNewAccounts(oldAccount, newAccount, 7L, "new", 2L));
    }

    @Test
    public void distinctEnabledAccountsPasses() {
        Account oldAccount = mock(Account.class);
        Account newAccount = mock(Account.class);
        when(oldAccount.getAccountId()).thenReturn(7L);
        when(newAccount.getAccountId()).thenReturn(8L);
        when(newAccount.getState()).thenReturn(Account.State.ENABLED);
        validator.validateOldAndNewAccounts(oldAccount, newAccount, 7L, "new", 2L);
    }

    // ---- checkCallerAccessToAccounts ----

    @Test
    public void callerAccessIsCheckedAgainstBothOldAndNew() {
        Account caller = mock(Account.class);
        Account oldAccount = mock(Account.class);
        Account newAccount = mock(Account.class);

        validator.checkCallerAccessToAccounts(caller, oldAccount, newAccount);

        verify(accountManager).checkAccess(caller, null, true, oldAccount);
        verify(accountManager).checkAccess(caller, null, true, newAccount);
    }

}
