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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.apache.cloudstack.api.command.admin.vm.AssignVMCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.Network;
import com.cloud.network.NetworkModel;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.network.security.SecurityGroup;
import com.cloud.network.security.SecurityGroupManager;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.NetworkOffering.Availability;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.template.VirtualMachineTemplate;
import com.cloud.user.Account;
import com.cloud.user.UserVO;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.Journal;
import com.cloud.utils.Pair;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.vm.dao.NicDao;

@Component
public class VmAssignmentNetworkServiceImpl implements VmAssignmentNetworkService {

    protected Logger logger = LogManager.getLogger(getClass());

    @Inject
    private DataCenterDao dcDao;
    @Inject
    private NetworkModel networkModel;
    @Inject
    private NetworkOrchestrationService networkMgr;
    @Inject
    private VirtualMachineManager itMgr;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private NicDao nicDao;
    @Inject
    private SecurityGroupManager securityGroupMgr;
    @Inject
    private NetworkOfferingDao networkOfferingDao;
    @Inject
    private PhysicalNetworkDao physicalNetworkDao;
    @Inject
    private UserDao userDao;
    @Inject
    private EntityManager entityMgr;
    @Override
    public Network ensureDestinationNetwork(AssignVMCmd cmd, UserVmVO vm, Account newAccount) throws InsufficientCapacityException, ResourceAllocationException {
        DataCenterVO zone = dcDao.findById(vm.getDataCenterId());
        if (zone.getNetworkType() == NetworkType.Basic) {
            logger.debug("No need to ensure an isolated network for the VM because the zone uses basic networks.");
            return null;
        }

        List<Long> networkIdList = cmd.getNetworkIds();
        List<Long> securityGroupIdList = cmd.getSecurityGroupIdList();
        if (networkModel.checkSecurityGroupSupportForNetwork(newAccount, zone, networkIdList, securityGroupIdList)) {
            logger.debug("No need to ensure an isolated network for the VM because security groups is enabled for this zone.");
            return null;
        }
        if (CollectionUtils.isNotEmpty(securityGroupIdList)) {
            throw new InvalidParameterValueException("Cannot move VM with security groups; security group feature is not enabled in this zone.");
        }

        LinkedHashSet<NetworkVO> applicableNetworks = new LinkedHashSet<>();
        addNetworksToNetworkIdList(vm, newAccount, networkIdList, applicableNetworks, new HashMap<>(), new HashMap<>());
        if (!applicableNetworks.isEmpty()) {
            logger.debug("No need to create an isolated network for the VM because there are other applicable networks.");
            return null;
        }

        List<? extends Network> virtualNetworks = networkModel.listNetworksForAccount(newAccount.getId(), zone.getId(), Network.GuestType.Isolated);
        if (!virtualNetworks.isEmpty()) {
            logger.debug("No need create a new isolated network for the VM because the owner already has existing isolated networks.");
            return null;
        }

        return createApplicableNetworkToCreateVm(newAccount, zone);
    }

    @Override
    public NetworkOfferingVO getOfferingWithRequiredAvailabilityForNetworkCreation() {
        List<NetworkOfferingVO> requiredOfferings = networkOfferingDao.listByAvailability(Availability.Required, false);
        if (CollectionUtils.isEmpty(requiredOfferings)) {
            throw new InvalidParameterValueException(String.format("Unable to find network offering with availability [%s] to automatically create the network as a part of VM "
                    + "creation.", Availability.Required));
        }
        NetworkOfferingVO firstRequiredOffering = requiredOfferings.get(0);
        if (firstRequiredOffering.getState() != NetworkOffering.State.Enabled) {
            throw new InvalidParameterValueException(String.format("Required network offering ID [%s] is not in [%s] state.", firstRequiredOffering.getId(),
                    NetworkOffering.State.Enabled));
        }
        return firstRequiredOffering;
    }

