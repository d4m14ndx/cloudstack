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

import static com.cloud.hypervisor.Hypervisor.HypervisorType.Functionality;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.context.CallContext;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.dao.ClusterDao;
import com.cloud.dc.dao.DedicatedResourceDao;
import com.cloud.dc.DedicatedResourceVO;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.host.Host;
import com.cloud.host.HostVO;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.offering.ServiceOffering;
import com.cloud.resource.ResourceState;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.StoragePool;
import com.cloud.storage.StorageManager;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.storage.dao.VolumeDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

/**
 * VM migration / storage migration pre-flight validation —
 * extracted from {@link UserVmManagerImpl}.
 *
 * @see VmMigrationValidator
 */
@Component
public class VmMigrationValidatorImpl implements VmMigrationValidator {
    private static final Logger LOG = LogManager.getLogger(VmMigrationValidatorImpl.class);

    @Inject
    private HostDao hostDao;
    @Inject
    private ClusterDao clusterDao;
    @Inject
    private StorageManager storageManager;
    @Inject
    private ServiceOfferingDao serviceOfferingDao;
    @Inject
    private VMTemplateDao templateDao;
    @Inject
    private DedicatedResourceDao dedicatedDao;
    @Inject
    private AccountManager accountManager;
    @Inject
    private VMInstanceDao vmInstanceDao;
    @Inject
    private VolumeDao volumeDao;
    @Inject
    private VMSnapshotDao vmSnapshotDao;

    @Override
    public VMInstanceVO preVmStorageMigrationCheck(Long vmId) {
        Account caller = CallContext.current().getCallingAccount();
        if (!accountManager.isRootAdmin(caller.getId())) {
            LOG.debug("Caller is not a root admin, permission denied to migrate the VM");
            throw new PermissionDeniedException("No permission to migrate VM, Only Root Admin can migrate a VM!");
        }

        VMInstanceVO vm = vmInstanceDao.findById(vmId);
        if (vm == null) {
            throw new InvalidParameterValueException("Unable to find the VM by id=" + vmId);
        }

        if (vm.getState() != State.Stopped) {
            InvalidParameterValueException ex = new InvalidParameterValueException("VM is not Stopped, unable to migrate the vm having the specified id");
            ex.addProxyObject(vm.getUuid(), "vmId");
            throw ex;
        }

        HypervisorType hypervisorType = vm.getHypervisorType();
        List<HypervisorType> supportedHypervisorsForNonUserVMStorageMigration = HypervisorType.getListOfHypervisorsSupportingFunctionality(Functionality.VmStorageMigration)
                .stream().filter(hypervisor -> !hypervisor.equals(HypervisorType.XenServer)).collect(Collectors.toList());
        if (vm.getType() != VirtualMachine.Type.User && !supportedHypervisorsForNonUserVMStorageMigration.contains(hypervisorType)) {
            throw new InvalidParameterValueException(String.format(
                    "Unable to migrate storage of non-user VMs for hypervisor [%s]. Operation only supported for the following hypervisors: [%s].",
                    hypervisorType, supportedHypervisorsForNonUserVMStorageMigration));
        }

        List<VolumeVO> vols = volumeDao.findByInstance(vm.getId());
        if (vols.size() > 1
                && !(HypervisorType.VMware.equals(hypervisorType) || HypervisorType.KVM.equals(hypervisorType))) {
            throw new InvalidParameterValueException("Data disks attached to the vm, can not migrate. Need to detach data disks first");
        }

        if (vmSnapshotDao.findByVm(vmId).size() > 0) {
            throw new InvalidParameterValueException("Instance's disk cannot be migrated, please remove all the Instance Snapshots for this Instance");
        }

        return vm;
    }

    @Override
    public void checkIfDestinationPoolHasSameStorageAccessGroups(StoragePool destPool, VMInstanceVO vm) {
        Long hostId = vm.getHostId();
        if (hostId != null) {
            Host host = hostDao.findById(hostId);
            if (!storageManager.checkIfHostAndStoragePoolHasCommonStorageAccessGroups(host, destPool)) {
                throw new InvalidParameterValueException(String.format("Destination pool %s does not have matching storage access groups as host %s", destPool.getName(), host.getName()));
            }
        }
    }

    @Override
    public void checkDestinationHypervisorType(StoragePool destPool, VMInstanceVO vm) {
        HypervisorType destHypervisorType = destPool.getHypervisor();
        if (destHypervisorType == null) {
            destHypervisorType = clusterDao.findById(destPool.getClusterId()).getHypervisorType();
        }

        if (vm.getHypervisorType() != destHypervisorType && destHypervisorType != HypervisorType.Any) {
            throw new InvalidParameterValueException("hypervisor is not compatible: dest: " + destHypervisorType.toString() + ", vm: " + vm.getHypervisorType().toString());
        }
    }

