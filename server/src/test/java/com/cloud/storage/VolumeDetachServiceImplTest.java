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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.cloudstack.api.command.user.volume.DetachVolumeCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.Outcome;
import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobJoinMapDao;
import org.apache.cloudstack.framework.jobs.dao.VmWorkJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.utils.Pair;
import com.cloud.vm.UserVmService;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VmWorkDetachVolume;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

/**
 * Focused unit tests for {@link VolumeDetachServiceImpl}.
 *
 * <p>Tests cover all four public methods of the extracted service plus
 * the key private helpers (handleTargetsForVMware, updateMissingRootDiskController
 * copy, detachVolumeFromVmThroughJobQueue job-building).</p>
 */
@RunWith(MockitoJUnitRunner.class)
public class VolumeDetachServiceImplTest {

    // -----------------------------------------------------------------------
    // mocks
    // -----------------------------------------------------------------------

    @Mock private VolumeDao volsDao;
    @Mock private VMInstanceDao vmInstanceDao;
    @Mock private UserVmDao userVmDao;
    @Mock private VMSnapshotDao vmSnapshotDao;
    @Mock private PrimaryDataStoreDao storagePoolDao;
    @Mock private AccountManager accountMgr;
    @Mock private AgentManager agentMgr;
    @Mock private AsyncJobManager jobMgr;
    @Mock private AsyncJobJoinMapDao joinMapDao;
    @Mock private VmWorkJobDao workJobDao;
    @Mock private VolumeDataFactory volFactory;
    @Mock private VolumeService volService;
    @Mock private DataStoreManager dataStoreMgr;
    @Mock private VolumeOrchestrationService volumeMgr;
    @Mock private VolumeAttachValidator volumeAttachValidator;
    @Mock private HostDao hostDao;
    @Mock private UserVmService userVmService;
    @Mock private VolumeHostTopologyService volumeHostTopologyService;
    @Mock private DiskOfferingDao diskOfferingDao;

    @InjectMocks
    private VolumeDetachServiceImpl service = new VolumeDetachServiceImpl();

    private MockedStatic<UsageEventUtils> usageEventUtilsMock;

    // -----------------------------------------------------------------------
    // test fixtures
    // -----------------------------------------------------------------------

    private static final long VM_ID     = 10L;
    private static final long VOL_ID    = 20L;
    private static final long POOL_ID   = 30L;
    private static final long HOST_ID   = 40L;
    private static final long ACCOUNT_ID = 1L;

    @Before
    public void setUp() {
        AccountVO account = new AccountVO("admin", 1L, "domain", Account.Type.NORMAL, UUID.randomUUID().toString());
        UserVO user = new UserVO(1, "testuser", "password", "first", "last", "email", "tz",
                UUID.randomUUID().toString(), User.Source.UNKNOWN);
        CallContext.register(user, account);

        AsyncJobExecutionContext ctx = new AsyncJobExecutionContext();
        AsyncJobExecutionContext.init(jobMgr, joinMapDao);
        AsyncJobVO job = new AsyncJobVO();
        ctx.setJob(job);
        AsyncJobExecutionContext.setCurrentExecutionContext(ctx);

        Mockito.lenient().when(jobMgr.submitAsyncJob(any(AsyncJobVO.class), anyString(), anyLong())).thenReturn(1L);
        Mockito.lenient().doNothing().when(jobMgr).updateAsyncJobAttachment(anyLong(), anyString(), anyLong());

        usageEventUtilsMock = Mockito.mockStatic(UsageEventUtils.class);
        usageEventUtilsMock.when(() -> UsageEventUtils.publishUsageEvent(
                anyString(), anyLong(), anyLong(), anyLong(), anyString(),
                anyLong(), any(), anyLong(), anyString(), anyString(), any(), anyBoolean()))
                .thenAnswer(inv -> null);
    }

