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

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import jakarta.inject.Inject;

import org.apache.cloudstack.api.ApiConstants;
import org.apache.cloudstack.framework.config.dao.ConfigurationDao;
import org.apache.cloudstack.network.RoutedIpv4Manager;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.stereotype.Component;

import com.cloud.configuration.Config;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.PVlanType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.NsxProviderDao;
import com.cloud.network.dao.PhysicalNetworkDao;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.element.NsxProviderVO;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcManager;
import com.cloud.offering.NetworkOffering;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.utils.Pair;
import com.cloud.utils.exception.CloudRuntimeException;
import com.cloud.utils.net.NetUtils;

@Component
public class NetworkCreationValidationServiceImpl implements NetworkCreationValidationService {

    @Inject
    protected DataCenterDao _dcDao;
    @Inject
    protected NetworkDao _networksDao;
    @Inject
    protected PhysicalNetworkDao _physicalNetworkDao;
    @Inject
    protected ConfigurationDao _configDao;
    @Inject
    protected RoutedIpv4Manager routedIpv4Manager;
    @Inject
    protected AccountManager _accountMgr;
    @Inject
    protected NsxProviderDao nsxProviderDao;

    protected Logger logger = LogManager.getLogger(getClass());

    @Override
    public void checkSharedNetworkCidrOverlap(Long zoneId, long physicalNetworkId, String cidr) {
        if (zoneId == null || cidr == null) {
            return;
        }

        DataCenter zone = _dcDao.findById(zoneId);
        List<NetworkVO> networks = _networksDao.listByZone(zoneId);
        Map<Long, String> networkToCidr = new HashMap<Long, String>();

        // check for CIDR overlap with all possible CIDR for isolated guest networks
        // in the zone when using external networking
        PhysicalNetworkVO pNetwork = _physicalNetworkDao.findById(physicalNetworkId);
        if (pNetwork.getVnet() != null) {
            List<Pair<Integer, Integer>> vlanList = pNetwork.getVnet();
            for (Pair<Integer, Integer> vlanRange : vlanList) {
                Integer lowestVlanTag = vlanRange.first();
                Integer highestVlanTag = vlanRange.second();
                for (int vlan = lowestVlanTag; vlan <= highestVlanTag; ++vlan) {
                    int offset = vlan - lowestVlanTag;
                    String globalVlanBits = _configDao.getValue(Config.GuestVlanBits.key());
                    int cidrSize = 8 + Integer.parseInt(globalVlanBits);
                    String guestNetworkCidr = zone.getGuestNetworkCidr();
                    String[] cidrTuple = guestNetworkCidr.split("\\/");
                    long newCidrAddress = (NetUtils.ip2Long(cidrTuple[0]) & 0xff000000) | (offset << (32 - cidrSize));
                    if (NetUtils.isNetworksOverlap(NetUtils.long2Ip(newCidrAddress), cidr)) {
                        throw new InvalidParameterValueException("Specified CIDR for shared network conflict with CIDR that is reserved for zone vlan " + vlan);
                    }
                }
            }
        }

        // check for CIDR overlap with all CIDR's of the shared networks in the zone
        for (NetworkVO network : networks) {
            if (network.getGuestType() == GuestType.Isolated) {
                continue;
            }
            if (network.getCidr() != null) {
                networkToCidr.put(network.getId(), network.getCidr());
            }
        }
        if (networkToCidr != null && !networkToCidr.isEmpty()) {
            for (long networkId : networkToCidr.keySet()) {
                String ntwkCidr = networkToCidr.get(networkId);
                if (NetUtils.isNetworksOverlap(ntwkCidr, cidr)) {
                    throw new InvalidParameterValueException("Specified CIDR for shared network conflict with CIDR of a shared network in the zone.");
                }
            }
        }
    }

