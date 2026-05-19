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
package com.cloud.api;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Consumer;

import jakarta.inject.Inject;

import org.apache.cloudstack.acl.ControlledEntity.ACLType;
import org.apache.cloudstack.annotation.AnnotationService;
import org.apache.cloudstack.annotation.dao.AnnotationDao;
import org.apache.cloudstack.api.BaseResponseWithAssociatedNetwork;
import org.apache.cloudstack.api.ResponseObject.ResponseView;
import org.apache.cloudstack.api.response.BgpPeerResponse;
import org.apache.cloudstack.api.response.CapabilityResponse;
import org.apache.cloudstack.api.response.Ipv4RouteResponse;
import org.apache.cloudstack.api.response.Ipv6RouteResponse;
import org.apache.cloudstack.api.response.NetworkResponse;
import org.apache.cloudstack.api.response.PrivateGatewayResponse;
import org.apache.cloudstack.api.response.ProviderResponse;
import org.apache.cloudstack.api.response.RemoteAccessVpnResponse;
import org.apache.cloudstack.api.response.ResourceTagResponse;
import org.apache.cloudstack.api.response.ServiceResponse;
import org.apache.cloudstack.api.response.Site2SiteCustomerGatewayResponse;
import org.apache.cloudstack.api.response.Site2SiteVpnConnectionResponse;
import org.apache.cloudstack.api.response.Site2SiteVpnGatewayResponse;
import org.apache.cloudstack.api.response.StaticRouteResponse;
import org.apache.cloudstack.api.response.VpcOfferingResponse;
import org.apache.cloudstack.api.response.VpcResponse;
import org.apache.cloudstack.api.response.VpnUsersResponse;
import org.apache.cloudstack.context.CallContext;
import org.apache.cloudstack.network.BgpPeerVO;
import org.apache.cloudstack.network.RoutedIpv4Manager;
import org.apache.cloudstack.network.dao.BgpPeerDao;
import org.apache.commons.collections.CollectionUtils;
import org.springframework.stereotype.Component;

import com.cloud.api.query.vo.ResourceTagJoinVO;
import com.cloud.api.query.vo.VpcOfferingJoinVO;
import com.cloud.dc.ASNumberVO;
import com.cloud.dc.DataCenter;
import com.cloud.dc.dao.ASNumberDao;
import com.cloud.network.IpAddress;
import com.cloud.network.Ipv6Service;
import com.cloud.network.Network;
import com.cloud.network.Network.Capability;
import com.cloud.network.Network.Provider;
import com.cloud.network.Network.Service;
import com.cloud.network.NetworkProfile;
import com.cloud.network.Networks.BroadcastDomainType;
import com.cloud.network.PhysicalNetwork;
import com.cloud.network.RemoteAccessVpn;
import com.cloud.network.Site2SiteCustomerGateway;
import com.cloud.network.Site2SiteVpnConnection;
import com.cloud.network.Site2SiteVpnGateway;
import com.cloud.network.VpnUser;
import com.cloud.network.dao.IPAddressDao;
import com.cloud.network.dao.IPAddressVO;
import com.cloud.network.dao.NetworkDao;
import com.cloud.network.dao.NetworkDetailVO;
import com.cloud.network.dao.NetworkDetailsDao;
import com.cloud.network.dao.NetworkVO;
import com.cloud.network.dao.PhysicalNetworkVO;
import com.cloud.network.vpc.NetworkACL;
import com.cloud.network.vpc.PrivateGateway;
import com.cloud.network.vpc.StaticRoute;
import com.cloud.network.vpc.Vpc;
import com.cloud.network.vpc.VpcGateway;
import com.cloud.network.vpc.VpcOffering;
import com.cloud.network.vpc.dao.VpcOfferingDao;
import com.cloud.network.vpn.Site2SiteVpnManager;
import com.cloud.offering.NetworkOffering;
import com.cloud.offerings.dao.NetworkOfferingDao;
import com.cloud.server.ResourceTag;
import com.cloud.server.ResourceTag.ResourceObjectType;
import com.cloud.user.Account;
import com.cloud.user.AccountManager;
import com.cloud.user.UserStatisticsVO;
import com.cloud.user.dao.UserStatisticsDao;
import com.cloud.utils.Pair;
import com.cloud.utils.db.EntityManager;
import com.cloud.utils.db.SearchBuilder;
import com.cloud.utils.db.SearchCriteria;
import com.cloud.utils.db.SearchCriteria.Op;
import com.cloud.utils.net.NetUtils;
import com.cloud.vm.VMInstanceVO;
import com.cloud.vm.VirtualMachine;

@Component
public class ApiVpcVpnResponseServiceImpl implements ApiVpcVpnResponseService {

    private static final ApiResponseOwnerService STATIC_OWNER_SERVICE = new ApiResponseOwnerServiceImpl();

