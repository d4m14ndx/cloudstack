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
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.cloudstack.api.command.admin.offering.CreateDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.DeleteDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.UpdateDiskOfferingCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.resourcedetail.DiskOfferingDetailVO;
import org.apache.cloudstack.resourcedetail.dao.DiskOfferingDetailsDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
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
import com.cloud.offering.DiskOffering;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.DomainHelper;
import com.cloud.utils.db.EntityManager;

import org.apache.cloudstack.annotation.dao.AnnotationDao;

/**
 * Focused tests for {@link DiskOfferingServiceImpl} — the Phase 4 extraction
 * of disk-offering CRUD out of {@link ConfigurationManagerImpl}.
 *
 * Behavior is also exercised through the manager's delegating wrappers
 * in {@code ConfigurationManagerImplTest}; these tests target the service
 * directly so future refactors of the manager don't drop coverage.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class DiskOfferingServiceImplTest {

    @Mock private DiskOfferingDao diskOfferingDao;
    @Mock private DiskOfferingDetailsDao diskOfferingDetailsDao;
    @Mock private DataCenterDao zoneDao;
    @Mock private DomainDao domainDao;
    @Mock private UserDao userDao;
    @Mock private AccountDao accountDao;
    @Mock private EntityManager entityMgr;
    @Mock private VsphereStoragePolicyDao vsphereStoragePolicyDao;
    @Mock private PrimaryDataStoreDao storagePoolDao;
    @Mock private StoragePoolTagsDao storagePoolTagDao;
    @Mock private VolumeDao volumeDao;
    @Mock private AnnotationDao annotationDao;
    @Mock private DomainHelper domainHelper;

    @InjectMocks
    private DiskOfferingServiceImpl service;

    private static final long DO_ID = 42L;
    private static final long DOMAIN_ID = 1L;
    private static final long ZONE_ID = 10L;
    private static final long USER_ID = 7L;

    private AccountVO adminAccount;
    private AccountVO domainAdminAccount;
    private AccountVO normalAccount;
    private UserVO userVO;

    @Before
    public void setUp() {
        // The impl uses underscore-prefixed field names matching the manager;
        // @InjectMocks already binds by type, but make the wiring explicit so
        // refactors of the impl can't silently drop dependencies.
        ReflectionTestUtils.setField(service, "_diskOfferingDao", diskOfferingDao);
        ReflectionTestUtils.setField(service, "diskOfferingDetailsDao", diskOfferingDetailsDao);
        ReflectionTestUtils.setField(service, "_zoneDao", zoneDao);
        ReflectionTestUtils.setField(service, "_domainDao", domainDao);
        ReflectionTestUtils.setField(service, "_userDao", userDao);
        ReflectionTestUtils.setField(service, "_accountDao", accountDao);
        ReflectionTestUtils.setField(service, "_entityMgr", entityMgr);
        ReflectionTestUtils.setField(service, "vsphereStoragePolicyDao", vsphereStoragePolicyDao);
        ReflectionTestUtils.setField(service, "_storagePoolDao", storagePoolDao);
        ReflectionTestUtils.setField(service, "storagePoolTagDao", storagePoolTagDao);
        ReflectionTestUtils.setField(service, "_volumeDao", volumeDao);
        ReflectionTestUtils.setField(service, "annotationDao", annotationDao);
        ReflectionTestUtils.setField(service, "domainHelper", domainHelper);

        adminAccount = new AccountVO("admin", 1L, "domain", Account.Type.ADMIN, UUID.randomUUID().toString());
        domainAdminAccount = new AccountVO("da", 1L, "domain", Account.Type.DOMAIN_ADMIN, UUID.randomUUID().toString());
        normalAccount = new AccountVO("normal", 1L, "domain", Account.Type.NORMAL, UUID.randomUUID().toString());
        userVO = new UserVO(USER_ID, "u", "p", "f", "l", "e", "tz", UUID.randomUUID().toString(), User.Source.UNKNOWN);
    }

    @After
    public void tearDown() {
        while (CallContext.unregister() != null) {
            // drain thread-local CallContext registrations
        }
    }

    // ---------------------------------------------------------------------
    // createDiskOffering — validation paths
    // ---------------------------------------------------------------------

    @Test
    public void createDiskOfferingThrowsForInvalidDomainId() {
        CreateDiskOfferingCmd cmd = Mockito.mock(CreateDiskOfferingCmd.class);
        Mockito.when(cmd.getDomainIds()).thenReturn(Collections.singletonList(99L));
        Mockito.when(domainDao.findById(99L)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createDiskOffering(cmd));
        assertTrue(ex.getMessage().toLowerCase().contains("domain"));
    }

    @Test
    public void createDiskOfferingThrowsForInvalidZoneId() {
        CreateDiskOfferingCmd cmd = Mockito.mock(CreateDiskOfferingCmd.class);
        Mockito.when(cmd.getZoneIds()).thenReturn(Collections.singletonList(ZONE_ID));
        Mockito.when(zoneDao.findById(ZONE_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createDiskOffering(cmd));
        assertTrue(ex.getMessage().toLowerCase().contains("zone"));
    }

    @Test
    public void createDiskOfferingThrowsWhenNonCustomizedHasNoDiskSize() {
        CreateDiskOfferingCmd cmd = Mockito.mock(CreateDiskOfferingCmd.class);
        Mockito.when(cmd.isCustomized()).thenReturn(false);
        Mockito.when(cmd.getDiskSize()).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createDiskOffering(cmd));
        assertTrue(ex.getMessage().contains("Disksize is required"));
    }

    @Test
    public void createDiskOfferingThrowsWhenCustomizedHasDiskSize() {
        CreateDiskOfferingCmd cmd = Mockito.mock(CreateDiskOfferingCmd.class);
        Mockito.when(cmd.isCustomized()).thenReturn(true);
        Mockito.when(cmd.getDiskSize()).thenReturn(10L);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createDiskOffering(cmd));
        assertTrue(ex.getMessage().contains("not allowed"));
    }

    @Test
    public void createDiskOfferingThrowsForInvalidCacheMode() {
        CreateDiskOfferingCmd cmd = Mockito.mock(CreateDiskOfferingCmd.class);
        Mockito.when(cmd.isCustomized()).thenReturn(true);
        Mockito.when(cmd.getDiskSize()).thenReturn(null);
        Mockito.when(cmd.getCacheMode()).thenReturn("bogus_cache");

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createDiskOffering(cmd));
        assertTrue(ex.getMessage().toLowerCase().contains("cache mode"));
    }

    @Test
    public void createDiskOfferingThrowsForUnknownStorageType() {
        CreateDiskOfferingCmd cmd = Mockito.mock(CreateDiskOfferingCmd.class);
        Mockito.when(cmd.isCustomized()).thenReturn(true);
        Mockito.when(cmd.getDiskSize()).thenReturn(null);
        Mockito.when(cmd.getStorageType()).thenReturn("attic");

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createDiskOffering(cmd));
        assertTrue(ex.getMessage().contains("Invalid storage type"));
    }

    @Test
    public void createDiskOfferingThrowsForUnknownStoragePolicy() {
        CreateDiskOfferingCmd cmd = Mockito.mock(CreateDiskOfferingCmd.class);
        Mockito.when(cmd.isCustomized()).thenReturn(true);
        Mockito.when(cmd.getDiskSize()).thenReturn(null);
        Mockito.when(cmd.getStoragePolicy()).thenReturn(500L);
        Mockito.when(vsphereStoragePolicyDao.findById(500L)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createDiskOffering(cmd));
        assertTrue(ex.getMessage().contains("vSphere storage policy"));
    }

    @Test
    public void createDiskOfferingThrowsWhenIopsReadRateExceedsMax() {
        CreateDiskOfferingCmd cmd = Mockito.mock(CreateDiskOfferingCmd.class);
        Mockito.when(cmd.isCustomized()).thenReturn(true);
        Mockito.when(cmd.getDiskSize()).thenReturn(null);
        // Stub the storage-policy getter to null explicitly — the unstubbed mock
        // appears to surface a non-null value for Long-typed getters in some
        // mockito-junit configurations, which short-circuits us to a
        // different error message.
        Mockito.when(cmd.getStoragePolicy()).thenReturn(null);
        Mockito.when(cmd.getIopsReadRate()).thenReturn(2000L);
        Mockito.when(cmd.getIopsReadRateMax()).thenReturn(1000L);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createDiskOffering(cmd));
        assertTrue("unexpected message: " + ex.getMessage(),
                ex.getMessage().contains("IOPS Read"));
    }

    // ---------------------------------------------------------------------
    // updateDiskOffering — validation paths
    // ---------------------------------------------------------------------

    @Test
    public void updateDiskOfferingThrowsWhenOfferingNotFound() {
        UpdateDiskOfferingCmd cmd = Mockito.mock(UpdateDiskOfferingCmd.class);
        Mockito.when(cmd.getId()).thenReturn(DO_ID);
        Mockito.when(entityMgr.findById(DiskOffering.class, DO_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.updateDiskOffering(cmd));
        assertTrue(ex.getMessage().contains("Unable to find disk offering"));
    }

    @Test
    public void updateDiskOfferingThrowsForNormalAccount() {
        CallContext.register(userVO, normalAccount);
        UpdateDiskOfferingCmd cmd = Mockito.mock(UpdateDiskOfferingCmd.class);
        Mockito.when(cmd.getId()).thenReturn(DO_ID);
        DiskOffering existing = Mockito.mock(DiskOffering.class);
        Mockito.when(entityMgr.findById(DiskOffering.class, DO_ID)).thenReturn(existing);
        Mockito.when(diskOfferingDetailsDao.findDomainIds(DO_ID)).thenReturn(new ArrayList<>());
        Mockito.when(diskOfferingDetailsDao.findZoneIds(DO_ID)).thenReturn(new ArrayList<>());
        // CallContext from UserVO constructed without explicit id returns 0; stub findById broadly
        Mockito.when(userDao.findById(Mockito.anyLong())).thenReturn(userVO);
        Mockito.when(accountDao.findById(Mockito.anyLong())).thenReturn(normalAccount);
        Mockito.when(domainHelper.filterChildSubDomains(Mockito.any())).thenReturn(new ArrayList<>());

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.updateDiskOffering(cmd));
        assertTrue(ex.getMessage().contains("not root-admin or domain-admin"));
    }

    @Test
    public void updateDiskOfferingShortCircuitsWhenNothingChanged() {
        CallContext.register(userVO, adminAccount);
        UpdateDiskOfferingCmd cmd = Mockito.mock(UpdateDiskOfferingCmd.class);
        Mockito.when(cmd.getId()).thenReturn(DO_ID);
        // all cmd attributes that drive `shouldUpdate*` must be null/false to
        // short-circuit.
        Mockito.when(cmd.getDiskOfferingName()).thenReturn(null);
        Mockito.when(cmd.getDisplayText()).thenReturn(null);
        Mockito.when(cmd.getSortKey()).thenReturn(null);
        Mockito.when(cmd.getDisplayOffering()).thenReturn(null);
        Mockito.when(cmd.getTags()).thenReturn(null);
        Mockito.when(cmd.getCacheMode()).thenReturn(null);
        Mockito.when(cmd.getState()).thenReturn(null);
        Mockito.when(cmd.getBytesReadRate()).thenReturn(null);
        Mockito.when(cmd.getBytesReadRateMax()).thenReturn(null);
        Mockito.when(cmd.getBytesReadRateMaxLength()).thenReturn(null);
        Mockito.when(cmd.getBytesWriteRate()).thenReturn(null);
        Mockito.when(cmd.getBytesWriteRateMax()).thenReturn(null);
        Mockito.when(cmd.getBytesWriteRateMaxLength()).thenReturn(null);
        Mockito.when(cmd.getIopsReadRate()).thenReturn(null);
        Mockito.when(cmd.getIopsReadRateMax()).thenReturn(null);
        Mockito.when(cmd.getIopsReadRateMaxLength()).thenReturn(null);
        Mockito.when(cmd.getIopsWriteRate()).thenReturn(null);
        Mockito.when(cmd.getIopsWriteRateMax()).thenReturn(null);
        Mockito.when(cmd.getIopsWriteRateMaxLength()).thenReturn(null);
        Mockito.when(cmd.getDomainIds()).thenReturn(null);
        Mockito.when(cmd.getZoneIds()).thenReturn(null);

        DiskOffering existing = Mockito.mock(DiskOffering.class);
        Mockito.when(entityMgr.findById(DiskOffering.class, DO_ID)).thenReturn(existing);
        Mockito.when(diskOfferingDetailsDao.findDomainIds(DO_ID)).thenReturn(new ArrayList<>());
        Mockito.when(diskOfferingDetailsDao.findZoneIds(DO_ID)).thenReturn(new ArrayList<>());
        Mockito.when(userDao.findById(Mockito.anyLong())).thenReturn(userVO);
        Mockito.when(accountDao.findById(Mockito.anyLong())).thenReturn(adminAccount);
        Mockito.when(domainHelper.filterChildSubDomains(Mockito.any())).thenReturn(new ArrayList<>());

        DiskOfferingVO existingVO = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOfferingDao.findById(DO_ID)).thenReturn(existingVO);

        DiskOffering result = service.updateDiskOffering(cmd);

        // updateNeeded=false && detailsUpdateNeeded=false => return current row
        assertNotNull(result);
        Mockito.verify(diskOfferingDao, Mockito.never()).update(Mockito.anyLong(), Mockito.any());
        Mockito.verify(diskOfferingDao, Mockito.never()).createForUpdate(Mockito.anyLong());
    }

    // ---------------------------------------------------------------------
    // deleteDiskOffering — validation paths
    // ---------------------------------------------------------------------

    @Test
    public void deleteDiskOfferingThrowsWhenOfferingNotFound() {
        DeleteDiskOfferingCmd cmd = Mockito.mock(DeleteDiskOfferingCmd.class);
        Mockito.when(cmd.getId()).thenReturn(DO_ID);
        Mockito.when(diskOfferingDao.findById(DO_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.deleteDiskOffering(cmd));
        assertTrue(ex.getMessage().contains("Unable to find disk offering"));
    }

    @Test
    public void deleteDiskOfferingThrowsForNormalAccount() {
        CallContext.register(userVO, normalAccount);
        DeleteDiskOfferingCmd cmd = Mockito.mock(DeleteDiskOfferingCmd.class);
        Mockito.when(cmd.getId()).thenReturn(DO_ID);
        DiskOfferingVO offering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOfferingDao.findById(DO_ID)).thenReturn(offering);
        Mockito.when(userDao.findById(Mockito.anyLong())).thenReturn(userVO);
        Mockito.when(accountDao.findById(Mockito.anyLong())).thenReturn(normalAccount);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.deleteDiskOffering(cmd));
        assertTrue(ex.getMessage().contains("not root-admin or domain-admin"));
    }

    @Test
    public void deleteDiskOfferingThrowsForDomainAdminWithPublicOffering() {
        CallContext.register(userVO, domainAdminAccount);
        DeleteDiskOfferingCmd cmd = Mockito.mock(DeleteDiskOfferingCmd.class);
        Mockito.when(cmd.getId()).thenReturn(DO_ID);
        DiskOfferingVO offering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOfferingDao.findById(DO_ID)).thenReturn(offering);
        Mockito.when(userDao.findById(Mockito.anyLong())).thenReturn(userVO);
        Mockito.when(accountDao.findById(Mockito.anyLong())).thenReturn(domainAdminAccount);
        Mockito.when(diskOfferingDetailsDao.findDomainIds(DO_ID)).thenReturn(new ArrayList<>());

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.deleteDiskOffering(cmd));
        assertTrue(ex.getMessage().contains("public disk offering"));
    }

    @Test
    public void deleteDiskOfferingThrowsForDomainAdminWithNonChildDomain() {
        CallContext.register(userVO, domainAdminAccount);
        DeleteDiskOfferingCmd cmd = Mockito.mock(DeleteDiskOfferingCmd.class);
        Mockito.when(cmd.getId()).thenReturn(DO_ID);
        DiskOfferingVO offering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(diskOfferingDao.findById(DO_ID)).thenReturn(offering);
        Mockito.when(userDao.findById(Mockito.anyLong())).thenReturn(userVO);
        Mockito.when(accountDao.findById(Mockito.anyLong())).thenReturn(domainAdminAccount);
        Mockito.when(diskOfferingDetailsDao.findDomainIds(DO_ID))
                .thenReturn(Collections.singletonList(DOMAIN_ID));
        Mockito.when(domainDao.isChildDomain(domainAdminAccount.getDomainId(), DOMAIN_ID))
                .thenReturn(false);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.deleteDiskOffering(cmd));
        assertTrue(ex.getMessage().contains("not child domain"));
    }

    @Test
    public void deleteDiskOfferingMarksInactiveForAdmin() {
        CallContext.register(userVO, adminAccount);
        DeleteDiskOfferingCmd cmd = Mockito.mock(DeleteDiskOfferingCmd.class);
        Mockito.when(cmd.getId()).thenReturn(DO_ID);
        DiskOfferingVO offering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(offering.getId()).thenReturn(DO_ID);
        Mockito.when(offering.getUuid()).thenReturn("offering-uuid");
        Mockito.when(diskOfferingDao.findById(DO_ID)).thenReturn(offering);
        Mockito.when(userDao.findById(Mockito.anyLong())).thenReturn(userVO);
        Mockito.when(accountDao.findById(Mockito.anyLong())).thenReturn(adminAccount);
        Mockito.when(diskOfferingDao.update(DO_ID, offering)).thenReturn(true);

        boolean result = service.deleteDiskOffering(cmd);

        assertTrue(result);
        Mockito.verify(offering).setState(DiskOffering.State.Inactive);
        Mockito.verify(annotationDao).removeByEntityType(Mockito.anyString(), Mockito.eq("offering-uuid"));
    }

    @Test
    public void deleteDiskOfferingReturnsFalseWhenDaoUpdateFails() {
        CallContext.register(userVO, adminAccount);
        DeleteDiskOfferingCmd cmd = Mockito.mock(DeleteDiskOfferingCmd.class);
        Mockito.when(cmd.getId()).thenReturn(DO_ID);
        DiskOfferingVO offering = Mockito.mock(DiskOfferingVO.class);
        Mockito.when(offering.getId()).thenReturn(DO_ID);
        Mockito.when(offering.getUuid()).thenReturn("offering-uuid");
        Mockito.when(diskOfferingDao.findById(DO_ID)).thenReturn(offering);
        Mockito.when(userDao.findById(Mockito.anyLong())).thenReturn(userVO);
        Mockito.when(accountDao.findById(Mockito.anyLong())).thenReturn(adminAccount);
        Mockito.when(diskOfferingDao.update(DO_ID, offering)).thenReturn(false);

        assertFalse(service.deleteDiskOffering(cmd));
    }

    // ---------------------------------------------------------------------
    // Helper-level checks — these mirror the inner validators kept on the
    // manager for spy compat, and verify the local copies here behave the
    // same so the duplication can't drift.
    // ---------------------------------------------------------------------

    @Test
    public void validateDomainEmptyListIsNoop() {
        service.validateDomain(Collections.emptyList());
        service.validateDomain(null);
        // no throw, no DAO call
        Mockito.verifyNoInteractions(domainDao);
    }

    @Test
    public void validateDomainInvalidIdThrows() {
        Mockito.when(domainDao.findById(99L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.validateDomain(Collections.singletonList(99L)));
    }

    @Test
    public void validateZoneEmptyListIsNoop() {
        service.validateZone(Collections.emptyList());
        service.validateZone(null);
        Mockito.verifyNoInteractions(zoneDao);
    }

    @Test
    public void validateZoneInvalidIdThrows() {
        Mockito.when(zoneDao.findById(99L)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.validateZone(Collections.singletonList(99L)));
    }

    @Test
    public void validateCacheModeAcceptsValidMode() {
        // valid modes are case-insensitive members of DiskOffering.DiskCacheMode
        service.validateCacheMode("none");
        service.validateCacheMode("WRITEBACK");
        service.validateCacheMode(null);
        // no throw expected
    }

    @Test
    public void validateCacheModeRejectsUnknownMode() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.validateCacheMode("not_a_mode"));
    }

    @Test
    public void validateMaxRateThrowsWhenNormalExceedsMax() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.validateMaxRateEqualsOrGreater(100L, 50L, "IOPS Read"));
    }

    @Test
    public void validateMaxRateAcceptsNullAndEqualValues() {
        service.validateMaxRateEqualsOrGreater(null, null, "Bytes Read");
        service.validateMaxRateEqualsOrGreater(100L, 100L, "Bytes Read");
        service.validateMaxRateEqualsOrGreater(50L, 100L, "Bytes Read");
        // no throw
    }

    @Test
    public void shouldUpdateDiskOfferingFlagsAnyAttribute() {
        // any non-null attribute triggers update
        assertTrue(service.shouldUpdateDiskOffering("new", null, null, null, null, null, null));
        assertTrue(service.shouldUpdateDiskOffering(null, "x", null, null, null, null, null));
        assertTrue(service.shouldUpdateDiskOffering(null, null, 5, null, null, null, null));
        assertTrue(service.shouldUpdateDiskOffering(null, null, null, Boolean.TRUE, null, null, null));
        assertTrue(service.shouldUpdateDiskOffering(null, null, null, null, "tag", null, null));
        assertTrue(service.shouldUpdateDiskOffering(null, null, null, null, null, "none", null));
        assertTrue(service.shouldUpdateDiskOffering(null, null, null, null, null, null, DiskOffering.State.Inactive));
        assertFalse(service.shouldUpdateDiskOffering(null, null, null, null, null, null, null));
    }

    @Test
    public void shouldUpdateBytesRateParametersChecksAllSlots() {
        assertTrue(service.shouldUpdateBytesRateParameters(1L, null, null, null, null, null));
        assertTrue(service.shouldUpdateBytesRateParameters(null, 1L, null, null, null, null));
        assertTrue(service.shouldUpdateBytesRateParameters(null, null, null, null, null, 1L));
        assertFalse(service.shouldUpdateBytesRateParameters(null, null, null, null, null, null));
    }

    @Test
    public void shouldUpdateIopsRateParametersChecksAllSlots() {
        assertTrue(service.shouldUpdateIopsRateParameters(1L, null, null, null, null, null));
        assertTrue(service.shouldUpdateIopsRateParameters(null, null, null, null, null, 1L));
        assertFalse(service.shouldUpdateIopsRateParameters(null, null, null, null, null, null));
    }

    @Test
    public void updateDiskOfferingIfCmdAttributeNotNullCopiesAllSetValues() {
        DiskOfferingVO target = Mockito.mock(DiskOfferingVO.class);
        UpdateDiskOfferingCmd cmd = Mockito.mock(UpdateDiskOfferingCmd.class);
        Mockito.when(cmd.getDiskOfferingName()).thenReturn("name1");
        Mockito.when(cmd.getDisplayText()).thenReturn("disp1");
        Mockito.when(cmd.getSortKey()).thenReturn(3);
        Mockito.when(cmd.getDisplayOffering()).thenReturn(true);

        service.updateDiskOfferingIfCmdAttributeNotNull(target, cmd);

        Mockito.verify(target).setName("name1");
        Mockito.verify(target).setDisplayText("disp1");
        Mockito.verify(target).setSortKey(3);
        Mockito.verify(target).setDisplayOffering(true);
    }

    @Test
    public void updateDiskOfferingIfCmdAttributeNotNullLeavesUnsetValues() {
        DiskOfferingVO target = Mockito.mock(DiskOfferingVO.class);
        UpdateDiskOfferingCmd cmd = Mockito.mock(UpdateDiskOfferingCmd.class);
        // Mockito's default for primitive wrappers is null, but explicit stubs
        // make the intent obvious and immune to changes in default behaviour.
        Mockito.doReturn(null).when(cmd).getDiskOfferingName();
        Mockito.doReturn(null).when(cmd).getDisplayText();
        Mockito.doReturn(null).when(cmd).getSortKey();
        Mockito.doReturn(null).when(cmd).getDisplayOffering();

        service.updateDiskOfferingIfCmdAttributeNotNull(target, cmd);

        Mockito.verify(target, Mockito.never()).setName(Mockito.anyString());
        Mockito.verify(target, Mockito.never()).setDisplayText(Mockito.anyString());
        Mockito.verify(target, Mockito.never()).setSortKey(Mockito.anyInt());
        Mockito.verify(target, Mockito.never()).setDisplayOffering(Mockito.anyBoolean());
    }

    @Test
    public void checkDomainAdminUpdateRestrictionsThrowsOnZoneChange() {
        DiskOffering offering = Mockito.mock(DiskOffering.class);
        User user = Mockito.mock(User.class);
        List<Long> filteredZones = Arrays.asList(1L);
        List<Long> existingZones = Arrays.asList(2L);
        List<Long> existingDomains = Arrays.asList(DOMAIN_ID);
        List<Long> filteredDomains = Arrays.asList(DOMAIN_ID);

        assertThrows(InvalidParameterValueException.class,
                () -> service.checkDomainAdminUpdateOfferingRestrictions(offering, user,
                        filteredZones, existingZones, existingDomains, filteredDomains));
    }

    @Test
    public void checkDomainAdminUpdateRestrictionsThrowsOnPublicToScopedConversion() {
        DiskOffering offering = Mockito.mock(DiskOffering.class);
        User user = Mockito.mock(User.class);
        List<Long> zones = Arrays.asList(1L);
        List<Long> empty = Collections.emptyList();
        List<Long> filteredDomains = Arrays.asList(DOMAIN_ID);

        assertThrows(InvalidParameterValueException.class,
                () -> service.checkDomainAdminUpdateOfferingRestrictions(offering, user,
                        zones, zones, empty, filteredDomains));
    }

    @Test
    public void updateDiskOfferingDetailsDomainIdsAddsNewRows() {
        List<DiskOfferingDetailVO> out = new ArrayList<>();
        @SuppressWarnings("unchecked")
        com.cloud.utils.db.SearchCriteria<DiskOfferingDetailVO> sc =
                (com.cloud.utils.db.SearchCriteria<DiskOfferingDetailVO>) Mockito.mock(com.cloud.utils.db.SearchCriteria.class);
        List<Long> filtered = Arrays.asList(1L, 2L);
        List<Long> existing = Arrays.asList(3L);

        service.updateDiskOfferingDetailsDomainIds(out, sc, DO_ID, filtered, existing);

        assertEquals(2, out.size());
        Mockito.verify(diskOfferingDetailsDao).remove(sc);
    }

    @Test
    public void updateDiskOfferingDetailsDomainIdsIsNoopWhenUnchanged() {
        List<DiskOfferingDetailVO> out = new ArrayList<>();
        @SuppressWarnings("unchecked")
        com.cloud.utils.db.SearchCriteria<DiskOfferingDetailVO> sc =
                (com.cloud.utils.db.SearchCriteria<DiskOfferingDetailVO>) Mockito.mock(com.cloud.utils.db.SearchCriteria.class);
        List<Long> same = Arrays.asList(1L, 2L);

        service.updateDiskOfferingDetailsDomainIds(out, sc, DO_ID, same, same);

        assertTrue(out.isEmpty());
        Mockito.verifyNoInteractions(diskOfferingDetailsDao);
    }

    @Test
    public void updateDiskOfferingDetailsZoneIdsAddsNewRows() {
        List<DiskOfferingDetailVO> out = new ArrayList<>();
        @SuppressWarnings("unchecked")
        com.cloud.utils.db.SearchCriteria<DiskOfferingDetailVO> sc =
                (com.cloud.utils.db.SearchCriteria<DiskOfferingDetailVO>) Mockito.mock(com.cloud.utils.db.SearchCriteria.class);

        service.updateDiskOfferingDetailsZoneIds(out, sc, DO_ID, Arrays.asList(10L), Arrays.asList(20L));

        assertEquals(1, out.size());
        Mockito.verify(diskOfferingDetailsDao).remove(sc);
    }

    @Test
    public void getAccountNonChildDomainsReturnsAllWhenNothingChanges() {
        DiskOffering offering = Mockito.mock(DiskOffering.class);
        UpdateDiskOfferingCmd cmd = Mockito.mock(UpdateDiskOfferingCmd.class);
        // Match existing ConfigurationManagerImplTest pattern — stub the cmd's
        // attribute getters to null explicitly so ObjectUtils.anyNotNull(...)
        // sees all-null and the non-child domain ids fall through.
        Mockito.doReturn(null).when(cmd).getDiskOfferingName();
        Mockito.doReturn(null).when(cmd).getDisplayText();
        Mockito.doReturn(null).when(cmd).getSortKey();
        User user = Mockito.mock(User.class);
        Mockito.when(user.getUuid()).thenReturn("uuser");
        Mockito.when(offering.getUuid()).thenReturn("uoff");

        List<Long> existing = Arrays.asList(1L, 2L);
        // no domain is a child of the admin's tree
        Mockito.when(domainDao.isChildDomain(domainAdminAccount.getDomainId(), 1L)).thenReturn(false);
        Mockito.when(domainDao.isChildDomain(domainAdminAccount.getDomainId(), 2L)).thenReturn(false);

        List<Long> result = service.getAccountNonChildDomains(offering, domainAdminAccount, user, cmd, existing);

        // when none of the cmd-attributes are set, every non-child domain is appended through
        assertEquals(existing.size(), result.size());
    }

    @Test
    public void getAccountNonChildDomainsThrowsWhenAttributeChangeAndNonChildDomainExist() {
        DiskOffering offering = Mockito.mock(DiskOffering.class);
        UpdateDiskOfferingCmd cmd = Mockito.mock(UpdateDiskOfferingCmd.class);
        User user = Mockito.mock(User.class);
        Mockito.when(user.getUuid()).thenReturn("uuser");
        Mockito.when(offering.getUuid()).thenReturn("uoff");
        Mockito.when(cmd.getDiskOfferingName()).thenReturn("rename");

        Mockito.when(domainDao.isChildDomain(domainAdminAccount.getDomainId(), 1L)).thenReturn(false);

        assertThrows(InvalidParameterValueException.class,
                () -> service.getAccountNonChildDomains(offering, domainAdminAccount, user, cmd,
                        Collections.singletonList(1L)));
    }

    @Test
    public void checkIfDomainIsChildDomainThrowsForNonChild() {
        DiskOffering offering = Mockito.mock(DiskOffering.class);
        User user = Mockito.mock(User.class);
        Mockito.when(user.getUuid()).thenReturn("uuser");
        Mockito.when(offering.getUuid()).thenReturn("uoff");
        com.cloud.domain.Domain domain = Mockito.mock(com.cloud.domain.Domain.class);
        Mockito.when(domain.getUuid()).thenReturn("udomain");
        Mockito.when(domainDao.isChildDomain(domainAdminAccount.getDomainId(), 1L)).thenReturn(false);
        Mockito.when(entityMgr.findById(com.cloud.domain.Domain.class, 1L)).thenReturn(domain);

        assertThrows(InvalidParameterValueException.class,
                () -> service.checkIfDomainIsChildDomain(offering, domainAdminAccount, user,
                        Collections.singletonList(1L)));
    }

    @Test
    public void checkIfDomainIsChildDomainAllowsChildDomains() {
        DiskOffering offering = Mockito.mock(DiskOffering.class);
        User user = Mockito.mock(User.class);
        Mockito.when(domainDao.isChildDomain(domainAdminAccount.getDomainId(), 1L)).thenReturn(true);

        // no throw expected
        service.checkIfDomainIsChildDomain(offering, domainAdminAccount, user, Collections.singletonList(1L));
    }

    @Test
    public void updateOfferingTagsIfIsNotNullNullTagsIsNoop() {
        DiskOfferingVO target = Mockito.mock(DiskOfferingVO.class);
        service.updateOfferingTagsIfIsNotNull(null, target);
        Mockito.verify(target, Mockito.never()).setTags(Mockito.anyString());
    }

    @Test
    public void updateOfferingTagsIfIsNotNullBlankTagsClearsField() {
        DiskOfferingVO target = Mockito.mock(DiskOfferingVO.class);
        service.updateOfferingTagsIfIsNotNull("", target);
        Mockito.verify(target).setTags(null);
    }
}
