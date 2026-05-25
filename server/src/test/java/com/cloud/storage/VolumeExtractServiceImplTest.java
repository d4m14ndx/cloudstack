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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.Optional;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO;
import org.apache.cloudstack.storage.image.datastore.ImageStoreEntity;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.ApiDBUtils;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Focused unit tests for {@link VolumeExtractServiceImpl}.
 *
 * <p>The extracted slice owns three responsibilities:
 * <ul>
 *   <li>{@link VolumeExtractServiceImpl#validateExtractRequest} — preflight
 *       checks on the volume / zone / mode / caller (covered below by
 *       around a dozen cases that walk every failure branch);</li>
 *   <li>{@link VolumeExtractServiceImpl#findOrRegenerateExistingExtractUrl} —
 *       reusing an existing extract URL or minting one from the install
 *       path; and</li>
 *   <li>{@link VolumeExtractServiceImpl#orchestrateExtractVolume} — the
 *       primary-to-secondary copy / persist path.</li>
 * </ul>
 *
 * <p>These tests run against the extracted component directly so the
 * focused behaviour is exercised even when the orchestration in
 * {@link VolumeApiServiceImpl#extractVolume} hasn't been instantiated
 * with a real CallContext.</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class VolumeExtractServiceImplTest {

    @Mock
    private VolumeDao volumeDao;
    @Mock
    private VolumeDataStoreDao volumeStoreDao;
    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private DataCenterDao dataCenterDao;
    @Mock
    private ConfigurationDao configDao;
    @Mock
    private VolumeDataFactory volFactory;
    @Mock
    private VolumeService volService;
    @Mock
    private DataStoreManager dataStoreMgr;
    @Mock
    private AccountManager accountManager;

    @Mock
    private VolumeVO volume;
    @Mock
    private Account caller;
    @Mock
    private StoragePoolVO poolVO;
    @Mock
    private DataCenterVO dataCenter;
    @Mock
    private VMInstanceVO vmInstance;
    @Mock
    private VolumeDataStoreVO volumeStoreRef;

    @InjectMocks
    private VolumeExtractServiceImpl service;

    private static final long VOLUME_ID = 100L;
    private static final long ZONE_ID = 200L;
    private static final long POOL_ID = 300L;
    private static final long ACCOUNT_ID = 7L;
    private static final long INSTANCE_ID = 999L;
    private static final String MODE = "HTTP_DOWNLOAD";

    @Before
    public void setUp() {
        Mockito.lenient().when(caller.getId()).thenReturn(ACCOUNT_ID);
        Mockito.lenient().when(volume.getId()).thenReturn(VOLUME_ID);
        Mockito.lenient().when(volume.getPoolId()).thenReturn(POOL_ID);
        Mockito.lenient().when(volume.getUuid()).thenReturn("volume-uuid");
        Mockito.lenient().when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        Mockito.lenient().when(volume.getInstanceId()).thenReturn(null);
        Mockito.lenient().when(volume.getPassphraseId()).thenReturn(null);
        Mockito.lenient().when(volume.getTemplateId()).thenReturn(null);
    }

    // ------------------------------------------------------------------
    // validateExtractRequest
    // ------------------------------------------------------------------

    @Test(expected = PermissionDeniedException.class)
    public void validateRejectsNonAdminWhenExtractionDisabled() {
        Mockito.when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(false);
        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDbUtils.when(ApiDBUtils::isExtractionDisabled).thenReturn(true);
            service.validateExtractRequest(VOLUME_ID, ZONE_ID, MODE, caller);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateRejectsUnknownVolume() {
        Mockito.when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(true);
        Mockito.when(volumeDao.findById(VOLUME_ID)).thenReturn(null);
        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            service.validateExtractRequest(VOLUME_ID, ZONE_ID, MODE, caller);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateRejectsUnknownZone() {
        Mockito.when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(true);
        Mockito.when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(null);
        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            service.validateExtractRequest(VOLUME_ID, ZONE_ID, MODE, caller);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateRejectsVolumeWithoutPool() {
        Mockito.when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(true);
        Mockito.when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(dataCenter);
        Mockito.when(volume.getPoolId()).thenReturn(null);
        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            service.validateExtractRequest(VOLUME_ID, ZONE_ID, MODE, caller);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateRejectsPowerFlexPool() {
        Mockito.when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(true);
        Mockito.when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(dataCenter);
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(poolVO);
        Mockito.when(poolVO.getPoolType()).thenReturn(Storage.StoragePoolType.PowerFlex);
        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            service.validateExtractRequest(VOLUME_ID, ZONE_ID, MODE, caller);
        }
    }

    @Test(expected = PermissionDeniedException.class)
    public void validateRejectsVolumeAttachedToRunningVm() {
        Mockito.when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(true);
        Mockito.when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(dataCenter);
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(poolVO);
        Mockito.when(poolVO.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        Mockito.when(volume.getInstanceId()).thenReturn(INSTANCE_ID);
        Mockito.when(vmInstance.getState()).thenReturn(State.Running);
        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDbUtils.when(() -> ApiDBUtils.findVMInstanceById(INSTANCE_ID)).thenReturn(vmInstance);
            service.validateExtractRequest(VOLUME_ID, ZONE_ID, MODE, caller);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateRejectsEncryptedVolume() {
        Mockito.when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(true);
        Mockito.when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(dataCenter);
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(poolVO);
        Mockito.when(poolVO.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        Mockito.when(volume.getPassphraseId()).thenReturn(42L);
        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            service.validateExtractRequest(VOLUME_ID, ZONE_ID, MODE, caller);
        }
    }

    @Test(expected = PermissionDeniedException.class)
    public void validateRejectsNonAdminExtractingNonExtractableTemplateForRootDisk() {
        Mockito.when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(false);
        Mockito.when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(dataCenter);
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(poolVO);
        Mockito.when(poolVO.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Mockito.when(volume.getTemplateId()).thenReturn(55L);
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        Mockito.when(template.isExtractable()).thenReturn(false);
        // getTemplateType() is not consulted thanks to && short-circuit when
        // isExtractable() already returns false, so leave it un-stubbed.
        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDbUtils.when(ApiDBUtils::isExtractionDisabled).thenReturn(false);
            apiDbUtils.when(() -> ApiDBUtils.findTemplateById(55L)).thenReturn(template);
            service.validateExtractRequest(VOLUME_ID, ZONE_ID, MODE, caller);
        }
    }

    @Test
    public void validateAllowsRootAdminExtractingNonExtractableTemplateForRootDisk() {
        Mockito.when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(true);
        Mockito.when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(dataCenter);
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(poolVO);
        Mockito.when(poolVO.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Mockito.when(volume.getTemplateId()).thenReturn(55L);
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        // template is non-extractable; admin must still be allowed
        Mockito.lenient().when(template.isExtractable()).thenReturn(false);
        Mockito.lenient().when(template.getTemplateType()).thenReturn(Storage.TemplateType.USER);
        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            apiDbUtils.when(() -> ApiDBUtils.findTemplateById(55L)).thenReturn(template);
            VolumeVO returned = service.validateExtractRequest(VOLUME_ID, ZONE_ID, MODE, caller);
            assertSame(volume, returned);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateRejectsMissingMode() {
        Mockito.when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(true);
        Mockito.when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(dataCenter);
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(poolVO);
        Mockito.when(poolVO.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            service.validateExtractRequest(VOLUME_ID, ZONE_ID, null, caller);
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateRejectsUnknownMode() {
        Mockito.when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(true);
        Mockito.when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(dataCenter);
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(poolVO);
        Mockito.when(poolVO.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            service.validateExtractRequest(VOLUME_ID, ZONE_ID, "SCP", caller);
        }
    }

    @Test
    public void validateAcceptsHappyPathForDataDiskHttpDownload() {
        Mockito.when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(true);
        Mockito.when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(dataCenter);
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(poolVO);
        Mockito.when(poolVO.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        // DATADISK without template -- the extractability check is skipped
        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            VolumeVO returned = service.validateExtractRequest(VOLUME_ID, ZONE_ID, MODE, caller);
            assertSame(volume, returned);
        }
    }

    @Test
    public void validateAcceptsFtpUpload() {
        Mockito.when(accountManager.isRootAdmin(ACCOUNT_ID)).thenReturn(true);
        Mockito.when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(dataCenterDao.findById(ZONE_ID)).thenReturn(dataCenter);
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(poolVO);
        Mockito.when(poolVO.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        try (MockedStatic<ApiDBUtils> apiDbUtils = Mockito.mockStatic(ApiDBUtils.class)) {
            VolumeVO returned = service.validateExtractRequest(VOLUME_ID, ZONE_ID, "FTP_UPLOAD", caller);
            assertSame(volume, returned);
        }
    }

    // ------------------------------------------------------------------
    // findOrRegenerateExistingExtractUrl
    // ------------------------------------------------------------------

    @Test
    public void findOrRegenerateReturnsEmptyWhenNoSecondaryStorageEntry() {
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        Mockito.when(volume.getInstanceId()).thenReturn(null);
        SearchCriteria<VolumeDataStoreVO> sc = mockSearchCriteria();
        Mockito.when(volumeStoreDao.search(ArgumentMatchers.eq(sc), ArgumentMatchers.any()))
                .thenReturn(Collections.emptyList());
        Optional<String> result = service.findOrRegenerateExistingExtractUrl(sc, volume);
        assertFalse(result.isPresent());
    }

    @Test
    public void findOrRegenerateReturnsCachedExtractUrl() {
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        Mockito.when(volume.getInstanceId()).thenReturn(null);
        SearchCriteria<VolumeDataStoreVO> sc = mockSearchCriteria();
        Mockito.when(volumeStoreRef.getExtractUrl()).thenReturn("http://cached/url");
        Mockito.when(volumeStoreDao.search(ArgumentMatchers.eq(sc), ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(volumeStoreRef));
        Optional<String> result = service.findOrRegenerateExistingExtractUrl(sc, volume);
        assertTrue(result.isPresent());
        assertEquals("http://cached/url", result.get());
    }

    @Test
    public void findOrRegenerateUsesVmUpdateTimeForRootDisk() {
        // For ROOT volumes the search must constrain `updated` by the
        // attached VM's update time, not the volume's own updated
        // timestamp -- so that detaching/attaching invalidates the cache.
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        Mockito.when(volume.getInstanceId()).thenReturn(INSTANCE_ID);
        Date vmUpdate = new Date(123_456L);
        Mockito.when(vmInstance.getUpdateTime()).thenReturn(vmUpdate);
        Mockito.when(vmInstanceDao.findById(INSTANCE_ID)).thenReturn(vmInstance);
        SearchCriteria<VolumeDataStoreVO> sc = mockSearchCriteria();
        Mockito.when(volumeStoreDao.search(ArgumentMatchers.eq(sc), ArgumentMatchers.any()))
                .thenReturn(Collections.emptyList());

        service.findOrRegenerateExistingExtractUrl(sc, volume);

        Mockito.verify(sc).addAnd("updated", SearchCriteria.Op.GTEQ, vmUpdate);
        Mockito.verify(vmInstanceDao).findById(INSTANCE_ID);
    }

    @Test
    public void findOrRegenerateUsesVolumeUpdatedForDetachedDataDisk() {
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        Mockito.when(volume.getInstanceId()).thenReturn(null);
        Date volumeUpdated = new Date(987_654L);
        Mockito.when(volume.getUpdated()).thenReturn(volumeUpdated);
        SearchCriteria<VolumeDataStoreVO> sc = mockSearchCriteria();
        Mockito.when(volumeStoreDao.search(ArgumentMatchers.eq(sc), ArgumentMatchers.any()))
                .thenReturn(Collections.emptyList());

        service.findOrRegenerateExistingExtractUrl(sc, volume);

        Mockito.verify(sc).addAnd("updated", SearchCriteria.Op.GTEQ, volumeUpdated);
        Mockito.verify(vmInstanceDao, Mockito.never()).findById(ArgumentMatchers.anyLong());
    }

    @Test
    public void findOrRegenerateMintsNewUrlWhenInstallPathExistsButUrlIsMissing() {
        Mockito.when(volume.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        Mockito.when(volume.getInstanceId()).thenReturn(null);
        Mockito.when(volume.getFormat()).thenReturn(Storage.ImageFormat.QCOW2);
        SearchCriteria<VolumeDataStoreVO> sc = mockSearchCriteria();
        Mockito.when(volumeStoreDao.search(ArgumentMatchers.eq(sc), ArgumentMatchers.any()))
                .thenReturn(Arrays.asList(volumeStoreRef));
        Mockito.when(volumeStoreRef.getExtractUrl()).thenReturn(null);
        Mockito.when(volumeStoreRef.getInstallPath()).thenReturn("/sec/path");
        Mockito.when(volumeStoreRef.getDataStoreId()).thenReturn(8L);
        VolumeInfo destVol = Mockito.mock(VolumeInfo.class);
        Mockito.when(volFactory.getVolume(VOLUME_ID, DataStoreRole.Image)).thenReturn(destVol);
        ImageStoreEntity secStore = Mockito.mock(ImageStoreEntity.class);
        Mockito.when(dataStoreMgr.getDataStore(8L, DataStoreRole.Image)).thenReturn(secStore);
        Mockito.when(secStore.createEntityExtractUrl("/sec/path", Storage.ImageFormat.QCOW2, destVol)).thenReturn("http://fresh/url");
        // findByVolume is called again to re-fetch before update -- return same mock
        Mockito.when(volumeStoreDao.findByVolume(VOLUME_ID)).thenReturn(volumeStoreRef);

        Optional<String> result = service.findOrRegenerateExistingExtractUrl(sc, volume);

        assertTrue(result.isPresent());
        assertEquals("http://fresh/url", result.get());
        Mockito.verify(volumeStoreRef).setExtractUrl("http://fresh/url");
        Mockito.verify(volumeStoreDao).update(ArgumentMatchers.anyLong(), ArgumentMatchers.eq(volumeStoreRef));
    }

    // ------------------------------------------------------------------
    // orchestrateExtractVolume
    // ------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void orchestrateRejectsRemovedVolume() {
        Mockito.when(volumeDao.findById(VOLUME_ID)).thenReturn(null);
        service.orchestrateExtractVolume(VOLUME_ID, ZONE_ID);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void orchestrateRejectsVolumeNotInReadyState() {
        Mockito.when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(volume.getState()).thenReturn(Volume.State.Allocated);
        service.orchestrateExtractVolume(VOLUME_ID, ZONE_ID);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void orchestrateRejectsZoneWithoutSecondaryStorageCapacity() {
        Mockito.when(volumeDao.findById(VOLUME_ID)).thenReturn(volume);
        Mockito.when(volume.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(dataStoreMgr.getImageStoreWithFreeCapacity(ZONE_ID)).thenReturn(null);
        service.orchestrateExtractVolume(VOLUME_ID, ZONE_ID);
    }

    // ------------------------------------------------------------------
    // wiring smoke
    // ------------------------------------------------------------------

    @Test
    public void serviceIsInjectable() {
        assertNotNull(service);
    }

    // ------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    private SearchCriteria<VolumeDataStoreVO> mockSearchCriteria() {
        return Mockito.mock(SearchCriteria.class);
    }
}
