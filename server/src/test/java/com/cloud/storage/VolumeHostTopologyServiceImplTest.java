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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VmDetailConstants;

/**
 * Focused unit tests for {@link VolumeHostTopologyServiceImpl}.
 * Mirrors the slice extraction tests run against the manager-level
 * spy paths in {@link VolumeApiServiceImplTest}, but exercises the
 * extracted component directly.
 */
@RunWith(MockitoJUnitRunner.class)
public class VolumeHostTopologyServiceImplTest {

    @Mock
    private HostDao hostDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private HypervisorCapabilitiesDao hypervisorCapabilitiesDao;
    @Mock
    private VirtualMachineManager virtualMachineManager;
    @Mock
    private StorageUtil storageUtil;

    @Mock
    private UserVmVO vm;
    @Mock
    private VirtualMachine genericVm;
    @Mock
    private HostVO host;
    @Mock
    private StoragePoolVO pool;

    @InjectMocks
    private VolumeHostTopologyServiceImpl topology;

    private static final long VM_ID = 100L;
    private static final long HOST_ID = 200L;
    private static final long CLUSTER_ID = 300L;
    private static final long POOL_ID = 400L;
    private static final long ZONE_ID = 500L;

    @Before
    public void setUp() {
        Mockito.lenient().when(vm.getId()).thenReturn(VM_ID);
        Mockito.lenient().when(genericVm.getId()).thenReturn(VM_ID);
    }

    // --- verifyManagedStorage --------------------------------------------------

    @Test
    public void verifyManagedStorageNoopWhenPoolIdNull() {
        topology.verifyManagedStorage(null, HOST_ID);
        Mockito.verifyNoInteractions(storagePoolDao, hostDao, storageUtil);
    }

    @Test
    public void verifyManagedStorageNoopWhenHostIdNull() {
        topology.verifyManagedStorage(POOL_ID, null);
        Mockito.verifyNoInteractions(storagePoolDao, hostDao, storageUtil);
    }

    @Test
    public void verifyManagedStorageNoopWhenPoolMissing() {
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(null);
        topology.verifyManagedStorage(POOL_ID, HOST_ID);
        Mockito.verifyNoInteractions(hostDao, storageUtil);
    }

    @Test
    public void verifyManagedStorageNoopWhenPoolUnmanaged() {
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(pool);
        Mockito.when(pool.isManaged()).thenReturn(false);
        topology.verifyManagedStorage(POOL_ID, HOST_ID);
        Mockito.verifyNoInteractions(hostDao, storageUtil);
    }

    @Test
    public void verifyManagedStorageNoopWhenHostMissing() {
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(pool);
        Mockito.when(pool.isManaged()).thenReturn(true);
        Mockito.when(hostDao.findById(HOST_ID)).thenReturn(null);
        topology.verifyManagedStorage(POOL_ID, HOST_ID);
        Mockito.verifyNoInteractions(storageUtil);
    }

    @Test
    public void verifyManagedStoragePassesWhenPoolCanScale() {
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(pool);
        Mockito.when(pool.isManaged()).thenReturn(true);
        Mockito.when(hostDao.findById(HOST_ID)).thenReturn(host);
        Mockito.when(host.getClusterId()).thenReturn(CLUSTER_ID);
        Mockito.when(host.getId()).thenReturn(HOST_ID);
        Mockito.when(storageUtil.managedStoragePoolCanScale(pool, CLUSTER_ID, HOST_ID)).thenReturn(true);
        topology.verifyManagedStorage(POOL_ID, HOST_ID);
    }

    @Test(expected = CloudRuntimeException.class)
    public void verifyManagedStorageThrowsWhenPoolCantScale() {
        Mockito.when(storagePoolDao.findById(POOL_ID)).thenReturn(pool);
        Mockito.when(pool.isManaged()).thenReturn(true);
        Mockito.when(hostDao.findById(HOST_ID)).thenReturn(host);
        Mockito.when(host.getClusterId()).thenReturn(CLUSTER_ID);
        Mockito.when(host.getId()).thenReturn(HOST_ID);
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(storageUtil.managedStoragePoolCanScale(pool, CLUSTER_ID, HOST_ID)).thenReturn(false);
        topology.verifyManagedStorage(POOL_ID, HOST_ID);
    }

