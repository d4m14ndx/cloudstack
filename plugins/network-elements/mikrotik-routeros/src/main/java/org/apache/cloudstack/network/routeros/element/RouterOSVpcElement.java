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

import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.UnsupportedServiceException;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.Site2SiteVpnConnection;
import com.cloud.network.element.NetworkACLServiceProvider;
import com.cloud.network.element.Site2SiteVpnServiceProvider;
import com.cloud.network.element.VpcProvider;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.vpc.NetworkACLItem;
import com.cloud.network.vpc.PrivateGateway;
import com.cloud.network.vpc.StaticRouteProfile;
import com.cloud.network.vpc.Vpc;
import com.cloud.offering.NetworkOffering;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.ReservationContext;

/**
 * VPC flavour of the RouterOS element: one CHR appliance per VPC routes all
 * tiers, programs per-tier network ACLs and VPC static routes. Private
 * gateways and site-to-site VPN are not implemented yet and fail loudly.
 */
public class RouterOSVpcElement extends RouterOSElement implements VpcProvider, NetworkACLServiceProvider, Site2SiteVpnServiceProvider {

    private static final Map<Service, Map<Capability, String>> capabilities = setVpcCapabilities();

    @Override
    public Provider getProvider() {
        return Provider.VpcRouterOS;
    }

    @Override
    public Map<Service, Map<Capability, String>> getCapabilities() {
        return capabilities;
    }

    @Override
    protected boolean canHandle(final Network network, final Service service) {
        final DataCenter zone = _entityMgr.findById(DataCenter.class, network.getDataCenterId());
        if (zone == null || zone.getNetworkType() != NetworkType.Advanced) {
            return false;
        }
        if (network.getTrafficType() != TrafficType.Guest || network.getVpcId() == null) {
            logger.trace("{} only handles guest networks that are part of a VPC", getName());
            return false;
        }
        if (service == null) {
            return _networkModel.isProviderForNetwork(getProvider(), network.getId());
        }
        return _networkModel.isProviderSupportServiceInNetwork(network.getId(), service, getProvider());
    }

    // ------------------------------------------------------------------
    // VpcProvider
    // ------------------------------------------------------------------

    @Override
    public boolean implementVpc(final Vpc vpc, final DeployDestination dest, final ReservationContext context) throws ConcurrentOperationException,
            ResourceUnavailableException, InsufficientCapacityException {
        return _routerOSMgr.deployForVpc(vpc, dest) != null;
    }

    @Override
    public boolean shutdownVpc(final Vpc vpc, final ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException {
        return _routerOSMgr.destroyForVpc(vpc);
    }

    @Override
    public boolean implement(final Network network, final NetworkOffering offering, final DeployDestination dest, final ReservationContext context)
            throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        if (!canHandle(network, null)) {
            return true;
        }
        // the VPC appliance is deployed by implementVpc; here the tier is plumbed into it
        return _routerOSMgr.addVpcTier(network);
    }

    @Override
    public boolean shutdown(final Network network, final ReservationContext context, final boolean cleanup) throws ConcurrentOperationException,
            ResourceUnavailableException {
        if (!canHandle(network, null)) {
            return true;
        }
        return _routerOSMgr.removeVpcTier(network);
    }

    @Override
    public boolean destroy(final Network network, final ReservationContext context) throws ConcurrentOperationException, ResourceUnavailableException {
        if (!canHandle(network, null)) {
            return true;
        }
        return _routerOSMgr.removeVpcTier(network);
    }

    @Override
    public boolean createPrivateGateway(final PrivateGateway gateway) throws ConcurrentOperationException, ResourceUnavailableException {
        throw new UnsupportedServiceException(getName() + " does not support VPC private gateways yet");
    }

    @Override
    public boolean deletePrivateGateway(final PrivateGateway privateGateway) throws ConcurrentOperationException, ResourceUnavailableException {
        throw new UnsupportedServiceException(getName() + " does not support VPC private gateways yet");
    }

