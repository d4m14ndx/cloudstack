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
package org.apache.cloudstack.network.routeros;

import java.util.List;

import org.apache.cloudstack.framework.config.ConfigKey;

import com.cloud.deploy.DeployDestination;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddress;
import com.cloud.network.Network;
import com.cloud.network.PublicIpAddress;
import com.cloud.network.element.RouterOSDeviceVO;
import com.cloud.network.rules.FirewallRule;
import com.cloud.network.rules.PortForwardingRule;
import com.cloud.network.rules.StaticNat;
import com.cloud.network.vpc.NetworkACLItem;
import com.cloud.network.vpc.StaticRouteProfile;
import com.cloud.network.vpc.Vpc;
import com.cloud.utils.component.Manager;
import com.cloud.vm.NicProfile;

/**
 * Deploys and programs Mikrotik RouterOS CHR appliances acting as the router
 * of isolated guest networks and VPCs.
 */
public interface RouterOSVmManager extends Manager {

    ConfigKey<String> RouterOSTemplateName = new ConfigKey<>("Network", String.class, "routeros.template.name", "routeros-chr",
            "Name of the registered template used to deploy Mikrotik RouterOS CHR appliances", true);
    ConfigKey<String> RouterOSServiceOfferingUuid = new ConfigKey<>("Network", String.class, "routeros.service.offering", null,
            "UUID of the compute offering used for RouterOS CHR appliances; when unset a built-in default offering is used", true);
    ConfigKey<Integer> RouterOSApiPort = new ConfigKey<>("Network", Integer.class, "routeros.api.port", "443",
            "Port of the RouterOS REST API (www-ssl service) on deployed CHR appliances", true);
    ConfigKey<String> RouterOSApiUser = new ConfigKey<>("Network", String.class, "routeros.api.user", "admin",
            "RouterOS user the management server authenticates with", true);
    ConfigKey<String> RouterOSTemplatePassword = new ConfigKey<>("Network", String.class, "routeros.template.password", "",
            "Initial password of the RouterOS API user as baked into the CHR template; rotated to a generated per-appliance secret during provisioning", true);
    ConfigKey<Integer> RouterOSApiTimeout = new ConfigKey<>("Network", Integer.class, "routeros.api.timeout", "30",
            "HTTP timeout in seconds for RouterOS REST API calls", true);
    ConfigKey<Integer> RouterOSProvisionWait = new ConfigKey<>("Network", Integer.class, "routeros.provision.wait", "300",
            "Seconds to wait for a freshly started CHR appliance to expose its REST API before deferring provisioning", true);

    /** Prefix of every RouterOS object comment written by this plugin. */
    String COMMENT_PREFIX = "cs-";

    RouterOSDeviceVO deployForNetwork(Network network, DeployDestination dest) throws InsufficientCapacityException, ResourceUnavailableException, ConcurrentOperationException;

    RouterOSDeviceVO deployForVpc(Vpc vpc, DeployDestination dest) throws InsufficientCapacityException, ResourceUnavailableException, ConcurrentOperationException;

    boolean addVpcTier(Network network) throws InsufficientCapacityException, ResourceUnavailableException, ConcurrentOperationException;

    boolean removeVpcTier(Network network) throws ResourceUnavailableException;

    boolean destroyForNetwork(Network network) throws ResourceUnavailableException, ConcurrentOperationException;

    boolean destroyForVpc(Vpc vpc) throws ResourceUnavailableException, ConcurrentOperationException;

    boolean applyFirewallRules(Network network, List<? extends FirewallRule> rules) throws ResourceUnavailableException;

    boolean applyPortForwardingRules(Network network, List<PortForwardingRule> rules) throws ResourceUnavailableException;

    boolean applyStaticNats(Network network, List<? extends StaticNat> rules) throws ResourceUnavailableException;

    boolean applyIps(Network network, List<? extends PublicIpAddress> ips) throws ResourceUnavailableException;

    boolean applyNetworkACLs(Network network, List<? extends NetworkACLItem> rules) throws ResourceUnavailableException;

    boolean applyStaticRoutes(Vpc vpc, List<StaticRouteProfile> routes) throws ResourceUnavailableException;

    boolean updateSourceNatIp(Vpc vpc, IpAddress address);

    boolean configureDhcpForNetwork(Network network) throws ResourceUnavailableException;

    boolean removeDhcpForNetwork(Network network) throws ResourceUnavailableException;

    boolean addDhcpEntry(Network network, NicProfile nic) throws ResourceUnavailableException;

    boolean removeDhcpEntry(Network network, NicProfile nic) throws ResourceUnavailableException;
}
