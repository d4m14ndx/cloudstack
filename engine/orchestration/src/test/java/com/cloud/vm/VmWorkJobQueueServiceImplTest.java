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
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.api.command.admin.vm.MigrateVMCmd;
import org.apache.cloudstack.api.command.admin.volume.MigrateVolumeCmdByAdmin;
import org.apache.cloudstack.api.command.user.volume.MigrateVolumeCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.jobs.AsyncJob;
import org.apache.cloudstack.framework.jobs.AsyncJobExecutionContext;
import org.apache.cloudstack.framework.jobs.AsyncJobManager;
import org.apache.cloudstack.framework.jobs.Outcome;
import org.apache.cloudstack.framework.jobs.dao.VmWorkJobDao;
import org.apache.cloudstack.framework.jobs.impl.VmWorkJobVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.cloudstack.utils.identity.ManagementServerNode;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.DataCenter;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InsufficientServerCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.Network;
import com.cloud.org.Cluster;
import com.cloud.storage.VolumeApiService;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmWorkJobQueueServiceImplTest {

    private static final long VM_ID = 42L;
    private static final String VM_UUID = "vm-uuid";
    private static final long ACCOUNT_ID = 11L;
    private static final long USER_ID = 12L;
    private static final String CONTEXT_ID = "context-id";
    private static final String ORIGIN_JOB_ID = "origin-job-id";

    @InjectMocks
    private VmWorkJobQueueServiceImpl service;

    @Mock
    private EntityManager entityMgr;
    @Mock
    private VMInstanceDao vmDao;
    @Mock
    private VmWorkJobDao workJobDao;
    @Mock
    private AsyncJobManager jobMgr;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private AsyncJobExecutionContext jobExecutionContext;
    @Mock
    private CallContext callContext;
    @Mock
    private Account account;
    @Mock
    private User user;

    @Before
    public void setUp() {
        when(callContext.getCallingAccount()).thenReturn(account);
        when(callContext.getCallingUser()).thenReturn(user);
        when(callContext.getContextId()).thenReturn(CONTEXT_ID);
        when(account.getId()).thenReturn(ACCOUNT_ID);
        when(user.getId()).thenReturn(USER_ID);
    }

    @Test
    public void createPlaceHolderWorkSetsPlaceholderFieldsAndPersists() {
        try (MockedStatic<ManagementServerNode> managementServerNode = mockStatic(ManagementServerNode.class)) {
            managementServerNode.when(ManagementServerNode::getManagementServerId).thenReturn(123L);

            VmWorkJobVO job = service.createPlaceHolderWork(VM_ID);

            assertEquals(VmWorkConstants.VM_WORK_JOB_PLACEHOLDER, job.getDispatcher());
            assertEquals("", job.getCmd());
            assertEquals("", job.getCmdInfo());
            assertEquals(0L, job.getAccountId());
            assertEquals(0L, job.getUserId());
            assertEquals(VmWorkJobVO.Step.Starting, job.getStep());
            assertEquals(VirtualMachine.Type.Instance, job.getVmType());
            assertEquals(VM_ID, job.getVmInstanceId());
            assertEquals(Long.valueOf(123L), job.getInitMsid());
            assertNull(job.getSecondaryObjectIdentifier());
            verify(workJobDao).persist(job);
        }
    }

    @Test
    public void createPlaceHolderWorkSetsSecondaryObjectIdentifierWhenPresent() {
        try (MockedStatic<ManagementServerNode> managementServerNode = mockStatic(ManagementServerNode.class)) {
            managementServerNode.when(ManagementServerNode::getManagementServerId).thenReturn(123L);

            VmWorkJobVO job = service.createPlaceHolderWork(VM_ID, "network-uuid");

            assertEquals("network-uuid", job.getSecondaryObjectIdentifier());
            verify(workJobDao).persist(job);
        }
    }

    @Test
    public void expungePlaceHolderWorkNoOpsForNullAndExpungesNonNull() {
        service.expungePlaceHolderWork(null);
        verify(workJobDao, never()).expunge(anyLong());

        VmWorkJobVO job = new VmWorkJobVO("");
        job.setId(55L);
        service.expungePlaceHolderWork(job);

        verify(workJobDao).expunge(55L);
    }

    @Test
    public void retrievePendingWorkJobResolvesVmAndReturnsFirstPendingJob() {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        VmWorkJobVO firstJob = new VmWorkJobVO("");
        VmWorkJobVO secondJob = new VmWorkJobVO("");
        when(vmDao.findByUuid(VM_UUID)).thenReturn(vm);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getType()).thenReturn(VirtualMachine.Type.DomainRouter);
        when(workJobDao.listPendingWorkJobs(VirtualMachine.Type.DomainRouter, VM_ID, VmWorkStart.class.getName()))
                .thenReturn(Arrays.asList(firstJob, secondJob));

        Pair<VmWorkJobVO, Long> result = service.retrievePendingWorkJob(null, VM_UUID, null, VmWorkStart.class.getName());

        assertSame(firstJob, result.first());
        assertEquals(Long.valueOf(VM_ID), result.second());
    }

    @Test
    public void retrievePendingWorkJobThrowsExistingMessageWhenVmUuidLookupFails() {
        when(vmDao.findByUuid(VM_UUID)).thenReturn(null);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.retrievePendingWorkJob(VM_UUID, VmWorkStart.class.getName()));

        assertEquals("Could not find a VM with the uuid [vm-uuid]. Unable to continue validations with command [com.cloud.vm.VmWorkStart] through job queue.",
                exception.getMessage());
    }

    @Test
    public void createWorkJobAndWorkInfoCopiesContextAndUsesVirtualMachineManagerHandler() {
        try (MockedStatic<CallContext> callContextStatic = mockStatic(CallContext.class);
                MockedStatic<AsyncJobExecutionContext> asyncJobExecutionContext = mockStatic(AsyncJobExecutionContext.class)) {
            callContextStatic.when(CallContext::current).thenReturn(callContext);
            asyncJobExecutionContext.when(AsyncJobExecutionContext::getOriginJobId).thenReturn(ORIGIN_JOB_ID);

            Pair<VmWorkJobVO, VmWork> result = service.createWorkJobAndWorkInfo(
                    VmWorkStop.class.getName(), VmWorkJobVO.Step.Prepare, VM_ID);

            VmWorkJobVO job = result.first();
            assertEquals(VmWorkConstants.VM_WORK_JOB_DISPATCHER, job.getDispatcher());
            assertEquals(VmWorkStop.class.getName(), job.getCmd());
            assertEquals(ACCOUNT_ID, job.getAccountId());
            assertEquals(USER_ID, job.getUserId());
            assertEquals(VmWorkJobVO.Step.Prepare, job.getStep());
            assertEquals(VirtualMachine.Type.Instance, job.getVmType());
            assertEquals(VM_ID, job.getVmInstanceId());
            assertEquals(ORIGIN_JOB_ID, job.getRelated());

            VmWork work = result.second();
            assertEquals(USER_ID, work.getUserId());
            assertEquals(ACCOUNT_ID, work.getAccountId());
            assertEquals(VM_ID, work.getVmId());
            assertEquals(VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER, work.getHandlerName());
        }
    }

    @Test
    public void setCmdInfoAndSubmitAsyncJobSerializesWorkAndSubmitsToVmWorkQueue() {
        VmWorkJobVO job = new VmWorkJobVO("");
        VmWork work = new VmWork(USER_ID, ACCOUNT_ID, VM_ID, VirtualMachineManagerImpl.VM_WORK_JOB_HANDLER);

        service.setCmdInfoAndSubmitAsyncJob(job, work, VM_ID);

        assertEquals(work.getVmId(), VmWorkSerializer.deserialize(VmWork.class, job.getCmdInfo()).getVmId());
        verify(jobMgr).submitAsyncJob(job, VmWorkConstants.VM_WORK_QUEUE, VM_ID);
    }

    @Test
    public void retrieveResultFromJobOutcomeReturnsNullAndPlainResult() throws Exception {
        Outcome<VirtualMachine> outcome = mockOutcome();
        when(jobMgr.unmarshallResultObject(outcome.getJob())).thenReturn(null, "ok");

        assertNull(service.retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome));
        assertEquals("ok", service.retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome));
    }

    @Test
    public void retrieveResultFromJobOutcomeRethrowsExpectedExceptionTypes() {
        assertRethrown(new AgentUnavailableException("agent", 1L), AgentUnavailableException.class);
        assertRethrown(new InsufficientServerCapacityException("capacity", Cluster.class, 2L), InsufficientServerCapacityException.class);
        assertRethrown(new ResourceUnavailableException("resource", DataCenter.class, 3L), ResourceUnavailableException.class);
        assertRethrown(new InsufficientCapacityException("capacity", DataCenter.class, 4L) {}, InsufficientCapacityException.class);
        assertRethrown(new ConcurrentOperationException("concurrent"), ConcurrentOperationException.class);
        assertRethrown(new IllegalStateException("runtime"), IllegalStateException.class);
    }

    @Test
    public void retrieveResultFromJobOutcomeWrapsGenericThrowable() {
        Outcome<VirtualMachine> outcome = mockOutcome();
        Exception cause = new Exception("checked");
        when(jobMgr.unmarshallResultObject(outcome.getJob())).thenReturn(cause);

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> service.retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome));

        assertEquals("Unexpected exception", exception.getMessage());
        assertSame(cause, exception.getCause());
    }

    @Test
    public void addVmToNetworkThroughJobQueueReusesMatchingPendingJob() {
        VirtualMachine vm = mock(VirtualMachine.class);
        Network network = mock(Network.class);
        VmWorkJobVO pendingJob = new VmWorkJobVO("");
        pendingJob.setId(77L);
        when(vm.getId()).thenReturn(VM_ID);
        when(network.getUuid()).thenReturn("network-uuid");
        when(workJobDao.listPendingWorkJobs(VirtualMachine.Type.Instance, VM_ID, VmWorkAddVmToNetwork.class.getName()))
                .thenReturn(Collections.emptyList());
        when(workJobDao.listPendingWorkJobs(VirtualMachine.Type.Instance, VM_ID, VmWorkAddVmToNetwork.class.getName(), "network-uuid"))
                .thenReturn(Collections.singletonList(pendingJob));

        try (MockedStatic<CallContext> callContextStatic = mockStatic(CallContext.class);
                MockedStatic<AsyncJobExecutionContext> asyncJobExecutionContext = mockStatic(AsyncJobExecutionContext.class)) {
            callContextStatic.when(CallContext::current).thenReturn(callContext);
            asyncJobExecutionContext.when(AsyncJobExecutionContext::getCurrentExecutionContext).thenReturn(jobExecutionContext);

            Outcome<VirtualMachine> outcome = service.addVmToNetworkThroughJobQueue(vm, network, mock(NicProfile.class));

            assertSame(pendingJob, ReflectionTestUtils.getField(outcome, "_job"));
            verify(jobExecutionContext).joinJob(77L);
            verify(jobMgr, never()).submitAsyncJob(any(), eq(VmWorkConstants.VM_WORK_QUEUE), eq(VM_ID));
        }
    }

    @Test
    public void addVmToNetworkThroughJobQueueThrowsExistingDuplicateJobMessage() {
        VirtualMachine vm = mock(VirtualMachine.class);
        Network network = mock(Network.class);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getInstanceName()).thenReturn("vm-name");
        when(network.getUuid()).thenReturn("network-uuid");
        when(workJobDao.listPendingWorkJobs(VirtualMachine.Type.Instance, VM_ID, VmWorkAddVmToNetwork.class.getName()))
                .thenReturn(Collections.emptyList());
        when(workJobDao.listPendingWorkJobs(VirtualMachine.Type.Instance, VM_ID, VmWorkAddVmToNetwork.class.getName(), "network-uuid"))
                .thenReturn(Arrays.asList(new VmWorkJobVO(""), new VmWorkJobVO("")));

        try (MockedStatic<CallContext> callContextStatic = mockStatic(CallContext.class)) {
            callContextStatic.when(CallContext::current).thenReturn(callContext);

            CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                    () -> service.addVmToNetworkThroughJobQueue(vm, network, mock(NicProfile.class)));

            assertEquals("The number of jobs to add network network-uuid to vm vm-name are 2", exception.getMessage());
        }
    }

    @Test
    public void migrateVmStorageThroughJobQueueChecksEachUniquePoolOnceBeforeSubmitting() throws Exception {
        VMInstanceVO vm = mock(VMInstanceVO.class);
        StoragePoolVO poolOne = mock(StoragePoolVO.class);
        StoragePoolVO poolTwo = mock(StoragePoolVO.class);
        when(vmDao.findByUuid(VM_UUID)).thenReturn(vm);
        when(vm.getId()).thenReturn(VM_ID);
        when(storagePoolDao.findById(100L)).thenReturn(poolOne);
        when(storagePoolDao.findById(200L)).thenReturn(poolTwo);
        when(poolOne.getUuid()).thenReturn("pool-one");
        when(poolTwo.getUuid()).thenReturn("pool-two");
        when(workJobDao.listPendingWorkJobs(VirtualMachine.Type.Instance, VM_ID, VmWorkStorageMigration.class.getName()))
                .thenReturn(Collections.emptyList());

        Map<Long, Long> volumeToPool = new HashMap<>();
        volumeToPool.put(1L, 100L);
        volumeToPool.put(2L, 100L);
        volumeToPool.put(3L, 200L);

        Object originalThresholdValue = setConfigKeyValue(VolumeApiService.ConcurrentMigrationsThresholdPerDatastore, null);
        Object originalThresholdDefault = setConfigKeyField(VolumeApiService.ConcurrentMigrationsThresholdPerDatastore, "_defaultValue", "1");
        try (MockedStatic<CallContext> callContextStatic = mockStatic(CallContext.class);
                MockedStatic<AsyncJobExecutionContext> asyncJobExecutionContext = mockStatic(AsyncJobExecutionContext.class)) {
            callContextStatic.when(CallContext::current).thenReturn(callContext);
            asyncJobExecutionContext.when(AsyncJobExecutionContext::getOriginJobId).thenReturn(ORIGIN_JOB_ID);
            asyncJobExecutionContext.when(AsyncJobExecutionContext::getCurrentExecutionContext).thenReturn(jobExecutionContext);

            service.migrateVmStorageThroughJobQueue(VM_UUID, volumeToPool);

            verify(storagePoolDao).findById(100L);
            verify(storagePoolDao).findById(200L);
            verify(jobMgr).countPendingJobs("\"storageid\":\"pool-one\"", MigrateVMCmd.class.getName(), MigrateVolumeCmd.class.getName(), MigrateVolumeCmdByAdmin.class.getName());
            verify(jobMgr).countPendingJobs("\"storageid\":\"pool-two\"", MigrateVMCmd.class.getName(), MigrateVolumeCmd.class.getName(), MigrateVolumeCmdByAdmin.class.getName());
            ArgumentCaptor<VmWorkJobVO> jobCaptor = ArgumentCaptor.forClass(VmWorkJobVO.class);
            verify(jobMgr).submitAsyncJob(jobCaptor.capture(), eq(VmWorkConstants.VM_WORK_QUEUE), eq(VM_ID));
            VmWorkStorageMigration work = VmWorkSerializer.deserialize(VmWorkStorageMigration.class, jobCaptor.getValue().getCmdInfo());
            assertEquals(volumeToPool, work.getVolumeToPool());
        } finally {
            setConfigKeyValue(VolumeApiService.ConcurrentMigrationsThresholdPerDatastore, originalThresholdValue);
            setConfigKeyField(VolumeApiService.ConcurrentMigrationsThresholdPerDatastore, "_defaultValue", originalThresholdDefault);
        }
    }

    private Outcome<VirtualMachine> mockOutcome() {
        Outcome<VirtualMachine> outcome = mock(Outcome.class);
        AsyncJob job = mock(AsyncJob.class);
        when(outcome.getJob()).thenReturn(job);
        return outcome;
    }

    private <T extends Throwable> void assertRethrown(T throwable, Class<T> expectedType) {
        Outcome<VirtualMachine> outcome = mockOutcome();
        when(jobMgr.unmarshallResultObject(outcome.getJob())).thenReturn(throwable);

        assertSame(throwable, assertThrows(expectedType,
                () -> service.retrieveResultFromJobOutcomeAndThrowExceptionIfNeeded(outcome)));
    }

    private Object setConfigKeyValue(final ConfigKey configKey, final Object value) throws Exception {
        return setConfigKeyField(configKey, "_value", value);
    }

    private Object setConfigKeyField(final ConfigKey configKey, final String fieldName, final Object value) throws Exception {
        Field valueField = ConfigKey.class.getDeclaredField(fieldName);
        valueField.setAccessible(true);
        Object originalValue = valueField.get(configKey);
        valueField.set(configKey, value);
        return originalValue;
    }
}
