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

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.command.user.vm.AddNicToVMCmd;
import org.apache.cloudstack.api.command.user.vm.RemoveNicFromVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateDefaultNicForVMCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateVmNicIpCmd;
import org.apache.cloudstack.api.command.user.vm.UpdateVmNicCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.Vlan;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.hypervisor.Hypervisor.HypervisorType;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Network;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.LoadBalancerVMMapDao;
import com.cloud.network.dao.LoadBalancerVMMapVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.PortForwardingRuleVO;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.offerings.NetworkOfferingVO;
import org.apache.cloudstack.acl.ControlledEntity;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.Ip;
import com.cloud.utils.net.NetUtils;
import com.cloud.uservm.UserVm;
import com.cloud.vm.VirtualMachine.State;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicExtraDhcpOptionDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.cloud.vm.snapshot.dao.VMSnapshotDao;

/**
 * VM NIC lifecycle implementation — extracted from {@link UserVmManagerImpl}.
 *
 * @see VmNicService
 */
@Component
public class VmNicServiceImpl implements VmNicService {
    private static final Logger LOG = LogManager.getLogger(VmNicServiceImpl.class);

    @Inject private UserVmDao vmDao;
    @Inject private VMInstanceDao vmInstanceDao;
    @Inject private VMSnapshotDao vmSnapshotDao;
    @Inject private NicDao nicDao;
    @Inject private NetworkDao networkDao;
    @Inject private DataCenterDao dcDao;
    @Inject private NicExtraDhcpOptionDao nicExtraDhcpOptionDao;
    @Inject private com.cloud.offerings.dao.NetworkOfferingDao networkOfferingDao;
    @Inject private IPAddressDao ipAddressDao;
    @Inject private LoadBalancerVMMapDao loadBalancerVmMapDao;
    @Inject private com.cloud.network.dao.FirewallRulesDao rulesDao;
    @Inject private PortForwardingRulesDao portForwardingDao;
    @Inject private AccountDao accountDao;
    @Inject private VlanDao vlanDao;
    @Inject private AccountManager accountManager;
    @Inject private NetworkModel networkModel;
    @Inject private NetworkOrchestrationService networkMgr;
    @Inject private VirtualMachineManager itMgr;
    @Inject private RulesManager rulesMgr;
    @Inject private IpAddressManager ipAddrMgr;

    // ===========================================================================
    // addNicToVirtualMachine
    // ===========================================================================

