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

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.cloudstack.affinity.AffinityGroupService;
import org.apache.cloudstack.affinity.dao.AffinityGroupDao;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.command.admin.zone.CreateZoneCmd;
import org.apache.cloudstack.api.command.admin.zone.DeleteZoneCmd;
import org.apache.cloudstack.api.command.admin.zone.UpdateZoneCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.ZoneScope;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.capacity.dao.CapacityDao;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterIpAddressDao;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.host.dao.HostDao;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.NetrisProviderDao;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateZoneDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;

/**
 * Focused tests for {@link ZoneServiceImpl} — the Phase 4 extraction of
 * zone (DataCenter) CRUD out of {@link ConfigurationManagerImpl} (parallel
 * slice, 5th).
 *
 * <p>Behavior is also exercised through the manager's delegating wrappers
 * in {@code ConfigurationManagerTest}; these tests target the service
 * directly so future refactors of the manager don't drop coverage.
 */
@RunWith(MockitoJUnitRunner.Silent.class)
public class ZoneServiceImplTest {

    @Mock private DataCenterDao _zoneDao;
    @Mock private DomainDao _domainDao;
    @Mock private AccountDao _accountDao;
    @Mock private HostDao _hostDao;
    @Mock private HostPodDao _podDao;
    @Mock private VolumeDao _volumeDao;
    @Mock private VMInstanceDao _vmInstanceDao;
    @Mock private IPAddressDao _publicIpAddressDao;
    @Mock private DataCenterIpAddressDao _privateIpAddressDao;
    @Mock private PhysicalNetworkDao _physicalNetworkDao;
    @Mock private PhysicalNetworkTrafficTypeDao _trafficTypeDao;
    @Mock private ImageStoreDao _imageStoreDao;
    @Mock private VlanDao _vlanDao;
    @Mock private CapacityDao _capacityDao;
    @Mock private DedicatedResourceDao _dedicatedDao;
    @Mock private AffinityGroupDao _affinityGroupDao;
    @Mock private AffinityGroupService _affinityGroupService;
    @Mock private NetworkOfferingDao _networkOfferingDao;
    @Mock private NetworkOrchestrationService _networkMgr;
    @Mock private NetworkService _networkSvc;
    @Mock private NetworkModel _networkModel;
    @Mock private VMTemplateZoneDao templateZoneDao;
    @Mock private AnnotationDao annotationDao;
    @Mock private NsxProviderDao nsxProviderDao;
    @Mock private NetrisProviderDao netrisProviderDao;

    @InjectMocks
    private ZoneServiceImpl service;

    private static final long ZONE_ID = 1L;

