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
package com.cloud.network;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import jakarta.inject.Inject;
import javax.naming.ConfigurationException;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.apache.cloudstack.acl.SecurityChecker.AccessType;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.address.ReleasePodIpCmdByAdmin;
import org.apache.cloudstack.api.command.admin.network.CreateNetworkCmdByAdmin;
import org.apache.cloudstack.api.command.admin.network.DedicateGuestVlanRangeCmd;
import org.apache.cloudstack.api.command.admin.network.ListDedicatedGuestVlanRangesCmd;
import org.apache.cloudstack.api.command.admin.network.ListGuestVlansCmd;
import org.apache.cloudstack.api.command.admin.network.UpdateNetworkCmdByAdmin;
import org.apache.cloudstack.api.command.admin.usage.ListTrafficTypeImplementorsCmd;
import org.apache.cloudstack.api.command.user.address.RemoveQuarantinedIpCmd;
import org.apache.cloudstack.api.command.user.address.UpdateQuarantinedIpCmd;
import org.apache.cloudstack.api.command.user.network.CreateNetworkCmd;
import org.apache.cloudstack.api.command.user.network.CreateNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.ListNetworksCmd;
import org.apache.cloudstack.api.command.user.network.RemoveNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.ResetNetworkPermissionsCmd;
import org.apache.cloudstack.api.command.user.network.RestartNetworkCmd;
import org.apache.cloudstack.api.command.user.network.UpdateNetworkCmd;
import org.apache.cloudstack.api.command.user.vm.ListNicsCmd;
import org.apache.cloudstack.api.response.AcquirePodIpCmdResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.cloudstack.framework.config.ConfigKey;
import org.apache.cloudstack.framework.config.Configurable;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.PublishScope;
import org.apache.cloudstack.network.RoutedIpv4Manager;
import org.apache.cloudstack.network.dao.NetworkPermissionDao;
import org.apache.cloudstack.network.element.InternalLoadBalancerElementService;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;

import com.cloud.agent.api.Answer;
import com.cloud.agent.api.Command;
import com.cloud.agent.api.to.IpAddressTO;
import com.cloud.agent.api.to.NicTO;
import com.cloud.agent.manager.Commands;
import com.cloud.alert.AlertManager;
import com.cloud.api.ApiDBUtils;
import com.cloud.api.query.dao.DomainRouterJoinDao;
import com.cloud.bgp.BGPService;
import com.cloud.configuration.Config;
import com.cloud.configuration.ConfigurationManager;
import com.cloud.configuration.Resource;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.ASNumberDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterVnetDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.deploy.DeployDestination;
import com.cloud.domain.Domain;
import com.cloud.domain.DomainVO;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientAddressCapacityException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.exception.UnsupportedServiceException;
import com.cloud.host.Host;
import com.cloud.host.dao.HostDao;
import com.cloud.hypervisor.Hypervisor;
import com.cloud.network.IpAddress.State;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.IpAddresses;
import com.cloud.network.Network.PVlanType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.VirtualRouterProvider.Type;
import com.cloud.network.addr.PublicIp;
import com.cloud.network.dao.AccountGuestVlanMapDao;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.Ipv6GuestPrefixSubnetNetworkMapDao;
import com.cloud.network.dao.LoadBalancerDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDetailVO;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.network.dao.NetworkDomainDao;
import com.cloud.network.dao.NetworkServiceMapDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.OvsProviderDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderDao;
import com.cloud.network.dao.PhysicalNetworkServiceProviderVO;
import com.cloud.network.dao.PhysicalNetworkTrafficTypeDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.dao.VirtualRouterProviderDao;
import com.cloud.network.element.NetworkElement;
import com.cloud.network.element.VirtualRouterProviderVO;
import com.cloud.network.guru.GuestNetworkGuru;
import com.cloud.network.guru.NetworkGuru;
import com.cloud.network.nsx.NsxService;
import com.cloud.network.router.CommandSetupHelper;
import com.cloud.network.router.NetworkHelper;
import com.cloud.network.rules.FirewallRule.Purpose;
import com.cloud.network.rules.FirewallRuleVO;
import com.cloud.network.rules.RulesManager;
import com.cloud.network.rules.dao.PortForwardingRulesDao;
import com.cloud.network.security.SecurityGroupService;
import com.cloud.network.vpc.NetworkACL;
import com.cloud.network.vpc.PrivateIpVO;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcGatewayVO;
import com.cloud.network.vpc.VpcManager;
import com.cloud.network.vpc.dao.NetworkACLDao;
import com.cloud.network.vpc.dao.PrivateIpDao;
import com.cloud.network.vpc.dao.VpcDao;
import com.cloud.network.vpc.dao.VpcGatewayDao;
import com.cloud.network.vpc.dao.VpcOfferingDao;
import com.cloud.offering.NetworkOffering;
import com.cloud.offering.ServiceOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.org.Grouping;
import com.cloud.projects.ProjectManager;
import com.cloud.service.ServiceOfferingVO;
import com.cloud.service.dao.ServiceOfferingDao;
import com.cloud.tags.dao.ResourceTagDao;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.AccountService;
import com.cloud.user.DomainManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.UserVO;
import com.cloud.user.dao.AccountDao;
import com.cloud.user.dao.UserDao;
import com.cloud.utils.Journal;
import com.cloud.utils.NumbersUtil;
import com.cloud.utils.Pair;
import com.cloud.utils.component.AdapterBase;
import com.cloud.utils.component.ComponentContext;
import com.cloud.utils.component.ManagerBase;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionCallbackWithException;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.exception.ExceptionUtil;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.DomainRouterVO;
import com.cloud.vm.Nic;
import com.cloud.vm.NicProfile;
import com.cloud.vm.NicSecondaryIp;
import com.cloud.vm.NicVO;
import com.cloud.vm.ReservationContext;
import com.cloud.vm.ReservationContextImpl;
import com.cloud.vm.UserVmVO;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;
import com.cloud.vm.VirtualMachineManager;
import com.cloud.vm.VirtualMachineProfile;
import com.cloud.vm.VirtualMachineProfileImpl;
import com.cloud.vm.dao.DomainRouterDao;
import com.cloud.vm.dao.NicDao;
import com.cloud.vm.dao.NicSecondaryIpDao;
import com.cloud.vm.dao.UserVmDao;
import com.cloud.vm.dao.VMInstanceDao;
import com.googlecode.ipv6.IPv6Address;

/**
 * NetworkServiceImpl implements NetworkService.
 */
public class NetworkServiceImpl extends ManagerBase implements NetworkService, Configurable {

    private static final ConfigKey<Boolean> AllowDuplicateNetworkName = new ConfigKey<>("Advanced", Boolean.class,
            "allow.duplicate.networkname", "true", "Allow creating networks with same name in Account", true, ConfigKey.Scope.Account);
    private static final ConfigKey<Boolean> AllowEmptyStartEndIpAddress = new ConfigKey<>("Advanced", Boolean.class,
            "allow.empty.start.end.ipaddress", "true", "Allow creating network without mentioning start and end IP address",
            true, ConfigKey.Scope.Account);
    public static final ConfigKey<Boolean> AllowUsersToMakeNetworksRedundant = new ConfigKey<>("Advanced", Boolean.class,
            "allow.users.to.make.networks.redundant", "true", "Allow Users to make Networks Redundant",
            true, ConfigKey.Scope.Global);
    private static final long MIN_VLAN_ID = 0L;
    private static final long MAX_VLAN_ID = 4095L; // 2^12 - 1
    private static final long MIN_GRE_KEY = 0L;
    private static final long MAX_GRE_KEY = 4294967295L; // 2^32 -1
    private static final long MIN_VXLAN_VNI = 0L;
    /**
     // MAX_VXLAN_VNI should be 16777215L (2^24-1), but Linux vxlan interface doesn't accept VNI:2^24-1 now.
     // It seems a bug.
     // Is this still valid (per 2023?)
     */
    private static final long MAX_VXLAN_VNI = 16777214L; // 2^24 -2
    private static final String NETWORK_OFFERING_ID = "networkOfferingId";

    @Inject
    DataCenterDao _dcDao = null;
    @Inject
    VlanDao _vlanDao = null;
    @Inject
    IPAddressDao _ipAddressDao = null;
    @Inject
    AccountDao _accountDao = null;
    @Inject
    DomainDao _domainDao = null;
    @Inject
    UserDao _userDao = null;
    @Inject
    ConfigurationDao _configDao;
    @Inject
    UserVmDao _userVmDao = null;
    @Inject
    AccountManager _accountMgr;
    @Inject
    ConfigurationManager _configMgr;
    @Inject
    NetworkOfferingDao _networkOfferingDao = null;
    @Inject
    NetworkDao _networksDao = null;
    @Inject
    NetworkPermissionDao _networkPermissionDao = null;
    @Inject
    NicDao _nicDao = null;
    @Inject
    RulesManager _rulesMgr;
    List<NetworkGuru> _networkGurus;
    @Inject
    NetworkDomainDao _networkDomainDao;
    @Inject
    VMInstanceDao _vmDao;
    @Inject
    FirewallRulesDao _firewallDao;
    @Inject
    ResourceLimitService _resourceLimitMgr;
    @Inject
    DomainManager _domainMgr;
    @Inject
    ProjectManager _projectMgr;
    @Inject
    NetworkOfferingServiceMapDao _ntwkOfferingSrvcDao;
    @Inject
    PhysicalNetworkDao _physicalNetworkDao;
    @Inject
    PhysicalNetworkServiceProviderDao _pNSPDao;
    @Inject
    PhysicalNetworkTrafficTypeDao _pNTrafficTypeDao;
    @Inject
    NetworkServiceMapDao _ntwkSrvcDao;
    @Inject
    StorageNetworkManager _stnwMgr;
    @Inject
    VpcManager _vpcMgr;
    @Inject
    PrivateIpDao _privateIpDao;
    @Inject
    ResourceTagDao _resourceTagDao;
    @Inject
    NetworkOrchestrationService _networkMgr;
    @Inject
    NetworkModel _networkModel;
    @Inject
    NicSecondaryIpDao _nicSecondaryIpDao;
    @Inject
    PortForwardingRulesDao _portForwardingDao;
    @Inject
    HostDao _hostDao;
    @Inject
    DataCenterVnetDao _dcVnetDao;
    @Inject
    AccountGuestVlanMapDao _accountGuestVlanMapDao;
    @Inject
    VpcDao _vpcDao;
    @Inject
    NetworkACLDao _networkACLDao;
    @Inject
    OvsProviderDao _ovsProviderDao;
    @Inject
    IpAddressManager _ipAddrMgr;
    @Inject
    Ipv6AddressManager ipv6AddrMgr;
    @Inject
    EntityManager _entityMgr;
    @Inject
    public SecurityGroupService _securityGroupService;
    @Inject
    MessageBus _messageBus;
    @Inject
    NetworkDetailsDao _networkDetailsDao;
    @Inject
    LoadBalancerDao _loadBalancerDao;
    @Inject
    NetworkMigrationManager _networkMigrationManager;
    @Inject
    VpcOfferingDao _vpcOfferingDao;
    @Inject
    AccountService _accountService;
    @Inject
    VirtualMachineManager vmManager;
    @Inject
    Ipv6Service ipv6Service;
    @Inject
    Ipv6GuestPrefixSubnetNetworkMapDao ipv6GuestPrefixSubnetNetworkMapDao;
    @Inject
    VpcGatewayDao vpcGatewayDao;
    @Inject
    AlertManager alertManager;
    @Inject
    DomainRouterDao routerDao;
    @Inject
    DomainRouterJoinDao routerJoinDao;
    @Inject
    CommandSetupHelper commandSetupHelper;
    @Inject
    ServiceOfferingDao serviceOfferingDao;
    @Inject
    private VirtualRouterProviderDao virtualRouterProviderDao;
    @Inject
    RoutedIpv4Manager routedIpv4Manager;
    @Inject
    private BGPService bgpService;
    @Inject
    private ASNumberDao asNumberDao;
    @Inject
    NetworkPermissionService networkPermissionService;
    @Inject
    PublicIpQuarantineManager publicIpQuarantineManager;
    @Inject
    DedicatedGuestVlanRangeService dedicatedGuestVlanRangeService;

    List<InternalLoadBalancerElementService> internalLoadBalancerElementServices = new ArrayList<>();
    Map<String, InternalLoadBalancerElementService> internalLoadBalancerElementServiceMap = new HashMap<>();

    @Autowired
    @Qualifier("networkHelper")
    protected NetworkHelper networkHelper;

    @Inject
    protected NicSecondaryIpService nicSecondaryIpService;

    @Inject
    protected IpAddressLifecycleService ipAddressLifecycleService;

    @Inject
    protected PhysicalNetworkManagementService physicalNetworkManagementService;

    @Inject
    protected NetworkSearchService networkSearchService;

    @Inject
    protected NetworkMigrationService networkMigrationService;

    @Inject
    protected NetworkMtuService networkMtuService;

    @Inject
    protected NetworkCreationValidationService networkCreationValidationService;

    int _cidrLimit;
    boolean _allowSubdomainNetworkAccess;

    private Map<String, String> _configs;

    /* Get a list of IPs, classify them by service */
    protected Map<PublicIp, Set<Service>> getIpToServices(List<PublicIp> publicIps, boolean rulesRevoked, boolean includingFirewall) {
        Map<PublicIp, Set<Service>> ipToServices = new HashMap<PublicIp, Set<Service>>();

        if (publicIps != null && !publicIps.isEmpty()) {
            Set<Long> networkSNAT = new HashSet<Long>();
            for (PublicIp ip : publicIps) {
                Set<Service> services = ipToServices.get(ip);
                if (services == null) {
                    services = new HashSet<Service>();
                }
                if (ip.isSourceNat()) {
                    if (!networkSNAT.contains(ip.getAssociatedWithNetworkId())) {
                        services.add(Service.SourceNat);
                        networkSNAT.add(ip.getAssociatedWithNetworkId());
                    } else {
                        CloudRuntimeException ex = new CloudRuntimeException("Multiple generic source NAT IPs provided for network");
                        // see the IPAddressVO.java class.
                        IPAddressVO ipAddr = ApiDBUtils.findIpAddressById(ip.getAssociatedWithNetworkId());
                        String ipAddrUuid = ip.getAssociatedWithNetworkId().toString();
                        if (ipAddr != null) {
                            ipAddrUuid = ipAddr.getUuid();
                        }
                        ex.addProxyObject(ipAddrUuid, "networkId");
                        throw ex;
                    }
                }
                ipToServices.put(ip, services);

                // if IP in allocating state then it will not have any rules attached so skip IPAssoc to network service
                // provider
                if (ip.getState() == State.Allocating) {
                    continue;
                }

                // check if any active rules are applied on the public IP
                Set<Purpose> purposes = getPublicIpPurposeInRules(ip, false, includingFirewall);
                // Firewall rules didn't cover static NAT
                if (ip.isOneToOneNat() && ip.getAssociatedWithVmId() != null) {
                    if (purposes == null) {
                        purposes = new HashSet<Purpose>();
                    }
                    purposes.add(Purpose.StaticNat);
                }
                if (purposes == null || purposes.isEmpty()) {
                    // since no active rules are there check if any rules are applied on the public IP but are in
// revoking state

                    purposes = getPublicIpPurposeInRules(ip, true, includingFirewall);
                    if (ip.isOneToOneNat()) {
                        if (purposes == null) {
                            purposes = new HashSet<Purpose>();
                        }
                        purposes.add(Purpose.StaticNat);
                    }
                    if (purposes == null || purposes.isEmpty()) {
                        // IP is not being used for any purpose so skip IPAssoc to network service provider
                        continue;
                    } else {
                        if (rulesRevoked) {
                            // no active rules/revoked rules are associated with this public IP, so remove the
// association with the provider
                            ip.setState(State.Releasing);
                        } else {
                            if (ip.getState() == State.Releasing) {
                                // rules are not revoked yet, so don't let the network service provider revoke the IP
// association
                                // mark IP is allocated so that IP association will not be removed from the provider
                                ip.setState(State.Allocated);
                            }
                        }
                    }
                }
                if (purposes.contains(Purpose.StaticNat)) {
                    services.add(Service.StaticNat);
                }
                if (purposes.contains(Purpose.LoadBalancing)) {
                    services.add(Service.Lb);
                }
                if (purposes.contains(Purpose.PortForwarding)) {
                    services.add(Service.PortForwarding);
                }
                if (purposes.contains(Purpose.Vpn)) {
                    services.add(Service.Vpn);
                }
                if (purposes.contains(Purpose.Firewall)) {
                    services.add(Service.Firewall);
                }
                if (services.isEmpty()) {
                    continue;
                }
                ipToServices.put(ip, services);
            }
        }
        return ipToServices;
    }

    protected boolean canIpUsedForNonConserveService(PublicIp ip, Service service) {
        // If it's non-conserve mode, then the new IP should not be used by any other services
        List<PublicIp> ipList = new ArrayList<PublicIp>();
        ipList.add(ip);
        Map<PublicIp, Set<Service>> ipToServices = getIpToServices(ipList, false, false);
        Set<Service> services = ipToServices.get(ip);
        // Not used currently, safe
        if (services == null || services.isEmpty()) {
            return true;
        }
        // Since it's non-conserve mode, only one service should be used for IP
        if (services.size() != 1) {
            throw new InvalidParameterValueException("There are multiple services used IP " + ip.getAddress() + ".");
        }
        if (service != null && !((Service)services.toArray()[0] == service || service.equals(Service.Firewall))) {
            throw new InvalidParameterValueException("The IP " + ip.getAddress() + " is already used as " + ((Service)services.toArray()[0]).getName() + " rather than " + service.getName());
        }
        return true;
    }

