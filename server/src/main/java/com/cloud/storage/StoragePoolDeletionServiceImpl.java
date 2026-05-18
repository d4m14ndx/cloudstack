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

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.storage.DeletePoolCmd;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult;
import org.apache.cloudstack.framework.async.AsyncCallFuture;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class StoragePoolDeletionServiceImpl implements StoragePoolDeletionService {

    private static final Logger logger = LogManager.getLogger(StoragePoolDeletionServiceImpl.class);

    @Inject
    protected PrimaryDataStoreDao storagePoolDao;
    @Inject
    protected VolumeDao volumeDao;
    @Inject
    protected VolumeService volService;
    @Inject
    protected VolumeDataFactory volFactory;
    @Inject
    protected DataStoreProviderManager dataStoreProviderMgr;
    @Inject
    protected DataStoreManager dataStoreMgr;
    @Inject
    protected VMInstanceDao vmInstanceDao;

    @Override
    public boolean deletePool(DeletePoolCmd cmd) {
        Long id = cmd.getId();
        boolean forced = cmd.isForced();

        StoragePoolVO sPool = storagePoolDao.findById(id);
        if (sPool == null) {
            logger.warn("Unable to find pool:" + id);
            throw new InvalidParameterValueException("Unable to find pool by id " + id);
        }
        if (sPool.getStatus() != StoragePoolStatus.Maintenance) {
            logger.warn("Unable to delete storage pool: {} due to it is not in Maintenance state", sPool);
            throw new InvalidParameterValueException(String.format("Unable to delete storage due to it is not in Maintenance state, pool: %s", sPool));
        }

        if (sPool.getPoolType() == StoragePoolType.DatastoreCluster) {
            // FR41 yet to handle on failure of deletion of any of the child storage pool
            if (checkIfDataStoreClusterCanbeDeleted(sPool, forced)) {
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(TransactionStatus status) {
                        List<StoragePoolVO> childStoragePools = storagePoolDao.listChildStoragePoolsInDatastoreCluster(sPool.getId());
                        for (StoragePoolVO childPool : childStoragePools) {
                            deleteDataStoreInternal(childPool, forced);
                        }
                    }
                });
            } else {
                logger.debug("Cannot delete storage pool {} as the following non-destroyed volumes are on it: {}.", sPool::toString, () -> getStoragePoolNonDestroyedVolumesLog(sPool.getId()));
                throw new CloudRuntimeException(String.format("Cannot delete pool %s as there are associated non-destroyed vols for this pool", sPool));
            }
        }
        return deleteDataStoreInternal(sPool, forced);
    }

    protected boolean checkIfDataStoreClusterCanbeDeleted(StoragePoolVO sPool, boolean forced) {
        List<StoragePoolVO> childStoragePools = storagePoolDao.listChildStoragePoolsInDatastoreCluster(sPool.getId());
        boolean canDelete = true;
        for (StoragePoolVO childPool : childStoragePools) {
            Pair<Long, Long> vlms = volumeDao.getCountAndTotalByPool(childPool.getId());
            if (forced) {
                if (vlms.first() > 0) {
                    Pair<Long, Long> nonDstrdVlms = volumeDao.getNonDestroyedCountAndTotalByPool(childPool.getId());
                    if (nonDstrdVlms.first() > 0) {
                        canDelete = false;
                        break;
                    }
                }
            } else {
                if (vlms.first() > 0) {
                    canDelete = false;
                    break;
                }
            }
        }
        return canDelete;
    }

    protected boolean deleteDataStoreInternal(StoragePoolVO sPool, boolean forced) {
        Pair<Long, Long> vlms = volumeDao.getCountAndTotalByPool(sPool.getId());
        if (forced) {
            if (vlms.first() > 0) {
                Pair<Long, Long> nonDstrdVlms = volumeDao.getNonDestroyedCountAndTotalByPool(sPool.getId());
                if (nonDstrdVlms.first() > 0) {
                    logger.debug("Cannot delete storage pool {} as the following non-destroyed volumes are on it: {}.", sPool::toString, () -> getStoragePoolNonDestroyedVolumesLog(sPool.getId()));
                    throw new CloudRuntimeException(String.format("Cannot delete pool %s as there are non-destroyed volumes associated to this pool.", sPool));
                }
                // force expunge non-destroyed volumes
                List<VolumeVO> vols = volumeDao.listVolumesToBeDestroyed();
                for (VolumeVO vol : vols) {
                    AsyncCallFuture<VolumeApiResult> future = volService.expungeVolumeAsync(volFactory.getVolume(vol.getId()));
                    try {
                        future.get();
                    } catch (InterruptedException | ExecutionException e) {
                        logger.debug("expunge volume failed: {}", vol, e);
                    }
                }
            }
        } else {
            // Check if the pool has associated volumes in the volumes table
            // If it does , then you cannot delete the pool
            if (vlms.first() > 0) {
                logger.debug("Cannot delete storage pool {} as the following non-destroyed volumes are on it: {}.", sPool::toString, () -> getStoragePoolNonDestroyedVolumesLog(sPool.getId()));
                throw new CloudRuntimeException(String.format("Cannot delete pool %s as there are non-destroyed volumes associated to this pool.", sPool));
            }
        }

        // First get the host_id from storage_pool_host_ref for given pool id
        StoragePoolVO lock = storagePoolDao.acquireInLockTable(sPool.getId());

        if (lock == null) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to acquire lock when deleting PrimaryDataStoreVO: {}", sPool);
            }
            return false;
        }

        storagePoolDao.releaseFromLockTable(lock.getId());
        logger.trace("Released lock for storage pool {}", sPool);

        DataStoreProvider storeProvider = dataStoreProviderMgr.getDataStoreProvider(sPool.getStorageProviderName());
        DataStoreLifeCycle lifeCycle = storeProvider.getDataStoreLifeCycle();
        DataStore store = dataStoreMgr.getDataStore(sPool.getId(), DataStoreRole.Primary);
        return lifeCycle.deleteDataStore(store);
    }

    @Override
    public String getStoragePoolNonDestroyedVolumesLog(long storagePoolId) {
        StringBuilder sb = new StringBuilder();
        List<VolumeVO> nonDestroyedVols = volumeDao.findNonDestroyedVolumesByPoolId(storagePoolId, null);
        VMInstanceVO volInstance;
        List<String> logMessageInfo = new ArrayList<>();

        sb.append("[");
        for (VolumeVO vol : nonDestroyedVols) {
            if (vol.getInstanceId() != null) {
                volInstance = vmInstanceDao.findById(vol.getInstanceId());
                if (volInstance != null) {
                    logMessageInfo.add(String.format("Volume [%s] (attached to VM [%s])", vol.getUuid(), volInstance.getUuid()));
                } else {
                    logMessageInfo.add(String.format("Volume [%s] (attached VM with ID [%d] doesn't exists)", vol.getUuid(), vol.getInstanceId()));
                }
            } else {
                logMessageInfo.add(String.format("Volume [%s] (not attached to any VM)", vol.getUuid()));
            }
        }
        sb.append(String.join(", ", logMessageInfo));
        sb.append("]");

        return sb.toString();
    }
}
