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

import org.apache.cloudstack.api.command.admin.region.CreatePortableIpRangeCmd;
import org.apache.cloudstack.api.command.admin.region.DeletePortableIpRangeCmd;
import org.apache.cloudstack.api.command.admin.region.ListPortableIpRangesCmd;
import org.apache.cloudstack.region.PortableIp;
import org.apache.cloudstack.region.PortableIpRange;

import com.cloud.exception.ConcurrentOperationException;

/**
 * Portable IP range CRUD — create, delete, list portable IP ranges and the
 * portable IPs they contain. Extracted from {@link ConfigurationManagerImpl}
 * as the third parallel slice of the Phase&nbsp;4 Spring-component
 * decomposition.
 *
 * <p>{@code ConfigurationManagerImpl} retains thin delegating wrappers so the
 * {@code ConfigurationService} interface contract keeps working unchanged.
 * The overlap helper {@code checkOverlapPortableIpRange} is duplicated inside
 * {@link PortableIpRangeServiceImpl} because the manager still calls its own
 * copy from the VLAN public-IP-range path — the same pattern used by
 * {@link PodServiceImpl} and {@link DiskOfferingServiceImpl}.
 */
public interface PortableIpRangeService {

    /**
     * Create a portable IP range under the specified region. Validates the
     * region exists, the start / end IP form a valid IPv4 range, the start
     * and end IPs sit within the gateway / netmask subnet, the range does
     * not overlap any existing portable IP range in the region, and any
     * supplied VLAN tag is not already in use by a zone VLAN or overlapping
     * a public IP range. Persists the range and its individual portable IPs
     * inside a transaction guarded by a {@code "PortablePublicIpRange"}
     * global lock, and flips the region's {@code portableipEnabled} flag on.
     */
    PortableIpRange createPortableIpRange(CreatePortableIpRangeCmd cmd) throws ConcurrentOperationException;

    /**
     * Delete a portable IP range. Only succeeds when every IP in the range
     * is in {@code Free} state; if this is the last range in the region the
     * region's {@code portableipEnabled} flag is flipped back off.
     */
    boolean deletePortableIpRange(DeletePortableIpRangeCmd cmd);

    /**
     * List portable IP ranges, optionally scoped to a region or a single
     * range ID.
     */
    List<? extends PortableIpRange> listPortableIpRanges(ListPortableIpRangesCmd cmd);

    /**
     * List the individual portable IPs that belong to a single portable IP
     * range.
     */
    List<? extends PortableIp> listPortableIps(long id);
}
