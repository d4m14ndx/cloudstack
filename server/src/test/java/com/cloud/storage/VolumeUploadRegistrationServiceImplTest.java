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
package com.cloud.storage;

import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.UUID;

import org.apache.cloudstack.api.command.user.volume.UploadVolumeCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.ApiDBUtils;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.DiskOffering;
import com.cloud.org.Grouping;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.TemplateManager;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Focused unit tests for {@link VolumeUploadRegistrationServiceImpl}.
 * Business logic extracted from {@link VolumeApiServiceImpl} as part of Phase 4
 * Spring-component decomposition (slice 10).
 */
@RunWith(MockitoJUnitRunner.class)
public class VolumeUploadRegistrationServiceImplTest {

    @Spy
    @InjectMocks
    private VolumeUploadRegistrationServiceImpl service = new VolumeUploadRegistrationServiceImpl();

    @Mock
    private VolumeDataFactory volFactory;
    @Mock
    private VolumeService volService;
    @Mock
    private TemplateManager tmpltMgr;
    @Mock
    private AccountManager accountMgr;
    @Mock
    private EntityManager entityMgr;
    @Mock
    private AccountDao accountDao;
    @Mock
    private DiskOfferingDao diskOfferingDao;
    @Mock
    private ConfigurationDao configDao;
    @Mock
    private ConfigurationManager configMgr;
    @Mock
    private DataCenterDao dcDao;
    @Mock
    private ResourceLimitService resourceLimitMgr;
    @Mock
    private ResourceManager resourceMgr;
    @Mock
    private VolumeDao volsDao;
    @Mock
    private VolumeDataStoreDao volumeStoreDao;
    @Mock
    private DomainDao domainDao;
    @Mock
    private DiskOfferingCompatibilityService diskOfferingCompatibilityService;

    @Mock
    private Account callerMock;
    @Mock
    private Account ownerMock;
    @Mock
    private DataCenterVO zoneMock;
    @Mock
    private DiskOfferingVO diskOfferingMock;

    private static final Long ZONE_ID = 1L;
    private static final Long OWNER_ID = 2L;
    private static final Long DISK_OFFERING_ID = 100L;

    @Before
    public void setUp() {
        AccountVO account = new AccountVO("admin", 1L, "domain", Account.Type.NORMAL, UUID.randomUUID().toString());
        UserVO user = new UserVO(1, "testuser", "password", "first", "last", "email", "tz",
                UUID.randomUUID().toString(), User.Source.UNKNOWN);
        CallContext.register(user, account);

        lenient().when(callerMock.getId()).thenReturn(1L);
        lenient().when(ownerMock.getAccountId()).thenReturn(OWNER_ID);
        lenient().when(ownerMock.getDomainId()).thenReturn(1L);
        lenient().when(zoneMock.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);
        lenient().when(zoneMock.getUuid()).thenReturn("zone-uuid");
        lenient().when(dcDao.findById(ZONE_ID)).thenReturn(zoneMock);
    }

    @After
    public void tearDown() {
        CallContext.unregisterAll();
    }

    // -------------------------------------------------------------------------
    // 1. sanitizeFormat — blank format
    // -------------------------------------------------------------------------
    @Test
    public void testUploadVolume_url_blankFormat_throwsCloudRuntimeException() {
        UploadVolumeCmd cmd = mock(UploadVolumeCmd.class);
        when(cmd.getFormat()).thenReturn("");
        when(cmd.getEntityOwnerId()).thenReturn(OWNER_ID);
        when(entityMgr.findById(Account.class, OWNER_ID)).thenReturn(ownerMock);

        assertThrows(CloudRuntimeException.class, () -> service.uploadVolume(cmd));
    }

    // -------------------------------------------------------------------------
    // 2. sanitizeFormat — invalid format
    // -------------------------------------------------------------------------
    @Test
    public void testUploadVolume_url_invalidFormat_throwsIllegalArgumentException() {
        UploadVolumeCmd cmd = mock(UploadVolumeCmd.class);
        when(cmd.getFormat()).thenReturn("BADFORMAT");
        when(cmd.getEntityOwnerId()).thenReturn(OWNER_ID);
        when(entityMgr.findById(Account.class, OWNER_ID)).thenReturn(ownerMock);

        assertThrows(IllegalArgumentException.class, () -> service.uploadVolume(cmd));
    }

