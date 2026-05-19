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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.apache.cloudstack.affinity.AffinityGroupVMMapVO;
import org.apache.cloudstack.affinity.dao.AffinityGroupVMMapDao;
import org.apache.cloudstack.vm.UnmanagedVMsManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.event.UsageEventUtils;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.ResourceLimitService;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

public class VmUnmanageServiceImplTest {

    private static final long VM_ID = 42L;
    private static final long ACCOUNT_ID = 43L;
    private static final long ZONE_ID = 44L;
    private static final long SERVICE_OFFERING_ID = 45L;
    private static final long TEMPLATE_ID = 46L;
    private static final long ROOT_VOLUME_ID = 47L;
    private static final long DATA_VOLUME_ID = 48L;
    private static final long DISK_OFFERING_ID = 49L;
    private static final String VM_UUID = "vm-uuid";
    private static final Long HOST_ID = 50L;

    @Mock
    private UserVmDao userVmDao;
    @Mock
    private VolumeDao volumeDao;
    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Mock
    private AffinityGroupVMMapDao affinityGroupVMMapDao;
    @Mock
    private ServiceOfferingDao serviceOfferingDao;
    @Mock
    private VMTemplateDao templateDao;
    @Mock
    private DiskOfferingDao diskOfferingDao;
    @Mock
    private ResourceLimitService resourceLimitService;
    @Mock
    private VirtualMachineManager virtualMachineManager;
    @Mock
    private VmVolumeLifecycleValidationService vmVolumeLifecycleValidationService;
    @Mock
    private VmUnmanageService.ManagerOperations managerOperations;
    @Mock
    private UserVmVO userVm;
    @Mock
    private VMInstanceVO vmInstance;
    @Mock
    private ServiceOfferingVO serviceOffering;
    @Mock
    private VMTemplateVO template;
    @Mock
    private DiskOfferingVO diskOffering;

    private AutoCloseable mocks;
    private MockedStatic<UnmanagedVMsManager> unmanagedVMsManagerMockedStatic;
    private MockedStatic<UsageEventUtils> usageEventUtilsMockedStatic;
    private VmUnmanageServiceImpl service;

    @Before
    public void setUp() {
        mocks = MockitoAnnotations.openMocks(this);
        unmanagedVMsManagerMockedStatic = mockStatic(UnmanagedVMsManager.class);
        usageEventUtilsMockedStatic = mockStatic(UsageEventUtils.class);
        service = new VmUnmanageServiceImpl();
        ReflectionTestUtils.setField(service, "_vmDao", userVmDao);
        ReflectionTestUtils.setField(service, "_volsDao", volumeDao);
        ReflectionTestUtils.setField(service, "_vmInstanceDao", vmInstanceDao);
        ReflectionTestUtils.setField(service, "vmInstanceDetailsDao", vmInstanceDetailsDao);
        ReflectionTestUtils.setField(service, "_affinityGroupVMMapDao", affinityGroupVMMapDao);
        ReflectionTestUtils.setField(service, "serviceOfferingDao", serviceOfferingDao);
        ReflectionTestUtils.setField(service, "_templateDao", templateDao);
        ReflectionTestUtils.setField(service, "_diskOfferingDao", diskOfferingDao);
        ReflectionTestUtils.setField(service, "_resourceLimitMgr", resourceLimitService);
        ReflectionTestUtils.setField(service, "_itMgr", virtualMachineManager);
        ReflectionTestUtils.setField(service, "vmVolumeLifecycleValidationService", vmVolumeLifecycleValidationService);
        when(vmInstance.getId()).thenReturn(VM_ID);
        when(userVm.getAccountId()).thenReturn(ACCOUNT_ID);
    }

    @After
    public void tearDown() throws Exception {
        usageEventUtilsMockedStatic.close();
        unmanagedVMsManagerMockedStatic.close();
        mocks.close();
    }

