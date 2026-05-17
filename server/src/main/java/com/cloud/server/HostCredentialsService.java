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
package com.cloud.server;

import org.apache.cloudstack.api.command.admin.host.UpdateHostPasswordCmd;
import org.apache.cloudstack.api.command.user.vm.GetVMPasswordCmd;

/**
 * Credential metadata management for hypervisor hosts/clusters and the
 * read-only retrieval of an instance's encrypted root password.
 *
 * <p>This slice owns the host-side persistence pieces only: it writes the
 * {@code USERNAME} and (encrypted) {@code PASSWORD} entries that the
 * management server keeps in {@code host_details} for hypervisor types whose
 * agent connection is established by username/password (KVM, XenServer). The
 * accompanying push of the credentials to the running agent lives in
 * {@code ResourceManagerImpl.updateHostPassword}, which the
 * {@code updateHostPassword} API command invokes alongside the management
 * server's call and is intentionally out of scope here.
 *
 * <p>Extracted from {@link ManagementServerImpl} as part of the Phase 4
 * Spring-component decomposition. The three public {@code ManagementService}
 * entry points covered here ({@code getVMPassword},
 * {@code updateClusterPassword}, {@code updateHostPassword}) remain on the
 * god class as one-line delegating wrappers so callers still binding the
 * {@code ManagementService} interface continue to resolve their method calls
 * there.
 */
public interface HostCredentialsService {

    /**
     * Return the encrypted root password stored against the user VM
     * identified by {@code cmd.getId()}. Verifies that the calling account
     * is permitted to access the VM and throws
     * {@link com.cloud.exception.InvalidParameterValueException} when the
     * VM does not exist or has no encrypted password stored (typically
     * because the SSH keypair has since been reset).
     */
    String getVMPassword(GetVMPasswordCmd cmd);

    /**
     * Update the {@code USERNAME} / {@code PASSWORD} host detail rows for
     * every host in the cluster identified by {@code cmd.getClusterId()}.
     * Returns {@code true} when the transactional write succeeded; throws
     * {@link com.cloud.exception.InvalidParameterValueException} when the
     * cluster id is missing, the cluster is unknown, or the cluster's
     * hypervisor type is not one of the hypervisor types this slice supports
     * (KVM, XenServer).
     */
    boolean updateClusterPassword(UpdateHostPasswordCmd cmd);

    /**
     * Update the {@code USERNAME} / {@code PASSWORD} host detail rows for the
     * single host identified by {@code cmd.getHostId()}. XenServer hosts are
     * rejected (single-host password updates are not supported for that
     * hypervisor — callers must supply the cluster id instead). Returns
     * {@code true} when the transactional write succeeded.
     */
    boolean updateHostPassword(UpdateHostPasswordCmd cmd);
}
