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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.agent.api.to.deployasis.OVFNetworkTO;
import com.cloud.dc.DataCenter;
import com.cloud.deployasis.dao.TemplateDeployAsIsDetailsDao;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;

@RunWith(MockitoJUnitRunner.class)
public class VmDeployAsIsNetworkMappingServiceImplTest {

    private static final long TEMPLATE_ID = 6L;
    private static final long ZONE_ID = 8L;
    private static final String ZONE_UUID = "zone-uuid";
    private static final String ACCOUNT_UUID = "account-uuid";

    private VmDeployAsIsNetworkMappingServiceImpl service;

    @Mock
    private TemplateDeployAsIsDetailsDao templateDeployAsIsDetailsDao;
    @Mock
    private NetworkModel networkModel;
    @Mock
    private DataCenter zone;
    @Mock
    private Account owner;
    @Mock
    private VirtualMachineTemplate template;

    @Before
    public void setUp() {
        service = new VmDeployAsIsNetworkMappingServiceImpl();
        ReflectionTestUtils.setField(service, "templateDeployAsIsDetailsDao", templateDeployAsIsDetailsDao);
        ReflectionTestUtils.setField(service, "networkModel", networkModel);
        lenient().when(zone.getId()).thenReturn(ZONE_ID);
        lenient().when(zone.getUuid()).thenReturn(ZONE_UUID);
        lenient().when(owner.getUuid()).thenReturn(ACCOUNT_UUID);
        lenient().when(template.getId()).thenReturn(TEMPLATE_ID);
    }

    @Test
    public void getDeployAsIsVmNetworkMappingReturnsEmptyForNonOvaTemplate()
            throws InsufficientCapacityException, ResourceAllocationException {
        when(template.getFormat()).thenReturn(ImageFormat.QCOW2);

        LinkedHashMap<Integer, Long> mapping = service.getDeployAsIsVmNetworkMapping(zone, owner, template,
                new HashMap<>(), this::failIfDefaultNetworkRequested);

        assertEquals(new LinkedHashMap<>(), mapping);
        verifyNoInteractions(templateDeployAsIsDetailsDao, networkModel);
    }

    @Test
    public void getDeployAsIsVmNetworkMappingReturnsEmptyWhenOvaHasNoNetworkRequirements()
            throws InsufficientCapacityException, ResourceAllocationException {
        when(template.getFormat()).thenReturn(ImageFormat.OVA);
        when(templateDeployAsIsDetailsDao.listNetworkRequirementsByTemplateId(TEMPLATE_ID)).thenReturn(List.of());

        LinkedHashMap<Integer, Long> mapping = service.getDeployAsIsVmNetworkMapping(zone, owner, template,
                new HashMap<>(), this::failIfDefaultNetworkRequested);

        assertEquals(new LinkedHashMap<>(), mapping);
        verify(networkModel, never()).getNetworkWithSGWithFreeIPs(owner, ZONE_ID);
    }

    @Test
    public void getDeployAsIsVmNetworkMappingPreservesExplicitMappingsInOvfOrder()
            throws InsufficientCapacityException, ResourceAllocationException {
        when(template.getFormat()).thenReturn(ImageFormat.OVA);
        when(templateDeployAsIsDetailsDao.listNetworkRequirementsByTemplateId(TEMPLATE_ID))
                .thenReturn(List.of(ovfNetwork(2), ovfNetwork(1)));
        Map<Integer, Long> requestedMapping = new HashMap<>();
        requestedMapping.put(1, 101L);
        requestedMapping.put(2, 202L);

        LinkedHashMap<Integer, Long> mapping = service.getDeployAsIsVmNetworkMapping(zone, owner, template,
                requestedMapping, this::failIfDefaultNetworkRequested);

        assertEquals(List.of(2, 1), List.copyOf(mapping.keySet()));
        assertEquals(List.of(202L, 101L), List.copyOf(mapping.values()));
    }

