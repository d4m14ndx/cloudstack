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

/**
 * Callback interface that schedules an IP-fetch retry for a NIC that
 * belongs to a VM on a shared network without services or an L2 network.
 *
 * <p>The implementation (provided by {@link UserVmManagerImpl} as a lambda)
 * writes an entry into the god-class {@code vmIdCountMap} that the
 * background {@code VmIpAddrFetchTask} consumes.  Injected into
 * {@link VmRebootServiceImpl} so the extracted service does not need to
 * access the map directly.
 */
@FunctionalInterface
public interface VmIpFetchScheduler {

    /**
     * Enqueue a fetch-retry entry for the given NIC.
     *
     * @param nicId  the NIC whose IP address should be re-fetched
     * @param vmId   the VM instance the NIC belongs to
     */
    void scheduleIpFetch(long nicId, long vmId);
}
