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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.apache.cloudstack.api.command.admin.region.DeletePortableIpRangeCmd;
import org.apache.cloudstack.api.command.admin.region.ListPortableIpRangesCmd;
import org.apache.cloudstack.region.PortableIp;
import org.apache.cloudstack.region.PortableIpDao;
import org.apache.cloudstack.region.PortableIpRange;
import org.apache.cloudstack.region.PortableIpRangeDao;
import org.apache.cloudstack.region.PortableIpRangeVO;
import org.apache.cloudstack.region.PortableIpVO;
import org.apache.cloudstack.region.RegionVO;
import org.apache.cloudstack.region.dao.RegionDao;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.dao.IPAddressDao;

/**
 * Focused tests for {@link PortableIpRangeServiceImpl} — the Phase 4
 * extraction of portable IP range CRUD out of {@link ConfigurationManagerImpl}
 * (parallel slice, 3rd).
 *
 * Behavior is also exercised through the manager's delegating wrappers in
 * {@code ConfigurationManagerImplTest}; these tests target the service
 * directly so future refactors of the manager don't drop coverage.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class PortableIpRangeServiceImplTest {

    @Mock private RegionDao _regionDao;
    @Mock private PortableIpRangeDao _portableIpRangeDao;
    @Mock private PortableIpDao _portableIpDao;
    @Mock private DataCenterDao _zoneDao;
    @Mock private VlanDao _vlanDao;
    @Mock private IPAddressDao _publicIpAddressDao;

    @InjectMocks
    private PortableIpRangeServiceImpl service;

    private static final int REGION_ID = 1;
    private static final long RANGE_ID = 10L;

    @Before
    public void setUp() {
        ReflectionTestUtils.setField(service, "_regionDao", _regionDao);
        ReflectionTestUtils.setField(service, "_portableIpRangeDao", _portableIpRangeDao);
        ReflectionTestUtils.setField(service, "_portableIpDao", _portableIpDao);
        ReflectionTestUtils.setField(service, "_zoneDao", _zoneDao);
        ReflectionTestUtils.setField(service, "_vlanDao", _vlanDao);
        ReflectionTestUtils.setField(service, "_publicIpAddressDao", _publicIpAddressDao);
    }

    // ------------------------------------------------------------------
    // deletePortableIpRange — validation paths
    // ------------------------------------------------------------------

    @Test
    public void deletePortableIpRangeThrowsWhenRangeNotFound() {
        DeletePortableIpRangeCmd cmd = Mockito.mock(DeletePortableIpRangeCmd.class);
        Mockito.when(cmd.getId()).thenReturn(RANGE_ID);
        Mockito.when(_portableIpRangeDao.findById(RANGE_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.deletePortableIpRange(cmd));
        assertTrue(ex.getMessage().contains("valid portable IP range id"));
    }

    @Test
    public void deletePortableIpRangeThrowsWhenIpsAreAssigned() {
        DeletePortableIpRangeCmd cmd = Mockito.mock(DeletePortableIpRangeCmd.class);
        Mockito.when(cmd.getId()).thenReturn(RANGE_ID);

        PortableIpRangeVO range = new PortableIpRangeVO(REGION_ID, "untagged", "10.0.0.1", "255.255.255.0", "10.0.0.10", "10.0.0.12");
        ReflectionTestUtils.setField(range, "id", RANGE_ID);
        Mockito.when(_portableIpRangeDao.findById(RANGE_ID)).thenReturn(range);

        PortableIpVO ip1 = Mockito.mock(PortableIpVO.class);
        PortableIpVO ip2 = Mockito.mock(PortableIpVO.class);
        // full range has 2 IPs, only 1 is free → assigned IPs remain
        Mockito.when(_portableIpDao.listByRangeId(RANGE_ID)).thenReturn(Arrays.asList(ip1, ip2));
        Mockito.when(_portableIpDao.listByRangeIdAndState(RANGE_ID, PortableIp.State.Free))
                .thenReturn(Collections.singletonList(ip1));

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.deletePortableIpRange(cmd));
        assertTrue(ex.getMessage().contains("assigned"));
    }

    @Test
    public void deletePortableIpRangeReturnsFalseWhenBothListsNull() {
        DeletePortableIpRangeCmd cmd = Mockito.mock(DeletePortableIpRangeCmd.class);
        Mockito.when(cmd.getId()).thenReturn(RANGE_ID);

        PortableIpRangeVO range = new PortableIpRangeVO(REGION_ID, "untagged", "10.0.0.1", "255.255.255.0", "10.0.0.10", "10.0.0.12");
        ReflectionTestUtils.setField(range, "id", RANGE_ID);
        Mockito.when(_portableIpRangeDao.findById(RANGE_ID)).thenReturn(range);

        Mockito.when(_portableIpDao.listByRangeId(RANGE_ID)).thenReturn(null);
        Mockito.when(_portableIpDao.listByRangeIdAndState(RANGE_ID, PortableIp.State.Free)).thenReturn(null);

        boolean result = service.deletePortableIpRange(cmd);

        assertFalse(result);
    }

    @Test
    public void deletePortableIpRangeSucceedsAndFlipsRegionFlagWhenLastRange() {
        DeletePortableIpRangeCmd cmd = Mockito.mock(DeletePortableIpRangeCmd.class);
        Mockito.when(cmd.getId()).thenReturn(RANGE_ID);

        PortableIpRangeVO range = new PortableIpRangeVO(REGION_ID, "untagged", "10.0.0.1", "255.255.255.0", "10.0.0.10", "10.0.0.10");
        ReflectionTestUtils.setField(range, "id", RANGE_ID);
        Mockito.when(_portableIpRangeDao.findById(RANGE_ID)).thenReturn(range);

        PortableIpVO ip = Mockito.mock(PortableIpVO.class);
        Mockito.when(_portableIpDao.listByRangeId(RANGE_ID)).thenReturn(Collections.singletonList(ip));
        Mockito.when(_portableIpDao.listByRangeIdAndState(RANGE_ID, PortableIp.State.Free))
                .thenReturn(Collections.singletonList(ip));
        // No remaining ranges after delete
        Mockito.when(_portableIpRangeDao.listAll()).thenReturn(Collections.emptyList());

        RegionVO region = new RegionVO(REGION_ID, "default", "http://localhost");
        Mockito.when(_regionDao.findById(REGION_ID)).thenReturn(region);

        boolean result = service.deletePortableIpRange(cmd);

        assertTrue(result);
        assertFalse(region.getPortableipEnabled());
        Mockito.verify(_regionDao).update(REGION_ID, region);
    }

    // ------------------------------------------------------------------
    // listPortableIpRanges — validation and routing
    // ------------------------------------------------------------------

    @Test
    public void listPortableIpRangesThrowsForInvalidRegionId() {
        ListPortableIpRangesCmd cmd = Mockito.mock(ListPortableIpRangesCmd.class);
        Mockito.when(cmd.getRegionIdId()).thenReturn(REGION_ID);
        Mockito.when(cmd.getPortableIpRangeId()).thenReturn(null);
        Mockito.when(_regionDao.findById(REGION_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.listPortableIpRanges(cmd));
        assertTrue(ex.getMessage().contains("Invalid region ID"));
    }

    @Test
    public void listPortableIpRangesByRegionIdReturnsRangesFromDao() {
        ListPortableIpRangesCmd cmd = Mockito.mock(ListPortableIpRangesCmd.class);
        Mockito.when(cmd.getRegionIdId()).thenReturn(REGION_ID);
        Mockito.when(cmd.getPortableIpRangeId()).thenReturn(null);
        Mockito.when(_regionDao.findById(REGION_ID)).thenReturn(new RegionVO(REGION_ID, "default", "http://localhost"));

        PortableIpRangeVO range = new PortableIpRangeVO(REGION_ID, "untagged", "10.0.0.1", "255.255.255.0", "10.0.0.10", "10.0.0.12");
        Mockito.when(_portableIpRangeDao.listByRegionId(REGION_ID))
                .thenReturn(Collections.singletonList(range));

        List<? extends PortableIpRange> result = service.listPortableIpRanges(cmd);

        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    @Test
    public void listPortableIpRangesByRangeIdThrowsForUnknownRangeId() {
        ListPortableIpRangesCmd cmd = Mockito.mock(ListPortableIpRangesCmd.class);
        Mockito.when(cmd.getRegionIdId()).thenReturn(null);
        Mockito.when(cmd.getPortableIpRangeId()).thenReturn(RANGE_ID);
        Mockito.when(_portableIpRangeDao.findById(RANGE_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.listPortableIpRanges(cmd));
        assertTrue(ex.getMessage().contains("Invalid portable IP range ID"));
    }

    @Test
    public void listPortableIpRangesWithNoCriteriaReturnsAll() {
        ListPortableIpRangesCmd cmd = Mockito.mock(ListPortableIpRangesCmd.class);
        Mockito.when(cmd.getRegionIdId()).thenReturn(null);
        Mockito.when(cmd.getPortableIpRangeId()).thenReturn(null);
        Mockito.when(_portableIpRangeDao.listAll())
                .thenReturn(Collections.emptyList());

        List<? extends PortableIpRange> result = service.listPortableIpRanges(cmd);

        assertNotNull(result);
        Mockito.verify(_portableIpRangeDao).listAll();
    }

    // ------------------------------------------------------------------
    // listPortableIps — validation
    // ------------------------------------------------------------------

    @Test
    public void listPortableIpsThrowsForUnknownRangeId() {
        Mockito.when(_portableIpRangeDao.findById(RANGE_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.listPortableIps(RANGE_ID));
        assertTrue(ex.getMessage().contains("valid portable IP range id"));
    }

    @Test
    public void listPortableIpsReturnsIpsFromDao() {
        PortableIpRangeVO range = new PortableIpRangeVO(REGION_ID, "untagged", "10.0.0.1", "255.255.255.0", "10.0.0.10", "10.0.0.11");
        ReflectionTestUtils.setField(range, "id", RANGE_ID);
        Mockito.when(_portableIpRangeDao.findById(RANGE_ID)).thenReturn(range);

        PortableIpVO ip = Mockito.mock(PortableIpVO.class);
        Mockito.when(_portableIpDao.listByRangeId(RANGE_ID)).thenReturn(Collections.singletonList(ip));

        List<? extends PortableIp> result = service.listPortableIps(RANGE_ID);

        assertNotNull(result);
        assertFalse(result.isEmpty());
    }

    // ------------------------------------------------------------------
    // checkOverlapPortableIpRange — internal helper
    // ------------------------------------------------------------------

    @Test
    public void checkOverlapReturnsFalseWhenNoExistingRanges() {
        Mockito.when(_portableIpRangeDao.listByRegionId(REGION_ID)).thenReturn(Collections.emptyList());

        boolean overlap = service.checkOverlapPortableIpRange(REGION_ID, "10.0.0.10", "10.0.0.20");

        assertFalse(overlap);
    }

    @Test
    public void checkOverlapReturnsTrueWhenNewRangeOverlapsExisting() {
        PortableIpRangeVO existing = new PortableIpRangeVO(REGION_ID, "untagged", "10.0.0.1", "255.255.255.0", "10.0.0.10", "10.0.0.20");
        Mockito.when(_portableIpRangeDao.listByRegionId(REGION_ID)).thenReturn(Collections.singletonList(existing));

        // New range [10.0.0.15 - 10.0.0.25] overlaps with [10.0.0.10 - 10.0.0.20]
        boolean overlap = service.checkOverlapPortableIpRange(REGION_ID, "10.0.0.15", "10.0.0.25");

        assertTrue(overlap);
    }

    @Test
    public void checkOverlapReturnsFalseWhenNewRangeIsAdjacentButNotOverlapping() {
        PortableIpRangeVO existing = new PortableIpRangeVO(REGION_ID, "untagged", "10.0.0.1", "255.255.255.0", "10.0.0.10", "10.0.0.20");
        Mockito.when(_portableIpRangeDao.listByRegionId(REGION_ID)).thenReturn(Collections.singletonList(existing));

        // [10.0.0.21 - 10.0.0.30] does not overlap [10.0.0.10 - 10.0.0.20]
        boolean overlap = service.checkOverlapPortableIpRange(REGION_ID, "10.0.0.21", "10.0.0.30");

        assertFalse(overlap);
    }

    @Test
    public void checkOverlapReturnsTrueWhenExistingRangeSurroundsNewRange() {
        PortableIpRangeVO existing = new PortableIpRangeVO(REGION_ID, "untagged", "10.0.0.1", "255.255.255.0", "10.0.0.5", "10.0.0.50");
        Mockito.when(_portableIpRangeDao.listByRegionId(REGION_ID)).thenReturn(Collections.singletonList(existing));

        // New range [10.0.0.10 - 10.0.0.20] is entirely inside [10.0.0.5 - 10.0.0.50]
        boolean overlap = service.checkOverlapPortableIpRange(REGION_ID, "10.0.0.10", "10.0.0.20");

        assertTrue(overlap);
    }

    // ------------------------------------------------------------------
    // checkOverlapPublicIpRange — internal helper
    // ------------------------------------------------------------------

    @Test
    public void checkOverlapPublicIpRangeThrowsWhenPublicIpInRange() {
        com.cloud.network.dao.IPAddressVO publicIp = Mockito.mock(com.cloud.network.dao.IPAddressVO.class);
        com.cloud.utils.net.Ip addr = Mockito.mock(com.cloud.utils.net.Ip.class);
        Mockito.when(addr.addr()).thenReturn("10.0.0.15");
        Mockito.when(publicIp.getAddress()).thenReturn(addr);
        Mockito.when(_publicIpAddressDao.listByDcId(1L)).thenReturn(Collections.singletonList(publicIp));

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.checkOverlapPublicIpRange(1L, "10.0.0.10", "10.0.0.20"));
        assertTrue(ex.getMessage().contains("overlap with Public IP"));
    }

    @Test
    public void checkOverlapPublicIpRangePassesWhenPublicIpOutsideRange() {
        com.cloud.network.dao.IPAddressVO publicIp = Mockito.mock(com.cloud.network.dao.IPAddressVO.class);
        com.cloud.utils.net.Ip addr = Mockito.mock(com.cloud.utils.net.Ip.class);
        Mockito.when(addr.addr()).thenReturn("192.168.1.1");
        Mockito.when(publicIp.getAddress()).thenReturn(addr);
        Mockito.when(_publicIpAddressDao.listByDcId(1L)).thenReturn(Collections.singletonList(publicIp));

        // No exception expected — the public IP is outside 10.0.0.10-10.0.0.20
        service.checkOverlapPublicIpRange(1L, "10.0.0.10", "10.0.0.20");
    }

    // ------------------------------------------------------------------
    // createPortableIpRange — validation paths (no TX, stopping before lock)
    // ------------------------------------------------------------------

    @Test
    public void createPortableIpRangeThrowsForInvalidRegionId() {
        org.apache.cloudstack.api.command.admin.region.CreatePortableIpRangeCmd cmd =
                Mockito.mock(org.apache.cloudstack.api.command.admin.region.CreatePortableIpRangeCmd.class);
        Mockito.when(cmd.getRegionId()).thenReturn(REGION_ID);
        Mockito.when(_regionDao.findById(REGION_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createPortableIpRange(cmd));
        assertTrue(ex.getMessage().contains("Invalid region ID"));
    }

    @Test
    public void createPortableIpRangeThrowsForInvalidIpRange() throws Exception {
        org.apache.cloudstack.api.command.admin.region.CreatePortableIpRangeCmd cmd =
                Mockito.mock(org.apache.cloudstack.api.command.admin.region.CreatePortableIpRangeCmd.class);
        Mockito.when(cmd.getRegionId()).thenReturn(REGION_ID);
        Mockito.when(cmd.getStartIp()).thenReturn("not-an-ip");
        Mockito.when(cmd.getEndIp()).thenReturn("also-not-an-ip");
        Mockito.when(_regionDao.findById(REGION_ID)).thenReturn(new RegionVO(REGION_ID, "default", "http://localhost"));

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createPortableIpRange(cmd));
        assertTrue(ex.getMessage().contains("Invalid portable ip  range"));
    }

    @Test
    public void createPortableIpRangeThrowsWhenStartIpNotInSubnet() throws Exception {
        org.apache.cloudstack.api.command.admin.region.CreatePortableIpRangeCmd cmd =
                Mockito.mock(org.apache.cloudstack.api.command.admin.region.CreatePortableIpRangeCmd.class);
        Mockito.when(cmd.getRegionId()).thenReturn(REGION_ID);
        Mockito.when(cmd.getStartIp()).thenReturn("192.168.1.10");
        Mockito.when(cmd.getEndIp()).thenReturn("192.168.1.20");
        Mockito.when(cmd.getGateway()).thenReturn("10.0.0.1");
        Mockito.when(cmd.getNetmask()).thenReturn("255.255.255.0");
        Mockito.when(_regionDao.findById(REGION_ID)).thenReturn(new RegionVO(REGION_ID, "default", "http://localhost"));

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createPortableIpRange(cmd));
        assertTrue(ex.getMessage().contains("start IP is in the same subnet"));
    }

    @Test
    public void createPortableIpRangeThrowsWhenOverlapDetected() throws Exception {
        org.apache.cloudstack.api.command.admin.region.CreatePortableIpRangeCmd cmd =
                Mockito.mock(org.apache.cloudstack.api.command.admin.region.CreatePortableIpRangeCmd.class);
        Mockito.when(cmd.getRegionId()).thenReturn(REGION_ID);
        Mockito.when(cmd.getStartIp()).thenReturn("10.0.0.10");
        Mockito.when(cmd.getEndIp()).thenReturn("10.0.0.20");
        Mockito.when(cmd.getGateway()).thenReturn("10.0.0.1");
        Mockito.when(cmd.getNetmask()).thenReturn("255.255.255.0");
        Mockito.when(cmd.getVlan()).thenReturn(null);
        Mockito.when(_regionDao.findById(REGION_ID)).thenReturn(new RegionVO(REGION_ID, "default", "http://localhost"));

        // Overlap: existing range [10.0.0.5 - 10.0.0.15] conflicts with [10.0.0.10 - 10.0.0.20]
        PortableIpRangeVO existing = new PortableIpRangeVO(REGION_ID, "untagged", "10.0.0.1", "255.255.255.0", "10.0.0.5", "10.0.0.15");
        Mockito.when(_portableIpRangeDao.listByRegionId(REGION_ID)).thenReturn(Collections.singletonList(existing));

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createPortableIpRange(cmd));
        assertTrue(ex.getMessage().contains("overlaps with a portable"));
    }

    @Test
    public void createPortableIpRangeThrowsForInvalidVlanId() throws Exception {
        org.apache.cloudstack.api.command.admin.region.CreatePortableIpRangeCmd cmd =
                Mockito.mock(org.apache.cloudstack.api.command.admin.region.CreatePortableIpRangeCmd.class);
        Mockito.when(cmd.getRegionId()).thenReturn(REGION_ID);
        Mockito.when(cmd.getStartIp()).thenReturn("10.0.0.10");
        Mockito.when(cmd.getEndIp()).thenReturn("10.0.0.20");
        Mockito.when(cmd.getGateway()).thenReturn("10.0.0.1");
        Mockito.when(cmd.getNetmask()).thenReturn("255.255.255.0");
        Mockito.when(cmd.getVlan()).thenReturn("not-a-vlan-id");
        Mockito.when(_regionDao.findById(REGION_ID)).thenReturn(new RegionVO(REGION_ID, "default", "http://localhost"));
        Mockito.when(_portableIpRangeDao.listByRegionId(REGION_ID)).thenReturn(Collections.emptyList());

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createPortableIpRange(cmd));
        assertTrue(ex.getMessage().contains("Invalid vlan id"));
    }

    @Test
    public void createPortableIpRangeThrowsWhenVlanAlreadyUsedInZone() throws Exception {
        org.apache.cloudstack.api.command.admin.region.CreatePortableIpRangeCmd cmd =
                Mockito.mock(org.apache.cloudstack.api.command.admin.region.CreatePortableIpRangeCmd.class);
        Mockito.when(cmd.getRegionId()).thenReturn(REGION_ID);
        Mockito.when(cmd.getStartIp()).thenReturn("10.0.0.10");
        Mockito.when(cmd.getEndIp()).thenReturn("10.0.0.20");
        Mockito.when(cmd.getGateway()).thenReturn("10.0.0.1");
        Mockito.when(cmd.getNetmask()).thenReturn("255.255.255.0");
        Mockito.when(cmd.getVlan()).thenReturn("100");
        Mockito.when(_regionDao.findById(REGION_ID)).thenReturn(new RegionVO(REGION_ID, "default", "http://localhost"));
        Mockito.when(_portableIpRangeDao.listByRegionId(REGION_ID)).thenReturn(Collections.emptyList());

        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getId()).thenReturn(1L);
        Mockito.when(_zoneDao.listAllZones()).thenReturn(Collections.singletonList(zone));

        VlanVO conflictingVlan = Mockito.mock(VlanVO.class);
        Mockito.when(_vlanDao.findByZoneAndVlanId(1L, "100")).thenReturn(conflictingVlan);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createPortableIpRange(cmd));
        assertTrue(ex.getMessage().contains("conflicts with VLAN id of the portable ip range"));
    }
}
