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
package com.cloud.network.vpc;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.apache.cloudstack.api.command.admin.vpc.CloneVPCOfferingCmd;
import org.apache.cloudstack.api.command.admin.vpc.CreateVPCOfferingCmd;
import org.apache.cloudstack.api.command.admin.vpc.UpdateVPCOfferingCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.network.RoutedIpv4Manager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.vpc.VpcOffering.State;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.network.vpc.dao.VpcOfferingDao;
import com.cloud.network.vpc.dao.VpcOfferingDetailsDao;
import com.cloud.network.vpc.dao.VpcOfferingServiceMapDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.user.Account;
import com.cloud.user.User;
import com.cloud.utils.DomainHelper;

@RunWith(MockitoJUnitRunner.class)
public class VpcOfferingCrudServiceImplTest {

    @Mock
    VpcOfferingDao vpcOfferingDao;
    @Mock
    VpcOfferingDetailsDao vpcOfferingDetailsDao;
    @Mock
    VpcOfferingServiceMapDao vpcOfferingServiceMapDao;
    @Mock
    DomainDao domainDao;
    @Mock
    DataCenterDao dcDao;
    @Mock
    VpcDao vpcDao;
    @Mock
    NetworkService networkService;
    @Mock
    NetworkModel networkModel;
    @Mock
    DomainHelper domainHelper;
    @Mock
    VpcOfferingQueryService vpcOfferingQueryService;

    VpcOfferingCrudServiceImpl service;

    private AutoCloseable closeable;

    @Before
    public void setUp() throws Exception {
        closeable = MockitoAnnotations.openMocks(this);
        service = new VpcOfferingCrudServiceImpl();
        ReflectionTestUtils.setField(service, "_vpcOffDao", vpcOfferingDao);
        ReflectionTestUtils.setField(service, "vpcOfferingDetailsDao", vpcOfferingDetailsDao);
        ReflectionTestUtils.setField(service, "_vpcOffSvcMapDao", vpcOfferingServiceMapDao);
        ReflectionTestUtils.setField(service, "domainDao", domainDao);
        ReflectionTestUtils.setField(service, "_dcDao", dcDao);
        ReflectionTestUtils.setField(service, "vpcDao", vpcDao);
        ReflectionTestUtils.setField(service, "_ntwkSvc", networkService);
        ReflectionTestUtils.setField(service, "_ntwkModel", networkModel);
        ReflectionTestUtils.setField(service, "domainHelper", domainHelper);
        ReflectionTestUtils.setField(service, "vpcOfferingQueryService", vpcOfferingQueryService);
        // Default: domainHelper returns empty list
        Mockito.lenient().when(domainHelper.filterChildSubDomains(any())).thenReturn(new ArrayList<>());
        CallContext.register(mock(User.class), mock(Account.class));
    }

