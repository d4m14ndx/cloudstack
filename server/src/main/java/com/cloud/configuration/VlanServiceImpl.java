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
package com.cloud.configuration;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Vector;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.SecurityChecker;
import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.api.command.admin.vlan.CreateVlanIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.DedicatePublicIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.DeleteVlanIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.ReleasePublicIpRangeCmd;
import org.apache.cloudstack.api.command.admin.vlan.UpdateVlanIpRangeCmd;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.framework.messagebus.MessageBus;
import org.apache.cloudstack.framework.messagebus.PublishScope;
import org.apache.cloudstack.region.PortableIpRangeDao;
import org.apache.cloudstack.region.PortableIpRangeVO;
import org.apache.cloudstack.region.dao.RegionDao;
import org.apache.cloudstack.reservation.dao.ReservationDao;
import org.apache.commons.collections.CollectionUtils;
import org.apache.commons.collections.MapUtils;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.configuration.Resource.ResourceType;
import com.cloud.dc.AccountVlanMapVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.DomainVlanMapVO;
import com.cloud.dc.Pod;
import com.cloud.dc.PodVlanMapVO;
import com.cloud.dc.Vlan;
import com.cloud.dc.Vlan.VlanType;
import com.cloud.dc.VlanDetailsVO;
import com.cloud.dc.VlanVO;
import com.cloud.dc.dao.AccountVlanMapDao;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.dc.dao.DataCenterIpAddressDao;
import com.cloud.dc.dao.DomainVlanMapDao;
import com.cloud.dc.dao.HostPodDao;
import com.cloud.dc.dao.PodVlanMapDao;
import com.cloud.dc.dao.VlanDao;
import com.cloud.dc.dao.VlanDetailsDao;
import com.cloud.domain.Domain;
import com.cloud.domain.dao.DomainDao;
import com.cloud.event.ActionEvent;
import com.cloud.event.EventTypes;
import com.cloud.event.UsageEventUtils;
import com.cloud.exception.ConcurrentOperationException;
import com.cloud.exception.InsufficientCapacityException;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.exception.PermissionDeniedException;
import com.cloud.exception.ResourceAllocationException;
import com.cloud.exception.ResourceUnavailableException;
import com.cloud.network.IpAddress;
import com.cloud.network.IpAddressManager;
import com.cloud.network.Ipv6Service;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.UserIpv6AddressVO;
import com.cloud.network.dao.FirewallRulesDao;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetrisProviderDao;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.dao.UserIpv6AddressDao;
import com.cloud.network.element.NetrisProviderVO;
import com.cloud.network.element.NsxProviderVO;
import com.cloud.network.netris.NetrisService;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.offerings.dao.NetworkOfferingServiceMapDao;
import com.cloud.org.Grouping;
import com.cloud.projects.Project;
import com.cloud.test.IPRangeConfig;
import com.cloud.projects.ProjectManager;
import com.cloud.resourcelimit.CheckedReservation;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.ResourceLimitService;
import com.cloud.user.User;
import com.cloud.user.dao.AccountDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.DB;
import com.cloud.utils.db.GlobalLock;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.Transaction;
import com.cloud.utils.db.TransactionCallback;
import com.cloud.utils.db.TransactionCallbackNoReturn;
import com.cloud.utils.db.TransactionLegacy;
import com.cloud.utils.db.TransactionStatus;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.NicIpAlias;
import com.cloud.vm.dao.NicIpAliasDao;
import com.cloud.vm.dao.NicIpAliasVO;

import com.google.common.base.MoreObjects;
import com.googlecode.ipv6.IPv6Address;
import com.googlecode.ipv6.IPv6Network;

import com.cloud.utils.UriUtils;

/**
 * Default {@link VlanService} implementation — VLAN and public IP-range
 * management. Extracted from {@link ConfigurationManagerImpl} as slice 7
 * of the Phase 4 Spring-component decomposition.
 *
 * <p>Mirrors the sibling-slice convention: no back-reference to the
 * manager; small helpers ({@code checkIpRange},
 * {@code checkOverlapPrivateIpRange}, {@code checkOverlapPortableIpRange},
 * {@code getCidrAddress}, {@code getCidrSize}, {@code getZoneName},
 * {@code getPodName}, {@code checkZoneAccess}) are duplicated locally.
 */
@Component
public class VlanServiceImpl implements VlanService {

    protected static final Logger logger = LogManager.getLogger(VlanServiceImpl.class);

    private static final String SENDER = VlanServiceImpl.class.getSimpleName();

    @Inject VlanDao _vlanDao;
    @Inject VlanDetailsDao vlanDetailsDao;
    @Inject IPAddressDao _publicIpAddressDao;
    @Inject DataCenterIpAddressDao _privateIpAddressDao;
    @Inject AccountVlanMapDao _accountVlanMapDao;
    @Inject DomainVlanMapDao _domainVlanMapDao;
    @Inject PodVlanMapDao podVlanMapDao;
    @Inject NetworkDao _networkDao;
    @Inject DataCenterDao _zoneDao;
    @Inject AccountDao _accountDao;
    @Inject HostPodDao _podDao;
    @Inject DomainDao _domainDao;
    @Inject PortableIpRangeDao _portableIpRangeDao;
    @Inject NicIpAliasDao _nicIpAliasDao;
    @Inject UserIpv6AddressDao _ipv6Dao;
    @Inject ReservationDao reservationDao;
    @Inject PhysicalNetworkDao _physicalNetworkDao;
    @Inject NetworkOfferingDao _networkOfferingDao;
    @Inject NetworkOfferingServiceMapDao _ntwkOffServiceMapDao;
    @Inject FirewallRulesDao _firewallDao;
    @Inject NsxProviderDao nsxProviderDao;
    @Inject NetrisProviderDao netrisProviderDao;
    @Inject AccountManager _accountMgr;
    @Inject ProjectManager _projectMgr;
    @Inject NetworkService _networkSvc;
    @Inject NetworkModel _networkModel;
    @Inject IpAddressManager _ipAddrMgr;
    @Inject ResourceLimitService _resourceLimitMgr;
    @Inject Ipv6Service ipv6Service;
    @Inject MessageBus messageBus;
    @Inject RegionDao _regionDao;
    @Inject
    jakarta.inject.Provider<NetrisService> netrisServiceProvider;
    @Inject
    protected org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService _networkMgr;

    protected List<SecurityChecker> _secChecker;

    public List<SecurityChecker> getSecChecker() { return _secChecker; }
    public void setSecChecker(List<SecurityChecker> checkers) { _secChecker = checkers; }

    @Override
    public Vlan createVlanAndPublicIpRange(final CreateVlanIpRangeCmd cmd) throws InsufficientCapacityException, ConcurrentOperationException, ResourceUnavailableException,
    ResourceAllocationException {
        Long zoneId = cmd.getZoneId();
        final Long podId = cmd.getPodId();
        final String startIP = cmd.getStartIp();
        String endIP = cmd.getEndIp();
        final String newVlanGateway = cmd.getGateway();
        final String newVlanNetmask = cmd.getNetmask();
        Long networkId = cmd.getNetworkID();
        Long physicalNetworkId = cmd.getPhysicalNetworkId();

        // Verify that network exists
        Network network = getNetwork(networkId);
        if (network != null) {
            zoneId = network.getDataCenterId();
            physicalNetworkId = network.getPhysicalNetworkId();
        }

        String vlanId = cmd.getVlan();
        vlanId = verifyAndUpdateVlanId(vlanId, network);

        // TODO decide if we should be forgiving or demand a valid and complete URI
        if (!(vlanId == null || "".equals(vlanId) || vlanId.startsWith(BroadcastDomainType.Vlan.scheme()))) {
            vlanId = BroadcastDomainType.Vlan.toUri(vlanId).toString();
        }
        final Boolean forVirtualNetwork = cmd.isForVirtualNetwork();
        final String accountName = cmd.getAccountName();
        final Long projectId = cmd.getProjectId();
        final Long domainId = cmd.getDomainId();
        final String startIPv6 = cmd.getStartIpv6();
        String endIPv6 = cmd.getEndIpv6();
        final String ip6Gateway = cmd.getIp6Gateway();
        final String ip6Cidr = cmd.getIp6Cidr();
        final Boolean forSystemVms = cmd.isForSystemVms();

        Account vlanOwner = null;

        if (forSystemVms && accountName != null) {
            throw new InvalidParameterValueException("Account name should not be provided when ForSystemVMs is enabled");
        }

        final boolean ipv4 = startIP != null;
        final boolean ipv6 = ip6Cidr != null;

        if (!ipv4 && !ipv6) {
            throw new InvalidParameterValueException("StartIP or IPv6 CIDR is missing in the parameters!");
        }

        if (ipv4) {
            // if end ip is not specified, default it to startIp
            if (endIP == null && startIP != null) {
                endIP = startIP;
            }
        }

        if (ipv6) {
            // if end ip is not specified, default it to startIp
            if (endIPv6 == null && startIPv6 != null) {
                endIPv6 = startIPv6;
            }

            IPv6Network iPv6Network = IPv6Network.fromString(ip6Cidr);
            if (iPv6Network.getNetmask().asPrefixLength() > Ipv6Service.IPV6_SLAAC_CIDR_NETMASK) {
                throw new InvalidParameterValueException(String.format("For IPv6 range, prefix must be /%d or less", Ipv6Service.IPV6_SLAAC_CIDR_NETMASK));
            }
        }

        if (projectId != null) {
            if (accountName != null) {
                throw new InvalidParameterValueException("Account and projectId are mutually exclusive");
            }
            final Project project = _projectMgr.getProject(projectId);
            if (project == null) {
                throw new InvalidParameterValueException("Unable to find project by id " + projectId);
            }

            vlanOwner = _accountMgr.getAccount(project.getProjectAccountId());
            if (vlanOwner == null) {
                throw new InvalidParameterValueException("Please specify a valid projectId");
            }
        }

        Domain domain = null;
        if (accountName != null && domainId != null) {
            vlanOwner = _accountDao.findActiveAccount(accountName, domainId);
            if (vlanOwner == null) {
                throw new InvalidParameterValueException("Please specify a valid account.");
            } else if (vlanOwner.getId() == Account.ACCOUNT_ID_SYSTEM) {
                vlanOwner = null;
            }
        } else if (domainId != null) {
            domain = _domainDao.findById(domainId);
            if (domain == null) {
                throw new InvalidParameterValueException("Please specify a valid domain id");
            }
        }

        // Verify that zone exists
        final DataCenterVO zone = _zoneDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Unable to find zone by id " + zoneId);
        }

        // If external provider is provided, verify zone has that provider enabled and the controller added
        Provider provider = cmd.getProvider();
        NsxProviderVO nsxProvider = nsxProviderDao.findByZoneId(zoneId);
        NetrisProviderVO netrisProvider = netrisProviderDao.findByZoneId(zoneId);
        if (Objects.nonNull(provider) && ObjectUtils.anyNotNull(nsxProvider, netrisProvider)) {
            boolean unsupported =
                    (Provider.Nsx == provider && nsxProvider == null) ||
                            (Provider.Netris == provider && netrisProvider == null);
            if (unsupported) {
                throw new InvalidParameterValueException(String.format("Cannot add public IP range as the zone does not support provider: %s", provider.getName()));
            }
        }

        // verify that physical network exists
        PhysicalNetworkVO pNtwk = null;
        if (physicalNetworkId != null) {
            pNtwk = _physicalNetworkDao.findById(physicalNetworkId);
            if (pNtwk == null) {
                throw new InvalidParameterValueException("Unable to find Physical Network with id=" + physicalNetworkId);
            }
            if (zoneId == null) {
                zoneId = pNtwk.getDataCenterId();
            }
        } else {
            if (zoneId == null) {
                throw new InvalidParameterValueException("");
            }
            if (network != null && network.getPhysicalNetworkId() != null) {
                physicalNetworkId = network.getPhysicalNetworkId();
            } else {
                if (forVirtualNetwork) {
                    physicalNetworkId = _networkModel.getDefaultPhysicalNetworkByZoneAndTrafficType(zoneId, TrafficType.Public).getId();
                } else {
                    if (zone.getNetworkType() == DataCenter.NetworkType.Basic) {
                        physicalNetworkId = _networkModel.getDefaultPhysicalNetworkByZoneAndTrafficType(zoneId, TrafficType.Guest).getId();
                    } else if (zone.getNetworkType() == DataCenter.NetworkType.Advanced) {
                        if (zone.isSecurityGroupEnabled()) {
                            physicalNetworkId = _networkModel.getDefaultPhysicalNetworkByZoneAndTrafficType(zoneId, TrafficType.Guest).getId();
                        } else {
                            throw new InvalidParameterValueException("Physical Network Id is null, please provide the Network id for Direct vlan creation ");
                        }
                    }
                }
            }
        }

