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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.TimeUnit;

import org.apache.cloudstack.acl.ControlledEntity;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVmIpAddressCommand;
import com.cloud.event.ActionEventUtils;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.storage.GuestOSCategoryVO;
import com.cloud.storage.GuestOSVO;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.utils.db.GlobalLock;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class VmExternalDhcpIpFetchServiceImplTest {

    @Mock private NetworkDao networkDao;
    @Mock private NicDao nicDao;
    @Mock private VMInstanceDao vmInstanceDao;
    @Mock private UserVmDao vmDao;
    @Mock private AgentManager agentMgr;
    @Mock private GuestOSDao guestOSDao;
    @Mock private GuestOSCategoryDao guestOSCategoryDao;
    @Mock private NetworkModel networkModel;

    private VmExternalDhcpIpFetchServiceImpl service;

    @Before
    public void setUp() {
        service = new VmExternalDhcpIpFetchServiceImpl();
        ReflectionTestUtils.setField(service, "networkDao", networkDao);
        ReflectionTestUtils.setField(service, "nicDao", nicDao);
        ReflectionTestUtils.setField(service, "vmInstanceDao", vmInstanceDao);
        ReflectionTestUtils.setField(service, "vmDao", vmDao);
        ReflectionTestUtils.setField(service, "agentMgr", agentMgr);
        ReflectionTestUtils.setField(service, "guestOSDao", guestOSDao);
        ReflectionTestUtils.setField(service, "guestOSCategoryDao", guestOSCategoryDao);
        ReflectionTestUtils.setField(service, "networkModel", networkModel);
        ReflectionTestUtils.setField(service, "vmIpFetchThreadExecutor", new DirectExecutorService());
    }

    @Test
    public void loadVmDetailsQueuesOnlyRunningVmsOnEligibleNetworksWithMissingIp() {
        NetworkVO shared = network(10L, Network.GuestType.Shared);
        NetworkVO isolated = network(11L, Network.GuestType.Shared);
        NetworkVO l2 = network(12L, Network.GuestType.L2);
        NicVO queuedSharedNic = nic(101L, 201L, 10L, null);
        NicVO nicWithIp = nic(102L, 202L, 10L, "192.0.2.9");
        NicVO queuedL2Nic = nic(104L, 204L, 12L, null);
        VMInstanceVO runningSharedVm = vm(201L, VirtualMachine.State.Running);
        VMInstanceVO stoppedVm = vm(204L, VirtualMachine.State.Stopped);

        when(networkDao.listByGuestType(Network.GuestType.Shared)).thenReturn(Arrays.asList(shared, isolated));
        when(networkDao.listByGuestType(Network.GuestType.L2)).thenReturn(Collections.singletonList(l2));
        when(networkModel.isSharedNetworkWithoutServices(10L)).thenReturn(true);
        when(networkModel.isSharedNetworkWithoutServices(11L)).thenReturn(false);
        when(nicDao.listByNetworkId(10L)).thenReturn(Arrays.asList(queuedSharedNic, nicWithIp));
        when(nicDao.listByNetworkId(12L)).thenReturn(Collections.singletonList(queuedL2Nic));
        when(vmInstanceDao.findById(201L)).thenReturn(runningSharedVm);
        when(vmInstanceDao.findById(204L)).thenReturn(stoppedVm);

        service.loadVmDetailsInMapForExternalDhcpIp();

        assertEquals(1, service.getTrackedNicCount());
        service.scheduleIpFetch(104L, 204L);
        assertEquals(2, service.getTrackedNicCount());
    }

    @Test
    public void vmIpFetchTaskUpdatesNicAndClearsEntryWhenAgentReturnsValidIp() throws Exception {
        long nicId = 101L;
        long vmId = 201L;
        NicVO nic = nic(nicId, vmId, 10L, null);
        VMInstanceVO vmInstance = vm(vmId, VirtualMachine.State.Running);
        vmInstance.setUuid("vm-uuid");
        vmInstance.setInstanceName("i-2-201-VM");
        vmInstance.setGuestOSId(301L);
        vmInstance.setHostId(401L);
        UserVmVO userVm = mock(UserVmVO.class);
        NetworkVO network = network(10L, Network.GuestType.Shared);
        network.setCidr("192.0.2.0/24");
        GuestOSVO guestOS = new GuestOSVO();
        guestOS.setCategoryId(501L);
        GuestOSCategoryVO guestOSCategory = new GuestOSCategoryVO("Linux", true);
        GlobalLock lock = mock(GlobalLock.class);

        when(lock.lock(VmExternalDhcpIpFetchServiceImpl.ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION)).thenReturn(true);
        when(vmDao.findById(vmId)).thenReturn(userVm);
        when(vmInstanceDao.findById(vmId)).thenReturn(vmInstance);
        when(nicDao.findById(nicId)).thenReturn(nic);
        when(networkDao.findById(10L)).thenReturn(network);
        when(userVm.getGuestOSId()).thenReturn(301L);
        when(userVm.getHostId()).thenReturn(401L);
        when(guestOSDao.findById(301L)).thenReturn(guestOS);
        when(guestOSCategoryDao.findById(501L)).thenReturn(guestOSCategory);
        when(agentMgr.send(eq(401L), any(GetVmIpAddressCommand.class))).thenReturn(new Answer(null, true, "192.0.2.25"));

        service.scheduleIpFetch(nicId, vmId);

        try (MockedStatic<GlobalLock> ignored = mockStatic(GlobalLock.class);
             MockedStatic<ActionEventUtils> ignoredEvents = mockStatic(ActionEventUtils.class)) {
            when(GlobalLock.getInternLock("vmIpFetch")).thenReturn(lock);

            service.getVmIpFetchTask().run();
        }

        assertEquals("192.0.2.25", nic.getIPv4Address());
        assertEquals(0, service.getTrackedNicCount());
        verify(nicDao).update(nicId, nic);
        verify(lock).unlock();
        verify(lock).releaseRef();
    }

    @Test
    public void vmIpFetchTaskDropsEntryAndSkipsAgentWhenRetryCountIsExhausted() throws Exception {
        long nicId = 101L;
        long vmId = 201L;
        GlobalLock lock = mock(GlobalLock.class);

        when(lock.lock(VmExternalDhcpIpFetchServiceImpl.ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION)).thenReturn(true);
        service.trackNicForIpFetch(nicId, vmId, 0);

        try (MockedStatic<GlobalLock> ignored = mockStatic(GlobalLock.class);
             MockedStatic<ActionEventUtils> ignoredEvents = mockStatic(ActionEventUtils.class)) {
            when(GlobalLock.getInternLock("vmIpFetch")).thenReturn(lock);

            service.getVmIpFetchTask().run();
        }

        assertEquals(0, service.getTrackedNicCount());
        verify(agentMgr, never()).send(eq(401L), any(GetVmIpAddressCommand.class));
    }

    @Test
    public void vmIpAddrFetchThreadDecrementsCountWhenAgentReturnsNoIp() throws Exception {
        long nicId = 101L;
        long vmId = 201L;
        NicVO nic = nic(nicId, vmId, 10L, null);
        VMInstanceVO vmInstance = vm(vmId, VirtualMachine.State.Running);
        vmInstance.setUuid("vm-uuid");
        vmInstance.setInstanceName("i-2-201-VM");
        vmInstance.setGuestOSId(301L);
        vmInstance.setHostId(401L);
        UserVmVO userVm = mock(UserVmVO.class);
        NetworkVO network = network(10L, Network.GuestType.Shared);
        network.setCidr("192.0.2.0/24");
        GuestOSVO guestOS = new GuestOSVO();
        guestOS.setCategoryId(501L);
        GuestOSCategoryVO guestOSCategory = new GuestOSCategoryVO("Linux", true);
        GlobalLock lock = mock(GlobalLock.class);

        when(lock.lock(VmExternalDhcpIpFetchServiceImpl.ACQUIRE_GLOBAL_LOCK_TIMEOUT_FOR_COOPERATION)).thenReturn(true);
        when(vmDao.findById(vmId)).thenReturn(userVm);
        when(vmInstanceDao.findById(vmId)).thenReturn(vmInstance);
        when(nicDao.findById(nicId)).thenReturn(nic);
        when(networkDao.findById(10L)).thenReturn(network);
        when(userVm.getGuestOSId()).thenReturn(301L);
        when(userVm.getHostId()).thenReturn(401L);
        when(guestOSDao.findById(301L)).thenReturn(guestOS);
        when(guestOSCategoryDao.findById(501L)).thenReturn(guestOSCategory);
        when(agentMgr.send(eq(401L), any(GetVmIpAddressCommand.class))).thenReturn(new Answer(null, true, null));

        service.trackNicForIpFetch(nicId, vmId, 2);

        try (MockedStatic<GlobalLock> ignored = mockStatic(GlobalLock.class)) {
            when(GlobalLock.getInternLock("vmIpFetch")).thenReturn(lock);

            service.getVmIpFetchTask().run();
        }

        assertEquals(1, service.getTrackedRetrievalCount(nicId));
        verify(nicDao, never()).update(eq(nicId), any());
    }

    @Test
    public void trackNicForIpFetchAddsEntryWithCorrectVmAndCount() {
        service.trackNicForIpFetch(201L, 301L, 5);

        assertEquals(1, service.getTrackedNicCount());
        assertEquals(5, service.getTrackedRetrievalCount(201L));
    }

    @Test
    public void getTrackedNicCountReturnsZeroAfterSetup() {
        assertEquals(0, service.getTrackedNicCount());
    }

    @Test
    public void scheduleIpFetchAddsNicToTrackedMap() {
        service.scheduleIpFetch(301L, 401L);

        assertEquals(1, service.getTrackedNicCount());
    }

    @Test
    public void trackNicForIpFetchOverwritesExistingEntry() {
        service.trackNicForIpFetch(201L, 301L, 5);
        service.trackNicForIpFetch(201L, 302L, 3);

        assertEquals(1, service.getTrackedNicCount());
        assertEquals(3, service.getTrackedRetrievalCount(201L));
    }

    @Test
    public void loadVmDetailsSkipsStoppedVms() {
        NetworkVO shared = network(10L, Network.GuestType.Shared);
        NicVO nic = nic(101L, 201L, 10L, null);
        VMInstanceVO stoppedVm = vm(201L, VirtualMachine.State.Stopped);

        when(networkDao.listByGuestType(Network.GuestType.Shared)).thenReturn(Collections.singletonList(shared));
        when(networkDao.listByGuestType(Network.GuestType.L2)).thenReturn(Collections.emptyList());
        when(networkModel.isSharedNetworkWithoutServices(10L)).thenReturn(true);
        when(nicDao.listByNetworkId(10L)).thenReturn(Collections.singletonList(nic));
        when(vmInstanceDao.findById(201L)).thenReturn(stoppedVm);

        service.loadVmDetailsInMapForExternalDhcpIp();

        assertEquals(0, service.getTrackedNicCount());
    }

    @Test
    public void loadVmDetailsSkipsNicsWithExistingIp() {
        NetworkVO shared = network(10L, Network.GuestType.Shared);
        NicVO nicWithIp = nic(102L, 202L, 10L, "192.0.2.5");

        when(networkDao.listByGuestType(Network.GuestType.Shared)).thenReturn(Collections.singletonList(shared));
        when(networkDao.listByGuestType(Network.GuestType.L2)).thenReturn(Collections.emptyList());
        when(networkModel.isSharedNetworkWithoutServices(10L)).thenReturn(true);
        when(nicDao.listByNetworkId(10L)).thenReturn(Collections.singletonList(nicWithIp));

        service.loadVmDetailsInMapForExternalDhcpIp();

        assertEquals(0, service.getTrackedNicCount());
    }

    private NetworkVO network(long id, Network.GuestType guestType) {
        return new NetworkVO(id, Networks.TrafficType.Guest, Networks.Mode.None, Networks.BroadcastDomainType.Native,
                1L, 1L, 1L, id, "net-" + id, "net-" + id, "example.com", guestType, 1L, 1L,
                ControlledEntity.ACLType.Account, true, null, false);
    }

    private NicVO nic(long id, long vmId, long networkId, String ipAddress) {
        NicVO nic = new NicVO("reserver", vmId, networkId, VirtualMachine.Type.User);
        ReflectionTestUtils.setField(nic, "id", id);
        nic.setIPv4Address(ipAddress);
        nic.setMacAddress("02:00:00:00:00:" + id);
        return nic;
    }

    private VMInstanceVO vm(long vmId, VirtualMachine.State state) {
        VMInstanceVO vm = new VMInstanceVO();
        ReflectionTestUtils.setField(vm, "id", vmId);
        vm.setState(state);
        return vm;
    }

    private static class DirectExecutorService extends AbstractExecutorService {
        private boolean shutdown;

        @Override
        public void shutdown() {
            shutdown = true;
        }

        @Override
        public java.util.List<Runnable> shutdownNow() {
            shutdown = true;
            return Collections.emptyList();
        }

        @Override
        public boolean isShutdown() {
            return shutdown;
        }

        @Override
        public boolean isTerminated() {
            return shutdown;
        }

        @Override
        public boolean awaitTermination(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public void execute(Runnable command) {
            command.run();
        }
    }
}