    @Inject
    private ApiResponseOwnerService apiResponseOwnerService;
    @Inject
    private AnnotationDao annotationDao;
    @Inject
    protected AccountManager _accountMgr;
    @Inject
    private EntityManager _entityMgr;
    @Inject
    private IPAddressDao userIpAddressDao;
    @Inject
    private NetworkDao networkDao;
    @Inject
    private NetworkDetailsDao networkDetailsDao;
    @Inject
    private NetworkOfferingDao networkOfferingDao;
    @Inject
    private VpcOfferingDao vpcOfferingDao;
    @Inject
    private BgpPeerDao bgpPeerDao;
    @Inject
    private RoutedIpv4Manager routedIpv4Manager;
    @Inject
    private Site2SiteVpnManager site2SiteVpnManager;
    @Inject
    private Ipv6Service ipv6Service;
    @Inject
    private UserStatisticsDao userStatsDao;
    @Inject
    private ASNumberDao asNumberDao;

    @Override
    public VpcOfferingResponse createVpcOfferingResponse(VpcOffering offering) {
        if (!(offering instanceof VpcOfferingJoinVO)) {
            offering = ApiDBUtils.newVpcOfferingView(offering);
        }
        VpcOfferingResponse response = ApiDBUtils.newVpcOfferingResponse(offering);
        Map<Service, Set<Provider>> serviceProviderMap = ApiDBUtils.listVpcOffServices(offering.getId());
        List<ServiceResponse> serviceResponses = new ArrayList<>();
        for (Map.Entry<Service, Set<Provider>> entry : serviceProviderMap.entrySet()) {
            Service service = entry.getKey();
            Set<Provider> serviceProviders = entry.getValue();

            ServiceResponse svcRsp = new ServiceResponse();
            if (service == Service.Gateway) {
                continue;
            }
            svcRsp.setName(service.getName());
            List<ProviderResponse> providers = new ArrayList<>();
            for (Provider provider : serviceProviders) {
                if (provider != null) {
                    ProviderResponse providerRsp = new ProviderResponse();
                    providerRsp.setName(provider.getName());
                    providers.add(providerRsp);
                }
            }
            svcRsp.setProviders(providers);

            serviceResponses.add(svcRsp);
        }
        response.setServices(serviceResponses);
        return response;
    }

    @Override
    public VpcResponse createVpcResponse(ResponseView view, Vpc vpc) {
        VpcResponse response = new VpcResponse();
        response.setId(vpc.getUuid());
        response.setName(vpc.getName());
        response.setDisplayText(vpc.getDisplayText());
        response.setCreated(vpc.getCreated());
        response.setState(vpc.getState().name());
        VpcOffering voff = ApiDBUtils.findVpcOfferingById(vpc.getVpcOfferingId());
        if (voff != null) {
            response.setVpcOfferingId(voff.getUuid());
            response.setVpcOfferingName(voff.getName());
            response.setVpcOfferingConserveMode(voff.isConserveMode());
        }
        response.setCidr(vpc.getCidr());
        response.setRestartRequired(vpc.isRestartRequired());
        response.setNetworkDomain(vpc.getNetworkDomain());
        response.setForDisplay(vpc.isDisplay());
        response.setUsesDistributedRouter(vpc.usesDistributedRouter());
        response.setRedundantRouter(vpc.isRedundant());
        response.setRegionLevelVpc(vpc.isRegionLevelVpc());
        ASNumberVO asNumberVO = asNumberDao.findByZoneAndVpcId(vpc.getZoneId(), vpc.getId());
        if (Objects.nonNull(asNumberVO)) {
            response.setAsNumberId(asNumberVO.getUuid());
            response.setAsNumber(asNumberVO.getAsNumber());
        }
        Map<Service, Set<Provider>> serviceProviderMap = ApiDBUtils.listVpcOffServices(vpc.getVpcOfferingId());
        List<ServiceResponse> serviceResponses = new ArrayList<>();
        for (Map.Entry<Service, Set<Provider>> entry : serviceProviderMap.entrySet()) {
            Service service = entry.getKey();
            Set<Provider> serviceProviders = entry.getValue();
            ServiceResponse svcRsp = new ServiceResponse();
            if (service == Service.Gateway) {
                continue;
            }
            svcRsp.setName(service.getName());
            List<ProviderResponse> providers = new ArrayList<>();
            for (Provider provider : serviceProviders) {
                if (provider != null) {
                    ProviderResponse providerRsp = new ProviderResponse();
                    providerRsp.setName(provider.getName());
                    providers.add(providerRsp);
                }
            }
            svcRsp.setProviders(providers);

            serviceResponses.add(svcRsp);
        }

        List<NetworkResponse> networkResponses = new ArrayList<>();
        List<? extends Network> networks = ApiDBUtils.listVpcNetworks(vpc.getId());
        for (Network network : networks) {
            NetworkResponse ntwkRsp = createNetworkResponse(view, network);
            networkResponses.add(ntwkRsp);
        }

        DataCenter zone = ApiDBUtils.findZoneById(vpc.getZoneId());
        if (zone != null) {
            response.setZoneId(zone.getUuid());
            response.setZoneName(zone.getName());
        }

        response.setNetworks(networkResponses);
        response.setServices(serviceResponses);
        response.setPublicMtu(vpc.getPublicMtu());
        getApiResponseOwnerService().populateOwner(response, vpc);

        List<? extends ResourceTag> tags = ApiDBUtils.listByResourceTypeAndId(ResourceObjectType.Vpc, vpc.getId());
        List<ResourceTagResponse> tagResponses = new ArrayList<>();
        for (ResourceTag tag : tags) {
            ResourceTagResponse tagResponse = createResourceTagResponse(tag, true);
            CollectionUtils.addIgnoreNull(tagResponses, tagResponse);
        }
        response.setTags(tagResponses);
        response.setHasAnnotation(annotationDao.hasAnnotations(vpc.getUuid(), AnnotationService.EntityType.VPC.name(),
                _accountMgr.isRootAdmin(CallContext.current().getCallingAccount().getId())));
        ipv6Service.updateIpv6RoutesForVpcResponse(vpc, response);
        response.setDns1(vpc.getIp4Dns1());
        response.setDns2(vpc.getIp4Dns2());
        response.setIpv6Dns1(vpc.getIp6Dns1());
        response.setIpv6Dns2(vpc.getIp6Dns2());

        if (vpcOfferingDao.isRoutedVpc(vpc.getVpcOfferingId())) {
            if (Objects.nonNull(asNumberVO)) {
                response.setIpv4Routing(Network.Routing.Dynamic.name());
            } else {
                response.setIpv4Routing(Network.Routing.Static.name());
            }
            response.setIpv4Routes(new LinkedHashSet<>());
            List<IPAddressVO> ips = userIpAddressDao.listByAssociatedVpc(vpc.getId(), true);
            for (Network network : networkDao.listByVpc(vpc.getId())) {
                for (IPAddressVO ip : ips) {
                    Ipv4RouteResponse route = new Ipv4RouteResponse(network.getCidr(), ip.getAddress().addr());
                    response.addIpv4Route(route);
                }
            }
            if (view == ResponseView.Full) {
                List<BgpPeerVO> bgpPeerVOS = bgpPeerDao.listNonRevokeByVpcId(vpc.getId());
                for (BgpPeerVO bgpPeerVO : bgpPeerVOS) {
                    BgpPeerResponse bgpPeerResponse = routedIpv4Manager.createBgpPeerResponse(bgpPeerVO);
                    response.addBgpPeer(bgpPeerResponse);
                }
            }
        }

        if (CallContext.current().getCallingAccount().getType() == Account.Type.ADMIN) {
            response.setKeepMacAddressOnPublicNic(vpc.getKeepMacAddressOnPublicNic());
        }
        response.setObjectName("vpc");
        return response;
    }