    @Test
    public void getDeployAsIsVmNetworkMappingUsesSecurityGroupNetworkForSecurityGroupZone()
            throws InsufficientCapacityException, ResourceAllocationException {
        Network securityGroupNetwork = network(303L);
        when(template.getFormat()).thenReturn(ImageFormat.OVA);
        when(templateDeployAsIsDetailsDao.listNetworkRequirementsByTemplateId(TEMPLATE_ID)).thenReturn(List.of(ovfNetwork(1)));
        when(zone.isSecurityGroupEnabled()).thenReturn(true);
        when(networkModel.getNetworkWithSGWithFreeIPs(owner, ZONE_ID)).thenReturn(securityGroupNetwork);

        LinkedHashMap<Integer, Long> mapping = service.getDeployAsIsVmNetworkMapping(zone, owner, template,
                new HashMap<>(), this::failIfDefaultNetworkRequested);

        assertEquals(List.of(303L), List.copyOf(mapping.values()));
    }

    @Test
    public void getDeployAsIsVmNetworkMappingUsesSecurityGroupNetworkWhenZoneSupportsSecurityGroups()
            throws InsufficientCapacityException, ResourceAllocationException {
        Network securityGroupNetwork = network(404L);
        when(template.getFormat()).thenReturn(ImageFormat.OVA);
        when(templateDeployAsIsDetailsDao.listNetworkRequirementsByTemplateId(TEMPLATE_ID)).thenReturn(List.of(ovfNetwork(1)));
        when(zone.isSecurityGroupEnabled()).thenReturn(false);
        when(networkModel.isSecurityGroupSupportedForZone(ZONE_ID)).thenReturn(true);
        when(networkModel.getNetworkWithSGWithFreeIPs(owner, ZONE_ID)).thenReturn(securityGroupNetwork);

        LinkedHashMap<Integer, Long> mapping = service.getDeployAsIsVmNetworkMapping(zone, owner, template,
                new HashMap<>(), this::failIfDefaultNetworkRequested);

        assertEquals(List.of(404L), List.copyOf(mapping.values()));
    }

    @Test
    public void getDeployAsIsVmNetworkMappingThrowsWhenSecurityGroupNetworkIsMissing()
            throws InsufficientCapacityException, ResourceAllocationException {
        when(template.getFormat()).thenReturn(ImageFormat.OVA);
        when(templateDeployAsIsDetailsDao.listNetworkRequirementsByTemplateId(TEMPLATE_ID)).thenReturn(List.of(ovfNetwork(1)));
        when(zone.isSecurityGroupEnabled()).thenReturn(true);
        when(networkModel.getNetworkWithSGWithFreeIPs(owner, ZONE_ID)).thenReturn(null);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.getDeployAsIsVmNetworkMapping(zone, owner, template, new HashMap<>(),
                        this::failIfDefaultNetworkRequested));

