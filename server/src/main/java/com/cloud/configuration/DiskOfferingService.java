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
package com.cloud.configuration;

import java.util.List;

import org.apache.cloudstack.api.command.admin.offering.CloneDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.CreateDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.DeleteDiskOfferingCmd;
import org.apache.cloudstack.api.command.admin.offering.UpdateDiskOfferingCmd;

import com.cloud.offering.DiskOffering;

/**
 * Disk-offering create/clone/read/update/delete operations for a
 * {@code DISK_OFFERING} from an admin or domain-admin command. Extracted from
 * {@link ConfigurationManagerImpl} as the second parallel slice of the Phase&nbsp;4
 * Spring-component decomposition.
 *
 * <p>The manager retains only API-contract wrappers for these methods. Service
 * offering creation owns its separate root-disk persistence path and is handled
 * by {@link ServiceOfferingServiceImpl}; do not route service-offering work
 * through this disk-offering service.
 */
public interface DiskOfferingService {

    /**
     * Create a new disk offering from a {@link CreateDiskOfferingCmd}. Validates
     * domain / zone references, disk-size mode (customised vs fixed),
     * cache-mode enum, storage-policy reference, and bytes/IOPS rate
     * parameters, then persists the offering with any domain, zone and
     * detail rows. Sets {@code disk-offering} on the {@link
     * org.apache.cloudstack.context.CallContext} event details.
     */
    DiskOffering createDiskOffering(CreateDiskOfferingCmd cmd);

    /**
     * Clone an existing disk offering, inheriting omitted parameters from the
     * source offering and persisting the result through the disk-offering write
     * path owned by this service.
     */
    DiskOffering cloneDiskOffering(CloneDiskOfferingCmd cmd);

    /**
     * Return domain ids attached to an existing disk offering.
     */
    List<Long> getDiskOfferingDomains(Long diskOfferingId);

    /**
     * Return zone ids attached to an existing disk offering.
     */
    List<Long> getDiskOfferingZones(Long diskOfferingId);

    /**
     * Update an existing disk offering. Re-validates domain / zone references
     * and bytes/IOPS rate parameters, applies any name / displayText / sortKey
     * / displayOffering / tag / cache-mode / state change, and persists
     * domain-id and zone-id detail rows when they differ from the current
     * binding. Domain-admin callers are restricted from changing the zone
     * binding and from broadening the offering past their child-domain tree.
     */
    DiskOffering updateDiskOffering(UpdateDiskOfferingCmd cmd);

    /**
     * Soft-delete a disk offering by setting its state to {@code Inactive}.
     * Verifies the caller is root admin (or domain-admin restricted to a
     * child-domain offering), and removes any annotations attached to the
     * offering.
     */
    boolean deleteDiskOffering(DeleteDiskOfferingCmd cmd);
}
