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
package com.cloud.network.router;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.command.admin.router.UpgradeRouterCmd;
import org.apache.cloudstack.api.command.admin.router.UpgradeRouterTemplateCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.api.ApiAsyncJobDispatcher;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.DomainRouterDao;

@RunWith(MockitoJUnitRunner.class)
public class RouterUpgradeServiceImplTest {

    private static final long ROUTER_ID = 100L;
    private static final long NEW_OFFERING_ID = 200L;
    private static final long DISK_OFFERING_ID = 300L;
    private static final long ACCOUNT_ID = 7L;
    private static final long CALLER_ID = 42L;
    private static final long DOMAIN_ID = 13L;
    private static final long ZONE_ID = 1L;
    private static final long POD_ID = 2L;
    private static final long CLUSTER_ID = 3L;

    @Mock
    private DomainRouterDao routerDao;
    @Mock
    private AccountManager accountManager;
    @Mock
    private EntityManager entityManager;
    @Mock
    private VirtualMachineManager itManager;
    @Mock
    private AsyncJobManager asyncManager;
    @Mock
    private ApiAsyncJobDispatcher asyncDispatcher;
    @Mock
    private NetworkHelper networkHelper;

    @Mock
    private AccountVO callerAccount;
    @Mock
    private User callerUser;

