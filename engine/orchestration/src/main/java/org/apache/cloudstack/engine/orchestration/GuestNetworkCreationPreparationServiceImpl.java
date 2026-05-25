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
package org.apache.cloudstack.engine.orchestration;

import java.net.URI;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.apache.cloudstack.engine.orchestration.service.NetworkOrchestrationService;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import com.cloud.configuration.ConfigurationManager;
import com.cloud.dc.DataCenter.NetworkType;
import com.cloud.dc.DataCenterVO;
import com.cloud.dc.Vlan;
import com.cloud.dc.dao.DataCenterDao;
import com.cloud.deploy.DataCenterDeployment;
import com.cloud.domain.Domain;
import com.cloud.exception.InvalidParameterValueException;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.GuestType;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkModel;
import com.cloud.network.NetworkService;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.Networks.TrafficType;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.NetworkOfferingVO;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.user.Account;
import com.cloud.utils.Pair;
import com.cloud.utils.UuidUtils;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.net.NetUtils;

@Component
public class GuestNetworkCreationPreparationServiceImpl implements GuestNetworkCreationPreparationService {

    @Inject
    protected NetworkOfferingDao networkOfferingDao;
    @Inject
    protected DataCenterDao dcDao;
    @Inject
    protected NetworkDao networksDao;
    @Inject
    protected NetworkModel networkModel;
    @Inject
    protected EntityManager entityMgr;
    @Inject
    protected NetworkOfferingVlanValidationService networkOfferingVlanValidationService;

    @Override
    public GuestNetworkCreationPreparation prepareGuestNetworkCreation(final long networkOfferingId, final String gateway, final String cidr, String vlanId,
            boolean bypassVlanOverlapCheck, String networkDomain, final Account owner, final Long domainId, final PhysicalNetwork physicalNetwork,
            final long zoneId, final ACLType aclType, Boolean subdomainAccess, final String ip6Gateway, final String ip6Cidr, final String isolatedPvlan,
            Network.PVlanType isolatedPvlanType, String externalId, final Boolean isPrivateNetwork, String routerIp, String routerIpv6, final String ip4Dns1,
            final String ip4Dns2, final String ip6Dns1, final String ip6Dns2, Pair<Integer, Integer> vrIfaceMTUs, Integer networkCidrSize,
            boolean keepMacAddressOnPublicNic) {
        final NetworkOfferingVO networkOffering = networkOfferingDao.findById(networkOfferingId);
        final DataCenterVO zone = dcDao.findById(zoneId);

        if (networkOffering.getTrafficType() != TrafficType.Guest) {
            return null;
        }

        validateNetworkOffering(networkOffering);
        validatePhysicalNetwork(physicalNetwork);

        boolean ipv6 = false;
        if (StringUtils.isNoneBlank(ip6Gateway, ip6Cidr)) {
            ipv6 = true;
        }

        if (zone.getNetworkType() == NetworkType.Basic) {
            subdomainAccess = validateAndNormalizeBasicZone(networkOffering, zone, vlanId, domainId, aclType, subdomainAccess);
            if (vlanId == null) {
                vlanId = Vlan.UNTAGGED;
            }
        } else if (zone.getNetworkType() == NetworkType.Advanced) {
            validateAdvancedZone(networkOffering, zone, isolatedPvlan);
        }

        if (ipv6 && !GuestType.Shared.equals(networkOffering.getGuestType())) {
            networkModel.checkIp6CidrSizeEqualTo64(ip6Cidr);
        }

        networkOfferingVlanValidationService.validateGuestNetworkOfferingVlan(vlanId, isolatedPvlan, bypassVlanOverlapCheck, networkOffering, physicalNetwork, zone, zoneId,
                owner, isPrivateNetwork);

        networkDomain = normalizeNetworkDomain(networkOfferingId, networkDomain, owner, domainId, zoneId, aclType, zone);

        validateCidrRequirements(networkOffering, zone, cidr, ip6Cidr);
        networkOfferingVlanValidationService.checkL2OfferingServices(networkOffering);
        validateBasicZoneCidr(zone, cidr);
        validateGuestCidr(networkOffering, cidr);

        Long physicalNetworkId = null;
        if (physicalNetwork != null) {
            physicalNetworkId = physicalNetwork.getId();
        }
        final DataCenterDeployment plan = new DataCenterDeployment(zoneId, null, null, null, null, physicalNetworkId);
        final NetworkVO userNetwork = buildPredefinedNetwork(networkDomain, gateway, cidr, vlanId, ip6Gateway, ip6Cidr, isolatedPvlan, isolatedPvlanType, externalId,
                routerIp, routerIpv6, ip4Dns1, ip4Dns2, ip6Dns1, ip6Dns2, vrIfaceMTUs, networkCidrSize, keepMacAddressOnPublicNic, physicalNetwork, physicalNetworkId, zone);

        return new GuestNetworkCreationPreparation(networkOffering, zone, networkDomain, subdomainAccess, plan, userNetwork);
    }

