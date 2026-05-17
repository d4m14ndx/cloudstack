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

import java.util.Iterator;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.storage.CancelPrimaryStorageMaintenanceCmd;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.StorageUnavailableException;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Primary storage pool maintenance state transitions — extracted from
 * {@link StorageManagerImpl}.
 *
 * @see PrimaryStorageMaintenanceService
 */
@Component
public class PrimaryStorageMaintenanceServiceImpl implements PrimaryStorageMaintenanceService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected PrimaryDataStoreDao storagePoolDao;
    @Inject
    protected DataStoreManager dataStoreMgr;
    @Inject
    protected DataStoreProviderManager dataStoreProviderMgr;

    @Override
    public PrimaryDataStoreInfo preparePrimaryStorageForMaintenance(Long primaryStorageId) throws ResourceUnavailableException, InsufficientCapacityException {
        StoragePoolVO primaryStorage = null;
        primaryStorage = storagePoolDao.findById(primaryStorageId);

        if (primaryStorage == null) {
            String msg = "Unable to obtain lock on the storage pool record in preparePrimaryStorageForMaintenance()";
            logger.error(msg);
            throw new InvalidParameterValueException(msg);
        }

        if (!primaryStorage.getStatus().equals(StoragePoolStatus.Up) && !primaryStorage.getStatus().equals(StoragePoolStatus.ErrorInMaintenance)) {
            throw new InvalidParameterValueException(String.format("Primary storage %s is not ready for migration, as the status is:%s", primaryStorage, primaryStorage.getStatus().toString()));
        }

        DataStoreProvider provider = dataStoreProviderMgr.getDataStoreProvider(primaryStorage.getStorageProviderName());
        DataStoreLifeCycle lifeCycle = provider.getDataStoreLifeCycle();
        DataStore store = dataStoreMgr.getDataStore(primaryStorage.getId(), DataStoreRole.Primary);

        if (primaryStorage.getPoolType() == StoragePoolType.DatastoreCluster) {
            if (primaryStorage.getStatus() == StoragePoolStatus.PrepareForMaintenance) {
                throw new CloudRuntimeException(String.format("There is already a job running for preparation for maintenance of the storage pool %s", primaryStorage));
            }
            handlePrepareDatastoreClusterMaintenance(lifeCycle, primaryStorageId);
        }
        lifeCycle.maintain(store);

        return (PrimaryDataStoreInfo)dataStoreMgr.getDataStore(primaryStorage.getId(), DataStoreRole.Primary);
    }

    protected void handlePrepareDatastoreClusterMaintenance(DataStoreLifeCycle lifeCycle, Long primaryStorageId) {
        StoragePoolVO datastoreCluster = storagePoolDao.findById(primaryStorageId);
        datastoreCluster.setStatus(StoragePoolStatus.PrepareForMaintenance);
        storagePoolDao.update(datastoreCluster.getId(), datastoreCluster);

        // Before preparing the datastorecluster to maintenance mode, the storagepools in the datastore cluster needs to put in maintenance
        List<StoragePoolVO> childDatastores = storagePoolDao.listChildStoragePoolsInDatastoreCluster(primaryStorageId);
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                for (StoragePoolVO childDatastore : childDatastores) {
                    // set the pool state to prepare for maintenance, so that VMs will not migrate to the storagepools in the same cluster
                    childDatastore.setStatus(StoragePoolStatus.PrepareForMaintenance);
                    storagePoolDao.update(childDatastore.getId(), childDatastore);
                }
            }
        });
        for (Iterator<StoragePoolVO> iteratorChildDatastore = childDatastores.listIterator(); iteratorChildDatastore.hasNext(); ) {
            DataStore childStore = dataStoreMgr.getDataStore(iteratorChildDatastore.next().getId(), DataStoreRole.Primary);
            try {
                lifeCycle.maintain(childStore);
            } catch (Exception e) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Exception on maintenance preparation of one of the child datastores in datastore cluster {} with error {}", datastoreCluster, e);
                }
                // Set to ErrorInMaintenance state of all child storage pools and datastore cluster
                for (StoragePoolVO childDatastore : childDatastores) {
                    childDatastore.setStatus(StoragePoolStatus.ErrorInMaintenance);
                    storagePoolDao.update(childDatastore.getId(), childDatastore);
                }
                datastoreCluster.setStatus(StoragePoolStatus.ErrorInMaintenance);
                storagePoolDao.update(datastoreCluster.getId(), datastoreCluster);
                throw new CloudRuntimeException(String.format("Failed to prepare maintenance mode for datastore cluster %s with error %s %s", datastoreCluster, e.getMessage(), e));
            }
        }
    }

    @Override
    public PrimaryDataStoreInfo cancelPrimaryStorageForMaintenance(CancelPrimaryStorageMaintenanceCmd cmd) throws ResourceUnavailableException {
        Long primaryStorageId = cmd.getId();
        StoragePoolVO primaryStorage = null;

        primaryStorage = storagePoolDao.findById(primaryStorageId);

        if (primaryStorage == null) {
            String msg = "Unable to obtain lock on the storage pool in cancelPrimaryStorageForMaintenance()";
            logger.error(msg);
            throw new InvalidParameterValueException(msg);
        }

        if (primaryStorage.getStatus().equals(StoragePoolStatus.Up) || primaryStorage.getStatus().equals(StoragePoolStatus.PrepareForMaintenance)) {
            throw new StorageUnavailableException("Primary storage " + primaryStorage + " is not ready to complete migration, as the status is:" + primaryStorage.getStatus().toString(),
                    primaryStorageId);
        }

        DataStoreProvider provider = dataStoreProviderMgr.getDataStoreProvider(primaryStorage.getStorageProviderName());
        DataStoreLifeCycle lifeCycle = provider.getDataStoreLifeCycle();
        DataStore store = dataStoreMgr.getDataStore(primaryStorage.getId(), DataStoreRole.Primary);
        if (primaryStorage.getPoolType() == StoragePoolType.DatastoreCluster) {
            primaryStorage.setStatus(StoragePoolStatus.Up);
            storagePoolDao.update(primaryStorage.getId(), primaryStorage);
            //FR41 need to handle when one of the primary stores is unable to cancel the maintenance mode
            List<StoragePoolVO> childDatastores = storagePoolDao.listChildStoragePoolsInDatastoreCluster(primaryStorageId);
            for (StoragePoolVO childDatastore : childDatastores) {
                DataStore childStore = dataStoreMgr.getDataStore(childDatastore.getId(), DataStoreRole.Primary);
                lifeCycle.cancelMaintain(childStore);
            }
        }
        lifeCycle.cancelMaintain(store);

        return (PrimaryDataStoreInfo)dataStoreMgr.getDataStore(primaryStorage.getId(), DataStoreRole.Primary);
    }
}
