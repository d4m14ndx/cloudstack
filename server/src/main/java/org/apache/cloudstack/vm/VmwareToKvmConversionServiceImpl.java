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
package org.apache.cloudstack.vm;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStore;
import org.apache.cloudstack.engine.subsystem.api.storage.DataStoreManager;
import org.apache.cloudstack.storage.datastore.db.ImageStoreDao;
import org.apache.cloudstack.storage.datastore.db.ImageStoreVO;
import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.AgentManager;
import com.cloud.agent.api.Answer;
import com.cloud.agent.api.CheckConvertInstanceAnswer;
import com.cloud.agent.api.CheckConvertInstanceCommand;
import com.cloud.agent.api.ConvertInstanceAnswer;
import com.cloud.agent.api.ConvertInstanceCommand;
import com.cloud.agent.api.ImportConvertedInstanceAnswer;
import com.cloud.agent.api.ImportConvertedInstanceCommand;
import com.cloud.agent.api.to.DataStoreTO;
import com.cloud.agent.api.to.RemoteInstanceTO;
import com.cloud.exception.AgentUnavailableException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.OperationTimedoutException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.hypervisor.HypervisorGuru;
import com.cloud.hypervisor.HypervisorGuruManager;
import com.cloud.org.Cluster;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DataStoreRole;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.Storage;
import com.cloud.storage.VolumeApiService;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VmDetailConstants;

/**
 * VMware-to-KVM conversion helpers extracted from
 * {@link UnmanagedVMsManagerImpl}.
 *
 * @see VmwareToKvmConversionService
 */
@Component
public class VmwareToKvmConversionServiceImpl implements VmwareToKvmConversionService {
    protected Logger logger = LogManager.getLogger(VmwareToKvmConversionServiceImpl.class);

    private static final List<Storage.StoragePoolType> FORCE_CONVERT_TO_POOL_ALLOWED_TYPES =
            Arrays.asList(Storage.StoragePoolType.NetworkFilesystem, Storage.StoragePoolType.Filesystem,
                    Storage.StoragePoolType.SharedMountPoint);
    private static final String DETAIL_VDDK_TRANSPORTS = "vddk.transports";
    private static final String DETAIL_VDDK_THUMBPRINT = "vddk.thumbprint";

    @Inject
    private AgentManager agentManager;
    @Inject
    private HostDao hostDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private PrimaryDataStoreDao primaryDataStoreDao;
    @Inject
    private VolumeApiService volumeApiService;
    @Inject
    private HypervisorGuruManager hypervisorGuruManager;
    @Inject
    private ImageStoreDao imageStoreDao;
    @Inject
    private DataStoreManager dataStoreManager;

    @Override
    public void addServiceOfferingDetailsToParams(Map<String, String> params, ServiceOfferingVO serviceOffering) {
        if (serviceOffering == null) {
            return;
        }
        serviceOfferingDao.loadDetails(serviceOffering);
        Map<String, String> serviceOfferingDetails = serviceOffering.getDetails();

        if (serviceOffering.getCpu() != null) {
            params.put(VmDetailConstants.CPU_NUMBER, String.valueOf(serviceOffering.getCpu()));
        } else if (MapUtils.isNotEmpty(serviceOfferingDetails) && serviceOfferingDetails.containsKey(ApiConstants.MIN_CPU_NUMBER)) {
            params.put(VmDetailConstants.CPU_NUMBER, serviceOfferingDetails.get(ApiConstants.MIN_CPU_NUMBER));
        }

        if (serviceOffering.getSpeed() != null) {
            params.put(VmDetailConstants.CPU_SPEED, String.valueOf(serviceOffering.getSpeed()));
        }

        if (serviceOffering.getRamSize() != null) {
            params.put(VmDetailConstants.MEMORY, String.valueOf(serviceOffering.getRamSize()));
        } else if (MapUtils.isNotEmpty(serviceOfferingDetails) && serviceOfferingDetails.containsKey(ApiConstants.MIN_MEMORY)) {
            params.put(VmDetailConstants.MEMORY, serviceOfferingDetails.get(ApiConstants.MIN_MEMORY));
        }
    }

