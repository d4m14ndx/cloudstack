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

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.admin.storage.DeleteObjectStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateObjectStoragePoolCmd;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreLifeCycle;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreDetailsDao;
import org.apache.cloudstack.storage.datastore.db.ObjectStoreVO;
import org.apache.cloudstack.storage.object.ObjectStore;
import org.apache.cloudstack.storage.object.ObjectStoreEntity;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.capacity.CapacityVO;
import com.cloud.configuration.Resource.ResourceType;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.storage.dao.BucketDao;
import com.cloud.utils.UriUtils;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;

/**
 * {@link Component} implementation of {@link ObjectStoreService}.
 *
 * <p>Extracted from {@link StorageManagerImpl} (Phase 4, slice 8). All method
 * bodies are moved verbatim; the god class keeps one-line delegating wrappers
 * carrying the {@code @ActionEvent} annotations so the event framework is
 * unaffected.
 */
@Component
public class ObjectStoreServiceImpl implements ObjectStoreService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected DataStoreManager dataStoreMgr;

    @Inject
    protected DataStoreProviderManager dataStoreProviderMgr;

    @Inject
    protected ObjectStoreDao objectStoreDao;

    @Inject
    protected ObjectStoreDetailsDao objectStoreDetailsDao;

    @Inject
    protected BucketDao bucketDao;

    @Inject
    protected StorageCapacityService storageCapacityService;

    @Override
    public ObjectStore discoverObjectStore(String name, String url, Long size, String providerName, Map details)
            throws IllegalArgumentException, InvalidParameterValueException {
        DataStoreProvider storeProvider = dataStoreProviderMgr.getDataStoreProvider(providerName);

        if (storeProvider == null) {
            throw new InvalidParameterValueException("can't find object store provider: " + providerName);
        }

        // Check Unique object store name
        ObjectStoreVO objectStore = objectStoreDao.findByName(name);
        if (objectStore != null) {
            throw new InvalidParameterValueException("The object store with name " + name + " already exists, try creating with another name");
        }

        try {
            UriUtils.validateUrl(url);
        } catch (InvalidParameterValueException e) {
            throw new InvalidParameterValueException(url + " is not a valid URL:" + e.getMessage());
        }

        // Check Unique object store url
        ObjectStoreVO objectStoreUrl = objectStoreDao.findByUrl(url);
        if (objectStoreUrl != null) {
            throw new InvalidParameterValueException("The object store with url " + url + " already exists");
        }

        Map<String, Object> params = new HashMap<>();
        params.put("url", url);
        params.put("name", name);
        if (size == null) {
            params.put("size", 0L);
        } else {
            params.put("size", size);
        }
        params.put("providerName", storeProvider.getName());
        params.put("role", DataStoreRole.Object);
        params.put("details", details);

        DataStoreLifeCycle lifeCycle = storeProvider.getDataStoreLifeCycle();

        DataStore store;
        try {
            store = lifeCycle.initialize(params);
        } catch (Exception e) {
            if (logger.isDebugEnabled()) {
                logger.debug("Failed to add object store: " + e.getMessage(), e);
            }
            throw new CloudRuntimeException("Failed to add object store: " + e.getMessage(), e);
        }

        return (ObjectStore) dataStoreMgr.getDataStore(store.getId(), DataStoreRole.Object);
    }

    @Override
    public boolean deleteObjectStore(DeleteObjectStoragePoolCmd cmd) {
        final long storeId = cmd.getId();
        // Verify that object store exists
        ObjectStoreVO store = objectStoreDao.findById(storeId);
        if (store == null) {
            throw new InvalidParameterValueException("Object store with id " + storeId + " doesn't exist");
        }

        // Verify that there are no buckets in the store
        List<BucketVO> buckets = bucketDao.listByObjectStoreId(storeId);
        if (buckets != null && buckets.size() > 0) {
            throw new InvalidParameterValueException("Cannot delete object store with buckets");
        }

        // ready to delete
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(TransactionStatus status) {
                objectStoreDetailsDao.deleteDetails(storeId);
                objectStoreDao.remove(storeId);
            }
        });
        logger.debug("Successfully deleted object store: {}", store);
        return true;
    }

    @Override
    public ObjectStore updateObjectStore(Long id, UpdateObjectStoragePoolCmd cmd) {

        // Input validation
        ObjectStoreVO objectStoreVO = objectStoreDao.findById(id);
        if (objectStoreVO == null) {
            throw new IllegalArgumentException("Unable to find object store with ID: " + id);
        }

        if (cmd.getUrl() != null) {
            String url = cmd.getUrl();
            try {
                // Check URL
                UriUtils.validateUrl(url);
            } catch (final Exception e) {
                throw new InvalidParameterValueException(url + " is not a valid URL");
            }
            ObjectStoreEntity objectStore = (ObjectStoreEntity) dataStoreMgr.getDataStore(objectStoreVO.getId(), DataStoreRole.Object);
            String oldUrl = objectStoreVO.getUrl();
            objectStoreVO.setUrl(url);
            objectStoreDao.update(id, objectStoreVO);
            //Update URL and check access
            try {
                objectStore.listBuckets();
            } catch (Exception e) {
                //Revert to old URL on failure
                objectStoreVO.setUrl(oldUrl);
                objectStoreDao.update(id, objectStoreVO);
                throw new IllegalArgumentException("Unable to access Object Storage with URL: " + cmd.getUrl());
            }
        }

        if (cmd.getName() != null) {
            objectStoreVO.setName(cmd.getName());
        }
        if (cmd.getSize() != null) {
            objectStoreVO.setTotalSize(cmd.getSize() * ResourceType.bytesToGiB);
        }
        objectStoreDao.update(id, objectStoreVO);
        logger.debug("Successfully updated object store: {}", objectStoreVO);
        return objectStoreVO;
    }

    @Override
    public CapacityVO getObjectStorageUsedStats(Long zoneId) {
        return storageCapacityService.getObjectStorageUsedStats(zoneId);
    }
}
