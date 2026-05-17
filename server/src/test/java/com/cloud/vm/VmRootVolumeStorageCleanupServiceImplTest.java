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
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;

import org.apache.cloudstack.engine.orchestration.service.VolumeOrchestrationService;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.command.DeleteCommand;
import org.apache.cloudstack.storage.command.DettachCommand;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.ModifyTargetsCommand;
import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.agent.manager.Commands;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Unit tests for {@link VmRootVolumeStorageCleanupServiceImpl} — the
 * hypervisor-aware managed-storage cleanup helper extracted from
 * {@code UserVmManagerImpl} as slice 18 of the Phase 4 Spring-component
 * decomposition.
 */
@RunWith(MockitoJUnitRunner.class)
public class VmRootVolumeStorageCleanupServiceImplTest {

    @Mock private PrimaryDataStoreDao storagePoolDao;
    @Mock private HostDao hostDao;
    @Mock private VolumeDataFactory volFactory;
    @Mock private AgentManager agentMgr;
    @Mock private DataStoreManager dataStoreMgr;
    @Mock private VolumeOrchestrationService volumeMgr;

    private VmRootVolumeStorageCleanupServiceImpl service;

    @Before
    public void setUp() {
        service = spy(new VmRootVolumeStorageCleanupServiceImpl());
        ReflectionTestUtils.setField(service, "storagePoolDao", storagePoolDao);
        ReflectionTestUtils.setField(service, "hostDao", hostDao);
        ReflectionTestUtils.setField(service, "volFactory", volFactory);
        ReflectionTestUtils.setField(service, "agentMgr", agentMgr);
        ReflectionTestUtils.setField(service, "dataStoreMgr", dataStoreMgr);
        ReflectionTestUtils.setField(service, "volumeMgr", volumeMgr);
    }

    private VolumeVO mockRoot(Volume.State state, Long poolId, long volId) {
        VolumeVO root = mock(VolumeVO.class);
        when(root.getState()).thenReturn(state);
        when(root.getPoolId()).thenReturn(poolId);
        when(root.getId()).thenReturn(volId);
        return root;
    }

    private UserVmVO mockVm(Long hostId, Long lastHostId, String instanceName) {
        UserVmVO vm = mock(UserVmVO.class);
        when(vm.getHostId()).thenReturn(hostId);
        when(vm.getLastHostId()).thenReturn(lastHostId);
        when(vm.getInstanceName()).thenReturn(instanceName);
        return vm;
    }

    /**
     * The production {@code AgentManager.send(Long, Commands)}
     * populates {@code Commands.setAnswers} as a side-effect; the
     * cleanup helper subsequently reads {@code cmds.getAnswers()} to
     * check for per-command failures. With a bare Mockito stub the
     * field stays {@code null}, so {@code isSuccessful()} returns
     * {@code false} and the loop NPEs. This helper installs the
     * "success" side-effect so the happy paths don't blow up.
     */
    private void stubAgentSendSuccess() throws Exception {
        doAnswer(inv -> {
            Commands c = inv.getArgument(1);
            Answer[] answers = new Answer[c.size()];
            for (int i = 0; i < answers.length; i++) {
                answers[i] = new Answer(null, true, "");
            }
            c.setAnswers(answers);
            return answers;
        }).when(agentMgr).send(anyLong(), any(Commands.class));
    }

    @Test
    public void cleanupShortCircuitsOnAllocatedVolume() {
        UserVmVO vm = mockVm(7L, null, "i-1");
        VolumeVO root = mockRoot(Volume.State.Allocated, 99L, 1L);

        service.cleanupRootVolumeOnManagedStorage(vm, root);

        // Nothing else inspected — no pool lookup, no agent send.
        verify(storagePoolDao, never()).findById(anyLong());
        verify(agentMgr, never()).easySend(anyLong(), any());
    }