    @Test
    public void unmanageUserVMWhenVmNotFoundRejectsWithoutLock() {
        when(userVmDao.findById(VM_ID)).thenReturn(null);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.unmanageUserVM(VM_ID, HOST_ID, managerOperations));

        assertEquals("Unable to find a VM with ID = " + VM_ID, exception.getMessage());
        verify(userVmDao, never()).acquireInLockTable(anyLong());
        verify(userVmDao, never()).releaseFromLockTable(anyLong());
    }

    @Test
    public void unmanageUserVMWhenVmAlreadyRemovedRejectsWithoutLock() {
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);
        when(userVm.getRemoved()).thenReturn(new Date());

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.unmanageUserVM(VM_ID, HOST_ID, managerOperations));

        assertEquals("Unable to find a VM with ID = " + VM_ID, exception.getMessage());
        verify(userVmDao, never()).acquireInLockTable(anyLong());
        verify(userVmDao, never()).releaseFromLockTable(anyLong());
    }

    @Test
    public void unmanageUserVMWhenStateInvalidRejectsAndReleasesLock() {
        stubVmLookupAndLock();
        when(userVm.getState()).thenReturn(VirtualMachine.State.Starting);
        when(userVm.getName()).thenReturn("test-vm");

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.unmanageUserVM(VM_ID, HOST_ID, managerOperations));

        assertEquals("Instance: test-vm is not running or stopped, cannot be unmanaged", exception.getMessage());
        verify(userVmDao).releaseFromLockTable(VM_ID);
    }

    @Test
    public void unmanageUserVMWhenHypervisorUnsupportedRejectsAndReleasesLock() {
        stubVmLookupAndLock();
        when(userVm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(userVm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.Hyperv);
        unmanagedVMsManagerMockedStatic.when(() -> UnmanagedVMsManager.isSupported(Hypervisor.HypervisorType.Hyperv)).thenReturn(false);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.unmanageUserVM(VM_ID, HOST_ID, managerOperations));

        assertEquals("Unmanaging a VM is currently not supported on hypervisor Hyperv", exception.getMessage());
        verify(userVmDao).releaseFromLockTable(VM_ID);
    }

    @Test
    public void unmanageUserVMWhenItManagerReturnsFalseRejectsWithoutCleanupOrDb() {
        stubVmLookupAndLock();
        stubRunnableVm(VirtualMachine.State.Running);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.emptyList());
        when(virtualMachineManager.unmanage(VM_UUID, HOST_ID)).thenReturn(new Pair<>(false, "Backend failure"));

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.unmanageUserVM(VM_ID, HOST_ID, managerOperations));

        assertEquals("Error while unmanaging VM: " + VM_UUID, exception.getMessage());
        verify(managerOperations, never()).cleanupVmResources(any(UserVmVO.class));
        verify(vmInstanceDao, never()).update(anyLong(), any(VMInstanceVO.class));
        verify(vmInstanceDetailsDao, never()).removeDetails(anyLong());
        verify(userVmDao).releaseFromLockTable(VM_ID);
    }

    @Test
    public void unmanageUserVMWhenValidationThrowsWrapsExceptionAndReleasesLock() {
        RuntimeException testException = new RuntimeException("Something went wrong");
        stubVmLookupAndLock();
        stubRunnableVm(VirtualMachine.State.Running);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.emptyList());
        doThrow(testException).when(vmVolumeLifecycleValidationService).checkUnmanagingVMOngoingVolumeSnapshots(userVm);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.unmanageUserVM(VM_ID, HOST_ID, managerOperations));

        assertSame(testException, exception.getCause());
        verify(userVmDao).releaseFromLockTable(VM_ID);
    }

    @Test
    public void unmanageUserVMWhenSuccessfulRunsValidationUnmanageCleanupDbEventsAndReleasesLock() {
        VolumeVO rootVolume = stubVolume(ROOT_VOLUME_ID, Volume.Type.ROOT);
        VolumeVO dataVolume = stubVolume(DATA_VOLUME_ID, Volume.Type.DATADISK);
        List<VolumeVO> volumes = Arrays.asList(rootVolume, dataVolume);
        stubVmLookupAndLock();
        stubRunnableVm(VirtualMachine.State.Running);
        stubPostProcessing();
        when(volumeDao.findByInstance(VM_ID)).thenReturn(volumes);
        when(virtualMachineManager.unmanage(VM_UUID, HOST_ID)).thenReturn(new Pair<>(true, "Unmanaged successfully"));
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vmInstance);

        Pair<Boolean, String> result = service.unmanageUserVM(VM_ID, HOST_ID, managerOperations);

        assertTrue(result.first());
        assertEquals("Unmanaged successfully", result.second());
        verify(vmVolumeLifecycleValidationService).checkUnmanagingVMOngoingVolumeSnapshots(userVm);
        verify(vmVolumeLifecycleValidationService).checkUnmanagingVMVolumes(userVm, volumes);
        verify(virtualMachineManager).unmanage(VM_UUID, HOST_ID);
        verify(managerOperations).cleanupVmResources(userVm);
        verify(vmInstanceDetailsDao).removeDetails(VM_ID);
        verify(vmInstance).setState(VirtualMachine.State.Expunging);
        verify(vmInstance).setRemoved(any(Date.class));
        verify(vmInstanceDao).update(VM_ID, vmInstance);
        verify(userVmDao).releaseFromLockTable(VM_ID);
    }

    @Test
    public void unmanageVMFromDBRemovesDetailsMarksExpungingAndUpdatesInstance() {
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vmInstance);

        service.unmanageVMFromDB(VM_ID);

        verify(vmInstanceDetailsDao).removeDetails(VM_ID);
        verify(vmInstance).setState(VirtualMachine.State.Expunging);
        verify(vmInstance).setRemoved(any(Date.class));
        verify(vmInstanceDao).update(VM_ID, vmInstance);
    }

    @Test
    public void cleanupUnmanageVMResourcesCleansManagerResourcesAndAffinityGroups() {
        AffinityGroupVMMapVO firstMap = mock(AffinityGroupVMMapVO.class);
        AffinityGroupVMMapVO secondMap = mock(AffinityGroupVMMapVO.class);
        when(firstMap.getId()).thenReturn(1L);
        when(secondMap.getId()).thenReturn(2L);
        when(userVm.getId()).thenReturn(VM_ID);
        when(affinityGroupVMMapDao.listByInstanceId(VM_ID)).thenReturn(Arrays.asList(firstMap, secondMap));

        service.cleanupUnmanageVMResources(userVm, managerOperations);

        verify(managerOperations).cleanupVmResources(userVm);
        verify(affinityGroupVMMapDao).expunge(1L);
        verify(affinityGroupVMMapDao).expunge(2L);
    }

    @Test
    public void cleanupUnmanageVMResourcesNoOpsAffinityWhenNoMaps() {
        when(userVm.getId()).thenReturn(VM_ID);
        when(affinityGroupVMMapDao.listByInstanceId(VM_ID)).thenReturn(Collections.emptyList());

        service.cleanupUnmanageVMResources(userVm, managerOperations);

        verify(managerOperations).cleanupVmResources(userVm);
        verify(affinityGroupVMMapDao, never()).expunge(anyLong());
    }

    @Test
    public void postProcessingUnmanageVMForRunningVmDecrementsVmResourcesOnce() {
        stubVmIdentity();
        when(userVm.getState()).thenReturn(VirtualMachine.State.Running);
        when(serviceOfferingDao.findById(SERVICE_OFFERING_ID)).thenReturn(serviceOffering);
        when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(template);

        service.postProcessingUnmanageVM(userVm, managerOperations);

        verify(managerOperations, times(1)).resourceCountDecrement(ACCOUNT_ID, true, serviceOffering, template);
    }

    @Test
    public void postProcessingUnmanageVMForStoppedVmDecrementsVmResourcesOnce() {
        stubVmIdentity();
        when(userVm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(serviceOfferingDao.findById(SERVICE_OFFERING_ID)).thenReturn(serviceOffering);
        when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(template);

        service.postProcessingUnmanageVM(userVm, managerOperations);

        verify(managerOperations, times(1)).resourceCountDecrement(ACCOUNT_ID, true, serviceOffering, template);
    }

    @Test
    public void postProcessingUnmanageVMVolumesDecrementsAllVolumes() {
        VolumeVO rootVolume = stubVolume(ROOT_VOLUME_ID, Volume.Type.ROOT);
        VolumeVO dataVolume = stubVolume(DATA_VOLUME_ID, Volume.Type.DATADISK);
        when(diskOfferingDao.findByIdIncludingRemoved(DISK_OFFERING_ID)).thenReturn(diskOffering);

        service.postProcessingUnmanageVMVolumes(Arrays.asList(rootVolume, dataVolume), userVm);

        verify(resourceLimitService, times(2)).decrementVolumeResourceCount(ACCOUNT_ID, true, 1024L, diskOffering);
    }

    private void stubVmLookupAndLock() {
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);
        when(userVm.getId()).thenReturn(VM_ID);
        when(userVmDao.acquireInLockTable(VM_ID)).thenReturn(userVm);
    }

    private void stubRunnableVm(VirtualMachine.State state) {
        stubVmIdentity();
        when(userVm.getState()).thenReturn(state);
        when(userVm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(userVm.getUuid()).thenReturn(VM_UUID);
        unmanagedVMsManagerMockedStatic.when(() -> UnmanagedVMsManager.isSupported(Hypervisor.HypervisorType.KVM)).thenReturn(true);
    }

    private void stubVmIdentity() {
        when(userVm.getId()).thenReturn(VM_ID);
        when(userVm.getAccountId()).thenReturn(ACCOUNT_ID);
        when(userVm.getDataCenterId()).thenReturn(ZONE_ID);
        when(userVm.getServiceOfferingId()).thenReturn(SERVICE_OFFERING_ID);
        when(userVm.getTemplateId()).thenReturn(TEMPLATE_ID);
        when(userVm.getHypervisorType()).thenReturn(Hypervisor.HypervisorType.KVM);
        when(userVm.getUuid()).thenReturn(VM_UUID);
        when(userVm.isDisplayVm()).thenReturn(true);
        when(userVm.getHostName()).thenReturn("test-vm-host");
    }

    private void stubPostProcessing() {
        when(managerOperations.cleanupVmResources(userVm)).thenReturn(true);
        when(affinityGroupVMMapDao.listByInstanceId(VM_ID)).thenReturn(Collections.emptyList());
        when(serviceOfferingDao.findById(SERVICE_OFFERING_ID)).thenReturn(serviceOffering);
        when(templateDao.findByIdIncludingRemoved(TEMPLATE_ID)).thenReturn(template);
        when(diskOfferingDao.findByIdIncludingRemoved(DISK_OFFERING_ID)).thenReturn(diskOffering);
    }

    private VolumeVO stubVolume(long volumeId, Volume.Type type) {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getId()).thenReturn(volumeId);
        when(volume.getVolumeType()).thenReturn(type);
        when(volume.getAccountId()).thenReturn(ACCOUNT_ID);
        when(volume.getDataCenterId()).thenReturn(ZONE_ID);
        when(volume.getName()).thenReturn("volume-" + volumeId);
        when(volume.getUuid()).thenReturn("volume-uuid-" + volumeId);
        when(volume.isDisplayVolume()).thenReturn(true);
        when(volume.getSize()).thenReturn(1024L);
        when(volume.getDiskOfferingId()).thenReturn(DISK_OFFERING_ID);
        return volume;
    }
}
