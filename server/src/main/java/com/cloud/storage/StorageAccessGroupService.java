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

/**
 * Pure helpers for the storage-access-group (SAG) feature — resolve
 * the effective SAG set for a host / cluster / pod / zone, and verify
 * that proposed new SAGs do not collide with what already exists at
 * the parent level.
 *
 * <p>Extracted from {@link StorageManagerImpl} as part of the Phase 4
 * Spring-component decomposition. The orchestration paths in
 * {@code StorageManagerImpl} ({@code configureStorageAccess},
 * {@code checkIfHostAndStoragePoolHasCommonStorageAccessGroups},
 * {@code checkIfReadyVolumeFitsInStoragePoolWithStorageAccessGroups})
 * stay on the manager and continue calling the manager's delegating
 * wrappers so existing test spies keep working.
 */
public interface StorageAccessGroupService {

    /**
     * Throw {@link com.cloud.utils.exception.CloudRuntimeException}
     * if any of the supplied storage-access groups is already defined
     * on the zone identified by {@code zoneId}.
     */
    void checkIfStorageAccessGroupsExistsOnZone(long zoneId, List<String> storageAccessGroups);

    /**
     * Throw {@link com.cloud.utils.exception.CloudRuntimeException}
     * if any of the supplied storage-access groups is already defined
     * on the pod or on its parent zone.
     */
    void checkIfStorageAccessGroupsExistsOnPod(long podId, List<String> storageAccessGroups);

    /**
     * Throw {@link com.cloud.utils.exception.CloudRuntimeException}
     * if any of the supplied storage-access groups is already defined
     * on the cluster, on its parent pod, or on its parent zone.
     */
    void checkIfStorageAccessGroupsExistsOnCluster(long clusterId, List<String> storageAccessGroups);

    /**
     * Return the merged storage-access groups effective at the most
     * specific non-null level supplied (host beats cluster beats pod
     * beats zone), de-duplicated and with blank entries dropped.
     */
    String[] getStorageAccessGroups(Long zoneId, Long podId, Long clusterId, Long hostId);
}