    @Override
    public void sanitizeConvertedInstance(UnmanagedInstanceTO convertedInstance, UnmanagedInstanceTO sourceVMwareInstance) {
        convertedInstance.setCpuCores(sourceVMwareInstance.getCpuCores());
        convertedInstance.setCpuSpeed(sourceVMwareInstance.getCpuSpeed());
        convertedInstance.setCpuCoresPerSocket(sourceVMwareInstance.getCpuCoresPerSocket());
        convertedInstance.setMemory(sourceVMwareInstance.getMemory());
        convertedInstance.setPowerState(UnmanagedInstanceTO.PowerState.PowerOff);
        List<UnmanagedInstanceTO.Disk> convertedInstanceDisks = convertedInstance.getDisks();
        List<UnmanagedInstanceTO.Disk> sourceVMwareInstanceDisks = sourceVMwareInstance.getDisks();
        for (int i = 0; i < convertedInstanceDisks.size(); i++) {
            UnmanagedInstanceTO.Disk disk = convertedInstanceDisks.get(i);
            disk.setDiskId(sourceVMwareInstanceDisks.get(i).getDiskId());
        }
        List<UnmanagedInstanceTO.Nic> convertedInstanceNics = convertedInstance.getNics();
        List<UnmanagedInstanceTO.Nic> sourceVMwareInstanceNics = sourceVMwareInstance.getNics();
        if (CollectionUtils.isEmpty(convertedInstanceNics) && CollectionUtils.isNotEmpty(sourceVMwareInstanceNics)) {
            for (UnmanagedInstanceTO.Nic nic : sourceVMwareInstanceNics) {
                // In case the NICs information is not parsed from the converted XML domain, use the cloned instance NICs with virtio adapter
                nic.setAdapterType("virtio");
            }
            convertedInstance.setNics(sourceVMwareInstanceNics);
            for (int i = 0; i < convertedInstanceNics.size(); i++) {
                UnmanagedInstanceTO.Nic nic = convertedInstanceNics.get(i);
                nic.setNicId(sourceVMwareInstanceNics.get(i).getNicId());
            }
        } else if (CollectionUtils.isNotEmpty(convertedInstanceNics) && CollectionUtils.isNotEmpty(sourceVMwareInstanceNics)
                && convertedInstanceNics.size() == sourceVMwareInstanceNics.size()) {
            for (int i = 0; i < convertedInstanceNics.size(); i++) {
                UnmanagedInstanceTO.Nic nic = convertedInstanceNics.get(i);
                nic.setNicId(sourceVMwareInstanceNics.get(i).getNicId());
                if (nic.getMacAddress() == null) {
                    nic.setMacAddress(sourceVMwareInstanceNics.get(i).getMacAddress());
                }
            }
        }
    }

    @Override
    public Map<String, String> createParamsForRemoveClonedInstance(String vcenter, String datacenterName, String username,
                                                                   String password, String sourceVM) {
        Map<String, String> params = new HashMap<>();
        params.put(VmDetailConstants.VMWARE_VCENTER_HOST, vcenter);
        params.put(VmDetailConstants.VMWARE_DATACENTER_NAME, datacenterName);
        params.put(VmDetailConstants.VMWARE_VCENTER_USERNAME, username);
        params.put(VmDetailConstants.VMWARE_VCENTER_PASSWORD, password);
        return params;
    }

    @Override
    public Map<String, String> createParamsForTemplateFromVmwareVmMigration(String vcenterHost, String datacenterName,
                                                                            String username, String password,
                                                                            String clusterName, String sourceHostName,
                                                                            String sourceVMName) {
        Map<String, String> params = new HashMap<>();
        params.put(VmDetailConstants.VMWARE_VCENTER_HOST, vcenterHost);
        params.put(VmDetailConstants.VMWARE_DATACENTER_NAME, datacenterName);
        params.put(VmDetailConstants.VMWARE_VCENTER_USERNAME, username);
        params.put(VmDetailConstants.VMWARE_VCENTER_PASSWORD, password);
        params.put(VmDetailConstants.VMWARE_CLUSTER_NAME, clusterName);
        params.put(VmDetailConstants.VMWARE_HOST_NAME, sourceHostName);
        params.put(VmDetailConstants.VMWARE_VM_NAME, sourceVMName);
        return params;
    }

    @Override
    public void removeClonedInstance(String vcenter, String datacenterName, String username, String password,
                                     String sourceHostName, String clonedInstanceName, String sourceVM) {
        HypervisorGuru vmwareGuru = hypervisorGuruManager.getGuru(Hypervisor.HypervisorType.VMware);
        Map<String, String> params = createParamsForRemoveClonedInstance(vcenter, datacenterName, username, password, sourceVM);
        boolean result = vmwareGuru.removeClonedHypervisorVMOutOfBand(sourceHostName, clonedInstanceName, params);
        if (!result) {
            String msg = String.format("Could not properly remove the cloned instance %s from VMware datacenter %s:%s",
                    clonedInstanceName, vcenter, datacenterName);
            logger.warn(msg);
            return;
        }
        logger.debug(String.format("Removed the cloned instance %s from VMWare datacenter %s/%s",
                clonedInstanceName, vcenter, datacenterName));
    }

