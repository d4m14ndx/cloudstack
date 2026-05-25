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
package com.cloud.resource;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.api.command.admin.cluster.AddClusterCmd;
import org.apache.cloudstack.api.command.admin.cluster.DeleteClusterCmd;
import org.apache.cloudstack.api.command.admin.cluster.UpdateClusterCmd;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.extensions.dao.ExtensionDao;
import org.apache.cloudstack.framework.extensions.dao.ExtensionResourceMapDao;
import org.apache.cloudstack.framework.extensions.manager.ExtensionsManager;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.capacity.dao.CapacityDao;
import com.cloud.configuration.Config;
import com.cloud.dc.ClusterDetailsDao;
import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.DedicatedResourceVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.ClusterVSMMapDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.org.Cluster;
import com.cloud.org.Grouping;
import com.cloud.org.Managed;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountVO;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;

@RunWith(MockitoJUnitRunner.class)
public class ClusterLifecycleServiceImplTest {

    private static final long ZONE_ID = 1L;
    private static final long POD_ID = 2L;
    private static final long CLUSTER_ID = 10L;

    @InjectMocks
    private ClusterLifecycleServiceImpl service;

    @Mock
    private DataCenterDao dataCenterDao;
    @Mock
    private HostPodDao podDao;
    @Mock
    private ClusterDao clusterDao;
    @Mock
    private ClusterDetailsDao clusterDetailsDao;
    @Mock
    private HostDao hostDao;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private CapacityDao capacityDao;
    @Mock
    private ConfigurationDao configDao;
    @Mock
    private ClusterVSMMapDao clusterVSMMapDao;
    @Mock
    private DedicatedResourceDao dedicatedDao;
    @Mock
    private AnnotationDao annotationDao;
    @Mock
    private ExtensionResourceMapDao extensionResourceMapDao;
    @Mock
    private ExtensionDao extensionDao;
    @Mock
    private ExtensionsManager extensionsManager;
    @Mock
    private AccountManager accountManager;
    @Mock
    private HostLookupService hostLookupService;
    @Mock
    private ClusterLifecycleCallbacks callbacks;

    // --- shared fixtures ---

    private DataCenterVO zone;
    private HostPodVO pod;
    private AddClusterCmd addCmd;

    @Before
    public void setUp() {
        zone = org.mockito.Mockito.mock(DataCenterVO.class);
        lenient().when(zone.getId()).thenReturn(ZONE_ID);
        lenient().when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);
        lenient().when(zone.isSecurityGroupEnabled()).thenReturn(false);
        lenient().when(zone.getUuid()).thenReturn("zone-uuid");

        pod = new HostPodVO("pod1", ZONE_ID, "10.1.0.0", "10.1.0.0/24", 8, "desc");
        setField(pod, "id", POD_ID);

        addCmd = new AddClusterCmd();
        setField(addCmd, "zoneId", ZONE_ID);
        setField(addCmd, "podId", POD_ID);
        setField(addCmd, "clusterName", "cluster1");
        setField(addCmd, "hypervisor", "KVM");
        setField(addCmd, "clusterType", "CloudManaged");
        setField(addCmd, "allocationState", "Enabled");

        lenient().when(dataCenterDao.findById(ZONE_ID)).thenReturn(zone);
        lenient().when(podDao.findById(POD_ID)).thenReturn(pod);
        lenient().when(accountManager.isRootAdmin(anyLong())).thenReturn(true);

        // CallContext is needed by discoverCluster (getCallingAccount, putContextParameter)
        AccountVO account = new AccountVO("admin", 1L, "ROOT", Account.Type.ADMIN, UUID.randomUUID().toString());
        setField(account, "id", 1L);
        UserVO user = new UserVO(1, "admin", "pass", "admin", "admin", "admin@test.com", "GMT",
                UUID.randomUUID().toString(), User.Source.UNKNOWN);
        setField(user, "id", 1L);
        CallContext.register(user, account);

