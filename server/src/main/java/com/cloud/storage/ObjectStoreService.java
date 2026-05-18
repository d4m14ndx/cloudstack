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

import java.util.Map;

import org.apache.cloudstack.api.command.admin.storage.DeleteObjectStoragePoolCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateObjectStoragePoolCmd;
import org.apache.cloudstack.storage.object.ObjectStore;

import com.cloud.capacity.CapacityVO;
import com.cloud.exception.InvalidParameterValueException;

/**
 * Object-store lifecycle management — extracted from {@link StorageManagerImpl}.
 *
 * <p>This slice owns the four public object-store entry points:
 *
 * <ul>
 *   <li><b>Discover</b> — validate provider, name, and URL uniqueness, build the
 *       initialisation params map, invoke the provider's
 *       {@link org.apache.cloudstack.engine.subsystem.api.storage.DataStoreLifeCycle#initialize}
 *       and return the resulting {@link ObjectStore}.</li>
 *   <li><b>Delete</b> — ensure no buckets remain, then delete details and the
 *       store record in a single transaction.</li>
 *   <li><b>Update</b> — validate and apply a new URL (testing connectivity with
 *       a {@code listBuckets()} call and reverting on failure), name, and size.</li>
 *   <li><b>Used-stats delegation</b> — thin delegate to
 *       {@link StorageCapacityService#getObjectStorageUsedStats(Long)} so all
 *       four object-store entry points live in one component.</li>
 * </ul>
 *
 * <p>The corresponding public {@link com.cloud.storage.StorageManager} methods
 * stay as one-line delegates so the event framework ({@code @ActionEvent}) and
 * existing callers are unaffected.
 */
public interface ObjectStoreService {

    /**
     * Discover (register) a new object store with the given {@code name},
     * endpoint {@code url}, optional total {@code size} in GiB, and
     * {@code providerName}. The {@code details} map is passed verbatim to
     * the provider's lifecycle initialisation.
     *
     * @throws InvalidParameterValueException if the provider is unknown, the
     *         name or URL is already taken, or the URL is malformed.
     * @throws com.cloud.utils.exception.CloudRuntimeException if the
     *         provider's lifecycle {@code initialize} call fails.
     */
    ObjectStore discoverObjectStore(String name, String url, Long size, String providerName, Map details)
            throws IllegalArgumentException, InvalidParameterValueException;

    /**
     * Delete the object store identified by {@code cmd.getId()}. Fails if
     * the store still contains buckets.
     *
     * @throws InvalidParameterValueException if the store does not exist or
     *         buckets are present.
     * @return {@code true} on success.
     */
    boolean deleteObjectStore(DeleteObjectStoragePoolCmd cmd);

    /**
     * Update the name, URL, and/or total size of the object store identified
     * by {@code id}. If a new URL is supplied it is validated and a
     * {@code listBuckets()} connectivity check is performed; on failure the
     * old URL is restored before the exception propagates.
     *
     * @throws IllegalArgumentException if the store does not exist or the new
     *         URL cannot be reached.
     * @throws InvalidParameterValueException if the supplied URL is malformed.
     */
    ObjectStore updateObjectStore(Long id, UpdateObjectStoragePoolCmd cmd);

    /**
     * Aggregate used and total capacity across all configured object stores,
     * scoped to {@code zoneId}. Delegates to
     * {@link StorageCapacityService#getObjectStorageUsedStats(Long)}.
     */
    CapacityVO getObjectStorageUsedStats(Long zoneId);
}