    @Override
    public UserVm addNicToVirtualMachine(AddNicToVMCmd cmd)
            throws InvalidParameterValueException, PermissionDeniedException, CloudRuntimeException {
        Long vmId = cmd.getVmId();
        Long networkId = cmd.getNetworkId();
        String ipAddress = cmd.getIpAddress();
        String macAddress = cmd.getMacAddress();
        Account caller = CallContext.current().getCallingAccount();

        UserVmVO vmInstance = vmDao.findById(vmId);
        if (vmInstance == null) {
            throw new InvalidParameterValueException("Unable to find an Instance with ID " + vmId);
        }
        if (!vmSnapshotDao.findByVm(vmId).isEmpty()) {
            throw new InvalidParameterValueException("NIC cannot be added to Instance with Instance Snapshots");
        }
        NetworkVO network = networkDao.findById(networkId);
        if (network == null) {
            throw new InvalidParameterValueException("Unable to find a Network with ID " + networkId);
        }
        if (UserVmManager.SHAREDFSVM.equals(vmInstance.getUserVmType()) && network.getGuestType() == Network.GuestType.Shared) {
            if (network.getAclType() != ControlledEntity.ACLType.Account
                    || network.getDomainId() != vmInstance.getDomainId()
                    || network.getAccountId() != vmInstance.getAccountId()) {
                throw new InvalidParameterValueException("Shared network which is not Account scoped and not belonging to the same account can not be added to a Shared FileSystem Instance");
            }
        }

        Account vmOwner = accountManager.getAccount(vmInstance.getAccountId());
        networkModel.checkNetworkPermissions(vmOwner, network);
        checkIfNetExistsForVM(vmInstance, network);

        macAddress = validateOrReplaceMacAddress(macAddress, network);
        if (nicDao.findByNetworkIdAndMacAddress(networkId, macAddress) != null) {
            throw new CloudRuntimeException("A NIC with this MAC address exists for network: " + network.getUuid());
        }

        NicProfile profile = new NicProfile(ipAddress, null, macAddress);
        if (ipAddress != null && !(NetUtils.isValidIp4(ipAddress) || NetUtils.isValidIp6(ipAddress))) {
            throw new InvalidParameterValueException("Invalid format for IP address parameter: " + ipAddress);
        }

        accountManager.checkAccess(caller, null, true, vmInstance);

        DataCenterVO dc = dcDao.findById(vmInstance.getDataCenterId());
        if (dc.getNetworkType() == DataCenter.NetworkType.Basic) {
            throw new CloudRuntimeException(String.format("Zone %s, has a NetworkType of Basic. Can't add a new NIC to a Instance on a Basic Network", dc));
        }
        if (network.getDataCenterId() != vmInstance.getDataCenterId()) {
            throw new CloudRuntimeException(String.format("%s is in zone: %s but %s is in zone: %s",
                    vmInstance, dc, network, dcDao.findById(network.getDataCenterId())));
        }

        if (networkModel.getNicInNetwork(vmInstance.getId(), network.getId()) != null) {
            LOG.debug("Instance {} already in network {} going to add another NIC", vmInstance, network);
        } else {
            List<String> hostNames = vmInstanceDao.listDistinctHostNames(network.getId());
            if (hostNames.contains(vmInstance.getHostName())) {
                throw new CloudRuntimeException("Network " + network.getName() + " already has an Instance with host name: " + vmInstance.getHostName());
            }
        }

        setNicAsDefaultIfNeeded(vmInstance, profile);

        NicProfile guestNic;
        boolean cleanUp = true;
        try {
            guestNic = itMgr.addVmToNetwork(vmInstance, network, profile);
            saveExtraDhcpOptions(guestNic.getId(), cmd.getDhcpOptionsMap());
            networkMgr.configureExtraDhcpOptions(network, guestNic.getId(), cmd.getDhcpOptionsMap());
            cleanUp = false;
        } catch (ResourceUnavailableException e) {
            throw new CloudRuntimeException("Unable to add NIC to " + vmInstance + ": " + e);
        } catch (InsufficientCapacityException e) {
            throw new CloudRuntimeException("Insufficient capacity when adding NIC to " + vmInstance + ": " + e);
        } catch (ConcurrentOperationException e) {
            throw new CloudRuntimeException("Concurrent operations on adding NIC to " + vmInstance + ": " + e);
        } finally {
            if (cleanUp) {
                try {
                    itMgr.removeVmFromNetwork(vmInstance, network, null);
                } catch (ResourceUnavailableException e) {
                    throw new CloudRuntimeException("Error while cleaning up NIC " + e);
                }
            }
        }
        CallContext.current().putContextParameter(Nic.class, guestNic.getUuid());
        LOG.debug("Successful addition of {} from {} through {}", network, vmInstance, guestNic);
        return vmDao.findById(vmInstance.getId());
    }

    /** Set NIC as default if VM has no default NIC. */
    void setNicAsDefaultIfNeeded(UserVmVO vmInstance, NicProfile nicProfile) {
        if (networkModel.getDefaultNic(vmInstance.getId()) == null) {
            LOG.debug("Setting NIC {} as default as Instance {} has no default NIC.", nicProfile.getName(), vmInstance.getName());
            nicProfile.setDefaultNic(true);
        }
    }

    private void checkIfNetExistsForVM(VirtualMachine virtualMachine, Network network) {
        List<NicVO> allNics = nicDao.listByVmId(virtualMachine.getId());
        for (NicVO nic : allNics) {
            if (nic.getNetworkId() == network.getId()) {
                throw new CloudRuntimeException("A NIC already exists for VM:" + virtualMachine.getInstanceName() + " in network: " + network.getUuid());
            }
        }
    }

