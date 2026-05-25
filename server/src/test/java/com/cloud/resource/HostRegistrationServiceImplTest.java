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
package com.cloud.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.StartupCommand;
import com.cloud.agent.api.StartupRoutingCommand;
import com.cloud.agent.api.VgpuTypesInfo;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterIpAddressVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterIpAddressDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.host.dao.HostDetailsDao;
import com.cloud.host.dao.HostTagsDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.resource.ResourceStateAdapter.Event;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.net.Ip;
import com.cloud.cpu.CPU;

@RunWith(MockitoJUnitRunner.class)
public class HostRegistrationServiceImplTest {
    private static final long HOST_ID = 42L;
    private static final long DC_ID = 7L;
    private static final long POD_ID = 8L;
    private static final long CLUSTER_ID = 9L;
    private static final long NODE_ID = 123L;

    @Mock private AgentManager agentManager;
    @Mock private HostDao hostDao;
    @Mock private HostDetailsDao hostDetailsDao;
    @Mock private HostTagsDao hostTagsDao;
    @Mock private HostPodDao podDao;
    @Mock private DataCenterDao dcDao;
    @Mock private ClusterDao clusterDao;
    @Mock private ClusterDetailsDao clusterDetailsDao;
    @Mock private DataCenterIpAddressDao privateIpAddressDao;
    @Mock private IPAddressDao publicIpAddressDao;
    @Mock private HostGpuService hostGpuService;
    @Mock private HostQueryService hostQueryService;
    @Mock private HostLookupService hostLookupService;
    @Mock private HostRegistrationCallbacks callbacks;
    @Mock private ServerResource resource;

    private HostRegistrationServiceImpl service;

    @Before
    public void setUp() {
        service = new HostRegistrationServiceImpl();
        service.agentManager = agentManager;
        service.hostDao = hostDao;
        service.hostDetailsDao = hostDetailsDao;
        service.hostTagsDao = hostTagsDao;
        service.podDao = podDao;
        service.dcDao = dcDao;
        service.clusterDao = clusterDao;
        service.clusterDetailsDao = clusterDetailsDao;
        service.privateIpAddressDao = privateIpAddressDao;
        service.publicIpAddressDao = publicIpAddressDao;
        service.hostGpuService = hostGpuService;
        service.hostQueryService = hostQueryService;
        service.hostLookupService = hostLookupService;
        service.callbacks = callbacks;

        when(callbacks.getManagementServerNodeId()).thenReturn(NODE_ID);
        when(agentManager.agentStatusTransitTo(any(HostVO.class), any(Status.Event.class), anyLong())).thenReturn(true);
    }

    @Test
    public void checkCIDR_returnsWhenPrivateIpMissing() {
        HostPodVO pod = pod("pod-1", DC_ID, "10.1.1.0", 24);
        DataCenterVO dc = zone(DC_ID, "zone-1");

        service.checkCIDR(pod, dc, null, "255.255.255.0");
    }

    @Test
    public void checkCIDR_throwsWhenSubnetDoesNotMatchPod() {
        HostPodVO pod = pod("pod-1", DC_ID, "10.1.1.0", 24);
        DataCenterVO dc = zone(DC_ID, "zone-1");

        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class,
                () -> service.checkCIDR(pod, dc, "10.1.2.10", "255.255.255.0"));