        assertEquals("No network with security enabled is found in zone ID: " + ZONE_UUID, exception.getMessage());
    }

    @Test
    public void getDeployAsIsVmNetworkMappingUsesDefaultNetworkProviderWhenSecurityGroupsAreNotEnabled()
            throws InsufficientCapacityException, ResourceAllocationException {
        Network defaultNetwork = network(505L);
        when(template.getFormat()).thenReturn(ImageFormat.OVA);
        when(templateDeployAsIsDetailsDao.listNetworkRequirementsByTemplateId(TEMPLATE_ID)).thenReturn(List.of(ovfNetwork(1)));
        when(zone.isSecurityGroupEnabled()).thenReturn(false);
        when(networkModel.isSecurityGroupSupportedForZone(ZONE_ID)).thenReturn(false);

        LinkedHashMap<Integer, Long> mapping = service.getDeployAsIsVmNetworkMapping(zone, owner, template,
                new HashMap<>(), (requestedZone, requestedOwner, selectAny) -> {
                    assertEquals(zone, requestedZone);
                    assertEquals(owner, requestedOwner);
                    assertEquals(true, selectAny);
                    return defaultNetwork;
                });

        assertEquals(List.of(505L), List.copyOf(mapping.values()));
    }

    @Test
    public void getDeployAsIsVmNetworkMappingThrowsWhenDefaultNetworkIsMissing()
            throws InsufficientCapacityException, ResourceAllocationException {
        when(template.getFormat()).thenReturn(ImageFormat.OVA);
        when(templateDeployAsIsDetailsDao.listNetworkRequirementsByTemplateId(TEMPLATE_ID)).thenReturn(List.of(ovfNetwork(1)));
        when(zone.isSecurityGroupEnabled()).thenReturn(false);
        when(networkModel.isSecurityGroupSupportedForZone(ZONE_ID)).thenReturn(false);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.getDeployAsIsVmNetworkMapping(zone, owner, template, new HashMap<>(),
                        (requestedZone, requestedOwner, selectAny) -> null));

        assertEquals(String.format("Default network not found for zone ID: %s and account ID: %s", ZONE_UUID, ACCOUNT_UUID),
                exception.getMessage());
    }

    @Test
    public void getDeployAsIsVmNetworkMappingReusesFallbackNetworkForMultipleUnmappedOvfEntries()
            throws InsufficientCapacityException, ResourceAllocationException {
        Network defaultNetwork = network(606L);
        when(template.getFormat()).thenReturn(ImageFormat.OVA);
        when(templateDeployAsIsDetailsDao.listNetworkRequirementsByTemplateId(TEMPLATE_ID))
                .thenReturn(List.of(ovfNetwork(1), ovfNetwork(2)));
        when(zone.isSecurityGroupEnabled()).thenReturn(false);
        when(networkModel.isSecurityGroupSupportedForZone(ZONE_ID)).thenReturn(false);

        LinkedHashMap<Integer, Long> mapping = service.getDeployAsIsVmNetworkMapping(zone, owner, template,
                new HashMap<>(), (requestedZone, requestedOwner, selectAny) -> defaultNetwork);

        assertEquals(List.of(606L, 606L), List.copyOf(mapping.values()));
        verify(networkModel).isSecurityGroupSupportedForZone(ZONE_ID);
    }

    @Test
    public void getDeployAsIsVmNetworkMappingCombinesExplicitAndFallbackMappingsInOrder()
            throws InsufficientCapacityException, ResourceAllocationException {
        Network defaultNetwork = network(707L);
        when(template.getFormat()).thenReturn(ImageFormat.OVA);
        when(templateDeployAsIsDetailsDao.listNetworkRequirementsByTemplateId(TEMPLATE_ID))
                .thenReturn(List.of(ovfNetwork(1), ovfNetwork(2), ovfNetwork(3)));
        when(zone.isSecurityGroupEnabled()).thenReturn(false);
        when(networkModel.isSecurityGroupSupportedForZone(ZONE_ID)).thenReturn(false);
        Map<Integer, Long> requestedMapping = new HashMap<>();
        requestedMapping.put(2, 222L);

        LinkedHashMap<Integer, Long> mapping = service.getDeployAsIsVmNetworkMapping(zone, owner, template,
                requestedMapping, (requestedZone, requestedOwner, selectAny) -> defaultNetwork);

        assertEquals(List.of(1, 2, 3), List.copyOf(mapping.keySet()));
        assertEquals(List.of(707L, 222L, 707L), List.copyOf(mapping.values()));
    }

    @Test
    public void getDeployAsIsVmNetworkMappingThrowsNullPointerForNullRequestedMappingWhenOvaHasRequirements() {
        when(template.getFormat()).thenReturn(ImageFormat.OVA);
        when(templateDeployAsIsDetailsDao.listNetworkRequirementsByTemplateId(TEMPLATE_ID)).thenReturn(List.of(ovfNetwork(1)));

        assertThrows(NullPointerException.class,
                () -> service.getDeployAsIsVmNetworkMapping(zone, owner, template, null, this::failIfDefaultNetworkRequested));
    }

    private Network failIfDefaultNetworkRequested(DataCenter zone, Account owner, boolean selectAny) {
        throw new AssertionError("Default network provider should not be called");
    }

    private OVFNetworkTO ovfNetwork(int instanceId) {
        OVFNetworkTO network = new OVFNetworkTO();
        network.setInstanceID(instanceId);
        return network;
    }

    private Network network(long id) {
        Network network = mock(Network.class);
        when(network.getId()).thenReturn(id);
        return network;
    }
}
