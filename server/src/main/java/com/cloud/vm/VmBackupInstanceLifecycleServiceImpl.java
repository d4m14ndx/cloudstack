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

import static org.apache.cloudstack.api.ApiConstants.MAX_IOPS;
import static org.apache.cloudstack.api.ApiConstants.MIN_IOPS;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.vm.CreateVMFromBackupCmdByAdmin;
import org.apache.cloudstack.api.command.user.vm.CreateVMFromBackupCmd;
import org.apache.cloudstack.backup.BackupManager;
import org.apache.cloudstack.backup.BackupVO;
import org.apache.cloudstack.backup.dao.BackupDao;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.agent.api.to.deployasis.OVFNetworkTO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deployasis.dao.TemplateDeployAsIsDetailsDao;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.Network.IpAddresses;
import com.cloud.network.NetworkModel;
import com.cloud.offering.DiskOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.storage.dao.DiskOfferingDao;
import com.cloud.storage.dao.VMTemplateDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountService;
import com.cloud.uservm.UserVm;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDetailsDao;
import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

@Component
public class VmBackupInstanceLifecycleServiceImpl implements VmBackupInstanceLifecycleService {

    private static final long GiB_TO_BYTES = 1024 * 1024 * 1024;

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    protected BackupDao backupDao;
    @Inject
    protected BackupManager backupManager;
    @Inject
    protected DataCenterDao _dcDao;
    @Inject
    protected UserVmDao _vmDao;
    @Inject
    protected ServiceOfferingDao serviceOfferingDao;
    @Inject
    protected VMTemplateDao _templateDao;
    @Inject
    protected DiskOfferingDao _diskOfferingDao;
    @Inject
    protected AccountService _accountService;
    @Inject
    protected VMInstanceDetailsDao vmInstanceDetailsDao;
    @Inject
    protected TemplateDeployAsIsDetailsDao templateDeployAsIsDetailsDao;
    @Inject
    protected NetworkModel _networkModel;

    private void updateDetailsWithRootDiskAttributes(Map<String, String> details, VmDiskInfo rootVmDiskInfo) {
        details.put(VmDetailConstants.ROOT_DISK_SIZE, rootVmDiskInfo.getSize().toString());
        if (rootVmDiskInfo.getMinIops() != null) {
            details.put(MIN_IOPS, rootVmDiskInfo.getMinIops().toString());
        }
        if (rootVmDiskInfo.getMaxIops() != null) {
            details.put(MAX_IOPS, rootVmDiskInfo.getMaxIops().toString());
        }
    }

    private void checkRootDiskSizeAgainstBackup(Long instanceVolumeSize, DiskOffering rootDiskOffering, Long backupVolumeSize) {
        Long instanceRootDiskSize = rootDiskOffering.isCustomized() ? instanceVolumeSize : rootDiskOffering.getDiskSize() / GiB_TO_BYTES;
        if (instanceRootDiskSize < backupVolumeSize) {
            throw new InvalidParameterValueException(
                    String.format("Instance volume root disk size %d[GiB] cannot be less than the backed-up volume size %d[GiB].",
                            instanceVolumeSize, backupVolumeSize));
        }
    }

