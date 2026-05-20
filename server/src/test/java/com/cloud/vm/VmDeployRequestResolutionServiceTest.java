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
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.LinkedHashMap;
import java.util.List;

import org.apache.cloudstack.api.command.user.vm.DeployVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.Scope;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;
import org.springframework.test.util.ReflectionTestUtils;

import com.cloud.dc.DataCenter;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.ScopeType;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.utils.db.EntityManager;

@RunWith(MockitoJUnitRunner.class)
public class VmDeployRequestResolutionServiceTest {

    private static final long OWNER_ID = 1L;
    private static final long CALLER_ID = 2L;
    private static final long ZONE_ID = 3L;
    private static final long SERVICE_OFFERING_ID = 4L;
    private static final long SERVICE_ROOT_DISK_OFFERING_ID = 5L;
    private static final long TEMPLATE_ID = 6L;
    private static final long VOLUME_ID = 7L;
    private static final long SNAPSHOT_ID = 8L;
    private static final long SNAPSHOT_VOLUME_ID = 9L;
    private static final long DISK_OFFERING_ID = 10L;
    private static final long REQUESTED_NETWORK_ID = 11L;
    private static final long DEPLOY_AS_IS_NETWORK_ID = 12L;

    private VmDeployRequestResolutionService service;

    @Mock
    private AccountService accountService;
    @Mock
    private EntityManager entityManager;
    @Mock
    private AccountManager accountManager;
    @Mock
    private SnapshotDao snapshotDao;
    @Mock
    private VolumeDataFactory volumeDataFactory;
    @Mock
    private VmCreationValidator vmCreationValidator;
    @Mock
    private VmDeployAsIsNetworkMappingService vmDeployAsIsNetworkMappingService;
    @Mock
    private DeployVMCmd cmd;
    @Mock
    private Account owner;
    @Mock
    private Account caller;
    @Mock
    private DataCenter zone;
    @Mock
    private ServiceOffering serviceOffering;
    @Mock
    private VirtualMachineTemplate template;
    @Mock
    private DiskOffering serviceRootDiskOffering;
    @Mock
    private DiskOffering diskOffering;

    @Before
    public void setUp() {
        service = new VmDeployRequestResolutionService();
        ReflectionTestUtils.setField(service, "accountService", accountService);
        ReflectionTestUtils.setField(service, "entityMgr", entityManager);
        ReflectionTestUtils.setField(service, "accountMgr", accountManager);
        ReflectionTestUtils.setField(service, "snapshotDao", snapshotDao);
        ReflectionTestUtils.setField(service, "volFactory", volumeDataFactory);
        ReflectionTestUtils.setField(service, "vmCreationValidator", vmCreationValidator);
        ReflectionTestUtils.setField(service, "vmDeployAsIsNetworkMappingService", vmDeployAsIsNetworkMappingService);

        lenient().when(cmd.getEntityOwnerId()).thenReturn(OWNER_ID);
        lenient().when(cmd.getZoneId()).thenReturn(ZONE_ID);
        lenient().when(cmd.getServiceOfferingId()).thenReturn(SERVICE_OFFERING_ID);
        lenient().when(cmd.getTemplateId()).thenReturn(TEMPLATE_ID);
        lenient().when(cmd.getVolumeId()).thenReturn(null);
        lenient().when(cmd.getSnapshotId()).thenReturn(null);
        lenient().when(cmd.getDiskOfferingId()).thenReturn(null);
        lenient().when(cmd.getDataDiskInfoList()).thenReturn(null);
        lenient().when(cmd.isVolumeOrSnapshotProvided()).thenReturn(false);
        lenient().when(cmd.getNetworkIds()).thenReturn(List.of(REQUESTED_NETWORK_ID));
        lenient().when(cmd.getVmNetworkMap()).thenReturn(new LinkedHashMap<>());
        lenient().when(accountService.getActiveAccountById(OWNER_ID)).thenReturn(owner);
        lenient().when(entityManager.findById(DataCenter.class, ZONE_ID)).thenReturn(zone);
        lenient().when(entityManager.findById(ServiceOffering.class, SERVICE_OFFERING_ID)).thenReturn(serviceOffering);
        lenient().when(entityManager.findById(VirtualMachineTemplate.class, TEMPLATE_ID)).thenReturn(template);
        lenient().when(serviceOffering.getId()).thenReturn(SERVICE_OFFERING_ID);
        lenient().when(serviceOffering.getDiskOfferingId()).thenReturn(SERVICE_ROOT_DISK_OFFERING_ID);
        lenient().when(entityManager.findById(DiskOffering.class, SERVICE_ROOT_DISK_OFFERING_ID)).thenReturn(serviceRootDiskOffering);
        lenient().when(zone.isLocalStorageEnabled()).thenReturn(true);
        lenient().when(caller.getId()).thenReturn(CALLER_ID);
        CallContext.register(mock(com.cloud.user.User.class), caller);
    }

    @After
    public void tearDown() {
        CallContext.unregisterAll();
    }

