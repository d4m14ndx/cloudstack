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

import org.apache.cloudstack.api.command.admin.zone.CreateZoneCmd;
import org.apache.cloudstack.api.command.admin.zone.DeleteZoneCmd;
import org.apache.cloudstack.api.command.admin.zone.UpdateZoneCmd;

import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.exception.ConcurrentOperationException;

/**
 * Zone (DataCenter) CRUD — create, update, and delete the top-level
 * {@link DataCenter} entities. Extracted from {@link ConfigurationManagerImpl}
 * as the fifth parallel slice of the Phase&nbsp;4 Spring-component
 * decomposition.
 *
 * <p>{@code ConfigurationManagerImpl} retains thin delegating wrappers so
 * both the {@code ConfigurationService} (API-facing) and
 * {@code ConfigurationManager} (engine-facing) contracts keep working
 * unchanged. The deletability and zone-parameter helpers are duplicated
 * inside {@link ZoneServiceImpl} so the slice has no back-reference into
 * the manager — the same pattern used by {@link PodServiceImpl},
 * {@link DiskOfferingServiceImpl}, and {@link PortableIpRangeServiceImpl}.
 */
public interface ZoneService {

    /**
     * Create a new zone from a {@code CreateZoneCmd}. Validates the
     * network type (Basic or Advanced), enforces the Basic-zone
     * constraints (security-group always on, no guest CIDR), and
     * delegates to the long-arg form for the actual persistence.
     */
    DataCenter createZone(CreateZoneCmd cmd);

    /**
     * Create a new zone from raw parameters. Validates the guest CIDR
     * (subject to {@code AllowNonRFC1918CompliantIPs}), the network
     * domain, and the DNS / allocation-state inputs via
     * {@link #checkZoneParameters}, then persists the {@link DataCenterVO}
     * inside a transaction. When {@code domainId} is supplied, creates a
     * dedicated {@code AffinityGroup} and binds the zone to it. After the
     * zone is persisted the default system networks (Management,
     * Control, Public, Storage) are created.
     */
    DataCenterVO createZone(long userId, String zoneName, String dns1, String dns2,
            String internalDns1, String internalDns2, String guestCidr, String domain,
            Long domainId, NetworkType zoneType, String allocationState, String networkDomain,
            boolean isSecurityGroupEnabled, boolean isLocalStorageEnabled,
            String ip6Dns1, String ip6Dns2, boolean isEdge, List<String> storageAccessGroups);

    /**
     * Update a zone from an {@code UpdateZoneCmd}. Re-validates parameters
     * (allowing partial input — unspecified fields keep their existing
     * values), validates any new details map and DNS-search list, and
     * applies the update inside a transaction. When the allocation state
     * is being flipped to Enabled, ensures that Management and Public
     * traffic types exist on the zone's physical network and falls back
     * to copying the Management traffic config for Storage when missing.
     * When the zone is being made public the dedicated affinity group
     * binding is released.
     */
    DataCenter editZone(UpdateZoneCmd cmd);

    /**
     * Delete a zone from a {@code DeleteZoneCmd}. Verifies the zone is
     * empty via {@code checkIfZoneIsDeletable} (no hosts, pods, VMs,
     * volumes, allocated IPs, physical networks, or secondary storage),
     * removes any VLANs in the zone, removes any NSX / Netris external
     * network providers, deletes capacity records, releases the
     * dedicated affinity group, and removes annotations.
     */
    boolean deleteZone(DeleteZoneCmd cmd);

    /**
     * Create the default system networks (Management, Control, Public,
     * Storage as applicable) for the given zone. Called from
     * {@link #createZone(long, String, String, String, String, String, String, String, Long, NetworkType, String, String, boolean, boolean, String, String, boolean, List)}
     * but also exposed for callers that build a zone via lower-level
     * paths (deployment data centers, configuration drives, tests).
     */
    void createDefaultSystemNetworks(long zoneId) throws ConcurrentOperationException;
}