    @After
    public void tearDown() {
        CallContext.unregisterAll();
        if (usageEventUtilsMock != null) {
            usageEventUtilsMock.close();
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------

    private VolumeVO makeVolume(long id, long vmId, Volume.Type type, Long poolId) {
        VolumeVO v = new VolumeVO("vol-" + id, 1L, 1L, 1L, 1L, vmId, "path", "path",
                Storage.ProvisioningType.THIN, 1L, null, null, "vol-" + id, type);
        ReflectionTestUtils.setField(v, "id", id);
        if (poolId != null) {
            v.setPoolId(poolId);
        }
        return v;
    }

    private UserVmVO makeVm(long id, State state, HypervisorType ht) {
        UserVmVO vm = new UserVmVO(id, "vm-" + id, "vm-" + id, 1, ht, 1L,
                false, false, 1L, 1L, 1, 1L, null, null, null, "vm-" + id);
        vm.setState(state);
        vm.setDataCenterId(1L);
        return vm;
    }

    private StoragePoolVO makePool(long id, boolean managed) {
        StoragePoolVO pool = new StoragePoolVO();
        ReflectionTestUtils.setField(pool, "id", id);
        pool.setManaged(managed);
        return pool;
    }

    /** Build a minimal DetachVolumeCmd with the given volume id set via reflection. */
    private DetachVolumeCmd makeDetachCmd(Long volumeId) throws Exception {
        DetachVolumeCmd cmd = new DetachVolumeCmd();
        java.lang.reflect.Field f = DetachVolumeCmd.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(cmd, volumeId);
        return cmd;
    }

    // -----------------------------------------------------------------------
    // 1. detachVolumeFromVM — volume null → InvalidParameterValueException
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void testDetachVolumeFromVM_volumeNull_throws() throws Exception {
        when(volsDao.findById(VOL_ID)).thenReturn(null);
        DetachVolumeCmd cmd = makeDetachCmd(VOL_ID);
        service.detachVolumeFromVM(cmd);
    }

    // -----------------------------------------------------------------------
    // 2. detachVolumeFromVM — volume not attached (vmId null) → throws
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void testDetachVolumeFromVM_volumeNotAttached_throws() throws Exception {
        // volume has no instanceId (not attached)
        VolumeVO vol = makeVolume(VOL_ID, 0L, Volume.Type.DATADISK, POOL_ID);
        when(volsDao.findById(VOL_ID)).thenReturn(vol);
        // volumeInstanceId is 0, cmd.getVirtualMachineId() is null → vmId will be null
        // But volume.getInstanceId() returns 0, not null. Let's use a mock.
        VolumeVO mockVol = Mockito.mock(VolumeVO.class);
        when(mockVol.getInstanceId()).thenReturn(null);
        when(volsDao.findById(VOL_ID)).thenReturn(mockVol);
        Mockito.lenient().doNothing().when(accountMgr).checkAccess(any(), any(), anyBoolean(), any());
        DetachVolumeCmd cmd = makeDetachCmd(VOL_ID);
        service.detachVolumeFromVM(cmd);
    }

    // -----------------------------------------------------------------------
    // 3. detachVolumeFromVM — VM has snapshots → throws
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void testDetachVolumeFromVM_vmHasSnapshots_throws() throws Exception {
        VolumeVO vol = makeVolume(VOL_ID, VM_ID, Volume.Type.DATADISK, POOL_ID);
        when(volsDao.findById(VOL_ID)).thenReturn(vol);
        Mockito.lenient().doNothing().when(accountMgr).checkAccess(any(), any(), anyBoolean(), any());

        UserVmVO vm = makeVm(VM_ID, State.Stopped, HypervisorType.KVM);
        when(userVmDao.findById(VM_ID)).thenReturn(vm);

        List<VMSnapshotVO> snaps = new ArrayList<>();
        snaps.add(Mockito.mock(VMSnapshotVO.class));
        when(vmSnapshotDao.findByVm(VM_ID)).thenReturn(snaps);

        DetachVolumeCmd cmd = makeDetachCmd(VOL_ID);
        service.detachVolumeFromVM(cmd);
    }

    // -----------------------------------------------------------------------
    // 4. detachVolumeFromVM — root volume on HyperV → validateRootVolumeDetachAttach throws
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void testDetachVolumeFromVM_rootVolumeHyperVThrows() throws Exception {
        VolumeVO vol = makeVolume(VOL_ID, VM_ID, Volume.Type.ROOT, POOL_ID);
        when(volsDao.findById(VOL_ID)).thenReturn(vol);
        Mockito.lenient().doNothing().when(accountMgr).checkAccess(any(), any(), anyBoolean(), any());

        UserVmVO vm = makeVm(VM_ID, State.Stopped, HypervisorType.Hyperv);
        when(userVmDao.findById(VM_ID)).thenReturn(vm);
        Mockito.lenient().when(vmSnapshotDao.findByVm(VM_ID)).thenReturn(Collections.emptyList());

        doThrow(new InvalidParameterValueException("Root volume detach not supported")).when(volumeAttachValidator)
                .validateRootVolumeDetachAttach(any(VolumeVO.class), any(UserVmVO.class));

        DetachVolumeCmd cmd = makeDetachCmd(VOL_ID);
        service.detachVolumeFromVM(cmd);
    }

    // -----------------------------------------------------------------------
    // 5. detachVolumeFromVM — bad volume type → InvalidParameterValueException
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void testDetachVolumeFromVM_isoVolumeType_throws() throws Exception {
        // Volume type is neither ROOT nor DATADISK
        VolumeVO vol = Mockito.mock(VolumeVO.class);
        when(vol.getInstanceId()).thenReturn(VM_ID);
        when(vol.getVolumeType()).thenReturn(Volume.Type.ISO);
        when(volsDao.findById(VOL_ID)).thenReturn(vol);
        Mockito.lenient().doNothing().when(accountMgr).checkAccess(any(), any(), anyBoolean(), any());

        UserVmVO vm = makeVm(VM_ID, State.Stopped, HypervisorType.KVM);
        when(userVmDao.findById(VM_ID)).thenReturn(vm);
        Mockito.lenient().when(vmSnapshotDao.findByVm(VM_ID)).thenReturn(Collections.emptyList());
        Mockito.lenient().doNothing().when(volumeAttachValidator).checkForBackups(any(), anyBoolean());

        DetachVolumeCmd cmd = makeDetachCmd(VOL_ID);
        service.detachVolumeFromVM(cmd);
    }

    // -----------------------------------------------------------------------
    // 6. detachVolumeViaDestroyVM — delegates straight to orchestrate
    // -----------------------------------------------------------------------

    @Test
    public void testDetachVolumeViaDestroyVM_callsOrchestrate() {
        VolumeVO vol = makeVolume(VOL_ID, VM_ID, Volume.Type.DATADISK, POOL_ID);
        when(volsDao.findById(VOL_ID)).thenReturn(vol);
        Mockito.lenient().doNothing().when(accountMgr).checkAccess(any(), any(), anyBoolean(), any());

        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        when(vm.getState()).thenReturn(State.Stopped);
        when(vm.getHostName()).thenReturn("host1");
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);

        StoragePoolVO pool = makePool(POOL_ID, false);
        when(storagePoolDao.findByIdIncludingRemoved(POOL_ID)).thenReturn(pool);
        when(volumeHostTopologyService.getHostForVmVolumeAttachDetach(any(), any())).thenReturn(null);
        Mockito.lenient().when(volumeHostTopologyService.isSendCommandForVmVolumeAttachDetach(any(), any())).thenReturn(false);

        // sendCommand=false (stopped, host=null, pool not requiring send)
        // volsDao.detachVolume should be called
        VolumeVO detached = makeVolume(VOL_ID, 0L, Volume.Type.DATADISK, POOL_ID);
        Mockito.lenient().when(volsDao.findById(VOL_ID)).thenReturn(detached);

        // pool.getPoolId() is not null → provideVMInfo
        DataStore ds = Mockito.mock(DataStore.class);
        Mockito.lenient().when(dataStoreMgr.getDataStore(anyLong(), any())).thenReturn(ds);
        VolumeInfo volInfo = Mockito.mock(VolumeInfo.class);
        Mockito.lenient().when(volFactory.getVolume(anyLong())).thenReturn(volInfo);
        Mockito.lenient().doNothing().when(volService).revokeAccess(any(), any(), any());
        Mockito.lenient().doNothing().when(volumeHostTopologyService).provideVMInfo(any(), anyLong(), anyLong());

        Volume result = service.detachVolumeViaDestroyVM(VM_ID, VOL_ID);
        assertNotNull(result);
        verify(volsDao).detachVolume(VOL_ID);
    }

    // -----------------------------------------------------------------------
    // 7. orchestrateDetachVolumeFromVM — happy path (stopped VM, no host)
    // -----------------------------------------------------------------------

    @Test
    public void testOrchestrateDetach_stoppedVm_noSendCommand_detachesVolume() throws Exception {
        VolumeVO vol = makeVolume(VOL_ID, VM_ID, Volume.Type.DATADISK, POOL_ID);
        Mockito.lenient().when(volsDao.findById(VOL_ID)).thenReturn(vol);

        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        when(vm.getState()).thenReturn(State.Stopped);
        when(vm.getHostName()).thenReturn("vm-host");
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);

        StoragePoolVO pool = makePool(POOL_ID, false);
        when(storagePoolDao.findByIdIncludingRemoved(POOL_ID)).thenReturn(pool);
        when(volumeHostTopologyService.getHostForVmVolumeAttachDetach(any(), any())).thenReturn(null);
        when(volumeHostTopologyService.isSendCommandForVmVolumeAttachDetach(any(), any())).thenReturn(false);

        DataStore ds = Mockito.mock(DataStore.class);
        when(dataStoreMgr.getDataStore(POOL_ID, DataStoreRole.Primary)).thenReturn(ds);
        VolumeInfo volInfo = Mockito.mock(VolumeInfo.class);
        when(volFactory.getVolume(VOL_ID)).thenReturn(volInfo);
        Mockito.lenient().doNothing().when(volService).revokeAccess(any(), any(), any());
        Mockito.lenient().doNothing().when(volumeHostTopologyService).provideVMInfo(any(), anyLong(), anyLong());

        VolumeVO detachedVol = makeVolume(VOL_ID, 0L, Volume.Type.DATADISK, POOL_ID);
        when(volsDao.findById(VOL_ID)).thenReturn(detachedVol);

        Volume result = service.orchestrateDetachVolumeFromVM(VM_ID, VOL_ID);

        assertNotNull(result);
        verify(volsDao).detachVolume(VOL_ID);
        verify(agentMgr, never()).send(anyLong(), any(com.cloud.agent.api.Command.class));
    }

