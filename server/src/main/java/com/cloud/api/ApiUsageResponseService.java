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
package com.cloud.api;

import static com.cloud.utils.NumbersUtil.toHumanReadableSize;

import java.text.DecimalFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.response.ControlledEntityResponse;
import org.apache.cloudstack.api.response.ResourceTagResponse;
import org.apache.cloudstack.api.response.UsageRecordResponse;
import org.apache.cloudstack.backup.BackupOffering;
import org.apache.cloudstack.backup.dao.BackupOfferingDao;
import org.apache.cloudstack.usage.Usage;
import org.apache.cloudstack.usage.UsageService;
import org.apache.cloudstack.usage.UsageTypes;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenter;
import com.cloud.domain.Domain;
import com.cloud.host.HostVO;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.VpnUserVO;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerVO;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.router.VirtualRouter;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.security.SecurityGroupVO;
import com.cloud.network.vpc.Vpc;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.projects.Project;
import com.cloud.server.ResourceTag;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.storage.BucketVO;
import com.cloud.storage.DiskOfferingVO;
import com.cloud.storage.GuestOS;
import com.cloud.storage.GuestOsCategory;
import com.cloud.storage.SnapshotVO;
import com.cloud.storage.VMTemplateVO;
import com.cloud.storage.VolumeVO;
import com.cloud.storage.dao.GuestOSCategoryDao;
import com.cloud.storage.dao.GuestOSDao;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.utils.db.EntityManager;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.snapshot.VMSnapshotVO;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

@Component
public class ApiUsageResponseService {

    protected Logger logger = LogManager.getLogger(ApiUsageResponseService.class);

    @Inject
    private EntityManager _entityMgr;
    @Inject
    private UsageService _usageSvc;
    @Inject
    private ResourceTagDao _resourceTagDao;
    @Inject
    private VMSnapshotDao vmSnapshotDao;
    @Inject
    private BackupOfferingDao backupOfferingDao;
    @Inject
    private GuestOSCategoryDao _guestOsCategoryDao;
    @Inject
    private GuestOSDao _guestOsDao;

    public Map<String, Set<ResourceTagResponse>> getUsageResourceTags()
    {
        try {
            return _resourceTagDao.listTags();
        } catch(Exception ex) {
            logger.warn("Failed to get resource details for Usage data due to exception : ", ex);
        }
        return null;
    }

    public UsageRecordResponse createUsageResponse(Usage usageRecord) {
        return createUsageResponse(usageRecord, null, false);
    }

