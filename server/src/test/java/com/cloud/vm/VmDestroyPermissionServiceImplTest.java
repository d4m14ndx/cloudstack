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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.PermissionDeniedException;
import com.cloud.kubernetes.cluster.KubernetesServiceHelper;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.uservm.UserVm;
import com.cloud.utils.component.ComponentContext;

/**
 * Unit tests for {@link VmDestroyPermissionServiceImpl} — the destroy/expunge/
 * force-stop permission checks extracted from {@code UserVmManagerImpl} as
 * slice 11 of the Phase 4 Spring-component decomposition.
 */
@RunWith(MockitoJUnitRunner.class)
public class VmDestroyPermissionServiceImplTest {

    @Mock private AccountManager accountManager;
    @Mock private Account callingAccount;
    @Mock private UserVm userVm;

    private VmDestroyPermissionServiceImpl service;

    @Before
    public void setUp() {
        // Spy so we can stub the protected isUserExpungeRecoverVmAllowed /
        // checkForceStopVmConfig wrappers and avoid touching the static
        // ConfigKey machinery from these unit tests.
        service = Mockito.spy(new VmDestroyPermissionServiceImpl());
        ReflectionTestUtils.setField(service, "accountManager", accountManager);
        when(callingAccount.getId()).thenReturn(42L);
    }

    // ---- checkExpungeVmPermission ----

    @Test
    public void checkExpungeVmPermissionRejectsNonAdminWhenConfigFalse() {
        when(accountManager.isAdmin(anyLong())).thenReturn(false);
        doReturn(false).when(service).isUserExpungeRecoverVmAllowed(anyLong());

        assertThrows(PermissionDeniedException.class,
                () -> service.checkExpungeVmPermission(callingAccount, null));
        verify(accountManager, never()).checkApiAccess(any(Account.class), anyString(), any());
    }

    @Test
    public void checkExpungeVmPermissionRejectsNonAdminConfigTrueWhenApiAccessDenied() {
        when(accountManager.isAdmin(anyLong())).thenReturn(false);
        doReturn(true).when(service).isUserExpungeRecoverVmAllowed(anyLong());
        doThrow(PermissionDeniedException.class).when(accountManager)
                .checkApiAccess(callingAccount, "expungeVirtualMachine", null);

        assertThrows(PermissionDeniedException.class,
                () -> service.checkExpungeVmPermission(callingAccount, null));
    }

    @Test
    public void checkExpungeVmPermissionAcceptsNonAdminConfigTrueWithApiAccess() {
        when(accountManager.isAdmin(anyLong())).thenReturn(false);
        doReturn(true).when(service).isUserExpungeRecoverVmAllowed(anyLong());

        service.checkExpungeVmPermission(callingAccount, null);
        verify(accountManager).checkApiAccess(callingAccount, "expungeVirtualMachine", null);
    }

    @Test
    public void checkExpungeVmPermissionAcceptsAdminWithoutConsultingConfig() {
        when(accountManager.isAdmin(anyLong())).thenReturn(true);

        service.checkExpungeVmPermission(callingAccount, null);
        // Admins skip the config check entirely.
        verify(service, never()).isUserExpungeRecoverVmAllowed(anyLong());
    }

    @Test
    public void checkExpungeVmPermissionRejectsAdminWhenApiAccessDenied() {
        when(accountManager.isAdmin(anyLong())).thenReturn(true);
        doThrow(PermissionDeniedException.class).when(accountManager)
                .checkApiAccess(callingAccount, "expungeVirtualMachine", null);

        assertThrows(PermissionDeniedException.class,
                () -> service.checkExpungeVmPermission(callingAccount, null));
    }

    @Test
    public void checkExpungeVmPermissionPassesApiKeyThroughToAccessCheck() {
        when(accountManager.isAdmin(anyLong())).thenReturn(true);

        service.checkExpungeVmPermission(callingAccount, "my-api-key");
        verify(accountManager).checkApiAccess(callingAccount, "expungeVirtualMachine", "my-api-key");
    }

    // ---- checkPluginsIfVmCanBeDestroyed ----

    @Test
    public void checkPluginsIfVmCanBeDestroyedSwallowsMissingHelper() {
        try (MockedStatic<ComponentContext> mocked = Mockito.mockStatic(ComponentContext.class)) {
            mocked.when(() -> ComponentContext.getDelegateComponentOfType(KubernetesServiceHelper.class))
                    .thenThrow(new NoSuchBeanDefinitionException("KubernetesServiceHelper"));

            // Should not throw, just log and continue.
            service.checkPluginsIfVmCanBeDestroyed(userVm);
        }
    }

    @Test
    public void checkPluginsIfVmCanBeDestroyedDelegatesToHelperWhenPresent() {
        KubernetesServiceHelper helper = mock(KubernetesServiceHelper.class);
        try (MockedStatic<ComponentContext> mocked = Mockito.mockStatic(ComponentContext.class)) {
            mocked.when(() -> ComponentContext.getDelegateComponentOfType(KubernetesServiceHelper.class))
                    .thenReturn(helper);
            doNothing().when(helper).checkVmCanBeDestroyed(userVm);

            service.checkPluginsIfVmCanBeDestroyed(userVm);
            verify(helper, times(1)).checkVmCanBeDestroyed(userVm);
        }
    }

    @Test
    public void checkPluginsIfVmCanBeDestroyedPropagatesHelperVeto() {
        KubernetesServiceHelper helper = mock(KubernetesServiceHelper.class);
        try (MockedStatic<ComponentContext> mocked = Mockito.mockStatic(ComponentContext.class)) {
            mocked.when(() -> ComponentContext.getDelegateComponentOfType(KubernetesServiceHelper.class))
                    .thenReturn(helper);
            doThrow(new IllegalStateException("VM is in a cluster"))
                    .when(helper).checkVmCanBeDestroyed(userVm);

            assertThrows(IllegalStateException.class,
                    () -> service.checkPluginsIfVmCanBeDestroyed(userVm));
        }
    }

    // ---- checkForceStopVmPermission ----

    @Test
    public void checkForceStopVmPermissionRejectsWhenGlobalDisabled() {
        doReturn(false).when(service).isUserForceStopVmAllowed(anyLong());

        assertThrows(PermissionDeniedException.class,
                () -> service.checkForceStopVmPermission(callingAccount));
    }

    @Test
    public void checkForceStopVmPermissionAcceptsWhenGlobalEnabled() {
        doReturn(true).when(service).isUserForceStopVmAllowed(anyLong());

        service.checkForceStopVmPermission(callingAccount);
        verify(service).isUserForceStopVmAllowed(42L);
    }

    @Test
    public void checkForceStopVmPermissionUsesCallingAccountId() {
        when(callingAccount.getId()).thenReturn(99L);
        doReturn(true).when(service).isUserForceStopVmAllowed(anyLong());

        service.checkForceStopVmPermission(callingAccount);
        verify(service).isUserForceStopVmAllowed(99L);
    }

    // ---- isUserExpungeRecoverVmAllowed ----

    @Test
    public void isUserExpungeRecoverVmAllowedFlowsStubbedValueOut() {
        doReturn(true).when(service).isUserExpungeRecoverVmAllowed(7L);
        org.junit.Assert.assertTrue(service.isUserExpungeRecoverVmAllowed(7L));

        doReturn(false).when(service).isUserExpungeRecoverVmAllowed(8L);
        org.junit.Assert.assertFalse(service.isUserExpungeRecoverVmAllowed(8L));
    }
}