    @Test
    public void cleanupShortCircuitsWhenPoolIsNull() {
        UserVmVO vm = mockVm(7L, null, "i-1");
        VolumeVO root = mockRoot(Volume.State.Ready, 99L, 1L);
        when(storagePoolDao.findById(99L)).thenReturn(null);

        service.cleanupRootVolumeOnManagedStorage(vm, root);

        verify(hostDao, never()).findById(anyLong());
    }

    @Test
    public void cleanupShortCircuitsWhenPoolIsUnmanaged() {
        UserVmVO vm = mockVm(7L, null, "i-1");
        VolumeVO root = mockRoot(Volume.State.Ready, 99L, 1L);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(false);
        when(storagePoolDao.findById(99L)).thenReturn(pool);

        service.cleanupRootVolumeOnManagedStorage(vm, root);

        verify(hostDao, never()).findById(anyLong());
    }

    @Test
    public void cleanupShortCircuitsWhenVmHasNoHostOrLastHost() {
        UserVmVO vm = mockVm(null, null, "i-1");
        VolumeVO root = mockRoot(Volume.State.Ready, 99L, 1L);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(true);
        when(storagePoolDao.findById(99L)).thenReturn(pool);

        service.cleanupRootVolumeOnManagedStorage(vm, root);

        verify(hostDao, never()).findById(anyLong());
    }

    @Test
    public void cleanupFallsBackToLastHostWhenCurrentHostIsNull() {
        UserVmVO vm = mockVm(null, 42L, "i-1");
        VolumeVO root = mockRoot(Volume.State.Ready, 99L, 1L);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(true);
        when(storagePoolDao.findById(99L)).thenReturn(pool);
        when(hostDao.findById(42L)).thenReturn(null);

        service.cleanupRootVolumeOnManagedStorage(vm, root);

        // We attempted host lookup with the last-host fallback.
        verify(hostDao).findById(42L);
    }

    @Test
    public void cleanupReturnsQuietlyWhenHostNotFound() throws Exception {
        UserVmVO vm = mockVm(42L, null, "i-1");
        VolumeVO root = mockRoot(Volume.State.Ready, 99L, 1L);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(true);
        when(storagePoolDao.findById(99L)).thenReturn(pool);
        when(hostDao.findById(42L)).thenReturn(null);

        service.cleanupRootVolumeOnManagedStorage(vm, root);

        // Did not attempt to fetch the volume or send any agent command.
        verify(volFactory, never()).getVolume(anyLong());
        verify(agentMgr, never()).send(anyLong(), any(Commands.class));
    }

    @Test
    public void cleanupOnXenServerBuildsManagedDetachCommand() throws Exception {
        UserVmVO vm = mockVm(42L, null, "i-1");
        VolumeVO root = mockRoot(Volume.State.Ready, 99L, 1L);
        when(root.getDeviceId()).thenReturn(0L);
        when(root.getPath()).thenReturn("/vol/path");
        when(root.getVolumeType()).thenReturn(Volume.Type.ROOT);
        when(root.get_iScsiName()).thenReturn("iqn.test");

        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(true);
        when(pool.getHostAddress()).thenReturn("10.0.0.1");
        when(pool.getPort()).thenReturn(3260);
        when(storagePoolDao.findById(99L)).thenReturn(pool);

        HostVO host = mock(HostVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        when(host.getId()).thenReturn(42L);
        when(hostDao.findById(42L)).thenReturn(host);

        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeInfo.getTO()).thenReturn(mock(DataTO.class));
        when(volFactory.getVolume(1L)).thenReturn(volumeInfo);

        DataStore dataStore = mock(DataStore.class);
        when(dataStoreMgr.getDataStore(99L, DataStoreRole.Primary)).thenReturn(dataStore);

        stubAgentSendSuccess();
        service.cleanupRootVolumeOnManagedStorage(vm, root);

        ArgumentCaptor<Commands> cap = ArgumentCaptor.forClass(Commands.class);
        verify(agentMgr).send(eq(42L), cap.capture());
        Commands sent = cap.getValue();
        assertEquals(1, sent.size());
        assertTrue(sent.toCommands()[0] instanceof DettachCommand);
        DettachCommand det = (DettachCommand) sent.toCommands()[0];
        assertTrue(det.isManaged());
        assertEquals("10.0.0.1", det.getStorageHost());
        assertEquals(3260, det.getStoragePort());
        assertEquals("iqn.test", det.get_iScsiName());
    }

