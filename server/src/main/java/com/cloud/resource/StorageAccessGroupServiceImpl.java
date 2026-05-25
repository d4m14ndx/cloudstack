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

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.ArrayUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.ClusterVO;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.HostPodVO;
import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.exception.StorageConflictException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.storage.ScopeType;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePoolAndAccessGroupMapVO;
import com.cloud.storage.StoragePoolHostVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.StoragePoolAndAccessGroupMapDao;
import com.cloud.storage.dao.StoragePoolHostDao;
import com.cloud.storage.dao.StoragePoolTagsDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.db.GenericSearchBuilder;
import com.cloud.utils.db.JoinBuilder;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Func;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.dao.VMInstanceDao;

/**
 * Storage Access Group operations and host-to-storage-pool connection plumbing
 * extracted from {@link ResourceManagerImpl} as part of the Phase 4
 * Spring-component decomposition (slice 7).
 */
@Component
public class StorageAccessGroupServiceImpl implements StorageAccessGroupService {

    protected static final Logger LOG = LogManager.getLogger(StorageAccessGroupServiceImpl.class);

    @Inject
    protected HostLookupService hostLookupService;
    @Inject
    protected PrimaryDataStoreDao storagePoolDao;
    @Inject
    protected StoragePoolAndAccessGroupMapDao storagePoolAccessGroupMapDao;
    @Inject
    protected StoragePoolHostDao storagePoolHostDao;
    @Inject
    protected StoragePoolTagsDao storagePoolTagsDao;
    @Inject
    protected StorageManager storageManager;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected VMInstanceDao vmDao;
    @Inject
    protected VolumeDao volumeDao;
    @Inject
    protected ClusterDao clusterDao;
    @Inject
    protected HostPodDao podDao;
    @Inject
    protected DataCenterDao dataCenterDao;

    // -----------------------------------------------------------------------
    // Private helpers (moved verbatim from RM lines 1518-1611 with rename map)
    // -----------------------------------------------------------------------

    private void removeStorageAccessGroupsOnPodsInZone(long zoneId, List<String> newStoragePoolTags, List<String> tagsToDeleteOnZone) {
        List<HostPodVO> pods = podDao.listByDataCenterId(zoneId);
        for (HostPodVO pod : pods) {
            removeStorageAccessGroupsOnClustersInPod(pod.getId(), newStoragePoolTags, tagsToDeleteOnZone);
            updateStorageAccessGroupsToBeAddedOnPodInZone(pod.getId(), newStoragePoolTags);
        }
    }

    private void removeStorageAccessGroupsOnClustersInPod(long podId, List<String> newStoragePoolTags, List<String> tagsToDeleteOnPod) {
        List<ClusterVO> clusters = clusterDao.listByPodId(podId);
        for (ClusterVO cluster : clusters) {
            updateStorageAccessGroupsToBeDeletedOnHostsInCluster(cluster.getId(), tagsToDeleteOnPod);
            updateStorageAccessGroupsToBeAddedOnHostsInCluster(cluster.getId(), newStoragePoolTags);
            updateStorageAccessGroupsToBeAddedOnClustersInPod(cluster.getId(), newStoragePoolTags);
        }
    }

    private void updateStorageAccessGroupsToBeDeletedOnHostsInCluster(long clusterId, List<String> storageAccessGroupsToDeleteOnCluster) {
        if (CollectionUtils.isEmpty(storageAccessGroupsToDeleteOnCluster)) {
            return;
        }

        List<HostVO> hosts = hostDao.findByClusterId(clusterId);
        List<Long> hostIdsUsingStorageAccessGroups = listOfHostIdsUsingTheStorageAccessGroups(storageAccessGroupsToDeleteOnCluster, clusterId, null, null);
        for (HostVO host : hosts) {
            String hostStorageAccessGroups = host.getStorageAccessGroups();
            if (hostIdsUsingStorageAccessGroups != null && hostIdsUsingStorageAccessGroups.contains(host.getId())) {
                Set<String> mergedSet = hostStorageAccessGroups != null
                        ? new HashSet<>(Arrays.asList(hostStorageAccessGroups.split(",")))
                        : new HashSet<>();
                mergedSet.addAll(storageAccessGroupsToDeleteOnCluster);
                host.setStorageAccessGroups(String.join(",", mergedSet));
                hostDao.update(host.getId(), host);
            } else {
                if (hostStorageAccessGroups != null) {
                    List<String> hostTagsList = new ArrayList<>(Arrays.asList(hostStorageAccessGroups.split(",")));
                    hostTagsList.removeAll(storageAccessGroupsToDeleteOnCluster);
                    String updatedClusterStoragePoolTags = hostTagsList.isEmpty() ? null : String.join(",", hostTagsList);
                    host.setStorageAccessGroups(updatedClusterStoragePoolTags);
                    hostDao.update(host.getId(), host);
                }
            }
        }
    }

    private void updateStorageAccessGroupsToBeAddedOnHostsInCluster(long clusterId, List<String> tagsAddedOnCluster) {
        if (CollectionUtils.isEmpty(tagsAddedOnCluster)) {
            return;
        }

        List<HostVO> hosts = hostDao.findByClusterId(clusterId);
        for (HostVO host : hosts) {
            String hostStoragePoolTags = host.getStorageAccessGroups();
            Set<String> hostStoragePoolTagsSet = hostStoragePoolTags != null
                    ? new HashSet<>(Arrays.asList(hostStoragePoolTags.split(",")))
                    : new HashSet<>();

            hostStoragePoolTagsSet.removeIf(tagsAddedOnCluster::contains);
            host.setStorageAccessGroups(hostStoragePoolTagsSet.isEmpty() ? null : String.join(",", hostStoragePoolTagsSet));
            hostDao.update(host.getId(), host);
        }
    }

