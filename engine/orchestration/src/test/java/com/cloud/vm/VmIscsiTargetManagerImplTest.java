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
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.ModifyTargetsCommand;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;

@RunWith(MockitoJUnitRunner.class)
public class VmIscsiTargetManagerImplTest {

    @Mock private HostDao hostDao;
    @Mock private VolumeDao volumeDao;
    @Mock private PrimaryDataStoreDao storagePoolDao;
    @Mock private AgentManager agentMgr;

    @InjectMocks
    private VmIscsiTargetManagerImpl manager;

    private static final long HOST_ID = 17L;
    private static final long VM_ID = 42L;
    private static final long POOL_ID_A = 101L;
    private static final long POOL_ID_B = 202L;

    private HostVO mockHost(HypervisorType type) {
        HostVO host = mock(HostVO.class);
        when(host.getHypervisorType()).thenReturn(type);
        return host;
    }

    private VolumeVO mockVolume(long poolId, String iScsiName) {
        VolumeVO volume = mock(VolumeVO.class);
        when(volume.getPoolId()).thenReturn(poolId);
        when(volume.get_iScsiName()).thenReturn(iScsiName);
        return volume;
    }

    private StoragePoolVO mockPool(boolean managed, String hostAddress, int port) {
        StoragePoolVO pool = mock(StoragePoolVO.class);
        when(pool.isManaged()).thenReturn(managed);
        if (managed) {
            when(pool.getHostAddress()).thenReturn(hostAddress);
            when(pool.getPort()).thenReturn(port);
        }
        return pool;
    }

    // ---- getTargets ----

    @Test
    public void getTargetsReturnsEmptyWhenHostNotFound() {
        when(hostDao.findById(HOST_ID)).thenReturn(null);

        List<Map<String, String>> targets = manager.getTargets(HOST_ID, VM_ID);

        assertTrue(targets.isEmpty());
        verify(volumeDao, never()).findByInstance(anyLong());
        verify(storagePoolDao, never()).findById(anyLong());
    }

    @Test
    public void getTargetsReturnsEmptyForNullHostId() {
        when(hostDao.findById((Long) null)).thenReturn(null);

        List<Map<String, String>> targets = manager.getTargets(null, VM_ID);

        assertTrue(targets.isEmpty());
        verify(volumeDao, never()).findByInstance(anyLong());
    }

    @Test
    public void getTargetsReturnsEmptyForNonVmwareHost() {
        HostVO host = mockHost(HypervisorType.KVM);
        when(hostDao.findById(HOST_ID)).thenReturn(host);

        List<Map<String, String>> targets = manager.getTargets(HOST_ID, VM_ID);

        assertTrue(targets.isEmpty());
        verify(volumeDao, never()).findByInstance(anyLong());
        verify(storagePoolDao, never()).findById(anyLong());
    }

    @Test
    public void getTargetsReturnsEmptyForXenServerHost() {
        HostVO host = mockHost(HypervisorType.XenServer);
        when(hostDao.findById(HOST_ID)).thenReturn(host);

        List<Map<String, String>> targets = manager.getTargets(HOST_ID, VM_ID);

        assertTrue(targets.isEmpty());
        verify(volumeDao, never()).findByInstance(anyLong());
    }

    @Test
    public void getTargetsReturnsEmptyForHyperVHost() {
        HostVO host = mockHost(HypervisorType.Hyperv);
        when(hostDao.findById(HOST_ID)).thenReturn(host);

        List<Map<String, String>> targets = manager.getTargets(HOST_ID, VM_ID);

        assertTrue(targets.isEmpty());
        verify(volumeDao, never()).findByInstance(anyLong());
    }

    @Test
    public void getTargetsReturnsEmptyWhenVmHasNoVolumes() {
        HostVO host = mockHost(HypervisorType.VMware);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.emptyList());

        List<Map<String, String>> targets = manager.getTargets(HOST_ID, VM_ID);