    @Override
    public void updateVmNetwork(AssignVMCmd cmd, Account caller, UserVmVO vm, Account newAccount, VirtualMachineTemplate template)
            throws InsufficientCapacityException, ResourceAllocationException {
        logger.trace("Updating network for VM [{}].", vm);

        VirtualMachine vmoi = itMgr.findById(vm.getId());
        VirtualMachineProfileImpl vmOldProfile = new VirtualMachineProfileImpl(vmoi);

        DataCenterVO zone = dcDao.findById(vm.getDataCenterId());

        List<Long> networkIdList = cmd.getNetworkIds();
        List<Long> securityGroupIdList = cmd.getSecurityGroupIdList();

        if (zone.getNetworkType() == NetworkType.Basic) {
            updateBasicTypeNetworkForVm(vm, newAccount, template, vmOldProfile, zone, networkIdList, securityGroupIdList);
            return;
        }

        updateAdvancedTypeNetworkForVm(caller, vm, newAccount, template, vmOldProfile, zone, networkIdList, securityGroupIdList);
    }

    @Override
    public void updateBasicTypeNetworkForVm(UserVmVO vm, Account newAccount, VirtualMachineTemplate template, VirtualMachineProfileImpl vmOldProfile,
            DataCenterVO zone, List<Long> networkIdList, List<Long> securityGroupIdList) throws InsufficientCapacityException {
        if (networkIdList != null && !networkIdList.isEmpty()) {
            throw new InvalidParameterValueException("Cannot move VM with Network IDs; this is a basic zone VM.");
        }

        logger.trace("Cleanup of old security groups for VM [{}]. They will be recreated for the new account once the VM is started.", vm);
        securityGroupMgr.removeInstanceFromGroups(vm);

        cleanupOfOldOwnerNicsForNetwork(vmOldProfile);

        List<NetworkVO> networkList = new ArrayList<>();
        Network defaultNetwork = networkModel.getExclusiveGuestNetwork(zone.getId());
        addDefaultNetworkToNetworkList(networkList, defaultNetwork);

        LinkedHashMap<Network, List<? extends NicProfile>> networks = new LinkedHashMap<>();
        NicProfile profile = new NicProfile();
        profile.setDefaultNic(true);
        networks.put(networkList.get(0), new ArrayList<>(Arrays.asList(profile)));

        allocateNetworksForVm(vm, networks);

        addSecurityGroupsToVm(newAccount, vm, template, securityGroupIdList, defaultNetwork);
    }

    @Override
    public void updateAdvancedTypeNetworkForVm(Account caller, UserVmVO vm, Account newAccount, VirtualMachineTemplate template,
            VirtualMachineProfileImpl vmOldProfile, DataCenterVO zone, List<Long> networkIdList, List<Long> securityGroupIdList)
            throws InsufficientCapacityException, ResourceAllocationException, InvalidParameterValueException {
        LinkedHashSet<NetworkVO> applicableNetworks = new LinkedHashSet<>();
        Map<Long, String> requestedIPv4ForNics = new HashMap<>();
        Map<Long, String> requestedIPv6ForNics = new HashMap<>();
        LinkedHashMap<Network, List<? extends NicProfile>> networks = new LinkedHashMap<>();

        if (networkModel.checkSecurityGroupSupportForNetwork(newAccount, zone, networkIdList, securityGroupIdList)) {
            logger.debug("Cleanup of old security groups for VM [{}]. They will be recreated for the new account once the VM is started.", vm);
            securityGroupMgr.removeInstanceFromGroups(vm);

            addNetworksToNetworkIdList(vm, newAccount, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);
            cleanupOfOldOwnerNicsForNetwork(vmOldProfile);

            NetworkVO defaultNetwork = addNicsToApplicableNetworksAndReturnDefaultNetwork(applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics, networks);

            if (applicableNetworks.isEmpty()) {
                throw new InvalidParameterValueException("No network is specified, please specify one when you move the VM. For now, please add a network to VM on NICs tab.");
            } else {
                allocateNetworksForVm(vm, networks);
            }

            addSecurityGroupsToVm(newAccount, vm, template, securityGroupIdList, defaultNetwork);
            return;
        }

        if (securityGroupIdList != null && !securityGroupIdList.isEmpty()) {
            throw new InvalidParameterValueException("Cannot move VM with security groups; security group feature is not enabled in this zone.");
        }

        addNetworksToNetworkIdList(vm, newAccount, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);
        cleanupOfOldOwnerNicsForNetwork(vmOldProfile);

        if (applicableNetworks.isEmpty()) {
            selectApplicableNetworkToCreateVm(newAccount, zone, applicableNetworks);
        }

        addNicsToApplicableNetworksAndReturnDefaultNetwork(applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics, networks);

        allocateNetworksForVm(vm, networks);
        logger.debug("Adding [{}] networks to VM [{}].", networks.size(), vm.getInstanceName());
    }

