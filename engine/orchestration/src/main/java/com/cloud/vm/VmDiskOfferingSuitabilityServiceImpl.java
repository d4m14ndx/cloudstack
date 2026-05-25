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

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.engine.subsystem.api.storage.StoragePoolAllocator;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.dao.ClusterDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.deploy.DeploymentPlanner.ExcludeList;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.org.Cluster;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.StoragePool;
import com.cloud.storage.Volume;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

@Component
public class VmDiskOfferingSuitabilityServiceImpl implements VmDiskOfferingSuitabilityService {

    private static final Logger logger = LogManager.getLogger(VmDiskOfferingSuitabilityServiceImpl.class);

    @Inject
    protected VMInstanceDao vmInstanceDao;
    @Inject
    protected VMInstanceDetailsDao vmInstanceDetailsDao;
    @Inject
    protected VolumeDao volumeDao;
    @Inject
    protected PrimaryDataStoreDao storagePoolDao;
    @Inject
    protected HostDao hostDao;
    @Inject
    protected ClusterDao clusterDao;
    @Inject
    protected DiskOfferingDao diskOfferingDao;

    private List<StoragePoolAllocator> storagePoolAllocators;

    @Inject
    public void setStoragePoolAllocators(final List<StoragePoolAllocator> storagePoolAllocators) {
        this.storagePoolAllocators = storagePoolAllocators;
    }

    public List<StoragePoolAllocator> getStoragePoolAllocators() {
        return storagePoolAllocators;
    }

    Pair<Long, Long> findClusterAndHostIdForVmFromVolumes(long vmId) {
        Long clusterId = null;
        Long hostId = null;
        List<VolumeVO> volumes = volumeDao.findByInstance(vmId);
        for (VolumeVO volume : volumes) {
            if (Volume.State.Ready.equals(volume.getState()) &&
                    volume.getPoolId() != null) {
                StoragePoolVO pool = storagePoolDao.findById(volume.getPoolId());
                if (pool != null && pool.getClusterId() != null) {
                    clusterId = pool.getClusterId();
                    // hostId to be used only for sending commands, capacity check skipped
                    List<HostVO> hosts = hostDao.findHypervisorHostInCluster(pool.getClusterId());
                    if (CollectionUtils.isNotEmpty(hosts)) {
                        hostId = hosts.get(0).getId();
                        break;
                    }
                }
            }
        }
        return new Pair<>(clusterId, hostId);
    }

    @Override
    public Pair<Long, Long> findClusterAndHostIdForVm(VirtualMachine vm, boolean skipCurrentHostForStartingVm) {
        Long hostId = null;
        Host host = null;
        if (!skipCurrentHostForStartingVm || !State.Starting.equals(vm.getState())) {
            hostId = vm.getHostId();
        }
        Long clusterId = null;
        if (hostId == null) {
            if (vm.getLastHostId() == null) {
                return findClusterAndHostIdForVmFromVolumes(vm.getId());
            }
            hostId = vm.getLastHostId();
            host = hostDao.findById(hostId);
            logger.debug("host id is null, using last host {} with id {}", host, hostId);
        }
        host = host == null ? hostDao.findById(hostId) : host;
        if (host != null) {
            clusterId = host.getClusterId();
            return new Pair<>(clusterId, hostId);
        }
        return findClusterAndHostIdForVmFromVolumes(vm.getId());
    }

    Pair<Long, Long> findClusterAndHostIdForVm(VirtualMachine vm) {
        return findClusterAndHostIdForVm(vm, false);
    }

    @Override
    public Pair<Long, Long> findClusterAndHostIdForVm(long vmId) {
        VMInstanceVO vm = vmInstanceDao.findById(vmId);
        if (vm == null) {
            return new Pair<>(null, null);
        }
        return findClusterAndHostIdForVm(vm);
    }

    @Override
    public boolean isDiskOfferingSuitableForVm(VMInstanceVO vm, VirtualMachineProfile profile, long podId, long clusterId, long hostId, long diskOfferingId) {
        DiskOfferingVO diskOffering = diskOfferingDao.findById(diskOfferingId);
        VolumeVO dummyVolume = new VolumeVO("Data", vm.getDataCenterId(), podId, vm.getAccountId(),
                vm.getDomainId(), vm.getId(), null, null, diskOffering.getProvisioningType(), diskOffering.getDiskSize(), Volume.Type.DATADISK);
        try {
            Field idField = dummyVolume.getClass().getDeclaredField("id");
            idField.setAccessible(true);
            idField.set(dummyVolume, Volume.DISK_OFFERING_SUITABILITY_CHECK_VOLUME_ID);
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
            return false;
        }
        dummyVolume.setDiskOfferingId(diskOfferingId);
        DiskProfile diskProfile = new DiskProfile(dummyVolume, diskOffering, profile.getHypervisorType());
        diskProfile.setMinIops(diskOffering.getMinIops());
        diskProfile.setMaxIops(diskOffering.getMaxIops());
        ExcludeList avoid = new ExcludeList();
        DataCenterDeployment plan = new DataCenterDeployment(vm.getDataCenterId(), podId, clusterId, hostId, null, null);
        for (StoragePoolAllocator allocator : storagePoolAllocators) {
            List<StoragePool> poolListFromAllocator = allocator.allocateToPool(diskProfile, profile, plan, avoid, 1);
            if (CollectionUtils.isNotEmpty(poolListFromAllocator)) {
                logger.debug("Found a suitable pool: {} for disk offering: {}", poolListFromAllocator.get(0).getName(), diskOffering.getName());
                return true;
            }
        }
        return false;
    }

    @Override
    public Map<Long, Boolean> getDiskOfferingSuitabilityForVm(long vmId, List<Long> diskOfferingIds) {
        VMInstanceVO vm = vmInstanceDao.findById(vmId);
        if (vmInstanceDetailsDao.findDetail(vm.getId(), VmDetailConstants.DEPLOY_VM) != null) {
            return new HashMap<>();
        }
        VirtualMachineProfile profile = new VirtualMachineProfileImpl(vm);
        Pair<Long, Long> clusterAndHost = findClusterAndHostIdForVm(vm, false);
        Long clusterId = clusterAndHost.first();
        Cluster cluster = clusterDao.findById(clusterId);
        Map<Long, Boolean> result = new HashMap<>();
        for (Long diskOfferingId : diskOfferingIds) {
            result.put(diskOfferingId, isDiskOfferingSuitableForVm(vm, profile, cluster.getPodId(), clusterId, clusterAndHost.second(), diskOfferingId));
        }
        return result;
    }
}
