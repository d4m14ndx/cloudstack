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
package com.cloud.configuration;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.apache.cloudstack.api.command.admin.network.CreateGuestNetworkIpv6PrefixCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteGuestNetworkIpv6PrefixCmd;
import org.apache.cloudstack.api.command.admin.network.ListGuestNetworkIpv6PrefixesCmd;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.DataCenterGuestIpv6Prefix;
import com.cloud.dc.DataCenterGuestIpv6PrefixVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterGuestIpv6PrefixDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Ipv6GuestPrefixSubnetNetworkMapVO;
import com.cloud.network.dao.Ipv6GuestPrefixSubnetNetworkMapDao;
import com.cloud.utils.exception.CloudRuntimeException;

public class GuestIpv6PrefixServiceImplTest {

    private GuestIpv6PrefixServiceImpl service;
    private AutoCloseable closeable;

    @Mock
    private DataCenterDao zoneDao;
    @Mock
    private DataCenterGuestIpv6PrefixDao dataCenterGuestIpv6PrefixDao;
    @Mock
    private Ipv6GuestPrefixSubnetNetworkMapDao ipv6GuestPrefixSubnetNetworkMapDao;

    @Before
    public void setUp() {
        closeable = MockitoAnnotations.openMocks(this);
        service = new GuestIpv6PrefixServiceImpl();
        ReflectionTestUtils.setField(service, "_zoneDao", zoneDao);
        ReflectionTestUtils.setField(service, "dataCenterGuestIpv6PrefixDao", dataCenterGuestIpv6PrefixDao);
        ReflectionTestUtils.setField(service, "ipv6GuestPrefixSubnetNetworkMapDao", ipv6GuestPrefixSubnetNetworkMapDao);
    }

    @After
    public void tearDown() throws Exception {
        closeable.close();
    }

    @Test(expected = InvalidParameterValueException.class)
    public void createDataCenterGuestIpv6PrefixThrowsWhenZoneMissing() throws Exception {
        CreateGuestNetworkIpv6PrefixCmd cmd = mock(CreateGuestNetworkIpv6PrefixCmd.class);
        when(cmd.getZoneId()).thenReturn(1L);
        when(zoneDao.findById(anyLong())).thenReturn(null);

        service.createDataCenterGuestIpv6Prefix(cmd);
    }