    protected boolean canIpsUsedForNonConserve(List<PublicIp> publicIps) {
        boolean result = true;
        for (PublicIp ip : publicIps) {
            result = canIpUsedForNonConserveService(ip, null);
            if (!result) {
                break;
            }
        }
        return result;
    }

    private boolean canIpsUseOffering(List<PublicIp> publicIps, long offeringId) {
        Map<PublicIp, Set<Service>> ipToServices = getIpToServices(publicIps, false, true);
        Map<Service, Set<Provider>> serviceToProviders = _networkModel.getNetworkOfferingServiceProvidersMap(offeringId);
        NetworkOfferingVO offering = _networkOfferingDao.findById(offeringId);
        //For inline mode checking, using firewall provider for LB instead, because public ip would apply on firewall provider
        if (offering.isInline()) {
            Provider firewallProvider = null;
            if (serviceToProviders.containsKey(Service.Firewall)) {
                firewallProvider = (Provider)serviceToProviders.get(Service.Firewall).toArray()[0];
            }
            Set<Provider> p = new HashSet<Provider>();
            p.add(firewallProvider);
            serviceToProviders.remove(Service.Lb);
            serviceToProviders.put(Service.Lb, p);
        }
        for (PublicIp ip : ipToServices.keySet()) {
            Set<Service> services = ipToServices.get(ip);
            Provider provider = null;
            for (Service service : services) {
                Set<Provider> curProviders = serviceToProviders.get(service);
                if (curProviders == null || curProviders.isEmpty()) {
                    continue;
                }
                Provider curProvider = (Provider)curProviders.toArray()[0];
                if (provider == null) {
                    provider = curProvider;
                    continue;
                }
                // We don't support multiple providers for one service now
                if (!provider.equals(curProvider)) {
                    throw new InvalidParameterValueException("There would be multiple providers for IP " + ip.getAddress() + " with the new network offering!");
                }
            }
        }
        return true;
    }

    private Set<Purpose> getPublicIpPurposeInRules(PublicIp ip, boolean includeRevoked, boolean includingFirewall) {
        Set<Purpose> result = new HashSet<Purpose>();
        List<FirewallRuleVO> rules = null;
        if (includeRevoked) {
            rules = _firewallDao.listByIp(ip.getId());
        } else {
            rules = _firewallDao.listByIpAndNotRevoked(ip.getId());
        }

        if (rules == null || rules.isEmpty()) {
            return null;
        }

        for (FirewallRuleVO rule : rules) {
            if (rule.getPurpose() != Purpose.Firewall || includingFirewall) {
                result.add(rule.getPurpose());
            }
        }

        return result;
    }

    private void checkNetworkDns(boolean isIpv6, NetworkOffering networkOffering, Long vpcId,
        String ip4Dns1, String ip4Dns2, String ip6Dns1, String ip6Dns2) {
        if (ObjectUtils.anyNotNull(ip4Dns1, ip4Dns2, ip6Dns1, ip6Dns2)) {
            if (GuestType.L2.equals(networkOffering.getGuestType())) {
                throw new InvalidParameterValueException(String.format("DNS can not be specified %s networks", GuestType.L2));
            }
            if (vpcId != null) {
                throw new InvalidParameterValueException("DNS can not be specified for a VPC tier");
            }
            if (!areServicesSupportedByNetworkOffering(networkOffering.getId(), Service.Dns)) {
                throw new InvalidParameterValueException("DNS can not be specified for networks with network offering that do not support DNS service");
            }
        }
        if (!isIpv6 && !StringUtils.isAllEmpty(ip6Dns1, ip6Dns2)) {
            throw new InvalidParameterValueException("IPv6 DNS cannot be specified for IPv4 only network");
        }
        _networkModel.verifyIp4DnsPair(ip4Dns1, ip4Dns2);
        _networkModel.verifyIp6DnsPair(ip6Dns1, ip6Dns2);
    }

    protected boolean checkAndUpdateNetworkDns(NetworkVO network, NetworkOffering networkOffering, String newIp4Dns1,
        String newIp4Dns2, String newIp6Dns1, String newIp6Dns2) {
        String ip4Dns1 = network.getDns1();
        String ip4Dns2 = network.getDns2();
        String ip6Dns1 = network.getIp6Dns1();
        String ip6Dns2 = network.getIp6Dns2();
        if (ObjectUtils.allNull(newIp4Dns1, newIp4Dns2, newIp6Dns1, newIp6Dns2)) {
            if (ObjectUtils.anyNotNull(ip4Dns1, ip4Dns2, ip6Dns1, ip6Dns2) &&
                    !areServicesSupportedByNetworkOffering(networkOffering.getId(), Service.Dns)) {
                network.setDns1(null);
                network.setDns2(null);
                network.setIp6Dns1(null);
                network.setIp6Dns2(null);
                return true;
            }
            return false;
        }
        if (StringUtils.equals(ip4Dns1, StringUtils.trimToNull(newIp4Dns1)) && StringUtils.equals(ip4Dns2, StringUtils.trimToNull(newIp4Dns2)) &&
                StringUtils.equals(ip6Dns1, StringUtils.trimToNull(newIp6Dns1)) && StringUtils.equals(ip6Dns2, StringUtils.trimToNull(newIp6Dns2))) {
            return false;
        }
        boolean isIpv6 = (GuestType.Shared.equals(network.getGuestType()) &&
                StringUtils.isNotEmpty(network.getIp6Cidr())) ||
                _networkOfferingDao.isIpv6Supported(networkOffering.getId());
        if (newIp4Dns1 != null) {
            ip4Dns1 = StringUtils.trimToNull(newIp4Dns1);
        }
        if (newIp4Dns2 != null) {
            ip4Dns2 = StringUtils.trimToNull(newIp4Dns2);
        }
        if (newIp6Dns1 != null) {
            ip6Dns1 = StringUtils.trimToNull(newIp6Dns1);
        }
        if (newIp6Dns2 != null) {
            ip6Dns2 = StringUtils.trimToNull(newIp6Dns2);
        }
        checkNetworkDns(isIpv6, networkOffering, network.getVpcId(), ip4Dns1, ip4Dns2, ip6Dns1, ip6Dns2);
        network.setDns1(ip4Dns1);
        network.setDns2(ip4Dns2);
        network.setIp6Dns1(ip6Dns1);
        network.setIp6Dns2(ip6Dns2);
        return true;
    }

    @Override
    public List<? extends Network> getIsolatedNetworksOwnedByAccountInZone(long zoneId, Account owner) {

        return _networksDao.listByZoneAndGuestType(owner.getId(), zoneId, Network.GuestType.Isolated, false);
    }

    @Override
    public List<? extends Network> getIsolatedNetworksWithSourceNATOwnedByAccountInZone(long zoneId, Account owner) {

        return _networksDao.listSourceNATEnabledNetworks(owner.getId(), zoneId, Network.GuestType.Isolated);
    }

    @Override
    public IpAddress allocateIP(Account ipOwner, long zoneId, Long networkId, Boolean displayIp, String ipaddress)
            throws ResourceAllocationException, InsufficientAddressCapacityException, ConcurrentOperationException {
        return ipAddressLifecycleService.allocateIP(ipOwner, zoneId, networkId, displayIp, ipaddress);
    }

    @Override
    public IpAddress allocatePortableIP(Account ipOwner, int regionId, Long zoneId, Long networkId, Long vpcId)
            throws ResourceAllocationException, InsufficientAddressCapacityException, ConcurrentOperationException {
        return ipAddressLifecycleService.allocatePortableIP(ipOwner, regionId, zoneId, networkId, vpcId);
    }

    @Override
    public boolean releasePortableIpAddress(long ipAddressId) {
        return ipAddressLifecycleService.releasePortableIpAddress(ipAddressId);
    }

    @Override
    @DB
    public boolean configure(final String name, final Map<String, Object> params) throws ConfigurationException {
        _configs = _configDao.getConfiguration("Network", params);

        _cidrLimit = NumbersUtil.parseInt(_configs.get(Config.NetworkGuestCidrLimit.key()), 22);

        _allowSubdomainNetworkAccess = Boolean.valueOf(_configs.get(Config.SubDomainNetworkAccess.key()));

        logger.info("Network Service is configured.");

        return true;
    }

    @Override
    public boolean start() {
        initializeInternalLoadBalancerElementsMap();
        return true;
    }

    private void initializeInternalLoadBalancerElementsMap() {
        if (MapUtils.isEmpty(internalLoadBalancerElementServiceMap) && CollectionUtils.isNotEmpty(internalLoadBalancerElementServices)) {
            for (InternalLoadBalancerElementService service : internalLoadBalancerElementServices) {
                internalLoadBalancerElementServiceMap.put(service.getProviderType().name(), service);
            }
            logger.debug(String.format("Discovered internal loadbalancer elements configured on NetworkServiceImpl"));
        }
    }

    @Override
    public boolean stop() {
        return true;
    }

    protected NetworkServiceImpl() {
    }

    @Override
    public boolean configureNicSecondaryIp(NicSecondaryIp secIp, boolean isZoneSgEnabled) {
        return nicSecondaryIpService.configureNicSecondaryIp(secIp, isZoneSgEnabled);
    }

    @Override
    public NicSecondaryIp allocateSecondaryGuestIP(final long nicId, IpAddresses requestedIpPair) throws InsufficientAddressCapacityException {
        return nicSecondaryIpService.allocateSecondaryGuestIP(nicId, requestedIpPair);
    }

    @Override
    public boolean releaseSecondaryIpFromNic(long ipAddressId) {
        return nicSecondaryIpService.releaseSecondaryIpFromNic(ipAddressId);
    }

    @Override
    public IpAddress reserveIpAddress(Account account, Boolean displayIp, Long ipAddressId) throws ResourceAllocationException {
        return ipAddressLifecycleService.reserveIpAddress(account, displayIp, ipAddressId);
    }

    @Override
    public IpAddress reserveIpAddressWithVlanDetail(Account account, DataCenter zone, Boolean displayIp, String vlanDetailKey) throws ResourceAllocationException {
        return ipAddressLifecycleService.reserveIpAddressWithVlanDetail(account, zone, displayIp, vlanDetailKey);
    }

    @Override
    public boolean releaseReservedIpAddress(long ipAddressId) throws InsufficientAddressCapacityException {
        return ipAddressLifecycleService.releaseReservedIpAddress(ipAddressId);
    }

    @Override
    public boolean releaseIpAddress(long ipAddressId) throws InsufficientAddressCapacityException {
        return ipAddressLifecycleService.releaseIpAddress(ipAddressId);
    }

    @Override
    @DB
    public Network getNetwork(long id) {
        return _networksDao.findById(id);
    }

    private void checkSharedNetworkCidrOverlap(Long zoneId, long physicalNetworkId, String cidr) {
        networkCreationValidationService.checkSharedNetworkCidrOverlap(zoneId, physicalNetworkId, cidr);
    }

    void validateNetworkCidrSize(Account caller, Integer cidrSize, String cidr, NetworkOffering networkOffering, long accountId, long zoneId) {
        networkCreationValidationService.validateNetworkCidrSize(caller, cidrSize, cidr, networkOffering, accountId, zoneId);
    }

    void validateSharedNetworkRouterIPs(String gateway, String startIP, String endIP, String netmask, String routerIPv4, String routerIPv6, String startIPv6, String endIPv6, String ip6Cidr, NetworkOffering ntwkOff) {
        networkCreationValidationService.validateSharedNetworkRouterIPs(gateway, startIP, endIP, netmask, routerIPv4, routerIPv6, startIPv6, endIPv6, ip6Cidr, ntwkOff);
    }

