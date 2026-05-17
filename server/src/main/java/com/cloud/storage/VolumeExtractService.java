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

import java.util.Optional;

import org.apache.cloudstack.storage.datastore.db.VolumeDataStoreVO;

import com.cloud.user.Account;
import com.cloud.utils.db.SearchCriteria;

/**
 * Helpers backing {@link VolumeApiServiceImpl#extractVolume} — the API
 * path that produces a downloadable URL for a volume by copying it from
 * primary to secondary storage (when needed) and minting an extract
 * URL. The host class keeps the public {@code extractVolume} entry
 * point so the surrounding async-job / VM-serialization plumbing
 * continues to be exercised by the existing manager-level tests, while
 * this service owns the three coherent responsibilities that surround
 * the {@code volService.copyVolume} call:
 *
 * <ul>
 *   <li>pre-flight {@linkplain #validateExtractRequest validation} of
 *       the volume, the destination zone, the extract mode, and the
 *       caller's privileges — including extraction-disabled, encrypted
 *       volumes, attached-to-running-VM, PowerFlex pools, and
 *       non-extractable templates;</li>
 *   <li>finding an existing extract URL for the same volume snapshot
 *       on secondary storage and refreshing the
 *       {@link VolumeDataStoreVO} entry if needed
 *       ({@link #findOrRegenerateExistingExtractUrl});</li>
 *   <li>actually copying the volume from primary to secondary storage
 *       and minting / persisting the extract URL
 *       ({@link #orchestrateExtractVolume}).</li>
 * </ul>
 *
 * <p>Extracted from {@link VolumeApiServiceImpl} as part of the Phase 4
 * Spring-component decomposition (parallel slice, 5th).</p>
 */
public interface VolumeExtractService {

    /**
     * Validate that the caller is allowed to extract {@code volume} to
     * {@code zoneId} with the given {@code mode}. Throws
     * {@link com.cloud.exception.PermissionDeniedException} when the
     * caller is non-admin and the global {@code allow.user.extract.volume}
     * flag is disabled, when the volume's instance is non-stopped, or
     * when the underlying template is not flagged extractable. Throws
     * {@link com.cloud.exception.InvalidParameterValueException} when
     * the volume is null, the zone is unknown, the volume has no pool,
     * the pool is a PowerFlex pool, the volume is encrypted, or the
     * mode is missing / not one of {@code FTP_UPLOAD}/{@code HTTP_DOWNLOAD}.
     *
     * <p>Mutates the {@code volumeId} proxy details on the thrown
     * exception so the API layer can surface the right entity in the
     * error response.</p>
     */
    VolumeVO validateExtractRequest(Long volumeId, Long zoneId, String mode, Account caller);

    /**
     * Build a {@link SearchCriteria} for the volume's
     * {@link VolumeDataStoreVO} entry on secondary storage and either
     * return the cached extract URL or regenerate it from the
     * underlying secondary-store install path. Returns
     * {@link Optional#empty()} when no usable secondary-storage state
     * exists yet — in that case the caller must fall through to
     * {@link #orchestrateExtractVolume}. The {@code SearchCriteria}
     * created by the caller has its {@code state}, {@code volumeId},
     * {@code destroyed} and {@code updated} predicates added in-place.
     */
    Optional<String> findOrRegenerateExistingExtractUrl(SearchCriteria<VolumeDataStoreVO> sc, VolumeVO volume);

    /**
     * Copy the volume from primary to secondary storage (if it is not
     * already there) and produce a fresh, time-stamped extract URL
     * persisted on the {@link VolumeDataStoreVO}. Throws
     * {@link com.cloud.exception.InvalidParameterValueException} when
     * the volume has been removed under us or is not in {@code Ready},
     * and when no secondary storage with free capacity exists in
     * {@code zoneId}. Throws {@link com.cloud.utils.exception.CloudRuntimeException}
     * when the copy itself fails.
     */
    String orchestrateExtractVolume(long volumeId, long zoneId);
}