    @Override
    public void validateStorageAccessGroupsOnHosts(Host srcHost, Host destinationHost) {
        String[] storageAccessGroupsOnSrcHost = storageManager.getStorageAccessGroups(null, null, null, srcHost.getId());
        String[] storageAccessGroupsOnDestHost = storageManager.getStorageAccessGroups(null, null, null, destinationHost.getId());

        List<String> srcHostStorageAccessGroupsList = storageAccessGroupsOnSrcHost != null ? Arrays.asList(storageAccessGroupsOnSrcHost) : Collections.emptyList();
        List<String> destHostStorageAccessGroupsList = storageAccessGroupsOnDestHost != null ? Arrays.asList(storageAccessGroupsOnDestHost) : Collections.emptyList();

        if (CollectionUtils.isEmpty(srcHostStorageAccessGroupsList)) {
            return;
        }

        if (CollectionUtils.isEmpty(destHostStorageAccessGroupsList)) {
            throw new CloudRuntimeException("Source host has storage access groups, but destination host has none.");
        }

        if (!destHostStorageAccessGroupsList.containsAll(srcHostStorageAccessGroupsList)) {
            throw new CloudRuntimeException("Storage access groups on the source and destination hosts did not match.");
        }
    }

    @Override
    public boolean checkEnforceStrictHostTagCheck(VMInstanceVO vm, HostVO host) {
        ServiceOffering serviceOffering = serviceOfferingDao.findByIdIncludingRemoved(vm.getServiceOfferingId());
        VirtualMachineTemplate template = templateDao.findByIdIncludingRemoved(vm.getTemplateId());
        return checkEnforceStrictHostTagCheck(host, serviceOffering, template);
    }

    @Override
    public boolean checkEnforceStrictHostTagCheck(HostVO host, ServiceOffering serviceOffering, VirtualMachineTemplate template) {
        Set<String> strictHostTags = UserVmManager.getStrictHostTags();
        return host.checkHostServiceOfferingAndTemplateTags(serviceOffering, template, strictHostTags);
    }

    @Override
    public void validateStrictHostTagCheck(VMInstanceVO vm, HostVO host) {
        ServiceOffering serviceOffering = serviceOfferingDao.findByIdIncludingRemoved(vm.getServiceOfferingId());
        VirtualMachineTemplate template = templateDao.findByIdIncludingRemoved(vm.getTemplateId());

        if (!checkEnforceStrictHostTagCheck(host, serviceOffering, template)) {
            Set<String> missingTags = host.getHostServiceOfferingAndTemplateMissingTags(serviceOffering, template, UserVmManager.getStrictHostTags());
            LOG.error("Cannot deploy VM: {} to host : {} due to tag mismatch. host tags: {}, " +
                            "strict host tags: {} serviceOffering tags: {}, template tags: {}, missing tags: {}",
                    vm, host, host.getHostTags(), UserVmManager.getStrictHostTags(), serviceOffering.getHostTag(), template.getTemplateTag(), missingTags);
            throw new InvalidParameterValueException(String.format("Cannot deploy VM, destination host: %s " +
                    "is not compatible for the VM", host.getName()));
        }
    }

    @Override
    public boolean checkIfHostIsDedicated(HostVO host) {
        long hostId = host.getId();
        DedicatedResourceVO dedicatedHost = dedicatedDao.findByHostId(hostId);
        DedicatedResourceVO dedicatedClusterOfHost = dedicatedDao.findByClusterId(host.getClusterId());
        DedicatedResourceVO dedicatedPodOfHost = dedicatedDao.findByPodId(host.getPodId());
        return dedicatedHost != null || dedicatedClusterOfHost != null || dedicatedPodOfHost != null;
    }

    @Override
    public void checkIfHostOfVMIsInPrepareForMaintenanceState(VirtualMachine vm, String operation) {
        long hostId = vm.getHostId();
        HostVO host = hostDao.findById(hostId);
        if (host.getResourceState() != ResourceState.PrepareForMaintenance) {
            return;
        }

        LOG.debug("Host is in PrepareForMaintenance state - {} VM operation on the VM: {} is not allowed", operation, vm);
        throw new InvalidParameterValueException(String.format("%s VM operation on the VM: %s is not allowed as host is preparing for maintenance mode", operation, vm));
    }

    @Override
    public boolean isOnSupportedHypevisorForMigration(VMInstanceVO vm) {
        return vm.getHypervisorType().equals(HypervisorType.XenServer)
                || vm.getHypervisorType().equals(HypervisorType.VMware)
                || vm.getHypervisorType().equals(HypervisorType.KVM)
                || vm.getHypervisorType().equals(HypervisorType.Hyperv)
                || vm.getHypervisorType().equals(HypervisorType.LXC)
                || vm.getHypervisorType().equals(HypervisorType.Simulator);
    }
}
