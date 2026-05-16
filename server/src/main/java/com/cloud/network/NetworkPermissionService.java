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

import org.apache.cloudstack.api.command.user.network.CreateNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.RemoveNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.ResetNetworkPermissionsCmd;

/**
 * Per-account network sharing — list, grant, revoke, and reset
 * {@code NetworkPermission} entries for non-VPC, account-scoped guest
 * networks. Backs the {@code listNetworkPermissions /
 * createNetworkPermissions / removeNetworkPermissions /
 * resetNetworkPermissions} APIs.
 *
 * <p>Extracted from {@link NetworkServiceImpl} as part of the Phase 4
 * Spring-component decomposition. {@code NetworkServiceImpl} keeps
 * delegating wrappers so the {@link NetworkService} interface contract
 * is preserved.
 */
public interface NetworkPermissionService {

    /**
     * List the currently-granted per-account permissions for a single
     * network. Caller must have {@code OperateEntry} access on the
     * network; an unknown network id throws
     * {@link com.cloud.exception.InvalidParameterValueException}.
     */
    List<? extends NetworkPermission> listNetworkPermissions(ListNetworkPermissionsCmd cmd);

    /**
     * Grant access to the given network for one or more accounts (named
     * directly, by id, or via project membership). The network owner is
     * skipped silently; pre-existing grants are idempotent.
     */
    boolean createNetworkPermissions(CreateNetworkPermissionsCmd cmd);

    /**
     * Revoke access to the given network for the supplied accounts.
     * Unknown account ids cause a validation error before any state
     * change.
     */
    boolean removeNetworkPermissions(RemoveNetworkPermissionsCmd cmd);

    /**
     * Wipe every per-account permission for the given network, leaving
     * only the owner with implicit access.
     */
    boolean resetNetworkPermissions(ResetNetworkPermissionsCmd cmd);
}