        assertTrue(ex.getMessage().contains("not compatible"));
    }

    @Test
    public void checkCIDR_throwsWhenServerNetmaskIsNarrowerThanPod() {
        HostPodVO pod = pod("pod-1", DC_ID, "10.1.1.0", 24);
        DataCenterVO dc = zone(DC_ID, "zone-1");

        assertThrows(IllegalArgumentException.class,
                () -> service.checkCIDR(pod, dc, "10.1.1.10", "255.255.255.128"));
    }

    @Test
    public void createHostVOForConnectedAgent_resolvesDefaultZoneAndPodByCidr() {
        StartupCommand startup = startup("guid-1");
        startup.setDataCenter("default");
        startup.setPod("default");
        startup.setPrivateIpAddress("10.20.30.40");
        startup.setPrivateNetmask("255.255.255.0");
        HostPodVO matchingPod = pod("pod-a", DC_ID, "10.20.30.0", 24);
        when(hostQueryService.findHostByGuid(startup.getGuid())).thenReturn(null);
        when(hostQueryService.findHostByGuidPrefix(startup.getGuid())).thenReturn(null);
        when(podDao.listAllIncludingRemoved()).thenReturn(Collections.singletonList(matchingPod));
        when(dcDao.findById(DC_ID)).thenReturn(zone(DC_ID, "zone-a"));
        when(dcDao.findByName("zone-a")).thenReturn(zone(DC_ID, "zone-a"));
        when(podDao.findByName("pod-a", DC_ID)).thenReturn(matchingPod);
        when(callbacks.dispatchCreateHostVo(eq(Event.CREATE_HOST_VO_FOR_CONNECTED), any(HostVO.class), any(StartupCommand[].class),
                eq(null), eq(null), eq(null))).thenAnswer(invocation -> {
                    HostVO host = invocation.getArgument(1);
                    ReflectionTestUtils.setField(host, "id", HOST_ID);
                    return host;
                });
        when(hostDao.persist(any(HostVO.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HostVO result = service.createHostVOForConnectedAgent(new StartupCommand[] {startup});

        assertEquals(Long.valueOf(DC_ID), Long.valueOf(result.getDataCenterId()));
        assertEquals(Long.valueOf(POD_ID), result.getPodId());
    }

    @Test
    public void createHostVOForConnectedAgent_throwsWhenZoneUnknown() {
        StartupCommand startup = startup("guid-2");
        startup.setDataCenter("missing-zone");
        startup.setPod("pod-1");

        when(hostQueryService.findHostByGuid(startup.getGuid())).thenReturn(null);
        when(hostQueryService.findHostByGuidPrefix(startup.getGuid())).thenReturn(null);
        when(dcDao.findByName("missing-zone")).thenReturn(null);

        assertThrows(IllegalArgumentException.class,
                () -> service.createHostVOForConnectedAgent(new StartupCommand[] {startup}));
    }

    @Test
    public void createHostVOForConnectedAgent_supportsNumericPodFallback() {
        StartupCommand startup = startup("guid-3");
        startup.setDataCenter("zone-a");
        startup.setPod(String.valueOf(POD_ID));

        when(hostQueryService.findHostByGuid(startup.getGuid())).thenReturn(null);
        when(hostQueryService.findHostByGuidPrefix(startup.getGuid())).thenReturn(null);
        when(dcDao.findByName("zone-a")).thenReturn(zone(DC_ID, "zone-a"));
        when(podDao.findByName(String.valueOf(POD_ID), DC_ID)).thenReturn(null);
        when(podDao.findById(POD_ID)).thenReturn(pod("pod-b", DC_ID, "10.2.2.0", 24));
        when(callbacks.dispatchCreateHostVo(eq(Event.CREATE_HOST_VO_FOR_CONNECTED), any(HostVO.class), any(StartupCommand[].class),
                eq(null), eq(null), eq(null))).thenAnswer(invocation -> invocation.getArgument(1));
        when(hostDao.persist(any(HostVO.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HostVO result = service.createHostVOForConnectedAgent(new StartupCommand[] {startup});

        assertEquals(Long.valueOf(POD_ID), result.getPodId());
    }

    @Test
    public void createHostVOForConnectedAgent_createsMissingNamedCluster() {
        StartupCommand startup = startup("guid-4");
        startup.setDataCenter("zone-a");
        startup.setPod("pod-a");
        startup.setCluster("cluster-a");
        HostPodVO pod = pod("pod-a", DC_ID, "10.2.2.0", 24);
        ClusterVO persistedCluster = new ClusterVO(DC_ID, POD_ID, "cluster-a");
        ReflectionTestUtils.setField(persistedCluster, "id", CLUSTER_ID);

        when(hostQueryService.findHostByGuid(startup.getGuid())).thenReturn(null);
        when(hostQueryService.findHostByGuidPrefix(startup.getGuid())).thenReturn(null);
        when(dcDao.findByName("zone-a")).thenReturn(zone(DC_ID, "zone-a"));
        when(podDao.findByName("pod-a", DC_ID)).thenReturn(pod);
        when(clusterDao.findBy("cluster-a", POD_ID)).thenReturn(null);
        when(clusterDao.persist(any(ClusterVO.class))).thenReturn(persistedCluster);
        when(callbacks.dispatchCreateHostVo(eq(Event.CREATE_HOST_VO_FOR_CONNECTED), any(HostVO.class), any(StartupCommand[].class),
                eq(null), eq(null), eq(null))).thenAnswer(invocation -> invocation.getArgument(1));
        when(hostDao.persist(any(HostVO.class))).thenAnswer(invocation -> invocation.getArgument(0));

        HostVO result = service.createHostVOForConnectedAgent(new StartupCommand[] {startup});

        assertEquals(Long.valueOf(CLUSTER_ID), result.getClusterId());
    }

    @Test
    public void createHostVOForConnectedAgent_throwsWhenCallbackReturnsNull() {
        StartupCommand startup = startup("guid-5");
        startup.setDataCenter("zone-a");
        startup.setPod("pod-a");

        when(hostQueryService.findHostByGuid(startup.getGuid())).thenReturn(null);
        when(hostQueryService.findHostByGuidPrefix(startup.getGuid())).thenReturn(null);
        when(dcDao.findByName("zone-a")).thenReturn(zone(DC_ID, "zone-a"));
        when(podDao.findByName("pod-a", DC_ID)).thenReturn(pod("pod-a", DC_ID, "10.2.2.0", 24));
        when(callbacks.dispatchCreateHostVo(eq(Event.CREATE_HOST_VO_FOR_CONNECTED), any(HostVO.class), any(StartupCommand[].class),
                eq(null), eq(null), eq(null))).thenReturn(null);

        assertThrows(CloudRuntimeException.class,
                () -> service.createHostVOForConnectedAgent(new StartupCommand[] {startup}));
    }

    @Test
    public void createHostVOForConnectedAgent_setsManagementServerIdAndPersistsNewHost() {
        StartupCommand startup = startup("guid-6");
        startup.setDataCenter("zone-a");
        startup.setPod("pod-a");
        HostVO adaptedHost = new HostVO(startup.getGuid());

        when(hostQueryService.findHostByGuid(startup.getGuid())).thenReturn(null);
        when(hostQueryService.findHostByGuidPrefix(startup.getGuid())).thenReturn(null);
        when(dcDao.findByName("zone-a")).thenReturn(zone(DC_ID, "zone-a"));
        when(podDao.findByName("pod-a", DC_ID)).thenReturn(pod("pod-a", DC_ID, "10.2.2.0", 24));
        when(callbacks.dispatchCreateHostVo(eq(Event.CREATE_HOST_VO_FOR_CONNECTED), any(HostVO.class), any(StartupCommand[].class),
                eq(null), eq(null), eq(null))).thenAnswer(invocation -> invocation.getArgument(1));
        when(hostDao.persist(any(HostVO.class))).thenAnswer(invocation -> {
            HostVO persisted = invocation.getArgument(0);
            ReflectionTestUtils.setField(persisted, "id", HOST_ID);
            return persisted;
        });

        HostVO result = service.createHostVOForConnectedAgent(new StartupCommand[] {startup});

        assertEquals(Long.valueOf(NODE_ID), Long.valueOf(result.getManagementServerId()));
        verify(hostDao).persist(result);
    }

    @Test
    public void createHostVOForConnectedAgent_updatesGpuGroupsForNewRoutingHost() {
        StartupRoutingCommand startup = routingStartup("guid-7");
        List<VgpuTypesInfo> gpuDevices = Collections.singletonList(mock(VgpuTypesInfo.class));
        startup.setGpuDevices(gpuDevices);
        startup.setGpuGroupDetails(new HashMap<>());

        HostVO adaptedHost = seededHost();
        adaptedHost.setGuid(startup.getGuid());
        HashMap<String, HashMap<String, VgpuTypesInfo>> gpuGroups = new HashMap<>();

        when(hostQueryService.findHostByGuid(startup.getGuid())).thenReturn(null);
        when(hostQueryService.findHostByGuidPrefix(startup.getGuid())).thenReturn(null);
        when(dcDao.findByName("zone-a")).thenReturn(zone(DC_ID, "zone-a"));
        when(podDao.findByName("pod-a", DC_ID)).thenReturn(pod("pod-a", DC_ID, "10.2.2.0", 24));
        when(callbacks.dispatchCreateHostVo(eq(Event.CREATE_HOST_VO_FOR_CONNECTED), any(HostVO.class), any(StartupCommand[].class),
                eq(null), eq(null), eq(null))).thenAnswer(invocation -> adaptedHost);
        when(hostDao.persist(adaptedHost)).thenReturn(adaptedHost);
        when(hostDao.listIdsByClusterId(CLUSTER_ID)).thenReturn(Collections.singletonList(HOST_ID));
        when(hostDetailsDao.findDetail(HOST_ID, "supportsResign")).thenReturn(new com.cloud.host.DetailVO(HOST_ID, "supportsResign", Boolean.TRUE.toString()));
        when(hostGpuService.getGroupDetails(adaptedHost, gpuDevices, startup.getGpuGroupDetails())).thenReturn(gpuGroups);

        HostVO result = service.createHostVOForConnectedAgent(new StartupCommand[] {startup});

        assertSame(adaptedHost, result);
        verify(hostGpuService).getGroupDetails(adaptedHost, gpuDevices, startup.getGpuGroupDetails());
        verify(hostDao).update(eq(adaptedHost.getId()), eq(adaptedHost));
    }

    @Test
    public void createHostAndAgent_returnsNullForOldHostThatAlreadyExists() throws Exception {
        StartupCommand startup = startup("guid-8");
        HostVO existingHost = new HostVO(startup.getGuid());

        when(resource.initialize(false)).thenReturn(new StartupCommand[] {startup});
        when(hostQueryService.findHostByGuid(startup.getGuid())).thenReturn(existingHost);
        Host result = service.createHostAndAgent(null, resource, new HashMap<>(), true, Collections.emptyList(), false, false);

        assertNull(result);
        verify(resource).disconnected();
        verify(agentManager, never()).handleDirectConnectAgent(any(Host.class), any(StartupCommand[].class), eq(resource), anyBoolean(), anyBoolean());
    }

    @Test
    public void createHostAndAgent_returnsReloadedHostAfterSuccessfulDirectConnect() throws Exception {
        StartupCommand startup = startup("guid-9");
        HostVO createdHost = new HostVO(startup.getGuid());
        ReflectionTestUtils.setField(createdHost, "id", HOST_ID);
        HostVO reloadedHost = new HostVO(startup.getGuid());
        ReflectionTestUtils.setField(reloadedHost, "id", HOST_ID);

        when(resource.initialize(false)).thenReturn(new StartupCommand[] {startup});
        when(hostQueryService.findHostByGuid(startup.getGuid())).thenReturn(null, null, createdHost);
        when(hostQueryService.findHostByGuidPrefix(startup.getGuid())).thenReturn(null, null);
        when(dcDao.findByName("zone-a")).thenReturn(zone(DC_ID, "zone-a"));
        when(podDao.findByName("pod-a", DC_ID)).thenReturn(pod("pod-a", DC_ID, "10.2.2.0", 24));
        when(callbacks.dispatchCreateHostVo(eq(Event.CREATE_HOST_VO_FOR_DIRECT_CONNECT), any(HostVO.class), any(StartupCommand[].class),
                eq(resource), any(Map.class), any(List.class))).thenAnswer(invocation -> invocation.getArgument(1));
        when(hostDao.persist(any(HostVO.class))).thenReturn(createdHost);
        when(agentManager.handleDirectConnectAgent(eq(createdHost), any(StartupCommand[].class), eq(resource), eq(false), eq(true))).thenReturn(true);
        when(hostDao.findById(HOST_ID)).thenReturn(reloadedHost);

        Host result = service.createHostAndAgent(null, resource, new HashMap<>(), false, Collections.emptyList(), false, false);

        assertSame(reloadedHost, result);
    }

    @Test
    public void createHostAndAgent_disconnectsAndMarksHostWhenDirectConnectFails() throws Exception {
        StartupCommand startup = startup("guid-10");
        HostVO createdHost = new HostVO(startup.getGuid());
        ReflectionTestUtils.setField(createdHost, "id", HOST_ID);

        when(resource.initialize(false)).thenReturn(new StartupCommand[] {startup});
        when(hostQueryService.findHostByGuid(startup.getGuid())).thenReturn(null, null);
        when(hostQueryService.findHostByGuidPrefix(startup.getGuid())).thenReturn(null, null);
        when(dcDao.findByName("zone-a")).thenReturn(zone(DC_ID, "zone-a"));
        when(podDao.findByName("pod-a", DC_ID)).thenReturn(pod("pod-a", DC_ID, "10.2.2.0", 24));
        when(callbacks.dispatchCreateHostVo(eq(Event.CREATE_HOST_VO_FOR_DIRECT_CONNECT), any(HostVO.class), any(StartupCommand[].class),
                eq(resource), any(Map.class), any(List.class))).thenAnswer(invocation -> invocation.getArgument(1));
        when(hostDao.persist(any(HostVO.class))).thenReturn(createdHost);
        when(agentManager.handleDirectConnectAgent(eq(createdHost), any(StartupCommand[].class), eq(resource), eq(false), eq(true))).thenReturn(false);
        when(hostDao.findById(HOST_ID)).thenReturn(createdHost);

        Host result = service.createHostAndAgent(null, resource, new HashMap<>(), false, Collections.emptyList(), false, false);

        assertSame(createdHost, result);
        verify(resource).disconnected();
        verify(agentManager).agentStatusTransitTo(createdHost, Status.Event.AgentDisconnected, NODE_ID);
    }

    @Test
    public void addHost_throwsWhenZoneMissing() {
        when(dcDao.findById(DC_ID)).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.addHost(DC_ID, resource, Host.Type.Routing, Collections.singletonMap("guid", "guid-1")));
    }

    @Test
    public void addHost_returnsExistingZoneHostWithMatchingGuid() {
        HostVO existingHost = new HostVO("guid-existing");
        ReflectionTestUtils.setField(existingHost, "id", HOST_ID);
        List<HostVO> existingHosts = Collections.singletonList(existingHost);
        Map<String, String> details = Collections.singletonMap("guid", "guid-existing");

        when(dcDao.findById(DC_ID)).thenReturn(zone(DC_ID, "zone-a"));
        when(hostLookupService.listAllUpAndEnabledHostsInOneZoneByType(Host.Type.Routing, DC_ID)).thenReturn(existingHosts);

        Host result = service.addHost(DC_ID, resource, Host.Type.Routing, details);

        assertSame(existingHost, result);
    }

    @Test
    public void fillRoutingHostVO_throwsWhenPodIdMissing() {
        HostVO host = new HostVO("guid-11");
        StartupRoutingCommand startup = routingStartup("guid-11");

        assertThrows(IllegalArgumentException.class,
                () -> service.fillRoutingHostVO(host, startup, HypervisorType.KVM, new HashMap<>(), Collections.emptyList()));
    }

    @Test
    public void fillRoutingHostVO_throwsWhenHypervisorTypeDiffersFromCluster() {
        HostVO host = seededHost();
        StartupRoutingCommand startup = routingStartup("guid-12");
        ClusterVO cluster = new ClusterVO(DC_ID, POD_ID, "cluster-a");
        cluster.setHypervisorType(HypervisorType.XenServer.toString());

        when(clusterDao.findById(CLUSTER_ID)).thenReturn(cluster);

        assertThrows(IllegalArgumentException.class,
                () -> service.fillRoutingHostVO(host, startup, HypervisorType.KVM, new HashMap<>(), Collections.emptyList()));
    }

    @Test
    public void fillRoutingHostVO_throwsWhenCpuArchDiffersFromCluster() {
        HostVO host = seededHost();
        StartupRoutingCommand startup = routingStartup("guid-13");
        startup.setCpuArch(CPU.CPUArch.amd64.getType());
        ClusterVO cluster = new ClusterVO(DC_ID, POD_ID, "cluster-a");
        cluster.setHypervisorType(HypervisorType.KVM.toString());
        cluster.setArch(CPU.CPUArch.arm64.getType());

        when(clusterDao.findById(CLUSTER_ID)).thenReturn(cluster);

        assertThrows(IllegalArgumentException.class,
                () -> service.fillRoutingHostVO(host, startup, HypervisorType.KVM, new HashMap<>(), Collections.emptyList()));
    }

    @Test
    public void fillRoutingHostVO_mergesDetailsPopulatesRoutingFieldsAndChecksIps() {
        HostVO host = seededHost();
        StartupRoutingCommand startup = routingStartup("guid-14");
        ClusterVO cluster = new ClusterVO(DC_ID, POD_ID, "cluster-a");
        cluster.setHypervisorType(HypervisorType.KVM.toString());
        cluster.setArch(CPU.CPUArch.amd64.getType());
        HashMap<String, String> details = new HashMap<>();
        details.put("existing", "value");
        startup.getHostDetails().put("new-detail", "new-value");
        startup.setCaps("cap-1");
        startup.setCpuSockets(2);
        startup.setCpus(16);
        startup.setCpuArch(CPU.CPUArch.amd64.getType());
        startup.setMemory(1024L);
        startup.setSpeed(2300L);
        startup.setHypervisorVersion("8.2");
        List<VgpuTypesInfo> gpuDevices = Collections.singletonList(mock(VgpuTypesInfo.class));
        startup.setGpuDevices(gpuDevices);

        when(clusterDao.findById(CLUSTER_ID)).thenReturn(cluster);
        when(podDao.findById(POD_ID)).thenReturn(pod("pod-a", DC_ID, "10.2.2.0", 24));
        when(dcDao.findById(DC_ID)).thenReturn(zone(DC_ID, "zone-a"));
        when(privateIpAddressDao.mark(DC_ID, POD_ID, startup.getPrivateIpAddress())).thenReturn(true);
        when(publicIpAddressDao.mark(DC_ID, new Ip(startup.getPublicIpAddress()))).thenReturn(true);
        when(hostGpuService.getGroupDetails(host, gpuDevices, startup.getGpuGroupDetails())).thenReturn(new HashMap<>());

        HostVO result = service.fillRoutingHostVO(host, startup, HypervisorType.KVM, details, Collections.emptyList());

        assertSame(host, result);
        assertEquals("new-value", details.get("new-detail"));
        assertEquals(Host.Type.Routing, host.getType());
        assertEquals(CPU.CPUArch.amd64, host.getArch());
        assertEquals(HypervisorType.KVM, host.getHypervisorType());
    }

    @Test
    @SuppressWarnings("unchecked")
    public void createHostAndAgentForDiscovery_defersWhenClusterAlreadyHasAnotherHost() throws Exception {
        StartupCommand startup = startup("guid-15");
        startup.setCluster(String.valueOf(CLUSTER_ID));
        HostVO createdHost = seededHost();
        createdHost.setGuid(startup.getGuid());
        createdHost.setClusterId(CLUSTER_ID);
        SearchBuilder<HostVO> searchBuilder = mock(SearchBuilder.class);
        SearchCriteria<HostVO> criteria = mock(SearchCriteria.class);
        GlobalLock addHostLock = mock(GlobalLock.class);

        when(resource.initialize()).thenReturn(new StartupCommand[] {startup});
        when(hostQueryService.findHostByGuid(startup.getGuid())).thenReturn(null, null);
        when(hostQueryService.findHostByGuidPrefix(startup.getGuid())).thenReturn(null, null);
        when(dcDao.findByName("zone-a")).thenReturn(zone(DC_ID, "zone-a"));
        when(podDao.findByName("pod-a", DC_ID)).thenReturn(pod("pod-a", DC_ID, "10.2.2.0", 24));
        when(callbacks.dispatchCreateHostVo(eq(Event.CREATE_HOST_VO_FOR_DIRECT_CONNECT), any(HostVO.class), any(StartupCommand[].class),
                eq(resource), any(Map.class), any(List.class))).thenReturn(createdHost);
        when(hostDao.persist(createdHost)).thenReturn(createdHost);
        when(hostDao.update(anyLong(), any(HostVO.class))).thenReturn(true);
        when(hostDao.createSearchBuilder()).thenReturn(searchBuilder);
        when(searchBuilder.entity()).thenReturn(createdHost);
        when(searchBuilder.create()).thenReturn(criteria);
        when(hostDao.search(criteria, null)).thenReturn(Arrays.asList(createdHost, seededHost()));
        when(hostDao.findById(HOST_ID)).thenReturn(createdHost);
        when(addHostLock.lock(anyInt())).thenReturn(true);

        Host result;
        try (MockedStatic<GlobalLock> globalLockMock = Mockito.mockStatic(GlobalLock.class)) {
            globalLockMock.when(() -> GlobalLock.getInternLock("AddHostLock")).thenReturn(addHostLock);
            result = service.createHostAndAgentForDiscovery(resource, new HashMap<>(), Collections.emptyList(), Collections.emptyList());
        }

        assertSame(createdHost, result);
        verify(agentManager, never()).handleDirectConnectAgent(any(Host.class), any(StartupCommand[].class), eq(resource), anyBoolean(), anyBoolean());
        verify(agentManager).agentStatusTransitTo(createdHost, Status.Event.AgentDisconnected, NODE_ID);
        assertEquals(0L, createdHost.getLastPinged());
        verify(addHostLock).unlock();
        verify(addHostLock).releaseRef();
    }

    @Test
    public void fillRoutingHostVO_throwsWhenPrivateOrPublicIpAlreadyInUse() {
        HostVO host = seededHost();
        StartupRoutingCommand startup = routingStartup("guid-16");
        ClusterVO cluster = new ClusterVO(DC_ID, POD_ID, "cluster-a");
        cluster.setHypervisorType(HypervisorType.KVM.toString());
        cluster.setArch(CPU.CPUArch.amd64.getType());

        when(clusterDao.findById(CLUSTER_ID)).thenReturn(cluster);
        when(podDao.findById(POD_ID)).thenReturn(pod("pod-a", DC_ID, "10.2.2.0", 24));
        when(dcDao.findById(DC_ID)).thenReturn(zone(DC_ID, "zone-a"));
        when(privateIpAddressDao.mark(DC_ID, POD_ID, startup.getPrivateIpAddress())).thenReturn(false);
        DataCenterIpAddressVO privateIp = new DataCenterIpAddressVO(startup.getPrivateIpAddress(), DC_ID, POD_ID);
        privateIp.setNicId(88L);
        when(privateIpAddressDao.listByPodIdDcIdIpAddress(POD_ID, DC_ID, startup.getPrivateIpAddress()))
                .thenReturn(Collections.singletonList(privateIp));

        assertThrows(IllegalArgumentException.class,
                () -> service.fillRoutingHostVO(host, startup, HypervisorType.KVM, new HashMap<>(), Collections.emptyList()));
    }

    private HostVO seededHost() {
        HostVO host = new HostVO("seed-host");
        ReflectionTestUtils.setField(host, "id", HOST_ID);
        host.setClusterId(CLUSTER_ID);
        host.setDataCenterId(DC_ID);
        host.setPodId(POD_ID);
        return host;
    }

    private StartupCommand startup(String guid) {
        StartupCommand startup = new StartupCommand(Host.Type.Routing);
        startup.setGuid(guid);
        startup.setName("host-" + guid);
        startup.setVersion("4.0");
        startup.setDataCenter("zone-a");
        startup.setPod("pod-a");
        startup.setPrivateIpAddress("10.2.2.10");
        startup.setPrivateNetmask("255.255.255.0");
        startup.setPrivateMacAddress("00:11:22:33:44:55");
        startup.setPublicIpAddress("172.16.1.20");
        startup.setPublicNetmask("255.255.255.0");
        startup.setPublicMacAddress("00:11:22:33:44:66");
        startup.setStorageIpAddress("192.168.10.20");
        startup.setStorageNetmask("255.255.255.0");
        startup.setStorageMacAddress("00:11:22:33:44:77");
        startup.setArch(CPU.CPUArch.amd64.getType());
        return startup;
    }

    private StartupRoutingCommand routingStartup(String guid) {
        StartupRoutingCommand startup = new StartupRoutingCommand();
        startup.setGuid(guid);
        startup.setName("routing-" + guid);
        startup.setVersion("4.0");
        startup.setDataCenter("zone-a");
        startup.setPod("pod-a");
        startup.setCluster(String.valueOf(CLUSTER_ID));
        startup.setPrivateIpAddress("10.2.2.10");
        startup.setPrivateNetmask("255.255.255.0");
        startup.setPrivateMacAddress("00:11:22:33:44:55");
        startup.setPublicIpAddress("172.16.1.20");
        startup.setPublicNetmask("255.255.255.0");
        startup.setPublicMacAddress("00:11:22:33:44:66");
        startup.setStorageIpAddress("192.168.10.20");
        startup.setStorageNetmask("255.255.255.0");
        startup.setStorageMacAddress("00:11:22:33:44:77");
        startup.setArch(CPU.CPUArch.amd64.getType());
        return startup;
    }

    private HostPodVO pod(String name, long dcId, String cidr, int cidrSize) {
        HostPodVO pod = new HostPodVO(name, dcId, "10.2.2.1", cidr, cidrSize, "pod");
        ReflectionTestUtils.setField(pod, "id", POD_ID);
        return pod;
    }

    private DataCenterVO zone(long id, String name) {
        return new DataCenterVO(id, name, "desc", "8.8.8.8", "8.8.4.4", "10.0.0.1", "10.0.0.2",
                "10.2.2.0/24", "local", 1L, DataCenterVO.NetworkType.Advanced, null, "internal");
    }
}
