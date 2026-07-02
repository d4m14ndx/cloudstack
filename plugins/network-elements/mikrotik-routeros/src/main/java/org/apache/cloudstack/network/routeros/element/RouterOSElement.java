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
package org.apache.cloudstack.network.routeros.element;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;

import org.apache.cloudstack.network.routeros.RouterOSVmManager;

import com.cloud.agent.api.to.LoadBalancerTO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.UnsupportedServiceException;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetworkServiceProvider;
import com.cloud.network.PublicIpAddress;
import com.cloud.network.RemoteAccessVpn;
import com.cloud.network.VpnUser;
import com.cloud.network.element.DhcpServiceProvider;
import com.cloud.network.element.FirewallServiceProvider;
import com.cloud.network.element.IpDeployer;
import com.cloud.network.element.LoadBalancingServiceProvider;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.element.PortForwardingServiceProvider;
import com.cloud.network.element.RemoteAccessVPNServiceProvider;
import com.cloud.network.element.SourceNatServiceProvider;
import com.cloud.network.element.StaticNatServiceProvider;
import com.cloud.network.element.UserDataServiceProvider;
import com.cloud.network.lb.LoadBalancingRule;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.StaticNat;
import com.cloud.offering.NetworkOffering;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.NicProfile;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineProfile;

/**
 * Network element backing the {@code RouterOS} provider: a Mikrotik RouterOS
 * CHR appliance deployed per isolated network replaces the CloudStack virtual
 * router for Gateway/DHCP/SourceNat/StaticNat/PortForwarding/Firewall.
 *
 * Load balancing, remote access VPN and UserData are intentionally not
 * declared in the capability map and their provider methods raise
 * {@link UnsupportedServiceException} so misconfiguration fails loudly.
 */