    @Override
    public void cleanupOfOldOwnerNicsForNetwork(VirtualMachineProfileImpl vmOldProfile) {
        logger.trace("Cleanup of old owner network for VM [{}].", vmOldProfile);
        networkMgr.cleanupNics(vmOldProfile);
        networkMgr.removeNics(vmOldProfile);
    }

    @Override
    public void addDefaultNetworkToNetworkList(List<NetworkVO> networkList, Network defaultNetwork) {
        logger.trace("Adding default network to network list.");
        if (defaultNetwork == null) {
            throw new InvalidParameterValueException("Unable to find a default network to start a VM.");
        }
        networkList.add(networkDao.findById(defaultNetwork.getId()));
    }

    @Override
    public void allocateNetworksForVm(UserVmVO vm, LinkedHashMap<Network, List<? extends NicProfile>> networks) throws InsufficientCapacityException {
        logger.trace("Allocating networks for VM [{}].", vm);
        VirtualMachine vmi = itMgr.findById(vm.getId());
        VirtualMachineProfileImpl vmProfile = new VirtualMachineProfileImpl(vmi);
        networkMgr.allocate(vmProfile, networks, null);
    }

    @Override
    public void addSecurityGroupsToVm(Account newAccount, UserVmVO vm, VirtualMachineTemplate template, List<Long> securityGroupIdList, Network defaultNetwork) {
        int securityIdList = securityGroupIdList != null ? securityGroupIdList.size() : 0;
        logger.debug("Adding security groups no " + securityIdList + " to " + vm.getInstanceName());

        boolean isVmWare = (template.getHypervisorType() == HypervisorType.VMware);
        if (securityGroupIdList != null && isVmWare) {
            throw new InvalidParameterValueException("Security group feature is not supported for VMWare hypervisor.");
        } else if (!isVmWare && (defaultNetwork == null || networkModel.isSecurityGroupSupportedInNetwork(defaultNetwork)) && networkModel.canAddDefaultSecurityGroup()) {
            if (securityGroupIdList == null) {
                securityGroupIdList = new ArrayList<>();
            }
            addDefaultSecurityGroupToSecurityGroupIdList(newAccount, securityGroupIdList);
        }

        securityGroupMgr.addInstanceToGroups(vm, securityGroupIdList);
    }

    @Override
    public void addNetworksToNetworkIdList(UserVmVO vm, Account newAccount, List<Long> networkIdList, Set<NetworkVO> applicableNetworks,
            Map<Long, String> requestedIPv4ForNics, Map<Long, String> requestedIPv6ForNics) {
        logger.trace("Adding networks to network list.");
        keepOldSharedNetworkForVm(vm, newAccount, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);
        addAdditionalNetworksToVm(vm, newAccount, networkIdList, applicableNetworks, requestedIPv4ForNics, requestedIPv6ForNics);
    }

    @Override
    @Nullable
    public NetworkVO addNicsToApplicableNetworksAndReturnDefaultNetwork(LinkedHashSet<NetworkVO> applicableNetworks, Map<Long, String> requestedIPv4ForNics,
            Map<Long, String> requestedIPv6ForNics, LinkedHashMap<Network, List<? extends NicProfile>> networks) {
        logger.trace("Adding NICs to applicable networks.");

        NetworkVO defaultNetwork = null;
        if (!applicableNetworks.isEmpty()) {
            NicProfile defaultNic = new NicProfile();
            defaultNic.setDefaultNic(true);
            defaultNetwork = applicableNetworks.iterator().next();

            for (NetworkVO appNet : applicableNetworks) {
                defaultNic.setRequestedIPv4(requestedIPv4ForNics.get(appNet.getId()));
                defaultNic.setRequestedIPv6(requestedIPv6ForNics.get(appNet.getId()));
                networks.put(appNet, new ArrayList<>(Arrays.asList(defaultNic)));
                defaultNic = new NicProfile();
            }
        }
        return defaultNetwork;
    }

