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
package com.cloud.server;

import java.util.List;

import com.cloud.storage.StoragePool;
import com.cloud.utils.Pair;

/**
 * Volume storage-pool migration listing logic extracted from
 * {@link ManagementServerImpl}.
 */
public interface VolumeStoragePoolMigrationService {

    Pair<List<? extends StoragePool>, List<? extends StoragePool>> listStoragePoolsForMigrationOfVolume(Long volumeId, String keyword);

    Pair<List<? extends StoragePool>, List<? extends StoragePool>> listStoragePoolsForSystemMigrationOfVolume(Long volumeId,
            Long newDiskOfferingId, Long newSize, Long newMinIops, Long newMaxIops, boolean keepSourceStoragePool,
            boolean bypassStorageTypeCheck);

    Pair<List<? extends StoragePool>, List<? extends StoragePool>> listStoragePoolsForMigrationOfVolumeInternal(Long volumeId,
            Long newDiskOfferingId, Long newSize, Long newMinIops, Long newMaxIops, boolean keepSourceStoragePool,
            boolean bypassStorageTypeCheck, boolean bypassAccountCheck, String keyword);
}