    // -------------------------------------------------------------------------
    // 3. validateVolume — unknown zone
    // -------------------------------------------------------------------------
    @Test
    public void testUploadVolume_url_unknownZone_throwsInvalidParameterValueException() throws ResourceAllocationException {
        UploadVolumeCmd cmd = buildMinimalUploadCmd(ZONE_ID);
        when(dcDao.findById(ZONE_ID)).thenReturn(null);
        when(accountMgr.getActiveAccountById(OWNER_ID)).thenReturn(ownerMock);
        doNothing().when(accountMgr).checkAccess(any(), any(), anyBoolean(), any());
        doNothing().when(resourceLimitMgr).checkVolumeResourceLimit(any(), anyBoolean(), any(), any(), any());

        assertThrows(InvalidParameterValueException.class, () -> service.uploadVolume(cmd));
    }

    // -------------------------------------------------------------------------
    // 4. validateVolume — zone disabled, non-admin caller
    // -------------------------------------------------------------------------
    @Test
    public void testUploadVolume_url_zoneDisabledNonAdmin_throwsPermissionDeniedException() throws ResourceAllocationException {
        UploadVolumeCmd cmd = buildMinimalUploadCmd(ZONE_ID);
        when(zoneMock.getAllocationState()).thenReturn(Grouping.AllocationState.Disabled);
        when(accountMgr.getActiveAccountById(OWNER_ID)).thenReturn(ownerMock);
        doNothing().when(accountMgr).checkAccess(any(), any(), anyBoolean(), any());
        doNothing().when(resourceLimitMgr).checkVolumeResourceLimit(any(), anyBoolean(), any(), any(), any());
        lenient().when(accountMgr.isRootAdmin(anyLong())).thenReturn(false);

        assertThrows(PermissionDeniedException.class, () -> service.uploadVolume(cmd));
    }

    // -------------------------------------------------------------------------
    // 5. validateVolume — file:// URL rejected
    // -------------------------------------------------------------------------
    @Test
    public void testUploadVolume_url_fileSchemeUrl_throwsInvalidParameterValueException() throws ResourceAllocationException {
        UploadVolumeCmd cmd = buildMinimalUploadCmd(ZONE_ID);
        when(cmd.getUrl()).thenReturn("file:///some/path/disk.qcow2");
        when(accountMgr.getActiveAccountById(OWNER_ID)).thenReturn(ownerMock);
        doNothing().when(accountMgr).checkAccess(any(), any(), anyBoolean(), any());
        doNothing().when(resourceLimitMgr).checkVolumeResourceLimit(any(), anyBoolean(), any(), any(), any());

        assertThrows(InvalidParameterValueException.class, () -> service.uploadVolume(cmd));
    }

    // -------------------------------------------------------------------------
    // 6. validateVolume — explicit disk offering is non-custom
    // -------------------------------------------------------------------------
    @Test
    public void testUploadVolume_url_diskOfferingNotCustom_throwsInvalidParameterValueException() throws ResourceAllocationException {
        UploadVolumeCmd cmd = buildMinimalUploadCmdWithOffering(ZONE_ID, DISK_OFFERING_ID);
        when(diskOfferingDao.findById(DISK_OFFERING_ID)).thenReturn(diskOfferingMock);
        when(diskOfferingMock.getRemoved()).thenReturn(null);
        when(diskOfferingMock.isComputeOnly()).thenReturn(false);
        when(diskOfferingMock.isCustomized()).thenReturn(false);
        when(accountMgr.getActiveAccountById(OWNER_ID)).thenReturn(ownerMock);
        doNothing().when(accountMgr).checkAccess(any(), any(), anyBoolean(), any());
        doNothing().when(resourceLimitMgr).checkVolumeResourceLimit(any(), anyBoolean(), any(), any(), any());
        doNothing().when(resourceLimitMgr).checkResourceLimit(any(), any());

        // Use null URL so the URL-check branch is skipped; the disk offering check fires last
        when(cmd.getUrl()).thenReturn(null);

        // Stub zone hypervisor check to pass
        try (MockedStatic<ApiDBUtils> staticApiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            staticApiDbUtils.when(() -> ApiDBUtils.getHypervisorTypeFromFormat(anyLong(), any(ImageFormat.class)))
                    .thenReturn(HypervisorType.KVM);
            when(resourceMgr.getSupportedHypervisorTypes(anyLong(), anyBoolean(), any()))
                    .thenReturn(Collections.singletonList(HypervisorType.KVM));

            assertThrows(InvalidParameterValueException.class, () -> service.uploadVolume(cmd));
        }
    }