    private String getVpcPrependedNetworkName(String networkName, Vpc vpc) {
        return networkCreationValidationService.getVpcPrependedNetworkName(networkName, vpc);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_CREATE, eventDescription = "creating network")
    public Network createGuestNetwork(CreateNetworkCmd cmd) throws InsufficientCapacityException, ConcurrentOperationException, ResourceAllocationException {
        Long networkOfferingId = cmd.getNetworkOfferingId();
        String gateway = cmd.getGateway();
        String startIP = cmd.getStartIp();
        String endIP = cmd.getEndIp();
        String netmask = cmd.getNetmask();
        String networkDomain = cmd.getNetworkDomain();

        boolean adminCalledUs = cmd instanceof CreateNetworkCmdByAdmin;
        String vlanId = adminCalledUs ? ((CreateNetworkCmdByAdmin)cmd).getVlan() : null;
        boolean bypassVlanOverlapCheck = adminCalledUs && ((CreateNetworkCmdByAdmin)cmd).getBypassVlanOverlapCheck();
        boolean hideIpAddressUsage = adminCalledUs && ((CreateNetworkCmdByAdmin)cmd).getHideIpAddressUsage();
        String routerIPv4 = adminCalledUs ? ((CreateNetworkCmdByAdmin)cmd).getRouterIp() : null;
        String routerIPv6 = adminCalledUs ? ((CreateNetworkCmdByAdmin)cmd).getRouterIpv6() : null;
        Long asNumber = cmd.getAsNumber();

        String name = cmd.getNetworkName();
        String displayText = cmd.getDisplayText();
        Account caller = CallContext.current().getCallingAccount();
        Long physicalNetworkId = cmd.getPhysicalNetworkId();
        Long domainId = cmd.getDomainId();
        Boolean subdomainAccess = cmd.getSubdomainAccess();
        Long vpcId = cmd.getVpcId();
        String startIPv6 = cmd.getStartIpv6();
        String endIPv6 = cmd.getEndIpv6();
        String ip6Gateway = cmd.getIp6Gateway();
        String ip6Cidr = cmd.getIp6Cidr();
        boolean displayNetwork = ! Boolean.FALSE.equals(cmd.getDisplayNetwork());
        Long aclId = cmd.getAclId();
        String isolatedPvlan = cmd.getIsolatedPvlan();
        String externalId = cmd.getExternalId();
        String isolatedPvlanType = cmd.getIsolatedPvlanType();
        Long associatedNetworkId = cmd.getAssociatedNetworkId();
        Integer publicMtu = cmd.getPublicMtu();
        Integer privateMtu = cmd.getPrivateMtu();
        String ip4Dns1 = cmd.getIp4Dns1();
        String ip4Dns2 = cmd.getIp4Dns2();
        String ip6Dns1 = cmd.getIp6Dns1();
        String ip6Dns2 = cmd.getIp6Dns2();
        Integer networkCidrSize = cmd.getCidrSize();
        List<Long> bgpPeerIds = adminCalledUs ? ((CreateNetworkCmdByAdmin)cmd).getBgpPeerIds() : null;

        // Validate network offering id
        NetworkOffering ntwkOff = getAndValidateNetworkOffering(networkOfferingId);

        Account owner = getOwningAccount(cmd, caller);

        PhysicalNetwork pNtwk = getAndValidatePhysicalNetwork(physicalNetworkId);

        DataCenter zone = getAndValidateZone(cmd, pNtwk);

        boolean keepMacAddressOnPublicNic = getAndValidateSupportForKeepMacAddressOnPublicNicParameter(cmd.getKeepMacAddressOnPublicNic(), ntwkOff);

        _accountMgr.checkAccess(owner, ntwkOff, zone);

        validateZoneAvailability(caller, zone);
        validateNetworkCreationSupported(zone.getId(), zone.getName(), ntwkOff.getGuestType());

        ACLType aclType = getAclType(caller, cmd.getAclType(), ntwkOff);

        if (ntwkOff.getGuestType() != GuestType.Shared && (!StringUtils.isAllBlank(routerIPv4, routerIPv6))) {
            throw new InvalidParameterValueException("Router IP can be specified only for Shared networks");
        }

        if (ntwkOff.getGuestType() == GuestType.Shared && !_networkModel.isProviderForNetworkOffering(Provider.VirtualRouter, networkOfferingId)
                && (!StringUtils.isAllBlank(routerIPv4, routerIPv6))) {
            throw new InvalidParameterValueException("Virtual Router is not a supported provider for the Shared network, hence router IP should not be provided");
        }

        boolean isDomainSpecific = isDomainSpecificNetworkRequested(caller, domainId, subdomainAccess, ntwkOff, aclType);

        if (aclType == ACLType.Domain) {
            owner = _accountDao.findById(Account.ACCOUNT_ID_SYSTEM);
        }

        // The network name is unique under the account
        if (!AllowDuplicateNetworkName.valueIn(owner.getAccountId())) {
            List<NetworkVO> existingNetwork = _networksDao.listByAccountIdNetworkName(owner.getId(), name);
            if (!existingNetwork.isEmpty()) {
                throw new InvalidParameterValueException("Another network with same name already exists within account: " + owner.getAccountName());
            }
        }

        boolean ipv4 = false, ipv6 = false;
        if (org.apache.commons.lang3.StringUtils.isNoneBlank(gateway, netmask)) {
            ipv4 = true;
        }
        if (StringUtils.isNoneBlank(ip6Cidr, ip6Gateway)) {
            ipv6 = true;
        }

        if (gateway != null) {
            try {
                // getByName on a literal representation will only check validity of the address
                // http://docs.oracle.com/javase/6/docs/api/java/net/InetAddress.html#getByName(java.lang.String)
                InetAddress gatewayAddress = InetAddress.getByName(gateway);
                if (gatewayAddress instanceof Inet6Address) {
                    ipv6 = true;
                } else {
                    ipv4 = true;
                }
            } catch (UnknownHostException e) {
                logger.error("Unable to convert gateway IP to a InetAddress", e);
                throw new InvalidParameterValueException("Gateway parameter is invalid");
            }
        }

        // Start and end IP address are mandatory for shared networks.
        if (ntwkOff.getGuestType() == GuestType.Shared && vpcId == null) {
            if (!AllowEmptyStartEndIpAddress.valueIn(owner.getAccountId()) &&
                (startIP == null && endIP == null) &&
                (startIPv6 == null && endIPv6 == null)) {
                throw new InvalidParameterValueException("Either IPv4 or IPv6 start and end address are mandatory");
            }
        }

        String cidr = null;
        if (ipv4) {
            // if end ip is not specified, default it to startIp
            if (startIP != null) {
                if (!NetUtils.isValidIp4(startIP)) {
                    throw new InvalidParameterValueException("Invalid format for the startIp parameter");
                }
                if (endIP == null) {
                    endIP = startIP;
                } else if (!NetUtils.isValidIp4(endIP)) {
                    throw new InvalidParameterValueException("Invalid format for the endIp parameter");
                }
                if (!(gateway != null && netmask != null)) {
                    throw new InvalidParameterValueException("gateway and netmask should be defined when startIP/endIP are passed in");
                }
            }
            if (gateway != null && netmask != null) {
                if (NetUtils.isNetworkorBroadcastIP(gateway, netmask)) {
                    if (logger.isDebugEnabled()) {
                        logger.debug("The gateway IP provided is " + gateway + " and netmask is " + netmask + ". The IP is either broadcast or network IP.");
                    }
                    throw new InvalidParameterValueException("Invalid gateway IP provided. Either the IP is broadcast or network IP.");
                }

                if (!NetUtils.isValidIp4(gateway)) {
                    throw new InvalidParameterValueException("Invalid gateway");
                }
                if (!NetUtils.isValidIp4Netmask(netmask)) {
                    throw new InvalidParameterValueException("Invalid netmask");
                }

                cidr = NetUtils.ipAndNetMaskToCidr(gateway, netmask);
            }

        }

        if (ipv6) {
            if (endIPv6 == null) {
                endIPv6 = startIPv6;
            }
            _networkModel.checkIp6Parameters(startIPv6, endIPv6, ip6Gateway, ip6Cidr);
            if (!GuestType.Shared.equals(ntwkOff.getGuestType())) {
                _networkModel.checkIp6CidrSizeEqualTo64(ip6Cidr);
            }

            if (zone.getNetworkType() != NetworkType.Advanced || ntwkOff.getGuestType() != Network.GuestType.Shared) {
                throw new InvalidParameterValueException("Can only support create IPv6 network with advance shared network!");
            }

            if(StringUtils.isAllBlank(ip6Dns1, ip6Dns2, zone.getIp6Dns1(), zone.getIp6Dns2())) {
                throw new InvalidParameterValueException("Can only create IPv6 network if the zone has IPv6 DNS! Please configure the zone IPv6 DNS1 and/or IPv6 DNS2.");
            }

            if (!ipv4 && ntwkOff.getGuestType() == GuestType.Shared && _networkModel.isProviderForNetworkOffering(Provider.VirtualRouter, networkOfferingId)) {
                throw new InvalidParameterValueException("Currently IPv6-only Shared network with Virtual Router provider is not supported.");
            }
        }

        if (NetworkOffering.NetworkMode.ROUTED.equals(ntwkOff.getNetworkMode())
                && !routedIpv4Manager.isRoutedNetworkVpcEnabled(zone.getId())) {
            throw new InvalidParameterValueException("Routed network is not enabled in this zone");
        }

        if (isNonVpcNetworkSupportingDynamicRouting(ntwkOff) && ntwkOff.isSpecifyAsNumber() && asNumber == null) {
            throw new InvalidParameterValueException("AS number is required for the network but not passed.");
        }

        validateNetworkCidrSize(caller, networkCidrSize, cidr, ntwkOff, owner.getAccountId(), zone.getId());

        validateSharedNetworkRouterIPs(gateway, startIP, endIP, netmask, routerIPv4, routerIPv6, startIPv6, endIPv6, ip6Cidr, ntwkOff);

        Pair<String, String> ip6GatewayCidr = null;
        if (zone.getNetworkType() == NetworkType.Advanced && ntwkOff.getGuestType() == GuestType.Isolated) {
            ipv6 = _networkOfferingDao.isIpv6Supported(ntwkOff.getId());
            if (ipv6) {
                ip6GatewayCidr = ipv6Service.preAllocateIpv6SubnetForNetwork(zone);
                ip6Gateway = ip6GatewayCidr.first();
                ip6Cidr = ip6GatewayCidr.second();
            }
        }

        if (StringUtils.isNotBlank(isolatedPvlan)) {
            if (!_accountMgr.isRootAdmin(caller.getId())) {
                throw new InvalidParameterValueException("Only ROOT admin is allowed to create Private VLAN network");
            }
            if (zone.getNetworkType() != NetworkType.Advanced || ntwkOff.getGuestType() == GuestType.Isolated) {
                throw new InvalidParameterValueException("Can only support create Private VLAN network with advanced shared or L2 network!");
            }
            if (ipv6) {
                throw new InvalidParameterValueException("Can only support create Private VLAN network with IPv4!");
            }
        }

        Pair<String, PVlanType> pvlanPair = getPrivateVlanPair(isolatedPvlan, isolatedPvlanType, vlanId);
        String secondaryVlanId = pvlanPair.first();
        PVlanType privateVlanType = pvlanPair.second();

        if ((StringUtils.isNotBlank(secondaryVlanId) || privateVlanType != null) && StringUtils.isBlank(vlanId)) {
            throw new InvalidParameterValueException("VLAN ID has to be set in order to configure a Private VLAN");
        }

        performBasicPrivateVlanChecks(vlanId, secondaryVlanId, privateVlanType);

        if (!_accountMgr.isRootAdmin(caller.getId())) {
            validateNetworkOfferingForNonRootAdminUser(ntwkOff);
        }

        // Ignore vlanId if it is passed but specifyvlan=false in network offering
        if (ntwkOff.getGuestType() == GuestType.Shared && ! ntwkOff.isSpecifyVlan() && vlanId != null) {
            throw new InvalidParameterValueException("Cannot specify vlanId when create a network from network offering with specifyvlan=false");
        }

        // Don't allow to specify vlan if the caller is not ROOT admin
        if (!_accountMgr.isRootAdmin(caller.getId()) && (ntwkOff.isSpecifyVlan() || vlanId != null || bypassVlanOverlapCheck)) {
            throw new InvalidParameterValueException("Only ROOT admin is allowed to specify vlanId or bypass vlan overlap check");
        }

        // Validate BGP peers
        if (CollectionUtils.isNotEmpty(bgpPeerIds)) {
            if (vpcId != null) {
                throw new InvalidParameterValueException("The BGP peers of VPC tiers will inherit from the VPC, do not add separately.");
            }
            if (!routedIpv4Manager.isDynamicRoutedNetwork(ntwkOff)) {
                throw new InvalidParameterValueException("The network offering does not support Dynamic routing");
            }
            routedIpv4Manager.validateBgpPeers(owner, zone.getId(), bgpPeerIds);
        }

        if (ipv4) {
            // For non-root admins check cidr limit - if it's allowed by global config value
            if (!_accountMgr.isRootAdmin(caller.getId()) && cidr != null) {

                String[] cidrPair = cidr.split("\\/");
                int cidrSize = Integer.parseInt(cidrPair[1]);

                if (cidrSize < _cidrLimit) {
                    throw new InvalidParameterValueException("Cidr size can't be less than " + _cidrLimit);
                }
            }
        }

        Collection<String> ntwkProviders = _networkMgr.finalizeServicesAndProvidersForNetwork(ntwkOff, physicalNetworkId).values();
        if (ipv6 && providersConfiguredForExternalNetworking(ntwkProviders)) {
            throw new InvalidParameterValueException("Cannot support IPv6 on network offering with external devices!");
        }

        if (StringUtils.isNotBlank(secondaryVlanId) && providersConfiguredForExternalNetworking(ntwkProviders)) {
            throw new InvalidParameterValueException("Cannot support private vlan on network offering with external devices!");
        }

        if (cidr != null && providersConfiguredForExternalNetworking(ntwkProviders)) {
            if (ntwkOff.getGuestType() == GuestType.Shared && (zone.getNetworkType() == NetworkType.Advanced) && isSharedNetworkOfferingWithServices(networkOfferingId)) {
                // validate if CIDR specified overlaps with any of the CIDR's allocated for isolated networks and shared networks in the zone
                checkSharedNetworkCidrOverlap(zone.getId(), pNtwk.getId(), cidr);
            } else {
                // if the guest network is for the VPC, if any External Provider are supported in VPC
                // cidr will not be null as it is generated from the super cidr of vpc.
                // if cidr is not null and network is not part of vpc then throw the exception
                if (vpcId == null) {
                    throw new InvalidParameterValueException("Cannot specify CIDR when using network offering with external devices!");
                }
            }
        }

        // Vlan is created in 1 cases - works in Advance zone only:
        // 1) GuestType is Shared
        boolean createVlan = (startIP != null && endIP != null && zone.getNetworkType() == NetworkType.Advanced && ((ntwkOff.getGuestType() == Network.GuestType.Shared)
                || (ntwkOff.getGuestType() == GuestType.Isolated && !areServicesSupportedByNetworkOffering(ntwkOff.getId(), Service.SourceNat))));

        if (!createVlan) {
            // Only support advance shared network in IPv6, which means createVlan is a must
            if (ipv6 && ntwkOff.getGuestType() != GuestType.Isolated) {
                createVlan = true;
            }
        }

        // Can add vlan range only to the network which allows it
        if (createVlan && !ntwkOff.isSpecifyIpRanges()) {
            throwInvalidIdException("Network offering with specified id doesn't support adding multiple IP ranges", ntwkOff.getUuid(), NETWORK_OFFERING_ID);
        }



        if (GuestType.Shared == ntwkOff.getGuestType()) {
            if (!ntwkOff.isSpecifyIpRanges()) {
                throw new CloudRuntimeException("The 'specifyipranges' parameter should be true for Shared Networks");
            }
            if (ipv4 && Objects.isNull(startIP)) {
                throw new CloudRuntimeException("IPv4 address range needs to be provided");
            }
            if (ipv6) {
                logger.info(String.format("ip range for network '%s' is specified as %s - %s", name, startIPv6, endIPv6));
            }
        }
        Pair<Integer, Integer> interfaceMTUs = validateMtuConfig(publicMtu, privateMtu, zone.getId());
        mtuCheckForVpcNetwork(vpcId, interfaceMTUs, publicMtu);

        Network associatedNetwork = null;
        if (associatedNetworkId != null) {
            if (vlanId != null) {
                throw new InvalidParameterValueException("Associated network and vlanId are mutually exclusive");
            }
            if (!_networkMgr.isSharedNetworkWithoutSpecifyVlan(ntwkOff)) {
                throw new InvalidParameterValueException("Can only create Shared network with associated network if specifyVlan is false");
            }
            associatedNetwork = implementAssociatedNetwork(associatedNetworkId, caller, owner, zone,
                    aclType == ACLType.Domain ? domainId : null,
                    aclType == ACLType.Account ? owner.getAccountId() : null,
                    cidr, startIP, endIP);
        }

        checkNetworkDns(ipv6, ntwkOff, vpcId, ip4Dns1, ip4Dns2, ip6Dns1, ip6Dns2);

        if (vpcId != null && VpcManager.VpcTierNamePrepend.value()) {
            Vpc vpc = _vpcDao.findById(vpcId);
            if (vpc != null) {
                name = getVpcPrependedNetworkName(name, vpc);
            }
        }

        Network network = commitNetwork(networkOfferingId, gateway, startIP, endIP, netmask, networkDomain, vlanId, bypassVlanOverlapCheck, name, displayText, caller, physicalNetworkId, zone.getId(),
                domainId, isDomainSpecific, subdomainAccess, vpcId, startIPv6, endIPv6, ip6Gateway, ip6Cidr, displayNetwork, aclId, secondaryVlanId, privateVlanType, ntwkOff, pNtwk, aclType, owner, cidr, createVlan,
                externalId, routerIPv4, routerIPv6, associatedNetwork, ip4Dns1, ip4Dns2, ip6Dns1, ip6Dns2, interfaceMTUs, networkCidrSize, keepMacAddressOnPublicNic);

        // retrieve, acquire and associate the correct IP addresses
        checkAndSetRouterSourceNatIp(owner, cmd, network);

        if (hideIpAddressUsage) {
            _networkDetailsDao.persist(new NetworkDetailVO(network.getId(), Network.hideIpAddressUsage, String.valueOf(hideIpAddressUsage), false));
        }

        // assign to network
        if (NetworkOffering.NetworkMode.ROUTED.equals(ntwkOff.getNetworkMode())) {
            routedIpv4Manager.assignIpv4SubnetToNetwork(network);
        }
        if (isNonVpcNetworkSupportingDynamicRouting(ntwkOff)) {
            try {
                bgpService.allocateASNumber(zone.getId(), asNumber, network.getId(), null);
            } catch (CloudRuntimeException ex) {
                deleteNetwork(network.getId(), true);
                throw ex;
            }
        }
        if (CollectionUtils.isNotEmpty(bgpPeerIds)) {
            routedIpv4Manager.persistBgpPeersForGuestNetwork(network.getId(), bgpPeerIds);
        }

        // if the network offering has persistent set to true, implement the network
        if (ntwkOff.isPersistent()) {
            return implementedNetworkInCreation(caller, zone, network);
        }
        return network;
    }

    private boolean isNonVpcNetworkSupportingDynamicRouting(NetworkOffering networkOffering) {
        return networkCreationValidationService.isNonVpcNetworkSupportingDynamicRouting(networkOffering);
    }

    private void validateNetworkCreationSupported(long zoneId, String zoneName, GuestType guestType) {
        networkCreationValidationService.validateNetworkCreationSupported(zoneId, zoneName, guestType);
    }

    protected boolean getAndValidateSupportForKeepMacAddressOnPublicNicParameter(Boolean keepMacAddressOnPublicNic, NetworkOffering networkOffering) {
        return networkCreationValidationService.getAndValidateSupportForKeepMacAddressOnPublicNicParameter(keepMacAddressOnPublicNic, networkOffering);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_CREATE, eventDescription = "creating network")
    public Network createGuestNetwork(long networkOfferingId, String name, String displayText, Account owner,
              PhysicalNetwork physicalNetwork, long zoneId, ACLType aclType) throws
            InsufficientCapacityException, ConcurrentOperationException, ResourceAllocationException {
        return _networkMgr.createGuestNetwork(networkOfferingId, name, displayText,
                null, null, null, false, null, owner, null, physicalNetwork, zoneId,
                aclType, null, null, null, null, true, null,
                null, null, null, null, null, null, null, null, null, null);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_CREATE, eventDescription = "creating network")
    public Network createGuestNetwork(long networkOfferingId, String name, String displayText, Account owner,
                                      PhysicalNetwork physicalNetwork, long zoneId, ACLType aclType, Pair<Integer, Integer> vrIfaceMTUs) throws
            InsufficientCapacityException, ConcurrentOperationException, ResourceAllocationException {
        return _networkMgr.createGuestNetwork(networkOfferingId, name, displayText,
                null, null, null, false, null, owner, null, physicalNetwork, zoneId,
                aclType, null, null, null, null, true, null,
                null, null, null, null, null, null, null, null, vrIfaceMTUs, null);
    }

    void checkAndSetRouterSourceNatIp(Account owner, CreateNetworkCmd cmd, Network network) throws InsufficientAddressCapacityException, ResourceAllocationException {
        String sourceNatIp = cmd.getSourceNatIP();
        if (sourceNatIp == null) {
            logger.debug(String.format("No source NAT IP given for create Network %s command, using something arbitrary.", cmd.getNetworkName()));
            return; // nothing to try
        }
        IpAddress ip = allocateIP(owner, cmd.getZoneId(), network.getId(), null, sourceNatIp);
        try {
            associateIPToNetwork(ip.getId(), network.getId());
        } catch (ResourceUnavailableException e) {
            String msg = String.format("Can´t use %s as source NAT IP address for Network %s/%s as it is unavailable", sourceNatIp, network.getName(), network.getUuid());
            logger.error(msg);
            throw new CloudRuntimeException(msg,e);
        }
    }

    /**
     * @param cmd
     * @param network
     * @return whether the sourceNat is changed, and consequently restart is needed
     * @throws InsufficientAddressCapacityException
     * @throws ResourceAllocationException
     */
    private boolean checkAndUpdateRouterSourceNatIp(UpdateNetworkCmd cmd, Network network) {
        IPAddressVO requestedIp = checkSourceNatIpAddressForUpdate(cmd, network);
        if (requestedIp == null) return false; // ip not associated with this network

        List<IPAddressVO> userIps = _ipAddressDao.listByAssociatedNetwork(network.getId(), true);
        if (! userIps.isEmpty()) {
            try {
                _ipAddrMgr.updateSourceNatIpAddress(requestedIp, userIps);
            } catch (Exception e) { // pokemon exception from transaction
                String msg = String.format("Update of source NAT IP to %s for Network \"%s\"/%s failed due to %s",
                        requestedIp.getAddress().addr(), network.getName(), network.getUuid(), e.getLocalizedMessage());
                logger.error(msg);
                throw new CloudRuntimeException(msg, e);
            }
        }
        return true;
    }

    @Nullable
    private IPAddressVO checkSourceNatIpAddressForUpdate(UpdateNetworkCmd cmd, Network network) {
        String sourceNatIp = cmd.getSourceNatIP();
        if (sourceNatIp == null) {
            logger.trace(String.format("No source NAT ip given to update Network %s with.", cmd.getNetworkName()));
            return null;
        } else {
            logger.info(String.format("Updating Network %s to have source NAT IP %s", cmd.getNetworkName(), sourceNatIp));
        }
        // check if the address is already acquired for this network
        IPAddressVO requestedIp = _ipAddressDao.findByIp(sourceNatIp);
        if (requestedIp == null || requestedIp.getAssociatedWithNetworkId() == null || ! requestedIp.getAssociatedWithNetworkId().equals(network.getId())) {
            logger.warn(String.format("Source NAT IP %s is not associated with Network %s/%s. It cannot be used as source NAT IP.",
                    sourceNatIp, network.getName(), network.getUuid()));
            return null;
        }
        // check if it is the current source NAT address
        if (requestedIp.isSourceNat()) {
            logger.info(String.format("IP address %s is already the source Nat address. Not updating!", sourceNatIp));
            return null;
        }
        return requestedIp;
    }

