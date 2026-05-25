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
import java.util.LinkedHashMap;
import java.util.List;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.user.vm.DeployVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeDataFactory;
import org.apache.cloudstack.engine.subsystem.api.storage.VolumeInfo;
import org.apache.commons.collections.MapUtils;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenter;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.storage.ScopeType;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.dao.SnapshotDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.utils.db.EntityManager;

@Component
public class VmDeployRequestResolutionService {

    @Inject
    private AccountService accountService;
    @Inject
    private EntityManager entityMgr;
    @Inject
    private AccountManager accountMgr;
    @Inject
    private SnapshotDao snapshotDao;
    @Inject
    private VolumeDataFactory volFactory;
    @Inject
    private VmCreationValidator vmCreationValidator;
    @Inject
    private VmDeployAsIsNetworkMappingService vmDeployAsIsNetworkMappingService;

    public VmDeployRequestResolution resolve(DeployVMCmd cmd,
            VmDeployAsIsNetworkMappingService.DefaultNetworkProvider defaultNetworkProvider)
            throws InsufficientCapacityException, ResourceAllocationException {
        Account owner = accountService.getActiveAccountById(cmd.getEntityOwnerId());

        vmCreationValidator.verifyDetails(cmd.getDetails());

        Long zoneId = cmd.getZoneId();

        DataCenter zone = entityMgr.findById(DataCenter.class, zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Unable to find zone by id=" + zoneId);
        }

        Long serviceOfferingId = cmd.getServiceOfferingId();
        if (serviceOfferingId == null) {
            throw new InvalidParameterValueException("Unable to execute API command deployvirtualmachine due to missing parameter serviceofferingid");
        }
        Long overrideDiskOfferingId = cmd.getOverrideDiskOfferingId();

        ServiceOffering serviceOffering = entityMgr.findById(ServiceOffering.class, serviceOfferingId);
        if (serviceOffering == null) {
            throw new InvalidParameterValueException("Unable to find service offering: " + serviceOffering.getId());
        }
        vmCreationValidator.verifyServiceOffering(cmd, serviceOffering);

        Account caller = CallContext.current().getCallingAccount();

        Long templateId = cmd.getTemplateId();
        VolumeInfo volume = null;
        SnapshotVO snapshot = null;

        if (cmd.getVolumeId() != null) {
            volume = getVolume(cmd.getVolumeId(), templateId, false);
            if (volume == null) {
                throw new InvalidParameterValueException("Could not find volume with id=" + cmd.getVolumeId());
            }
            accountMgr.checkAccess(caller, null, true, volume);
            templateId = volume.getTemplateId();
        } else if (cmd.getSnapshotId() != null) {
            snapshot = snapshotDao.findById(cmd.getSnapshotId());
            if (snapshot == null) {
                throw new InvalidParameterValueException("Could not find snapshot with id=" + cmd.getSnapshotId());
            }
            accountMgr.checkAccess(caller, null, true, snapshot);
            VolumeInfo volumeOfSnapshot = getVolume(snapshot.getVolumeId(), templateId, true);
            templateId = volumeOfSnapshot.getTemplateId();
        }

        VirtualMachineTemplate template = null;
        if (volume != null || snapshot != null) {
            template = entityMgr.findByIdIncludingRemoved(VirtualMachineTemplate.class, templateId);
        } else {
            template = entityMgr.findById(VirtualMachineTemplate.class, templateId);
        }
        if (cmd.isVolumeOrSnapshotProvided() &&
                (!(HypervisorType.KVM.equals(template.getHypervisorType()) || HypervisorType.KVM.equals(cmd.getHypervisor())))) {
            throw new InvalidParameterValueException("Deploying a virtual machine with existing volume/snapshot is supported only from KVM hypervisors");
        }
        if (template == null) {
            throw new InvalidParameterValueException("Unable to use template " + templateId);
        }
        vmCreationValidator.verifyTemplate(cmd, template, serviceOfferingId);

        Long diskOfferingId = cmd.getDiskOfferingId();
        DiskOffering diskOffering = null;
        if (diskOfferingId != null) {
            diskOffering = entityMgr.findById(DiskOffering.class, diskOfferingId);
            if (diskOffering == null) {
                throw new InvalidParameterValueException("Unable to find disk offering " + diskOfferingId);
            }
            if (diskOffering.isComputeOnly()) {
                throw new InvalidParameterValueException(String.format("The disk offering %s provided is directly mapped to a service offering, please provide an individual disk offering", diskOffering));
            }
        }

        List<VmDiskInfo> dataDiskInfoList = cmd.getDataDiskInfoList();
        if (dataDiskInfoList != null && diskOfferingId != null) {
            new InvalidParameterValueException("Cannot specify both disk offering id and data disk offering details");
        }

        if (!zone.isLocalStorageEnabled()) {
            DiskOffering diskOfferingMappedInServiceOffering = entityMgr.findById(DiskOffering.class, serviceOffering.getDiskOfferingId());
            if (diskOfferingMappedInServiceOffering.isUseLocalStorage()) {
                throw new InvalidParameterValueException("Zone is not configured to use local storage but disk offering " + diskOfferingMappedInServiceOffering.getName() + " mapped in service offering uses it");
            }
            if (diskOffering != null && diskOffering.isUseLocalStorage()) {
                throw new InvalidParameterValueException("Zone is not configured to use local storage but disk offering " + diskOffering.getName() + " uses it");
            }
        }

        List<Long> networkIds = cmd.getNetworkIds();
        LinkedHashMap<Integer, Long> userVmNetworkMap = vmDeployAsIsNetworkMappingService.getDeployAsIsVmNetworkMapping(zone, owner, template, cmd.getVmNetworkMap(), defaultNetworkProvider);
        if (MapUtils.isNotEmpty(userVmNetworkMap)) {
            networkIds = new ArrayList<>(userVmNetworkMap.values());
        }

        return new VmDeployRequestResolution(zone, owner, serviceOffering, template, diskOfferingId, cmd.getSize(),
                overrideDiskOfferingId, dataDiskInfoList, networkIds, cmd.getIpToNetworkMap(), volume, snapshot);
    }

    private VolumeInfo getVolume(long id, Long templateId, boolean isSnapshot) {
        VolumeInfo volume = volFactory.getVolume(id);
        if (volume != null) {
            if (volume.getDataStore() == null || !ScopeType.ZONE.equals(volume.getDataStore().getScope().getScopeType())) {
                throw new InvalidParameterValueException("Deployment of virtual machine is supported only for Zone-wide storage pools");
            }
            checkIfVolumeTemplateIsTheSameAsTheProvided(volume, templateId);
            if (volume.getInstanceId() != null && !isSnapshot) {
                throw new InvalidParameterValueException(String.format("The volume %s is already attached to a VM %s", volume, volume.getInstanceId()));
            }
        }
        return volume;
    }

    private void checkIfVolumeTemplateIsTheSameAsTheProvided(VolumeInfo volume, Long templateId) {
        if (volume.getTemplateId() != null) {
            if (templateId != null && !volume.getTemplateId().equals(templateId)) {
                throw new InvalidParameterValueException(String.format("The volume's template %s is not the same as the provided one %s", volume.getTemplateId(), templateId));
            }
        } else {
            throw new InvalidParameterValueException("The provided volume/snapshot doesn't have a template to deploy a VM");
        }
    }
}