        // Default empty discoverers list; tests can override service.discoverers
        service.discoverers = new ArrayList<>();
    }

    @After
    public void tearDown() {
        CallContext.unregister();
    }

    // =====================================================================
    // discoverCluster tests
    // =====================================================================

    @Test
    public void testDiscoverCluster_missingZone_throws() {
        when(dataCenterDao.findById(ZONE_ID)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class, () -> service.discoverCluster(addCmd));
    }

    @Test
    public void testDiscoverCluster_disabledZoneNonAdmin_throws() {
        when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Disabled);
        when(accountManager.isRootAdmin(anyLong())).thenReturn(false);
        assertThrows(PermissionDeniedException.class, () -> service.discoverCluster(addCmd));
    }

    @Test
    public void testDiscoverCluster_missingPod_throws() {
        when(podDao.findById(POD_ID)).thenReturn(null);
        assertThrows(InvalidParameterValueException.class, () -> service.discoverCluster(addCmd));
    }

    @Test
    public void testDiscoverCluster_podWrongZone_throws() {
        HostPodVO otherPod = new HostPodVO("other", 99L, "10.2.0.0", "10.2.0.0/24", 8, "desc");
        setField(otherPod, "id", POD_ID);
        when(podDao.findById(POD_ID)).thenReturn(otherPod);
        assertThrows(InvalidParameterValueException.class, () -> service.discoverCluster(addCmd));
    }

    @Test
    public void testDiscoverCluster_missingClusterName_throws() {
        setField(addCmd, "clusterName", "");
        assertThrows(InvalidParameterValueException.class, () -> service.discoverCluster(addCmd));
    }

    @Test
    public void testDiscoverCluster_missingHypervisor_throws() {
        setField(addCmd, "hypervisor", "");
        assertThrows(InvalidParameterValueException.class, () -> service.discoverCluster(addCmd));
    }

    @Test
    public void testDiscoverCluster_noMatchingDiscoverer_throws() {
        // Use a real (empty) discoverers list so findDiscoverer returns null
        service.discoverers = new ArrayList<>();
        assertThrows(InvalidParameterValueException.class, () -> service.discoverCluster(addCmd));
    }

    @Test
    public void testDiscoverCluster_extensionOnNonExternalHypervisor_throws() {
        setField(addCmd, "extensionId", 5L);
        // KVM is not External
        assertThrows(InvalidParameterValueException.class, () -> service.discoverCluster(addCmd));
    }

    @Test
    public void testDiscoverCluster_cloudManaged_happyPath() throws Exception {
        ClusterVO persisted = new ClusterVO(ZONE_ID, POD_ID, "cluster1");
        setField(persisted, "id", CLUSTER_ID);
        when(clusterDao.persist(any(ClusterVO.class))).thenReturn(persisted);

        // CloudManaged still calls findDiscoverer before the early return
        Discoverer kvmDiscoverer = org.mockito.Mockito.mock(Discoverer.class);
        when(kvmDiscoverer.getHypervisorType()).thenReturn(HypervisorType.KVM);
        service.discoverers = List.of(kvmDiscoverer);

        List<? extends Cluster> result = service.discoverCluster(addCmd);
        assertNotNull(result);
        assertEquals(1, result.size());
        verify(clusterDetailsDao).persist(eq(CLUSTER_ID), any(Map.class));
    }

    @Test
    public void testDiscoverCluster_noDiscovererFound_throws() throws Exception {
        // Need a hypervisor that has no matching discoverer
        // discoverers is a mock list, so findDiscoverer returns null
        setField(addCmd, "hypervisor", "VMware");
        setField(addCmd, "clusterType", "ExternalManaged");
        setField(addCmd, "url", "http://vc/dc");
        // mock discoverers as empty
        service.discoverers = new ArrayList<>();
        assertThrows(InvalidParameterValueException.class, () -> service.discoverCluster(addCmd));
    }

    // =====================================================================
    // deleteCluster tests
    // =====================================================================

    @Test
    public void testDeleteCluster_nonExistentCluster_throws() {
        DeleteClusterCmd cmd = new DeleteClusterCmd();
        setField(cmd, "id", CLUSTER_ID);
        when(clusterDao.lockRow(CLUSTER_ID, true)).thenReturn(null);
        assertThrows(CloudRuntimeException.class, () -> service.deleteCluster(cmd));
    }

    @Test
    public void testDeleteCluster_hasHosts_throws() {
        DeleteClusterCmd cmd = new DeleteClusterCmd();
        setField(cmd, "id", CLUSTER_ID);
        ClusterVO cluster = mockCluster(HypervisorType.KVM);
        when(clusterDao.lockRow(CLUSTER_ID, true)).thenReturn(cluster);
        when(hostDao.listIdsByClusterId(CLUSTER_ID)).thenReturn(List.of(42L));
        assertThrows(CloudRuntimeException.class, () -> service.deleteCluster(cmd));
    }

    @Test
    public void testDeleteCluster_hasStoragePools_throws() {
        DeleteClusterCmd cmd = new DeleteClusterCmd();
        setField(cmd, "id", CLUSTER_ID);
        ClusterVO cluster = mockCluster(HypervisorType.KVM);
        when(clusterDao.lockRow(CLUSTER_ID, true)).thenReturn(cluster);
        when(hostDao.listIdsByClusterId(CLUSTER_ID)).thenReturn(Collections.emptyList());
        StoragePoolVO pool = new StoragePoolVO();
        when(storagePoolDao.listPoolsByCluster(CLUSTER_ID)).thenReturn(List.of(pool));
        assertThrows(CloudRuntimeException.class, () -> service.deleteCluster(cmd));
    }

    @Test
    public void testDeleteCluster_kvmCluster_removesCapacityAndDedicationAndAnnotations() {
        DeleteClusterCmd cmd = new DeleteClusterCmd();
        setField(cmd, "id", CLUSTER_ID);
        ClusterVO cluster = mockCluster(HypervisorType.KVM);
        when(clusterDao.lockRow(CLUSTER_ID, true)).thenReturn(cluster);
        when(hostDao.listIdsByClusterId(CLUSTER_ID)).thenReturn(Collections.emptyList());
        when(storagePoolDao.listPoolsByCluster(CLUSTER_ID)).thenReturn(Collections.emptyList());
        when(clusterDao.remove(CLUSTER_ID)).thenReturn(true);
        DedicatedResourceVO dr = new DedicatedResourceVO(ZONE_ID, null, null, CLUSTER_ID, null, 1L, 1L);
        setField(dr, "id", 99L);
        when(dedicatedDao.findByClusterId(CLUSTER_ID)).thenReturn(dr);

        boolean result = service.deleteCluster(cmd);

        assertTrue(result);
        verify(capacityDao).removeBy(isNull(), isNull(), isNull(), eq(CLUSTER_ID), isNull());
        verify(dedicatedDao).remove(99L);
        verify(annotationDao).removeByEntityType(eq(AnnotationService.EntityType.CLUSTER.name()), anyString());
    }

    @Test
    public void testDeleteCluster_vmwareWithNexusVSwitch_removesVsmMap() {
        DeleteClusterCmd cmd = new DeleteClusterCmd();
        setField(cmd, "id", CLUSTER_ID);
        ClusterVO cluster = mockCluster(HypervisorType.VMware);
        when(clusterDao.lockRow(CLUSTER_ID, true)).thenReturn(cluster);
        when(hostDao.listIdsByClusterId(CLUSTER_ID)).thenReturn(Collections.emptyList());
        when(storagePoolDao.listPoolsByCluster(CLUSTER_ID)).thenReturn(Collections.emptyList());
        when(clusterDao.remove(CLUSTER_ID)).thenReturn(true);
        when(dedicatedDao.findByClusterId(CLUSTER_ID)).thenReturn(null);
        when(configDao.getValue(Config.VmwareUseNexusVSwitch.toString())).thenReturn("true");

        boolean result = service.deleteCluster(cmd);

        assertTrue(result);
        verify(clusterVSMMapDao).removeByClusterId(CLUSTER_ID);
    }

    @Test
    public void testDeleteCluster_kvmCluster_doesNotRemoveVsmMap() {
        DeleteClusterCmd cmd = new DeleteClusterCmd();
        setField(cmd, "id", CLUSTER_ID);
        ClusterVO cluster = mockCluster(HypervisorType.KVM);
        when(clusterDao.lockRow(CLUSTER_ID, true)).thenReturn(cluster);
        when(hostDao.listIdsByClusterId(CLUSTER_ID)).thenReturn(Collections.emptyList());
        when(storagePoolDao.listPoolsByCluster(CLUSTER_ID)).thenReturn(Collections.emptyList());
        when(clusterDao.remove(CLUSTER_ID)).thenReturn(true);
        when(dedicatedDao.findByClusterId(CLUSTER_ID)).thenReturn(null);

        service.deleteCluster(cmd);

        verify(clusterVSMMapDao, never()).removeByClusterId(anyLong());
    }

    // =====================================================================
    // updateCluster tests
    // =====================================================================

    @Test
    public void testUpdateCluster_noChanges_returnsCluster() {
        ClusterVO cluster = mockCluster(HypervisorType.KVM);
        when(callbacks.getCluster(CLUSTER_ID)).thenReturn(cluster);
        when(extensionsManager.extensionResourceMapDetailsNeedUpdate(
                anyLong(), any(), any())).thenReturn(new Pair<>(false, null));
        UpdateClusterCmd cmd = new UpdateClusterCmd();
        setField(cmd, "id", CLUSTER_ID);
        when(clusterDao.findById(CLUSTER_ID)).thenReturn(cluster);

        Cluster result = service.updateCluster(cmd);

        assertNotNull(result);
        verify(clusterDao, never()).update(anyLong(), any());
    }

    @Test
    public void testUpdateCluster_renameVmwareCluster_throws() {
        ClusterVO cluster = mockCluster(HypervisorType.VMware);
        when(callbacks.getCluster(CLUSTER_ID)).thenReturn(cluster);
        when(extensionsManager.extensionResourceMapDetailsNeedUpdate(
                anyLong(), any(), any())).thenReturn(new Pair<>(false, null));
        UpdateClusterCmd cmd = new UpdateClusterCmd();
        setField(cmd, "id", CLUSTER_ID);
        setField(cmd, "clusterName", "newName");

        assertThrows(InvalidParameterValueException.class, () -> service.updateCluster(cmd));
    }

    @Test
    public void testUpdateCluster_validHypervisorChange_updatesCluster() {
        ClusterVO cluster = mockCluster(HypervisorType.KVM);
        when(callbacks.getCluster(CLUSTER_ID)).thenReturn(cluster);
        when(extensionsManager.extensionResourceMapDetailsNeedUpdate(
                anyLong(), any(), any())).thenReturn(new Pair<>(false, null));
        UpdateClusterCmd cmd = new UpdateClusterCmd();
        setField(cmd, "id", CLUSTER_ID);
        setField(cmd, "hypervisor", "XenServer");
        when(clusterDao.findById(CLUSTER_ID)).thenReturn(cluster);

        Cluster result = service.updateCluster(cmd);

        assertNotNull(result);
        verify(clusterDao).update(eq(CLUSTER_ID), any(ClusterVO.class));
    }

    @Test
    public void testUpdateCluster_invalidAllocationState_throws() {
        ClusterVO cluster = mockCluster(HypervisorType.KVM);
        when(callbacks.getCluster(CLUSTER_ID)).thenReturn(cluster);
        when(extensionsManager.extensionResourceMapDetailsNeedUpdate(
                anyLong(), any(), any())).thenReturn(new Pair<>(false, null));
        UpdateClusterCmd cmd = new UpdateClusterCmd();
        setField(cmd, "id", CLUSTER_ID);
        setField(cmd, "allocationState", "BADSTATE");

        assertThrows(InvalidParameterValueException.class, () -> service.updateCluster(cmd));
    }

    @Test
    public void testUpdateCluster_prepareUnmanaged_callsUmanageOnUpHosts() {
        ClusterVO cluster = mockCluster(HypervisorType.KVM);
        cluster.setManagedState(Managed.ManagedState.Managed);
        when(callbacks.getCluster(CLUSTER_ID)).thenReturn(cluster);
        when(extensionsManager.extensionResourceMapDetailsNeedUpdate(
                anyLong(), any(), any())).thenReturn(new Pair<>(false, null));

        HostVO upHost = new HostVO("guid-up");
        setField(upHost, "id", 55L);
        setField(upHost, "status", Status.Up);
        setField(upHost, "type", Host.Type.Routing);

        HostVO downHost = new HostVO("guid-down");
        setField(downHost, "id", 56L);
        setField(downHost, "status", Status.Down);
        setField(downHost, "type", Host.Type.Routing);

        when(hostLookupService.listAllHosts(eq(Host.Type.Routing), eq(CLUSTER_ID), any(), anyLong()))
                .thenReturn(List.of(upHost, downHost));
        // After umanage, simulate hosts go down
        when(hostLookupService.listAllUpAndEnabledHosts(eq(Host.Type.Routing), eq(CLUSTER_ID), any(), anyLong()))
                .thenReturn(Collections.emptyList());
        when(callbacks.umanageHost(55L)).thenReturn(true);
        when(clusterDao.findById(CLUSTER_ID)).thenReturn(cluster);

        UpdateClusterCmd cmd = new UpdateClusterCmd();
        setField(cmd, "id", CLUSTER_ID);
        setField(cmd, "managedState", "Unmanaged");

        Cluster result = service.updateCluster(cmd);

        assertNotNull(result);
        verify(callbacks).umanageHost(55L);
        verify(callbacks, never()).umanageHost(56L);
    }

    // =====================================================================
    // helpers
    // =====================================================================

    private ClusterVO mockCluster(HypervisorType type) {
        ClusterVO cluster = new ClusterVO(ZONE_ID, POD_ID, "testcluster");
        setField(cluster, "id", CLUSTER_ID);
        cluster.setHypervisorType(type.toString());
        cluster.setManagedState(Managed.ManagedState.Managed);
        return cluster;
    }

    private Account mockCallingAccount(boolean rootAdmin) {
        Account account = org.mockito.Mockito.mock(Account.class);
        when(account.getId()).thenReturn(100L);
        // CallContext cannot be easily mocked without a running context;
        // just return the account for the accountManager check
        return account;
    }

    private static void setField(Object target, String fieldName, Object value) {
        try {
            Class<?> clazz = target.getClass();
            while (clazz != null) {
                try {
                    java.lang.reflect.Field f = clazz.getDeclaredField(fieldName);
                    f.setAccessible(true);
                    f.set(target, value);
                    return;
                } catch (NoSuchFieldException e) {
                    clazz = clazz.getSuperclass();
                }
            }
            throw new RuntimeException("Field " + fieldName + " not found on " + target.getClass());
        } catch (IllegalAccessException e) {
            throw new RuntimeException(e);
        }
    }
}