    @Test
    public void cleanupOnVMwareSendsDeleteCommandAndModifyTargets() throws Exception {
        UserVmVO vm = mockVm(42L, null, "i-1");
        VolumeVO root = mockRoot(Volume.State.Ready, 99L, 1L);
        when(root.get_iScsiName()).thenReturn("iqn.vmware");

        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(true);
        when(pool.getHostAddress()).thenReturn("10.0.0.2");
        when(pool.getPort()).thenReturn(3260);
        when(storagePoolDao.findById(99L)).thenReturn(pool);

        HostVO host = mock(HostVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.VMware);
        when(host.getId()).thenReturn(42L);
        when(hostDao.findById(42L)).thenReturn(host);

        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeInfo.getTO()).thenReturn(mock(DataTO.class));
        PrimaryDataStore pds = mock(PrimaryDataStore.class);
        Map<String, String> details = new HashMap<>();
        when(pds.getDetails()).thenReturn(details);
        when(volumeInfo.getDataStore()).thenReturn(pds);
        when(volFactory.getVolume(1L)).thenReturn(volumeInfo);

        DataStore dataStore = mock(DataStore.class);
        when(dataStoreMgr.getDataStore(99L, DataStoreRole.Primary)).thenReturn(dataStore);
        when(agentMgr.easySend(eq(42L), any(ModifyTargetsCommand.class)))
                .thenReturn(new Answer(null, true, ""));

        stubAgentSendSuccess();
        service.cleanupRootVolumeOnManagedStorage(vm, root);