    @Override
    public boolean applyStaticRoutes(final Vpc vpc, final List<StaticRouteProfile> routes) throws ResourceUnavailableException {
        return _routerOSMgr.applyStaticRoutes(vpc, routes);
    }

    @Override
    public boolean applyACLItemsToPrivateGw(final PrivateGateway gateway, final List<? extends NetworkACLItem> rules) throws ResourceUnavailableException {
        throw new UnsupportedServiceException(getName() + " does not support VPC private gateways yet");
    }

    @Override
    public boolean updateVpcSourceNatIp(final Vpc vpc, final IpAddress address) {
        return _routerOSMgr.updateSourceNatIp(vpc, address);
    }

    // ------------------------------------------------------------------
    // NetworkACLServiceProvider
    // ------------------------------------------------------------------

    @Override
    public boolean applyNetworkACLs(final Network network, final List<? extends NetworkACLItem> rules) throws ResourceUnavailableException {
        if (!canHandle(network, Service.NetworkACL)) {
            return false;
        }
        return _routerOSMgr.applyNetworkACLs(network, rules);
    }

    @Override
    public boolean reorderAclRules(final Vpc vpc, final List<? extends Network> networks, final List<? extends NetworkACLItem> networkACLItems) {
        try {
            boolean result = true;
            for (final Network network : networks) {
                result = result && _routerOSMgr.applyNetworkACLs(network, networkACLItems);
            }
            return result;
        } catch (final ResourceUnavailableException e) {
            logger.error("Failed to reorder ACL rules on the RouterOS appliance of VPC {}: {}", vpc.getName(), e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------
    // Site2SiteVpnServiceProvider (stub)
    // ------------------------------------------------------------------

    @Override
    public boolean startSite2SiteVpn(final Site2SiteVpnConnection conn) throws ResourceUnavailableException {
        throw new UnsupportedServiceException(getName() + " does not support site-to-site VPN yet");
    }

    @Override
    public boolean stopSite2SiteVpn(final Site2SiteVpnConnection conn) throws ResourceUnavailableException {
        throw new UnsupportedServiceException(getName() + " does not support site-to-site VPN yet");
    }

    // ------------------------------------------------------------------
    // Capabilities
    // ------------------------------------------------------------------

    protected static Map<Service, Map<Capability, String>> setVpcCapabilities() {
        final Map<Service, Map<Capability, String>> capabilities = new HashMap<>();

        final Map<Capability, String> gatewayCapabilities = new HashMap<>();
        gatewayCapabilities.put(Capability.RedundantRouter, "false");
        capabilities.put(Service.Gateway, gatewayCapabilities);

        final Map<Capability, String> dhcpCapabilities = new HashMap<>();
        dhcpCapabilities.put(Capability.DhcpAccrossMultipleSubnets, "false");
        capabilities.put(Service.Dhcp, dhcpCapabilities);

        final Map<Capability, String> sourceNatCapabilities = new HashMap<>();
        sourceNatCapabilities.put(Capability.SupportedSourceNatTypes, "perzone, peraccount");
        sourceNatCapabilities.put(Capability.RedundantRouter, "false");
        capabilities.put(Service.SourceNat, sourceNatCapabilities);

        capabilities.put(Service.StaticNat, null);

        final Map<Capability, String> portForwardingCapabilities = new HashMap<>();
        portForwardingCapabilities.put(Capability.SupportedProtocols, NetUtils.TCP_PROTO + "," + NetUtils.UDP_PROTO);
        capabilities.put(Service.PortForwarding, portForwardingCapabilities);

        final Map<Capability, String> aclCapabilities = new HashMap<>();
        aclCapabilities.put(Capability.SupportedProtocols, "tcp,udp,icmp,all");
        capabilities.put(Service.NetworkACL, aclCapabilities);

        return capabilities;
    }

    /**
     * The firewall service does not apply inside VPCs (ACLs are used instead).
     */
    @Override
    public boolean applyFWRules(final Network network, final List<? extends FirewallRule> rules) throws ResourceUnavailableException {
        throw new UnsupportedServiceException(getName() + " uses network ACLs inside VPCs; the Firewall service is not available");
    }
}