    @Override
    public void removeTemplate(DataStoreTO convertLocation, String ovfTemplateOnConvertLocation) {
        HypervisorGuru vmwareGuru = hypervisorGuruManager.getGuru(Hypervisor.HypervisorType.VMware);
        boolean result = vmwareGuru.removeVMTemplateOutOfBand(convertLocation, ovfTemplateOnConvertLocation);
        if (!result) {
            String msg = String.format("Could not remove the template file %s on datastore %s",
                    ovfTemplateOnConvertLocation, convertLocation.getUrl());
            logger.warn(msg);
            return;
        }
        logger.debug(String.format("Removed the template file %s on datastore %s",
                ovfTemplateOnConvertLocation, convertLocation.getUrl()));
    }

    @Override
    public void checkConversionStoragePool(Long convertStoragePoolId, boolean forceConvertToPool) {
        if (forceConvertToPool && convertStoragePoolId == null) {
            String msg = "The parameter forceconverttopool is set to true, but a primary storage pool has not been provided for conversion";
            logFailureAndThrowException(msg);
        }
        if (convertStoragePoolId != null) {
            StoragePoolVO selectedStoragePool = primaryDataStoreDao.findById(convertStoragePoolId);
            if (selectedStoragePool == null) {
                logFailureAndThrowException(String.format("Cannot find a storage pool with ID %s", convertStoragePoolId));
            }
            if (forceConvertToPool && !FORCE_CONVERT_TO_POOL_ALLOWED_TYPES.contains(selectedStoragePool.getPoolType())) {
                logFailureAndThrowException(String.format("The selected storage pool %s does not support direct conversion " +
                        "as its type %s", selectedStoragePool.getName(), selectedStoragePool.getPoolType().name()));
            }
        }
    }

    @Override
    public void validateSelectedConversionStoragePoolForVddk(boolean useVddk, Long convertStoragePoolId,
                                                             ServiceOfferingVO serviceOffering, Map<String, Long> dataDiskOfferingMap) {
        if (!useVddk || convertStoragePoolId == null) {
            return;
        }

        StoragePoolVO selectedStoragePool = primaryDataStoreDao.findById(convertStoragePoolId);
        if (selectedStoragePool == null) {
            return;
        }

        if (serviceOffering.getDiskOfferingId() != null) {
            DiskOfferingVO rootDiskOffering = diskOfferingDao.findById(serviceOffering.getDiskOfferingId());
            if (rootDiskOffering == null) {
                throw new InvalidParameterValueException(String.format("Cannot find disk offering with ID %s that belongs to the service offering %s",
                        serviceOffering.getDiskOfferingId(), serviceOffering.getName()));
            }
            if (!volumeApiService.doesStoragePoolSupportDiskOffering(selectedStoragePool, rootDiskOffering)) {
                throw new InvalidParameterValueException(String.format("The root disk offering '%s' is not supported by the selected conversion storage pool '%s'. " +
                        "When using VDDK, all selected disk offerings must be compatible with the conversion storage pool, as it will become the primary storage for the imported volumes.",
                        rootDiskOffering.getName(), selectedStoragePool.getName()));
            }
        }

        if (MapUtils.isNotEmpty(dataDiskOfferingMap)) {
            for (Long diskOfferingId : dataDiskOfferingMap.values()) {
                DiskOfferingVO diskOffering = diskOfferingDao.findById(diskOfferingId);
                if (diskOffering == null) {
                    throw new InvalidParameterValueException(String.format("Cannot find disk offering with ID %s", diskOfferingId));
                }
                if (!volumeApiService.doesStoragePoolSupportDiskOffering(selectedStoragePool, diskOffering)) {
                    throw new InvalidParameterValueException(String.format("The data disk offering '%s' is not supported by the selected conversion storage pool '%s'. " +
                            "When using VDDK, all selected disk offerings must be compatible with the conversion storage pool, as it will become the primary storage for the imported volumes.",
                            diskOffering.getName(), selectedStoragePool.getName()));
                }
            }
        }
    }

