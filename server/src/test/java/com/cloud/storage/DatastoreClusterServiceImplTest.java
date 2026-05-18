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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.command.admin.storage.SyncStoragePoolCmd;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.command.SyncVolumePathAnswer;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.ModifyStoragePoolAnswer;
import com.cloud.agent.api.StoragePoolInfo;
import com.cloud.agent.api.to.DataTO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.dao.StoragePoolAndAccessGroupMapDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Focused tests for {@link DatastoreClusterServiceImpl} -- the Phase 4
 * (slice 7) extraction of VMware datastore-cluster sync out of
 * {@link StorageManagerImpl}. Covers happy-path reconcile + the
 * defensive validation paths (no host, wrong type, child not Up).
 */
@RunWith(MockitoJUnitRunner.class)
public class DatastoreClusterServiceImplTest {

    /** Concrete stub to avoid ByteBuddy/Java-25 inline-mock issues with BaseCmd hierarchy. */
    private static final class StubSyncStoragePoolCmd extends SyncStoragePoolCmd {
        private final long id;
        StubSyncStoragePoolCmd(long id) { this.id = id; }
        @Override public Long getPoolId() { return id; }
    }

    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private StoragePoolTagsDao storagePoolTagsDao;
    @Mock
    private StoragePoolHostDao storagePoolHostDao;
    @Mock
    private StoragePoolAndAccessGroupMapDao storagePoolAccessGroupMapDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private AgentManager agentMgr;
    @Mock
    private DataStoreManager dataStoreMgr;
    @Mock
    private VolumeDataFactory volFactory;

    @InjectMocks
    private DatastoreClusterServiceImpl service;

    private static final long POOL_ID = 42L;
    private static final long HOST_ID = 7L;

    private SyncStoragePoolCmd cmd;
    private StoragePoolVO clusterPool;
    private ModifyStoragePoolAnswer mspAnswer;
    private StoragePoolInfo poolInfo;

    @Before
    public void setUp() {
        cmd = new StubSyncStoragePoolCmd(POOL_ID);

        clusterPool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(clusterPool.getId()).thenReturn(POOL_ID);
        Mockito.when(clusterPool.getPoolType()).thenReturn(Storage.StoragePoolType.DatastoreCluster);
        Mockito.when(clusterPool.getStatus()).thenReturn(StoragePoolStatus.Up);

        mspAnswer = Mockito.mock(ModifyStoragePoolAnswer.class);
        poolInfo = Mockito.mock(StoragePoolInfo.class);
        Mockito.when(mspAnswer.getPoolInfo()).thenReturn(poolInfo);
        Mockito.when(poolInfo.getLocalPath()).thenReturn("/mnt/path");
        Mockito.when(poolInfo.getCapacityBytes()).thenReturn(1000L);
        Mockito.when(poolInfo.getAvailableBytes()).thenReturn(400L);
        Mockito.when(mspAnswer.getDatastoreClusterChildren()).thenReturn(Collections.emptyList());
        Mockito.when(mspAnswer.getResult()).thenReturn(true);
    }