        // Check if zone is enabled
        final Account caller = CallContext.current().getCallingAccount();
        if (Grouping.AllocationState.Disabled == zone.getAllocationState()
                && !_accountMgr.isRootAdmin(caller.getId())) {
            throw new PermissionDeniedException(String.format("Cannot perform this operation, Zone is currently disabled: %s", zone));
        }

        if (zone.isSecurityGroupEnabled() && zone.getNetworkType() != DataCenter.NetworkType.Basic && forVirtualNetwork) {
            throw new InvalidParameterValueException("Can't add virtual IP range into a zone with security group enabled");
        }

        if (forVirtualNetwork) {
            if (network == null) {
                networkId = _networkModel.getSystemNetworkByZoneAndTrafficType(zoneId, TrafficType.Public).getId();
                network = _networkModel.getNetwork(networkId);
            } else if (network.getGuestType() != null || network.getTrafficType() != TrafficType.Public) {
                throw new InvalidParameterValueException(String.format("Can't find Public network %s", network));
            }
        } else {
            if (network == null) {
                if (zone.getNetworkType() == DataCenter.NetworkType.Basic) {
                    networkId = _networkModel.getExclusiveGuestNetwork(zoneId).getId();
                    network = _networkModel.getNetwork(networkId);
                } else {
                    network = _networkModel.getNetworkWithSecurityGroupEnabled(zoneId);
                    if (network == null) {
                        throw new InvalidParameterValueException("Network id is required for Direct vlan creation ");
                    }
                    networkId = network.getId();
                    zoneId = network.getDataCenterId();
                }
            } else if (network.getGuestType() == null ||
                    network.getGuestType() == Network.GuestType.Isolated
                    && _ntwkOffServiceMapDao.areServicesSupportedByNetworkOffering(network.getNetworkOfferingId(), Service.SourceNat)) {
                throw new InvalidParameterValueException(String.format("Can't create direct vlan for network %s with type: %s", network, network.getGuestType()));
            }
        }

        Pair<Boolean, Pair<String, String>> sameSubnet = null;
        if (!network.getSpecifyIpRanges()) {
            throw new InvalidParameterValueException("Network " + network + " doesn't support adding IP ranges");
        }

        if (zone.getNetworkType() == DataCenter.NetworkType.Advanced) {
            if (network.getTrafficType() == TrafficType.Guest) {
                if (network.getGuestType() != GuestType.Shared) {
                    throw new InvalidParameterValueException(String.format("Can execute createVLANIpRanges on shared guest Network, but type of this guest Network %s is %s", network, network.getGuestType()));
                }

                final List<VlanVO> vlans = _vlanDao.listVlansByNetworkId(network.getId());
                if (vlans != null && vlans.size() > 0) {
                    final VlanVO vlan = vlans.get(0);
                    if (vlanId == null || vlanId.contains(Vlan.UNTAGGED)) {
                        vlanId = vlan.getVlanTag();
                    } else if (!NetUtils.isSameIsolationId(vlan.getVlanTag(), vlanId)) {
                        throw new InvalidParameterValueException(String.format("there is already one vlan %s on network :%s, only one vlan is allowed on guest network", vlan.getVlanTag(), network));
                    }
                }
                sameSubnet = validateIpRange(startIP, endIP, newVlanGateway, newVlanNetmask, vlans, ipv4, ipv6, ip6Gateway, ip6Cidr, startIPv6, endIPv6, network);

            }

        } else if (network.getTrafficType() == TrafficType.Management) {
            throw new InvalidParameterValueException("Cannot execute createVLANIpRanges on management network");
        } else if (zone.getNetworkType() == NetworkType.Basic) {
            final List<VlanVO> vlans = _vlanDao.listVlansByNetworkId(network.getId());
            sameSubnet = validateIpRange(startIP, endIP, newVlanGateway, newVlanNetmask, vlans, ipv4, ipv6, ip6Gateway, ip6Cidr, startIPv6, endIPv6, network);
        }

        if (zoneId == null || ipv6 && (ip6Gateway == null || ip6Cidr == null)) {
            throw new InvalidParameterValueException("Gateway, netmask and zoneId have to be passed in for virtual and direct untagged networks");
        }

        if (ipv4) {
            checkOverlapPrivateIpRange(zoneId, startIP, endIP);
        }

        long reservedIpAddressesAmount = 0L;
        if (forVirtualNetwork && vlanOwner != null) {
            reservedIpAddressesAmount = NetUtils.ip2Long(endIP) - NetUtils.ip2Long(startIP) + 1;
        }