    public UsageRecordResponse createUsageResponse(Usage usageRecord, Map<String, Set<ResourceTagResponse>> resourceTagResponseMap, boolean oldFormat) {
        UsageRecordResponse usageRecResponse = new UsageRecordResponse();
        Account account = ApiDBUtils.findAccountById(usageRecord.getAccountId());
        if (account.getType() == Account.Type.PROJECT) {
            //find the project
            Project project = ApiDBUtils.findProjectByProjectAccountIdIncludingRemoved(account.getId());
            if (project != null) {
                usageRecResponse.setProjectId(project.getUuid());
                usageRecResponse.setProjectName(project.getName());
            }
        } else {
            usageRecResponse.setAccountId(account.getUuid());
            usageRecResponse.setAccountName(account.getAccountName());
        }

        populateDomain(usageRecResponse, account.getDomainId());

        if (usageRecord.getZoneId() != null) {
            DataCenter zone = ApiDBUtils.findZoneById(usageRecord.getZoneId());
            if (zone != null) {
                usageRecResponse.setZoneId(zone.getUuid());
            }
        }
        usageRecResponse.setDescription(usageRecord.getDescription());
        usageRecResponse.setUsage(usageRecord.getUsageDisplay());
        usageRecResponse.setUsageType(usageRecord.getUsageType());
        VMInstanceVO vmInstance = null;
        if (usageRecord.getVmInstanceId() != null) {
            vmInstance = _entityMgr.findByIdIncludingRemoved(VMInstanceVO.class, usageRecord.getVmInstanceId());
            if (vmInstance != null) {
                usageRecResponse.setVirtualMachineId(vmInstance.getUuid());
            }
        }
        usageRecResponse.setResourceName(usageRecord.getVmName());
        VMTemplateVO template = null;
        if (usageRecord.getTemplateId() != null) {
            template = ApiDBUtils.findTemplateById(usageRecord.getTemplateId());
            if (template != null) {
                usageRecResponse.setTemplateId(template.getUuid());
            }
        }

        ResourceTag.ResourceObjectType resourceType = null;
        Long resourceId = null;
        if (usageRecord.getUsageType() == UsageTypes.RUNNING_VM || usageRecord.getUsageType() == UsageTypes.ALLOCATED_VM) {
            ServiceOfferingVO svcOffering = _entityMgr.findByIdIncludingRemoved(ServiceOfferingVO.class, usageRecord.getOfferingId().toString());
            //Service Offering Id
            if(svcOffering != null) {
                usageRecResponse.setOfferingId(svcOffering.getUuid());
            }
            //VM Instance ID
            VMInstanceVO vm = null;
            if (usageRecord.getUsageId() != null && usageRecord.getUsageId().equals(usageRecord.getVmInstanceId())) {
                vm = vmInstance;
            } else {
                vm = _entityMgr.findByIdIncludingRemoved(VMInstanceVO.class, usageRecord.getUsageId().toString());
            }
            if (vm != null) {
                resourceType = ResourceTag.ResourceObjectType.UserVm;
                usageRecResponse.setUsageId(vm.getUuid());
                resourceId = vm.getId();
                final GuestOS guestOS = _guestOsDao.findById(vm.getGuestOSId());
                if (guestOS != null) {
                    usageRecResponse.setOsTypeId(guestOS.getUuid());
                    usageRecResponse.setOsDisplayName(guestOS.getDisplayName());
                    final GuestOsCategory guestOsCategory = _guestOsCategoryDao.findById(guestOS.getCategoryId());
                    if (guestOsCategory != null) {
                        usageRecResponse.setOsCategoryId(guestOsCategory.getUuid());
                        usageRecResponse.setOsCategoryName(guestOsCategory.getName());
                    }
                }
            }
            //Hypervisor Type
            usageRecResponse.setType(usageRecord.getType());
            //Dynamic compute offerings details
            if(usageRecord.getCpuCores() != null) {
                usageRecResponse.setCpuNumber(usageRecord.getCpuCores());
            } else if (svcOffering.getCpu() != null){
                usageRecResponse.setCpuNumber(svcOffering.getCpu().longValue());
            }
            if(usageRecord.getCpuSpeed() != null) {
                usageRecResponse.setCpuSpeed(usageRecord.getCpuSpeed());
            } else if(svcOffering.getSpeed() != null){
                usageRecResponse.setCpuSpeed(svcOffering.getSpeed().longValue());
            }
            if(usageRecord.getMemory() != null) {
                usageRecResponse.setMemory(usageRecord.getMemory());
            } else if(svcOffering.getRamSize() != null) {
                usageRecResponse.setMemory(svcOffering.getRamSize().longValue());
            }
            if (!oldFormat) {
                final StringBuilder builder = new StringBuilder();
                if (usageRecord.getUsageType() == UsageTypes.RUNNING_VM) {
                    builder.append("Running VM usage ");
                } else if(usageRecord.getUsageType() == UsageTypes.ALLOCATED_VM) {
                    builder.append("Allocated VM usage ");
                }
                if (vm != null) {
                    builder.append("for ").append(vm.getHostName()).append(" (").append(vm.getInstanceName()).append(") (").append(vm.getUuid()).append(") ");
                }
                if (svcOffering != null) {
                    builder.append("using service offering ").append(svcOffering.getName()).append(" (").append(svcOffering.getUuid()).append(") ");
                }
                if (template != null) {
                    builder.append("and template ").append(template.getName()).append(" (").append(template.getUuid()).append(")");
                }
                usageRecResponse.setDescription(builder.toString());
            }
        } else if (usageRecord.getUsageType() == UsageTypes.IP_ADDRESS) {
            //IP Address ID
            IPAddressVO ip = _entityMgr.findByIdIncludingRemoved(IPAddressVO.class, usageRecord.getUsageId().toString());
            if (ip != null) {
                Long networkId = ip.getAssociatedWithNetworkId();
                if (networkId == null) {
                    networkId = ip.getSourceNetworkId();
                }
                resourceType = ResourceObjectType.PublicIpAddress;
                resourceId = ip.getId();
                usageRecResponse.setUsageId(ip.getUuid());
            }
            //isSourceNAT
            usageRecResponse.setSourceNat((usageRecord.getType().equals("SourceNat")) ? true : false);
            //isSystem
            usageRecResponse.setSystem((usageRecord.getSize() == 1) ? true : false);
        } else if (usageRecord.getUsageType() == UsageTypes.NETWORK_BYTES_SENT || usageRecord.getUsageType() == UsageTypes.NETWORK_BYTES_RECEIVED) {
            //Device Type
            resourceType = ResourceObjectType.UserVm;
            usageRecResponse.setType(usageRecord.getType());
            VMInstanceVO vm = null;
            HostVO host = null;
            if (usageRecord.getType().equals("DomainRouter") || usageRecord.getType().equals("UserVm")) {
                //Domain Router Id
                vm = _entityMgr.findByIdIncludingRemoved(VMInstanceVO.class, usageRecord.getUsageId().toString());
                if (vm != null) {
                    resourceId = vm.getId();
                    usageRecResponse.setUsageId(vm.getUuid());
                }
            } else {
                //External Device Host Id
                host = _entityMgr.findByIdIncludingRemoved(HostVO.class, usageRecord.getUsageId().toString());
                if (host != null) {
                    usageRecResponse.setUsageId(host.getUuid());
                }
            }
            //Network ID
            NetworkVO network = null;
            if((usageRecord.getNetworkId() != null) && (usageRecord.getNetworkId() != 0)) {
                network = _entityMgr.findByIdIncludingRemoved(NetworkVO.class, usageRecord.getNetworkId().toString());
                if (network != null) {
                    resourceType = ResourceObjectType.Network;
                    if (network.getTrafficType() == TrafficType.Public) {
                        VirtualRouter router = ApiDBUtils.findDomainRouterById(usageRecord.getUsageId());
                        Vpc vpc = ApiDBUtils.findVpcByIdIncludingRemoved(router.getVpcId());
                        usageRecResponse.setVpcId(vpc.getUuid());
                        resourceId = vpc.getId();
                    } else {
                        usageRecResponse.setNetworkId(network.getUuid());
                        resourceId = network.getId();
                    }
                    usageRecResponse.setResourceName(network.getName());
                }
            }
            if (!oldFormat) {
                final StringBuilder builder = new StringBuilder();
                if (usageRecord.getUsageType() == UsageTypes.NETWORK_BYTES_SENT) {
                    builder.append("Bytes sent by network ");
                } else if (usageRecord.getUsageType() == UsageTypes.NETWORK_BYTES_RECEIVED) {
                    builder.append("Bytes received by network ");
                }
                if (network != null) {
                    if (network.getName() != null) {
                        builder.append(network.getName());
                    }
                    if (network.getUuid() != null){
                        builder.append(" (").append(network.getUuid()).append(") ");
                    }
                    builder.append(" " + toHumanReadableSize(usageRecord.getRawUsage().longValue())  + " ");
                }
                if (vm != null) {
                    builder.append("using router ").append(vm.getInstanceName()).append(" (").append(vm.getUuid()).append(")");
                } else if (host != null) {
                    builder.append("using host ").append(host.getName()).append(" (").append(host.getUuid()).append(")");
                }
                usageRecResponse.setDescription(builder.toString());
            }
        } else if (usageRecord.getUsageType() == UsageTypes.VM_DISK_IO_READ || usageRecord.getUsageType() == UsageTypes.VM_DISK_IO_WRITE
                || usageRecord.getUsageType() == UsageTypes.VM_DISK_BYTES_READ || usageRecord.getUsageType() == UsageTypes.VM_DISK_BYTES_WRITE) {
            //Device Type
            usageRecResponse.setType(usageRecord.getType());
            resourceType = ResourceObjectType.Volume;
            //Volume ID
            VolumeVO volume = _entityMgr.findByIdIncludingRemoved(VolumeVO.class, usageRecord.getUsageId().toString());
            if (volume != null) {
                usageRecResponse.setUsageId(volume.getUuid());
                resourceId = volume.getId();
            }
            if (!oldFormat) {
                final StringBuilder builder = new StringBuilder();
                if (usageRecord.getUsageType() == UsageTypes.VM_DISK_IO_READ) {
                    builder.append("Disk I/O read requests");
                } else if (usageRecord.getUsageType() == UsageTypes.VM_DISK_IO_WRITE) {
                    builder.append("Disk I/O write requests");
                } else if (usageRecord.getUsageType() == UsageTypes.VM_DISK_BYTES_READ) {
                    builder.append("Disk I/O read bytes");
                } else if (usageRecord.getUsageType() == UsageTypes.VM_DISK_BYTES_WRITE) {
                    builder.append("Disk I/O write bytes");
                }
                if (vmInstance != null) {
                    builder.append(" for VM ").append(vmInstance.getHostName()).append(" (").append(vmInstance.getUuid()).append(")");
                }
                if (volume != null) {
                    builder.append(" and volume ").append(volume.getName()).append(" (").append(volume.getUuid()).append(")");
                }
                if (usageRecord.getRawUsage()!= null){
                    builder.append(" " + toHumanReadableSize(usageRecord.getRawUsage().longValue()));
                }
                usageRecResponse.setDescription(builder.toString());
            }
        } else if (usageRecord.getUsageType() == UsageTypes.VOLUME) {
            //Volume ID
            VolumeVO volume = _entityMgr.findByIdIncludingRemoved(VolumeVO.class, usageRecord.getUsageId().toString());
            resourceType = ResourceObjectType.Volume;
            if (volume != null) {
                usageRecResponse.setUsageId(volume.getUuid());
                resourceId = volume.getId();
            }
            //Volume Size
            usageRecResponse.setSize(usageRecord.getSize());
            //Disk Offering Id
            DiskOfferingVO diskOff = null;
            if (usageRecord.getOfferingId() != null) {
                diskOff = _entityMgr.findByIdIncludingRemoved(DiskOfferingVO.class, usageRecord.getOfferingId().toString());
                usageRecResponse.setOfferingId(diskOff.getUuid());
            }
            if (!oldFormat) {
                final StringBuilder builder = new StringBuilder();
                builder.append("Volume usage ");
                if (volume != null) {
                    builder.append("for ").append(volume.getName()).append(" (").append(volume.getUuid()).append(")");
                }
                if (vmInstance != null) {
                    builder.append(" attached to VM ").append(vmInstance.getHostName()).append(" (").append(vmInstance.getUuid()).append(")");
                }
                if (diskOff != null) {
                    builder.append(" with disk offering ").append(diskOff.getName()).append(" (").append(diskOff.getUuid()).append(")");
                }
                if (template != null) {
                    builder.append(" and template ").append(template.getName()).append(" (").append(template.getUuid()).append(")");
                }
                if (usageRecord.getSize() != null) {
                    builder.append(" and size " + toHumanReadableSize(usageRecord.getSize()));
                }
                usageRecResponse.setDescription(builder.toString());
            }
        } else if (usageRecord.getUsageType() == UsageTypes.TEMPLATE || usageRecord.getUsageType() == UsageTypes.ISO) {
            //Template/ISO ID
            VMTemplateVO tmpl = _entityMgr.findByIdIncludingRemoved(VMTemplateVO.class, usageRecord.getUsageId().toString());
            if (tmpl != null) {
                usageRecResponse.setUsageId(tmpl.getUuid());
                resourceId = tmpl.getId();
            }
            //Template/ISO Size
            usageRecResponse.setSize(usageRecord.getSize());
            if (usageRecord.getUsageType() == UsageTypes.ISO) {
                usageRecResponse.setVirtualSize(usageRecord.getSize());
                resourceType = ResourceObjectType.ISO;
            } else {
                usageRecResponse.setVirtualSize(usageRecord.getVirtualSize());
                resourceType = ResourceObjectType.Template;
            }
            if (!oldFormat) {
                final StringBuilder builder = new StringBuilder();
                if (usageRecord.getUsageType() == UsageTypes.TEMPLATE) {
                    builder.append("Template usage");
                } else if (usageRecord.getUsageType() == UsageTypes.ISO) {
                    builder.append("ISO usage");
                }
                if (tmpl != null) {
                    builder.append(" for ").append(tmpl.getName()).append(" (").append(tmpl.getUuid()).append(") ")
                            .append("with size ").append(toHumanReadableSize(usageRecord.getSize())).append(" and virtual size ").append(toHumanReadableSize(usageRecord.getVirtualSize()));
                }
                usageRecResponse.setDescription(builder.toString());
            }
        } else if (usageRecord.getUsageType() == UsageTypes.SNAPSHOT) {
            //Snapshot ID
            SnapshotVO snap = _entityMgr.findByIdIncludingRemoved(SnapshotVO.class, usageRecord.getUsageId().toString());
            resourceType = ResourceObjectType.Snapshot;
            if (snap != null) {
                usageRecResponse.setUsageId(snap.getUuid());
                resourceId = snap.getId();
            }
            //Snapshot Size
            usageRecResponse.setSize(usageRecord.getSize());
            if (!oldFormat) {
                final StringBuilder builder = new StringBuilder();
                builder.append("Snapshot usage ");
                if (snap != null) {
                    builder.append("for ").append(snap.getName()).append(" (").append(snap.getUuid()).append(") ")
                            .append("with size ").append(toHumanReadableSize(usageRecord.getSize()));
                }
                usageRecResponse.setDescription(builder.toString());
            }
        } else if (usageRecord.getUsageType() == UsageTypes.LOAD_BALANCER_POLICY) {
            //Load Balancer Policy ID
            LoadBalancerVO lb = _entityMgr.findByIdIncludingRemoved(LoadBalancerVO.class, usageRecord.getUsageId().toString());
            resourceType = ResourceObjectType.LoadBalancer;
            if (lb != null) {
                usageRecResponse.setUsageId(lb.getUuid());
                resourceId = lb.getId();
            }
            if (!oldFormat) {
                final StringBuilder builder = new StringBuilder();
                builder.append("Loadbalancer policy usage ");
                if (lb != null) {
                    builder.append(lb.getName()).append(" (").append(lb.getUuid()).append(")");
                }
                usageRecResponse.setDescription(builder.toString());
            }
        } else if (usageRecord.getUsageType() == UsageTypes.PORT_FORWARDING_RULE) {
            //Port Forwarding Rule ID
            PortForwardingRuleVO pf = _entityMgr.findByIdIncludingRemoved(PortForwardingRuleVO.class, usageRecord.getUsageId().toString());
            resourceType = ResourceObjectType.PortForwardingRule;
            if (pf != null) {
                usageRecResponse.setUsageId(pf.getUuid());
                resourceId = pf.getId();
            }
            if (!oldFormat) {
                final StringBuilder builder = new StringBuilder();
                builder.append("Port forwarding rule usage");
                if (pf != null) {
                    builder.append(" (").append(pf.getUuid()).append(")");
                }
                usageRecResponse.setDescription(builder.toString());
            }
        } else if (usageRecord.getUsageType() == UsageTypes.NETWORK_OFFERING) {
            //Network Offering Id
            NetworkOfferingVO netOff = _entityMgr.findByIdIncludingRemoved(NetworkOfferingVO.class, usageRecord.getOfferingId().toString());
            usageRecResponse.setOfferingId(netOff.getUuid());
            //is Default
            usageRecResponse.setDefault(usageRecord.getUsageId() == 1);
            if (!oldFormat) {
                final StringBuilder builder = new StringBuilder();
                builder.append("Network offering ");
                if (netOff != null) {
                    builder.append(netOff.getName()).append(" (").append(netOff.getUuid()).append(") usage ");
                }
                if (vmInstance != null) {
                    builder.append("for VM ").append(vmInstance.getHostName()).append(" (").append(vmInstance.getUuid()).append(") ");
                }
                usageRecResponse.setDescription(builder.toString());
            }
        } else if (usageRecord.getUsageType() == UsageTypes.VPN_USERS) {
            //VPN User ID
            VpnUserVO vpnUser = _entityMgr.findByIdIncludingRemoved(VpnUserVO.class, usageRecord.getUsageId().toString());
            if (vpnUser != null) {
                usageRecResponse.setUsageId(vpnUser.getUuid());
            }
            if (!oldFormat) {
                final StringBuilder builder = new StringBuilder();
                builder.append("VPN usage ");
                if (vpnUser != null) {
                    builder.append("for user ").append(vpnUser.getUsername()).append(" (").append(vpnUser.getUuid()).append(")");
                }
                usageRecResponse.setDescription(builder.toString());
            }
        } else if (usageRecord.getUsageType() == UsageTypes.SECURITY_GROUP) {
            //Security Group Id
            SecurityGroupVO sg = _entityMgr.findByIdIncludingRemoved(SecurityGroupVO.class, usageRecord.getUsageId().toString());
            resourceType = ResourceObjectType.SecurityGroup;
            if (sg != null) {
                resourceId = sg.getId();
                usageRecResponse.setUsageId(sg.getUuid());
            }
            if (!oldFormat) {
                final StringBuilder builder = new StringBuilder();
                builder.append("Security group");
                if (sg != null) {
                    builder.append(" ").append(sg.getName()).append(" (").append(sg.getUuid()).append(") usage");
                }
                if (vmInstance != null) {
                    builder.append(" for VM ").append(vmInstance.getHostName()).append(" (").append(vmInstance.getUuid()).append(")");
                }
                usageRecResponse.setDescription(builder.toString());
            }
        } else if (usageRecord.getUsageType() == UsageTypes.BACKUP) {
            resourceType = ResourceObjectType.Backup;
            final StringBuilder builder = new StringBuilder();
            builder.append("Backup usage");
            if (vmInstance != null) {
                resourceId = vmInstance.getId();
                usageRecResponse.setResourceName(vmInstance.getInstanceName());
                usageRecResponse.setUsageId(vmInstance.getUuid());
                builder.append(" for VM ").append(vmInstance.getHostName())
                        .append(" (").append(vmInstance.getUuid()).append(")");
                final BackupOffering backupOffering = backupOfferingDao.findByIdIncludingRemoved(usageRecord.getOfferingId());
                if (backupOffering != null) {
                    builder.append(" and backup offering ").append(backupOffering.getName())
                            .append(" (").append(backupOffering.getUuid()).append(", user ad-hoc/scheduled backup allowed: ")
                            .append(backupOffering.isUserDrivenBackupAllowed()).append(")");
                }
            }
            builder.append(" with size ").append(toHumanReadableSize(usageRecord.getSize()));
            builder.append(" and with virtual size ").append(toHumanReadableSize(usageRecord.getVirtualSize()));
            usageRecResponse.setDescription(builder.toString());
            usageRecResponse.setSize(usageRecord.getSize());
            usageRecResponse.setVirtualSize(usageRecord.getVirtualSize());
        } else if (usageRecord.getUsageType() == UsageTypes.VM_SNAPSHOT) {
            resourceType = ResourceObjectType.VMSnapshot;
            VMSnapshotVO vmSnapshotVO = null;
            if (usageRecord.getUsageId() != null) {
                vmSnapshotVO = vmSnapshotDao.findByIdIncludingRemoved(usageRecord.getUsageId());
                if (vmSnapshotVO != null) {
                    resourceId = vmSnapshotVO.getId();
                    usageRecResponse.setResourceName(vmSnapshotVO.getDisplayName());
                    usageRecResponse.setUsageId(vmSnapshotVO.getUuid());
                }
            }
            usageRecResponse.setSize(usageRecord.getSize());
            if (usageRecord.getVirtualSize() != null) {
                usageRecResponse.setVirtualSize(usageRecord.getVirtualSize());
            }
            if (usageRecord.getOfferingId() != null) {
                usageRecResponse.setOfferingId(usageRecord.getOfferingId().toString());
            }
            if (!oldFormat) {
                VolumeVO volume = null;
                if (vmSnapshotVO == null && usageRecord.getUsageId() != null) {
                     volume = _entityMgr.findByIdIncludingRemoved(VolumeVO.class, usageRecord.getUsageId().toString());
                }

                DiskOfferingVO diskOff = null;
                if (usageRecord.getOfferingId() != null) {
                    diskOff = _entityMgr.findByIdIncludingRemoved(DiskOfferingVO.class, usageRecord.getOfferingId());
                }
                final StringBuilder builder = new StringBuilder();
                builder.append("VMSnapshot usage");
                if (vmSnapshotVO != null) {
                    builder.append(" Id: ").append(vmSnapshotVO.getUuid());
                }
                if (vmInstance != null) {
                    builder.append(" for VM ").append(vmInstance.getHostName()).append(" (").append(vmInstance.getUuid()).append(")");
                }
                if (volume != null) {
                    builder.append(" with volume ").append(volume.getName()).append(" (").append(volume.getUuid()).append(")");
                }
                if (diskOff != null) {
                    builder.append(" using disk offering ").append(diskOff.getName()).append(" (").append(diskOff.getUuid()).append(")");
                }
                if (usageRecord.getSize() != null){
                    builder.append(" and size " + toHumanReadableSize(usageRecord.getSize()));
                }
                usageRecResponse.setDescription(builder.toString());
            }
        } else if (usageRecord.getUsageType() == UsageTypes.VOLUME_SECONDARY) {
            VolumeVO volume = _entityMgr.findByIdIncludingRemoved(VolumeVO.class, usageRecord.getUsageId().toString());
            if (!oldFormat) {
                final StringBuilder builder = new StringBuilder();
                builder.append("Volume on secondary storage usage");
                if (volume != null) {
                    builder.append(" for ").append(volume.getName()).append(" (").append(volume.getUuid()).append(") ")
                            .append("with size ").append(toHumanReadableSize(usageRecord.getSize()));
                }
                usageRecResponse.setDescription(builder.toString());
            }
        } else if (usageRecord.getUsageType() == UsageTypes.VM_SNAPSHOT_ON_PRIMARY) {
            resourceType = ResourceObjectType.VMSnapshot;
            VMSnapshotVO vmSnapshotVO = null;
            if (usageRecord.getUsageId() != null) {
                vmSnapshotVO = vmSnapshotDao.findByIdIncludingRemoved(usageRecord.getUsageId());
                if (vmSnapshotVO != null) {
                    resourceId = vmSnapshotVO.getId();
                    usageRecResponse.setResourceName(vmSnapshotVO.getDisplayName());
                    usageRecResponse.setUsageId(vmSnapshotVO.getUuid());
                }
            }
            usageRecResponse.setSize(usageRecord.getVirtualSize());
            if (!oldFormat) {
                final StringBuilder builder = new StringBuilder();
                builder.append("VMSnapshot on primary storage usage");
                if (vmSnapshotVO != null) {
                    builder.append(" Id: ").append(vmSnapshotVO.getUuid());
                }
                if (vmInstance != null) {
                    builder.append(" for VM ").append(vmInstance.getHostName()).append(" (").append(vmInstance.getUuid()).append(") ")
                            .append("with size ").append(toHumanReadableSize(usageRecord.getVirtualSize()));
                }
                usageRecResponse.setDescription(builder.toString());
            }
        } else if (usageRecord.getUsageType() == UsageTypes.BUCKET) {
            BucketVO bucket = _entityMgr.findByIdIncludingRemoved(BucketVO.class, usageRecord.getUsageId().toString());
            usageRecResponse.setUsageId(bucket.getUuid());
            usageRecResponse.setResourceName(bucket.getName());
        }
        if(resourceTagResponseMap != null && resourceTagResponseMap.get(resourceId + ":" + resourceType) != null) {
             usageRecResponse.setTags(resourceTagResponseMap.get(resourceId + ":" + resourceType));
        }

        if (usageRecord.getRawUsage() != null) {
            DecimalFormat decimalFormat = new DecimalFormat("###########.######");
            usageRecResponse.setRawUsage(decimalFormat.format(usageRecord.getRawUsage()));
        }

        if (usageRecord.getStartDate() != null) {
            usageRecResponse.setStartDate(usageRecord.getStartDate());
        }
        if (usageRecord.getEndDate() != null) {
            usageRecResponse.setEndDate(usageRecord.getEndDate());
        }

        return usageRecResponse;
    }

