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
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.PublishScope;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenter;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.Network.GuestType;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.security.SecurityGroup;
import com.cloud.network.security.SecurityGroupManager;
import com.cloud.network.security.SecurityGroupService;
import com.cloud.network.vpc.VpcManager;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.NetworkOffering.Availability;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.storage.Storage.ImageFormat;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.db.EntityManager;

@Component
public class VmCreationNetworkSelectionServiceImpl implements VmCreationNetworkSelectionService {

    private static final Logger logger = LogManager.getLogger(VmCreationNetworkSelectionServiceImpl.class);

    @Inject
    private AccountManager accountManager;
    @Inject
    private EntityManager entityManager;
    @Inject
    private NetworkModel networkModel;
    @Inject
    private NetworkOrchestrationService networkMgr;
    @Inject
    private SecurityGroupManager securityGroupManager;
    @Inject
    private NetworkOfferingDao networkOfferingDao;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private PhysicalNetworkDao physicalNetworkDao;
    @Inject
    private VpcManager vpcManager;
    @Inject
    private VmHostNameUniquenessService vmHostNameUniquenessService;
    @Inject
    private MessageBus messageBus;

    @Override
    public VmCreationSecurityGroupNetworkSelection selectBasicSecurityGroupNetworks(DataCenter zone, VirtualMachineTemplate template,
            List<Long> securityGroupIdList, Account owner, HypervisorType hypervisor) {
        List<NetworkVO> networkList = new ArrayList<>();
        Network defaultNetwork = networkModel.getExclusiveGuestNetwork(zone.getId());

        if (defaultNetwork == null) {
            throw new InvalidParameterValueException("Unable to find a default network to start a vm");
        } else {
            networkList.add(networkDao.findById(defaultNetwork.getId()));
        }

        boolean isVmWare = isVmWare(template, hypervisor);

        if (securityGroupIdList != null && isVmWare) {
            throw new InvalidParameterValueException("Security group feature is not supported for VMware hypervisor");
        } else if (!isVmWare && networkModel.isSecurityGroupSupportedInNetwork(defaultNetwork) && networkModel.canAddDefaultSecurityGroup()) {
            securityGroupIdList = addDefaultSecurityGroupIfNeeded(securityGroupIdList, owner, false, null);
        }
        return new VmCreationSecurityGroupNetworkSelection(networkList, securityGroupIdList);
    }

    @Override
    public VmCreationSecurityGroupNetworkSelection selectAdvancedSecurityGroupNetworks(DataCenter zone, VirtualMachineTemplate template,
            List<Long> networkIdList, List<Long> securityGroupIdList, Account owner, HypervisorType hypervisor, String eventSource) {
        List<NetworkVO> networkList = new ArrayList<>();
        boolean isSecurityGroupEnabledNetworkUsed = false;
        boolean isVmWare = isVmWare(template, hypervisor);

        if (networkIdList == null || networkIdList.isEmpty()) {
            Network networkWithSecurityGroup = networkModel.getNetworkWithSGWithFreeIPs(owner, zone.getId());
            if (networkWithSecurityGroup == null) {
                throw new InvalidParameterValueException("No network with security enabled is found in zone id=" + zone.getUuid());
            }

            networkList.add(networkDao.findById(networkWithSecurityGroup.getId()));
            isSecurityGroupEnabledNetworkUsed = true;
        } else if (securityGroupIdList != null && !securityGroupIdList.isEmpty()) {
            if (isVmWare) {
                throw new InvalidParameterValueException("Security group feature is not supported for VMware hypervisor");
            }
            if (networkIdList.size() > 1 && template.getHypervisorType() != HypervisorType.KVM && hypervisor != HypervisorType.KVM) {
                throw new InvalidParameterValueException("Only support one network per VM if security group enabled");
            }

            for (Long networkId : networkIdList) {
                NetworkVO network = networkDao.findById(networkId);
                NetworkOffering ntwkOffering = networkOfferingDao.findById(network.getNetworkOfferingId());
                if (network == null) {
                    throw new InvalidParameterValueException("Unable to find network by id " + networkId);
                }

                if (!networkModel.isSecurityGroupSupportedInNetwork(network) && ntwkOffering.getGuestType() != GuestType.L2) {
                    throw new InvalidParameterValueException(String.format("Network is not security group enabled or not L2 network: %s", network));
                }

                accountManager.checkAccess(owner, AccessType.UseEntry, false, network);
                networkList.add(network);
            }
            isSecurityGroupEnabledNetworkUsed = true;
        } else {
            for (Long networkId : networkIdList) {
                NetworkVO network = networkDao.findById(networkId);

                if (network == null) {
                    throw new InvalidParameterValueException("Unable to find network by id " + networkIdList.get(0).longValue());
                }

                boolean isSecurityGroupEnabled = networkModel.isSecurityGroupSupportedInNetwork(network);
                if (isSecurityGroupEnabled) {
                    isSecurityGroupEnabledNetworkUsed = true;
                }

                if (network.getTrafficType() != TrafficType.Guest || !Arrays.asList(GuestType.Shared, GuestType.L2).contains(network.getGuestType())) {
                    throw new InvalidParameterValueException("Can specify only Shared or L2 Guest networks when deploy vm in Advance Security Group enabled zone");
                }

                accountManager.checkAccess(owner, AccessType.UseEntry, false, network);
                networkList.add(network);
            }
        }

        if (isSecurityGroupEnabledNetworkUsed && !isVmWare && networkModel.canAddDefaultSecurityGroup()) {
            securityGroupIdList = addDefaultSecurityGroupIfNeeded(securityGroupIdList, owner, true, eventSource);
        }
        return new VmCreationSecurityGroupNetworkSelection(networkList, securityGroupIdList);
    }

