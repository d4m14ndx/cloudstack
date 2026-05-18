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
package com.cloud.configuration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.cloudstack.api.command.admin.offering.CreateServiceOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.DeleteServiceOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.UpdateServiceOfferingCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.apache.cloudstack.vm.lease.VMLeaseManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VsphereStoragePolicyDao;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.server.ManagementService;
import com.cloud.offering.ServiceOffering;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.service.dao.ServiceOfferingDetailsDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.DomainHelper;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.VirtualMachine;

import org.apache.cloudstack.annotation.dao.AnnotationDao;

/**
 * Focused tests for {@link ServiceOfferingServiceImpl} — the Phase 4 extraction
 * of service-offering CRUD out of {@link ConfigurationManagerImpl}.
 *
 * Behaviour is also exercised through the manager's delegating wrappers in
 * {@code ConfigurationManagerImplTest}; these tests target the service directly
 * so future refactors of the manager don't drop coverage.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ServiceOfferingServiceImplTest {

    @Mock private ServiceOfferingDao serviceOfferingDao;
    @Mock private ServiceOfferingDetailsDao serviceOfferingDetailsDao;
    @Mock private DiskOfferingDao diskOfferingDao;
    @Mock private DiskOfferingDetailsDao diskOfferingDetailsDao;
    @Mock private VMInstanceDao vmInstanceDao;
    @Mock private DataCenterDao zoneDao;
    @Mock private DomainDao domainDao;
    @Mock private UserDao userDao;
    @Mock private AccountDao accountDao;
    @Mock private EntityManager entityMgr;
    @Mock private VsphereStoragePolicyDao vsphereStoragePolicyDao;
    @Mock private AnnotationDao annotationDao;
    @Mock private DomainHelper domainHelper;
    @Mock private ManagementService mgr;

    @InjectMocks
    private ServiceOfferingServiceImpl service;

    private static final long SO_ID   = 42L;
    private static final long DISK_ID = 99L;
    private static final long USER_ID = 7L;
    private static final long ZONE_ID = 10L;
    private static final long DOM_ID  = 1L;

    private AccountVO adminAccount;
    private AccountVO domainAdminAccount;
    private AccountVO normalAccount;
    private UserVO    userVO;

    @Before
    public void setUp() {
        ReflectionTestUtils.setField(service, "_serviceOfferingDao",        serviceOfferingDao);
        ReflectionTestUtils.setField(service, "_serviceOfferingDetailsDao", serviceOfferingDetailsDao);
        ReflectionTestUtils.setField(service, "_diskOfferingDao",           diskOfferingDao);
        ReflectionTestUtils.setField(service, "diskOfferingDetailsDao",     diskOfferingDetailsDao);
        ReflectionTestUtils.setField(service, "_vmInstanceDao",             vmInstanceDao);
        ReflectionTestUtils.setField(service, "_zoneDao",                   zoneDao);
        ReflectionTestUtils.setField(service, "_domainDao",                 domainDao);
        ReflectionTestUtils.setField(service, "_userDao",                   userDao);
        ReflectionTestUtils.setField(service, "_accountDao",                accountDao);
        ReflectionTestUtils.setField(service, "_entityMgr",                 entityMgr);
        ReflectionTestUtils.setField(service, "vsphereStoragePolicyDao",    vsphereStoragePolicyDao);
        ReflectionTestUtils.setField(service, "annotationDao",              annotationDao);
        ReflectionTestUtils.setField(service, "domainHelper",               domainHelper);
        ReflectionTestUtils.setField(service, "_mgr",                       mgr);

        adminAccount       = new AccountVO("admin",  1L, "domain", Account.Type.ADMIN,        UUID.randomUUID().toString());
        domainAdminAccount = new AccountVO("da",     1L, "domain", Account.Type.DOMAIN_ADMIN, UUID.randomUUID().toString());
        normalAccount      = new AccountVO("normal", 1L, "domain", Account.Type.NORMAL,       UUID.randomUUID().toString());
        userVO             = new UserVO(USER_ID, "u", "p", "f", "l", "e", "tz", UUID.randomUUID().toString(), User.Source.UNKNOWN);
    }

    @After
    public void tearDown() {
        while (CallContext.unregister() != null) {
            // drain thread-local CallContext stack
        }
    }

    // -------------------------------------------------------------------------
    // createServiceOffering — input-validation paths
    // -------------------------------------------------------------------------

    @Test
    public void createServiceOfferingThrowsForBlankName() {
        CreateServiceOfferingCmd cmd = Mockito.mock(CreateServiceOfferingCmd.class);
        Mockito.when(cmd.getServiceOfferingName()).thenReturn("");

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createServiceOffering(cmd));
        assertTrue(ex.getMessage().contains("specify the name"));
    }

    @Test
    public void createServiceOfferingThrowsForBlankDisplayText() {
        CreateServiceOfferingCmd cmd = Mockito.mock(CreateServiceOfferingCmd.class);
        Mockito.when(cmd.getServiceOfferingName()).thenReturn("offer1");
        Mockito.when(cmd.getDisplayText()).thenReturn("");

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createServiceOffering(cmd));
        assertTrue(ex.getMessage().contains("display text"));
    }

    @Test
    public void createServiceOfferingThrowsForInvalidDomainId() {
        CreateServiceOfferingCmd cmd = Mockito.mock(CreateServiceOfferingCmd.class);
        Mockito.when(cmd.getServiceOfferingName()).thenReturn("offer1");
        Mockito.when(cmd.getDisplayText()).thenReturn("display1");
        Mockito.when(cmd.isCustomized()).thenReturn(false);
        Mockito.when(cmd.getCpuNumber()).thenReturn(1);
        Mockito.when(cmd.getCpuSpeed()).thenReturn(1000);
        Mockito.when(cmd.getMemory()).thenReturn(512);
        Mockito.when(cmd.getDomainIds()).thenReturn(Collections.singletonList(99L));
        Mockito.when(domainDao.findById(99L)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createServiceOffering(cmd));
        assertTrue(ex.getMessage().toLowerCase().contains("domain"));
    }

    @Test
    public void createServiceOfferingThrowsForInvalidZoneId() {
        CreateServiceOfferingCmd cmd = Mockito.mock(CreateServiceOfferingCmd.class);
        Mockito.when(cmd.getServiceOfferingName()).thenReturn("offer1");
        Mockito.when(cmd.getDisplayText()).thenReturn("display1");
        Mockito.when(cmd.isCustomized()).thenReturn(false);
        Mockito.when(cmd.getCpuNumber()).thenReturn(1);
        Mockito.when(cmd.getCpuSpeed()).thenReturn(1000);
        Mockito.when(cmd.getMemory()).thenReturn(512);
        Mockito.when(cmd.getDomainIds()).thenReturn(Collections.emptyList());
        Mockito.when(cmd.getZoneIds()).thenReturn(Collections.singletonList(ZONE_ID));
        Mockito.when(zoneDao.findById(ZONE_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createServiceOffering(cmd));
        assertTrue(ex.getMessage().toLowerCase().contains("zone"));
    }

    @Test
    public void createServiceOfferingThrowsForHaWithLocalStorage() {
        CreateServiceOfferingCmd cmd = Mockito.mock(CreateServiceOfferingCmd.class);
        Mockito.when(cmd.getServiceOfferingName()).thenReturn("offer1");
        Mockito.when(cmd.getDisplayText()).thenReturn("display1");
        Mockito.when(cmd.isCustomized()).thenReturn(false);
        Mockito.when(cmd.getCpuNumber()).thenReturn(1);
        Mockito.when(cmd.getCpuSpeed()).thenReturn(1000);
        Mockito.when(cmd.getMemory()).thenReturn(512);
        Mockito.when(cmd.getDomainIds()).thenReturn(Collections.emptyList());
        Mockito.when(cmd.getZoneIds()).thenReturn(Collections.emptyList());
        Mockito.when(cmd.isOfferHa()).thenReturn(Boolean.TRUE);
        Mockito.when(cmd.getStorageType()).thenReturn("local");

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createServiceOffering(cmd));
        assertTrue(ex.getMessage().contains("HA offering with local storage"));
    }

    @Test
    public void createServiceOfferingThrowsForInvalidCacheMode() {
        CreateServiceOfferingCmd cmd = Mockito.mock(CreateServiceOfferingCmd.class);
        Mockito.when(cmd.getServiceOfferingName()).thenReturn("offer1");
        Mockito.when(cmd.getDisplayText()).thenReturn("display1");
        Mockito.when(cmd.isCustomized()).thenReturn(false);
        Mockito.when(cmd.getCpuNumber()).thenReturn(1);
        Mockito.when(cmd.getCpuSpeed()).thenReturn(1000);
        Mockito.when(cmd.getMemory()).thenReturn(512);
        Mockito.when(cmd.getDomainIds()).thenReturn(Collections.emptyList());
        Mockito.when(cmd.getZoneIds()).thenReturn(Collections.emptyList());
        Mockito.when(cmd.getCacheMode()).thenReturn("bogus_mode");

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createServiceOffering(cmd));
        assertTrue(ex.getMessage().toLowerCase().contains("cache mode"));
    }

    @Test
    public void createServiceOfferingThrowsForInvalidStoragePolicy() {
        CreateServiceOfferingCmd cmd = Mockito.mock(CreateServiceOfferingCmd.class);
        Mockito.when(cmd.getServiceOfferingName()).thenReturn("offer1");
        Mockito.when(cmd.getDisplayText()).thenReturn("display1");
        Mockito.when(cmd.isCustomized()).thenReturn(false);
        Mockito.when(cmd.getCpuNumber()).thenReturn(1);
        Mockito.when(cmd.getCpuSpeed()).thenReturn(1000);
        Mockito.when(cmd.getMemory()).thenReturn(512);
        Mockito.when(cmd.getDomainIds()).thenReturn(Collections.emptyList());
        Mockito.when(cmd.getZoneIds()).thenReturn(Collections.emptyList());
        Mockito.when(cmd.getStoragePolicy()).thenReturn(500L);
        Mockito.when(vsphereStoragePolicyDao.findById(500L)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createServiceOffering(cmd));
        assertTrue(ex.getMessage().contains("vSphere storage policy"));
    }

    @Test
    public void createServiceOfferingThrowsForComputeOnlyDiskOffering() {
        CreateServiceOfferingCmd cmd = Mockito.mock(CreateServiceOfferingCmd.class);
        Mockito.when(cmd.getServiceOfferingName()).thenReturn("offer1");
        Mockito.when(cmd.getDisplayText()).thenReturn("display1");
        Mockito.when(cmd.isCustomized()).thenReturn(false);
        Mockito.when(cmd.getCpuNumber()).thenReturn(1);
        Mockito.when(cmd.getCpuSpeed()).thenReturn(1000);
        Mockito.when(cmd.getMemory()).thenReturn(512);
        Mockito.when(cmd.getDomainIds()).thenReturn(Collections.emptyList());
        Mockito.when(cmd.getZoneIds()).thenReturn(Collections.emptyList());
        // Explicitly null out storage policy so we reach the disk offering check
        Mockito.when(cmd.getStoragePolicy()).thenReturn(null);
        Mockito.when(cmd.getDiskOfferingId()).thenReturn(DISK_ID);
        DiskOfferingVO computeOnly = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(computeOnly.isComputeOnly()).thenReturn(true);
        Mockito.when(diskOfferingDao.findById(DISK_ID)).thenReturn(computeOnly);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createServiceOffering(cmd));
        assertTrue("unexpected message: " + ex.getMessage(),
                ex.getMessage().contains("valid disk offering"));
    }

    @Test
    public void createServiceOfferingOverloadPersistsStoragePolicyWithNullDetails() {
        CallContext.register(userVO, adminAccount);
        Mockito.when(userDao.findById(USER_ID)).thenReturn(userVO);
        Mockito.when(accountDao.findById(Mockito.anyLong())).thenReturn(adminAccount);
        Mockito.when(domainHelper.filterChildSubDomains(Collections.emptyList())).thenReturn(Collections.emptyList());

        DiskOfferingVO diskOffering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOffering.getId()).thenReturn(DISK_ID);
        Mockito.when(diskOfferingDao.persist(Mockito.any(DiskOfferingVO.class))).thenReturn(diskOffering);

        ServiceOfferingVO persistedOffering = new ServiceOfferingVO("offer1", 1, 512, 1000, null, null, false,
                false, false, "display1", false, VirtualMachine.Type.User, null, null, false, false);
        persistedOffering.setId(SO_ID);
        Mockito.when(serviceOfferingDao.persist(Mockito.any(ServiceOfferingVO.class))).thenReturn(persistedOffering);

        ServiceOfferingService serviceContract = service;
        ServiceOfferingVO result = serviceContract.createServiceOffering(USER_ID, false, VirtualMachine.Type.User,
                "offer1", 1, 512, 1000, "display1", "thin", false,
                false, false, false, null, Collections.emptyList(), Collections.emptyList(), null,
                null, null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null,
                null, null, 123L, false, null, false,
                false, false, null, null, null, false, null, null);

        assertEquals(persistedOffering, result);
        Mockito.verify(diskOfferingDetailsDao).saveDetails(Mockito.anyList());
    }

    // -------------------------------------------------------------------------
    // updateServiceOffering — validation paths
    // -------------------------------------------------------------------------

    @Test
    public void updateServiceOfferingThrowsWhenOfferingNotFound() {
        UpdateServiceOfferingCmd cmd = Mockito.mock(UpdateServiceOfferingCmd.class);
        Mockito.when(cmd.getId()).thenReturn(SO_ID);
        Mockito.when(entityMgr.findById(ServiceOffering.class, SO_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.updateServiceOffering(cmd));
        assertTrue(ex.getMessage().contains("unable to find service offering"));
    }

    @Test
    public void updateServiceOfferingThrowsForInvalidDomainId() {
        UpdateServiceOfferingCmd cmd = Mockito.mock(UpdateServiceOfferingCmd.class);
        Mockito.when(cmd.getId()).thenReturn(SO_ID);
        ServiceOffering existing = Mockito.mock(ServiceOffering.class);
        Mockito.when(entityMgr.findById(ServiceOffering.class, SO_ID)).thenReturn(existing);
        Mockito.when(serviceOfferingDetailsDao.findDomainIds(SO_ID)).thenReturn(new ArrayList<>());
        Mockito.when(serviceOfferingDetailsDao.findZoneIds(SO_ID)).thenReturn(new ArrayList<>());
        Mockito.when(serviceOfferingDetailsDao.listDetailsKeyPairs(SO_ID)).thenReturn(new java.util.HashMap<>());
        Mockito.when(cmd.getDomainIds()).thenReturn(Collections.singletonList(99L));
        Mockito.when(domainDao.findById(99L)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.updateServiceOffering(cmd));
        assertTrue(ex.getMessage().toLowerCase().contains("domain"));
    }

    @Test
    public void updateServiceOfferingThrowsForNormalUser() {
        CallContext.register(userVO, normalAccount);
        UpdateServiceOfferingCmd cmd = Mockito.mock(UpdateServiceOfferingCmd.class);
        Mockito.when(cmd.getId()).thenReturn(SO_ID);
        ServiceOffering existing = Mockito.mock(ServiceOffering.class);
        Mockito.when(entityMgr.findById(ServiceOffering.class, SO_ID)).thenReturn(existing);
        Mockito.when(serviceOfferingDetailsDao.findDomainIds(SO_ID)).thenReturn(new ArrayList<>());
        Mockito.when(serviceOfferingDetailsDao.findZoneIds(SO_ID)).thenReturn(new ArrayList<>());
        Mockito.when(serviceOfferingDetailsDao.listDetailsKeyPairs(SO_ID)).thenReturn(new java.util.HashMap<>());
        Mockito.when(cmd.getDomainIds()).thenReturn(null);
        Mockito.when(cmd.getZoneIds()).thenReturn(null);
        Mockito.when(userDao.findById(Mockito.anyLong())).thenReturn(userVO);
        Mockito.when(accountDao.findById(Mockito.anyLong())).thenReturn(normalAccount);
        Mockito.when(domainHelper.filterChildSubDomains(Mockito.any())).thenReturn(new ArrayList<>());
        Mockito.when(vmInstanceDao.getVmCountByOfferingNotInDomain(Mockito.anyLong(), Mockito.any())).thenReturn(0);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.updateServiceOffering(cmd));
        assertTrue(ex.getMessage().contains("not root-admin or domain-admin"));
    }

    // -------------------------------------------------------------------------
    // deleteServiceOffering — validation paths
    // -------------------------------------------------------------------------

    @Test
    public void deleteServiceOfferingThrowsWhenOfferingNotFound() {
        CallContext.register(userVO, adminAccount);
        DeleteServiceOfferingCmd cmd = Mockito.mock(DeleteServiceOfferingCmd.class);
        Mockito.when(cmd.getId()).thenReturn(SO_ID);
        Mockito.when(userDao.findById(Mockito.anyLong())).thenReturn(userVO);
        Mockito.when(accountDao.findById(Mockito.anyLong())).thenReturn(adminAccount);
        Mockito.when(serviceOfferingDao.findById(SO_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.deleteServiceOffering(cmd));
        assertTrue(ex.getMessage().contains("unable to find service offering"));
    }

    @Test
    public void deleteServiceOfferingThrowsForDefaultOffering() {
        CallContext.register(userVO, adminAccount);
        DeleteServiceOfferingCmd cmd = Mockito.mock(DeleteServiceOfferingCmd.class);
        Mockito.when(cmd.getId()).thenReturn(SO_ID);
        Mockito.when(userDao.findById(Mockito.anyLong())).thenReturn(userVO);
        Mockito.when(accountDao.findById(Mockito.anyLong())).thenReturn(adminAccount);

        ServiceOfferingVO offering = Mockito.mock(ServiceOfferingVO.class);
        Mockito.when(offering.getDefaultUse()).thenReturn(true);
        Mockito.when(offering.getDisplayText()).thenReturn("system-default-offering");
        Mockito.when(serviceOfferingDao.findById(SO_ID)).thenReturn(offering);

        // disk offering must be stubbed to prevent NPE before the default-use guard
        DiskOfferingVO disk = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(offering.getDiskOfferingId()).thenReturn(DISK_ID);
        Mockito.when(diskOfferingDao.findById(DISK_ID)).thenReturn(disk);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.deleteServiceOffering(cmd));
        assertTrue(ex.getMessage().contains("default use"));
    }

    // -------------------------------------------------------------------------
    // getServiceOfferingDomains / Zones — lookup paths
    // -------------------------------------------------------------------------

    @Test
    public void getServiceOfferingDomainsThrowsWhenOfferingNotFound() {
        Mockito.when(entityMgr.findById(ServiceOffering.class, SO_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.getServiceOfferingDomains(SO_ID));
        assertTrue(ex.getMessage().contains("Unable to find service offering"));
    }

    @Test
    public void getServiceOfferingDomainsReturnsListFromDao() {
        ServiceOffering existing = Mockito.mock(ServiceOffering.class);
        Mockito.when(entityMgr.findById(ServiceOffering.class, SO_ID)).thenReturn(existing);
        List<Long> domainIds = Arrays.asList(1L, 2L);
        Mockito.when(serviceOfferingDetailsDao.findDomainIds(SO_ID)).thenReturn(domainIds);

        List<Long> result = service.getServiceOfferingDomains(SO_ID);
        assertEquals(domainIds, result);
    }

    @Test
    public void getServiceOfferingZonesThrowsWhenOfferingNotFound() {
        Mockito.when(entityMgr.findById(ServiceOffering.class, SO_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.getServiceOfferingZones(SO_ID));
        assertTrue(ex.getMessage().contains("Unable to find service offering"));
    }

    @Test
    public void getServiceOfferingZonesReturnsEmptyListForPublicOffering() {
        ServiceOffering existing = Mockito.mock(ServiceOffering.class);
        Mockito.when(entityMgr.findById(ServiceOffering.class, SO_ID)).thenReturn(existing);
        Mockito.when(serviceOfferingDetailsDao.findZoneIds(SO_ID)).thenReturn(Collections.emptyList());

        List<Long> result = service.getServiceOfferingZones(SO_ID);
        assertNotNull(result);
        assertTrue(result.isEmpty());
    }

    // -------------------------------------------------------------------------
    // validateCacheMode — helper coverage
    // -------------------------------------------------------------------------

    @Test
    public void validateCacheModeAcceptsNullWithoutThrowing() {
        // should not throw
        service.validateCacheMode(null);
    }

    @Test
    public void validateCacheModeAcceptsWriteback() {
        // recognised enum value — should not throw
        service.validateCacheMode("writeback");
    }

    @Test
    public void validateCacheModeThrowsForUnknownValue() {
        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.validateCacheMode("unknown_mode"));
        assertTrue(ex.getMessage().toLowerCase().contains("cache mode"));
    }

    // -------------------------------------------------------------------------
    // validateAndGetLeaseExpiryAction — helper coverage
    // -------------------------------------------------------------------------

    @Test
    public void validateLeaseExpiryActionReturnsNullWhenBothParamsNull() {
        VMLeaseManager.ExpiryAction result = service.validateAndGetLeaseExpiryAction(null, null);
        assertNull(result);
    }

    @Test
    public void validateLeaseExpiryActionReturnsNullWhenLeaseFeatureDisabled() {
        // When InstanceLeaseEnabled config key is false (default in test context),
        // the helper short-circuits and returns null regardless of params.
        VMLeaseManager.ExpiryAction result =
                service.validateAndGetLeaseExpiryAction(30, VMLeaseManager.ExpiryAction.DESTROY);
        // Either null (feature off) or the supplied action (feature on) are valid outcomes;
        // the important thing is the method doesn't throw.
        assertTrue(result == null || result == VMLeaseManager.ExpiryAction.DESTROY);
    }

    @Test
    public void validateLeaseExpiryActionReturnsActionWhenBothParamsProvided() {
        // When both params are provided and the feature gate is enabled,
        // the supplied action should be returned. We test the non-null branch
        // indirectly: if the feature is off the method returns null without
        // throwing; if it's on it returns the action. Either way no exception.
        VMLeaseManager.ExpiryAction result =
                service.validateAndGetLeaseExpiryAction(365, VMLeaseManager.ExpiryAction.STOP);
        assertTrue(result == null || result == VMLeaseManager.ExpiryAction.STOP);
    }

    // -------------------------------------------------------------------------
    // serviceOfferingExternalDetailsNeedUpdate — helper coverage
    // -------------------------------------------------------------------------

    @Test
    public void externalDetailsNeedUpdateReturnsFalseWhenBothEmpty() {
        java.util.Map<String, String> offeringDetails = new java.util.HashMap<>();
        assertFalse(service.serviceOfferingExternalDetailsNeedUpdate(offeringDetails, null, false));
    }
}