    @Nullable
    private ACLType getAclType(Account caller, String aclTypeStr, NetworkOffering ntwkOff) {
        // Only domain and account ACL types are supported in Acton.
        ACLType aclType = null;
        if (aclTypeStr != null) {
            aclType = getAclType(aclTypeStr, ntwkOff);
        } else {
            aclType = getAclType(caller, ntwkOff, aclType);
        }
        return aclType;
    }

    @NotNull
    private static ACLType getAclType(String aclTypeStr, NetworkOffering ntwkOff) {
        ACLType aclType;
        if (aclTypeStr.equalsIgnoreCase(ACLType.Account.toString())) {
            aclType = ACLType.Account;
        } else if (aclTypeStr.equalsIgnoreCase(ACLType.Domain.toString())) {
            aclType = ACLType.Domain;
        } else {
            throw new InvalidParameterValueException("Incorrect aclType specified. Check the API documentation for supported types");
        }
        // In 3.0 all Shared networks should have aclType == Domain, all Isolated networks aclType==Account
        if (ntwkOff.getGuestType() == GuestType.Isolated && aclType != ACLType.Account) {
            throw new InvalidParameterValueException("AclType should be " + ACLType.Account + " for network of type " + GuestType.Isolated);
        }
        return aclType;
    }

    private ACLType getAclType(Account caller, NetworkOffering ntwkOff, ACLType aclType) {
        if (ntwkOff.getGuestType() == GuestType.Isolated || ntwkOff.getGuestType() == GuestType.L2) {
            aclType = ACLType.Account;
        } else if (ntwkOff.getGuestType() == GuestType.Shared) {
            if (_accountMgr.isRootAdmin(caller.getId())) {
                aclType = ACLType.Domain;
            } else if (_accountMgr.isNormalUser(caller.getId())) {
                aclType = ACLType.Account;
            } else {
                throw new InvalidParameterValueException("AclType must be specified for shared network created by domain admin");
            }
        }
        return aclType;
    }

    private void validateZoneAvailability(Account caller, DataCenter zone) {
        if (Grouping.AllocationState.Disabled == zone.getAllocationState() && !_accountMgr.isRootAdmin(caller.getId())) {
            // See DataCenterVO.java
            PermissionDeniedException ex = new PermissionDeniedException("Cannot perform this operation since specified Zone is currently disabled");
            ex.addProxyObject(zone.getUuid(), "zoneId");
            throw ex;
        }
    }

    private boolean isDomainSpecificNetworkRequested(Account caller, Long domainId, Boolean subdomainAccess, NetworkOffering ntwkOff, ACLType aclType) {
        boolean isDomainSpecific = false;
        // Check if the network is domain specific
        if (aclType == ACLType.Domain) {
            // only Admin can create domain with aclType=Domain
            if (!_accountMgr.isAdmin(caller.getId())) {
                throw new PermissionDeniedException("Only admin can create networks with aclType=Domain");
            }

            // only shared networks can be Domain specific
            if (ntwkOff.getGuestType() != GuestType.Shared) {
                throw new InvalidParameterValueException("Only " + GuestType.Shared + " networks can have aclType=" + ACLType.Domain);
            }

            if (domainId != null) {
                if (ntwkOff.getTrafficType() != TrafficType.Guest || ntwkOff.getGuestType() != GuestType.Shared) {
                    throw new InvalidParameterValueException("Domain level networks are supported just for traffic type " + TrafficType.Guest + " and guest type " + GuestType.Shared);
                }

                DomainVO domain = _domainDao.findById(domainId);
                if (domain == null) {
                    throw new InvalidParameterValueException("Unable to find domain by specified id");
                }
                _accountMgr.checkAccess(caller, domain);
            }
            isDomainSpecific = true;

        } else if (subdomainAccess != null) {
            throw new InvalidParameterValueException("Parameter subDomainAccess can be specified only with aclType=Domain");
        }
        return isDomainSpecific;
    }