    /** If the given MAC address is invalid, replace it with the next available MAC address. */
    @Override
    public String validateOrReplaceMacAddress(String macAddress, NetworkVO network) {
        if (!NetUtils.isValidMac(macAddress)) {
            try {
                macAddress = networkModel.getNextAvailableMacAddressInNetwork(network.getId());
            } catch (InsufficientAddressCapacityException e) {
                throw new CloudRuntimeException(String.format("A MAC address cannot be generated for this NIC in the network [%s] ", network));
            }
        }
        return macAddress;
    }

    private void saveExtraDhcpOptions(long nicId, Map<Integer, String> dhcpOptions) {
        List<NicExtraDhcpOptionVO> opts = dhcpOptions.entrySet().stream()
                .map(entry -> new NicExtraDhcpOptionVO(nicId, entry.getKey(), entry.getValue()))
                .collect(Collectors.toList());
        nicExtraDhcpOptionDao.saveExtraDhcpOptions(opts);
    }

    // ===========================================================================
    // removeNicFromVirtualMachine
    // ===========================================================================

    @Override
    public UserVm removeNicFromVirtualMachine(RemoveNicFromVMCmd cmd)
            throws InvalidParameterValueException, PermissionDeniedException, CloudRuntimeException {
        Long vmId = cmd.getVmId();
        Long nicId = cmd.getNicId();
        Account caller = CallContext.current().getCallingAccount();

        UserVmVO vmInstance = vmDao.findById(vmId);
        if (vmInstance == null) {
            throw new InvalidParameterValueException("Unable to find an Instance with ID " + vmId);
        }
        if (!vmSnapshotDao.findByVm(vmId).isEmpty()) {
            throw new InvalidParameterValueException("NIC cannot be removed from Instance with Instance Snapshots");
        }
        NicVO nic = nicDao.findById(nicId);
        if (nic == null) {
            throw new InvalidParameterValueException("Unable to find a NIC with ID " + nicId);
        }
        NetworkVO network = networkDao.findById(nic.getNetworkId());
        if (network == null) {
            throw new InvalidParameterValueException("Unable to find a Network with ID " + nic.getNetworkId());
        }
        accountManager.checkAccess(caller, null, true, vmInstance);

        DataCenterVO dc = dcDao.findById(vmInstance.getDataCenterId());
        if (dc.getNetworkType() == DataCenter.NetworkType.Basic) {
            throw new InvalidParameterValueException(String.format("Zone %s, has a NetworkType of Basic. Can't remove a NIC from a VM on a Basic Network", dc));
        }
        if (nic.getInstanceId() != vmId) {
            throw new InvalidParameterValueException(nic + " is not a NIC on " + vmInstance);
        }
        if (nic.isDefaultNic() && vmInstance.getType() == VirtualMachine.Type.User) {
            throw new InvalidParameterValueException("Unable to remove NIC from " + vmInstance + " in " + network + ", NIC is default.");
        }
        if (!rulesMgr.listAssociatedRulesForGuestNic(nic).isEmpty()) {
            throw new InvalidParameterValueException("Unable to remove NIC from " + vmInstance + " in " + network + ", NIC has associated Port forwarding or Load balancer or Static NAT rules.");
        }

        boolean removed;
        try {
            removed = itMgr.removeNicFromVm(vmInstance, nic);
        } catch (ResourceUnavailableException e) {
            throw new CloudRuntimeException("Unable to remove " + network + " from " + vmInstance + ": " + e);
        } catch (ConcurrentOperationException e) {
            throw new CloudRuntimeException("Concurrent operations on removing " + network + " from " + vmInstance + ": " + e);
        }
        if (!removed) {
            throw new CloudRuntimeException("Unable to remove " + network + " from " + vmInstance);
        }
        LOG.debug("Successful removal of {} from {}", network, vmInstance);
        return vmDao.findById(vmInstance.getId());
    }