    @Override
    public PrivateGatewayResponse createPrivateGatewayResponse(ResponseView view, PrivateGateway result) {
        PrivateGatewayResponse response = new PrivateGatewayResponse();
        response.setId(result.getUuid());
        if (view == ResponseView.Full) {
            response.setBroadcastUri(result.getBroadcastUri());
        }
        response.setGateway(result.getGateway());
        response.setNetmask(result.getNetmask());
        if (result.getVpcId() != null) {
            Vpc vpc = ApiDBUtils.findVpcById(result.getVpcId());
            response.setVpcId(vpc.getUuid());
            response.setVpcName(vpc.getName());
        }

        DataCenter zone = ApiDBUtils.findZoneById(result.getZoneId());
        if (zone != null) {
            response.setZoneId(zone.getUuid());
            response.setZoneName(zone.getName());
        }
        response.setAddress(result.getIp4Address());
        PhysicalNetwork pnet = ApiDBUtils.findPhysicalNetworkById(result.getPhysicalNetworkId());
        if (pnet != null) {
            response.setPhysicalNetworkId(pnet.getUuid());
        }

        getApiResponseOwnerService().populateAccount(response, result.getAccountId());
        getApiResponseOwnerService().populateDomain(response, result.getDomainId());
        response.setState(result.getState().toString());
        response.setSourceNat(result.getSourceNat());

        NetworkACL acl = ApiDBUtils.findByNetworkACLId(result.getNetworkACLId());
        if (acl != null) {
            response.setAclId(acl.getUuid());
            response.setAclName(acl.getName());
        }

        setResponseAssociatedNetworkInformation(response, result.getNetworkId());

        response.setObjectName("privategateway");

        return response;
    }

    @Override
    public StaticRouteResponse createStaticRouteResponse(StaticRoute result) {
        StaticRouteResponse response = new StaticRouteResponse();
        response.setId(result.getUuid());
        if (result.getVpcId() != null) {
            Vpc vpc = ApiDBUtils.findVpcById(result.getVpcId());
            if (vpc != null) {
                response.setVpcId(vpc.getUuid());
            }
        }
        if (result.getVpcGatewayId() != null) {
            VpcGateway vpcGateway = _entityMgr.findById(VpcGateway.class, result.getVpcGatewayId());
            if (vpcGateway != null) {
                response.setVpcGatewayId(vpcGateway.getUuid());
                response.setVpcGatewayIp(vpcGateway.getIp4Address());
            }
        }
        if (result.getNextHop() != null) {
            response.setNextHop(result.getNextHop());
        }
        response.setCidr(result.getCidr());

        StaticRoute.State state = result.getState();
        if (state.equals(StaticRoute.State.Revoke)) {
            state = StaticRoute.State.Deleting;
        }
        response.setState(state.toString());
        getApiResponseOwnerService().populateAccount(response, result.getAccountId());
        getApiResponseOwnerService().populateDomain(response, result.getDomainId());

        List<? extends ResourceTag> tags = ApiDBUtils.listByResourceTypeAndId(ResourceObjectType.StaticRoute, result.getId());
        List<ResourceTagResponse> tagResponses = new ArrayList<>();
        for (ResourceTag tag : tags) {
            ResourceTagResponse tagResponse = createResourceTagResponse(tag, true);
            CollectionUtils.addIgnoreNull(tagResponses, tagResponse);
        }
        response.setTags(tagResponses);
        response.setObjectName("staticroute");

        return response;
    }

