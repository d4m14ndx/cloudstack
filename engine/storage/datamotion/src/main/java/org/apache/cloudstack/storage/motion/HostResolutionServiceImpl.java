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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.SnapshotInfo;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.springframework.stereotype.Component;

import com.cloud.dc.dao.ClusterDao;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.resource.ResourceManager;
import com.cloud.resource.ResourceState;
import com.cloud.storage.DataStoreRole;
import com.cloud.utils.exception.CloudRuntimeException;
import com.google.common.base.Preconditions;

/**
 * Eligible-host lookups for storage-side data-motion operations -
 * extracted from {@link StorageSystemDataMotionStrategy}.
 *
 * @see HostResolutionService
 */
@Component
public class HostResolutionServiceImpl implements HostResolutionService {

    private static final Random RANDOM = new Random(System.nanoTime());

    @Inject
    private ClusterDao clusterDao;

    @Inject
    private HostDao hostDao;

    @Inject
    private DataStoreManager dataStoreMgr;

    @Inject
    private ResourceManager resourceManager;

    @Override
    public HostVO getHost(SnapshotInfo snapshotInfo) {
        HypervisorType hypervisorType = snapshotInfo.getHypervisorType();

        if (HypervisorType.XenServer.equals(hypervisorType)) {
            HostVO hostVO = getHost(snapshotInfo, hypervisorType, true);

            if (hostVO == null) {
                hostVO = getHost(snapshotInfo, hypervisorType, false);

                if (hostVO == null) {
                    throw new CloudRuntimeException("Unable to locate an applicable host in data center with ID = " + snapshotInfo.getDataCenterId());
                }
            }

            return hostVO;
        }

        if (HypervisorType.VMware.equals(hypervisorType) || HypervisorType.KVM.equals(hypervisorType)) {
            return getHost(snapshotInfo, hypervisorType, false);
        }

        throw new CloudRuntimeException("Unsupported hypervisor type");
    }

    @Override
    public HostVO getHostInCluster(StoragePoolVO storagePool) {
        DataStore store = dataStoreMgr.getDataStore(storagePool.getId(), DataStoreRole.Primary);
        List<HostVO> hosts = resourceManager.getEligibleUpAndEnabledHostsInClusterForStorageConnection((PrimaryDataStoreInfo) store);

        if (hosts != null && hosts.size() > 0) {
            Collections.shuffle(hosts, RANDOM);

            for (HostVO host : hosts) {
                if (ResourceState.Enabled.equals(host.getResourceState())) {
                    return host;
                }
            }
        }

        throw new CloudRuntimeException("Unable to locate a host");
    }

    @Override
    public HostVO getHost(SnapshotInfo snapshotInfo, HypervisorType hypervisorType, boolean computeClusterMustSupportResign) {
        Long zoneId = snapshotInfo.getDataCenterId();
        Preconditions.checkArgument(zoneId != null, "Zone ID cannot be null.");
        Preconditions.checkArgument(hypervisorType != null, "Hypervisor type cannot be null.");

        List<HostVO> hosts;
        if (DataStoreRole.Primary.equals(snapshotInfo.getDataStore().getRole())) {
            hosts = resourceManager.getEligibleUpAndEnabledHostsInZoneForStorageConnection(snapshotInfo.getDataStore(), zoneId, hypervisorType);
        } else {
            hosts = hostDao.listByDataCenterIdAndHypervisorType(zoneId, hypervisorType);
        }

        return getHost(hosts, computeClusterMustSupportResign);
    }

    @Override
    public HostVO getHost(VolumeInfo volumeInfo, HypervisorType hypervisorType, boolean computeClusterMustSupportResign) {
        Long zoneId = volumeInfo.getDataCenterId();
        Preconditions.checkArgument(zoneId != null, "Zone ID cannot be null.");
        Preconditions.checkArgument(hypervisorType != null, "Hypervisor type cannot be null.");

        List<HostVO> hosts;
        if (DataStoreRole.Primary.equals(volumeInfo.getDataStore().getRole())) {
            hosts = resourceManager.getEligibleUpAndEnabledHostsInZoneForStorageConnection(volumeInfo.getDataStore(), zoneId, hypervisorType);
        } else {
            hosts = hostDao.listByDataCenterIdAndHypervisorType(zoneId, hypervisorType);
        }

        return getHost(hosts, computeClusterMustSupportResign);
    }

    @Override
    public HostVO getHost(List<HostVO> hosts, boolean computeClusterMustSupportResign) {
        if (hosts == null) {
            return null;
        }

        List<Long> clustersToSkip = new ArrayList<>();

        Collections.shuffle(hosts, RANDOM);

        for (HostVO host : hosts) {
            if (!ResourceState.Enabled.equals(host.getResourceState())) {
                continue;
            }

            if (computeClusterMustSupportResign) {
                long clusterId = host.getClusterId();

                if (clustersToSkip.contains(clusterId)) {
                    continue;
                }

                if (clusterDao.getSupportsResigning(clusterId)) {
                    return host;
                }
                else {
                    clustersToSkip.add(clusterId);
                }
            }
            else {
                return host;
            }
        }

        return null;
    }
}