        assertTrue(targets.isEmpty());
        verify(storagePoolDao, never()).findById(anyLong());
    }

    @Test
    public void getTargetsSkipsUnmanagedPools() {
        HostVO host = mockHost(HypervisorType.VMware);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        VolumeVO volume = mockVolume(POOL_ID_A, "iqn.unmanaged");
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.singletonList(volume));
        StoragePoolVO pool = mockPool(false, null, 0);
        when(storagePoolDao.findById(POOL_ID_A)).thenReturn(pool);

        List<Map<String, String>> targets = manager.getTargets(HOST_ID, VM_ID);

        assertTrue(targets.isEmpty());
    }

    @Test
    public void getTargetsSkipsNullPoolLookups() {
        HostVO host = mockHost(HypervisorType.VMware);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        VolumeVO volume = mockVolume(POOL_ID_A, "iqn.orphan");
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.singletonList(volume));
        when(storagePoolDao.findById(POOL_ID_A)).thenReturn(null);

        List<Map<String, String>> targets = manager.getTargets(HOST_ID, VM_ID);

        assertTrue(targets.isEmpty());
    }

    @Test
    public void getTargetsBuildsTupleFromSingleManagedVolume() {
        HostVO host = mockHost(HypervisorType.VMware);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        VolumeVO volume = mockVolume(POOL_ID_A, "iqn.2001-04.com.example:storage.disk1");
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Collections.singletonList(volume));
        StoragePoolVO pool = mockPool(true, "10.1.1.10", 3260);
        when(storagePoolDao.findById(POOL_ID_A)).thenReturn(pool);

        List<Map<String, String>> targets = manager.getTargets(HOST_ID, VM_ID);

        assertEquals(1, targets.size());
        Map<String, String> entry = targets.get(0);
        assertEquals("10.1.1.10", entry.get(ModifyTargetsCommand.STORAGE_HOST));
        assertEquals("3260", entry.get(ModifyTargetsCommand.STORAGE_PORT));
        assertEquals("iqn.2001-04.com.example:storage.disk1", entry.get(ModifyTargetsCommand.IQN));
    }

    @Test
    public void getTargetsHandlesMixedManagedAndUnmanagedVolumes() {
        HostVO host = mockHost(HypervisorType.VMware);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        VolumeVO managed = mockVolume(POOL_ID_A, "iqn.managed");
        VolumeVO unmanaged = mockVolume(POOL_ID_B, "iqn.unmanaged");
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Arrays.asList(managed, unmanaged));
        StoragePoolVO managedPool = mockPool(true, "10.1.1.10", 3260);
        StoragePoolVO unmanagedPool = mockPool(false, null, 0);
        when(storagePoolDao.findById(POOL_ID_A)).thenReturn(managedPool);
        when(storagePoolDao.findById(POOL_ID_B)).thenReturn(unmanagedPool);

        List<Map<String, String>> targets = manager.getTargets(HOST_ID, VM_ID);

        assertEquals(1, targets.size());
        assertEquals("iqn.managed", targets.get(0).get(ModifyTargetsCommand.IQN));
    }

    @Test
    public void getTargetsCollectsAllManagedTuplesWhenMultipleVolumesAreManaged() {
        HostVO host = mockHost(HypervisorType.VMware);
        when(hostDao.findById(HOST_ID)).thenReturn(host);
        VolumeVO a = mockVolume(POOL_ID_A, "iqn.a");
        VolumeVO b = mockVolume(POOL_ID_B, "iqn.b");
        when(volumeDao.findByInstance(VM_ID)).thenReturn(Arrays.asList(a, b));
        StoragePoolVO poolA = mockPool(true, "10.1.1.10", 3260);
        StoragePoolVO poolB = mockPool(true, "10.1.1.11", 3261);
        when(storagePoolDao.findById(POOL_ID_A)).thenReturn(poolA);
        when(storagePoolDao.findById(POOL_ID_B)).thenReturn(poolB);

        List<Map<String, String>> targets = manager.getTargets(HOST_ID, VM_ID);

        assertEquals(2, targets.size());
        assertEquals("10.1.1.10", targets.get(0).get(ModifyTargetsCommand.STORAGE_HOST));
        assertEquals("3260", targets.get(0).get(ModifyTargetsCommand.STORAGE_PORT));
        assertEquals("10.1.1.11", targets.get(1).get(ModifyTargetsCommand.STORAGE_HOST));
        assertEquals("3261", targets.get(1).get(ModifyTargetsCommand.STORAGE_PORT));
    }

    // ---- removeDynamicTargets ----

    @Test
    public void removeDynamicTargetsSendsModifyTargetsWithDynamicRemovalConfigured() {
        List<Map<String, String>> input = Collections.singletonList(Collections.singletonMap("iqn", "iqn.test"));
        Answer ok = mock(Answer.class);
        when(ok.getResult()).thenReturn(true);
        when(agentMgr.easySend(eq(HOST_ID), any())).thenReturn(ok);

        manager.removeDynamicTargets(HOST_ID, input);

        ArgumentCaptor<ModifyTargetsCommand> captor = ArgumentCaptor.forClass(ModifyTargetsCommand.class);
        verify(agentMgr).easySend(eq(HOST_ID), captor.capture());
        ModifyTargetsCommand sent = captor.getValue();
        assertEquals(input, sent.getTargets());
        assertTrue(sent.getApplyToAllHostsInCluster());
        assertFalse(sent.getAdd());
        assertEquals(ModifyTargetsCommand.TargetTypeToRemove.DYNAMIC, sent.getTargetTypeToRemove());
    }

    @Test
    public void removeDynamicTargetsAcceptsEmptyTargetList() {
        Answer ok = mock(Answer.class);
        when(ok.getResult()).thenReturn(true);
        when(agentMgr.easySend(eq(HOST_ID), any())).thenReturn(ok);

        manager.removeDynamicTargets(HOST_ID, Collections.emptyList());

        ArgumentCaptor<ModifyTargetsCommand> captor = ArgumentCaptor.forClass(ModifyTargetsCommand.class);
        verify(agentMgr).easySend(eq(HOST_ID), captor.capture());
        assertTrue(captor.getValue().getTargets().isEmpty());
    }

    // ---- sendModifyTargetsCommand ----

    @Test
    public void sendModifyTargetsCommandSwallowsNullAnswerWithoutThrowing() {
        ModifyTargetsCommand cmd = new ModifyTargetsCommand();
        cmd.setTargets(Collections.singletonList(Collections.singletonMap("iqn", "iqn.test")));
        when(agentMgr.easySend(eq(HOST_ID), any())).thenReturn(null);

        manager.sendModifyTargetsCommand(cmd, HOST_ID);

        verify(agentMgr).easySend(eq(HOST_ID), any());
    }

    @Test
    public void sendModifyTargetsCommandSwallowsFailureAnswerWithoutThrowing() {
        ModifyTargetsCommand cmd = new ModifyTargetsCommand();
        cmd.setTargets(Collections.singletonList(Collections.singletonMap("iqn", "iqn.test")));
        Answer failure = mock(Answer.class);
        when(failure.getResult()).thenReturn(false);
        when(agentMgr.easySend(eq(HOST_ID), any())).thenReturn(failure);

        manager.sendModifyTargetsCommand(cmd, HOST_ID);

        verify(agentMgr).easySend(eq(HOST_ID), any());
    }

    @Test
    public void sendModifyTargetsCommandReturnsCleanlyOnSuccessfulAnswer() {
        ModifyTargetsCommand cmd = new ModifyTargetsCommand();
        cmd.setTargets(Collections.singletonList(Collections.singletonMap("iqn", "iqn.test")));
        Answer success = mock(Answer.class);
        when(success.getResult()).thenReturn(true);
        when(agentMgr.easySend(eq(HOST_ID), any())).thenReturn(success);

        manager.sendModifyTargetsCommand(cmd, HOST_ID);

        verify(agentMgr).easySend(eq(HOST_ID), any());
    }
}
