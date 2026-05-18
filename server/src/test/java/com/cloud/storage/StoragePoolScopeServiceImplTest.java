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
package com.cloud.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.cloudstack.api.command.admin.storage.ChangeStoragePoolScopeCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreLifeCycle;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockedStatic;
import org.mockito.Mockito;
import org.mockito.Spy;
import org.mockito.junit.MockitoJUnitRunner;

import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.org.Grouping;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

@RunWith(MockitoJUnitRunner.class)
public class StoragePoolScopeServiceImplTest {

    private static final long POOL_ID = 1L;
    private static final long CLUSTER_ID = 2L;
    private static final long ZONE_ID = 3L;

    @Mock
    private AccountManager accountMgr;
    @Mock
    private PrimaryDataStoreDao storagePoolDao;
    @Mock
    private DataCenterDao dcDao;
    @Mock
    private ClusterDao clusterDao;
    @Mock
    private VMInstanceDao vmInstanceDao;
    @Mock
    private DataStoreProviderManager dataStoreProviderMgr;
    @Mock
    private DataStoreManager dataStoreMgr;

    @Mock
    private ChangeStoragePoolScopeCmd cmd;
    @Mock
    private StoragePoolVO primaryStorage;
    @Mock
    private DataCenterVO zone;
    @Mock
    private ClusterVO cluster;
    @Mock
    private DataStoreProvider dataStoreProvider;
    @Mock
    private PrimaryDataStoreLifeCycle lifeCycle;
    @Mock
    private DataStore primaryDataStore;
    @Mock
    private CallContext callContext;

    @Spy
    @InjectMocks
    private StoragePoolScopeServiceImpl service;

    @Before
    public void setUp() {
        when(cmd.getId()).thenReturn(POOL_ID);
        when(cmd.getEntityOwnerId()).thenReturn(99L);
        when(cmd.getClusterId()).thenReturn(CLUSTER_ID);
        when(cmd.getScope()).thenReturn("ZONE");

        when(accountMgr.isRootAdmin(99L)).thenReturn(true);
        when(storagePoolDao.findById(POOL_ID)).thenReturn(primaryStorage);

        when(primaryStorage.getId()).thenReturn(POOL_ID);
        when(primaryStorage.getUuid()).thenReturn("pool-uuid");
        when(primaryStorage.getName()).thenReturn("pool-name");
        when(primaryStorage.getDataCenterId()).thenReturn(ZONE_ID);
        when(primaryStorage.getClusterId()).thenReturn(CLUSTER_ID);
        when(primaryStorage.getScope()).thenReturn(ScopeType.CLUSTER);
        when(primaryStorage.getStatus()).thenReturn(StoragePoolStatus.Disabled);
        when(primaryStorage.getStorageProviderName()).thenReturn("nfs");
        when(primaryStorage.getHypervisor()).thenReturn(HypervisorType.KVM);

        when(dcDao.findById(ZONE_ID)).thenReturn(zone);
        when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);

        when(clusterDao.findById(CLUSTER_ID)).thenReturn(cluster);
        when(cluster.getAllocationState()).thenReturn(Grouping.AllocationState.Enabled);
        when(cluster.getHypervisorType()).thenReturn(HypervisorType.KVM);
        when(cluster.getName()).thenReturn("cluster-a");
        when(cluster.getPodId()).thenReturn(8L);

