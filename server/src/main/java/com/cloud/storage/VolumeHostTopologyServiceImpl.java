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
package com.cloud.storage;

import java.util.ArrayList;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreDriver;
import org.apache.cloudstack.engine.subsystem.api.storage.PrimaryDataStoreDriver;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.hypervisor.dao.HypervisorCapabilitiesDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VmDetailConstants;

/**
 * Host- and device-topology helpers backing the volume attach / detach
 * orchestration in {@link VolumeApiServiceImpl} — extracted from that
 * class.
 *
 * @see VolumeHostTopologyService
 */
@Component
public class VolumeHostTopologyServiceImpl implements VolumeHostTopologyService {
    private static final Logger LOG = LogManager.getLogger(VolumeHostTopologyServiceImpl.class);

    /**
     * Hard floor on the per-VM data-disk limit when neither the
     * running host nor the configured default-hypervisor entry yields
     * a positive value.
     */
    static final int DEFAULT_MAX_DATA_VOLUMES = 6;

    /**
     * Slot reserved for the cdrom; never handed out as a data-disk
     * device id.
     */
    static final long CDROM_DEVICE_ID = 3L;

    @Inject
    private HostDao hostDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private PrimaryDataStoreDao storagePoolDao;
    @Inject
    private HypervisorCapabilitiesDao hypervisorCapabilitiesDao;
    @Inject
    private VirtualMachineManager virtualMachineManager;
    @Inject
    private StorageUtil storageUtil;

    /**
     * Cached at first call to mirror the field
     * {@code VolumeApiServiceImpl.supportingDefaultHV} that used to be
     * primed in {@code configure()}.
     */
    private volatile List<HypervisorType> supportingDefaultHV;

    @Override
    public void verifyManagedStorage(Long storagePoolId, Long hostId) {
        if (storagePoolId == null || hostId == null) {
            return;
        }

        StoragePoolVO storagePoolVO = storagePoolDao.findById(storagePoolId);

        if (storagePoolVO == null || !storagePoolVO.isManaged()) {
            return;
        }

        HostVO hostVO = hostDao.findById(hostId);

        if (hostVO == null) {
            return;
        }

        if (!storageUtil.managedStoragePoolCanScale(storagePoolVO, hostVO.getClusterId(), hostVO.getId())) {
            throw new CloudRuntimeException("Insufficient number of available " + getNameOfClusteredFileSystem(hostVO));
        }
    }

    @Override
    public String getNameOfClusteredFileSystem(HostVO hostVO) {
        HypervisorType hypervisorType = hostVO.getHypervisorType();

        if (HypervisorType.XenServer.equals(hypervisorType)) {
            return "SRs";
        }

        if (HypervisorType.VMware.equals(hypervisorType)) {
            return "datastores";
        }

        return "clustered file systems";
    }

    @Override
    public HostVO getHostForVmVolumeAttachDetach(VirtualMachine vm, StoragePoolVO volumeStoragePool) {
        HostVO host = null;
        Pair<Long, Long> clusterAndHostId = virtualMachineManager.findClusterAndHostIdForVm(vm.getId());
        Long hostId = clusterAndHostId.second();
        Long clusterId = clusterAndHostId.first();
        if (hostId == null && clusterId != null &&
                State.Stopped.equals(vm.getState()) &&
                volumeStoragePool != null &&
                !ScopeType.HOST.equals(volumeStoragePool.getScope())) {
            List<HostVO> hosts = hostDao.findHypervisorHostInCluster(clusterId);
            if (!hosts.isEmpty()) {
                host = hosts.get(0);
            }
        }
        if (host == null && hostId != null) {
            host = hostDao.findById(hostId);
        }
        return host;
    }

    @Override
    public boolean isSendCommandForVmVolumeAttachDetach(HostVO host, StoragePoolVO volumeStoragePool) {
        if (host == null || volumeStoragePool == null) {
            return false;
        }
        boolean sendCommand = HypervisorType.VMware.equals(host.getHypervisorType());
        if (HypervisorType.XenServer.equals(host.getHypervisorType()) &&
                volumeStoragePool.isManaged()) {
            sendCommand = true;
        }
        return sendCommand;
    }

    @Override
    public boolean isIothreadsSupported(UserVmVO vm) {
        return vm.getHypervisorType() == HypervisorType.KVM
                && vm.getDetails() != null
                && vm.getDetail(VmDetailConstants.IOTHREADS) != null;
    }

    @Override
    public String getIoPolicy(UserVmVO vm, long poolId) {
        String ioPolicy = null;
        if (vm.getHypervisorType() == HypervisorType.KVM && vm.getDetails() != null && vm.getDetail(VmDetailConstants.IO_POLICY) != null) {
            ioPolicy = vm.getDetail(VmDetailConstants.IO_POLICY);
            if (ApiConstants.IoDriverPolicy.STORAGE_SPECIFIC.toString().equals(ioPolicy)) {
                String storageIoPolicyDriver = StorageManager.STORAGE_POOL_IO_POLICY.valueIn(poolId);
                ioPolicy = storageIoPolicyDriver != null ? storageIoPolicyDriver : null;
            }
        }
        return ioPolicy;
    }

