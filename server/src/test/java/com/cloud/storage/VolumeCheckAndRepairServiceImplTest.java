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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.UUID;
import java.util.concurrent.ExecutionException;

import org.apache.cloudstack.api.command.user.volume.CheckAndRepairVolumeCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.framework.jobs.AsyncJob;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.Outcome;
import org.apache.cloudstack.framework.jobs.dao.AsyncJobJoinMapDao;
import org.apache.cloudstack.framework.jobs.dao.VmWorkJobDao;
import org.apache.cloudstack.framework.jobs.impl.AsyncJobVO;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.UserVO;
import com.cloud.utils.Pair;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VmWorkCheckAndRepairVolume;
import com.cloud.vm.VmWorkConstants;
import com.cloud.vm.VmWorkSerializer;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.Silent.class)
public class VolumeCheckAndRepairServiceImplTest {

    private static final long VOLUME_ID = 11L;
    private static final long VM_ID = 22L;
    private static final long ACCOUNT_ID = 33L;
    private static final long USER_ID = 44L;
    private static final String REPAIR = "repair";

    @Mock
    private VolumeDao volsDao;
    @Mock
    private AccountManager accountMgr;
    @Mock
    private UserVmDao userVmDao;
    @Mock
    private VolumeDataFactory volFactory;
    @Mock
    private VolumeService volService;
    @Mock
    private AsyncJobManager jobMgr;
    @Mock
    private AsyncJobJoinMapDao joinMapDao;
    @Mock
    private VmWorkJobDao workJobDao;
    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private EntityManager entityMgr;

    @Spy
    @InjectMocks
    private VolumeCheckAndRepairServiceImpl service = new VolumeCheckAndRepairServiceImpl();

    @Mock
    private VolumeVO volume;
    @Mock
    private UserVmVO vm;
    @Mock
    private VMInstanceVO vmInstance;
    @Mock
    private VolumeInfo volumeInfo;
    @Mock
    private Outcome<Pair> outcome;
    @Mock
    private AsyncJob asyncJob;

    @Before
    public void setUp() {
        AccountVO account = new AccountVO("admin", ACCOUNT_ID, "domain", Account.Type.NORMAL, UUID.randomUUID().toString());
        UserVO user = new UserVO(USER_ID, "tester", "password", "first", "last", "email", "tz",
                UUID.randomUUID().toString(), com.cloud.user.User.Source.UNKNOWN);
        ReflectionTestUtils.setField(account, "id", ACCOUNT_ID);
        ReflectionTestUtils.setField(user, "id", USER_ID);
        CallContext.register(user, account);

        Mockito.lenient().doNothing().when(accountMgr).checkAccess(any(Account.class), any(), anyBoolean(), any());
        Mockito.lenient().when(volume.getId()).thenReturn(VOLUME_ID);
        Mockito.lenient().when(volume.getName()).thenReturn("volume-" + VOLUME_ID);
        Mockito.lenient().when(vm.getState()).thenReturn(State.Stopped);
        Mockito.lenient().when(vmInstance.getId()).thenReturn(VM_ID);

        AsyncJobExecutionContext context = new AsyncJobExecutionContext();
        AsyncJobExecutionContext.init(jobMgr, joinMapDao);
        AsyncJobVO job = new AsyncJobVO();
        ReflectionTestUtils.setField(job, "id", 500L);
        context.setJob(job);
        AsyncJobExecutionContext.setCurrentExecutionContext(context);
    }