    // -----------------------------------------------------------------------
    // 8. orchestrateDetachVolumeFromVM — answer fails → CloudRuntimeException
    // -----------------------------------------------------------------------

    @Test(expected = com.cloud.utils.exception.CloudRuntimeException.class)
    public void testOrchestrateDetach_agentAnswerFails_throws() throws Exception {
        VolumeVO vol = makeVolume(VOL_ID, VM_ID, Volume.Type.DATADISK, POOL_ID);
        when(volsDao.findById(VOL_ID)).thenReturn(vol);

        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        when(vm.getState()).thenReturn(State.Running);
        when(vm.getHostName()).thenReturn("vm-host");
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);

        StoragePoolVO pool = makePool(POOL_ID, false);
        when(storagePoolDao.findByIdIncludingRemoved(POOL_ID)).thenReturn(pool);
        HostVO host = Mockito.mock(HostVO.class);
        when(host.getId()).thenReturn(HOST_ID);
        when(volumeHostTopologyService.getHostForVmVolumeAttachDetach(any(), any())).thenReturn(host);
        Mockito.lenient().when(volumeHostTopologyService.isSendCommandForVmVolumeAttachDetach(any(), any())).thenReturn(false);

        // Stub the vol stats + DettachCommand send
        UserVmVO userVm = makeVm(VM_ID, State.Running, HypervisorType.KVM);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);

        VolumeInfo volInfo = Mockito.mock(VolumeInfo.class);
        org.apache.cloudstack.storage.to.VolumeObjectTO volTO = Mockito.mock(org.apache.cloudstack.storage.to.VolumeObjectTO.class);
        when(volFactory.getVolume(VOL_ID)).thenReturn(volInfo);
        when(volInfo.getTO()).thenReturn(volTO);
        Mockito.lenient().when(volumeMgr.getVolumeCheckpointPathsAndImageStoreUrls(anyLong(), any()))
                .thenReturn(new Pair<>(null, null));

        StoragePoolVO poolForParent = makePool(POOL_ID, false);
        when(storagePoolDao.findById(POOL_ID)).thenReturn(poolForParent);

        Answer answer = Mockito.mock(Answer.class);
        when(answer.getResult()).thenReturn(false);
        when(answer.getDetails()).thenReturn("disk busy");
        when(agentMgr.send(anyLong(), any(com.cloud.agent.api.Command.class))).thenReturn(answer);

        service.orchestrateDetachVolumeFromVM(VM_ID, VOL_ID);
    }

    // -----------------------------------------------------------------------
    // 9. orchestrateDetachVolumeFromVM — VMware pool → handleTargetsForVMware
    // -----------------------------------------------------------------------

    @Test
    public void testOrchestrateDetach_vmwarePool_sendsModifyTargets() {
        VolumeVO vol = makeVolume(VOL_ID, VM_ID, Volume.Type.DATADISK, POOL_ID);
        Mockito.lenient().when(volsDao.findById(VOL_ID)).thenReturn(vol);

        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        when(vm.getState()).thenReturn(State.Stopped);
        when(vm.getHostName()).thenReturn("esxi-host");
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);

        StoragePoolVO pool = makePool(POOL_ID, false);
        pool.setHostAddress("192.168.1.1");
        pool.setPort(3260);
        when(storagePoolDao.findByIdIncludingRemoved(POOL_ID)).thenReturn(pool);

        HostVO host = Mockito.mock(HostVO.class);
        when(host.getId()).thenReturn(HOST_ID);
        when(host.getHypervisorType()).thenReturn(HypervisorType.VMware);
        when(volumeHostTopologyService.getHostForVmVolumeAttachDetach(any(), any())).thenReturn(host);
        when(volumeHostTopologyService.isSendCommandForVmVolumeAttachDetach(any(), any())).thenReturn(false);
        when(hostDao.findById(HOST_ID)).thenReturn(host);

        DataStore ds = Mockito.mock(DataStore.class);
        when(dataStoreMgr.getDataStore(POOL_ID, DataStoreRole.Primary)).thenReturn(ds);
        VolumeInfo volInfo = Mockito.mock(VolumeInfo.class);
        when(volFactory.getVolume(VOL_ID)).thenReturn(volInfo);
        Mockito.lenient().doNothing().when(volService).revokeAccess(any(), any(), any());
        Mockito.lenient().doNothing().when(volumeHostTopologyService).provideVMInfo(any(), anyLong(), anyLong());

        Answer modifyAnswer = Mockito.mock(Answer.class);
        when(modifyAnswer.getResult()).thenReturn(true);
        when(agentMgr.easySend(eq(HOST_ID), any())).thenReturn(modifyAnswer);

        VolumeVO detached = makeVolume(VOL_ID, 0L, Volume.Type.DATADISK, POOL_ID);
        when(volsDao.findById(VOL_ID)).thenReturn(detached);

        service.orchestrateDetachVolumeFromVM(VM_ID, VOL_ID);

        // verify ModifyTargetsCommand was sent to agent
        verify(agentMgr).easySend(eq(HOST_ID), any(com.cloud.agent.api.ModifyTargetsCommand.class));
    }

    // -----------------------------------------------------------------------
    // 10. orchestrateDetachVolumeFromVM — agentMgr throws → CloudRuntimeException
    // -----------------------------------------------------------------------

    @Test(expected = com.cloud.utils.exception.CloudRuntimeException.class)
    public void testOrchestrateDetach_agentUnavailable_throws() throws Exception {
        VolumeVO vol = makeVolume(VOL_ID, VM_ID, Volume.Type.DATADISK, POOL_ID);
        when(volsDao.findById(VOL_ID)).thenReturn(vol);

        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        when(vm.getState()).thenReturn(State.Running);
        when(vm.getHostName()).thenReturn("host");
        when(vm.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);

        StoragePoolVO pool = makePool(POOL_ID, false);
        when(storagePoolDao.findByIdIncludingRemoved(POOL_ID)).thenReturn(pool);
        HostVO host = Mockito.mock(HostVO.class);
        when(host.getId()).thenReturn(HOST_ID);
        when(volumeHostTopologyService.getHostForVmVolumeAttachDetach(any(), any())).thenReturn(host);
        Mockito.lenient().when(volumeHostTopologyService.isSendCommandForVmVolumeAttachDetach(any(), any())).thenReturn(false);

        UserVmVO userVm = makeVm(VM_ID, State.Running, HypervisorType.KVM);
        when(userVmDao.findById(VM_ID)).thenReturn(userVm);

        VolumeInfo volInfo = Mockito.mock(VolumeInfo.class);
        org.apache.cloudstack.storage.to.VolumeObjectTO volTO = Mockito.mock(org.apache.cloudstack.storage.to.VolumeObjectTO.class);
        when(volFactory.getVolume(VOL_ID)).thenReturn(volInfo);
        when(volInfo.getTO()).thenReturn(volTO);
        Mockito.lenient().when(volumeMgr.getVolumeCheckpointPathsAndImageStoreUrls(anyLong(), any()))
                .thenReturn(new Pair<>(null, null));
        StoragePoolVO poolForParent = makePool(POOL_ID, false);
        when(storagePoolDao.findById(POOL_ID)).thenReturn(poolForParent);

        when(agentMgr.send(anyLong(), any(com.cloud.agent.api.Command.class))).thenThrow(new AgentUnavailableException("host unavailable", HOST_ID));

        service.orchestrateDetachVolumeFromVM(VM_ID, VOL_ID);
    }

    // -----------------------------------------------------------------------
    // 11. detachVolumeFromVmThroughJobQueue — builds correct VmWorkDetachVolume job
    // -----------------------------------------------------------------------

    @Test
    public void testDetachThroughJobQueue_buildsCorrectWorkJob() {
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        when(vm.getId()).thenReturn(VM_ID);
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vm);

        when(jobMgr.submitAsyncJob(any(VmWorkJobVO.class), anyString(), anyLong())).thenReturn(1L);

        Outcome<Volume> outcome = service.detachVolumeFromVmThroughJobQueue(VM_ID, VOL_ID);

        assertNotNull(outcome);

        ArgumentCaptor<VmWorkJobVO> jobCaptor = ArgumentCaptor.forClass(VmWorkJobVO.class);
        verify(jobMgr).submitAsyncJob(jobCaptor.capture(), eq("VmWorkJobQueue"), eq(VM_ID));

        VmWorkJobVO captured = jobCaptor.getValue();
        assertEquals(VmWorkDetachVolume.class.getName(), captured.getCmd());
    }

    // -----------------------------------------------------------------------
    // 12. detachVolumeFromVM — bad param combination → InvalidParameterValueException
    // -----------------------------------------------------------------------

    @Test(expected = InvalidParameterValueException.class)
    public void testDetachVolumeFromVM_badParams_throws() throws Exception {
        // No id, no (deviceId + vmId) → invalid
        DetachVolumeCmd cmd = new DetachVolumeCmd();
        // id=null, deviceId=null, vmId=null → the first guard fires
        service.detachVolumeFromVM(cmd);
    }
}
