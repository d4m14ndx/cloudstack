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
package com.cloud.vm;

import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.uservm.UserVm;

/**
 * Hypervisor-specific extra-VM-config validation and persistence —
 * decoding the URL-encoded blob coming through the API, checking each
 * key against the configured allow-list for the target hypervisor,
 * and writing the resulting key/value pairs into the VM detail table.
 *
 * <p>Extracted from {@link UserVmManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 8). {@code UserVmManagerImpl}
 * keeps delegating wrappers so the {@link UserVmManager} interface
 * contract and existing test spies continue to work.
 */
public interface VmExtraConfigService {

    /**
     * Persist the supplied extra config blob for a VM, dispatching to
     * the right hypervisor-specific persister.
     *
     * @throws com.cloud.utils.exception.CloudRuntimeException for
     *         unsupported hypervisors or malformed config
     */
    void addExtraConfig(UserVm vm, String extraConfig);

    /**
     * URL-decode the raw extra-config string supplied via the API.
     */
    String decodeExtraConfig(String encodeString);

    /**
     * Top-level guard called from API command handlers — refuses when
     * the {@link UserVmManager#EnableAdditionalVmConfig} feature flag
     * is off for the account, and runs the KVM allow-list check for
     * KVM hypervisors.
     */
    void validateExtraConfig(long accountId, HypervisorType hypervisorType, String extraConfig);

    /**
     * Validate KVM extra-config XML against the account-scoped
     * KVM additional-config allow-list.
     */
    void validateKvmExtraConfig(String decodedUrl, long accountId);

    /**
     * Persist VMware extra-config key=value entries (one per line).
     */
    void persistExtraConfigVmware(String decodedUrl, UserVm vm);

    /**
     * Persist XenServer extra-config key=value entries (one per line),
     * numbered with the {@code extraconfig-N} key naming convention.
     */
    void persistExtraConfigXenServer(String decodedUrl, UserVm vm);

    /**
     * Persist KVM extra-config XML blocks (separated by blank lines),
     * keyed by the block tag when present.
     */
    void persistExtraConfigKvm(String decodedUrl, UserVm vm);

    /**
     * Quick regex check used by the XenServer/VMware persisters to
     * reject input that isn't a sequence of {@code key=value} pairs.
     */
    boolean isValidKeyValuePair(String decodedUrl);

    /**
     * Returns {@code true} when the key portion of a {@code key=value}
     * pair matches one of the entries in the supplied allow-list.
     */
    boolean isValidXenOrVmwareConfiguration(String cfg, String[] allowedKeyList);
}