    private RouterUpgradeServiceImpl service;
    private AutoCloseable closeable;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        service = new RouterUpgradeServiceImpl();
        ReflectionTestUtils.setField(service, "routerDao", routerDao);
        ReflectionTestUtils.setField(service, "accountManager", accountManager);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "itManager", itManager);
        ReflectionTestUtils.setField(service, "asyncManager", asyncManager);
        ReflectionTestUtils.setField(service, "asyncDispatcher", asyncDispatcher);
        ReflectionTestUtils.setField(service, "networkHelper", networkHelper);

        // CallContext queries these on register/check-access. Use lenient
        // because not every test exercises the caller path.
        lenient().when(callerAccount.getId()).thenReturn(CALLER_ID);
        lenient().when(callerAccount.getDomainId()).thenReturn(DOMAIN_ID);
        CallContext.register(callerUser, callerAccount);
    }

    @After
    public void tearDown() throws Exception {
        CallContext.unregister();
        closeable.close();
    }

    // ----------------------- upgradeRouter -----------------------

    @Test(expected = InvalidParameterValueException.class)
    public void upgradeRouterThrowsWhenRouterMissing() {
        UpgradeRouterCmd cmd = Mockito.mock(UpgradeRouterCmd.class);
        when(cmd.getId()).thenReturn(ROUTER_ID);
        when(cmd.getServiceOfferingId()).thenReturn(NEW_OFFERING_ID);
        when(routerDao.findById(ROUTER_ID)).thenReturn(null);

        service.upgradeRouter(cmd);
    }

    @Test
    public void upgradeRouterShortCircuitsWhenOfferingAlreadyMatches() {
        UpgradeRouterCmd cmd = Mockito.mock(UpgradeRouterCmd.class);
        when(cmd.getId()).thenReturn(ROUTER_ID);
        when(cmd.getServiceOfferingId()).thenReturn(NEW_OFFERING_ID);

        DomainRouterVO router = Mockito.mock(DomainRouterVO.class);
        when(router.getServiceOfferingId()).thenReturn(NEW_OFFERING_ID);
        when(routerDao.findById(ROUTER_ID)).thenReturn(router);

        VirtualRouter result = service.upgradeRouter(cmd);

        assertSame(router, result);
        verify(routerDao, never()).update(anyLong(), any());
        verify(entityManager, never()).findById(eq(ServiceOffering.class), anyLong());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void upgradeRouterThrowsWhenNewServiceOfferingMissing() {
        UpgradeRouterCmd cmd = Mockito.mock(UpgradeRouterCmd.class);
        when(cmd.getId()).thenReturn(ROUTER_ID);
        when(cmd.getServiceOfferingId()).thenReturn(NEW_OFFERING_ID);

        DomainRouterVO router = Mockito.mock(DomainRouterVO.class);
        when(router.getServiceOfferingId()).thenReturn(999L);
        when(routerDao.findById(ROUTER_ID)).thenReturn(router);
        when(entityManager.findById(ServiceOffering.class, NEW_OFFERING_ID)).thenReturn(null);

        service.upgradeRouter(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void upgradeRouterThrowsWhenDiskOfferingMissing() {
        UpgradeRouterCmd cmd = Mockito.mock(UpgradeRouterCmd.class);
        when(cmd.getId()).thenReturn(ROUTER_ID);
        when(cmd.getServiceOfferingId()).thenReturn(NEW_OFFERING_ID);

        DomainRouterVO router = Mockito.mock(DomainRouterVO.class);
        when(router.getServiceOfferingId()).thenReturn(999L);
        when(routerDao.findById(ROUTER_ID)).thenReturn(router);

        ServiceOffering offering = Mockito.mock(ServiceOffering.class);
        when(offering.getDiskOfferingId()).thenReturn(DISK_OFFERING_ID);
        when(entityManager.findById(ServiceOffering.class, NEW_OFFERING_ID)).thenReturn(offering);
        when(entityManager.findById(DiskOffering.class, DISK_OFFERING_ID)).thenReturn(null);

        service.upgradeRouter(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void upgradeRouterRejectsNonSystemOffering() {
        UpgradeRouterCmd cmd = mockUpgradeCmd();
        DomainRouterVO router = mockRouterStopped();
        ServiceOffering offering = Mockito.mock(ServiceOffering.class);
        when(offering.getDiskOfferingId()).thenReturn(DISK_OFFERING_ID);
        when(offering.isSystemUse()).thenReturn(false);
        DiskOffering disk = Mockito.mock(DiskOffering.class);

        when(routerDao.findById(ROUTER_ID)).thenReturn(router);
        when(entityManager.findById(ServiceOffering.class, NEW_OFFERING_ID)).thenReturn(offering);
        when(entityManager.findById(DiskOffering.class, DISK_OFFERING_ID)).thenReturn(disk);

        service.upgradeRouter(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void upgradeRouterRejectsRunningRouter() {
        UpgradeRouterCmd cmd = mockUpgradeCmd();
        DomainRouterVO router = Mockito.mock(DomainRouterVO.class);
        when(router.getServiceOfferingId()).thenReturn(999L);
        when(router.getState()).thenReturn(VirtualMachine.State.Running);
        ServiceOffering offering = Mockito.mock(ServiceOffering.class);
        when(offering.getDiskOfferingId()).thenReturn(DISK_OFFERING_ID);
        when(offering.isSystemUse()).thenReturn(true);
        DiskOffering disk = Mockito.mock(DiskOffering.class);

        when(routerDao.findById(ROUTER_ID)).thenReturn(router);
        when(entityManager.findById(ServiceOffering.class, NEW_OFFERING_ID)).thenReturn(offering);
        when(entityManager.findById(DiskOffering.class, DISK_OFFERING_ID)).thenReturn(disk);

        service.upgradeRouter(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void upgradeRouterRejectsLocalStorageMismatch() {
        UpgradeRouterCmd cmd = mockUpgradeCmd();
        DomainRouterVO router = mockRouterStopped();
        ServiceOffering offering = Mockito.mock(ServiceOffering.class);
        when(offering.getDiskOfferingId()).thenReturn(DISK_OFFERING_ID);
        when(offering.isSystemUse()).thenReturn(true);
        DiskOffering disk = Mockito.mock(DiskOffering.class);
        when(disk.isUseLocalStorage()).thenReturn(true);

        when(routerDao.findById(ROUTER_ID)).thenReturn(router);
        when(entityManager.findById(ServiceOffering.class, NEW_OFFERING_ID)).thenReturn(offering);
        when(entityManager.findById(DiskOffering.class, DISK_OFFERING_ID)).thenReturn(disk);
        // router's root volume is NOT on local storage -> mismatch
        when(itManager.isRootVolumeOnLocalStorage(ROUTER_ID)).thenReturn(false);

        service.upgradeRouter(cmd);
    }

    @Test
    public void upgradeRouterUpdatesAndReturnsRouterOnSuccess() {
        UpgradeRouterCmd cmd = mockUpgradeCmd();
        DomainRouterVO router = mockRouterStopped();
        ServiceOffering offering = Mockito.mock(ServiceOffering.class);
        when(offering.getDiskOfferingId()).thenReturn(DISK_OFFERING_ID);
        when(offering.isSystemUse()).thenReturn(true);
        DiskOffering disk = Mockito.mock(DiskOffering.class);
        when(disk.isUseLocalStorage()).thenReturn(false);

        when(routerDao.findById(ROUTER_ID)).thenReturn(router);
        when(entityManager.findById(ServiceOffering.class, NEW_OFFERING_ID)).thenReturn(offering);
        when(entityManager.findById(DiskOffering.class, DISK_OFFERING_ID)).thenReturn(disk);
        when(itManager.isRootVolumeOnLocalStorage(ROUTER_ID)).thenReturn(false);
        when(routerDao.update(eq(ROUTER_ID), any(DomainRouterVO.class))).thenReturn(true);

        VirtualRouter result = service.upgradeRouter(cmd);

        assertSame(router, result);
        verify(router).setServiceOfferingId(NEW_OFFERING_ID);
        verify(accountManager).checkAccess(any(Account.class), any(), eq(true), eq(router));
    }

    @Test(expected = CloudRuntimeException.class)
    public void upgradeRouterThrowsCloudRuntimeWhenUpdateFails() {
        UpgradeRouterCmd cmd = mockUpgradeCmd();
        DomainRouterVO router = mockRouterStopped();
        ServiceOffering offering = Mockito.mock(ServiceOffering.class);
        when(offering.getDiskOfferingId()).thenReturn(DISK_OFFERING_ID);
        when(offering.isSystemUse()).thenReturn(true);
        DiskOffering disk = Mockito.mock(DiskOffering.class);
        when(disk.isUseLocalStorage()).thenReturn(false);

        when(routerDao.findById(ROUTER_ID)).thenReturn(router);
        when(entityManager.findById(ServiceOffering.class, NEW_OFFERING_ID)).thenReturn(offering);
        when(entityManager.findById(DiskOffering.class, DISK_OFFERING_ID)).thenReturn(disk);
        when(itManager.isRootVolumeOnLocalStorage(ROUTER_ID)).thenReturn(false);
        when(routerDao.update(eq(ROUTER_ID), any(DomainRouterVO.class))).thenReturn(false);

        service.upgradeRouter(cmd);
    }

    // ----------------------- upgradeRouterTemplate -----------------------

    @Test
    public void upgradeRouterTemplateByRouterIdRebootsMatchingRouter() {
        UpgradeRouterTemplateCmd cmd = blankTemplateCmd();
        when(cmd.getId()).thenReturn(ROUTER_ID);

        DomainRouterVO router = mockRouterOutOfDate();
        when(routerDao.findById(ROUTER_ID)).thenReturn(router);
        when(asyncDispatcher.getName()).thenReturn("apiAsyncJobDispatcher");
        when(asyncManager.submitAsyncJob(any(AsyncJobVO.class))).thenReturn(555L);

        try (MockedStatic<ComponentContext> mocked = Mockito.mockStatic(ComponentContext.class)) {
            mocked.when(() -> ComponentContext.inject(any())).thenReturn(true);

            List<Long> jobIds = service.upgradeRouterTemplate(cmd);

            assertEquals(Collections.singletonList(555L), jobIds);
            verify(asyncManager, times(1)).submitAsyncJob(any(AsyncJobVO.class));
        }
    }

    @Test
    public void upgradeRouterTemplateRejectsMultipleScopeParams() {
        UpgradeRouterTemplateCmd cmd = blankTemplateCmd();
        when(cmd.getId()).thenReturn(ROUTER_ID);
        when(cmd.getZoneId()).thenReturn(ZONE_ID);

        DomainRouterVO router = Mockito.mock(DomainRouterVO.class);
        when(routerDao.findById(ROUTER_ID)).thenReturn(router);
        when(routerDao.listRunningByDataCenter(ZONE_ID)).thenReturn(Arrays.asList(router));

        try {
            service.upgradeRouterTemplate(cmd);
            fail("expected InvalidParameterValueException for multiple params");
        } catch (InvalidParameterValueException expected) {
            // expected
        }
        verify(asyncManager, never()).submitAsyncJob(any(AsyncJobVO.class));
    }

    @Test
    public void upgradeRouterTemplateScopesByDomainListsByDomain() {
        UpgradeRouterTemplateCmd cmd = blankTemplateCmd();
        when(cmd.getDomainId()).thenReturn(DOMAIN_ID);
        when(routerDao.listRunningByDomain(DOMAIN_ID)).thenReturn(Collections.emptyList());

        List<Long> jobIds = service.upgradeRouterTemplate(cmd);

        assertEquals(Collections.emptyList(), jobIds);
        verify(routerDao).listRunningByDomain(DOMAIN_ID);
        verify(routerDao, never()).listRunningByAccountId(anyLong());
    }

    @Test
    public void upgradeRouterTemplateScopesByDomainAndAccountListsByAccount() {
        UpgradeRouterTemplateCmd cmd = blankTemplateCmd();
        when(cmd.getDomainId()).thenReturn(DOMAIN_ID);
        when(cmd.getAccount()).thenReturn("alice");

        Account acct = Mockito.mock(Account.class);
        when(acct.getId()).thenReturn(ACCOUNT_ID);
        when(accountManager.getActiveAccountByName("alice", DOMAIN_ID)).thenReturn(acct);
        when(routerDao.listRunningByAccountId(ACCOUNT_ID)).thenReturn(Collections.emptyList());

        List<Long> jobIds = service.upgradeRouterTemplate(cmd);

        assertEquals(Collections.emptyList(), jobIds);
        verify(routerDao).listRunningByAccountId(ACCOUNT_ID);
        verify(routerDao, never()).listRunningByDomain(anyLong());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void upgradeRouterTemplateThrowsWhenAccountInDomainMissing() {
        UpgradeRouterTemplateCmd cmd = blankTemplateCmd();
        when(cmd.getDomainId()).thenReturn(DOMAIN_ID);
        when(cmd.getAccount()).thenReturn("ghost");
        when(accountManager.getActiveAccountByName("ghost", DOMAIN_ID)).thenReturn(null);

        service.upgradeRouterTemplate(cmd);
    }

    @Test
    public void upgradeRouterTemplateScopesByClusterPodAndZone() {
        UpgradeRouterTemplateCmd zoneCmd = blankTemplateCmd();
        when(zoneCmd.getZoneId()).thenReturn(ZONE_ID);
        when(routerDao.listRunningByDataCenter(ZONE_ID)).thenReturn(Collections.emptyList());
        service.upgradeRouterTemplate(zoneCmd);
        verify(routerDao).listRunningByDataCenter(ZONE_ID);

        UpgradeRouterTemplateCmd podCmd = blankTemplateCmd();
        when(podCmd.getPodId()).thenReturn(POD_ID);
        when(routerDao.listRunningByPodId(POD_ID)).thenReturn(Collections.emptyList());
        service.upgradeRouterTemplate(podCmd);
        verify(routerDao).listRunningByPodId(POD_ID);

        UpgradeRouterTemplateCmd clusterCmd = blankTemplateCmd();
        when(clusterCmd.getClusterId()).thenReturn(CLUSTER_ID);
        when(routerDao.listRunningByClusterId(CLUSTER_ID)).thenReturn(Collections.emptyList());
        service.upgradeRouterTemplate(clusterCmd);
        verify(routerDao).listRunningByClusterId(CLUSTER_ID);
    }

    @Test
    public void upgradeRouterTemplateReturnsEmptyListForUnknownRouterId() {
        UpgradeRouterTemplateCmd cmd = blankTemplateCmd();
        when(cmd.getId()).thenReturn(ROUTER_ID);
        when(routerDao.findById(ROUTER_ID)).thenReturn(null);

        List<Long> jobIds = service.upgradeRouterTemplate(cmd);

        // When router lookup misses, the routers list stays empty and
        // rebootRoutersForTemplateUpgrade returns an empty job list (not null).
        assertEquals(Collections.emptyList(), jobIds);
        verify(asyncManager, never()).submitAsyncJob(any(AsyncJobVO.class));
    }

    // ----------------------- rebootRoutersForTemplateUpgrade -----------------------

    @Test
    public void rebootRoutersForTemplateUpgradeEmptyListReturnsEmpty() {
        List<Long> jobIds = service.rebootRoutersForTemplateUpgrade(Collections.emptyList());
        assertEquals(Collections.emptyList(), jobIds);
        verify(asyncManager, never()).submitAsyncJob(any(AsyncJobVO.class));
    }

    @Test
    public void rebootRoutersForTemplateUpgradeSubmitsJobWhenVersionOutOfDate() {
        DomainRouterVO router = mockRouterOutOfDate();
        when(asyncDispatcher.getName()).thenReturn("apiAsyncJobDispatcher");
        when(asyncManager.submitAsyncJob(any(AsyncJobVO.class))).thenReturn(101L);

        try (MockedStatic<ComponentContext> mocked = Mockito.mockStatic(ComponentContext.class)) {
            mocked.when(() -> ComponentContext.inject(any())).thenReturn(true);

            List<Long> jobIds = service.rebootRoutersForTemplateUpgrade(
                    Arrays.asList(router));

            assertEquals(Collections.singletonList(101L), jobIds);
            verify(asyncManager).submitAsyncJob(any(AsyncJobVO.class));
        }
    }

    @Test(expected = CloudRuntimeException.class)
    public void rebootRoutersForTemplateUpgradeThrowsWhenAlreadyLatestVersion() {
        DomainRouterVO router = Mockito.mock(DomainRouterVO.class);
        when(networkHelper.checkRouterTemplateVersion(router)).thenReturn(true);

        service.rebootRoutersForTemplateUpgrade(Arrays.asList(router));
    }

    @Test
    public void rebootRoutersForTemplateUpgradeSubmitsOnePerOutdatedRouter() {
        DomainRouterVO r1 = mockRouterOutOfDate();
        DomainRouterVO r2 = mockRouterOutOfDate();
        when(asyncDispatcher.getName()).thenReturn("apiAsyncJobDispatcher");
        when(asyncManager.submitAsyncJob(any(AsyncJobVO.class)))
                .thenReturn(1L, 2L);

        try (MockedStatic<ComponentContext> mocked = Mockito.mockStatic(ComponentContext.class)) {
            mocked.when(() -> ComponentContext.inject(any())).thenReturn(true);

            List<Long> jobIds = service.rebootRoutersForTemplateUpgrade(Arrays.asList(r1, r2));

            assertEquals(Arrays.asList(1L, 2L), jobIds);
            verify(asyncManager, times(2)).submitAsyncJob(any(AsyncJobVO.class));
        }
    }

    @Test
    public void rebootRoutersForTemplateUpgradeUsesSystemUserAndRouterAccount() {
        DomainRouterVO router = mockRouterOutOfDate();
        when(asyncDispatcher.getName()).thenReturn("apiAsyncJobDispatcher");
        when(asyncManager.submitAsyncJob(any(AsyncJobVO.class)))
                .thenAnswer(invocation -> {
                    AsyncJobVO job = invocation.getArgument(0);
                    assertEquals(User.UID_SYSTEM, job.getUserId());
                    assertEquals(ACCOUNT_ID, job.getAccountId());
                    assertEquals("apiAsyncJobDispatcher", job.getDispatcher());
                    return 77L;
                });

        try (MockedStatic<ComponentContext> mocked = Mockito.mockStatic(ComponentContext.class)) {
            mocked.when(() -> ComponentContext.inject(any())).thenReturn(true);

            List<Long> jobIds = service.rebootRoutersForTemplateUpgrade(Arrays.asList(router));

            assertEquals(Collections.singletonList(77L), jobIds);
        }
    }

    // ----------------------- helpers -----------------------

    private UpgradeRouterCmd mockUpgradeCmd() {
        UpgradeRouterCmd cmd = Mockito.mock(UpgradeRouterCmd.class);
        when(cmd.getId()).thenReturn(ROUTER_ID);
        when(cmd.getServiceOfferingId()).thenReturn(NEW_OFFERING_ID);
        return cmd;
    }

    /**
     * Mockito's default answer returns auto-boxed 0L (not null) for Long
     * getters on mocks of concrete classes, which incorrectly counts as
     * "param set" in {@code upgradeRouterTemplate}. Force every Long getter
     * to null so callers only have to stub the scope they want to exercise.
     */
    private UpgradeRouterTemplateCmd blankTemplateCmd() {
        UpgradeRouterTemplateCmd cmd = Mockito.mock(UpgradeRouterTemplateCmd.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getDomainId()).thenReturn(null);
        when(cmd.getClusterId()).thenReturn(null);
        when(cmd.getPodId()).thenReturn(null);
        when(cmd.getZoneId()).thenReturn(null);
        when(cmd.getAccount()).thenReturn(null);
        return cmd;
    }

    private DomainRouterVO mockRouterStopped() {
        DomainRouterVO router = Mockito.mock(DomainRouterVO.class);
        when(router.getServiceOfferingId()).thenReturn(999L);
        when(router.getState()).thenReturn(VirtualMachine.State.Stopped);
        return router;
    }

    private DomainRouterVO mockRouterOutOfDate() {
        DomainRouterVO router = Mockito.mock(DomainRouterVO.class);
        when(router.getAccountId()).thenReturn(ACCOUNT_ID);
        when(router.getId()).thenReturn(ROUTER_ID);
        when(networkHelper.checkRouterTemplateVersion(router)).thenReturn(false);
        return router;
    }
}
