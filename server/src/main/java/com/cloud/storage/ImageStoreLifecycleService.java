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

import org.apache.cloudstack.api.command.admin.storage.CreateSecondaryStagingStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.DeleteImageStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.DeleteSecondaryStagingStoreCmd;
import org.apache.cloudstack.api.command.admin.storage.UpdateImageStoreCmd;

import com.cloud.exception.DiscoveryException;
import com.cloud.exception.InvalidParameterValueException;

/**
 * Secondary image store and cache (staging) store lifecycle operations —
 * add (discover), update name/capacity/read-only state, migrate NFS to
 * an object store, and delete. For region-wide image stores these calls
 * also fan out the bootstrap system-VM template seeding and per-zone
 * cache-record duplication that previously lived inline on the manager.
 *
 * <p>Extracted from {@link StorageManagerImpl} as part of the Phase 4
 * Spring-component decomposition. The public {@code StorageService}
 * entry points stay on the manager — they carry the {@code @ActionEvent}
 * annotations the framework expects — and now delegate the workflow to
 * this service.
 */
public interface ImageStoreLifecycleService {

    /**
     * Add a secondary image store. Resolves the provider (falling back
     * to the default image-store provider when {@code providerName} is
     * unknown), enforces single-provider co-existence, then drives the
     * provider's {@link
     * org.apache.cloudstack.engine.subsystem.api.storage.DataStoreLifeCycle}
     * to initialize the store and seeds bootstrap system-VM templates.
     *
     * <p>For region-scoped stores existing per-zone cache records for
     * templates / snapshots / volumes are duplicated into the new store
     * so they remain visible cluster-wide.
     *
     * @param name display name; falls back to {@code url} when null
     * @param url store URL
     * @param providerName secondary storage provider name; ignored and
     *     replaced with the default provider name when unresolvable
     * @param zoneId scope target — {@code null} = region scope
     * @param details provider-specific options (passed verbatim into
     *     the lifecycle's {@code initialize} parameter map)
     */
    ImageStore discoverImageStore(String name, String url, String providerName, Long zoneId, Map details)
            throws IllegalArgumentException, DiscoveryException, InvalidParameterValueException;

    /**
     * Convert every existing NFS secondary store into an
     * {@code ImageCache} (staging) store and then add a new object
     * store via {@link #discoverImageStore}. Fails fast if any existing
     * secondary store is not an NFS store, since mixed-mode migration
     * is not supported.
     */
    ImageStore migrateToObjectStore(String name, String url, String providerName, Map<String, String> details)
            throws DiscoveryException, InvalidParameterValueException;

    /**
     * Convenience wrapper that unpacks a {@link UpdateImageStoreCmd}
     * and delegates to {@link #updateImageStoreStatus(Long, String,
     * Boolean, Long)}.
     */
    ImageStore updateImageStore(UpdateImageStoreCmd cmd);

    /**
     * Update mutable fields on an image store. Each non-null argument
     * replaces the corresponding column; arguments that are
     * {@code null} are ignored. Throws
     * {@link IllegalArgumentException} when no store matches {@code id}.
     */
    ImageStore updateImageStoreStatus(Long id, String name, Boolean readonly, Long capacityBytes);

    /**
     * Short-form update that only flips the read-only flag. Equivalent
     * to {@link #updateImageStoreStatus(Long, String, Boolean, Long)}
     * with the other fields left as {@code null}.
     */
    ImageStore updateImageStoreStatus(Long id, Boolean readonly);

    /**
     * Delete an image store. Refuses to delete a store that still has
     * any active snapshot, volume, or user-template backups. On
     * success this also expunges {@code image_store_details} rows and
     * cascade rows in {@code template_store_ref},
     * {@code snapshot_store_ref}, and {@code volume_store_ref} for the
     * store inside a single DB transaction.
     */
    boolean deleteImageStore(DeleteImageStoreCmd cmd);

    /**
     * Add a secondary staging (cache) store. Only zone scope is
     * supported. The default cache-store provider is used when
     * {@code cmd.getProviderName()} is unknown.
     */
    ImageStore createSecondaryStagingStore(CreateSecondaryStagingStoreCmd cmd);

    /**
     * Delete a secondary staging (cache) store. Refuses to delete a
     * store that still has any active staging snapshots, volumes, or
     * templates referenced. On success this also expunges
     * {@code image_store_details} rows and cascade rows in
     * {@code template_store_ref}, {@code snapshot_store_ref}, and
     * {@code volume_store_ref} for the store inside a single DB
     * transaction.
     */
    boolean deleteSecondaryStagingStore(DeleteSecondaryStagingStoreCmd cmd);
}
