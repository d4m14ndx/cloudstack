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
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.StoragePoolAllocator;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.api.query.dao.StoragePoolJoinDao;
import com.cloud.api.query.vo.StoragePoolJoinVO;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.HypervisorCapabilitiesVO;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.org.Cluster;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StoragePoolStatus;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.vm.DiskProfile;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VolumeStoragePoolMigrationServiceImplTest {

    @Mock
    private AccountManager _accountMgr;
    @Mock
    private VolumeDao _volumeDao;
    @Mock
    private VMInstanceDao _vmInstanceDao;
    @Mock
    private HostDao _hostDao;
    @Mock
    private HypervisorCapabilitiesDao _hypervisorCapabilitiesDao;
    @Mock
    private PrimaryDataStoreDao _poolDao;
    @Mock
    private ClusterDao _clusterDao;
    @Mock
    private DiskOfferingDao _diskOfferingDao;
    @Mock
    private StoragePoolJoinDao _poolJoinDao;
    @Mock
    private StoragePoolAllocator storagePoolAllocator;
    @Mock
    private Account callerAccount;
    @Mock
    private User callerUser;

    @InjectMocks
    private VolumeStoragePoolMigrationServiceImpl service = new VolumeStoragePoolMigrationServiceImpl();

    @Before
    public void setUp() {
        lenient().when(callerAccount.getId()).thenReturn(7L);
        CallContext.register(callerUser, callerAccount);
        ReflectionTestUtils.setField(service, "_storagePoolAllocators", List.of(storagePoolAllocator));
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    @Test
    public void listStoragePoolsForMigrationOfVolumeInternalRejectsNonRootCallerWhenAccountCheckRequired() {
        when(_accountMgr.isRootAdmin(7L)).thenReturn(false);

        try {
            service.listStoragePoolsForMigrationOfVolumeInternal(10L, null, null, null, null, false, true, false, "fast");
            Assert.fail("Expected PermissionDeniedException");
        } catch (PermissionDeniedException e) {
            Assert.assertEquals("No permission to migrate volume, only root admin can migrate a volume", e.getMessage());
        }

        verify(_volumeDao, never()).findById(10L);
    }

    @Test
    public void listStoragePoolsForMigrationOfVolumeInternalSkipsAccountCheckWhenBypassAccountCheckTrue() {
        VolumeVO volume = volume(10L, Volume.State.Allocated, null, 100L, 20L, 30L);
        when(_volumeDao.findById(10L)).thenReturn(volume);

        Pair<List<? extends StoragePool>, List<? extends StoragePool>> result =
                service.listStoragePoolsForMigrationOfVolumeInternal(10L, null, null, null, null, false, true, true, null);

        Assert.assertTrue(result.first().isEmpty());
        Assert.assertTrue(result.second().isEmpty());
        verify(_accountMgr, never()).isRootAdmin(Mockito.anyLong());
        verify(_volumeDao).findById(10L);
    }

    @Test
    public void listStoragePoolsForMigrationOfVolumeInternalThrowsWhenVolumeMissing() {
        when(_volumeDao.findById(10L)).thenReturn(null);

        try {
            service.listStoragePoolsForMigrationOfVolumeInternal(10L, null, null, null, null, false, true, true, null);
            Assert.fail("Expected InvalidParameterValueException");
        } catch (InvalidParameterValueException e) {
            Assert.assertTrue(e.getMessage().contains("Unable to find volume with specified id."));
        }
    }

    @Test
    public void listStoragePoolsForMigrationOfVolumeInternalReturnsEmptyForVolumeNotReady() {
        VolumeVO volume = volume(10L, Volume.State.Allocated, null, 100L, 20L, 30L);
        when(_volumeDao.findById(10L)).thenReturn(volume);

        Pair<List<? extends StoragePool>, List<? extends StoragePool>> result =
                service.listStoragePoolsForMigrationOfVolumeInternal(10L, null, null, null, null, false, true, true, null);

        Assert.assertTrue(result.first().isEmpty());
        Assert.assertTrue(result.second().isEmpty());
        verify(_poolDao, never()).findById(Mockito.anyLong());
    }

    @Test
    public void listStoragePoolsForMigrationOfVolumeInternalReturnsEmptyWhenRunningVmLacksStorageMotion() {
        VolumeVO volume = volume(10L, Volume.State.Ready, 20L, 100L, 30L, 40L);
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        HostVO host = Mockito.mock(HostVO.class);
        HypervisorCapabilitiesVO capabilities = Mockito.mock(HypervisorCapabilitiesVO.class);
        when(_volumeDao.findById(10L)).thenReturn(volume);
        when(_vmInstanceDao.findById(20L)).thenReturn(vm);
        when(vm.getState()).thenReturn(State.Running);
        when(vm.getHostId()).thenReturn(50L);
        when(_hostDao.findById(50L)).thenReturn(host);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(host.getHypervisorVersion()).thenReturn("8.0");
        when(_hypervisorCapabilitiesDao.findByHypervisorTypeAndVersion(HypervisorType.KVM, "8.0")).thenReturn(capabilities);
        when(capabilities.isStorageMotionSupported()).thenReturn(false);

        Pair<List<? extends StoragePool>, List<? extends StoragePool>> result =
                service.listStoragePoolsForMigrationOfVolumeInternal(10L, null, null, null, null, false, true, true, null);

        Assert.assertTrue(result.first().isEmpty());
        Assert.assertTrue(result.second().isEmpty());
        verify(_poolDao, never()).findById(Mockito.anyLong());
    }

    @Test
    public void getHypervisorTypeUsesVmHypervisorWhenVmPresent() {
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        StoragePool sourcePool = storagePool(100L, 0L);
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);

        HypervisorType result = service.getHypervisorType(vm, sourcePool);

        Assert.assertEquals(HypervisorType.KVM, result);
        verify(_poolDao, never()).findById(Mockito.anyLong());
    }

    @Test
    public void getHypervisorTypeUsesClusterHypervisorForDetachedClusterScopedPool() {
        StoragePool sourcePool = storagePool(100L, 0L);
        StoragePoolVO sourcePoolVo = Mockito.mock(StoragePoolVO.class);
        ClusterVO cluster = Mockito.mock(ClusterVO.class);
        when(_poolDao.findById(100L)).thenReturn(sourcePoolVo);
        when(sourcePoolVo.getScope()).thenReturn(ScopeType.CLUSTER);
        when(sourcePoolVo.getClusterId()).thenReturn(200L);
        when(_clusterDao.findById(200L)).thenReturn(cluster);
        when(cluster.getHypervisorType()).thenReturn(HypervisorType.KVM);

        HypervisorType result = service.getHypervisorType(null, sourcePool);

        Assert.assertEquals(HypervisorType.KVM, result);
    }

    @Test
    public void getVolumeVmHostClustersUsesCurrentHostBeforeLastHost() {
        StoragePool sourcePool = storagePool(100L, 0L);
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        HostVO currentHost = Mockito.mock(HostVO.class);
        ClusterVO sourceCluster = Mockito.mock(ClusterVO.class);
        when(sourcePool.getClusterId()).thenReturn(300L);
        when(vm.getHostId()).thenReturn(400L);
        lenient().when(vm.getLastHostId()).thenReturn(500L);
        when(_hostDao.findById(400L)).thenReturn(currentHost);
        when(_clusterDao.findById(300L)).thenReturn(sourceCluster);

        Pair<Host, List<Cluster>> result = service.getVolumeVmHostClusters(sourcePool, vm, HypervisorType.KVM);

        Assert.assertSame(currentHost, result.first());
        Assert.assertEquals(List.of(sourceCluster), result.second());
        verify(_hostDao, never()).findById(500L);
    }

    @Test
    public void getVolumeVmHostClustersFallsBackToLastHostAndZoneClusters() {
        StoragePool attachedSourcePool = storagePool(100L, 0L);
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        HostVO lastHost = Mockito.mock(HostVO.class);
        ClusterVO lastHostCluster = Mockito.mock(ClusterVO.class);
        when(attachedSourcePool.getClusterId()).thenReturn(null);
        when(vm.getHostId()).thenReturn(null);
        when(vm.getLastHostId()).thenReturn(500L);
        when(_hostDao.findById(500L)).thenReturn(lastHost);
        when(lastHost.getClusterId()).thenReturn(600L);
        when(_clusterDao.findById(600L)).thenReturn(lastHostCluster);

        Pair<Host, List<Cluster>> attachedResult =
                service.getVolumeVmHostClusters(attachedSourcePool, vm, HypervisorType.KVM);

        Assert.assertSame(lastHost, attachedResult.first());
        Assert.assertEquals(List.of(lastHostCluster), attachedResult.second());

        StoragePool detachedSourcePool = storagePool(101L, 0L);
        ClusterVO zoneCluster = Mockito.mock(ClusterVO.class);
        when(detachedSourcePool.getClusterId()).thenReturn(null);
        when(detachedSourcePool.getDataCenterId()).thenReturn(700L);
        when(_clusterDao.listByDcHyType(700L, HypervisorType.KVM.toString())).thenReturn(List.of(zoneCluster));

        Pair<Host, List<Cluster>> detachedResult =
                service.getVolumeVmHostClusters(detachedSourcePool, null, HypervisorType.KVM);

        Assert.assertNull(detachedResult.first());
        Assert.assertEquals(List.of(zoneCluster), detachedResult.second());
    }

    @Test
    public void getAllStoragePoolCompatibleWithVolumeSourceStoragePoolMergesZoneWideAndClusterPools() {
        StoragePool sourcePool = storagePool(100L, 0L);
        ClusterVO clusterOne = cluster(200L, 20L);
        ClusterVO clusterTwo = cluster(300L, 30L);
        StoragePoolVO zonePool = storagePoolVo(400L, 0L);
        StoragePoolVO clusterPool = storagePoolVo(500L, 0L);
        when(sourcePool.getDataCenterId()).thenReturn(10L);
        when(_poolDao.findZoneWideStoragePoolsByHypervisor(10L, HypervisorType.KVM, "fast"))
                .thenReturn(List.of(zonePool));
        when(_poolDao.findPoolsInClusters(List.of(200L, 300L), "fast")).thenReturn(List.of(clusterPool));

        List<? extends StoragePool> result = service.getAllStoragePoolCompatibleWithVolumeSourceStoragePool(
                sourcePool, HypervisorType.KVM, List.of(clusterOne, clusterTwo), "fast");

        Assert.assertEquals(List.of(zonePool, clusterPool), result);
    }

    @Test
    public void findAllSuitableStoragePoolsForVmKeepsSharedPoolsAndLocalPoolOnVmHostOnly() {
        VolumeVO volume = volume(10L, Volume.State.Ready, 20L, 100L, 30L, 40L);
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        HostVO vmHost = Mockito.mock(HostVO.class);
        ClusterVO sourceCluster = cluster(300L, 20L);
        DiskOfferingVO diskOffering = diskOffering(30L, new String[0]);
        StoragePool sharedPool = storagePool(400L, 0L);
        StoragePool localSameHostPool = storagePool(500L, 0L);
        StoragePool localOtherHostPool = storagePool(600L, 0L);
        StoragePool neitherSharedNorLocalPool = storagePool(700L, 0L);
        when(_diskOfferingDao.findById(30L)).thenReturn(diskOffering);
        when(vmHost.getPrivateIpAddress()).thenReturn("10.0.0.5");
        when(sharedPool.isShared()).thenReturn(true);
        when(localSameHostPool.isLocal()).thenReturn(true);
        when(localSameHostPool.getHostAddress()).thenReturn("10.0.0.5");
        when(localOtherHostPool.isLocal()).thenReturn(true);
        when(localOtherHostPool.getHostAddress()).thenReturn("10.0.0.6");
        when(storagePoolAllocator.allocateToPool(any(DiskProfile.class), any(VirtualMachineProfile.class),
                any(DataCenterDeployment.class), any(ExcludeList.class), anyInt(), anyBoolean(), eq("fast")))
                .thenReturn(Arrays.asList(sharedPool, localSameHostPool, localOtherHostPool, neitherSharedNorLocalPool));

        List<StoragePool> result = service.findAllSuitableStoragePoolsForVm(
                volume, 30L, null, null, null, vm, vmHost, new ExcludeList(), sourceCluster, HypervisorType.KVM, true, "fast");

        Assert.assertEquals(List.of(sharedPool, localSameHostPool), result);
    }

    @Test
    public void findAllSuitableStoragePoolsForVmAppliesDiskOfferingOverrideSizing() {
        VolumeVO volume = volume(10L, Volume.State.Ready, 20L, 100L, 30L, 40L);
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        ClusterVO sourceCluster = cluster(300L, 20L);
        DiskOfferingVO diskOffering = diskOffering(31L, new String[0]);
        when(_diskOfferingDao.findById(31L)).thenReturn(diskOffering);
        ArgumentCaptor<DiskProfile> diskProfileCaptor = ArgumentCaptor.forClass(DiskProfile.class);
        when(storagePoolAllocator.allocateToPool(diskProfileCaptor.capture(), any(VirtualMachineProfile.class),
                any(DataCenterDeployment.class), any(ExcludeList.class), anyInt(), anyBoolean(), isNull()))
                .thenReturn(new ArrayList<>());

        service.findAllSuitableStoragePoolsForVm(
                volume, 31L, 8192L, 1000L, 2000L, vm, null, new ExcludeList(), sourceCluster, HypervisorType.KVM, false, null);

        DiskProfile diskProfile = diskProfileCaptor.getValue();
        Assert.assertEquals(8192L, diskProfile.getSize());
        Assert.assertEquals(Long.valueOf(1000L), diskProfile.getMinIops());
        Assert.assertEquals(Long.valueOf(2000L), diskProfile.getMaxIops());
    }

    @Test
    public void findAllSuitableStoragePoolsForDetachedVolumeFiltersByStatusAndTags() {
        VolumeVO volume = volume(10L, Volume.State.Ready, null, 100L, 30L, 40L);
        DiskOfferingVO diskOffering = diskOffering(30L, new String[]{"gold"});
        StoragePool matchingPool = storagePool(1L, 0L);
        StoragePool wrongTagPool = storagePool(2L, 0L);
        StoragePool downPool = storagePool(3L, 0L);
        StoragePool nullTagPool = storagePool(4L, 0L);
        StoragePoolJoinVO matchingJoin = storagePoolJoin(1L, StoragePoolStatus.Up, "gold");
        StoragePoolJoinVO wrongTagJoin = storagePoolJoin(2L, StoragePoolStatus.Up, "silver");
        StoragePoolJoinVO downJoin = storagePoolJoin(3L, StoragePoolStatus.Maintenance, "gold");
        StoragePoolJoinVO nullTagJoin = storagePoolJoin(4L, StoragePoolStatus.Up, null);
        when(_diskOfferingDao.findById(30L)).thenReturn(diskOffering);
        when(_poolJoinDao.searchByIds(1L, 2L, 3L, 4L))
                .thenReturn(List.of(matchingJoin, wrongTagJoin, downJoin, nullTagJoin));

        List<StoragePool> result = service.findAllSuitableStoragePoolsForDetachedVolume(
                volume, 30L, List.of(matchingPool, wrongTagPool, downPool, nullTagPool));

        Assert.assertEquals(List.of(matchingPool), result);
    }

    @Test
    public void abstractDataStoreClustersListReplacesChildrenWithParentsAndHonorsAvoidPools() {
        StoragePoolVO parentPool = storagePoolVo(100L, 0L);
        StoragePoolVO avoidedParentPool = storagePoolVo(200L, 0L);
        StoragePool childOne = storagePool(101L, 100L);
        StoragePool childTwo = storagePool(102L, 100L);
        StoragePool avoidedChild = storagePool(201L, 200L);
        StoragePool normalPool = storagePool(300L, 0L);
        List<StoragePool> storagePools = new ArrayList<>(List.of(parentPool, childOne, childTwo, avoidedChild, normalPool));
        when(_poolDao.findById(100L)).thenReturn(parentPool);
        when(_poolDao.findById(200L)).thenReturn(avoidedParentPool);

        service.abstractDataStoreClustersList(storagePools, List.of(avoidedParentPool));

        Assert.assertTrue(storagePools.contains(parentPool));
        Assert.assertTrue(storagePools.contains(normalPool));
        Assert.assertFalse(storagePools.contains(childOne));
        Assert.assertFalse(storagePools.contains(childTwo));
        Assert.assertFalse(storagePools.contains(avoidedChild));
        Assert.assertFalse(storagePools.contains(avoidedParentPool));
        Assert.assertEquals(1, storagePools.stream().filter(parentPool::equals).count());
    }

    @Test
    public void listStoragePoolsForMigrationOfVolumeAbstractsDatastoreClustersAndAvoidsSourceParent() {
        VolumeStoragePoolMigrationServiceImpl serviceSpy = Mockito.spy(service);
        VolumeVO volume = volume(10L, Volume.State.Ready, null, 1L, 30L, 40L);
        StoragePoolVO sourceChild = storagePoolVo(1L, 100L);
        StoragePoolVO sourceParent = storagePoolVo(100L, 0L);
        StoragePoolVO otherChild = storagePoolVo(2L, 200L);
        StoragePoolVO otherParent = storagePoolVo(200L, 0L);
        List<StoragePool> allPools = new ArrayList<>(List.of(sourceChild, otherChild));
        List<StoragePool> suitablePools = new ArrayList<>(List.of(sourceChild, otherChild));
        Pair<List<? extends StoragePool>, List<? extends StoragePool>> internalResult = new Pair<>(allPools, suitablePools);
        Mockito.doReturn(internalResult).when(serviceSpy).listStoragePoolsForMigrationOfVolumeInternal(
                eq(10L), isNull(), isNull(), isNull(), isNull(), eq(false), eq(true), eq(false), eq("fast"));
        when(_volumeDao.findById(10L)).thenReturn(volume);
        when(_poolDao.findById(1L)).thenReturn(sourceChild);
        when(_poolDao.findById(100L)).thenReturn(sourceParent);
        when(_poolDao.findById(200L)).thenReturn(otherParent);

        Pair<List<? extends StoragePool>, List<? extends StoragePool>> result =
                serviceSpy.listStoragePoolsForMigrationOfVolume(10L, "fast");

        Assert.assertEquals(List.of(sourceParent, otherParent), result.first());
        Assert.assertEquals(List.of(otherParent), result.second());
    }

    private VolumeVO volume(long id, Volume.State state, Long instanceId, Long poolId, long diskOfferingId, long dataCenterId) {
        VolumeVO volume = Mockito.mock(VolumeVO.class);
        lenient().when(volume.getId()).thenReturn(id);
        lenient().when(volume.getState()).thenReturn(state);
        lenient().when(volume.getInstanceId()).thenReturn(instanceId);
        lenient().when(volume.getPoolId()).thenReturn(poolId);
        lenient().when(volume.getDiskOfferingId()).thenReturn(diskOfferingId);
        lenient().when(volume.getDataCenterId()).thenReturn(dataCenterId);
        lenient().when(volume.getVolumeType()).thenReturn(Volume.Type.ROOT);
        lenient().when(volume.getName()).thenReturn("volume-" + id);
        lenient().when(volume.getSize()).thenReturn(4096L);
        return volume;
    }

    private StoragePool storagePool(long id, Long parent) {
        StoragePool pool = Mockito.mock(StoragePool.class);
        lenient().when(pool.getId()).thenReturn(id);
        lenient().when(pool.getParent()).thenReturn(parent);
        lenient().when(pool.getDataCenterId()).thenReturn(40L);
        lenient().when(pool.getHypervisor()).thenReturn(HypervisorType.KVM);
        lenient().when(pool.getClusterId()).thenReturn(300L);
        return pool;
    }

    private StoragePoolVO storagePoolVo(long id, Long parent) {
        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        lenient().when(pool.getId()).thenReturn(id);
        lenient().when(pool.getParent()).thenReturn(parent);
        lenient().when(pool.getDataCenterId()).thenReturn(40L);
        lenient().when(pool.getHypervisor()).thenReturn(HypervisorType.KVM);
        lenient().when(pool.getClusterId()).thenReturn(300L);
        return pool;
    }

    private ClusterVO cluster(long id, Long podId) {
        ClusterVO cluster = Mockito.mock(ClusterVO.class);
        lenient().when(cluster.getId()).thenReturn(id);
        lenient().when(cluster.getPodId()).thenReturn(podId);
        return cluster;
    }

    private DiskOfferingVO diskOffering(long id, String[] tags) {
        DiskOfferingVO diskOffering = Mockito.mock(DiskOfferingVO.class);
        lenient().when(diskOffering.getId()).thenReturn(id);
        lenient().when(diskOffering.getTagsArray()).thenReturn(tags);
        return diskOffering;
    }

    private StoragePoolJoinVO storagePoolJoin(long id, StoragePoolStatus status, String tag) {
        StoragePoolJoinVO storagePoolJoin = Mockito.mock(StoragePoolJoinVO.class);
        lenient().when(storagePoolJoin.getId()).thenReturn(id);
        lenient().when(storagePoolJoin.getStatus()).thenReturn(status);
        lenient().when(storagePoolJoin.getTag()).thenReturn(tag);
        return storagePoolJoin;
    }
}
