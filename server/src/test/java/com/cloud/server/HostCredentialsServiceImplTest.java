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
package com.cloud.server;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.host.UpdateHostPasswordCmd;
import org.apache.cloudstack.api.command.user.vm.GetVMPasswordCmd;
import org.apache.cloudstack.context.CallContext;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.DetailVO;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.dao.UserVmDao;

@RunWith(MockitoJUnitRunner.class)
public class HostCredentialsServiceImplTest {

    @Mock
    private AccountManager accountManager;
    @Mock
    private UserVmDao userVmDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private HostDetailsDao hostDetailsDao;
    @Mock
    private ClusterDao clusterDao;

    @InjectMocks
    private HostCredentialsServiceImpl service = new HostCredentialsServiceImpl();

    @Mock
    private AccountVO callerAccount;
    @Mock
    private User callerUser;

    private AutoCloseable closeable;
    private MockedStatic<Transaction> transactionMocked;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        lenient().when(callerAccount.getId()).thenReturn(1L);
        lenient().when(callerAccount.getType()).thenReturn(Account.Type.ADMIN);
        CallContext.register(callerUser, callerAccount);

        // Run the transactional body inline so the persistence logic in the
        // service is exercised directly. Without this, none of the
        // host_details writes would happen during the test.
        transactionMocked = Mockito.mockStatic(Transaction.class);
        transactionMocked.when(() -> Transaction.execute(any(TransactionCallbackNoReturn.class)))
                .thenAnswer(inv -> {
                    TransactionCallbackNoReturn cb = inv.getArgument(0);
                    cb.doInTransaction(null);
                    return null;
                });
    }

    @After
    public void tearDown() throws Exception {
        CallContext.unregister();
        transactionMocked.close();
        closeable.close();
    }

    // --- getVMPassword -----------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void getVMPasswordRejectsMissingVm() {
        GetVMPasswordCmd cmd = mock(GetVMPasswordCmd.class);
        when(cmd.getId()).thenReturn(42L);
        when(userVmDao.findById(42L)).thenReturn(null);

        service.getVMPassword(cmd);
    }

    @Test
    public void getVMPasswordReturnsStoredEncryptedPassword() {
        GetVMPasswordCmd cmd = mock(GetVMPasswordCmd.class);
        when(cmd.getId()).thenReturn(7L);
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getDetail("Encrypted.Password")).thenReturn("ciphertext");
        when(userVmDao.findById(7L)).thenReturn(vm);

        String result = service.getVMPassword(cmd);

        Assert.assertEquals("ciphertext", result);
        verify(accountManager).checkAccess(eq(callerAccount), eq(null), eq(true), eq(vm));
        verify(userVmDao).loadDetails(vm);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void getVMPasswordRejectsBlankStoredPassword() {
        GetVMPasswordCmd cmd = mock(GetVMPasswordCmd.class);
        when(cmd.getId()).thenReturn(8L);
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getDetail("Encrypted.Password")).thenReturn("");
        when(userVmDao.findById(8L)).thenReturn(vm);

        service.getVMPassword(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void getVMPasswordRejectsNullStoredPassword() {
        GetVMPasswordCmd cmd = mock(GetVMPasswordCmd.class);
        when(cmd.getId()).thenReturn(9L);
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getDetail("Encrypted.Password")).thenReturn(null);
        when(userVmDao.findById(9L)).thenReturn(vm);

        service.getVMPassword(cmd);
    }

    // --- updateClusterPassword --------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void updateClusterPasswordRejectsNullClusterId() {
        UpdateHostPasswordCmd cmd = mock(UpdateHostPasswordCmd.class);
        when(cmd.getClusterId()).thenReturn(null);

        service.updateClusterPassword(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateClusterPasswordRejectsMissingCluster() {
        UpdateHostPasswordCmd cmd = mock(UpdateHostPasswordCmd.class);
        when(cmd.getClusterId()).thenReturn(3L);
        when(clusterDao.findById(3L)).thenReturn(null);

        service.updateClusterPassword(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateClusterPasswordRejectsUnsupportedHypervisor() {
        UpdateHostPasswordCmd cmd = mock(UpdateHostPasswordCmd.class);
        when(cmd.getClusterId()).thenReturn(4L);
        ClusterVO cluster = mock(ClusterVO.class);
        when(cluster.getHypervisorType()).thenReturn(HypervisorType.VMware);
        when(clusterDao.findById(4L)).thenReturn(cluster);

        service.updateClusterPassword(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateClusterPasswordRejectsBlankUsername() {
        UpdateHostPasswordCmd cmd = mock(UpdateHostPasswordCmd.class);
        when(cmd.getClusterId()).thenReturn(5L);
        when(cmd.getUsername()).thenReturn("   ");
        ClusterVO cluster = mock(ClusterVO.class);
        when(cluster.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(clusterDao.findById(5L)).thenReturn(cluster);
        when(hostDao.listIdsByClusterId(5L)).thenReturn(Collections.singletonList(100L));

        service.updateClusterPassword(cmd);
    }

    @Test
    public void updateClusterPasswordInsertsBothDetailsWhenUsernameMissing() {
        UpdateHostPasswordCmd cmd = mock(UpdateHostPasswordCmd.class);
        when(cmd.getClusterId()).thenReturn(6L);
        when(cmd.getUsername()).thenReturn("root");
        when(cmd.getPassword()).thenReturn("secret");
        ClusterVO cluster = mock(ClusterVO.class);
        when(cluster.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(clusterDao.findById(6L)).thenReturn(cluster);
        when(hostDao.listIdsByClusterId(6L)).thenReturn(Arrays.asList(101L, 102L));
        when(hostDetailsDao.findDetail(anyLong(), eq(ApiConstants.USERNAME))).thenReturn(null);

        boolean ok = service.updateClusterPassword(cmd);

        Assert.assertTrue(ok);
        // Two hosts * two persist calls (USERNAME + PASSWORD detail) = 4
        verify(hostDetailsDao, Mockito.times(4)).persist(any(DetailVO.class));
    }

    @Test
    public void updateClusterPasswordUpdatesPasswordWhenUsernameMatches() {
        UpdateHostPasswordCmd cmd = mock(UpdateHostPasswordCmd.class);
        when(cmd.getClusterId()).thenReturn(7L);
        when(cmd.getUsername()).thenReturn("root");
        when(cmd.getPassword()).thenReturn("newpw");
        ClusterVO cluster = mock(ClusterVO.class);
        when(cluster.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        when(clusterDao.findById(7L)).thenReturn(cluster);
        when(hostDao.listIdsByClusterId(7L)).thenReturn(Collections.singletonList(200L));
        DetailVO existing = mock(DetailVO.class);
        when(existing.getValue()).thenReturn("root");
        when(hostDetailsDao.findDetail(200L, ApiConstants.USERNAME)).thenReturn(existing);
        DetailVO existingPw = mock(DetailVO.class);
        when(hostDetailsDao.findDetail(200L, ApiConstants.PASSWORD)).thenReturn(existingPw);

        boolean ok = service.updateClusterPassword(cmd);

        Assert.assertTrue(ok);
        verify(existingPw).setValue(any(String.class));
        verify(hostDetailsDao).persist(existingPw);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateClusterPasswordRejectsMismatchedUsername() {
        UpdateHostPasswordCmd cmd = mock(UpdateHostPasswordCmd.class);
        when(cmd.getClusterId()).thenReturn(8L);
        when(cmd.getUsername()).thenReturn("admin");
        // cmd.getPassword() is never reached because the username mismatch
        // branch throws before the encrypted password is needed.
        ClusterVO cluster = mock(ClusterVO.class);
        when(cluster.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(clusterDao.findById(8L)).thenReturn(cluster);
        when(hostDao.listIdsByClusterId(8L)).thenReturn(Collections.singletonList(300L));
        DetailVO existing = mock(DetailVO.class);
        when(existing.getValue()).thenReturn("root");
        when(hostDetailsDao.findDetail(300L, ApiConstants.USERNAME)).thenReturn(existing);

        service.updateClusterPassword(cmd);
    }

    // --- updateHostPassword -----------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void updateHostPasswordRejectsNullHostId() {
        UpdateHostPasswordCmd cmd = mock(UpdateHostPasswordCmd.class);
        when(cmd.getHostId()).thenReturn(null);

        service.updateHostPassword(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateHostPasswordRejectsXenServer() {
        UpdateHostPasswordCmd cmd = mock(UpdateHostPasswordCmd.class);
        when(cmd.getHostId()).thenReturn(10L);
        HostVO host = mock(HostVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        when(hostDao.findById(10L)).thenReturn(host);

        service.updateHostPassword(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateHostPasswordRejectsUnsupportedHypervisor() {
        UpdateHostPasswordCmd cmd = mock(UpdateHostPasswordCmd.class);
        when(cmd.getHostId()).thenReturn(11L);
        HostVO host = mock(HostVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.VMware);
        when(hostDao.findById(11L)).thenReturn(host);

        service.updateHostPassword(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateHostPasswordRejectsBlankUsername() {
        UpdateHostPasswordCmd cmd = mock(UpdateHostPasswordCmd.class);
        when(cmd.getHostId()).thenReturn(12L);
        when(cmd.getUsername()).thenReturn("");
        HostVO host = mock(HostVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(hostDao.findById(12L)).thenReturn(host);

        service.updateHostPassword(cmd);
    }

    @Test
    public void updateHostPasswordInsertsBothDetailsWhenUsernameMissing() {
        UpdateHostPasswordCmd cmd = mock(UpdateHostPasswordCmd.class);
        when(cmd.getHostId()).thenReturn(13L);
        when(cmd.getUsername()).thenReturn("root");
        when(cmd.getPassword()).thenReturn("topsecret");
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(13L);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(hostDao.findById(13L)).thenReturn(host);
        when(hostDetailsDao.findDetail(13L, ApiConstants.USERNAME)).thenReturn(null);

        boolean ok = service.updateHostPassword(cmd);

        Assert.assertTrue(ok);
        verify(hostDetailsDao, Mockito.times(2)).persist(any(DetailVO.class));
    }

    @Test
    public void updateHostPasswordUpdatesPasswordWhenUsernameMatches() {
        UpdateHostPasswordCmd cmd = mock(UpdateHostPasswordCmd.class);
        when(cmd.getHostId()).thenReturn(14L);
        when(cmd.getUsername()).thenReturn("admin");
        when(cmd.getPassword()).thenReturn("newpw");
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(14L);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(hostDao.findById(14L)).thenReturn(host);
        DetailVO existing = mock(DetailVO.class);
        when(existing.getValue()).thenReturn("admin");
        when(hostDetailsDao.findDetail(14L, ApiConstants.USERNAME)).thenReturn(existing);
        DetailVO existingPw = mock(DetailVO.class);
        when(hostDetailsDao.findDetail(14L, ApiConstants.PASSWORD)).thenReturn(existingPw);

        boolean ok = service.updateHostPassword(cmd);

        Assert.assertTrue(ok);
        verify(existingPw).setValue(any(String.class));
        verify(hostDetailsDao).persist(existingPw);
        // The two-insert branch (one extra persist) must not run — total
        // persist calls should be exactly one (the existing password row).
        verify(hostDetailsDao, Mockito.times(1)).persist(any(DetailVO.class));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void updateHostPasswordRejectsMismatchedUsername() {
        UpdateHostPasswordCmd cmd = mock(UpdateHostPasswordCmd.class);
        when(cmd.getHostId()).thenReturn(15L);
        when(cmd.getUsername()).thenReturn("admin");
        // cmd.getPassword() is never reached because the username mismatch
        // branch throws before the encrypted password is needed.
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(15L);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(hostDao.findById(15L)).thenReturn(host);
        DetailVO existing = mock(DetailVO.class);
        when(existing.getValue()).thenReturn("root");
        when(hostDetailsDao.findDetail(15L, ApiConstants.USERNAME)).thenReturn(existing);

        service.updateHostPassword(cmd);
    }

    @Test
    public void updateHostPasswordTrimsUsernameWhitespace() {
        UpdateHostPasswordCmd cmd = mock(UpdateHostPasswordCmd.class);
        when(cmd.getHostId()).thenReturn(16L);
        when(cmd.getUsername()).thenReturn("  root  ");
        when(cmd.getPassword()).thenReturn("pw");
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(16L);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(hostDao.findById(16L)).thenReturn(host);
        DetailVO existing = mock(DetailVO.class);
        // The stored value should match the trimmed username, not the raw value.
        when(existing.getValue()).thenReturn("root");
        when(hostDetailsDao.findDetail(16L, ApiConstants.USERNAME)).thenReturn(existing);
        DetailVO existingPw = mock(DetailVO.class);
        when(hostDetailsDao.findDetail(16L, ApiConstants.PASSWORD)).thenReturn(existingPw);

        boolean ok = service.updateHostPassword(cmd);

        Assert.assertTrue(ok);
        verify(hostDetailsDao).persist(existingPw);
    }

    // --- wiring smoke test ------------------------------------------------

    @Test
    public void wiringInjectsAllCollaborators() {
        HostCredentialsServiceImpl s = new HostCredentialsServiceImpl();
        ReflectionTestUtils.setField(s, "accountManager", accountManager);
        ReflectionTestUtils.setField(s, "userVmDao", userVmDao);
        ReflectionTestUtils.setField(s, "hostDao", hostDao);
        ReflectionTestUtils.setField(s, "hostDetailsDao", hostDetailsDao);
        ReflectionTestUtils.setField(s, "clusterDao", clusterDao);

        Assert.assertSame(accountManager, ReflectionTestUtils.getField(s, "accountManager"));
        Assert.assertSame(userVmDao, ReflectionTestUtils.getField(s, "userVmDao"));
        Assert.assertSame(hostDao, ReflectionTestUtils.getField(s, "hostDao"));
        Assert.assertSame(hostDetailsDao, ReflectionTestUtils.getField(s, "hostDetailsDao"));
        Assert.assertSame(clusterDao, ReflectionTestUtils.getField(s, "clusterDao"));
    }
}