        when(dataStoreProviderMgr.getDataStoreProvider("nfs")).thenReturn(dataStoreProvider);
        when(dataStoreProvider.getDataStoreLifeCycle()).thenReturn(lifeCycle);
        when(dataStoreMgr.getPrimaryDataStore(POOL_ID)).thenReturn(primaryDataStore);
    }

    @Test
    public void changeStoragePoolScopeNonRootAdminThrows() {
        when(accountMgr.isRootAdmin(99L)).thenReturn(false);

        PermissionDeniedException exception = assertThrows(PermissionDeniedException.class,
                () -> service.changeStoragePoolScope(cmd));

        assertEquals("Only root admin can perform this operation", exception.getMessage());
    }

    @Test
    public void changeStoragePoolScopeInvalidScopeThrows() {
        when(cmd.getScope()).thenReturn("pod");

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.changeStoragePoolScope(cmd));

        assertEquals("Invalid scope podfor Primary storage", exception.getMessage());
    }

    @Test
    public void changeStoragePoolScopeMissingStoragePoolThrows() {
        when(storagePoolDao.findById(POOL_ID)).thenReturn(null);

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> service.changeStoragePoolScope(cmd));

        assertEquals("Unable to find storage pool with ID: 1", exception.getMessage());
    }

    @Test
    public void changeStoragePoolScopeSameScopeThrows() {
        when(primaryStorage.getScope()).thenReturn(ScopeType.ZONE);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.changeStoragePoolScope(cmd));

        assertEquals("New scope must be different than the current scope", exception.getMessage());
    }

    @Test
    public void changeStoragePoolScopeUnsupportedCurrentScopeThrows() {
        when(primaryStorage.getScope()).thenReturn(ScopeType.HOST);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.changeStoragePoolScope(cmd));

        assertEquals("This operation is supported only for Primary storages having scope CLUSTER or ZONE", exception.getMessage());
    }

    @Test
    public void changeStoragePoolScopeRequiresDisabledPool() {
        when(primaryStorage.getStatus()).thenReturn(StoragePoolStatus.Up);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.changeStoragePoolScope(cmd));

        assertTrue(exception.getMessage().contains("cannot be changed, as it is not in the Disabled state"));
        assertTrue(exception.getMessage().contains("pool-uuid"));
    }

    @Test
    public void changeStoragePoolScopeMissingZoneThrows() {
        when(dcDao.findById(ZONE_ID)).thenReturn(null);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.changeStoragePoolScope(cmd));

        assertEquals("Unable to find zone by id 3", exception.getMessage());
    }

    @Test
    public void changeStoragePoolScopeDisabledZoneThrows() {
        when(zone.getAllocationState()).thenReturn(Grouping.AllocationState.Disabled);

        PermissionDeniedException exception = assertThrows(PermissionDeniedException.class,
                () -> service.changeStoragePoolScope(cmd));

        assertEquals("Cannot perform this operation, Zone is currently disabled: 3", exception.getMessage());
    }

    @Test
    public void changeStoragePoolScopeToZoneRejectsUnsupportedHypervisor() {
        when(cluster.getHypervisorType()).thenReturn(HypervisorType.XenServer);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.changeStoragePoolScopeToZone(primaryStorage));

        assertEquals("Primary storage scope change to Zone is not supported for hypervisor type XenServer", exception.getMessage());
    }

    @Test
    public void changeStoragePoolScopeToZoneInvokesLifecycle() {
        service.changeStoragePoolScopeToZone(primaryStorage);

        verify(lifeCycle).changeStoragePoolScopeToZone(Mockito.eq(primaryDataStore), any(), Mockito.eq(HypervisorType.KVM));
    }

    @Test
    public void changeStoragePoolScopeToClusterRejectsNullClusterId() {
        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.changeStoragePoolScopeToCluster(primaryStorage, null));

        assertEquals("Cluster ID not provided", exception.getMessage());
    }

    @Test
    public void changeStoragePoolScopeToClusterRejectsMissingTargetCluster() {
        when(clusterDao.findById(CLUSTER_ID)).thenReturn(null);

        InvalidParameterValueException exception = assertThrows(InvalidParameterValueException.class,
                () -> service.changeStoragePoolScopeToCluster(primaryStorage, CLUSTER_ID));

        assertEquals("Unable to find cluster by id 2", exception.getMessage());
    }

    @Test
    public void changeStoragePoolScopeToClusterRejectsDisabledCluster() {
        when(cluster.getAllocationState()).thenReturn(Grouping.AllocationState.Disabled);

        PermissionDeniedException exception = assertThrows(PermissionDeniedException.class,
                () -> service.changeStoragePoolScopeToCluster(primaryStorage, CLUSTER_ID));

        assertEquals("Cannot perform this operation, Cluster is currently disabled: 2", exception.getMessage());
    }

    @Test
    public void changeStoragePoolScopeToClusterRejectsForeignClusterVms() {
        VMInstanceVO instance = Mockito.mock(VMInstanceVO.class);
        when(vmInstanceDao.listByVmsNotInClusterUsingPool(CLUSTER_ID, POOL_ID))
                .thenReturn(new Pair<>(List.of(instance), 1));

        CloudRuntimeException exception = assertThrows(CloudRuntimeException.class,
                () -> service.changeStoragePoolScopeToCluster(primaryStorage, CLUSTER_ID));

        assertTrue(exception.getMessage().contains("Cannot change scope of the storage pool [pool-name] to cluster [cluster-a]"));
        assertTrue(exception.getMessage().contains("there are 1 VMs"));
    }

    @Test
    public void changeStoragePoolScopeToClusterInvokesLifecycle() {
        when(vmInstanceDao.listByVmsNotInClusterUsingPool(CLUSTER_ID, POOL_ID))
                .thenReturn(new Pair<>(List.of(), 0));

        service.changeStoragePoolScopeToCluster(primaryStorage, CLUSTER_ID);

        verify(dataStoreMgr).getPrimaryDataStore(POOL_ID);
        verify(lifeCycle).changeStoragePoolScopeToCluster(Mockito.eq(primaryDataStore), any(), Mockito.eq(HypervisorType.KVM));
    }

    @Test
    public void changeStoragePoolScopeSetsEventDetailsAndRoutesToZoneHelper() {
        doNothing().when(service).changeStoragePoolScopeToZone(primaryStorage);

        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            when(CallContext.current()).thenReturn(callContext);

            service.changeStoragePoolScope(cmd);

            verify(callContext).setEventDetails(" Storage pool Id: pool-uuid to ZONE");
            verify(service).changeStoragePoolScopeToZone(primaryStorage);
            verify(service, never()).changeStoragePoolScopeToCluster(any(), anyLong());
        }
    }

    @Test
    public void changeStoragePoolScopeRoutesToClusterHelper() {
        when(cmd.getScope()).thenReturn("CLUSTER");
        when(primaryStorage.getScope()).thenReturn(ScopeType.ZONE);
        doNothing().when(service).changeStoragePoolScopeToCluster(primaryStorage, CLUSTER_ID);

        try (MockedStatic<CallContext> ignored = Mockito.mockStatic(CallContext.class)) {
            when(CallContext.current()).thenReturn(callContext);

            service.changeStoragePoolScope(cmd);

            verify(service).changeStoragePoolScopeToCluster(primaryStorage, CLUSTER_ID);
            verify(service, never()).changeStoragePoolScopeToZone(any());
        }
    }
}
