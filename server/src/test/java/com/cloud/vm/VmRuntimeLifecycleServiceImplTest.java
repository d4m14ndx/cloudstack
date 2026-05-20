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

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.api.Command;
import com.cloud.agent.manager.Commands;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.rules.RulesManager;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.TemplateManager;
import com.cloud.user.VmDiskStatisticsVO;
import com.cloud.user.dao.VmDiskStatisticsDao;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.snapshot.VMSnapshotManager;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

@RunWith(MockitoJUnitRunner.class)
public class VmRuntimeLifecycleServiceImplTest {

    private static final long VM_ID = 42L;
    private static final long ACCOUNT_ID = 7L;
    private static final long DATA_CENTER_ID = 8L;
    private static final long NIC_ID = 9L;
    private static final long NETWORK_ID = 10L;
    private static final long VOLUME_ID = 11L;

    @Mock private UserVmDao vmDao;
    @Mock private NicDao nicDao;
    @Mock private NetworkDao networkDao;
    @Mock private VolumeDao volsDao;
    @Mock private VmDiskStatisticsDao vmDiskStatsDao;
    @Mock private VMSnapshotDao vmSnapshotDao;
    @Mock private VMSnapshotManager vmSnapshotMgr;
    @Mock private TemplateManager templateMgr;
    @Mock private NetworkModel networkModel;
    @Mock private DataCenterDao dcDao;
    @Mock private NetworkServiceMapDao ntwkSrvcDao;
    @Mock private RulesManager rulesMgr;
    @Mock private IPAddressDao ipAddressDao;
    @Mock private EntityManager entityManager;

    private VmRuntimeLifecycleServiceImpl service;

    @Before
    public void setUp() {
        service = new VmRuntimeLifecycleServiceImpl();
        ReflectionTestUtils.setField(service, "vmDao", vmDao);
        ReflectionTestUtils.setField(service, "nicDao", nicDao);
        ReflectionTestUtils.setField(service, "networkDao", networkDao);
        ReflectionTestUtils.setField(service, "volsDao", volsDao);
        ReflectionTestUtils.setField(service, "vmDiskStatsDao", vmDiskStatsDao);
        ReflectionTestUtils.setField(service, "vmSnapshotDao", vmSnapshotDao);
        ReflectionTestUtils.setField(service, "vmSnapshotMgr", vmSnapshotMgr);
        ReflectionTestUtils.setField(service, "templateMgr", templateMgr);
        ReflectionTestUtils.setField(service, "networkModel", networkModel);
        ReflectionTestUtils.setField(service, "dcDao", dcDao);
        ReflectionTestUtils.setField(service, "ntwkSrvcDao", ntwkSrvcDao);
        ReflectionTestUtils.setField(service, "rulesMgr", rulesMgr);
        ReflectionTestUtils.setField(service, "ipAddressDao", ipAddressDao);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
    }

    @Test
    public void finalizeDeploymentPersistsMissingDiskStatsAndUpdatesGuestPrivateAddress() {
        VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        UserVmVO vm = mock(UserVmVO.class);
        NicVO guestNic = mock(NicVO.class);
        NetworkVO guestNetwork = mock(NetworkVO.class);
        VolumeVO volume = mock(VolumeVO.class);
        Commands cmds = new Commands(Command.OnError.Stop);

        when(profile.getId()).thenReturn(VM_ID);
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getId()).thenReturn(VM_ID);
        when(vm.getAccountId()).thenReturn(ACCOUNT_ID);
        when(vm.getDataCenterId()).thenReturn(DATA_CENTER_ID);
        when(nicDao.listByVmId(VM_ID)).thenReturn(List.of(guestNic));
        when(guestNic.getNetworkId()).thenReturn(NETWORK_ID);
        when(guestNic.getIPv4Address()).thenReturn("10.1.1.5");
        when(guestNic.getMacAddress()).thenReturn("02:00:00:00:00:05");
        when(networkDao.findById(NETWORK_ID)).thenReturn(guestNetwork);
        when(guestNetwork.getTrafficType()).thenReturn(TrafficType.Guest);
        when(volsDao.findByInstance(VM_ID)).thenReturn(List.of(volume));
        when(volume.getId()).thenReturn(VOLUME_ID);
        when(vmDiskStatsDao.findBy(ACCOUNT_ID, DATA_CENTER_ID, VM_ID, VOLUME_ID)).thenReturn(null);

        assertTrue(service.finalizeDeployment(cmds, profile, null, null));

        verify(vm).setPrivateIpAddress("10.1.1.5");
        verify(vm).setPrivateMacAddress("02:00:00:00:00:05");
        verify(vmDao).update(eq(VM_ID), eq(vm));
        ArgumentCaptor<VmDiskStatisticsVO> statsCaptor = ArgumentCaptor.forClass(VmDiskStatisticsVO.class);
        verify(vmDiskStatsDao).persist(statsCaptor.capture());
        verify(vmSnapshotMgr).createRestoreCommand(eq(vm), any());
    }

    @Test
    public void updateVncPasswordIfItHasChangedPersistsReturnedPassword() {
        VirtualMachineProfile profile = mock(VirtualMachineProfile.class);
        UserVmVO vm = mock(UserVmVO.class);
        when(profile.getId()).thenReturn(VM_ID);
        when(vmDao.findById(VM_ID)).thenReturn(vm);
        when(vm.getId()).thenReturn(VM_ID);

        service.updateVncPasswordIfItHasChanged("original", "returned", profile);

        verify(vm).setVncPassword("returned");
        verify(vmDao).update(eq(VM_ID), eq(vm));
    }

    @Test
    public void updateVncPasswordIfItHasNotChangedDoesNotPersist() {
        VirtualMachineProfile profile = mock(VirtualMachineProfile.class);

        service.updateVncPasswordIfItHasChanged("same", "same", profile);

        verify(vmDao, never()).update(anyLong(), any(UserVmVO.class));
    }
}