    @Before
    public void setUp() {
        ReflectionTestUtils.setField(service, "_zoneDao", _zoneDao);
        ReflectionTestUtils.setField(service, "_domainDao", _domainDao);
        ReflectionTestUtils.setField(service, "_accountDao", _accountDao);
        ReflectionTestUtils.setField(service, "_hostDao", _hostDao);
        ReflectionTestUtils.setField(service, "_podDao", _podDao);
        ReflectionTestUtils.setField(service, "_volumeDao", _volumeDao);
        ReflectionTestUtils.setField(service, "_vmInstanceDao", _vmInstanceDao);
        ReflectionTestUtils.setField(service, "_publicIpAddressDao", _publicIpAddressDao);
        ReflectionTestUtils.setField(service, "_privateIpAddressDao", _privateIpAddressDao);
        ReflectionTestUtils.setField(service, "_physicalNetworkDao", _physicalNetworkDao);
        ReflectionTestUtils.setField(service, "_trafficTypeDao", _trafficTypeDao);
        ReflectionTestUtils.setField(service, "_imageStoreDao", _imageStoreDao);
        ReflectionTestUtils.setField(service, "_vlanDao", _vlanDao);
        ReflectionTestUtils.setField(service, "_capacityDao", _capacityDao);
        ReflectionTestUtils.setField(service, "_dedicatedDao", _dedicatedDao);
        ReflectionTestUtils.setField(service, "_affinityGroupDao", _affinityGroupDao);
        ReflectionTestUtils.setField(service, "_affinityGroupService", _affinityGroupService);
        ReflectionTestUtils.setField(service, "_networkOfferingDao", _networkOfferingDao);
        ReflectionTestUtils.setField(service, "_networkMgr", _networkMgr);
        ReflectionTestUtils.setField(service, "_networkSvc", _networkSvc);
        ReflectionTestUtils.setField(service, "_networkModel", _networkModel);
        ReflectionTestUtils.setField(service, "templateZoneDao", templateZoneDao);
        ReflectionTestUtils.setField(service, "annotationDao", annotationDao);
        ReflectionTestUtils.setField(service, "nsxProviderDao", nsxProviderDao);
        ReflectionTestUtils.setField(service, "netrisProviderDao", netrisProviderDao);

        // CallContext is required by createZone (calls
        // CallContext.current().getCallingUserId() and putContextParameter).
        AccountVO account = new AccountVO("admin", 1L, "ROOT", Account.Type.ADMIN, UUID.randomUUID().toString());
        ReflectionTestUtils.setField(account, "id", 1L);
        UserVO user = new UserVO(1, "admin", "password", "admin", "admin",
                "admin@admin.com", "GMT", UUID.randomUUID().toString(), User.Source.UNKNOWN);
        ReflectionTestUtils.setField(user, "id", 1L);
        CallContext.register(user, account);

        // The Mockito.RETURNS_DEFAULTS for primitive returns gives us 0 / empty
        // lists which suffice for the deletability checks; we still need to
        // stub findById for any path that runs against a real-ish zone.
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // ------------------------------------------------------------------
    // createZone(CreateZoneCmd) — early validation paths
    // ------------------------------------------------------------------

    @Test
    public void createZoneRejectsUnknownNetworkType() {
        CreateZoneCmd cmd = Mockito.mock(CreateZoneCmd.class);
        Mockito.when(cmd.getNetworkType()).thenReturn("Bogus");
        Mockito.when(cmd.getDomainId()).thenReturn(null);
        Mockito.when(cmd.isEdge()).thenReturn(false);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createZone(cmd));
        assertTrue(ex.getMessage().contains("Invalid zone type"));
    }