    protected void validateNetworkOffering(final NetworkOfferingVO networkOffering) {
        if (networkOffering.getState() != NetworkOffering.State.Enabled) {
            final InvalidParameterValueException ex = new InvalidParameterValueException(
                    "Can't use specified network offering id as its state is not " + NetworkOffering.State.Enabled);
            ex.addProxyObject(networkOffering.getUuid(), "networkOfferingId");
            throw ex;
        }
    }

    protected void validatePhysicalNetwork(final PhysicalNetwork physicalNetwork) {
        if (physicalNetwork.getState() != PhysicalNetwork.State.Enabled) {
            final InvalidParameterValueException ex = new InvalidParameterValueException("Specified physical network id is" + " in incorrect state:" + physicalNetwork.getState());
            ex.addProxyObject(physicalNetwork.getUuid(), "physicalNetworkId");
            throw ex;
        }
    }

    protected Boolean validateAndNormalizeBasicZone(final NetworkOfferingVO networkOffering, final DataCenterVO zone, final String vlanId, final Long domainId,
            final ACLType aclType, Boolean subdomainAccess) {
        if (aclType == null || aclType != ACLType.Domain) {
            throw new InvalidParameterValueException("Only AclType=Domain can be specified for network creation in Basic zone");
        }

        final List<NetworkVO> guestNetworks = networksDao.listByZoneAndTrafficType(zone.getId(), TrafficType.Guest);
        if (!guestNetworks.isEmpty()) {
            throw new InvalidParameterValueException("Can't have more than one Guest network in zone with network type " + NetworkType.Basic);
        }

        if (!(networkOffering.getGuestType() == GuestType.Shared && !networkModel.areServicesSupportedByNetworkOffering(networkOffering.getId(), Service.SourceNat))) {
            throw new InvalidParameterValueException("For zone of type " + NetworkType.Basic + " only offerings of " + "guestType " + GuestType.Shared + " with disabled "
                    + Service.SourceNat.getName() + " service are allowed");
        }

        if (domainId == null || domainId != Domain.ROOT_DOMAIN) {
            throw new InvalidParameterValueException("Guest network in Basic zone should be dedicated to ROOT domain");
        }

        if (subdomainAccess == null) {
            subdomainAccess = true;
        } else if (!subdomainAccess) {
            throw new InvalidParameterValueException("Subdomain access should be set to true for the" + " guest network in the Basic zone");
        }

        if (vlanId != null && !vlanId.equalsIgnoreCase(Vlan.UNTAGGED)) {
            throw new InvalidParameterValueException("Only vlan " + Vlan.UNTAGGED + " can be created in " + "the zone of type " + NetworkType.Basic);
        }
        return subdomainAccess;
    }

    protected void validateAdvancedZone(final NetworkOfferingVO networkOffering, final DataCenterVO zone, final String isolatedPvlan) {
        if (zone.isSecurityGroupEnabled()) {
            if (isolatedPvlan != null) {
                throw new InvalidParameterValueException("Isolated Private VLAN is not supported with security group!");
            }
            if ((networkOffering.getGuestType() != GuestType.Shared) && (networkOffering.getGuestType() != GuestType.L2)) {
                throw new InvalidParameterValueException("Only shared or L2 guest network can be created in security group enabled zone");
            }
            if (networkModel.areServicesSupportedByNetworkOffering(networkOffering.getId(), Service.SourceNat)) {
                throw new InvalidParameterValueException("Service SourceNat is not allowed in security group enabled zone");
            }
        }

        if (networkOffering.isElasticIp() || networkOffering.isElasticLb()) {
            throw new InvalidParameterValueException("Elastic IP and Elastic LB services are supported in zone of type " + NetworkType.Basic);
        }
    }

