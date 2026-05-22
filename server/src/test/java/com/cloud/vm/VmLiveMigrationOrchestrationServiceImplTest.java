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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeployDestination;
import com.cloud.deploy.DeploymentPlanner;
import com.cloud.deploy.DeploymentPlanningManager;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class VmLiveMigrationOrchestrationServiceImplTest {

    @Mock
    private DiskOfferingDao diskOfferingDao;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private ServiceOfferingDao serviceOfferingDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private VirtualMachineManager itMgr;
    @Mock
    private DeploymentPlanningManager planningMgr;

    @InjectMocks
    private VmLiveMigrationOrchestrationServiceImpl service;

    @Test
    public void isAnyVmVolumeUsingLocalStorageReturnsTrueForLocalDiskOffering() {
        VolumeVO volume = volume(1L, 10L);
        DiskOfferingVO diskOffering = mock(DiskOfferingVO.class);
        when(diskOfferingDao.findById(10L)).thenReturn(diskOffering);
        when(diskOffering.isUseLocalStorage()).thenReturn(true);

        assertTrue(service.isAnyVmVolumeUsingLocalStorage(Collections.singletonList(volume)));
    }

    @Test
    public void isAnyVmVolumeUsingLocalStorageReturnsTrueForLocalPool() {
        VolumeVO volume = volume(1L, 10L);
        DiskOfferingVO diskOffering = mock(DiskOfferingVO.class);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(diskOfferingDao.findById(10L)).thenReturn(diskOffering);
        when(diskOffering.isUseLocalStorage()).thenReturn(false);
        when(storagePoolDao.findById(1L)).thenReturn(pool);
        when(pool.isLocal()).thenReturn(true);

        assertTrue(service.isAnyVmVolumeUsingLocalStorage(Collections.singletonList(volume)));
    }

    @Test
    public void isAnyVmVolumeUsingLocalStorageReturnsFalseWhenAllVolumesAreShared() {
        VolumeVO volume = volume(1L, 10L);
        DiskOfferingVO diskOffering = mock(DiskOfferingVO.class);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(diskOfferingDao.findById(10L)).thenReturn(diskOffering);
        when(diskOffering.isUseLocalStorage()).thenReturn(false);
        when(storagePoolDao.findById(1L)).thenReturn(pool);
        when(pool.isLocal()).thenReturn(false);

        assertFalse(service.isAnyVmVolumeUsingLocalStorage(Collections.singletonList(volume)));
    }

    @Test
    public void isAllVmVolumesOnZoneWideStoreRequiresEveryVolumeOnZonePool() {
        VolumeVO first = volume(1L, 10L);
        VolumeVO second = volume(2L, 20L);
        StoragePoolVO zonePool = pool(ScopeType.ZONE);
        StoragePoolVO clusterPool = pool(ScopeType.CLUSTER);
        when(storagePoolDao.findById(1L)).thenReturn(zonePool);
        when(storagePoolDao.findById(2L)).thenReturn(clusterPool);

        assertFalse(service.isAllVmVolumesOnZoneWideStore(Arrays.asList(first, second)));
    }

    @Test
    public void isVmCanBeMigratedWithoutStorageAllowsSameClusterWithoutPoolMap() {
        Host srcHost = host(1L, 5L);
        Host destinationHost = host(2L, 5L);
        VolumeVO volume = volume(1L, 10L);
        stubSharedVolume(volume);

        assertTrue(service.isVmCanBeMigratedWithoutStorage(srcHost, destinationHost, Collections.singletonList(volume), Collections.emptyMap()));
    }

    @Test
    public void chooseVmMigrationDestinationUsingVolumePoolMapUsesFirstDestinationPoolForPlanner() throws InsufficientServerCapacityException {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        Host srcHost = host(1L, 5L);
        Host destinationHost = host(2L, 5L);
        HostVO srcHostVo = mock(HostVO.class);
        ServiceOfferingVO offering = mock(ServiceOfferingVO.class);
        DataCenterDeployment plan = mock(DataCenterDeployment.class);
        DeployDestination deployDestination = mock(DeployDestination.class);
        Map<Long, Long> volumeToPoolMap = new LinkedHashMap<>();
        volumeToPoolMap.put(100L, 200L);
        volumeToPoolMap.put(101L, 201L);
        when(vm.getId()).thenReturn(42L);
        when(vm.getServiceOfferingId()).thenReturn(300L);
        when(serviceOfferingDao.findById(42L, 300L)).thenReturn(offering);
        when(hostDao.findById(1L)).thenReturn(srcHostVo);
        when(itMgr.getMigrationDeployment(eq(vm), eq(srcHostVo), eq(200L), any(DeploymentPlanner.ExcludeList.class))).thenReturn(plan);
        when(planningMgr.planDeployment(any(VirtualMachineProfile.class), eq(plan), any(DeploymentPlanner.ExcludeList.class), nullable(DeploymentPlanner.class)))
                .thenReturn(deployDestination);
        when(deployDestination.getHost()).thenReturn(destinationHost);

        assertEquals(destinationHost, service.chooseVmMigrationDestinationUsingVolumePoolMap(vm, srcHost, volumeToPoolMap));
        verify(itMgr).getMigrationDeployment(eq(vm), eq(srcHostVo), eq(200L), any(DeploymentPlanner.ExcludeList.class));
    }

    @Test
    public void chooseVmMigrationDestinationUsingVolumePoolMapThrowsWhenPlannerFindsNoHost() throws InsufficientServerCapacityException {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        Host srcHost = host(1L, 5L);
        HostVO srcHostVo = mock(HostVO.class);
        DataCenterDeployment plan = mock(DataCenterDeployment.class);
        DeployDestination deployDestination = mock(DeployDestination.class);
        when(vm.getId()).thenReturn(42L);
        when(vm.getServiceOfferingId()).thenReturn(300L);
        when(hostDao.findById(1L)).thenReturn(srcHostVo);
        when(itMgr.getMigrationDeployment(eq(vm), eq(srcHostVo), isNull(), any(DeploymentPlanner.ExcludeList.class))).thenReturn(plan);
        when(planningMgr.planDeployment(any(VirtualMachineProfile.class), eq(plan), any(DeploymentPlanner.ExcludeList.class), nullable(DeploymentPlanner.class)))
                .thenReturn(deployDestination);

        assertThrows(CloudRuntimeException.class, () -> service.chooseVmMigrationDestinationUsingVolumePoolMap(vm, srcHost, null));
    }

    @Test
    public void isAllVmVolumesOnZoneWideStoreReturnsFalseForEmptyList() {
        assertFalse(service.isAllVmVolumesOnZoneWideStore(Collections.emptyList()));
    }

    @Test
    public void isAllVmVolumesOnZoneWideStoreReturnsTrueWhenAllVolumesAreZoneScoped() {
        VolumeVO vol1 = volume(1L, 10L);
        VolumeVO vol2 = volume(2L, 20L);
        StoragePoolVO zonePool1 = pool(ScopeType.ZONE);
        StoragePoolVO zonePool2 = pool(ScopeType.ZONE);
        when(storagePoolDao.findById(1L)).thenReturn(zonePool1);
        when(storagePoolDao.findById(2L)).thenReturn(zonePool2);

        assertTrue(service.isAllVmVolumesOnZoneWideStore(Arrays.asList(vol1, vol2)));
    }

    @Test
    public void isVmCanBeMigratedWithoutStorageReturnsFalseWhenDestinationHostIsNull() {
        Host srcHost = host(1L, 5L);
        VolumeVO volume = volume(1L, 10L);
        stubSharedVolume(volume);

        assertFalse(service.isVmCanBeMigratedWithoutStorage(srcHost, null, Collections.singletonList(volume), Collections.emptyMap()));
    }

    private VolumeVO volume(long poolId, long diskOfferingId) {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getPoolId()).thenReturn(poolId);
        when(volume.getDiskOfferingId()).thenReturn(diskOfferingId);
        return volume;
    }

    private StoragePoolVO pool(ScopeType scope) {
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.getScope()).thenReturn(scope);
        return pool;
    }

    private Host host(long id, long clusterId) {
        Host host = mock(Host.class);
        when(host.getId()).thenReturn(id);
        when(host.getClusterId()).thenReturn(clusterId);
        return host;
    }

    private void stubSharedVolume(VolumeVO volume) {
        DiskOfferingVO diskOffering = mock(DiskOfferingVO.class);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(diskOfferingDao.findById(volume.getDiskOfferingId())).thenReturn(diskOffering);
        when(diskOffering.isUseLocalStorage()).thenReturn(false);
        when(storagePoolDao.findById(volume.getPoolId())).thenReturn(pool);
        when(pool.isLocal()).thenReturn(false);
    }
}