    @Test
    public void createZoneRejectsGuestCidrInBasicZone() {
        CreateZoneCmd cmd = Mockito.mock(CreateZoneCmd.class);
        Mockito.when(cmd.getNetworkType()).thenReturn(NetworkType.Basic.toString());
        Mockito.when(cmd.getGuestCidrAddress()).thenReturn("10.1.1.0/24");
        Mockito.when(cmd.isEdge()).thenReturn(false);
        Mockito.when(cmd.getDomainId()).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createZone(cmd));
        assertTrue(ex.getMessage().contains("guestCidrAddress parameter is not supported for Basic zone"));
    }

    @Test
    public void createZoneRejectsEdgeOnBasic() {
        CreateZoneCmd cmd = Mockito.mock(CreateZoneCmd.class);
        Mockito.when(cmd.getNetworkType()).thenReturn(NetworkType.Basic.toString());
        Mockito.when(cmd.isEdge()).thenReturn(true);
        Mockito.when(cmd.getDomainId()).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createZone(cmd));
        assertTrue(ex.getMessage().contains("Only advanced network type zones can be edge zones"));
    }

    // ------------------------------------------------------------------
    // createZone(long, …) — parameter validation paths
    // ------------------------------------------------------------------

    @Test
    public void createZoneLongFormRejectsInvalidGuestCidr() {
        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createZone(1L, "z", null, null, null, null,
                        "not-a-cidr", null, null, NetworkType.Advanced, null, null,
                        false, false, null, null, false, null));
        assertTrue(ex.getMessage().contains("Please enter a valid guest cidr"));
    }

    @Test
    public void createZoneLongFormRejectsInvalidNetworkDomain() {
        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createZone(1L, "z", null, null, null, null,
                        null, null, null, NetworkType.Advanced, null, "invalid_domain!",
                        false, false, null, null, false, null));
        assertTrue(ex.getMessage().contains("Invalid network domain"));
    }

    @Test
    public void createZoneLongFormRejectsDuplicateName() {
        DataCenterVO existing = Mockito.mock(DataCenterVO.class);
        Mockito.when(_zoneDao.findByName("dup")).thenReturn(existing);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createZone(1L, "dup", null, null, null, null,
                        null, null, null, NetworkType.Advanced, null, null,
                        false, false, null, null, false, null));
        assertTrue(ex.getMessage().contains("A zone with that name already exists"));
    }

    @Test
    public void createZoneLongFormRejectsInvalidAllocationState() {
        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createZone(1L, "z", null, null, null, null,
                        null, null, null, NetworkType.Advanced, "BogusState", null,
                        false, false, null, null, false, null));
        assertTrue(ex.getMessage().contains("Unable to resolve Allocation State"));
    }

    @Test
    public void createZoneLongFormRejectsInvalidDns1() {
        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createZone(1L, "z", "not-an-ip", null, null, null,
                        null, null, null, NetworkType.Advanced, null, null,
                        false, false, null, null, false, null));
        assertTrue(ex.getMessage().contains("valid IP address for DNS1"));
    }

    @Test
    public void createZoneLongFormRejectsInvalidIp6Dns1() {
        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createZone(1L, "z", null, null, null, null,
                        null, null, null, NetworkType.Advanced, null, null,
                        false, false, "not-ipv6", null, false, null));
        assertTrue(ex.getMessage().contains("valid IPv6 address for IP6 DNS1"));
    }

    @Test
    public void createZoneLongFormRejectsInvalidDomainId() {
        Mockito.when(_domainDao.findById(99L)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createZone(1L, "z", null, null, null, null,
                        null, null, 99L, NetworkType.Advanced, null, null,
                        false, false, null, null, false, null));
        assertTrue(ex.getMessage().contains("valid domain id"));
    }

    // ------------------------------------------------------------------
    // editZone — validation paths
    // ------------------------------------------------------------------

    @Test
    public void editZoneRejectsMissingZone() {
        UpdateZoneCmd cmd = Mockito.mock(UpdateZoneCmd.class);
        Mockito.when(cmd.getId()).thenReturn(42L);
        Mockito.when(_zoneDao.findById(42L)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.editZone(cmd));
        assertTrue(ex.getMessage().contains("unable to find zone by id"));
    }

    @Test
    public void editZoneRejectsInvalidDnsSearchEntry() {
        UpdateZoneCmd cmd = Mockito.mock(UpdateZoneCmd.class);
        Mockito.when(cmd.getId()).thenReturn(1L);
        Mockito.when(cmd.getDnsSearchOrder()).thenReturn(Collections.singletonList("bogus_!"));

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.editZone(cmd));
        assertTrue(ex.getMessage().contains("Invalid network domain suffixes"));
    }

    // ------------------------------------------------------------------
    // deleteZone — early validation paths
    // ------------------------------------------------------------------

    @Test
    public void deleteZoneRejectsMissingZone() {
        DeleteZoneCmd cmd = Mockito.mock(DeleteZoneCmd.class);
        Mockito.when(cmd.getId()).thenReturn(42L);
        Mockito.when(_zoneDao.findById(42L)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.deleteZone(cmd));
        assertTrue(ex.getMessage().contains("does not exist"));
    }

    @Test
    public void deleteZoneRefusesWhenHostsPresent() {
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getId()).thenReturn(ZONE_ID);
        Mockito.when(_zoneDao.findById(ZONE_ID)).thenReturn(zone);
        Mockito.when(_hostDao.listEnabledIdsByDataCenterId(ZONE_ID)).thenReturn(List.of(1L));

        DeleteZoneCmd cmd = Mockito.mock(DeleteZoneCmd.class);
        Mockito.when(cmd.getId()).thenReturn(ZONE_ID);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.deleteZone(cmd));
        assertTrue(ex.getMessage().contains("there are servers"));
    }

    @Test
    public void deleteZoneRefusesWhenPodsPresent() {
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getId()).thenReturn(ZONE_ID);
        Mockito.when(_zoneDao.findById(ZONE_ID)).thenReturn(zone);
        Mockito.when(_hostDao.listEnabledIdsByDataCenterId(ZONE_ID)).thenReturn(Collections.emptyList());
        HostPodVO pod = Mockito.mock(HostPodVO.class);
        Mockito.when(_podDao.listByDataCenterId(ZONE_ID)).thenReturn(List.of(pod));

        DeleteZoneCmd cmd = Mockito.mock(DeleteZoneCmd.class);
        Mockito.when(cmd.getId()).thenReturn(ZONE_ID);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.deleteZone(cmd));
        assertTrue(ex.getMessage().contains("there are pods"));
    }

    @Test
    public void deleteZoneRefusesWhenPublicIpAllocated() {
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getId()).thenReturn(ZONE_ID);
        Mockito.when(_zoneDao.findById(ZONE_ID)).thenReturn(zone);
        Mockito.when(_publicIpAddressDao.countIPs(ZONE_ID, true)).thenReturn(1);

        DeleteZoneCmd cmd = Mockito.mock(DeleteZoneCmd.class);
        Mockito.when(cmd.getId()).thenReturn(ZONE_ID);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.deleteZone(cmd));
        assertTrue(ex.getMessage().contains("public IP addresses"));
    }

    @Test
    public void deleteZoneRefusesWhenVmsPresent() {
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getId()).thenReturn(ZONE_ID);
        Mockito.when(_zoneDao.findById(ZONE_ID)).thenReturn(zone);
        VMInstanceVO vm = Mockito.mock(VMInstanceVO.class);
        Mockito.when(_vmInstanceDao.listByZoneId(ZONE_ID)).thenReturn(List.of(vm));

        DeleteZoneCmd cmd = Mockito.mock(DeleteZoneCmd.class);
        Mockito.when(cmd.getId()).thenReturn(ZONE_ID);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.deleteZone(cmd));
        assertTrue(ex.getMessage().contains("virtual machines"));
    }

    @Test
    public void deleteZoneRefusesWhenVolumesPresent() {
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getId()).thenReturn(ZONE_ID);
        Mockito.when(_zoneDao.findById(ZONE_ID)).thenReturn(zone);
        VolumeVO volume = Mockito.mock(VolumeVO.class);
        Mockito.when(_volumeDao.findByDc(ZONE_ID)).thenReturn(List.of(volume));

        DeleteZoneCmd cmd = Mockito.mock(DeleteZoneCmd.class);
        Mockito.when(cmd.getId()).thenReturn(ZONE_ID);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.deleteZone(cmd));
        assertTrue(ex.getMessage().contains("storage volumes"));
    }

    @Test
    public void deleteZoneRefusesWhenPhysicalNetworksPresent() {
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getId()).thenReturn(ZONE_ID);
        Mockito.when(_zoneDao.findById(ZONE_ID)).thenReturn(zone);
        PhysicalNetworkVO physicalNetwork = Mockito.mock(PhysicalNetworkVO.class);
        Mockito.when(_physicalNetworkDao.listByZone(ZONE_ID)).thenReturn(List.of(physicalNetwork));

        DeleteZoneCmd cmd = Mockito.mock(DeleteZoneCmd.class);
        Mockito.when(cmd.getId()).thenReturn(ZONE_ID);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.deleteZone(cmd));
        assertTrue(ex.getMessage().contains("physical networks"));
    }

    @Test
    public void deleteZoneRefusesWhenSecondaryStoragePresent() {
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getId()).thenReturn(ZONE_ID);
        Mockito.when(_zoneDao.findById(ZONE_ID)).thenReturn(zone);
        ImageStoreVO imageStore = Mockito.mock(ImageStoreVO.class);
        Mockito.when(_imageStoreDao.findByZone(Mockito.any(ZoneScope.class), Mockito.eq(null))).thenReturn(List.of(imageStore));

        DeleteZoneCmd cmd = Mockito.mock(DeleteZoneCmd.class);
        Mockito.when(cmd.getId()).thenReturn(ZONE_ID);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.deleteZone(cmd));
        assertTrue(ex.getMessage().contains("Secondary storages"));
    }

    // ------------------------------------------------------------------
    // createDefaultSystemNetworks — null-zone short-circuit
    // ------------------------------------------------------------------

    @Test
    public void createDefaultSystemNetworksSkipsWhenZoneMissing() throws Exception {
        Mockito.when(_zoneDao.findById(ZONE_ID)).thenReturn(null);

        service.createDefaultSystemNetworks(ZONE_ID);

        Mockito.verifyNoInteractions(_networkOfferingDao);
        Mockito.verifyNoInteractions(_networkMgr);
    }

    @Test
    public void createDefaultSystemNetworksRunsForExistingZone() throws Exception {
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getId()).thenReturn(ZONE_ID);
        Mockito.when(_zoneDao.findById(ZONE_ID)).thenReturn(zone);
        Mockito.when(_networkOfferingDao.listSystemNetworkOfferings()).thenReturn(new ArrayList<>());

        service.createDefaultSystemNetworks(ZONE_ID);

        Mockito.verify(_networkOfferingDao).listSystemNetworkOfferings();
        Mockito.verifyNoInteractions(_networkMgr);
    }
}