    @NotNull
    private DataCenter getAndValidateZone(CreateNetworkCmd cmd, PhysicalNetwork pNtwk) {
        Long zoneId = (cmd.getZoneId() == null) ? pNtwk.getDataCenterId() : cmd.getZoneId();
        DataCenter zone = _dcDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Specified zone id was not found");
        }
        return zone;
    }

    /**
     // validate physical network and zone
     // Check if physical network exists
     *
     * @param physicalNetworkId the id of the required physical network
     * @return the data object for the physical network
     */
    @NotNull
    private PhysicalNetwork getAndValidatePhysicalNetwork(Long physicalNetworkId) {
        PhysicalNetwork pNtwk = null;
        if (physicalNetworkId != null) {
            pNtwk = getPhysicalNetwork(physicalNetworkId);
            if (pNtwk == null) {
                throw new InvalidParameterValueException("Unable to find a physical network having the specified physical network id");
            }
        } else {
            throw new CloudRuntimeException("cannot create Guestnetwork without physical network.");
        }
        return pNtwk;
    }

    private Account getOwningAccount(CreateNetworkCmd cmd, Account caller) {
        Account owner = null;
        Long domainId = cmd.getDomainId();
        if ((cmd.getAccountName() != null && domainId != null) || cmd.getProjectId() != null) {
            owner = _accountMgr.finalizeOwner(caller, cmd.getAccountName(), domainId, cmd.getProjectId());
        } else {
            logger.info(String.format("Assigning the network to caller:%s because either projectId or accountname and domainId are not provided", caller.getAccountName()));
            owner = caller;
        }
        return owner;
    }

    @NotNull
    private NetworkOffering getAndValidateNetworkOffering(Long networkOfferingId) {
        NetworkOfferingVO ntwkOff = _networkOfferingDao.findById(networkOfferingId);
        if (ntwkOff == null || ntwkOff.isSystemOnly()) {
            InvalidParameterValueException ex = new InvalidParameterValueException("Unable to find network offering by specified id");
            if (ntwkOff != null) {
                ex.addProxyObject(ntwkOff.getUuid(), NETWORK_OFFERING_ID);
            }
            throw ex;
        }
        return ntwkOff;
    }

    protected void mtuCheckForVpcNetwork(Long vpcId, Pair<Integer, Integer> interfaceMTUs, Integer publicMtu) {
        networkMtuService.mtuCheckForVpcNetwork(vpcId, interfaceMTUs, publicMtu);
    }

    protected Pair<Integer, Integer> validateMtuConfig(Integer publicMtu, Integer privateMtu, Long zoneId) {
        return networkMtuService.validateMtuConfig(publicMtu, privateMtu, zoneId);
    }

    private Network implementAssociatedNetwork(Long associatedNetworkId, Account caller, Account owner, DataCenter zone, Long domainId, Long accountId,
                                               String cidr, String startIp, String endIp) throws InsufficientCapacityException {
        Network associatedNetwork = _networksDao.findById(associatedNetworkId);
        if (associatedNetwork == null) {
            throw new InvalidParameterValueException("Cannot find associated network with id = " + associatedNetworkId);
        }
        if (associatedNetwork.getGuestType() != GuestType.Isolated && associatedNetwork.getGuestType() != GuestType.L2) {
            throw new InvalidParameterValueException("Associated network MUST be an Isolated or L2 network");
        }
        _accountMgr.checkAccess(caller, null, true, associatedNetwork);
        if (accountId != null && associatedNetwork.getAccountId() != accountId) {
            throw new InvalidParameterValueException("The new network and associated network MUST be owned by same account");
        }
        if (domainId != null && associatedNetwork.getDomainId() != domainId) {
            throw new InvalidParameterValueException("The new network and associated network MUST be in same domain");
        }
        if (cidr != null && associatedNetwork.getCidr() != null) {
            String[] guestVmCidrPair = associatedNetwork.getCidr().split("\\/");
            String[] cidrIpRange = NetUtils.getIpRangeFromCidr(guestVmCidrPair[0], Long.valueOf(guestVmCidrPair[1]));
            if (StringUtils.isNoneBlank(startIp, endIp) && NetUtils.ipRangesOverlap(startIp, endIp, cidrIpRange[0], cidrIpRange[1])) {
                throw new InvalidParameterValueException(String.format("The IP range (%s-%s) overlaps with cidr of associated network: %s (%s)",
                        startIp, endIp, associatedNetwork.getName(), associatedNetwork.getCidr()));
            }
        }
        // Check IP range overlap on shared networks and vpc private gateways associated to the same network
        checkIpRangeOverlapWithAssociatedNetworks(associatedNetworkId, startIp, endIp);

        associatedNetwork = implementedNetworkInCreation(caller, zone, associatedNetwork);
        if (associatedNetwork == null || (associatedNetwork.getState() != Network.State.Implemented && associatedNetwork.getState() != Network.State.Setup)) {
            throw new InvalidParameterValueException("Unable to implement associated network " + associatedNetwork);
        }
        return associatedNetwork;
    }

    private Network implementedNetworkInCreation(final Account caller, final DataCenter zone, final Network network) throws InsufficientCapacityException {
        try {
            DeployDestination dest = new DeployDestination(zone, null, null, null);
            UserVO callerUser = _userDao.findById(CallContext.current().getCallingUserId());
            Journal journal = new Journal.LogJournal("Implementing " + network, logger);
            ReservationContext context = new ReservationContextImpl(UUID.randomUUID().toString(), journal, callerUser, caller);
            logger.debug("Implementing network " + network + " as a part of network provision for persistent network");
            Pair<? extends NetworkGuru, ? extends Network> implementedNetwork = _networkMgr.implementNetwork(network.getId(), dest, context);
            if (implementedNetwork == null || implementedNetwork.first() == null) {
                logger.warn("Failed to provision the network " + network);
            }
            return implementedNetwork.second();
        } catch (ResourceUnavailableException ex) {
            logger.warn("Failed to implement persistent guest network " + network + "due to ", ex);
            CloudRuntimeException e = new CloudRuntimeException("Failed to implement persistent guest network");
            e.addProxyObject(network.getUuid(), "networkId");
            throw e;
        }
    }

    private void validateNetworkOfferingForNonRootAdminUser(NetworkOffering ntwkOff) {
        networkCreationValidationService.validateNetworkOfferingForNonRootAdminUser(ntwkOff);
    }

    /**
     * Retrieve information (if set) for private VLAN when creating the network
     */
    protected Pair<String, PVlanType> getPrivateVlanPair(String pvlanId, String pvlanTypeStr, String vlanId) {
        return networkCreationValidationService.getPrivateVlanPair(pvlanId, pvlanTypeStr, vlanId);
    }

    /**
     * Basic checks for setting up private VLANs, considering the VLAN ID, secondary VLAN ID and private VLAN type
     */
    protected void performBasicPrivateVlanChecks(String vlanId, String secondaryVlanId, PVlanType privateVlanType) {
        networkCreationValidationService.performBasicPrivateVlanChecks(vlanId, secondaryVlanId, privateVlanType);
    }

    protected Network commitNetwork(final Long networkOfferingId, final String gateway, final String startIP, final String endIP, final String netmask, final String networkDomain, final String vlanIdFinal,
                                  final Boolean bypassVlanOverlapCheck, final String name, final String displayText, final Account caller, final Long physicalNetworkId, final Long zoneId, final Long domainId,
                                  final boolean isDomainSpecific, final Boolean subdomainAccessFinal, final Long vpcId, final String startIPv6, final String endIPv6, final String ip6Gateway, final String ip6Cidr,
                                  final Boolean displayNetwork, final Long aclId, final String isolatedPvlan, final PVlanType isolatedPvlanType, final NetworkOffering ntwkOff, final PhysicalNetwork pNtwk, final ACLType aclType, final Account ownerFinal,
                                  final String cidr, final boolean createVlan, final String externalId, String routerIp, String routerIpv6,
                                  final Network associatedNetwork, final String ip4Dns1, final String ip4Dns2, final String ip6Dns1, final String ip6Dns2, Pair<Integer, Integer> vrIfaceMTUs,
                                  final Integer networkCidrSize, final boolean keepMacAddressOnPublicNic) throws InsufficientCapacityException, ResourceAllocationException {
        try {
            Network network = Transaction.execute(new TransactionCallbackWithException<Network, Exception>() {
                @Override
                public Network doInTransaction(TransactionStatus status) throws InsufficientCapacityException, ResourceAllocationException {
                    Account owner = ownerFinal;
                    Boolean subdomainAccess = subdomainAccessFinal;

                    Long sharedDomainId = null;
                    if (isDomainSpecific) {
                        if (domainId != null) {
                            sharedDomainId = domainId;
                        } else {
                            sharedDomainId = _domainMgr.getDomain(Domain.ROOT_DOMAIN).getId();
                            subdomainAccess = true;
                        }
                    }

                    // default owner to system if network has aclType=Domain
                    if (aclType == ACLType.Domain) {
                        owner = _accountMgr.getAccount(Account.ACCOUNT_ID_SYSTEM);
                    }

                    String vlanId = vlanIdFinal;
                    if (createVlan && vlanId == null && ntwkOff.getGuestType() == Network.GuestType.Shared && ! ntwkOff.isSpecifyVlan()) {
                        if (associatedNetwork != null) {
                            // Get vlanId from associated network
                            vlanId = associatedNetwork.getBroadcastUri().toString();
                        } else {
                            // Allocate a vnet to shared network with specifyvlan=false
                            vlanId = _dcDao.allocateVnet(zoneId, physicalNetworkId, owner.getAccountId(), null, GuestNetworkGuru.UseSystemGuestVlans.valueIn(owner.getAccountId()));
                            if (vlanId == null) {
                                throw new InvalidParameterValueException("Cannot allocate a vnet for this Shared network");
                            }
                        }
                    }

                    // Create guest network
                    Network network = null;
                    if (vpcId != null) {
                        if (!_configMgr.isOfferingForVpc(ntwkOff)) {
                            throw new InvalidParameterValueException("Network offering can't be used for VPC networks");
                        }

                        if (aclId != null) {
                            NetworkACL acl = _networkACLDao.findById(aclId);
                            if (acl == null) {
                                throw new InvalidParameterValueException("Unable to find specified NetworkACL");
                            }

                            Long aclVpcId = acl.getVpcId();
                            if (!isDefaultAcl(aclId) && isAclAttachedToVpc(aclVpcId, vpcId)) {
                                throw new InvalidParameterValueException(String.format("ACL [%s] does not belong to the VPC [%s].", acl, aclVpcId));
                            }
                        }
                        network = _vpcMgr.createVpcGuestNetwork(networkOfferingId, name, displayText, gateway, cidr, vlanId, networkDomain, owner, sharedDomainId, pNtwk, zoneId, aclType,
                                subdomainAccess, vpcId, aclId, caller, displayNetwork, externalId, ip6Gateway, ip6Cidr, ip4Dns1, ip4Dns2, ip6Dns1, ip6Dns2, vrIfaceMTUs, networkCidrSize);
                    } else {
                        if (_configMgr.isOfferingForVpc(ntwkOff)) {
                            throw new InvalidParameterValueException("Network offering can be used for VPC networks only");
                        }
                        if (ntwkOff.isInternalLb()) {
                            throw new InvalidParameterValueException("Internal Lb can be enabled on vpc networks only");
                        }
                        network = _networkMgr.createGuestNetwork(networkOfferingId, name, displayText, gateway, cidr, vlanId, bypassVlanOverlapCheck, networkDomain, owner, sharedDomainId, pNtwk,
                                zoneId, aclType, subdomainAccess, vpcId, ip6Gateway, ip6Cidr, displayNetwork, isolatedPvlan, isolatedPvlanType, externalId, routerIp, routerIpv6, ip4Dns1, ip4Dns2,
                                ip6Dns1, ip6Dns2, vrIfaceMTUs, networkCidrSize, keepMacAddressOnPublicNic);
                    }

                    if (createVlan && network != null) {
                        Provider networkProvider = getNetworkOfferingProvider(ntwkOff);
                        // Create vlan ip range
                        _configMgr.createVlanAndPublicIpRange(pNtwk.getDataCenterId(), network.getId(), physicalNetworkId, false, false, null, startIP, endIP, gateway, netmask, vlanId,
                                bypassVlanOverlapCheck, null, null, startIPv6, endIPv6, ip6Gateway, ip6Cidr, networkProvider);
                    }
                    if (associatedNetwork != null) {
                        _networkDetailsDao.persist(new NetworkDetailVO(network.getId(), Network.AssociatedNetworkId, String.valueOf(associatedNetwork.getId()), true));
                    }
                    return network;
                }
            });
            if (domainId != null && aclType == ACLType.Domain) {
                // send event for storing the domain wide resource access
                Map<String, Object> params = new HashMap<String, Object>();
                params.put(ApiConstants.ENTITY_TYPE, Network.class);
                params.put(ApiConstants.ENTITY_ID, network.getId());
                params.put(ApiConstants.DOMAIN_ID, domainId);
                params.put(ApiConstants.SUBDOMAIN_ACCESS, subdomainAccessFinal == null ? Boolean.TRUE : subdomainAccessFinal);
                _messageBus.publish(_name, EntityManager.MESSAGE_ADD_DOMAIN_WIDE_ENTITY_EVENT, PublishScope.LOCAL, params);
            }
            return network;
        } catch (Exception e) {
            ExceptionUtil.rethrowRuntime(e);
            ExceptionUtil.rethrow(e, InsufficientCapacityException.class);
            ExceptionUtil.rethrow(e, ResourceAllocationException.class);
            throw new IllegalStateException(e);
        }
    }

    private Provider getNetworkOfferingProvider(NetworkOffering networkOffering) {
        if (_networkModel.isProviderForNetworkOffering(Provider.Nsx, networkOffering.getId())) {
            return Provider.Nsx;
        } else if (_networkModel.isProviderForNetworkOffering(Provider.Netris, networkOffering.getId())) {
            return Provider.Netris;
        }
        return null;
    }

    @Override
    public Pair<List<? extends Network>, Integer> searchForNetworks(ListNetworksCmd cmd) {
        return networkSearchService.searchForNetworks(cmd);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_DELETE, eventDescription = "deleting network", async = true)
    public boolean deleteNetwork(long networkId, boolean forced) {

        Account caller = CallContext.current().getCallingAccount();

        // Verify network id
        NetworkVO network = getNetworkVO(networkId, "Unable to find a network with the specified ID.");

        // don't allow to delete system network
        if (isNetworkSystem(network)) {
            throwInvalidIdException("Network with specified id is system and can't be removed", network.getUuid(), "networkId");
        }

        List<NetworkDetailVO> associatedNetworks = _networkDetailsDao.findDetails(Network.AssociatedNetworkId, String.valueOf(networkId), null);
        for (NetworkDetailVO networkDetailVO : associatedNetworks) {
            NetworkVO associatedNetwork = _networksDao.findById(networkDetailVO.getResourceId());
            if (associatedNetwork != null) {
                String msg = String.format("Cannot delete network %s which is associated to another network %s", network.getUuid(), associatedNetwork.getUuid());
                logger.debug(msg);
                throw new InvalidParameterValueException(msg);
            }
        }

        Account owner = _accountMgr.getAccount(network.getAccountId());

        if (forced && !_accountMgr.isRootAdmin(caller.getId())) {
            throw new InvalidParameterValueException("Delete network with 'forced' option can only be called by root admins");
        }

        User callerUser = _accountMgr.getActiveUser(CallContext.current().getCallingUserId());
        ReservationContext context = new ReservationContextImpl(null, null, callerUser, owner);

        return _networkMgr.destroyNetwork(networkId, context, forced);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_RESTART, eventDescription = "restarting network", async = true)
    public boolean restartNetwork(Long networkId, boolean cleanup, boolean makeRedundant, boolean livePatch, User user) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        NetworkVO network = getNetworkVO(networkId, "Network with specified id doesn't exist");
        return restartNetwork(network, cleanup, makeRedundant, livePatch, user);
    }

    private NetworkVO getNetworkVO(Long networkId, String errMsgFormat) {
        NetworkVO network = _networksDao.findById(networkId);
        if (network == null) {
            throwInvalidIdException(errMsgFormat, networkId.toString(), "networkId");
        }
        return network;
    }

    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_RESTART, eventDescription = "restarting network", async = true)
    public boolean restartNetwork(NetworkVO network, boolean cleanup, boolean makeRedundant, boolean livePatch, User user) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {

        // Don't allow to restart network if it's not in Implemented/Setup state
        if (!(network.getState() == Network.State.Implemented || network.getState() == Network.State.Setup)) {
            throw new InvalidParameterValueException("Network is not in the right state to be restarted. Correct states are: " + Network.State.Implemented + ", " + Network.State.Setup);
        }

        if (network.getBroadcastDomainType() == BroadcastDomainType.Lswitch) {
            /**
             * Unable to restart these networks now.
             * TODO Restarting a SDN based network requires updating the nics and the configuration
             * in the controller. This requires a non-trivial rewrite of the restart procedure.
             */
            throw new InvalidParameterValueException("Unable to restart a running SDN network.");
        }

        Account callerAccount = _accountMgr.getActiveAccountById(user.getAccountId());
        _accountMgr.checkAccess(callerAccount, AccessType.OperateEntry, true, network);
        if (!network.isRedundant() && makeRedundant) {
            NetworkOffering networkOffering = _entityMgr.findById(NetworkOffering.class, network.getNetworkOfferingId());
            Map<Network.Capability, String> sourceNatCapabilities = getNetworkOfferingServiceCapabilities(networkOffering, Service.SourceNat);
            String isRedundantRouterSupported = sourceNatCapabilities.get(Capability.RedundantRouter);
            if (!Boolean.parseBoolean(isRedundantRouterSupported)) {
                throw new InvalidParameterValueException(String.format("Redundant router is not supported by the network offering %s", networkOffering));
            }
            network.setRedundant(true);
            if (!_networksDao.update(network.getId(), network)) {
                throw new CloudRuntimeException("Failed to update network into a redundant one, please try again");
            }
            cleanup = true;
        }
        if (cleanup) {
            livePatch = false;
        }
        long id = network.getId();
        boolean success = _networkMgr.restartNetwork(id, callerAccount, user, cleanup, livePatch);
        if (success) {
            logger.debug("Network {} is restarted successfully.", network);
        } else {
            logger.warn("Network {} failed to restart.", network);
        }

        return success;
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_RESTART, eventDescription = "restarting network", async = true)
    public boolean restartNetwork(RestartNetworkCmd cmd) throws ConcurrentOperationException, ResourceUnavailableException, InsufficientCapacityException {
        // This method restarts all network elements belonging to the network and re-applies all the rules
        NetworkVO network = getNetworkVO(cmd.getNetworkId(), "Network [%s] to restart was not found.");
        boolean cleanup = cmd.getCleanup();
        if (network.getVpcId() != null && cleanup) {
            throwInvalidIdException("Cannot restart a VPC tier with cleanup, please restart the whole VPC.", network.getUuid(), "network tier");
        }
        boolean makeRedundant = cmd.getMakeRedundant();
        User callerUser = _accountMgr.getActiveUser(CallContext.current().getCallingUserId());
        if (makeRedundant && !_accountMgr.isRootAdmin(callerUser.getAccountId()) && !AllowUsersToMakeNetworksRedundant.value() ) {
            throw new InvalidParameterValueException("Could not make the network redundant. Please contact administrator.");
        }

        boolean livePatch = cmd.getLivePatch();
        return restartNetwork(network, cleanup, makeRedundant, livePatch, callerUser);
    }

    @Override
    public int getActiveNicsInNetwork(long networkId) {
        return _networksDao.getActiveNicsIn(networkId);
    }

    @Override
    public Map<Capability, String> getNetworkOfferingServiceCapabilities(NetworkOffering offering, Service service) {

        if (!areServicesSupportedByNetworkOffering(offering.getId(), service)) {
            // TBD: We should be sending networkOfferingId and not the offering object itself.
            throw new UnsupportedServiceException("Service " + service.getName() + " is not supported by the network offering " + offering);
        }

        Map<Capability, String> serviceCapabilities = new HashMap<Capability, String>();

        // get the Provider for this Service for this offering
        List<String> providers = _ntwkOfferingSrvcDao.listProvidersForServiceForNetworkOffering(offering.getId(), service);
        if (providers.isEmpty()) {
            // TBD: We should be sending networkOfferingId and not the offering object itself.
            throw new InvalidParameterValueException("Service " + service.getName() + " is not supported by the network offering " + offering);
        }

        // FIXME - in post 3.0 we are going to support multiple providers for the same service per network offering, so
        // we have to calculate capabilities for all of them
        String provider = providers.get(0);

        // FIXME we return the capabilities of the first provider of the service - what if we have multiple providers
        // for same Service?
        NetworkElement element = _networkModel.getElementImplementingProvider(provider);
        if (element != null) {
            Map<Service, Map<Capability, String>> elementCapabilities = element.getCapabilities();
            ;

            if (elementCapabilities == null || !elementCapabilities.containsKey(service)) {
                // TBD: We should be sending providerId and not the offering object itself.
                throw new UnsupportedServiceException("Service " + service.getName() + " is not supported by the element=" + element.getName() + " implementing Provider=" + provider);
            }
            serviceCapabilities = elementCapabilities.get(service);
        }

        return serviceCapabilities;
    }

    @Override
    public IpAddress getIp(long ipAddressId) {
        return ipAddressLifecycleService.getIp(ipAddressId);
    }

    @Override
    public IpAddress getIp(String ipAddress) {
        return ipAddressLifecycleService.getIp(ipAddress);
    }

    protected boolean providersConfiguredForExternalNetworking(Collection<String> providers) {
        for (String providerStr : providers) {
            Provider provider = Network.Provider.getProvider(providerStr);
            if (provider.isExternal()) {
                return true;
            }
        }
        return false;
    }

    protected boolean isSharedNetworkOfferingWithServices(long networkOfferingId) {
        NetworkOfferingVO networkOffering = _networkOfferingDao.findById(networkOfferingId);
        if ((networkOffering.getGuestType() == Network.GuestType.Shared) && (areServicesSupportedByNetworkOffering(networkOfferingId, Service.SourceNat)
                || areServicesSupportedByNetworkOffering(networkOfferingId, Service.StaticNat) || areServicesSupportedByNetworkOffering(networkOfferingId, Service.Firewall)
                || areServicesSupportedByNetworkOffering(networkOfferingId, Service.PortForwarding) || areServicesSupportedByNetworkOffering(networkOfferingId, Service.Lb))) {
            return true;
        }
        return false;
    }

    protected boolean areServicesSupportedByNetworkOffering(long networkOfferingId, Service... services) {
        return (_ntwkOfferingSrvcDao.areServicesSupportedByNetworkOffering(networkOfferingId, services));
    }

    protected boolean areServicesSupportedInNetwork(long networkId, Service... services) {
        return (_ntwkSrvcDao.areServicesSupportedInNetwork(networkId, services));
    }

    private boolean checkForNonStoppedVmInNetwork(long networkId) {
        List<UserVmVO> vms = _userVmDao.listByNetworkIdAndStates(networkId, VirtualMachine.State.Starting, VirtualMachine.State.Running, VirtualMachine.State.Migrating, VirtualMachine.State.Stopping);
        return vms.isEmpty();
    }

    private void replugNicsForUpdatedNetwork(NetworkVO network) throws ResourceUnavailableException, InsufficientCapacityException {
        List<NicVO> nics = _nicDao.listByNetworkId(network.getId());
        Network updatedNetwork = getNetwork(network.getId());
        for (NicVO nic : nics) {
            if (Nic.ReservationStrategy.PlaceHolder.equals(nic.getReservationStrategy())) {
                continue;
            }
            long vmId = nic.getInstanceId();
            VMInstanceVO vm = _vmDao.findById(vmId);
            if (vm == null) {
                logger.error(String.format("Cannot replug NIC: %s as Instance for it is not found with ID: %d", nic, vmId));
                continue;
            }
            if (!Hypervisor.HypervisorType.VMware.equals(vm.getHypervisorType())) {
                logger.debug(String.format("Cannot replug NIC: %s for Instance: %s as it is not on VMware", nic, vm));
                continue;
            }
            if (!VirtualMachine.Type.User.equals(vm.getType())) {
                logger.debug(String.format("Cannot replug NIC: %s for VM: %s as it is not a user VM", nic, vm));
                continue;
            }
            if (!VirtualMachine.State.Running.equals(vm.getState())) {
                logger.debug(String.format("Cannot replug NIC: %s for VM: %s as it is not in running state", nic, vm));
                continue;
            }
            Host host = _hostDao.findById(vm.getHostId());
            VirtualMachineProfile vmProfile = new VirtualMachineProfileImpl(vm, null, null, null, null);
            NicProfile nicProfile = new NicProfile(nic, network, nic.getBroadcastUri(), nic.getIsolationUri(),
                    _networkModel.getNetworkRate(network.getId(), vm.getId()),
                    _networkModel.isSecurityGroupSupportedInNetwork(updatedNetwork),
                    _networkModel.getNetworkTag(vmProfile.getVirtualMachine().getHypervisorType(), network));
            vmManager.replugNic(updatedNetwork, vmManager.toNicTO(nicProfile, vm.getHypervisorType()), vmManager.toVmTO(vmProfile), host);
        }
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_UPDATE, eventDescription = "updating network", async = true)
    public Network updateGuestNetwork(final UpdateNetworkCmd cmd) {
        User callerUser = _accountService.getActiveUser(CallContext.current().getCallingUserId());
        Account callerAccount = _accountService.getActiveAccountById(callerUser.getAccountId());
        final long networkId = cmd.getId();
        String name = cmd.getNetworkName();
        String displayText = cmd.getDisplayText();
        String domainSuffix = cmd.getNetworkDomain();
        final Long networkOfferingId = cmd.getNetworkOfferingId();
        Boolean changeCidr = cmd.getChangeCidr();
        String guestVmCidr = cmd.getGuestVmCidr();
        Boolean displayNetwork = cmd.getDisplayNetwork();
        String customId = cmd.getCustomId();
        boolean updateInSequence = cmd.getUpdateInSequence();
        Integer publicMtu = cmd.getPublicMtu();
        Integer privateMtu = cmd.getPrivateMtu();
        boolean forced = cmd.getForced();
        String ip4Dns1 = cmd.getIp4Dns1();
        String ip4Dns2 = cmd.getIp4Dns2();
        String ip6Dns1 = cmd.getIp6Dns1();
        String ip6Dns2 = cmd.getIp6Dns2();
        Boolean keepMacAddressOnPublicNic = cmd.getKeepMacAddressOnPublicNic();

        boolean restartNetwork = false;

        // verify input parameters
        final NetworkVO network = getNetworkVO(networkId, "Specified network id doesn't exist in the system");
        String prevNetworkName = network.getName();
        //perform below validation if the network is vpc network
        if (network.getVpcId() != null && networkOfferingId != null) {
            Vpc vpc = _entityMgr.findById(Vpc.class, network.getVpcId());
            _vpcMgr.validateNtwkOffForNtwkInVpc(networkId, networkOfferingId, null, null, vpc, null, _accountMgr.getAccount(network.getAccountId()), network.getNetworkACLId());
        }

        // don't allow to update network in Destroy state
        if (network.getState() == Network.State.Destroy) {
            throw new InvalidParameterValueException("Don't allow to update network in state " + Network.State.Destroy);
        }

        // Don't allow to update system network
        NetworkOffering offering = _networkOfferingDao.findByIdIncludingRemoved(network.getNetworkOfferingId());
        if (offering.isSystemOnly()) {
            throw new InvalidParameterValueException("Can't update system networks");
        }

        // allow to upgrade only Guest networks
        if (network.getTrafficType() != Networks.TrafficType.Guest) {
            throw new InvalidParameterValueException("Can't allow networks which traffic type is not " + TrafficType.Guest);
        }

        _accountMgr.checkAccess(callerAccount, AccessType.OperateEntry, true, network);
        _accountMgr.checkAccess(_accountMgr.getActiveAccountById(network.getAccountId()), offering, _dcDao.findById(network.getDataCenterId()));

        restartNetwork |= checkAndUpdateRouterSourceNatIp(cmd, network);

        if (cmd instanceof UpdateNetworkCmdByAdmin) {
            final Boolean hideIpAddressUsage = ((UpdateNetworkCmdByAdmin) cmd).getHideIpAddressUsage();
            if (hideIpAddressUsage != null) {
                final NetworkDetailVO detail = _networkDetailsDao.findDetail(network.getId(), Network.hideIpAddressUsage);
                if (detail != null) {
                    detail.setValue(hideIpAddressUsage.toString());
                    _networkDetailsDao.update(detail.getId(), detail);
                } else {
                    _networkDetailsDao.persist(new NetworkDetailVO(network.getId(), Network.hideIpAddressUsage, hideIpAddressUsage.toString(), false));
                }
            }
        }

        if (name != null) {
            network.setName(name);
        }

        if (displayText != null) {
            network.setDisplayText(displayText);
        }

        if (customId != null) {
            network.setUuid(customId);
        }

        if (keepMacAddressOnPublicNic != null) {
            network.setKeepMacAddressOnPublicNic(getAndValidateSupportForKeepMacAddressOnPublicNicParameter(keepMacAddressOnPublicNic, offering));
        }

        // display flag is not null and has changed
        if (displayNetwork != null && displayNetwork != network.getDisplayNetwork()) {
            // Update resource count if it needs to be updated
            NetworkOffering networkOffering = _networkOfferingDao.findById(network.getNetworkOfferingId());
            if (_networkMgr.isResourceCountUpdateNeeded(networkOffering)) {
                _resourceLimitMgr.changeResourceCount(network.getAccountId(), Resource.ResourceType.network, displayNetwork);
            }

            network.setDisplayNetwork(displayNetwork);
        }

        // network offering and domain suffix can be updated for Isolated networks only in 3.0
        if (networkOfferingId != null && network.getGuestType() != GuestType.Isolated) {
            throw new InvalidParameterValueException("NetworkOffering update can be performed for Isolated networks only.");
        }
        // network offering and domain suffix can be updated for Isolated networks only in 3.0
        if (domainSuffix != null && ! Arrays.asList(GuestType.Isolated, GuestType.Shared).contains(network.getGuestType())) {
            throw new InvalidParameterValueException("Domain suffix update can only be performed for Isolated and shared networks.");
        }

        boolean networkOfferingChanged = false;

        final long oldNetworkOfferingId = network.getNetworkOfferingId();
        NetworkOffering oldNtwkOff = _networkOfferingDao.findByIdIncludingRemoved(oldNetworkOfferingId);
        NetworkOfferingVO networkOffering = _networkOfferingDao.findById(networkOfferingId);
        if (networkOfferingId != null) {
            if (networkOffering == null || networkOffering.isSystemOnly()) {
                throwInvalidIdException("Unable to find network offering with specified id", networkOfferingId.toString(), NETWORK_OFFERING_ID);
            }

            // network offering should be in Enabled state
            if (networkOffering.getState() != NetworkOffering.State.Enabled) {
                throwInvalidIdException("Network offering with specified id is not in " + NetworkOffering.State.Enabled + " state, can't upgrade to it", networkOffering.getUuid(),
                        NETWORK_OFFERING_ID);
            }
            //can't update from vpc to non-vpc network offering
            boolean forVpcNew = _configMgr.isOfferingForVpc(networkOffering);
            boolean vorVpcOriginal = _configMgr.isOfferingForVpc(_entityMgr.findById(NetworkOffering.class, oldNetworkOfferingId));
            if (forVpcNew != vorVpcOriginal) {
                String errMsg = forVpcNew ? "a vpc offering " : "not a vpc offering";
                throw new InvalidParameterValueException("Can't update as the new offering is " + errMsg);
            }

            if (networkOfferingId != oldNetworkOfferingId) {
                Collection<String> newProviders = _networkMgr.finalizeServicesAndProvidersForNetwork(networkOffering, network.getPhysicalNetworkId()).values();
                Collection<String> oldProviders = _networkMgr.finalizeServicesAndProvidersForNetwork(oldNtwkOff, network.getPhysicalNetworkId()).values();

                if (providersConfiguredForExternalNetworking(newProviders) != providersConfiguredForExternalNetworking(oldProviders) && !changeCidr) {
                    throw new InvalidParameterValueException("Updating network failed since guest CIDR needs to be changed!");
                }
                if (changeCidr) {
                    if (!checkForNonStoppedVmInNetwork(network.getId())) {
                        throwInvalidIdException("All user vm of network of specified id should be stopped before changing CIDR!", network.getUuid(), "networkId");
                    }
                }
                // check if the network is upgradable
                if (!canUpgrade(network, oldNetworkOfferingId, networkOfferingId)) {
                    throw new InvalidParameterValueException("Can't upgrade from network offering " + oldNtwkOff.getUuid() + " to " + networkOffering.getUuid() + "; check logs for more information");
                }
                boolean isIpv6Supported = _networkOfferingDao.isIpv6Supported(oldNetworkOfferingId);
                boolean isIpv6SupportedNew = _networkOfferingDao.isIpv6Supported(networkOfferingId);
                if (!isIpv6Supported && isIpv6SupportedNew) {
                    try {
                        ipv6Service.checkNetworkIpv6Upgrade(network);
                    } catch (ResourceAllocationException | InsufficientAddressCapacityException ex) {
                        throw new CloudRuntimeException(String.format("Failed to upgrade network offering to '%s' as unable to allocate IPv6 network", networkOffering.getDisplayText()), ex);
                    }
                }
                restartNetwork = true;
                networkOfferingChanged = true;

                //Setting the new network's isReduntant to the new network offering's RedundantRouter.
                network.setRedundant(_networkOfferingDao.findById(networkOfferingId).isRedundantRouter());
            }
        }

        restartNetwork |= checkAndUpdateNetworkDns(network, networkOfferingChanged ? networkOffering : oldNtwkOff, ip4Dns1, ip4Dns2,
                ip6Dns1, ip6Dns2);

        final Map<String, String> newSvcProviders = networkOfferingChanged
                ? _networkMgr.finalizeServicesAndProvidersForNetwork(_entityMgr.findById(NetworkOffering.class, networkOfferingId), network.getPhysicalNetworkId())
                : new HashMap<String, String>();

        // don't allow to modify network domain if the service is not supported
        if (domainSuffix != null) {
            // validate network domain
            if (!NetUtils.verifyDomainName(domainSuffix)) {
                throw new InvalidParameterValueException(
                        "Invalid network domain. Total length shouldn't exceed 190 chars. Each domain label must be between 1 and 63 characters long, can contain ASCII letters 'a' through 'z', the digits '0' through '9', "
                                + "and the hyphen ('-'); can't start or end with \"-\"");
            }

            long offeringId = oldNetworkOfferingId;
            if (networkOfferingId != null) {
                offeringId = networkOfferingId;
            }

            Map<Network.Capability, String> dnsCapabilities = getNetworkOfferingServiceCapabilities(_entityMgr.findById(NetworkOffering.class, offeringId), Service.Dns);
            String isUpdateDnsSupported = dnsCapabilities.get(Capability.AllowDnsSuffixModification);
            if (isUpdateDnsSupported == null || !Boolean.valueOf(isUpdateDnsSupported)) {
                // TBD: use uuid instead of networkOfferingId. May need to hardcode tablename in call to addProxyObject().
                throw new InvalidParameterValueException(String.format("Domain name change is not supported by the network offering %s", networkOffering));
            }

            network.setNetworkDomain(domainSuffix);
            // have to restart the network
            restartNetwork = true;
        }

        //IP reservation checks
        // allow reservation only to Isolated Guest networks
        DataCenter dc = _dcDao.findById(network.getDataCenterId());
        String networkCidr = network.getNetworkCidr();

        if (guestVmCidr != null) {
            if (dc.getNetworkType() == NetworkType.Basic) {
                throw new InvalidParameterValueException("Guest VM CIDR can't be specified for zone with " + NetworkType.Basic + " networking");
            }
            if (network.getGuestType() != GuestType.Isolated) {
                throw new InvalidParameterValueException("Can only allow IP Reservation in networks with guest type " + GuestType.Isolated);
            }
            if (networkOfferingChanged) {
                throw new InvalidParameterValueException("Cannot specify this network offering change and guestVmCidr at same time. Specify only one.");
            }
            if (network.getState() != Network.State.Implemented && network.getState() != Network.State.Allocated) {
                throw new InvalidParameterValueException(String.format("The network must be in %s or %s state. IP Reservation cannot be applied in %s state",
                        Network.State.Implemented, Network.State.Allocated, network.getState()));
            }
            if (!NetUtils.isValidIp4Cidr(guestVmCidr)) {
                throw new InvalidParameterValueException("Invalid format of Guest VM CIDR.");
            }
            if (!NetUtils.validateGuestCidr(guestVmCidr, !ConfigurationManager.AllowNonRFC1918CompliantIPs.value())) {
                throw new InvalidParameterValueException("Invalid format of Guest VM CIDR. Make sure it is RFC1918 compliant. ");
            }

            // If networkCidr is null it implies that there was no prior IP reservation, so the network cidr is network.getCidr()
            // But in case networkCidr is a non null value (IP reservation already exists), it implies network cidr is networkCidr
            if (networkCidr != null) {
                if (!NetUtils.isNetworkAWithinNetworkB(guestVmCidr, networkCidr)) {
                    throw new InvalidParameterValueException("Invalid value of Guest VM CIDR. For IP Reservation, Guest VM CIDR  should be a subset of network CIDR : " + networkCidr);
                }
            } else {
                if (!NetUtils.isNetworkAWithinNetworkB(guestVmCidr, network.getCidr())) {
                    throw new InvalidParameterValueException("Invalid value of Guest VM CIDR. For IP Reservation, Guest VM CIDR  should be a subset of network CIDR :  " + network.getCidr());
                }
            }

            // This check makes sure there are no active IPs existing outside the guestVmCidr in the network
            String[] guestVmCidrPair = guestVmCidr.split("\\/");
            Long size = Long.valueOf(guestVmCidrPair[1]);
            List<NicVO> nicsPresent = _nicDao.listByNetworkId(networkId);

            String cidrIpRange[] = NetUtils.getIpRangeFromCidr(guestVmCidrPair[0], size);
            logger.info("The start IP of the specified guest vm cidr is: " + cidrIpRange[0] + " and end IP is: " + cidrIpRange[1]);
            long startIp = NetUtils.ip2Long(cidrIpRange[0]);
            long endIp = NetUtils.ip2Long(cidrIpRange[1]);
            long range = endIp - startIp + 1;
            logger.info("The specified guest vm cidr has " + range + " IPs");

            for (NicVO nic : nicsPresent) {
                if (nic.getIPv4Address() == null) {
                    continue;
                }
                long nicIp = NetUtils.ip2Long(nic.getIPv4Address());
                //check if nic IP is outside the guest vm cidr
                if ((nicIp < startIp || nicIp > endIp) && nic.getState() != Nic.State.Deallocating) {
                    throw new InvalidParameterValueException("Active IPs like " + nic.getIPv4Address() + " exist outside the Guest VM CIDR. Cannot apply reservation ");
                }
            }

            // In some scenarios even though guesVmCidr and network CIDR do not appear similar but
            // the IP ranges exactly matches, in these special cases make sure no Reservation gets applied
            if (network.getNetworkCidr() == null) {
                if (NetUtils.isSameIpRange(guestVmCidr, network.getCidr()) && !guestVmCidr.equals(network.getCidr())) {
                    throw new InvalidParameterValueException("The Start IP and End IP of guestvmcidr: " + guestVmCidr + " and CIDR: " + network.getCidr() + " are same, "
                            + "even though both the cidrs appear to be different. As a precaution no IP Reservation will be applied.");
                }
            } else {
                if (NetUtils.isSameIpRange(guestVmCidr, network.getNetworkCidr()) && !guestVmCidr.equals(network.getNetworkCidr())) {
                    throw new InvalidParameterValueException("The Start IP and End IP of guestvmcidr: " + guestVmCidr + " and Network CIDR: " + network.getNetworkCidr() + " are same, "
                            + "even though both the cidrs appear to be different. As a precaution IP Reservation will not be affected. If you want to reset IP Reservation, "
                            + "specify guestVmCidr to be: " + network.getNetworkCidr());
                }
            }

            // Check IP range overlap on shared networks and vpc private gateways associated to this network
            checkIpRangeOverlapWithAssociatedNetworks(networkId, cidrIpRange[0], cidrIpRange[1]);

            // When reservation is applied for the first time, network_cidr will be null
            // Populate it with the actual network cidr
            if (network.getNetworkCidr() == null) {
                network.setNetworkCidr(network.getCidr());
            }

            // Condition for IP Reservation reset : guestVmCidr and network CIDR are same
            if (network.getNetworkCidr().equals(guestVmCidr)) {
                logger.warn("Guest VM CIDR and Network CIDR both are same, reservation will reset.");
                network.setNetworkCidr(null);
            }
            // Finally update "cidr" with the guestVmCidr
            // which becomes the effective address space for CloudStack guest VMs
            network.setCidr(guestVmCidr);
            _networksDao.update(networkId, network);
            logger.info("IP Reservation has been applied. The new CIDR for Guests Vms is " + guestVmCidr);
        }

        networkMtuService.updateNetworkMtu(network, networkId, dc.getId(), publicMtu, privateMtu, restartNetwork);

        ReservationContext context = new ReservationContextImpl(null, null, callerUser, callerAccount);
        // 1) Shutdown all the elements and cleanup all the rules. Don't allow to shutdown network in intermediate
        // states - Shutdown and Implementing
        int resourceCount = 1;
        if (updateInSequence && restartNetwork && _networkOfferingDao.findById(network.getNetworkOfferingId()).isRedundantRouter()
                && (networkOfferingId == null || _networkOfferingDao.findById(networkOfferingId).isRedundantRouter()) && network.getVpcId() == null) {
            _networkMgr.canUpdateInSequence(network, forced);
            NetworkDetailVO networkDetail = new NetworkDetailVO(network.getId(), Network.updatingInSequence, "true", true);
            _networkDetailsDao.persist(networkDetail);
            _networkMgr.configureUpdateInSequence(network);
            resourceCount = _networkMgr.getResourceCount(network);
        }
        List<String> servicesNotInNewOffering = null;
        if (networkOfferingId != null) {
            servicesNotInNewOffering = _networkMgr.getServicesNotSupportedInNewOffering(network, networkOfferingId);
        }
        if (!forced && servicesNotInNewOffering != null && !servicesNotInNewOffering.isEmpty()) {
            NetworkOfferingVO newOffering = _networkOfferingDao.findById(networkOfferingId);
            throw new CloudRuntimeException("The new offering:" + newOffering.getUniqueName() + " will remove the following services " + servicesNotInNewOffering
                    + "along with all the related configuration currently in use. will not proceed with the network update." + "set forced parameter to true for forcing an update.");
        }
        try {
            if (servicesNotInNewOffering != null && !servicesNotInNewOffering.isEmpty()) {
                _networkMgr.cleanupConfigForServicesInNetwork(servicesNotInNewOffering, network);
            }
        } catch (Exception e) { // old pokemon catch that used to catch throwable
            logger.debug("failed to cleanup config related to unused services error:" + e.getMessage());
        }

        boolean validStateToShutdown = (network.getState() == Network.State.Implemented || network.getState() == Network.State.Setup || network.getState() == Network.State.Allocated);
        try {

            do {
                if (restartNetwork) {
                    if (validStateToShutdown) {
                        if (!changeCidr) {
                            logger.debug("Shutting down elements and resources for network {} as a part of network update", network);

                            if (!_networkMgr.shutdownNetworkElementsAndResources(context, true, network)) {
                                logger.warn("Failed to shutdown the network elements and resources as a part of network restart: " + network);
                                CloudRuntimeException ex = new CloudRuntimeException("Failed to shutdown the network elements and resources as a part of update to network of specified id");
                                ex.addProxyObject(network.getUuid(), "networkId");
                                throw ex;
                            }
                        } else {
                            // We need to shutdown the network, since we want to re-implement the network.
                            logger.debug("Shutting down network {} as a part of network update", network);

                            //check if network has reservation
                            if (NetUtils.isNetworkAWithinNetworkB(network.getCidr(), network.getNetworkCidr())) {
                                logger.warn("Existing IP reservation will become ineffective for the network {} You need to reapply reservation after network reimplementation.", network);
                                //set cidr to the network cidr
                                network.setCidr(network.getNetworkCidr());
                                //set networkCidr to null to bring network back to no IP reservation state
                                network.setNetworkCidr(null);
                            }

                            if (!_networkMgr.shutdownNetwork(network.getId(), context, true)) {
                                logger.warn("Failed to shutdown the network as a part of update to network with specified id");
                                CloudRuntimeException ex = new CloudRuntimeException("Failed to shutdown the network as a part of update of specified network id");
                                ex.addProxyObject(network.getUuid(), "networkId");
                                throw ex;
                            }
                        }
                    } else {
                        CloudRuntimeException ex = new CloudRuntimeException(
                                "Failed to shutdown the network elements and resources as a part of update to network with specified id; network is in wrong state: " + network.getState());
                        ex.addProxyObject(network.getUuid(), "networkId");
                        throw ex;
                    }
                }

                // 2) Only after all the elements and rules are shutdown properly, update the network VO
                // get updated network
                Network.State networkState = _networksDao.findById(networkId).getState();
                boolean validStateToImplement = (networkState == Network.State.Implemented || networkState == Network.State.Setup || networkState == Network.State.Allocated);
                if (restartNetwork && !validStateToImplement) {
                    CloudRuntimeException ex = new CloudRuntimeException(
                            "Failed to implement the network elements and resources as a part of update to network with specified id; network is in wrong state: " + networkState);
                    ex.addProxyObject(network.getUuid(), "networkId");
                    throw ex;
                }

                if (networkOfferingId != null) {
                    if (networkOfferingChanged) {
                        Transaction.execute(new TransactionCallbackNoReturn() {
                            @Override
                            public void doInTransactionWithoutResult(TransactionStatus status) {
                                updateNetworkIpv6(network, networkOfferingId);
                                network.setNetworkOfferingId(networkOfferingId);
                                _networksDao.update(networkId, network, newSvcProviders);
                                // get all nics using this network
                                // log remove usage events for old offering
                                // log assign usage events for new offering
                                List<NicVO> nics = _nicDao.listByNetworkId(networkId);
                                for (NicVO nic : nics) {
                                    if (Nic.ReservationStrategy.PlaceHolder.equals(nic.getReservationStrategy())) {
                                        continue;
                                    }
                                    long vmId = nic.getInstanceId();
                                    VMInstanceVO vm = _vmDao.findById(vmId);
                                    if (vm == null) {
                                        logger.error("Instance for NIC {} not found with Instance Id: {}", nic, vmId);
                                        continue;
                                    }
                                    long isDefault = (nic.isDefaultNic()) ? 1 : 0;
                                    String nicIdString = Long.toString(nic.getId());
                                    UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_REMOVE, vm.getAccountId(), vm.getDataCenterId(), vm.getId(), nicIdString, oldNetworkOfferingId,
                                            null, isDefault, VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplay());
                                    UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NETWORK_OFFERING_ASSIGN, vm.getAccountId(), vm.getDataCenterId(), vm.getId(), nicIdString, networkOfferingId,
                                            null, isDefault, VirtualMachine.class.getName(), vm.getUuid(), vm.isDisplay());
                                }
                            }
                        });
                    } else {
                        network.setNetworkOfferingId(networkOfferingId);
                        _networksDao.update(networkId, network,
                                _networkMgr.finalizeServicesAndProvidersForNetwork(_entityMgr.findById(NetworkOffering.class, networkOfferingId), network.getPhysicalNetworkId()));
                    }
                } else {
                    _networksDao.update(networkId, network);
                }

                // 3) Implement the elements and rules again
                if (restartNetwork) {
                    if (network.getState() != Network.State.Allocated) {
                        DeployDestination dest = new DeployDestination(_dcDao.findById(network.getDataCenterId()), null, null, null);
                        logger.debug("Implementing the network " + network + " elements and resources as a part of network update");
                        try {
                            if (!changeCidr) {
                                _networkMgr.implementNetworkElementsAndResources(dest, context, network, _networkOfferingDao.findById(network.getNetworkOfferingId()));
                            } else {
                                _networkMgr.implementNetwork(network.getId(), dest, context);
                            }
                        } catch (Exception ex) {
                            logger.warn("Failed to implement network " + network + " elements and resources as a part of network update due to ", ex);
                            CloudRuntimeException e = new CloudRuntimeException("Failed to implement network (with specified id) elements and resources as a part of network update");
                            e.addProxyObject(network.getUuid(), "networkId");
                            throw e;
                        }
                    }
                    if (networkOfferingChanged) {
                        replugNicsForUpdatedNetwork(network);
                    }
                }

                // 4) if network has been upgraded from a non persistent ntwk offering to a persistent ntwk offering,
                // implement the network if its not already
                if (networkOfferingChanged && !oldNtwkOff.isPersistent() && networkOffering.isPersistent()) {
                    if (network.getState() == Network.State.Allocated) {
                        try {
                            DeployDestination dest = new DeployDestination(_dcDao.findById(network.getDataCenterId()), null, null, null);
                            _networkMgr.implementNetwork(network.getId(), dest, context);
                        } catch (Exception ex) {
                            logger.warn("Failed to implement network " + network + " elements and resources as a part o" + "f network update due to ", ex);
                            CloudRuntimeException e = new CloudRuntimeException("Failed to implement network (with specified" + " id) elements and resources as a part of network update");
                            e.addProxyObject(network.getUuid(), "networkId");
                            throw e;
                        }
                    }
                }
                resourceCount--;
            } while (updateInSequence && resourceCount > 0);
        } catch (Exception exception) {
            if (updateInSequence) {
                _networkMgr.finalizeUpdateInSequence(network, false);
            }
            throw new CloudRuntimeException("failed to update network " + network.getUuid() + " due to " + exception.getMessage(), exception);
        } finally {
            if (updateInSequence) {
                if (_networkDetailsDao.findDetail(networkId, Network.updatingInSequence) != null) {
                    _networkDetailsDao.removeDetail(networkId, Network.updatingInSequence);
                }
            }
        }
        Network updatedNetwork = getNetwork(network.getId());
        UsageEventUtils.publishNetworkUpdate(updatedNetwork);
        updateProviderNetwork(updatedNetwork, prevNetworkName);
        return updatedNetwork;
    }

    private void updateProviderNetwork(Network network, String prevNetworkName) {
        final NetworkGuru guru = AdapterBase.getAdapterByName(_networkGurus, network.getGuruName());
        if (Objects.nonNull(guru) && !guru.update(network, prevNetworkName)) {
            logger.error("Failed to update name of network on provider");
        }
    }

    protected Pair<Integer, Integer> validateMtuOnUpdate(NetworkVO network, Long zoneId, Integer publicMtu, Integer privateMtu) {
        return networkMtuService.validateMtuOnUpdate(network, zoneId, publicMtu, privateMtu);
    }

    protected boolean updateMtuOnVr(Map<Long, Set<IpAddressTO>> routersToIpList) {
        return networkMtuService.updateMtuOnVr(routersToIpList);
    }
    private void updateNetworkIpv6(NetworkVO network, Long networkOfferingId) {
        boolean isIpv6Supported = _networkOfferingDao.isIpv6Supported(network.getNetworkOfferingId());
        boolean isIpv6SupportedNew = _networkOfferingDao.isIpv6Supported(networkOfferingId);
        if (isIpv6Supported && ! isIpv6SupportedNew) {
//            _ipv6AddressDao.unmark(network.getId(), network.getDomainId(), network.getAccountId());
            network.setIp6Gateway(null);
            network.setIp6Cidr(null);
            List<NicVO> nics = _nicDao.listByNetworkId(network.getId());
            for (NicVO nic : nics) {
                if (Nic.ReservationStrategy.PlaceHolder.equals(nic.getReservationStrategy())) {
                    continue;
                }
                nic.setIPv6Address(null);
                nic.setIPv6Cidr(null);
                nic.setIPv6Gateway(null);
                _nicDao.update(nic.getId(), nic);
            }
        } else if (!isIpv6Supported && isIpv6SupportedNew) {
            Pair<String, String> ip6GatewayCidr;
            try {
                ip6GatewayCidr = ipv6Service.preAllocateIpv6SubnetForNetwork(_dcDao.findById(network.getDataCenterId()));
                ipv6Service.assignIpv6SubnetToNetwork(ip6GatewayCidr.second(), network.getId());
            } catch (ResourceAllocationException ex) {
                throw new CloudRuntimeException("unable to allocate IPv6 network", ex);
            }
            String ip6Gateway = ip6GatewayCidr.first();
            String ip6Cidr = ip6GatewayCidr.second();
            network.setIp6Gateway(ip6Gateway);
            network.setIp6Cidr(ip6Cidr);
            Ipv6GuestPrefixSubnetNetworkMapVO map = ipv6GuestPrefixSubnetNetworkMapDao.findByNetworkId(network.getId());
            List<NicVO> nics = _nicDao.listByNetworkId(network.getId());
            for (NicVO nic : nics) {
                if (Nic.ReservationStrategy.PlaceHolder.equals(nic.getReservationStrategy())) {
                    continue;
                }
                IPv6Address iPv6Address = NetUtils.EUI64Address(map.getSubnet(), nic.getMacAddress());
                nic.setIPv6Address(iPv6Address.toString());
                nic.setIPv6Cidr(ip6Cidr);
                nic.setIPv6Gateway(ip6Gateway);
                _nicDao.update(nic.getId(), nic);
            }
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_NETWORK_MIGRATE, eventDescription = "migrating network", async = true)
    public Network migrateGuestNetwork(long networkId, long networkOfferingId, Account callerAccount, User callerUser, boolean resume) {
        return networkMigrationService.migrateGuestNetwork(networkId, networkOfferingId, callerAccount, callerUser, resume);
    }

    @Override
    public Vpc migrateVpcNetwork(long vpcId, long vpcOfferingId, Map<String, String> networkToOffering, Account account, User callerUser, boolean resume) {
        return networkMigrationService.migrateVpcNetwork(vpcId, vpcOfferingId, networkToOffering, account, callerUser, resume);
    }

    private void throwInvalidIdException(String message, String uuid, String description) {
        InvalidParameterValueException ex = new InvalidParameterValueException(message);
        ex.addProxyObject(uuid, description);
        throw ex;
    }

    private boolean canMoveToPhysicalNetwork(Network network, long oldNetworkOfferingId, long newNetworkOfferingId) {
        return networkMigrationService.canMoveToPhysicalNetwork(network, oldNetworkOfferingId, newNetworkOfferingId);
    }

    protected boolean canUpgrade(Network network, long oldNetworkOfferingId, long newNetworkOfferingId) {
        NetworkOffering oldNetworkOffering = _networkOfferingDao.findByIdIncludingRemoved(oldNetworkOfferingId);
        NetworkOffering newNetworkOffering = _networkOfferingDao.findById(newNetworkOfferingId);

        // security group service should be the same
        if (areServicesSupportedByNetworkOffering(oldNetworkOfferingId, Service.SecurityGroup) != areServicesSupportedByNetworkOffering(newNetworkOfferingId, Service.SecurityGroup)) {
            logger.debug("Offerings {} and {} have different securityGroupProperty, can't upgrade", newNetworkOffering, oldNetworkOffering);
            return false;
        }

        // tags should be the same
        if (newNetworkOffering.getTags() != null) {
            if (oldNetworkOffering.getTags() == null) {
                logger.debug("New network offering id={} has tags and old network offering id={} doesn't, can't upgrade", newNetworkOffering, oldNetworkOffering);
                return false;
            }

            if (!com.cloud.utils.StringUtils.areTagsEqual(oldNetworkOffering.getTags(), newNetworkOffering.getTags())) {
                logger.debug("Network offerings {} and {} have different tags, can't upgrade", newNetworkOffering, oldNetworkOffering);
                return false;
            }
        }

        // specify vlan should be the same
        if (oldNetworkOffering.isSpecifyVlan() != newNetworkOffering.isSpecifyVlan()) {
            logger.debug("Network offerings {} and {} have different values for specifyVlan, can't upgrade", newNetworkOffering, oldNetworkOffering);
            return false;
        }

        // network mode should be the same
        NetworkOffering.NetworkMode oldNetworkMode = oldNetworkOffering.getNetworkMode() == null ? NetworkOffering.NetworkMode.NATTED: oldNetworkOffering.getNetworkMode();
        NetworkOffering.NetworkMode newNetworkMode = newNetworkOffering.getNetworkMode() == null ? NetworkOffering.NetworkMode.NATTED: newNetworkOffering.getNetworkMode();
        if (!oldNetworkMode.equals(newNetworkMode)) {
            logger.debug("Network offerings {} and {} have different values for network mode, can't upgrade", newNetworkOffering, oldNetworkOffering);
            return false;
        }

        return canMoveToPhysicalNetwork(network, oldNetworkOfferingId, newNetworkOfferingId);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_PHYSICAL_NETWORK_CREATE, eventDescription = "Creating Physical Network", create = true)
    public PhysicalNetwork createPhysicalNetwork(final Long zoneId, final String vnetRange, final String networkSpeed, final List<String> isolationMethods, String broadcastDomainRangeStr,
            final Long domainId, final List<String> tags, final String name) {
        return physicalNetworkManagementService.createPhysicalNetwork(zoneId, vnetRange, networkSpeed, isolationMethods, broadcastDomainRangeStr, domainId, tags, name);
    }

    @Override
    public Pair<List<? extends PhysicalNetwork>, Integer> searchPhysicalNetworks(Long id, Long zoneId, String keyword, Long startIndex, Long pageSize, String name) {
        return physicalNetworkManagementService.searchPhysicalNetworks(id, zoneId, keyword, startIndex, pageSize, name);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_PHYSICAL_NETWORK_UPDATE, eventDescription = "updating physical network", async = true)
    public PhysicalNetwork updatePhysicalNetwork(Long id, String networkSpeed, List<String> tags, String newVnetRange, String state) {
        return physicalNetworkManagementService.updatePhysicalNetwork(id, networkSpeed, tags, newVnetRange, state);
    }

    @DB
    public void addOrRemoveVnets(String[] listOfRanges, final PhysicalNetworkVO network) {
        physicalNetworkManagementService.addOrRemoveVnets(listOfRanges, network);
    }

    public void validateIfServiceOfferingIsActiveAndSystemVmTypeIsDomainRouter(final Long serviceOfferingId) {
        final ServiceOfferingVO serviceOffering = serviceOfferingDao.findById(serviceOfferingId);

        logger.debug(String.format("Validating if service offering (%s) with id %d is active, and if system VM is of Domain Router type.", serviceOffering, serviceOfferingId));

        if (serviceOffering == null) {
            throw new InvalidParameterValueException(String.format("Could not find specified service offering [%s].", serviceOfferingId));
        }

        if (serviceOffering.getState() == ServiceOffering.State.Inactive) {
            throw new InvalidParameterValueException(String.format("The specified service offering [%s] is inactive.", serviceOffering));
        }

        final String virtualMachineDomainRouterType = VirtualMachine.Type.DomainRouter.toString();
        if (!virtualMachineDomainRouterType.equalsIgnoreCase(serviceOffering.getVmType())) {
            throw new InvalidParameterValueException(String.format("The specified service offering [%s] is of type [%s]. Virtual routers can only be created with service offering "
                    + "of type [%s].", serviceOffering, serviceOffering.getVmType(), virtualMachineDomainRouterType.toLowerCase()));
        }
    }


    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PHYSICAL_NETWORK_DELETE, eventDescription = "deleting physical network", async = true)
    @DB
    public boolean deletePhysicalNetwork(final Long physicalNetworkId) {
        return physicalNetworkManagementService.deletePhysicalNetwork(physicalNetworkId);
    }

    @Override
    public GuestVlanRange dedicateGuestVlanRange(DedicateGuestVlanRangeCmd cmd) {
        return dedicatedGuestVlanRangeService.dedicateGuestVlanRange(cmd);
    }

    @Override
    public Pair<List<? extends GuestVlanRange>, Integer> listDedicatedGuestVlanRanges(ListDedicatedGuestVlanRangesCmd cmd) {
        return dedicatedGuestVlanRangeService.listDedicatedGuestVlanRanges(cmd);
    }

    @Override
    public boolean releaseDedicatedGuestVlanRange(Long dedicatedGuestVlanRangeId) {
        return dedicatedGuestVlanRangeService.releaseDedicatedGuestVlanRange(dedicatedGuestVlanRangeId);
    }

    @Override
    public List<? extends Service> listNetworkServices(String providerName) {
        return physicalNetworkManagementService.listNetworkServices(providerName);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_PROVIDER_CREATE, eventDescription = "Creating Physical Network ServiceProvider", create = true)
    public PhysicalNetworkServiceProvider addProviderToPhysicalNetwork(Long physicalNetworkId, String providerName, Long destinationPhysicalNetworkId, List<String> enabledServices) {
        return physicalNetworkManagementService.addProviderToPhysicalNetwork(physicalNetworkId, providerName, destinationPhysicalNetworkId, enabledServices);
    }

    @Override
    public Pair<List<? extends PhysicalNetworkServiceProvider>, Integer> listNetworkServiceProviders(Long physicalNetworkId, String name, String state, Long startIndex, Long pageSize) {
        return physicalNetworkManagementService.listNetworkServiceProviders(physicalNetworkId, name, state, startIndex, pageSize);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_PROVIDER_UPDATE, eventDescription = "Updating physical network ServiceProvider", async = true)
    public PhysicalNetworkServiceProvider updateNetworkServiceProvider(Long id, String stateStr, List<String> enabledServices) {
        try {
            return physicalNetworkManagementService.updateNetworkServiceProvider(id, stateStr, enabledServices);
        } catch (ResourceUnavailableException | ConcurrentOperationException e) {
            throw new CloudRuntimeException("Error updating network service provider", e);
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_PROVIDER_DELETE, eventDescription = "Deleting physical network ServiceProvider", async = true)
    public boolean deleteNetworkServiceProvider(Long id) throws ConcurrentOperationException, ResourceUnavailableException {
        return physicalNetworkManagementService.deleteNetworkServiceProvider(id);
    }

    @Override
    public PhysicalNetwork getPhysicalNetwork(Long physicalNetworkId) {
        return physicalNetworkManagementService.getPhysicalNetwork(physicalNetworkId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_PHYSICAL_NETWORK_CREATE, eventDescription = "Creating Physical Network", async = true)
    public PhysicalNetwork getCreatedPhysicalNetwork(Long physicalNetworkId) {
        return physicalNetworkManagementService.getCreatedPhysicalNetwork(physicalNetworkId);
    }

    @Override
    public PhysicalNetworkServiceProvider getPhysicalNetworkServiceProvider(Long providerId) {
        return physicalNetworkManagementService.getPhysicalNetworkServiceProvider(providerId);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_SERVICE_PROVIDER_CREATE, eventDescription = "Creating Physical Network ServiceProvider", async = true)
    public PhysicalNetworkServiceProvider getCreatedPhysicalNetworkServiceProvider(Long providerId) {
        return physicalNetworkManagementService.getCreatedPhysicalNetworkServiceProvider(providerId);
    }

    @Override
    public long findPhysicalNetworkId(long zoneId, String tag, TrafficType trafficType) {
        return physicalNetworkManagementService.findPhysicalNetworkId(zoneId, tag, trafficType);
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_TRAFFIC_TYPE_CREATE, eventDescription = "Creating Physical Network TrafficType", create = true)
    public PhysicalNetworkTrafficType addTrafficTypeToPhysicalNetwork(Long physicalNetworkId, String trafficTypeStr, String isolationMethod, String xenLabel, String kvmLabel, String vmwareLabel,
            String simulatorLabel, String vlan, String hypervLabel) {
        return physicalNetworkManagementService.addTrafficTypeToPhysicalNetwork(physicalNetworkId, trafficTypeStr, isolationMethod, xenLabel, kvmLabel, vmwareLabel, simulatorLabel, vlan, hypervLabel);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TRAFFIC_TYPE_CREATE, eventDescription = "Creating Physical Network TrafficType", async = true)
    public PhysicalNetworkTrafficType getPhysicalNetworkTrafficType(Long id) {
        return physicalNetworkManagementService.getPhysicalNetworkTrafficType(id);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TRAFFIC_TYPE_UPDATE, eventDescription = "Updating physical network TrafficType", async = true)
    public PhysicalNetworkTrafficType updatePhysicalNetworkTrafficType(Long id, String xenLabel, String kvmLabel, String vmwareLabel, String hypervLabel) {
        return physicalNetworkManagementService.updatePhysicalNetworkTrafficType(id, xenLabel, kvmLabel, vmwareLabel, hypervLabel);
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_TRAFFIC_TYPE_DELETE, eventDescription = "Deleting physical network TrafficType", async = true)
    public boolean deletePhysicalNetworkTrafficType(Long id) {
        return physicalNetworkManagementService.deletePhysicalNetworkTrafficType(id);
    }

    @Override
    public Pair<List<? extends PhysicalNetworkTrafficType>, Integer> listTrafficTypes(Long physicalNetworkId) {
        return physicalNetworkManagementService.listTrafficTypes(physicalNetworkId);
    }

    @Override
    //TODO: duplicated in NetworkModel
    public NetworkVO getExclusiveGuestNetwork(long zoneId) {
        return physicalNetworkManagementService.getExclusiveGuestNetwork(zoneId);
    }

    protected PhysicalNetworkServiceProvider addDefaultVirtualRouterToPhysicalNetwork(long physicalNetworkId) {
        return physicalNetworkManagementService.addDefaultVirtualRouterToPhysicalNetwork(physicalNetworkId);
    }

    protected PhysicalNetworkServiceProvider addDefaultVpcVirtualRouterToPhysicalNetwork(long physicalNetworkId) {
        return physicalNetworkManagementService.addDefaultVpcVirtualRouterToPhysicalNetwork(physicalNetworkId);
    }

    protected PhysicalNetworkServiceProvider addDefaultInternalLbProviderToPhysicalNetwork(long physicalNetworkId) {
        return physicalNetworkManagementService.addDefaultInternalLbProviderToPhysicalNetwork(physicalNetworkId);
    }

    protected PhysicalNetworkServiceProvider addDefaultSecurityGroupProviderToPhysicalNetwork(long physicalNetworkId) {
        return physicalNetworkManagementService.addDefaultSecurityGroupProviderToPhysicalNetwork(physicalNetworkId);
    }

    protected boolean isNetworkSystem(Network network) {
        NetworkOffering no = _networkOfferingDao.findByIdIncludingRemoved(network.getNetworkOfferingId());
        if (no.isSystemOnly()) {
            return true;
        } else {
            return false;
        }
    }

    private boolean getAllowSubdomainAccessGlobal() {
        return _allowSubdomainNetworkAccess;
    }

    @Override
    public List<Pair<TrafficType, String>> listTrafficTypeImplementor(ListTrafficTypeImplementorsCmd cmd) {
        return physicalNetworkManagementService.listTrafficTypeImplementor(cmd);
    }

    @Override
    public IpAddress associateIPToNetwork(long ipId, long networkId)
            throws InsufficientAddressCapacityException, ResourceAllocationException, ResourceUnavailableException, ConcurrentOperationException {
        return ipAddressLifecycleService.associateIPToNetwork(ipId, networkId);
    }

    @Override
    @DB
    public Network createPrivateNetwork(final String networkName, final String displayText, long physicalNetworkId, String broadcastUriString, final String startIp, String endIp, final String gateway,
            String netmask, final long networkOwnerId, final Long vpcId, final Boolean sourceNat, final Long networkOfferingId, final Boolean bypassVlanOverlapCheck, final Long associatedNetworkId)
                    throws ResourceAllocationException, ConcurrentOperationException, InsufficientCapacityException {

        final Account caller = CallContext.current().getCallingAccount();
        final Account owner = _accountMgr.getAccount(networkOwnerId);

        // Get system network offering
        NetworkOfferingVO ntwkOff = null;
        if (networkOfferingId != null) {
            ntwkOff = _networkOfferingDao.findById(networkOfferingId);
        }
        if (ntwkOff == null) {
            ntwkOff = findSystemNetworkOffering(NetworkOffering.SystemPrivateGatewayNetworkOffering);
        }

        // Validate physical network
        final PhysicalNetwork pNtwk = _physicalNetworkDao.findById(physicalNetworkId);
        if (pNtwk == null) {
            throwInvalidIdException("Unable to find a physical network" + " having the given id", String.valueOf(physicalNetworkId), "physicalNetworkId");
        }

        // VALIDATE IP INFO
        // if end IP is not specified, default it to startIp
        if (!NetUtils.isValidIp4(startIp)) {
            throw new InvalidParameterValueException("Invalid format for the ip address parameter");
        }
        if (endIp == null) {
            endIp = startIp;
        } else if (!NetUtils.isValidIp4(endIp)) {
            throw new InvalidParameterValueException("Invalid format for the endIp address parameter");
        }

        if (!NetUtils.isValidIp4(gateway)) {
            throw new InvalidParameterValueException("Invalid gateway");
        }
        if (!NetUtils.isValidIp4Netmask(netmask)) {
            throw new InvalidParameterValueException("Invalid netmask");
        }

        final String cidr = NetUtils.ipAndNetMaskToCidr(gateway, netmask);

        final String uriString;
        if (broadcastUriString != null) {
            URI uri = BroadcastDomainType.fromString(broadcastUriString);
            uriString = uri.toString();
            BroadcastDomainType tiep = BroadcastDomainType.getSchemeValue(uri);
            // numeric vlan or vlan URI are ok for now
            // TODO make a test for any supported scheme
            if (!(tiep == BroadcastDomainType.Vlan || tiep == BroadcastDomainType.Lswitch)) {
                throw new InvalidParameterValueException("unsupported type of broadcastUri specified: " + broadcastUriString);
            }
        } else if (associatedNetworkId != null) {
            DataCenter zone = _dcDao.findById(pNtwk.getDataCenterId());
            Network associatedNetwork = implementAssociatedNetwork(associatedNetworkId, caller, owner, zone, null, owner.getAccountId(), cidr, startIp, endIp);
            uriString = associatedNetwork.getBroadcastUri().toString();
        } else {
            throw new InvalidParameterValueException("One of uri and associatedNetworkId must be passed");
        }

        final NetworkOfferingVO ntwkOffFinal = ntwkOff;
        try {
            return Transaction.execute(new TransactionCallbackWithException<Network, Exception>() {
                @Override
                public Network doInTransaction(TransactionStatus status) throws ResourceAllocationException, InsufficientCapacityException {
                    //lock datacenter as we need to get mac address seq from there
                    DataCenterVO dc = _dcDao.lockRow(pNtwk.getDataCenterId(), true);

                    //check if we need to create guest network
                    Network privateNetwork = _networksDao.getPrivateNetwork(uriString, cidr, networkOwnerId, pNtwk.getDataCenterId(), networkOfferingId, vpcId);
                    if (privateNetwork == null) {
                        //create Guest network
                        privateNetwork = _networkMgr.createPrivateNetwork(ntwkOffFinal.getId(), networkName, displayText, gateway, cidr, uriString, bypassVlanOverlapCheck, owner, pNtwk, vpcId);
                        if (privateNetwork != null) {
                            logger.debug("Successfully created guest network " + privateNetwork);
                            if (associatedNetworkId != null) {
                                _networkDetailsDao.persist(new NetworkDetailVO(privateNetwork.getId(), Network.AssociatedNetworkId, String.valueOf(associatedNetworkId), true));
                            }
                        } else {
                            throw new CloudRuntimeException("Creating guest network failed");
                        }
                    } else {
                        logger.debug("Private network already exists: " + privateNetwork);
                        //Do not allow multiple private gateways with same Vlan within a VPC
                        throw new InvalidParameterValueException(String.format("Private network for the vlan: %s and cidr  %s  already exists for Vpc %s in zone %s",
                                uriString, cidr, _vpcDao.findById(vpcId), _entityMgr.findById(DataCenter.class, pNtwk.getDataCenterId()).getName()));
                    }
                    if (vpcId != null) {
                        //add entry to private_ip_address table
                        PrivateIpVO privateIp = _privateIpDao.findByIpAndSourceNetworkIdAndVpcId(privateNetwork.getId(), startIp, vpcId);
                        if (privateIp != null) {
                            throw new InvalidParameterValueException(
                                    "Private ip address " + startIp + " already used for private gateway" + " in zone " + _entityMgr.findById(DataCenter.class, pNtwk.getDataCenterId()).getName());
                        }
                        Long mac = dc.getMacAddress();
                        Long nextMac = mac + 1;
                        dc.setMacAddress(nextMac);
                        privateIp = new PrivateIpVO(startIp, privateNetwork.getId(), nextMac, vpcId, sourceNat);
                        _privateIpDao.persist(privateIp);
                        _dcDao.update(dc.getId(), dc);
                    }

                    logger.debug("Private network " + privateNetwork + " is created");

                    return privateNetwork;
                }
            });
        } catch (Exception e) {
            ExceptionUtil.rethrowRuntime(e);
            ExceptionUtil.rethrow(e, ResourceAllocationException.class);
            ExceptionUtil.rethrow(e, InsufficientCapacityException.class);
            throw new IllegalStateException(e);
        }
    }

    private NetworkOfferingVO findSystemNetworkOffering(String offeringName) {
        List<NetworkOfferingVO> allOfferings = _networkOfferingDao.listSystemNetworkOfferings();
        for (NetworkOfferingVO offer : allOfferings) {
            if (offer.getName().equals(offeringName)) {
                return offer;
            }
        }
        return null;
    }

    @Override
    public Network getNetwork(String networkUuid) {
        return _networksDao.findByUuid(networkUuid);
    }

    @Override
    public List<? extends Nic> listNics(ListNicsCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Long nicId = cmd.getNicId();
        long vmId = cmd.getVmId();
        String keyword = cmd.getKeyword();
        Long networkId = cmd.getNetworkId();
        UserVmVO userVm = _userVmDao.findById(vmId);

        if (userVm == null || (!userVm.isDisplayVm() && caller.getType() == Account.Type.NORMAL)) {
            throwInvalidIdException("Virtual machine id does not exist", Long.valueOf(vmId).toString(), "vmId");
        }

        _accountMgr.checkAccess(caller, null, true, userVm);
        return _networkMgr.listVmNics(vmId, nicId, networkId, keyword);
    }

    @Override
    public List<? extends NicSecondaryIp> listVmNicSecondaryIps(ListNicsCmd cmd) {
        Account caller = CallContext.current().getCallingAccount();
        Long nicId = cmd.getNicId();
        long vmId = cmd.getVmId();
        String keyword = cmd.getKeyword();
        UserVmVO userVm = _userVmDao.findById(vmId);

        if (userVm == null || (!userVm.isDisplayVm() && caller.getType() == Account.Type.NORMAL)) {
            throwInvalidIdException("Virtual machine id does not exist", Long.valueOf(vmId).toString(), "vmId");
        }

        _accountMgr.checkAccess(caller, null, true, userVm);
        return _nicSecondaryIpDao.listSecondaryIpUsingKeyword(nicId, keyword);
    }

    public List<NetworkGuru> getNetworkGurus() {
        return _networkGurus;
    }

    @Inject
    public void setNetworkGurus(List<NetworkGuru> networkGurus) {
        _networkGurus = networkGurus;
    }

    public void setInternalLoadBalancerElementServices(List<InternalLoadBalancerElementService> services) {
        this.internalLoadBalancerElementServices = services;
    }

    @Override
    public IpAddress updateIP(Long id, String customId, Boolean displayIp) {
        return ipAddressLifecycleService.updateIP(id, customId, displayIp);
    }

    @Override
    public AcquirePodIpCmdResponse allocatePodIp(Account ipOwner, String zoneId, String podId) throws ResourceAllocationException {
        return ipAddressLifecycleService.allocatePodIp(ipOwner, zoneId, podId);
    }

    @Override
    public boolean releasePodIp(ReleasePodIpCmdByAdmin ip) throws CloudRuntimeException {
        return ipAddressLifecycleService.releasePodIp(ip);
    }

    @Override
    public Pair<List<? extends GuestVlan>, Integer> listGuestVlans(ListGuestVlansCmd cmd) {
        return physicalNetworkManagementService.listGuestVlans(cmd);
    }

    @Override
    public List<? extends NetworkPermission> listNetworkPermissions(ListNetworkPermissionsCmd cmd) {
        return networkPermissionService.listNetworkPermissions(cmd);
    }

    @Override
    public boolean createNetworkPermissions(CreateNetworkPermissionsCmd cmd) {
        return networkPermissionService.createNetworkPermissions(cmd);
    }

    @Override
    public boolean removeNetworkPermissions(RemoveNetworkPermissionsCmd cmd) {
        return networkPermissionService.removeNetworkPermissions(cmd);
    }

    @Override
    public boolean resetNetworkPermissions(ResetNetworkPermissionsCmd cmd) {
        return networkPermissionService.resetNetworkPermissions(cmd);
    }

    private void checkIpRangeOverlapWithAssociatedNetworks(Long associatedNetworkId, String startIp, String endIp) {
        List<NetworkDetailVO> associatedNetworks = _networkDetailsDao.findDetails(Network.AssociatedNetworkId, String.valueOf(associatedNetworkId), null);
        for (NetworkDetailVO networkDetailVO : associatedNetworks) {
            NetworkVO associatedNetwork2 = _networksDao.findById(networkDetailVO.getResourceId());
            if (associatedNetwork2 != null) {
                List<VlanVO> vlans = _vlanDao.listVlansByNetworkId(associatedNetwork2.getId());
                if (vlans.isEmpty()) {
                    VpcGatewayVO vpcGateway = vpcGatewayDao.getVpcGatewayByNetworkId(associatedNetwork2.getId());
                    if (vpcGateway != null && NetUtils.ipRangesOverlap(startIp, endIp, vpcGateway.getIp4Address(), vpcGateway.getIp4Address())) {
                        throw new InvalidParameterValueException(String.format("The startIp/endIp (%s - %s) overlaps with vpc private gateway %s (%s): ",
                                startIp, endIp, associatedNetwork2.getName(), vpcGateway.getIp4Address()));
                    }
                    continue;
                }
                String startIP2 = vlans.get(0).getIpRange().split("-")[0];
                String endIP2 = vlans.get(0).getIpRange().split("-")[1];
                if (StringUtils.isNoneBlank(startIp, startIP2) && NetUtils.ipRangesOverlap(startIp, endIp, startIP2, endIP2)) {
                    throw new InvalidParameterValueException(String.format("The startIp/endIp (%s - %s) overlaps with network %s (%s - %s)",
                            startIp, endIp, associatedNetwork2.getName(), startIP2, endIP2));
                }
            }
        }
    }

    @Override
    public String getConfigComponentName() {
        return NetworkService.class.getSimpleName();
    }

    @Override
    public ConfigKey<?>[] getConfigKeys() {
        return new ConfigKey<?>[] {AllowDuplicateNetworkName, AllowEmptyStartEndIpAddress, AllowUsersToMakeNetworksRedundant, VRPrivateInterfaceMtu, VRPublicInterfaceMtu, AllowUsersToSpecifyVRMtu};
    }

    public boolean isDefaultAcl(Long aclId) {
        return aclId == NetworkACL.DEFAULT_DENY || aclId == NetworkACL.DEFAULT_ALLOW;
    }

    public boolean isAclAttachedToVpc(Long aclVpcId, Long vpcId) {
        return aclVpcId != 0 && !vpcId.equals(aclVpcId);
    }

    @Override
    public PublicIpQuarantine updatePublicIpAddressInQuarantine(UpdateQuarantinedIpCmd cmd) throws CloudRuntimeException {
        return publicIpQuarantineManager.updatePublicIpAddressInQuarantine(cmd);
    }

    @Override
    public void removePublicIpAddressFromQuarantine(RemoveQuarantinedIpCmd cmd) throws CloudRuntimeException {
        publicIpQuarantineManager.removePublicIpAddressFromQuarantine(cmd);
    }

    @Override
    public InternalLoadBalancerElementService getInternalLoadBalancerElementByType(Type type) {
        return internalLoadBalancerElementServiceMap.getOrDefault(type.name(), null);
    }

    @Override
    public InternalLoadBalancerElementService getInternalLoadBalancerElementByNetworkServiceProviderId(long networkProviderId) {
        PhysicalNetworkServiceProviderVO provider = _pNSPDao.findById(networkProviderId);
        if (provider == null) {
            String msg = String.format("Cannot find a network service provider with ID %s", networkProviderId);
            logger.error(msg);
            throw new CloudRuntimeException(msg);
        }
        Type type = provider.getProviderName().equalsIgnoreCase("nsx") ? Type.Nsx : Type.InternalLbVm;
        return getInternalLoadBalancerElementByType(type);
    }

    @Override
    public InternalLoadBalancerElementService getInternalLoadBalancerElementById(long providerId) {
        VirtualRouterProviderVO provider = virtualRouterProviderDao.findById(providerId);
        return getInternalLoadBalancerElementByType(provider.getType());
    }

    @Override
    public List<InternalLoadBalancerElementService> getInternalLoadBalancerElements() {
        return new ArrayList<>(this.internalLoadBalancerElementServiceMap.values());
    }

    @Override
    public boolean handleCksIsoOnNetworkVirtualRouter(Long virtualRouterId, boolean mount) throws ResourceUnavailableException {
        DomainRouterVO router = routerDao.findById(virtualRouterId);
        if (router == null) {
            String err = String.format("Cannot find VR with ID %s", virtualRouterId);
            logger.error(err);
            throw new CloudRuntimeException(err);
        }
        Commands commands = new Commands(Command.OnError.Stop);
        commandSetupHelper.createHandleCksIsoCommand(router, mount, commands);
        if (!networkHelper.sendCommandsToRouter(router, commands)) {
            throw new CloudRuntimeException(String.format("Unable to send commands to virtual router: %s", router.getHostId()));
        }
        Answer answer = commands.getAnswer("handleCksIso");
        if (answer == null || !answer.getResult()) {
            logger.error(String.format("Could not handle the CKS ISO properly: %s", answer.getDetails()));
            return false;
        }
        return true;
    }


    @Override
    public String getNicVlanValueForExternalVm(NicTO nic) {
        Networks.BroadcastDomainType broadcastDomainType = Networks.BroadcastDomainType.getSchemeValue(nic.getBroadcastUri());
        if (Networks.BroadcastDomainType.NSX.equals(broadcastDomainType)) {
            NetworkVO networkVO = _networksDao.findById(nic.getNetworkId());
            try {
                NsxService nsxService = ComponentContext.getDelegateComponentOfType(NsxService.class);
                return nsxService.getSegmentId(networkVO.getDomainId(), networkVO.getDataCenterId(),
                        networkVO.getAccountId(), networkVO.getVpcId(), networkVO.getId());
            } catch (NoSuchBeanDefinitionException e) {
                logger.error("NSX service is not available, unable to retrieve segment ID for NIC: {}", nic, e);
                throw new CloudRuntimeException(
                        String.format("Unable to retrieve segment ID for NIC with NSX broadcast domain: %s", nic));
            }
        }
        return Networks.BroadcastDomainType.getValue(nic.getBroadcastUri());
    }

    @Override
    public Long getPreferredNetworkIdForPublicIpRuleAssignment(IpAddress ip, Long networkId) {
        return _ipAddrMgr.getPreferredNetworkIdForPublicIpRuleAssignment(ip, networkId);
    }

    @Override
    public Network.IpAddresses getIpAddressesFromIps(String ipAddress, String ip6Address, String macAddress) {
        if (ip6Address != null) {
            ip6Address = NetUtils.standardizeIp6Address(ip6Address);
        }
        if (macAddress != null) {
            if (!NetUtils.isValidMac(macAddress)) {
                throw new InvalidParameterValueException("Mac address is not valid: " + macAddress);
            } else if (!NetUtils.isUnicastMac(macAddress)) {
                throw new InvalidParameterValueException("Mac address is not unicast: " + macAddress);
            }
            macAddress = NetUtils.standardizeMacAddress(macAddress);
        }
        return new Network.IpAddresses(ipAddress, ip6Address, macAddress);
    }
}
