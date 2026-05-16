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

import org.apache.cloudstack.api.command.user.address.RemoveQuarantinedIpCmd;
import org.apache.cloudstack.api.command.user.address.UpdateQuarantinedIpCmd;

import com.cloud.utils.exception.CloudRuntimeException;

/**
 * Lifecycle operations for the public-IP quarantine — the cool-down
 * window applied to a public IP after its previous owner releases it,
 * during which the IP cannot be reallocated to a different account.
 * Backs the {@code updateQuarantinedIp} and {@code removeQuarantinedIp}
 * APIs and centralises the lookup-by-id-or-address and caller-access
 * checks used by both.
 *
 * <p>Extracted from {@link NetworkServiceImpl} as part of the Phase 4
 * Spring-component decomposition (parallel slice, 2nd on this file).
 * {@code NetworkServiceImpl} keeps delegating wrappers so the
 * {@link NetworkService} interface contract is preserved.
 */
public interface PublicIpQuarantineManager {

    /**
     * Extend or shorten an active quarantine's end date. The request is
     * rejected when the new end date is in the past, when no active
     * quarantine matches the given id/address, when the quarantine has
     * already expired, or when the caller cannot {@code checkAccess} the
     * previous owner's domain.
     */
    PublicIpQuarantine updatePublicIpAddressInQuarantine(UpdateQuarantinedIpCmd cmd) throws CloudRuntimeException;

    /**
     * Terminate an active quarantine early with an audit reason. The
     * request is rejected when no active quarantine matches the given
     * id/address, when the removal reason is blank, or when the caller
     * cannot {@code checkAccess} the previous owner's domain.
     */
    void removePublicIpAddressFromQuarantine(RemoveQuarantinedIpCmd cmd) throws CloudRuntimeException;

    /**
     * Resolve the active quarantine row by primary key (preferred) or
     * by public IP address (fallback). Throws when both arguments are
     * null or no row is found.
     */
    PublicIpQuarantine retrievePublicIpQuarantine(Long ipId, String ipAddress) throws CloudRuntimeException;
}