public class RouterOSElement extends AdapterBase implements NetworkElement, DhcpServiceProvider, FirewallServiceProvider, SourceNatServiceProvider,
        StaticNatServiceProvider, PortForwardingServiceProvider, IpDeployer, LoadBalancingServiceProvider, RemoteAccessVPNServiceProvider,
        UserDataServiceProvider {

    private static final Map<Service, Map<Capability, String>> capabilities = setCapabilities();

    @Inject
    protected RouterOSVmManager _routerOSMgr;
    @Inject
    protected NetworkModel _networkModel;
    @Inject
    protected EntityManager _entityMgr;

    // ------------------------------------------------------------------
    // NetworkElement
    // ------------------------------------------------------------------

    @Override
    public Map<Service, Map<Capability, String>> getCapabilities() {
        return capabilities;
    }

    @Override
    public Provider getProvider() {
        return Provider.RouterOS;
    }

    protected boolean canHandle(final Network network, final Service service) {
        final DataCenter zone = _entityMgr.findById(DataCenter.class, network.getDataCenterId());
        if (zone == null || zone.getNetworkType() != NetworkType.Advanced) {
            logger.trace("{} only handles advanced zones", getName());
            return false;
        }
        if (network.getTrafficType() != TrafficType.Guest || network.getGuestType() != Network.GuestType.Isolated) {
            logger.trace("{} only handles isolated guest networks", getName());
            return false;
        }
        if (service == null) {
            return _networkModel.isProviderForNetwork(getProvider(), network.getId());
        }
        return _networkModel.isProviderSupportServiceInNetwork(network.getId(), service, getProvider());
    }

    @Override
    public boolean implement(final Network network, final NetworkOffering offering, final DeployDestination dest, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        if (!canHandle(network, null)) {
            return true;
        }
        return _routerOSMgr.deployForNetwork(network, dest) != null;
    }

    @Override
    public boolean prepare(final Network network, final NicProfile nic, final VirtualMachineProfile vm, final DeployDestination dest, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        if (!canHandle(network, null)) {
            return true;
        }
        if (vm.getType() == VirtualMachine.Type.User) {
            // make sure the appliance exists and is running before guest instances come up
            return _routerOSMgr.deployForNetwork(network, dest) != null;
        }
        return true;
    }

    @Override
    public boolean release(final Network network, final NicProfile nic, final VirtualMachineProfile vm, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException {
        return true;
    }

    @Override
    public boolean shutdown(final Network network, final ReservationContext context, final boolean cleanup) throws ConcurrentOperationException,
            ResourceUnavailableException {
        if (!canHandle(network, null)) {
            return true;
        }
        return _routerOSMgr.destroyForNetwork(network);
    }

    @Override
    public boolean destroy(final Network network, final ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException {
        if (!canHandle(network, null)) {
            return true;
        }
        return _routerOSMgr.destroyForNetwork(network);
    }

    @Override
    public boolean isReady(final PhysicalNetworkServiceProvider provider) {
        return true;
    }

    @Override
    public boolean shutdownProviderInstances(final PhysicalNetworkServiceProvider provider, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException {
        // appliances are tied to networks and are destroyed with them
        return true;
    }

    @Override
    public boolean canEnableIndividualServices() {
        return true;
    }

    @Override
    public boolean verifyServicesCombination(final Set<Service> services) {
        if (services.contains(Service.SourceNat)) {
            return true;
        }
        if (services.contains(Service.StaticNat) || services.contains(Service.PortForwarding) || services.contains(Service.Firewall)) {
            logger.warn("{} requires the SourceNat service to provide StaticNat, PortForwarding or Firewall; unsupported combination: {}", getName(), services);
            return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // IpDeployer / SourceNat
    // ------------------------------------------------------------------

    @Override
    public IpDeployer getIpDeployer(final Network network) {
        return this;
    }

    @Override
    public boolean applyIps(final Network network, final List<? extends PublicIpAddress> ipAddress, final Set<Service> services) throws ResourceUnavailableException {
        if (!canHandle(network, null)) {
            return false;
        }
        return _routerOSMgr.applyIps(network, ipAddress);
    }

    // ------------------------------------------------------------------
    // Firewall / StaticNat / PortForwarding
    // ------------------------------------------------------------------

    @Override
    public boolean applyFWRules(final Network network, final List<? extends FirewallRule> rules) throws ResourceUnavailableException {
        if (!canHandle(network, Service.Firewall)) {
            return false;
        }
        return _routerOSMgr.applyFirewallRules(network, rules);
    }

    @Override
    public boolean applyStaticNats(final Network network, final List<? extends StaticNat> rules) throws ResourceUnavailableException {
        if (!canHandle(network, Service.StaticNat)) {
            return false;
        }
        return _routerOSMgr.applyStaticNats(network, rules);
    }

    @Override
    public boolean applyPFRules(final Network network, final List<PortForwardingRule> rules) throws ResourceUnavailableException {
        if (!canHandle(network, Service.PortForwarding)) {
            return false;
        }
        return _routerOSMgr.applyPortForwardingRules(network, rules);
    }

    // ------------------------------------------------------------------
    // DHCP
    // ------------------------------------------------------------------

    @Override
    public boolean addDhcpEntry(final Network network, final NicProfile nic, final VirtualMachineProfile vm, final DeployDestination dest, final ReservationContext context)
            throws ConcurrentOperationException, InsufficientCapacityException, ResourceUnavailableException {
        if (!canHandle(network, Service.Dhcp)) {
            return false;
        }
        return _routerOSMgr.addDhcpEntry(network, nic);
    }

    @Override
    public boolean configDhcpSupportForSubnet(final Network network, final NicProfile nic, final VirtualMachineProfile vm, final DeployDestination dest,
            final ReservationContext context) throws ConcurrentOperationException, InsufficientCapacityException, ResourceUnavailableException {
        if (!canHandle(network, Service.Dhcp)) {
            return false;
        }
        return _routerOSMgr.configureDhcpForNetwork(network);
    }

    @Override
    public boolean removeDhcpSupportForSubnet(final Network network) throws ResourceUnavailableException {
        if (!canHandle(network, Service.Dhcp)) {
            return false;
        }
        return _routerOSMgr.removeDhcpForNetwork(network);
    }

    @Override
    public boolean setExtraDhcpOptions(final Network network, final long nicId, final Map<Integer, String> dhcpOptions) {
        if (dhcpOptions == null || dhcpOptions.isEmpty()) {
            return true;
        }
        logger.warn("{} does not support extra DHCP options; requested options for nic {} are ignored", getName(), nicId);
        return false;
    }

    @Override
    public boolean removeDhcpEntry(final Network network, final NicProfile nic, final VirtualMachineProfile vmProfile) throws ResourceUnavailableException {
        if (!canHandle(network, Service.Dhcp)) {
            return false;
        }
        return _routerOSMgr.removeDhcpEntry(network, nic);
    }

    // ------------------------------------------------------------------
    // Honest stubs: services this provider does not (yet) implement
    // ------------------------------------------------------------------

    @Override
    public boolean applyLBRules(final Network network, final List<LoadBalancingRule> rules) throws ResourceUnavailableException {
        throw new UnsupportedServiceException(getName() + " does not support load balancing; use a dedicated LB provider in the network offering");
    }

    @Override
    public boolean validateLBRule(final Network network, final LoadBalancingRule rule) {
        throw new UnsupportedServiceException(getName() + " does not support load balancing; use a dedicated LB provider in the network offering");
    }

    @Override
    public List<LoadBalancerTO> updateHealthChecks(final Network network, final List<LoadBalancingRule> lbrules) {
        return null;
    }

    @Override
    public boolean handlesOnlyRulesInTransitionState() {
        return false;
    }

    @Override
    public String[] applyVpnUsers(final RemoteAccessVpn vpn, final List<? extends VpnUser> users) throws ResourceUnavailableException {
        throw new UnsupportedServiceException(getName() + " does not support remote access VPN");
    }

    @Override
    public boolean startVpn(final RemoteAccessVpn vpn) throws ResourceUnavailableException {
        throw new UnsupportedServiceException(getName() + " does not support remote access VPN");
    }

    @Override
    public boolean stopVpn(final RemoteAccessVpn vpn) throws ResourceUnavailableException {
        throw new UnsupportedServiceException(getName() + " does not support remote access VPN");
    }

    @Override
    public boolean addPasswordAndUserdata(final Network network, final NicProfile nic, final VirtualMachineProfile vm, final DeployDestination dest,
            final ReservationContext context) throws ConcurrentOperationException, InsufficientCapacityException, ResourceUnavailableException {
        throw new UnsupportedServiceException(getName() + " does not support the UserData service; use ConfigDrive in the network offering");
    }

    @Override
    public boolean savePassword(final Network network, final NicProfile nic, final VirtualMachineProfile vm) throws ResourceUnavailableException {
        throw new UnsupportedServiceException(getName() + " does not support the UserData service; use ConfigDrive in the network offering");
    }

    @Override
    public boolean saveUserData(final Network network, final NicProfile nic, final VirtualMachineProfile vm) throws ResourceUnavailableException {
        throw new UnsupportedServiceException(getName() + " does not support the UserData service; use ConfigDrive in the network offering");
    }

    @Override
    public boolean saveSSHKey(final Network network, final NicProfile nic, final VirtualMachineProfile vm, final String sshPublicKey) throws ResourceUnavailableException {
        throw new UnsupportedServiceException(getName() + " does not support the UserData service; use ConfigDrive in the network offering");
    }

    @Override
    public boolean saveHypervisorHostname(final NicProfile profile, final Network network, final VirtualMachineProfile vm, final DeployDestination dest)
            throws ResourceUnavailableException {
        return true;
    }

    // ------------------------------------------------------------------
    // Capabilities
    // ------------------------------------------------------------------

    protected static Map<Service, Map<Capability, String>> setCapabilities() {
        final Map<Service, Map<Capability, String>> capabilities = new HashMap<>();

        final Map<Capability, String> gatewayCapabilities = new HashMap<>();
        gatewayCapabilities.put(Capability.RedundantRouter, "false");
        capabilities.put(Service.Gateway, gatewayCapabilities);

        final Map<Capability, String> dhcpCapabilities = new HashMap<>();
        dhcpCapabilities.put(Capability.DhcpAccrossMultipleSubnets, "false");
        capabilities.put(Service.Dhcp, dhcpCapabilities);

        final Map<Capability, String> sourceNatCapabilities = new HashMap<>();
        sourceNatCapabilities.put(Capability.SupportedSourceNatTypes, "peraccount");
        sourceNatCapabilities.put(Capability.RedundantRouter, "false");
        capabilities.put(Service.SourceNat, sourceNatCapabilities);

        capabilities.put(Service.StaticNat, null);

        final Map<Capability, String> portForwardingCapabilities = new HashMap<>();
        portForwardingCapabilities.put(Capability.SupportedProtocols, NetUtils.TCP_PROTO + "," + NetUtils.UDP_PROTO);
        capabilities.put(Service.PortForwarding, portForwardingCapabilities);

        final Map<Capability, String> firewallCapabilities = new HashMap<>();
        firewallCapabilities.put(Capability.SupportedProtocols, "tcp,udp,icmp");
        firewallCapabilities.put(Capability.SupportedEgressProtocols, "tcp,udp,icmp,all");
        firewallCapabilities.put(Capability.SupportedTrafficDirection, "ingress, egress");
        firewallCapabilities.put(Capability.MultipleIps, "true");
        capabilities.put(Service.Firewall, firewallCapabilities);

        return capabilities;
    }
}
