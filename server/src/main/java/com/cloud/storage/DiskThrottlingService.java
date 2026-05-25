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

import com.cloud.agent.api.to.DataTO;
import com.cloud.agent.api.to.DiskTO;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.vm.DiskProfile;

/**
 * Pure helpers for resolving the bytes/iops disk throttling rates that
 * should apply to a given (service-offering, disk-offering) pair, and
 * for stamping those rates onto a {@link DiskProfile} or {@link DiskTO}
 * at VM/volume preparation time.
 *
 * <p>The resolution rules are unchanged from the legacy implementation
 * on {@code StorageManagerImpl}: disk-offering values win when set and
 * positive; otherwise fall back to the global
 * {@code vm.disk.throttling.*} configuration values, but only when the
 * caller is not a system VM (i.e. {@code offering == null} or
 * {@code !offering.isSystemUse()}). Returns {@code 0L} when nothing
 * applies.
 *
 * <p>Extracted from {@link StorageManagerImpl} as part of the Phase 4
 * Spring-component decomposition. The orchestration paths in
 * {@code StorageManagerImpl} ({@code setDiskProfileThrottling},
 * {@code getDiskWithThrottling}) stay on the manager and continue
 * calling the manager's delegating wrappers so existing test spies
 * keep working.
 */
public interface DiskThrottlingService {

    /**
     * Return the {@code bytesReadRate} that should be applied for the
     * given {@code offering}/{@code diskOffering} pair, or {@code 0L}
     * if no rate applies.
     */
    Long getDiskBytesReadRate(ServiceOffering offering, DiskOffering diskOffering);

    /**
     * Return the {@code bytesWriteRate} that should be applied for the
     * given {@code offering}/{@code diskOffering} pair, or {@code 0L}
     * if no rate applies.
     */
    Long getDiskBytesWriteRate(ServiceOffering offering, DiskOffering diskOffering);

    /**
     * Return the {@code iopsReadRate} that should be applied for the
     * given {@code offering}/{@code diskOffering} pair, or {@code 0L}
     * if no rate applies.
     */
    Long getDiskIopsReadRate(ServiceOffering offering, DiskOffering diskOffering);

    /**
     * Return the {@code iopsWriteRate} that should be applied for the
     * given {@code offering}/{@code diskOffering} pair, or {@code 0L}
     * if no rate applies.
     */
    Long getDiskIopsWriteRate(ServiceOffering offering, DiskOffering diskOffering);

    /**
     * Stamp all four throttling rates onto {@code dskCh}.
     */
    void setDiskProfileThrottling(DiskProfile dskCh, ServiceOffering offering, DiskOffering diskOffering);

    /**
     * Build a {@link DiskTO} for {@code volTO} and stamp the throttling
     * rates onto it. Non-root volumes do not inherit the
     * service-offering rates.
     */
    DiskTO getDiskWithThrottling(DataTO volTO, Volume.Type volumeType, long deviceId, String path,
                                 long offeringId, long diskOfferingId);
}
