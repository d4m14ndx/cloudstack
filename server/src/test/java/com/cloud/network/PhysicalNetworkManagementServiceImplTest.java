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
package com.cloud.network;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.command.admin.network.ListGuestVlansCmd;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.DataCenterVnetVO;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterVnetDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.AccountGuestVlanMapDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.OvsProviderDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderVO;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeDao;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeVO;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.element.NetworkElement;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.user.Account;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Filter;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class PhysicalNetworkManagementServiceImplTest {

    @Mock PhysicalNetworkDao _physicalNetworkDao;
    @Mock PhysicalNetworkServiceProviderDao _pNSPDao;
    @Mock PhysicalNetworkTrafficTypeDao _pNTrafficTypeDao;
    @Mock DataCenterVnetDao _dcVnetDao;
    @Mock OvsProviderDao _ovsProviderDao;
    @Mock DataCenterDao _dcDao;
    @Mock NetworkDao _networksDao;
    @Mock org.apache.cloudstack.framework.config.dao.ConfigurationDao _configDao;
    @Mock VlanDao _vlanDao;
    @Mock IPAddressDao _ipAddressDao;
    @Mock ServiceOfferingDao serviceOfferingDao;
    @Mock AccountGuestVlanMapDao _accountGuestVlanMapDao;
    @Mock NetworkOfferingDao _networkOfferingDao;
    @Mock NetworkModel _networkModel;
    @Mock com.cloud.user.AccountManager _accountMgr;
    @Mock StorageNetworkManager _stnwMgr;

    @InjectMocks
    PhysicalNetworkManagementServiceImpl service;

    // -------------------------------------------------------------------
    // Test 1: searchPhysicalNetworks_byZoneId_returnsFiltered
    // -------------------------------------------------------------------
    @Test
    public void searchPhysicalNetworks_byZoneId_returnsFiltered() {
        PhysicalNetworkVO pn = mock(PhysicalNetworkVO.class);
        List<PhysicalNetworkVO> list = Collections.singletonList(pn);
        SearchCriteria<PhysicalNetworkVO> sc = mock(SearchCriteria.class);
        when(_physicalNetworkDao.createSearchCriteria()).thenReturn(sc);
        when(_physicalNetworkDao.searchAndCount(any(SearchCriteria.class), any(Filter.class)))
                .thenReturn(new Pair<>(list, 1));

        Pair<List<? extends PhysicalNetwork>, Integer> result =
                service.searchPhysicalNetworks(null, 1L, null, 0L, 20L, null);

        assertEquals(1, result.second().intValue());
        assertEquals(pn, result.first().get(0));
    }

    // -------------------------------------------------------------------
    // Test 2: searchPhysicalNetworks_byKeyword_returnsFiltered
    // -------------------------------------------------------------------
    @Test
    public void searchPhysicalNetworks_byKeyword_returnsFiltered() {
        List<PhysicalNetworkVO> list = new ArrayList<>();
        SearchCriteria<PhysicalNetworkVO> sc = mock(SearchCriteria.class);
        when(_physicalNetworkDao.createSearchCriteria()).thenReturn(sc);
        when(_physicalNetworkDao.searchAndCount(any(SearchCriteria.class), any(Filter.class)))
                .thenReturn(new Pair<>(list, 0));

        Pair<List<? extends PhysicalNetwork>, Integer> result =
                service.searchPhysicalNetworks(null, null, "missing", 0L, 20L, null);

        assertEquals(0, result.second().intValue());
    }

    // -------------------------------------------------------------------
    // Test 3: updatePhysicalNetwork_extendsVnetRange_setsCorrectString  (migrated from UpdatePhysicalNetworkTest)
    // -------------------------------------------------------------------
    @Test
    public void updatePhysicalNetwork_extendsVnetRange_setsCorrectString() {
        TransactionLegacy txn = TransactionLegacy.open("updatePhysicalNetwork_extendsVnetRange");
        try {
            PhysicalNetworkVO networkVO = mock(PhysicalNetworkVO.class);
            DataCenterVO dcVO = mock(DataCenterVO.class);
            List<String> existingRange = new ArrayList<>();
            existingRange.add("524");

            when(_physicalNetworkDao.findById(anyLong())).thenReturn(networkVO);
            when(_dcDao.findById(anyLong())).thenReturn(dcVO);
            when(networkVO.getDataCenterId()).thenReturn(1L);
            lenient().when(dcVO.getNetworkType()).thenReturn(NetworkType.Advanced);
            when(networkVO.getId()).thenReturn(1L);
            when(networkVO.getIsolationMethods()).thenReturn(Collections.emptyList());
            when(_physicalNetworkDao.acquireInLockTable(anyLong(), any(Integer.class))).thenReturn(networkVO);
            when(_dcVnetDao.listVnetsByPhysicalNetworkAndDataCenter(anyLong(), anyLong())).thenReturn(existingRange);
            lenient().when(_dcVnetDao.countAllocatedVnets(anyLong())).thenReturn(0);
            lenient().when(_accountGuestVlanMapDao.listAccountGuestVlanMapsByPhysicalNetwork(anyLong()))
                    .thenReturn(Collections.emptyList());
            when(_physicalNetworkDao.update(anyLong(), any(PhysicalNetworkVO.class))).thenReturn(true);

            service.updatePhysicalNetwork(1L, null, null, "524-524,525-530", null);

            ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
            verify(networkVO).setVnet(captor.capture());
            assertEquals("524-530", captor.getValue());
        } finally {
            txn.close("updatePhysicalNetwork_extendsVnetRange");
        }
    }

    // -------------------------------------------------------------------
    // Test 4: updatePhysicalNetwork_invalidId_throwsInvalidParam
    // -------------------------------------------------------------------
    @Test
    public void updatePhysicalNetwork_invalidId_throwsInvalidParam() {
        when(_physicalNetworkDao.findById(anyLong())).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.updatePhysicalNetwork(99L, null, null, null, null));
    }

    // -------------------------------------------------------------------
    // Test 5: updatePhysicalNetwork_basicZoneVnetRange_throws
    // -------------------------------------------------------------------
    @Test
    public void updatePhysicalNetwork_basicZoneVnetRange_throws() {
        PhysicalNetworkVO networkVO = mock(PhysicalNetworkVO.class);
        DataCenterVO dcVO = mock(DataCenterVO.class);
        when(_physicalNetworkDao.findById(anyLong())).thenReturn(networkVO);
        when(networkVO.getDataCenterId()).thenReturn(1L);
        when(_dcDao.findById(anyLong())).thenReturn(dcVO);
        when(dcVO.getNetworkType()).thenReturn(NetworkType.Basic);

        assertThrows(InvalidParameterValueException.class,
                () -> service.updatePhysicalNetwork(1L, null, null, "10-100", null));
    }

    // -------------------------------------------------------------------
    // Test 6: updatePhysicalNetwork_invalidState_throws
    // -------------------------------------------------------------------
    @Test
    public void updatePhysicalNetwork_invalidState_throws() {
        PhysicalNetworkVO networkVO = mock(PhysicalNetworkVO.class);
        DataCenterVO dcVO = mock(DataCenterVO.class);
        when(_physicalNetworkDao.findById(anyLong())).thenReturn(networkVO);
        when(networkVO.getDataCenterId()).thenReturn(1L);
        when(_dcDao.findById(anyLong())).thenReturn(dcVO);
        lenient().when(dcVO.getNetworkType()).thenReturn(NetworkType.Advanced);

        assertThrows(InvalidParameterValueException.class,
                () -> service.updatePhysicalNetwork(1L, null, null, null, "InvalidState"));
    }

    // -------------------------------------------------------------------
    // Test 7: updatePhysicalNetwork_disabledState_persistsState
    // -------------------------------------------------------------------
    @Test
    public void updatePhysicalNetwork_disabledState_persistsState() {
        PhysicalNetworkVO networkVO = mock(PhysicalNetworkVO.class);
        DataCenterVO dcVO = mock(DataCenterVO.class);
        when(_physicalNetworkDao.findById(anyLong())).thenReturn(networkVO);
        when(networkVO.getDataCenterId()).thenReturn(1L);
        when(_dcDao.findById(anyLong())).thenReturn(dcVO);
        lenient().when(dcVO.getNetworkType()).thenReturn(NetworkType.Advanced);
        when(_physicalNetworkDao.update(anyLong(), any(PhysicalNetworkVO.class))).thenReturn(true);

        PhysicalNetwork result = service.updatePhysicalNetwork(1L, null, null, null, "Disabled");
        verify(networkVO).setState(PhysicalNetwork.State.Disabled);
        assertEquals(networkVO, result);
    }

    // -------------------------------------------------------------------
    // Test 8: generateVnetString_multipleRanges
    // -------------------------------------------------------------------
    @Test
    public void generateVnetString_multipleRanges() {
        List<String> vnets = new ArrayList<>();
        for (int i = 524; i <= 530; i++) {
            vnets.add(String.valueOf(i));
        }
        String result = service.generateVnetString(vnets);
        assertEquals("524-530", result);
    }

    // -------------------------------------------------------------------
    // Test 9: listNetworkServices_unknownProvider_throws
    // -------------------------------------------------------------------
    @Test
    public void listNetworkServices_unknownProvider_throws() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.listNetworkServices("NonExistentProvider12345"));
    }

    // -------------------------------------------------------------------
    // Test 10: listNetworkServices_nullProvider_returnsAll
    // -------------------------------------------------------------------
    @Test
    public void listNetworkServices_nullProvider_returnsAll() {
        List<? extends Network.Service> services = service.listNetworkServices(null);
        assertNotNull(services);
        assertFalse(services.isEmpty());
    }

    // -------------------------------------------------------------------
    // Test 11: addProviderToPhysicalNetwork_unknownPhysicalNetwork_throws
    // -------------------------------------------------------------------
    @Test
    public void addProviderToPhysicalNetwork_unknownPhysicalNetwork_throws() {
        when(_physicalNetworkDao.findById(anyLong())).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.addProviderToPhysicalNetwork(1L, "VirtualRouter", null, null));
    }

    // -------------------------------------------------------------------
    // Test 12: addProviderToPhysicalNetwork_duplicateProvider_throws
    // -------------------------------------------------------------------
    @Test
    public void addProviderToPhysicalNetwork_duplicateProvider_throws() {
        PhysicalNetworkVO pn = mock(PhysicalNetworkVO.class);
        when(_physicalNetworkDao.findById(anyLong())).thenReturn(pn);
        when(_pNSPDao.findByServiceProvider(anyLong(), anyString()))
                .thenReturn(mock(PhysicalNetworkServiceProviderVO.class));

        assertThrows(CloudRuntimeException.class,
                () -> service.addProviderToPhysicalNetwork(1L, "VirtualRouter", null, null));
    }

    // -------------------------------------------------------------------
    // Test 13: addProviderToPhysicalNetwork_unknownProvider_throws
    // -------------------------------------------------------------------
    @Test
    public void addProviderToPhysicalNetwork_unknownProvider_throws() {
        PhysicalNetworkVO pn = mock(PhysicalNetworkVO.class);
        when(_physicalNetworkDao.findById(anyLong())).thenReturn(pn);
        lenient().when(_pNSPDao.findByServiceProvider(anyLong(), anyString())).thenReturn(null);

        assertThrows(InvalidParameterValueException.class,
                () -> service.addProviderToPhysicalNetwork(1L, "NoSuchProvider9999", null, null));
    }

    // -------------------------------------------------------------------
    // Test 14: listNetworkServiceProviders_byPhysicalNetworkId_returnsList
    // -------------------------------------------------------------------
    @Test
    public void listNetworkServiceProviders_byPhysicalNetworkId_returnsList() {
        SearchBuilder<PhysicalNetworkServiceProviderVO> sb = mock(SearchBuilder.class);
        SearchCriteria<PhysicalNetworkServiceProviderVO> sc = mock(SearchCriteria.class);
        when(_pNSPDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        List<PhysicalNetworkServiceProviderVO> list = new ArrayList<>();
        when(_pNSPDao.searchAndCount(any(SearchCriteria.class), any(Filter.class)))
                .thenReturn(new Pair<>(list, 0));

        Pair<List<? extends PhysicalNetworkServiceProvider>, Integer> result =
                service.listNetworkServiceProviders(1L, null, null, 0L, 20L);

        assertNotNull(result);
        assertEquals(0, result.second().intValue());
    }

    // -------------------------------------------------------------------
    // Test 15: updateNetworkServiceProvider_invalidId_throws
    // -------------------------------------------------------------------
    @Test
    public void updateNetworkServiceProvider_invalidId_throws() throws Exception {
        when(_pNSPDao.findById(anyLong())).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.updateNetworkServiceProvider(999L, "Enabled", null));
    }

    // -------------------------------------------------------------------
    // Test 16: updateNetworkServiceProvider_invalidState_throws
    // -------------------------------------------------------------------
    @Test
    public void updateNetworkServiceProvider_invalidState_throws() throws Exception {
        PhysicalNetworkServiceProviderVO provider = mock(PhysicalNetworkServiceProviderVO.class);
        when(_pNSPDao.findById(anyLong())).thenReturn(provider);
        NetworkElement element = mock(NetworkElement.class);
        when(provider.getProviderName()).thenReturn("VirtualRouter");
        when(_networkModel.getElementImplementingProvider(anyString())).thenReturn(element);

        assertThrows(InvalidParameterValueException.class,
                () -> service.updateNetworkServiceProvider(1L, "BOGUS_STATE", null));
    }

    // -------------------------------------------------------------------
    // Test 17: updateNetworkServiceProvider_disabledState_persists
    // -------------------------------------------------------------------
    @Test
    public void updateNetworkServiceProvider_disabledState_persists() throws Exception {
        PhysicalNetworkServiceProviderVO provider = mock(PhysicalNetworkServiceProviderVO.class);
        when(_pNSPDao.findById(anyLong())).thenReturn(provider);
        NetworkElement element = mock(NetworkElement.class);
        when(provider.getProviderName()).thenReturn("VirtualRouter");
        when(_networkModel.getElementImplementingProvider(anyString())).thenReturn(element);
        when(_pNSPDao.update(anyLong(), any(PhysicalNetworkServiceProviderVO.class))).thenReturn(true);

        service.updateNetworkServiceProvider(1L, "Disabled", null);
        verify(provider).setState(PhysicalNetworkServiceProvider.State.Disabled);
    }

    // -------------------------------------------------------------------
    // Test 18: deleteNetworkServiceProvider_unknownId_throws
    // -------------------------------------------------------------------
    @Test
    public void deleteNetworkServiceProvider_unknownId_throws() {
        when(_pNSPDao.findById(anyLong())).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.deleteNetworkServiceProvider(999L));
    }

    // -------------------------------------------------------------------
    // Test 19: getPhysicalNetwork_unknownId_returnsNull
    // -------------------------------------------------------------------
    @Test
    public void getPhysicalNetwork_unknownId_returnsNull() {
        when(_physicalNetworkDao.findById(anyLong())).thenReturn(null);
        assertNull(service.getPhysicalNetwork(42L));
    }

    // -------------------------------------------------------------------
    // Test 20: getPhysicalNetwork_knownId_returnsVO
    // -------------------------------------------------------------------
    @Test
    public void getPhysicalNetwork_knownId_returnsVO() {
        PhysicalNetworkVO pn = mock(PhysicalNetworkVO.class);
        when(_physicalNetworkDao.findById(1L)).thenReturn(pn);
        assertEquals(pn, service.getPhysicalNetwork(1L));
    }

    // -------------------------------------------------------------------
    // Test 21: getCreatedPhysicalNetwork_delegatesToGetPhysicalNetwork
    // -------------------------------------------------------------------
    @Test
    public void getCreatedPhysicalNetwork_delegatesToGetPhysicalNetwork() {
        PhysicalNetworkVO pn = mock(PhysicalNetworkVO.class);
        when(_physicalNetworkDao.findById(1L)).thenReturn(pn);
        assertEquals(pn, service.getCreatedPhysicalNetwork(1L));
    }

    // -------------------------------------------------------------------
    // Test 22: getPhysicalNetworkServiceProvider_returnsVO
    // -------------------------------------------------------------------
    @Test
    public void getPhysicalNetworkServiceProvider_returnsVO() {
        PhysicalNetworkServiceProviderVO p = mock(PhysicalNetworkServiceProviderVO.class);
        when(_pNSPDao.findById(1L)).thenReturn(p);
        assertEquals(p, service.getPhysicalNetworkServiceProvider(1L));
    }

    // -------------------------------------------------------------------
    // Test 23: findPhysicalNetworkId_byTagAndTrafficType_returnsId
    // -------------------------------------------------------------------
    @Test
    public void findPhysicalNetworkId_byTagAndTrafficType_returnsId() {
        when(_networkModel.findPhysicalNetworkId(1L, "tag1", TrafficType.Guest)).thenReturn(42L);
        assertEquals(42L, service.findPhysicalNetworkId(1L, "tag1", TrafficType.Guest));
    }

    // -------------------------------------------------------------------
    // Test 24: getPhysicalNetworkTrafficType_returnsVO
    // -------------------------------------------------------------------
    @Test
    public void getPhysicalNetworkTrafficType_returnsVO() {
        PhysicalNetworkTrafficTypeVO tt = mock(PhysicalNetworkTrafficTypeVO.class);
        when(_pNTrafficTypeDao.findById(1L)).thenReturn(tt);
        assertEquals(tt, service.getPhysicalNetworkTrafficType(1L));
    }

    // -------------------------------------------------------------------
    // Test 25: updatePhysicalNetworkTrafficType_changesKvmLabel_persists
    // -------------------------------------------------------------------
    @Test
    public void updatePhysicalNetworkTrafficType_changesKvmLabel_persists() {
        PhysicalNetworkTrafficTypeVO tt = mock(PhysicalNetworkTrafficTypeVO.class);
        when(_pNTrafficTypeDao.findById(1L)).thenReturn(tt);
        when(_pNTrafficTypeDao.update(anyLong(), any(PhysicalNetworkTrafficTypeVO.class))).thenReturn(true);

        service.updatePhysicalNetworkTrafficType(1L, null, "kvmbr0", null, null);
        verify(tt).setKvmNetworkLabel("kvmbr0");
    }

    // -------------------------------------------------------------------
    // Test 26: updatePhysicalNetworkTrafficType_unknownId_throws
    // -------------------------------------------------------------------
    @Test
    public void updatePhysicalNetworkTrafficType_unknownId_throws() {
        when(_pNTrafficTypeDao.findById(anyLong())).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.updatePhysicalNetworkTrafficType(99L, null, null, null, null));
    }

    // -------------------------------------------------------------------
    // Test 27: deletePhysicalNetworkTrafficType_unknownId_throws
    // -------------------------------------------------------------------
    @Test
    public void deletePhysicalNetworkTrafficType_unknownId_throws() {
        when(_pNTrafficTypeDao.findById(anyLong())).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.deletePhysicalNetworkTrafficType(99L));
    }

    // -------------------------------------------------------------------
    // Test 28: deletePhysicalNetworkTrafficType_guestWithNetworks_throws
    // -------------------------------------------------------------------
    @Test
    public void deletePhysicalNetworkTrafficType_guestWithNetworks_throws() {
        PhysicalNetworkTrafficTypeVO tt = mock(PhysicalNetworkTrafficTypeVO.class);
        when(_pNTrafficTypeDao.findById(anyLong())).thenReturn(tt);
        when(tt.getTrafficType()).thenReturn(TrafficType.Guest);
        when(_networksDao.listByPhysicalNetworkTrafficType(anyLong(), any(TrafficType.class)))
                .thenReturn(Collections.singletonList(mock(com.cloud.network.dao.NetworkVO.class)));

        assertThrows(CloudRuntimeException.class,
                () -> service.deletePhysicalNetworkTrafficType(1L));
    }

    // -------------------------------------------------------------------
    // Test 29: deletePhysicalNetworkTrafficType_happyPath_returnsTrue
    // -------------------------------------------------------------------
    @Test
    public void deletePhysicalNetworkTrafficType_happyPath_returnsTrue() {
        PhysicalNetworkTrafficTypeVO tt = mock(PhysicalNetworkTrafficTypeVO.class);
        when(_pNTrafficTypeDao.findById(anyLong())).thenReturn(tt);
        when(tt.getTrafficType()).thenReturn(TrafficType.Management);
        when(_pNTrafficTypeDao.remove(anyLong())).thenReturn(true);

        assertTrue(service.deletePhysicalNetworkTrafficType(1L));
    }

    // -------------------------------------------------------------------
    // Test 30: listTrafficTypes_byPhysicalNetworkId_returnsList
    // -------------------------------------------------------------------
    @Test
    public void listTrafficTypes_byPhysicalNetworkId_returnsList() {
        PhysicalNetworkVO pn = mock(PhysicalNetworkVO.class);
        when(_physicalNetworkDao.findById(anyLong())).thenReturn(pn);
        List<PhysicalNetworkTrafficTypeVO> list = new ArrayList<>();
        when(_pNTrafficTypeDao.listAndCountBy(anyLong())).thenReturn(new Pair<>(list, 0));

        Pair<List<? extends PhysicalNetworkTrafficType>, Integer> result =
                service.listTrafficTypes(1L);
        assertNotNull(result);
        assertEquals(0, result.second().intValue());
    }

    // -------------------------------------------------------------------
    // Test 31: listTrafficTypes_unknownPhysicalNetwork_throws
    // -------------------------------------------------------------------
    @Test
    public void listTrafficTypes_unknownPhysicalNetwork_throws() {
        when(_physicalNetworkDao.findById(anyLong())).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.listTrafficTypes(99L));
    }

    @Test
    public void getExclusiveGuestNetwork_noSharedGuestNetwork_throws() {
        when(_networksDao.listBy(Account.ACCOUNT_ID_SYSTEM, 7L, GuestType.Shared, TrafficType.Guest))
                .thenReturn(Collections.emptyList());

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.getExclusiveGuestNetwork(7L));

        assertEquals("Unable to find network with trafficType Guest and guestType Shared in zone 7", ex.getMessage());
    }

    @Test
    public void getExclusiveGuestNetwork_multipleSharedGuestNetworks_throws() {
        NetworkVO first = mock(NetworkVO.class);
        NetworkVO second = mock(NetworkVO.class);
        when(_networksDao.listBy(Account.ACCOUNT_ID_SYSTEM, 7L, GuestType.Shared, TrafficType.Guest))
                .thenReturn(Arrays.asList(first, second));

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.getExclusiveGuestNetwork(7L));

        assertEquals("Found more than 1 network with trafficType Guest and guestType Shared in zone 7", ex.getMessage());
    }

    @Test
    public void getExclusiveGuestNetwork_oneSharedGuestNetwork_returnsIt() {
        NetworkVO network = mock(NetworkVO.class);
        when(_networksDao.listBy(Account.ACCOUNT_ID_SYSTEM, 7L, GuestType.Shared, TrafficType.Guest))
                .thenReturn(Collections.singletonList(network));

        assertEquals(network, service.getExclusiveGuestNetwork(7L));
    }

    private static class TestListGuestVlansCmd extends ListGuestVlansCmd {
        private final Long id;
        private final Long zoneId;
        private final Long physicalNetworkId;
        private final String vnet;
        private final Boolean allocatedOnly;
        private final String keyword;
        private final Long startIndex;
        private final Long pageSizeVal;

        TestListGuestVlansCmd(Long id, Long zoneId, Long physicalNetworkId, String vnet,
                Boolean allocatedOnly, String keyword, Long startIndex, Long pageSizeVal) {
            this.id = id;
            this.zoneId = zoneId;
            this.physicalNetworkId = physicalNetworkId;
            this.vnet = vnet;
            this.allocatedOnly = allocatedOnly;
            this.keyword = keyword;
            this.startIndex = startIndex;
            this.pageSizeVal = pageSizeVal;
        }

        @Override
        public Long getId() {
            return id;
        }

        @Override
        public Long getZoneId() {
            return zoneId;
        }

        @Override
        public Long getPhysicalNetworkId() {
            return physicalNetworkId;
        }

        @Override
        public String getVnet() {
            return vnet;
        }

        @Override
        public Boolean getAllocatedOnly() {
            return allocatedOnly;
        }

        @Override
        public String getKeyword() {
            return keyword;
        }

        @Override
        public Long getStartIndex() {
            return startIndex;
        }

        @Override
        public Long getPageSizeVal() {
            return pageSizeVal;
        }
    }

    @Test
    public void listGuestVlans_allFilters_buildsSearchAndReturnsCount() {
        SearchCriteria<DataCenterVnetVO> sc = mock(SearchCriteria.class);
        DataCenterVnetVO vlan = mock(DataCenterVnetVO.class);
        when(_dcVnetDao.createSearchCriteria()).thenReturn(sc);
        when(_dcVnetDao.searchAndCount(any(SearchCriteria.class), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.singletonList(vlan), 1));

        TestListGuestVlansCmd cmd = new TestListGuestVlansCmd(11L, 12L, 13L, "400",
                true, "40", 0L, 50L);

        Pair<List<? extends GuestVlan>, Integer> result = service.listGuestVlans(cmd);

        verify(sc).addAnd("id", SearchCriteria.Op.EQ, 11L);
        verify(sc).addAnd("dataCenterId", SearchCriteria.Op.EQ, 12L);
        verify(sc).addAnd("physicalNetworkId", SearchCriteria.Op.EQ, 13L);
        verify(sc).addAnd("vnet", SearchCriteria.Op.EQ, "400");
        verify(sc).addAnd("takenAt", SearchCriteria.Op.NNULL);
        verify(sc).addAnd("vnet", SearchCriteria.Op.LIKE, "%40%");
        assertEquals(1, result.second().intValue());
        assertEquals(vlan, result.first().get(0));
    }

    @Test
    public void listGuestVlans_allocatedOnlyFalse_doesNotFilterTakenAt() {
        SearchCriteria<DataCenterVnetVO> sc = mock(SearchCriteria.class);
        when(_dcVnetDao.createSearchCriteria()).thenReturn(sc);
        when(_dcVnetDao.searchAndCount(any(SearchCriteria.class), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.emptyList(), 0));

        TestListGuestVlansCmd cmd = new TestListGuestVlansCmd(null, null, null, null,
                false, null, 5L, 10L);

        Pair<List<? extends GuestVlan>, Integer> result = service.listGuestVlans(cmd);

        verify(sc, Mockito.never()).addAnd(Mockito.eq("takenAt"), Mockito.eq(SearchCriteria.Op.NNULL));
        assertEquals(0, result.second().intValue());
    }

    // -------------------------------------------------------------------
    // Test 32: createPhysicalNetwork_nullZone_throws
    // -------------------------------------------------------------------
    @Test
    public void createPhysicalNetwork_nullZone_throws() {
        assertThrows(InvalidParameterValueException.class,
                () -> service.createPhysicalNetwork(null, null, null, null, null, null, null, "net1"));
    }

    // -------------------------------------------------------------------
    // Test 33: createPhysicalNetwork_unknownZone_throws
    // -------------------------------------------------------------------
    @Test
    public void createPhysicalNetwork_unknownZone_throws() {
        when(_dcDao.findById(anyLong())).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.createPhysicalNetwork(1L, null, null, null, null, null, null, "net1"));
    }

    // -------------------------------------------------------------------
    // Test 34: createPhysicalNetwork_enabledZone_throws
    // -------------------------------------------------------------------
    @Test
    public void createPhysicalNetwork_enabledZone_throws() {
        DataCenterVO dc = mock(DataCenterVO.class);
        when(_dcDao.findById(anyLong())).thenReturn(dc);
        when(dc.getAllocationState()).thenReturn(com.cloud.org.Grouping.AllocationState.Enabled);
        assertThrows(com.cloud.exception.PermissionDeniedException.class,
                () -> service.createPhysicalNetwork(1L, null, null, null, null, null, null, "net1"));
    }

    // -------------------------------------------------------------------
    // Test 35: createPhysicalNetwork_basicZoneAlreadyHasNetwork_throws
    // -------------------------------------------------------------------
    @Test
    public void createPhysicalNetwork_basicZoneAlreadyHasNetwork_throws() {
        DataCenterVO dc = mock(DataCenterVO.class);
        when(_dcDao.findById(anyLong())).thenReturn(dc);
        when(dc.getAllocationState()).thenReturn(com.cloud.org.Grouping.AllocationState.Disabled);
        when(dc.getNetworkType()).thenReturn(NetworkType.Basic);
        PhysicalNetworkVO existing = mock(PhysicalNetworkVO.class);
        when(_physicalNetworkDao.listByZone(anyLong())).thenReturn(Collections.singletonList(existing));
        assertThrows(CloudRuntimeException.class,
                () -> service.createPhysicalNetwork(1L, null, null, null, null, null, null, "net1"));
    }

    // -------------------------------------------------------------------
    // Test 36: deletePhysicalNetwork_unknownId_throws
    // -------------------------------------------------------------------
    @Test
    public void deletePhysicalNetwork_unknownId_throws() {
        when(_physicalNetworkDao.findById(anyLong())).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.deletePhysicalNetwork(999L));
    }

    // -------------------------------------------------------------------
    // Test 37: addOrRemoveVnets_emptyArray_doesNothing
    // -------------------------------------------------------------------
    @Test
    public void addOrRemoveVnets_emptyArray_doesNothing() {
        PhysicalNetworkVO pn = mock(PhysicalNetworkVO.class);
        // No exception expected, no DAO calls expected
        service.addOrRemoveVnets(new String[0], pn);
    }

    // -------------------------------------------------------------------
    // Test 38: listNetworkServiceProviders_withNameFilter_filters
    // -------------------------------------------------------------------
    @Test
    public void listNetworkServiceProviders_withNameFilter_filters() {
        SearchBuilder<PhysicalNetworkServiceProviderVO> sb = mock(SearchBuilder.class);
        SearchCriteria<PhysicalNetworkServiceProviderVO> sc = mock(SearchCriteria.class);
        when(_pNSPDao.createSearchBuilder()).thenReturn(sb);
        when(sb.create()).thenReturn(sc);
        PhysicalNetworkServiceProviderVO p = mock(PhysicalNetworkServiceProviderVO.class);
        when(_pNSPDao.searchAndCount(any(SearchCriteria.class), any(Filter.class)))
                .thenReturn(new Pair<>(Collections.singletonList(p), 1));

        Pair<List<? extends PhysicalNetworkServiceProvider>, Integer> result =
                service.listNetworkServiceProviders(1L, "VirtualRouter", "Enabled", 0L, 20L);
        assertEquals(1, result.second().intValue());
    }

    // -------------------------------------------------------------------
    // Test 39: addTrafficTypeToPhysicalNetwork_unknownPhysicalNetwork_throws
    // -------------------------------------------------------------------
    @Test
    public void addTrafficTypeToPhysicalNetwork_unknownPhysicalNetwork_throws() {
        when(_physicalNetworkDao.findById(anyLong())).thenReturn(null);
        assertThrows(InvalidParameterValueException.class,
                () -> service.addTrafficTypeToPhysicalNetwork(99L, "Guest", null, null, null, null, null, null, null));
    }

    // -------------------------------------------------------------------
    // Test 40: addTrafficTypeToPhysicalNetwork_invalidTrafficType_throws
    // -------------------------------------------------------------------
    @Test
    public void addTrafficTypeToPhysicalNetwork_invalidTrafficType_throws() {
        PhysicalNetworkVO pn = mock(PhysicalNetworkVO.class);
        when(_physicalNetworkDao.findById(anyLong())).thenReturn(pn);
        assertThrows(InvalidParameterValueException.class,
                () -> service.addTrafficTypeToPhysicalNetwork(1L, "BOGUS_TYPE", null, null, null, null, null, null, null));
    }
}
