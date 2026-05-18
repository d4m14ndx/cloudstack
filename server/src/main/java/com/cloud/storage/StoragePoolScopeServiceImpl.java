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

import java.util.List;
import java.util.Set;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.storage.ChangeStoragePoolScopeCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.ClusterScope;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreLifeCycle;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.lang3.EnumUtils;
import org.springframework.stereotype.Component;

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
import com.google.common.collect.Sets;

@Component
public class StoragePoolScopeServiceImpl implements StoragePoolScopeService {

    private final Set<HypervisorType> zoneWidePoolSupportedHypervisorTypes =
            Sets.newHashSet(HypervisorType.KVM, HypervisorType.VMware);

    @Inject
    protected AccountManager accountMgr;
    @Inject
    protected PrimaryDataStoreDao storagePoolDao;
    @Inject
    protected DataCenterDao dcDao;
    @Inject
    protected ClusterDao clusterDao;
    @Inject
    protected VMInstanceDao vmInstanceDao;
    @Inject
    protected DataStoreProviderManager dataStoreProviderMgr;
    @Inject
    protected DataStoreManager dataStoreMgr;

    @Override
    public void changeStoragePoolScope(ChangeStoragePoolScopeCmd cmd) throws IllegalArgumentException, InvalidParameterValueException, PermissionDeniedException {
        Long id = cmd.getId();

        Long accountId = cmd.getEntityOwnerId();
        if (!accountMgr.isRootAdmin(accountId)) {
            throw new PermissionDeniedException("Only root admin can perform this operation");
        }

        ScopeType newScope = EnumUtils.getEnumIgnoreCase(ScopeType.class, cmd.getScope());
        if (newScope != ScopeType.ZONE && newScope != ScopeType.CLUSTER) {
            throw new InvalidParameterValueException("Invalid scope " + cmd.getScope() + "for Primary storage");
        }

        StoragePoolVO primaryStorage = storagePoolDao.findById(id);
        if (primaryStorage == null) {
            throw new IllegalArgumentException("Unable to find storage pool with ID: " + id);
        }

        String eventDetails = String.format(" Storage pool Id: %s to %s", primaryStorage.getUuid(), newScope);
        CallContext.current().setEventDetails(eventDetails);

        ScopeType currentScope = primaryStorage.getScope();
        if (currentScope.equals(newScope)) {
            throw new InvalidParameterValueException("New scope must be different than the current scope");
        }

        if (currentScope != ScopeType.ZONE && currentScope != ScopeType.CLUSTER) {
            throw new InvalidParameterValueException("This operation is supported only for Primary storages having scope "
                    + ScopeType.CLUSTER + " or " + ScopeType.ZONE);
        }

        if (!primaryStorage.getStatus().equals(StoragePoolStatus.Disabled)) {
            throw new InvalidParameterValueException("Scope of the Primary storage with id "
                    + primaryStorage.getUuid() +
                    " cannot be changed, as it is not in the Disabled state");
        }

        Long zoneId = primaryStorage.getDataCenterId();
        DataCenterVO zone = dcDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Unable to find zone by id " + zoneId);
        }
        if (zone.getAllocationState().equals(Grouping.AllocationState.Disabled)) {
            throw new PermissionDeniedException("Cannot perform this operation, Zone is currently disabled: " + zoneId);
        }

        if (newScope.equals(ScopeType.ZONE)) {
            changeStoragePoolScopeToZone(primaryStorage);
        } else {
            changeStoragePoolScopeToCluster(primaryStorage, cmd.getClusterId());
        }
    }

    protected void changeStoragePoolScopeToZone(StoragePoolVO primaryStorage) {
        Long clusterId = primaryStorage.getClusterId();
        ClusterVO clusterVO = clusterDao.findById(clusterId);
        HypervisorType hypervisorType = clusterVO.getHypervisorType();
        if (!zoneWidePoolSupportedHypervisorTypes.contains(hypervisorType)) {
            throw new InvalidParameterValueException("Primary storage scope change to Zone is not supported for hypervisor type " + hypervisorType);
        }

        DataStoreProvider storeProvider = dataStoreProviderMgr.getDataStoreProvider(primaryStorage.getStorageProviderName());
        PrimaryDataStoreLifeCycle lifeCycle = (PrimaryDataStoreLifeCycle) storeProvider.getDataStoreLifeCycle();

        DataStore primaryStore = dataStoreMgr.getPrimaryDataStore(primaryStorage.getId());
        ClusterScope clusterScope = new ClusterScope(primaryStorage.getClusterId(), null, primaryStorage.getDataCenterId());

        lifeCycle.changeStoragePoolScopeToZone(primaryStore, clusterScope, hypervisorType);
    }

    protected void changeStoragePoolScopeToCluster(StoragePoolVO primaryStorage, Long clusterId) {
        if (clusterId == null) {
            throw new InvalidParameterValueException("Cluster ID not provided");
        }
        ClusterVO clusterVO = clusterDao.findById(clusterId);
        if (clusterVO == null) {
            throw new InvalidParameterValueException("Unable to find cluster by id " + clusterId);
        }
        if (clusterVO.getAllocationState().equals(Grouping.AllocationState.Disabled)) {
            throw new PermissionDeniedException("Cannot perform this operation, Cluster is currently disabled: " + clusterId);
        }

        Long id = primaryStorage.getId();
        Pair<List<VMInstanceVO>, Integer> vmsNotInClusterUsingPool = vmInstanceDao.listByVmsNotInClusterUsingPool(clusterId, id);
        if (vmsNotInClusterUsingPool.second() != 0) {
            throw new CloudRuntimeException(String.format("Cannot change scope of the storage pool [%s] to cluster [%s] "
                            + "as there are %s VMs with volumes in this pool that are running on other clusters. "
                            + "All such User VMs must be stopped and System VMs must be destroyed before proceeding. "
                            + "Please use the API listAffectedVmsForStorageScopeChange to get the list.",
                    primaryStorage.getName(), clusterVO.getName(), vmsNotInClusterUsingPool.second()));
        }

        DataStoreProvider storeProvider = dataStoreProviderMgr.getDataStoreProvider(primaryStorage.getStorageProviderName());
        PrimaryDataStoreLifeCycle lifeCycle = (PrimaryDataStoreLifeCycle) storeProvider.getDataStoreLifeCycle();

        DataStore primaryStore = dataStoreMgr.getPrimaryDataStore(id);
        ClusterScope clusterScope = new ClusterScope(clusterId, clusterVO.getPodId(), primaryStorage.getDataCenterId());

        lifeCycle.changeStoragePoolScopeToCluster(primaryStore, clusterScope, primaryStorage.getHypervisor());
    }
}