    @Test
    public void resolveReturnsDeployAsIsNetworkIdsWhenMappingProvided()
            throws InsufficientCapacityException, ResourceAllocationException {
        LinkedHashMap<Integer, Long> deployAsIsMapping = new LinkedHashMap<>();
        deployAsIsMapping.put(2, DEPLOY_AS_IS_NETWORK_ID);
        when(vmDeployAsIsNetworkMappingService.getDeployAsIsVmNetworkMapping(eq(zone), eq(owner), eq(template),
                any(), nullable(VmDeployAsIsNetworkMappingService.DefaultNetworkProvider.class))).thenReturn(deployAsIsMapping);

        VmDeployRequestResolution resolution = service.resolve(cmd, this::defaultNetwork);

        assertSame(owner, resolution.getOwner());
        assertSame(zone, resolution.getZone());
        assertSame(serviceOffering, resolution.getServiceOffering());
        assertSame(template, resolution.getTemplate());
        assertEquals(List.of(DEPLOY_AS_IS_NETWORK_ID), resolution.getNetworkIds());
        verify(vmCreationValidator).verifyDetails(cmd.getDetails());
        verify(vmCreationValidator).verifyServiceOffering(cmd, serviceOffering);
        verify(vmCreationValidator).verifyTemplate(cmd, template, SERVICE_OFFERING_ID);
    }

    @Test
    public void resolveRejectsLocalDataDiskOfferingWhenZoneDisablesLocalStorage()
            throws InsufficientCapacityException, ResourceAllocationException {
        when(cmd.getDiskOfferingId()).thenReturn(DISK_OFFERING_ID);
        when(entityManager.findById(DiskOffering.class, DISK_OFFERING_ID)).thenReturn(diskOffering);
        when(zone.isLocalStorageEnabled()).thenReturn(false);
        when(serviceRootDiskOffering.isUseLocalStorage()).thenReturn(false);
        when(diskOffering.isUseLocalStorage()).thenReturn(true);
        when(diskOffering.getName()).thenReturn("local-data");

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.resolve(cmd, this::defaultNetwork));

        assertEquals("Zone is not configured to use local storage but disk offering local-data uses it",
                exception.getMessage());
    }

    @Test
    public void resolveExistingVolumeUsesRemovedTemplateLookupAndCarriesVolume()
            throws InsufficientCapacityException, ResourceAllocationException {
        VolumeInfo volume = zoneWideVolume(TEMPLATE_ID);
        when(cmd.getVolumeId()).thenReturn(VOLUME_ID);
        when(cmd.isVolumeOrSnapshotProvided()).thenReturn(true);
        when(volumeDataFactory.getVolume(VOLUME_ID)).thenReturn(volume);
        when(entityManager.findByIdIncludingRemoved(VirtualMachineTemplate.class, TEMPLATE_ID)).thenReturn(template);
        when(template.getHypervisorType()).thenReturn(HypervisorType.KVM);

        VmDeployRequestResolution resolution = service.resolve(cmd, this::defaultNetwork);

        assertSame(volume, resolution.getVolume());
        assertSame(template, resolution.getTemplate());
        verify(accountManager).checkAccess(caller, null, true, volume);
    }

    @Test
    public void resolveExistingSnapshotUsesSnapshotVolumeTemplateAndCarriesSnapshot()
            throws InsufficientCapacityException, ResourceAllocationException {
        SnapshotVO snapshot = mock(SnapshotVO.class);
        VolumeInfo snapshotVolume = zoneWideVolume(TEMPLATE_ID);
        when(cmd.getSnapshotId()).thenReturn(SNAPSHOT_ID);
        when(cmd.isVolumeOrSnapshotProvided()).thenReturn(true);
        when(snapshotDao.findById(SNAPSHOT_ID)).thenReturn(snapshot);
        when(snapshot.getVolumeId()).thenReturn(SNAPSHOT_VOLUME_ID);
        when(volumeDataFactory.getVolume(SNAPSHOT_VOLUME_ID)).thenReturn(snapshotVolume);
        when(entityManager.findByIdIncludingRemoved(VirtualMachineTemplate.class, TEMPLATE_ID)).thenReturn(template);
        when(template.getHypervisorType()).thenReturn(HypervisorType.KVM);

        VmDeployRequestResolution resolution = service.resolve(cmd, this::defaultNetwork);

        assertSame(snapshot, resolution.getSnapshot());
        assertSame(template, resolution.getTemplate());
        verify(accountManager).checkAccess(caller, null, true, snapshot);
    }

    @Test
    public void resolveRejectsExistingVolumeWhenVolumeTemplateDiffersFromProvidedTemplate()
            throws InsufficientCapacityException, ResourceAllocationException {
        VolumeInfo volume = zoneWideVolume(99L);
        when(cmd.getVolumeId()).thenReturn(VOLUME_ID);
        when(volumeDataFactory.getVolume(VOLUME_ID)).thenReturn(volume);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.resolve(cmd, this::defaultNetwork));

        assertEquals("The volume's template 99 is not the same as the provided one 6", exception.getMessage());
    }

    private VolumeInfo zoneWideVolume(Long templateId) {
        DataStore dataStore = mock(DataStore.class);
        Scope scope = mock(Scope.class);
        VolumeInfo volume = mock(VolumeInfo.class);
        when(volume.getDataStore()).thenReturn(dataStore);
        when(dataStore.getScope()).thenReturn(scope);
        when(scope.getScopeType()).thenReturn(ScopeType.ZONE);
        when(volume.getTemplateId()).thenReturn(templateId);
        when(volume.getInstanceId()).thenReturn(null);
        return volume;
    }

    private Network defaultNetwork(DataCenter zone, Account owner, boolean selectAny) {
        return mock(Network.class);
    }
}