    @Override
    public Site2SiteVpnGatewayResponse createSite2SiteVpnGatewayResponse(Site2SiteVpnGateway result) {
        Site2SiteVpnGatewayResponse response = new Site2SiteVpnGatewayResponse();
        response.setId(result.getUuid());
        response.setIp(ApiDBUtils.findIpAddressById(result.getAddrId()).getAddress().toString());
        Vpc vpc = ApiDBUtils.findVpcById(result.getVpcId());
        if (vpc != null) {
            response.setVpcId(vpc.getUuid());
            response.setVpcName(vpc.getName());
        }
        response.setRemoved(result.getRemoved());
        response.setForDisplay(result.isDisplay());
        response.setObjectName("vpngateway");

        getApiResponseOwnerService().populateAccount(response, result.getAccountId());
        getApiResponseOwnerService().populateDomain(response, result.getDomainId());
        return response;
    }

    @Override
    public Site2SiteCustomerGatewayResponse createSite2SiteCustomerGatewayResponse(Site2SiteCustomerGateway result) {
        Site2SiteCustomerGatewayResponse response = new Site2SiteCustomerGatewayResponse();
        response.setId(result.getUuid());
        response.setName(result.getName());
        response.setGatewayIp(result.getGatewayIp());
        response.setGuestCidrList(result.getGuestCidrList());
        response.setIpsecPsk(result.getIpsecPsk());
        response.setIkePolicy(result.getIkePolicy());
        response.setEspPolicy(result.getEspPolicy());
        response.setIkeLifetime(result.getIkeLifetime());
        response.setEspLifetime(result.getEspLifetime());
        response.setDpd(result.getDpd());
        response.setEncap(result.getEncap());
        response.setRemoved(result.getRemoved());
        response.setIkeVersion(result.getIkeVersion());
        response.setSplitConnections(result.getSplitConnections());

        Set<String> obsoleteParameters = site2SiteVpnManager.getObsoleteVpnGatewayParameters(result);
        if (CollectionUtils.isNotEmpty(obsoleteParameters)) {
            response.setContainsObsoleteParameters(obsoleteParameters.toString());
        }
        Set<String> excludedParameters = site2SiteVpnManager.getExcludedVpnGatewayParameters(result);
        if (CollectionUtils.isNotEmpty(excludedParameters)) {
            response.setContainsExcludedParameters(excludedParameters.toString());
        }

        response.setObjectName("vpncustomergateway");
        response.setHasAnnotation(annotationDao.hasAnnotations(result.getUuid(), AnnotationService.EntityType.VPN_CUSTOMER_GATEWAY.name(),
                _accountMgr.isRootAdmin(CallContext.current().getCallingAccount().getId())));

        getApiResponseOwnerService().populateAccount(response, result.getAccountId());
        getApiResponseOwnerService().populateDomain(response, result.getDomainId());

        return response;
    }

    @Override
    public Site2SiteVpnConnectionResponse createSite2SiteVpnConnectionResponse(Site2SiteVpnConnection result) {
        Site2SiteVpnConnectionResponse response = new Site2SiteVpnConnectionResponse();
        response.setId(result.getUuid());
        response.setPassive(result.isPassive());

        Long vpnGatewayId = result.getVpnGatewayId();
        if (vpnGatewayId != null) {
            Site2SiteVpnGateway vpnGateway = ApiDBUtils.findVpnGatewayById(vpnGatewayId);
            if (vpnGateway != null) {
                response.setVpnGatewayId(vpnGateway.getUuid());
                long ipId = vpnGateway.getAddrId();
                IPAddressVO ipObj = ApiDBUtils.findIpAddressById(ipId);
                response.setIp(ipObj.getAddress().addr());
            }
        }

        Long customerGatewayId = result.getCustomerGatewayId();
        if (customerGatewayId != null) {
            Site2SiteCustomerGateway customerGateway = ApiDBUtils.findCustomerGatewayById(customerGatewayId);
            if (customerGateway != null) {
                response.setCustomerGatewayId(customerGateway.getUuid());
                response.setGatewayIp(customerGateway.getGatewayIp());
                response.setGuestCidrList(customerGateway.getGuestCidrList());
                response.setIpsecPsk(customerGateway.getIpsecPsk());
                response.setIkePolicy(customerGateway.getIkePolicy());
                response.setEspPolicy(customerGateway.getEspPolicy());
                response.setIkeLifetime(customerGateway.getIkeLifetime());
                response.setEspLifetime(customerGateway.getEspLifetime());
                response.setDpd(customerGateway.getDpd());
                response.setEncap(customerGateway.getEncap());
                response.setIkeVersion(customerGateway.getIkeVersion());
                response.setSplitConnections(customerGateway.getSplitConnections());
            }
        }

        getApiResponseOwnerService().populateAccount(response, result.getAccountId());
        getApiResponseOwnerService().populateDomain(response, result.getDomainId());

        response.setState(result.getState().toString());
        response.setCreated(result.getCreated());
        response.setRemoved(result.getRemoved());
        response.setForDisplay(result.isDisplay());
        response.setObjectName("vpnconnection");
        return response;
    }

