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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.storage.datastore.db.PrimaryDataStoreDao;
import org.apache.cloudstack.storage.datastore.db.StoragePoolVO;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.event.UsageEventVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.ScopeType;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.Volume.Type;
import com.cloud.storage.VolumeApiServiceImpl;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.utils.StringUtils;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;

/**
 * Service-offering upgrade persistence — extracted from
 * {@link VirtualMachineManagerImpl}.
 *
 * @see VmServiceOfferingUpgradeManager
 */
@Component
public class VmServiceOfferingUpgradeManagerImpl implements VmServiceOfferingUpgradeManager {

    private static final Logger logger = LogManager.getLogger(VmServiceOfferingUpgradeManagerImpl.class);

    @Inject
    private VolumeDao volumeDao;
    @Inject
    private PrimaryDataStoreDao storagePoolDao;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private VMInstanceDetailsDao vmInstanceDetailsDao;
    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private DiskOfferingDao diskOfferingDao;
    @Inject
    private EntityManager entityMgr;
    @Inject
    private UserVmManager userVmManager;

    @Override
    public void checkIfCanUpgrade(final VirtualMachine vmInstance, final ServiceOffering newServiceOffering) {
        if (newServiceOffering == null) {
            throw new InvalidParameterValueException("Invalid parameter, newServiceOffering can't be null");
        }

        if (ServiceOffering.State.Inactive.equals(newServiceOffering.getState())) {
            throw new InvalidParameterValueException(String.format("New service offering is inactive: [%s].", newServiceOffering.getUuid()));
        }

        if (!(vmInstance.getState().equals(State.Stopped) || vmInstance.getState().equals(State.Running))) {
            logger.warn("Unable to upgrade virtual machine {} in state {}", vmInstance.toString(), vmInstance.getState());
            throw new InvalidParameterValueException("Unable to upgrade virtual machine " + vmInstance.toString() + " " + " in state " +
                    vmInstance.getState() + "; make sure the virtual machine is stopped/running");
        }

        if (!newServiceOffering.isDynamic() && vmInstance.getServiceOfferingId() == newServiceOffering.getId()) {
            logger.info("Not upgrading vm {} since it already has the requested service offering ({})", vmInstance.toString(), newServiceOffering.getName());

            throw new InvalidParameterValueException("Not upgrading vm " + vmInstance.toString() + " since it already " +
                    "has the requested service offering (" + newServiceOffering.getName() + ")");
        }

        final ServiceOfferingVO currentServiceOffering = serviceOfferingDao.findByIdIncludingRemoved(vmInstance.getId(), vmInstance.getServiceOfferingId());
        final DiskOfferingVO currentDiskOffering = diskOfferingDao.findByIdIncludingRemoved(currentServiceOffering.getDiskOfferingId());
        final DiskOfferingVO newDiskOffering = diskOfferingDao.findById(newServiceOffering.getDiskOfferingId());

        checkIfNewOfferingStorageScopeMatchesStoragePool(vmInstance, newDiskOffering);

        if (currentServiceOffering.isSystemUse() != newServiceOffering.isSystemUse()) {
            throw new InvalidParameterValueException("isSystem property is different for current service offering and new service offering");
        }

        final List<String> currentTags = StringUtils.csvTagsToList(currentDiskOffering.getTags());
        final List<String> newTags = StringUtils.csvTagsToList(newDiskOffering.getTags());
        if (VolumeApiServiceImpl.MatchStoragePoolTagsWithDiskOffering.valueIn(vmInstance.getDataCenterId())) {
            if (!VolumeApiServiceImpl.doesNewDiskOfferingHasTagsAsOldDiskOffering(currentDiskOffering, newDiskOffering)) {
                throw new InvalidParameterValueException("Unable to upgrade virtual machine; the current service offering " +
                        " should have tags as subset of the new service offering tags. Current service offering tags: " + currentTags + "; " +
                        "new service offering tags: " + newTags);
            }
        }
    }

    @Override
    public void checkIfNewOfferingStorageScopeMatchesStoragePool(VirtualMachine vmInstance, DiskOffering newDiskOffering) {
        boolean isRootVolumeOnLocalStorage = isRootVolumeOnLocalStorage(vmInstance.getId());

        if (newDiskOffering.isUseLocalStorage() && !isRootVolumeOnLocalStorage) {
            String message = String.format("Unable to upgrade virtual machine %s, target offering use local storage but the storage pool where "
                    + "the volume is allocated is a shared storage.", vmInstance.toString());
            throw new InvalidParameterValueException(message);
        }

        if (!newDiskOffering.isUseLocalStorage() && isRootVolumeOnLocalStorage) {
            String message = String.format("Unable to upgrade virtual machine %s, target offering use shared storage but the storage pool where "
                    + "the volume is allocated is a local storage.", vmInstance.toString());
            throw new InvalidParameterValueException(message);
        }
    }

