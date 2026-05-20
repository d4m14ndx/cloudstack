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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.Before;
import org.junit.Test;

import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.element.LoadBalancingServiceProvider;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.network.guru.NetworkGuruAdditionalFunctions;
import com.cloud.vm.Nic;
import com.cloud.vm.NicVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.Type;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicSecondaryIpDao;
import com.cloud.vm.dao.NicSecondaryIpVO;

public class NicAuxiliaryServiceImplTest {

    private static final long VM_ID = 11L;
    private static final long NIC_ID = 22L;
    private static final long NETWORK_ID = 33L;
    private static final String GURU_NAME = "testGuru";

    private NicAuxiliaryServiceImpl service;
    private NicDao nicDao;
    private NicSecondaryIpDao nicSecondaryIpDao;
    private NetworkDao networkDao;
    private NetworkModel networkModel;

    @Before
    public void setUp() {
        service = new NicAuxiliaryServiceImpl();
        nicDao = mock(NicDao.class);
        nicSecondaryIpDao = mock(NicSecondaryIpDao.class);
        networkDao = mock(NetworkDao.class);
        networkModel = mock(NetworkModel.class);
        service.nicDao = nicDao;
        service.nicSecondaryIpDao = nicSecondaryIpDao;
        service.networksDao = networkDao;
        service.networkModel = networkModel;
    }

    @Test
    public void listVmNicsAddsNsxLogicalSwitchMetadataWhenProviderIsPresent() {
        NicVO nic = new NicVO(GURU_NAME, VM_ID, NETWORK_ID, Type.User);
        nic.setUuid("nic-uuid");
        NetworkVO network = new NetworkVO();
        network.setGuruName(GURU_NAME);
        NetworkGuruAdditionalFunctions guru = mock(NetworkGuruAdditionalFunctions.class,
                org.mockito.Mockito.withSettings().extraInterfaces(NetworkGuru.class));
        Map<String, Object> params = new HashMap<>();
        params.put(NetworkGuruAdditionalFunctions.NSX_LSWITCH_UUID, "logical-switch");
        params.put(NetworkGuruAdditionalFunctions.NSX_LSWITCHPORT_UUID, "logical-port");

        when(((NetworkGuru) guru).getName()).thenReturn(GURU_NAME);
        when(nicDao.listByVmId(VM_ID)).thenReturn(Collections.singletonList(nic));
        when(networkModel.isProviderForNetwork(Network.Provider.Nsx, NETWORK_ID)).thenReturn(true);
        when(networkDao.findById(NETWORK_ID)).thenReturn(network);
        org.mockito.Mockito.doReturn(params).when(guru).listAdditionalNicParams("nic-uuid");

        List<? extends Nic> result = service.listVmNics(VM_ID, null, null, null, Collections.singletonList((NetworkGuru) guru));

        assertEquals(1, result.size());
        assertSame(nic, result.get(0));
        assertEquals("logical-switch", nic.getNsxLogicalSwitchUuid());
        assertEquals("logical-port", nic.getNsxLogicalSwitchPortUuid());
    }

    @Test
    public void removeVmSecondaryIpsOfNicDeletesAllSecondaryIpRows() {
        NicSecondaryIpVO first = mock(NicSecondaryIpVO.class);
        NicSecondaryIpVO second = mock(NicSecondaryIpVO.class);
        when(first.getId()).thenReturn(101L);
        when(second.getId()).thenReturn(102L);
        when(nicSecondaryIpDao.listByNicId(NIC_ID)).thenReturn(List.of(first, second));

        assertTrue(service.removeVmSecondaryIpsOfNic(NIC_ID));

        verify(nicSecondaryIpDao).remove(101L);
        verify(nicSecondaryIpDao).remove(102L);
    }

    @Test
    public void savePlaceholderNicPersistsReservedPlaceholderNic() {
        Network network = mock(Network.class);
        when(network.getId()).thenReturn(NETWORK_ID);
        when(nicDao.persist(any(NicVO.class))).thenAnswer(invocation -> invocation.getArgument(0));

        NicVO nic = service.savePlaceholderNic(network, "10.1.1.10", "2001:db8::10",
                "2001:db8::/64", "2001:db8::1", "unit-test", Type.DomainRouter);

        assertEquals("10.1.1.10", nic.getIPv4Address());
        assertEquals("2001:db8::10", nic.getIPv6Address());
        assertEquals("2001:db8::/64", nic.getIPv6Cidr());
        assertEquals("2001:db8::1", nic.getIPv6Gateway());
        assertEquals(Nic.ReservationStrategy.PlaceHolder, nic.getReservationStrategy());
        assertEquals(Nic.State.Reserved, nic.getState());
        assertEquals(Type.DomainRouter, nic.getVmType());
        verify(nicDao).persist(nic);
    }

    @Test
    public void unmanageNicsRemovesEveryNicForStoppedVm() {
        VirtualMachineProfile vmProfile = mock(VirtualMachineProfile.class);
        VirtualMachine vm = mock(VirtualMachine.class);
        NicVO first = new NicVO(GURU_NAME, VM_ID, NETWORK_ID, Type.User);
        NicVO second = new NicVO(GURU_NAME, VM_ID, NETWORK_ID, Type.User);
        AtomicInteger removed = new AtomicInteger();

        when(vmProfile.getVirtualMachine()).thenReturn(vm);
        when(vmProfile.getId()).thenReturn(VM_ID);
        when(vm.getState()).thenReturn(VirtualMachine.State.Stopped);
        when(nicDao.listByVmId(VM_ID)).thenReturn(List.of(first, second));

        service.unmanageNics(vmProfile, (profile, nic) -> removed.incrementAndGet());

        assertEquals(2, removed.get());
    }

    @Test
    public void expungeLbVmRefsCallsOnlyLoadBalancingElements() {
        LoadBalancingServiceProvider lbProvider = mock(LoadBalancingServiceProvider.class,
                org.mockito.Mockito.withSettings().extraInterfaces(NetworkElement.class));
        NetworkElement otherElement = mock(NetworkElement.class);
        List<Long> vmIds = List.of(1L, 2L);

        service.expungeLbVmRefs(List.of((NetworkElement) lbProvider, otherElement), vmIds, 50L);

        verify(lbProvider).expungeLbVmRefs(vmIds, 50L);
    }
}
