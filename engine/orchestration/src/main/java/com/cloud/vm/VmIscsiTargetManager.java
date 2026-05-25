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

import java.util.List;
import java.util.Map;

/**
 * VMware managed-iSCSI dynamic-target cleanup helpers — gather the
 * dynamic iSCSI targets a VM's managed volumes contribute to a VMware
 * host, then ask that host (and its cluster siblings) to drop them from
 * the iSCSI HBA's dynamic-target list.
 *
 * <p>Only VMware hosts produce non-empty target lists; every other
 * hypervisor short-circuits to an empty result, leaving the
 * {@link com.cloud.agent.api.ModifyTargetsCommand} unsent.
 *
 * <p>Extracted from {@link VirtualMachineManagerImpl} as part of the
 * Phase 4 Spring-component decomposition.
 */
public interface VmIscsiTargetManager {

    /**
     * Collect the host/port/IQN tuples for every managed primary storage
     * pool backing the VM's volumes. Returns an empty list when the
     * host is not VMware, the host record is missing, the VM has no
     * volumes, or none of its pools are flagged as managed.
     */
    List<Map<String, String>> getTargets(Long hostId, long vmId);

    /**
     * Send a {@link com.cloud.agent.api.ModifyTargetsCommand} that
     * removes the supplied {@code targets} from the dynamic-target list
     * on {@code hostId} and every other host in its cluster.
     */
    void removeDynamicTargets(long hostId, List<Map<String, String>> targets);
}
