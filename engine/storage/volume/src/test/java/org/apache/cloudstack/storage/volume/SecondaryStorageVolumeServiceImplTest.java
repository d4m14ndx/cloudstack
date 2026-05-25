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
package org.apache.cloudstack.storage.volume;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiFunction;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPoint;
import org.apache.cloudstack.engine.subsystem.api.storage.EndPointSelector;
import org.apache.cloudstack.engine.subsystem.api.storage.Scope;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.MockedStatic;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.storage.ListVolumeAnswer;
import com.cloud.alert.AlertManager;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.ScopeType;
import com.cloud.storage.VMTemplateStorageResourceAssoc.Status;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.storage.template.TemplateProp;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class SecondaryStorageVolumeServiceImplTest {

    private SecondaryStorageVolumeServiceImpl service;

    @Mock
    private VolumeDataStoreDao volumeStoreDao;
    @Mock
    private VolumeDao volDao;
    @Mock
    private VolumeDataFactory volFactory;
    @Mock
    private EndPointSelector epSelector;
    @Mock
    private ResourceLimitService resourceLimitMgr;
    @Mock
    private AccountManager accountMgr;
    @Mock
    private AlertManager alertMgr;

    @Mock
    private DataStore store;
    @Mock
    private EndPoint endPoint;
    @Mock
    private Scope scope;

    @Before
    public void setUp() {
        service = new SecondaryStorageVolumeServiceImpl();
        service.volumeStoreDao = volumeStoreDao;
        service.volDao = volDao;
        service.volFactory = volFactory;
        service.epSelector = epSelector;
        service.resourceLimitMgr = resourceLimitMgr;
        service.accountMgr = accountMgr;
        service.alertMgr = alertMgr;
    }

    private BiFunction<VolumeInfo, DataStore, AsyncCallFuture<VolumeApiResult>> noopDownloader() {
        return (v, s) -> new AsyncCallFuture<>();
    }

    /**
     * Stub the global lock so its lock() returns true and unlock()/releaseRef()
     * are no-ops. Otherwise GlobalLock tries to acquire a real DB lock.
     */
    private MockedStatic<GlobalLock> stubGlobalLock() {
        MockedStatic<GlobalLock> mocked = Mockito.mockStatic(GlobalLock.class);
        GlobalLock fake = Mockito.mock(GlobalLock.class);
        Mockito.when(fake.lock(Mockito.anyInt())).thenReturn(true);
        mocked.when(() -> GlobalLock.getInternLock(Mockito.anyString())).thenReturn(fake);
        return mocked;
    }

    // --- buildVolumePath -----------------------------------------------

    @Test
    public void buildVolumePathConstructsCanonicalPath() {
        String path = service.buildVolumePath(42L, 7L);
        Assert.assertTrue("expected DEFAULT_VOLUME_ROOT_DIR prefix, was: " + path,
                path.endsWith("/42/7") && path.contains("/"));
    }

    @Test
    public void buildVolumePathHandlesZeroIds() {
        String path = service.buildVolumePath(0L, 0L);
        Assert.assertTrue(path.endsWith("/0/0"));
    }

    // --- handleVolumeSync null store -----------------------------------

    @Test
    public void handleVolumeSyncReturnsEarlyOnNullStore() {
        // null store check happens before any GlobalLock interaction
        service.handleVolumeSync(null, noopDownloader());
        Mockito.verifyNoInteractions(volumeStoreDao, volDao, volFactory, epSelector);
    }

    // --- listVolume -----------------------------------------------------

    @Test
    public void listVolumeReturnsNullWhenEndpointIsNull() {
        Mockito.when(store.getTO()).thenReturn(null);
        Mockito.when(store.getUri()).thenReturn("nfs://x");
        Mockito.when(epSelector.select(store)).thenReturn(null);

        Map<Long, TemplateProp> result = service.listVolume(store);

        Assert.assertNull(result);
    }

    @Test
    public void listVolumeReturnsNullOnFailedAnswer() {
        Mockito.when(store.getTO()).thenReturn(null);
        Mockito.when(store.getUri()).thenReturn("nfs://x");
        Mockito.when(epSelector.select(store)).thenReturn(endPoint);
        Answer failed = Mockito.mock(Answer.class);
        Mockito.when(failed.getResult()).thenReturn(false);
        Mockito.when(endPoint.sendMessage(Mockito.any())).thenReturn(failed);

        Map<Long, TemplateProp> result = service.listVolume(store);

        Assert.assertNull(result);
    }

    @Test
    public void listVolumeReturnsTemplateInfoOnSuccess() {
        Mockito.when(store.getTO()).thenReturn(null);
        Mockito.when(store.getUri()).thenReturn("nfs://x");
        Mockito.when(epSelector.select(store)).thenReturn(endPoint);
        Map<Long, TemplateProp> expected = new HashMap<>();
        expected.put(1L, new TemplateProp("t1", "/path/t1", false, false));
        ListVolumeAnswer answer = Mockito.mock(ListVolumeAnswer.class);
        Mockito.when(answer.getResult()).thenReturn(true);
        Mockito.when(answer.getTemplateInfo()).thenReturn(expected);
        Mockito.when(endPoint.sendMessage(Mockito.any())).thenReturn(answer);

        Map<Long, TemplateProp> result = service.listVolume(store);

        Assert.assertSame(expected, result);
    }

    // --- handleVolumeSync orphaned-ref -----------------------------------

    @Test
    public void handleVolumeSyncMarksOrphanedDbRowsDestroyed() {
        // image store returns empty list, DB has 1 row whose volume id is missing
        SecondaryStorageVolumeServiceImpl spy = Mockito.spy(service);
        Mockito.doReturn(new HashMap<>()).when(spy).listVolume(store);
        Mockito.when(store.getId()).thenReturn(11L);

        VolumeDataStoreVO orphan = Mockito.mock(VolumeDataStoreVO.class);
        Mockito.when(orphan.getId()).thenReturn(101L);
        Mockito.when(orphan.getVolumeId()).thenReturn(202L);
        Mockito.when(volumeStoreDao.listByStoreId(11L)).thenReturn(Collections.singletonList(orphan));
        Mockito.when(volDao.findById(202L)).thenReturn(null);

        try (MockedStatic<GlobalLock> ignored = stubGlobalLock()) {
            spy.handleVolumeSync(store, noopDownloader());
        }

        Mockito.verify(orphan).setDestroyed(true);
        Mockito.verify(volumeStoreDao).update(101L, orphan);
    }

    // --- handleVolumeSync skip-when-listVolume-null ----------------------

    @Test
    public void handleVolumeSyncSkipsBodyWhenListVolumeReturnsNull() {
        SecondaryStorageVolumeServiceImpl spy = Mockito.spy(service);
        Mockito.doReturn(null).when(spy).listVolume(store);
        Mockito.when(store.getId()).thenReturn(11L);

        try (MockedStatic<GlobalLock> ignored = stubGlobalLock()) {
            spy.handleVolumeSync(store, noopDownloader());
        }

        // never queried for DB volumes -- sync body skipped
        Mockito.verify(volumeStoreDao, Mockito.never()).listByStoreId(Mockito.anyLong());
    }

    // --- handleVolumeSync triggers downloader for missing volume --------

    @Test
    public void handleVolumeSyncTriggersDownloaderForMissingDownloadable() {
        SecondaryStorageVolumeServiceImpl spy = Mockito.spy(service);
        Mockito.doReturn(new HashMap<Long, TemplateProp>()).when(spy).listVolume(store);
        Mockito.when(store.getId()).thenReturn(11L);
        Mockito.when(store.getScope()).thenReturn(scope);
        Mockito.when(scope.getScopeType()).thenReturn(ScopeType.ZONE);

        VolumeDataStoreVO toDownload = Mockito.mock(VolumeDataStoreVO.class);
        Mockito.when(toDownload.getId()).thenReturn(101L);
        Mockito.when(toDownload.getVolumeId()).thenReturn(202L);
        Mockito.when(toDownload.getDownloadUrl()).thenReturn("http://src/vol.qcow2");
        Mockito.when(toDownload.getDownloadState()).thenReturn(Status.NOT_DOWNLOADED);
        Mockito.when(volumeStoreDao.listByStoreId(11L)).thenReturn(Collections.singletonList(toDownload));

        VolumeVO vol = new VolumeVO("v", 1L, 1L, 1L, 1L, 1L, "folder", "path", null, 0L, Volume.Type.DATADISK);
        vol.setState(Volume.State.Ready);
        Mockito.when(volDao.findById(202L)).thenReturn(vol);

        VolumeObject volObj = Mockito.mock(VolumeObject.class);
        Mockito.when(volObj.getFormat()).thenReturn(com.cloud.storage.Storage.ImageFormat.QCOW2);
        Mockito.when(volFactory.getVolume(202L)).thenReturn(volObj);

        @SuppressWarnings("unchecked")
        BiFunction<VolumeInfo, DataStore, AsyncCallFuture<VolumeApiResult>> downloader = Mockito.mock(BiFunction.class);
        AsyncCallFuture<VolumeApiResult> future = new AsyncCallFuture<>();
        Mockito.when(downloader.apply(Mockito.any(), Mockito.any())).thenReturn(future);

        try (MockedStatic<GlobalLock> ignored = stubGlobalLock()) {
            spy.handleVolumeSync(store, downloader);
        }

        Mockito.verify(downloader).apply(volObj, store);
        Mockito.verify(volumeStoreDao).remove(101L);
    }

    @Test
    public void handleVolumeSyncSkipsDownloadWhenUrlNull() {
        SecondaryStorageVolumeServiceImpl spy = Mockito.spy(service);
        Mockito.doReturn(new HashMap<Long, TemplateProp>()).when(spy).listVolume(store);
        Mockito.when(store.getId()).thenReturn(11L);

        VolumeDataStoreVO noUrl = Mockito.mock(VolumeDataStoreVO.class);
        Mockito.when(noUrl.getVolumeId()).thenReturn(202L);
        Mockito.when(noUrl.getDownloadUrl()).thenReturn(null);
        Mockito.when(volumeStoreDao.listByStoreId(11L)).thenReturn(Collections.singletonList(noUrl));

        VolumeVO vol = new VolumeVO("v", 1L, 1L, 1L, 1L, 1L, "folder", "path", null, 0L, Volume.Type.DATADISK);
        vol.setState(Volume.State.Ready);
        Mockito.when(volDao.findById(202L)).thenReturn(vol);

        @SuppressWarnings("unchecked")
        BiFunction<VolumeInfo, DataStore, AsyncCallFuture<VolumeApiResult>> downloader = Mockito.mock(BiFunction.class);

        try (MockedStatic<GlobalLock> ignored = stubGlobalLock()) {
            spy.handleVolumeSync(store, downloader);
        }

        Mockito.verifyNoInteractions(downloader);
    }

    // --- handleVolumeSync deletes store-only entries --------------------

    @Test
    public void handleVolumeSyncDeletesOrphanedStoreEntries() {
        SecondaryStorageVolumeServiceImpl spy = Mockito.spy(service);
        Map<Long, TemplateProp> volsOnStore = new HashMap<>();
        TemplateProp prop = new TemplateProp("orphanedOnStore", "/path/orphan", false, false);
        volsOnStore.put(999L, prop);
        Mockito.doReturn(volsOnStore).when(spy).listVolume(store);
        Mockito.when(store.getId()).thenReturn(11L);
        Mockito.when(store.getTO()).thenReturn(null);
        Mockito.when(volumeStoreDao.listByStoreId(11L)).thenReturn(Collections.<VolumeDataStoreVO>emptyList());

        Mockito.when(epSelector.select(store)).thenReturn(endPoint);
        Answer okAnswer = Mockito.mock(Answer.class);
        Mockito.when(okAnswer.getResult()).thenReturn(true);
        Mockito.when(endPoint.sendMessage(Mockito.any())).thenReturn(okAnswer);

        try (MockedStatic<GlobalLock> ignored = stubGlobalLock()) {
            spy.handleVolumeSync(store, noopDownloader());
        }

        Mockito.verify(endPoint).sendMessage(Mockito.any(org.apache.cloudstack.storage.command.DeleteCommand.class));
    }

    @Test
    public void handleVolumeSyncToleratesNullEndpointDuringDelete() {
        SecondaryStorageVolumeServiceImpl spy = Mockito.spy(service);
        Map<Long, TemplateProp> volsOnStore = new HashMap<>();
        volsOnStore.put(999L, new TemplateProp("orphan", "/p", false, false));
        Mockito.doReturn(volsOnStore).when(spy).listVolume(store);
        Mockito.when(store.getId()).thenReturn(11L);
        Mockito.when(store.getTO()).thenReturn(null);
        Mockito.when(volumeStoreDao.listByStoreId(11L)).thenReturn(Collections.<VolumeDataStoreVO>emptyList());
        Mockito.when(epSelector.select(store)).thenReturn(null);

        // should not throw
        try (MockedStatic<GlobalLock> ignored = stubGlobalLock()) {
            spy.handleVolumeSync(store, noopDownloader());
        }
    }

    // --- moveVolumeOnSecondaryStorageToAnotherAccount -------------------

    @Test
    public void moveVolumeOnSecondaryReturnsEarlyWhenNoStoreRow() {
        Volume volume = Mockito.mock(Volume.class);
        Mockito.when(volume.getId()).thenReturn(7L);
        Account src = Mockito.mock(Account.class);
        Account dst = Mockito.mock(Account.class);
        Mockito.when(volumeStoreDao.findByVolume(7L)).thenReturn(null);

        service.moveVolumeOnSecondaryStorageToAnotherAccount(volume, src, dst);

        Mockito.verifyNoInteractions(volFactory, epSelector);
    }

    @Test
    public void moveVolumeOnSecondaryUpdatesInstallPathOnSuccess() {
        Volume volume = Mockito.mock(Volume.class);
        Mockito.when(volume.getId()).thenReturn(7L);
        Mockito.when(volume.getUuid()).thenReturn("uuid-1");
        Mockito.when(volume.getName()).thenReturn("vol1");
        Mockito.when(volume.getDataCenterId()).thenReturn(3L);
        Account src = Mockito.mock(Account.class);
        Account dst = Mockito.mock(Account.class);
        Mockito.when(dst.getAccountId()).thenReturn(99L);

        VolumeDataStoreVO storeVo = Mockito.mock(VolumeDataStoreVO.class);
        Mockito.when(storeVo.getId()).thenReturn(101L);
        Mockito.when(volumeStoreDao.findByVolume(7L)).thenReturn(storeVo);
        Mockito.when(volumeStoreDao.update(101L, storeVo)).thenReturn(true);

        VolumeInfo info = Mockito.mock(VolumeInfo.class);
        DataStore secStore = Mockito.mock(DataStore.class);
        Mockito.when(info.getDataStore()).thenReturn(secStore);
        Mockito.when(secStore.getUri()).thenReturn("nfs://store");
        Mockito.when(info.getPath()).thenReturn("/template/2/7/file.qcow2");
        Mockito.when(volFactory.getVolume(7L, DataStoreRole.Image)).thenReturn(info);

        EndPoint ssvm = Mockito.mock(EndPoint.class);
        Mockito.when(epSelector.findSsvm(3L)).thenReturn(ssvm);
        Answer ok = Mockito.mock(Answer.class);
        Mockito.when(ok.getResult()).thenReturn(true);
        Mockito.when(ssvm.sendMessage(Mockito.any())).thenReturn(ok);

        service.moveVolumeOnSecondaryStorageToAnotherAccount(volume, src, dst);

        ArgumentCaptor<String> pathCaptor = ArgumentCaptor.forClass(String.class);
        Mockito.verify(storeVo).setInstallPath(pathCaptor.capture());
        Assert.assertTrue("install path should land in dest folder & keep filename: " + pathCaptor.getValue(),
                pathCaptor.getValue().contains("/99/7/") && pathCaptor.getValue().endsWith("file.qcow2"));
    }

    @Test(expected = CloudRuntimeException.class)
    public void moveVolumeOnSecondaryThrowsWhenSsvmAnswerFails() {
        Volume volume = Mockito.mock(Volume.class);
        Mockito.when(volume.getId()).thenReturn(7L);
        Mockito.when(volume.getUuid()).thenReturn("uuid-1");
        Mockito.when(volume.getName()).thenReturn("vol1");
        Mockito.when(volume.getDataCenterId()).thenReturn(3L);
        Account src = Mockito.mock(Account.class);
        Account dst = Mockito.mock(Account.class);
        Mockito.when(dst.getAccountId()).thenReturn(99L);

        VolumeDataStoreVO storeVo = Mockito.mock(VolumeDataStoreVO.class);
        Mockito.when(volumeStoreDao.findByVolume(7L)).thenReturn(storeVo);

        VolumeInfo info = Mockito.mock(VolumeInfo.class);
        DataStore secStore = Mockito.mock(DataStore.class);
        Mockito.when(info.getDataStore()).thenReturn(secStore);
        Mockito.when(secStore.getUri()).thenReturn("nfs://store");
        Mockito.when(info.getPath()).thenReturn("/template/2/7/file.qcow2");
        Mockito.when(volFactory.getVolume(7L, DataStoreRole.Image)).thenReturn(info);

        EndPoint ssvm = Mockito.mock(EndPoint.class);
        Mockito.when(epSelector.findSsvm(3L)).thenReturn(ssvm);
        Answer bad = Mockito.mock(Answer.class);
        Mockito.when(bad.getResult()).thenReturn(false);
        Mockito.when(bad.getDetails()).thenReturn("no space");
        Mockito.when(ssvm.sendMessage(Mockito.any())).thenReturn(bad);

        service.moveVolumeOnSecondaryStorageToAnotherAccount(volume, src, dst);
    }

    @Test(expected = CloudRuntimeException.class)
    public void moveVolumeOnSecondaryThrowsWhenDbUpdateFails() {
        Volume volume = Mockito.mock(Volume.class);
        Mockito.when(volume.getId()).thenReturn(7L);
        Mockito.when(volume.getUuid()).thenReturn("uuid-1");
        Mockito.when(volume.getName()).thenReturn("vol1");
        Mockito.when(volume.getDataCenterId()).thenReturn(3L);
        Account src = Mockito.mock(Account.class);
        Account dst = Mockito.mock(Account.class);
        Mockito.when(dst.getAccountId()).thenReturn(99L);

        VolumeDataStoreVO storeVo = Mockito.mock(VolumeDataStoreVO.class);
        Mockito.when(storeVo.getId()).thenReturn(101L);
        Mockito.when(volumeStoreDao.findByVolume(7L)).thenReturn(storeVo);
        Mockito.when(volumeStoreDao.update(Mockito.eq(101L), Mockito.any())).thenReturn(false);

        VolumeInfo info = Mockito.mock(VolumeInfo.class);
        DataStore secStore = Mockito.mock(DataStore.class);
        Mockito.when(info.getDataStore()).thenReturn(secStore);
        Mockito.when(secStore.getUri()).thenReturn("nfs://store");
        Mockito.when(info.getPath()).thenReturn("/template/2/7/file.qcow2");
        Mockito.when(volFactory.getVolume(7L, DataStoreRole.Image)).thenReturn(info);

        EndPoint ssvm = Mockito.mock(EndPoint.class);
        Mockito.when(epSelector.findSsvm(3L)).thenReturn(ssvm);
        Answer ok = Mockito.mock(Answer.class);
        Mockito.when(ok.getResult()).thenReturn(true);
        Mockito.when(ssvm.sendMessage(Mockito.any())).thenReturn(ok);

        service.moveVolumeOnSecondaryStorageToAnotherAccount(volume, src, dst);
    }

    // --- god-class delegation smoke test ----------------------------------

    @Test
    public void godClassDelegatesHandleVolumeSyncToService() {
        VolumeServiceImpl impl = new VolumeServiceImpl();
        SecondaryStorageVolumeService svc = Mockito.mock(SecondaryStorageVolumeService.class);
        impl.secondaryStorageVolumeService = svc;

        DataStore ds = Mockito.mock(DataStore.class);
        impl.handleVolumeSync(ds);

        @SuppressWarnings("rawtypes")
        ArgumentCaptor<BiFunction> captor = ArgumentCaptor.forClass(BiFunction.class);
        Mockito.verify(svc).handleVolumeSync(Mockito.eq(ds), captor.capture());
        Assert.assertNotNull(captor.getValue());
    }

    @Test
    public void godClassDelegatesMoveToService() {
        VolumeServiceImpl impl = new VolumeServiceImpl();
        SecondaryStorageVolumeService svc = Mockito.mock(SecondaryStorageVolumeService.class);
        impl.secondaryStorageVolumeService = svc;

        Volume v = Mockito.mock(Volume.class);
        Account a1 = Mockito.mock(Account.class);
        Account a2 = Mockito.mock(Account.class);
        impl.moveVolumeOnSecondaryStorageToAnotherAccount(v, a1, a2);

        Mockito.verify(svc).moveVolumeOnSecondaryStorageToAnotherAccount(v, a1, a2);
    }

    // --- helper plumbing checks -------------------------------------------

    @Test
    public void serviceFieldsAreNotNullAfterSetup() {
        Assert.assertNotNull(service.volumeStoreDao);
        Assert.assertNotNull(service.volDao);
        Assert.assertNotNull(service.volFactory);
        Assert.assertNotNull(service.epSelector);
        Assert.assertNotNull(service.resourceLimitMgr);
        Assert.assertNotNull(service.accountMgr);
        Assert.assertNotNull(service.alertMgr);
    }

    @Test
    public void handleVolumeSyncSkipsBodyWhenLockNotAcquired() {
        // mock the lock so lock() returns false -> body should not run
        SecondaryStorageVolumeServiceImpl spy = Mockito.spy(service);
        Mockito.when(store.getId()).thenReturn(99L);

        try (MockedStatic<GlobalLock> mocked = Mockito.mockStatic(GlobalLock.class)) {
            GlobalLock noLock = Mockito.mock(GlobalLock.class);
            Mockito.when(noLock.lock(Mockito.anyInt())).thenReturn(false);
            mocked.when(() -> GlobalLock.getInternLock(Mockito.anyString())).thenReturn(noLock);

            spy.handleVolumeSync(store, noopDownloader());

            // listVolume must never have been called
            Mockito.verify(spy, Mockito.never()).listVolume(Mockito.any());
            Mockito.verify(noLock).releaseRef();
        }
    }

    @Test
    public void handleVolumeSyncIgnoresEmptyStoreAndEmptyDb() {
        SecondaryStorageVolumeServiceImpl spy = Mockito.spy(service);
        Mockito.doReturn(new HashMap<Long, TemplateProp>()).when(spy).listVolume(store);
        Mockito.when(store.getId()).thenReturn(11L);
        Mockito.when(volumeStoreDao.listByStoreId(11L)).thenReturn(Collections.<VolumeDataStoreVO>emptyList());

        try (MockedStatic<GlobalLock> ignored = stubGlobalLock()) {
            spy.handleVolumeSync(store, noopDownloader());
        }

        // nothing to update or delete
        Mockito.verify(volumeStoreDao, Mockito.never()).update(Mockito.anyLong(), Mockito.any());
        Mockito.verify(volumeStoreDao, Mockito.never()).remove(Mockito.anyLong());
    }
}