    @Override
    public UserVm allocateVMFromBackup(CreateVMFromBackupCmd cmd, ManagerOperations managerOperations)
            throws InsufficientCapacityException, ResourceAllocationException, ResourceUnavailableException {
        BackupVO backup = backupDao.findById(cmd.getBackupId());
        if (backup == null) {
            throw new InvalidParameterValueException("Backup " + cmd.getBackupId() + " does not exist");
        }
        backupManager.validateBackupForZone(backup.getZoneId());

        if (!backupManager.canCreateInstanceFromBackup(cmd.getBackupId())) {
            throw new CloudRuntimeException("Create instance from backup is not supported for this provider.");
        }

        DataCenter targetZone = _dcDao.findById(cmd.getZoneId());
        if (targetZone == null) {
            throw new InvalidParameterValueException("Unable to find zone by id=" + cmd.getZoneId());
        }

        if (cmd.getZoneId() != backup.getZoneId() &&
            !backupManager.canCreateInstanceFromBackupAcrossZones(cmd.getBackupId())) {
            throw new CloudRuntimeException("Create Instance from Backup on another Zone is not supported by this provider or the Backup Repository.");
        }

        backupDao.loadDetails(backup);
        managerOperations.verifyDetails(cmd.getDetails());

        UserVmVO backupVm = _vmDao.findByIdIncludingRemoved(backup.getVmId());
        HypervisorType hypervisorType = backupVm.getHypervisorType();

        Long serviceOfferingId = cmd.getServiceOfferingId();
        ServiceOffering serviceOffering;
        if (serviceOfferingId != null) {
            serviceOffering = serviceOfferingDao.findById(serviceOfferingId);
            if (serviceOffering == null) {
                throw new InvalidParameterValueException("Unable to find service offering: " + serviceOffering.getId());
            }
        } else {
            String serviceOfferingUuid = backup.getDetail(ApiConstants.SERVICE_OFFERING_ID);
            if (serviceOfferingUuid == null) {
                throw new CloudRuntimeException("Backup doesn't contain a Service Offering UUID. Please specify a valid Service Offering while creating the Instance");
            }
            serviceOffering = serviceOfferingDao.findByUuid(serviceOfferingUuid);
            if (serviceOffering == null) {
                throw new CloudRuntimeException("Unable to find Service Offering with the UUID stored in the Backup. Please specify a valid Service Offering while creating the Instance");
            }
        }
        managerOperations.verifyServiceOffering(cmd, serviceOffering);

        VirtualMachineTemplate template;
        if (cmd.getTemplateId() != null) {
            Long templateId = cmd.getTemplateId();
            template = _templateDao.findById(templateId);
            if (template == null) {
                throw new InvalidParameterValueException("Unable to use template " + templateId);
            }
        } else {
            String templateUuid = backup.getDetail(ApiConstants.TEMPLATE_ID);
            if (templateUuid == null) {
                throw new CloudRuntimeException("Backup doesn't contain a Template UUID. Please specify a valid Template/ISO while creating the Instance");
            }
            template = _templateDao.findByUuid(templateUuid);
            if (template == null) {
                throw new CloudRuntimeException("Unable to find Template with the UUID stored in the Backup. Please specify a valid Template/ISO while creating the Instance");
            }
        }
        managerOperations.verifyTemplate(cmd, template, serviceOffering.getId());

        Long size = cmd.getSize();

        Long diskOfferingId = cmd.getDiskOfferingId();
        Boolean isIso = template.getFormat().equals(ImageFormat.ISO);
        if (diskOfferingId != null) {
            if (!isIso) {
                throw new InvalidParameterValueException(ApiConstants.DISK_OFFERING_ID + " parameter is supported for creating instance from backup only for ISO. For creating VMs with templates, please use the parameter " + ApiConstants.DATADISKS_DETAILS);
            }
            DiskOffering diskOffering = _diskOfferingDao.findById(diskOfferingId);
            if (diskOffering == null) {
                throw new InvalidParameterValueException("Unable to find disk offering " + diskOfferingId);
            }
            if (diskOffering.isComputeOnly()) {
                throw new InvalidParameterValueException(String.format("The disk offering %s provided is directly mapped to a service offering, please provide an individual disk offering", diskOffering));
            }
        }

        Long overrideDiskOfferingId = cmd.getOverrideDiskOfferingId();

        VmDiskInfo rootVmDiskInfoFromBackup = backupManager.getRootDiskInfoFromBackup(backup);

        if (isIso) {
            if (diskOfferingId == null) {
                diskOfferingId = rootVmDiskInfoFromBackup.getDiskOffering().getId();
                updateDetailsWithRootDiskAttributes(cmd.getDetails(), rootVmDiskInfoFromBackup);
                size = rootVmDiskInfoFromBackup.getSize();
            } else {
                DiskOffering rootDiskOffering = _diskOfferingDao.findById(diskOfferingId);
                checkRootDiskSizeAgainstBackup(size, rootDiskOffering, rootVmDiskInfoFromBackup.getSize());
            }
        } else {
            if (overrideDiskOfferingId == null) {
                overrideDiskOfferingId = serviceOffering.getDiskOfferingId();
                updateDetailsWithRootDiskAttributes(cmd.getDetails(), rootVmDiskInfoFromBackup);
            } else {
                DiskOffering overrideDiskOffering = _diskOfferingDao.findById(overrideDiskOfferingId);
                if (overrideDiskOffering.isComputeOnly()) {
                    updateDetailsWithRootDiskAttributes(cmd.getDetails(), rootVmDiskInfoFromBackup);
                } else {
                    String diskSizeFromDetails = cmd.getDetails().get(VmDetailConstants.ROOT_DISK_SIZE);
                    Long rootDiskSize = diskSizeFromDetails == null ? null : Long.parseLong(diskSizeFromDetails);
                    checkRootDiskSizeAgainstBackup(rootDiskSize, overrideDiskOffering, rootVmDiskInfoFromBackup.getSize());
                }
            }
        }

        List<VmDiskInfo> dataDiskInfoList = cmd.getDataDiskInfoList();
        if (dataDiskInfoList != null) {
            backupManager.checkVmDisksSizeAgainstBackup(dataDiskInfoList, backup);
        } else {
            dataDiskInfoList = backupManager.getDataDiskInfoListFromBackup(backup);
        }

        List<Long> networkIds = cmd.getNetworkIds();
        Account owner = _accountService.getActiveAccountById(cmd.getEntityOwnerId());
        LinkedHashMap<Integer, Long> userVmNetworkMap = getVmOvfNetworkMapping(targetZone, owner, template, cmd.getVmNetworkMap(), managerOperations);
        if (MapUtils.isNotEmpty(userVmNetworkMap)) {
            networkIds = new ArrayList<>(userVmNetworkMap.values());
        }

        Map<Long, IpAddresses> ipToNetworkMap = cmd.getIpToNetworkMap();
        if (networkIds == null && ipToNetworkMap == null) {
            networkIds = new ArrayList<>();
            ipToNetworkMap = backupManager.getIpToNetworkMapFromBackup(backup, cmd.getPreserveIp(), networkIds);
        }

        UserVm vm = managerOperations.createVirtualMachine(cmd, targetZone, owner, serviceOffering, template, hypervisorType, diskOfferingId, size,
                overrideDiskOfferingId, dataDiskInfoList, networkIds, ipToNetworkMap, null, null);

        String vmSettingsFromBackup = backup.getDetail(ApiConstants.VM_SETTINGS);
        if (vm != null && vmSettingsFromBackup != null) {
            UserVmVO vmVO = _vmDao.findById(vm.getId());
            Map<String, String> details = vmInstanceDetailsDao.listDetailsKeyPairs(vm.getId());
            vmVO.setDetails(details);

            Type type = new TypeToken<Map<String, String>>(){}.getType();
            Map<String, String> vmDetailsFromBackup = new Gson().fromJson(vmSettingsFromBackup, type);
            for (Entry<String, String> entry : vmDetailsFromBackup.entrySet()) {
                if (!details.containsKey(entry.getKey())) {
                    vmVO.setDetail(entry.getKey(), entry.getValue());
                }
            }
            _vmDao.saveDetails(vmVO);
        }

        return vm;
    }