    @Test
    public void syncStoragePoolPoolNotFoundThrows() {
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class, () -> service.syncStoragePool(cmd));
    }

    @Test
    public void syncStoragePoolWrongTypeThrows() {
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(clusterPool);
        Mockito.when(clusterPool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        assertThrows(InvalidParameterValueException.class, () -> service.syncStoragePool(cmd));
    }

    @Test
    public void syncStoragePoolNotUpThrows() {
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(clusterPool);
        Mockito.when(clusterPool.getStatus()).thenReturn(StoragePoolStatus.Maintenance);
        assertThrows(InvalidParameterValueException.class, () -> service.syncStoragePool(cmd));
    }

    @Test
    public void syncStoragePoolNoConnectedHostsThrows() {
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(clusterPool);
        Mockito.when(storagePoolHostDao.findHostsConnectedToPools(Mockito.anyList())).thenReturn(Collections.emptyList());
        assertThrows(CloudRuntimeException.class, () -> service.syncStoragePool(cmd));
    }

    @Test
    public void syncStoragePoolNullAnswerThrows() {
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(clusterPool);
        Mockito.when(storagePoolHostDao.findHostsConnectedToPools(Mockito.anyList())).thenReturn(Arrays.asList(HOST_ID));
        Mockito.when(agentMgr.easySend(eq(HOST_ID), Mockito.any())).thenReturn(null);
        assertThrows(CloudRuntimeException.class, () -> service.syncStoragePool(cmd));
    }

    @Test
    public void syncStoragePoolAnswerFailedThrows() {
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(clusterPool);
        Mockito.when(storagePoolHostDao.findHostsConnectedToPools(Mockito.anyList())).thenReturn(Arrays.asList(HOST_ID));
        Answer bad = Mockito.mock(Answer.class);
        Mockito.when(bad.getResult()).thenReturn(false);
        Mockito.when(agentMgr.easySend(eq(HOST_ID), Mockito.any())).thenReturn(bad);
        Mockito.when(hostDao.findById(HOST_ID)).thenReturn(Mockito.mock(HostVO.class));
        assertThrows(CloudRuntimeException.class, () -> service.syncStoragePool(cmd));
    }

    @Test
    public void syncStoragePoolHappyPathReturnsPrimaryDataStoreInfo() {
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(clusterPool);
        Mockito.when(storagePoolHostDao.findHostsConnectedToPools(Mockito.anyList())).thenReturn(Arrays.asList(HOST_ID));
        Mockito.when(agentMgr.easySend(eq(HOST_ID), Mockito.any())).thenReturn(mspAnswer);
        Mockito.when(storagePoolDao.listChildStoragePoolsInDatastoreCluster(POOL_ID)).thenReturn(Collections.emptyList());
        Mockito.when(storagePoolTagsDao.findStoragePoolTags(POOL_ID)).thenReturn(Collections.emptyList());

        PrimaryDataStoreInfo expected = Mockito.mock(PrimaryDataStoreInfo.class,
                Mockito.withSettings().extraInterfaces(DataStore.class));
        Mockito.doReturn(expected).when(dataStoreMgr).getDataStore(POOL_ID, DataStoreRole.Primary);

        StoragePool result = service.syncStoragePool(cmd);
        assertEquals(expected, result);
        // Manager-side state was refreshed for the parent pool
        Mockito.verify(storagePoolHostDao).findByPoolHost(POOL_ID, HOST_ID);
    }

    @Test
    public void validateChildDatastoresToBeAddedRejectsNonUpChild() {
        StoragePoolInfo childInfo = Mockito.mock(StoragePoolInfo.class);
        Mockito.when(childInfo.getUuid()).thenReturn("uuid-1");
        ModifyStoragePoolAnswer childAns = Mockito.mock(ModifyStoragePoolAnswer.class);
        Mockito.when(childAns.getPoolInfo()).thenReturn(childInfo);
        Mockito.lenient().when(childAns.getPoolType()).thenReturn("VMFS");

        StoragePoolVO existing = Mockito.mock(StoragePoolVO.class);
        Mockito.when(existing.getStatus()).thenReturn(StoragePoolStatus.Maintenance);
        Mockito.when(storagePoolDao.findPoolByUUID("uuid-1")).thenReturn(existing);

        assertThrows(CloudRuntimeException.class,
                () -> service.validateChildDatastoresToBeAddedInUpState(clusterPool, Arrays.asList(childAns)));
    }

    @Test
    public void validateChildDatastoresPassesWhenChildIsUpOrMissing() {
        // child #1: known and Up
        StoragePoolInfo info1 = Mockito.mock(StoragePoolInfo.class);
        Mockito.when(info1.getUuid()).thenReturn("uuid-up");
        ModifyStoragePoolAnswer a1 = Mockito.mock(ModifyStoragePoolAnswer.class);
        Mockito.when(a1.getPoolInfo()).thenReturn(info1);
        Mockito.lenient().when(a1.getPoolType()).thenReturn("VMFS");
        StoragePoolVO existing = Mockito.mock(StoragePoolVO.class);
        Mockito.when(existing.getStatus()).thenReturn(StoragePoolStatus.Up);
        Mockito.when(storagePoolDao.findPoolByUUID("uuid-up")).thenReturn(existing);

        // child #2: unknown -- will be created later, must not throw here
        StoragePoolInfo info2 = Mockito.mock(StoragePoolInfo.class);
        Mockito.when(info2.getUuid()).thenReturn("uuid-new");
        ModifyStoragePoolAnswer a2 = Mockito.mock(ModifyStoragePoolAnswer.class);
        Mockito.when(a2.getPoolInfo()).thenReturn(info2);
        Mockito.when(a2.getPoolType()).thenReturn("VMFS");
        Mockito.when(storagePoolDao.findPoolByUUID("uuid-new")).thenReturn(null);

        service.validateChildDatastoresToBeAddedInUpState(clusterPool, Arrays.asList(a1, a2));
        // no throw, no NFS fallback queried
        Mockito.verify(storagePoolDao, Mockito.never()).findPoolsByStorageType(Mockito.any());
    }

    @Test
    public void updateStoragePoolHostVOAndBytesCreatesRowWhenMissingAndUpdatesBytes() {
        StoragePool pool = Mockito.mock(StoragePool.class);
        Mockito.when(pool.getId()).thenReturn(POOL_ID);
        Mockito.when(storagePoolHostDao.findByPoolHost(POOL_ID, HOST_ID)).thenReturn(null);
        StoragePoolVO poolVO = Mockito.mock(StoragePoolVO.class);
        Mockito.when(poolVO.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(poolVO);

        service.updateStoragePoolHostVOAndBytes(pool, HOST_ID, mspAnswer);

        ArgumentCaptor<StoragePoolHostVO> capt = ArgumentCaptor.forClass(StoragePoolHostVO.class);
        Mockito.verify(storagePoolHostDao).persist(capt.capture());
        assertEquals("/mnt/path", capt.getValue().getLocalPath());
        Mockito.verify(poolVO).setUsedBytes(600L);
        Mockito.verify(poolVO).setCapacityBytes(1000L);
        Mockito.verify(storagePoolDao).update(POOL_ID, poolVO);
    }

    @Test
    public void updateStoragePoolHostVOAndBytesSkipsBytesForStorPool() {
        StoragePool pool = Mockito.mock(StoragePool.class);
        Mockito.when(pool.getId()).thenReturn(POOL_ID);
        StoragePoolHostVO existing = Mockito.mock(StoragePoolHostVO.class);
        Mockito.when(storagePoolHostDao.findByPoolHost(POOL_ID, HOST_ID)).thenReturn(existing);
        StoragePoolVO poolVO = Mockito.mock(StoragePoolVO.class);
        Mockito.when(poolVO.getPoolType()).thenReturn(Storage.StoragePoolType.StorPool);
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(poolVO);

        service.updateStoragePoolHostVOAndBytes(pool, HOST_ID, mspAnswer);

        Mockito.verify(existing).setLocalPath("/mnt/path");
        Mockito.verify(poolVO, Mockito.never()).setUsedBytes(anyLong());
        Mockito.verify(poolVO, Mockito.never()).setCapacityBytes(anyLong());
        Mockito.verify(storagePoolDao).update(POOL_ID, poolVO);
    }

    @Test
    public void syncDatastoreClusterStoragePoolCreatesNewChildWhenUnknown() {
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(clusterPool);
        Mockito.when(storagePoolDao.listChildStoragePoolsInDatastoreCluster(POOL_ID)).thenReturn(Collections.emptyList());
        Mockito.when(storagePoolTagsDao.findStoragePoolTags(POOL_ID)).thenReturn(Collections.emptyList());
        Mockito.when(storagePoolAccessGroupMapDao.getStorageAccessGroups(POOL_ID)).thenReturn(Collections.emptyList());

        StoragePoolInfo childInfo = Mockito.mock(StoragePoolInfo.class);
        Mockito.when(childInfo.getUuid()).thenReturn("a1b2-uuid-new");
        Mockito.when(childInfo.getName()).thenReturn("child-name");
        Mockito.when(childInfo.getHost()).thenReturn("1.2.3.4");
        Mockito.when(childInfo.getHostPath()).thenReturn("/child");
        Mockito.when(childInfo.getCapacityBytes()).thenReturn(500L);
        Mockito.when(childInfo.getAvailableBytes()).thenReturn(200L);
        Mockito.when(childInfo.getLocalPath()).thenReturn("/loc");
        Mockito.when(clusterPool.getStorageProviderName()).thenReturn("VMware");
        Mockito.when(clusterPool.getDataCenterId()).thenReturn(1L);

        ModifyStoragePoolAnswer childAns = Mockito.mock(ModifyStoragePoolAnswer.class);
        Mockito.when(childAns.getPoolInfo()).thenReturn(childInfo);
        Mockito.when(childAns.getPoolType()).thenReturn("VMFS");

        // child pool isn't in DB
        Mockito.when(storagePoolDao.findByUuid("a1b2-uuid-new")).thenReturn(null);
        // After persist, updateStoragePoolHostVOAndBytes will look the new child up by id (0 for a fresh VO)
        StoragePoolVO persistedChild = Mockito.mock(StoragePoolVO.class);
        Mockito.when(persistedChild.getPoolType()).thenReturn(Storage.StoragePoolType.PreSetup);
        Mockito.when(storagePoolDao.findById(0L)).thenReturn(persistedChild);

        service.syncDatastoreClusterStoragePool(POOL_ID, Arrays.asList(childAns), HOST_ID);

        // Verify createChildDatastoreVO path was taken: persist called with VO + details/tags/SAG
        Mockito.verify(storagePoolDao).persist(Mockito.any(StoragePoolVO.class), Mockito.anyMap(),
                Mockito.anyList(), Mockito.anyBoolean(), Mockito.anyList());
    }

    @Test
    public void syncDatastoreClusterStoragePoolRetainsKnownChild() {
        long childId = 88L;
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(clusterPool);
        StoragePoolVO existingChild = Mockito.mock(StoragePoolVO.class);
        Mockito.when(existingChild.getId()).thenReturn(childId);
        Mockito.when(existingChild.getUuid()).thenReturn("a1b2-uuid-known");
        Mockito.when(existingChild.getParent()).thenReturn(POOL_ID);
        Mockito.when(existingChild.getPoolType()).thenReturn(Storage.StoragePoolType.PreSetup);
        Mockito.when(storagePoolDao.listChildStoragePoolsInDatastoreCluster(POOL_ID)).thenReturn(Arrays.asList(existingChild));
        Mockito.when(storagePoolDao.findById(childId)).thenReturn(existingChild);

        StoragePoolInfo childInfo = Mockito.mock(StoragePoolInfo.class);
        Mockito.when(childInfo.getUuid()).thenReturn("a1b2-uuid-known");
        Mockito.when(childInfo.getLocalPath()).thenReturn("/x");
        ModifyStoragePoolAnswer childAns = Mockito.mock(ModifyStoragePoolAnswer.class);
        Mockito.when(childAns.getPoolInfo()).thenReturn(childInfo);
        Mockito.lenient().when(childAns.getPoolType()).thenReturn("VMFS");

        Mockito.when(storagePoolDao.findByUuid("a1b2-uuid-known")).thenReturn(existingChild);

        service.syncDatastoreClusterStoragePool(POOL_ID, Arrays.asList(childAns), HOST_ID);

        // child not re-parented (already correct), no persist of a new VO
        Mockito.verify(storagePoolDao, Mockito.never()).persist(Mockito.any(StoragePoolVO.class), Mockito.anyMap(),
                Mockito.anyList(), Mockito.anyBoolean(), Mockito.anyList());
        // No remove-from-cluster either (the answer covers the only child)
        Mockito.verify(volumeDao, Mockito.never()).findNonDestroyedVolumesByPoolId(anyLong());
    }

    @Test
    public void syncDatastoreClusterStoragePoolClearsParentForRemovedChild() {
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(clusterPool);
        StoragePoolVO removedChild = Mockito.mock(StoragePoolVO.class);
        Mockito.when(removedChild.getUuid()).thenReturn("uuid-gone");
        Mockito.when(removedChild.getId()).thenReturn(99L);
        Mockito.when(storagePoolDao.listChildStoragePoolsInDatastoreCluster(POOL_ID))
                .thenReturn(Arrays.asList(removedChild));
        Mockito.when(storagePoolDao.findPoolByUUID("uuid-gone")).thenReturn(removedChild);
        Mockito.when(volumeDao.findNonDestroyedVolumesByPoolId(99L)).thenReturn(new ArrayList<>());

        // Empty answer list => all known children are "removed"
        service.syncDatastoreClusterStoragePool(POOL_ID, Collections.emptyList(), HOST_ID);

        Mockito.verify(removedChild).setParent(0L);
        Mockito.verify(storagePoolDao).update(99L, removedChild);
    }

    @Test
    public void syncDatastoreClusterStoragePoolReParentsMisplacedChild() {
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(clusterPool);
        Mockito.when(storagePoolDao.listChildStoragePoolsInDatastoreCluster(POOL_ID)).thenReturn(Collections.emptyList());
        Mockito.when(storagePoolTagsDao.findStoragePoolTags(POOL_ID)).thenReturn(Collections.emptyList());

        StoragePoolVO misplaced = Mockito.mock(StoragePoolVO.class);
        Mockito.when(misplaced.getId()).thenReturn(123L);
        Mockito.lenient().when(misplaced.getUuid()).thenReturn("a1b2-uuid-x");
        Mockito.when(misplaced.getParent()).thenReturn(999L);  // wrong parent
        Mockito.when(misplaced.getPoolType()).thenReturn(Storage.StoragePoolType.PreSetup);

        StoragePoolInfo childInfo = Mockito.mock(StoragePoolInfo.class);
        Mockito.when(childInfo.getUuid()).thenReturn("a1b2-uuid-x");
        Mockito.when(childInfo.getName()).thenReturn("x");
        ModifyStoragePoolAnswer childAns = Mockito.mock(ModifyStoragePoolAnswer.class);
        Mockito.when(childAns.getPoolInfo()).thenReturn(childInfo);
        Mockito.lenient().when(childAns.getPoolType()).thenReturn("VMFS");
        Mockito.when(childInfo.getLocalPath()).thenReturn("/p");
        Mockito.when(childInfo.getCapacityBytes()).thenReturn(0L);
        Mockito.when(childInfo.getAvailableBytes()).thenReturn(0L);

        Mockito.when(storagePoolDao.findByUuid("a1b2-uuid-x")).thenReturn(misplaced);
        Mockito.when(storagePoolDao.findById(123L)).thenReturn(misplaced);

        service.syncDatastoreClusterStoragePool(POOL_ID, Arrays.asList(childAns), HOST_ID);

        Mockito.verify(misplaced).setParent(POOL_ID);
        // update is called once for the re-parent and again via updateStoragePoolHostVOAndBytes
        Mockito.verify(storagePoolDao, Mockito.atLeastOnce()).update(123L, misplaced);
    }

    @Test
    public void handleRemoveSkipsVolumesWithoutInstance() {
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(clusterPool);

        StoragePoolVO removed = Mockito.mock(StoragePoolVO.class);
        Mockito.when(removed.getId()).thenReturn(55L);
        Mockito.when(removed.getUuid()).thenReturn("uuid-rm");
        Mockito.when(storagePoolDao.listChildStoragePoolsInDatastoreCluster(POOL_ID)).thenReturn(Arrays.asList(removed));
        Mockito.when(storagePoolDao.findPoolByUUID("uuid-rm")).thenReturn(removed);

        // Volume with instance = null is filtered out
        VolumeVO v1 = Mockito.mock(VolumeVO.class);
        Mockito.when(v1.getInstanceId()).thenReturn(null);
        Mockito.lenient().when(v1.getState()).thenReturn(Volume.State.Ready);
        // Volume not Ready is filtered out
        VolumeVO v2 = Mockito.mock(VolumeVO.class);
        Mockito.when(v2.getInstanceId()).thenReturn(1L);
        Mockito.when(v2.getState()).thenReturn(Volume.State.Destroy);

        List<VolumeVO> volumes = new ArrayList<>(Arrays.asList(v1, v2));
        Mockito.when(volumeDao.findNonDestroyedVolumesByPoolId(55L)).thenReturn(volumes);

        service.syncDatastoreClusterStoragePool(POOL_ID, Collections.emptyList(), HOST_ID);

        // No SyncVolumePathCommand sent (nothing eligible)
        Mockito.verify(agentMgr, Mockito.never()).easySend(anyLong(), Mockito.any());
        Mockito.verify(removed).setParent(0L);
    }

    @Test
    public void handleRemoveThrowsWhenSyncVolumePathReturnsNull() {
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(clusterPool);

        StoragePoolVO removed = Mockito.mock(StoragePoolVO.class);
        Mockito.when(removed.getId()).thenReturn(55L);
        Mockito.when(removed.getUuid()).thenReturn("uuid-rm");
        Mockito.when(storagePoolDao.listChildStoragePoolsInDatastoreCluster(POOL_ID)).thenReturn(Arrays.asList(removed));
        Mockito.when(storagePoolDao.findPoolByUUID("uuid-rm")).thenReturn(removed);

        VolumeVO v = Mockito.mock(VolumeVO.class);
        Mockito.when(v.getId()).thenReturn(11L);
        Mockito.when(v.getInstanceId()).thenReturn(2L);
        Mockito.when(v.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(v.getDeviceId()).thenReturn(0L);
        Mockito.when(v.getPath()).thenReturn("/p");
        Mockito.when(v.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        Mockito.when(volumeDao.findNonDestroyedVolumesByPoolId(55L)).thenReturn(new ArrayList<>(Arrays.asList(v)));

        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getHostId()).thenReturn(77L);
        Mockito.when(vmInstanceDao.findById(2L)).thenReturn(vm);
        Mockito.when(hostDao.findById(77L)).thenReturn(Mockito.mock(HostVO.class));

        VolumeInfo volumeInfo = Mockito.mock(VolumeInfo.class);
        DataTO dataTO = Mockito.mock(DataTO.class);
        Mockito.when(volumeInfo.getTO()).thenReturn(dataTO);
        Mockito.when(volFactory.getVolume(11L)).thenReturn(volumeInfo);

        Mockito.when(agentMgr.easySend(eq(77L), Mockito.any())).thenReturn(null);

        assertThrows(CloudRuntimeException.class,
                () -> service.syncDatastoreClusterStoragePool(POOL_ID, Collections.emptyList(), HOST_ID));
    }

    @Test
    public void getExistingPoolByUuidConvertsHexFormToCanonicalUuid() {
        // 32-char hex form (no dashes). The conversion path must split it
        // into two 16-char halves, build a UUID, and call findByUuid with
        // the canonical dashed form.
        String hexForm = "0123456789abcdef0123456789abcdef";
        Mockito.when(storagePoolDao.findByUuid(Mockito.contains("-"))).thenReturn(null);

        StoragePoolVO got = service.getExistingPoolByUuid(hexForm);

        assertEquals(null, got);
        ArgumentCaptor<String> capt = ArgumentCaptor.forClass(String.class);
        Mockito.verify(storagePoolDao).findByUuid(capt.capture());
        assertTrue("hex form should be normalised to a dashed uuid", capt.getValue().contains("-"));
    }

    @Test
    public void getExistingPoolByUuidPassesThroughCanonicalUuid() {
        String canonical = "deadbeef-0000-1111-2222-3333deadbeef";
        StoragePoolVO mock = Mockito.mock(StoragePoolVO.class);
        Mockito.when(storagePoolDao.findByUuid(canonical)).thenReturn(mock);

        StoragePoolVO got = service.getExistingPoolByUuid(canonical);

        assertNotNull(got);
        Mockito.verify(storagePoolDao).findByUuid(canonical);
    }

    @Test
    public void unusedNoopBranches() {
        // Cover the assert + answer-true branch of the SyncVolumePath path,
        // and verify the answer payload-extraction code (volumePath, chainInfo,
        // datastoreName) doesn't crash with nulls.
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(clusterPool);

        StoragePoolVO removed = Mockito.mock(StoragePoolVO.class);
        Mockito.when(removed.getId()).thenReturn(55L);
        Mockito.when(removed.getUuid()).thenReturn("uuid-rm");
        Mockito.when(storagePoolDao.listChildStoragePoolsInDatastoreCluster(POOL_ID)).thenReturn(Arrays.asList(removed));
        Mockito.when(storagePoolDao.findPoolByUUID("uuid-rm")).thenReturn(removed);

        VolumeVO v = Mockito.mock(VolumeVO.class);
        Mockito.when(v.getId()).thenReturn(11L);
        Mockito.when(v.getInstanceId()).thenReturn(2L);
        Mockito.when(v.getState()).thenReturn(Volume.State.Ready);
        Mockito.when(v.getDeviceId()).thenReturn(0L);
        Mockito.when(v.getPath()).thenReturn("/p");
        Mockito.when(v.getVolumeType()).thenReturn(Volume.Type.DATADISK);
        Mockito.when(volumeDao.findNonDestroyedVolumesByPoolId(55L)).thenReturn(new ArrayList<>(Arrays.asList(v)));
        Mockito.when(volumeDao.findById(11L)).thenReturn(v);

        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(vm.getHostId()).thenReturn(77L);
        Mockito.when(vmInstanceDao.findById(2L)).thenReturn(vm);
        Mockito.when(hostDao.findById(77L)).thenReturn(Mockito.mock(HostVO.class));

        VolumeInfo volumeInfo = Mockito.mock(VolumeInfo.class);
        DataTO dataTO = Mockito.mock(DataTO.class);
        Mockito.when(volumeInfo.getTO()).thenReturn(dataTO);
        Mockito.when(volFactory.getVolume(11L)).thenReturn(volumeInfo);

        SyncVolumePathAnswer good = Mockito.mock(SyncVolumePathAnswer.class);
        Mockito.when(good.getResult()).thenReturn(true);
        Mockito.when(good.getContextParam("datastoreName")).thenReturn(null);
        Mockito.when(good.getContextParam("volumePath")).thenReturn(null);
        Mockito.when(good.getContextParam("chainInfo")).thenReturn(null);
        Mockito.when(agentMgr.easySend(eq(77L), Mockito.any())).thenReturn(good);

        service.syncDatastoreClusterStoragePool(POOL_ID, Collections.emptyList(), HOST_ID);

        Mockito.verify(volumeDao).update(11L, v);
        Mockito.verify(removed).setParent(0L);
    }

    @Test
    public void validateChildDatastoresFallsBackToNfsLookup() {
        StoragePoolInfo childInfo = Mockito.mock(StoragePoolInfo.class);
        Mockito.when(childInfo.getUuid()).thenReturn("uuid-nfs");
        Mockito.when(childInfo.getName()).thenReturn("uuidnfs");
        ModifyStoragePoolAnswer childAns = Mockito.mock(ModifyStoragePoolAnswer.class);
        Mockito.when(childAns.getPoolInfo()).thenReturn(childInfo);
        Mockito.when(childAns.getPoolType()).thenReturn("nfs");

        Mockito.when(storagePoolDao.findPoolByUUID("uuid-nfs")).thenReturn(null);

        StoragePoolVO nfsPool = Mockito.mock(StoragePoolVO.class);
        Mockito.when(nfsPool.getUuid()).thenReturn("u-u-i-d-nfs");
        Mockito.when(nfsPool.getStatus()).thenReturn(StoragePoolStatus.Maintenance);
        Mockito.when(storagePoolDao.findPoolsByStorageType(Storage.StoragePoolType.NetworkFilesystem))
                .thenReturn(Arrays.asList(nfsPool));

        // The NFS lookup matches when the name equals the uuid with dashes stripped.
        // "u-u-i-d-nfs".replaceAll("-", "") == "uuidnfs" -- matches the answer.name.
        assertThrows(CloudRuntimeException.class,
                () -> service.validateChildDatastoresToBeAddedInUpState(clusterPool, Arrays.asList(childAns)));
    }
}
