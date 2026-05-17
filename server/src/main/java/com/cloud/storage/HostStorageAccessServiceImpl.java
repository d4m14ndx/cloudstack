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

import java.util.Collections;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.springframework.stereotype.Component;

import com.cloud.host.Host;
import com.cloud.host.dao.HostDao;
import com.cloud.storage.dao.StoragePoolHostDao;

/**
 * Host-to-managed-storage-pool access checks — extracted from
 * {@link StorageManagerImpl}.
 *
 * @see HostStorageAccessService
 */
@Component
public class HostStorageAccessServiceImpl implements HostStorageAccessService {

    @Inject
    protected StoragePoolHostDao storagePoolHostDao;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected PrimaryDataStoreDao storagePoolDao;
    @Inject
    protected DataStoreProviderManager dataStoreProviderMgr;

    @Override
    public Host findUpAndEnabledHostWithAccessToStoragePools(List<Long> poolIds) {
        List<Long> hostIds = storagePoolHostDao.findHostsConnectedToPools(poolIds);
        if (hostIds.isEmpty()) {
            return null;
        }
        Collections.shuffle(hostIds);

        for (Long hostId : hostIds) {
            Host host = hostDao.findById(hostId);
            if (canHostAccessStoragePools(host, poolIds)) {
                return host;
            }
        }

        return null;
    }

    /**
     * Look up each {@code poolId} via the DAO and verify the host can
     * access every one of them via {@link #canHostAccessStoragePool}.
     *
     * @return {@code false} when the pool-id list is null/empty, or
     *         when any single pool refuses the host; {@code true} only
     *         when every pool accepts.
     */
    protected boolean canHostAccessStoragePools(Host host, List<Long> poolIds) {
        if (poolIds == null || poolIds.isEmpty()) {
            return false;
        }

        for (Long poolId : poolIds) {
            StoragePool pool = storagePoolDao.findById(poolId);
            if (!canHostAccessStoragePool(host, pool)) {
                return false;
            }
        }

        return true;
    }

    @Override
    public boolean canHostAccessStoragePool(Host host, StoragePool pool) {
        if (host == null || pool == null) {
            return false;
        }

        if (!pool.isManaged()) {
            return true;
        }

        DataStoreProvider storeProvider = dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();

        return (storeDriver instanceof PrimaryDataStoreDriver && ((PrimaryDataStoreDriver)storeDriver).canHostAccessStoragePool(host, pool));
    }

    @Override
    public boolean canHostPrepareStoragePoolAccess(Host host, StoragePool pool) {
        if (host == null || pool == null || !pool.isManaged()) {
            return false;
        }

        DataStoreProvider storeProvider = dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();
        return storeDriver instanceof PrimaryDataStoreDriver && ((PrimaryDataStoreDriver)storeDriver).canHostPrepareStoragePoolAccess(host, pool);
    }

    @Override
    public boolean canDisconnectHostFromStoragePool(Host host, StoragePool pool) {
        if (pool == null || !pool.isManaged()) {
            return true;
        }

        DataStoreProvider storeProvider = dataStoreProviderMgr.getDataStoreProvider(pool.getStorageProviderName());
        DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();
        return storeDriver instanceof PrimaryDataStoreDriver && ((PrimaryDataStoreDriver)storeDriver).canDisconnectHostFromStoragePool(host, pool);
    }
}