    @Override
    public void applyVddkOverridesFromDetails(ConvertInstanceCommand cmd, Map<String, String> details) {
        if (MapUtils.isEmpty(details)) {
            return;
        }

        cmd.setVddkLibDir(StringUtils.trimToNull(details.get(Host.HOST_VDDK_LIB_DIR)));
        cmd.setVddkTransports(StringUtils.trimToNull(details.get(DETAIL_VDDK_TRANSPORTS)));
        cmd.setVddkThumbprint(StringUtils.trimToNull(details.get(DETAIL_VDDK_THUMBPRINT)));
    }

    @Override
    public StoragePoolVO getStoragePoolWithTags(List<StoragePoolVO> pools, String tags) {
        if (StringUtils.isEmpty(tags)) {
            return pools.get(0);
        }
        for (StoragePoolVO pool : pools) {
            if (volumeApiService.doesStoragePoolSupportDiskOfferingTags(pool, tags)) {
                return pool;
            }
        }
        return null;
    }

    @Override
    public List<String> selectInstanceConversionStoragePools(List<StoragePoolVO> pools, List<UnmanagedInstanceTO.Disk> disks,
                                                             ServiceOfferingVO serviceOffering, Map<String, Long> dataDiskOfferingMap) {
        List<String> storagePools = new ArrayList<>(disks.size());
        Set<String> dataDiskIds = dataDiskOfferingMap.keySet();
        for (UnmanagedInstanceTO.Disk disk : disks) {
            Long diskOfferingId = null;
            if (dataDiskIds.contains(disk.getDiskId())) {
                diskOfferingId = dataDiskOfferingMap.get(disk.getDiskId());
            } else {
                diskOfferingId = serviceOffering.getDiskOfferingId();
            }

            //TODO: Choose pools by capacity
            if (diskOfferingId == null) {
                storagePools.add(pools.get(0).getUuid());
            } else {
                DiskOfferingVO diskOffering = diskOfferingDao.findById(diskOfferingId);
                StoragePoolVO pool = getStoragePoolWithTags(pools, diskOffering.getTags());
                storagePools.add(pool.getUuid());
            }
        }
        return storagePools;
    }

    @Override
    public List<HostVO> filterHostsWithVddkSupport(List<HostVO> hosts) {
        return hosts.stream().filter(h -> {
            hostDao.loadDetails(h);
            return Boolean.parseBoolean(h.getDetail(Host.HOST_VDDK_SUPPORT));
        }).collect(Collectors.toList());
    }

    @Override
    public CheckConvertInstanceAnswer checkConversionSupportOnHost(HostVO convertHost, String sourceVM,
                                                                   boolean checkWindowsGuestConversionSupport,
                                                                   boolean useVddk, Map<String, String> details) {
        logger.debug(String.format("Checking the %s%s conversion support on the host %s",
                useVddk ? "VDDK " : "",
                checkWindowsGuestConversionSupport ? "windows guest " : "",
                convertHost));
        CheckConvertInstanceCommand cmd = new CheckConvertInstanceCommand(checkWindowsGuestConversionSupport, useVddk);
        if (MapUtils.isNotEmpty(details)) {
            cmd.setVddkLibDir(StringUtils.trimToNull(details.get(Host.HOST_VDDK_LIB_DIR)));
        }
        int timeoutSeconds = 60;
        cmd.setWait(timeoutSeconds);

        CheckConvertInstanceAnswer checkConvertInstanceAnswer;
        try {
            checkConvertInstanceAnswer = (CheckConvertInstanceAnswer) agentManager.send(convertHost.getId(), cmd);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            String err = String.format("Failed to check %s conversion support on the host %s for converting instance %s from VMware to KVM due to: %s",
                    checkWindowsGuestConversionSupport ? "windows guest" : "", convertHost, sourceVM, e.getMessage());
            logger.error(err);
            throw new CloudRuntimeException(err);
        }

        if (!checkConvertInstanceAnswer.getResult()) {
            String err = String.format("The host %s doesn't support conversion of instance %s from VMware to KVM due to: %s",
                    convertHost, sourceVM, checkConvertInstanceAnswer.getDetails());
            logger.error(err);
            throw new CloudRuntimeException(err);
        }

        return checkConvertInstanceAnswer;
    }