    private void updateStorageAccessGroupsToBeAddedOnClustersInPod(long clusterId, List<String> tagsAddedOnPod) {
        if (CollectionUtils.isEmpty(tagsAddedOnPod)) {
            return;
        }

        ClusterVO cluster = clusterDao.findById(clusterId);
        String clusterStoragePoolTags = cluster.getStorageAccessGroups();
        if (clusterStoragePoolTags != null) {
            List<String> clusterTagsList = new ArrayList<>(Arrays.asList(clusterStoragePoolTags.split(",")));
            clusterTagsList.removeAll(tagsAddedOnPod);
            String updatedClusterStoragePoolTags = clusterTagsList.isEmpty() ? null : String.join(",", clusterTagsList);
            cluster.setStorageAccessGroups(updatedClusterStoragePoolTags);
            clusterDao.update(cluster.getId(), cluster);
        }
    }

    private void updateStorageAccessGroupsToBeAddedOnPodInZone(long podId, List<String> tagsAddedOnZone) {
        if (CollectionUtils.isEmpty(tagsAddedOnZone)) {
            return;
        }

        HostPodVO pod = podDao.findById(podId);
        String podStoragePoolTags = pod.getStorageAccessGroups();
        if (podStoragePoolTags != null) {
            List<String> podTagsList = new ArrayList<>(Arrays.asList(podStoragePoolTags.split(",")));
            podTagsList.removeAll(tagsAddedOnZone);
            String updatedClusterStoragePoolTags = podTagsList.isEmpty() ? null : String.join(",", podTagsList);
            pod.setStorageAccessGroups(updatedClusterStoragePoolTags);
            podDao.update(pod.getId(), pod);
        }
    }

    // -----------------------------------------------------------------------
    // LEAF methods — body lives here; RM delegates to this impl.
    // -----------------------------------------------------------------------

