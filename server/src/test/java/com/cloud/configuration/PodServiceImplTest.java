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
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import org.apache.cloudstack.api.command.admin.network.CreateManagementNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.DeleteManagementNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.network.UpdatePodManagementNetworkIpRangeCmd;
import org.apache.cloudstack.api.command.admin.pod.DeletePodCmd;
import org.apache.cloudstack.context.CallContext;
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
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenterIpAddressVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterIpAddressDao;
import com.cloud.dc.dao.DataCenterLinkLocalIpAddressDao;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.host.dao.HostDao;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.org.Grouping;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.VMInstanceDao;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.annotation.dao.AnnotationDao;

/**
 * Focused tests for {@link PodServiceImpl} — the Phase 4 extraction of
 * pod CRUD and management-network IP-range operations out of
 * {@link ConfigurationManagerImpl}.
 *
 * Behavior is also exercised through the manager's delegating wrappers
 * in {@code ConfigurationManagerTest}; these tests target the service
 * directly so future refactors of the manager don't drop coverage.
 */
@RunWith(MockitoJUnitRunner.class)
public class PodServiceImplTest {

    @Mock private HostPodDao podDao;
    @Mock private DataCenterDao zoneDao;
    @Mock private DataCenterIpAddressDao privateIpAddressDao;
    @Mock private DataCenterLinkLocalIpAddressDao linkLocalIpAllocDao;
    @Mock private VlanDao vlanDao;
    @Mock private CapacityDao capacityDao;
    @Mock private DedicatedResourceDao dedicatedDao;
    @Mock private NetworkModel networkModel;
    @Mock private AccountManager accountMgr;
    @Mock private ConfigurationDao configDao;
    @Mock private MessageBus messageBus;
    @Mock private AnnotationDao annotationDao;
    @Mock private IPAddressDao publicIpAddressDao;
    @Mock private VolumeDao volumeDao;
    @Mock private HostDao hostDao;
    @Mock private VMInstanceDao vmInstanceDao;
    @Mock private ClusterDao clusterDao;

    @InjectMocks
    private PodServiceImpl service;

    private static final long POD_ID = 100L;
    private static final long ZONE_ID = 10L;

    private Account adminAccount;
    private Account normalAccount;
    private UserVO user;

    @Before
    public void setUp() {
        // The PodServiceImpl uses underscore-prefixed field names (consistent
        // with the manager); InjectMocks matches by *type* and binds these.
        // Verify wiring landed correctly.
        ReflectionTestUtils.setField(service, "_podDao", podDao);
        ReflectionTestUtils.setField(service, "_zoneDao", zoneDao);
        ReflectionTestUtils.setField(service, "_privateIpAddressDao", privateIpAddressDao);
        ReflectionTestUtils.setField(service, "_linkLocalIpAllocDao", linkLocalIpAllocDao);
        ReflectionTestUtils.setField(service, "_vlanDao", vlanDao);
        ReflectionTestUtils.setField(service, "_capacityDao", capacityDao);
        ReflectionTestUtils.setField(service, "_dedicatedDao", dedicatedDao);
        ReflectionTestUtils.setField(service, "_networkModel", networkModel);
        ReflectionTestUtils.setField(service, "_accountMgr", accountMgr);
        ReflectionTestUtils.setField(service, "_configDao", configDao);
        ReflectionTestUtils.setField(service, "messageBus", messageBus);
        ReflectionTestUtils.setField(service, "annotationDao", annotationDao);
        ReflectionTestUtils.setField(service, "_publicIpAddressDao", publicIpAddressDao);
        ReflectionTestUtils.setField(service, "_volumeDao", volumeDao);
        ReflectionTestUtils.setField(service, "_hostDao", hostDao);
        ReflectionTestUtils.setField(service, "_vmInstanceDao", vmInstanceDao);
        ReflectionTestUtils.setField(service, "_clusterDao", clusterDao);

        adminAccount = new AccountVO("admin", 1, "domain", Account.Type.ADMIN, UUID.randomUUID().toString());
        normalAccount = new AccountVO("normal", 1, "domain", Account.Type.NORMAL, UUID.randomUUID().toString());
        user = new UserVO(1, "u", "p", "f", "l", "e", "tz", UUID.randomUUID().toString(), User.Source.UNKNOWN);
    }

    @After
    public void tearDown() {
        // CallContext is thread-local; unregister any registration to keep
        // tests independent.
        while (CallContext.unregister() != null) {
            // drain
        }
    }