    // -------------------------------------------------------------------------
    // 7. validateVolume — explicit disk offering is removed / compute-only
    // -------------------------------------------------------------------------
    @Test
    public void testUploadVolume_url_diskOfferingRemoved_throwsInvalidParameterValueException() throws ResourceAllocationException {
        UploadVolumeCmd cmd = buildMinimalUploadCmdWithOffering(ZONE_ID, DISK_OFFERING_ID);
        when(cmd.getUrl()).thenReturn(null);
        when(diskOfferingDao.findById(DISK_OFFERING_ID)).thenReturn(diskOfferingMock);
        when(diskOfferingMock.getRemoved()).thenReturn(new java.util.Date());
        when(accountMgr.getActiveAccountById(OWNER_ID)).thenReturn(ownerMock);
        doNothing().when(accountMgr).checkAccess(any(), any(), anyBoolean(), any());
        doNothing().when(resourceLimitMgr).checkVolumeResourceLimit(any(), anyBoolean(), any(), any(), any());
        doNothing().when(resourceLimitMgr).checkResourceLimit(any(), any());

        try (MockedStatic<ApiDBUtils> staticApiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            staticApiDbUtils.when(() -> ApiDBUtils.getHypervisorTypeFromFormat(anyLong(), any(ImageFormat.class)))
                    .thenReturn(HypervisorType.KVM);
            when(resourceMgr.getSupportedHypervisorTypes(anyLong(), anyBoolean(), any()))
                    .thenReturn(Collections.singletonList(HypervisorType.KVM));

            assertThrows(InvalidParameterValueException.class, () -> service.uploadVolume(cmd));
        }
    }

    // -------------------------------------------------------------------------
    // 8. checkFormatWithSupportedHypervisorsInZone — unsupported hypervisor
    // -------------------------------------------------------------------------
    @Test
    public void testCheckFormat_unsupportedHypervisor_throwsInvalidParameterValueException() throws ResourceAllocationException {
        UploadVolumeCmd cmd = buildMinimalUploadCmd(ZONE_ID);
        when(cmd.getUrl()).thenReturn(null);
        when(accountMgr.getActiveAccountById(OWNER_ID)).thenReturn(ownerMock);
        doNothing().when(accountMgr).checkAccess(any(), any(), anyBoolean(), any());
        doNothing().when(resourceLimitMgr).checkVolumeResourceLimit(any(), anyBoolean(), any(), any(), any());
        doNothing().when(resourceLimitMgr).checkResourceLimit(any(), any());

        try (MockedStatic<ApiDBUtils> staticApiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            // Zone supports VMware but format maps to XenServer — mismatch
            staticApiDbUtils.when(() -> ApiDBUtils.getHypervisorTypeFromFormat(anyLong(), any(ImageFormat.class)))
                    .thenReturn(HypervisorType.XenServer);
            when(resourceMgr.getSupportedHypervisorTypes(anyLong(), anyBoolean(), any()))
                    .thenReturn(Collections.singletonList(HypervisorType.VMware));

            assertThrows(InvalidParameterValueException.class, () -> service.uploadVolume(cmd));
        }
    }

    // -------------------------------------------------------------------------
    // 9. getRandomVolumeName — basic contract
    // -------------------------------------------------------------------------
    @Test
    public void testGetRandomVolumeName_returnsNonNull() {
        String name = service.getRandomVolumeName();
        assertNotNull(name);
    }

    @Test
    public void testGetRandomVolumeName_returnsDifferentValueOnConsecutiveCalls() {
        String first = service.getRandomVolumeName();
        String second = service.getRandomVolumeName();
        assertNotEquals("Each call should produce a unique UUID", first, second);
    }

    // -------------------------------------------------------------------------
    // 10. persistVolume — no disk offering supplied, default custom found
    // -------------------------------------------------------------------------
    @Test
    public void testPersistVolume_noDiskOfferingId_picksDefaultCustomOffering() {
        DiskOfferingVO customOffering = mock(DiskOfferingVO.class);
        when(customOffering.getId()).thenReturn(42L);
        when(customOffering.getState()).thenReturn(DiskOffering.State.Active);
        when(customOffering.isCustomizedIops()).thenReturn(false);
        when(customOffering.getMinIops()).thenReturn(0L);
        when(customOffering.getMaxIops()).thenReturn(0L);
        when(diskOfferingDao.findByUniqueName("Cloud.com-Custom")).thenReturn(customOffering);
        when(diskOfferingDao.findById(42L)).thenReturn(customOffering);
        doNothing().when(configMgr).checkDiskOfferingAccess(any(), any(), any());

        VolumeVO persisted = mock(VolumeVO.class);
        when(persisted.getAccountId()).thenReturn(OWNER_ID);
        when(persisted.getUuid()).thenReturn("vol-uuid");
        when(volsDao.persist(any(VolumeVO.class))).thenReturn(persisted);
        doNothing().when(resourceLimitMgr).incrementVolumeResourceCount(anyLong(), anyBoolean(), any(), any());

        // url=null so the remoteSize branch in persistVolume is skipped
        VolumeVO result = service.persistVolume(ownerMock, ZONE_ID, "test-vol", null, "QCOW2", null, Volume.State.NotUploaded);
        assertNotNull(result);
        verify(diskOfferingDao).findByUniqueName("Cloud.com-Custom");
    }