    @Override
    public UnmanagedInstanceTO convertAndImportToKVM(ConvertInstanceCommand convertInstanceCommand, HostVO convertHost,
                                                     HostVO importHost, String sourceVM, RemoteInstanceTO remoteInstanceTO,
                                                     List<String> destinationStoragePools, DataStoreTO temporaryConvertLocation,
                                                     boolean forceConvertToPool) {
        Answer convertAnswer;
        try {
            convertAnswer = agentManager.send(convertHost.getId(), convertInstanceCommand);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            String err = String.format("Could not send the convert instance command to host %s due to: %s",
                    convertHost, e.getMessage());
            logger.error(err, e);
            throw new CloudRuntimeException(err);
        }

        if (!convertAnswer.getResult()) {
            String err = String.format("The convert process failed for instance %s from VMware to KVM on host %s: %s",
                    sourceVM, convertHost, convertAnswer.getDetails());
            logger.error(err);
            throw new CloudRuntimeException(err);
        }

        Answer importAnswer;
        try {
            ImportConvertedInstanceCommand importCmd = new ImportConvertedInstanceCommand(
                    remoteInstanceTO, destinationStoragePools, temporaryConvertLocation,
                    ((ConvertInstanceAnswer) convertAnswer).getTemporaryConvertUuid(), forceConvertToPool);
            importAnswer = agentManager.send(importHost.getId(), importCmd);
        } catch (AgentUnavailableException | OperationTimedoutException e) {
            String err = String.format(
                    "Could not send the import converted instance command to host %s due to: %s",
                    importHost, e.getMessage());
            logger.error(err, e);
            throw new CloudRuntimeException(err);
        }

        if (!importAnswer.getResult()) {
            String err = String.format(
                    "The import process failed for instance %s from VMware to KVM on host %s: %s",
                    sourceVM, importHost, importAnswer.getDetails());
            logger.error(err);
            throw new CloudRuntimeException(err);
        }

        return ((ImportConvertedInstanceAnswer) importAnswer).getConvertedInstance();
    }

    @Override
    public List<StoragePoolVO> findInstanceConversionDestinationStoragePoolsInCluster(Cluster destinationCluster,
                                                                                      ServiceOfferingVO serviceOffering,
                                                                                      Map<String, Long> dataDiskOfferingMap,
                                                                                      DataStoreTO temporaryConvertLocation,
                                                                                      boolean forceConvertToPool) {
        List<StoragePoolVO> poolsList;
        if (!forceConvertToPool) {
            Set<StoragePoolVO> pools = new HashSet<>(primaryDataStoreDao.findClusterWideStoragePoolsByHypervisorAndPoolType(destinationCluster.getId(), Hypervisor.HypervisorType.KVM, Storage.StoragePoolType.NetworkFilesystem));
            pools.addAll(primaryDataStoreDao.findZoneWideStoragePoolsByHypervisorAndPoolType(destinationCluster.getDataCenterId(), Hypervisor.HypervisorType.KVM, Storage.StoragePoolType.NetworkFilesystem));
            if (pools.isEmpty()) {
                String msg = String.format("Cannot find suitable storage pools in the cluster %s for the conversion", destinationCluster.getName());
                logger.error(msg);
                throw new CloudRuntimeException(msg);
            }
            poolsList = new ArrayList<>(pools);
        } else {
            DataStore dataStore = dataStoreManager.getDataStore(temporaryConvertLocation.getUuid(), temporaryConvertLocation.getRole());
            poolsList = Collections.singletonList(primaryDataStoreDao.findById(dataStore.getId()));
        }

        if (serviceOffering.getDiskOfferingId() != null) {
            DiskOfferingVO diskOffering = diskOfferingDao.findById(serviceOffering.getDiskOfferingId());
            if (diskOffering == null) {
                String msg = String.format("Cannot find disk offering with ID %s that belongs to the service offering %s", serviceOffering.getDiskOfferingId(), serviceOffering.getName());
                logger.error(msg);
                throw new CloudRuntimeException(msg);
            }
            if (getStoragePoolWithTags(poolsList, diskOffering.getTags()) == null) {
                String msg = String.format("Cannot find suitable storage pool for disk offering %s that belongs to the service offering %s", diskOffering.getName(), serviceOffering.getName());
                logger.error(msg);
                throw new CloudRuntimeException(msg);
            }
        }
        for (Long diskOfferingId : dataDiskOfferingMap.values()) {
            DiskOfferingVO diskOffering = diskOfferingDao.findById(diskOfferingId);
            if (diskOffering == null) {
                String msg = String.format("Cannot find disk offering with ID %s", diskOfferingId);
                logger.error(msg);
                throw new CloudRuntimeException(msg);
            }
            if (getStoragePoolWithTags(poolsList, diskOffering.getTags()) == null) {
                String msg = String.format("Cannot find suitable storage pool for disk offering %s", diskOffering.getName());
                logger.error(msg);
                throw new CloudRuntimeException(msg);
            }
        }

        return poolsList;
    }