    @After
    public void tearDown() {
        AsyncJobExecutionContext.unregister();
        CallContext.unregisterAll();
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validationsRejectNonReadyVolume() {
        Mockito.lenient().when(volume.getState()).thenReturn(Volume.State.Allocated);

        service.validationsForCheckVolumeOperation(volume);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validationsRejectNonKvmHypervisor() {
        Mockito.lenient().when(volume.getState()).thenReturn(Volume.State.Ready);
        Mockito.lenient().when(volsDao.getHypervisorType(VOLUME_ID)).thenReturn(HypervisorType.XenServer);
        Mockito.lenient().when(volume.getFormat()).thenReturn(ImageFormat.QCOW2);

        service.validationsForCheckVolumeOperation(volume);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validationsRejectUnsupportedFormat() {
        Mockito.lenient().when(volume.getState()).thenReturn(Volume.State.Ready);
        Mockito.lenient().when(volsDao.getHypervisorType(VOLUME_ID)).thenReturn(HypervisorType.KVM);
        Mockito.lenient().when(volume.getFormat()).thenReturn(ImageFormat.RAW);

        service.validationsForCheckVolumeOperation(volume);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateVmRejectsMissingVm() {
        when(userVmDao.findById(VM_ID)).thenReturn(null);

        service.validateVMforCheckVolumeOperation(VM_ID, "volume");
    }

    @Test(expected = InvalidParameterValueException.class)
    public void validateVmRejectsVmThatIsNotStopped() {
        when(userVmDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getState()).thenReturn(State.Running);

        service.validateVMforCheckVolumeOperation(VM_ID, "volume");
    }

    @Test
    public void validationsSucceedForStoppedVmOnReadyKvmQcow2Volume() {
        when(volume.getInstanceId()).thenReturn(VM_ID);
        when(userVmDao.findById(VM_ID)).thenReturn(vm);
        when(volume.getState()).thenReturn(Volume.State.Ready);
        when(volsDao.getHypervisorType(VOLUME_ID)).thenReturn(HypervisorType.KVM);
        when(volume.getFormat()).thenReturn(ImageFormat.QCOW2);

        service.validationsForCheckVolumeOperation(volume);

        verify(accountMgr).checkAccess(any(Account.class), any(), eq(true), eq(volume));
        verify(accountMgr).checkAccess(any(Account.class), any(), eq(true), eq(vm));
    }

    @Test
    public void handleCheckAndRepairVolumeAddsPayloadAndReturnsServiceResult() {
        Pair<String, String> expected = new Pair<>("check", "repair");
        when(volFactory.getVolume(VOLUME_ID)).thenReturn(volumeInfo);
        when(volService.checkAndRepairVolume(volumeInfo)).thenReturn(expected);

        Pair<String, String> result = service.handleCheckAndRepairVolume(VOLUME_ID, REPAIR);

        ArgumentCaptor<CheckAndRepairVolumePayload> payloadCaptor = ArgumentCaptor.forClass(CheckAndRepairVolumePayload.class);
        verify(volumeInfo).addPayload(payloadCaptor.capture());
        assertEquals(REPAIR, payloadCaptor.getValue().getRepair());
        assertSame(expected, result);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void orchestrateRejectsMissingVolumeInfo() {
        when(volFactory.getVolume(VOLUME_ID)).thenReturn(null);

        service.orchestrateCheckAndRepairVolume(VOLUME_ID, REPAIR);
    }

    @Test
    public void orchestrateAddsPayloadAndDelegatesWhenVolumeExists() {
        Pair<String, String> expected = new Pair<>("check", "repair");
        when(volFactory.getVolume(VOLUME_ID)).thenReturn(volumeInfo);
        when(volService.checkAndRepairVolume(volumeInfo)).thenReturn(expected);

        Pair<String, String> result = service.orchestrateCheckAndRepairVolume(VOLUME_ID, REPAIR);

        ArgumentCaptor<CheckAndRepairVolumePayload> payloadCaptor = ArgumentCaptor.forClass(CheckAndRepairVolumePayload.class);
        verify(volumeInfo).addPayload(payloadCaptor.capture());
        assertEquals(REPAIR, payloadCaptor.getValue().getRepair());
        assertSame(expected, result);
    }

    @Test
    public void checkAndRepairVolumeUsesDirectPathForDetachedVolume() throws ResourceAllocationException {
        CheckAndRepairVolumeCmd cmd = Mockito.mock(CheckAndRepairVolumeCmd.class);
        Pair<String, String> expected = new Pair<>("check", null);

        when(cmd.getId()).thenReturn(VOLUME_ID);
        when(cmd.getRepair()).thenReturn(REPAIR);
        when(volsDao.findById(VOLUME_ID)).thenReturn(volume);
        when(volume.getInstanceId()).thenReturn(null);
        doNothing().when(service).validationsForCheckVolumeOperation(volume);
        doReturn(expected).when(service).handleCheckAndRepairVolume(VOLUME_ID, REPAIR);

        Pair<String, String> result = service.checkAndRepairVolume(cmd);

        assertSame(expected, result);
        verify(service).handleCheckAndRepairVolume(VOLUME_ID, REPAIR);
        verify(service, never()).handleCheckAndRepairVolumeJob(anyLong(), anyLong(), anyString());
    }

    @Test
    public void checkAndRepairVolumeUsesQueuedPathForAttachedVolume() throws ResourceAllocationException {
        CheckAndRepairVolumeCmd cmd = Mockito.mock(CheckAndRepairVolumeCmd.class);
        Pair<String, String> expected = new Pair<>("check", "repair");

        when(cmd.getId()).thenReturn(VOLUME_ID);
        when(cmd.getRepair()).thenReturn(REPAIR);
        when(volsDao.findById(VOLUME_ID)).thenReturn(volume);
        when(volume.getInstanceId()).thenReturn(VM_ID);
        doNothing().when(service).validationsForCheckVolumeOperation(volume);
        doReturn(expected).when(service).handleCheckAndRepairVolumeJob(VM_ID, VOLUME_ID, REPAIR);

        Pair<String, String> result = service.checkAndRepairVolume(cmd);

        assertSame(expected, result);
        verify(service).handleCheckAndRepairVolumeJob(VM_ID, VOLUME_ID, REPAIR);
        verify(service, never()).handleCheckAndRepairVolume(anyLong(), anyString());
    }

    @Test
    public void checkAndRepairVolumeThroughJobQueueBuildsVmWorkJob() {
        when(vmInstanceDao.findById(VM_ID)).thenReturn(vmInstance);
        when(jobMgr.submitAsyncJob(any(VmWorkJobVO.class), eq(VmWorkConstants.VM_WORK_QUEUE), eq(VM_ID))).thenReturn(901L);

        Outcome<Pair> createdOutcome = service.checkAndRepairVolumeThroughJobQueue(VM_ID, VOLUME_ID, REPAIR);

        ArgumentCaptor<VmWorkJobVO> captor = ArgumentCaptor.forClass(VmWorkJobVO.class);
        verify(jobMgr).submitAsyncJob(captor.capture(), eq(VmWorkConstants.VM_WORK_QUEUE), eq(VM_ID));
        verify(jobMgr).joinJob(500L, 0L);

        VmWorkJobVO workJob = captor.getValue();
        assertEquals(VmWorkConstants.VM_WORK_JOB_DISPATCHER, workJob.getDispatcher());
        assertEquals(VmWorkCheckAndRepairVolume.class.getName(), workJob.getCmd());
        assertEquals(VM_ID, workJob.getVmInstanceId());
        assertEquals(ACCOUNT_ID, workJob.getAccountId());
        assertEquals(USER_ID, workJob.getUserId());
        assertEquals("500", workJob.getRelated());
        assertNotNull(workJob.getCmdInfo());

        VmWorkCheckAndRepairVolume work = VmWorkSerializer.deserialize(VmWorkCheckAndRepairVolume.class, workJob.getCmdInfo());
        assertEquals(Long.valueOf(VOLUME_ID), work.getVolumeId());
        assertEquals(REPAIR, work.getRepair());
        assertNotNull(createdOutcome);
    }

    @Test
    public void handleCheckAndRepairVolumeJobReentrantPathUsesPlaceholderWork() throws ResourceAllocationException {
        AsyncJobVO dispatchedJob = new AsyncJobVO();
        dispatchedJob.setDispatcher(VmWorkConstants.VM_WORK_JOB_DISPATCHER);
        AsyncJobExecutionContext.getCurrentExecutionContext().setJob(dispatchedJob);
        Pair<String, String> expected = new Pair<>("check", "repair");

        doAnswer(invocation -> {
            VmWorkJobVO workJob = invocation.getArgument(0);
            ReflectionTestUtils.setField(workJob, "id", 707L);
            return workJob;
        }).when(workJobDao).persist(any(VmWorkJobVO.class));
        doReturn(expected).when(service).orchestrateCheckAndRepairVolume(VOLUME_ID, REPAIR);

        Pair<String, String> result = service.handleCheckAndRepairVolumeJob(VM_ID, VOLUME_ID, REPAIR);

        assertSame(expected, result);
        verify(workJobDao).persist(any(VmWorkJobVO.class));
        verify(workJobDao).expunge(707L);
        verify(jobMgr, never()).submitAsyncJob(any(VmWorkJobVO.class), anyString(), anyLong());
    }

    @Test
    public void handleCheckAndRepairVolumeJobUnwrapsConcurrentOperationException() throws Exception {
        ConcurrentOperationException expected = new ConcurrentOperationException("busy");

        doReturn(outcome).when(service).checkAndRepairVolumeThroughJobQueue(VM_ID, VOLUME_ID, REPAIR);
        when(outcome.getJob()).thenReturn(asyncJob);
        when(jobMgr.unmarshallResultObject(asyncJob)).thenReturn(expected);

        try {
            service.handleCheckAndRepairVolumeJob(VM_ID, VOLUME_ID, REPAIR);
        } catch (ConcurrentOperationException e) {
            assertSame(expected, e);
        }
    }

    @Test
    public void handleCheckAndRepairVolumeJobUnwrapsResourceAllocationException() throws Exception {
        ResourceAllocationException expected = new ResourceAllocationException("nope", ResourceType.volume);

        doReturn(outcome).when(service).checkAndRepairVolumeThroughJobQueue(VM_ID, VOLUME_ID, REPAIR);
        when(outcome.getJob()).thenReturn(asyncJob);
        when(jobMgr.unmarshallResultObject(asyncJob)).thenReturn(expected);

        try {
            service.handleCheckAndRepairVolumeJob(VM_ID, VOLUME_ID, REPAIR);
        } catch (ResourceAllocationException e) {
            assertSame(expected, e);
        }
    }

    @Test
    public void handleCheckAndRepairVolumeJobWrapsUnexpectedThrowable() throws Exception {
        IllegalStateException expected = new IllegalStateException("boom");

        doReturn(outcome).when(service).checkAndRepairVolumeThroughJobQueue(VM_ID, VOLUME_ID, REPAIR);
        when(outcome.getJob()).thenReturn(asyncJob);
        when(jobMgr.unmarshallResultObject(asyncJob)).thenReturn(expected);

        try {
            service.handleCheckAndRepairVolumeJob(VM_ID, VOLUME_ID, REPAIR);
        } catch (RuntimeException e) {
            assertSame(expected, e.getCause());
        }
    }

    @Test
    public void handleCheckAndRepairVolumeJobReturnsPairPayload() throws Exception {
        Pair<String, String> expected = new Pair<>("check", null);

        doReturn(outcome).when(service).checkAndRepairVolumeThroughJobQueue(VM_ID, VOLUME_ID, REPAIR);
        when(outcome.getJob()).thenReturn(asyncJob);
        when(jobMgr.unmarshallResultObject(asyncJob)).thenReturn(expected);

        Pair<String, String> result = service.handleCheckAndRepairVolumeJob(VM_ID, VOLUME_ID, REPAIR);

        assertSame(expected, result);
    }

    @Test
    public void handleCheckAndRepairVolumeJobReturnsNullWhenNoPayloadExists() throws Exception {
        doReturn(outcome).when(service).checkAndRepairVolumeThroughJobQueue(VM_ID, VOLUME_ID, REPAIR);
        when(outcome.getJob()).thenReturn(asyncJob);
        when(jobMgr.unmarshallResultObject(asyncJob)).thenReturn(null);

        Pair<String, String> result = service.handleCheckAndRepairVolumeJob(VM_ID, VOLUME_ID, REPAIR);

        assertNull(result);
    }

    @Test(expected = RuntimeException.class)
    public void handleCheckAndRepairVolumeJobWrapsInterruptedWait() throws Exception {
        doReturn(outcome).when(service).checkAndRepairVolumeThroughJobQueue(VM_ID, VOLUME_ID, REPAIR);
        when(outcome.get()).thenThrow(new InterruptedException("interrupted"));

        service.handleCheckAndRepairVolumeJob(VM_ID, VOLUME_ID, REPAIR);
    }

    @Test(expected = RuntimeException.class)
    public void handleCheckAndRepairVolumeJobWrapsExecutionFailure() throws Exception {
        doReturn(outcome).when(service).checkAndRepairVolumeThroughJobQueue(VM_ID, VOLUME_ID, REPAIR);
        when(outcome.get()).thenThrow(new ExecutionException("failed", new IllegalStateException("boom")));

        service.handleCheckAndRepairVolumeJob(VM_ID, VOLUME_ID, REPAIR);
    }
}