    @Override
    public List<NetworkVO> selectAdvancedNetworks(DataCenter zone, VirtualMachineTemplate template, List<Long> networkIdList, Account owner,
            HypervisorType hypervisor, Map<String, Map<Integer, String>> dhcpOptionsMap)
            throws InsufficientCapacityException, ResourceAllocationException {
        List<NetworkVO> networkList = new ArrayList<>();
        List<HypervisorType> vpcSupportedHTypes = vpcManager.getSupportedVpcHypervisors();
        if (networkIdList == null || networkIdList.isEmpty()) {
            NetworkVO defaultNetwork = getDefaultNetwork(zone, owner, false);
            if (defaultNetwork != null) {
                networkList.add(defaultNetwork);
            }
        } else {
            for (Long networkId : networkIdList) {
                networkList.add(validateVpcNetworkAndReturnIt(template, owner, hypervisor, vpcSupportedHTypes, networkId));
            }
        }
        verifyExtraDhcpOptionsNetwork(dhcpOptionsMap, networkList);
        return networkList;
    }

    @Override
    public NetworkVO validateVpcNetworkAndReturnIt(VirtualMachineTemplate template, Account owner, HypervisorType hypervisor,
            List<HypervisorType> vpcSupportedHTypes, Long networkId) {
        NetworkVO network = networkDao.findById(networkId);
        if (network == null) {
            throw new InvalidParameterValueException("Unable to find network by id " + networkId);
        }
        if (network.getVpcId() != null) {
            if (template.getFormat() != ImageFormat.ISO && !vpcSupportedHTypes.contains(template.getHypervisorType())) {
                throw new InvalidParameterValueException("Can't create Instance from Template with hypervisor " + template.getHypervisorType() + " in VPC Network " + network);
            } else if (template.getFormat() == ImageFormat.ISO && !vpcSupportedHTypes.contains(hypervisor)) {
                throw new InvalidParameterValueException("Can't create Instance of hypervisor type " + hypervisor + " in VPC Network");
            }
        }

        networkModel.checkNetworkPermissions(owner, network);

        NetworkOffering networkOffering = entityManager.findById(NetworkOffering.class, network.getNetworkOfferingId());
        if (networkOffering.isSystemOnly()) {
            throw new InvalidParameterValueException(String.format("Network id=%s is system only and can't be used for vm deployment", network.getUuid()));
        }
        return network;
    }