    public String getDateStringInternal(Date inputDate) {
        if (inputDate == null) {
            return null;
        }

        TimeZone tz = _usageSvc.getUsageTimezone();
        Calendar cal = Calendar.getInstance(tz);
        cal.setTime(inputDate);

        StringBuilder sb = new StringBuilder(32);
        sb.append(cal.get(Calendar.YEAR)).append('-');

        int month = cal.get(Calendar.MONTH) + 1;
        if (month < 10) {
            sb.append('0');
        }
        sb.append(month).append('-');

        int day = cal.get(Calendar.DAY_OF_MONTH);
        if (day < 10) {
            sb.append('0');
        }
        sb.append(day);

        sb.append("'T'");

        int hour = cal.get(Calendar.HOUR_OF_DAY);
        if (hour < 10) {
            sb.append('0');
        }
        sb.append(hour).append(':');

        int minute = cal.get(Calendar.MINUTE);
        if (minute < 10) {
            sb.append('0');
        }
        sb.append(minute).append(':');

        int seconds = cal.get(Calendar.SECOND);
        if (seconds < 10) {
            sb.append('0');
        }
        sb.append(seconds);

        double offset = cal.get(Calendar.ZONE_OFFSET);
        if (tz.inDaylightTime(inputDate)) {
            offset += (1.0 * tz.getDSTSavings()); // add the timezone's DST
            // value (typically 1 hour
            // expressed in milliseconds)
        }

        offset = offset / (1000d * 60d * 60d);
        int hourOffset = (int)offset;
        double decimalVal = Math.abs(offset) - Math.abs(hourOffset);
        int minuteOffset = (int)(decimalVal * 60);

        if (hourOffset < 0) {
            if (hourOffset > -10) {
                sb.append("-0");
            } else {
                sb.append('-');
            }
            sb.append(Math.abs(hourOffset));
        } else {
            if (hourOffset < 10) {
                sb.append("+0");
            } else {
                sb.append("+");
            }
            sb.append(hourOffset);
        }

        sb.append(':');

        if (minuteOffset == 0) {
            sb.append("00");
        } else if (minuteOffset < 10) {
            sb.append('0').append(minuteOffset);
        } else {
            sb.append(minuteOffset);
        }

        return sb.toString();
    }


    private void populateDomain(ControlledEntityResponse response, long domainId) {
        Domain domain = ApiDBUtils.findDomainById(domainId);
        if (domain == null) {
            return;
        }
        response.setDomainId(domain.getUuid());
        response.setDomainName(domain.getName());
        response.setDomainPath(ApiResponseHelper.getPrettyDomainPath(domain.getPath()));
    }
}