    @Override
    public DataStoreTO selectInstanceConversionTemporaryLocation(Cluster destinationCluster,
                                                                 HostVO convertHost, HostVO importHost,
                                                                 Long convertStoragePoolId, boolean forceConvertToPool) {
        if (convertStoragePoolId == null) {
            String msg = String.format("No convert storage pool has been provided, " +
                    "selecting an NFS secondary storage pool from the destination cluster (%s) zone", destinationCluster.getName());
            logger.debug(msg);
            return getImageStoreOnDestinationZoneForTemporaryConversion(destinationCluster, forceConvertToPool);
        }

        StoragePoolVO selectedStoragePool = primaryDataStoreDao.findById(convertStoragePoolId);
        checkBeforeSelectingTemporaryConversionStoragePool(selectedStoragePool, convertStoragePoolId, destinationCluster, convertHost);
        checkDestinationOrTemporaryStoragePoolForConversion(selectedStoragePool, forceConvertToPool, convertHost, importHost);

        return dataStoreManager.getPrimaryDataStore(convertStoragePoolId).getTO();
    }

    protected void checkBeforeSelectingTemporaryConversionStoragePool(StoragePoolVO selectedStoragePool, Long convertStoragePoolId, Cluster destinationCluster, HostVO convertHost) {
        if (selectedStoragePool == null) {
            logFailureAndThrowException(String.format("Cannot find a storage pool with ID %s", convertStoragePoolId));
        }
        if ((selectedStoragePool.getScope() == ScopeType.CLUSTER && selectedStoragePool.getClusterId() != destinationCluster.getId()) ||
                (selectedStoragePool.getScope() == ScopeType.ZONE && selectedStoragePool.getDataCenterId() != destinationCluster.getDataCenterId())) {
            logFailureAndThrowException(String.format("Cannot use the storage pool %s for the instance conversion as " +
                    "it is not in the scope of the cluster %s", selectedStoragePool.getName(), destinationCluster.getName()));
        }
        if (convertHost != null && selectedStoragePool.getScope() == ScopeType.CLUSTER && !selectedStoragePool.getClusterId().equals(convertHost.getClusterId())) {
            logFailureAndThrowException(String.format("Cannot use the storage pool %s for the instance conversion as " +
                    "the host %s for conversion is in a different cluster", selectedStoragePool.getName(), convertHost.getName()));
        }
    }

    protected DataStoreTO getImageStoreOnDestinationZoneForTemporaryConversion(Cluster destinationCluster, boolean forceConvertToPool) {
        if (forceConvertToPool) {
            logFailureAndThrowException("Please select a primary storage pool when the parameter forceconverttopool is set to true");
        }
        long zoneId = destinationCluster.getDataCenterId();
        ImageStoreVO imageStore = imageStoreDao.findOneByZoneAndProtocol(zoneId, "nfs");
        if (imageStore == null) {
            logFailureAndThrowException(String.format("Could not find an NFS secondary storage pool on zone %s to use as a temporary location " +
                    "for instance conversion", zoneId));
        }
        DataStore dataStore = dataStoreManager.getDataStore(imageStore.getId(), DataStoreRole.Image);
        return dataStore.getTO();
    }

    protected void checkDestinationOrTemporaryStoragePoolForConversion(StoragePoolVO selectedStoragePool, boolean forceConvertToPool, HostVO convertHost, HostVO importHost) {
        if (selectedStoragePool.getScope() == ScopeType.HOST && (ObjectUtils.anyNull(convertHost, importHost) ||
                ObjectUtils.allNotNull(convertHost, importHost) && convertHost.getId() != importHost.getId() ||
                !forceConvertToPool)) {
            logFailureAndThrowException("Please select the same host as convert and importing host and " +
                    "set forceconvertopool to true to use a local storage pool for conversion");
        }
        if (!forceConvertToPool && selectedStoragePool.getPoolType() != Storage.StoragePoolType.NetworkFilesystem) {
            logFailureAndThrowException(String.format("The storage pool %s is not supported for temporary conversion location," +
                    "only NFS storage pools are supported when forceconverttopool is set to false", selectedStoragePool.getName()));
        }
    }

    protected void logFailureAndThrowException(String msg) {
        logger.error(msg);
        throw new CloudRuntimeException(msg);
    }
}