    protected String normalizeNetworkDomain(final long networkOfferingId, String networkDomain, final Account owner, final Long domainId, final long zoneId,
            final ACLType aclType, final DataCenterVO zone) {
        if (networkModel.areServicesSupportedByNetworkOffering(networkOfferingId, Service.Dns)) {
            final Map<Network.Capability, String> dnsCapabilities = networkModel.getNetworkOfferingServiceCapabilities(entityMgr.findById(NetworkOffering.class, networkOfferingId),
                    Service.Dns);
            final String isUpdateDnsSupported = dnsCapabilities.get(Capability.AllowDnsSuffixModification);
            if (isUpdateDnsSupported == null || !Boolean.valueOf(isUpdateDnsSupported)) {
                if (networkDomain != null) {
                    throw new InvalidParameterValueException(String.format("Domain name change is not supported by network offering id=%d in zone %s", networkOfferingId, zone));
                }
            } else {
                if (networkDomain == null) {
                    if (aclType == ACLType.Domain) {
                        networkDomain = networkModel.getDomainNetworkDomain(domainId, zoneId);
                    } else if (aclType == ACLType.Account) {
                        networkDomain = networkModel.getAccountNetworkDomain(owner.getId(), zoneId);
                    }

                    if (networkDomain == null) {
                        networkDomain = "cs" + Long.toHexString(owner.getId()) + NetworkOrchestrationService.GuestDomainSuffix.valueIn(zoneId);
                    }
                } else {
                    if (!NetUtils.verifyDomainName(networkDomain)) {
                        throw new InvalidParameterValueException("Invalid network domain. Total length shouldn't exceed 190 chars. Each domain "
                                + "label must be between 1 and 63 characters long, can contain ASCII letters 'a' through 'z', the digits '0' through '9', "
                                + "and the hyphen ('-'); can't start or end with \"-\"");
                    }
                }
            }
        }
        return networkDomain;
    }

    protected void validateCidrRequirements(final NetworkOfferingVO networkOffering, final DataCenterVO zone, final String cidr, final String ip6Cidr) {
        final boolean cidrRequired = zone.getNetworkType() == NetworkType.Advanced
                && networkOffering.getTrafficType() == TrafficType.Guest
                && (networkOffering.getGuestType() == GuestType.Shared || (networkOffering.getGuestType() == GuestType.Isolated
                && !networkModel.areServicesSupportedByNetworkOffering(networkOffering.getId(), Service.SourceNat)
                && !networkModel.areServicesSupportedByNetworkOffering(networkOffering.getId(), Service.Gateway)));
        if (cidr == null && ip6Cidr == null && cidrRequired) {
            if (networkOffering.getGuestType() == GuestType.Shared) {
                throw new InvalidParameterValueException(String.format("Gateway/netmask are required when creating %s networks.", Network.GuestType.Shared));
            } else {
                throw new InvalidParameterValueException("gateway/netmask are required when create network of" + " type " + GuestType.Isolated + " with service "
                        + Service.SourceNat.getName() + " disabled");
            }
        }
    }

    protected void validateBasicZoneCidr(final DataCenterVO zone, final String cidr) {
        if (zone.getNetworkType() == NetworkType.Basic && cidr != null) {
            throw new InvalidParameterValueException("StartIp/endIp/gateway/netmask can't be specified for zone of type " + NetworkType.Basic);
        }
    }

    protected void validateGuestCidr(final NetworkOfferingVO networkOffering, final String cidr) {
        if (cidr != null && (networkOffering.getGuestType() == Network.GuestType.Isolated && networkOffering.getTrafficType() == TrafficType.Guest) &&
                !NetUtils.validateGuestCidr(cidr, !ConfigurationManager.AllowNonRFC1918CompliantIPs.value())) {
            throw new InvalidParameterValueException("Virtual Guest Cidr " + cidr + " is not RFC 1918 or 6598 compliant");
        }
    }

    protected NetworkVO buildPredefinedNetwork(final String networkDomain, final String gateway, final String cidr, final String vlanId, final String ip6Gateway,
            final String ip6Cidr, final String isolatedPvlan, final Network.PVlanType isolatedPvlanType, final String externalId, final String routerIp,
            final String routerIpv6, final String ip4Dns1, final String ip4Dns2, final String ip6Dns1, final String ip6Dns2, final Pair<Integer, Integer> vrIfaceMTUs,
            final Integer networkCidrSize, final boolean keepMacAddressOnPublicNic, final PhysicalNetwork physicalNetwork, final Long physicalNetworkId, final DataCenterVO zone) {
        final NetworkVO userNetwork = new NetworkVO();
        userNetwork.setNetworkDomain(networkDomain);

        if (cidr != null && gateway != null) {
            userNetwork.setCidr(cidr);
            userNetwork.setGateway(gateway);
        }

        if (StringUtils.isNoneBlank(ip6Gateway, ip6Cidr)) {
            userNetwork.setIp6Cidr(ip6Cidr);
            userNetwork.setIp6Gateway(ip6Gateway);
        }

        if (externalId != null) {
            userNetwork.setExternalId(externalId);
        }

        if (StringUtils.isNotBlank(routerIp)) {
            userNetwork.setRouterIp(routerIp);
        }

        if (StringUtils.isNotBlank(routerIpv6)) {
            userNetwork.setRouterIpv6(routerIpv6);
        }

        setMtu(vrIfaceMTUs, userNetwork);
        setDns(ip4Dns1, ip4Dns2, ip6Dns1, ip6Dns2, userNetwork);
        setBroadcast(vlanId, isolatedPvlan, isolatedPvlanType, physicalNetwork, physicalNetworkId, zone, userNetwork);

        userNetwork.setNetworkCidrSize(networkCidrSize);
        userNetwork.setKeepMacAddressOnPublicNic(keepMacAddressOnPublicNic);
        return userNetwork;
    }