    @Test(expected = IllegalArgumentException.class)
    public void createDataCenterGuestIpv6PrefixPropagatesMalformedPrefix() throws Exception {
        CreateGuestNetworkIpv6PrefixCmd cmd = mock(CreateGuestNetworkIpv6PrefixCmd.class);
        when(cmd.getZoneId()).thenReturn(1L);
        when(cmd.getPrefix()).thenReturn("Invalid");
        when(zoneDao.findById(anyLong())).thenReturn(mock(DataCenterVO.class));

        service.createDataCenterGuestIpv6Prefix(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void createDataCenterGuestIpv6PrefixRejectsPrefixLongerThanSlaacMask() throws Exception {
        CreateGuestNetworkIpv6PrefixCmd cmd = mock(CreateGuestNetworkIpv6PrefixCmd.class);
        when(cmd.getZoneId()).thenReturn(1L);
        when(cmd.getPrefix()).thenReturn("fd17:5:8a43:e2a4:c000::/66");
        when(zoneDao.findById(anyLong())).thenReturn(mock(DataCenterVO.class));

        service.createDataCenterGuestIpv6Prefix(cmd);
    }

    @Test(expected = InvalidParameterValueException.class)
    public void createDataCenterGuestIpv6PrefixRejectsOverlappingExistingPrefix() throws Exception {
        CreateGuestNetworkIpv6PrefixCmd cmd = mock(CreateGuestNetworkIpv6PrefixCmd.class);
        when(cmd.getZoneId()).thenReturn(1L);
        when(cmd.getPrefix()).thenReturn("fd17:5:8a43:e2a5::/64");
        when(zoneDao.findById(anyLong())).thenReturn(mock(DataCenterVO.class));

        DataCenterGuestIpv6PrefixVO existingPrefix = mock(DataCenterGuestIpv6PrefixVO.class);
        when(existingPrefix.getPrefix()).thenReturn("fd17:5:8a43:e2a4::/62");
        when(dataCenterGuestIpv6PrefixDao.listByDataCenterId(anyLong())).thenReturn(List.of(existingPrefix));

        service.createDataCenterGuestIpv6Prefix(cmd);
    }

    @Test
    public void createDataCenterGuestIpv6PrefixPersistsValidPrefix() throws Exception {
        long zoneId = 1L;
        String prefix = "fd17:5:8a43:e2a5::/64";
        CreateGuestNetworkIpv6PrefixCmd cmd = mock(CreateGuestNetworkIpv6PrefixCmd.class);
        when(cmd.getZoneId()).thenReturn(zoneId);
        when(cmd.getPrefix()).thenReturn(prefix);
        when(zoneDao.findById(anyLong())).thenReturn(mock(DataCenterVO.class));
        when(dataCenterGuestIpv6PrefixDao.listByDataCenterId(anyLong())).thenReturn(new ArrayList<>());
        when(dataCenterGuestIpv6PrefixDao.persist(any(DataCenterGuestIpv6PrefixVO.class))).thenAnswer(invocation -> invocation.getArgument(0));

        DataCenterGuestIpv6Prefix result = service.createDataCenterGuestIpv6Prefix(cmd);

        ArgumentCaptor<DataCenterGuestIpv6PrefixVO> captor = ArgumentCaptor.forClass(DataCenterGuestIpv6PrefixVO.class);
        verify(dataCenterGuestIpv6PrefixDao).persist(captor.capture());
        assertEquals(zoneId, captor.getValue().getDataCenterId().longValue());
        assertEquals(prefix, captor.getValue().getPrefix());
        assertEquals(prefix, result.getPrefix());
    }

    @Test(expected = CloudRuntimeException.class)
    public void createDataCenterGuestIpv6PrefixWrapsPersistFailure() throws Exception {
        CreateGuestNetworkIpv6PrefixCmd cmd = mock(CreateGuestNetworkIpv6PrefixCmd.class);
        when(cmd.getZoneId()).thenReturn(1L);
        when(cmd.getPrefix()).thenReturn("fd17:5:8a43:e2a5::/64");
        when(zoneDao.findById(anyLong())).thenReturn(mock(DataCenterVO.class));
        when(dataCenterGuestIpv6PrefixDao.listByDataCenterId(anyLong())).thenReturn(new ArrayList<>());
        when(dataCenterGuestIpv6PrefixDao.persist(any(DataCenterGuestIpv6PrefixVO.class))).thenThrow(new RuntimeException("boom"));

        service.createDataCenterGuestIpv6Prefix(cmd);
    }

    @Test
    public void listDataCenterGuestIpv6PrefixesReturnsSinglePrefixById() throws Exception {
        ListGuestNetworkIpv6PrefixesCmd cmd = mock(ListGuestNetworkIpv6PrefixesCmd.class);
        DataCenterGuestIpv6PrefixVO prefix = mock(DataCenterGuestIpv6PrefixVO.class);
        when(cmd.getId()).thenReturn(1L);
        when(dataCenterGuestIpv6PrefixDao.findById(anyLong())).thenReturn(prefix);

        List<? extends DataCenterGuestIpv6Prefix> result = service.listDataCenterGuestIpv6Prefixes(cmd);

        assertEquals(1, result.size());
        assertEquals(prefix, result.get(0));
    }

    @Test
    public void listDataCenterGuestIpv6PrefixesReturnsEmptyListWhenIdMissing() throws Exception {
        ListGuestNetworkIpv6PrefixesCmd cmd = mock(ListGuestNetworkIpv6PrefixesCmd.class);
        when(cmd.getId()).thenReturn(1L);
        when(dataCenterGuestIpv6PrefixDao.findById(anyLong())).thenReturn(null);

        List<? extends DataCenterGuestIpv6Prefix> result = service.listDataCenterGuestIpv6Prefixes(cmd);

        assertTrue(result.isEmpty());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void listDataCenterGuestIpv6PrefixesThrowsWhenZoneMissing() throws Exception {
        ListGuestNetworkIpv6PrefixesCmd cmd = mock(ListGuestNetworkIpv6PrefixesCmd.class);
        when(cmd.getId()).thenReturn(null);
        when(cmd.getZoneId()).thenReturn(1L);
        when(zoneDao.findById(anyLong())).thenReturn(null);

        service.listDataCenterGuestIpv6Prefixes(cmd);
    }

    @Test
    public void listDataCenterGuestIpv6PrefixesReturnsZonePrefixes() throws Exception {
        ListGuestNetworkIpv6PrefixesCmd cmd = mock(ListGuestNetworkIpv6PrefixesCmd.class);
        List<DataCenterGuestIpv6PrefixVO> prefixes = List.of(mock(DataCenterGuestIpv6PrefixVO.class), mock(DataCenterGuestIpv6PrefixVO.class));
        when(cmd.getId()).thenReturn(null);
        when(cmd.getZoneId()).thenReturn(1L);
        when(zoneDao.findById(anyLong())).thenReturn(mock(DataCenterVO.class));
        when(dataCenterGuestIpv6PrefixDao.listByDataCenterId(anyLong())).thenReturn(prefixes);

        List<? extends DataCenterGuestIpv6Prefix> result = service.listDataCenterGuestIpv6Prefixes(cmd);

        assertEquals(2, result.size());
    }

    @Test
    public void listDataCenterGuestIpv6PrefixesReturnsAllPrefixes() throws Exception {
        ListGuestNetworkIpv6PrefixesCmd cmd = mock(ListGuestNetworkIpv6PrefixesCmd.class);
        List<DataCenterGuestIpv6PrefixVO> prefixes = List.of(mock(DataCenterGuestIpv6PrefixVO.class), mock(DataCenterGuestIpv6PrefixVO.class),
                mock(DataCenterGuestIpv6PrefixVO.class));
        when(cmd.getId()).thenReturn(null);
        when(cmd.getZoneId()).thenReturn(null);
        when(dataCenterGuestIpv6PrefixDao.listAll()).thenReturn(prefixes);

        List<? extends DataCenterGuestIpv6Prefix> result = service.listDataCenterGuestIpv6Prefixes(cmd);

        assertEquals(3, result.size());
    }

    @Test(expected = InvalidParameterValueException.class)
    public void deleteDataCenterGuestIpv6PrefixThrowsWhenMissing() {
        DeleteGuestNetworkIpv6PrefixCmd cmd = mock(DeleteGuestNetworkIpv6PrefixCmd.class);
        when(cmd.getId()).thenReturn(1L);
        when(dataCenterGuestIpv6PrefixDao.findById(anyLong())).thenReturn(null);

        service.deleteDataCenterGuestIpv6Prefix(cmd);
    }

    @Test
    public void deleteDataCenterGuestIpv6PrefixRejectsUsedSubnets() {
        long prefixId = 1L;
        DeleteGuestNetworkIpv6PrefixCmd cmd = mock(DeleteGuestNetworkIpv6PrefixCmd.class);
        DataCenterGuestIpv6PrefixVO prefix = mock(DataCenterGuestIpv6PrefixVO.class);
        Ipv6GuestPrefixSubnetNetworkMapVO subnet = mock(Ipv6GuestPrefixSubnetNetworkMapVO.class);
        when(cmd.getId()).thenReturn(prefixId);
        when(prefix.getUuid()).thenReturn("prefix-uuid");
        when(prefix.getPrefix()).thenReturn("fd17:5:8a43:e2a5::/64");
        when(subnet.getSubnet()).thenReturn("fd17:5:8a43:e2a5::/80");
        when(dataCenterGuestIpv6PrefixDao.findById(anyLong())).thenReturn(prefix);
        when(ipv6GuestPrefixSubnetNetworkMapDao.listUsedByPrefix(anyLong())).thenReturn(List.of(subnet));

        Assert.assertThrows(CloudRuntimeException.class, () -> service.deleteDataCenterGuestIpv6Prefix(cmd));

        verify(ipv6GuestPrefixSubnetNetworkMapDao, never()).deleteByPrefixId(anyLong());
        verify(dataCenterGuestIpv6PrefixDao, never()).remove(anyLong());
    }

    @Test
    public void deleteDataCenterGuestIpv6PrefixDeletesUnusedPrefix() {
        long prefixId = 1L;
        DeleteGuestNetworkIpv6PrefixCmd cmd = mock(DeleteGuestNetworkIpv6PrefixCmd.class);
        DataCenterGuestIpv6PrefixVO prefix = mock(DataCenterGuestIpv6PrefixVO.class);
        when(cmd.getId()).thenReturn(prefixId);
        when(dataCenterGuestIpv6PrefixDao.findById(anyLong())).thenReturn(prefix);
        when(ipv6GuestPrefixSubnetNetworkMapDao.listUsedByPrefix(anyLong())).thenReturn(new ArrayList<>());

        boolean result = service.deleteDataCenterGuestIpv6Prefix(cmd);

        assertTrue(result);
        verify(ipv6GuestPrefixSubnetNetworkMapDao).deleteByPrefixId(prefixId);
        verify(dataCenterGuestIpv6PrefixDao).remove(prefixId);
    }
}