    // -------------------------------------------------------------------------
    // 11. persistVolume — default custom not found, falls back to list
    // -------------------------------------------------------------------------
    @Test
    public void testPersistVolume_noDiskOfferingId_fallsBackToFirstAccessibleCustomOffering() {
        DiskOfferingVO fallbackOffering = mock(DiskOfferingVO.class);
        when(fallbackOffering.getId()).thenReturn(55L);
        when(fallbackOffering.isCustomizedIops()).thenReturn(false);
        when(fallbackOffering.getMinIops()).thenReturn(0L);
        when(fallbackOffering.getMaxIops()).thenReturn(0L);
        when(diskOfferingDao.findByUniqueName("Cloud.com-Custom")).thenReturn(null);
        when(diskOfferingDao.findCustomDiskOfferings()).thenReturn(Collections.singletonList(fallbackOffering));
        when(diskOfferingDao.findById(55L)).thenReturn(fallbackOffering);
        doNothing().when(configMgr).checkDiskOfferingAccess(any(), any(), any());

        VolumeVO persisted = mock(VolumeVO.class);
        when(persisted.getAccountId()).thenReturn(OWNER_ID);
        when(persisted.getUuid()).thenReturn("vol-uuid-fallback");
        when(volsDao.persist(any(VolumeVO.class))).thenReturn(persisted);
        doNothing().when(resourceLimitMgr).incrementVolumeResourceCount(anyLong(), anyBoolean(), any(), any());

        VolumeVO result = service.persistVolume(ownerMock, ZONE_ID, "test-vol-fallback", null, "QCOW2", null, Volume.State.NotUploaded);
        assertNotNull(result);
        verify(diskOfferingDao).findCustomDiskOfferings();
    }

    // -------------------------------------------------------------------------
    // 12. persistVolume — no accessible custom offering at all → CloudRuntimeException
    // -------------------------------------------------------------------------
    @Test
    public void testPersistVolume_noDiskOfferingId_noneAccessible_throwsCloudRuntimeException() {
        when(diskOfferingDao.findByUniqueName("Cloud.com-Custom")).thenReturn(null);
        when(diskOfferingDao.findCustomDiskOfferings()).thenReturn(Collections.emptyList());

        assertThrows(CloudRuntimeException.class,
                () -> service.persistVolume(ownerMock, ZONE_ID, "vol", null, "QCOW2", null, Volume.State.NotUploaded));
    }

    // -------------------------------------------------------------------------
    // 13. persistVolume — explicit disk offering supplied
    // -------------------------------------------------------------------------
    @Test
    public void testPersistVolume_withExplicitDiskOfferingId_usesThatOffering() {
        when(diskOfferingDao.findById(DISK_OFFERING_ID)).thenReturn(diskOfferingMock);
        when(diskOfferingMock.isCustomizedIops()).thenReturn(false);
        when(diskOfferingMock.getMinIops()).thenReturn(0L);
        when(diskOfferingMock.getMaxIops()).thenReturn(0L);

        VolumeVO persisted = mock(VolumeVO.class);
        when(persisted.getAccountId()).thenReturn(OWNER_ID);
        when(persisted.getUuid()).thenReturn("vol-explicit");
        when(volsDao.persist(any(VolumeVO.class))).thenReturn(persisted);
        doNothing().when(resourceLimitMgr).incrementVolumeResourceCount(anyLong(), anyBoolean(), any(), any());

        VolumeVO result = service.persistVolume(ownerMock, ZONE_ID, "explicit-vol", null, "QCOW2", DISK_OFFERING_ID, Volume.State.Allocated);
        assertNotNull(result);
        // Should NOT look up the default custom offering since one was supplied
        verify(diskOfferingDao, Mockito.never()).findByUniqueName(any());
    }