        try (CheckedReservation publicIpReservation = new CheckedReservation(vlanOwner, ResourceType.public_ip, null, null, null, reservedIpAddressesAmount, null, reservationDao, _resourceLimitMgr)) {
            return commitVlan(zoneId, podId, startIP, endIP, newVlanGateway, newVlanNetmask, vlanId, forVirtualNetwork, forSystemVms, networkId, physicalNetworkId, startIPv6, endIPv6, ip6Gateway,
                    ip6Cidr, domain, vlanOwner, network, sameSubnet, cmd.getProvider());
        }
    }

    private Network getNetwork(Long networkId) {
        if (networkId == null) {
            return null;
        }

        Network network = _networkDao.findById(networkId);
        if (network == null) {
            throw new InvalidParameterValueException("Unable to find network by id " + networkId);
        }

        return network;
    }

    private String verifyAndUpdateVlanId(String vlanId, Network network) {
        if (!StringUtils.isBlank(vlanId)) {
            return vlanId;
        }

        if (network == null || network.getTrafficType() != TrafficType.Guest) {
            return Vlan.UNTAGGED;
        }

        boolean connectivityWithoutVlan = isConnectivityWithoutVlan(network);
        return getNetworkVlanId(network, connectivityWithoutVlan);
    }

    private Vlan commitVlan(final Long zoneId, final Long podId, final String startIP, final String endIP, final String newVlanGatewayFinal, final String newVlanNetmaskFinal,
            final String vlanId, final Boolean forVirtualNetwork, final Boolean forSystemVms, final Long networkId, final Long physicalNetworkId, final String startIPv6, final String endIPv6,
            final String ip6Gateway, final String ip6Cidr, final Domain domain, final Account vlanOwner, final Network network, final Pair<Boolean, Pair<String, String>> sameSubnet, Provider provider) {
        final GlobalLock commitVlanLock = GlobalLock.getInternLock("CommitVlan");
        commitVlanLock.lock(5);
        logger.debug("Acquiring lock for committing vlan");
        try {
            Vlan vlan = Transaction.execute(new TransactionCallback<>() {
                @Override
                public Vlan doInTransaction(final TransactionStatus status) {
                    String newVlanNetmask = newVlanNetmaskFinal;
                    String newVlanGateway = newVlanGatewayFinal;

                    if ((sameSubnet == null || !sameSubnet.first()) && network.getTrafficType() == TrafficType.Guest && network.getGuestType() == GuestType.Shared
                            && _vlanDao.listVlansByNetworkId(networkId) != null) {
                        final Map<Capability, String> dhcpCapabilities = _networkSvc.getNetworkOfferingServiceCapabilities(_networkOfferingDao.findById(network.getNetworkOfferingId()),
                                Service.Dhcp);
                        final String supportsMultipleSubnets = dhcpCapabilities.get(Capability.DhcpAccrossMultipleSubnets);
                        if (supportsMultipleSubnets == null || !Boolean.valueOf(supportsMultipleSubnets)) {
                            throw new InvalidParameterValueException("The dhcp service provider for this network does not support dhcp across multiple subnets");
                        }
                        logger.info("adding a new subnet to the network {}", network);
                    } else if (sameSubnet != null) {
                        newVlanGateway = sameSubnet.second().first();
                        newVlanNetmask = sameSubnet.second().second();
                    }
                    final Vlan vlan = createVlanAndPublicIpRange(zoneId, networkId, physicalNetworkId, forVirtualNetwork, forSystemVms, podId, startIP, endIP, newVlanGateway, newVlanNetmask, vlanId,
                            false, domain, vlanOwner, startIPv6, endIPv6, ip6Gateway, ip6Cidr, provider);
                    return vlan;
                }
            });

            if (provider == Provider.Netris && netrisProviderDao.findByZoneId(zoneId) != null) {
                if (Objects.nonNull(netrisServiceProvider) && Objects.nonNull(netrisServiceProvider.get())) {
                    NetrisService netrisService = netrisServiceProvider.get();
                    netrisService.createIPAMAllocationsForZoneLevelPublicRanges(zoneId);
                }
            }
            messageBus.publish(SENDER, com.cloud.configuration.ConfigurationManager.MESSAGE_CREATE_VLAN_IP_RANGE_EVENT, PublishScope.LOCAL, vlan);

            return vlan;
        } finally {
            commitVlanLock.unlock();
        }
    }

    @Override
    public NetUtils.SupersetOrSubset checkIfSubsetOrSuperset(String vlanGateway, String vlanNetmask, String newVlanGateway, String newVlanNetmask, final String newStartIP, final String newEndIP) {
        if (newVlanGateway == null && newVlanNetmask == null) {
            newVlanGateway = vlanGateway;
            newVlanNetmask = vlanNetmask;
            if (NetUtils.sameSubnet(newStartIP, newVlanGateway, newVlanNetmask)) {
                if (NetUtils.sameSubnet(newEndIP, newVlanGateway, newVlanNetmask)) {
                    return NetUtils.SupersetOrSubset.sameSubnet;
                }
            }
            return NetUtils.SupersetOrSubset.neitherSubetNorSuperset;
        } else if (newVlanGateway == null || newVlanNetmask == null) {
            throw new InvalidParameterValueException(
                    "either both netmask and gateway should be passed or both should me omited.");
        } else {
            if (!NetUtils.sameSubnet(newStartIP, newVlanGateway, newVlanNetmask)) {
                throw new InvalidParameterValueException("The start IP and gateway do not belong to the same subnet");
            }
            if (!NetUtils.sameSubnet(newEndIP, newVlanGateway, newVlanNetmask)) {
                throw new InvalidParameterValueException("The end IP and gateway do not belong to the same subnet");
            }
        }
        final String cidrnew = NetUtils.getCidrFromGatewayAndNetmask(newVlanGateway, newVlanNetmask);
        final String existing_cidr = NetUtils.getCidrFromGatewayAndNetmask(vlanGateway, vlanNetmask);

        return NetUtils.isNetworkASubsetOrSupersetOfNetworkB(cidrnew, existing_cidr);
    }

    @Override
    public Pair<Boolean, Pair<String, String>> validateIpRange(final String startIP, final String endIP, final String newVlanGateway, final String newVlanNetmask, final List<VlanVO> vlans, final boolean ipv4,
            final boolean ipv6, String ip6Gateway, String ip6Cidr, final String startIPv6, final String endIPv6, final Network network) {
        String vlanGateway = null;
        String vlanNetmask = null;
        boolean sameSubnet = false;
        if (CollectionUtils.isNotEmpty(vlans)) {
            for (final VlanVO vlan : vlans) {
                vlanGateway = vlan.getVlanGateway();
                vlanNetmask = vlan.getVlanNetmask();
                sameSubnet = hasSameSubnet(ipv4, vlanGateway, vlanNetmask, newVlanGateway, newVlanNetmask, startIP, endIP,
                        ipv6, ip6Gateway, ip6Cidr, startIPv6, endIPv6, network);
                if (sameSubnet) break;
            }
        } else if(network.getGateway() != null && network.getCidr() != null) {
            vlanGateway = network.getGateway();
            vlanNetmask = NetUtils.getCidrNetmask(network.getCidr());
            sameSubnet = hasSameSubnet(ipv4, vlanGateway, vlanNetmask, newVlanGateway, newVlanNetmask, startIP, endIP,
                    ipv6, ip6Gateway, ip6Cidr, startIPv6, endIPv6, network);
        }
        if (newVlanGateway == null && newVlanNetmask == null && !sameSubnet) {
            throw new InvalidParameterValueException("The IP range dose not belong to any of the existing subnets, Provide the netmask and gateway if you want to add new subnet");
        }
        Pair<String, String> vlanDetails = null;

        if (sameSubnet) {
            vlanDetails = new Pair<>(vlanGateway, vlanNetmask);
        } else {
            vlanDetails = new Pair<>(newVlanGateway, newVlanNetmask);
        }
        if (ipv4 && NetUtils.ipRangesOverlap(startIP, endIP, vlanDetails.first(), vlanDetails.first())) {
            throw new InvalidParameterValueException("The gateway ip should not be the part of the ip range being added.");
        }

        return new Pair<>(sameSubnet, vlanDetails);
    }

    @Override
    public boolean hasSameSubnet(boolean ipv4, String vlanGateway, String vlanNetmask, String newVlanGateway, String newVlanNetmask, String newStartIp, String newEndIp,
                                  boolean ipv6, String newIp6Gateway, String newIp6Cidr, String newIp6StartIp, String newIp6EndIp, Network network) {
        if (ipv4) {
            final NetUtils.SupersetOrSubset val = checkIfSubsetOrSuperset(vlanGateway, vlanNetmask, newVlanGateway, newVlanNetmask, newStartIp, newEndIp);
            if (val == NetUtils.SupersetOrSubset.isSuperset) {
                throw new InvalidParameterValueException("The subnet you are trying to add is a superset of the existing subnet having gateway " + vlanGateway
                        + " and netmask " + vlanNetmask);
            } else if (val == NetUtils.SupersetOrSubset.neitherSubetNorSuperset) {
                // not superset or subset of this subnet
            } else if (val == NetUtils.SupersetOrSubset.isSubset) {
                throw new InvalidParameterValueException("The subnet you are trying to add is a subset of the existing subnet having gateway " + vlanGateway
                        + " and netmask " + vlanNetmask);
            } else if (val == NetUtils.SupersetOrSubset.sameSubnet) {
                if (newVlanGateway != null && !newVlanGateway.equals(vlanGateway)) {
                    throw new InvalidParameterValueException("The gateway of the subnet should be unique. The subnet already has a gateway " + vlanGateway);
                }
                return true;
            }
        }
        if (ipv6) {
            if (newIp6Gateway != null && !newIp6Gateway.equals(network.getIp6Gateway())) {
                throw new InvalidParameterValueException("The input gateway " + newIp6Gateway + " is not same as network gateway " + network.getIp6Gateway());
            }
            if (newIp6Cidr != null && !newIp6Cidr.equals(network.getIp6Cidr())) {
                throw new InvalidParameterValueException("The input cidr " + newIp6Cidr + " is not same as network cidr " + network.getIp6Cidr());
            }

            newIp6Gateway = MoreObjects.firstNonNull(newIp6Gateway, network.getIp6Gateway());
            newIp6Cidr = MoreObjects.firstNonNull(newIp6Cidr, network.getIp6Cidr());
            _networkModel.checkIp6Parameters(newIp6StartIp, newIp6EndIp, newIp6Gateway, newIp6Cidr);
            if (!GuestType.Shared.equals(network.getGuestType())) {
                _networkModel.checkIp6CidrSizeEqualTo64(newIp6Cidr);
            }
            return true;
        }
        return false;
    }

    @Override
    @DB
    public Vlan createVlanAndPublicIpRange(final long zoneId, final long networkId, final long physicalNetworkId, final boolean forVirtualNetwork, final boolean forSystemVms, final Long podId, final String startIP, final String endIP,
                                           final String vlanGateway, final String vlanNetmask, String vlanId, boolean bypassVlanOverlapCheck, Domain domain, final Account vlanOwner, final String startIPv6, final String endIPv6, final String vlanIp6Gateway, final String vlanIp6Cidr, Provider provider) {
        final Network network = _networkModel.getNetwork(networkId);

        boolean ipv4 = false, ipv6 = false;

        if (startIP != null) {
            ipv4 = true;
        }

        if (vlanIp6Cidr != null) {
            ipv6 = true;
        }

        if (!ipv4 && !ipv6) {
            throw new InvalidParameterValueException("Please specify IPv4 or IPv6 address.");
        }

        final DataCenterVO zone = _zoneDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Please specify a valid zone.");
        }

        // ACL check
        checkZoneAccess(CallContext.current().getCallingAccount(), zone);

        if (_physicalNetworkDao.findById(physicalNetworkId) == null) {
            throw new InvalidParameterValueException("Please specify a valid physical network id");
        }

        if (podId != null) {
            final Pod pod = _podDao.findById(podId);
            if (pod == null) {
                throw new InvalidParameterValueException("Please specify a valid pod.");
            }
            if (pod.getDataCenterId() != zoneId) {
                throw new InvalidParameterValueException(String.format("Pod %s doesn't belong to zone id=%d", pod, zoneId));
            }
            if (zone.getNetworkType() != NetworkType.Basic || network.getTrafficType() != TrafficType.Guest) {
                throw new InvalidParameterValueException("Pod id can be specified only for the networks of type " + TrafficType.Guest + " in zone of type " + NetworkType.Basic);
            }
        }

        boolean forExternalProvider = ConfigurationService.IsIpRangeForProvider(provider);
        if (network.getTrafficType() == TrafficType.Guest) {
            boolean connectivityWithoutVlan = isConnectivityWithoutVlan(network);
            String networkVlanId = getNetworkVlanId(network, connectivityWithoutVlan);
            if (vlanId != null && !connectivityWithoutVlan) {
                if (networkVlanId != null && !NetUtils.isSameIsolationId(networkVlanId, vlanId)) {
                    throw new InvalidParameterValueException("Vlan doesn't match vlan of the network");
                }
            } else {
                vlanId = networkVlanId;
            }
        } else if (network.getTrafficType() == TrafficType.Public && vlanId == null && !forExternalProvider) {
            throw new InvalidParameterValueException("Unable to determine vlan id or untagged vlan for public network");
        }

        if (vlanId == null && !forExternalProvider) {
            vlanId = Vlan.UNTAGGED;
        }

        final VlanType vlanType = forVirtualNetwork ? VlanType.VirtualNetwork : VlanType.DirectAttached;

        if ((domain != null || vlanOwner != null) && zone.getNetworkType() != NetworkType.Advanced) {
            throw new InvalidParameterValueException("Vlan owner can be defined only in the zone of type " + NetworkType.Advanced);
        }

        if (ipv4) {
            if (!NetUtils.isValidIp4(vlanGateway)) {
                throw new InvalidParameterValueException("Please specify a valid gateway");
            }
            if (!NetUtils.isValidIp4Netmask(vlanNetmask)) {
                throw new InvalidParameterValueException("Please specify a valid netmask");
            }
        }

        if (ipv6) {
            if (!NetUtils.isValidIp6(vlanIp6Gateway)) {
                throw new InvalidParameterValueException("Please specify a valid IPv6 gateway");
            }
            if (!NetUtils.isValidIp6Cidr(vlanIp6Cidr)) {
                throw new InvalidParameterValueException("Please specify a valid IPv6 CIDR");
            }
        }

        boolean isSharedNetworkWithoutSpecifyVlan = _networkMgr.isSharedNetworkWithoutSpecifyVlan(_networkOfferingDao.findById(network.getNetworkOfferingId()));
        if (ipv4) {
            final String newCidr = NetUtils.getCidrFromGatewayAndNetmask(vlanGateway, vlanNetmask);

            if (!NetUtils.isIpWithInCidrRange(vlanGateway, newCidr) || !NetUtils.isIpWithInCidrRange(startIP, newCidr) || !NetUtils.isIpWithInCidrRange(endIP, newCidr)) {
                throw new InvalidParameterValueException("Please specify a valid IP range or valid netmask or valid gateway");
            }

            final String guestNetworkCidr = zone.getGuestNetworkCidr();
            if (guestNetworkCidr != null && NetUtils.isNetworksOverlap(newCidr, guestNetworkCidr) && _zoneDao.findVnet(zoneId, physicalNetworkId, vlanId).isEmpty() != true) {
                throw new InvalidParameterValueException("The new IP range you have specified has  overlapped with the guest network in zone: " + zone.getName()
                        + "along with existing Vlan also. Please specify a different gateway/netmask");
            }

            checkPublicIpRangeErrors(zoneId, vlanId, vlanGateway, vlanNetmask, startIP, endIP);

            checkConflictsWithPortableIpRange(zoneId, vlanId, vlanGateway, vlanNetmask, startIP, endIP);

            if (!isSharedNetworkWithoutSpecifyVlan) {
                checkZoneVlanIpOverlap(zone, network, newCidr, vlanId, vlanGateway, vlanNetmask, startIP, endIP);
            }
        }

        String ipv6Range = null;
        if (ipv6) {
            ipv6Range = startIPv6;
            if (StringUtils.isNotEmpty(ipv6Range) && StringUtils.isNotEmpty(endIPv6)) {
                ipv6Range += "-" + endIPv6;
            }

            final List<VlanVO> vlans = _vlanDao.listByZone(zone.getId());
            for (final VlanVO vlan : vlans) {
                if (vlan.getIp6Gateway() == null) {
                    continue;
                }
                if ((StringUtils.isAllEmpty(ipv6Range, vlan.getIp6Range())) &&
                        NetUtils.ipv6NetworksOverlap(IPv6Network.fromString(vlanIp6Cidr), IPv6Network.fromString(vlan.getIp6Cidr()))) {
                    throw new InvalidParameterValueException(String.format("The IPv6 range with tag: %s already has IPs that overlap with the new range.",
                            vlan.getVlanTag()));
                }
                if (!StringUtils.isAllEmpty(ipv6Range, vlan.getIp6Range())) {
                    String r1 = StringUtils.isEmpty(ipv6Range) ? NetUtils.getIpv6RangeFromCidr(vlanIp6Cidr) : ipv6Range;
                    String r2 = StringUtils.isEmpty(vlan.getIp6Range()) ? NetUtils.getIpv6RangeFromCidr(vlan.getIp6Cidr()) : vlan.getIp6Range();
                    if(NetUtils.isIp6RangeOverlap(r1, r2)) {
                        throw new InvalidParameterValueException(String.format("The IPv6 range with tag: %s already has IPs that overlap with the new range.",
                                vlan.getVlanTag()));
                    }
                }
            }
        }

        if (isSharedNetworkWithoutSpecifyVlan) {
            bypassVlanOverlapCheck = true;
        }
        if (!bypassVlanOverlapCheck && !forExternalProvider && !_zoneDao.findVnet(zoneId, physicalNetworkId, BroadcastDomainType.getValue(BroadcastDomainType.fromString(vlanId))).isEmpty()) {
            throw new InvalidParameterValueException("The VLAN tag " + vlanId + " is already being used for dynamic vlan allocation for the guest network in zone "
                    + zone.getName());
        }

        String ipRange = null;

        if (ipv4) {
            ipRange = startIP;
            if (endIP != null) {
                ipRange += "-" + endIP;
            }
        }

        final VlanVO vlan = commitVlanAndIpRange(zoneId, networkId, physicalNetworkId, podId, startIP, endIP, vlanGateway, vlanNetmask, vlanId, domain, vlanOwner, vlanIp6Gateway, vlanIp6Cidr,
                ipv4, zone, vlanType, ipv6Range, ipRange, forSystemVms, provider);

        if (vlan != null && network.getTrafficType() != TrafficType.Public) {
            if (ipv4) {
                addCidrAndGatewayForIpv4(networkId, vlanGateway, vlanNetmask);
            } else if (ipv6) {
                addCidrAndGatewayForIpv6(networkId, vlanIp6Gateway, vlanIp6Cidr);
            }
        }

        return vlan;
    }

    private void addCidrAndGatewayForIpv4(final long networkId, final String vlanGateway, final String vlanNetmask) {
        final NetworkVO networkVO = _networkDao.findById(networkId);
        String networkCidr = networkVO.getCidr();
        String newCidr = NetUtils.getCidrFromGatewayAndNetmask(vlanGateway, vlanNetmask);
        String newNetworkCidr = com.cloud.utils.StringUtils.updateCommaSeparatedStringWithValue(networkCidr, newCidr, true);
        networkVO.setCidr(newNetworkCidr);

        String networkGateway = networkVO.getGateway();
        String newNetworkGateway = com.cloud.utils.StringUtils.updateCommaSeparatedStringWithValue(networkGateway, vlanGateway, true);
        networkVO.setGateway(newNetworkGateway);
        _networkDao.update(networkId, networkVO);
    }

    private void addCidrAndGatewayForIpv6(final long networkId, final String vlanIp6Gateway, final String vlanIp6Cidr) {
        final NetworkVO networkVO = _networkDao.findById(networkId);
        String networkIp6Cidr = networkVO.getIp6Cidr();
        String newNetworkIp6Cidr = com.cloud.utils.StringUtils.updateCommaSeparatedStringWithValue(networkIp6Cidr, vlanIp6Cidr, true);
        networkVO.setIp6Cidr(newNetworkIp6Cidr);

        String networkIp6Gateway = networkVO.getIp6Gateway();
        String newNetworkIp6Gateway = com.cloud.utils.StringUtils.updateCommaSeparatedStringWithValue(networkIp6Gateway, vlanIp6Gateway, true);
        networkVO.setIp6Gateway(newNetworkIp6Gateway);
        _networkDao.update(networkId, networkVO);
    }

    private boolean isConnectivityWithoutVlan(Network network) {
        boolean connectivityWithoutVlan = false;
        if (_networkModel.areServicesSupportedInNetwork(network.getId(), Service.Connectivity)) {
            Map<Capability, String> connectivityCapabilities = _networkModel.getNetworkServiceCapabilities(network.getId(), Service.Connectivity);
            connectivityWithoutVlan = MapUtils.isNotEmpty(connectivityCapabilities) && connectivityCapabilities.containsKey(Capability.NoVlan);
        }
        return connectivityWithoutVlan;
    }

    private String getNetworkVlanId(Network network, boolean connectivityWithoutVlan) {
        String networkVlanId = null;
        if (connectivityWithoutVlan) {
            return network.getBroadcastDomainType().toUri(network.getUuid()).toString();
        }

        final URI uri = network.getBroadcastUri();
        if (uri != null) {
            if (uri.toString().startsWith("vlan")) {
                final String[] vlan = uri.toString().split("vlan:\\/\\/");
                networkVlanId = vlan[1];
                if (network.getBroadcastDomainType() != BroadcastDomainType.Vlan) {
                    networkVlanId = networkVlanId.split("-")[0];
                }
            }
        }
        return networkVlanId;
    }

    private void checkZoneVlanIpOverlap(DataCenterVO zone, Network network, String newCidr, String vlanId, String vlanGateway, String vlanNetmask, String startIP, String endIP) {
        final List<VlanVO> vlans = _vlanDao.listByZone(zone.getId());
        for (final VlanVO vlan : vlans) {
            final String otherVlanGateway = vlan.getVlanGateway();
            final String otherVlanNetmask = vlan.getVlanNetmask();
            if (ObjectUtils.anyNull(otherVlanGateway, otherVlanNetmask, vlan.getNetworkId())) {
                continue;
            }
            final String otherCidr = NetUtils.getCidrFromGatewayAndNetmask(otherVlanGateway, otherVlanNetmask);
            if( !NetUtils.isNetworksOverlap(newCidr,  otherCidr)) {
                continue;
            }
            VlanDetailsVO vlanDetail = vlanDetailsDao.findDetail(vlan.getId(), ApiConstants.NSX_DETAIL_KEY);
            if ((Objects.isNull(vlanId) && Objects.nonNull(vlanDetail) && vlanDetail.getValue().equals("true")) || Objects.nonNull(vlanId) &&
                    (vlanId.toLowerCase().contains(Vlan.UNTAGGED) || UriUtils.checkVlanUriOverlap(
                    BroadcastDomainType.getValue(BroadcastDomainType.fromString(vlanId)),
                    BroadcastDomainType.getValue(BroadcastDomainType.fromString(vlan.getVlanTag()))))) {
                final String[] otherVlanIpRange = vlan.getIpRange().split("\\-");
                final String otherVlanStartIP = otherVlanIpRange[0];
                String otherVlanEndIP = null;
                if (otherVlanIpRange.length > 1) {
                    otherVlanEndIP = otherVlanIpRange[1];
                }

                if (!vlanGateway.equals(otherVlanGateway) || !vlanNetmask.equals(vlan.getVlanNetmask())) {
                    throw new InvalidParameterValueException("The IP range has already been added with gateway "
                            + otherVlanGateway + " ,and netmask " + otherVlanNetmask
                            + ", Please specify the gateway/netmask if you want to extend ip range" );
                }
                if (!NetUtils.is31PrefixCidr(newCidr) && NetUtils.ipRangesOverlap(startIP, endIP, otherVlanStartIP, otherVlanEndIP)) {
                    throw new InvalidParameterValueException("The IP range already has IPs that overlap with the new range." +
                            " Please specify a different start IP/end IP.");
                }
            } else {
                boolean overlapped = false;
                if (network.getTrafficType() == TrafficType.Public) {
                    overlapped = true;
                } else {
                    final Long nwId = vlan.getNetworkId();
                    if (nwId != null) {
                        final Network nw = _networkModel.getNetwork(nwId);
                        if (nw != null && nw.getTrafficType() == TrafficType.Public) {
                            overlapped = true;
                        }
                    }

                }
                if (overlapped) {
                    throw new InvalidParameterValueException("The IP range with tag: " + vlan.getVlanTag()
                            + " in zone " + zone.getName()
                            + " has overlapped with the subnet. Please specify a different gateway/netmask.");
                }
            }
        }
    }

    private VlanVO commitVlanAndIpRange(final long zoneId, final long networkId, final long physicalNetworkId, final Long podId, final String startIP, final String endIP,
            final String vlanGateway, final String vlanNetmask, final String vlanId, final Domain domain, final Account vlanOwner, final String vlanIp6Gateway, final String vlanIp6Cidr,
            final boolean ipv4, final DataCenterVO zone, final VlanType vlanType, final String ipv6Range, final String ipRange, final boolean forSystemVms, final Provider provider) {
        return Transaction.execute(new TransactionCallback<>() {
            @Override
            public VlanVO doInTransaction(final TransactionStatus status) {
                VlanVO vlan = new VlanVO(vlanType, vlanId, vlanGateway, vlanNetmask, zone.getId(), ipRange, networkId, physicalNetworkId, vlanIp6Gateway, vlanIp6Cidr, ipv6Range);
                logger.debug("Saving vlan range " + vlan);
                vlan = _vlanDao.persist(vlan);
                if (Objects.nonNull(provider)) {
                    addProviderVlanDetailKey(vlan, provider);
                }

                if (ipv4) {
                    if (!savePublicIPRange(startIP, endIP, zoneId, vlan.getId(), networkId, physicalNetworkId, forSystemVms)) {
                        throw new CloudRuntimeException("Failed to save IPv4 range. Please contact Cloud Support.");
                    }
                }

                if (vlanOwner != null) {
                    final AccountVlanMapVO accountVlanMapVO = new AccountVlanMapVO(vlanOwner.getId(), vlan.getId());
                    _accountVlanMapDao.persist(accountVlanMapVO);

                    final List<IPAddressVO> ips = _publicIpAddressDao.listByVlanId(vlan.getId());
                    for (final IPAddressVO ip : ips) {
                        final boolean usageHidden = _ipAddrMgr.isUsageHidden(ip);
                        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NET_IP_ASSIGN, vlanOwner.getId(), ip.getDataCenterId(), ip.getId(), ip.getAddress().toString(),
                                ip.isSourceNat(), vlan.getVlanType().toString(), ip.getSystem(), usageHidden, ip.getClass().getName(), ip.getUuid());
                    }
                    _resourceLimitMgr.incrementResourceCount(vlanOwner.getId(), ResourceType.public_ip, (long)ips.size());
                } else if (domain != null && !forSystemVms) {
                    final DomainVlanMapVO domainVlanMapVO = new DomainVlanMapVO(domain.getId(), vlan.getId());
                    _domainVlanMapDao.persist(domainVlanMapVO);
                } else if (podId != null) {
                    final PodVlanMapVO podVlanMapVO = new PodVlanMapVO(podId, vlan.getId());
                    podVlanMapDao.persist(podVlanMapVO);
                }
                return vlan;
            }
        });

    }

    private void addProviderVlanDetailKey(Vlan vlan, Provider provider) {
        vlanDetailsDao.addDetail(vlan.getId(), getProviderDetailKey(provider.getName()),
                String.valueOf(ConfigurationService.IsIpRangeForProvider(provider)), true);
    }

    private String getProviderDetailKey(String providerName) {
        return ConfigurationService.ProviderDetailKeyMap.get(providerName);
    }

    @Override
    public Vlan updateVlanAndPublicIpRange(UpdateVlanIpRangeCmd cmd) throws ConcurrentOperationException,
            ResourceUnavailableException, ResourceAllocationException {

        return  updateVlanAndPublicIpRange(cmd.getId(), cmd.getStartIp(),cmd.getEndIp(), cmd.getGateway(),cmd.getNetmask(),
                cmd.getStartIpv6(), cmd.getEndIpv6(), cmd.getIp6Gateway(), cmd.getIp6Cidr(), cmd.isForSystemVms());
    }

    @DB
    @ActionEvent(eventType = EventTypes.EVENT_VLAN_IP_RANGE_UPDATE, eventDescription = "update vlan ip Range", async
            = false)
    public Vlan updateVlanAndPublicIpRange(final long id, String startIp,
                                           String endIp,
                                           String gateway,
                                           String netmask,
                                           String startIpv6,
                                           String endIpv6,
                                           String ip6Gateway,
                                           String ip6Cidr,
                                           Boolean forSystemVms) throws ConcurrentOperationException, ResourceAllocationException {

        VlanVO vlanRange = _vlanDao.findById(id);
        if (vlanRange == null) {
            throw new InvalidParameterValueException("Please specify a valid IP range id.");
        }

        final boolean ipv4 = vlanRange.getVlanGateway() != null;
        final boolean ipv6 = vlanRange.getIp6Gateway() != null;
        if (!ipv4) {
            if (startIp != null || endIp != null || gateway != null || netmask != null) {
                throw new InvalidParameterValueException("IPv4 is not support in this IP range.");
            }
        }
        if (!ipv6) {
            if (startIpv6 != null || endIpv6 != null || ip6Gateway != null || ip6Cidr != null) {
                throw new InvalidParameterValueException("IPv6 is not support in this IP range.");
            }
        }

        AccountVlanMapVO accountMap = _accountVlanMapDao.findAccountVlanMap(null, id);
        Account account = accountMap != null ? _accountDao.findById(accountMap.getAccountId()) : null;

        DomainVlanMapVO domainMap = _domainVlanMapDao.findDomainVlanMap(null, id);
        Long domainId = domainMap != null ? domainMap.getDomainId() : null;

        final Boolean isRangeForSystemVM = checkIfVlanRangeIsForSystemVM(id);
        if (forSystemVms != null && isRangeForSystemVM != forSystemVms) {
            if (VlanType.DirectAttached.equals(vlanRange.getVlanType())) {
                throw new InvalidParameterValueException("forSystemVms is not available for this IP range with vlan type: " + VlanType.DirectAttached);
            }
            if (account != null) {
                throw new InvalidParameterValueException("Specified Public IP range has already been dedicated to an account");
            }
            if (domainId != null) {
                throw new InvalidParameterValueException("Specified Public IP range has already been dedicated to a domain");
            }
        }
        if (ipv4) {
            long existingIpAddressAmount = 0L;
            long newIpAddressAmount = 0L;

            if (account != null) {
                existingIpAddressAmount = _publicIpAddressDao.countIPs(vlanRange.getDataCenterId(), id, false);
                newIpAddressAmount = NetUtils.ip2Long(endIp) - NetUtils.ip2Long(startIp) + 1;
            }

            try (CheckedReservation publicIpReservation = new CheckedReservation(account, ResourceType.public_ip, null, null, null, newIpAddressAmount, existingIpAddressAmount, reservationDao, _resourceLimitMgr)) {

                updateVlanAndIpv4Range(id, vlanRange, startIp, endIp, gateway, netmask, isRangeForSystemVM, forSystemVms);

                if (account != null) {
                    long countDiff = newIpAddressAmount - existingIpAddressAmount;
                    if (countDiff > 0) {
                        _resourceLimitMgr.incrementResourceCount(account.getId(), ResourceType.public_ip, countDiff);
                    } else if (countDiff < 0) {
                        _resourceLimitMgr.decrementResourceCount(account.getId(), ResourceType.public_ip, Math.abs(countDiff));
                    }
                }
            }
        }
        if (ipv6) {
            updateVlanAndIpv6Range(id, vlanRange, startIpv6, endIpv6, ip6Gateway, ip6Cidr, isRangeForSystemVM, forSystemVms);
        }
        return _vlanDao.findById(id);
    }

    private void updateVlanAndIpv4Range(final long id, final VlanVO vlanRange,
                                        String startIp,
                                        String endIp,
                                        String gateway,
                                        String netmask,
                                        final Boolean isRangeForSystemVM,
                                        final Boolean forSystemVms) {
        final List<IPAddressVO> listAllocatedIPs = _publicIpAddressDao.listByVlanIdAndState(id, IpAddress.State.Allocated);

        if (gateway != null && !gateway.equals(vlanRange.getVlanGateway()) && CollectionUtils.isNotEmpty(listAllocatedIPs)) {
            throw new InvalidParameterValueException(String.format("Unable to change gateway to %s because some IPs are in use", gateway));
        }
        if (netmask != null && !netmask.equals(vlanRange.getVlanNetmask()) && CollectionUtils.isNotEmpty(listAllocatedIPs)) {
            throw new InvalidParameterValueException(String.format("Unable to change netmask to %s because some IPs are in use", netmask));
        }

        gateway = MoreObjects.firstNonNull(gateway, vlanRange.getVlanGateway());
        netmask = MoreObjects.firstNonNull(netmask, vlanRange.getVlanNetmask());

        final String[] existingVlanIPRangeArray = vlanRange.getIpRange().split("-");
        final String currentStartIP = existingVlanIPRangeArray[0];
        final String currentEndIP = existingVlanIPRangeArray[1];

        startIp = MoreObjects.firstNonNull(startIp, currentStartIP);
        endIp = MoreObjects.firstNonNull(endIp, currentEndIP);

        final String cidr = NetUtils.ipAndNetMaskToCidr(gateway, netmask);
        if (StringUtils.isEmpty(cidr)) {
            throw new InvalidParameterValueException(String.format("Invalid gateway (%s) or netmask (%s)", gateway, netmask));
        }
        final String cidrAddress = getCidrAddress(cidr);
        final long cidrSize = getCidrSize(cidr);

        checkIpRange(startIp, endIp, cidrAddress, cidrSize);

        checkGatewayOverlap(startIp, endIp, gateway);

        checkAllocatedIpsAreWithinVlanRange(listAllocatedIPs, startIp, endIp, forSystemVms);

        try {
            final String newStartIP = startIp;
            final String newEndIP = endIp;

            VlanVO range = _vlanDao.acquireInLockTable(id, 30);
            if (range == null) {
                throw new CloudRuntimeException("Unable to acquire vlan configuration: " + id);
            }

            if (logger.isDebugEnabled()) {
                logger.debug("lock on vlan {} is acquired", range);
            }

            commitUpdateVlanAndIpRange(id, newStartIP, newEndIP, currentStartIP, currentEndIP, gateway, netmask,true, isRangeForSystemVM, forSystemVms);

        } catch (final Exception e) {
            logger.error("Unable to edit VlanRange due to " + e.getMessage(), e);
            throw new CloudRuntimeException("Failed to edit VlanRange. Please contact Cloud Support.");
        } finally {
            _vlanDao.releaseFromLockTable(id);
        }
    }

    private void updateVlanAndIpv6Range(final long id, final VlanVO vlanRange,
                                        String startIpv6,
                                        String endIpv6,
                                        String ip6Gateway,
                                        String ip6Cidr,
                                        final Boolean isRangeForSystemVM,
                                        final Boolean forSystemVms) {
        final List<UserIpv6AddressVO> listAllocatedIPs = _ipv6Dao.listByVlanIdAndState(id, IpAddress.State.Allocated);

        if (ip6Gateway != null && !ip6Gateway.equals(vlanRange.getIp6Gateway()) && (CollectionUtils.isNotEmpty(listAllocatedIPs) || CollectionUtils.isNotEmpty(ipv6Service.getAllocatedIpv6FromVlanRange(vlanRange)))) {
            throw new InvalidParameterValueException(String.format("Unable to change IPv6 gateway to %s because some IPs are in use", ip6Gateway));
        }
        if (ip6Cidr != null && !ip6Cidr.equals(vlanRange.getIp6Cidr()) && (CollectionUtils.isNotEmpty(listAllocatedIPs) || CollectionUtils.isNotEmpty(ipv6Service.getAllocatedIpv6FromVlanRange(vlanRange)))) {
            throw new InvalidParameterValueException(String.format("Unable to change IPv6 CIDR to %s because some IPs are in use", ip6Cidr));
        }
        ip6Gateway = MoreObjects.firstNonNull(ip6Gateway, vlanRange.getIp6Gateway());
        ip6Cidr = MoreObjects.firstNonNull(ip6Cidr, vlanRange.getIp6Cidr());

        final String[] existingVlanIPRangeArray = StringUtils.isNotEmpty(vlanRange.getIp6Range()) ? vlanRange.getIp6Range().split("-") : null;
        final String currentStartIPv6 = existingVlanIPRangeArray != null ? existingVlanIPRangeArray[0] : null;
        final String currentEndIPv6 = existingVlanIPRangeArray != null ? existingVlanIPRangeArray[1] : null;

        startIpv6 = ObjectUtils.allNull(startIpv6, currentStartIPv6) ? null : MoreObjects.firstNonNull(startIpv6, currentStartIPv6);
        endIpv6 = ObjectUtils.allNull(endIpv6, currentEndIPv6) ? null : MoreObjects.firstNonNull(endIpv6, currentEndIPv6);

        _networkModel.checkIp6Parameters(startIpv6, endIpv6, ip6Gateway, ip6Cidr);
        final Network network = _networkModel.getNetwork(vlanRange.getNetworkId());
        if (!GuestType.Shared.equals(network.getGuestType())) {
            _networkModel.checkIp6CidrSizeEqualTo64(ip6Cidr);
        }

        if (!ObjectUtils.allNull(startIpv6, endIpv6) && ObjectUtils.anyNull(startIpv6, endIpv6)) {
            throw new InvalidParameterValueException(String.format("Invalid IPv6 range %s-%s", startIpv6, endIpv6));
        }
        if (ObjectUtils.allNotNull(startIpv6, endIpv6) && (!startIpv6.equals(currentStartIPv6) || !endIpv6.equals(currentEndIPv6))) {
            checkAllocatedIpv6sAreWithinVlanRange(listAllocatedIPs, startIpv6, endIpv6);
        }

        try {
            VlanVO range = _vlanDao.acquireInLockTable(id, 30);
            if (range == null) {
                throw new CloudRuntimeException("Unable to acquire vlan configuration: " + id);
            }

            if (logger.isDebugEnabled()) {
                logger.debug("lock on vlan {} is acquired", range);
            }

            commitUpdateVlanAndIpRange(id, startIpv6, endIpv6, currentStartIPv6, currentEndIPv6, ip6Gateway, ip6Cidr, false, isRangeForSystemVM,forSystemVms);

        } catch (final Exception e) {
            logger.error("Unable to edit VlanRange due to " + e.getMessage(), e);
            throw new CloudRuntimeException("Failed to edit VlanRange. Please contact Cloud Support.");
        } finally {
            _vlanDao.releaseFromLockTable(id);
        }
    }

    private VlanVO commitUpdateVlanAndIpRange(final Long id, final String newStartIP, final String newEndIP, final String currentStartIP, final String currentEndIP,
                                              final String gateway, final String netmask,
                                              final boolean ipv4, final Boolean isRangeForSystemVM, final Boolean forSystemvms) {

        return Transaction.execute(new TransactionCallback<>() {
            @Override
            public VlanVO doInTransaction(final TransactionStatus status) {
                VlanVO vlanRange = _vlanDao.findById(id);
                logger.debug("Updating vlan range {}", vlanRange);
                if (ipv4) {
                    vlanRange.setIpRange(newStartIP + "-" + newEndIP);
                    vlanRange.setVlanGateway(gateway);
                    vlanRange.setVlanNetmask(netmask);
                    _vlanDao.update(vlanRange.getId(), vlanRange);
                    if (!updatePublicIPRange(newStartIP, currentStartIP, newEndIP, currentEndIP, vlanRange.getDataCenterId(), vlanRange.getId(), vlanRange.getNetworkId(), vlanRange.getPhysicalNetworkId(), isRangeForSystemVM, forSystemvms)) {
                        throw new CloudRuntimeException("Failed to update IPv4 range. Please contact Cloud Support.");
                    }
                } else {
                    if (ObjectUtils.allNotNull(newStartIP, newEndIP)) {
                        vlanRange.setIp6Range(newStartIP + "-" + newEndIP);
                    } else {
                        vlanRange.setIp6Range(null);
                    }
                    vlanRange.setIp6Gateway(gateway);
                    vlanRange.setIp6Cidr(netmask);
                    _vlanDao.update(vlanRange.getId(), vlanRange);
                }
                return vlanRange;
            }
        });
    }

    private boolean checkIfVlanRangeIsForSystemVM(final long vlanId) {
        List<IPAddressVO> existingPublicIPs = _publicIpAddressDao.listByVlanId(vlanId);
        if (CollectionUtils.isEmpty(existingPublicIPs)) {
            return false;
        }
        boolean initialIsSystemVmValue = existingPublicIPs.get(0).isForSystemVms();
        for (IPAddressVO existingIPs : existingPublicIPs) {
            if (initialIsSystemVmValue != existingIPs.isForSystemVms()) {
                throw new CloudRuntimeException("Your \"For System VM\" value seems to be inconsistent with the rest of the records. Please contact Cloud Support");
            }
        }
        return initialIsSystemVmValue;
    }

    private void checkAllocatedIpsAreWithinVlanRange
            (List<IPAddressVO> listAllocatedIPs, String startIp, String endIp, Boolean forSystemVms) {
        Collections.sort(listAllocatedIPs, Comparator.comparing(IPAddressVO::getAddress));
        for (IPAddressVO allocatedIP : listAllocatedIPs) {
            if ((StringUtils.isNotEmpty(startIp) && NetUtils.ip2Long(startIp) > NetUtils.ip2Long(allocatedIP.getAddress().addr()))
                    || (StringUtils.isNotEmpty(endIp) && NetUtils.ip2Long(endIp) < NetUtils.ip2Long(allocatedIP.getAddress().addr()))) {
                throw new InvalidParameterValueException(String.format("The start IP address must be less than or equal to %s which is already in use. "
                                + "The end IP address must be greater than or equal to %s which is already in use. "
                                + "There are %d IPs already allocated in this range.",
                        listAllocatedIPs.get(0).getAddress(), listAllocatedIPs.get(listAllocatedIPs.size() - 1).getAddress(), listAllocatedIPs.size()));
            }
            if (forSystemVms != null && allocatedIP.isForSystemVms() != forSystemVms) {
                throw new InvalidParameterValueException(String.format("IP %s is in use, cannot change forSystemVms of the IP range", allocatedIP.getAddress().addr()));
            }
        }
    }

    private void checkAllocatedIpv6sAreWithinVlanRange(List<UserIpv6AddressVO> listAllocatedIPs, String startIpv6, String endIpv6) {
        Collections.sort(listAllocatedIPs, Comparator.comparing(UserIpv6AddressVO::getAddress));
        for (UserIpv6AddressVO allocatedIP : listAllocatedIPs) {
            if ((StringUtils.isNotEmpty(startIpv6)
                    && IPv6Address.fromString(startIpv6).toBigInteger().compareTo(IPv6Address.fromString(allocatedIP.getAddress()).toBigInteger()) > 0)
                    || (StringUtils.isNotEmpty(endIpv6)
                    && IPv6Address.fromString(endIpv6).toBigInteger().compareTo(IPv6Address.fromString(allocatedIP.getAddress()).toBigInteger()) < 0)) {
                throw new InvalidParameterValueException(String.format("The start IPv6 address must be less than or equal to %s which is already in use. "
                                + "The end IPv6 address must be greater than or equal to %s which is already in use. "
                                + "There are %d IPv6 addresses already allocated in this range.",
                        listAllocatedIPs.get(0).getAddress(), listAllocatedIPs.get(listAllocatedIPs.size() - 1).getAddress(), listAllocatedIPs.size()));
            }
        }
    }

    private void checkGatewayOverlap(String startIp, String endIp, String gateway) {
        if (NetUtils.ipRangesOverlap(startIp, endIp, gateway, gateway)) {
            throw new InvalidParameterValueException("The gateway shouldn't overlap the new start/end ip "
                    + "addresses");
        }
    }

    @Override
    @DB
    public VlanVO deleteVlanAndPublicIpRange(final long userId, final long vlanDbId, final Account caller) {
        VlanVO vlanRange = _vlanDao.findById(vlanDbId);
        if (vlanRange == null) {
            throw new InvalidParameterValueException("Please specify a valid IP range id.");
        }

        boolean isAccountSpecific = false;
        final List<AccountVlanMapVO> acctVln = _accountVlanMapDao.listAccountVlanMapsByVlan(vlanRange.getId());
        if (acctVln != null && !acctVln.isEmpty()) {
            isAccountSpecific = true;
        }

        boolean isDomainSpecific = false;
        List<DomainVlanMapVO> domainVlan = _domainVlanMapDao.listDomainVlanMapsByVlan(vlanRange.getId());
        if (domainVlan != null && !domainVlan.isEmpty()) {
            isDomainSpecific = true;
        }

        final List<IPAddressVO> ips = _publicIpAddressDao.listByVlanId(vlanDbId);
        if (isAccountSpecific) {
            int resourceCountToBeDecrement = 0;
            try {
                vlanRange = _vlanDao.acquireInLockTable(vlanDbId, 30);
                if (vlanRange == null) {
                    throw new CloudRuntimeException("Unable to acquire vlan configuration: " + vlanDbId);
                }

                if (logger.isDebugEnabled()) {
                    logger.debug("lock on vlan {} is acquired", vlanRange);
                }
                for (final IPAddressVO ip : ips) {
                    boolean success = true;
                    if (ip.isOneToOneNat()) {
                        throw new InvalidParameterValueException(String.format("Can't delete account specific vlan %s as ip %s belonging to the range is used for static nat purposes. Cleanup the rules first", vlanRange, ip));
                    }

                    if (ip.isSourceNat()) {
                        throw new InvalidParameterValueException(String.format("Can't delete account specific vlan %s as ip %s belonging to the range is a source nat ip for the network id=%d. IP range with the source nat ip address can be removed either as a part of Network, or account removal", vlanRange, ip, ip.getSourceNetworkId()));
                    }

                    if (_firewallDao.countRulesByIpId(ip.getId()) > 0) {
                        throw new InvalidParameterValueException(String.format("Can't delete account specific vlan %s as ip %s belonging to the range has firewall rules applied. Cleanup the rules first", vlanRange, ip));
                    }
                    if (ip.getAllocatedTime() != null) {
                        success = _ipAddrMgr.disassociatePublicIpAddress(ip, userId, caller);
                    }
                    if (!success) {
                        logger.warn("Some ip addresses failed to be released as a part of vlan {} removal", vlanRange);
                    } else {
                        resourceCountToBeDecrement++;
                        final boolean usageHidden = _ipAddrMgr.isUsageHidden(ip);
                        UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NET_IP_RELEASE, acctVln.get(0).getAccountId(), ip.getDataCenterId(), ip.getId(),
                                ip.getAddress().toString(), ip.isSourceNat(), vlanRange.getVlanType().toString(), ip.getSystem(), usageHidden, ip.getClass().getName(), ip.getUuid());
                    }
                }
            } finally {
                _vlanDao.releaseFromLockTable(vlanDbId);
                if (resourceCountToBeDecrement > 0) {
                    _resourceLimitMgr.decrementResourceCount(acctVln.get(0).getAccountId(), ResourceType.public_ip, (long)resourceCountToBeDecrement);
                }
            }
        } else {
            final NicIpAliasVO ipAlias = _nicIpAliasDao.findByGatewayAndNetworkIdAndState(vlanRange.getVlanGateway(), vlanRange.getNetworkId(), NicIpAlias.State.active);
            if (ipAlias != null && vlanDbId == _publicIpAddressDao.findByIpAndSourceNetworkId(vlanRange.getNetworkId(), ipAlias.getIp4Address()).getVlanId()) {
                throw new InvalidParameterValueException(String.format("Cannot delete vlan range %s as %sis being used for providing dhcp service in this subnet. Delete all VMs in this subnet and try again", vlanRange, ipAlias.getIp4Address()));
            }
            final long allocIpCount = _publicIpAddressDao.countIPs(vlanRange.getDataCenterId(), vlanDbId, true);
            if (allocIpCount > 0) {
                throw new InvalidParameterValueException(allocIpCount + "  Ips are in use. Cannot delete this vlan");
            }
        }
        List<String> ipAddresses = ipv6Service.getAllocatedIpv6FromVlanRange(vlanRange);
        if (CollectionUtils.isNotEmpty(ipAddresses)) {
            throw new InvalidParameterValueException(String.format("%d IPv6 addresses are in use. Cannot delete this vlan", ipAddresses.size()));
        }

        VlanVO finalVlanRange = vlanRange;
        Transaction.execute(new TransactionCallbackNoReturn() {
            @Override
            public void doInTransactionWithoutResult(final TransactionStatus status) {
                _publicIpAddressDao.deletePublicIPRange(vlanDbId);
                logger.debug("Delete Public IP Range (from user_ip_address, where vlan_db_id={})", vlanDbId);

                _vlanDao.remove(vlanDbId);
                logger.debug("Mark vlan as Remove vlan (vlan_db_id={})", vlanDbId);

                SearchBuilder<PodVlanMapVO> sb = podVlanMapDao.createSearchBuilder();
                sb.and("vlan_db_id", sb.entity().getVlanDbId(), SearchCriteria.Op.EQ);
                SearchCriteria<PodVlanMapVO> sc = sb.create();
                sc.setParameters("vlan_db_id", vlanDbId);
                podVlanMapDao.remove(sc);
                logger.debug("Delete vlan_db_id={} in pod_vlan_map", vlanDbId);
            }
        });

        return vlanRange;
    }

    @Override
    @DB
    @ActionEvent(eventType = EventTypes.EVENT_VLAN_IP_RANGE_DEDICATE, eventDescription = "dedicating vlan ip range", async = false)
    public Vlan dedicatePublicIpRange(final DedicatePublicIpRangeCmd cmd) throws ResourceAllocationException {
        final Long vlanDbId = cmd.getId();
        final String accountName = cmd.getAccountName();
        final Long domainId = cmd.getDomainId();
        final Long projectId = cmd.getProjectId();

        Account vlanOwner = null;
        if (projectId != null) {
            if (accountName != null) {
                throw new InvalidParameterValueException("accountName and projectId are mutually exclusive");
            }
            final Project project = _projectMgr.getProject(projectId);
            if (project == null) {
                throw new InvalidParameterValueException("Unable to find project by id " + projectId);
            }
            vlanOwner = _accountMgr.getAccount(project.getProjectAccountId());
            if (vlanOwner == null) {
                throw new InvalidParameterValueException("Please specify a valid projectId");
            }
        }

        Domain domain = null;
        if (accountName != null && domainId != null) {
            vlanOwner = _accountDao.findActiveAccount(accountName, domainId);
            if (vlanOwner == null) {
                throw new InvalidParameterValueException("Unable to find account by name " + accountName);
            } else if (vlanOwner.getId() == Account.ACCOUNT_ID_SYSTEM) {
                throw new InvalidParameterValueException("Please specify a valid account. Cannot dedicate IP range to system account");
            }
        } else if (domainId != null) {
            domain = _domainDao.findById(domainId);
            if (domain == null) {
                throw new InvalidParameterValueException("Please specify a valid domain id");
            }
        }

        final VlanVO vlan = _vlanDao.findById(vlanDbId);
        if (vlan == null) {
            throw new InvalidParameterValueException("Unable to find vlan by id " + vlanDbId);
        }

        final List<AccountVlanMapVO> maps = _accountVlanMapDao.listAccountVlanMapsByVlan(vlanDbId);
        if (maps != null && !maps.isEmpty()) {
            throw new InvalidParameterValueException("Specified Public IP range has already been dedicated");
        }

        List<DomainVlanMapVO> domainmaps = _domainVlanMapDao.listDomainVlanMapsByVlan(vlanDbId);
        if (domainmaps != null && !domainmaps.isEmpty()) {
            throw new InvalidParameterValueException("Specified Public IP range has already been dedicated to a domain");
        }

        final Long zoneId = vlan.getDataCenterId();
        final DataCenterVO zone = _zoneDao.findById(zoneId);
        if (zone == null) {
            throw new InvalidParameterValueException("Unable to find zone by id " + zoneId);
        }
        if (zone.getNetworkType() == NetworkType.Basic) {
            throw new InvalidParameterValueException("Public IP range can be dedicated to an account only in the zone of type " + NetworkType.Advanced);
        }

        final List<IPAddressVO> ips = _publicIpAddressDao.listByVlanId(vlanDbId);
        for (final IPAddressVO ip : ips) {
            if (ip.isForSystemVms()) {
                throw new InvalidParameterValueException(ip.getAddress() + " Public IP address in range is dedicated to system vms ");
            }
            final Long allocatedToAccountId = ip.getAllocatedToAccountId();
            if (allocatedToAccountId != null) {
                if (vlanOwner != null && allocatedToAccountId != vlanOwner.getId()) {
                    throw new InvalidParameterValueException(ip.getAddress() + " Public IP address in range is allocated to another account ");
                }
                final Account accountAllocatedTo = _accountMgr.getActiveAccountById(allocatedToAccountId);
                if (vlanOwner == null && domain != null && domain.getId() != accountAllocatedTo.getDomainId()){
                    throw new InvalidParameterValueException(ip.getAddress()
                            + " Public IP address in range is allocated to another domain/account ");
                }
            }
        }

        long reservedIpAddressesAmount = vlanOwner != null ? _publicIpAddressDao.countIPs(zoneId, vlanDbId, false) : 0L;
        try (CheckedReservation publicIpReservation = new CheckedReservation(vlanOwner, ResourceType.public_ip, null, null, null, reservedIpAddressesAmount, null, reservationDao, _resourceLimitMgr)) {

            if (vlanOwner != null) {
                final AccountVlanMapVO accountVlanMapVO = new AccountVlanMapVO(vlanOwner.getId(), vlan.getId());
                _accountVlanMapDao.persist(accountVlanMapVO);

                for (final IPAddressVO ip : ips) {
                    final boolean usageHidden = _ipAddrMgr.isUsageHidden(ip);
                    UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NET_IP_ASSIGN, vlanOwner.getId(), ip.getDataCenterId(), ip.getId(), ip.getAddress().toString(), ip.isSourceNat(),
                            vlan.getVlanType().toString(), ip.getSystem(), usageHidden, ip.getClass().getName(), ip.getUuid());
                }
            } else if (domain != null) {
                DomainVlanMapVO domainVlanMapVO = new DomainVlanMapVO(domain.getId(), vlan.getId());
                _domainVlanMapDao.persist(domainVlanMapVO);
            }

            if (vlanOwner != null) {
                _resourceLimitMgr.incrementResourceCount(vlanOwner.getId(), ResourceType.public_ip, (long)ips.size());
            }

            return vlan;

        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VLAN_IP_RANGE_RELEASE, eventDescription = "releasing a public ip range", async = false)
    public boolean releasePublicIpRange(final ReleasePublicIpRangeCmd cmd) {
        final Long vlanDbId = cmd.getId();

        final VlanVO vlan = _vlanDao.findById(vlanDbId);
        if (vlan == null) {
            throw new InvalidParameterValueException("Please specify a valid IP range id.");
        }

        return releasePublicIpRange(vlanDbId, CallContext.current().getCallingUser(), CallContext.current().getCallingAccount());
    }

    @Override
    @DB
    public boolean releasePublicIpRange(final long vlanDbId, final User user, final Account caller) {
        VlanVO vlan = _vlanDao.findById(vlanDbId);
        if(vlan == null) {
            logger.warn("Skipping the process for releasing public IP range as could not find a VLAN with ID '{}' for Account '{}' and User '{}'.",
                    vlanDbId, caller, user);
            return true;
        }

        boolean isAccountSpecific = false;
        final List<AccountVlanMapVO> acctVln = _accountVlanMapDao.listAccountVlanMapsByVlan(vlanDbId);
        if (acctVln != null && !acctVln.isEmpty()) {
            isAccountSpecific = true;
        }

        boolean isDomainSpecific = false;
        final List<DomainVlanMapVO> domainVlan = _domainVlanMapDao.listDomainVlanMapsByVlan(vlanDbId);
        if (domainVlan != null && !domainVlan.isEmpty()) {
            isDomainSpecific = true;
        }

        if (!isAccountSpecific && !isDomainSpecific) {
            throw new InvalidParameterValueException("Can't release Public IP range " + vlanDbId
                    + " as it not dedicated to any domain and any account");
        }
        final long allocIpCount = _publicIpAddressDao.countIPs(vlan.getDataCenterId(), vlanDbId, true);
        final List<IPAddressVO> ips = _publicIpAddressDao.listByVlanId(vlanDbId);
        boolean success = true;
        final List<IPAddressVO> ipsInUse = new ArrayList<>();
        if (allocIpCount > 0) {
            try {
                vlan = _vlanDao.acquireInLockTable(vlanDbId, 30);
                if (vlan == null) {
                    throw new CloudRuntimeException("Unable to acquire vlan configuration: " + vlanDbId);
                }
                if (logger.isDebugEnabled()) {
                    logger.debug("lock on vlan {} is acquired", vlan);
                }
                for (final IPAddressVO ip : ips) {
                    if (!ip.isOneToOneNat() && !ip.isSourceNat() && !(_firewallDao.countRulesByIpId(ip.getId()) > 0)) {
                        if (logger.isDebugEnabled()) {
                            logger.debug("Releasing Public IP addresses {} of vlan {} as part of Public IP range release to the system pool", ip, vlan);
                        }
                        success = success && _ipAddrMgr.disassociatePublicIpAddress(ip, user.getId(), caller);
                    } else {
                        ipsInUse.add(ip);
                    }
                }
                if (!success) {
                    logger.warn("Some Public IP addresses that were not in use failed to be released as a part of vlan {} release to the system pool", vlan);
                }
            } finally {
                _vlanDao.releaseFromLockTable(vlanDbId);
            }
        }

        if (isAccountSpecific && _accountVlanMapDao.remove(acctVln.get(0).getId())) {
            for (final IPAddressVO ip : ips) {
                if (!ipsInUse.contains(ip)) {
                    final boolean usageHidden = _ipAddrMgr.isUsageHidden(ip);
                    UsageEventUtils.publishUsageEvent(EventTypes.EVENT_NET_IP_RELEASE, acctVln.get(0).getAccountId(), ip.getDataCenterId(), ip.getId(), ip.getAddress().toString(),
                            ip.isSourceNat(), vlan.getVlanType().toString(), ip.getSystem(), usageHidden, ip.getClass().getName(), ip.getUuid());
                }
            }
            _resourceLimitMgr.decrementResourceCount(acctVln.get(0).getAccountId(), ResourceType.public_ip, (long)ips.size());
            success = true;
        } else if (isDomainSpecific && _domainVlanMapDao.remove(domainVlan.get(0).getId())) {
            logger.debug("Remove the vlan from domain_vlan_map successfully.");
            success = true;
        } else {
            success = false;
        }

        return success;
    }

    @DB
    protected boolean savePublicIPRange(final String startIP, final String endIP, final long zoneId, final long vlanDbId, final long sourceNetworkid, final long physicalNetworkId, final boolean forSystemVms) {
        final long startIPLong = NetUtils.ip2Long(startIP);
        final long endIPLong = NetUtils.ip2Long(endIP);

        final List<String> problemIps = Transaction.execute(new TransactionCallback<>() {
            @Override
            public List<String> doInTransaction(final TransactionStatus status) {
                final IPRangeConfig config = new IPRangeConfig();
                return config.savePublicIPRange(TransactionLegacy.currentTxn(), startIPLong, endIPLong, zoneId, vlanDbId, sourceNetworkid, physicalNetworkId, forSystemVms);
            }
        });

        return CollectionUtils.isEmpty(problemIps);
    }

    @DB
    protected boolean updatePublicIPRange(final String newStartIP, final String currentStartIP, final String newEndIP, final String currentEndIP, final long zoneId, final long vlanDbId, final long sourceNetworkid, final long physicalNetworkId, final boolean isRangeForSystemVM, final Boolean forSystemVms) {
        long newStartIPLong = NetUtils.ip2Long(newStartIP);
        long newEndIPLong = NetUtils.ip2Long(newEndIP);
        long currentStartIPLong = NetUtils.ip2Long(currentStartIP);
        long currentEndIPLong = NetUtils.ip2Long(currentEndIP);

        List<Long> currentIPRange = new ArrayList<>();
        List<Long> newIPRange = new ArrayList<>();
        while (newStartIPLong <= newEndIPLong) {
            newIPRange.add(newStartIPLong);
            newStartIPLong++;
        }
        while (currentStartIPLong <= currentEndIPLong) {
            currentIPRange.add(currentStartIPLong);
            currentStartIPLong++;
        }

        final List<String> problemIps = Transaction.execute(new TransactionCallback<>() {

            @Override
            public List<String> doInTransaction(final TransactionStatus status) {
                final IPRangeConfig config = new IPRangeConfig();
                Vector<String> configResult = new Vector<>();
                List<Long> ipAddressesToAdd = new ArrayList(newIPRange);
                ipAddressesToAdd.removeAll(currentIPRange);
                if (ipAddressesToAdd.size() > 0) {
                    for (Long startIP : ipAddressesToAdd) {
                        configResult.addAll(config.savePublicIPRange(TransactionLegacy.currentTxn(), startIP, startIP, zoneId, vlanDbId, sourceNetworkid, physicalNetworkId, forSystemVms != null ? forSystemVms : isRangeForSystemVM));
                    }
                }
                List<Long> ipAddressesToDelete = new ArrayList(currentIPRange);
                ipAddressesToDelete.removeAll(newIPRange);
                if (ipAddressesToDelete.size() > 0) {
                    for (Long startIP : ipAddressesToDelete) {
                        configResult.addAll(config.deletePublicIPRange(TransactionLegacy.currentTxn(), startIP, startIP, vlanDbId));
                    }
                }
                if (forSystemVms != null && isRangeForSystemVM != forSystemVms) {
                    List<Long> ipAddressesToUpdate = new ArrayList(currentIPRange);
                    ipAddressesToUpdate.removeAll(ipAddressesToDelete);
                    if (ipAddressesToUpdate.size() > 0) {
                        for (Long startIP : ipAddressesToUpdate) {
                            configResult.addAll(config.updatePublicIPRange(TransactionLegacy.currentTxn(), startIP, startIP, vlanDbId, forSystemVms));
                        }
                    }
                }
                return configResult;
            }
        });
        return problemIps != null && problemIps.size() == 0;
    }

    private void checkPublicIpRangeErrors(final long zoneId, final String vlanId, final String vlanGateway, final String vlanNetmask, final String startIP, final String endIP) {
        if (!NetUtils.isValidIp4(startIP)) {
            throw new InvalidParameterValueException("Please specify a valid start IP");
        }

        if (endIP != null && !NetUtils.isValidIp4(endIP)) {
            throw new InvalidParameterValueException("Please specify a valid end IP");
        }

        if (endIP != null && !NetUtils.validIpRange(startIP, endIP)) {
            throw new InvalidParameterValueException("Please specify a valid IP range.");
        }

        if (vlanNetmask == null) {
            throw new InvalidParameterValueException("Please ensure that your IP range's netmask is specified");
        }

        if (endIP != null && !NetUtils.sameSubnet(startIP, endIP, vlanNetmask)) {
            throw new InvalidParameterValueException("Please ensure that your start IP and end IP are in the same subnet, as per the IP range's netmask.");
        }

        if (!NetUtils.sameSubnet(startIP, vlanGateway, vlanNetmask)) {
            throw new InvalidParameterValueException("Please ensure that your start IP is in the same subnet as your IP range's gateway, as per the IP range's netmask.");
        }

        if (endIP != null && !NetUtils.sameSubnet(endIP, vlanGateway, vlanNetmask)) {
            throw new InvalidParameterValueException("Please ensure that your end IP is in the same subnet as your IP range's gateway, as per the IP range's netmask.");
        }
        final String newCidr = NetUtils.getCidrFromGatewayAndNetmask(vlanGateway, vlanNetmask);
        if (!NetUtils.is31PrefixCidr(newCidr)) {
            if (NetUtils.ipRangesOverlap(startIP, endIP, vlanGateway, vlanGateway)) {
                throw new InvalidParameterValueException(
                        "The gateway ip should not be the part of the ip range being added.");
            }
        }
    }

    private void checkConflictsWithPortableIpRange(final long zoneId, final String vlanId, final String vlanGateway, final String vlanNetmask, final String startIP, final String endIP) {
        if (checkOverlapPortableIpRange(_regionDao.getRegionId(), startIP, endIP)) {
            throw new InvalidParameterValueException("Ip range: " + startIP + "-" + endIP + " overlaps with a portable" + " IP range already configured in the region "
                    + _regionDao.getRegionId());
        }

        final List<PortableIpRangeVO> existingPortableIPRanges = _portableIpRangeDao.listByRegionId(_regionDao.getRegionId());
        if (existingPortableIPRanges != null && !existingPortableIPRanges.isEmpty()) {
            for (final PortableIpRangeVO portableIpRange : existingPortableIPRanges) {
                if (NetUtils.isSameIsolationId(portableIpRange.getVlanTag(), vlanId)) {
                    throw new InvalidParameterValueException("The VLAN tag " + vlanId + " is already being used for portable ip range in this region");
                }
            }
        }
    }

    private boolean checkOverlapPortableIpRange(final int regionId, final String newStartIpStr, final String newEndIpStr) {
        final long newStartIp = NetUtils.ip2Long(newStartIpStr);
        final long newEndIp = NetUtils.ip2Long(newEndIpStr);

        final List<PortableIpRangeVO> existingPortableIPRanges = _portableIpRangeDao.listByRegionId(regionId);

        if (existingPortableIPRanges == null || existingPortableIPRanges.isEmpty()) {
            return false;
        }

        for (final PortableIpRangeVO portableIpRange : existingPortableIPRanges) {
            final String ipRangeStr = portableIpRange.getIpRange();
            final String[] range = ipRangeStr.split("-");
            final long startip = NetUtils.ip2Long(range[0]);
            final long endIp = NetUtils.ip2Long(range[1]);

            if (newStartIp >= startip && newStartIp <= endIp || newEndIp >= startip && newEndIp <= endIp) {
                return true;
            }

            if (startip >= newStartIp && startip <= newEndIp || endIp >= newStartIp && endIp <= newEndIp) {
                return true;
            }
        }
        return false;
    }

    private String getCidrAddress(final String cidr) {
        final String[] cidrPair = cidr.split("\\/");
        return cidrPair[0];
    }

    private int getCidrSize(final String cidr) {
        final String[] cidrPair = cidr.split("\\/");
        return Integer.parseInt(cidrPair[1]);
    }

    @Override
    public void checkPodCidrSubnets(final long dcId, final Long podIdToBeSkipped, final String cidr) {
        long skipPod = 0;
        if (podIdToBeSkipped != null) {
            skipPod = podIdToBeSkipped;
        }
        final HashMap<Long, List<Object>> currentPodCidrSubnets = _podDao.getCurrentPodCidrSubnets(dcId, skipPod);
        final List<Object> newCidrPair = new ArrayList<>();
        newCidrPair.add(0, getCidrAddress(cidr));
        newCidrPair.add(1, (long)getCidrSize(cidr));
        currentPodCidrSubnets.put(-1L, newCidrPair);

        final DataCenterVO dcVo = _zoneDao.findById(dcId);
        final String guestNetworkCidr = dcVo.getGuestNetworkCidr();

        String guestIpNetwork = null;
        Long guestCidrSize = null;
        if (guestNetworkCidr != null) {
            final String[] cidrTuple = guestNetworkCidr.split("\\/");
            guestIpNetwork = NetUtils.getIpRangeStartIpFromCidr(cidrTuple[0], Long.parseLong(cidrTuple[1]));
            guestCidrSize = Long.parseLong(cidrTuple[1]);
        }

        final String zoneName = getZoneName(dcId);

        for (final Long podId : currentPodCidrSubnets.keySet()) {
            String podName;
            if (podId.longValue() == -1) {
                podName = "newPod";
            } else {
                podName = getPodName(podId.longValue());
            }

            final List<Object> cidrPair = currentPodCidrSubnets.get(podId);
            final String cidrAddress = (String)cidrPair.get(0);
            final long cidrSize = ((Long)cidrPair.get(1)).longValue();

            long cidrSizeToUse = -1;
            if (guestCidrSize == null || cidrSize < guestCidrSize) {
                cidrSizeToUse = cidrSize;
            } else {
                cidrSizeToUse = guestCidrSize;
            }

            String cidrSubnet = NetUtils.getCidrSubNet(cidrAddress, cidrSizeToUse);

            if (guestNetworkCidr != null) {
                final String guestSubnet = NetUtils.getCidrSubNet(guestIpNetwork, cidrSizeToUse);
                if (cidrSubnet.equals(guestSubnet)) {
                    if (podName.equals("newPod")) {
                        throw new InvalidParameterValueException(
                                "The subnet of the pod you are adding conflicts with the subnet of the Guest IP Network. Please specify a different CIDR.");
                    } else {
                        throw new InvalidParameterValueException(
                                "Warning: The subnet of pod "
                                        + podName
                                        + " in zone "
                                        + zoneName
                                        + " conflicts with the subnet of the Guest IP Network. Please change either the pod's CIDR or the Guest IP Network's subnet, and re-run install-vmops-management.");
                    }
                }
            }

            for (final Long otherPodId : currentPodCidrSubnets.keySet()) {
                if (podId.equals(otherPodId)) {
                    continue;
                }

                final List<Object> otherCidrPair = currentPodCidrSubnets.get(otherPodId);
                final String otherCidrAddress = (String)otherCidrPair.get(0);
                final long otherCidrSize = ((Long)otherCidrPair.get(1)).longValue();

                if (cidrSize < otherCidrSize) {
                    cidrSizeToUse = cidrSize;
                } else {
                    cidrSizeToUse = otherCidrSize;
                }

                cidrSubnet = NetUtils.getCidrSubNet(cidrAddress, cidrSizeToUse);
                final String otherCidrSubnet = NetUtils.getCidrSubNet(otherCidrAddress, cidrSizeToUse);

                if (cidrSubnet.equals(otherCidrSubnet)) {
                    final String otherPodName = getPodName(otherPodId.longValue());
                    if (podName.equals("newPod")) {
                        throw new InvalidParameterValueException("The subnet of the pod you are adding conflicts with the subnet of pod " + otherPodName + " in zone " + zoneName
                                + ". Please specify a different CIDR.");
                    } else {
                        throw new InvalidParameterValueException("Warning: The pods " + podName + " and " + otherPodName + " in zone " + zoneName
                                + " have conflicting CIDR subnets. Please change the CIDR of one of these pods.");
                    }
                }
            }
        }

    }

    private String getPodName(final long podId) {
        return _podDao.findById(podId).getName();
    }

    private String getZoneName(final long zoneId) {
        final DataCenterVO zone = _zoneDao.findById(zoneId);
        if (zone != null) {
            return zone.getName();
        } else {
            return null;
        }
    }

    @Override
    @ActionEvent(eventType = EventTypes.EVENT_VLAN_IP_RANGE_DELETE, eventDescription = "deleting vlan ip range", async = false)
    public boolean deleteVlanIpRange(final DeleteVlanIpRangeCmd cmd) {
        final Long vlanDbId = cmd.getId();

        final VlanVO vlan = _vlanDao.findById(vlanDbId);
        if (vlan == null) {
            throw new InvalidParameterValueException("Please specify a valid IP range id.");
        }

        return deleteAndPublishVlanAndPublicIpRange(CallContext.current().getCallingUserId(), vlanDbId, CallContext.current().getCallingAccount());
    }

    private boolean deleteAndPublishVlanAndPublicIpRange(final long userId, final long vlanDbId, final Account caller) {
        VlanVO deletedVlan = deleteVlanAndPublicIpRange(userId, vlanDbId, caller);
        if (deletedVlan != null) {
            final boolean ipv4 = deletedVlan.getVlanGateway() != null;
            final boolean ipv6 = deletedVlan.getIp6Gateway() != null;
            final long networkId = deletedVlan.getNetworkId();
            final NetworkVO networkVO = _networkDao.findById(networkId);

            if (networkVO != null && networkVO.getTrafficType() != TrafficType.Public) {
                if (ipv4) {
                    removeCidrAndGatewayForIpv4(networkId, deletedVlan);
                } else if (ipv6) {
                    removeCidrAndGatewayForIpv6(networkId, deletedVlan);
                }
            }

            messageBus.publish(SENDER, com.cloud.configuration.ConfigurationManager.MESSAGE_DELETE_VLAN_IP_RANGE_EVENT, PublishScope.LOCAL, deletedVlan);
            return true;
        }
        return false;
    }

    private void removeCidrAndGatewayForIpv4(final long networkId, VlanVO deletedVlan) {
        final NetworkVO networkVO = _networkDao.findById(networkId);
        String networkCidr = networkVO.getCidr();
        String cidrToRemove = NetUtils.getCidrFromGatewayAndNetmask(deletedVlan.getVlanGateway(), deletedVlan.getVlanNetmask());
        String newNetworkCidr = com.cloud.utils.StringUtils.updateCommaSeparatedStringWithValue(networkCidr, cidrToRemove, false);
        networkVO.setCidr(newNetworkCidr);

        String networkGateway = networkVO.getGateway();
        String newNetworkGateway = com.cloud.utils.StringUtils.updateCommaSeparatedStringWithValue(networkGateway, deletedVlan.getVlanGateway(), false);
        networkVO.setGateway(newNetworkGateway);
        _networkDao.update(networkId, networkVO);
    }

    private void removeCidrAndGatewayForIpv6(final long networkId, VlanVO deletedVlan) {
        final NetworkVO networkVO = _networkDao.findById(networkId);
        String networkIp6Cidr = networkVO.getIp6Cidr();
        String newNetworkIp6Cidr = com.cloud.utils.StringUtils.updateCommaSeparatedStringWithValue(networkIp6Cidr, deletedVlan.getIp6Cidr(), false);
        networkVO.setIp6Cidr(newNetworkIp6Cidr);

        String networkIp6Gateway = networkVO.getIp6Gateway();
        String newNetworkIp6Gateway = com.cloud.utils.StringUtils.updateCommaSeparatedStringWithValue(networkIp6Gateway, deletedVlan.getIp6Gateway(), false);
        networkVO.setIp6Gateway(newNetworkIp6Gateway);
        _networkDao.update(networkId, networkVO);
    }

    @Override
    public Account getVlanAccount(final long vlanId) {
        final Vlan vlan = _vlanDao.findById(vlanId);

        if (vlan.getVlanType() == VlanType.VirtualNetwork) {
            final List<AccountVlanMapVO> maps = _accountVlanMapDao.listAccountVlanMapsByVlan(vlanId);
            if (maps != null && !maps.isEmpty()) {
                return _accountMgr.getAccount(maps.get(0).getAccountId());
            }
        }

        return null;
    }

    @Override
    public Domain getVlanDomain(long vlanId) {
        Vlan vlan = _vlanDao.findById(vlanId);

        if (vlan.getVlanType() == VlanType.VirtualNetwork) {
            List<DomainVlanMapVO> maps = _domainVlanMapDao.listDomainVlanMapsByVlan(vlanId);
            if (maps != null && !maps.isEmpty()) {
                return _domainDao.findById(maps.get(0).getDomainId());
            }
        }

        return null;
    }

    @Override
    @DB
    public boolean releaseDomainSpecificVirtualRanges(final Domain domain) {
        final List<DomainVlanMapVO> maps = _domainVlanMapDao.listDomainVlanMapsByDomain(domain.getId());
        if (CollectionUtils.isNotEmpty(maps)) {
            try {
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(final TransactionStatus status) {
                        for (DomainVlanMapVO map : maps) {
                            if (!releasePublicIpRange(map.getVlanDbId(), _accountMgr.getSystemUser(), _accountMgr.getAccount(Account.ACCOUNT_ID_SYSTEM))) {
                                throw new CloudRuntimeException(String.format("Failed to release domain specific virtual ip ranges for domain %s", domain));
                            }
                        }
                    }
                });
            } catch (final CloudRuntimeException e) {
                logger.error(e);
                return false;
            }
        } else {
            logger.trace("Domain {} has no domain specific virtual ip ranges, nothing to release", domain);
        }
        return true;
    }

    @Override
    @DB
    public boolean releaseAccountSpecificVirtualRanges(final Account account) {
        final List<AccountVlanMapVO> maps = _accountVlanMapDao.listAccountVlanMapsByAccount(account.getId());
        if (maps != null && !maps.isEmpty()) {
            try {
                Transaction.execute(new TransactionCallbackNoReturn() {
                    @Override
                    public void doInTransactionWithoutResult(final TransactionStatus status) {
                        for (final AccountVlanMapVO map : maps) {
                            if (!releasePublicIpRange(map.getVlanDbId(), _accountMgr.getSystemUser(), _accountMgr.getAccount(Account.ACCOUNT_ID_SYSTEM))) {
                                throw new CloudRuntimeException(String.format("Failed to release account specific virtual ip ranges for account %s", account));
                            }
                        }
                    }
                });
            } catch (final CloudRuntimeException e) {
                logger.error(e);
                return false;
            }
        } else {
            logger.trace("Account {} has no account specific virtual ip ranges, nothing to release", account);
        }
        return true;
    }

    // --- Helpers duplicated from ConfigurationManagerImpl ------------------

    protected void checkIpRange(final String startIp, final String endIp, final String cidrAddress, final long cidrSize) {
        if (StringUtils.isNotEmpty(startIp) && !NetUtils.isValidIp4(startIp)) {
            throw new InvalidParameterValueException("The start address of the IP range is not a valid IP address.");
        }

        if (StringUtils.isNotEmpty(endIp) && !NetUtils.isValidIp4(endIp)) {
            throw new InvalidParameterValueException("The end address of the IP range is not a valid IP address.");
        }

        if (StringUtils.isNotEmpty(startIp) && !NetUtils.getCidrSubNet(startIp, cidrSize).equalsIgnoreCase(NetUtils.getCidrSubNet(cidrAddress, cidrSize))) {
            throw new InvalidParameterValueException("The start address of the IP range is not in the CIDR subnet.");
        }

        if (StringUtils.isNotEmpty(endIp) && !NetUtils.getCidrSubNet(endIp, cidrSize).equalsIgnoreCase(NetUtils.getCidrSubNet(cidrAddress, cidrSize))) {
            throw new InvalidParameterValueException("The end address of the IP range is not in the CIDR subnet.");
        }

        if (StringUtils.isNotEmpty(endIp) && NetUtils.ip2Long(startIp) > NetUtils.ip2Long(endIp)) {
            throw new InvalidParameterValueException("The start IP address must have a lower value than the end IP address.");
        }
    }

    protected void checkOverlapPrivateIpRange(final Long zoneId, final String startIp, final String endIp) {
        final List<com.cloud.dc.HostPodVO> podsInZone = _podDao.listByDataCenterId(zoneId);
        for (final com.cloud.dc.HostPodVO hostPod : podsInZone) {
            final String[] existingPodIpRanges = hostPod.getDescription().split(",");

            for(String podIpRange: existingPodIpRanges) {
                final String[] existingPodIpRange = podIpRange.split("-");

                if (existingPodIpRange.length > 1) {
                    if (!NetUtils.isValidIp4(existingPodIpRange[0]) || !NetUtils.isValidIp4(existingPodIpRange[1])) {
                        continue;
                    }

                    if (NetUtils.ipRangesOverlap(startIp, endIp, existingPodIpRange[0], existingPodIpRange[1])) {
                        throw new InvalidParameterValueException("The Start IP and EndIP address range overlap with private IP :" + existingPodIpRange[0] + ":" + existingPodIpRange[1]);
                    }
                }
            }
        }
    }

    protected void checkZoneAccess(final Account caller, final DataCenter zone) {
        if (_secChecker == null) {
            return;
        }
        for (final SecurityChecker checker : _secChecker) {
            if (checker.checkAccess(caller, zone)) {
                if (logger.isDebugEnabled()) {
                    logger.debug("Access granted to {} to zone:{} by {}", caller, zone, checker.getName());
                }
                return;
            } else {
                throw new PermissionDeniedException(String.format("Access denied to %s by %s for zone %s", caller, checker.getName(), zone));
            }
        }

        throw new PermissionDeniedException(String.format("There's no way to confirm %s has access to zone:%s", caller, zone));
    }
}