    @Override
    public NetworkVO getDefaultNetwork(DataCenter zone, Account owner, boolean selectAny)
            throws InsufficientCapacityException, ResourceAllocationException {
        NetworkVO defaultNetwork = null;

        List<NetworkOfferingVO> requiredOfferings = networkOfferingDao.listByAvailability(Availability.Required, false);
        if (requiredOfferings.size() < 1) {
            throw new InvalidParameterValueException("Unable to find network offering with availability=" + Availability.Required
                    + " to automatically create the network as a part of vm creation");
        }

        if (requiredOfferings.get(0).getState() == NetworkOffering.State.Enabled) {
            List<? extends Network> virtualNetworks = networkModel.listNetworksForAccount(owner.getId(), zone.getId(), Network.GuestType.Isolated);
            if (virtualNetworks == null) {
                throw new InvalidParameterValueException("No (virtual) networks are found for Account " + owner);
            }
            if (virtualNetworks.isEmpty()) {
                defaultNetwork = createDefaultNetworkForAccount(zone, owner, requiredOfferings);
            } else if (virtualNetworks.size() > 1 && !selectAny) {
                throw new InvalidParameterValueException("More than 1 default Isolated networks are found for Account " + owner + "; please specify networkIds");
            } else {
                defaultNetwork = networkDao.findById(virtualNetworks.get(0).getId());
            }
        } else {
            throw new InvalidParameterValueException(String.format("Required network offering %s is not in %s", requiredOfferings.get(0), NetworkOffering.State.Enabled));
        }

        return defaultNetwork;
    }

    @Override
    public void verifyExtraDhcpOptionsNetwork(Map<String, Map<Integer, String>> dhcpOptionsMap, List<NetworkVO> networkList)
            throws InvalidParameterValueException {
        vmHostNameUniquenessService.verifyExtraDhcpOptionsNetwork(dhcpOptionsMap, networkList);
    }

    protected NetworkVO createDefaultNetworkForAccount(DataCenter zone, Account owner, List<NetworkOfferingVO> requiredOfferings)
            throws InsufficientCapacityException, ResourceAllocationException {
        NetworkVO defaultNetwork = null;
        long physicalNetworkId = networkModel.findPhysicalNetworkId(zone.getId(), requiredOfferings.get(0).getTags(), requiredOfferings.get(0).getTrafficType());
        PhysicalNetwork physicalNetwork = physicalNetworkDao.findById(physicalNetworkId);
        if (physicalNetwork == null) {
            throw new InvalidParameterValueException("Unable to find physical network with id: " + physicalNetworkId + " and tag: "
                    + requiredOfferings.get(0).getTags());
        }
        logger.debug("Creating Network for Account {} from the network offering {} as a part of deployVM process", owner, requiredOfferings.get(0));
        Network newNetwork = networkMgr.createGuestNetwork(requiredOfferings.get(0).getId(), owner.getAccountName() + "-network", owner.getAccountName() + "-network",
                null, null, null, false, null, owner, null, physicalNetwork, zone.getId(), ACLType.Account, null, null, null, null, true, null, null,
                null, null, null, null, null, null, null, null, null);
        if (newNetwork != null) {
            defaultNetwork = networkDao.findById(newNetwork.getId());
        }
        return defaultNetwork;
    }

    private List<Long> addDefaultSecurityGroupIfNeeded(List<Long> securityGroupIdList, Account owner, boolean publishCreateEvent, String eventSource) {
        if (securityGroupIdList == null || securityGroupIdList.isEmpty()) {
            if (securityGroupIdList == null) {
                securityGroupIdList = new ArrayList<>();
            }
            SecurityGroup defaultGroup = securityGroupManager.getDefaultSecurityGroup(owner.getId());
            if (defaultGroup != null) {
                securityGroupIdList.add(defaultGroup.getId());
            } else {
                logger.debug("Couldn't find default security group for the Account {} so creating a new one", owner);
                defaultGroup = securityGroupManager.createSecurityGroup(SecurityGroupManager.DEFAULT_GROUP_NAME, SecurityGroupManager.DEFAULT_GROUP_DESCRIPTION,
                        owner.getDomainId(), owner.getId(), owner.getAccountName());
                if (publishCreateEvent) {
                    messageBus.publish(eventSource, SecurityGroupService.MESSAGE_CREATE_TUNGSTEN_SECURITY_GROUP_EVENT, PublishScope.LOCAL, defaultGroup);
                }
                securityGroupIdList.add(defaultGroup.getId());
            }
        }
        return securityGroupIdList;
    }

    private boolean isVmWare(VirtualMachineTemplate template, HypervisorType hypervisor) {
        return template.getHypervisorType() == HypervisorType.VMware || hypervisor == HypervisorType.VMware;
    }
}