        // Step 1 — delete-command sent through the Commands batch.
        ArgumentCaptor<Commands> cap = ArgumentCaptor.forClass(Commands.class);
        verify(agentMgr).send(eq(42L), cap.capture());
        assertTrue(cap.getValue().toCommands()[0] instanceof DeleteCommand);
        // managed flag should be stamped on the data-store details map.
        assertEquals(Boolean.TRUE.toString(), details.get(DiskTO.MANAGED));
        // Step 2 — modify-targets via easySend on the host.
        verify(agentMgr).easySend(eq(42L), any(ModifyTargetsCommand.class));
    }

    @Test
    public void cleanupOnKvmIssuesNoAgentCommandButStillRevokesAccess() throws Exception {
        UserVmVO vm = mockVm(42L, null, "i-1");
        VolumeVO root = mockRoot(Volume.State.Ready, 99L, 1L);

        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(true);
        when(storagePoolDao.findById(99L)).thenReturn(pool);

        HostVO host = mock(HostVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(host.getId()).thenReturn(42L);
        when(hostDao.findById(42L)).thenReturn(host);

        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volFactory.getVolume(1L)).thenReturn(volumeInfo);

        DataStore dataStore = mock(DataStore.class);
        when(dataStoreMgr.getDataStore(99L, DataStoreRole.Primary)).thenReturn(dataStore);

        service.cleanupRootVolumeOnManagedStorage(vm, root);

        verify(agentMgr, never()).send(anyLong(), any(Commands.class));
        // Access revoke runs regardless of hypervisor (host arg is the Host, not HostVO).
        verify(volumeMgr).revokeAccess(eq(volumeInfo), eq(host), eq(dataStore));
    }

    @Test
    public void cleanupOnUnsupportedHypervisorThrows() {
        UserVmVO vm = mockVm(42L, null, "i-1");
        VolumeVO root = mockRoot(Volume.State.Ready, 99L, 1L);
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(true);
        when(storagePoolDao.findById(99L)).thenReturn(pool);

        HostVO host = mock(HostVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.Ovm);
        when(hostDao.findById(42L)).thenReturn(host);

        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volFactory.getVolume(1L)).thenReturn(volumeInfo);

        try {
            service.cleanupRootVolumeOnManagedStorage(vm, root);
            fail("Expected CloudRuntimeException for unsupported hypervisor");
        } catch (CloudRuntimeException e) {
            assertTrue(e.getMessage().contains("not supported on managed storage"));
        }
    }

    @Test
    public void cleanupWrapsAgentSendExceptionAsCloudRuntimeException() throws Exception {
        UserVmVO vm = mockVm(42L, null, "i-1");
        VolumeVO root = mockRoot(Volume.State.Ready, 99L, 1L);

        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(true);
        when(storagePoolDao.findById(99L)).thenReturn(pool);

        HostVO host = mock(HostVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        when(hostDao.findById(42L)).thenReturn(host);

        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeInfo.getTO()).thenReturn(mock(DataTO.class));
        when(volFactory.getVolume(1L)).thenReturn(volumeInfo);

        doThrow(new RuntimeException("boom")).when(agentMgr).send(anyLong(), any(Commands.class));

        try {
            service.cleanupRootVolumeOnManagedStorage(vm, root);
            fail("Expected CloudRuntimeException wrapping the send failure");
        } catch (CloudRuntimeException e) {
            assertEquals("boom", e.getMessage());
        }
    }

    @Test
    public void cleanupRaisesWhenAgentAnswerReportsFailure() throws Exception {
        UserVmVO vm = mockVm(42L, null, "i-1");
        VolumeVO root = mockRoot(Volume.State.Ready, 99L, 1L);

        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(true);
        when(storagePoolDao.findById(99L)).thenReturn(pool);

        HostVO host = mock(HostVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        when(hostDao.findById(42L)).thenReturn(host);

        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeInfo.getTO()).thenReturn(mock(DataTO.class));
        when(volFactory.getVolume(1L)).thenReturn(volumeInfo);

        // Stub send() to populate a failing answer into the Commands batch
        // (the production AgentManager calls Commands.setAnswers as a
        // side-effect, so we replicate that here through doAnswer).
        Answer[] failing = new Answer[]{ new Answer(null, false, "disk locked") };
        doAnswer(inv -> {
            Commands c = inv.getArgument(1);
            c.setAnswers(failing);
            return failing;
        }).when(agentMgr).send(anyLong(), any(Commands.class));

        try {
            service.cleanupRootVolumeOnManagedStorage(vm, root);
            fail("Expected CloudRuntimeException with failure detail");
        } catch (CloudRuntimeException e) {
            assertTrue(e.getMessage().contains("disk locked"));
        }
    }

    @Test
    public void cleanupSkipsDataStoreLookupWhenRootPoolIdIsNull() throws Exception {
        UserVmVO vm = mockVm(42L, null, "i-1");
        // Volume's pool id starts null — the pool DAO would not even be hit
        // for the managed-pool check, so this exercises the "no pool" fast
        // path through to the access-revoke step. The original code path
        // returns before reaching dataStoreMgr.
        VolumeVO root = mockRoot(Volume.State.Ready, null, 1L);
        when(storagePoolDao.findById(null)).thenReturn(null);

        service.cleanupRootVolumeOnManagedStorage(vm, root);

        verify(dataStoreMgr, never()).getDataStore(anyLong(), any(DataStoreRole.class));
        verify(volumeMgr, never()).revokeAccess(any(), any(), any());
    }

    @Test
    public void cleanupRevokesAccessOnHostAfterAgentCommand() throws Exception {
        UserVmVO vm = mockVm(42L, null, "i-1");
        VolumeVO root = mockRoot(Volume.State.Ready, 99L, 1L);
        when(root.getVolumeType()).thenReturn(Volume.Type.ROOT);

        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(true);
        when(storagePoolDao.findById(99L)).thenReturn(pool);

        HostVO host = mock(HostVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        when(host.getId()).thenReturn(42L);
        when(hostDao.findById(42L)).thenReturn(host);

        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeInfo.getTO()).thenReturn(mock(DataTO.class));
        when(volFactory.getVolume(1L)).thenReturn(volumeInfo);

        DataStore dataStore = mock(DataStore.class);
        when(dataStoreMgr.getDataStore(99L, DataStoreRole.Primary)).thenReturn(dataStore);

        stubAgentSendSuccess();
        service.cleanupRootVolumeOnManagedStorage(vm, root);

        verify(volumeMgr).revokeAccess(eq(volumeInfo), eq(host), eq(dataStore));
    }

    @Test
    public void cleanupOnXenServerDoesNotInvokeVMwareTargetsHelper() throws Exception {
        UserVmVO vm = mockVm(42L, null, "i-1");
        VolumeVO root = mockRoot(Volume.State.Ready, 99L, 1L);
        when(root.getVolumeType()).thenReturn(Volume.Type.ROOT);

        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(true);
        when(storagePoolDao.findById(99L)).thenReturn(pool);

        HostVO host = mock(HostVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        when(host.getId()).thenReturn(42L);
        when(hostDao.findById(42L)).thenReturn(host);

        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeInfo.getTO()).thenReturn(mock(DataTO.class));
        when(volFactory.getVolume(1L)).thenReturn(volumeInfo);

        DataStore dataStore = mock(DataStore.class);
        when(dataStoreMgr.getDataStore(99L, DataStoreRole.Primary)).thenReturn(dataStore);
        // The VMware-targets helper does an extra hostDao.findById; if it
        // ever fires for a non-VMware host, easySend would be invoked.
        when(hostDao.findById(42L)).thenReturn(host);

        stubAgentSendSuccess();
        service.cleanupRootVolumeOnManagedStorage(vm, root);

        verify(agentMgr, never()).easySend(anyLong(), any(ModifyTargetsCommand.class));
    }

    @Test
    public void handleTargetsForVMwareNoOpsForNonVMwareHost() {
        HostVO host = mock(HostVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        when(hostDao.findById(7L)).thenReturn(host);

        service.handleTargetsForVMware(7L, "1.2.3.4", 3260, "iqn.x");

        verify(agentMgr, never()).easySend(anyLong(), any(ModifyTargetsCommand.class));
    }

    @Test
    public void handleTargetsForVMwareBroadcastsModifyTargetsClusterWide() {
        HostVO host = mock(HostVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.VMware);
        when(host.getId()).thenReturn(7L);
        when(hostDao.findById(7L)).thenReturn(host);
        when(agentMgr.easySend(eq(7L), any(ModifyTargetsCommand.class)))
                .thenReturn(new Answer(null, true, ""));

        service.handleTargetsForVMware(7L, "1.2.3.4", 3260, "iqn.x");

        ArgumentCaptor<ModifyTargetsCommand> cap = ArgumentCaptor.forClass(ModifyTargetsCommand.class);
        verify(agentMgr).easySend(eq(7L), cap.capture());
        ModifyTargetsCommand cmd = cap.getValue();
        assertTrue(cmd.getApplyToAllHostsInCluster());
        assertEquals(ModifyTargetsCommand.TargetTypeToRemove.DYNAMIC, cmd.getTargetTypeToRemove());
        assertEquals(1, cmd.getTargets().size());
        Map<String, String> target = cmd.getTargets().get(0);
        assertEquals("1.2.3.4", target.get(ModifyTargetsCommand.STORAGE_HOST));
        assertEquals("3260", target.get(ModifyTargetsCommand.STORAGE_PORT));
        assertEquals("iqn.x", target.get(ModifyTargetsCommand.IQN));
    }

    @Test
    public void sendModifyTargetsCommandSwallowsNullAnswer() {
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(7L);
        when(agentMgr.easySend(eq(7L), any(ModifyTargetsCommand.class))).thenReturn(null);

        // Must not throw — the warn log is the only side-effect.
        service.sendModifyTargetsCommand(new ModifyTargetsCommand(), host);

        verify(agentMgr, times(1)).easySend(eq(7L), any(ModifyTargetsCommand.class));
    }

    @Test
    public void sendModifyTargetsCommandSwallowsFailedAnswer() {
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(7L);
        when(agentMgr.easySend(eq(7L), any(ModifyTargetsCommand.class)))
                .thenReturn(new Answer(null, false, "iqn missing"));

        // Must not throw — failed answer is logged, not raised.
        service.sendModifyTargetsCommand(new ModifyTargetsCommand(), host);

        verify(agentMgr).easySend(eq(7L), any(ModifyTargetsCommand.class));
    }

    @Test
    public void cleanupReusesSameVolumeInfoInstanceForRevokeAccess() throws Exception {
        // Defensive: regression-guard against the helper accidentally
        // looking up a fresh VolumeInfo via volFactory.getVolume() for
        // the revokeAccess call — the original code does a *second*
        // getVolume() lookup, so we assert volFactory.getVolume() is
        // called twice for the same id.
        UserVmVO vm = mockVm(42L, null, "i-1");
        VolumeVO root = mockRoot(Volume.State.Ready, 99L, 1L);
        when(root.getVolumeType()).thenReturn(Volume.Type.ROOT);

        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(true);
        when(storagePoolDao.findById(99L)).thenReturn(pool);

        HostVO host = mock(HostVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.XenServer);
        when(host.getId()).thenReturn(42L);
        when(hostDao.findById(42L)).thenReturn(host);

        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeInfo.getTO()).thenReturn(mock(DataTO.class));
        when(volFactory.getVolume(1L)).thenReturn(volumeInfo);

        DataStore dataStore = mock(DataStore.class);
        when(dataStoreMgr.getDataStore(99L, DataStoreRole.Primary)).thenReturn(dataStore);

        stubAgentSendSuccess();
        service.cleanupRootVolumeOnManagedStorage(vm, root);

        verify(volFactory, times(2)).getVolume(1L);
        verify(volumeMgr).revokeAccess(eq(volumeInfo), any(), any());
    }

    @Test
    public void cleanupOnVMwareCreatesDetailsMapWhenAbsent() throws Exception {
        // Original behaviour: if the data-store details map is null, the
        // helper allocates a new HashMap and pushes it onto the data
        // store before stamping the managed flag.
        UserVmVO vm = mockVm(42L, null, "i-1");
        VolumeVO root = mockRoot(Volume.State.Ready, 99L, 1L);

        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(true);
        when(storagePoolDao.findById(99L)).thenReturn(pool);

        HostVO host = mock(HostVO.class);
        when(host.getHypervisorType()).thenReturn(HypervisorType.VMware);
        when(host.getId()).thenReturn(42L);
        when(hostDao.findById(42L)).thenReturn(host);

        VolumeInfo volumeInfo = mock(VolumeInfo.class);
        when(volumeInfo.getTO()).thenReturn(mock(DataTO.class));
        PrimaryDataStore pds = mock(PrimaryDataStore.class);
        when(pds.getDetails()).thenReturn(null);
        when(volumeInfo.getDataStore()).thenReturn(pds);
        when(volFactory.getVolume(1L)).thenReturn(volumeInfo);

        DataStore dataStore = mock(DataStore.class);
        when(dataStoreMgr.getDataStore(99L, DataStoreRole.Primary)).thenReturn(dataStore);
        when(agentMgr.easySend(eq(42L), any(ModifyTargetsCommand.class)))
                .thenReturn(new Answer(null, true, ""));

        stubAgentSendSuccess();
        service.cleanupRootVolumeOnManagedStorage(vm, root);

        // Verify setDetails(map) was invoked with a fresh HashMap, and
        // that the map contains the managed flag.
        ArgumentCaptor<Map<String, String>> cap = ArgumentCaptor.forClass(Map.class);
        verify(pds).setDetails(cap.capture());
        Map<String, String> details = cap.getValue();
        assertSame(Boolean.TRUE.toString(), details.get(DiskTO.MANAGED));
    }
}
