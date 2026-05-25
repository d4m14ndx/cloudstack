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
package com.cloud.network;

import java.util.List;

import org.apache.cloudstack.api.command.admin.network.DedicateGuestVlanRangeCmd;
import org.apache.cloudstack.api.command.admin.network.ListDedicatedGuestVlanRangesCmd;

import com.cloud.utils.Pair;

/**
 * Lifecycle and search operations for dedicated guest VLAN ranges — the
 * mapping of a contiguous block of guest VLAN ids on a physical network
 * to a single account or project. Backs the
 * {@code dedicateGuestVlanRange}, {@code listDedicatedGuestVlanRanges}
 * and {@code releaseDedicatedGuestVlanRange} APIs and owns the parsing,
 * overlap, datacenter-VLAN ownership and range-merge logic those APIs
 * share.
 *
 * <p>Extracted from {@link NetworkServiceImpl} as part of the Phase 4
 * Spring-component decomposition (parallel slice, 3rd on this file).
 * {@code NetworkServiceImpl} keeps delegating wrappers so the
 * {@link NetworkService} interface contract is preserved.
 */
public interface DedicatedGuestVlanRangeService {

    /**
     * Dedicate a contiguous guest VLAN range on a physical network to an
     * account (or project account). Validates the owner, that the
     * physical network uses VLAN/VXLAN isolation, that the range parses
     * as {@code start-end}, that the range exists within the physical
     * network's configured VLANs, that no VLAN in the range is allocated
     * to another account, and that the range does not overlap an
     * existing dedication. If the new range extends an existing
     * dedication owned by the same account, the existing row is grown
     * in place (and adjacent ranges merged) rather than creating a new
     * row.
     */
    GuestVlanRange dedicateGuestVlanRange(DedicateGuestVlanRangeCmd cmd);

    /**
     * Search the dedicated guest VLAN ranges by id, owner account or
     * project, exact range string, physical network and zone, with
     * standard pagination. Returns the page of matching rows plus the
     * total count.
     */
    Pair<List<? extends GuestVlanRange>, Integer> listDedicatedGuestVlanRanges(ListDedicatedGuestVlanRangesCmd cmd);

    /**
     * Release a previously dedicated guest VLAN range by primary key.
     * Clears the dedication marker on every VLAN row in the range and
     * removes the {@code account_guest_vlan_map} entry. Returns
     * {@code false} if the underlying delete fails; throws
     * {@code InvalidParameterValueException} when the id is unknown.
     */
    boolean releaseDedicatedGuestVlanRange(Long dedicatedGuestVlanRangeId);
}