    @Override
    public void validateNetworkCidrSize(Account caller, Integer cidrSize, String cidr, NetworkOffering networkOffering, long accountId, long zoneId) {
        if (!GuestType.Isolated.equals(networkOffering.getGuestType())) {
            if (cidrSize != null) {
                throw new InvalidParameterValueException("network cidr size is only applicable on Isolated networks");
            }
            return;
        }
        if (ObjectUtils.allNotNull(cidr, cidrSize)) {
            throw new InvalidParameterValueException("network cidr and cidr size are mutually exclusive");
        }
        if (NetworkOffering.NetworkMode.ROUTED.equals(networkOffering.getNetworkMode())
                && routedIpv4Manager.isVirtualRouterGateway(networkOffering)) {
            if (cidr != null) {
                if (!networkOffering.isForVpc() && !_accountMgr.isRootAdmin(caller.getId())) {
                    throw new InvalidParameterValueException("Only root admin can set the gateway/netmask of Isolated networks with ROUTED mode");
                }
                return;
            }
            if (cidrSize == null) {
                throw new InvalidParameterValueException("network cidr or cidr size is required for Isolated networks with ROUTED mode");
            }
            Integer maxCidrSize = RoutedIpv4Manager.RoutedNetworkIPv4MaxCidrSize.valueIn(accountId);
            if (cidrSize > maxCidrSize) {
                throw new InvalidParameterValueException("network cidr size cannot be bigger than maximum cidr size " + maxCidrSize);
            }
            Integer minCidrSize = RoutedIpv4Manager.RoutedNetworkIPv4MinCidrSize.valueIn(accountId);
            if (cidrSize < minCidrSize) {
                throw new InvalidParameterValueException("network cidr size cannot be smaller than minimum cidr size " + minCidrSize);
            }
        } else if (cidrSize != null) {
            throw new InvalidParameterValueException("network cidr size is only applicable on Isolated networks with ROUTED mode: " + cidrSize);
        }
    }

    @Override
    public void validateSharedNetworkRouterIPs(String gateway, String startIP, String endIP, String netmask, String routerIPv4, String routerIPv6,
            String startIPv6, String endIPv6, String ip6Cidr, NetworkOffering ntwkOff) {
        if (ntwkOff.getGuestType() == GuestType.Shared) {
            validateSharedNetworkRouterIPv4(routerIPv4, startIP, endIP, gateway, netmask);
            validateSharedNetworkRouterIPv6(routerIPv6, startIPv6, endIPv6, ip6Cidr);
        }
    }

    @Override
    public String getVpcPrependedNetworkName(String networkName, Vpc vpc) {
        final String delimiter = VpcManager.VpcTierNamePrependDelimiter.value();
        return vpc.getName() + delimiter + networkName;
    }

    @Override
    public boolean isNonVpcNetworkSupportingDynamicRouting(NetworkOffering networkOffering) {
        return !networkOffering.isForVpc() && NetworkOffering.RoutingMode.Dynamic == networkOffering.getRoutingMode();
    }

    @Override
    public void validateNetworkCreationSupported(long zoneId, String zoneName, GuestType guestType) {
        NsxProviderVO nsxProviderVO = nsxProviderDao.findByZoneId(zoneId);
        if (Objects.nonNull(nsxProviderVO) && GuestType.L2.equals(guestType)) {
            throw new InvalidParameterValueException(
                    String.format("Creation of %s networks is not supported in NSX enabled zone %s", guestType.name(), zoneName)
            );
        }
    }

    @Override
    public boolean getAndValidateSupportForKeepMacAddressOnPublicNicParameter(Boolean keepMacAddressOnPublicNic, NetworkOffering networkOffering) {
        if (networkOffering.isForVpc() && keepMacAddressOnPublicNic != null) {
            throw new InvalidParameterValueException(
                    String.format("The [%s] parameter cannot be specified on the creation of VPC tiers.", ApiConstants.KEEP_MAC_ADDRESS_ON_PUBLIC_NIC)
            );
        }

        GuestType guestType = networkOffering.getGuestType();
        if (guestType != GuestType.Isolated && keepMacAddressOnPublicNic != null) {
            throw new InvalidParameterValueException(String.format(
                    "The [%s] parameter can only be specified on the creation of [%s] networks.",
                    ApiConstants.KEEP_MAC_ADDRESS_ON_PUBLIC_NIC, GuestType.Isolated
            ));
        }

        return keepMacAddressOnPublicNic == null || keepMacAddressOnPublicNic;
    }

    @Override
    public void validateNetworkOfferingForNonRootAdminUser(NetworkOffering ntwkOff) {
        if (ntwkOff.getTrafficType() != TrafficType.Guest) {
            throw new InvalidParameterValueException("This user can only create a Guest network");
        }
        if (ntwkOff.getGuestType() == GuestType.L2 || ntwkOff.getGuestType() == GuestType.Isolated) {
            logger.debug(String.format("Creating a network from network offerings having traffic type [%s] and network type [%s].",
                    TrafficType.Guest, ntwkOff.getGuestType()));
        } else if (ntwkOff.getGuestType() == GuestType.Shared && ! ntwkOff.isSpecifyVlan()) {
            logger.debug(String.format("Creating a network from network offerings having traffic type [%s] and network type [%s] with specifyVlan=%s.",
                    TrafficType.Guest, GuestType.Shared, ntwkOff.isSpecifyVlan()));
        } else {
            throw new InvalidParameterValueException(
                    String.format("This user can only create an %s network, a %s network or a %s network with specifyVlan=false.", GuestType.Isolated, GuestType.L2, GuestType.Shared));
        }
    }