    // -------------------------------------------------------------------------
    // 14. persistVolume — default custom offering is not Active → skipped
    // -------------------------------------------------------------------------
    @Test
    public void testPersistVolume_defaultCustomOfferingInactive_fallsBackToList() {
        DiskOfferingVO inactiveDefault = mock(DiskOfferingVO.class);
        when(inactiveDefault.getState()).thenReturn(DiskOffering.State.Inactive);
        when(diskOfferingDao.findByUniqueName("Cloud.com-Custom")).thenReturn(inactiveDefault);

        DiskOfferingVO activeOffering = mock(DiskOfferingVO.class);
        when(activeOffering.getId()).thenReturn(77L);
        when(activeOffering.isCustomizedIops()).thenReturn(false);
        when(activeOffering.getMinIops()).thenReturn(0L);
        when(activeOffering.getMaxIops()).thenReturn(0L);
        when(diskOfferingDao.findCustomDiskOfferings()).thenReturn(Collections.singletonList(activeOffering));
        when(diskOfferingDao.findById(77L)).thenReturn(activeOffering);
        doNothing().when(configMgr).checkDiskOfferingAccess(any(), any(), any());

        VolumeVO persisted = mock(VolumeVO.class);
        when(persisted.getAccountId()).thenReturn(OWNER_ID);
        when(persisted.getUuid()).thenReturn("vol-active-fallback");
        when(volsDao.persist(any(VolumeVO.class))).thenReturn(persisted);
        doNothing().when(resourceLimitMgr).incrementVolumeResourceCount(anyLong(), anyBoolean(), any(), any());

        VolumeVO result = service.persistVolume(ownerMock, ZONE_ID, "vol-inactive-default", null, "QCOW2", null, Volume.State.NotUploaded);
        assertNotNull(result);
        verify(diskOfferingDao).findCustomDiskOfferings();
    }

    // -------------------------------------------------------------------------
    // 15. persistVolume — default custom offering denied by permission → list
    // -------------------------------------------------------------------------
    @Test
    public void testPersistVolume_defaultCustomOfferingPermissionDenied_fallsBackToList() {
        DiskOfferingVO defaultOffering = mock(DiskOfferingVO.class);
        when(defaultOffering.getState()).thenReturn(DiskOffering.State.Active);
        when(diskOfferingDao.findByUniqueName("Cloud.com-Custom")).thenReturn(defaultOffering);
        doThrow(new PermissionDeniedException("denied")).when(configMgr).checkDiskOfferingAccess(any(), eq(defaultOffering), any());

        DiskOfferingVO accessible = mock(DiskOfferingVO.class);
        when(accessible.getId()).thenReturn(88L);
        when(accessible.isCustomizedIops()).thenReturn(false);
        when(accessible.getMinIops()).thenReturn(0L);
        when(accessible.getMaxIops()).thenReturn(0L);
        when(diskOfferingDao.findCustomDiskOfferings()).thenReturn(Collections.singletonList(accessible));
        when(diskOfferingDao.findById(88L)).thenReturn(accessible);
        doNothing().when(configMgr).checkDiskOfferingAccess(any(), eq(accessible), any());

        VolumeVO persisted = mock(VolumeVO.class);
        when(persisted.getAccountId()).thenReturn(OWNER_ID);
        when(persisted.getUuid()).thenReturn("vol-perm-denied");
        when(volsDao.persist(any(VolumeVO.class))).thenReturn(persisted);
        doNothing().when(resourceLimitMgr).incrementVolumeResourceCount(anyLong(), anyBoolean(), any(), any());

        VolumeVO result = service.persistVolume(ownerMock, ZONE_ID, "vol-perm-denied", null, "QCOW2", null, Volume.State.NotUploaded);
        assertNotNull(result);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private UploadVolumeCmd buildMinimalUploadCmd(Long zoneId) {
        UploadVolumeCmd cmd = mock(UploadVolumeCmd.class);
        when(cmd.getFormat()).thenReturn("QCOW2");
        when(cmd.getZoneId()).thenReturn(zoneId);
        when(cmd.getEntityOwnerId()).thenReturn(OWNER_ID);
        when(entityMgr.findById(Account.class, OWNER_ID)).thenReturn(ownerMock);
        lenient().when(cmd.getUrl()).thenReturn("http://example.com/disk.qcow2");
        lenient().when(cmd.getVolumeName()).thenReturn("test-volume");
        lenient().when(cmd.getDiskOfferingId()).thenReturn(null);
        lenient().when(cmd.getImageStoreUuid()).thenReturn(null);
        // CallContext is already registered in setUp() — set callerMock as calling account
        return cmd;
    }

    private UploadVolumeCmd buildMinimalUploadCmdWithOffering(Long zoneId, Long diskOfferingId) {
        UploadVolumeCmd cmd = buildMinimalUploadCmd(zoneId);
        lenient().when(cmd.getDiskOfferingId()).thenReturn(diskOfferingId);
        return cmd;
    }
}