    // ---------------------------------------------------------------------
    // deletePod — validation paths (transaction body is not exercised)
    // ---------------------------------------------------------------------

    @Test
    public void deletePodThrowsWhenPodDoesNotExist() {
        DeletePodCmd cmd = Mockito.mock(DeletePodCmd.class);
        Mockito.when(cmd.getId()).thenReturn(POD_ID);
        Mockito.when(podDao.findById(POD_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.deletePod(cmd));
        assertTrue(ex.getMessage().contains("does not exist"));
    }

    @Test
    public void deletePodThrowsWhenPrivateIpsStillAllocated() {
        DeletePodCmd cmd = Mockito.mock(DeletePodCmd.class);
        Mockito.when(cmd.getId()).thenReturn(POD_ID);
        HostPodVO pod = Mockito.mock(HostPodVO.class);
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        // checkIfPodIsDeletable: any allocated private IP throws
        Mockito.when(privateIpAddressDao.countIPs(POD_ID, ZONE_ID, true)).thenReturn(3);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.deletePod(cmd));
        assertTrue(ex.getMessage().contains("private IP addresses"));
    }

    @Test
    public void deletePodThrowsWhenHostsStillInPod() {
        DeletePodCmd cmd = Mockito.mock(DeletePodCmd.class);
        Mockito.when(cmd.getId()).thenReturn(POD_ID);
        HostPodVO pod = Mockito.mock(HostPodVO.class);
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(privateIpAddressDao.countIPs(POD_ID, ZONE_ID, true)).thenReturn(0);
        Mockito.when(volumeDao.findByPod(POD_ID)).thenReturn(Collections.emptyList());
        Mockito.when(hostDao.findByPodId(POD_ID)).thenReturn(Arrays.asList(Mockito.mock(com.cloud.host.HostVO.class)));

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.deletePod(cmd));
        assertTrue(ex.getMessage().contains("servers"));
    }

    // ---------------------------------------------------------------------
    // createPodIpRange — validation paths
    // ---------------------------------------------------------------------

    @Test
    public void createPodIpRangeThrowsForNonRootAdmin() {
        CallContext.register(user, normalAccount);
        Mockito.when(accountMgr.isRootAdmin(normalAccount.getId())).thenReturn(false);
        CreateManagementNetworkIpRangeCmd cmd = Mockito.mock(CreateManagementNetworkIpRangeCmd.class);

        PermissionDeniedException ex = assertThrows(PermissionDeniedException.class,
                () -> service.createPodIpRange(cmd));
        assertTrue(ex.getMessage().contains("root admin"));
    }

    @Test
    public void createPodIpRangeThrowsWhenPodNotFound() {
        CallContext.register(user, adminAccount);
        Mockito.when(accountMgr.isRootAdmin(adminAccount.getId())).thenReturn(true);
        CreateManagementNetworkIpRangeCmd cmd = Mockito.mock(CreateManagementNetworkIpRangeCmd.class);
        Mockito.when(cmd.getPodId()).thenReturn(POD_ID);
        Mockito.when(cmd.getGateWay()).thenReturn("10.0.0.1");
        Mockito.when(cmd.getNetmask()).thenReturn("255.255.255.0");
        Mockito.when(cmd.getStartIp()).thenReturn("10.0.0.10");
        Mockito.when(cmd.getEndIp()).thenReturn("10.0.0.20");
        Mockito.when(cmd.getVlan()).thenReturn(BroadcastDomainType.Vlan.toUri("100").toString());
        Mockito.when(podDao.findById(POD_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createPodIpRange(cmd));
        assertTrue(ex.getMessage().contains("Unable to find pod"));
    }

    @Test
    public void createPodIpRangeThrowsForInvalidGateway() {
        CallContext.register(user, adminAccount);
        Mockito.when(accountMgr.isRootAdmin(adminAccount.getId())).thenReturn(true);
        CreateManagementNetworkIpRangeCmd cmd = Mockito.mock(CreateManagementNetworkIpRangeCmd.class);
        Mockito.when(cmd.getPodId()).thenReturn(POD_ID);
        Mockito.when(cmd.getGateWay()).thenReturn("not-an-ip");
        Mockito.when(cmd.getNetmask()).thenReturn("255.255.255.0");
        Mockito.when(cmd.getVlan()).thenReturn(BroadcastDomainType.Vlan.toUri("100").toString());
        Mockito.when(podDao.findById(POD_ID)).thenReturn(Mockito.mock(HostPodVO.class));

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createPodIpRange(cmd));
        assertTrue(ex.getMessage().contains("gateway IP address is invalid"));
    }

    @Test
    public void createPodIpRangeThrowsForInvalidNetmask() {
        CallContext.register(user, adminAccount);
        Mockito.when(accountMgr.isRootAdmin(adminAccount.getId())).thenReturn(true);
        CreateManagementNetworkIpRangeCmd cmd = Mockito.mock(CreateManagementNetworkIpRangeCmd.class);
        Mockito.when(cmd.getPodId()).thenReturn(POD_ID);
        Mockito.when(cmd.getGateWay()).thenReturn("10.0.0.1");
        Mockito.when(cmd.getNetmask()).thenReturn("bogus");
        Mockito.when(cmd.getVlan()).thenReturn(BroadcastDomainType.Vlan.toUri("100").toString());
        Mockito.when(podDao.findById(POD_ID)).thenReturn(Mockito.mock(HostPodVO.class));

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createPodIpRange(cmd));
        assertTrue(ex.getMessage().contains("netmask IP address is invalid"));
    }

    // ---------------------------------------------------------------------
    // deletePodIpRange — validation paths
    // ---------------------------------------------------------------------

    @Test
    public void deletePodIpRangeThrowsWhenPodMissing() {
        DeleteManagementNetworkIpRangeCmd cmd = Mockito.mock(DeleteManagementNetworkIpRangeCmd.class);
        Mockito.when(cmd.getPodId()).thenReturn(POD_ID);
        Mockito.when(cmd.getStartIp()).thenReturn("10.0.0.10");
        Mockito.when(cmd.getEndIp()).thenReturn("10.0.0.20");
        Mockito.when(cmd.getVlan()).thenReturn(BroadcastDomainType.Vlan.toUri("100").toString());
        Mockito.when(podDao.findById(POD_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.deletePodIpRange(cmd));
        assertTrue(ex.getMessage().contains("Unable to find pod"));
    }

    @Test
    public void deletePodIpRangeThrowsForInvalidStartIp() throws ResourceUnavailableException, ConcurrentOperationException {
        DeleteManagementNetworkIpRangeCmd cmd = Mockito.mock(DeleteManagementNetworkIpRangeCmd.class);
        Mockito.when(cmd.getPodId()).thenReturn(POD_ID);
        Mockito.when(cmd.getStartIp()).thenReturn("not-an-ip");
        Mockito.when(cmd.getEndIp()).thenReturn("10.0.0.20");
        Mockito.when(cmd.getVlan()).thenReturn(BroadcastDomainType.Vlan.toUri("100").toString());
        Mockito.when(podDao.findById(POD_ID)).thenReturn(Mockito.mock(HostPodVO.class));

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.deletePodIpRange(cmd));
        assertTrue(ex.getMessage().contains("start address"));
    }

    @Test
    public void deletePodIpRangeThrowsWhenIpsStillAllocated() throws Exception {
        DeleteManagementNetworkIpRangeCmd cmd = Mockito.mock(DeleteManagementNetworkIpRangeCmd.class);
        Mockito.when(cmd.getPodId()).thenReturn(POD_ID);
        Mockito.when(cmd.getStartIp()).thenReturn("10.0.0.10");
        Mockito.when(cmd.getEndIp()).thenReturn("10.0.0.10");
        Mockito.when(cmd.getVlan()).thenReturn(BroadcastDomainType.Vlan.toUri("100").toString());
        HostPodVO pod = Mockito.mock(HostPodVO.class);
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(privateIpAddressDao.countIpAddressUsage(Mockito.anyString(), Mockito.eq(POD_ID), Mockito.eq(ZONE_ID), Mockito.eq(true))).thenReturn(1);

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.deletePodIpRange(cmd));
        assertTrue(ex.getMessage().contains("allocated"));
    }

    // ---------------------------------------------------------------------
    // updatePodIpRange — validation paths
    // ---------------------------------------------------------------------

    @Test
    public void updatePodIpRangeThrowsWhenPodMissing() {
        UpdatePodManagementNetworkIpRangeCmd cmd = Mockito.mock(UpdatePodManagementNetworkIpRangeCmd.class);
        Mockito.when(cmd.getPodId()).thenReturn(POD_ID);
        Mockito.when(podDao.findById(POD_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.updatePodIpRange(cmd));
        assertTrue(ex.getMessage().contains("Unable to find pod"));
    }

    @Test
    public void updatePodIpRangeThrowsWhenNewRangeEqualsCurrent() {
        UpdatePodManagementNetworkIpRangeCmd cmd = Mockito.mock(UpdatePodManagementNetworkIpRangeCmd.class);
        Mockito.when(cmd.getPodId()).thenReturn(POD_ID);
        Mockito.when(cmd.getCurrentStartIP()).thenReturn("10.0.0.10");
        Mockito.when(cmd.getCurrentEndIP()).thenReturn("10.0.0.20");
        Mockito.when(cmd.getNewStartIP()).thenReturn("10.0.0.10");
        Mockito.when(cmd.getNewEndIP()).thenReturn("10.0.0.20");
        HostPodVO pod = Mockito.mock(HostPodVO.class);
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.updatePodIpRange(cmd));
        assertTrue(ex.getMessage().contains("same as current"));
    }

    // ---------------------------------------------------------------------
    // createPod (8-arg) — validation paths
    // ---------------------------------------------------------------------

    @Test
    public void createPodThrowsForInvalidZone() {
        CallContext.register(user, adminAccount);
        Mockito.when(zoneDao.findById(ZONE_ID)).thenReturn(null);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createPod(ZONE_ID, "p", "10.0.0.10", "10.0.0.20", "10.0.0.1", "255.255.255.0", null, null));
        assertTrue(ex.getMessage().contains("valid zone"));
    }

    @Test
    public void createPodThrowsForDisabledZoneAndNonRootAdmin() {
        CallContext.register(user, normalAccount);
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Disabled);
        Mockito.when(zoneDao.findById(ZONE_ID)).thenReturn(zone);
        Mockito.when(accountMgr.isRootAdmin(normalAccount.getId())).thenReturn(false);

        PermissionDeniedException ex = assertThrows(PermissionDeniedException.class,
                () -> service.createPod(ZONE_ID, "p", "10.0.0.10", "10.0.0.20", "10.0.0.1", "255.255.255.0", null, null));
        assertTrue(ex.getMessage().contains("currently disabled"));
    }

    @Test
    public void createPodThrowsWhenEdgeZoneGetsIpRangeParameters() {
        CallContext.register(user, adminAccount);
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);
        Mockito.when(zone.getType()).thenReturn(DataCenter.Type.Edge);
        Mockito.when(zoneDao.findById(ZONE_ID)).thenReturn(zone);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createPod(ZONE_ID, "p", "10.0.0.10", "10.0.0.20", "10.0.0.1", "255.255.255.0", null, null));
        assertTrue(ex.getMessage().contains("edge zone"));
    }

    @Test
    public void createPodThrowsForInvalidStartIpInNonEdgeZone() {
        CallContext.register(user, adminAccount);
        DataCenterVO zone = Mockito.mock(DataCenterVO.class);
        Mockito.when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);
        Mockito.when(zone.getType()).thenReturn(DataCenter.Type.Core);
        Mockito.when(zoneDao.findById(ZONE_ID)).thenReturn(zone);

        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.createPod(ZONE_ID, "p", "not-an-ip", "10.0.0.20", "10.0.0.1", "255.255.255.0", null, null));
        assertTrue(ex.getMessage().contains("start IP"));
    }

    // ---------------------------------------------------------------------
    // Helper methods (small protected utilities)
    // ---------------------------------------------------------------------

    @Test
    public void getCidrAddressSplitsCidrString() {
        assertEquals("10.0.0.0", service.getCidrAddress("10.0.0.0/24"));
    }

    @Test
    public void getCidrSizeReturnsPrefixLength() {
        assertEquals(24, service.getCidrSize("10.0.0.0/24"));
    }

    @Test
    public void verifyIpRangeParametersThrowsForInvalidStartIp() {
        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.verifyIpRangeParameters("not-an-ip", "10.0.0.20"));
        assertTrue(ex.getMessage().contains("not a valid IP"));
    }

    @Test
    public void verifyIpRangeParametersThrowsWhenStartAfterEnd() {
        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.verifyIpRangeParameters("10.0.0.50", "10.0.0.10"));
        assertTrue(ex.getMessage().contains("lower value"));
    }

    @Test
    public void listAllIPsWithintheRangeReturnsAllIpsInclusive() {
        List<Long> ips = service.listAllIPsWithintheRange("10.0.0.10", "10.0.0.12");
        assertEquals(3, ips.size());
    }

    @Test
    public void checkIpRangeAllowsValidIpsInsideCidr() {
        // Should not throw
        service.checkIpRange("10.0.0.10", "10.0.0.20", "10.0.0.0", 24L);
    }

    @Test
    public void checkIpRangeThrowsWhenStartOutsideCidrSubnet() {
        InvalidParameterValueException ex = assertThrows(InvalidParameterValueException.class,
                () -> service.checkIpRange("192.168.1.10", "192.168.1.20", "10.0.0.0", 24L));
        assertTrue(ex.getMessage().contains("not in the CIDR"));
    }

    @Test
    public void validPodByIdReturnsTrueWhenPresent() {
        Mockito.when(podDao.findById(POD_ID)).thenReturn(Mockito.mock(HostPodVO.class));
        assertTrue(service.validPod(POD_ID));
    }

    @Test
    public void validPodByIdReturnsFalseWhenAbsent() {
        Mockito.when(podDao.findById(POD_ID)).thenReturn(null);
        assertFalse(service.validPod(POD_ID));
    }

    @Test
    public void validPodByNameReturnsTrueWhenPresent() {
        Mockito.when(podDao.findByName("pod-1", ZONE_ID)).thenReturn(Mockito.mock(HostPodVO.class));
        assertTrue(service.validPod("pod-1", ZONE_ID));
    }

    @Test
    public void podHasAllocatedPrivateIPsReadsFromPrivateIpDao() {
        HostPodVO pod = Mockito.mock(HostPodVO.class);
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(privateIpAddressDao.countIPs(POD_ID, ZONE_ID, true)).thenReturn(5);

        assertTrue(service.podHasAllocatedPrivateIPs(POD_ID));
    }

    @Test
    public void podHasAllocatedPrivateIPsFalseWhenZero() {
        HostPodVO pod = Mockito.mock(HostPodVO.class);
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(privateIpAddressDao.countIPs(POD_ID, ZONE_ID, true)).thenReturn(0);

        assertFalse(service.podHasAllocatedPrivateIPs(POD_ID));
    }

    @Test
    public void checkIfPodIsDeletablePassesWhenAllEmpty() {
        HostPodVO pod = Mockito.mock(HostPodVO.class);
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(privateIpAddressDao.countIPs(POD_ID, ZONE_ID, true)).thenReturn(0);
        Mockito.when(volumeDao.findByPod(POD_ID)).thenReturn(Collections.emptyList());
        Mockito.when(hostDao.findByPodId(POD_ID)).thenReturn(Collections.emptyList());
        Mockito.when(vmInstanceDao.listByPodId(POD_ID)).thenReturn(Collections.emptyList());
        Mockito.when(clusterDao.listByPodId(POD_ID)).thenReturn(Collections.emptyList());

        service.checkIfPodIsDeletable(POD_ID);
        // No exception expected.
        assertNotNull(pod);
    }

    @Test
    public void checkIfPodIsDeletableThrowsWhenVolumesPresent() {
        HostPodVO pod = Mockito.mock(HostPodVO.class);
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        Mockito.when(podDao.findById(POD_ID)).thenReturn(pod);
        Mockito.when(privateIpAddressDao.countIPs(POD_ID, ZONE_ID, true)).thenReturn(0);
        Mockito.when(volumeDao.findByPod(POD_ID)).thenReturn(Arrays.asList(Mockito.mock(com.cloud.storage.VolumeVO.class)));

        CloudRuntimeException ex = assertThrows(CloudRuntimeException.class,
                () -> service.checkIfPodIsDeletable(POD_ID));
        assertTrue(ex.getMessage().contains("storage volumes"));
    }

    @Test
    public void getVlanNumberFromUriExtractsTaggedNumber() {
        assertEquals("42", service.getVlanNumberFromUri(BroadcastDomainType.Vlan.toUri("42").toString()));
    }

    @Test
    public void checkIpRangeContainsTakenAddressesAllowsWhenAllTakenIpsInNewRange() {
        HostPodVO pod = Mockito.mock(HostPodVO.class);
        Mockito.when(pod.getId()).thenReturn(POD_ID);
        Mockito.when(pod.getDataCenterId()).thenReturn(ZONE_ID);
        // No taken IPs reported.
        Mockito.when(privateIpAddressDao.listIpAddressUsage(POD_ID, ZONE_ID, true)).thenReturn(Collections.<DataCenterIpAddressVO>emptyList());

        // No exception expected.
        service.checkIpRangeContainsTakenAddresses(pod, "10.0.0.10", "10.0.0.20", "10.0.0.5", "10.0.0.30");
        assertNotNull(pod);
    }
}