    protected void setMtu(final Pair<Integer, Integer> vrIfaceMTUs, final NetworkVO userNetwork) {
        if (vrIfaceMTUs != null) {
            if (vrIfaceMTUs.first() != null && vrIfaceMTUs.first() > 0) {
                userNetwork.setPublicMtu(vrIfaceMTUs.first());
            } else {
                userNetwork.setPublicMtu(Integer.valueOf(NetworkService.VRPublicInterfaceMtu.defaultValue()));
            }

            if (vrIfaceMTUs.second() != null && vrIfaceMTUs.second() > 0) {
                userNetwork.setPrivateMtu(vrIfaceMTUs.second());
            } else {
                userNetwork.setPrivateMtu(Integer.valueOf(NetworkService.VRPrivateInterfaceMtu.defaultValue()));
            }
        } else {
            userNetwork.setPublicMtu(Integer.valueOf(NetworkService.VRPublicInterfaceMtu.defaultValue()));
            userNetwork.setPrivateMtu(Integer.valueOf(NetworkService.VRPrivateInterfaceMtu.defaultValue()));
        }
    }

    protected void setDns(final String ip4Dns1, final String ip4Dns2, final String ip6Dns1, final String ip6Dns2, final NetworkVO userNetwork) {
        if (!GuestType.L2.equals(userNetwork.getGuestType())) {
            if (StringUtils.isNotBlank(ip4Dns1)) {
                userNetwork.setDns1(ip4Dns1);
            }
            if (StringUtils.isNotBlank(ip4Dns2)) {
                userNetwork.setDns2(ip4Dns2);
            }
            if (StringUtils.isNotBlank(ip6Dns1)) {
                userNetwork.setIp6Dns1(ip6Dns1);
            }
            if (StringUtils.isNotBlank(ip6Dns2)) {
                userNetwork.setIp6Dns2(ip6Dns2);
            }
        }
    }

    protected void setBroadcast(final String vlanId, final String isolatedPvlan, final Network.PVlanType isolatedPvlanType, final PhysicalNetwork physicalNetwork,
            final Long physicalNetworkId, final DataCenterVO zone, final NetworkVO userNetwork) {
        if (vlanId != null) {
            if (isolatedPvlan == null) {
                URI uri = null;
                if (UuidUtils.isUuid(vlanId)) {
                    userNetwork.setVlanIdAsUUID(vlanId);
                } else {
                    uri = networkOfferingVlanValidationService.encodeVlanIdIntoBroadcastUri(vlanId, physicalNetwork);
                }

                if (networksDao.listByPhysicalNetworkPvlan(physicalNetworkId, uri.toString()).size() > 0) {
                    throw new InvalidParameterValueException(String.format("Network with vlan %s already exists or overlaps with other network pvlans in zone %s", vlanId, zone));
                }

                userNetwork.setBroadcastUri(uri);
                if (!vlanId.equalsIgnoreCase(Vlan.UNTAGGED)) {
                    userNetwork.setBroadcastDomainType(BroadcastDomainType.Vlan);
                } else {
                    userNetwork.setBroadcastDomainType(BroadcastDomainType.Native);
                }
            } else {
                if (vlanId.equalsIgnoreCase(Vlan.UNTAGGED)) {
                    throw new InvalidParameterValueException("Cannot support pvlan with untagged primary vlan!");
                }
                URI uri = NetUtils.generateUriForPvlan(vlanId, isolatedPvlan, isolatedPvlanType.toString());
                if (networksDao.listByPhysicalNetworkPvlan(physicalNetworkId, uri.toString(), isolatedPvlanType).size() > 0) {
                    throw new InvalidParameterValueException(String.format(
                            "Network with primary vlan %s and secondary vlan %s type %s already exists or overlaps with other network pvlans in zone %s",
                            vlanId, isolatedPvlan, isolatedPvlanType, zone));
                }
                userNetwork.setBroadcastUri(uri);
                userNetwork.setBroadcastDomainType(BroadcastDomainType.Pvlan);
                userNetwork.setPvlanType(isolatedPvlanType);
            }
        }
    }
}