    @Override
    public void selectApplicableNetworkToCreateVm(Account newAccount, DataCenterVO zone, Set<NetworkVO> applicableNetworks)
            throws InsufficientCapacityException, ResourceAllocationException {
        logger.trace("Selecting the applicable network to create the VM.");

        NetworkVO defaultNetwork;
        List<? extends Network> virtualNetworks = networkModel.listNetworksForAccount(newAccount.getId(), zone.getId(), Network.GuestType.Isolated);
        if (virtualNetworks.isEmpty()) {
            throw new CloudRuntimeException(String.format("Could not find an applicable network to create virtual machine for account [%s].", newAccount));
        } else if (virtualNetworks.size() > 1) {
            throw new InvalidParameterValueException(String.format("More than one default isolated network has been found for account [%s]; please specify networkIDs.",
                    newAccount));
        } else {
            defaultNetwork = networkDao.findById(virtualNetworks.get(0).getId());
        }

        applicableNetworks.add(defaultNetwork);
    }

    @Override
    public void addDefaultSecurityGroupToSecurityGroupIdList(Account newAccount, List<Long> securityGroupIdList) {
        logger.debug("Adding default security group to security group list if not already in it.");

        Long newAccountId = newAccount.getId();
        SecurityGroup defaultGroup = securityGroupMgr.getDefaultSecurityGroup(newAccountId);
        boolean defaultGroupPresent = false;

        if (defaultGroup != null) {
            if (securityGroupIdList.contains(defaultGroup.getId())) {
                defaultGroupPresent = true;
            }
        } else {
            logger.debug("Could not find a default security group for account [{}]. Creating a new one.", newAccount);
            defaultGroup = securityGroupMgr.createSecurityGroup(SecurityGroupManager.DEFAULT_GROUP_NAME, SecurityGroupManager.DEFAULT_GROUP_DESCRIPTION, newAccount.getDomainId(),
                    newAccountId, newAccount.getAccountName());
        }

        if (!defaultGroupPresent) {
            securityGroupIdList.add(defaultGroup.getId());
        }
    }

    @Override
    public void keepOldSharedNetworkForVm(UserVmVO vm, Account newAccount, List<Long> networkIdList, Set<NetworkVO> applicableNetworks,
            Map<Long, String> requestedIPv4ForNics, Map<Long, String> requestedIPv6ForNics) {
        logger.trace("Attempting to keep old shared network for VM [{}].", vm);

        if (CollectionUtils.isNotEmpty(networkIdList)) {
            return;
        }

        NicVO defaultNicOld = nicDao.findDefaultNicForVM(vm.getId());
        if (defaultNicOld == null) {
            return;
        }

        NetworkVO defaultNetworkOld = networkDao.findById(defaultNicOld.getNetworkId());
        if (canAccountUseNetwork(newAccount, defaultNetworkOld)) {
            applicableNetworks.add(defaultNetworkOld);

            Long defaultNetworkOldId = defaultNetworkOld.getId();
            requestedIPv4ForNics.put(defaultNetworkOldId, defaultNicOld.getIPv4Address());
            requestedIPv6ForNics.put(defaultNetworkOldId, defaultNicOld.getIPv6Address());

            logger.debug("Using old shared network [{}] with old IP [{}] on default NIC of VM [{}].", defaultNicOld.getIPv4Address(), defaultNetworkOld, vm);
        }
    }

    @Override
    public void addAdditionalNetworksToVm(UserVmVO vm, Account newAccount, List<Long> networkIdList, Set<NetworkVO> applicableNetworks,
            Map<Long, String> requestedIPv4ForNics, Map<Long, String> requestedIPv6ForNics) {
        logger.trace("Adding additional networks to VM [{}].", vm);

        if (CollectionUtils.isEmpty(networkIdList)) {
            return;
        }

        for (Long networkId : networkIdList) {
            NetworkVO network = networkDao.findById(networkId);
            if (network == null) {
                InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find specified Network ID.");
                ex.addProxyObject(networkId.toString(), "networkId");
                throw ex;
            }

            networkModel.checkNetworkPermissions(newAccount, network);

            NetworkOffering networkOffering = entityMgr.findById(NetworkOffering.class, network.getNetworkOfferingId());
            if (networkOffering.isSystemOnly()) {
                throw new InvalidParameterValueException(String.format("Specified network [%s] is system only and cannot be used for VM deployment.", network));
            }

            if (network.getGuestType() == Network.GuestType.Shared && network.getAclType() == ACLType.Domain) {
                NicVO nicOld = nicDao.findByNtwkIdAndInstanceId(networkId, vm.getId());
                if (nicOld != null) {
                    requestedIPv4ForNics.put(networkId, nicOld.getIPv4Address());
                    requestedIPv6ForNics.put(networkId, nicOld.getIPv6Address());
                    logger.debug("Using old shared network [{}] with old IP [{}] on NIC of VM [{}].", network, nicOld.getIPv4Address(), vm);
                }
            }
            logger.debug("Added network [{}] to VM [{}].", network.getName(), vm.getId());
            applicableNetworks.add(network);
        }
    }