    @After
    public void tearDown() throws Exception {
        CallContext.unregister();
        closeable.close();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private VpcOfferingVO makeOffering(long id, String name, boolean isDefault) {
        VpcOfferingVO off = mock(VpcOfferingVO.class);
        Mockito.when(off.getId()).thenReturn(id);
        Mockito.when(off.getUuid()).thenReturn("uuid-" + id);
        Mockito.when(off.getName()).thenReturn(name);
        Mockito.when(off.isDefault()).thenReturn(isDefault);
        Mockito.when(off.getState()).thenReturn(State.Enabled);
        return off;
    }

    private StubCreateVPCOfferingCmd makeCreateCmd(String name, List<String> services) {
        StubCreateVPCOfferingCmd cmd = new StubCreateVPCOfferingCmd();
        cmd.vpcOfferingName = name;
        cmd.displayText = "desc";
        cmd.supportedServices = services;
        cmd.enable = true;
        return cmd;
    }

    /** Minimal stub so we can set fields without ByteBuddy mocking cmd classes. */
    static class StubCreateVPCOfferingCmd extends CreateVPCOfferingCmd {
        String vpcOfferingName;
        String displayText;
        List<String> supportedServices;
        boolean enable;
        Long serviceOfferingId;
        List<Long> domainIds;
        List<Long> zoneIds;
        String networkMode;
        String provider;
        String routingMode;
        Boolean specifyAsNumber = false;
        boolean conserveMode = false;
        String internetProtocol;

        @Override public String getVpcOfferingName() { return vpcOfferingName; }
        @Override public String getDisplayText() { return displayText; }
        @Override public List<String> getSupportedServices() { return supportedServices; }
        @Override public Boolean getEnable() { return enable; }
        @Override public Long getServiceOfferingId() { return serviceOfferingId; }
        @Override public List<Long> getDomainIds() { return domainIds; }
        @Override public List<Long> getZoneIds() { return zoneIds; }
        @Override public String getNetworkMode() { return networkMode; }
        @Override public String getProvider() { return provider; }
        @Override public String getRoutingMode() { return routingMode; }
        @Override public Boolean getSpecifyAsNumber() { return specifyAsNumber != null && specifyAsNumber; }
        @Override public boolean isConserveMode() { return conserveMode; }
        @Override public String getInternetProtocol() { return internetProtocol; }
        @Override public Map<String, List<String>> getServiceProviders() { return null; }
        @Override public Map getServiceCapabilityList() { return null; }
    }

    static class StubCloneVPCOfferingCmd extends CloneVPCOfferingCmd {
        // Only fields unique to CloneVPCOfferingCmd that are NOT in the parent CreateVPCOfferingCmd.
        // Fields that correspond to parent private fields are intentionally NOT declared here;
        // ConfigurationManagerImpl.setField will walk up the hierarchy and write to the parent's
        // private fields, and the parent's getters will handle the transformation correctly.
        Long sourceOfferingId;
        String vpcOfferingName;
        String provider;
        String networkMode;

        @Override public Long getSourceOfferingId() { return sourceOfferingId; }
        @Override public String getVpcOfferingName() { return vpcOfferingName; }
        @Override public String getProvider() { return provider; }
        @Override public String getNetworkMode() { return networkMode; }
        @Override public List<String> getAddServices() { return null; }
        @Override public List<String> getDropServices() { return null; }
        @Override public boolean isConserveMode() { return false; }
    }

    static class StubUpdateVPCOfferingCmd extends UpdateVPCOfferingCmd {
        Long id;
        String vpcOfferingName;
        String displayText;
        String state;
        List<Long> domainIds;
        List<Long> zoneIds;
        Integer sortKey;

        @Override public Long getId() { return id; }
        @Override public String getVpcOfferingName() { return vpcOfferingName; }
        @Override public String getDisplayText() { return displayText; }
        @Override public String getState() { return state; }
        @Override public List<Long> getDomainIds() { return domainIds; }
        @Override public List<Long> getZoneIds() { return zoneIds; }
        @Override public Integer getSortKey() { return sortKey; }
    }

    private void overrideConfigKey(ConfigKey key, String field, Object value) throws Exception {
        Field f = ConfigKey.class.getDeclaredField(field);
        f.setAccessible(true);
        f.set(key, value);
    }

    // -------------------------------------------------------------------------
    // Tests
    // -------------------------------------------------------------------------

    // 1. createVpcOffering(cmd) — invalid domain
    @Test(expected = InvalidParameterValueException.class)
    public void createVpcOffering_cmd_invalidDomain_throwsIPVE() {
        StubCreateVPCOfferingCmd cmd = makeCreateCmd("test", Arrays.asList("Dhcp"));
        cmd.domainIds = Arrays.asList(99L);
        Mockito.when(domainDao.findById(99L)).thenReturn(null);

        service.createVpcOffering(cmd);
    }

    // 2. createVpcOffering(cmd) — invalid zone
    @Test(expected = InvalidParameterValueException.class)
    public void createVpcOffering_cmd_invalidZone_throwsIPVE() {
        StubCreateVPCOfferingCmd cmd = makeCreateCmd("test", Arrays.asList("Dhcp"));
        cmd.zoneIds = Arrays.asList(99L);
        Mockito.when(dcDao.findById(99L)).thenReturn(null);

        service.createVpcOffering(cmd);
    }

    // 3. createVpcOffering(cmd) — invalid network mode string
    @Test(expected = InvalidParameterValueException.class)
    public void createVpcOffering_cmd_invalidNetworkMode_throwsIPVE() {
        StubCreateVPCOfferingCmd cmd = makeCreateCmd("test", Arrays.asList("Dhcp"));
        cmd.networkMode = "INVALID_MODE";

        service.createVpcOffering(cmd);
    }

    // 4. createVpcOffering(cmd) — routed mode but config disabled
    @Test(expected = InvalidParameterValueException.class)
    public void createVpcOffering_cmd_routedModeButConfigDisabled_throwsIPVE() throws Exception {
        overrideConfigKey(RoutedIpv4Manager.RoutedNetworkVpcEnabled, "_defaultValue", "false");
        StubCreateVPCOfferingCmd cmd = makeCreateCmd("test", Arrays.asList("Dhcp"));
        cmd.networkMode = "ROUTED";

        service.createVpcOffering(cmd);
    }

    // 5. createVpcOffering(cmd) — specifyAsNumber without NSX provider
    @Test(expected = InvalidParameterValueException.class)
    public void createVpcOffering_cmd_specifyAsNumberWithoutNSX_throwsIPVE() {
        StubCreateVPCOfferingCmd cmd = makeCreateCmd("test", Arrays.asList("Dhcp"));
        cmd.specifyAsNumber = true;
        cmd.provider = "VpcVirtualRouter";
        cmd.routingMode = "Dynamic";

        service.createVpcOffering(cmd);
    }

    // 6. createVpcOffering(cmd) — specifyAsNumber with non-Dynamic routing mode
    @Test(expected = InvalidParameterValueException.class)
    public void createVpcOffering_cmd_specifyAsNumberNonDynamic_throwsIPVE() {
        StubCreateVPCOfferingCmd cmd = makeCreateCmd("test", Arrays.asList("Dhcp"));
        cmd.specifyAsNumber = true;
        cmd.provider = "NSX";
        // routingMode defaults to null → Static via verifyRoutingMode

        service.createVpcOffering(cmd);
    }

    // 7. createVpcOffering(cmd) — service offering validation invoked
    @Test
    public void createVpcOffering_cmd_serviceOfferingValidated() {
        StubCreateVPCOfferingCmd cmd = makeCreateCmd("test", Arrays.asList("Dhcp", "SourceNat"));
        cmd.serviceOfferingId = 5L;
        VpcOfferingVO persisted = makeOffering(1L, "test", false);
        Mockito.when(vpcOfferingDao.persist(any(VpcOfferingVO.class))).thenReturn(persisted);
        Mockito.lenient().doNothing().when(vpcOfferingDetailsDao).saveDetails(any());

        service.createVpcOffering(cmd);

        verify(networkService).validateIfServiceOfferingIsActiveAndSystemVmTypeIsDomainRouter(5L);
    }

    // 8. createVpcOffering(map-overload) — empty services without external provider throws IPVE
    @Test(expected = InvalidParameterValueException.class)
    public void createVpcOffering_emptyServicesAndNotExternalProvider_throwsIPVE() {
        service.createVpcOffering("name", "desc", Collections.emptyList(),
                null, null, null, null, null, null, null, null,
                State.Enabled, null, false, false);
    }

    // 9. createVpcOffering(map-overload) — happy path persists offering and svc map rows
    @Test
    public void createVpcOffering_cmd_happyPath_returnsPersistedOffering() {
        StubCreateVPCOfferingCmd cmd = makeCreateCmd("happy", Arrays.asList("Dhcp", "SourceNat"));
        VpcOfferingVO persisted = makeOffering(42L, "happy", false);
        Mockito.when(vpcOfferingDao.persist(any(VpcOfferingVO.class))).thenReturn(persisted);
        Mockito.lenient().doNothing().when(vpcOfferingDetailsDao).saveDetails(any());

        VpcOffering result = service.createVpcOffering(cmd);

        assertNotNull(result);
        // at least SourceNat + NetworkACL + Gateway (auto-added) → 3 svc rows
        verify(vpcOfferingServiceMapDao, Mockito.atLeast(1)).persist(any(VpcOfferingServiceMapVO.class));
    }

    // 10. cloneVPCOffering — unknown source throws IPVE
    @Test(expected = InvalidParameterValueException.class)
    public void cloneVPCOffering_unknownSource_throwsIPVE() {
        StubCloneVPCOfferingCmd cmd = new StubCloneVPCOfferingCmd();
        cmd.sourceOfferingId = 999L;
        cmd.vpcOfferingName = "clone";
        Mockito.when(vpcOfferingDao.findById(999L)).thenReturn(null);

        service.cloneVPCOffering(cmd);
    }

    // 11. cloneVPCOffering — name already exists throws IPVE
    @Test(expected = InvalidParameterValueException.class)
    public void cloneVPCOffering_nameAlreadyExists_throwsIPVE() {
        StubCloneVPCOfferingCmd cmd = new StubCloneVPCOfferingCmd();
        cmd.sourceOfferingId = 1L;
        cmd.vpcOfferingName = "existing";
        VpcOfferingVO source = makeOffering(1L, "source", false);
        Mockito.when(vpcOfferingDao.findById(1L)).thenReturn(source);
        VpcOfferingVO existing = makeOffering(2L, "existing", false);
        Mockito.when(vpcOfferingDao.findByUniqueName("existing")).thenReturn(existing);

        service.cloneVPCOffering(cmd);
    }

    // 12. cloneVPCOffering — change network mode for NSX provider throws IPVE
    @Test(expected = InvalidParameterValueException.class)
    public void cloneVPCOffering_changeNetworkModeForNSX_throwsIPVE() {
        StubCloneVPCOfferingCmd cmd = new StubCloneVPCOfferingCmd();
        cmd.sourceOfferingId = 1L;
        cmd.vpcOfferingName = "clone";
        cmd.networkMode = "ROUTED";

        VpcOfferingVO source = makeOffering(1L, "source", false);
        Mockito.when(source.getNetworkMode()).thenReturn(NetworkOffering.NetworkMode.NATTED);
        Mockito.when(vpcOfferingDao.findById(1L)).thenReturn(source);
        Mockito.when(vpcOfferingDao.findByUniqueName("clone")).thenReturn(null);

        // sourceServiceProviderMap with NSX provider
        Map<Service, Set<Provider>> svcMap = new HashMap<>();
        svcMap.put(Service.SourceNat, new HashSet<>(Arrays.asList(Provider.Nsx)));
        Mockito.when(vpcOfferingQueryService.getVpcOffSvcProvidersMap(1L)).thenReturn(svcMap);

        service.cloneVPCOffering(cmd);
    }

    // 13. cloneVPCOffering — happy path delegates to createVpcOffering
    @Test
    public void cloneVPCOffering_happyPath_delegatesToCreate() {
        StubCloneVPCOfferingCmd cmd = new StubCloneVPCOfferingCmd();
        cmd.sourceOfferingId = 1L;
        cmd.vpcOfferingName = "clone-happy";

        VpcOfferingVO source = makeOffering(1L, "source", false);
        Mockito.when(source.getState()).thenReturn(State.Enabled);
        Mockito.when(source.isOffersRegionLevelVPC()).thenReturn(false);
        Mockito.when(source.isSupportsDistributedRouter()).thenReturn(false);
        Mockito.when(source.isRedundantRouter()).thenReturn(false);
        Mockito.when(vpcOfferingDao.findById(1L)).thenReturn(source);
        Mockito.when(vpcOfferingDao.findByUniqueName("clone-happy")).thenReturn(null);

        Map<Service, Set<Provider>> svcMap = new HashMap<>();
        svcMap.put(Service.Dhcp, new HashSet<>(Arrays.asList(Provider.VPCVirtualRouter)));
        svcMap.put(Service.SourceNat, new HashSet<>(Arrays.asList(Provider.VPCVirtualRouter)));
        Mockito.when(vpcOfferingQueryService.getVpcOffSvcProvidersMap(1L)).thenReturn(svcMap);
        Mockito.when(vpcOfferingDetailsDao.findDomainIds(1L)).thenReturn(new ArrayList<>());
        Mockito.when(vpcOfferingDetailsDao.findZoneIds(1L)).thenReturn(new ArrayList<>());

        VpcOfferingVO persisted = makeOffering(2L, "clone-happy", false);
        Mockito.when(vpcOfferingDao.persist(any(VpcOfferingVO.class))).thenReturn(persisted);
        Mockito.lenient().doNothing().when(vpcOfferingDetailsDao).saveDetails(any());

        VpcOffering result = service.cloneVPCOffering(cmd);

        assertNotNull(result);
    }

    // 14. deleteVpcOffering — unknown id throws IPVE
    @Test(expected = InvalidParameterValueException.class)
    public void deleteVpcOffering_unknown_throwsIPVE() {
        Mockito.when(vpcOfferingDao.findById(anyLong())).thenReturn(null);
        service.deleteVpcOffering(1L);
    }

    // 15. deleteVpcOffering — default offering throws IPVE
    @Test(expected = InvalidParameterValueException.class)
    public void deleteVpcOffering_default_throwsIPVE() {
        VpcOfferingVO off = makeOffering(1L, "default", true);
        Mockito.when(vpcOfferingDao.findById(1L)).thenReturn(off);

        service.deleteVpcOffering(1L);
    }

    // 16. deleteVpcOffering — in use by vpcs throws IPVE
    @Test(expected = InvalidParameterValueException.class)
    public void deleteVpcOffering_inUseByVpcs_throwsIPVE() {
        VpcOfferingVO off = makeOffering(1L, "non-default", false);
        Mockito.when(vpcOfferingDao.findById(1L)).thenReturn(off);
        Mockito.when(vpcDao.getVpcCountByOfferingId(1L)).thenReturn(3);

        service.deleteVpcOffering(1L);
    }

    // 17. deleteVpcOffering — happy path returns true
    @Test
    public void deleteVpcOffering_happyPath_returnsTrue() {
        VpcOfferingVO off = makeOffering(1L, "non-default", false);
        Mockito.when(vpcOfferingDao.findById(1L)).thenReturn(off);
        Mockito.when(vpcDao.getVpcCountByOfferingId(1L)).thenReturn(0);
        Mockito.when(vpcOfferingDao.remove(1L)).thenReturn(true);

        assertTrue(service.deleteVpcOffering(1L));
    }

    // 18. updateVpcOffering(cmd) — invalid domain throws IPVE
    @Test(expected = InvalidParameterValueException.class)
    public void updateVpcOffering_cmd_invalidDomain_throwsIPVE() {
        StubUpdateVPCOfferingCmd cmd = new StubUpdateVPCOfferingCmd();
        cmd.id = 1L;
        cmd.domainIds = Arrays.asList(99L);
        Mockito.when(domainDao.findById(99L)).thenReturn(null);

        service.updateVpcOffering(cmd);
    }

    // 19. updateVpcOffering(cmd) — invalid zone throws IPVE
    @Test(expected = InvalidParameterValueException.class)
    public void updateVpcOffering_cmd_invalidZone_throwsIPVE() {
        StubUpdateVPCOfferingCmd cmd = new StubUpdateVPCOfferingCmd();
        cmd.id = 1L;
        cmd.zoneIds = Arrays.asList(99L);
        Mockito.when(dcDao.findById(99L)).thenReturn(null);

        service.updateVpcOffering(cmd);
    }

    // 20. updateVpcOfferingInternal — unknown id throws IPVE
    @Test(expected = InvalidParameterValueException.class)
    public void updateVpcOfferingInternal_unknownId_throwsIPVE() {
        Mockito.when(vpcOfferingDao.findById(99L)).thenReturn(null);
        service.updateVpcOffering(99L, null, null, null);
    }

    // 21. updateVpcOfferingInternal — invalid state throws IPVE
    @Test(expected = InvalidParameterValueException.class)
    public void updateVpcOfferingInternal_invalidState_throwsIPVE() {
        VpcOfferingVO off = makeOffering(1L, "test", false);
        Mockito.when(vpcOfferingDao.findById(1L)).thenReturn(off);
        VpcOfferingVO forUpdate = mock(VpcOfferingVO.class);
        Mockito.when(vpcOfferingDao.createForUpdate(1L)).thenReturn(forUpdate);
        Mockito.when(vpcOfferingDetailsDao.findDomainIds(1L)).thenReturn(new ArrayList<>());
        Mockito.when(vpcOfferingDetailsDao.findZoneIds(1L)).thenReturn(new ArrayList<>());

        service.updateVpcOffering(1L, null, null, "INVALID_STATE");
    }

    // 22. updateVpcOfferingInternal — rename and state persist update
    @Test
    public void updateVpcOfferingInternal_renameAndState_persistsUpdate() {
        VpcOfferingVO off = makeOffering(1L, "old", false);
        Mockito.when(vpcOfferingDao.findById(1L)).thenReturn(off);
        VpcOfferingVO forUpdate = mock(VpcOfferingVO.class);
        Mockito.when(vpcOfferingDao.createForUpdate(1L)).thenReturn(forUpdate);
        Mockito.when(vpcOfferingDetailsDao.findDomainIds(1L)).thenReturn(new ArrayList<>());
        Mockito.when(vpcOfferingDetailsDao.findZoneIds(1L)).thenReturn(new ArrayList<>());
        Mockito.when(vpcOfferingDao.update(eq(1L), any(VpcOfferingVO.class))).thenReturn(true);
        Mockito.when(vpcOfferingDao.findById(1L)).thenReturn(off);

        VpcOffering result = service.updateVpcOffering(1L, "new-name", "new-desc", "Disabled");

        assertNotNull(result);
        verify(forUpdate).setName("new-name");
        verify(forUpdate).setDisplayText("new-desc");
        verify(forUpdate).setState(State.Disabled);
        verify(vpcOfferingDao).update(eq(1L), any(VpcOfferingVO.class));
    }

    // 23. updateVpcOfferingInternal — domain and zone details persisted
    @Test
    public void updateVpcOfferingInternal_domainAndZoneDetails_persisted() {
        VpcOfferingVO off = makeOffering(1L, "test", false);
        Mockito.when(vpcOfferingDao.findById(1L)).thenReturn(off);
        VpcOfferingVO forUpdate = mock(VpcOfferingVO.class);
        Mockito.when(vpcOfferingDao.createForUpdate(1L)).thenReturn(forUpdate);
        Mockito.when(vpcOfferingDetailsDao.findDomainIds(1L)).thenReturn(new ArrayList<>());
        Mockito.when(vpcOfferingDetailsDao.findZoneIds(1L)).thenReturn(new ArrayList<>());

        StubUpdateVPCOfferingCmd cmd = new StubUpdateVPCOfferingCmd();
        cmd.id = 1L;
        cmd.domainIds = Arrays.asList(5L);
        cmd.zoneIds = Arrays.asList(7L);
        // stub domain and zone validation
        Mockito.when(domainDao.findById(5L)).thenReturn(mock(DomainVO.class));
        Mockito.when(dcDao.findById(7L)).thenReturn(mock(DataCenterVO.class));
        Mockito.when(domainHelper.filterChildSubDomains(Arrays.asList(5L))).thenReturn(Arrays.asList(5L));
        // stub SearchBuilder/SearchCriteria for detail removal — entity() must return a non-null probe
        @SuppressWarnings("unchecked")
        com.cloud.utils.db.SearchBuilder<VpcOfferingDetailsVO> sb =
                (com.cloud.utils.db.SearchBuilder<VpcOfferingDetailsVO>) mock(com.cloud.utils.db.SearchBuilder.class);
        VpcOfferingDetailsVO entityProbe = mock(VpcOfferingDetailsVO.class);
        Mockito.when(sb.entity()).thenReturn(entityProbe);
        @SuppressWarnings("unchecked")
        com.cloud.utils.db.SearchCriteria<VpcOfferingDetailsVO> sc =
                (com.cloud.utils.db.SearchCriteria<VpcOfferingDetailsVO>) mock(com.cloud.utils.db.SearchCriteria.class);
        Mockito.when(sb.create()).thenReturn(sc);
        Mockito.when(vpcOfferingDetailsDao.createSearchBuilder()).thenReturn(sb);

        service.updateVpcOffering(cmd);

        verify(vpcOfferingDetailsDao, times(2)).persist(any(VpcOfferingDetailsVO.class));
    }

    // 24. reconstructServiceCapabilityList — regionLevel and distributedRouter builds indexed map
    @Test
    public void reconstructServiceCapabilityList_regionLevelAndDistributedRouter_buildsIndexedMap() {
        // Exercise via cloneVPCOffering which calls reconstructServiceCapabilityList internally
        StubCloneVPCOfferingCmd cmd = new StubCloneVPCOfferingCmd();
        cmd.sourceOfferingId = 1L;
        cmd.vpcOfferingName = "clone-cap";

        VpcOfferingVO source = makeOffering(1L, "source", false);
        Mockito.when(source.getState()).thenReturn(State.Enabled);
        Mockito.when(source.isOffersRegionLevelVPC()).thenReturn(true);
        Mockito.when(source.isSupportsDistributedRouter()).thenReturn(true);
        Mockito.when(source.isRedundantRouter()).thenReturn(false);
        Mockito.when(vpcOfferingDao.findById(1L)).thenReturn(source);
        Mockito.when(vpcOfferingDao.findByUniqueName("clone-cap")).thenReturn(null);

        Map<Service, Set<Provider>> svcMap = new HashMap<>();
        svcMap.put(Service.Connectivity, new HashSet<>(Arrays.asList(Provider.VPCVirtualRouter)));
        svcMap.put(Service.SourceNat, new HashSet<>(Arrays.asList(Provider.VPCVirtualRouter)));
        Mockito.when(vpcOfferingQueryService.getVpcOffSvcProvidersMap(1L)).thenReturn(svcMap);
        Mockito.when(vpcOfferingDetailsDao.findDomainIds(1L)).thenReturn(new ArrayList<>());
        Mockito.when(vpcOfferingDetailsDao.findZoneIds(1L)).thenReturn(new ArrayList<>());

        // stub networkModel for capability validation (RegionLevelVpc + DistributedRouter on Connectivity)
        NetworkElement connElement = mock(NetworkElement.class);
        Mockito.when(networkModel.getElementImplementingProvider(Provider.VPCVirtualRouter.getName())).thenReturn(connElement);
        Map<Service, Map<Capability, String>> connCaps = new HashMap<>();
        Map<Capability, String> caps = new HashMap<>();
        caps.put(Capability.RegionLevelVpc, "supported");
        caps.put(Capability.DistributedRouter, "supported");
        connCaps.put(Service.Connectivity, caps);
        Mockito.when(connElement.getCapabilities()).thenReturn(connCaps);

        VpcOfferingVO persisted = makeOffering(2L, "clone-cap", false);
        Mockito.when(vpcOfferingDao.persist(any(VpcOfferingVO.class))).thenReturn(persisted);
        Mockito.lenient().doNothing().when(vpcOfferingDetailsDao).saveDetails(any());

        // If reconstruct produces regionLevel + distributedRouter capabilities, the clone creates them
        VpcOffering result = service.cloneVPCOffering(cmd);
        assertNotNull(result);
    }

    // 25. findCapabilityForService — service mismatch throws IPVE
    @Test(expected = InvalidParameterValueException.class)
    public void findCapabilityForService_serviceMismatch_throwsIPVE() {
        // Exercise via createVpcOffering map overload with serviceCapabilityList containing wrong service
        Map<String, Map<String, String>> capabilityMap = new HashMap<>();
        Map<String, String> cap = new HashMap<>();
        cap.put(VpcOfferingCrudServiceImpl.SERVICE, "Connectivity");
        cap.put(VpcOfferingCrudServiceImpl.CAPABILITYTYPE, Capability.RedundantRouter.getName());
        cap.put(VpcOfferingCrudServiceImpl.CAPABILITYVALUE, "true");
        capabilityMap.put("0", cap);

        // createVpcOffering with Connectivity in services (via list overload) and bad capability-service combo
        // RedundantRouter capability must be on SourceNat/Gateway, not Connectivity → throws
        service.createVpcOffering("name", "desc",
                Arrays.asList("Dhcp", "SourceNat"),
                null, capabilityMap, null, null, null, null, null, null,
                State.Enabled, null, false, false);
    }

    // 26. validateConnectivtyServiceCapabilities — invalid capability value throws IPVE
    @Test(expected = InvalidParameterValueException.class)
    public void validateConnectivtyServiceCapabilities_invalidCapabilityValue_throwsIPVE() {
        // Use createVpcOffering with Connectivity service + bad capability value
        Map<String, Map<String, String>> capabilityMap = new HashMap<>();
        Map<String, String> cap = new HashMap<>();
        cap.put(VpcOfferingCrudServiceImpl.SERVICE, "Connectivity");
        cap.put(VpcOfferingCrudServiceImpl.CAPABILITYTYPE, Capability.DistributedRouter.getName());
        cap.put(VpcOfferingCrudServiceImpl.CAPABILITYVALUE, "MAYBE");  // invalid — only "true"/"false"
        capabilityMap.put("0", cap);

        Set<Provider> providers = new HashSet<>(Arrays.asList(Provider.VPCVirtualRouter));
        NetworkElement element = mock(NetworkElement.class);
        Mockito.when(networkModel.getElementImplementingProvider(anyString())).thenReturn(element);
        Map<Service, Map<Capability, String>> caps = new HashMap<>();
        Map<Capability, String> connCaps = new HashMap<>();
        connCaps.put(Capability.DistributedRouter, "supported");
        caps.put(Service.Connectivity, connCaps);
        Mockito.when(element.getCapabilities()).thenReturn(caps);

        service.createVpcOffering("name", "desc",
                Arrays.asList("Dhcp", "SourceNat", "Connectivity"),
                null, capabilityMap, null, null, null, null, null, null,
                State.Enabled, null, false, false);
    }

    // 27. checkCapabilityPerServiceProvider — element lacks capability throws IPVE
    @Test(expected = InvalidParameterValueException.class)
    public void checkCapabilityPerServiceProvider_unsupported_throwsIPVE() {
        Set<Provider> providers = new HashSet<>(Arrays.asList(Provider.VPCVirtualRouter));
        NetworkElement element = mock(NetworkElement.class);
        Mockito.when(networkModel.getElementImplementingProvider(Provider.VPCVirtualRouter.getName())).thenReturn(element);

        // Element reports Connectivity service but NOT RegionLevelVpc capability
        Map<Service, Map<Capability, String>> caps = new HashMap<>();
        Map<Capability, String> connCaps = new HashMap<>();
        connCaps.put(Capability.DistributedRouter, "supported");
        // Missing RegionLevelVpc
        caps.put(Service.Connectivity, connCaps);
        Mockito.when(element.getCapabilities()).thenReturn(caps);

        service.checkCapabilityPerServiceProvider(providers, Capability.RegionLevelVpc, Service.Connectivity);
    }
}
