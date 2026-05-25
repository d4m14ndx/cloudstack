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

import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProvider;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreProviderManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.GetVolumeStatsAnswer;
import com.cloud.agent.api.GetVolumeStatsCommand;
import com.cloud.agent.api.VolumeStatsEntry;
import com.cloud.host.HostVO;
import com.cloud.host.Status;
import com.cloud.resource.ResourceManager;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.Storage.StoragePoolType;
import com.cloud.storage.StorageManager;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.vm.dao.VMInstanceDao;

@Component
public class VmVolumeStatisticsServiceImpl implements VmVolumeStatisticsService {

    @Inject
    private ResourceManager _resourceMgr;
    @Inject
    private PrimaryDataStoreDao _storagePoolDao;
    @Inject
    private VMInstanceDao _vmInstanceDao;
    @Inject
    private VolumeDao _volsDao;
    @Inject
    private DataStoreProviderManager _dataStoreProviderMgr;
    @Inject
    private StorageManager storageManager;
    @Inject
    private AgentManager _agentMgr;

    @Override
    public HashMap<String, VolumeStatsEntry> getVolumeStatistics(long clusterId, String poolUuid, StoragePoolType poolType, int timeout) {
        List<HostVO> neighbors = _resourceMgr.listHostsInClusterByStatus(clusterId, Status.Up);
        StoragePoolVO storagePool = _storagePoolDao.findPoolByUUID(poolUuid);
        HashMap<String, VolumeStatsEntry> volumeStatsByUuid = new HashMap<>();

        for (HostVO neighbor : neighbors) {

            // - zone wide storage for specific hypervisortypes
            if ((ScopeType.ZONE.equals(storagePool.getScope()) && storagePool.getHypervisor() != neighbor.getHypervisorType())) {
                // skip this neighbour if their hypervisor type is not the same as that of the store
                continue;
            }

            List<String> volumeLocators = getVolumesByHost(neighbor, storagePool);
            if (!CollectionUtils.isEmpty(volumeLocators)) {

                GetVolumeStatsCommand cmd = new GetVolumeStatsCommand(poolType, poolUuid, volumeLocators);
                Answer answer = null;

                DataStoreProvider storeProvider = _dataStoreProviderMgr
                        .getDataStoreProvider(storagePool.getStorageProviderName());
                DataStoreDriver storeDriver = storeProvider.getDataStoreDriver();

                if (storeDriver instanceof PrimaryDataStoreDriver && ((PrimaryDataStoreDriver) storeDriver).canProvideVolumeStats()) {
                    // Get volume stats from the pool directly instead of sending cmd to host
                    answer = storageManager.getVolumeStats(storagePool, cmd);
                } else {
                    if (timeout > 0) {
                        cmd.setWait(timeout / 1000);
                    }

                    answer = _agentMgr.easySend(neighbor.getId(), cmd);
                }

                if (answer != null && answer instanceof GetVolumeStatsAnswer) {
                    GetVolumeStatsAnswer volstats = (GetVolumeStatsAnswer)answer;
                    if (volstats.getVolumeStats() != null) {
                        volumeStatsByUuid.putAll(volstats.getVolumeStats());
                    }
                }
            }
        }
        return volumeStatsByUuid.size() > 0 ? volumeStatsByUuid : null;
    }

    List<String> getVolumesByHost(HostVO host, StoragePool pool) {
        List<VMInstanceVO> vmsPerHost = _vmInstanceDao.listByHostId(host.getId());
        return vmsPerHost.stream()
                .flatMap(vm -> _volsDao.findNonDestroyedVolumesByInstanceIdAndPoolId(vm.getId(), pool.getId()).stream().map(vol ->
                vol.getState() == Volume.State.Ready ? (vol.getFormat() == ImageFormat.OVA ? vol.getChainInfo() : vol.getPath()) : null).filter(Objects::nonNull))
                .collect(Collectors.toList());
    }
}
