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
package com.cloud.server;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.api.command.admin.systemvm.ListSystemVMsCmd;
import org.apache.cloudstack.api.command.admin.systemvm.PatchSystemVMCmd;
import org.apache.cloudstack.api.command.admin.systemvm.ScaleSystemVMCmd;
import org.apache.cloudstack.api.command.admin.systemvm.UpgradeSystemVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.PatchSystemVmAnswer;
import com.cloud.agent.api.PatchSystemVmCommand;
import com.cloud.agent.manager.Commands;
import com.cloud.cpu.CPU;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ManagementServerException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.VirtualMachineMigrationException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Networks;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.NicVO;
import com.cloud.vm.UserVmManager;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Greenfield focused unit tests for {@link SystemVmOperationsServiceImpl}.
 * No existing ManagementServerImplTest cases needed migration — this seam had
 * zero prior test coverage.
 */
@RunWith(MockitoJUnitRunner.class)
public class SystemVmOperationsServiceImplTest {

    @Mock VMInstanceDao vmInstanceDao;
    @Mock UserVmManager userVmMgr;
    @Mock VirtualMachineManager itMgr;
    @Mock ServiceOfferingDao offeringDao;
    @Mock AccountManager accountMgr;
    @Mock AgentManager agentMgr;
    @Mock NicDao nicDao;
    @Mock NetworkDao networkDao;
    @Mock DomainRouterDao routerDao;
    @Mock PrimaryDataStoreDao primaryDataStoreDao;
    @Mock VolumeDao volumeDao;
    @Mock VMTemplateDao templateDao;
    @Mock CapabilitiesService capabilitiesService;

    @Spy
    @InjectMocks
    SystemVmOperationsServiceImpl service;

    private Account callerAccount;

    // ── Stub command classes (avoids ByteBuddy issues under JDK 25) ───────────

    static class StubScaleSystemVMCmd extends ScaleSystemVMCmd {
        private final Long id;
        private final Long serviceOfferingId;
        private final Map<String, String> details;

        StubScaleSystemVMCmd(Long id, Long serviceOfferingId, Map<String, String> details) {
            this.id = id;
            this.serviceOfferingId = serviceOfferingId;
            this.details = details;
        }

        @Override public Long getId() { return id; }
        @Override public Long getServiceOfferingId() { return serviceOfferingId; }
        @Override public Map<String, String> getDetails() { return details; }
    }

    static class StubUpgradeSystemVMCmd extends UpgradeSystemVMCmd {
        private final Long id;
        private final Long serviceOfferingId;
        private final Map<String, String> details;

        StubUpgradeSystemVMCmd(Long id, Long serviceOfferingId, Map<String, String> details) {
            this.id = id;
            this.serviceOfferingId = serviceOfferingId;
            this.details = details;
        }

        @Override public Long getId() { return id; }
        @Override public Long getServiceOfferingId() { return serviceOfferingId; }
        @Override public Map<String, String> getDetails() { return details; }
    }

    static class StubPatchSystemVMCmd extends PatchSystemVMCmd {
        private final Long id;
        private final boolean forced;

        StubPatchSystemVMCmd(Long id, boolean forced) {
            this.id = id;
            this.forced = forced;
        }

        @Override public Long getId() { return id; }
        @Override public boolean isForced() { return forced; }
    }

    static class StubListSystemVMsCmd extends ListSystemVMsCmd {
        private Long id;
        private Long zoneId;
        private Long podId;
        private Long hostId;
        private Long storageId;
        private String name;
        private String state;
        private String keyword;
        private String systemVmType;
        private CPU.CPUArch arch;
        private Long startIndex = 0L;
        private Long pageSizeVal = 20L;