    // ===========================================================================
    // updateDefaultNicForVirtualMachine
    // ===========================================================================

    @Override
    public UserVm updateDefaultNicForVirtualMachine(UpdateDefaultNicForVMCmd cmd)
            throws InvalidParameterValueException, CloudRuntimeException {
        Long vmId = cmd.getVmId();
        Long nicId = cmd.getNicId();
        Account caller = CallContext.current().getCallingAccount();

        UserVmVO vmInstance = vmDao.findById(vmId);
        if (vmInstance == null) {
            throw new InvalidParameterValueException("Unable to find an Instance with ID " + vmId);
        }
        if (!vmSnapshotDao.findByVm(vmId).isEmpty()) {
            throw new InvalidParameterValueException("NIC cannot be updated for Instance with Instance Snapshots");
        }
        NicVO nic = nicDao.findById(nicId);
        if (nic == null) {
            throw new InvalidParameterValueException("Unable to find a NIC with ID " + nicId);
        }
        NetworkVO network = networkDao.findById(nic.getNetworkId());
        if (network == null) {
            throw new InvalidParameterValueException("Unable to find a Network with ID " + nic.getNetworkId());
        }
        accountManager.checkAccess(caller, null, true, vmInstance);

        DataCenterVO dc = dcDao.findById(vmInstance.getDataCenterId());
        if (dc.getNetworkType() == DataCenter.NetworkType.Basic) {
            throw new CloudRuntimeException(String.format("Zone %s, has a NetworkType of Basic. Can't change default NIC on a Basic Network", dc));
        }

        Network existingdefaultnet = networkModel.getDefaultNetworkForVm(vmId);
        if (nic.getInstanceId() != vmId) {
            throw new InvalidParameterValueException(nic + " is not a NIC on  " + vmInstance);
        }
        if (nic.isDefaultNic()) {
            throw new CloudRuntimeException("refusing to set default NIC because chosen NIC is already the default");
        }
        if (vmInstance.getState() != State.Running && vmInstance.getState() != State.Stopped) {
            throw new CloudRuntimeException("refusing to set default " + vmInstance + " is not Running or Stopped");
        }

        NicProfile existing = null;
        for (NicProfile nicProfile : networkMgr.getNicProfiles(vmInstance)) {
            if (nicProfile.isDefaultNic() && existingdefaultnet != null && nicProfile.getNetworkId() == existingdefaultnet.getId()) {
                existing = nicProfile;
            }
        }
        if (existing == null) {
            LOG.warn("Failed to update default NIC, no NIC profile found for existing default Network");
            throw new CloudRuntimeException("Failed to find a NIC profile for the existing default Network. This is bad and probably means some sort of configuration corruption");
        }

        Network oldDefaultNetwork = networkModel.getDefaultNetworkForVm(vmId);
        String oldNicIdString = Long.toString(networkModel.getDefaultNic(vmId).getId());
        long oldNetworkOfferingId = oldDefaultNetwork != null ? oldDefaultNetwork.getNetworkOfferingId() : -1L;

        NicVO existingVO = nicDao.findById(existing.id);
        Integer chosenID = nic.getDeviceId();
        Integer existingID = existing.getDeviceId();

        Network newdefault = null;
        if (itMgr.updateDefaultNicForVM(vmInstance, nic, existingVO)) {
            newdefault = networkModel.getDefaultNetworkForVm(vmId);
        }

        if (newdefault == null) {
            nic.setDefaultNic(false);
            nic.setDeviceId(chosenID);
            existingVO.setDefaultNic(true);
            existingVO.setDeviceId(existingID);
            nic = nicDao.persist(nic);
            nicDao.persist(existingVO);

            newdefault = networkModel.getDefaultNetworkForVm(vmId);
            if (newdefault.getId() == existingdefaultnet.getId()) {
                throw new CloudRuntimeException("Setting a default nic failed, and we had no default nic, but we were able to set it back to the original");
            }
            throw new CloudRuntimeException("Failed to change default nic to " + nic + " and now we have no default");
        } else if (newdefault.getId() == nic.getNetworkId()) {
            LOG.debug("successfully set default network to {} for {}", network, vmInstance);
            String nicIdString = Long.toString(nic.getId());
            long newNetworkOfferingId = network.getNetworkOfferingId();
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_REMOVE, vmInstance.getAccountId(), vmInstance.getDataCenterId(), vmInstance.getId(),
                    oldNicIdString, oldNetworkOfferingId, null, 1L, VirtualMachine.class.getName(), vmInstance.getUuid(), vmInstance.isDisplay());
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN, vmInstance.getAccountId(), vmInstance.getDataCenterId(), vmInstance.getId(), nicIdString,
                    newNetworkOfferingId, null, 1L, VirtualMachine.class.getName(), vmInstance.getUuid(), vmInstance.isDisplay());
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_REMOVE, vmInstance.getAccountId(), vmInstance.getDataCenterId(), vmInstance.getId(), nicIdString,
                    newNetworkOfferingId, null, 0L, VirtualMachine.class.getName(), vmInstance.getUuid(), vmInstance.isDisplay());
            UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN, vmInstance.getAccountId(), vmInstance.getDataCenterId(), vmInstance.getId(),
                    oldNicIdString, oldNetworkOfferingId, null, 0L, VirtualMachine.class.getName(), vmInstance.getUuid(), vmInstance.isDisplay());

            if (vmInstance.getState() == State.Running) {
                try {
                    VirtualMachineProfile vmProfile = new com.cloud.vm.VirtualMachineProfileImpl(vmInstance);
                    User callerUser = accountManager.getActiveUser(CallContext.current().getCallingUserId());
                    com.cloud.vm.ReservationContext context = new com.cloud.vm.ReservationContextImpl(null, null, callerUser, caller);
                    DeployDestination dest = new DeployDestination(dc, null, null, null);
                    networkMgr.prepare(vmProfile, dest, context);
                } catch (final Exception e) {
                    LOG.info("Got exception: ", e);
                }
            }
            return vmDao.findById(vmInstance.getId());
        }

        throw new CloudRuntimeException(String.format("something strange happened, new default network(%s) is not null, and is not equal to the network(%d) of the chosen nic", newdefault, nic.getNetworkId()));
    }

    // ===========================================================================
    // updateNicIpForVirtualMachine
    // ===========================================================================

    @Override
    public UserVm updateNicIpForVirtualMachine(UpdateVmNicIpCmd cmd) {
        Long nicId = cmd.getNicId();
        String ipaddr = cmd.getIpaddress();
        Account caller = CallContext.current().getCallingAccount();

        NicVO nicVO = nicDao.findById(nicId);
        if (nicVO == null) {
            throw new InvalidParameterValueException("There is no nic for the " + nicId);
        }
        if (nicVO.getVmType() != VirtualMachine.Type.User) {
            throw new InvalidParameterValueException("The nic is not belongs to user vm");
        }

        UserVm vm = vmDao.findById(nicVO.getInstanceId());
        if (vm == null) {
            throw new InvalidParameterValueException("There is no vm with the nic");
        }

        Network network = networkDao.findById(nicVO.getNetworkId());
        if (network == null) {
            throw new InvalidParameterValueException("There is no network with the nic");
        }
        if (!(network.getState() == Network.State.Allocated || network.getState() == Network.State.Implemented || network.getState() == Network.State.Setup)) {
            throw new InvalidParameterValueException("Network is not in the right state to update vm nic ip. Correct states are: "
                    + Network.State.Allocated + ", " + Network.State.Implemented + ", " + Network.State.Setup);
        }

        NetworkOfferingVO offering = networkOfferingDao.findByIdIncludingRemoved(network.getNetworkOfferingId());
        if (offering == null) {
            throw new InvalidParameterValueException("There is no network offering with the network");
        }
        if (!networkModel.listNetworkOfferingServices(offering.getId()).isEmpty() && vm.getState() != State.Stopped) {
            InvalidParameterValueException ex = new InvalidParameterValueException("VM is not Stopped, unable to update the vm nic having the specified id");
            ex.addProxyObject(vm.getUuid(), "vmId");
            throw ex;
        }

        accountManager.checkAccess(caller, null, true, vm);
        Account ipOwner = accountDao.findByIdIncludingRemoved(vm.getAccountId());

        LOG.debug("Calling the IP allocation ...");
        DataCenter dc = dcDao.findById(network.getDataCenterId());
        if (dc == null) {
            throw new InvalidParameterValueException("There is no dc with the NIC");
        }
        if (dc.getNetworkType() == NetworkType.Advanced && network.getGuestType() == Network.GuestType.Isolated) {
            try {
                ipaddr = ipAddrMgr.allocateGuestIP(network, ipaddr);
            } catch (InsufficientAddressCapacityException e) {
                throw new InvalidParameterValueException(String.format("Allocating IP to guest NIC %s failed, for insufficient address capacity", nicVO));
            }
            if (ipaddr == null) {
                throw new InvalidParameterValueException(String.format("Allocating IP to guest NIC %s failed, please choose another IP", nicVO));
            }
            if (nicVO.getIPv4Address() != null) {
                updatePublicIpDnatVmIp(vm.getId(), network.getId(), nicVO.getIPv4Address(), ipaddr);
                updateLoadBalancerRulesVmIp(vm.getId(), network.getId(), nicVO.getIPv4Address(), ipaddr);
                updatePortForwardingRulesVmIp(vm.getId(), network.getId(), nicVO.getIPv4Address(), ipaddr);
            }
        } else if (dc.getNetworkType() == NetworkType.Basic || network.getGuestType() == Network.GuestType.Shared) {
            Long podId = null;
            if (dc.getNetworkType() == NetworkType.Basic) {
                podId = vm.getPodIdToDeployIn();
                if (podId == null) {
                    throw new InvalidParameterValueException("Instance Pod ID is null in Basic zone; can't decide the range for IP allocation");
                }
            }
            try {
                ipaddr = ipAddrMgr.allocatePublicIpForGuestNic(network, podId, ipOwner, ipaddr);
                if (ipaddr == null) {
                    throw new InvalidParameterValueException("Allocating IP to guest NIC " + nicVO.getUuid() + " failed, please choose another IP");
                }
                final IPAddressVO newIp = ipAddressDao.findByIpAndSourceNetworkId(network.getId(), ipaddr);
                final Vlan vlan = vlanDao.findById(newIp.getVlanId());
                nicVO.setIPv4Gateway(vlan.getVlanGateway());
                nicVO.setIPv4Netmask(vlan.getVlanNetmask());

                final IPAddressVO ip = ipAddressDao.findByIpAndSourceNetworkId(nicVO.getNetworkId(), nicVO.getIPv4Address());
                if (ip != null) {
                    Transaction.execute(new TransactionCallbackNoReturn() {
                        @Override
                        public void doInTransactionWithoutResult(TransactionStatus status) {
                            ipAddrMgr.markIpAsUnavailable(ip.getId());
                            ipAddressDao.unassignIpAddress(ip.getId());
                        }
                    });
                }
            } catch (InsufficientAddressCapacityException e) {
                LOG.error("Allocating IP to guest NIC {} failed, for insufficient address capacity", nicVO);
                return null;
            }
        } else {
            throw new InvalidParameterValueException("UpdateVmNicIpCmd is not supported in L2 network");
        }

        LOG.debug("Updating IPv4 address of NIC {} to {}/{} with gateway {}", nicVO, ipaddr, nicVO.getIPv4Netmask(), nicVO.getIPv4Gateway());
        nicVO.setIPv4Address(ipaddr);
        nicDao.persist(nicVO);
        return vm;
    }

    private void updatePublicIpDnatVmIp(long vmId, long networkId, String oldIp, String newIp) {
        if (!networkModel.areServicesSupportedInNetwork(networkId, Service.StaticNat)) {
            return;
        }
        List<IPAddressVO> publicIps = ipAddressDao.listByAssociatedVmId(vmId);
        for (IPAddressVO publicIp : publicIps) {
            if (oldIp.equals(publicIp.getVmIp()) && publicIp.getAssociatedWithNetworkId() == networkId) {
                publicIp.setVmIp(newIp);
                ipAddressDao.persist(publicIp);
            }
        }
    }

    private void updateLoadBalancerRulesVmIp(long vmId, long networkId, String oldIp, String newIp) {
        if (!networkModel.areServicesSupportedInNetwork(networkId, Service.Lb)) {
            return;
        }
        List<LoadBalancerVMMapVO> maps = loadBalancerVmMapDao.listByInstanceId(vmId);
        for (LoadBalancerVMMapVO map : maps) {
            FirewallRuleVO rule = rulesDao.findById(map.getLoadBalancerId());
            if (oldIp.equals(map.getInstanceIp()) && networkId == rule.getNetworkId()) {
                map.setInstanceIp(newIp);
                loadBalancerVmMapDao.persist(map);
            }
        }
    }

    private void updatePortForwardingRulesVmIp(long vmId, long networkId, String oldIp, String newIp) {
        if (!networkModel.areServicesSupportedInNetwork(networkId, Service.PortForwarding)) {
            return;
        }
        List<PortForwardingRuleVO> firewallRules = portForwardingDao.listByVm(vmId);
        for (PortForwardingRuleVO firewallRule : firewallRules) {
            FirewallRuleVO rule = rulesDao.findById(firewallRule.getId());
            if (oldIp.equals(firewallRule.getDestinationIpAddress().toString()) && networkId == rule.getNetworkId()) {
                firewallRule.setDestinationIpAddress(new Ip(newIp));
                portForwardingDao.persist(firewallRule);
            }
        }
    }

    // ===========================================================================
    // updateVirtualMachineNic
    // ===========================================================================

    @Override
    public UserVm updateVirtualMachineNic(UpdateVmNicCmd cmd) {
        Long nicId = cmd.getNicId();
        Boolean isNicEnabled = cmd.isEnabled();
        Account caller = CallContext.current().getCallingAccount();

        NicVO nic = nicDao.findById(nicId);
        if (nic == null) {
            throw new InvalidParameterValueException("Unable to find the specified NIC.");
        }
        UserVmVO vmInstance = vmDao.findById(nic.getInstanceId());
        if (vmInstance == null) {
            throw new InvalidParameterValueException("Unable to find a virtual machine associated with the specified NIC.");
        }
        if (vmInstance.getHypervisorType() != HypervisorType.KVM) {
            throw new InvalidParameterValueException("Updating the VM NIC is only supported by the KVM hypervisor.");
        }
        NetworkVO network = networkDao.findById(nic.getNetworkId());
        if (network == null) {
            throw new InvalidParameterValueException("Unable to find NIC's network.");
        }
        accountManager.checkAccess(caller, null, true, vmInstance);

        if (isNicEnabled == null) {
            return vmInstance;
        }

        boolean success;
        try {
            success = itMgr.updateVmNic(vmInstance, nic, isNicEnabled);
        } catch (ResourceUnavailableException e) {
            throw new CloudRuntimeException(String.format("Unable to update NIC %s of VM %s in network %s due to: %s.", nic, vmInstance, network.getUuid(), e.getMessage()));
        } catch (ConcurrentOperationException e) {
            throw new CloudRuntimeException(String.format("Concurrent operations while updating NIC %s for VM %s: %s.", nic, vmInstance, e.getMessage()));
        }
        if (!success) {
            throw new CloudRuntimeException(String.format("Failed to update NIC %s of VM %s in network %s.", nic, vmInstance, network.getUuid()));
        }

        LOG.debug("Successfully updated NIC {} in network {} for VM {}.", nic, network.getUuid(), vmInstance);
        return vmInstance;
    }
}
