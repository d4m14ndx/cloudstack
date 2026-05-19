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
package org.apache.cloudstack.engine.orchestration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URI;
import java.util.Arrays;
import java.util.Collections;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.SetupPersistentNetworkAnswer;
import com.cloud.agent.api.SetupPersistentNetworkCommand;
import com.cloud.agent.api.to.NicTO;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkVO;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.resource.ResourceManager;

public class PersistentNetworkSetupServiceImplTest {

    private static final long DC_ID = 11L;
    private static final long OFFERING_ID = 22L;
    private static final long CLUSTER_ID = 33L;
    private static final long FIRST_HOST_ID = 44L;
    private static final long SECOND_HOST_ID = 55L;
    private static final URI BROADCAST_URI = URI.create("vlan://101");

    private PersistentNetworkSetupServiceImpl service;
    private ClusterDao clusterDao;
    private ResourceManager resourceManager;
    private NetworkModel networkModel;
    private ConfigurationManager configurationManager;
    private AgentManager agentManager;
    private NetworkVO network;
    private NetworkOfferingVO offering;

    @Before
    public void setUp() {
        service = new PersistentNetworkSetupServiceImpl();
        clusterDao = mock(ClusterDao.class);
        resourceManager = mock(ResourceManager.class);
        networkModel = mock(NetworkModel.class);
        configurationManager = mock(ConfigurationManager.class);
        agentManager = mock(AgentManager.class);
        network = mock(NetworkVO.class);
        offering = mock(NetworkOfferingVO.class);

        service.clusterDao = clusterDao;
        service.resourceManager = resourceManager;
        service.networkModel = networkModel;
        service.configurationManager = configurationManager;
        service.agentManager = agentManager;

        when(network.getBroadcastDomainType()).thenReturn(BroadcastDomainType.Vlan);
        when(network.getBroadcastUri()).thenReturn(BROADCAST_URI);
        when(network.getDataCenterId()).thenReturn(DC_ID);
        when(network.getTrafficType()).thenReturn(TrafficType.Guest);
        when(offering.getId()).thenReturn(OFFERING_ID);
        when(clusterDao.listClustersByDcId(DC_ID)).thenReturn(Collections.singletonList(mock(ClusterVO.class)));
        when(configurationManager.getNetworkOfferingNetworkRate(OFFERING_ID, DC_ID)).thenReturn(200);
        when(networkModel.isSecurityGroupSupportedInNetwork(network)).thenReturn(true);
    }

    @Test
    public void setupPersistentNetworkSendsCommandWithNicDetails() throws AgentUnavailableException, OperationTimedoutException {
        HostVO host = host(FIRST_HOST_ID, CLUSTER_ID, HypervisorType.KVM);
        when(resourceManager.listAllUpAndEnabledHostsInOneZoneByType(Host.Type.Routing, DC_ID)).thenReturn(Collections.singletonList(host));
        when(networkModel.getNetworkTag(HypervisorType.KVM, network)).thenReturn("cloudbr-test");
        stubSuccessfulAnswer(FIRST_HOST_ID);
        ArgumentCaptor<SetupPersistentNetworkCommand> commandCaptor = ArgumentCaptor.forClass(SetupPersistentNetworkCommand.class);

        service.setupPersistentNetwork(network, offering, DC_ID);

        verify(agentManager).send(eq(FIRST_HOST_ID), commandCaptor.capture());
        NicTO nic = commandCaptor.getValue().getNic();
        assertEquals("cloudbr-test", nic.getName());
        assertEquals(BroadcastDomainType.Vlan, nic.getBroadcastType());
        assertEquals(TrafficType.Guest, nic.getType());
        assertSame(BROADCAST_URI, nic.getBroadcastUri());
        assertSame(BROADCAST_URI, nic.getIsolationUri());
        assertEquals(Integer.valueOf(200), nic.getNetworkRateMbps());
        assertTrue(nic.isSecurityGroupEnabled());
    }

    @Test
    public void setupPersistentNetworkConfiguresEveryKvmAndXenHostInSameCluster() throws AgentUnavailableException, OperationTimedoutException {
        HostVO kvmHost = host(FIRST_HOST_ID, CLUSTER_ID, HypervisorType.KVM);
        HostVO xenHost = host(SECOND_HOST_ID, CLUSTER_ID, HypervisorType.XenServer);
        when(resourceManager.listAllUpAndEnabledHostsInOneZoneByType(Host.Type.Routing, DC_ID)).thenReturn(Arrays.asList(kvmHost, xenHost));
        stubSuccessfulAnswer(FIRST_HOST_ID);
        stubSuccessfulAnswer(SECOND_HOST_ID);

        service.setupPersistentNetwork(network, offering, DC_ID);

        verify(agentManager).send(eq(FIRST_HOST_ID), any(SetupPersistentNetworkCommand.class));
        verify(agentManager).send(eq(SECOND_HOST_ID), any(SetupPersistentNetworkCommand.class));
    }