    @Override
    public VpnUsersResponse createVpnUserResponse(VpnUser vpnUser) {
        VpnUsersResponse vpnResponse = new VpnUsersResponse();
        vpnResponse.setId(vpnUser.getUuid());
        vpnResponse.setUserName(vpnUser.getUsername());
        vpnResponse.setState(vpnUser.getState().toString());

        getApiResponseOwnerService().populateOwner(vpnResponse, vpnUser);

        vpnResponse.setObjectName("vpnuser");
        return vpnResponse;
    }

    @Override
    public RemoteAccessVpnResponse createRemoteAccessVpnResponse(RemoteAccessVpn vpn) {
        RemoteAccessVpnResponse vpnResponse = new RemoteAccessVpnResponse();
        IpAddress ip = ApiDBUtils.findIpAddressById(vpn.getServerAddressId());
        if (ip != null) {
            vpnResponse.setPublicIpId(ip.getUuid());
            vpnResponse.setPublicIp(ip.getAddress().addr());
        }
        vpnResponse.setIpRange(vpn.getIpRange());
        vpnResponse.setPresharedKey(vpn.getIpsecPresharedKey());
        getApiResponseOwnerService().populateOwner(vpnResponse, vpn);
        vpnResponse.setState(vpn.getState().toString());
        vpnResponse.setId(vpn.getUuid());
        vpnResponse.setForDisplay(vpn.isDisplay());
        vpnResponse.setObjectName("remoteaccessvpn");

        return vpnResponse;
    }