    @Override
    public void provideVMInfo(DataStore dataStore, long vmId, Long volumeId) {
        DataStoreDriver dataStoreDriver = dataStore != null ? dataStore.getDriver() : null;

        if (dataStoreDriver instanceof PrimaryDataStoreDriver) {
            PrimaryDataStoreDriver storageDriver = (PrimaryDataStoreDriver) dataStoreDriver;
            if (storageDriver.isVmInfoNeeded()) {
                storageDriver.provideVmInfo(vmId, volumeId);
            }
        }
    }

    @Override
    public int getMaxDataVolumesSupported(UserVmVO vm) {
        Long hostId = vm.getHostId();
        if (hostId == null) {
            hostId = vm.getLastHostId();
        }
        HostVO host = hostDao.findById(hostId);
        Integer maxDataVolumesSupported = null;
        if (host != null) {
            hostDao.loadDetails(host);
            String hypervisorVersion = host.getDetail("product_version");
            if (StringUtils.isBlank(hypervisorVersion)) {
                hypervisorVersion = host.getHypervisorVersion();
            }
            maxDataVolumesSupported = hypervisorCapabilitiesDao.getMaxDataVolumesLimit(host.getHypervisorType(), hypervisorVersion);
        } else {
            HypervisorType hypervisorType = vm.getHypervisorType();
            List<HypervisorType> defaults = getSupportingDefaultHv();
            if (hypervisorType != null && CollectionUtils.isNotEmpty(defaults) && defaults.contains(hypervisorType)) {
                String hwVersion = getMinimumHypervisorVersionInDatacenter(vm.getDataCenterId(), hypervisorType);
                maxDataVolumesSupported = hypervisorCapabilitiesDao.getMaxDataVolumesLimit(hypervisorType, hwVersion);
            }
        }
        if (maxDataVolumesSupported == null || maxDataVolumesSupported.intValue() <= 0) {
            // 6 data disks by default if nothing is specified in 'hypervisor_capabilities' table
            maxDataVolumesSupported = DEFAULT_MAX_DATA_VOLUMES;
        }

        return maxDataVolumesSupported.intValue();
    }

    @Override
    public String getMinimumHypervisorVersionInDatacenter(long datacenterId, HypervisorType hypervisorType) {
        String defaultHypervisorVersion = "default";
        if (hypervisorType == HypervisorType.Simulator) {
            return defaultHypervisorVersion;
        }
        List<String> hwVersions = hostDao.listOrderedHostsHypervisorVersionsInDatacenter(datacenterId, hypervisorType);
        String minHwVersion = CollectionUtils.isNotEmpty(hwVersions) ? hwVersions.get(0) : defaultHypervisorVersion;
        return StringUtils.isBlank(minHwVersion) ? defaultHypervisorVersion : minHwVersion;
    }

    @Override
    public Long getDeviceId(UserVmVO vm, Long deviceId) {
        // allocate deviceId
        int maxDevices = getMaxDataVolumesSupported(vm) + 2; // add 2 to consider devices root volume and cdrom
        int maxDeviceId = maxDevices - 1;
        List<VolumeVO> vols = volumeDao.findByInstance(vm.getId());
        if (deviceId != null) {
            if (deviceId.longValue() < 0 || deviceId.longValue() > maxDeviceId || deviceId.longValue() == CDROM_DEVICE_ID) {
                throw new RuntimeException("deviceId should be 0,1,2,4-" + maxDeviceId);
            }
            for (VolumeVO vol : vols) {
                if (vol.getDeviceId().equals(deviceId)) {
                    throw new RuntimeException(String.format("deviceId %d is used by vol %s on vm %s", deviceId, vol, vm));
                }
            }
        } else {
            // allocate deviceId here
            List<String> devIds = new ArrayList<String>();
            for (int i = 1; i <= maxDeviceId; i++) {
                devIds.add(String.valueOf(i));
            }
            devIds.remove(String.valueOf(CDROM_DEVICE_ID));
            for (VolumeVO vol : vols) {
                devIds.remove(vol.getDeviceId().toString().trim());
            }
            if (devIds.isEmpty()) {
                throw new RuntimeException(String.format("All device Ids are used by vm %s", vm));
            }
            deviceId = Long.parseLong(devIds.iterator().next());
        }

        return deviceId;
    }

    /**
     * Lazily fetch & cache the list of hypervisor types that have a
     * {@code default} entry in {@code hypervisor_capabilities}. This
     * mirrors the field that {@link VolumeApiServiceImpl#configure}
     * used to seed eagerly.
     */
    private List<HypervisorType> getSupportingDefaultHv() {
        List<HypervisorType> local = supportingDefaultHV;
        if (local == null) {
            local = hypervisorCapabilitiesDao.getHypervisorsWithDefaultEntries();
            supportingDefaultHV = local;
        }
        return local;
    }
}
