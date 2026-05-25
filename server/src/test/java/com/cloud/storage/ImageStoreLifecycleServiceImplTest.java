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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;

import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.command.admin.storage.CreateSecondaryStagingStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.DeleteImageStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.DeleteSecondaryStagingStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateImageStoreCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.ImageStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.TemplateService;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDetailsDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.SnapshotDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.TemplateDataStoreVO;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.api.query.dao.TemplateJoinDao;
import com.cloud.api.query.vo.TemplateJoinVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.org.Grouping;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;

/**
 * Focused tests for {@link ImageStoreLifecycleServiceImpl} -- the Phase
 * 4 (slice 5) extraction of secondary image store + cache (staging)
 * store lifecycle out of {@link StorageManagerImpl}.
 *
 * <p>Covers the {@code discoverImageStore}, {@code deleteImageStore},
 * {@code createSecondaryStagingStore},
 * {@code deleteSecondaryStagingStore}, {@code updateImageStore} /
 * {@code updateImageStoreStatus}, and {@code migrateToObjectStore}
 * entry points, including the various validation guards (duplicate
 * names, mixed provider, unknown zone, in-use cache references).
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ImageStoreLifecycleServiceImplTest {

    private static final long STORE_ID = 17L;
    private static final long ZONE_ID = 3L;

    @Mock
    private ImageStoreDao imageStoreDao;
    @Mock
    private ImageStoreDetailsDao imageStoreDetailsDao;
    @Mock
    private DataStoreManager dataStoreMgr;
    @Mock
    private DataStoreProviderManager dataStoreProviderMgr;
    @Mock
    private DataCenterDao dcDao;
    @Mock
    private AccountManager accountMgr;
    @Mock
    private TemplateService imageSrv;
    @Mock
    private ClusterDao clusterDao;
    @Mock
    private VMTemplateDao vmTemplateDao;
    @Mock
    private VMTemplateZoneDao vmTemplateZoneDao;
    @Mock
    private TemplateDataStoreDao templateStoreDao;
    @Mock
    private SnapshotDataStoreDao snapshotStoreDao;
    @Mock
    private VolumeDataStoreDao volumeStoreDao;
    @Mock
    private TemplateJoinDao templateViewDao;
    @Mock
    private AnnotationDao annotationDao;
    @Mock
    private ImageStoreDetailsUtil imageStoreDetailsUtil;

    @InjectMocks
    private ImageStoreLifecycleServiceImpl service;

    private MockedStatic<CallContext> callContextStatic;
    private CallContext callContext;
    private Account callerAccount;

    @Before
    public void setUp() {
        callContextStatic = Mockito.mockStatic(CallContext.class);
        callContext = mock(CallContext.class);
        callerAccount = mock(Account.class);
        when(callerAccount.getId()).thenReturn(2L);
        callContextStatic.when(CallContext::current).thenReturn(callContext);
        when(callContext.getCallingAccount()).thenReturn(callerAccount);
    }

    @After
    public void tearDown() {
        if (callContextStatic != null) {
            callContextStatic.close();
        }
    }

    // ---------------------------------------------------------------------
    // discoverImageStore
    // ---------------------------------------------------------------------

    @Test
    public void discoverImageStoreUnknownProviderFallsBackToDefault() throws Exception {
        when(dataStoreProviderMgr.getDataStoreProvider("bogus")).thenReturn(null);
        when(dataStoreProviderMgr.getDefaultImageDataStoreProvider()).thenReturn(null);

        try {
            service.discoverImageStore("nfs1", "nfs://server/path", "bogus", ZONE_ID, new HashMap<>());
            fail("Expected InvalidParameterValueException when no fallback provider exists");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("can't find image store provider"));
        }
    }

    @Test
    public void discoverImageStoreNameAlreadyExistsThrows() throws Exception {
        DataStoreProvider provider = mock(DataStoreProvider.class, Mockito.withSettings().extraInterfaces(ImageStoreProvider.class));
        when(dataStoreProviderMgr.getDataStoreProvider("nfs")).thenReturn(provider);
        when(provider.getName()).thenReturn("nfs");
        when(imageStoreDao.findByName("dup")).thenReturn(mock(ImageStoreVO.class));

        try {
            service.discoverImageStore("dup", "nfs://server/path", "nfs", ZONE_ID, new HashMap<>());
            fail("Expected InvalidParameterValueException on duplicate name");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("already exists"));
        }
    }

    @Test
    public void discoverImageStoreScopeUnsupportedThrows() throws Exception {
        DataStoreProvider provider = mock(DataStoreProvider.class, Mockito.withSettings().extraInterfaces(ImageStoreProvider.class));
        when(dataStoreProviderMgr.getDataStoreProvider("nfs")).thenReturn(provider);
        when(provider.getName()).thenReturn("nfs");
        when(imageStoreDao.findByName(anyString())).thenReturn(null);
        when(((ImageStoreProvider) provider).isScopeSupported(any(ScopeType.class))).thenReturn(false);

        try {
            service.discoverImageStore("nfs1", "nfs://server/path", "nfs", ZONE_ID, new HashMap<>());
            fail("Expected InvalidParameterValueException for unsupported scope");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("does not support scope"));
        }
    }

    @Test
    public void discoverImageStoreDifferentProviderAlreadyExistsThrows() throws Exception {
        DataStoreProvider provider = mock(DataStoreProvider.class, Mockito.withSettings().extraInterfaces(ImageStoreProvider.class));
        when(dataStoreProviderMgr.getDataStoreProvider("nfs")).thenReturn(provider);
        when(provider.getName()).thenReturn("nfs");
        when(imageStoreDao.findByName(anyString())).thenReturn(null);
        when(((ImageStoreProvider) provider).isScopeSupported(any(ScopeType.class))).thenReturn(true);
        ImageStoreVO existing = mock(ImageStoreVO.class);
        when(existing.getProviderName()).thenReturn("s3");
        when(imageStoreDao.listImageStores()).thenReturn(Collections.singletonList(existing));

        try {
            service.discoverImageStore("nfs1", "nfs://server/path", "nfs", null, new HashMap<>());
            fail("Expected InvalidParameterValueException when a different provider already exists");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("only add new image stores from the same provider"));
        }
    }

    @Test
    public void discoverImageStoreUnknownZoneThrows() throws Exception {
        DataStoreProvider provider = mock(DataStoreProvider.class, Mockito.withSettings().extraInterfaces(ImageStoreProvider.class));
        when(dataStoreProviderMgr.getDataStoreProvider("nfs")).thenReturn(provider);
        when(provider.getName()).thenReturn("nfs");
        when(imageStoreDao.findByName(anyString())).thenReturn(null);
        when(((ImageStoreProvider) provider).isScopeSupported(any(ScopeType.class))).thenReturn(true);
        when(imageStoreDao.listImageStores()).thenReturn(Collections.emptyList());
        when(dcDao.findById(ZONE_ID)).thenReturn(null);

        try {
            service.discoverImageStore("nfs1", "nfs://server/path", "nfs", ZONE_ID, new HashMap<>());
            fail("Expected InvalidParameterValueException for unknown zone");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("Can't find zone"));
        }
    }

    // ---------------------------------------------------------------------
    // updateImageStore / updateImageStoreStatus
    // ---------------------------------------------------------------------

    @Test
    public void updateImageStoreStatusMissingStoreThrows() {
        when(imageStoreDao.findById(STORE_ID)).thenReturn(null);

        try {
            service.updateImageStoreStatus(STORE_ID, "newName", Boolean.TRUE, 1000L);
            fail("Expected IllegalArgumentException for missing store");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage().contains("Unable to find image store"));
        }
    }

    @Test
    public void updateImageStoreStatusAppliesEachNonNullField() {
        ImageStoreVO store = mock(ImageStoreVO.class);
        when(imageStoreDao.findById(STORE_ID)).thenReturn(store);

        service.updateImageStoreStatus(STORE_ID, "newName", Boolean.TRUE, 4096L);

        verify(store).setName("newName");
        verify(store).setReadonly(Boolean.TRUE);
        verify(store).setTotalSize(4096L);
        verify(imageStoreDao).update(STORE_ID, store);
    }

    @Test
    public void updateImageStoreStatusBlankNameIsIgnored() {
        ImageStoreVO store = mock(ImageStoreVO.class);
        when(imageStoreDao.findById(STORE_ID)).thenReturn(store);

        service.updateImageStoreStatus(STORE_ID, "  ", null, null);

        verify(store, never()).setName(anyString());
        verify(store, never()).setReadonly(any(Boolean.class));
        verify(store, never()).setTotalSize(anyLong());
        verify(imageStoreDao).update(STORE_ID, store);
    }

    @Test
    public void updateImageStoreStatusShortFormFlipsReadonlyOnly() {
        ImageStoreVO store = mock(ImageStoreVO.class);
        when(imageStoreDao.findById(STORE_ID)).thenReturn(store);

        service.updateImageStoreStatus(STORE_ID, Boolean.FALSE);

        verify(store, never()).setName(anyString());
        verify(store, never()).setTotalSize(anyLong());
        verify(store).setReadonly(Boolean.FALSE);
    }

    @Test
    public void updateImageStoreUnpacksCmd() {
        UpdateImageStoreCmd cmd = mock(UpdateImageStoreCmd.class);
        when(cmd.getId()).thenReturn(STORE_ID);
        when(cmd.getName()).thenReturn("renamed");
        when(cmd.getReadonly()).thenReturn(Boolean.TRUE);
        when(cmd.getCapacityBytes()).thenReturn(2048L);
        ImageStoreVO store = mock(ImageStoreVO.class);
        when(imageStoreDao.findById(STORE_ID)).thenReturn(store);

        service.updateImageStore(cmd);

        verify(store).setName("renamed");
        verify(store).setReadonly(Boolean.TRUE);
        verify(store).setTotalSize(2048L);
    }

    // ---------------------------------------------------------------------
    // deleteImageStore
    // ---------------------------------------------------------------------

    @Test
    public void deleteImageStoreMissingThrows() {
        DeleteImageStoreCmd cmd = mock(DeleteImageStoreCmd.class);
        when(cmd.getId()).thenReturn(STORE_ID);
        when(imageStoreDao.findById(STORE_ID)).thenReturn(null);

        try {
            service.deleteImageStore(cmd);
            fail("Expected InvalidParameterValueException for missing store");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("doesn't exist"));
        }
    }

    @Test
    public void deleteImageStoreActiveSnapshotsRefusesDelete() {
        DeleteImageStoreCmd cmd = mock(DeleteImageStoreCmd.class);
        when(cmd.getId()).thenReturn(STORE_ID);
        ImageStoreVO store = mock(ImageStoreVO.class);
        when(store.getDataCenterId()).thenReturn(ZONE_ID);
        when(imageStoreDao.findById(STORE_ID)).thenReturn(store);
        when(snapshotStoreDao.listByStoreId(STORE_ID, DataStoreRole.Image))
                .thenReturn(Collections.singletonList(mock(SnapshotDataStoreVO.class)));

        try {
            service.deleteImageStore(cmd);
            fail("Expected InvalidParameterValueException for active snapshots");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("active snapshots"));
        }
        verify(imageStoreDao, never()).remove(anyLong());
    }

    @Test
    public void deleteImageStoreActiveVolumesRefusesDelete() {
        DeleteImageStoreCmd cmd = mock(DeleteImageStoreCmd.class);
        when(cmd.getId()).thenReturn(STORE_ID);
        ImageStoreVO store = mock(ImageStoreVO.class);
        when(store.getDataCenterId()).thenReturn(ZONE_ID);
        when(imageStoreDao.findById(STORE_ID)).thenReturn(store);
        when(snapshotStoreDao.listByStoreId(STORE_ID, DataStoreRole.Image))
                .thenReturn(Collections.emptyList());
        when(volumeStoreDao.listByStoreId(STORE_ID))
                .thenReturn(Collections.singletonList(mock(VolumeDataStoreVO.class)));

        try {
            service.deleteImageStore(cmd);
            fail("Expected InvalidParameterValueException for active volumes");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("active volumes"));
        }
    }

    @Test
    public void deleteImageStoreActiveTemplatesRefusesDelete() {
        DeleteImageStoreCmd cmd = mock(DeleteImageStoreCmd.class);
        when(cmd.getId()).thenReturn(STORE_ID);
        ImageStoreVO store = mock(ImageStoreVO.class);
        when(store.getDataCenterId()).thenReturn(ZONE_ID);
        when(imageStoreDao.findById(STORE_ID)).thenReturn(store);
        when(snapshotStoreDao.listByStoreId(STORE_ID, DataStoreRole.Image))
                .thenReturn(Collections.emptyList());
        when(volumeStoreDao.listByStoreId(STORE_ID))
                .thenReturn(Collections.emptyList());
        when(templateViewDao.listActiveTemplates(STORE_ID))
                .thenReturn(Collections.singletonList(mock(TemplateJoinVO.class)));

        try {
            service.deleteImageStore(cmd);
            fail("Expected InvalidParameterValueException for active templates");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("active Templates"));
        }
    }

    // ---------------------------------------------------------------------
    // createSecondaryStagingStore
    // ---------------------------------------------------------------------

    @Test
    public void createSecondaryStagingStoreUnknownProviderNoDefaultThrows() {
        CreateSecondaryStagingStoreCmd cmd = mock(CreateSecondaryStagingStoreCmd.class);
        when(cmd.getProviderName()).thenReturn("missing");
        when(dataStoreProviderMgr.getDataStoreProvider("missing")).thenReturn(null);
        when(dataStoreProviderMgr.getDefaultCacheDataStoreProvider()).thenReturn(null);

        try {
            service.createSecondaryStagingStore(cmd);
            fail("Expected InvalidParameterValueException when no fallback exists");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("can't find cache store provider"));
        }
    }

    @Test
    public void createSecondaryStagingStoreInvalidScopeThrows() {
        CreateSecondaryStagingStoreCmd cmd = mock(CreateSecondaryStagingStoreCmd.class);
        when(cmd.getProviderName()).thenReturn("nfs");
        when(cmd.getScope()).thenReturn("garbage");
        DataStoreProvider provider = mock(DataStoreProvider.class);
        when(dataStoreProviderMgr.getDataStoreProvider("nfs")).thenReturn(provider);

        try {
            service.createSecondaryStagingStore(cmd);
            fail("Expected InvalidParameterValueException for invalid scope");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("invalid scope"));
        }
    }

    @Test
    public void createSecondaryStagingStoreRegionScopeRejected() {
        CreateSecondaryStagingStoreCmd cmd = mock(CreateSecondaryStagingStoreCmd.class);
        when(cmd.getProviderName()).thenReturn("nfs");
        when(cmd.getScope()).thenReturn("REGION");
        DataStoreProvider provider = mock(DataStoreProvider.class);
        when(dataStoreProviderMgr.getDataStoreProvider("nfs")).thenReturn(provider);

        try {
            service.createSecondaryStagingStore(cmd);
            fail("Expected InvalidParameterValueException for REGION scope");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("Only zone wide"));
        }
    }

    @Test
    public void createSecondaryStagingStoreZoneScopeRequiresZoneId() {
        CreateSecondaryStagingStoreCmd cmd = mock(CreateSecondaryStagingStoreCmd.class);
        when(cmd.getProviderName()).thenReturn("nfs");
        when(cmd.getScope()).thenReturn("ZONE");
        when(cmd.getZoneId()).thenReturn(null);
        DataStoreProvider provider = mock(DataStoreProvider.class);
        when(dataStoreProviderMgr.getDataStoreProvider("nfs")).thenReturn(provider);

        try {
            service.createSecondaryStagingStore(cmd);
            fail("Expected InvalidParameterValueException when zone id is null");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("zone id can't be null"));
        }
    }

    @Test
    public void createSecondaryStagingStoreUnknownZoneThrows() {
        CreateSecondaryStagingStoreCmd cmd = mock(CreateSecondaryStagingStoreCmd.class);
        when(cmd.getProviderName()).thenReturn("nfs");
        when(cmd.getScope()).thenReturn("ZONE");
        when(cmd.getZoneId()).thenReturn(ZONE_ID);
        DataStoreProvider provider = mock(DataStoreProvider.class);
        when(dataStoreProviderMgr.getDataStoreProvider("nfs")).thenReturn(provider);
        when(dcDao.findById(ZONE_ID)).thenReturn(null);

        try {
            service.createSecondaryStagingStore(cmd);
            fail("Expected InvalidParameterValueException for unknown zone");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("Can't find zone"));
        }
    }

    @Test
    public void createSecondaryStagingStoreHappyPath() {
        CreateSecondaryStagingStoreCmd cmd = mock(CreateSecondaryStagingStoreCmd.class);
        when(cmd.getProviderName()).thenReturn("nfs");
        when(cmd.getScope()).thenReturn("ZONE");
        when(cmd.getZoneId()).thenReturn(ZONE_ID);
        when(cmd.getUrl()).thenReturn("nfs://server/path");
        when(cmd.getDetails()).thenReturn(new HashMap<>());

        DataStoreProvider provider = mock(DataStoreProvider.class);
        when(provider.getName()).thenReturn("nfs");
        DataStoreLifeCycle lifeCycle = mock(DataStoreLifeCycle.class);
        when(provider.getDataStoreLifeCycle()).thenReturn(lifeCycle);
        when(dataStoreProviderMgr.getDataStoreProvider("nfs")).thenReturn(provider);

        DataCenterVO zone = mock(DataCenterVO.class);
        when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);
        when(dcDao.findById(ZONE_ID)).thenReturn(zone);

        DataStore store = mock(DataStore.class);
        when(store.getId()).thenReturn(STORE_ID);
        when(lifeCycle.initialize(any())).thenReturn(store);

        DataStoreAndImageStore expected = mock(DataStoreAndImageStore.class);
        when(dataStoreMgr.getDataStore(STORE_ID, DataStoreRole.ImageCache)).thenReturn(expected);

        ImageStore result = service.createSecondaryStagingStore(cmd);

        assertSame(expected, result);
        verify(lifeCycle, times(1)).initialize(any());
    }

    /** Test-only interface that implements both DataStore and ImageStore so a single mock satisfies both call sites. */
    public interface DataStoreAndImageStore extends DataStore, ImageStore {
    }

    // ---------------------------------------------------------------------
    // deleteSecondaryStagingStore
    // ---------------------------------------------------------------------

    @Test
    public void deleteSecondaryStagingStoreMissingThrows() {
        DeleteSecondaryStagingStoreCmd cmd = mock(DeleteSecondaryStagingStoreCmd.class);
        when(cmd.getId()).thenReturn(STORE_ID);
        when(imageStoreDao.findById(STORE_ID)).thenReturn(null);

        try {
            service.deleteSecondaryStagingStore(cmd);
            fail("Expected InvalidParameterValueException for missing cache store");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("Cache store"));
        }
    }

    @Test
    public void deleteSecondaryStagingStoreInUseSnapshotsRefuses() {
        DeleteSecondaryStagingStoreCmd cmd = mock(DeleteSecondaryStagingStoreCmd.class);
        when(cmd.getId()).thenReturn(STORE_ID);
        ImageStoreVO store = mock(ImageStoreVO.class);
        when(store.getDataCenterId()).thenReturn(ZONE_ID);
        when(imageStoreDao.findById(STORE_ID)).thenReturn(store);
        when(snapshotStoreDao.listActiveOnCache(STORE_ID))
                .thenReturn(Collections.singletonList(mock(SnapshotDataStoreVO.class)));

        try {
            service.deleteSecondaryStagingStore(cmd);
            fail("Expected InvalidParameterValueException for in-use snapshots");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("staging snapshots"));
        }
        verify(imageStoreDao, never()).remove(anyLong());
    }

    @Test
    public void deleteSecondaryStagingStoreInUseVolumesRefuses() {
        DeleteSecondaryStagingStoreCmd cmd = mock(DeleteSecondaryStagingStoreCmd.class);
        when(cmd.getId()).thenReturn(STORE_ID);
        ImageStoreVO store = mock(ImageStoreVO.class);
        when(store.getDataCenterId()).thenReturn(ZONE_ID);
        when(imageStoreDao.findById(STORE_ID)).thenReturn(store);
        when(snapshotStoreDao.listActiveOnCache(STORE_ID)).thenReturn(Collections.emptyList());
        when(volumeStoreDao.listActiveOnCache(STORE_ID))
                .thenReturn(Collections.singletonList(mock(VolumeDataStoreVO.class)));

        try {
            service.deleteSecondaryStagingStore(cmd);
            fail("Expected InvalidParameterValueException for in-use volumes");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("staging Volumes"));
        }
    }

    @Test
    public void deleteSecondaryStagingStoreInUseTemplatesRefuses() {
        DeleteSecondaryStagingStoreCmd cmd = mock(DeleteSecondaryStagingStoreCmd.class);
        when(cmd.getId()).thenReturn(STORE_ID);
        ImageStoreVO store = mock(ImageStoreVO.class);
        when(store.getDataCenterId()).thenReturn(ZONE_ID);
        when(imageStoreDao.findById(STORE_ID)).thenReturn(store);
        when(snapshotStoreDao.listActiveOnCache(STORE_ID)).thenReturn(Collections.emptyList());
        when(volumeStoreDao.listActiveOnCache(STORE_ID)).thenReturn(Collections.emptyList());
        when(templateStoreDao.listActiveOnCache(STORE_ID))
                .thenReturn(Collections.singletonList(mock(TemplateDataStoreVO.class)));

        try {
            service.deleteSecondaryStagingStore(cmd);
            fail("Expected InvalidParameterValueException for in-use templates");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("staging Templates"));
        }
    }

    // ---------------------------------------------------------------------
    // migrateToObjectStore
    // ---------------------------------------------------------------------

    @Test
    public void migrateToObjectStoreRejectsNonNfsExistingStore() throws Exception {
        ImageStoreVO existing = mock(ImageStoreVO.class);
        when(existing.getProviderName()).thenReturn("s3");
        when(imageStoreDao.listImageStores()).thenReturn(Collections.singletonList(existing));

        try {
            service.migrateToObjectStore("obj1", "s3://bucket", "s3", new HashMap<>());
            fail("Expected InvalidParameterValueException for non-NFS existing store");
        } catch (InvalidParameterValueException e) {
            assertTrue(e.getMessage().contains("only support migrate NFS"));
        }
    }
}