        @Override public Long getId() { return id; }
        @Override public Long getZoneId() { return zoneId; }
        @Override public Long getPodId() { return podId; }
        @Override public Long getHostId() { return hostId; }
        @Override public Long getStorageId() { return storageId; }
        @Override public String getSystemVmName() { return name; }
        @Override public String getState() { return state; }
        @Override public String getKeyword() { return keyword; }
        @Override public String getSystemVmType() { return systemVmType; }
        @Override public CPU.CPUArch getArch() { return arch; }
        @Override public Long getStartIndex() { return startIndex; }
        @Override public Long getPageSizeVal() { return pageSizeVal; }
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Before
    public void setUp() {
        callerAccount = Mockito.mock(Account.class);
        CallContext.register(Mockito.mock(User.class), callerAccount);
        Mockito.lenient().when(accountMgr.checkAccessAndSpecifyAuthority(any(Account.class), any())).thenReturn(null);
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // ── upgradeSystemVM(ScaleSystemVMCmd) ─────────────────────────────────────

    @Test(expected = InvalidParameterValueException.class)
    public void upgradeSystemVM_scale_xenServerRunning_throwsInvalidParameter()
            throws ResourceUnavailableException, ManagementServerException,
                   VirtualMachineMigrationException, ConcurrentOperationException {
        VMInstanceVO vm = new VMInstanceVO();
        vm.setHypervisorType(HypervisorType.XenServer);
        vm.setState(State.Running);
        when(vmInstanceDao.findById(1L)).thenReturn(vm);

        service.upgradeSystemVM(new StubScaleSystemVMCmd(1L, 2L, Collections.emptyMap()));
    }

    @Test
    public void upgradeSystemVM_scale_success_returnsRefreshedVm()
            throws ResourceUnavailableException, ManagementServerException,
                   VirtualMachineMigrationException, ConcurrentOperationException {
        VMInstanceVO vm = new VMInstanceVO();
        vm.setHypervisorType(HypervisorType.KVM);
        vm.setState(State.Running);
        VMInstanceVO refreshed = new VMInstanceVO();
        when(vmInstanceDao.findById(1L)).thenReturn(vm).thenReturn(refreshed);
        when(userVmMgr.upgradeVirtualMachine(1L, 2L, Collections.emptyMap())).thenReturn(true);

        VirtualMachine result = service.upgradeSystemVM(new StubScaleSystemVMCmd(1L, 2L, Collections.emptyMap()));
        assertSame(refreshed, result);
    }

    @Test(expected = CloudRuntimeException.class)
    public void upgradeSystemVM_scale_userVmMgrReturnsFalse_throwsCloudRuntime()
            throws ResourceUnavailableException, ManagementServerException,
                   VirtualMachineMigrationException, ConcurrentOperationException {
        VMInstanceVO vm = new VMInstanceVO();
        vm.setHypervisorType(HypervisorType.KVM);
        vm.setState(State.Running);
        when(vmInstanceDao.findById(1L)).thenReturn(vm);
        when(userVmMgr.upgradeVirtualMachine(anyLong(), anyLong(), any())).thenReturn(false);

        service.upgradeSystemVM(new StubScaleSystemVMCmd(1L, 2L, Collections.emptyMap()));
    }

    // ── upgradeSystemVM(UpgradeSystemVMCmd) / upgradeStoppedSystemVm ─────────

    @Test(expected = InvalidParameterValueException.class)
    public void upgradeSystemVM_upgrade_unknownId_throwsInvalidParameter() {
        when(vmInstanceDao.findByIdTypes(eq(99L),
                eq(VirtualMachine.Type.ConsoleProxy), eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(null);

        service.upgradeSystemVM(new StubUpgradeSystemVMCmd(99L, 2L, Collections.emptyMap()));
    }

    @Test
    public void upgradeSystemVM_upgrade_dynamicOffering_validatesCustomParams() {
        VMInstanceVO systemVm = new VMInstanceVO();
        ServiceOfferingVO dynamic = Mockito.mock(ServiceOfferingVO.class);
        ServiceOfferingVO current = Mockito.mock(ServiceOfferingVO.class);
        ServiceOfferingVO resolved = Mockito.mock(ServiceOfferingVO.class);
        VMInstanceVO refreshed = new VMInstanceVO();

        when(vmInstanceDao.findByIdTypes(eq(5L),
                eq(VirtualMachine.Type.ConsoleProxy), eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(systemVm);
        when(offeringDao.findById(2L)).thenReturn(dynamic);
        when(offeringDao.findById(5L, systemVm.getServiceOfferingId())).thenReturn(current);
        when(dynamic.isDynamic()).thenReturn(true);
        when(offeringDao.getComputeOffering(eq(dynamic), any())).thenReturn(resolved);
        when(itMgr.upgradeVmDb(eq(5L), eq(resolved), eq(current))).thenReturn(true);
        when(vmInstanceDao.findById(5L)).thenReturn(refreshed);

        VirtualMachine result = service.upgradeSystemVM(new StubUpgradeSystemVMCmd(5L, 2L, new HashMap<>()));

        assertSame(refreshed, result);
        verify(userVmMgr).validateCustomParameters(eq(dynamic), any());
    }

    @Test(expected = CloudRuntimeException.class)
    public void upgradeSystemVM_upgrade_dbUpgradeFails_throwsCloudRuntime() {
        VMInstanceVO systemVm = new VMInstanceVO();
        ServiceOfferingVO offering = Mockito.mock(ServiceOfferingVO.class);
        ServiceOfferingVO current = Mockito.mock(ServiceOfferingVO.class);

        when(vmInstanceDao.findByIdTypes(eq(5L),
                eq(VirtualMachine.Type.ConsoleProxy), eq(VirtualMachine.Type.SecondaryStorageVm))).thenReturn(systemVm);
        when(offeringDao.findById(2L)).thenReturn(offering);
        when(offeringDao.findById(5L, systemVm.getServiceOfferingId())).thenReturn(current);
        when(offering.isDynamic()).thenReturn(false);
        when(itMgr.upgradeVmDb(anyLong(), any(), any())).thenReturn(false);

        service.upgradeSystemVM(new StubUpgradeSystemVMCmd(5L, 2L, Collections.emptyMap()));
    }

    // ── patchSystemVM ─────────────────────────────────────────────────────────

    @Test(expected = InvalidParameterValueException.class)
    public void patchSystemVM_nullId_throwsInvalidParameter() {
        service.patchSystemVM(new StubPatchSystemVMCmd(null, false));
    }

    @Test(expected = InvalidParameterValueException.class)
    public void patchSystemVM_unknownId_throwsInvalidParameter() {
        when(vmInstanceDao.findByIdTypes(eq(42L),
                eq(VirtualMachine.Type.SecondaryStorageVm), eq(VirtualMachine.Type.ConsoleProxy))).thenReturn(null);

        service.patchSystemVM(new StubPatchSystemVMCmd(42L, false));
    }

    // ── updateSystemVM ────────────────────────────────────────────────────────

    @Test
    public void updateSystemVM_notRunning_returnsFalsePair() {
        VMInstanceVO vm = new VMInstanceVO();
        vm.setState(State.Stopped);

        Pair<Boolean, String> result = service.updateSystemVM(vm, false);

        assertFalse(result.first());
        assertTrue(result.second().contains("not in Running state"));
        // agentMgr.send should not have been called
        Mockito.verifyNoInteractions(agentMgr);
    }

    @Test
    public void updateSystemVM_running_callsAgentAndReturnsSuccess()
            throws AgentUnavailableException, OperationTimedoutException {
        VMInstanceVO vm = new VMInstanceVO();
        vm.setState(State.Running);
        vm.setHostId(10L);

        // stub getControlIp via nicDao / networkDao
        NicVO nic = Mockito.mock(NicVO.class);
        NetworkVO net = Mockito.mock(NetworkVO.class);
        when(nic.getNetworkId()).thenReturn(100L);
        when(networkDao.findById(100L)).thenReturn(net);
        when(net.getTrafficType()).thenReturn(Networks.TrafficType.Control);
        when(nic.getIPv4Address()).thenReturn("169.254.0.1");
        when(nicDao.listByVmId(vm.getId())).thenReturn(List.of(nic));

        PatchSystemVmAnswer answer = new PatchSystemVmAnswer(
                new PatchSystemVmCommand(), "OK", "1.0", "2.0");
        when(agentMgr.send(eq(Long.valueOf(10L)), any(Commands.class), anyInt())).thenReturn(new Answer[] { answer });

        Pair<Boolean, String> result = service.updateSystemVM(vm, false);

        assertTrue(result.first());
        assertEquals("OK", result.second());
    }

    // ── patchSystemVm (private → protected) ──────────────────────────────────

    @Test
    public void patchSystemVm_agentUnavailable_returnsFalsePair()
            throws AgentUnavailableException, OperationTimedoutException {
        VMInstanceVO vm = new VMInstanceVO();
        vm.setState(State.Running);
        vm.setHostId(10L);

        doReturn("169.254.0.1").when(service).getControlIp(vm.getId());
        when(agentMgr.send(eq(Long.valueOf(10L)), any(Commands.class), anyInt()))
                .thenThrow(new AgentUnavailableException("down", 10L));

        Pair<Boolean, String> result = service.patchSystemVm(vm, false);

        assertFalse(result.first());
        assertTrue(result.second().contains("SystemVM live patch failed"));
    }

    @Test
    public void patchSystemVm_operationTimedOut_returnsFalsePair()
            throws AgentUnavailableException, OperationTimedoutException {
        VMInstanceVO vm = new VMInstanceVO();
        vm.setState(State.Running);
        vm.setHostId(10L);

        doReturn("169.254.0.1").when(service).getControlIp(vm.getId());
        when(agentMgr.send(eq(Long.valueOf(10L)), any(Commands.class), anyInt()))
                .thenThrow(new OperationTimedoutException(null, 10L, 0L, 0, false));

        Pair<Boolean, String> result = service.patchSystemVm(vm, false);

        assertFalse(result.first());
        assertTrue(result.second().contains("SystemVM live patch failed"));
    }

    @Test
    public void patchSystemVm_domainRouterType_updatesRouterDetails()
            throws AgentUnavailableException, OperationTimedoutException {
        VMInstanceVO vm = new VMInstanceVO(1L, 1L, "r-1", "r-1-VM",
                VirtualMachine.Type.DomainRouter, null, HypervisorType.KVM, 1L, 1L, 1L, 1L, false);
        vm.setState(State.Running);
        vm.setHostId(10L);

        doReturn("169.254.0.1").when(service).getControlIp(vm.getId());

        PatchSystemVmAnswer answer = new PatchSystemVmAnswer(
                new PatchSystemVmCommand(), "details", "tmpl-v1", "script-v2");
        when(agentMgr.send(eq(Long.valueOf(10L)), any(Commands.class), anyInt())).thenReturn(new Answer[] { answer });

        DomainRouterVO router = Mockito.mock(DomainRouterVO.class);
        when(routerDao.findById(vm.getId())).thenReturn(router);
        when(routerDao.update(eq(vm.getId()), eq(router))).thenReturn(true);
        when(capabilitiesService.getVersion()).thenReturn("4.23.0.0");

        Pair<Boolean, String> result = service.patchSystemVm(vm, false);

        assertTrue(result.first());
        verify(routerDao).update(eq(vm.getId()), eq(router));
        verify(router).setScriptsVersion("script-v2");
        verify(router).setTemplateVersion("tmpl-v1");
    }

    @Test
    public void patchSystemVm_internalLbType_updatesRouterDetails()
            throws AgentUnavailableException, OperationTimedoutException {
        VMInstanceVO vm = new VMInstanceVO(2L, 1L, "lb-1", "lb-1-VM",
                VirtualMachine.Type.InternalLoadBalancerVm, null, HypervisorType.KVM, 1L, 1L, 1L, 1L, false);
        vm.setState(State.Running);
        vm.setHostId(10L);

        doReturn("169.254.0.1").when(service).getControlIp(vm.getId());

        PatchSystemVmAnswer answer = new PatchSystemVmAnswer(
                new PatchSystemVmCommand(), "details", "tmpl-v1", "script-v2");
        when(agentMgr.send(eq(Long.valueOf(10L)), any(Commands.class), anyInt())).thenReturn(new Answer[] { answer });

        DomainRouterVO router = Mockito.mock(DomainRouterVO.class);
        when(routerDao.findById(vm.getId())).thenReturn(router);
        when(routerDao.update(eq(vm.getId()), eq(router))).thenReturn(true);
        when(capabilitiesService.getVersion()).thenReturn("4.23.0.0");

        Pair<Boolean, String> result = service.patchSystemVm(vm, false);

        assertTrue(result.first());
        verify(routerDao).update(anyLong(), any());
    }

    // ── getControlIp ─────────────────────────────────────────────────────────

    @Test
    public void getControlIp_nicWithControlTraffic_returnsThatIp() {
        NicVO nic = Mockito.mock(NicVO.class);
        NetworkVO net = Mockito.mock(NetworkVO.class);
        when(nic.getNetworkId()).thenReturn(200L);
        when(networkDao.findById(200L)).thenReturn(net);
        when(net.getTrafficType()).thenReturn(Networks.TrafficType.Control);
        when(nic.getIPv4Address()).thenReturn("169.254.1.1");
        when(nicDao.listByVmId(7L)).thenReturn(List.of(nic));

        String ip = service.getControlIp(7L);
        assertEquals("169.254.1.1", ip);
    }

    @Test
    public void getControlIp_noControlNic_fallsBackToPrivateIp() {
        NicVO nic = Mockito.mock(NicVO.class);
        NetworkVO net = Mockito.mock(NetworkVO.class);
        when(nic.getNetworkId()).thenReturn(200L);
        when(networkDao.findById(200L)).thenReturn(net);
        when(net.getTrafficType()).thenReturn(Networks.TrafficType.Guest);
        when(nicDao.listByVmId(7L)).thenReturn(List.of(nic));

        VMInstanceVO vm = new VMInstanceVO();
        vm.setPrivateIpAddress("10.0.0.1");
        when(vmInstanceDao.findById(7L)).thenReturn(vm);

        String ip = service.getControlIp(7L);
        assertEquals("10.0.0.1", ip);
    }

    // ── updateRouterDetails ───────────────────────────────────────────────────

    @Test(expected = CloudRuntimeException.class)
    public void updateRouterDetails_unknownRouter_throwsCloudRuntime() {
        when(routerDao.findById(99L)).thenReturn(null);
        service.updateRouterDetails(99L, "s1", "t1");
    }

    // ── searchForSystemVm ─────────────────────────────────────────────────────

    @SuppressWarnings("unchecked")
    @Test
    public void searchForSystemVm_minimalCmd_defaultsToCpvmSsvmTypes() {
        StubListSystemVMsCmd cmd = new StubListSystemVMsCmd();

        SearchBuilder<VMInstanceVO> sb = Mockito.mock(SearchBuilder.class);
        VMInstanceVO entity = new VMInstanceVO();
        when(sb.entity()).thenReturn(entity);
        SearchCriteria<VMInstanceVO> sc = Mockito.mock(SearchCriteria.class);
        when(sb.create()).thenReturn(sc);
        when(vmInstanceDao.createSearchBuilder()).thenReturn(sb);
        when(vmInstanceDao.searchAndCount(eq(sc), any())).thenReturn(new Pair<>(Collections.emptyList(), 0));

        Pair<List<? extends VirtualMachine>, Integer> result = service.searchForSystemVm(cmd);

        assertNotNull(result);
        assertEquals(Integer.valueOf(0), result.second());
        verify(sc).setParameters(eq("nulltype"),
                eq(VirtualMachine.Type.SecondaryStorageVm), eq(VirtualMachine.Type.ConsoleProxy));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void searchForSystemVm_withStorageId_addsVolumeJoin() {
        StubListSystemVMsCmd cmd = new StubListSystemVMsCmd();
        cmd.storageId = 55L;

        StoragePoolVO pool = Mockito.mock(StoragePoolVO.class);
        when(pool.getPoolType()).thenReturn(Storage.StoragePoolType.NetworkFilesystem);
        when(primaryDataStoreDao.findById(55L)).thenReturn(pool);

        SearchBuilder<VMInstanceVO> sb = Mockito.mock(SearchBuilder.class);
        VMInstanceVO entity = new VMInstanceVO();
        when(sb.entity()).thenReturn(entity);
        SearchBuilder<VolumeVO> volSb = Mockito.mock(SearchBuilder.class);
        VolumeVO volEntity = Mockito.mock(VolumeVO.class);
        when(volSb.entity()).thenReturn(volEntity);
        when(volumeDao.createSearchBuilder()).thenReturn(volSb);
        SearchCriteria<VMInstanceVO> sc = Mockito.mock(SearchCriteria.class);
        when(sb.create()).thenReturn(sc);
        when(vmInstanceDao.createSearchBuilder()).thenReturn(sb);
        when(vmInstanceDao.searchAndCount(eq(sc), any())).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForSystemVm(cmd);

        verify(sb).join(eq("volumeSearch"), eq(volSb), any(), any(), eq(com.cloud.utils.db.JoinBuilder.JoinType.INNER));
        verify(sc).setJoinParameters(eq("volumeSearch"), eq("poolId"), eq(55L));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void searchForSystemVm_datastoreCluster_expandsToChildPools() {
        StubListSystemVMsCmd cmd = new StubListSystemVMsCmd();
        cmd.storageId = 55L;

        StoragePoolVO clusterPool = Mockito.mock(StoragePoolVO.class);
        when(clusterPool.getPoolType()).thenReturn(Storage.StoragePoolType.DatastoreCluster);
        when(primaryDataStoreDao.findById(55L)).thenReturn(clusterPool);

        StoragePoolVO child1 = Mockito.mock(StoragePoolVO.class);
        StoragePoolVO child2 = Mockito.mock(StoragePoolVO.class);
        when(child1.getId()).thenReturn(101L);
        when(child2.getId()).thenReturn(102L);
        List<StoragePoolVO> children = List.of(child1, child2);
        when(primaryDataStoreDao.listChildStoragePoolsInDatastoreCluster(55L)).thenReturn(children);

        SearchBuilder<VMInstanceVO> sb = Mockito.mock(SearchBuilder.class);
        VMInstanceVO entity = new VMInstanceVO();
        when(sb.entity()).thenReturn(entity);
        SearchBuilder<VolumeVO> volSb = Mockito.mock(SearchBuilder.class);
        VolumeVO volEntity = Mockito.mock(VolumeVO.class);
        when(volSb.entity()).thenReturn(volEntity);
        when(volumeDao.createSearchBuilder()).thenReturn(volSb);
        SearchCriteria<VMInstanceVO> sc = Mockito.mock(SearchCriteria.class);
        when(sb.create()).thenReturn(sc);
        when(vmInstanceDao.createSearchBuilder()).thenReturn(sb);
        when(vmInstanceDao.searchAndCount(eq(sc), any())).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForSystemVm(cmd);

        verify(primaryDataStoreDao).listChildStoragePoolsInDatastoreCluster(55L);
        // poolId is set with array containing 101L and 102L
        verify(sc).setJoinParameters(eq("volumeSearch"), eq("poolId"), any(Object[].class));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void searchForSystemVm_withArchFilter_addsTemplateJoin() {
        StubListSystemVMsCmd cmd = new StubListSystemVMsCmd();
        cmd.arch = CPU.CPUArch.arm64;

        SearchBuilder<VMInstanceVO> sb = Mockito.mock(SearchBuilder.class);
        VMInstanceVO entity = new VMInstanceVO();
        when(sb.entity()).thenReturn(entity);
        SearchBuilder<com.cloud.storage.VMTemplateVO> tmplSb = Mockito.mock(SearchBuilder.class);
        com.cloud.storage.VMTemplateVO tmplEntity = Mockito.mock(com.cloud.storage.VMTemplateVO.class);
        when(tmplSb.entity()).thenReturn(tmplEntity);
        when(templateDao.createSearchBuilder()).thenReturn(tmplSb);
        SearchCriteria<VMInstanceVO> sc = Mockito.mock(SearchCriteria.class);
        when(sb.create()).thenReturn(sc);
        when(vmInstanceDao.createSearchBuilder()).thenReturn(sb);
        when(vmInstanceDao.searchAndCount(eq(sc), any())).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForSystemVm(cmd);

        verify(sb).join(eq("vmTemplate"), eq(tmplSb), any(), any(), eq(com.cloud.utils.db.JoinBuilder.JoinType.INNER));
        verify(sc).setJoinParameters(eq("vmTemplate"), eq("templateArch"), eq(CPU.CPUArch.arm64));
    }

    @SuppressWarnings("unchecked")
    @Test
    public void searchForSystemVm_keywordFilter_buildsLikeOnHostNameAndState() {
        StubListSystemVMsCmd cmd = new StubListSystemVMsCmd();
        cmd.keyword = "cpvm";

        SearchBuilder<VMInstanceVO> sb = Mockito.mock(SearchBuilder.class);
        VMInstanceVO entity = new VMInstanceVO();
        when(sb.entity()).thenReturn(entity);
        SearchCriteria<VMInstanceVO> sc = Mockito.mock(SearchCriteria.class);
        SearchCriteria<VMInstanceVO> ssc = Mockito.mock(SearchCriteria.class);
        when(sb.create()).thenReturn(sc);
        when(vmInstanceDao.createSearchBuilder()).thenReturn(sb);
        when(vmInstanceDao.createSearchCriteria()).thenReturn(ssc);
        when(vmInstanceDao.searchAndCount(eq(sc), any())).thenReturn(new Pair<>(Collections.emptyList(), 0));

        service.searchForSystemVm(cmd);

        verify(ssc).addOr(eq("hostName"), eq(SearchCriteria.Op.LIKE), eq("%cpvm%"));
        verify(ssc).addOr(eq("state"), eq(SearchCriteria.Op.LIKE), eq("%cpvm%"));
        verify(sc).addAnd(eq("hostName"), eq(SearchCriteria.Op.SC), eq(ssc));
    }
}