    @Override
    public UserVm restoreVMFromBackup(CreateVMFromBackupCmd cmd, ManagerOperations managerOperations)
            throws ResourceUnavailableException, InsufficientCapacityException, ResourceAllocationException {
        long vmId = cmd.getEntityId();
        UserVm vm;
        Map<Long, DiskOffering> diskOfferingMap = cmd.getDataDiskTemplateToDiskOfferingMap();
        Map<VirtualMachineProfile.Param, Object> additonalParams = new HashMap<>();
        additonalParams.put(VirtualMachineProfile.Param.ReturnAfterVolumePrepare, true);

        try {
            Pair<UserVmVO, Map<VirtualMachineProfile.Param, Object>> vmParamPair = null;
            vmParamPair = managerOperations.startVirtualMachine(vmId, null, null, null, additonalParams, null);
            vm = vmParamPair.first();

            Long isoId = vm.getIsoId();
            if (isoId != null) {
                UserVmVO vmVO = _vmDao.findById(vmId);
                vmVO.setIsoId(null);
                _vmDao.update(vm.getId(), vmVO);
            }

            backupManager.restoreBackupToVM(cmd.getBackupId(), vmId);

        } catch (CloudRuntimeException | ResourceUnavailableException | ResourceAllocationException | InsufficientCapacityException e) {
            UserVmVO vmVO = _vmDao.findById(vmId);
            try {
                managerOperations.expunge(vmVO);
                logger.debug("Successfully cleaned up Instance {} after create Instance from backup failed", vmId);
            } catch (Exception cleanupException) {
                logger.debug("Failed to cleanup Instance {} after create Instance from backup failed", vmId, cleanupException);
            }
            throw e;
        }

        Account owner = _accountService.getActiveAccountById(cmd.getEntityOwnerId());
        UserVmVO userVm = _vmDao.findById(vmId);

        List<String> sshKeyPairNames = cmd.getSSHKeyPairNames();
        if (sshKeyPairNames != null && !sshKeyPairNames.isEmpty()) {
            vm = managerOperations.resetVMSSHKeyInternal(userVm, owner, sshKeyPairNames);
        }

        if (cmd.getStartVm()) {
            Long podId = null;
            Long clusterId = null;
            if (cmd instanceof CreateVMFromBackupCmdByAdmin) {
                CreateVMFromBackupCmdByAdmin adminCmd = (CreateVMFromBackupCmdByAdmin)cmd;
                podId = adminCmd.getPodId();
                clusterId = adminCmd.getClusterId();
            }
            additonalParams.remove(VirtualMachineProfile.Param.ReturnAfterVolumePrepare);
            vm = managerOperations.startVirtualMachine(vmId, podId, clusterId, cmd.getHostId(), diskOfferingMap, additonalParams, cmd.getDeploymentPlanner());
        }
        return vm;
    }

