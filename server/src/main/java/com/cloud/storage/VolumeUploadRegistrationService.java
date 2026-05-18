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

import java.net.MalformedURLException;

import org.apache.cloudstack.api.command.user.volume.GetUploadParamsForVolumeCmd;
import org.apache.cloudstack.api.command.user.volume.UploadVolumeCmd;
import org.apache.cloudstack.api.response.GetUploadParamsResponse;

import com.cloud.exception.ResourceAllocationException;
import com.cloud.user.Account;

/**
 * Handles registration of inbound volume uploads — both the legacy URL-pull
 * path ({@link UploadVolumeCmd}) and the S3-style POST-upload path
 * ({@link GetUploadParamsForVolumeCmd}).  The god class
 * {@link VolumeApiServiceImpl} keeps thin delegating wrappers for the two
 * public interface entry points; all business logic lives here.
 *
 * <p>Extracted from {@link VolumeApiServiceImpl} as part of the Phase 4
 * Spring-component decomposition (slice 10).</p>
 */
public interface VolumeUploadRegistrationService {

    /**
     * Register a volume for upload via a remote URL.
     * Corresponds to the legacy {@link UploadVolumeCmd} API.
     *
     * @throws ResourceAllocationException if resource limits would be exceeded
     */
    VolumeVO uploadVolume(UploadVolumeCmd cmd) throws ResourceAllocationException;

    /**
     * Register a volume for S3-style POST upload and return a signed upload
     * URL together with metadata and a signature.
     * Corresponds to the {@link GetUploadParamsForVolumeCmd} API.
     *
     * @throws ResourceAllocationException if resource limits would be exceeded
     * @throws MalformedURLException       if the generated upload URL is invalid
     */
    GetUploadParamsResponse uploadVolume(GetUploadParamsForVolumeCmd cmd) throws ResourceAllocationException, MalformedURLException;

    /**
     * Return a random UUID string suitable for use as a volume name when the
     * caller did not specify one.  Delegated from
     * {@link VolumeApiServiceImpl#getRandomVolumeName()} so that
     * {@code getVolumeNameFromCommand} continues to work via the wrapper.
     */
    String getRandomVolumeName();

    /**
     * Persist a new {@link VolumeVO} record in the database for an inbound
     * upload operation.  Resolves a custom disk-offering when none is
     * supplied by the caller.
     *
     * @param owner          the account that will own the volume
     * @param zoneId         the zone where the volume will reside
     * @param volumeName     the desired volume name
     * @param url            source URL (may be {@code null} for POST uploads)
     * @param format         image format string (upper-cased before persist)
     * @param diskOfferingId explicit disk-offering (may be {@code null})
     * @param state          initial {@link Volume.State}
     * @return the persisted {@link VolumeVO}
     */
    VolumeVO persistVolume(Account owner, Long zoneId, String volumeName, String url,
            String format, Long diskOfferingId, Volume.State state);
}