    private NetworkResponse createNetworkResponse(ResponseView view, Network network) {
        NetworkProfile profile = ApiDBUtils.getNetworkProfile(network.getId());
        NetworkResponse response = new NetworkResponse();
        response.setId(network.getUuid());
        response.setName(network.getName());
        response.setDisplaytext(network.getDisplayText());
        if (network.getBroadcastDomainType() != null) {
            response.setBroadcastDomainType(network.getBroadcastDomainType().toString());
        }

        if (network.getTrafficType() != null) {
            response.setTrafficType(network.getTrafficType().name());
        }

        if (network.getGuestType() != null) {
            response.setType(network.getGuestType().toString());
        }

        response.setGateway(com.cloud.utils.StringUtils.getFirstValueFromCommaSeparatedString(network.getGateway()));
        String cidr = com.cloud.utils.StringUtils.getFirstValueFromCommaSeparatedString(network.getCidr());

        response.setCidr(cidr);
        if (network.getNetworkCidr() != null) {
            response.setNetworkCidr((network.getNetworkCidr()));
        }
        if (network.getNetworkCidr() != null) {
            response.setNetmask(NetUtils.cidr2Netmask(network.getNetworkCidr()));
        }
        if ((cidr != null) && (network.getNetworkCidr() == null)) {
            response.setNetmask(NetUtils.cidr2Netmask(cidr));
        }

        response.setIp6Gateway(com.cloud.utils.StringUtils.getFirstValueFromCommaSeparatedString(network.getIp6Gateway()));
        response.setIp6Cidr(com.cloud.utils.StringUtils.getFirstValueFromCommaSeparatedString(network.getIp6Cidr()));

        String reservation = null;
        if ((cidr != null) && (NetUtils.isNetworkAWithinNetworkB(cidr, network.getNetworkCidr()))) {
            String[] guestVmCidrPair = cidr.split("\\/");
            String[] guestCidrPair = network.getNetworkCidr().split("\\/");

            Long guestVmCidrSize = Long.valueOf(guestVmCidrPair[1]);
            Long guestCidrSize = Long.valueOf(guestCidrPair[1]);

            String[] guestVmIpRange = NetUtils.getIpRangeFromCidr(guestVmCidrPair[0], guestVmCidrSize);
            String[] guestIpRange = NetUtils.getIpRangeFromCidr(guestCidrPair[0], guestCidrSize);
            long startGuestIp = NetUtils.ip2Long(guestIpRange[0]);
            long endGuestIp = NetUtils.ip2Long(guestIpRange[1]);
            long startVmIp = NetUtils.ip2Long(guestVmIpRange[0]);
            long endVmIp = NetUtils.ip2Long(guestVmIpRange[1]);

            if (startVmIp == startGuestIp && endVmIp < endGuestIp - 1) {
                reservation = NetUtils.long2Ip(endVmIp + 1) + "-" + NetUtils.long2Ip(endGuestIp);
            }
            if (endVmIp == endGuestIp && startVmIp > startGuestIp + 1) {
                reservation = NetUtils.long2Ip(startGuestIp) + "-" + NetUtils.long2Ip(startVmIp - 1);
            }
            if (startVmIp > startGuestIp + 1 && endVmIp < endGuestIp - 1) {
                reservation = NetUtils.long2Ip(startGuestIp) + "-" + NetUtils.long2Ip(startVmIp - 1) + " ,  " + NetUtils.long2Ip(endVmIp + 1) + "-" + NetUtils.long2Ip(endGuestIp);
            }
        }
        response.setReservedIpRange(reservation);
        if (network.getBroadcastUri() != null && view == ResponseView.Full) {
            String broadcastUri = network.getBroadcastUri().toString();
            response.setBroadcastUri(broadcastUri);
            String vlan = "N/A";
            switch (BroadcastDomainType.getSchemeValue(network.getBroadcastUri())) {
            case Vlan:
            case Vxlan:
                vlan = BroadcastDomainType.getValue(network.getBroadcastUri());
                break;
            default:
                break;
            }
            response.setVlan(vlan);
        }

        if (view == ResponseView.Full) {
            Map<String, String> details = new HashMap<>();
            for (NetworkDetailVO detail : networkDetailsDao.listDetails(network.getId())) {
                details.put(detail.getName(), detail.getValue());
            }
            response.setDetails(details);
        }

        DataCenter zone = ApiDBUtils.findZoneById(network.getDataCenterId());
        if (zone != null) {
            response.setZoneId(zone.getUuid());
            response.setZoneName(zone.getName());
        }
        if (network.getPhysicalNetworkId() != null) {
            PhysicalNetworkVO pnet = ApiDBUtils.findPhysicalNetworkById(network.getPhysicalNetworkId());
            response.setPhysicalNetworkId(pnet.getUuid());
        }

        NetworkOffering networkOffering = ApiDBUtils.findNetworkOfferingById(network.getNetworkOfferingId());
        if (networkOffering != null) {
            response.setNetworkOfferingId(networkOffering.getUuid());
            response.setNetworkOfferingName(networkOffering.getName());
            response.setNetworkOfferingDisplayText(networkOffering.getDisplayText());
            response.setNetworkOfferingConserveMode(networkOffering.isConserveMode());
            response.setIsSystem(networkOffering.isSystemOnly());
            response.setNetworkOfferingAvailability(networkOffering.getAvailability().toString());
            response.setIsPersistent(networkOffering.isPersistent());
            response.setSpecifyVlan(networkOffering.isSpecifyVlan());
            if (Network.GuestType.Isolated.equals(network.getGuestType()) && network.getVpcId() == null) {
                response.setEgressDefaultPolicy(networkOffering.isEgressDefaultPolicy());
            }
            ASNumberVO asNumberVO = networkOffering.isForVpc() ?
                    asNumberDao.findByZoneAndVpcId(network.getDataCenterId(), network.getVpcId()) :
                    asNumberDao.findByZoneAndNetworkId(network.getDataCenterId(), network.getId());
            if (Objects.nonNull(asNumberVO)) {
                response.setAsNumberId(asNumberVO.getUuid());
                response.setAsNumber(asNumberVO.getAsNumber());
            }
        }

        if (network.getAclType() != null) {
            response.setAclType(network.getAclType().toString());
        }
        response.setDisplayNetwork(network.getDisplayNetwork());
        response.setState(network.getState().toString());
        response.setRestartRequired(network.isRestartRequired());
        NetworkVO nw = ApiDBUtils.findNetworkById(network.getRelated());
        if (nw != null) {
            response.setRelated(nw.getUuid());
        }
        response.setNetworkDomain(network.getNetworkDomain());
        response.setPublicMtu(network.getPublicMtu());
        response.setPrivateMtu(network.getPrivateMtu());
        response.setDns1(profile.getDns1());
        response.setDns2(profile.getDns2());
        response.setIpv6Dns1(profile.getIp6Dns1());
        response.setIpv6Dns2(profile.getIp6Dns2());

        Map<Service, Map<Capability, String>> serviceCapabilitiesMap = ApiDBUtils.getNetworkCapabilities(network.getId(), network.getDataCenterId());
        Map<Service, Set<Provider>> serviceProviderMap = ApiDBUtils.listNetworkOfferingServices(network.getNetworkOfferingId());
        List<ServiceResponse> serviceResponses = new ArrayList<>();
        if (serviceCapabilitiesMap != null) {
            for (Map.Entry<Service, Map<Capability, String>> entry : serviceCapabilitiesMap.entrySet()) {
                Service service = entry.getKey();
                ServiceResponse serviceResponse = new ServiceResponse();
                if (service == Service.Gateway) {
                    continue;
                }
                serviceResponse.setName(service.getName());

                List<CapabilityResponse> capabilityResponses = new ArrayList<>();
                Map<Capability, String> serviceCapabilities = entry.getValue();
                if (serviceCapabilities != null) {
                    for (Map.Entry<Capability, String> serviceCapabilityEntry : serviceCapabilities.entrySet()) {
                        Capability capability = serviceCapabilityEntry.getKey();
                        String capabilityValue = serviceCapabilityEntry.getValue();
                        if (Service.Lb == service && capability.getName().equals(Capability.SupportedLBIsolation.getName())) {
                            capabilityValue = networkOffering.isDedicatedLB() ? "dedicated" : "shared";
                        }

                        Set<String> capabilitySet = new HashSet<>(Arrays.asList(Capability.SupportedLBIsolation.getName(),
                                Capability.SupportedSourceNatTypes.getName(),
                                Capability.RedundantRouter.getName()));
                        boolean canChoose = capabilitySet.contains(capability.getName());

                        createCapabilityResponse(capabilityResponses, capability.getName(),
                                capabilityValue, canChoose, "capability");
                    }
                }

                if (Service.SourceNat == service) {
                    capabilityResponses = new ArrayList<>();
                    createCapabilityResponse(capabilityResponses, Capability.SupportedSourceNatTypes.getName(),
                            networkOffering.isSharedSourceNat() ? "perzone" : "peraccount", true);

                    createCapabilityResponse(capabilityResponses, Capability.RedundantRouter.getName(),
                            networkOffering.isRedundantRouter() ? "true" : "false", true);
                } else if (service == Service.StaticNat) {
                    createCapabilityResponse(capabilityResponses, Capability.ElasticIp.getName(),
                            networkOffering.isElasticIp() ? "true" : "false", false);

                    createCapabilityResponse(capabilityResponses, Capability.AssociatePublicIP.getName(),
                            networkOffering.isAssociatePublicIP() ? "true" : "false", false);
                } else if (Service.Lb == service) {
                    createCapabilityResponse(capabilityResponses, Capability.ElasticLb.getName(),
                            networkOffering.isElasticLb() ? "true" : "false", false);

                    createCapabilityResponse(capabilityResponses, Capability.InlineMode.getName(),
                            networkOffering.isInline() ? "true" : "false", false);
                }
                serviceResponse.setCapabilities(capabilityResponses);

                List<ProviderResponse> providers = new ArrayList<>();
                for (Provider provider : serviceProviderMap.get(service)) {
                    if (provider != null) {
                        ProviderResponse providerRsp = new ProviderResponse();
                        providerRsp.setName(provider.getName());
                        providers.add(providerRsp);
                    }
                }
                serviceResponse.setProviders(providers);

                serviceResponse.setObjectName("service");
                serviceResponses.add(serviceResponse);
            }
        }
        response.setServices(serviceResponses);

        if (network.getAclType() == null || network.getAclType() == ACLType.Account) {
            getApiResponseOwnerService().populateOwner(response, network);
        } else {
            Pair<Long, Boolean> domainNetworkDetails = ApiDBUtils.getDomainNetworkDetails(network.getId());
            if (domainNetworkDetails.first() != null) {
                getApiResponseOwnerService().populateDomain(response, domainNetworkDetails.first());
            }
            response.setSubdomainAccess(domainNetworkDetails.second());
        }

        Long dedicatedDomainId = ApiDBUtils.getDedicatedNetworkDomain(network.getId());
        if (dedicatedDomainId != null) {
            getApiResponseOwnerService().populateDomain(response, dedicatedDomainId);
        }

        response.setSpecifyIpRanges(network.getSpecifyIpRanges());

        setVpcIdInResponse(network.getVpcId(), response::setVpcId, response::setVpcName);

        setResponseAssociatedNetworkInformation(response, network.getId());

        response.setCanUseForDeploy(ApiDBUtils.canUseForDeploy(network));

        List<? extends ResourceTag> tags = ApiDBUtils.listByResourceTypeAndId(ResourceObjectType.Network, network.getId());
        List<ResourceTagResponse> tagResponses = new ArrayList<>();
        for (ResourceTag tag : tags) {
            ResourceTagResponse tagResponse = createResourceTagResponse(tag, true);
            CollectionUtils.addIgnoreNull(tagResponses, tagResponse);
        }
        response.setTags(tagResponses);
        response.setHasAnnotation(annotationDao.hasAnnotations(network.getUuid(), AnnotationService.EntityType.NETWORK.name(),
                _accountMgr.isRootAdmin(CallContext.current().getCallingAccount().getId())));

        if (network.getNetworkACLId() != null) {
            NetworkACL acl = ApiDBUtils.findByNetworkACLId(network.getNetworkACLId());
            if (acl != null) {
                response.setAclId(acl.getUuid());
                response.setAclName(acl.getName());
            }
        }

        response.setStrechedL2Subnet(network.isStrechedL2Network());
        if (network.isStrechedL2Network()) {
            Set<String> networkSpannedZones = new HashSet<>();
            List<VMInstanceVO> vmInstances = new ArrayList<>();
            vmInstances.addAll(ApiDBUtils.listUserVMsByNetworkId(network.getId()));
            vmInstances.addAll(ApiDBUtils.listDomainRoutersByNetworkId(network.getId()));
            for (VirtualMachine vm : vmInstances) {
                DataCenter vmZone = ApiDBUtils.findZoneById(vm.getDataCenterId());
                networkSpannedZones.add(vmZone.getUuid());
            }
            response.setNetworkSpannedZones(networkSpannedZones);
        }
        response.setExternalId(network.getExternalId());
        response.setRedundantRouter(network.isRedundant());
        response.setCreated(network.getCreated());
        response.setSupportsVmAutoScaling(networkOfferingDao.findByIdIncludingRemoved(network.getNetworkOfferingId()).isSupportsVmAutoScaling());

        Long bytesReceived = 0L;
        Long bytesSent = 0L;
        SearchBuilder<UserStatisticsVO> sb = userStatsDao.createSearchBuilder();
        sb.and("networkId", sb.entity().getNetworkId(), Op.EQ);
        SearchCriteria<UserStatisticsVO> sc = sb.create();
        sc.setParameters("networkId", network.getId());
        for (UserStatisticsVO stat : userStatsDao.search(sc, null)) {
            bytesReceived += stat.getNetBytesReceived() + stat.getCurrentBytesReceived();
            bytesSent += stat.getNetBytesSent() + stat.getCurrentBytesSent();
        }
        response.setBytesReceived(bytesReceived);
        response.setBytesSent(bytesSent);

        if (networkOfferingDao.isRoutedNetwork(network.getNetworkOfferingId())) {
            if (routedIpv4Manager.isDynamicRoutedNetwork(network)) {
                response.setIpv4Routing(Network.Routing.Dynamic.name());
            } else {
                response.setIpv4Routing(Network.Routing.Static.name());
            }
            response.setIpv4Routes(new LinkedHashSet<>());
            List<IPAddressVO> ips = network.getVpcId() != null ? userIpAddressDao.listByAssociatedVpc(network.getVpcId(), true) :
                    userIpAddressDao.listByAssociatedNetwork(network.getId(), true);
            for (IpAddress ip : ips) {
                Ipv4RouteResponse route = new Ipv4RouteResponse(network.getCidr(), ip.getAddress().addr());
                response.addIpv4Route(route);
            }

            if (view == ResponseView.Full) {
                List<BgpPeerVO> bgpPeerVOS = bgpPeerDao.listNonRevokeByNetworkId(network.getId());
                for (BgpPeerVO bgpPeerVO : bgpPeerVOS) {
                    BgpPeerResponse bgpPeerResponse = routedIpv4Manager.createBgpPeerResponse(bgpPeerVO);
                    response.addBgpPeer(bgpPeerResponse);
                }
            }
        }

        if (networkOfferingDao.isIpv6Supported(network.getNetworkOfferingId())) {
            response.setInternetProtocol(networkOfferingDao.getNetworkOfferingInternetProtocol(network.getNetworkOfferingId(), NetUtils.InternetProtocol.IPv4).toString());
            response.setIpv6Routing(Network.Routing.Static.toString());
            response.setIpv6Routes(new LinkedHashSet<>());
            if (Network.GuestType.Isolated.equals(networkOffering.getGuestType())) {
                List<String> ipv6Addresses = ipv6Service.getPublicIpv6AddressesForNetwork(network);
                for (String address : ipv6Addresses) {
                    Ipv6RouteResponse route = new Ipv6RouteResponse(network.getIp6Cidr(), address);
                    response.addIpv6Route(route);
                }
            }
        }

        if (CallContext.current().getCallingAccount().getType() == Account.Type.ADMIN &&
                network.getVpcId() == null && network.getGuestType() == Network.GuestType.Isolated) {
            response.setKeepMacAddressOnPublicNic(network.getKeepMacAddressOnPublicNic());
        }

        response.setObjectName("network");
        return response;
    }