    @Override
    public List<Long> listOfHostIdsUsingTheStorageAccessGroups(List<String> storageAccessGroups, Long clusterId, Long podId, Long datacenterId) {
        GenericSearchBuilder<VMInstanceVO, Long> vmInstanceSearch = vmDao.createSearchBuilder(Long.class);
        vmInstanceSearch.select(null, Func.DISTINCT, vmInstanceSearch.entity().getHostId());
        vmInstanceSearch.and("hostId", vmInstanceSearch.entity().getHostId(), Op.NNULL);
        vmInstanceSearch.and("removed", vmInstanceSearch.entity().getRemoved(), Op.NULL);

        GenericSearchBuilder<VolumeVO, Long> volumeSearch = volumeDao.createSearchBuilder(Long.class);
        volumeSearch.selectFields(volumeSearch.entity().getInstanceId());
        volumeSearch.and("state", volumeSearch.entity().getState(), Op.NIN);

        GenericSearchBuilder<StoragePoolVO, Long> storagePoolSearch = storagePoolDao.createSearchBuilder(Long.class);
        storagePoolSearch.and("clusterId", storagePoolSearch.entity().getClusterId(), Op.EQ);
        storagePoolSearch.and("podId", storagePoolSearch.entity().getPodId(), Op.EQ);
        storagePoolSearch.and("datacenterId", storagePoolSearch.entity().getDataCenterId(), Op.EQ);
        storagePoolSearch.selectFields(storagePoolSearch.entity().getId());

        GenericSearchBuilder<StoragePoolAndAccessGroupMapVO, Long> storageAccessGroupSearch = storagePoolAccessGroupMapDao.createSearchBuilder(Long.class);
        storageAccessGroupSearch.and("sag", storageAccessGroupSearch.entity().getStorageAccessGroup(), Op.IN);

        storagePoolSearch.join("storageAccessGroupSearch", storageAccessGroupSearch, storagePoolSearch.entity().getId(), storageAccessGroupSearch.entity().getPoolId(), JoinBuilder.JoinType.INNER);
        storageAccessGroupSearch.done();

        volumeSearch.join("storagePoolSearch", storagePoolSearch, volumeSearch.entity().getPoolId(), storagePoolSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        storagePoolSearch.done();

        vmInstanceSearch.join("volumeSearch", volumeSearch, vmInstanceSearch.entity().getId(), volumeSearch.entity().getInstanceId(), JoinBuilder.JoinType.INNER);
        volumeSearch.done();

        vmInstanceSearch.done();

        SearchCriteria<Long> sc = vmInstanceSearch.create();
        sc.setJoinParameters("storageAccessGroupSearch", "sag", storageAccessGroups.toArray());
        sc.setJoinParameters("volumeSearch", "state", new String[]{"Destroy", "Error", "Expunging", "Expunged"});
        if (clusterId != null) {
            sc.setParameters("storagePoolSearch", "clusterId", clusterId);
        }
        if (podId != null) {
            sc.setParameters("storagePoolSearch", "podId", podId);
        }
        if (datacenterId != null) {
            sc.setParameters("storagePoolSearch", "datacenterId", datacenterId);
        }

        return vmDao.customSearch(sc, null);
    }

    @Override
    public List<Long> listOfHostIdsUsingTheStoragePool(Long storagePoolId) {
        GenericSearchBuilder<VMInstanceVO, Long> vmInstanceSearch = vmDao.createSearchBuilder(Long.class);
        vmInstanceSearch.select(null, Func.DISTINCT, vmInstanceSearch.entity().getHostId());
        vmInstanceSearch.and("hostId", vmInstanceSearch.entity().getHostId(), Op.NNULL);
        vmInstanceSearch.and("removed", vmInstanceSearch.entity().getRemoved(), Op.NULL);

        GenericSearchBuilder<VolumeVO, Long> volumeSearch = volumeDao.createSearchBuilder(Long.class);
        volumeSearch.selectFields(volumeSearch.entity().getInstanceId());
        volumeSearch.and("state", volumeSearch.entity().getState(), Op.NIN);

        GenericSearchBuilder<StoragePoolVO, Long> storagePoolSearch = storagePoolDao.createSearchBuilder(Long.class);
        storagePoolSearch.selectFields(storagePoolSearch.entity().getId());
        storagePoolSearch.and("poolId", storagePoolSearch.entity().getId(), Op.EQ);

        volumeSearch.join("storagePoolSearch", storagePoolSearch, volumeSearch.entity().getPoolId(), storagePoolSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        storagePoolSearch.done();

        vmInstanceSearch.join("volumeSearch", volumeSearch, vmInstanceSearch.entity().getId(), volumeSearch.entity().getInstanceId(), JoinBuilder.JoinType.INNER);
        volumeSearch.done();

        vmInstanceSearch.done();

        SearchCriteria<Long> sc = vmInstanceSearch.create();
        sc.setJoinParameters("storagePoolSearch", "poolId", storagePoolId);
        sc.setJoinParameters("volumeSearch", "state", new String[]{"Destroy", "Error", "Expunging", "Expunged"});

        return vmDao.customSearch(sc, null);
    }

    @Override
    public List<VolumeVO> listOfVolumesUsingTheStorageAccessGroups(List<String> storageAccessGroups, Long hostId, Long clusterId, Long podId, Long datacenterId) {
        SearchBuilder<VolumeVO> volumeSearch = volumeDao.createSearchBuilder();
        volumeSearch.and("state", volumeSearch.entity().getState(), Op.NIN);

        GenericSearchBuilder<VMInstanceVO, Long> vmInstanceSearch = vmDao.createSearchBuilder(Long.class);
        vmInstanceSearch.selectFields(vmInstanceSearch.entity().getId());
        vmInstanceSearch.and("hostId", vmInstanceSearch.entity().getHostId(), Op.EQ);
        vmInstanceSearch.and("removed", vmInstanceSearch.entity().getRemoved(), Op.NULL);

        GenericSearchBuilder<StoragePoolVO, Long> storagePoolSearch = storagePoolDao.createSearchBuilder(Long.class);
        storagePoolSearch.and("clusterId", storagePoolSearch.entity().getClusterId(), Op.EQ);
        storagePoolSearch.and("podId", storagePoolSearch.entity().getPodId(), Op.EQ);
        storagePoolSearch.and("datacenterId", storagePoolSearch.entity().getDataCenterId(), Op.EQ);
        storagePoolSearch.selectFields(storagePoolSearch.entity().getId());

        GenericSearchBuilder<StoragePoolAndAccessGroupMapVO, Long> storageAccessGroupSearch = storagePoolAccessGroupMapDao.createSearchBuilder(Long.class);
        storageAccessGroupSearch.and("sag", storageAccessGroupSearch.entity().getStorageAccessGroup(), Op.IN);

        storagePoolSearch.join("storageAccessGroupSearch", storageAccessGroupSearch, storagePoolSearch.entity().getId(), storageAccessGroupSearch.entity().getPoolId(), JoinBuilder.JoinType.INNER);

        volumeSearch.join("storagePoolSearch", storagePoolSearch, volumeSearch.entity().getPoolId(), storagePoolSearch.entity().getId(), JoinBuilder.JoinType.INNER);

        volumeSearch.join("vmInstanceSearch", vmInstanceSearch, volumeSearch.entity().getInstanceId(), vmInstanceSearch.entity().getId(), JoinBuilder.JoinType.INNER);

        storageAccessGroupSearch.done();
        storagePoolSearch.done();
        vmInstanceSearch.done();
        volumeSearch.done();

        SearchCriteria<VolumeVO> sc = volumeSearch.create();
        sc.setParameters("state", new String[]{"Destroy", "Error", "Expunging", "Expunged"});
        sc.setJoinParameters("storageAccessGroupSearch", "sag", storageAccessGroups.toArray());
        if (hostId != null) {
            sc.setJoinParameters("vmInstanceSearch", "hostId", hostId);
        }
        if (clusterId != null) {
            sc.setJoinParameters("storagePoolSearch", "clusterId", clusterId);
        }
        if (podId != null) {
            sc.setJoinParameters("storagePoolSearch", "podId", podId);
        }
        if (datacenterId != null) {
            sc.setJoinParameters("storagePoolSearch", "datacenterId", datacenterId);
        }

        return volumeDao.customSearch(sc, null);
    }

    private List<Long> listOfStoragePoolIDsUsedByHost(long hostId) {
        GenericSearchBuilder<VMInstanceVO, Long> vmInstanceSearch = vmDao.createSearchBuilder(Long.class);
        vmInstanceSearch.selectFields(vmInstanceSearch.entity().getId());
        vmInstanceSearch.and("hostId", vmInstanceSearch.entity().getHostId(), Op.EQ);

        GenericSearchBuilder<VolumeVO, Long> volumeSearch = volumeDao.createSearchBuilder(Long.class);
        volumeSearch.selectFields(volumeSearch.entity().getPoolId());
        volumeSearch.and("state", volumeSearch.entity().getState(), Op.EQ);

        volumeSearch.join("vmInstanceSearch", vmInstanceSearch, volumeSearch.entity().getInstanceId(), vmInstanceSearch.entity().getId(), JoinBuilder.JoinType.INNER);
        vmInstanceSearch.done();

        GenericSearchBuilder<StoragePoolVO, Long> storagePoolSearch = storagePoolDao.createSearchBuilder(Long.class);
        storagePoolSearch.select(null, Func.DISTINCT, storagePoolSearch.entity().getId());

        storagePoolSearch.join("volumeSearch", volumeSearch, storagePoolSearch.entity().getId(), volumeSearch.entity().getPoolId(), JoinBuilder.JoinType.INNER);
        volumeSearch.done();

        storagePoolSearch.done();

        SearchCriteria<Long> sc = storagePoolSearch.create();
        sc.setJoinParameters("vmInstanceSearch", "hostId", hostId);
        sc.setJoinParameters("volumeSearch", "state", "Ready");

        List<Long> storagePoolsInUse = storagePoolDao.customSearch(sc, null);
        return storagePoolsInUse;
    }

    @Override
    public List<HostVO> filterHostsBasedOnStorageAccessGroups(List<HostVO> allHosts, List<String> storageAccessGroups) {
        List<HostVO> hostsToConnect = new ArrayList<>();
        for (HostVO host : allHosts) {
            String[] storageAccessGroupsOnHost = storageManager.getStorageAccessGroups(null, null, null, host.getId());
            List<String> listOfStorageAccessGroupsOnHost = Arrays.asList(storageAccessGroupsOnHost);
            if (CollectionUtils.isNotEmpty(storageAccessGroups)) {
                List<String> intersection = new ArrayList<>(listOfStorageAccessGroupsOnHost);
                intersection.retainAll(storageAccessGroups);
                if (CollectionUtils.isNotEmpty(intersection)) {
                    hostsToConnect.add(host);
                }
            } else {
                hostsToConnect.add(host);
            }
        }
        return hostsToConnect;
    }

    @Override
    public void checkIfAnyVolumesInUse(List<String> sagsToAdd, List<String> sagsToDelete, HostVO host) {
        if (CollectionUtils.isNotEmpty(sagsToDelete)) {
            List<VolumeVO> volumesUsingTheStoragePoolAccessGroups = listOfVolumesUsingTheStorageAccessGroups(sagsToDelete, host.getId(), null, null, null);
            if (CollectionUtils.isNotEmpty(volumesUsingTheStoragePoolAccessGroups)) {
                List<StoragePoolVO> poolsToAdd;
                if (CollectionUtils.isNotEmpty(sagsToAdd)) {
                    poolsToAdd = getStoragePoolsByAccessGroups(host.getDataCenterId(), host.getPodId(), host.getClusterId(), sagsToAdd.toArray(new String[0]), true);
                } else {
                    poolsToAdd = getStoragePoolsByEmptyStorageAccessGroups(host.getDataCenterId(), host.getPodId(), host.getClusterId());
                }
                if (CollectionUtils.isNotEmpty(poolsToAdd)) {
                    Set<Long> poolIdsToAdd = poolsToAdd.stream()
                            .map(StoragePoolVO::getId)
                            .collect(Collectors.toSet());
                    volumesUsingTheStoragePoolAccessGroups.removeIf(volume -> poolIdsToAdd.contains(volume.getPoolId()));
                }
                if (CollectionUtils.isNotEmpty(volumesUsingTheStoragePoolAccessGroups)) {
                    LOG.error(String.format("There are volumes in storage pools with the Storage Access Groups that need to be deleted or " +
                            "in the storage pools which are already connected to the host. Those volume IDs are %s", volumesUsingTheStoragePoolAccessGroups));
                    throw new CloudRuntimeException("There are volumes in storage pools with the Storage Access Groups that need to be deleted or " +
                            "in the storage pools which are already connected to the host");
                }
            }
        }
    }

    @Override
    public void updateConnectionsBetweenHostsAndStoragePools(Map<HostVO, List<String>> hostsAndStorageAccessGroupsMap) {
        List<HostVO> hostsList = new ArrayList<>(hostsAndStorageAccessGroupsMap.keySet());
        Map<HostVO, List<StoragePoolVO>> hostStoragePoolsMapBefore = getHostStoragePoolsBefore(hostsList);

        Map<HostVO, List<StoragePoolVO>> hostPoolsToAddMapAfter = getHostPoolsToAddAfter(hostsAndStorageAccessGroupsMap);

        disconnectPoolsNotInAccessGroups(hostStoragePoolsMapBefore, hostPoolsToAddMapAfter);
    }

    private Map<HostVO, List<StoragePoolVO>> getHostStoragePoolsBefore(List<HostVO> hostsList) {
        Map<HostVO, List<StoragePoolVO>> hostStoragePoolsMapBefore = new HashMap<>();
        for (HostVO host : hostsList) {
            List<StoragePoolHostVO> storagePoolsConnectedToHost = storageManager.findStoragePoolsConnectedToHost(host.getId());
            List<StoragePoolVO> storagePoolsConnectedBefore = new ArrayList<>();
            if (CollectionUtils.isNotEmpty(storagePoolsConnectedToHost)) {
                for (StoragePoolHostVO poolHost : storagePoolsConnectedToHost) {
                    StoragePoolVO pool = storagePoolDao.findById(poolHost.getPoolId());
                    if (pool != null) {
                        storagePoolsConnectedBefore.add(pool);
                    }
                }
            }
            hostStoragePoolsMapBefore.put(host, storagePoolsConnectedBefore);
        }
        return hostStoragePoolsMapBefore;
    }

    private Map<HostVO, List<StoragePoolVO>> getHostPoolsToAddAfter(Map<HostVO, List<String>> hostsAndStorageAccessGroupsMap) {
        Map<HostVO, List<StoragePoolVO>> hostPoolsToAddMapAfter = new HashMap<>();
        for (Map.Entry<HostVO, List<String>> entry : hostsAndStorageAccessGroupsMap.entrySet()) {
            HostVO host = entry.getKey();
            List<String> sagsToAdd = entry.getValue();
            List<StoragePoolVO> poolsToAdd;
            if (CollectionUtils.isNotEmpty(sagsToAdd)) {
                poolsToAdd = getStoragePoolsByAccessGroups(host.getDataCenterId(), host.getPodId(), host.getClusterId(), sagsToAdd.toArray(new String[0]), true);
            } else {
                poolsToAdd = getStoragePoolsByEmptyStorageAccessGroups(host.getDataCenterId(), host.getPodId(), host.getClusterId());
            }
            hostPoolsToAddMapAfter.put(host, poolsToAdd);
            connectHostToStoragePools(host, poolsToAdd);
        }
        return hostPoolsToAddMapAfter;
    }

    private void disconnectPoolsNotInAccessGroups(Map<HostVO, List<StoragePoolVO>> hostStoragePoolsMapBefore, Map<HostVO, List<StoragePoolVO>> hostPoolsToAddMapAfter) {
        for (Map.Entry<HostVO, List<StoragePoolVO>> entry : hostStoragePoolsMapBefore.entrySet()) {
            HostVO host = entry.getKey();
            List<StoragePoolVO> storagePoolsConnectedBefore = entry.getValue();
            List<StoragePoolVO> poolsToAdd = hostPoolsToAddMapAfter.get(host);
            List<StoragePoolVO> poolsToDelete = new ArrayList<>();

            for (StoragePoolVO pool : storagePoolsConnectedBefore) {
                if (poolsToAdd == null || !poolsToAdd.contains(pool)) {
                    poolsToDelete.add(pool);
                }
            }

            if (CollectionUtils.isNotEmpty(poolsToDelete)) {
                disconnectHostFromStoragePools(host, poolsToDelete);
            }
        }
    }

    @Override
    public List<StoragePoolVO> getStoragePoolsByAccessGroups(Long dcId, Long podId, Long clusterId, String[] storageAccessGroups, boolean includeEmptyTags) {
        List<StoragePoolVO> allPoolsByTags = new ArrayList<>();
        allPoolsByTags.addAll(storagePoolDao.findPoolsByAccessGroupsForHostConnection(dcId, podId, clusterId, ScopeType.CLUSTER, storageAccessGroups));
        allPoolsByTags.addAll(storagePoolDao.findZoneWideStoragePoolsByAccessGroupsForHostConnection(dcId, storageAccessGroups));
        if (includeEmptyTags) {
            allPoolsByTags.addAll(storagePoolDao.findStoragePoolsByEmptyStorageAccessGroups(dcId, podId, clusterId, ScopeType.CLUSTER, null));
            allPoolsByTags.addAll(storagePoolDao.findStoragePoolsByEmptyStorageAccessGroups(dcId, null, null, ScopeType.ZONE, null));
        }

        return allPoolsByTags;
    }

    private List<StoragePoolVO> getStoragePoolsByEmptyStorageAccessGroups(Long dcId, Long podId, Long clusterId) {
        List<StoragePoolVO> allPoolsByTags = new ArrayList<>();
        allPoolsByTags.addAll(storagePoolDao.findStoragePoolsByEmptyStorageAccessGroups(dcId, podId, clusterId, ScopeType.CLUSTER, null));
        allPoolsByTags.addAll(storagePoolDao.findStoragePoolsByEmptyStorageAccessGroups(dcId, null, null, ScopeType.ZONE, null));

        return allPoolsByTags;
    }

    private void connectHostToStoragePools(HostVO host, List<StoragePoolVO> poolsToAdd) {
        List<StoragePoolHostVO> storagePoolsConnectedToHost = storageManager.findStoragePoolsConnectedToHost(host.getId());
        for (StoragePoolVO storagePool : poolsToAdd) {
            if (CollectionUtils.isNotEmpty(storagePoolsConnectedToHost)) {
                boolean isPresent = storagePoolsConnectedToHost.stream()
                        .anyMatch(poolHost -> poolHost.getPoolId() == storagePool.getId());
                if (isPresent) {
                    continue;
                }
            }
            try {
                storageManager.connectHostToSharedPool(host, storagePool.getId());
            } catch (StorageConflictException se) {
                throw new CloudRuntimeException(String.format("Unable to establish a connection between pool %s and the host %s", storagePool, host));
            } catch (Exception e) {
                LOG.warn(String.format("Unable to establish a connection between pool %s and the host %s", storagePool, host), e);
            }
        }
    }

    @Override
    public void connectHostToStoragePool(HostVO host, StoragePoolVO storagePool) {
        try {
            storageManager.connectHostToSharedPool(host, storagePool.getId());
        } catch (StorageConflictException se) {
            throw new CloudRuntimeException(String.format("Unable to establish a connection between pool %s and the host %s", storagePool, host));
        } catch (Exception e) {
            LOG.warn(String.format("Unable to establish a connection between pool %s and the host %s", storagePool, host), e);
        }
    }

    private void disconnectHostFromStoragePools(HostVO host, List<StoragePoolVO> poolsToDelete) {
        List<Long> usedStoragePoolIDs = listOfStoragePoolIDsUsedByHost(host.getId());
        if (usedStoragePoolIDs != null) {
            poolsToDelete.removeIf(poolToDelete ->
                    usedStoragePoolIDs.stream().anyMatch(usedPoolId -> usedPoolId == poolToDelete.getId())
            );
        }
        for (StoragePoolVO storagePool : poolsToDelete) {
            disconnectHostFromStoragePool(host, storagePool);
        }
    }

    @Override
    public void disconnectHostFromStoragePool(HostVO host, StoragePoolVO storagePool) {
        try {
            storageManager.disconnectHostFromSharedPool(host, storagePool);
            storagePoolHostDao.deleteStoragePoolHostDetails(host.getId(), storagePool.getId());
        } catch (StorageConflictException se) {
            throw new CloudRuntimeException(String.format("Unable to disconnect the pool %s and the host %s", storagePool, host));
        } catch (Exception e) {
            LOG.warn(String.format("Unable to disconnect the pool %s and the host %s", storagePool, host), e);
        }
    }

    // -----------------------------------------------------------------------
    // ORCHESTRATOR methods — bodies duplicated here for direct callers;
    // RM retains verbatim copies for spy-compatibility (plan §5.2).
    // -----------------------------------------------------------------------

    @Override
    public void updateStoragePoolConnectionsOnHosts(Long poolId, List<String> storageAccessGroups) {
        StoragePoolVO storagePool = storagePoolDao.findById(poolId);
        List<HostVO> hosts = new ArrayList<>();

        if (storagePool.getScope().equals(ScopeType.CLUSTER)) {
            List<HostVO> hostsInCluster = hostLookupService.listAllUpHosts(Host.Type.Routing, storagePool.getClusterId(), storagePool.getPodId(), storagePool.getDataCenterId());
            hosts.addAll(hostsInCluster);
        } else if (storagePool.getScope().equals(ScopeType.ZONE)) {
            List<HostVO> hostsInZone = hostLookupService.listAllUpHosts(Host.Type.Routing, null, null, storagePool.getDataCenterId());
            hosts.addAll(hostsInZone);
        }

        List<HostVO> hostsToConnect = new ArrayList<>();
        List<HostVO> hostsToDisconnect = new ArrayList<>();
        boolean storagePoolHasAccessGroups = CollectionUtils.isNotEmpty(storageAccessGroups);

        for (HostVO host : hosts) {
            String[] storageAccessGroupsOnHost = storageManager.getStorageAccessGroups(null, null, null, host.getId());
            List<String> listOfStorageAccessGroupsOnHost = Arrays.asList(storageAccessGroupsOnHost);
            StoragePoolHostVO hostPoolRecord = storagePoolHostDao.findByPoolHost(storagePool.getId(), host.getId());

            if (storagePoolHasAccessGroups) {
                List<String> intersection = new ArrayList<>(listOfStorageAccessGroupsOnHost);
                intersection.retainAll(storageAccessGroups);
                if (CollectionUtils.isNotEmpty(intersection)) {
                    if (hostPoolRecord == null) {
                        hostsToConnect.add(host);
                    }
                } else {
                    hostsToDisconnect.add(host);
                }
            } else {
                if (hostPoolRecord == null) {
                    hostsToConnect.add(host);
                }
            }
        }

        if (CollectionUtils.isNotEmpty(hostsToDisconnect)) {
            List<Long> hostIdsUsingTheStoragePool = listOfHostIdsUsingTheStoragePool(poolId);
            List<Long> hostIdsToDisconnect = hostsToDisconnect.stream()
                    .map(HostVO::getId)
                    .collect(Collectors.toList());
            List<Long> conflictingHostIds = new ArrayList<>(CollectionUtils.intersection(hostIdsToDisconnect, hostIdsUsingTheStoragePool));
            if (CollectionUtils.isNotEmpty(conflictingHostIds)) {
                Map<HostVO, List<VolumeVO>> hostVolumeMap = new HashMap<>();
                List<VolumeVO> volumesInPool = volumeDao.findNonDestroyedVolumesByPoolId(poolId);
                Map<Long, VMInstanceVO> vmInstanceCache = new HashMap<>();

                for (Long hostId : conflictingHostIds) {
                    HostVO host = hostDao.findById(hostId);
                    List<VolumeVO> matchingVolumes = volumesInPool.stream()
                            .filter(volume -> {
                                Long vmId = volume.getInstanceId();
                                if (vmId == null) return false;

                                VMInstanceVO vmInstance = vmInstanceCache.computeIfAbsent(vmId, vmDao::findById);
                                return vmInstance != null && hostId.equals(vmInstance.getHostId());
                            })
                            .collect(Collectors.toList());
                    if (!matchingVolumes.isEmpty()) {
                        hostVolumeMap.put(host, matchingVolumes);
                    }
                }

                LOG.error(String.format("Conflict detected: Hosts using the storage pool that need to be disconnected or " +
                        "connected to the pool: Host IDs and volumes: %s", hostVolumeMap));
                throw new CloudRuntimeException("Storage access groups cannot be updated as they are currently in use by some hosts. Please check the logs.");
            }
        }

        if (!hostsToConnect.isEmpty()) {
            for (HostVO host : hostsToConnect) {
                LOG.debug(String.format("Connecting [%s] to [%s]", host, storagePool));
                connectHostToStoragePool(host, storagePool);
            }
        }

        if (!hostsToDisconnect.isEmpty()) {
            for (HostVO host : hostsToDisconnect) {
                LOG.debug(String.format("Disconnecting [%s] from [%s]", host, storagePool));
                disconnectHostFromStoragePool(host, storagePool);
            }
        }
    }

    @Override
    public List<HostVO> getEligibleUpHostsInClusterForStorageConnection(PrimaryDataStoreInfo primaryStore) {
        List<HostVO> allHosts = hostLookupService.listAllUpHosts(Host.Type.Routing, primaryStore.getClusterId(), primaryStore.getPodId(), primaryStore.getDataCenterId());
        if (CollectionUtils.isEmpty(allHosts)) {
            storagePoolDao.expunge(primaryStore.getId());
            throw new CloudRuntimeException("No host up to associate a storage pool with in cluster " + primaryStore.getClusterId());
        }

        List<String> storageAccessGroups = storagePoolAccessGroupMapDao.getStorageAccessGroups(primaryStore.getId());
        return filterHostsBasedOnStorageAccessGroups(allHosts, storageAccessGroups);
    }

    @Override
    public List<HostVO> getEligibleUpAndEnabledHostsInClusterForStorageConnection(PrimaryDataStoreInfo primaryStore) {
        List<HostVO> allHosts = hostLookupService.listAllUpAndEnabledHosts(Host.Type.Routing, primaryStore.getClusterId(), primaryStore.getPodId(), primaryStore.getDataCenterId());
        if (CollectionUtils.isEmpty(allHosts)) {
            storagePoolDao.expunge(primaryStore.getId());
            throw new CloudRuntimeException("No host up to associate a storage pool with in cluster " + primaryStore.getClusterId());
        }

        List<String> storageAccessGroups = storagePoolAccessGroupMapDao.getStorageAccessGroups(primaryStore.getId());
        return filterHostsBasedOnStorageAccessGroups(allHosts, storageAccessGroups);
    }

    @Override
    public List<HostVO> getEligibleUpAndEnabledHostsInZoneForStorageConnection(DataStore dataStore, long zoneId, HypervisorType hypervisorType) {
        List<HostVO> allHosts = hostLookupService.listAllUpAndEnabledHostsInOneZoneByHypervisor(hypervisorType, zoneId);

        List<String> storageAccessGroups = storagePoolAccessGroupMapDao.getStorageAccessGroups(dataStore.getId());
        return filterHostsBasedOnStorageAccessGroups(allHosts, storageAccessGroups);
    }

    protected void checkIfAllHostsInUse(List<String> sagsToDelete, Long clusterId, Long podId, Long zoneId) {
        if (CollectionUtils.isEmpty(sagsToDelete)) {
            return;
        }

        List<Long> hostIdsUsingStorageAccessGroups = listOfHostIdsUsingTheStorageAccessGroups(sagsToDelete, clusterId, podId, zoneId);

        // Check for zone level hosts
        if (zoneId != null) {
            List<HostVO> hostsInZone = hostDao.findByDataCenterId(zoneId);
            Set<Long> hostIdsInUseSet = hostIdsUsingStorageAccessGroups.stream().collect(Collectors.toSet());

            boolean allInUseZone = hostsInZone.stream()
                    .map(HostVO::getId)
                    .allMatch(hostIdsInUseSet::contains);

            if (allInUseZone) {
                throw new CloudRuntimeException("All hosts in the zone are using the storage access groups");
            }
        }

        // Check for cluster level hosts
        if (clusterId != null) {
            List<HostVO> hostsInCluster = hostDao.findByClusterId(clusterId, Host.Type.Routing);
            Set<Long> hostIdsInUseSet = hostIdsUsingStorageAccessGroups.stream().collect(Collectors.toSet());

            boolean allInUseCluster = hostsInCluster.stream()
                    .map(HostVO::getId)
                    .allMatch(hostIdsInUseSet::contains);

            if (allInUseCluster) {
                throw new CloudRuntimeException("All hosts in the cluster are using the storage access groups");
            }
        }

        // Check for pod level hosts
        if (podId != null) {
            List<HostVO> hostsInPod = hostDao.findByPodId(podId, Host.Type.Routing);
            Set<Long> hostIdsInUseSet = hostIdsUsingStorageAccessGroups.stream().collect(Collectors.toSet());

            boolean allInUsePod = hostsInPod.stream()
                    .map(HostVO::getId)
                    .allMatch(hostIdsInUseSet::contains);

            if (allInUsePod) {
                throw new CloudRuntimeException("All hosts in the pod are using the storage access groups");
            }
        }
    }

    @Override
    public void updateZoneStorageAccessGroups(long zoneId, List<String> newStorageAccessGroups) {
        DataCenterVO zoneVO = dataCenterDao.findById(zoneId);
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("Updating storage access groups %s to the zone %s", newStorageAccessGroups, zoneVO));
        }

        List<String> sagsToAdd = new ArrayList<>(newStorageAccessGroups);
        String sagsOnPod = zoneVO.getStorageAccessGroups();
        List<String> sagsToDelete;
        if (sagsOnPod == null || sagsOnPod.trim().isEmpty()) {
            sagsToDelete = new ArrayList<>();
        } else {
            sagsToDelete = new ArrayList<>(Arrays.asList(sagsOnPod.split(",")));
        }
        sagsToDelete.removeAll(newStorageAccessGroups);
        checkIfAllHostsInUse(sagsToDelete, null, null, zoneId);

        Map<HostVO, List<String>> hostsAndStorageAccessGroupsMap = new HashMap<>();
        List<HostPodVO> pods = podDao.listByDataCenterId(zoneId);
        for (HostPodVO pod : pods) {
            List<HostVO> hostsInPod = hostDao.findHypervisorHostInPod(pod.getId());
            for (HostVO host : hostsInPod) {
                String[] existingSAGs = storageManager.getStorageAccessGroups(null, null, null, host.getId());
                List<String> existingSAGsList = new ArrayList<>(Arrays.asList(existingSAGs));
                existingSAGsList.removeAll(sagsToDelete);
                List<String> combinedSAGs = new ArrayList<>(sagsToAdd);
                combinedSAGs.addAll(existingSAGsList);
                hostsAndStorageAccessGroupsMap.put(host, combinedSAGs);
            }
            updateConnectionsBetweenHostsAndStoragePools(hostsAndStorageAccessGroupsMap);
        }

        removeStorageAccessGroupsOnPodsInZone(zoneVO.getId(), newStorageAccessGroups, sagsToDelete);
    }