    private LinkedHashMap<Integer, Long> getVmOvfNetworkMapping(DataCenter zone, Account owner, VirtualMachineTemplate template,
            Map<Integer, Long> vmNetworkMapping, ManagerOperations managerOperations)
            throws InsufficientCapacityException, ResourceAllocationException {
        LinkedHashMap<Integer, Long> mapping = new LinkedHashMap<>();
        if (ImageFormat.OVA.equals(template.getFormat())) {
            List<OVFNetworkTO> OVFNetworkTOList =
                    templateDeployAsIsDetailsDao.listNetworkRequirementsByTemplateId(template.getId());
            if (CollectionUtils.isNotEmpty(OVFNetworkTOList)) {
                Network lastMappedNetwork = null;
                for (OVFNetworkTO OVFNetworkTO : OVFNetworkTOList) {
                    Long networkId = vmNetworkMapping.get(OVFNetworkTO.getInstanceID());
                    if (networkId == null && lastMappedNetwork == null) {
                        lastMappedNetwork = getNetworkForOvfNetworkMapping(zone, owner, managerOperations);
                    }
                    if (networkId == null) {
                        networkId = lastMappedNetwork.getId();
                    }
                    mapping.put(OVFNetworkTO.getInstanceID(), networkId);
                }
            }
        }
        return mapping;
    }

    private Network getNetworkForOvfNetworkMapping(DataCenter zone, Account owner, ManagerOperations managerOperations)
            throws InsufficientCapacityException, ResourceAllocationException {
        Network network = null;
        if (zone.isSecurityGroupEnabled() || _networkModel.isSecurityGroupSupportedForZone(zone.getId())) {
            network = _networkModel.getNetworkWithSGWithFreeIPs(owner, zone.getId());
            if (network == null) {
                throw new InvalidParameterValueException("No network with security enabled is found in zone ID: " + zone.getUuid());
            }
        } else {
            network = managerOperations.getDefaultNetwork(zone, owner, true);
            if (network == null) {
                throw new InvalidParameterValueException(String.format("Default network not found for zone ID: %s and account ID: %s", zone.getUuid(), owner.getUuid()));
            }
        }
        return network;
    }
}
