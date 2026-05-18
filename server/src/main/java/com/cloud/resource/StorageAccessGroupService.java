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
package com.cloud.resource;

import java.util.List;
import java.util.Map;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;

import com.cloud.host.HostVO;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.VolumeVO;

/**
 * Storage Access Group operations and host-to-storage-pool connection plumbing
 * extracted from {@link ResourceManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 7).
 */
public interface StorageAccessGroupService {

    // -----------------------------------------------------------------------
    // LEAF methods — slice owns the body; RM keeps spy-compatible wrappers.
    // -----------------------------------------------------------------------

    List<Long> listOfHostIdsUsingTheStorageAccessGroups(List<String> storageAccessGroups,
            Long clusterId, Long podId, Long datacenterId);

    List<Long> listOfHostIdsUsingTheStoragePool(Long storagePoolId);

    List<VolumeVO> listOfVolumesUsingTheStorageAccessGroups(List<String> storageAccessGroups,
            Long hostId, Long clusterId, Long podId, Long datacenterId);

    List<HostVO> filterHostsBasedOnStorageAccessGroups(List<HostVO> allHosts,
            List<String> storageAccessGroups);

    void checkIfAnyVolumesInUse(List<String> sagsToAdd, List<String> sagsToDelete, HostVO host);

    void updateConnectionsBetweenHostsAndStoragePools(Map<HostVO, List<String>> hostsAndStorageAccessGroupsMap);

    List<StoragePoolVO> getStoragePoolsByAccessGroups(Long dcId, Long podId, Long clusterId,
            String[] storageAccessGroups, boolean includeEmptyTags);

    void connectHostToStoragePool(HostVO host, StoragePoolVO storagePool);

    void disconnectHostFromStoragePool(HostVO host, StoragePoolVO storagePool);

    // -----------------------------------------------------------------------
    // Public API entry points — orchestration bodies are duplicated on the
    // slice for direct callers; RM retains verbatim orchestration bodies for
    // spy compatibility (see plan §5).
    // -----------------------------------------------------------------------

    void updateStoragePoolConnectionsOnHosts(Long poolId, List<String> storageAccessGroups);

    void updateZoneStorageAccessGroups(long zoneId, List<String> newSags);

    void updatePodStorageAccessGroups(long podId, List<String> newSags);

    void updateClusterStorageAccessGroups(Long clusterId, List<String> newSags);

    void updateHostStorageAccessGroups(Long hostId, List<String> newSags);

    List<HostVO> getEligibleUpHostsInClusterForStorageConnection(PrimaryDataStoreInfo store);

    List<HostVO> getEligibleUpAndEnabledHostsInClusterForStorageConnection(PrimaryDataStoreInfo store);

    List<HostVO> getEligibleUpAndEnabledHostsInZoneForStorageConnection(DataStore store,
            long zoneId, HypervisorType hypervisorType);

}
