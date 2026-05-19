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
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.api.to.VirtualMachineTO;
import com.cloud.api.query.dao.DomainRouterJoinDao;
import com.cloud.api.query.dao.UserVmJoinDao;
import com.cloud.api.query.vo.DomainRouterJoinVO;
import com.cloud.api.query.vo.UserVmJoinVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Networks;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.user.AccountVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class VmNetworkNameMappingServiceImplTest {

    @InjectMocks
    private VmNetworkNameMappingServiceImpl service;

    @Mock
    private UserVmJoinDao userVmJoinDao;
    @Mock
    private DomainRouterJoinDao domainRouterJoinDao;
    @Mock
    private NetworkDao networkDao;
    @Mock
    private AccountDao accountDao;
    @Mock
    private DomainDao domainDao;
    @Mock
    private DataCenterDao dataCenterDao;
    @Mock
    private VpcDao vpcDao;

    private VMInstanceVO vm;

    @Before
    public void setUp() {
        vm = new VMInstanceVO(1L, 1L, "VM1", "i-2-2-VM",
                VirtualMachine.Type.User, 1L, HypervisorType.KVM, 1L, 1L, 1L,
                1L, false, false);
        ReflectionTestUtils.setField(vm, "dataCenterId", 1L);
    }

    @Test
    public void userVmWithNoJoinRowsDoesNotSetNetworkNameMap() {
        VirtualMachineTO vmTO = mock(VirtualMachineTO.class);
        when(userVmJoinDao.searchByIds(anyLong())).thenReturn(List.of());

        service.setVmNetworkDetails(vm, vmTO);

        verify(vmTO, never()).setNetworkIdToNetworkNameMap(org.mockito.ArgumentMatchers.anyMap());
    }

    @Test
    public void userVmNetworkWithoutVpcMapsToDomainAccountZoneAndNetwork() {
        VirtualMachineTO vmTO = new VirtualMachineTO() {
        };
        UserVmJoinVO userVmJoin = userVmJoin(5L);
        when(userVmJoinDao.searchByIds(anyLong())).thenReturn(List.of(userVmJoin));
        stubNetworkNameInputs(network(5L, 2L, 3L, null), account(2L), domain(3L), zone(1L));

        service.setVmNetworkDetails(vm, vmTO);

        assertEquals(1, vmTO.getNetworkIdToNetworkNameMap().size());
        assertEquals("D3-A2-Z1-S5", vmTO.getNetworkIdToNetworkNameMap().get(5L));
    }

    @Test
    public void userVmNetworkWithVpcMapsToDomainAccountZoneVpcAndNetwork() {
        VirtualMachineTO vmTO = new VirtualMachineTO() {
        };
        UserVmJoinVO userVmJoin = userVmJoin(5L);
        when(userVmJoinDao.searchByIds(anyLong())).thenReturn(List.of(userVmJoin));
        stubNetworkNameInputs(network(5L, 2L, 3L, 4L), account(2L), domain(3L), zone(1L));
        VpcVO vpc = vpc(4L);
        when(vpcDao.findById(4L)).thenReturn(vpc);

        service.setVmNetworkDetails(vm, vmTO);

        assertEquals(1, vmTO.getNetworkIdToNetworkNameMap().size());
        assertEquals("D3-A2-Z1-V4-S5", vmTO.getNetworkIdToNetworkNameMap().get(5L));
    }

    @Test
    public void domainRouterMapsOnlyGuestNetworksWithoutVpcAndNxsBroadcastDomain() {
        VMInstanceVO routerVm = new VMInstanceVO(1L, 1L, "Router", "r-1-VM",
                VirtualMachine.Type.DomainRouter, 1L, HypervisorType.KVM, 1L, 1L, 1L,
                1L, false, false);
        ReflectionTestUtils.setField(routerVm, "dataCenterId", 1L);
        VirtualMachineTO vmTO = new VirtualMachineTO() {
        };
        DomainRouterJoinVO mappedJoin = routerJoin(5L);
        DomainRouterJoinVO vpcJoin = routerJoin(6L);
        DomainRouterJoinVO vlanJoin = routerJoin(7L);
        when(domainRouterJoinDao.getRouterByIdAndTrafficType(1L, Networks.TrafficType.Guest)).thenReturn(List.of(
                mappedJoin, vpcJoin, vlanJoin));
        NetworkVO mappedNetwork = network(5L, 2L, 3L, null, Networks.BroadcastDomainType.NSX);
        NetworkVO vpcNetwork = network(6L, 2L, 3L, 4L, Networks.BroadcastDomainType.NSX);
        NetworkVO vlanNetwork = network(7L, 2L, 3L, null, Networks.BroadcastDomainType.Vlan);
        when(networkDao.findById(5L)).thenReturn(mappedNetwork);
        when(networkDao.findById(6L)).thenReturn(vpcNetwork);
        when(networkDao.findById(7L)).thenReturn(vlanNetwork);
        AccountVO account = account(2L);
        DomainVO domain = domain(3L);
        DataCenterVO zone = zone(1L);
        when(accountDao.findById(2L)).thenReturn(account);
        when(domainDao.findById(3L)).thenReturn(domain);
        when(dataCenterDao.findById(1L)).thenReturn(zone);

        service.setVmNetworkDetails(routerVm, vmTO);

        assertEquals(1, vmTO.getNetworkIdToNetworkNameMap().size());
        assertEquals("D3-A2-Z1-S5", vmTO.getNetworkIdToNetworkNameMap().get(5L));
    }

    @Test
    public void missingZoneThrowsExistingMessage() {
        UserVmJoinVO userVmJoin = userVmJoin(5L);
        when(userVmJoinDao.searchByIds(anyLong())).thenReturn(List.of(userVmJoin));
        stubNetworkNameInputs(network(5L, 2L, 3L, null), account(2L), domain(3L), null);

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class, () -> service.setVmNetworkDetails(vm, new VirtualMachineTO() {
        }));

        assertEquals("Failed to find zone with ID: 1", exception.getMessage());
    }

    @Test
    public void missingAccountThrowsExistingMessage() {
        UserVmJoinVO userVmJoin = userVmJoin(5L);
        when(userVmJoinDao.searchByIds(anyLong())).thenReturn(List.of(userVmJoin));
        stubNetworkNameInputs(network(5L, 2L, 3L, null), null, domain(3L), zone(1L));

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class, () -> service.setVmNetworkDetails(vm, new VirtualMachineTO() {
        }));

        assertEquals("Failed to find account with ID: 2", exception.getMessage());
    }

    @Test
    public void missingDomainThrowsExistingMessage() {
        UserVmJoinVO userVmJoin = userVmJoin(5L);
        when(userVmJoinDao.searchByIds(anyLong())).thenReturn(List.of(userVmJoin));
        stubNetworkNameInputs(network(5L, 2L, 3L, null), account(2L), null, zone(1L));

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class, () -> service.setVmNetworkDetails(vm, new VirtualMachineTO() {
        }));

        assertEquals("Failed to find domain with ID: 3", exception.getMessage());
    }

    @Test
    public void missingVpcThrowsExistingMessage() {
        UserVmJoinVO userVmJoin = userVmJoin(5L);
        when(userVmJoinDao.searchByIds(anyLong())).thenReturn(List.of(userVmJoin));
        stubNetworkNameInputs(network(5L, 2L, 3L, 4L), account(2L), domain(3L), zone(1L));

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class, () -> service.setVmNetworkDetails(vm, new VirtualMachineTO() {
        }));

        assertEquals("Failed to find VPC with ID: 4", exception.getMessage());
    }

    private UserVmJoinVO userVmJoin(long networkId) {
        UserVmJoinVO userVmJoin = mock(UserVmJoinVO.class);
        when(userVmJoin.getNetworkId()).thenReturn(networkId);
        return userVmJoin;
    }

    private DomainRouterJoinVO routerJoin(long networkId) {
        DomainRouterJoinVO routerJoin = mock(DomainRouterJoinVO.class);
        when(routerJoin.getNetworkId()).thenReturn(networkId);
        return routerJoin;
    }

    private NetworkVO network(long id, long accountId, long domainId, Long vpcId) {
        return network(id, accountId, domainId, vpcId, Networks.BroadcastDomainType.NSX);
    }

    private NetworkVO network(long id, long accountId, long domainId, Long vpcId, Networks.BroadcastDomainType broadcastDomainType) {
        NetworkVO network = mock(NetworkVO.class);
        when(network.getId()).thenReturn(id);
        when(network.getAccountId()).thenReturn(accountId);
        when(network.getDomainId()).thenReturn(domainId);
        when(network.getVpcId()).thenReturn(vpcId);
        when(network.getBroadcastDomainType()).thenReturn(broadcastDomainType);
        return network;
    }

    private AccountVO account(long id) {
        AccountVO account = mock(AccountVO.class);
        when(account.getId()).thenReturn(id);
        return account;
    }

    private DomainVO domain(long id) {
        DomainVO domain = mock(DomainVO.class);
        when(domain.getId()).thenReturn(id);
        return domain;
    }

    private DataCenterVO zone(long id) {
        DataCenterVO zone = mock(DataCenterVO.class);
        when(zone.getId()).thenReturn(id);
        return zone;
    }

    private VpcVO vpc(long id) {
        VpcVO vpc = mock(VpcVO.class);
        when(vpc.getId()).thenReturn(id);
        return vpc;
    }

    private void stubNetworkNameInputs(NetworkVO network, AccountVO account, DomainVO domain, DataCenterVO zone) {
        long networkId = network.getId();
        long accountId = network.getAccountId();
        long domainId = network.getDomainId();
        when(networkDao.findById(networkId)).thenReturn(network);
        when(accountDao.findById(accountId)).thenReturn(account);
        when(domainDao.findById(domainId)).thenReturn(domain);
        when(dataCenterDao.findById(1L)).thenReturn(zone);
    }
}
