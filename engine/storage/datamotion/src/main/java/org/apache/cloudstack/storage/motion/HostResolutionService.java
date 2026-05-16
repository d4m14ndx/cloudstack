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
package org.apache.cloudstack.storage.motion;

import java.util.List;

import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

import com.cloud.host.HostVO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;

/**
 * Read-only host-resolution helpers that pick a suitable {@link HostVO}
 * on which to execute storage-side operations (copy, resignature, snapshot
 * chain caching, etc.) for a given {@link SnapshotInfo}, {@link VolumeInfo}
 * or {@link StoragePoolVO}.
 *
 * <p>Extracted from {@link StorageSystemDataMotionStrategy} as part of
 * the Phase 4 Spring-component decomposition. The strategy continues to
 * expose the matching {@code getHost(...)} / {@code getHostInCluster(...)}
 * methods as one-line wrappers that delegate here, so existing call sites
 * and test spies keep working unchanged.
 *
 * <p>Nothing in here mutates state -- the implementations read from
 * {@code ResourceManager}, {@code HostDao}, {@code ClusterDao} and
 * {@code DataStoreManager} and return a single shuffled-and-filtered
 * {@link HostVO}, or {@code null} when no eligible host exists.
 */
public interface HostResolutionService {

    /**
     * Pick a host capable of servicing the given {@code snapshotInfo}'s
     * storage. Routing depends on hypervisor type:
     * <ul>
     *   <li>XenServer -- prefers a host in a cluster that supports
     *       resigning; falls back to any eligible enabled host. Throws
     *       {@link com.cloud.utils.exception.CloudRuntimeException} when
     *       neither lookup succeeds.</li>
     *   <li>VMware / KVM -- any eligible enabled host.</li>
     *   <li>Any other hypervisor -- throws
     *       {@link com.cloud.utils.exception.CloudRuntimeException}.</li>
     * </ul>
     */
    HostVO getHost(SnapshotInfo snapshotInfo);

    /**
     * Return a single enabled host in the storage pool's cluster that is
     * eligible for connecting to the pool. Hosts are shuffled before
     * selection. Throws
     * {@link com.cloud.utils.exception.CloudRuntimeException} when no
     * eligible host is found.
     */
    HostVO getHostInCluster(StoragePoolVO storagePool);

    /**
     * Pick an eligible host for the given {@code snapshotInfo} within the
     * snapshot's zone, scoped to {@code hypervisorType}. When the snapshot
     * lives on primary storage the resource manager's storage-connection
     * filter is used; otherwise all hosts in the zone matching the
     * hypervisor are considered. May return {@code null}.
     *
     * @param computeClusterMustSupportResign when {@code true}, only hosts
     *        in clusters with {@code supportsResigning} set are returned.
     */
    HostVO getHost(SnapshotInfo snapshotInfo, HypervisorType hypervisorType, boolean computeClusterMustSupportResign);

    /**
     * Pick an eligible host for the given {@code volumeInfo} within the
     * volume's zone, scoped to {@code hypervisorType}. When the volume
     * lives on primary storage the resource manager's storage-connection
     * filter is used; otherwise all hosts in the zone matching the
     * hypervisor are considered. May return {@code null}.
     *
     * @param computeClusterMustSupportResign when {@code true}, only hosts
     *        in clusters with {@code supportsResigning} set are returned.
     */
    HostVO getHost(VolumeInfo volumeInfo, HypervisorType hypervisorType, boolean computeClusterMustSupportResign);

    /**
     * Filter and shuffle the supplied {@code hosts} list, returning the
     * first enabled host (optionally requiring its cluster to support
     * resigning). Returns {@code null} when {@code hosts} is {@code null}
     * or no host matches.
     */
    HostVO getHost(List<HostVO> hosts, boolean computeClusterMustSupportResign);
}