    @Override
    public Pair<String, PVlanType> getPrivateVlanPair(String pvlanId, String pvlanTypeStr, String vlanId) {
        String secondaryVlanId = pvlanId;
        PVlanType type = null;

        if (StringUtils.isNotBlank(pvlanTypeStr)) {
            PVlanType providedType = PVlanType.fromValue(pvlanTypeStr);
            type = providedType;
        } else if (StringUtils.isNoneBlank(vlanId, secondaryVlanId)) {
            // Preserve the existing functionality
            type = vlanId.equals(secondaryVlanId) ? PVlanType.Promiscuous : PVlanType.Isolated;
        }

        if (StringUtils.isBlank(secondaryVlanId) && type == PVlanType.Promiscuous) {
            secondaryVlanId = vlanId;
        }

        if (StringUtils.isNotBlank(secondaryVlanId)) {
            try {
                Integer.parseInt(secondaryVlanId);
            } catch (NumberFormatException e) {
                throw new CloudRuntimeException("The secondary VLAN ID: " + secondaryVlanId + " is not in numeric format", e);
            }
        }

        return new Pair<>(secondaryVlanId, type);
    }

    @Override
    public void performBasicPrivateVlanChecks(String vlanId, String secondaryVlanId, PVlanType privateVlanType) {
        if (StringUtils.isNotBlank(vlanId) && StringUtils.isBlank(secondaryVlanId) && privateVlanType != null && privateVlanType != PVlanType.Promiscuous) {
            throw new InvalidParameterValueException("Private VLAN ID has not been set, therefore Promiscuous type is expected");
        } else if (StringUtils.isNoneBlank(vlanId, secondaryVlanId) && !vlanId.equalsIgnoreCase(secondaryVlanId) && privateVlanType == PVlanType.Promiscuous) {
            throw new InvalidParameterValueException("Private VLAN type is set to Promiscuous, but VLAN ID and Secondary VLAN ID differ");
        } else if (StringUtils.isNoneBlank(vlanId, secondaryVlanId) && privateVlanType != null && privateVlanType != PVlanType.Promiscuous && vlanId.equalsIgnoreCase(secondaryVlanId)) {
            throw new InvalidParameterValueException("Private VLAN type is set to " + privateVlanType + ", but VLAN ID and Secondary VLAN ID are equal");
        }
    }

    private void validateSharedNetworkRouterIPv4(String routerIp, String startIp, String endIp, String gateway, String netmask) {
        if (StringUtils.isNotBlank(routerIp)) {
            if (startIp != null && endIp == null) {
                endIp = startIp;
            }
            isIPv4AddressValid(routerIp);
            if (StringUtils.isNoneBlank(startIp, endIp)) {
                if (!NetUtils.isIpInRange(routerIp, startIp, endIp)) {
                    throw new CloudRuntimeException("Router IPv4 IP provided is not within the specified range: " + startIp + " - " + endIp);
                }
            } else {
                String cidr = NetUtils.ipAndNetMaskToCidr(gateway, netmask);
                if (!NetUtils.isIpWithInCidrRange(routerIp, cidr)) {
                    throw new CloudRuntimeException("Router IP provided in not within the network range");
                }
            }
        }
    }

    private void validateSharedNetworkRouterIPv6(String routerIPv6, String startIPv6, String endIPv6, String cidrIPv6) {
        if (StringUtils.isNotBlank(routerIPv6)) {
            if (startIPv6 != null && endIPv6 == null) {
                endIPv6 = startIPv6;
            }
            isIPv6AddressValid(routerIPv6);
            if (StringUtils.isNoneBlank(startIPv6, endIPv6)) {
                String ipv6Range = startIPv6 + "-" + endIPv6;
                if (!NetUtils.isIp6InRange(routerIPv6, ipv6Range)) {
                    throw new CloudRuntimeException("Router IPv6 address provided is not within the specified range: " + startIPv6 + " - " + endIPv6);
                }
            } else {
                if (!NetUtils.isIp6InNetwork(routerIPv6, cidrIPv6)) {
                    throw new CloudRuntimeException("Router IPv6 address provided is not with the network range");
                }
            }
        }
    }

    private void isIPv4AddressValid(String routerIp) {
        if (!NetUtils.isValidIp4(routerIp)) {
            throw new CloudRuntimeException("Router IPv4 IP provided is of incorrect format");
        }
    }

    private void isIPv6AddressValid(String routerIPv6) {
        if (!NetUtils.isValidIp6(routerIPv6)) {
            throw new CloudRuntimeException("Router IPv6 address provided is of incorrect format");
        }
    }
}