    private void setVpcIdInResponse(Long vpcId, Consumer<String> setVpcId, Consumer<String> setVpcName) {
        if (vpcId == null) {
            return;
        }
        Vpc vpc = ApiDBUtils.findVpcById(vpcId);
        if (vpc == null) {
            return;
        }
        setVpcId.accept(vpc.getUuid());
        setVpcName.accept(vpc.getName());
    }

    private void setResponseAssociatedNetworkInformation(BaseResponseWithAssociatedNetwork response, Long networkId) {
        final NetworkDetailVO detail = networkDetailsDao.findDetail(networkId, Network.AssociatedNetworkId);
        if (detail != null) {
            Long associatedNetworkId = Long.valueOf(detail.getValue());
            NetworkVO associatedNetwork = ApiDBUtils.findNetworkById(associatedNetworkId);
            if (associatedNetwork != null) {
                response.setAssociatedNetworkId(associatedNetwork.getUuid());
                response.setAssociatedNetworkName(associatedNetwork.getName());
            }
        }
    }

    private ResourceTagResponse createResourceTagResponse(ResourceTag resourceTag, boolean keyValueOnly) {
        ResourceTagJoinVO rto = ApiDBUtils.newResourceTagView(resourceTag);
        if (rto == null) {
            return null;
        }
        return ApiDBUtils.newResourceTagResponse(rto, keyValueOnly);
    }

    private void createCapabilityResponse(List<CapabilityResponse> capabilityResponses,
            String name,
            String value,
            boolean canChoose,
            String objectName) {
        CapabilityResponse capabilityResponse = new CapabilityResponse();
        capabilityResponse.setName(name);
        capabilityResponse.setValue(value);
        capabilityResponse.setCanChoose(canChoose);
        capabilityResponse.setObjectName(objectName);

        capabilityResponses.add(capabilityResponse);
    }

    private void createCapabilityResponse(List<CapabilityResponse> capabilityResponses,
            String name,
            String value,
            boolean canChoose) {
        createCapabilityResponse(capabilityResponses, name, value, canChoose, null);
    }

    private ApiResponseOwnerService getApiResponseOwnerService() {
        return apiResponseOwnerService != null ? apiResponseOwnerService : STATIC_OWNER_SERVICE;
    }
}
