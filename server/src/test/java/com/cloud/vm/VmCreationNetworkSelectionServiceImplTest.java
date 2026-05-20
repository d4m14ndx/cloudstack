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
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.DataCenter;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.vpc.VpcManager;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.NetworkOffering.Availability;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.db.EntityManager;

@RunWith(MockitoJUnitRunner.class)
public class VmCreationNetworkSelectionServiceImplTest {

    private static final long ZONE_ID = 10L;
    private static final long ACCOUNT_ID = 11L;
    private static final long NETWORK_ID = 12L;
    private static final long OFFERING_ID = 13L;

    private VmCreationNetworkSelectionServiceImpl service;

    @Mock private AccountManager accountManager;
    @Mock private EntityManager entityManager;
    @Mock private NetworkModel networkModel;
    @Mock private NetworkOfferingDao networkOfferingDao;
    @Mock private NetworkDao networkDao;
    @Mock private PhysicalNetworkDao physicalNetworkDao;
    @Mock private VpcManager vpcManager;
    @Mock private VmHostNameUniquenessService vmHostNameUniquenessService;
    @Mock private DataCenter zone;
    @Mock private Account owner;
    @Mock private VirtualMachineTemplate template;
    @Mock private NetworkVO network;
    @Mock private NetworkOffering networkOffering;
    @Mock private NetworkOfferingVO requiredOffering;

    @Before
    public void setUp() {
        service = new VmCreationNetworkSelectionServiceImpl();
        ReflectionTestUtils.setField(service, "accountManager", accountManager);
        ReflectionTestUtils.setField(service, "entityManager", entityManager);
        ReflectionTestUtils.setField(service, "networkModel", networkModel);
        ReflectionTestUtils.setField(service, "networkOfferingDao", networkOfferingDao);
        ReflectionTestUtils.setField(service, "networkDao", networkDao);
        ReflectionTestUtils.setField(service, "physicalNetworkDao", physicalNetworkDao);
        ReflectionTestUtils.setField(service, "vpcManager", vpcManager);
        ReflectionTestUtils.setField(service, "vmHostNameUniquenessService", vmHostNameUniquenessService);
        when(zone.getId()).thenReturn(ZONE_ID);
        when(owner.getId()).thenReturn(ACCOUNT_ID);
    }

    @Test
    public void getDefaultNetworkReturnsOnlyIsolatedNetworkForAccount() throws Exception {
        when(networkOfferingDao.listByAvailability(Availability.Required, false)).thenReturn(List.of(requiredOffering));
        when(requiredOffering.getState()).thenReturn(NetworkOffering.State.Enabled);
        doReturn(List.of(network)).when(networkModel).listNetworksForAccount(ACCOUNT_ID, ZONE_ID, Network.GuestType.Isolated);
        when(network.getId()).thenReturn(NETWORK_ID);
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);

        NetworkVO defaultNetwork = service.getDefaultNetwork(zone, owner, false);

        assertSame(network, defaultNetwork);
    }

    @Test
    public void selectAdvancedNetworksRejectsVpcNetworkWhenTemplateHypervisorIsUnsupported() {
        when(vpcManager.getSupportedVpcHypervisors()).thenReturn(List.of(HypervisorType.KVM));
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(network.getVpcId()).thenReturn(44L);
        when(template.getFormat()).thenReturn(ImageFormat.QCOW2);
        when(template.getHypervisorType()).thenReturn(HypervisorType.LXC);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.selectAdvancedNetworks(zone, template, List.of(NETWORK_ID), owner, HypervisorType.LXC, null));

        assertEquals("Can't create Instance from Template with hypervisor LXC in VPC Network " + network, exception.getMessage());
    }

    @Test
    public void selectAdvancedNetworksRejectsSystemOnlyNetwork() {
        when(vpcManager.getSupportedVpcHypervisors()).thenReturn(List.of(HypervisorType.KVM));
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(network.getVpcId()).thenReturn(null);
        when(network.getNetworkOfferingId()).thenReturn(OFFERING_ID);
        when(network.getUuid()).thenReturn("network-uuid");
        when(entityManager.findById(NetworkOffering.class, OFFERING_ID)).thenReturn(networkOffering);
        when(networkOffering.isSystemOnly()).thenReturn(true);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.selectAdvancedNetworks(zone, template, List.of(NETWORK_ID), owner, HypervisorType.KVM, null));

        assertEquals("Network id=network-uuid is system only and can't be used for vm deployment", exception.getMessage());
        verify(networkModel).checkNetworkPermissions(owner, network);
    }

    @Test
    public void selectAdvancedNetworksVerifiesExtraDhcpOptionsForSelectedNetworks() throws Exception {
        when(vpcManager.getSupportedVpcHypervisors()).thenReturn(List.of(HypervisorType.KVM));
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        when(network.getVpcId()).thenReturn(null);
        when(network.getNetworkOfferingId()).thenReturn(OFFERING_ID);
        when(entityManager.findById(NetworkOffering.class, OFFERING_ID)).thenReturn(networkOffering);
        when(networkOffering.isSystemOnly()).thenReturn(false);

        List<NetworkVO> networks = service.selectAdvancedNetworks(zone, template, List.of(NETWORK_ID), owner, HypervisorType.KVM, null);

        assertEquals(List.of(network), networks);
        verify(networkModel).checkNetworkPermissions(owner, network);
        verify(vmHostNameUniquenessService).verifyExtraDhcpOptionsNetwork(null, networks);
    }
}