    @Override
    public void updatePodStorageAccessGroups(long podId, List<String> newStorageAccessGroups) {
        HostPodVO podVO = podDao.findById(podId);
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("Updating storage access groups %s to the pod %s", newStorageAccessGroups, podVO));
        }

        List<String> sagsToAdd = new ArrayList<>(newStorageAccessGroups);

        String sagsOnPod = podVO.getStorageAccessGroups();
        List<String> sagsToDelete;
        if (sagsOnPod == null || sagsOnPod.trim().isEmpty()) {
            sagsToDelete = new ArrayList<>();
        } else {
            sagsToDelete = new ArrayList<>(Arrays.asList(sagsOnPod.split(",")));
        }
        sagsToDelete.removeAll(newStorageAccessGroups);

        checkIfAllHostsInUse(sagsToDelete, null, podId, null);

        Map<HostVO, List<String>> hostsAndStorageAccessGroupsMap = new HashMap<>();
        List<HostVO> hostsInPod = hostDao.findHypervisorHostInPod(podId);
        for (HostVO host : hostsInPod) {
            String[] existingSAGs = storageManager.getStorageAccessGroups(null, null, null, host.getId());
            List<String> existingSAGsList = new ArrayList<>(Arrays.asList(existingSAGs));
            existingSAGsList.removeAll(sagsToDelete);
            List<String> combinedSAGs = new ArrayList<>(sagsToAdd);
            combinedSAGs.addAll(existingSAGsList);
            hostsAndStorageAccessGroupsMap.put(host, combinedSAGs);
        }

        updateConnectionsBetweenHostsAndStoragePools(hostsAndStorageAccessGroupsMap);
        removeStorageAccessGroupsOnClustersInPod(podId, newStorageAccessGroups, sagsToDelete);
    }

    @Override
    public void updateClusterStorageAccessGroups(Long clusterId, List<String> newStorageAccessGroups) {
        ClusterVO cluster = clusterDao.findById(clusterId);
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("Updating storage access groups %s to the cluster %s", newStorageAccessGroups, cluster));
        }

        List<String> sagsToAdd = new ArrayList<>(newStorageAccessGroups);

        String existingClusterStorageAccessGroups = cluster.getStorageAccessGroups();
        List<String> sagsToDelete;
        if (existingClusterStorageAccessGroups == null || existingClusterStorageAccessGroups.trim().isEmpty()) {
            sagsToDelete = new ArrayList<>();
        } else {
            sagsToDelete = new ArrayList<>(Arrays.asList(existingClusterStorageAccessGroups.split(",")));
        }
        sagsToDelete.removeAll(newStorageAccessGroups);

        checkIfAllHostsInUse(sagsToDelete, clusterId, null, null);

        List<HostVO> hostsInCluster = hostDao.findHypervisorHostInCluster(cluster.getId());
        Map<HostVO, List<String>> hostsAndStorageAccessGroupsMap = new HashMap<>();
        for (HostVO host : hostsInCluster) {
            String[] existingSAGs = storageManager.getStorageAccessGroups(null, null, null, host.getId());
            Set<String> existingSAGsSet = new HashSet<>(Arrays.asList(existingSAGs));
            existingSAGsSet.removeAll(sagsToDelete);
            List<String> existingSAGsList = new ArrayList<>(existingSAGsSet);
            Set<String> combinedSAGsSet = new HashSet<>(sagsToAdd);
            combinedSAGsSet.addAll(existingSAGsList);

            hostsAndStorageAccessGroupsMap.put(host, new ArrayList<>(combinedSAGsSet));
        }

        updateConnectionsBetweenHostsAndStoragePools(hostsAndStorageAccessGroupsMap);

        updateStorageAccessGroupsToBeDeletedOnHostsInCluster(cluster.getId(), sagsToDelete);
        updateStorageAccessGroupsToBeAddedOnHostsInCluster(cluster.getId(), newStorageAccessGroups);
    }

    @Override
    public void updateHostStorageAccessGroups(Long hostId, List<String> newStorageAccessGroups) {
        HostVO host = hostDao.findById(hostId);
        if (LOG.isDebugEnabled()) {
            LOG.debug(String.format("Updating storage access groups %s to the host %s", newStorageAccessGroups, host));
        }

        List<String> sagsToAdd = new ArrayList<>(newStorageAccessGroups);
        String[] sagsOnCluster = storageManager.getStorageAccessGroups(null, null, host.getClusterId(), null);
        if (ArrayUtils.isNotEmpty(sagsOnCluster)) {
            sagsToAdd.addAll(Arrays.asList(sagsOnCluster));
        }

        String sagsOnHost = host.getStorageAccessGroups();
        List<String> sagsToDelete;
        if (sagsOnHost == null || sagsOnHost.trim().isEmpty()) {
            sagsToDelete = new ArrayList<>();
        } else {
            sagsToDelete = new ArrayList<>(Arrays.asList(sagsOnHost.split(",")));
        }
        sagsToDelete.removeAll(newStorageAccessGroups);

        checkIfAnyVolumesInUse(sagsToAdd, sagsToDelete, host);

        updateConnectionsBetweenHostsAndStoragePools(java.util.Collections.singletonMap(host, sagsToAdd));

        host.setStorageAccessGroups(CollectionUtils.isEmpty(newStorageAccessGroups) ? null : String.join(",", newStorageAccessGroups));
        hostDao.update(host.getId(), host);
    }
}
