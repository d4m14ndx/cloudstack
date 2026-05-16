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
package org.apache.cloudstack.storage.volume;

import java.util.function.BiFunction;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeService.VolumeApiResult;
import org.apache.cloudstack.framework.async.AsyncCallFuture;

import com.cloud.storage.Volume;
import com.cloud.user.Account;

/**
 * Operations on the secondary (image) store for volumes -- volume sync
 * reconciliation against the DB and moving volume files between account
 * folders when ownership changes.
 *
 * <p>Extracted from {@link VolumeServiceImpl} as a cohesive slice covering
 * the secondary-storage side of the volume data plane.</p>
 */
public interface SecondaryStorageVolumeService {

    /**
     * Reconcile the volume_store_ref DB rows for the given image store
     * against the actual install paths reported by the store, fixing up
     * download state, queueing re-downloads, and deleting orphaned files.
     *
     * @param store     image store to sync
     * @param downloader callback used to (re)trigger a volume download
     *                   on the image store; typically {@code
     *                   volumeService::createVolumeAsync} so that
     *                   existing spy verifications keep working.
     */
    void handleVolumeSync(DataStore store,
            BiFunction<VolumeInfo, DataStore, AsyncCallFuture<VolumeApiResult>> downloader);

    /**
     * Move a volume's install path on the image store from the source
     * account folder to the destination account folder, then update the
     * volume_store_ref install path on the DB.
     */
    void moveVolumeOnSecondaryStorageToAnotherAccount(Volume volume, Account sourceAccount, Account destAccount);

    /**
     * Build the canonical volume path on the image store for a given
     * (accountId, volumeId) pair.
     */
    String buildVolumePath(long accountId, long volumeId);
}