    @Override
    public NetworkVO createApplicableNetworkToCreateVm(Account newAccount, DataCenterVO zone) throws InsufficientCapacityException, ResourceAllocationException {
        logger.trace("Creating an applicable network to create the VM.");

        NetworkVO defaultNetwork;
        Long zoneId = zone.getId();
        Account caller = CallContext.current().getCallingAccount();
        NetworkOfferingVO requiredOffering = getOfferingWithRequiredAvailabilityForNetworkCreation();
        String requiredOfferingTags = requiredOffering.getTags();

        long physicalNetworkId = networkModel.findPhysicalNetworkId(zoneId, requiredOfferingTags, requiredOffering.getTrafficType());

        PhysicalNetwork physicalNetwork = physicalNetworkDao.findById(physicalNetworkId);
        if (physicalNetwork == null) {
            throw new InvalidParameterValueException(String.format("Unable to find physical network with ID [%s] and tag [%s].", physicalNetworkId, requiredOfferingTags));
        }

        long requiredOfferingId = requiredOffering.getId();
        logger.debug("Creating network for account [{}] from the network offering [{}] as a part of VM deployment process.", newAccount, requiredOfferingId);

        String networkName = String.format("%s-network", newAccount.getAccountName());
        Network newNetwork = networkMgr.createGuestNetwork(requiredOfferingId, networkName, networkName, null, null, null,
                false, null, newAccount, null, physicalNetwork, zoneId, ACLType.Account, null, null, null, null, true, null,
                null, null, null, null, null, null, null, null, null, null);

        if (requiredOffering.isPersistent()) {
            newNetwork = implementNetwork(caller, zone, newNetwork);
        }

        defaultNetwork = networkDao.findById(newNetwork.getId());
        return defaultNetwork;
    }

    @Override
    public Network implementNetwork(Account caller, DataCenterVO zone, Network newNetwork) {
        logger.trace("Implementing network [{}].", newNetwork);

        DeployDestination dest = new DeployDestination(zone, null, null, null);
        Journal journal = new Journal.LogJournal("Implementing " + newNetwork, logger);
        UserVO callerUser = userDao.findById(CallContext.current().getCallingUserId());
        ReservationContext context = new ReservationContextImpl(UUID.randomUUID().toString(), journal, callerUser, caller);

        logger.debug("Implementing the network for account [{}] as a part of network provision for persistent networks.", newNetwork);

        try {
            Pair<? extends NetworkGuru, ? extends Network> implementedNetwork = networkMgr.implementNetwork(newNetwork.getId(), dest, context);

            if (implementedNetwork == null || implementedNetwork.first() == null || implementedNetwork.second() == null) {
                logger.warn("Failed to implement network [{}].", newNetwork);
            } else {
                newNetwork = implementedNetwork.second();
            }
        } catch (Exception ex) {
            logger.warn("Failed to implement network [{}] elements and resources as a part of network provision for persistent network due to [{}].", newNetwork, ex.getMessage(), ex);
            throw new CloudRuntimeException(String.format("Failed to implement network [%s] elements and resources as a part of network provision.", newNetwork));
        }

        return newNetwork;
    }

    @Override
    public boolean canAccountUseNetwork(Account newAccount, Network network) {
        if (network != null && network.getAclType() == ACLType.Domain && (network.getGuestType() == Network.GuestType.Shared || network.getGuestType() == Network.GuestType.L2)) {
            try {
                networkModel.checkNetworkPermissions(newAccount, network);
                return true;
            } catch (PermissionDeniedException e) {
                logger.debug("[{}] network [{}] cannot be used by new account [{}].", network.getGuestType(), network, newAccount);
                return false;
            }
        }
        return false;
    }
}