    // --- getNameOfClusteredFileSystem -----------------------------------------

    @Test
    public void getNameOfClusteredFileSystemReturnsSRsForXenServer() {
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        assertEquals("SRs", topology.getNameOfClusteredFileSystem(host));
    }

    @Test
    public void getNameOfClusteredFileSystemReturnsDatastoresForVMware() {
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.VMware);
        assertEquals("datastores", topology.getNameOfClusteredFileSystem(host));
    }

    @Test
    public void getNameOfClusteredFileSystemReturnsGenericForKVM() {
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        assertEquals("clustered file systems", topology.getNameOfClusteredFileSystem(host));
    }

    // --- getHostForVmVolumeAttachDetach ---------------------------------------

    @Test
    public void getHostForVmVolumeAttachDetachUsesCurrentHostWhenSet() {
        Mockito.when(virtualMachineManager.findClusterAndHostIdForVm(VM_ID))
                .thenReturn(new Pair<>(CLUSTER_ID, HOST_ID));
        Mockito.when(hostDao.findById(HOST_ID)).thenReturn(host);
        HostVO result = topology.getHostForVmVolumeAttachDetach(genericVm, pool);
        assertEquals(host, result);
    }

    @Test
    public void getHostForVmVolumeAttachDetachFallsBackToClusterForStoppedVm() {
        Mockito.when(virtualMachineManager.findClusterAndHostIdForVm(VM_ID))
                .thenReturn(new Pair<>(CLUSTER_ID, (Long) null));
        Mockito.when(genericVm.getState()).thenReturn(State.Stopped);
        Mockito.when(pool.getScope()).thenReturn(ScopeType.CLUSTER);
        Mockito.when(hostDao.findHypervisorHostInCluster(CLUSTER_ID))
                .thenReturn(Collections.singletonList(host));
        HostVO result = topology.getHostForVmVolumeAttachDetach(genericVm, pool);
        assertEquals(host, result);
    }

    @Test
    public void getHostForVmVolumeAttachDetachReturnsNullForHostScopedPool() {
        Mockito.when(virtualMachineManager.findClusterAndHostIdForVm(VM_ID))
                .thenReturn(new Pair<>(CLUSTER_ID, (Long) null));
        Mockito.when(genericVm.getState()).thenReturn(State.Stopped);
        Mockito.when(pool.getScope()).thenReturn(ScopeType.HOST);
        HostVO result = topology.getHostForVmVolumeAttachDetach(genericVm, pool);
        assertNull(result);
        Mockito.verify(hostDao, Mockito.never()).findHypervisorHostInCluster(Mockito.anyLong());
    }

    @Test
    public void getHostForVmVolumeAttachDetachReturnsNullWhenBothMissing() {
        Mockito.when(virtualMachineManager.findClusterAndHostIdForVm(VM_ID))
                .thenReturn(new Pair<>((Long) null, (Long) null));
        HostVO result = topology.getHostForVmVolumeAttachDetach(genericVm, pool);
        assertNull(result);
    }

    @Test
    public void getHostForVmVolumeAttachDetachIgnoresRunningVmWithoutHost() {
        Mockito.when(virtualMachineManager.findClusterAndHostIdForVm(VM_ID))
                .thenReturn(new Pair<>(CLUSTER_ID, (Long) null));
        Mockito.when(genericVm.getState()).thenReturn(State.Running);
        HostVO result = topology.getHostForVmVolumeAttachDetach(genericVm, pool);
        assertNull(result);
        Mockito.verify(hostDao, Mockito.never()).findHypervisorHostInCluster(Mockito.anyLong());
    }

    // --- isSendCommandForVmVolumeAttachDetach ---------------------------------

    @Test
    public void isSendCommandFalseForNulls() {
        assertFalse(topology.isSendCommandForVmVolumeAttachDetach(null, null));
        assertFalse(topology.isSendCommandForVmVolumeAttachDetach(null, pool));
        assertFalse(topology.isSendCommandForVmVolumeAttachDetach(host, null));
    }

    @Test
    public void isSendCommandTrueForVMwareHostAlways() {
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.VMware);
        assertTrue(topology.isSendCommandForVmVolumeAttachDetach(host, pool));
    }

    @Test
    public void isSendCommandTrueForXenServerWithManagedPool() {
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        Mockito.when(pool.isManaged()).thenReturn(true);
        assertTrue(topology.isSendCommandForVmVolumeAttachDetach(host, pool));
    }

    @Test
    public void isSendCommandFalseForXenServerWithUnmanagedPool() {
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        Mockito.when(pool.isManaged()).thenReturn(false);
        assertFalse(topology.isSendCommandForVmVolumeAttachDetach(host, pool));
    }

    @Test
    public void isSendCommandFalseForKVM() {
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        assertFalse(topology.isSendCommandForVmVolumeAttachDetach(host, pool));
    }

    // --- isIothreadsSupported -------------------------------------------------

    @Test
    public void isIothreadsSupportedTrueForKvmWithDetail() {
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Map<String, String> details = new HashMap<>();
        details.put(VmDetailConstants.IOTHREADS, "1");
        Mockito.when(vm.getDetails()).thenReturn(details);
        Mockito.when(vm.getDetail(VmDetailConstants.IOTHREADS)).thenReturn("1");
        assertTrue(topology.isIothreadsSupported(vm));
    }

    @Test
    public void isIothreadsSupportedFalseForKvmWithoutDetail() {
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(vm.getDetails()).thenReturn(new HashMap<>());
        Mockito.when(vm.getDetail(VmDetailConstants.IOTHREADS)).thenReturn(null);
        assertFalse(topology.isIothreadsSupported(vm));
    }

    @Test
    public void isIothreadsSupportedFalseForNonKvm() {
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.VMware);
        assertFalse(topology.isIothreadsSupported(vm));
    }

    @Test
    public void isIothreadsSupportedFalseWhenDetailsNull() {
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(vm.getDetails()).thenReturn(null);
        assertFalse(topology.isIothreadsSupported(vm));
    }

    // --- getIoPolicy ----------------------------------------------------------

    @Test
    public void getIoPolicyReturnsNullForNonKvm() {
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.VMware);
        assertNull(topology.getIoPolicy(vm, POOL_ID));
    }

    @Test
    public void getIoPolicyReturnsNullWhenNoDetail() {
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(vm.getDetails()).thenReturn(new HashMap<>());
        Mockito.when(vm.getDetail(VmDetailConstants.IO_POLICY)).thenReturn(null);
        assertNull(topology.getIoPolicy(vm, POOL_ID));
    }

    @Test
    public void getIoPolicyReturnsStaticValue() {
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Map<String, String> details = new HashMap<>();
        details.put(VmDetailConstants.IO_POLICY, "native");
        Mockito.when(vm.getDetails()).thenReturn(details);
        Mockito.when(vm.getDetail(VmDetailConstants.IO_POLICY)).thenReturn("native");
        assertEquals("native", topology.getIoPolicy(vm, POOL_ID));
    }

    // --- provideVMInfo --------------------------------------------------------

    @Test
    public void provideVMInfoNoopForNullStore() {
        topology.provideVMInfo(null, VM_ID, POOL_ID);
        // No throw is the assertion.
    }

    @Test
    public void provideVMInfoNoopForNonPrimaryDriver() {
        DataStore store = Mockito.mock(DataStore.class);
        DataStoreDriver driver = Mockito.mock(DataStoreDriver.class);
        Mockito.when(store.getDriver()).thenReturn(driver);
        topology.provideVMInfo(store, VM_ID, POOL_ID);
        // No throw is the assertion; we cannot call provideVmInfo on a non-primary driver.
    }

    @Test
    public void provideVMInfoNoopWhenDriverDoesNotNeedIt() {
        DataStore store = Mockito.mock(DataStore.class);
        PrimaryDataStoreDriver driver = Mockito.mock(PrimaryDataStoreDriver.class);
        Mockito.when(store.getDriver()).thenReturn(driver);
        Mockito.when(driver.isVmInfoNeeded()).thenReturn(false);
        topology.provideVMInfo(store, VM_ID, POOL_ID);
        Mockito.verify(driver, Mockito.never()).provideVmInfo(Mockito.anyLong(), Mockito.anyLong());
    }

    @Test
    public void provideVMInfoCallsProvideVmInfoWhenDriverNeedsIt() {
        DataStore store = Mockito.mock(DataStore.class);
        PrimaryDataStoreDriver driver = Mockito.mock(PrimaryDataStoreDriver.class);
        Mockito.when(store.getDriver()).thenReturn(driver);
        Mockito.when(driver.isVmInfoNeeded()).thenReturn(true);
        topology.provideVMInfo(store, VM_ID, POOL_ID);
        Mockito.verify(driver).provideVmInfo(VM_ID, POOL_ID);
    }

    // --- getMinimumHypervisorVersionInDatacenter ------------------------------

    @Test
    public void getMinimumHypervisorVersionReturnsDefaultForSimulator() {
        assertEquals("default", topology.getMinimumHypervisorVersionInDatacenter(ZONE_ID, HypervisorType.Simulator));
        Mockito.verifyNoInteractions(hostDao);
    }

    @Test
    public void getMinimumHypervisorVersionReturnsDefaultForEmptyVersionList() {
        Mockito.when(hostDao.listOrderedHostsHypervisorVersionsInDatacenter(ZONE_ID, HypervisorType.KVM))
                .thenReturn(Collections.<String>emptyList());
        assertEquals("default", topology.getMinimumHypervisorVersionInDatacenter(ZONE_ID, HypervisorType.KVM));
    }

    @Test
    public void getMinimumHypervisorVersionReturnsDefaultForBlankVersion() {
        Mockito.when(hostDao.listOrderedHostsHypervisorVersionsInDatacenter(ZONE_ID, HypervisorType.KVM))
                .thenReturn(Arrays.asList("", "xxxx"));
        assertEquals("default", topology.getMinimumHypervisorVersionInDatacenter(ZONE_ID, HypervisorType.KVM));
    }

    @Test
    public void getMinimumHypervisorVersionReturnsLowest() {
        Mockito.when(hostDao.listOrderedHostsHypervisorVersionsInDatacenter(ZONE_ID, HypervisorType.VMware))
                .thenReturn(Arrays.asList("6.7", "6.7.1", "6.7.2"));
        assertEquals("6.7", topology.getMinimumHypervisorVersionInDatacenter(ZONE_ID, HypervisorType.VMware));
    }

    // --- getMaxDataVolumesSupported -------------------------------------------

    @Test
    public void getMaxDataVolumesSupportedUsesHostProductVersion() {
        Mockito.when(vm.getHostId()).thenReturn(HOST_ID);
        Mockito.when(hostDao.findById(HOST_ID)).thenReturn(host);
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(host.getDetail("product_version")).thenReturn("8.5");
        Mockito.when(hypervisorCapabilitiesDao.getMaxDataVolumesLimit(HypervisorType.KVM, "8.5")).thenReturn(32);
        assertEquals(32, topology.getMaxDataVolumesSupported(vm));
    }

    @Test
    public void getMaxDataVolumesSupportedFallsBackToHypervisorVersion() {
        Mockito.when(vm.getHostId()).thenReturn(HOST_ID);
        Mockito.when(hostDao.findById(HOST_ID)).thenReturn(host);
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(host.getDetail("product_version")).thenReturn(null);
        Mockito.when(host.getHypervisorVersion()).thenReturn("7.0");
        Mockito.when(hypervisorCapabilitiesDao.getMaxDataVolumesLimit(HypervisorType.KVM, "7.0")).thenReturn(16);
        assertEquals(16, topology.getMaxDataVolumesSupported(vm));
    }

    @Test
    public void getMaxDataVolumesSupportedFallsBackToLastHostId() {
        Mockito.when(vm.getHostId()).thenReturn(null);
        Mockito.when(vm.getLastHostId()).thenReturn(HOST_ID);
        Mockito.when(hostDao.findById(HOST_ID)).thenReturn(host);
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(host.getDetail("product_version")).thenReturn("7.0");
        Mockito.when(hypervisorCapabilitiesDao.getMaxDataVolumesLimit(HypervisorType.KVM, "7.0")).thenReturn(8);
        assertEquals(8, topology.getMaxDataVolumesSupported(vm));
    }

    @Test
    public void getMaxDataVolumesSupportedDefaultsToSixWhenZero() {
        Mockito.when(vm.getHostId()).thenReturn(HOST_ID);
        Mockito.when(hostDao.findById(HOST_ID)).thenReturn(host);
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(host.getDetail("product_version")).thenReturn("8.5");
        Mockito.when(hypervisorCapabilitiesDao.getMaxDataVolumesLimit(HypervisorType.KVM, "8.5")).thenReturn(0);
        assertEquals(VolumeHostTopologyServiceImpl.DEFAULT_MAX_DATA_VOLUMES, topology.getMaxDataVolumesSupported(vm));
    }

    @Test
    public void getMaxDataVolumesSupportedDefaultsToSixWhenNoHostNoDefaultHv() {
        Mockito.when(vm.getHostId()).thenReturn(null);
        Mockito.when(vm.getLastHostId()).thenReturn(null);
        Mockito.when(hostDao.findById(null)).thenReturn(null);
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(hypervisorCapabilitiesDao.getHypervisorsWithDefaultEntries())
                .thenReturn(Collections.<HypervisorType>emptyList());
        assertEquals(VolumeHostTopologyServiceImpl.DEFAULT_MAX_DATA_VOLUMES, topology.getMaxDataVolumesSupported(vm));
    }

    @Test
    public void getMaxDataVolumesSupportedUsesDefaultHvWhenNoHostFound() {
        Mockito.when(vm.getHostId()).thenReturn(null);
        Mockito.when(vm.getLastHostId()).thenReturn(null);
        Mockito.when(hostDao.findById(null)).thenReturn(null);
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(vm.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(hypervisorCapabilitiesDao.getHypervisorsWithDefaultEntries())
                .thenReturn(Collections.singletonList(HypervisorType.KVM));
        Mockito.when(hostDao.listOrderedHostsHypervisorVersionsInDatacenter(ZONE_ID, HypervisorType.KVM))
                .thenReturn(Collections.singletonList("8.0"));
        Mockito.when(hypervisorCapabilitiesDao.getMaxDataVolumesLimit(HypervisorType.KVM, "8.0")).thenReturn(24);
        assertEquals(24, topology.getMaxDataVolumesSupported(vm));
    }

    @Test
    public void getMaxDataVolumesSupportedCachesDefaultHv() {
        // First call: no host, default-hv path populates the cache.
        Mockito.when(vm.getHostId()).thenReturn(null);
        Mockito.when(vm.getLastHostId()).thenReturn(null);
        Mockito.when(hostDao.findById(null)).thenReturn(null);
        Mockito.when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(vm.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(hypervisorCapabilitiesDao.getHypervisorsWithDefaultEntries())
                .thenReturn(Collections.singletonList(HypervisorType.KVM));
        Mockito.when(hostDao.listOrderedHostsHypervisorVersionsInDatacenter(ZONE_ID, HypervisorType.KVM))
                .thenReturn(Collections.singletonList("8.0"));
        Mockito.when(hypervisorCapabilitiesDao.getMaxDataVolumesLimit(HypervisorType.KVM, "8.0")).thenReturn(16);

        topology.getMaxDataVolumesSupported(vm);
        topology.getMaxDataVolumesSupported(vm);

        // The default-hv set should be cached after the first call.
        Mockito.verify(hypervisorCapabilitiesDao, Mockito.times(1)).getHypervisorsWithDefaultEntries();
    }

    // --- getDeviceId ----------------------------------------------------------

    private void primeMaxDataVolumes(int maxData) {
        Mockito.when(vm.getHostId()).thenReturn(HOST_ID);
        Mockito.when(hostDao.findById(HOST_ID)).thenReturn(host);
        Mockito.when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        Mockito.when(host.getDetail("product_version")).thenReturn("8.5");
        Mockito.when(hypervisorCapabilitiesDao.getMaxDataVolumesLimit(HypervisorType.KVM, "8.5")).thenReturn(maxData);
    }

    @Test
    public void getDeviceIdReturnsRequestedIdWhenInRangeAndFree() {
        primeMaxDataVolumes(6);
        Mockito.when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.<VolumeVO>emptyList());
        Long deviceId = topology.getDeviceId(vm, 4L);
        assertEquals(Long.valueOf(4L), deviceId);
    }

    @Test
    public void getDeviceIdRejectsCdromSlot() {
        primeMaxDataVolumes(6);
        try {
            topology.getDeviceId(vm, 3L);
            fail("Expected RuntimeException for cdrom slot");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("deviceId should be"));
        }
    }

    @Test
    public void getDeviceIdRejectsOutOfRange() {
        primeMaxDataVolumes(6);
        try {
            topology.getDeviceId(vm, 99L);
            fail("Expected RuntimeException for out of range deviceId");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("deviceId should be"));
        }
    }

    @Test
    public void getDeviceIdRejectsAlreadyUsed() {
        primeMaxDataVolumes(6);
        VolumeVO existing = Mockito.mock(VolumeVO.class);
        Mockito.when(existing.getDeviceId()).thenReturn(4L);
        Mockito.when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.singletonList(existing));
        try {
            topology.getDeviceId(vm, 4L);
            fail("Expected RuntimeException for already-used deviceId");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("is used by vol"));
        }
    }

    @Test
    public void getDeviceIdAllocatesLowestFreeWhenUnspecified() {
        primeMaxDataVolumes(6);
        Mockito.when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.<VolumeVO>emptyList());
        // maxDevices = 8, maxDeviceId = 7, candidates = [1,2,4,5,6,7]
        Long deviceId = topology.getDeviceId(vm, null);
        assertEquals(Long.valueOf(1L), deviceId);
    }

    @Test
    public void getDeviceIdSkipsCdromSlotWhenUnspecified() {
        primeMaxDataVolumes(6);
        VolumeVO v1 = Mockito.mock(VolumeVO.class);
        Mockito.when(v1.getDeviceId()).thenReturn(1L);
        VolumeVO v2 = Mockito.mock(VolumeVO.class);
        Mockito.when(v2.getDeviceId()).thenReturn(2L);
        Mockito.when(volumeDao.findByInstance(VM_ID)).thenReturn(Arrays.asList(v1, v2));
        Long deviceId = topology.getDeviceId(vm, null);
        // 1, 2 used; 3 (cdrom) reserved; next free is 4
        assertEquals(Long.valueOf(4L), deviceId);
    }

    @Test
    public void getDeviceIdThrowsWhenAllSlotsUsed() {
        primeMaxDataVolumes(6);
        VolumeVO[] used = new VolumeVO[]{
                deviceVol(1L), deviceVol(2L), deviceVol(4L), deviceVol(5L), deviceVol(6L), deviceVol(7L)
        };
        Mockito.when(volumeDao.findByInstance(VM_ID)).thenReturn(Arrays.asList(used));
        try {
            topology.getDeviceId(vm, null);
            fail("Expected RuntimeException when all device ids are used");
        } catch (RuntimeException e) {
            assertTrue(e.getMessage().contains("All device Ids are used"));
        }
    }

    private VolumeVO deviceVol(long deviceId) {
        VolumeVO v = Mockito.mock(VolumeVO.class);
        Mockito.when(v.getDeviceId()).thenReturn(deviceId);
        return v;
    }
}