    @Override
    public boolean isRootVolumeOnLocalStorage(long vmId) {
        ScopeType poolScope = ScopeType.ZONE;
        List<VolumeVO> volumes = volumeDao.findByInstanceAndType(vmId, Type.ROOT);
        if (CollectionUtils.isNotEmpty(volumes)) {
            VolumeVO rootDisk = volumes.get(0);
            Long poolId = rootDisk.getPoolId();
            if (poolId != null) {
                StoragePoolVO storagePoolVO = storagePoolDao.findById(poolId);
                poolScope = storagePoolVO.getScope();
            }
        }
        return ScopeType.HOST == poolScope;
    }

    @Override
    public boolean upgradeVmDb(final long vmId, final ServiceOffering newServiceOffering,
                               ServiceOffering currentServiceOffering) {
        final VMInstanceVO vmForUpdate = vmInstanceDao.findById(vmId);
        vmForUpdate.setServiceOfferingId(newServiceOffering.getId());
        final ServiceOffering newSvcOff = entityMgr.findById(ServiceOffering.class, newServiceOffering.getId());
        vmForUpdate.setHaEnabled(newSvcOff.isOfferHA());
        vmForUpdate.setLimitCpuUse(newSvcOff.getLimitCpuUse());
        vmForUpdate.setServiceOfferingId(newSvcOff.getId());
        if (newServiceOffering.isDynamic()) {
            saveCustomOfferingDetails(vmId, newServiceOffering);
        }
        if (currentServiceOffering.isDynamic() && !newServiceOffering.isDynamic()) {
            removeCustomOfferingDetails(vmId);
        }
        VMTemplateVO template = templateDao.findByIdIncludingRemoved(vmForUpdate.getTemplateId());
        boolean dynamicScalingEnabled = userVmManager.checkIfDynamicScalingCanBeEnabled(vmForUpdate, newServiceOffering, template, vmForUpdate.getDataCenterId());
        vmForUpdate.setDynamicallyScalable(dynamicScalingEnabled);
        return vmInstanceDao.update(vmId, vmForUpdate);
    }

    @Override
    public void removeCustomOfferingDetails(long vmId) {
        Map<String, String> details = vmInstanceDetailsDao.listDetailsKeyPairs(vmId);
        details.remove(UsageEventVO.DynamicParameters.cpuNumber.name());
        details.remove(UsageEventVO.DynamicParameters.cpuSpeed.name());
        details.remove(UsageEventVO.DynamicParameters.memory.name());
        List<VMInstanceDetailVO> detailList = new ArrayList<>();
        for (Map.Entry<String, String> entry : details.entrySet()) {
            VMInstanceDetailVO detailVO = new VMInstanceDetailVO(vmId, entry.getKey(), entry.getValue(), true);
            detailList.add(detailVO);
        }
        vmInstanceDetailsDao.saveDetails(detailList);
    }

    @Override
    public void saveCustomOfferingDetails(long vmId, ServiceOffering serviceOffering) {
        Map<String, String> details = vmInstanceDetailsDao.listDetailsKeyPairs(vmId);

        // We need to restore only the customizable parameters. If we save a parameter that is not customizable and attempt
        // to restore a VM snapshot, com.cloud.vm.UserVmManagerImpl.validateCustomParameters will fail.
        ServiceOffering unfilledOffering = serviceOfferingDao.findByIdIncludingRemoved(serviceOffering.getId());
        if (unfilledOffering.getCpu() == null) {
            details.put(UsageEventVO.DynamicParameters.cpuNumber.name(), serviceOffering.getCpu().toString());
        }
        if (unfilledOffering.getSpeed() == null) {
            details.put(UsageEventVO.DynamicParameters.cpuSpeed.name(), serviceOffering.getSpeed().toString());
        }
        if (unfilledOffering.getRamSize() == null) {
            details.put(UsageEventVO.DynamicParameters.memory.name(), serviceOffering.getRamSize().toString());
        }

        List<VMInstanceDetailVO> detailList = new ArrayList<>();
        for (Map.Entry<String, String> entry : details.entrySet()) {
            VMInstanceDetailVO detailVO = new VMInstanceDetailVO(vmId, entry.getKey(), entry.getValue(), true);
            detailList.add(detailVO);
        }
        vmInstanceDetailsDao.saveDetails(detailList);
    }
}