    @Test
    public void setupPersistentNetworkSkipsSecondNonKvmXenHostInSameCluster() throws AgentUnavailableException, OperationTimedoutException {
        HostVO firstHost = host(FIRST_HOST_ID, CLUSTER_ID, HypervisorType.VMware);
        HostVO secondHost = host(SECOND_HOST_ID, CLUSTER_ID, HypervisorType.VMware);
        when(resourceManager.listAllUpAndEnabledHostsInOneZoneByType(Host.Type.Routing, DC_ID)).thenReturn(Arrays.asList(firstHost, secondHost));
        stubSuccessfulAnswer(FIRST_HOST_ID);

        service.setupPersistentNetwork(network, offering, DC_ID);

        verify(agentManager).send(eq(FIRST_HOST_ID), any(SetupPersistentNetworkCommand.class));
        verify(agentManager, times(0)).send(eq(SECOND_HOST_ID), any(SetupPersistentNetworkCommand.class));
    }

    @Test
    public void setupPersistentNetworkRetriesSameClusterWhenFirstAnswerIsNull() throws AgentUnavailableException, OperationTimedoutException {
        HostVO firstHost = host(FIRST_HOST_ID, CLUSTER_ID, HypervisorType.VMware);
        HostVO secondHost = host(SECOND_HOST_ID, CLUSTER_ID, HypervisorType.VMware);
        when(resourceManager.listAllUpAndEnabledHostsInOneZoneByType(Host.Type.Routing, DC_ID)).thenReturn(Arrays.asList(firstHost, secondHost));
        when(agentManager.send(eq(FIRST_HOST_ID), any(SetupPersistentNetworkCommand.class))).thenReturn(null);
        stubSuccessfulAnswer(SECOND_HOST_ID);

        service.setupPersistentNetwork(network, offering, DC_ID);

        verify(agentManager).send(eq(FIRST_HOST_ID), any(SetupPersistentNetworkCommand.class));
        verify(agentManager).send(eq(SECOND_HOST_ID), any(SetupPersistentNetworkCommand.class));
    }

    @Test
    public void setupPersistentNetworkRetriesSameClusterWhenFirstAnswerFails() throws AgentUnavailableException, OperationTimedoutException {
        HostVO firstHost = host(FIRST_HOST_ID, CLUSTER_ID, HypervisorType.VMware);
        HostVO secondHost = host(SECOND_HOST_ID, CLUSTER_ID, HypervisorType.VMware);
        when(resourceManager.listAllUpAndEnabledHostsInOneZoneByType(Host.Type.Routing, DC_ID)).thenReturn(Arrays.asList(firstHost, secondHost));
        when(agentManager.send(eq(FIRST_HOST_ID), any(SetupPersistentNetworkCommand.class))).thenAnswer(invocation -> {
            SetupPersistentNetworkCommand command = invocation.getArgument(1);
            return new SetupPersistentNetworkAnswer(command, false, "bad");
        });
        stubSuccessfulAnswer(SECOND_HOST_ID);

        service.setupPersistentNetwork(network, offering, DC_ID);

        verify(agentManager).send(eq(FIRST_HOST_ID), any(SetupPersistentNetworkCommand.class));
        verify(agentManager).send(eq(SECOND_HOST_ID), any(SetupPersistentNetworkCommand.class));
    }

    @Test
    public void setupPersistentNetworkContinuesWhenAgentSendThrows() throws AgentUnavailableException, OperationTimedoutException {
        HostVO firstHost = host(FIRST_HOST_ID, CLUSTER_ID, HypervisorType.KVM);
        HostVO secondHost = host(SECOND_HOST_ID, 66L, HypervisorType.KVM);
        when(clusterDao.listClustersByDcId(DC_ID)).thenReturn(Arrays.asList(mock(ClusterVO.class), mock(ClusterVO.class)));
        when(resourceManager.listAllUpAndEnabledHostsInOneZoneByType(Host.Type.Routing, DC_ID)).thenReturn(Arrays.asList(firstHost, secondHost));
        when(agentManager.send(eq(FIRST_HOST_ID), any(SetupPersistentNetworkCommand.class))).thenThrow(new RuntimeException("boom"));
        stubSuccessfulAnswer(SECOND_HOST_ID);

        service.setupPersistentNetwork(network, offering, DC_ID);

        verify(agentManager).send(eq(FIRST_HOST_ID), any(SetupPersistentNetworkCommand.class));
        verify(agentManager).send(eq(SECOND_HOST_ID), any(SetupPersistentNetworkCommand.class));
    }

    private HostVO host(long id, long clusterId, HypervisorType hypervisorType) {
        HostVO host = mock(HostVO.class);
        when(host.getId()).thenReturn(id);
        when(host.getClusterId()).thenReturn(clusterId);
        when(host.getHypervisorType()).thenReturn(hypervisorType);
        return host;
    }

    private void stubSuccessfulAnswer(long hostId) throws AgentUnavailableException, OperationTimedoutException {
        when(agentManager.send(eq(hostId), any(SetupPersistentNetworkCommand.class))).thenAnswer(invocation -> {
            SetupPersistentNetworkCommand command = invocation.getArgument(1);
            return new SetupPersistentNetworkAnswer(command, true, "ok");
        });
    }
}
