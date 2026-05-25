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
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.UUID;

import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.Outcome;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobJoinMapDao;
import org.apache.cloudstack.framework.jobs.dao.VmWorkJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedConstruction;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.storage.Storage.ProvisioningType;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.VmDiskStatisticsDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VmWorkAttachVolume;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VolumeAttachServiceImplTest {

    @Mock private VolumeDataFactory volFactory;
    @Mock private UserVmDao userVmDao;
    @Mock private VMInstanceDao vmInstanceDao;
    @Mock private VolumeDao volsDao;
    @Mock private VMTemplateDao templateDao;
    @Mock private DataCenterDao dcDao;
    @Mock private ClusterDao clusterDao;
    @Mock private HostPodDao podDao;
    @Mock private DiskOfferingDao diskOfferingDao;
    @Mock private PrimaryDataStoreDao storagePoolDao;
    @Mock private HostDao hostDao;
    @Mock private VmDiskStatisticsDao vmDiskStatsDao;
    @Mock private AccountDao accountDao;
    @Mock private AccountManager accountMgr;
    @Mock private ResourceLimitService resourceLimitMgr;
    @Mock private AsyncJobManager jobMgr;
    @Mock private VmWorkJobDao workJobDao;
    @Mock private AgentManager agentMgr;
    @Mock private VolumeOrchestrationService volumeMgr;
    @Mock private VolumeService volService;
    @Mock private org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager dataStoreMgr;
    @Mock private StorageManager storageMgr;
    @Mock private VirtualMachineManager virtualMachineManager;
    @Mock private VolumeAttachValidator volumeAttachValidator;
    @Mock private VolumeHostTopologyService volumeHostTopologyService;
    @Mock private AsyncJobJoinMapDao joinMapDao;

    @Spy
    @InjectMocks
    private VolumeAttachServiceImpl service = new VolumeAttachServiceImpl();

    private static final long VM_ID = 2L;
    private static final long VOL_ID = 9L;
    private static final long POOL_ID = 1L;

    @Before
    public void setUp() {
        AccountVO account = new AccountVO("admin", 1L, "networkDomain", Account.Type.NORMAL, UUID.randomUUID().toString());
        UserVO user = new UserVO(1, "testuser", "password", "firstname", "lastName", "email", "timezone",
                UUID.randomUUID().toString(), User.Source.UNKNOWN);
        CallContext.register(user, account);

        AsyncJobExecutionContext.init(jobMgr, joinMapDao);
        AsyncJobExecutionContext context = new AsyncJobExecutionContext();
        AsyncJobVO job = new AsyncJobVO();
        org.springframework.test.util.ReflectionTestUtils.setField(job, "id", 500L);
        context.setJob(job);
        AsyncJobExecutionContext.setCurrentExecutionContext(context);

        lenient().doNothing().when(accountMgr).checkAccess(any(Account.class), any(), anyBoolean(), any());
        lenient().doNothing().when(jobMgr).updateAsyncJobAttachment(anyLong(), anyString(), anyLong());
        lenient().when(jobMgr.submitAsyncJob(any(AsyncJobVO.class), anyString(), anyLong())).thenReturn(1L);
        lenient().when(jobMgr.getAsyncJob(anyLong())).thenReturn(new AsyncJobVO());
        lenient().when(workJobDao.expunge(anyLong())).thenReturn(true);
        lenient().when(resourceLimitMgr.getResourceLimitStorageTagsForResourceCountOperation(anyBoolean(), any()))
                .thenReturn(Collections.emptyList());
        lenient().when(volumeAttachValidator.getRequiredPrimaryStorageSizeForVolumeAttach(any(), any())).thenReturn(0L);
        lenient().when(accountDao.findById(anyLong())).thenReturn(Mockito.mock(AccountVO.class));
        lenient().when(volumeHostTopologyService.getMaxDataVolumesSupported(any())).thenReturn(10);
    }

    @After
    public void tearDown() {
        CallContext.unregisterAll();
    }

    private VolumeInfo mockVolumeInfo(long id, Volume.Type type, Volume.State state) {
        VolumeInfo volume = Mockito.mock(VolumeInfo.class);
        lenient().when(volume.getId()).thenReturn(id);
        lenient().when(volume.getVolumeType()).thenReturn(type);
        lenient().when(volume.getState()).thenReturn(state);
        lenient().when(volume.getDataCenterId()).thenReturn(1L);
        lenient().when(volume.getInstanceId()).thenReturn(null);
        lenient().when(volume.getAccountId()).thenReturn(3L);
        lenient().when(volume.getDiskOfferingId()).thenReturn(1L);
        lenient().when(volume.isAttachedVM()).thenReturn(false);
        return volume;
    }

    private UserVmVO mockUserVm(long id, State state, HypervisorType hypervisorType) {
        UserVmVO vm = new UserVmVO(id, "vm-" + id, "vm-" + id, 1L, hypervisorType, 1L,
                false, false, 1L, 3L, 1L, 1L, null, null, null, "vm-" + id);
        vm.setState(state);
        vm.setDataCenterId(1L);
        vm.setBackupOfferingId(null);
        vm.setUserVmType("User");
        vm.setPodIdToDeployIn(1L);
        return vm;
    }

    private DiskOfferingVO mockDiskOffering(boolean encrypt) {
        DiskOfferingVO offering = new DiskOfferingVO();
        offering.setEncrypt(encrypt);
        offering.setUseLocalStorage(false);
        offering.setRecreatable(false);
        offering.setTags(null);
        return offering;
    }

    private void setNonVmWorkExecutionContext() {
        ((AsyncJobVO)AsyncJobExecutionContext.getCurrentExecutionContext().getJob()).setDispatcher("api");
    }

    private void setVmWorkExecutionContext() {
        ((AsyncJobVO)AsyncJobExecutionContext.getCurrentExecutionContext().getJob()).setDispatcher(com.cloud.vm.VmWorkConstants.VM_WORK_JOB_DISPATCHER);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void attachVolumeToVMRejectsInvalidVolumeType() {
        VolumeInfo volume = mockVolumeInfo(VOL_ID, Volume.Type.ISO, Volume.State.Ready);
        when(volFactory.getVolume(VOL_ID)).thenReturn(volume);
        service.attachVolumeToVM(VM_ID, VOL_ID, 0L, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void attachVolumeToVMRejectsMissingUserVm() {
        VolumeInfo volume = mockVolumeInfo(VOL_ID, Volume.Type.DATADISK, Volume.State.Ready);
        when(volFactory.getVolume(VOL_ID)).thenReturn(volume);
        when(userVmDao.findById(VM_ID)).thenReturn(null);
        service.attachVolumeToVM(VM_ID, VOL_ID, 0L, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void attachVolumeToVMRejectsZoneMismatch() {
        VolumeInfo volume = mockVolumeInfo(VOL_ID, Volume.Type.DATADISK, Volume.State.Ready);
        when(volume.getDataCenterId()).thenReturn(2L);
        when(volFactory.getVolume(VOL_ID)).thenReturn(volume);
        when(userVmDao.findById(VM_ID)).thenReturn(mockUserVm(VM_ID, State.Running, HypervisorType.XenServer));
        service.attachVolumeToVM(VM_ID, VOL_ID, 0L, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void attachVolumeToVMRejectsSharedFsVmWhenNotAllowed() {
        VolumeInfo volume = mockVolumeInfo(VOL_ID, Volume.Type.DATADISK, Volume.State.Ready);
        UserVmVO vm = mockUserVm(VM_ID, State.Running, HypervisorType.XenServer);
        vm.setUserVmType(UserVmManager.SHAREDFSVM);
        when(volFactory.getVolume(VOL_ID)).thenReturn(volume);
        when(userVmDao.findById(VM_ID)).thenReturn(vm);
        service.attachVolumeToVM(VM_ID, VOL_ID, 0L, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void attachVolumeToVMRejectsExternalHypervisor() {
        VolumeInfo volume = mockVolumeInfo(VOL_ID, Volume.Type.DATADISK, Volume.State.Ready);
        when(volFactory.getVolume(VOL_ID)).thenReturn(volume);
        when(userVmDao.findById(VM_ID)).thenReturn(mockUserVm(VM_ID, State.Running, HypervisorType.External));
        service.attachVolumeToVM(VM_ID, VOL_ID, 0L, false);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void attachVolumeToVMRejectsEncryptedOfferingOnNonKvm() {
        VolumeInfo volume = mockVolumeInfo(10L, Volume.Type.DATADISK, Volume.State.Allocated);
        when(volFactory.getVolume(10L)).thenReturn(volume);
        when(userVmDao.findById(4L)).thenReturn(mockUserVm(4L, State.Running, HypervisorType.XenServer));
        when(diskOfferingDao.findById(anyLong())).thenReturn(mockDiskOffering(true));
        service.attachVolumeToVM(4L, 10L, 1L, false);
    }

    @Test
    public void attachVolumeToVMUsesCheckedReservationAndJobResultForNonVmWork() throws Exception {
        setNonVmWorkExecutionContext();
        VolumeInfo volume = mockVolumeInfo(VOL_ID, Volume.Type.DATADISK, Volume.State.Uploaded);
        UserVmVO vm = mockUserVm(VM_ID, State.Running, HypervisorType.KVM);
        VolumeVO expected = Mockito.mock(VolumeVO.class);
        when(volFactory.getVolume(VOL_ID)).thenReturn(volume);
        when(userVmDao.findById(VM_ID)).thenReturn(vm);
        when(diskOfferingDao.findById(anyLong())).thenReturn(mockDiskOffering(false));
        lenient().when(volsDao.findByInstanceAndType(anyLong(), eq(Volume.Type.DATADISK))).thenReturn(new ArrayList<>(10));
        doReturn(expected).when(service).getVolumeAttachJobResult(eq(VM_ID), eq(VOL_ID), eq(0L));
        try (MockedConstruction<CheckedReservation> mocked = Mockito.mockConstruction(CheckedReservation.class)) {
            assertSame(expected, service.attachVolumeToVM(VM_ID, VOL_ID, 0L, false));
            Assert.assertEquals(1, mocked.constructed().size());
        }
    }

    @Test
    public void attachVolumeToVMUsesVmWorkShimPathWhenDispatchedByVmWork() throws Exception {
        setVmWorkExecutionContext();
        VolumeInfo volume = mockVolumeInfo(VOL_ID, Volume.Type.DATADISK, Volume.State.Ready);
        UserVmVO vm = mockUserVm(VM_ID, State.Running, HypervisorType.KVM);
        Volume expected = Mockito.mock(Volume.class);
        when(volFactory.getVolume(VOL_ID)).thenReturn(volume);
        when(userVmDao.findById(VM_ID)).thenReturn(vm);
        when(diskOfferingDao.findById(anyLong())).thenReturn(mockDiskOffering(false));
        lenient().when(volsDao.findByInstanceAndType(anyLong(), eq(Volume.Type.DATADISK))).thenReturn(Collections.emptyList());
        doReturn(expected).when(service).orchestrateAttachVolumeToVM(eq(VM_ID), eq(VOL_ID), eq(0L));
        Mockito.doAnswer(invocation -> {
            VmWorkJobVO placeHolder = invocation.getArgument(0);
            org.springframework.test.util.ReflectionTestUtils.setField(placeHolder, "id", 41L);
            return placeHolder;
        }).when(workJobDao).persist(any(VmWorkJobVO.class));
        try (MockedConstruction<CheckedReservation> mocked = Mockito.mockConstruction(CheckedReservation.class)) {
            assertSame(expected, service.attachVolumeToVM(VM_ID, VOL_ID, 0L, false));
            Assert.assertEquals(1, mocked.constructed().size());
        }
        verify(workJobDao).persist(any(VmWorkJobVO.class));
        verify(workJobDao).expunge(41L);
    }

    @Test(expected = CloudRuntimeException.class)
    public void getVmExistingVolumeForVolumeAttachThrowsForMultipleRootVolumes() {
        UserVmVO vm = mockUserVm(1L, State.Stopped, HypervisorType.XenServer);
        vm.setTemplateId(10L);
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        lenient().when(template.isDeployAsIs()).thenReturn(false);
        when(templateDao.findById(10L)).thenReturn(template);
        when(volsDao.findByInstanceAndType(1L, Volume.Type.ROOT))
                .thenReturn(Arrays.asList(Mockito.mock(VolumeVO.class), Mockito.mock(VolumeVO.class)));
        service.getVmExistingVolumeForVolumeAttach(vm, Mockito.mock(VolumeInfo.class));
    }

    @Test
    public void getVmExistingVolumeForVolumeAttachFallsBackToFirstNonAllocatedDataDisk() {
        UserVmVO vm = mockUserVm(1L, State.Stopped, HypervisorType.XenServer);
        vm.setTemplateId(10L);
        VMTemplateVO template = Mockito.mock(VMTemplateVO.class);
        lenient().when(template.isDeployAsIs()).thenReturn(false);
        when(templateDao.findById(10L)).thenReturn(template);
        lenient().when(volsDao.findByInstanceAndType(1L, Volume.Type.ROOT)).thenReturn(Collections.emptyList());
        VolumeVO allocated = new VolumeVO("a", 1L, 1L, 1L, 1L, 1L, "a", "a", ProvisioningType.THIN, 1L, null, null, "a", Volume.Type.DATADISK);
        allocated.setState(Volume.State.Allocated);
        VolumeVO ready = new VolumeVO("b", 1L, 1L, 1L, 1L, 1L, "b", "b", ProvisioningType.THIN, 1L, null, null, "b", Volume.Type.DATADISK);
        ready.setState(Volume.State.Ready);
        when(volsDao.findByInstanceAndType(1L, Volume.Type.DATADISK)).thenReturn(Arrays.asList(allocated, ready));
        assertSame(ready, service.getVmExistingVolumeForVolumeAttach(vm, Mockito.mock(VolumeInfo.class)));
    }

    @Test
    public void createVolumeOnPrimaryForAttachIfNeededReturnsOriginalVolumeForStoppedVmWithoutPool() {
        VolumeInfo volume = mockVolumeInfo(VOL_ID, Volume.Type.DATADISK, Volume.State.Allocated);
        UserVmVO vm = mockUserVm(VM_ID, State.Stopped, HypervisorType.KVM);
        doReturn(null).when(service).getSuitablePoolForAllocatedOrUploadedVolumeForAttach(volume, vm);
        assertSame(volume, service.createVolumeOnPrimaryForAttachIfNeeded(volume, vm, null));
        try {
            verify(volumeMgr, never()).createVolumeOnPrimaryStorage(any(), any(), any(), any());
        } catch (com.cloud.utils.fsm.NoTransitionException e) {
            Assert.fail(e.getMessage());
        }
    }

    @Test(expected = InvalidParameterValueException.class)
    public void createVolumeOnPrimaryForAttachIfNeededRejectsUploadedVolumeOnPowerFlex() {
        VolumeInfo volume = mockVolumeInfo(VOL_ID, Volume.Type.DATADISK, Volume.State.Uploaded);
        UserVmVO vm = mockUserVm(VM_ID, State.Running, HypervisorType.KVM);
        StoragePool pool = Mockito.mock(StoragePool.class);
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.PowerFlex);
        doReturn(pool).when(service).getSuitablePoolForAllocatedOrUploadedVolumeForAttach(volume, vm);
        service.createVolumeOnPrimaryForAttachIfNeeded(volume, vm, null);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void getVolumeAttachJobResultRethrowsInvalidParameterValueException() {
        @SuppressWarnings("unchecked")
        Outcome<Volume> outcome = Mockito.mock(Outcome.class);
        when(outcome.getJob()).thenReturn(new AsyncJobVO());
        doReturn(outcome).when(service).attachVolumeToVmThroughJobQueue(VM_ID, VOL_ID, 0L);
        when(jobMgr.unmarshallResultObject(any())).thenReturn(new InvalidParameterValueException("bad volume"));
        service.getVolumeAttachJobResult(VM_ID, VOL_ID, 0L);
    }

    @Test(expected = ConcurrentOperationException.class)
    public void getVolumeAttachJobResultRethrowsConcurrentOperationException() {
        @SuppressWarnings("unchecked")
        Outcome<Volume> outcome = Mockito.mock(Outcome.class);
        when(outcome.getJob()).thenReturn(new AsyncJobVO());
        doReturn(outcome).when(service).attachVolumeToVmThroughJobQueue(VM_ID, VOL_ID, 0L);
        when(jobMgr.unmarshallResultObject(any())).thenReturn(new ConcurrentOperationException("busy"));
        service.getVolumeAttachJobResult(VM_ID, VOL_ID, 0L);
    }

    @Test
    public void getVolumeAttachJobResultResolvesLongResultToVolume() {
        @SuppressWarnings("unchecked")
        Outcome<Volume> outcome = Mockito.mock(Outcome.class);
        when(outcome.getJob()).thenReturn(new AsyncJobVO());
        VolumeVO volume = Mockito.mock(VolumeVO.class);
        doReturn(outcome).when(service).attachVolumeToVmThroughJobQueue(VM_ID, VOL_ID, 0L);
        when(jobMgr.unmarshallResultObject(any())).thenReturn(42L);
        when(volsDao.findById(42L)).thenReturn(volume);
        assertSame(volume, service.getVolumeAttachJobResult(VM_ID, VOL_ID, 0L));
    }

    @Test
    public void attachVolumeToVmThroughJobQueueBuildsAttachWorkAndJoinsJob() {
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(VM_ID);
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);
        Outcome<Volume> outcome = service.attachVolumeToVmThroughJobQueue(VM_ID, VOL_ID, 3L);

        Assert.assertNotNull(outcome);

        ArgumentCaptor<VmWorkJobVO> captor = ArgumentCaptor.forClass(VmWorkJobVO.class);
        verify(jobMgr).submitAsyncJob(captor.capture(), eq(com.cloud.vm.VmWorkConstants.VM_WORK_QUEUE), eq(VM_ID));
        VmWorkJobVO workJob = captor.getValue();
        assertEquals(VirtualMachine.Type.Instance, workJob.getVmType());
        Assert.assertEquals(VM_ID, workJob.getVmInstanceId());
        assertEquals("500", workJob.getRelated());
        assertEquals(VmWorkAttachVolume.class.getName(), workJob.getCmd());
        verify(jobMgr).joinJob(500L, 0L);
    }
}
