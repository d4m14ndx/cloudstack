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

import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network.Service;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcManager;
import com.cloud.network.vpc.VpcOfferingVO;
import com.cloud.network.vpc.VpcVO;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.network.vpc.dao.VpcOfferingDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.server.ResourceTag;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.User;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class NetworkMigrationServiceImplTest {

    @Mock NetworkDao _networksDao;
    @Mock NetworkOfferingDao _networkOfferingDao;
    @Mock DataCenterDao _dcDao;
    @Mock ResourceTagDao _resourceTagDao;
    @Mock VpcDao _vpcDao;
    @Mock VMInstanceDao _vmDao;
    @Mock IPAddressDao _ipAddressDao;
    @Mock VlanDao _vlanDao;
    @Mock FirewallRulesDao _firewallDao;
    @Mock NetworkOfferingServiceMapDao _ntwkOfferingSrvcDao;
    @Mock ConfigurationManager _configMgr;
    @Mock AccountManager _accountMgr;
    @Mock NetworkOrchestrationService _networkMgr;
    @Mock NetworkMigrationManager _networkMigrationManager;
    @Mock VpcOfferingDao _vpcOfferingDao;
    @Mock AccountService _accountService;
    @Mock VpcManager _vpcMgr;
    @Mock NetworkModel _networkModel;
    @Mock Account account;
    @Mock User callerUser;

    @InjectMocks
    NetworkMigrationServiceImpl service;

    private NetworkVO guestNetwork;
    private NetworkVO migratedNetwork;
    private NetworkVO oldNetworkCopy;
    private NetworkOfferingVO oldOffering;
    private NetworkOfferingVO newOffering;
    private DataCenterVO zone;

    @Before
    public void setUp() {
        guestNetwork = mockNetwork(10L, 100L, null, 1L, 10L, 300L, Network.State.Implemented, TrafficType.Guest);
        migratedNetwork = mockNetwork(10L, 200L, null, 1L, 10L, 500L, Network.State.Allocated, TrafficType.Guest);
        oldNetworkCopy = mockNetwork(99L, 100L, null, 1L, 99L, 300L, Network.State.Implemented, TrafficType.Guest);
        oldOffering = mockOffering(100L, "old-off", Network.GuestType.Isolated, TrafficType.Guest);
        newOffering = mockOffering(200L, "new-off", Network.GuestType.Isolated, TrafficType.Guest);
        zone = mock(DataCenterVO.class);

        lenient().when(_networkOfferingDao.findByIdIncludingRemoved(100L)).thenReturn(oldOffering);
        lenient().when(_networkOfferingDao.findById(200L)).thenReturn(newOffering);
        lenient().when(_networkModel.findPhysicalNetworkId(anyLong(), any(), any())).thenReturn(500L);
        lenient().when(_networkMgr.finalizeServicesAndProvidersForNetwork(any(NetworkOffering.class), anyLong())).thenReturn(Collections.emptyMap());
        lenient().when(_vmDao.listNonRemovedVmsByTypeAndNetwork(anyLong(), isNull())).thenReturn(Collections.emptyList());
        lenient().when(_ipAddressDao.listByAssociatedNetwork(anyLong(), isNull())).thenReturn(Collections.emptyList());
        lenient().when(_networkModel.getNetworkOfferingServiceProvidersMap(anyLong())).thenReturn(Collections.emptyMap());
        lenient().when(_dcDao.findById(anyLong())).thenReturn(zone);
    }

    @Test
    public void migrateGuestNetwork_rejectsVpcTier() {
        guestNetwork = mockNetwork(10L, 100L, 50L, 1L, 1L, 300L, Network.State.Implemented, TrafficType.Guest);
        when(_networksDao.findById(10L)).thenReturn(guestNetwork);
        when(_networkOfferingDao.findById(200L)).thenReturn(newOffering);

        assertThrows(InvalidParameterValueException.class,
                () -> service.migrateGuestNetwork(10L, 200L, account, callerUser, false));
    }

    @Test
    public void migrateGuestNetwork_rejectsVpcOffering() {
        when(_networksDao.findById(10L)).thenReturn(guestNetwork);
        when(_networkOfferingDao.findById(200L)).thenReturn(newOffering);
        when(_configMgr.isOfferingForVpc(newOffering)).thenReturn(true);

        assertThrows(InvalidParameterValueException.class,
                () -> service.migrateGuestNetwork(10L, 200L, account, callerUser, false));
    }

    @Test
    public void migrateGuestNetwork_resumeFalseWithTransientRelated_throws() {
        guestNetwork = mockNetwork(10L, 100L, null, 1L, 99L, 300L, Network.State.Implemented, TrafficType.Guest);
        when(_networksDao.findById(10L)).thenReturn(guestNetwork);
        when(_networkOfferingDao.findById(200L)).thenReturn(newOffering);
        when(_configMgr.isOfferingForVpc(newOffering)).thenReturn(false);

        assertThrows(CloudRuntimeException.class,
                () -> service.migrateGuestNetwork(10L, 200L, account, callerUser, false));
    }

    @Test
    public void migrateGuestNetwork_noMigrationNeeded_returnsOriginalNetwork() {
        when(_networksDao.findById(10L)).thenReturn(guestNetwork);
        when(_networkOfferingDao.findById(100L)).thenReturn(oldOffering);
        when(_networkOfferingDao.findByIdIncludingRemoved(100L)).thenReturn(oldOffering);
        when(_configMgr.isOfferingForVpc(oldOffering)).thenReturn(false);

        Network result = service.migrateGuestNetwork(10L, 100L, account, callerUser, false);

        assertSame(guestNetwork, result);
        verify(_networkMigrationManager, never()).makeCopyOfNetwork(any(Network.class), any(NetworkOffering.class), any());
    }

    @Test
    public void migrateGuestNetwork_needsMigration_callsCopyUpgradeAssignDelete() throws Exception {
        when(_networksDao.findById(10L)).thenReturn(guestNetwork, guestNetwork);
        when(_networkOfferingDao.findById(200L)).thenReturn(newOffering);
        when(_configMgr.isOfferingForVpc(newOffering)).thenReturn(false);
        when(_networkMigrationManager.makeCopyOfNetwork(guestNetwork, oldOffering, null)).thenReturn(99L);
        when(_networkMigrationManager.upgradeNetworkToNewNetworkOffering(10L, 500L, 200L, null)).thenReturn(migratedNetwork);
        when(_networksDao.findById(99L)).thenReturn(oldNetworkCopy);
        when(_networkMgr.implementNetwork(anyLong(), any(), any(ReservationContext.class))).thenReturn((Pair) new Pair<>(null, migratedNetwork));

        Network result = service.migrateGuestNetwork(10L, 200L, account, callerUser, false);

        assertSame(guestNetwork, result);
        verify(_networkMigrationManager).makeCopyOfNetwork(guestNetwork, oldOffering, null);
        verify(_networkMigrationManager).upgradeNetworkToNewNetworkOffering(10L, 500L, 200L, null);
        verify(_networkMigrationManager).assignNicsToNewPhysicalNetwork(oldNetworkCopy, migratedNetwork);
        verify(_networkMigrationManager).deleteCopyOfNetwork(99L, 10L);
    }

    @Test
    public void migrateNetworkToPhysicalNetwork_resumePathRejectsMismatchedOffering() {
        guestNetwork = mockNetwork(10L, 100L, null, 1L, 99L, 300L, Network.State.Implemented, TrafficType.Guest);
        migratedNetwork = mockNetwork(10L, 201L, null, 1L, 99L, 500L, Network.State.Allocated, TrafficType.Guest);

        assertThrows(InvalidParameterValueException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "migrateNetworkToPhysicalNetwork", migratedNetwork, oldOffering, newOffering, null, null, 500L, account, callerUser));
    }

    @Test
    public void migrateNetworkToPhysicalNetwork_implementsPersistentNetworkWhenNeeded() throws Exception {
        when(_networkMigrationManager.makeCopyOfNetwork(guestNetwork, oldOffering, null)).thenReturn(99L);
        when(_networkMigrationManager.upgradeNetworkToNewNetworkOffering(10L, 500L, 200L, null)).thenReturn(migratedNetwork);
        when(_networksDao.findById(99L)).thenReturn(oldNetworkCopy);
        when(_networkMgr.implementNetwork(anyLong(), any(), any(ReservationContext.class))).thenReturn((Pair) new Pair<>(null, migratedNetwork));
        when(_networksDao.findById(10L)).thenReturn(guestNetwork);

        Network result = ReflectionTestUtils.invokeMethod(service,
                "migrateNetworkToPhysicalNetwork", guestNetwork, oldOffering, newOffering, null, null, 500L, account, callerUser);

        assertSame(guestNetwork, result);
        verify(_networkMgr).implementNetwork(anyLong(), any(), any(ReservationContext.class));
    }

    @Test
    public void migrateNetworkToPhysicalNetwork_implementationFailure_wrapsCloudRuntimeException() throws Exception {
        when(_networkMigrationManager.makeCopyOfNetwork(guestNetwork, oldOffering, null)).thenReturn(99L);
        when(_networkMigrationManager.upgradeNetworkToNewNetworkOffering(10L, 500L, 200L, null)).thenReturn(migratedNetwork);
        when(_networksDao.findById(99L)).thenReturn(oldNetworkCopy);
        doThrow(new CloudRuntimeException("boom")).when(_networkMgr).implementNetwork(anyLong(), any(), any(ReservationContext.class));

        assertThrows(CloudRuntimeException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "migrateNetworkToPhysicalNetwork", guestNetwork, oldOffering, newOffering, null, null, 500L, account, callerUser));
    }

    @Test
    public void migrateVpcNetwork_existingMigrationWithoutResume_throws() {
        ResourceTag relatedVpc = mock(ResourceTag.class);
        when(_resourceTagDao.findByKey(20L, ResourceTag.ResourceObjectType.Vpc, NetworkMigrationManager.MIGRATION)).thenReturn(relatedVpc);

        assertThrows(CloudRuntimeException.class,
                () -> service.migrateVpcNetwork(20L, 300L, new HashMap<>(), account, callerUser, false));
    }

    @Test
    public void migrateVpcNetwork_resumeValidatesAlreadyMigratedTiers() {
        ResourceTag relatedVpc = mock(ResourceTag.class);
        VpcVO originalVpc = mock(VpcVO.class);
        VpcVO migratedVpc = mock(VpcVO.class);
        NetworkVO migratedTier = mockNetwork(30L, 200L, null, 1L, 30L, 500L, Network.State.Implemented, TrafficType.Guest);
        Map<String, String> mappings = Map.of("tier-uuid", "new-off-uuid");

        when(relatedVpc.getValue()).thenReturn("20");
        when(_resourceTagDao.findByKey(30L, ResourceTag.ResourceObjectType.Vpc, NetworkMigrationManager.MIGRATION)).thenReturn(relatedVpc);
        when(_vpcDao.findById(30L)).thenReturn(migratedVpc);
        when(_vpcDao.findById(20L)).thenReturn(originalVpc);
        when(migratedVpc.getVpcOfferingId()).thenReturn(300L);
        when(migratedTier.getUuid()).thenReturn("tier-uuid");
        when(_networksDao.listByVpc(30L)).thenReturn(List.of(migratedTier));
        when(_networkOfferingDao.findByUuid("new-off-uuid")).thenReturn(newOffering);
        when(_vpcOfferingDao.findById(300L)).thenReturn(mock(VpcOfferingVO.class));
        when(originalVpc.getZoneId()).thenReturn(1L);
        when(originalVpc.getVpcOfferingId()).thenReturn(300L);

        Vpc result = service.migrateVpcNetwork(30L, 300L, mappings, account, callerUser, true);

        assertSame(originalVpc, result);
        verify(_accountMgr).checkAccess(account, null, true, originalVpc);
    }

    @Test
    public void migrateVpcNetwork_sameVpcOffering_returnsVpcWithoutWork() {
        VpcVO vpc = mock(VpcVO.class);

        when(_resourceTagDao.findByKey(20L, ResourceTag.ResourceObjectType.Vpc, NetworkMigrationManager.MIGRATION)).thenReturn(null);
        when(_vpcDao.findById(20L)).thenReturn(vpc);
        when(vpc.getZoneId()).thenReturn(1L);
        when(vpc.getVpcOfferingId()).thenReturn(300L);
        when(_vpcOfferingDao.findById(300L)).thenReturn(mock(VpcOfferingVO.class));

        Vpc result = service.migrateVpcNetwork(20L, 300L, new HashMap<>(), account, callerUser, false);

        assertSame(vpc, result);
        verify(_networkMigrationManager, never()).makeCopyOfVpc(anyLong(), anyLong());
    }

    @Test
    public void vpcTiersCanBeMigrated_missingTierMapping_throwsInvalidParameter() {
        NetworkVO tier = mockNetwork(30L, 100L, null, 1L, 30L, 300L, Network.State.Implemented, TrafficType.Guest);

        assertThrows(InvalidParameterValueException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "vpcTiersCanBeMigrated", List.of(tier), account, Collections.emptyMap(), false));
    }

    @Test
    public void verifyAlreadyMigratedTiers_mismatchedOffering_throws() {
        VpcVO migratedVpc = mock(VpcVO.class);

        when(_vpcDao.findById(30L)).thenReturn(migratedVpc);
        when(migratedVpc.getVpcOfferingId()).thenReturn(301L);
        when(_vpcOfferingDao.findById(301L)).thenReturn(mock(VpcOfferingVO.class));

        assertThrows(InvalidParameterValueException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "verifyAlreadyMigratedTiers", 30L, 300L, Collections.emptyMap()));
    }

    @Test
    public void canMoveToPhysicalNetwork_lbModeMismatch_throws() {
        when(_networkOfferingDao.findByIdIncludingRemoved(100L)).thenReturn(oldOffering);
        when(_networkOfferingDao.findById(200L)).thenReturn(newOffering);
        when(_ntwkOfferingSrvcDao.areServicesSupportedByNetworkOffering(100L, Service.Lb)).thenReturn(true);
        when(_ntwkOfferingSrvcDao.areServicesSupportedByNetworkOffering(200L, Service.Lb)).thenReturn(true);
        when(oldOffering.isPublicLb()).thenReturn(true);

        assertThrows(InvalidParameterValueException.class,
                () -> service.canMoveToPhysicalNetwork(guestNetwork, 100L, 200L));
    }

    @Test
    public void networkNeedsMigration_disabledNewOffering_throws() {
        when(newOffering.getState()).thenReturn(NetworkOffering.State.Disabled);

        assertThrows(InvalidParameterValueException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "networkNeedsMigration", guestNetwork, 500L, oldOffering, newOffering));
    }

    @Test
    public void networkNeedsMigration_transitioningVm_throws() {
        com.cloud.vm.VMInstanceVO transitioningVm = mock(com.cloud.vm.VMInstanceVO.class);
        when(_vmDao.listNonRemovedVmsByTypeAndNetwork(10L, null)).thenReturn(List.of(transitioningVm));
        when(transitioningVm.getState()).thenReturn(VirtualMachine.State.Starting);

        assertThrows(CloudRuntimeException.class, () -> ReflectionTestUtils.invokeMethod(service,
                "networkNeedsMigration", guestNetwork, 500L, oldOffering, newOffering));
    }

    private NetworkVO mockNetwork(long id, long offeringId, Long vpcId, long dataCenterId, long relatedId,
            long physicalNetworkId, Network.State state, TrafficType trafficType) {
        NetworkVO network = mock(NetworkVO.class);
        lenient().when(network.getId()).thenReturn(id);
        lenient().when(network.getNetworkOfferingId()).thenReturn(offeringId);
        lenient().when(network.getVpcId()).thenReturn(vpcId);
        lenient().when(network.getDataCenterId()).thenReturn(dataCenterId);
        lenient().when(network.getRelated()).thenReturn(relatedId);
        lenient().when(network.getPhysicalNetworkId()).thenReturn(physicalNetworkId);
        lenient().when(network.getState()).thenReturn(state);
        lenient().when(network.getTrafficType()).thenReturn(trafficType);
        lenient().when(network.getUuid()).thenReturn("network-" + id);
        lenient().when(network.getName()).thenReturn("network-" + id);
        lenient().when(network.getAccountId()).thenReturn(1L);
        lenient().when(network.getCidr()).thenReturn("10.0.0.0/24");
        lenient().when(network.getNetworkDomain()).thenReturn("example.com");
        lenient().when(network.getGateway()).thenReturn("10.0.0.1");
        lenient().when(network.getNetworkACLId()).thenReturn(1L);
        return network;
    }

    private NetworkOfferingVO mockOffering(long id, String uuid, Network.GuestType guestType, TrafficType trafficType) {
        NetworkOfferingVO offering = mock(NetworkOfferingVO.class);
        lenient().when(offering.getId()).thenReturn(id);
        lenient().when(offering.getUuid()).thenReturn(uuid);
        lenient().when(offering.getName()).thenReturn(uuid);
        lenient().when(offering.getGuestType()).thenReturn(guestType);
        lenient().when(offering.getTrafficType()).thenReturn(trafficType);
        lenient().when(offering.isSpecifyIpRanges()).thenReturn(false);
        lenient().when(offering.isConserveMode()).thenReturn(false);
        lenient().when(offering.isSystemOnly()).thenReturn(false);
        lenient().when(offering.isPersistent()).thenReturn(true);
        lenient().when(offering.getState()).thenReturn(NetworkOffering.State.Enabled);
        lenient().when(offering.getTags()).thenReturn("tag");
        lenient().when(offering.isPublicLb()).thenReturn(false);
        lenient().when(offering.isInternalLb()).thenReturn(false);
        return offering;
    }
}
